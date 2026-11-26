package kr.hs.gnflhs.whiteboard;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Region;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;

/** Temporary display layer. All paths are anchored to the board, including after a zoom. */
final class LassoFocus {
    interface Layers { void draw(Canvas canvas); }

    private final Path outline = new Path();
    private final Path outside = new Path();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] point = new float[2];
    private Region selectedPixels;
    private int pointer = -1;
    private int color;
    private volatile int generation;

    /** 완성된 올가미의 선택 상태. 원본 PDF를 그릴 때 바깥만 가리는 가벼운 불변 데이터. */
    static final class Mask {
        private final Path outside;
        final int color;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        Mask(Path existingOutside, int backgroundColor) {
            outside = new Path(existingOutside);
            color = backgroundColor;
            fill.setColor(color);
            fill.setStyle(Paint.Style.FILL);
        }
        void draw(Canvas canvas) {
            // 60fps 페이지 렌더링에서 Paint 객체를 매 프레임 만들지 않는다.
            canvas.drawPath(outside, fill);
        }
        void drawTransformed(Canvas canvas, Matrix boardToPreview) {
            Path mapped = new Path();
            outside.transform(boardToPreview, mapped);
            canvas.drawPath(mapped, fill);
        }
    }

    Mask snapshotMask() { return isActive() ? new Mask(outside, color) : null; }

    boolean isActive() { return selectedPixels != null; }

    void clipInterior(Canvas canvas) {
        canvas.clipPath(interior);
    }

    private final Path interior = new Path();

    void clear() {
        generation++;
        selectedPixels = null;
        outside.rewind();
        interior.rewind();
        cancelDrag();
    }

    void cancelDrag() {
        pointer = -1;
        outline.rewind();
    }

    private void append(float x, float y, Matrix inverse, boolean first) {
        point[0] = x;
        point[1] = y;
        inverse.mapPoints(point);
        if (first) outline.moveTo(point[0], point[1]);
        else outline.lineTo(point[0], point[1]);
    }

    boolean drag(MotionEvent event, Matrix inverse, Matrix viewport, int width, int height) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            cancelDrag();
            pointer = event.getPointerId(0);
            outline.setFillType(Path.FillType.EVEN_ODD);
            append(event.getX(), event.getY(), inverse, true);
            return false;
        }
        if (action == MotionEvent.ACTION_CANCEL || event.getPointerCount() != 1) {
            cancelDrag();
            return false;
        }
        if (pointer != event.getPointerId(0) ||
                (action != MotionEvent.ACTION_MOVE && action != MotionEvent.ACTION_UP)) return false;
        for (int i = 0; i < event.getHistorySize(); i++)
            append(event.getHistoricalX(i), event.getHistoricalY(i), inverse, false);
        append(event.getX(), event.getY(), inverse, false);
        if (action != MotionEvent.ACTION_UP) return false;

        pointer = -1;
        outline.close();
        Path screenPath = new Path();
        outline.transform(viewport, screenPath);
        Region region = new Region();
        if (!region.setPath(screenPath, new Region(0, 0, width, height))) return false;
        selectedPixels = region;
        interior.set(outline);
        outside.set(outline);
        outside.setFillType(Path.FillType.INVERSE_EVEN_ODD);
        return true;
    }

    /**
     * v2.11: 원본 방식은 선택 영역 크기의 전체 비트맵 및 수백만 개 int[]를 정렬했으며
     * 구형 Android 11 기기에서 ANR/OOM을 유발할 수 있었다.
     * 최대 512px로 축소 렌더링하고 고정 간격 표본만 집계한다.
     */
    void measure(View view, int boardColor, Matrix viewport, Layers layers, Handler worker,
                 Runnable onMeasured) {
        final int ticket = ++generation;
        final Region region = selectedPixels;
        if (region == null || region.isEmpty()) { if (onMeasured != null) view.post(onMeasured); return; }
        final android.graphics.Rect bounds = region.getBounds();
        if (bounds.isEmpty()) { if (onMeasured != null) view.post(onMeasured); return; }
        color = boardColor;
        final float ratio = Math.min(1f,512f / Math.max(bounds.width(),bounds.height()));
        final int w = Math.max(1,Math.round(bounds.width()*ratio));
        final int h = Math.max(1,Math.round(bounds.height()*ratio));
        Bitmap image = null;
        try {
            image = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(image);
            canvas.scale(ratio,ratio);
            canvas.translate(-bounds.left,-bounds.top);
            canvas.concat(viewport);
            canvas.drawColor(boardColor);
            layers.draw(canvas);
        } catch (OutOfMemoryError | RuntimeException e) {
            if (image != null) image.recycle();
            if (onMeasured != null) view.post(onMeasured);
            return;
        }
        final Bitmap snapshot = image;
        if (worker == null) {
            snapshot.recycle();
            if (onMeasured != null) view.post(onMeasured);
            return;
        }
        boolean posted = worker.post(() -> {
            try {
                if (ticket != generation) return;
                int result = sampledMostFrequent(snapshot,region,bounds,ratio,boardColor);
                view.post(() -> {
                    if (ticket == generation) {
                        color = result;
                        view.invalidate();
                        if (onMeasured != null) onMeasured.run();
                    }
                });
            } catch (RuntimeException e) {
                // 저장소/그림 처리 오류가 앱을 종료시키지 않도록 기존 칠판색을 사용한다.
                if (onMeasured != null) view.post(() -> {
                    if (ticket == generation) onMeasured.run();
                });
            } finally { snapshot.recycle(); }
        });
        if (!posted) {
            snapshot.recycle();
            if (onMeasured != null) view.post(onMeasured);
        }
    }

    private static int sampledMostFrequent(Bitmap bitmap, Region region,
            android.graphics.Rect bounds, float ratio, int fallback) {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        int step = Math.max(1, (int)Math.ceil(Math.max(width,height) / 256.0));
        DominantColor colorSamples = new DominantColor();
        for (int y = step/2; y < height; y += step) {
            for (int x = step/2; x < width; x += step) {
                int screenX = bounds.left + (int)((x + .5f) / ratio);
                int screenY = bounds.top + (int)((y + .5f) / ratio);
                if (!region.contains(screenX, screenY)) continue;
                colorSamples.add(bitmap.getPixel(x,y));
            }
        }
        return colorSamples.result(fallback);
    }

    /** 새 페이지 배경색. 이미 복사한 스냅샷에서 올가미 내부만 최대 65536점 분석한다. */
    int backgroundFromSnapshot(Bitmap snapshot, float ratio, int fallback) {
        Region region = selectedPixels;
        if (region == null || region.isEmpty() || ratio <= 0) return fallback;
        android.graphics.Rect bounds = region.getBounds();
        final int w = snapshot.getWidth(), h = snapshot.getHeight();
        int left = Math.max(0, (int)Math.floor(bounds.left * ratio));
        int right = Math.min(w, (int)Math.ceil(bounds.right * ratio));
        int top = Math.max(0, (int)Math.floor(bounds.top * ratio));
        int bottom = Math.min(h, (int)Math.ceil(bounds.bottom * ratio));
        int step = Math.max(1, (int)Math.ceil(Math.sqrt((double)(right-left)*(bottom-top)/65536.0)));
        DominantColor samples = new DominantColor();
        for (int y = top; y < bottom; y += step) {
            for (int x = left; x < right; x += step) {
                if (!region.contains((int)((x + .5f) / ratio), (int)((y + .5f) / ratio)))
                    continue;
                samples.add(snapshot.getPixel(x,y));
            }
        }
        return samples.result(fallback);
    }

    void draw(Canvas canvas, float zoom) {
        if (isActive()) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            canvas.drawPath(outside, paint);
        } else if (pointer != -1) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(Color.DKGRAY);
            paint.setStrokeWidth(4f / zoom);
            canvas.drawPath(outline, paint);
            paint.setColor(Color.WHITE);
            paint.setStrokeWidth(2f / zoom);
            canvas.drawPath(outline, paint);
        }
    }
}

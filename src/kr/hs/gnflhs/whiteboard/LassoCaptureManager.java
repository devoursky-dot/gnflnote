package kr.hs.gnflhs.whiteboard;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.util.SparseIntArray;
import android.util.TypedValue;
import android.view.*;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * GNFL NOTE V1.3 - 올가미 문제 캡처(스크랩) 툴 관리자
 * 
 * 1. 하단 좌측 툴바에 '✂ 올가미' 버튼을 자동 배치
 * 2. 올가미 모드에서 원하는 영역을 자유 드래그하면 PixelCopy로 현재 화면을 고화질 캡처
 * 3. 올가미 영역 내부에서 가장 많이 사용된 색(최빈색/Dominant Color)을 히스토그램으로 추출
 * 4. 2560x1440 새 캔버스 전체를 최빈색으로 채우고, 오려낸 영역을 좌측 상단에 깔끔히 합성
 * 5. 새 칠판 페이지를 자동 생성해 전체 화면에 배치하고 즉시 '쓰기' 모드로 전환하여 바로 판서 가능
 */
public class LassoCaptureManager {
    private static final String TAG = "LassoCaptureManager";
    private static LassoCaptureManager sInstance;

    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private HandlerThread mWorkerThread;
    private Handler mWorkerHandler;

    private Activity mCurrentActivity;
    private LassoOverlayView mOverlayView;
    private Button mLassoBtn;
    private ColorStateList mDefaultBtnTextColors;
    private int[] mLassoIconSize = new int[] { 72, 72 };

    private boolean mLassoMode = false;
    private boolean mIsDragging = false;
    private boolean mIsProcessing = false;
    private final ArrayList<PointF> mPoints = new ArrayList<PointF>();

    public static synchronized LassoCaptureManager getInstance() {
        if (sInstance == null) {
            sInstance = new LassoCaptureManager();
        }
        return sInstance;
    }

    private LassoCaptureManager() {
        mWorkerThread = new HandlerThread("LassoCaptureWorker");
        mWorkerThread.start();
        mWorkerHandler = new Handler(mWorkerThread.getLooper());
    }

    public boolean isLassoMode() {
        return mLassoMode;
    }

    public void ensureAttached(final Activity activity) {
        if (activity == null) return;
        if (mCurrentActivity != activity || mOverlayView == null) {
            mCurrentActivity = activity;
            try {
                ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
                if (mOverlayView != null && mOverlayView.getParent() != null) {
                    ((ViewGroup) mOverlayView.getParent()).removeView(mOverlayView);
                }
                mOverlayView = new LassoOverlayView(activity);
                decor.addView(mOverlayView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));
            } catch (Throwable t) {
                Log.e(TAG, "Failed to attach LassoOverlayView", t);
            }
            mLassoBtn = null;
        }
        DietManager.applyDiet(activity);
        ensureLassoButton(activity);
        ToolbarFitHelper.ensureFitButtons(activity);
        QuickColorBarManager.getInstance().ensureAndSync(activity);
        PdfPicker.updatePdfListButton(activity);
    }

    /**
     * 하단 왼쪽 툴바(toolbar_left_layout)에 기존 버튼(메뉴/나가기/목록)과 완벽히 동일한 스타일의
     * [올가미] 버튼을 추가합니다.
     */
    private void ensureLassoButton(final Activity activity) {
        if (mLassoBtn != null && mLassoBtn.getParent() != null) return;
        try {
            int resId = activity.getResources().getIdentifier("toolbar_left_layout", "id", "com.seewo.easinote");
            if (resId == 0) {
                resId = activity.getResources().getIdentifier("toolbar_left_layout", "id", activity.getPackageName());
            }
            if (resId == 0) return;
            View v = activity.findViewById(resId);
            if (!(v instanceof ViewGroup)) return;
            ViewGroup leftBar = (ViewGroup) v;

            // 이미 추가되어 있는지 태그로 확인
            View existing = leftBar.findViewWithTag("gnfl_lasso_btn");
            if (existing instanceof Button) {
                mLassoBtn = (Button) existing;
                return;
            }

            // 기존 순정 버튼(목록/메뉴 등)을 참조하여 아이콘 크기·글자 크기·패딩·색상 100% 통일
            Button refBtn = null;
            for (int i = 0; i < leftBar.getChildCount(); i++) {
                View child = leftBar.getChildAt(i);
                if (child instanceof Button && child.getVisibility() == View.VISIBLE) {
                    refBtn = (Button) child;
                    break;
                }
            }

            mLassoIconSize = ToolbarFitHelper.measureRefIconSize(activity, refBtn);
            Drawable lassoIcon = ToolbarFitHelper.createLassoIcon(mLassoIconSize[0], mLassoIconSize[1], ToolbarFitHelper.COLOR_CYAN);
            Button btn = ToolbarFitHelper.createStyledToolbarButton(activity, refBtn, "올가미", lassoIcon);
            btn.setTag("gnfl_lasso_btn");
            mDefaultBtnTextColors = btn.getTextColors();

            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    toggleLassoMode(activity);
                }
            });

            LinearLayout.LayoutParams lp = (refBtn != null)
                    ? ToolbarFitHelper.cloneLayoutParams(refBtn)
                    : new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 56));
            lp.gravity = Gravity.CENTER_VERTICAL;
            leftBar.addView(btn, lp);
            mLassoBtn = btn;
            updateButtonVisual(activity);
            Log.i(TAG, "Injected styled Lasso button into toolbar_left_layout");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to inject Lasso button", t);
        }
    }

    public void toggleLassoMode(Activity activity) {
        if (mIsProcessing) return;
        mLassoMode = !mLassoMode;
        mIsDragging = false;
        mPoints.clear();
        if (mOverlayView != null) {
            mOverlayView.setActiveMode(mLassoMode);
        }
        updateButtonVisual(activity);
        // LassoOverlayView 자체가 상단 안내 배너를 표시하므로 중복 토스트 생략
    }

    private void updateButtonVisual(Activity activity) {
        if (mLassoBtn == null || activity == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(activity, 6));
        int iconColor = mLassoMode ? Color.WHITE : ToolbarFitHelper.COLOR_CYAN;
        Drawable icon = ToolbarFitHelper.createLassoIcon(mLassoIconSize[0], mLassoIconSize[1], iconColor);
        icon.setBounds(0, 0, icon.getIntrinsicWidth(), icon.getIntrinsicHeight());
        mLassoBtn.setCompoundDrawables(null, icon, null, null);

        if (mLassoMode) {
            bg.setColor(ToolbarFitHelper.COLOR_TEAL);
            mLassoBtn.setTextColor(Color.WHITE);
        } else {
            bg.setColor(Color.TRANSPARENT);
            if (mDefaultBtnTextColors != null) {
                mLassoBtn.setTextColor(mDefaultBtnTextColors);
            } else {
                mLassoBtn.setTextColor(Color.parseColor("#475569"));
            }
        }
        mLassoBtn.setBackground(bg);
    }

    /**
     * 올가미 모드일 때 터치 이벤트를 가로채어 선택 영역을 수집합니다.
     * @return true이면 순정 앱으로 터치를 전달하지 않고 소비
     */
    public boolean handleTouch(final Activity activity, MotionEvent ev) {
        int action = ev.getActionMasked();
        // 성능 최적화: 120Hz ACTION_MOVE마다 리플렉션/뷰 스캔을 돌리지 않고 DOWN/UP에서만 동기화
        if (action == MotionEvent.ACTION_DOWN) {
            ensureAttached(activity);
        } else if (action == MotionEvent.ACTION_UP) {
            ensureAttached(activity);
            // 페이지 전환/테마 변경 버튼 클릭 직후 배경색이 바뀌는 경우를 위해 250ms 뒤 색상바 재동기화
            if (!mLassoMode && !mIsProcessing) {
                mUiHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!activity.isFinishing() && !activity.isDestroyed()) {
                            QuickColorBarManager.getInstance().syncWithCurrentPage(activity);
                        }
                    }
                }, 250);
            }
        }

        // 페이지 생성/이미지 삽입 처리 중에는 우발적 터치가 DrawingThread와 충돌하지 않도록 소비
        if (mIsProcessing) return true;
        if (!mLassoMode) return false;

        float x = ev.getX();
        float y = ev.getY();

        // 하단 툴바 영역 터치 시에는 올가미 버튼 재클릭 등 순정 UI 동작 허용
        if (!mIsDragging && mOverlayView != null && y > mOverlayView.getHeight() - 210) {
            return false;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mIsDragging = true;
                mPoints.clear();
                mPoints.add(new PointF(x, y));
                if (mOverlayView != null) mOverlayView.updatePath(mPoints);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!mIsDragging) return false;
                if (mPoints.isEmpty() || Math.hypot(x - mPoints.get(mPoints.size() - 1).x, y - mPoints.get(mPoints.size() - 1).y) > 4f) {
                    mPoints.add(new PointF(x, y));
                    if (mOverlayView != null) mOverlayView.updatePath(mPoints);
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (!mIsDragging) return false;
                mIsDragging = false;

                final ArrayList<PointF> capturedPts = new ArrayList<PointF>(mPoints);
                mPoints.clear();

                // 캡처 전에 올가미 점선/배너 오버레이를 즉시 숨김
                mLassoMode = false;
                if (mOverlayView != null) {
                    mOverlayView.setActiveMode(false);
                }
                updateButtonVisual(activity);

                if (capturedPts.size() >= 6) {
                    mIsProcessing = true;
                    // 오버레이가 완전히 사라진 다음 프레임(60ms 뒤)에 화면 캡처 실행
                    mUiHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            captureAndCreateNewPage(activity, capturedPts);
                        }
                    }, 60);
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                mIsDragging = false;
                mPoints.clear();
                if (mOverlayView != null) mOverlayView.clearPath();
                return true;
        }
        return false;
    }

    /**
     * SurfaceView(WhiteboardView)에 직접 PixelCopy를 요청하여 PDF + 판서 + 배경 전체를
     * 100% 선명하게 캡처한 뒤, 올가미 영역 추출 -> 최빈색 분석 -> 새 페이지 합성 -> 삽입을 수행합니다.
     */
    private void captureAndCreateNewPage(final Activity activity, final List<PointF> points) {
        try {
            Field fWb = activity.getClass().getDeclaredField("c");
            fWb.setAccessible(true);
            View wbView = (View) fWb.get(activity);
            if (wbView == null || wbView.getWidth() <= 0 || wbView.getHeight() <= 0) {
                mIsProcessing = false;
                return;
            }

            final int w = wbView.getWidth();
            final int h = wbView.getHeight();
            int[] loc = new int[2];
            wbView.getLocationInWindow(loc);
            final int offsetX = loc[0];
            final int offsetY = loc[1];

            // 올가미 좌표를 SurfaceView(WhiteboardView) 기준 좌표계로 변환
            final List<PointF> wbPoints = new ArrayList<PointF>(points.size());
            float minX = w, minY = h, maxX = 0, maxY = 0;
            for (int i = 0; i < points.size(); i++) {
                PointF pt = points.get(i);
                float px = pt.x - offsetX;
                float py = pt.y - offsetY;
                wbPoints.add(new PointF(px, py));
                if (px < minX) minX = px;
                if (py < minY) minY = py;
                if (px > maxX) maxX = px;
                if (py > maxY) maxY = py;
            }
            Rect lassoBounds = new Rect(
                    Math.max(0, (int) Math.floor(minX)),
                    Math.max(0, (int) Math.floor(minY)),
                    Math.min(w, (int) Math.ceil(maxX)),
                    Math.min(h, (int) Math.ceil(maxY))
            );

            // 1순위: 현재 올가미 영역이 PDF 페이지 위라면 원본 PDF 벡터(PdfRenderer)에서 해당 영역만 진짜 4K로 직접 렌더링!
            PdfHit pdfHit = findOverlappingPdfShape(activity, lassoBounds);
            if (pdfHit != null) {
                Log.i(TAG, "Lasso hit PDF page #" + (pdfHit.source.pageIndex + 1) + " -> rendering native 4K vector crop!");
                renderVectorPdfLassoAsync(activity, pdfHit, wbPoints);
                return;
            }

            if (wbView instanceof SurfaceView) {
                final Bitmap surfaceBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                PixelCopy.request((SurfaceView) wbView, surfaceBmp, new PixelCopy.OnPixelCopyFinishedListener() {
                    @Override
                    public void onPixelCopyFinished(int copyResult) {
                        if (copyResult == PixelCopy.SUCCESS) {
                            Log.i(TAG, "SurfaceView PixelCopy SUCCESS (" + w + "x" + h + ")");
                            processCapturedBitmapAsync(activity, surfaceBmp, wbPoints);
                        } else {
                            Log.w(TAG, "SurfaceView PixelCopy failed (" + copyResult + "), trying getDrawingBitmap");
                            surfaceBmp.recycle();
                            fallbackCaptureFromDrawingBitmap(activity, wbPoints);
                        }
                    }
                }, mUiHandler);
            } else {
                fallbackCaptureFromDrawingBitmap(activity, wbPoints);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error requesting SurfaceView PixelCopy", t);
            mIsProcessing = false;
            ToolbarFitHelper.showTopToast(activity, "올가미 캡처 오류: " + t.getMessage());
        }
    }

    private static final class PdfHit {
        final PdfPicker.PdfSourceRef source;
        final Rect imgBounds;

        PdfHit(PdfPicker.PdfSourceRef source, Rect imgBounds) {
            this.source = source;
            this.imgBounds = imgBounds;
        }
    }

    /**
     * 현재 페이지의 도형 중 PdfPicker로 삽입된 PDF ImageShape와 올가미 영역이 겹치는지 검사합니다.
     */
    private PdfHit findOverlappingPdfShape(Activity activity, Rect lassoBounds) {
        if (lassoBounds == null || lassoBounds.width() < 20 || lassoBounds.height() < 20) return null;
        try {
            Field fP4a = activity.getClass().getDeclaredField("t");
            fP4a.setAccessible(true);
            Object p4a = fP4a.get(activity);
            if (p4a == null) return null;

            Object stateHolder = p4a.getClass().getMethod("a").invoke(p4a);
            if (stateHolder == null) return null;

            Object page = stateHolder.getClass().getMethod("m0").invoke(stateHolder);
            if (page == null) return null;

            int count = (Integer) page.getClass().getMethod("size").invoke(page);
            Method mGetShape = page.getClass().getMethod("get", int.class);
            long lassoArea = (long) lassoBounds.width() * (long) lassoBounds.height();

            PdfHit bestHit = null;
            long bestOverlap = 0;

            for (int i = count - 1; i >= 0; i--) {
                Object shape = mGetShape.invoke(page, i);
                if (shape == null) continue;
                String clsName = shape.getClass().getName();
                if (!clsName.contains("ImageShape")) continue;

                String resPath = readStringField(shape, "mResourcePath");
                String savePath = readStringField(shape, "mSavePath");
                PdfPicker.PdfSourceRef ref = PdfPicker.findPdfSourceForPath(resPath);
                if (ref == null) ref = PdfPicker.findPdfSourceForPath(savePath);
                if (ref == null) continue;

                Rect b = (Rect) shape.getClass().getMethod("getBounds").invoke(shape);
                if (b == null || b.width() <= 0 || b.height() <= 0) continue;

                Rect inter = new Rect();
                if (inter.setIntersect(lassoBounds, b)) {
                    long interArea = (long) inter.width() * (long) inter.height();
                    if (interArea >= lassoArea * 0.45 && interArea > bestOverlap) {
                        bestOverlap = interArea;
                        bestHit = new PdfHit(ref, new Rect(b));
                    }
                }
            }
            return bestHit;
        } catch (Throwable t) {
            Log.w(TAG, "findOverlappingPdfShape skipped: " + t.getMessage());
            return null;
        }
    }

    private static String readStringField(Object obj, String fieldName) {
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                Object val = f.get(obj);
                if (val instanceof String) return (String) val;
                return null;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /**
     * 원본 PDF 파일의 벡터 데이터(PdfRenderer)에서 올가미로 선택한 영역만 직접 4K 해상도로 렌더링합니다.
     * 화면 픽셀을 늘리는 것이 아니라 벡터 원본에서 직접 그리므로 500%까지 확대해도 인쇄물처럼 선명합니다.
     */
    private void renderVectorPdfLassoAsync(final Activity activity, final PdfHit hit, final List<PointF> wbPoints) {
        mWorkerHandler.post(new Runnable() {
            @Override
            public void run() {
                String savedPath = null;
                int dominantColor = Color.WHITE;
                ParcelFileDescriptor pfd = null;
                PdfRenderer renderer = null;
                PdfRenderer.Page pdfPage = null;
                try {
                    Rect ib = hit.imgBounds;
                    float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
                    float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
                    for (int i = 0; i < wbPoints.size(); i++) {
                        PointF p = wbPoints.get(i);
                        if (p.x < minX) minX = p.x;
                        if (p.y < minY) minY = p.y;
                        if (p.x > maxX) maxX = p.x;
                        if (p.y > maxY) maxY = p.y;
                    }

                    // 올가미 바운딩 박스를 PDF ImageShape 영역과 교차
                    float cl = Math.max(ib.left, minX);
                    float ct = Math.max(ib.top, minY);
                    float cr = Math.min(ib.right, maxX);
                    float cb = Math.min(ib.bottom, maxY);
                    float cropWbW = cr - cl;
                    float cropWbH = cb - ct;
                    if (cropWbW < 20f || cropWbH < 20f) {
                        throw new IllegalStateException("PDF lasso crop too small");
                    }

                    float u0 = (cl - ib.left) / (float) ib.width();
                    float v0 = (ct - ib.top) / (float) ib.height();
                    float u1 = (cr - ib.left) / (float) ib.width();
                    float v1 = (cb - ib.top) / (float) ib.height();

                    if (hit.source.uri != null) {
                        pfd = activity.getContentResolver().openFileDescriptor(hit.source.uri, "r");
                    } else if (hit.source.filePath != null) {
                        pfd = ParcelFileDescriptor.open(new File(hit.source.filePath), ParcelFileDescriptor.MODE_READ_ONLY);
                    }
                    if (pfd == null) throw new IllegalStateException("Cannot reopen PDF source");

                    renderer = new PdfRenderer(pfd);
                    pdfPage = renderer.openPage(hit.source.pageIndex);
                    int pw = pdfPage.getWidth();
                    int ph = pdfPage.getHeight();

                    float pdfLeft = u0 * pw;
                    float pdfTop = v0 * ph;
                    float pdfCropW = Math.max(1f, (u1 - u0) * pw);
                    float pdfCropH = Math.max(1f, (v1 - v0) * ph);

                    // 진짜 4K 벡터 렌더링 해상도 계산 (긴 변 최대 3400px, 32비트 안전 상한 11.5MP)
                    float maxLongEdge = 3400f;
                    float renderScale = maxLongEdge / Math.max(pdfCropW, pdfCropH);
                    int outW = Math.max(1, Math.round(pdfCropW * renderScale));
                    int outH = Math.max(1, Math.round(pdfCropH * renderScale));
                    long maxPixels = 11_500_000L;
                    if ((long) outW * (long) outH > maxPixels) {
                        float shrink = (float) Math.sqrt((double) maxPixels / ((double) outW * (double) outH));
                        outW = Math.max(1, Math.round(outW * shrink));
                        outH = Math.max(1, Math.round(outH * shrink));
                    }

                    float scaleX = outW / pdfCropW;
                    float scaleY = outH / pdfCropH;

                    // 1. 원본 PDF 벡터 페이지에서 선택 영역만 4K 비트맵으로 직접 렌더링
                    Bitmap rawVecBmp = null;
                    FileOutputStream fos = null;
                    try {
                        rawVecBmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
                        Canvas rawCanvas = new Canvas(rawVecBmp);
                        rawCanvas.drawColor(Color.WHITE);

                        Matrix pdfMat = new Matrix();
                        pdfMat.postTranslate(-pdfLeft, -pdfTop);
                        pdfMat.postScale(scaleX, scaleY);
                        pdfPage.render(rawVecBmp, null, pdfMat, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

                        // 2. 메모리 50% 절감: 두 번째 46MB 비트맵을 할당하지 않고 INVERSE_EVEN_ODD + CLEAR로 인플레이스 마스킹!
                        Path clipPath = new Path();
                        for (int i = 0; i < wbPoints.size(); i++) {
                            PointF p = wbPoints.get(i);
                            float vx = (p.x - cl) * outW / cropWbW;
                            float vy = (p.y - ct) * outH / cropWbH;
                            if (i == 0) clipPath.moveTo(vx, vy);
                            else clipPath.lineTo(vx, vy);
                        }
                        clipPath.close();
                        clipPath.setFillType(Path.FillType.INVERSE_EVEN_ODD);
                        Paint clearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
                        rawCanvas.drawPath(clipPath, clearPaint);

                        // 3. 최빈색 추출 및 무손실 투명 PNG 저장
                        dominantColor = extractDominantColor(rawVecBmp);
                        File outFile = new File(activity.getCacheDir(), "gnfl_lasso_vec4k_" + System.currentTimeMillis() + ".png");
                        fos = new FileOutputStream(outFile);
                        rawVecBmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                        fos.flush();

                        savedPath = outFile.getAbsolutePath();
                        Log.i(TAG, String.format("Native 4K Vector PDF Lasso SUCCESS: page=%d, out=%dx%d, bg=#%06X",
                                hit.source.pageIndex + 1, outW, outH, (dominantColor & 0xFFFFFF)));
                    } finally {
                        try { if (fos != null) fos.close(); } catch (Throwable ignored) {}
                        try { if (rawVecBmp != null && !rawVecBmp.isRecycled()) rawVecBmp.recycle(); } catch (Throwable ignored) {}
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Vector PDF lasso failed, falling back to PixelCopy: " + t.getMessage());
                } finally {
                    try { if (pdfPage != null) pdfPage.close(); } catch (Throwable ignored) {}
                    try { if (renderer != null) renderer.close(); } catch (Throwable ignored) {}
                    try { if (pfd != null) pfd.close(); } catch (Throwable ignored) {}
                }

                final String finalPath = savedPath;
                final int finalBgColor = dominantColor;
                mUiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (finalPath != null) {
                            createNewPageAndInsert(activity, finalPath, finalBgColor);
                        } else {
                            // PDF 직접 렌더링 실패 시 기존 SurfaceView PixelCopy 방식으로 안전 폴백
                            fallbackCaptureFromDrawingBitmap(activity, wbPoints);
                        }
                    }
                });
            }
        });
    }

    private void fallbackCaptureFromDrawingBitmap(Activity activity, List<PointF> wbPoints) {
        try {
            Field fWb = activity.getClass().getDeclaredField("c");
            fWb.setAccessible(true);
            Object wbView = fWb.get(activity);
            Method mGetBm = wbView.getClass().getMethod("getDrawingBitmap");
            Bitmap bm = (Bitmap) mGetBm.invoke(wbView);
            if (bm != null && !bm.isRecycled()) {
                Bitmap copy = bm.copy(Bitmap.Config.ARGB_8888, false);
                processCapturedBitmapAsync(activity, copy, wbPoints);
                return;
            }
        } catch (Throwable t) {
            Log.e(TAG, "fallbackCaptureFromDrawingBitmap failed", t);
        }
        mIsProcessing = false;
        ToolbarFitHelper.showTopToast(activity, "화면 캡처에 실패했습니다.");
    }

    private void processCapturedBitmapAsync(final Activity activity, final Bitmap screenBmp, final List<PointF> points) {
        mWorkerHandler.post(new Runnable() {
            @Override
            public void run() {
                String savedPath = null;
                int dominantColor = Color.WHITE;
                Bitmap croppedBmp = null;
                Bitmap saveBmp = null;
                FileOutputStream fos = null;
                try {
                    int sw = screenBmp.getWidth();
                    int sh = screenBmp.getHeight();

                    // 1. 올가미 Path 및 바운딩 박스 계산
                    Path lassoPath = new Path();
                    float minX = sw, minY = sh, maxX = 0, maxY = 0;
                    for (int i = 0; i < points.size(); i++) {
                        float px = Math.max(0f, Math.min(sw - 1f, points.get(i).x));
                        float py = Math.max(0f, Math.min(sh - 1f, points.get(i).y));
                        if (i == 0) lassoPath.moveTo(px, py);
                        else lassoPath.lineTo(px, py);
                        if (px < minX) minX = px;
                        if (py < minY) minY = py;
                        if (px > maxX) maxX = px;
                        if (py > maxY) maxY = py;
                    }
                    lassoPath.close();

                    int left = Math.max(0, (int) Math.floor(minX));
                    int top = Math.max(0, (int) Math.floor(minY));
                    int right = Math.min(sw, (int) Math.ceil(maxX));
                    int bottom = Math.min(sh, (int) Math.ceil(maxY));
                    int cropW = right - left;
                    int cropH = bottom - top;

                    if (cropW < 30 || cropH < 30) {
                        mUiHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                mIsProcessing = false;
                                ToolbarFitHelper.showTopToast(activity, "선택 영역이 너무 작습니다.");
                            }
                        });
                        return;
                    }

                    // 2. 올가미 내부 픽셀만 투명 캔버스에 클리핑 추출
                    croppedBmp = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888);
                    Canvas cropCanvas = new Canvas(croppedBmp);
                    Path shiftedPath = new Path(lassoPath);
                    shiftedPath.offset(-left, -top);
                    cropCanvas.save();
                    cropCanvas.clipPath(shiftedPath);
                    cropCanvas.drawBitmap(screenBmp, new Rect(left, top, right, bottom), new Rect(0, 0, cropW, cropH), null);
                    cropCanvas.restore();
                    screenBmp.recycle();

                    // 3. 선택 영역 내 가장 많이 사용된 색깔(Dominant Color) 히스토그램 분석
                    dominantColor = extractDominantColor(croppedBmp);

                    // 4. 4K 고화질 업스케일링 (긴 변 최대 3840px, 최대 3.0배, 32비트 안전 상한 11MP)
                    int outW = cropW;
                    int outH = cropH;
                    float maxLongEdge = 3840f;
                    int longEdge = Math.max(cropW, cropH);
                    if (longEdge < maxLongEdge) {
                        float upScale = Math.min(3.0f, maxLongEdge / (float) longEdge);
                        outW = Math.max(1, Math.round(cropW * upScale));
                        outH = Math.max(1, Math.round(cropH * upScale));
                        long maxPixels = 11_000_000L;
                        if ((long) outW * (long) outH > maxPixels) {
                            float shrink = (float) Math.sqrt((double) maxPixels / ((double) outW * (double) outH));
                            outW = Math.max(cropW, Math.round(outW * shrink));
                            outH = Math.max(cropH, Math.round(outH * shrink));
                        }
                    }

                    saveBmp = croppedBmp;
                    if (outW > cropW || outH > cropH) {
                        Bitmap upscaled = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
                        Canvas uc = new Canvas(upscaled);
                        Paint upPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
                        uc.drawBitmap(croppedBmp, null, new RectF(0, 0, outW, outH), upPaint);
                        croppedBmp.recycle();
                        croppedBmp = null;
                        saveBmp = upscaled;
                    }
                    Log.i(TAG, String.format("Extracted Dominant Color: #%06X (crop=%dx%d -> 4K upscale=%dx%d)",
                            (dominantColor & 0xFFFFFF), cropW, cropH, outW, outH));

                    // 5. 4K 업스케일링된 올가미 영역을 투명 배경 PNG로 무손실 저장
                    File outFile = new File(activity.getCacheDir(), "gnfl_lasso_" + System.currentTimeMillis() + ".png");
                    fos = new FileOutputStream(outFile);
                    saveBmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    fos.flush();

                    savedPath = outFile.getAbsolutePath();
                } catch (Throwable t) {
                    Log.e(TAG, "Error processing lasso bitmap", t);
                } finally {
                    try { if (fos != null) fos.close(); } catch (Throwable ignored) {}
                    try { if (saveBmp != null && !saveBmp.isRecycled()) saveBmp.recycle(); } catch (Throwable ignored) {}
                    try { if (croppedBmp != null && !croppedBmp.isRecycled()) croppedBmp.recycle(); } catch (Throwable ignored) {}
                    try { if (!screenBmp.isRecycled()) screenBmp.recycle(); } catch (Throwable ignored) {}
                }

                final String finalPath = savedPath;
                final int finalBgColor = dominantColor;
                mUiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (finalPath == null) {
                            mIsProcessing = false;
                            ToolbarFitHelper.showTopToast(activity, "올가미 이미지 생성에 실패했습니다.");
                        } else {
                            createNewPageAndInsert(activity, finalPath, finalBgColor);
                        }
                    }
                });
            }
        });
    }

    /**
     * 올가미 내부 픽셀(alpha > 200)을 5-bit RGB 버킷으로 양자화해 최빈색을 고속 추출합니다.
     * 32비트 ARM 환경 메모리 절약을 위해 int[] 전체 복사 없이 getPixel 직접 샘플링을 사용합니다.
     */
    public static int extractDominantColor(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();

        SparseIntArray counts = new SparseIntArray();
        int step = Math.max(1, (int) Math.sqrt((w * h) / 25000.0)); // 최대 ~2.5만 픽셀 고속 샘플링 (0B 추가 할당)

        int bestKey = -1;
        int bestCount = 0;

        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int c = bmp.getPixel(x, y);
                if ((c >>> 24) < 200) continue; // 올가미 외부 투명 픽셀 제외

                int r5 = (Color.red(c) >> 3) & 0x1F;
                int g5 = (Color.green(c) >> 3) & 0x1F;
                int b5 = (Color.blue(c) >> 3) & 0x1F;
                int key = (r5 << 10) | (g5 << 5) | b5;

                int cnt = counts.get(key, 0) + 1;
                counts.put(key, cnt);
                if (cnt > bestCount) {
                    bestCount = cnt;
                    bestKey = key;
                }
            }
        }

        if (bestKey < 0) return Color.WHITE;

        // 최빈 버킷에 속하는 픽셀들의 정확한 평균 RGB 계산 (오차 없는 자연스러운 배경색)
        long sumR = 0, sumG = 0, sumB = 0;
        int matchCount = 0;
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int c = bmp.getPixel(x, y);
                if ((c >>> 24) < 200) continue;
                int r5 = (Color.red(c) >> 3) & 0x1F;
                int g5 = (Color.green(c) >> 3) & 0x1F;
                int b5 = (Color.blue(c) >> 3) & 0x1F;
                int key = (r5 << 10) | (g5 << 5) | b5;
                if (key == bestKey) {
                    sumR += Color.red(c);
                    sumG += Color.green(c);
                    sumB += Color.blue(c);
                    matchCount++;
                }
            }
        }

        if (matchCount <= 0) return Color.WHITE;
        return Color.rgb((int) (sumR / matchCount), (int) (sumG / matchCount), (int) (sumB / matchCount));
    }

    /**
     * 새 칠판 페이지를 추가하고, 해당 페이지의 무한 캔버스 전체 배경색을 최빈색(bgColor)으로 설정한 뒤
     * 오려낸 올가미 영역 이미지를 배치하고 DrawingThread 렌더링 완료 후 '쓰기' 모드로 전환합니다.
     */
    private void createNewPageAndInsert(final Activity activity, final String imagePath, final int bgColor) {
        try {
            // 1. 새 페이지 추가 버튼 클릭
            View addBtn = findToolbarBtn(activity, "explore_edu_toolbar_add_page");
            if (addBtn != null) {
                addBtn.performClick();
            }

            // 2. 새 페이지 초기화 완료 후 페이지 전체(화면 밖 무한 영역 포함) 배경색을 최빈색으로 설정
            mUiHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    applyInfinitePageBackgroundColor(activity, bgColor);

                    // 3. 배경색 반영 후 presenter.V0로 오려낸 올가미 영역 삽입
                    mUiHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Field fPres = activity.getClass().getDeclaredField("j");
                                fPres.setAccessible(true);
                                Object presenter = fPres.get(activity);
                                if (presenter != null) {
                                    Method mV0 = presenter.getClass().getDeclaredMethod("V0", String.class);
                                    mV0.setAccessible(true);
                                    mV0.invoke(presenter, imagePath);
                                }

                                // 4. V0의 비동기 디코딩 및 DrawingThread(z1.a0 -> v1.I -> WhiteboardView.m)가
                                // 완전히 종료된 뒤(1000ms) 쓰기 모드로 전환하여 v1.g1()의 Bitmap.recycle() 경합(SIGABRT) 원천 방지
                                mUiHandler.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        fitFullScreenAndSwitchToWrite(activity);
                                        QuickColorBarManager.getInstance().syncWithCurrentPage(activity);
                                        mIsProcessing = false;
                                        ToolbarFitHelper.showTopToast(activity, "✂️ 올가미 영역이 새 페이지에 배치되었습니다!");
                                    }
                                }, 1000);
                            } catch (Throwable t) {
                                Log.e(TAG, "Failed inserting lasso page", t);
                                mIsProcessing = false;
                            }
                        }
                    }, 180);
                }
            }, 450);
        } catch (Throwable t) {
            Log.e(TAG, "Error in createNewPageAndInsert", t);
            mIsProcessing = false;
        }
    }

    /**
     * 현재 페이지의 네이티브 배경(SolidColorBrush)을 지정한 RGB 색으로 변경합니다.
     * 화면에 보이는 영역뿐 아니라 확대/축소/이동 시 나타나는 화면 밖 무한 캔버스 전체에 적용됩니다.
     */
    public static void applyInfinitePageBackgroundColor(Activity activity, int bgColor) {
        try {
            Field fP4a = activity.getClass().getDeclaredField("t");
            fP4a.setAccessible(true);
            Object p4a = fP4a.get(activity);
            if (p4a == null) {
                Log.w(TAG, "PlainWhiteboardActivity.t (p4.a) is null");
                return;
            }
            Method mGetPageMgr = p4a.getClass().getMethod("b");
            Object pageMgr = mGetPageMgr.invoke(p4a);
            if (pageMgr == null) {
                Log.w(TAG, "p4.a.b() (PageManager) is null");
                return;
            }

            Class<?> bgIface = Class.forName("com.seewo.easinote.e4.a.l.h0.b.a");
            Class<?> solidColorBgCls = Class.forName("com.seewo.easinote.p4.e.b.c");
            Constructor<?> ctor = solidColorBgCls.getDeclaredConstructor(int.class);
            ctor.setAccessible(true);
            Object solidBg = ctor.newInstance(bgColor);

            Method mSetBg = pageMgr.getClass().getMethod("f", bgIface);
            mSetBg.invoke(pageMgr, solidBg);
            Log.i(TAG, String.format("Applied infinite page background color: #%06X", (bgColor & 0xFFFFFF)));
        } catch (Throwable t) {
            Log.e(TAG, "Failed to apply infinite page background color", t);
        }
    }

    private void fitFullScreenAndSwitchToWrite(Activity activity) {
        try {
            // 순정 presenter.V0가 2560x1440(16:9) 이미지를 화면에 100% 정상 렌더링하므로
            // ImageShape의 Matrix/resetSize를 건드리지 않고, 바로 판서할 수 있게 '쓰기' 모드만 자동 전환!
            View writeBtn = findToolbarBtn(activity, "explore_edu_toolbar_write");
            if (writeBtn != null && !writeBtn.isSelected()) {
                writeBtn.performClick();
            }
        } catch (Throwable t) {
            Log.w(TAG, "fitFullScreenAndSwitchToWrite warning", t);
        }
    }

    private static View findToolbarBtn(Activity activity, String idName) {
        if (activity == null) return null;
        try {
            int resId = activity.getResources().getIdentifier(idName, "id", "com.seewo.easinote");
            if (resId == 0) {
                resId = activity.getResources().getIdentifier(idName, "id", activity.getPackageName());
            }
            if (resId != 0) {
                return activity.findViewById(resId);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static int dp(Activity act, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, act.getResources().getDisplayMetrics());
    }
}

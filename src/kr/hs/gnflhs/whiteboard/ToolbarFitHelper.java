package kr.hs.gnflhs.whiteboard;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * GNFL NOTE V1.3 - 커스텀 툴바 버튼 스타일 통일 및 폭맞춤/쪽맞춤(Fit Width / Fit Page) 유틸리티
 *
 * 1. 순정 툴바 Button(drawableTop + text)의 폰트 크기·색상·패딩·아이콘 크기를 100% 복제하여
 *    '올가미', '폭맞춤', '쪽맞춤' 버튼이 기존 순정 아이콘들과 완전히 통일된 외형을 갖도록 생성
 * 2. '이동' 버튼(explore_edu_toolbar_move) 바로 옆에 '폭맞춤', '쪽맞춤' 버튼을 자동 배치
 * 3. 네이티브 페이지 변환(page.D0 / endTransform / K / stateHolder.m)을 사용해
 *    PDF·이미지·판서 전체를 부드럽고 선명하게 폭맞춤/쪽맞춤 변환
 */
public class ToolbarFitHelper {
    private static final String TAG = "ToolbarFitHelper";

    public static final int COLOR_BLUE = Color.parseColor("#1890FF");
    public static final int COLOR_TEAL = Color.parseColor("#00A884");
    public static final int COLOR_CYAN = Color.parseColor("#0EA5E9");

    /**
     * '이동' 버튼(explore_edu_toolbar_move) 바로 오른쪽에 [폭맞춤], [쪽맞춤] 버튼을 배치합니다.
     */
    public static void ensureFitButtons(final Activity activity) {
        if (activity == null) return;
        try {
            View moveView = findViewByIdName(activity, "explore_edu_toolbar_move");
            if (!(moveView instanceof Button)) return;
            ViewGroup parent = (ViewGroup) moveView.getParent();
            if (parent == null) return;

            Button moveBtn = (Button) moveView;
            int[] iconSize = measureRefIconSize(activity, moveBtn);

            if (parent.findViewWithTag("gnfl_fit_width_btn") == null) {
                int moveIdx = parent.indexOfChild(moveBtn);
                Drawable iconW = createFitWidthIcon(iconSize[0], iconSize[1], COLOR_BLUE);
                Button fitWBtn = createStyledToolbarButton(activity, moveBtn, "폭맞춤", iconW);
                fitWBtn.setTag("gnfl_fit_width_btn");
                fitWBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        applyPageFit(activity, true);
                    }
                });
                parent.addView(fitWBtn, moveIdx + 1, cloneLayoutParams(moveBtn));
                Log.i(TAG, "Injected Fit-Width button next to Move tool");
            }

            if (parent.findViewWithTag("gnfl_fit_page_btn") == null) {
                View fitWView = parent.findViewWithTag("gnfl_fit_width_btn");
                int insertIdx = (fitWView != null) ? (parent.indexOfChild(fitWView) + 1) : (parent.indexOfChild(moveBtn) + 1);
                Drawable iconP = createFitPageIcon(iconSize[0], iconSize[1], COLOR_TEAL);
                Button fitPBtn = createStyledToolbarButton(activity, moveBtn, "쪽맞춤", iconP);
                fitPBtn.setTag("gnfl_fit_page_btn");
                fitPBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        applyPageFit(activity, false);
                    }
                });
                parent.addView(fitPBtn, insertIdx, cloneLayoutParams(moveBtn));
                Log.i(TAG, "Injected Fit-Page button next to Fit-Width tool");
            }

            hookCleanButtonToInkOnly(activity);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to inject Fit buttons", t);
        }
    }

    /**
     * 하단 툴바의 '삭제'(explore_edu_toolbar_clean) 버튼을 후킹하여
     * PDF·올가미·이미지 등은 보존하고 '펜글씨(AbstractInk)'만 깔끔하게 삭제하도록 변경합니다.
     */
    public static void hookCleanButtonToInkOnly(final Activity activity) {
        if (activity == null) return;
        try {
            View cleanBtn = findViewByIdName(activity, "explore_edu_toolbar_clean");
            if (cleanBtn == null) return;
            if ("gnfl_ink_clean_hooked".equals(cleanBtn.getTag())) return;
            cleanBtn.setTag("gnfl_ink_clean_hooked");
            cleanBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clearOnlyPenStrokes(activity);
                }
            });
            Log.i(TAG, "Hooked explore_edu_toolbar_clean to clear pen ink only");
        } catch (Throwable t) {
            Log.w(TAG, "hookCleanButtonToInkOnly warning", t);
        }
    }

    /**
     * 현재 페이지에서 PDF·이미지·도형 등은 유지하고 순수 펜글씨(AbstractInk)만 삭제합니다.
     * 실행 취소(Undo) 스택에도 정상 등록됩니다.
     */
    public static void clearOnlyPenStrokes(Activity activity) {
        if (activity == null) return;
        try {
            Field fP4a = activity.getClass().getDeclaredField("t");
            fP4a.setAccessible(true);
            Object p4a = fP4a.get(activity);
            if (p4a == null) return;

            Object stateHolder = p4a.getClass().getMethod("a").invoke(p4a);
            if (stateHolder == null) return;

            Object page = stateHolder.getClass().getMethod("m0").invoke(stateHolder);
            if (page == null) return;

            int count = (Integer) page.getClass().getMethod("size").invoke(page);
            if (count <= 0) {
                showTopToast(activity, "삭제할 펜글씨가 없습니다.");
                return;
            }

            Class<?> abstractInkCls = null;
            try {
                abstractInkCls = Class.forName("com.seewo.libshapes.AbstractInk");
            } catch (Throwable ignored) {}

            Method mGet = page.getClass().getMethod("get", int.class);
            List<Object> penStrokes = new ArrayList<Object>();
            for (int i = 0; i < count; i++) {
                Object shape = mGet.invoke(page, i);
                if (shape == null) continue;
                if (isPenInkShape(shape, abstractInkCls)) {
                    penStrokes.add(shape);
                }
            }

            if (penStrokes.isEmpty()) {
                showTopToast(activity, "삭제할 펜글씨가 없습니다.");
                return;
            }

            // 1. 선택 박스 해제
            try {
                Class<?> d2Cls = Class.forName("com.seewo.easinote.p4.d.d0.d2");
                Object d2 = d2Cls.getMethod("k").invoke(null);
                if (d2 != null) d2Cls.getMethod("p").invoke(d2);
            } catch (Throwable ignored) {}

            // 2. Undo(실행 취소) 커맨드 등록 (가능한 경우)
            try {
                Class<?> nBCls = Class.forName("com.seewo.easinote.e4.a.n.b");
                Object cmdHolder = nBCls.getMethod("d").invoke(null);
                if (cmdHolder != null) {
                    Object cmdFactory = cmdHolder.getClass().getMethod("G1").invoke(cmdHolder);
                    if (cmdFactory != null) {
                        Class<?> pageIface = Class.forName("com.seewo.easinote.e4.a.l.h0.d.d");
                        Method mDelCmd = cmdFactory.getClass().getMethod("l", pageIface, List.class);
                        Object delCmd = mDelCmd.invoke(cmdFactory, page, penStrokes);
                        if (delCmd != null) {
                            Class<?> cmdIface = Class.forName("com.seewo.easinote.e4.a.l.h0.c.b$a");
                            stateHolder.getClass().getMethod("b", cmdIface).invoke(stateHolder, delCmd);
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Undo command registration skipped: " + t.getMessage());
            }

            // 3. 페이지에서 펜글씨 객체만 안전 제거 (page.f0(List))
            page.getClass().getMethod("f0", List.class).invoke(page, penStrokes);

            // 4. 화면 즉시 리페인트
            try {
                stateHolder.getClass().getMethod("R", Rect.class, boolean.class).invoke(stateHolder, null, true);
            } catch (Throwable ignored) {}
            try {
                stateHolder.getClass().getMethod("m").invoke(stateHolder);
            } catch (Throwable ignored) {}

            showTopToast(activity, "판서·도형 " + penStrokes.size() + "개를 삭제했습니다.");
            Log.i(TAG, "Cleared " + penStrokes.size() + " pen/shape objects (preserved PDF/ImageShape)");
        } catch (Throwable t) {
            Log.e(TAG, "Error in clearOnlyPenStrokes", t);
        }
    }

    private static boolean isPenInkShape(Object shape, Class<?> abstractInkCls) {
        if (shape == null) return false;
        String clsName = shape.getClass().getName();
        // PDF 페이지 및 올가미 이미지(ImageShape)는 반드시 보존!
        if (clsName.contains("ImageShape")) return false;
        if (abstractInkCls != null && abstractInkCls.isInstance(shape)) {
            return true;
        }
        // 모든 2D 도형(IStroke) 및 3D 입체도형(AbstractPolygon/Abstract3dShape)도 판서와 함께 일괄 삭제!
        try {
            if (Class.forName("com.seewo.libshapes.interfaces.IStroke").isInstance(shape)) {
                return true;
            }
        } catch (Throwable ignored) {}
        try {
            if (Class.forName("com.seewo.libshapes.AbstractPolygon").isInstance(shape)) {
                return true;
            }
        } catch (Throwable ignored) {}
        // 계층 이름 폴백 검사
        Class<?> c = shape.getClass();
        while (c != null && c != Object.class) {
            String n = c.getSimpleName();
            if (n.contains("Ink") || n.contains("Pen") || n.contains("Stroke")
                    || n.contains("Brush") || n.contains("Highlighter")
                    || n.contains("Ellipse") || n.contains("Line") || n.contains("Arrow")
                    || n.contains("Polygon") || n.contains("3dShape")) {
                return true;
            }
            c = c.getSuperclass();
        }
        return false;
    }

    /**
     * 현재 페이지의 모든 객체(PDF 이미지 + 판서)를 기준으로 폭맞춤 또는 쪽맞춤을 수행합니다.
     *
     * @param fitWidthOnly true = 폭맞춤(가로폭 94% 맞춤 + 상단 정렬), false = 쪽맞춤(한 화면에 전체 중앙 맞춤)
     */
    public static void applyPageFit(Activity activity, boolean fitWidthOnly) {
        if (activity == null) return;
        try {
            Field fP4a = activity.getClass().getDeclaredField("t");
            fP4a.setAccessible(true);
            Object p4a = fP4a.get(activity);
            if (p4a == null) return;

            Method mGetStateHolder = p4a.getClass().getMethod("a");
            Object stateHolder = mGetStateHolder.invoke(p4a);
            if (stateHolder == null) return;

            Method mGetPage = stateHolder.getClass().getMethod("m0");
            Object page = mGetPage.invoke(stateHolder);
            if (page == null) return;

            int count = (Integer) page.getClass().getMethod("size").invoke(page);
            if (count <= 0) {
                showTopToast(activity, "맞출 콘텐츠가 없습니다.");
                return;
            }

            // 1. 현재 페이지 내 모든 객체의 전체 바운딩 박스(union) 계산
            Rect contentRect = new Rect();
            Method mGetShape = page.getClass().getMethod("get", int.class);
            for (int i = 0; i < count; i++) {
                Object shape = mGetShape.invoke(page, i);
                if (shape == null) continue;
                try {
                    Rect b = (Rect) shape.getClass().getMethod("getBounds").invoke(shape);
                    if (b != null && !b.isEmpty()) {
                        contentRect.union(b);
                    }
                } catch (Throwable ignored) {}
            }

            if (contentRect.isEmpty() || contentRect.width() < 10 || contentRect.height() < 10) {
                showTopToast(activity, "맞출 콘텐츠가 없습니다.");
                return;
            }

            // 2. 칠판 작업 영역(WorkingRect) 조회
            Rect workRect = null;
            try {
                Object wbInterface = stateHolder.getClass().getMethod("T").invoke(stateHolder);
                if (wbInterface != null) {
                    workRect = (Rect) wbInterface.getClass().getMethod("getWorkingRect").invoke(wbInterface);
                }
            } catch (Throwable ignored) {}

            if (workRect == null || workRect.isEmpty()) {
                Field fWb = activity.getClass().getDeclaredField("c");
                fWb.setAccessible(true);
                View wbView = (View) fWb.get(activity);
                if (wbView != null && wbView.getWidth() > 0 && wbView.getHeight() > 0) {
                    workRect = new Rect(0, 0, wbView.getWidth(), wbView.getHeight());
                } else {
                    return;
                }
            }

            // 하단 툴바 영역을 제외한 실제 가시 판서 높이
            int bottomToolbarMargin = dp(activity, 96);
            float availW = workRect.width() * 0.94f;
            float availH = Math.max(200f, (workRect.height() - bottomToolbarMargin) * 0.94f);

            float scale;
            if (fitWidthOnly) {
                // 폭맞춤: 화면 가로폭의 94%에 맞춤
                scale = availW / (float) contentRect.width();
            } else {
                // 쪽맞춤: 가로·세로 모두 화면 안에 완전히 들어오도록 맞춤
                scale = Math.min(availW / (float) contentRect.width(), availH / (float) contentRect.height());
            }
            scale = Math.max(0.15f, Math.min(5.0f, scale));

            float scaledW = contentRect.width() * scale;
            float scaledH = contentRect.height() * scale;

            float dstLeft = workRect.left + (workRect.width() - scaledW) * 0.5f;
            float dstTop;
            if (fitWidthOnly) {
                // 폭맞춤 시 세로가 화면보다 길면 상단(여백 28px) 정렬, 짧으면 세로 중앙 정렬
                if (scaledH > availH) {
                    dstTop = workRect.top + dp(activity, 12);
                } else {
                    dstTop = workRect.top + Math.max(dp(activity, 12), ((workRect.height() - bottomToolbarMargin) - scaledH) * 0.5f);
                }
            } else {
                // 쪽맞춤 시 툴바 위 가시 영역 정중앙 배치
                dstTop = workRect.top + Math.max(dp(activity, 10), ((workRect.height() - bottomToolbarMargin) - scaledH) * 0.5f);
            }

            // 3. 변환 행렬 생성: 원점 이동 -> 스케일 -> 목표 위치 이동
            Matrix m = new Matrix();
            m.postTranslate(-contentRect.left, -contentRect.top);
            m.postScale(scale, scale);
            m.postTranslate(dstLeft, dstTop);

            // 4. 선택 박스 해제 후 네이티브 페이지 변환 실행 (i4.h.g.p 와 동일한 순정 시퀀스)
            try {
                Class<?> d2Cls = Class.forName("com.seewo.easinote.p4.d.d0.d2");
                Object d2 = d2Cls.getMethod("k").invoke(null);
                if (d2 != null) {
                    d2Cls.getMethod("p").invoke(d2);
                }
            } catch (Throwable ignored) {}

            page.getClass().getMethod("D0", Matrix.class).invoke(page, m);
            page.getClass().getMethod("endTransform", Matrix.class).invoke(page, m);
            page.getClass().getMethod("K").invoke(page);
            try {
                stateHolder.getClass().getMethod("R", Rect.class, boolean.class).invoke(stateHolder, null, true);
            } catch (Throwable ignored) {}
            try {
                stateHolder.getClass().getMethod("m").invoke(stateHolder);
            } catch (Throwable ignored) {}

            Log.i(TAG, String.format("Applied %s (scale=%.2f, content=%dx%d -> %.0fx%.0f)",
                    fitWidthOnly ? "FitWidth" : "FitPage", scale,
                    contentRect.width(), contentRect.height(), scaledW, scaledH));
        } catch (Throwable t) {
            Log.e(TAG, "Error in applyPageFit", t);
        }
    }

    /**
     * 인접한 순정 툴바 Button의 모든 스타일(글자 크기, 글꼴, 색상, 패딩, 아이콘 간격 등)을
     * 그대로 복제하여 완벽히 통일된 모양의 툴바 버튼을 생성합니다.
     */
    public static Button createStyledToolbarButton(Activity activity, Button refBtn, String label, Drawable topIcon) {
        Button btn = new Button(activity);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setSingleLine(true);
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setStateListAnimator(null);
        btn.setBackgroundColor(Color.TRANSPARENT);

        if (refBtn != null) {
            btn.setTextSize(TypedValue.COMPLEX_UNIT_PX, refBtn.getTextSize());
            ColorStateList colors = refBtn.getTextColors();
            if (colors != null) {
                btn.setTextColor(colors);
            } else {
                btn.setTextColor(Color.parseColor("#475569"));
            }
            btn.setTypeface(refBtn.getTypeface());
            btn.setGravity(refBtn.getGravity());
            btn.setPadding(
                    refBtn.getPaddingLeft(),
                    refBtn.getPaddingTop(),
                    refBtn.getPaddingRight(),
                    refBtn.getPaddingBottom()
            );
            btn.setCompoundDrawablePadding(refBtn.getCompoundDrawablePadding());
            btn.setMinWidth(0);
            btn.setMinimumWidth(0);
            btn.setMinHeight(0);
            btn.setMinimumHeight(0);
        } else {
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            btn.setTextColor(Color.parseColor("#475569"));
            btn.setGravity(Gravity.CENTER);
            btn.setPadding(0, dp(activity, 8), 0, dp(activity, 6));
            btn.setCompoundDrawablePadding(dp(activity, 4));
        }

        if (topIcon != null) {
            topIcon.setBounds(0, 0, topIcon.getIntrinsicWidth(), topIcon.getIntrinsicHeight());
            btn.setCompoundDrawables(null, topIcon, null, null);
        }
        return btn;
    }

    public static int[] measureRefIconSize(Activity activity, Button refBtn) {
        int def = dp(activity, 26);
        if (refBtn != null) {
            Drawable[] ds = refBtn.getCompoundDrawables();
            if (ds != null && ds.length > 1 && ds[1] != null) {
                int w = ds[1].getIntrinsicWidth();
                int h = ds[1].getIntrinsicHeight();
                if (w > 10 && h > 10) {
                    return new int[] { w, h };
                }
            }
        }
        return new int[] { def, def };
    }

    public static LinearLayout.LayoutParams cloneLayoutParams(View refView) {
        int w = dp(refView.getContext(), 48);
        int h = dp(refView.getContext(), 54);
        if (refView.getLayoutParams() != null) {
            ViewGroup.LayoutParams rlp = refView.getLayoutParams();
            if (rlp.width > 0) w = rlp.width;
            else if (refView.getWidth() > 20) w = refView.getWidth();
            if (rlp.height > 0) h = rlp.height;
            else if (refView.getHeight() > 20) h = refView.getHeight();
        } else if (refView.getWidth() > 20 && refView.getHeight() > 20) {
            w = refView.getWidth();
            h = refView.getHeight();
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.gravity = Gravity.CENTER_VERTICAL;
        return lp;
    }

    /**
     * 순정 툴바 아이콘과 동일한 얇은 라운드 라인아트 스타일의 '올가미' 아이콘 Drawable
     */
    public static Drawable createLassoIcon(final int width, final int height, final int color) {
        return new Drawable() {
            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                float w = b.width() > 0 ? b.width() : width;
                float h = b.height() > 0 ? b.height() : height;
                float s = Math.min(w, h);
                float ox = b.left + (w - s) * 0.5f;
                float oy = b.top + (h - s) * 0.5f;
                float sw = Math.max(2f, s * 0.068f);

                Paint dashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                dashPaint.setColor(color);
                dashPaint.setStyle(Paint.Style.STROKE);
                dashPaint.setStrokeWidth(sw);
                dashPaint.setStrokeCap(Paint.Cap.ROUND);
                dashPaint.setPathEffect(new DashPathEffect(new float[] { s * 0.14f, s * 0.09f }, 0));

                // 올가미 점선 타원 루프
                RectF loop = new RectF(ox + s * 0.13f, oy + s * 0.14f, ox + s * 0.87f, oy + s * 0.72f);
                canvas.drawOval(loop, dashPaint);

                // 올가미 매듭 + 손잡이 꼬리
                Paint solidPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                solidPaint.setColor(color);
                solidPaint.setStyle(Paint.Style.STROKE);
                solidPaint.setStrokeWidth(sw);
                solidPaint.setStrokeCap(Paint.Cap.ROUND);
                solidPaint.setStrokeJoin(Paint.Join.ROUND);

                float knotX = ox + s * 0.36f;
                float knotY = oy + s * 0.71f;
                canvas.drawCircle(knotX, knotY, s * 0.075f, solidPaint);

                Path tail = new Path();
                tail.moveTo(knotX, knotY + s * 0.075f);
                tail.quadTo(ox + s * 0.32f, oy + s * 0.90f, ox + s * 0.52f, oy + s * 0.88f);
                canvas.drawPath(tail, solidPaint);
            }

            @Override public int getIntrinsicWidth() { return width; }
            @Override public int getIntrinsicHeight() { return height; }
            @Override public void setAlpha(int alpha) {}
            @Override public void setColorFilter(ColorFilter colorFilter) {}
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    /**
     * 순정 툴바 아이콘과 동일한 라운드 라인아트 스타일의 '폭맞춤' (|← →|) 아이콘 Drawable
     */
    public static Drawable createFitWidthIcon(final int width, final int height, final int color) {
        return new Drawable() {
            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                float w = b.width() > 0 ? b.width() : width;
                float h = b.height() > 0 ? b.height() : height;
                float s = Math.min(w, h);
                float ox = b.left + (w - s) * 0.5f;
                float oy = b.top + (h - s) * 0.5f;
                float sw = Math.max(2f, s * 0.068f);

                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(color);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(sw);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);

                // 좌우 세로 경계선 (|)
                canvas.drawLine(ox + s * 0.14f, oy + s * 0.20f, ox + s * 0.14f, oy + s * 0.80f, p);
                canvas.drawLine(ox + s * 0.86f, oy + s * 0.20f, ox + s * 0.86f, oy + s * 0.80f, p);

                // 가로 양방향 화살표 (← →)
                float cy = oy + s * 0.50f;
                canvas.drawLine(ox + s * 0.26f, cy, ox + s * 0.74f, cy, p);

                Path leftHead = new Path();
                leftHead.moveTo(ox + s * 0.39f, cy - s * 0.14f);
                leftHead.lineTo(ox + s * 0.25f, cy);
                leftHead.lineTo(ox + s * 0.39f, cy + s * 0.14f);
                canvas.drawPath(leftHead, p);

                Path rightHead = new Path();
                rightHead.moveTo(ox + s * 0.61f, cy - s * 0.14f);
                rightHead.lineTo(ox + s * 0.75f, cy);
                rightHead.lineTo(ox + s * 0.61f, cy + s * 0.14f);
                canvas.drawPath(rightHead, p);
            }

            @Override public int getIntrinsicWidth() { return width; }
            @Override public int getIntrinsicHeight() { return height; }
            @Override public void setAlpha(int alpha) {}
            @Override public void setColorFilter(ColorFilter colorFilter) {}
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    /**
     * 순정 툴바 아이콘과 동일한 라운드 라인아트 스타일의 '쪽맞춤' (네 모서리 프레임 + 페이지) 아이콘 Drawable
     */
    public static Drawable createFitPageIcon(final int width, final int height, final int color) {
        return new Drawable() {
            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                float w = b.width() > 0 ? b.width() : width;
                float h = b.height() > 0 ? b.height() : height;
                float s = Math.min(w, h);
                float ox = b.left + (w - s) * 0.5f;
                float oy = b.top + (h - s) * 0.5f;
                float sw = Math.max(2f, s * 0.068f);

                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(color);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(sw);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);

                float l = ox + s * 0.14f;
                float t = oy + s * 0.14f;
                float r = ox + s * 0.86f;
                float bt = oy + s * 0.86f;
                float c = s * 0.19f;

                // 네 모서리 맞춤 브래킷
                Path corners = new Path();
                corners.moveTo(l, t + c); corners.lineTo(l, t); corners.lineTo(l + c, t);
                corners.moveTo(r - c, t); corners.lineTo(r, t); corners.lineTo(r, t + c);
                corners.moveTo(l, bt - c); corners.lineTo(l, bt); corners.lineTo(l + c, bt);
                corners.moveTo(r - c, bt); corners.lineTo(r, bt); corners.lineTo(r, bt - c);
                canvas.drawPath(corners, p);

                // 중앙 페이지 사각형
                RectF pageRect = new RectF(ox + s * 0.32f, oy + s * 0.28f, ox + s * 0.68f, oy + s * 0.72f);
                canvas.drawRoundRect(pageRect, s * 0.06f, s * 0.06f, p);
            }

            @Override public int getIntrinsicWidth() { return width; }
            @Override public int getIntrinsicHeight() { return height; }
            @Override public void setAlpha(int alpha) {}
            @Override public void setColorFilter(ColorFilter colorFilter) {}
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    /**
     * 칠판 전체 벡터 레이어를 즉시 재렌더링(stateHolder.R(null, true))합니다.
     * 형광펜(HighlightPencil) 필기 직후 호출하면 QuickRefreshView의 세그먼트 이음새(가는 금)를
     * 단일 Path 렌더링으로 즉시 깔끔하게 정리합니다.
     */
    public static void forceRepaintWhiteboard(Activity activity) {
        if (activity == null) return;
        try {
            Field fP4a = activity.getClass().getDeclaredField("t");
            fP4a.setAccessible(true);
            Object p4a = fP4a.get(activity);
            if (p4a == null) return;
            Object stateHolder = p4a.getClass().getMethod("a").invoke(p4a);
            if (stateHolder == null) return;
            try {
                stateHolder.getClass().getMethod("R", Rect.class, boolean.class).invoke(stateHolder, null, true);
            } catch (Throwable ignored) {}
            try {
                stateHolder.getClass().getMethod("m").invoke(stateHolder);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    public static View findViewByIdName(Activity activity, String idName) {
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

    private static final android.os.Handler sToastHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private static TextView sTopToastView;
    private static Runnable sHideTopToastRunnable;

    /**
     * 화면 상단 중앙(미러링 번호 바 바로 아래)에 터치 통과형 슬림 안내 배너를 표시합니다.
     * - 하단 툴바를 절대 가리지 않으며, 표시 중에도 그 위에서 바로 판서 가능합니다.
     * - 새 메시지가 오면 이전 메시지를 기다리지 않고 즉시 교체합니다.
     */
    public static void showTopToast(final Activity activity, final String msg) {
        showTopToast(activity, msg, false);
    }

    public static void showTopToast(final Activity activity, final String msg, final boolean isLong) {
        if (activity == null || msg == null || msg.isEmpty()) return;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
                    if (!(decor instanceof ViewGroup)) {
                        Toast.makeText(activity, msg, isLong ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ViewGroup root = (ViewGroup) decor;
                    TextView tv = sTopToastView;
                    if (tv == null || tv.getParent() != root) {
                        if (tv != null && tv.getParent() instanceof ViewGroup) {
                            ((ViewGroup) tv.getParent()).removeView(tv);
                        }
                        tv = new TextView(activity);
                        tv.setTag("gnfl_top_toast");
                        tv.setClickable(false);
                        tv.setFocusable(false);
                        tv.setTextColor(Color.WHITE);
                        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
                        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                        tv.setGravity(Gravity.CENTER);
                        tv.setPadding(dp(activity, 20), dp(activity, 10), dp(activity, 20), dp(activity, 10));
                        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                        bg.setColor(0xE61E293B);
                        bg.setCornerRadius(dp(activity, 22));
                        bg.setStroke(dp(activity, 1), 0x33FFFFFF);
                        tv.setBackground(bg);
                        if (android.os.Build.VERSION.SDK_INT >= 21) {
                            tv.setElevation(dp(activity, 8));
                        }
                        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                        lp.topMargin = dp(activity, 56);
                        root.addView(tv, lp);
                        sTopToastView = tv;
                    }

                    if (sHideTopToastRunnable != null) {
                        sToastHandler.removeCallbacks(sHideTopToastRunnable);
                    }
                    tv.animate().cancel();
                    tv.setText(msg);
                    tv.setVisibility(View.VISIBLE);
                    tv.bringToFront();
                    tv.setAlpha(0f);
                    tv.animate().alpha(1f).setDuration(140).start();

                    final TextView targetTv = tv;
                    sHideTopToastRunnable = new Runnable() {
                        @Override
                        public void run() {
                            try {
                                targetTv.animate().alpha(0f).setDuration(220).withEndAction(new Runnable() {
                                    @Override
                                    public void run() {
                                        targetTv.setVisibility(View.GONE);
                                    }
                                }).start();
                            } catch (Throwable ignored) {
                                targetTv.setVisibility(View.GONE);
                            }
                        }
                    };
                    sToastHandler.postDelayed(sHideTopToastRunnable, isLong ? 2800L : 1800L);
                } catch (Throwable t) {
                    try {
                        Toast.makeText(activity, msg, isLong ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {}
                }
            }
        });
    }

    private static int dp(android.content.Context ctx, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics());
    }
}

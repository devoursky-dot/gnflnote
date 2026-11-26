package kr.hs.gnflhs.whiteboard;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * GNFL NOTE V1.2 - 다이어트 버전 기능 정리 관리자
 * 
 * 실기기 전수 테스트에서 크래시하거나 동작하지 않는 기능들을 UI에서 깔끔하게 제거(GONE)합니다:
 * 1. 하단 툴바 '공유' (explore_edu_toolbar_share) -> 클릭 시 즉시 크래시
 * 2. 메뉴 '클라우드 저장공간' (explore_edu_menu_cloud) -> 클릭 시 무반응(먹통)
 * 3. 메뉴 '이메일 전송' (explore_edu_menu_send_email) -> 클릭 시 무반응(먹통)
 * 4. 더 보기 '마인드 매핑' (explore_edu_toolbar_mind) -> 외부 언어팩 다운로드 필요(미동작)
 * 5. 파일 탐색기 '공유 클라우드' -> 외부 클라우드 계정 필요
 */
public class DietManager {
    private static final String TAG = "DietManager";
    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    private static final String[] REMOVED_IDS = new String[] {
            "explore_edu_toolbar_share",
            "explore_edu_toolbar_mind",
            "explore_edu_toolbar_pair_mode",
            "explore_edu_toolbar_form",
            "explore_edu_toolbar_note",
            "explore_edu_toolbar_grid",
            "explore_edu_toolbar_ruler",
            "explore_edu_toolbar_exit",
            "recognize_text"
    };

    private static final String[] REMOVED_TEXTS = new String[] {
            "공유",
            "마인드 매핑",
            "공유 클라우드",
            "페어링 모드",
            "페어링모드",
            "시트",
            "GNFLNOTE",
            "그리드"
    };

    // 숨김 메뉴(메인 메뉴 팝업)에서만 임시로 UI를 숨기는 항목 (기능 보존, 다이얼로그 버튼은 건드리지 않음)
    private static final String[] HIDDEN_MAIN_MENU_TEXTS = new String[] {
            "새로 만들기",
            "새로만들기",
            "열기",
            "저장",
            "다른 이름으로 저장",
            "다른이름으로저장",
            "내보내기"
    };

    private static java.lang.ref.WeakReference<View> sLayoutWatchedDecor;

    /**
     * 메인 액티비티 및 떠 있는 모든 팝업 윈도우에서 불량/불필요 기능을 즉시 숨깁니다.
     */
    public static void applyDiet(final Activity activity) {
        if (activity == null) return;
        try {
            final View decor = activity.getWindow().getDecorView();
            pruneViewTree(activity, decor);
            pruneAllPopupWindows(activity);
            ensureShapeButtonVisibleIfWide(activity, decor);

            if (decor != null && (sLayoutWatchedDecor == null || sLayoutWatchedDecor.get() != decor)) {
                sLayoutWatchedDecor = new java.lang.ref.WeakReference<View>(decor);
                decor.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                    private int lastW = -1;
                    @Override
                    public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                        int w = r - l;
                        if (w != lastW) {
                            lastW = w;
                            sHandler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    ensureShapeButtonVisibleIfWide(activity, decor);
                                }
                            }, 80);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error in applyDiet", t);
        }
    }

    /**
     * 전체화면(충분한 가로 여유 공간)에서는 순정 툴바가 7색 퀵 팔레트 때문에
     * '도형' 버튼을 '더 보기(...)' 안으로 접어 숨기지 않도록 메인 툴바에 항상 표시합니다.
     * (자유창처럼 실제로 화면이 좁을 때는 순정 '더 보기' 동작을 그대로 유지!)
     */
    private static void ensureShapeButtonVisibleIfWide(Activity activity, View decor) {
        if (activity == null || decor == null) return;
        try {
            int w = decor.getWidth();
            float density = activity.getResources().getDisplayMetrics().density;
            float widthDp = (density > 0f) ? (w / density) : w;
            if (widthDp >= 960f) {
                View shapeBtn = ToolbarFitHelper.findViewByIdName(activity, "explore_edu_toolbar_shape");
                if (shapeBtn != null && shapeBtn.getVisibility() != View.VISIBLE) {
                    shapeBtn.setVisibility(View.VISIBLE);
                    if (shapeBtn.getParent() instanceof View) {
                        ((View) shapeBtn.getParent()).requestLayout();
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 팝업(메뉴/더보기/열기 등)이 열릴 타이밍에 맞춰 짧은 지연으로 스캔하여
     * 불량 항목 제거 및 불러오기->PDF 1회 선택 후킹을 적용합니다.
     */
    public static void schedulePopupDiet(final Activity activity) {
        if (activity == null) return;
        sHandler.post(new Runnable() {
            @Override
            public void run() {
                applyDiet(activity);
            }
        });
        sHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                applyDiet(activity);
            }
        }, 40);
        sHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                applyDiet(activity);
            }
        }, 160);
        sHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                applyDiet(activity);
            }
        }, 350);
    }

    private static void pruneAllPopupWindows(Activity activity) {
        try {
            Class<?> wmgCls = Class.forName("android.view.WindowManagerGlobal");
            Method getInst = wmgCls.getMethod("getInstance");
            Object wmg = getInst.invoke(null);
            Field fViews = wmgCls.getDeclaredField("mViews");
            fViews.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<View> views = (List<View>) fViews.get(wmg);
            if (views != null) {
                for (int i = 0; i < views.size(); i++) {
                    View root = views.get(i);
                    if (root != null) {
                        pruneViewTree(activity, root);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void hideViewCompletely(View v) {
        if (v == null) return;
        boolean changed = (v.getVisibility() != View.GONE);
        if (changed) {
            v.setVisibility(View.GONE);
        }
        try {
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null) {
                boolean isHorizontalBar = false;
                if (v.getParent() instanceof android.widget.LinearLayout) {
                    isHorizontalBar = (((android.widget.LinearLayout) v.getParent()).getOrientation() == android.widget.LinearLayout.HORIZONTAL);
                }
                if (isHorizontalBar) {
                    if (lp.width != 0) {
                        lp.width = 0;
                        v.setLayoutParams(lp);
                        changed = true;
                    }
                    v.setMinimumWidth(0);
                } else {
                    // AOSP ListView는 lp.height == 0을 UNSPECIFIED(WRAP_CONTENT)로 취급하므로
                    // AbsListView 자식인 경우 정확히 1px(EXACTLY)로 축소하여 빈 여백을 완벽히 제거합니다.
                    int targetH = (v.getParent() instanceof android.widget.AbsListView) ? 1 : 0;
                    if (lp.height != targetH) {
                        lp.height = targetH;
                        v.setLayoutParams(lp);
                        changed = true;
                    }
                    v.setMinimumHeight(0);
                    if (targetH == 1 && v instanceof ViewGroup) {
                        ViewGroup vg = (ViewGroup) v;
                        for (int i = 0; i < vg.getChildCount(); i++) {
                            vg.getChildAt(i).setVisibility(View.GONE);
                        }
                    }
                }
            }
            v.setPadding(0, 0, 0, 0);
            if (changed && v.getParent() instanceof View) {
                View p = (View) v.getParent();
                p.requestLayout();
                if (p.getParent() instanceof View) {
                    ((View) p.getParent()).requestLayout();
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void pruneViewTree(final Activity activity, View root) {
        if (root == null) return;

        // 1. 리소스 ID 매칭 검사
        int id = root.getId();
        if (id != View.NO_ID) {
            try {
                String entryName = root.getResources().getResourceEntryName(id);
                // 불러오기 -> PDF 버튼: 2번 선택하지 않도록 곧바로 SAF 문서 선택기로 직행!
                if ("import_menu_pdf".equals(entryName)) {
                    if (!"gnfl_diet_pdf".equals(root.getTag())) {
                        root.setTag("gnfl_diet_pdf");
                        root.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                PdfPicker.launchSafPdfPicker(activity);
                            }
                        });
                    }
                    return;
                }
                // 삭제 버튼(더 보기 팝업 포함): PDF/이미지는 보존하고 펜글씨만 삭제!
                if ("explore_edu_toolbar_clean".equals(entryName)) {
                    if (!"gnfl_ink_clean_hooked".equals(root.getTag())) {
                        root.setTag("gnfl_ink_clean_hooked");
                        root.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                ToolbarFitHelper.clearOnlyPenStrokes(activity);
                            }
                        });
                    }
                    return;
                }
                // 도형 버튼이 메인 툴바에 이미 표시 중이면, '더 보기' 팝업 안의 중복 '도형' 항목은 숨김!
                if ("explore_edu_toolbar_shape".equals(entryName)
                        && root.getRootView() != activity.getWindow().getDecorView()) {
                    View mainShapeBtn = ToolbarFitHelper.findViewByIdName(activity, "explore_edu_toolbar_shape");
                    if (mainShapeBtn != null && mainShapeBtn != root && mainShapeBtn.getVisibility() == View.VISIBLE) {
                        hideViewCompletely(findRowContainer(root));
                        return;
                    }
                }
                // 펜 팝업(pen_radio_group): 4번째 버튼(smart)을 순정 형광펜 아이콘 + 반투명 형광펜 모드로 교체!
                if ("pen_radio_group".equals(entryName) && root instanceof ViewGroup) {
                    configurePenPopupHighlighter(activity, (ViewGroup) root);
                    return;
                }
                // 도형 팝업(explore_edu_shape_window 좌측 2D/3D RadioGroup 0x7f0801ee):
                // 펜 팝업과 동일한 스타일의 도형 선 두께 슬라이더(SeekBar + 원형 미리보기) 주입!
                if (id == 0x7f0801ee && root.getParent() instanceof android.widget.RelativeLayout) {
                    configureShapePopupWidthSlider(activity, (android.widget.RelativeLayout) root.getParent(), id);
                }
                for (String target : REMOVED_IDS) {
                    if (target.equals(entryName)) {
                        hideViewCompletely(root);
                        return;
                    }
                }
            } catch (Throwable ignored) {}
        }

        // 2. 텍스트 매칭 검사 (팝업 메뉴 행/버튼)
        if (root instanceof TextView) {
            CharSequence cs = ((TextView) root).getText();
            if (cs != null) {
                String txt = cs.toString().trim();
                // 기존 "불러오기" -> "PDF 불러오기": 클릭 시 옆으로 확장 서브메뉴(새로 불러오기 + 최근 PDF 캐시) 표시!
                if ("불러오기".equals(txt) || "PDF 불러오기".equals(txt)) {
                    ((TextView) root).setText("PDF 불러오기");
                    final View row = findRowContainer(root);
                    View.OnTouchListener pdfSubmenuTouch = new View.OnTouchListener() {
                        @Override
                        public boolean onTouch(View v, android.view.MotionEvent event) {
                            int act = event.getActionMasked();
                            if (act == android.view.MotionEvent.ACTION_UP) {
                                PdfRecentSubmenu.showNextToAnchor(activity, row);
                            }
                            return true; // 순정 서브메뉴로 이벤트가 넘어가지 않도록 완전 소비!
                        }
                    };
                    row.setOnTouchListener(pdfSubmenuTouch);
                    root.setOnTouchListener(pdfSubmenuTouch);
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            PdfRecentSubmenu.showNextToAnchor(activity, row);
                        }
                    });
                    return;
                }

                // 무반응 '클라우드 저장공간' 행은 숨김 ('목록' 버튼은 PDF 로드 시 메뉴 옆 툴바에 직접 표시)
                if ("클라우드 저장공간".equals(txt) || "PDF 페이지 목록".equals(txt)) {
                    hideViewCompletely(findRowContainer(root));
                    return;
                }

                // '더 보기' 팝업 안의 중복 '도형' 항목: 메인 툴바에 '도형'이 이미 보이고 있으면 자동 숨김!
                if ("도형".equals(txt) && root.getRootView() != activity.getWindow().getDecorView()) {
                    View mainShapeBtn = ToolbarFitHelper.findViewByIdName(activity, "explore_edu_toolbar_shape");
                    if (mainShapeBtn != null && mainShapeBtn.getVisibility() == View.VISIBLE) {
                        hideViewCompletely(findRowContainer(root));
                        return;
                    }
                }

                // 기존 무반응 '이메일 전송' 행을 '앱 나가기'로 재활용 + 원래 나가기 아이콘(icon_exit)으로 교체
                if ("이메일 전송".equals(txt) || "앱 나가기".equals(txt)) {
                    ((TextView) root).setText("앱 나가기");
                    final View row = findRowContainer(root);
                    row.setVisibility(View.VISIBLE);
                    applyOriginalExitIcon(activity, row, (TextView) root);
                    View.OnTouchListener exitTouch = new View.OnTouchListener() {
                        @Override
                        public boolean onTouch(View v, android.view.MotionEvent event) {
                            if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                                PdfRecentSubmenu.dismissIfShowing();
                                View exitBtn = ToolbarFitHelper.findViewByIdName(activity, "explore_edu_toolbar_exit");
                                if (exitBtn != null) {
                                    exitBtn.performClick();
                                } else {
                                    activity.finish();
                                }
                            }
                            return true;
                        }
                    };
                    row.setOnTouchListener(exitTouch);
                    root.setOnTouchListener(exitTouch);
                    return;
                }

                for (String targetTxt : REMOVED_TEXTS) {
                    if (targetTxt.equals(txt)) {
                        View targetToHide = findRowContainer(root);
                        hideViewCompletely(targetToHide);
                        return;
                    }
                }

                if (!(root instanceof android.widget.Button)) {
                    for (String menuTxt : HIDDEN_MAIN_MENU_TEXTS) {
                        if (menuTxt.equals(txt) && isInsideMainMenuPopup(root)) {
                            hideViewCompletely(findRowContainer(root));
                            return;
                        }
                    }
                }
            }
        }

        // 3. 자식 뷰 재귀 탐색
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                pruneViewTree(activity, vg.getChildAt(i));
            }
        }
    }

    /**
     * TextView가 메뉴 항목(아이콘+텍스트를 감싸는 LinearLayout/RelativeLayout) 안에 있는 경우
     * 텍스트만 숨기지 않고 해당 메뉴 행 전체를 깔끔하게 숨깁니다.
     */
    private static View findRowContainer(View textView) {
        if (textView.isClickable()) {
            return textView;
        }
        if (textView.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) textView.getParent();
            // 아이콘 + 텍스트 2~3개 자식을 가진 메뉴 행 컨테이너인 경우
            if (parent.isClickable() || parent.getChildCount() <= 3) {
                return parent;
            }
        }
        return textView;
    }

    /**
     * '앱 나가기' 메뉴 행의 아이콘을 원래 툴바의 나가기 아이콘(icon_exit)으로 교체합니다.
     */
    private static void applyOriginalExitIcon(Activity activity, View row, TextView tv) {
        if (activity == null) return;
        try {
            int resId = activity.getResources().getIdentifier("icon_exit", "drawable", "com.seewo.easinote");
            if (resId == 0) {
                resId = activity.getResources().getIdentifier("icon_exit", "drawable", activity.getPackageName());
            }
            if (resId == 0) return;

            android.graphics.drawable.Drawable exitDr = activity.getResources().getDrawable(resId);
            if (exitDr == null) return;
            exitDr = exitDr.mutate();

            // 1. TextView의 좌측 compoundDrawable인 경우 교체
            if (tv != null) {
                android.graphics.drawable.Drawable[] cds = tv.getCompoundDrawables();
                if (cds != null && cds[0] != null) {
                    android.graphics.Rect b = cds[0].getBounds();
                    if (b.width() > 0 && b.height() > 0) {
                        exitDr.setBounds(0, 0, b.width(), b.height());
                    } else {
                        int sz = (int) (22 * activity.getResources().getDisplayMetrics().density);
                        exitDr.setBounds(0, 0, sz, sz);
                    }
                    tv.setCompoundDrawables(exitDr, cds[1], cds[2], cds[3]);
                    return;
                }
            }

            // 2. 행 컨테이너 내부의 ImageView 아이콘 교체
            if (row instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) row;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    View child = vg.getChildAt(i);
                    if (child instanceof android.widget.ImageView) {
                        android.widget.ImageView iv = (android.widget.ImageView) child;
                        iv.setBackground(null);
                        iv.clearColorFilter();
                        iv.setImageDrawable(exitDr);
                        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                        return;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyOriginalExitIcon failed", t);
        }
    }

    /**
     * 이 뷰가 하단 '메뉴' 팝업 내부 항목인지 판별합니다.
     * (나가기 확인 다이얼로그의 '저장' 버튼 등을 잘못 숨기지 않기 위한 안전장치)
     */
    private static boolean isInsideMainMenuPopup(View v) {
        try {
            View cur = v;
            int depth = 0;
            while (cur != null && depth < 6) {
                if (cur instanceof ViewGroup) {
                    if (containsMenuAnchorText((ViewGroup) cur)) {
                        return true;
                    }
                }
                if (cur.getParent() instanceof View) {
                    cur = (View) cur.getParent();
                } else {
                    break;
                }
                depth++;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean containsMenuAnchorText(ViewGroup vg) {
        for (int i = 0; i < vg.getChildCount(); i++) {
            View c = vg.getChildAt(i);
            if (c instanceof TextView) {
                CharSequence cs = ((TextView) c).getText();
                if (cs != null) {
                    String s = cs.toString().trim();
                    if ("PDF 불러오기".equals(s) || "불러오기".equals(s) || "테마".equals(s)
                            || "앱 나가기".equals(s) || "이메일 전송".equals(s) || "클라우드 저장공간".equals(s)) {
                        return true;
                    }
                }
            } else if (c instanceof ViewGroup) {
                if (containsMenuAnchorText((ViewGroup) c)) return true;
            }
        }
        return false;
    }

    private static float sSavedShapeWidth = -1f;

    public static float getSavedShapeWidth() {
        return sSavedShapeWidth;
    }

    /**
     * 현재 페이지의 도형(IShape) 개수를 반환합니다.
     */
    public static int getCurrentPageShapeCount(Activity activity) {
        if (activity == null) return -1;
        try {
            Field fP4a = activity.getClass().getDeclaredField("t");
            fP4a.setAccessible(true);
            Object p4a = fP4a.get(activity);
            if (p4a == null) return -1;
            Object stateHolder = p4a.getClass().getMethod("a").invoke(p4a);
            if (stateHolder == null) return -1;
            Object page = stateHolder.getClass().getMethod("m0").invoke(stateHolder);
            if (page == null) return -1;
            return (Integer) page.getClass().getMethod("size").invoke(page);
        } catch (Throwable ignored) {}
        return -1;
    }

    /**
     * 현재 페이지의 마지막 도형(IShape) 객체를 반환합니다. (스마트 도형 비동기 교체 감지용)
     */
    public static Object getLastPageShape(Activity activity) {
        if (activity == null) return null;
        try {
            Field fP4a = activity.getClass().getDeclaredField("t");
            fP4a.setAccessible(true);
            Object p4a = fP4a.get(activity);
            if (p4a == null) return null;
            Object stateHolder = p4a.getClass().getMethod("a").invoke(p4a);
            if (stateHolder == null) return null;
            Object page = stateHolder.getClass().getMethod("m0").invoke(stateHolder);
            if (page == null) return null;
            int sz = (Integer) page.getClass().getMethod("size").invoke(page);
            if (sz > 0) {
                return page.getClass().getMethod("get", int.class).invoke(page, sz - 1);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * 도형 툴로 방금 그려진 모든 2D 도형(IStroke), 3D 입체도형(AbstractPolygon.setStroke),
     * 및 손모양 스마트 도형(비동기 변환)에 슬라이더 선 두께를 즉시 적용합니다.
     */
    public static boolean applySavedWidthToNewShapes(Activity activity, int prevCount, Object prevLastShape) {
        if (activity == null || sSavedShapeWidth <= 0f || prevCount < 0) return false;
        try {
            Field fP4a = activity.getClass().getDeclaredField("t");
            fP4a.setAccessible(true);
            Object p4a = fP4a.get(activity);
            if (p4a == null) return false;
            Object stateHolder = p4a.getClass().getMethod("a").invoke(p4a);
            if (stateHolder == null) return false;
            Object page = stateHolder.getClass().getMethod("m0").invoke(stateHolder);
            if (page == null) return false;

            int curCount = (Integer) page.getClass().getMethod("size").invoke(page);
            if (curCount <= 0) return false;

            Class<?> iStrokeCls = Class.forName("com.seewo.libshapes.interfaces.IStroke");
            Class<?> polyCls = null;
            try { polyCls = Class.forName("com.seewo.libshapes.AbstractPolygon"); } catch (Throwable ignored) {}
            Method mGet = page.getClass().getMethod("get", int.class);
            Method mGetW = iStrokeCls.getMethod("getStrokeWidth");
            Method mSetW = iStrokeCls.getMethod("setStrokeWidth", float.class);
            Method mPolyGetW = (polyCls != null) ? polyCls.getMethod("getStroke") : null;
            Method mPolySetW = (polyCls != null) ? polyCls.getMethod("setStroke", float.class) : null;
            boolean modified = false;

            int startIdx = Math.min(prevCount, curCount - 1);
            if (startIdx < 0) startIdx = 0;
            for (int i = startIdx; i < curCount; i++) {
                Object shape = mGet.invoke(page, i);
                if (shape == null || shape == prevLastShape) continue;
                if (i < prevCount && i != curCount - 1) continue;
                if (iStrokeCls.isInstance(shape)) {
                    float curW = (Float) mGetW.invoke(shape);
                    if (Math.abs(curW - sSavedShapeWidth) > 0.5f) {
                        mSetW.invoke(shape, sSavedShapeWidth);
                        modified = true;
                    }
                } else if (polyCls != null && polyCls.isInstance(shape) && mPolyGetW != null && mPolySetW != null) {
                    // 3D 입체도형(Cube, Cylinder, Cone, Pyramid 등) 및 AbstractPolygon 계열: setStroke(float) 사용!
                    float curW = (Float) mPolyGetW.invoke(shape);
                    if (Math.abs(curW - sSavedShapeWidth) > 0.5f) {
                        mPolySetW.invoke(shape, sSavedShapeWidth);
                        modified = true;
                    }
                }
            }
            if (modified) {
                // stateHolder.m()은 진행 중인 제스처/프리젠터를 리셋하므로 호출하지 않고 R(null, true)로만 깔끔히 리페인트!
                try {
                    stateHolder.getClass().getMethod("R", android.graphics.Rect.class, boolean.class)
                            .invoke(stateHolder, null, true);
                } catch (Throwable ignored) {}
                Log.i(TAG, "Applied shape stroke width=" + sSavedShapeWidth + " (curCount=" + curCount + ")");
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "applySavedWidthToNewShapes failed", t);
        }
        return false;
    }

    /**
     * 도형 팝업(explore_edu_shape_window)의 도형 목록과 색상표 사이에
     * 펜 팝업과 동일한 디자인의 선 두께 슬라이더(SeekBar + 실시간 원형 미리보기)를 추가합니다.
     */
    private static void configureShapePopupWidthSlider(
            final Activity activity,
            final android.widget.RelativeLayout parent,
            final int leftRgId) {
        if (activity == null || parent == null) return;
        try {
            View existing = parent.findViewWithTag("gnfl_shape_width_bar");
            if (existing != null) {
                if (sSavedShapeWidth > 0f) {
                    applyGeometryWidth(activity, sSavedShapeWidth);
                }
                existing.invalidate();
                return;
            }

            View planeGrid = parent.findViewById(0x7f0801c6);
            View solidGrid = parent.findViewById(0x7f080235);
            View colorGrid = parent.findViewById(0x7f0800a2);
            if (colorGrid == null) return;

            float curW = readGeometryWidth(activity);
            if (sSavedShapeWidth > 0f) {
                curW = sSavedShapeWidth;
                applyGeometryWidth(activity, curW);
            } else {
                if (curW < 2f || curW > 28f) curW = 4f;
                sSavedShapeWidth = curW;
            }

            final float density = activity.getResources().getDisplayMetrics().density;
            int barId = View.generateViewId();

            android.widget.LinearLayout bar = new android.widget.LinearLayout(activity);
            bar.setId(barId);
            bar.setTag("gnfl_shape_width_bar");
            bar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            bar.setGravity(android.view.Gravity.CENTER_VERTICAL);

            final android.widget.SeekBar sb = new android.widget.SeekBar(activity);
            sb.setMax(26); // 2px ~ 28px (펜 슬라이더와 동일한 26단계)
            int initProg = Math.max(0, Math.min(26, Math.round(curW - 2f)));
            sb.setProgress(initProg);
            int teal = 0xFF00B589;
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                sb.setProgressTintList(android.content.res.ColorStateList.valueOf(teal));
                sb.setThumbTintList(android.content.res.ColorStateList.valueOf(teal));
                sb.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFCBD5E1));
            }
            android.widget.LinearLayout.LayoutParams sbLp = new android.widget.LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            bar.addView(sb, sbLp);

            // 우측 실시간 굵기 원형 미리보기 (펜 팝업과 동일한 느낌)
            final View previewDot = new View(activity);
            previewDot.setBackground(new android.graphics.drawable.Drawable() {
                @Override
                public void draw(android.graphics.Canvas canvas) {
                    android.graphics.Rect b = getBounds();
                    float cx = b.exactCenterX();
                    float cy = b.exactCenterY();
                    float w = (sSavedShapeWidth > 0f) ? sSavedShapeWidth : 4f;
                    float ratio = Math.max(0f, Math.min(1f, (w - 2f) / 26f));
                    float r = (3.0f + ratio * 7.5f) * density;
                    int col = readGeometryColor(activity);
                    if ((col & 0xFF000000) == 0) col = 0xFFFFFFFF;

                    android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                    p.setStyle(android.graphics.Paint.Style.FILL);
                    p.setColor(col);
                    canvas.drawCircle(cx, cy, r, p);

                    // 밝은 색(흰색 등)은 흰 팝업 배경에서도 보이도록 연회색 테두리 추가
                    int rC = (col >> 16) & 0xFF;
                    int gC = (col >> 8) & 0xFF;
                    int bC = col & 0xFF;
                    if ((rC * 299 + gC * 587 + bC * 114) / 1000 > 205) {
                        p.setStyle(android.graphics.Paint.Style.STROKE);
                        p.setStrokeWidth(Math.max(1.5f, 1.2f * density));
                        p.setColor(0xFF94A3B8);
                        canvas.drawCircle(cx, cy, r, p);
                    }
                }
                @Override public void setAlpha(int alpha) {}
                @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
                @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
            });
            int dotBox = Math.round(26f * density);
            android.widget.LinearLayout.LayoutParams dotLp = new android.widget.LinearLayout.LayoutParams(dotBox, dotBox);
            dotLp.leftMargin = Math.round(8f * density);
            bar.addView(previewDot, dotLp);

            sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                    float newW = 2f + progress;
                    sSavedShapeWidth = newW;
                    applyGeometryWidth(activity, newW);
                    previewDot.invalidate();
                }
                @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
            });

            // 색상표 클릭 시 원형 미리보기 색상도 즉시 동기화
            if (colorGrid instanceof android.widget.GridView) {
                final android.widget.GridView cgv = (android.widget.GridView) colorGrid;
                final android.widget.AdapterView.OnItemClickListener orig = cgv.getOnItemClickListener();
                cgv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
                    @Override
                    public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                        if (orig != null) {
                            try { orig.onItemClick(p, v, pos, id); } catch (Throwable ignored) {}
                        }
                        sHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (sSavedShapeWidth > 0f) applyGeometryWidth(activity, sSavedShapeWidth);
                                previewDot.invalidate();
                            }
                        }, 45);
                    }
                });
            }

            // RelativeLayout에 슬라이더 바 삽입 (도형 그리드 아래, 색상 그리드 위)
            android.widget.RelativeLayout.LayoutParams barLp = new android.widget.RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.round(32f * density));
            barLp.addRule(android.widget.RelativeLayout.RIGHT_OF, leftRgId);
            barLp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_END);
            barLp.addRule(android.widget.RelativeLayout.ABOVE, colorGrid.getId());
            barLp.leftMargin = Math.round(20f * density);
            barLp.rightMargin = Math.round(16f * density);
            barLp.topMargin = Math.round(2f * density);
            barLp.bottomMargin = Math.round(2f * density);
            parent.addView(bar, barLp);

            if (planeGrid != null && planeGrid.getLayoutParams() instanceof android.widget.RelativeLayout.LayoutParams) {
                ((android.widget.RelativeLayout.LayoutParams) planeGrid.getLayoutParams())
                        .addRule(android.widget.RelativeLayout.ABOVE, barId);
            }
            if (solidGrid != null && solidGrid.getLayoutParams() instanceof android.widget.RelativeLayout.LayoutParams) {
                ((android.widget.RelativeLayout.LayoutParams) solidGrid.getLayoutParams())
                        .addRule(android.widget.RelativeLayout.ABOVE, barId);
            }
            if (colorGrid.getLayoutParams() instanceof android.widget.RelativeLayout.LayoutParams) {
                ((android.widget.RelativeLayout.LayoutParams) colorGrid.getLayoutParams()).topMargin = Math.round(4f * density);
            }
            parent.requestLayout();
        } catch (Throwable t) {
            Log.w(TAG, "configureShapePopupWidthSlider failed", t);
        }
    }

    private static float readGeometryWidth(Activity activity) {
        try {
            Field fTb = activity.getClass().getDeclaredField("e");
            fTb.setAccessible(true);
            Object tb = fTb.get(activity);
            if (tb != null) {
                Object geom = tb.getClass().getMethod("getGeometry").invoke(tb);
                if (geom != null) {
                    return (Float) geom.getClass().getMethod("getWidth").invoke(geom);
                }
            }
        } catch (Throwable ignored) {}
        return 4f;
    }

    private static int readGeometryColor(Activity activity) {
        try {
            Field fTb = activity.getClass().getDeclaredField("e");
            fTb.setAccessible(true);
            Object tb = fTb.get(activity);
            if (tb != null) {
                Object geom = tb.getClass().getMethod("getGeometry").invoke(tb);
                if (geom != null) {
                    return (Integer) geom.getClass().getMethod("getColor").invoke(geom);
                }
            }
        } catch (Throwable ignored) {}
        return 0xFFFFFFFF;
    }

    private static void applyGeometryWidth(Activity activity, float width) {
        try {
            Field fTb = activity.getClass().getDeclaredField("e");
            fTb.setAccessible(true);
            Object tb = fTb.get(activity);
            if (tb != null) {
                // ToolbarView.f (m4 ShapeWindow)의 실제 가변 필드 m4.u (Geometry)에 직접 기록!
                boolean setOnField = false;
                try {
                    Field fShapeWin = tb.getClass().getDeclaredField("f");
                    fShapeWin.setAccessible(true);
                    Object shapeWin = fShapeWin.get(tb);
                    if (shapeWin != null) {
                        Field fGeom = shapeWin.getClass().getDeclaredField("u");
                        fGeom.setAccessible(true);
                        Object geom = fGeom.get(shapeWin);
                        if (geom != null) {
                            geom.getClass().getMethod("setWidth", float.class).invoke(geom, width);
                            setOnField = true;
                        }
                    }
                } catch (Throwable ignored) {}
                if (!setOnField) {
                    Object geom = tb.getClass().getMethod("getGeometry").invoke(tb);
                    if (geom != null) {
                        geom.getClass().getMethod("setWidth", float.class).invoke(geom, width);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyGeometryWidth failed", t);
        }
    }

    private static int sSavedNormalPenProgress = -1;

    /**
     * 펜 팝업의 4번째 버튼(smart)을 순정 형광펜 아이콘(explore_edu_pen_highlight)과
     * 반투명 형광펜 모드로 교체하고, 1~3번 펜 선택 시 자동으로 일반 불투명 모드로 복귀합니다.
     */
    private static void configurePenPopupHighlighter(final Activity activity, final ViewGroup rg) {
        if (activity == null || rg == null) return;
        try {
            View pencilBtn = null;
            View smartBtn = null;
            for (int i = 0; i < rg.getChildCount(); i++) {
                View c = rg.getChildAt(i);
                String cname = "";
                if (c.getId() != View.NO_ID) {
                    try { cname = c.getResources().getResourceEntryName(c.getId()); } catch (Throwable ignored) {}
                }
                if ("pencil".equals(cname)) pencilBtn = c;
                else if ("smart".equals(cname)) smartBtn = c;
                else if ("recognize_text".equals(cname)) hideViewCompletely(c);
            }
            if (smartBtn == null) return;

            final View fSmart = smartBtn;
            final android.widget.RadioGroup fRg = (rg instanceof android.widget.RadioGroup)
                    ? (android.widget.RadioGroup) rg : null;
            final View popupRoot = rg.getRootView();
            final android.widget.SeekBar fSb = (popupRoot != null) ? findFirstSeekBar(popupRoot) : null;

            final int activeTab = detectActivePenTab(popupRoot);
            QuickColorBarManager.getInstance().setActivePopupTab(activeTab);
            syncPenPopupVisualsForTab(activity, activeTab, fRg, fSmart, fSb);
            sHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    int tab = detectActivePenTab(popupRoot);
                    QuickColorBarManager.getInstance().setActivePopupTab(tab);
                    syncPenPopupVisualsForTab(activity, tab, fRg, fSmart, fSb);
                }
            }, 45);

            // 좌측 가는펜/굵은펜 탭(pen_one/pen_two/finger), 크기 슬라이더(SeekBar), 색상표(GridView) 연동
            if (popupRoot instanceof ViewGroup) {
                hookDualPenTabs(activity, (ViewGroup) popupRoot, fRg, fSmart, fSb);
                hookPopupSeekBarAndColors(activity, (ViewGroup) popupRoot, fRg, fSmart);
            }

            // 1~3번 일반 펜(pencil/brush/pressure) 터치 시 현재 탭만 일반 펜(1x 굵기)으로 복귀 (다른 탭 설정 보존!)
            for (int i = 0; i < rg.getChildCount(); i++) {
                final View c = rg.getChildAt(i);
                if (c == fSmart) continue;
                String cname = "";
                if (c.getId() != View.NO_ID) {
                    try { cname = c.getResources().getResourceEntryName(c.getId()); } catch (Throwable ignored) {}
                }
                final String restoreType;
                if ("pencil".equals(cname)) restoreType = "PENCIL";
                else if ("brush".equals(cname)) restoreType = "BRUSH";
                else if ("pressure".equals(cname)) restoreType = "PRESSURE_PENCIL";
                else restoreType = null;

                if (restoreType != null) {
                    c.setOnTouchListener(new View.OnTouchListener() {
                        @Override
                        public boolean onTouch(View v, android.view.MotionEvent event) {
                            if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                                int tab = detectActivePenTab(popupRoot);
                                if (QuickColorBarManager.getInstance().isTabHighlighter(tab)) {
                                    QuickColorBarManager.getInstance().exitHighlighterForTab(activity, tab, restoreType);
                                    fSmart.setTag("gnfl_hl_off");
                                    updateHighlighterButtonIcon(activity, fSmart, false);
                                }
                            }
                            return false; // 순정 RadioButton 선택 동작은 그대로 진행
                        }
                    });
                }
            }

            // 4번째 버튼: 현재 선택된 탭(가는펜 A 또는 굵은펜 B)만 독립적으로 형광펜(3배 굵기 + 반투명) 전환!
            fSmart.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, android.view.MotionEvent event) {
                    if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                        final int tab = detectActivePenTab(popupRoot);
                        int curProg = (fSb != null) ? fSb.getProgress() : -1;
                        int maxProg = (fSb != null) ? fSb.getMax() : 100;
                        if (fRg != null && fRg.getCheckedRadioButtonId() != -1) fRg.clearCheck();
                        QuickColorBarManager.getInstance().enterHighlighterForTab(activity, tab, curProg, maxProg);
                        syncPenPopupVisualsForTab(activity, tab, fRg, fSmart, fSb);
                        String tabLabel = (tab == 2) ? "굵은펜" : (tab == 1 ? "가는펜" : "펜");
                        ToolbarFitHelper.showTopToast(activity, "🖍️ " + tabLabel + " 형광펜 모드 (3배 굵기)");
                    }
                    return true; // 순정 smart 모드로 넘어가지 않도록 소비
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "configurePenPopupHighlighter failed", t);
        }
    }

    /**
     * 현재 활성 탭이 형광펜이면:
     * 1) 기본펜 RadioGroup 체크 해제 (중복 선택 방지)
     * 2) 4번 형광펜 아이콘 강조
     * 3) SeekBar 눈금을 저장된 1배 위치로 복원 (3배 값으로 인해 최대치로 튀는 현상 완벽 해결!)
     */
    private static void syncPenPopupVisualsForTab(
            Activity activity,
            int tab,
            android.widget.RadioGroup rg,
            View smartBtn,
            android.widget.SeekBar sb) {
        boolean isHl = QuickColorBarManager.getInstance().isTabHighlighter(tab);
        String desiredTag = isHl ? "gnfl_hl_on" : "gnfl_hl_off";
        if (smartBtn != null && !desiredTag.equals(smartBtn.getTag())) {
            smartBtn.setTag(desiredTag);
            updateHighlighterButtonIcon(activity, smartBtn, isHl);
        }
        if (isHl) {
            if (rg != null && rg.getCheckedRadioButtonId() != -1) {
                rg.clearCheck();
            }
            int savedProg = QuickColorBarManager.getInstance().getSavedSliderProgress(tab);
            if (sb != null && savedProg >= 0 && sb.getProgress() != savedProg) {
                sb.setProgress(savedProg);
            }
            QuickColorBarManager.getInstance().reapplyHighlighterForTab(activity, tab);
        }
    }

    private static int detectActivePenTab(View root) {
        if (root == null) return QuickColorBarManager.getInstance().getActivePopupTab();
        int found = findCheckedTabIndex(root);
        return (found >= 0) ? found : QuickColorBarManager.getInstance().getActivePopupTab();
    }

    private static int findCheckedTabIndex(View v) {
        if (v == null) return -1;
        if (v.getId() != View.NO_ID) {
            try {
                String name = v.getResources().getResourceEntryName(v.getId());
                boolean checked = false;
                if (v instanceof android.widget.Checkable) {
                    checked = ((android.widget.Checkable) v).isChecked();
                } else {
                    checked = v.isSelected();
                }
                if (checked) {
                    if ("finger".equals(name)) return 0;
                    if ("pen_one".equals(name)) return 1;
                    if ("pen_two".equals(name)) return 2;
                }
            } catch (Throwable ignored) {}
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                int r = findCheckedTabIndex(vg.getChildAt(i));
                if (r >= 0) return r;
            }
        }
        return -1;
    }

    private static void hookDualPenTabs(
            final Activity activity,
            ViewGroup root,
            final android.widget.RadioGroup rg,
            final View smartBtn,
            final android.widget.SeekBar sb) {
        if (root == null) return;
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            if (c.getId() != View.NO_ID) {
                try {
                    String name = c.getResources().getResourceEntryName(c.getId());
                    final int tabIdx = "finger".equals(name) ? 0 : ("pen_one".equals(name) ? 1 : ("pen_two".equals(name) ? 2 : -1));
                    if (tabIdx >= 0) {
                        c.setOnTouchListener(new View.OnTouchListener() {
                            @Override
                            public boolean onTouch(View v, android.view.MotionEvent event) {
                                if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                                    QuickColorBarManager.getInstance().setActivePopupTab(tabIdx);
                                    sHandler.postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            syncPenPopupVisualsForTab(activity, tabIdx, rg, smartBtn, sb);
                                        }
                                    }, 45);
                                }
                                return false;
                            }
                        });
                    }
                } catch (Throwable ignored) {}
            }
            if (c instanceof ViewGroup) {
                hookDualPenTabs(activity, (ViewGroup) c, rg, smartBtn, sb);
            }
        }
    }

    /**
     * 펜 팝업 내부의 크기 슬라이더(SeekBar)와 색상표(GridView)를 형광펜 모드와 연동합니다:
     * - SeekBar 조절 시: 1배 슬라이더 위치는 그대로 표시하고, 형광펜 탭이면 실제 필기만 3배 적용!
     * - GridView 색상 선택 시: 형광펜 탭이면 반투명 HIGHLIGHTER + 3배 굵기 유지
     */
    private static void hookPopupSeekBarAndColors(
            final Activity activity,
            final ViewGroup popupRoot,
            final android.widget.RadioGroup rg,
            final View smartBtn) {
        try {
            final android.widget.SeekBar sb = findFirstSeekBar(popupRoot);
            if (sb != null) {
                sb.setOnTouchListener(new View.OnTouchListener() {
                    @Override
                    public boolean onTouch(View v, android.view.MotionEvent event) {
                        int act = event.getActionMasked();
                        if (act == android.view.MotionEvent.ACTION_MOVE || act == android.view.MotionEvent.ACTION_UP) {
                            sHandler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    int tab = detectActivePenTab(popupRoot);
                                    QuickColorBarManager.getInstance().onPopupSeekBarChanged(
                                            activity, tab, sb.getProgress(), sb.getMax());
                                    if (QuickColorBarManager.getInstance().isTabHighlighter(tab)) {
                                        if (rg != null && rg.getCheckedRadioButtonId() != -1) rg.clearCheck();
                                        if (smartBtn != null && !"gnfl_hl_on".equals(smartBtn.getTag())) {
                                            smartBtn.setTag("gnfl_hl_on");
                                            updateHighlighterButtonIcon(activity, smartBtn, true);
                                        }
                                    }
                                }
                            }, (act == android.view.MotionEvent.ACTION_UP) ? 35 : 15);
                        }
                        return false; // 순정 SeekBar 드래그 동작은 그대로 진행
                    }
                });
            }

            final android.widget.GridView gv = findFirstGridView(popupRoot);
            if (gv != null && !"gnfl_hl_gv_hooked".equals(gv.getTag())) {
                gv.setTag("gnfl_hl_gv_hooked");
                final android.widget.AdapterView.OnItemClickListener origListener = gv.getOnItemClickListener();
                gv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
                    @Override
                    public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                        if (origListener != null) {
                            try { origListener.onItemClick(parent, view, position, id); } catch (Throwable ignored) {}
                        }
                        final int directColor = extractColorFromGridItem(gv, position);
                        Runnable syncTask = new Runnable() {
                            @Override
                            public void run() {
                                int tab = detectActivePenTab(popupRoot);
                                QuickColorBarManager.getInstance().setActivePopupTab(tab);
                                if (directColor != 0) {
                                    QuickColorBarManager.getInstance().applyPopupPickedColor(activity, directColor);
                                } else {
                                    QuickColorBarManager.getInstance().onPopupColorPicked(activity);
                                }
                                syncPenPopupVisualsForTab(activity, tab, rg, smartBtn, sb);
                            }
                        };
                        sHandler.postDelayed(syncTask, 40);
                        sHandler.postDelayed(syncTask, 120);
                    }
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "hookPopupSeekBarAndColors failed", t);
        }
    }

    private static final int[] POPUP_GRID_FALLBACK_COLORS = new int[] {
            0xFFFFFFFF, 0xFF000000, 0xFFD91E18, 0xFFFFEB00, 0xFFFFA500, 0xFFF26522,
            0xFF9BE64B, 0xFF2ECC71, 0xFF00A8F3, 0xFFD81B60, 0xFF7E3AF2
    };

    private static int extractColorFromGridItem(android.widget.GridView gv, int pos) {
        if (gv == null || pos < 0) return 0;
        try {
            Object item = gv.getItemAtPosition(pos);
            if (item instanceof Integer) {
                int c = (Integer) item;
                if ((c & 0xFF000000) == 0xFF000000) return c;
                if ((c & 0xFF000000) == 0x7F000000) {
                    return gv.getResources().getColor(c);
                }
            }
            if (item != null) {
                for (Field f : item.getClass().getDeclaredFields()) {
                    if (f.getType() == int.class) {
                        f.setAccessible(true);
                        int v = f.getInt(item);
                        if ((v & 0xFF000000) == 0xFF000000) return v;
                        if ((v & 0xFF000000) == 0x7F000000) {
                            try { return gv.getResources().getColor(v); } catch (Throwable ignored) {}
                        }
                    }
                }
            }
            if (pos < POPUP_GRID_FALLBACK_COLORS.length) {
                return POPUP_GRID_FALLBACK_COLORS[pos];
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static android.widget.GridView findFirstGridView(View v) {
        if (v instanceof android.widget.GridView) return (android.widget.GridView) v;
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                android.widget.GridView gv = findFirstGridView(vg.getChildAt(i));
                if (gv != null) return gv;
            }
        }
        return null;
    }

    private static void updateHighlighterButtonIcon(Activity activity, View btn, boolean selected) {
        if (activity == null || btn == null) return;
        try {
            String resName = selected ? "explore_edu_pen_highlight_selected" : "explore_edu_pen_highlight_normal";
            int resId = activity.getResources().getIdentifier(resName, "drawable", "com.seewo.easinote");
            if (resId == 0) {
                resId = activity.getResources().getIdentifier(resName, "drawable", activity.getPackageName());
            }
            if (resId != 0) {
                btn.setBackgroundResource(resId);
            }
        } catch (Throwable ignored) {}
    }

    private static android.widget.SeekBar findFirstSeekBar(View v) {
        if (v instanceof android.widget.SeekBar) return (android.widget.SeekBar) v;
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                android.widget.SeekBar sb = findFirstSeekBar(vg.getChildAt(i));
                if (sb != null) return sb;
            }
        }
        return null;
    }

    private static void boostSeekBarForHighlighter(View popupRoot) {
        try {
            android.widget.SeekBar sb = findFirstSeekBar(popupRoot);
            if (sb == null) return;
            int max = sb.getMax();
            if (max <= 0) return;
            if (sSavedNormalPenProgress < 0) {
                sSavedNormalPenProgress = sb.getProgress();
            }
            int target = Math.max(sb.getProgress(), (int) (max * 0.72f));
            sb.setProgress(target);
        } catch (Throwable ignored) {}
    }

    private static void restoreNormalSeekBar(View popupRoot) {
        try {
            if (sSavedNormalPenProgress < 0) return;
            android.widget.SeekBar sb = findFirstSeekBar(popupRoot);
            if (sb != null) {
                sb.setProgress(sSavedNormalPenProgress);
            }
            sSavedNormalPenProgress = -1;
        } catch (Throwable ignored) {}
    }
}

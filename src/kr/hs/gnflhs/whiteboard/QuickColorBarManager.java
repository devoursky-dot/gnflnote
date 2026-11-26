package kr.hs.gnflhs.whiteboard;

import android.app.Activity;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * GNFL NOTE V1.4 - 배경색 고대비 추천 5색 퀵 팔레트 & 가는펜/굵은펜 색상 통합 관리자
 *
 * 1. 하단 툴바 '이동' 버튼 바로 오른쪽에 5가지 펜 색상 원형 버튼을 배치
 * 2. 현재 페이지의 배경색(또는 PDF 페이지 밝기)을 실시간 분석해
 *    WCAG 고대비 기준으로 가장 잘 보이는 5가지 펜 색상을 자동 추천
 * 3. 색상을 선택하면 가는펜(stateHolder.j)과 굵은펜(stateHolder.l) 모두 동일 색상으로 즉시 통일
 * 4. 현재 선택된 색상은 외곽 강조 링으로 선명하게 표시
 * 5. 배경색이 크게 바뀔 때(예: 초록 칠판 -> 흰색 PDF 올가미 페이지) 현재 펜 색이 안 보이면
 *    자동으로 1번 기본 고대비 색상으로 전환
 */
public class QuickColorBarManager {
    private static final String TAG = "QuickColorBarManager";
    private static final int SLOT_COUNT = 7;
    private static QuickColorBarManager sInstance;

    // 어두운 배경(초록 칠판, 검정, 남색 등)용 고대비 7계열 × 2단계 (총 14색)
    private static final int[] DARK_BG_PRIMARY = new int[] {
            0xFFFFFFFF, // 1. 순백
            0xFFFDE047, // 2. 밝은 노랑
            0xFFFB923C, // 3. 오렌지
            0xFFFB7185, // 4. 코랄 레드
            0xFF4ADE80, // 5. 민트 그린
            0xFF38BDF8, // 6. 하늘 시안
            0xFFC084FC  // 7. 라벤더 퍼플
    };
    private static final int[] DARK_BG_ALT = new int[] {
            0xFFCBD5E1, // 1. 은회색
            0xFFFEF08A, // 2. 레몬 노랑
            0xFFFDBA74, // 3. 살구 오렌지
            0xFFFBCFE8, // 4. 연핑크
            0xFFD9F99D, // 5. 연라임
            0xFFBAE6FD, // 6. 파스텔 블루
            0xFFF472B6  // 7. 마젠타 핑크
    };

    // 밝은 배경(흰색 PDF, 미색/크림 종이 등)용 고대비 7계열 × 2단계 (총 14색)
    private static final int[] LIGHT_BG_PRIMARY = new int[] {
            0xFF111827, // 1. 진한 검정
            0xFFDC2626, // 2. 진한 빨강
            0xFFEA580C, // 3. 선명한 주황
            0xFF15803D, // 4. 진한 초록
            0xFF1D4ED8, // 5. 진한 파랑
            0xFF6D28D9, // 6. 진한 보라
            0xFFDB2777  // 7. 선명한 분홍
    };
    private static final int[] LIGHT_BG_ALT = new int[] {
            0xFF6B7280, // 1. 중간 회색
            0xFFF43F5E, // 2. 다홍/로즈
            0xFFB45309, // 3. 황토/갈색
            0xFF0D9488, // 4. 청록
            0xFF0284C7, // 5. 하늘색
            0xFF9333EA, // 6. 밝은 보라
            0xFFBE185D  // 7. 자주
    };

    // 유색 배경 충돌 시 대체할 고대비 후보색
    private static final int[] FALLBACK_CANDIDATES = new int[] {
            0xFFFFFFFF, 0xFF111827, 0xFFFDE047, 0xFFDC2626,
            0xFF2563EB, 0xFF06B6D4, 0xFFEC4899, 0xFF15803D, 0xFFEA580C,
            0xFF6D28D9, 0xFFB45309
    };

    private Activity mCurrentActivity;
    private LinearLayout mColorBar;
    private final View[] mSwatches = new View[SLOT_COUNT];
    private final int[] mPrimaryPalette = new int[SLOT_COUNT];
    private final int[] mAltPalette = new int[SLOT_COUNT];
    private final boolean[] mUseAlt = new boolean[SLOT_COUNT];
    private int mSelectedSlot = 0;
    private int mSelectedColor = 0xFFFFFFFF;
    private int mLastBgColor = Integer.MIN_VALUE;

    // 손가락(0), 가는펜 A(1), 굵은펜 B(2) 각각 독립적으로 형광펜 여부 및 1배 슬라이더 위치 관리!
    private final boolean[] mPenHighlighter = new boolean[] { false, false, false };
    private final int[] mPenBaseWidth = new int[] { 8, 8, 12 };
    private final int[] mPenSliderProgress = new int[] { -1, -1, -1 };
    private final int[] mPenSliderMax = new int[] { 100, 100, 100 };
    private int mActivePopupTab = 1;

    public boolean isHighlighterMode() {
        return mPenHighlighter[0] || mPenHighlighter[1] || mPenHighlighter[2];
    }

    public boolean isTabHighlighter(int tabIdx) {
        int idx = clampTab(tabIdx);
        return mPenHighlighter[idx];
    }

    public int getActivePopupTab() {
        return mActivePopupTab;
    }

    public void setActivePopupTab(int tabIdx) {
        mActivePopupTab = clampTab(tabIdx);
    }

    public int getSavedSliderProgress(int tabIdx) {
        return mPenSliderProgress[clampTab(tabIdx)];
    }

    private static int clampTab(int tabIdx) {
        if (tabIdx < 0) return 0;
        if (tabIdx > 2) return 2;
        return tabIdx;
    }

    private int getHighlighterWidthFor(int tabIdx) {
        int base = Math.max(4, Math.min(34, mPenBaseWidth[clampTab(tabIdx)]));
        return Math.max(15, Math.min(108, base * 3));
    }

    /**
     * 현재 선택된 탭(가는펜 A / 굵은펜 B / 손가락)만 독립적으로 형광펜(3배 굵기 + 반투명)으로 전환합니다.
     */
    public void enterHighlighterForTab(Activity activity, int tabIdx, int curProgress, int maxProgress) {
        if (activity == null) return;
        int idx = clampTab(tabIdx);
        mActivePopupTab = idx;
        boolean wasHl = mPenHighlighter[idx];
        mPenHighlighter[idx] = true;
        // 가는펜(1)이 형광펜이면 기본 터치(0)도 함께 맞춰 단일 펜/손가락 필기 일관성 유지
        if (idx == 1) mPenHighlighter[0] = true;

        try {
            if (maxProgress > 0 && curProgress >= 0 && !wasHl) {
                mPenSliderProgress[idx] = curProgress;
                mPenSliderMax[idx] = maxProgress;
                float ratio = Math.max(0f, Math.min(1f, (float) curProgress / (float) maxProgress));
                mPenBaseWidth[idx] = Math.round(4f + ratio * 26f);
                if (idx == 1) {
                    mPenSliderProgress[0] = curProgress;
                    mPenSliderMax[0] = maxProgress;
                    mPenBaseWidth[0] = mPenBaseWidth[idx];
                }
            } else if (mPenSliderProgress[idx] < 0) {
                Class<?> penMgrCls = Class.forName("com.seewo.easinote.n4.g");
                Object penMgr = penMgrCls.getMethod("b").invoke(null);
                int w = (Integer) penMgrCls.getMethod("d", int.class).invoke(penMgr, idx);
                mPenBaseWidth[idx] = (w > 0 && w <= 34) ? w : 10;
                if (idx == 1) mPenBaseWidth[0] = mPenBaseWidth[idx];
            }
            applyTabPenToEngine(activity, idx, true);
            if (idx == 1) applyTabPenToEngine(activity, 0, false);
            Log.i(TAG, "Tab " + idx + " -> HIGHLIGHTER (base=" + mPenBaseWidth[idx] + ", 3x=" + getHighlighterWidthFor(idx)
                    + ", slider=" + mPenSliderProgress[idx] + "/" + mPenSliderMax[idx] + ")");
        } catch (Throwable t) {
            Log.w(TAG, "enterHighlighterForTab failed", t);
        }
    }

    /**
     * 현재 탭만 일반 펜(PENCIL / BRUSH / PRESSURE_PENCIL, 1배 굵기)으로 복귀합니다.
     * 다른 탭(예: 굵은펜)의 형광펜 설정은 그대로 보존됩니다!
     */
    public void exitHighlighterForTab(Activity activity, int tabIdx, String restoreTypeName) {
        if (activity == null) return;
        int idx = clampTab(tabIdx);
        mActivePopupTab = idx;
        boolean wasHl = mPenHighlighter[idx];
        mPenHighlighter[idx] = false;
        if (idx == 1) mPenHighlighter[0] = false;
        if (!wasHl) return;

        try {
            Class<?> penMgrCls = Class.forName("com.seewo.easinote.n4.g");
            Object penMgr = penMgrCls.getMethod("b").invoke(null);
            Method mSetWidth = penMgrCls.getMethod("i", int.class, int.class);
            Method mSetTypeStr = penMgrCls.getMethod("j", int.class, String.class);

            String safeType = (restoreTypeName != null && !restoreTypeName.isEmpty()) ? restoreTypeName : "PENCIL";
            Class<?> toolTypeCls = Class.forName("com.seewo.libshapes.ToolType");
            Object restoreEnum = Enum.valueOf((Class<Enum>) toolTypeCls, safeType);

            int restoreW = Math.max(4, Math.min(34, mPenBaseWidth[idx]));
            mSetTypeStr.invoke(penMgr, idx, safeType);
            mSetWidth.invoke(penMgr, idx, restoreW);
            if (idx == 1) {
                mSetTypeStr.invoke(penMgr, 0, safeType);
                mSetWidth.invoke(penMgr, 0, restoreW);
            }
            applyToolbarPenTypeAndWidth(activity, restoreEnum, mSelectedColor, (float) restoreW, idx);
            Log.i(TAG, "Tab " + idx + " exited Highlighter -> " + safeType + " (1x width=" + restoreW + ")");
        } catch (Throwable t) {
            Log.w(TAG, "exitHighlighterForTab failed", t);
        }
    }

    private void applyTabPenToEngine(Activity activity, int tabIdx, boolean notifyEngineCallback) {
        int idx = clampTab(tabIdx);
        if (!mPenHighlighter[idx]) return;
        try {
            Class<?> penMgrCls = Class.forName("com.seewo.easinote.n4.g");
            Object penMgr = penMgrCls.getMethod("b").invoke(null);
            Method mSetWidth = penMgrCls.getMethod("i", int.class, int.class);
            Method mSetTypeStr = penMgrCls.getMethod("j", int.class, String.class);
            Method mSetColor = penMgrCls.getMethod("h", int.class, int.class);

            int hlW = getHighlighterWidthFor(idx);
            mSetTypeStr.invoke(penMgr, idx, "HIGHLIGHTER");
            mSetWidth.invoke(penMgr, idx, hlW);
            mSetColor.invoke(penMgr, idx, mSelectedColor);

            if (idx == mActivePopupTab && notifyEngineCallback) {
                Class<?> toolTypeCls = Class.forName("com.seewo.libshapes.ToolType");
                Object hlEnum = Enum.valueOf((Class<Enum>) toolTypeCls, "HIGHLIGHTER");
                applyToolbarPenTypeAndWidth(activity, hlEnum, mSelectedColor, (float) hlW, idx);
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyTabPenToEngine failed", t);
        }
    }

    public void reapplyHighlighterForTab(Activity activity, int tabIdx) {
        int idx = clampTab(tabIdx);
        if (!mPenHighlighter[idx]) return;
        applyTabPenToEngine(activity, idx, true);
        if (idx == 1) applyTabPenToEngine(activity, 0, false);
    }

    /**
     * 펜 팝업의 크기 슬라이더(SeekBar) 조절 시:
     * - 슬라이더 자체의 1배 위치(progress)를 그대로 기억해 팝업을 다시 열어도 최대치로 튀지 않음!
     * - 실제 칠판 필기 굵기만 해당 탭에 대해 정확히 3배(base1x * 3)로 적용!
     */
    public void onPopupSeekBarChanged(Activity activity, int tabIdx, int progress, int max) {
        if (activity == null) return;
        int idx = clampTab(tabIdx);
        mPenSliderProgress[idx] = progress;
        if (max > 0) mPenSliderMax[idx] = max;
        float ratio = (max > 0) ? Math.max(0f, Math.min(1f, (float) progress / (float) max)) : 0.35f;
        int base1x = Math.round(4f + ratio * 26f); // 4px ~ 30px (1배)
        mPenBaseWidth[idx] = base1x;
        if (idx == 1) {
            mPenSliderProgress[0] = progress;
            if (max > 0) mPenSliderMax[0] = max;
            mPenBaseWidth[0] = base1x;
        }
        if (mPenHighlighter[idx]) {
            applyTabPenToEngine(activity, idx, true);
            if (idx == 1) applyTabPenToEngine(activity, 0, false);
            Log.i(TAG, "Tab " + idx + " Highlighter SeekBar=" + progress + "/" + max + " -> 3x width=" + getHighlighterWidthFor(idx));
        }
    }

    /**
     * 펜 팝업 안의 색상표(GridView)에서 특정 색상(int)을 선택했을 때 즉시 반영합니다.
     */
    public void applyPopupPickedColor(Activity activity, int color) {
        if (activity == null || color == 0) return;
        mSelectedColor = (color & 0x00FFFFFF) | 0xFF000000;
        if (mPenHighlighter[mActivePopupTab]) {
            applyTabPenToEngine(activity, mActivePopupTab, true);
            if (mActivePopupTab == 1) applyTabPenToEngine(activity, 0, false);
        } else {
            applyDualPenColor(activity, mSelectedColor, false);
        }
    }

    /**
     * 펜 팝업 색상표/컬러휠 선택 후 칠판 터치 직전(ACTION_DOWN) 동기화:
     * 현재 활성 탭이 형광펜이면 반투명 ToolType.HIGHLIGHTER + 3배 굵기를 유지합니다.
     */
    public void onPopupColorPicked(Activity activity) {
        if (activity == null) return;
        try {
            int picked = 0;
            int tbColor = readCurrentPenColor(activity);
            if (tbColor != 0 && ((tbColor & 0xFFFFFF) != (mSelectedColor & 0xFFFFFF))) {
                picked = tbColor;
            } else {
                Class<?> penMgrCls = Class.forName("com.seewo.easinote.n4.g");
                Object penMgr = penMgrCls.getMethod("b").invoke(null);
                Method mGetColor = penMgrCls.getMethod("c", int.class);
                for (int p = 0; p < 3; p++) {
                    int c = (Integer) mGetColor.invoke(penMgr, p);
                    if (c != 0 && ((c & 0xFFFFFF) != (mSelectedColor & 0xFFFFFF))) {
                        picked = c;
                        break;
                    }
                }
            }
            if (picked != 0) {
                mSelectedColor = (picked & 0x00FFFFFF) | 0xFF000000;
            }
            for (int p = 0; p < 3; p++) {
                if (mPenHighlighter[p]) {
                    applyTabPenToEngine(activity, p, (p == mActivePopupTab));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "onPopupColorPicked failed", t);
        }
    }

    /**
     * ToolbarView.O(...)를 호출하지 않고 (O는 d4.s1()->d4.l1(2)로 굵은펜 탭을 강제 점프시키고 팝업을 번쩍이게 함)
     * 실제 필기 엔진 콜백(d4.p.e(ToolType), d4.p.l(tabIdx))과 Pen 객체만 직접 갱신합니다.
     */
    private void applyToolbarPenTypeAndWidth(Activity activity, Object toolTypeEnum, int color, float width, int tabIdx) {
        try {
            Field fToolbar = activity.getClass().getDeclaredField("e");
            fToolbar.setAccessible(true);
            Object toolbar = fToolbar.get(activity);
            if (toolbar != null) {
                Class<?> toolTypeCls = Class.forName("com.seewo.libshapes.ToolType");
                Object pen = toolbar.getClass().getMethod("getPen").invoke(toolbar);
                if (pen != null) {
                    if (toolTypeEnum != null) {
                        pen.getClass().getMethod("setType", toolTypeCls).invoke(pen, toolTypeEnum);
                    }
                    if (width > 0f) {
                        pen.getClass().getMethod("setWidth", float.class).invoke(pen, width);
                    }
                    if (color != 0) {
                        try { pen.getClass().getMethod("setColor", int.class).invoke(pen, color); } catch (Throwable ignored) {}
                    }
                }
                if (toolTypeEnum != null) {
                    try {
                        Field fPenWindow = toolbar.getClass().getDeclaredField("e");
                        fPenWindow.setAccessible(true);
                        Object penWindow = fPenWindow.get(toolbar);
                        if (penWindow != null) {
                            Field fCallback = penWindow.getClass().getDeclaredField("p");
                            fCallback.setAccessible(true);
                            Object cb = fCallback.get(penWindow);
                            if (cb != null) {
                                cb.getClass().getMethod("e", toolTypeCls).invoke(cb, toolTypeEnum);
                                cb.getClass().getMethod("l", int.class).invoke(cb, clampTab(tabIdx));
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    public static synchronized QuickColorBarManager getInstance() {
        if (sInstance == null) {
            sInstance = new QuickColorBarManager();
        }
        return sInstance;
    }

    private QuickColorBarManager() {
        System.arraycopy(DARK_BG_PRIMARY, 0, mPrimaryPalette, 0, SLOT_COUNT);
        System.arraycopy(DARK_BG_ALT, 0, mAltPalette, 0, SLOT_COUNT);
    }

    private int getSlotColor(int idx) {
        return mUseAlt[idx] ? mAltPalette[idx] : mPrimaryPalette[idx];
    }

    /**
     * '이동' 버튼 바로 오른쪽에 5색 퀵 팔레트를 주입하고 현재 페이지 배경색과 동기화합니다.
     */
    public void ensureAndSync(final Activity activity) {
        if (activity == null) return;
        ensureColorBar(activity);
        syncWithCurrentPage(activity);
    }

    private void ensureColorBar(final Activity activity) {
        if (mCurrentActivity != activity) {
            mCurrentActivity = activity;
            mColorBar = null;
            mLastBgColor = Integer.MIN_VALUE;
        }
        if (mColorBar != null && mColorBar.getParent() != null) return;
        try {
            View moveView = ToolbarFitHelper.findViewByIdName(activity, "explore_edu_toolbar_move");
            if (moveView == null || !(moveView.getParent() instanceof ViewGroup)) return;
            ViewGroup parent = (ViewGroup) moveView.getParent();

            View existing = parent.findViewWithTag("gnfl_quick_color_bar");
            if (existing instanceof LinearLayout) {
                mColorBar = (LinearLayout) existing;
                for (int i = 0; i < SLOT_COUNT && i < mColorBar.getChildCount(); i++) {
                    mSwatches[i] = mColorBar.getChildAt(i);
                }
                return;
            }

            LinearLayout bar = new LinearLayout(activity);
            bar.setTag("gnfl_quick_color_bar");
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            int padH = dp(activity, 4);
            bar.setPadding(padH, 0, padH, 0);

            int swatchSize = dp(activity, 32);
            int gap = dp(activity, 3);

            for (int i = 0; i < SLOT_COUNT; i++) {
                final int idx = i;
                View dot = new View(activity);
                dot.setClickable(true);
                dot.setFocusable(true);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(swatchSize, swatchSize);
                dlp.gravity = Gravity.CENTER_VERTICAL;
                dlp.setMargins(gap, 0, gap, 0);
                dot.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (mSelectedSlot == idx) {
                            // 이미 선택된 버튼을 다시 누르면 같은 계열의 진한색 <-> 연한색 토글!
                            mUseAlt[idx] = !mUseAlt[idx];
                        } else {
                            mSelectedSlot = idx;
                        }
                        int chosen = getSlotColor(idx);
                        applyDualPenColor(activity, chosen, true);
                    }
                });
                bar.addView(dot, dlp);
                mSwatches[i] = dot;
            }

            int moveIdx = parent.indexOfChild(moveView);
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );
            barLp.gravity = Gravity.CENTER_VERTICAL;
            parent.addView(bar, moveIdx + 1, barLp);
            mColorBar = bar;

            // 초기 펜 색상 읽기
            int curPen = readCurrentPenColor(activity);
            if (curPen != 0) mSelectedColor = curPen;
            refreshSwatchVisuals();
            Log.i(TAG, "Injected 7-color (14-shade) quick pen bar next to Move button");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to inject quick color bar", t);
        }
    }

    /**
     * 현재 페이지 배경색을 감지하여 7계열 × 2단계(총 14색) 고대비 추천 팔레트를 갱신합니다.
     * 배경색이 바뀌면 자동으로 1번 기본 고대비 색상(진한색)이 가는펜·굵은펜 모두에 자동 선택됩니다.
     */
    public void syncWithCurrentPage(Activity activity) {
        if (activity == null) return;
        try {
            int bgColor = detectEffectivePageBackgroundColor(activity);

            if (bgColor != mLastBgColor) {
                mLastBgColor = bgColor;
                buildContrastPalettes(bgColor, mPrimaryPalette, mAltPalette);
                for (int i = 0; i < SLOT_COUNT; i++) {
                    mUseAlt[i] = false;
                }
                mSelectedSlot = 0;
                // 배경색이 바뀌면 항상 대비되는 1번 색상을 가는펜·굵은펜 모두에 자동 선택!
                applyDualPenColor(activity, mPrimaryPalette[0], false);
                Log.i(TAG, String.format("Background changed to #%06X -> auto-selected slot #1 (#%06X)",
                        (bgColor & 0xFFFFFF), (mPrimaryPalette[0] & 0xFFFFFF)));
            }
        } catch (Throwable t) {
            Log.w(TAG, "syncWithCurrentPage warning", t);
        }
    }

    /**
     * 선택한 색상을 가는펜(Pen 1)과 굵은펜(Pen 2) 모두에 동일하게 적용합니다.
     */
    public void applyDualPenColor(Activity activity, int color, boolean switchToWriteMode) {
        if (activity == null) return;
        try {
            mSelectedColor = (color & 0x00FFFFFF) | 0xFF000000;
            // 순정 HighlightPencil은 오프스크린 합성(mHighlightBitmap) 자체적으로 반투명 렌더링을 수행하므로
            // 선명한 원본 RGB 색상을 그대로 전달합니다.
            int effectiveColor = mSelectedColor;

            // 1. 듀얼펜 싱글톤 관리자(com.seewo.easinote.n4.g.b())를 통해
            //    가는펜(1), 굵은펜(2), 기본/손가락(0) 모두 동일 색상으로 즉시 통일!
            try {
                Class<?> penMgrCls = Class.forName("com.seewo.easinote.n4.g");
                Object penMgr = penMgrCls.getMethod("b").invoke(null);
                if (penMgr != null) {
                    Method mSetPenColor = penMgrCls.getMethod("h", int.class, int.class);
                    mSetPenColor.invoke(penMgr, 0, effectiveColor);
                    mSetPenColor.invoke(penMgr, 1, effectiveColor);
                    mSetPenColor.invoke(penMgr, 2, effectiveColor);
                }
            } catch (Throwable t) {
                Log.w(TAG, "n4.g.h(penIdx, color) failed", t);
            }

            // 2. StateHolder의 기본 펜 색상(j) 동기화
            Field fP4a = activity.getClass().getDeclaredField("t");
            fP4a.setAccessible(true);
            Object p4a = fP4a.get(activity);
            if (p4a != null) {
                Object stateHolder = p4a.getClass().getMethod("a").invoke(p4a);
                if (stateHolder != null) {
                    try {
                        stateHolder.getClass().getMethod("j", int.class).invoke(stateHolder, effectiveColor);
                    } catch (Throwable t) {
                        Log.w(TAG, "stateHolder.j(color) failed", t);
                    }
                }
            }

            // 3. ToolbarView의 Pen 객체 색상도 동기화 (SmartPenManager 포함 100% 일치)
            try {
                Field fToolbar = activity.getClass().getDeclaredField("e");
                fToolbar.setAccessible(true);
                Object toolbar = fToolbar.get(activity);
                if (toolbar != null) {
                    Object pen = toolbar.getClass().getMethod("getPen").invoke(toolbar);
                    if (pen != null) {
                        pen.getClass().getMethod("setColor", int.class).invoke(pen, effectiveColor);
                        if (mPenHighlighter[mActivePopupTab]) {
                            Class<?> toolTypeCls = Class.forName("com.seewo.libshapes.ToolType");
                            Object hlEnum = Enum.valueOf((Class<Enum>) toolTypeCls, "HIGHLIGHTER");
                            float w = (float) getHighlighterWidthFor(mActivePopupTab);
                            applyToolbarPenTypeAndWidth(activity, hlEnum, effectiveColor, w, mActivePopupTab);
                        }
                    }
                }
            } catch (Throwable ignored) {}

            // 4. 필요 시 쓰기 모드로 자동 전환하여 바로 판서 가능하게 처리
            if (switchToWriteMode) {
                View writeBtn = ToolbarFitHelper.findViewByIdName(activity, "explore_edu_toolbar_write");
                if (writeBtn != null && !writeBtn.isSelected()) {
                    writeBtn.performClick();
                }
            }

            refreshSwatchVisuals();
            Log.i(TAG, String.format("Applied unified dual-pen color: #%06X", (color & 0xFFFFFF)));
        } catch (Throwable t) {
            Log.e(TAG, "Failed to apply dual pen color", t);
        }
    }

    private void refreshSwatchVisuals() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            View v = mSwatches[i];
            if (v == null) continue;
            int c = getSlotColor(i);
            boolean selected = (i == mSelectedSlot);
            v.setBackground(createSwatchDrawable(c, selected, mUseAlt[i]));
            v.invalidate();
        }
    }

    private Drawable createSwatchDrawable(final int fillColor, final boolean selected, final boolean isAltShade) {
        return new Drawable() {
            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                float cx = b.exactCenterX();
                float cy = b.exactCenterY();
                float rMax = Math.min(b.width(), b.height()) * 0.5f;

                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

                if (selected) {
                    // 선택 강조: 기본(진한) 톤은 청록 링, 토글(연한) 톤은 스카이블루 링으로 시각 구분
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(Math.max(3f, rMax * 0.17f));
                    p.setColor(isAltShade ? ToolbarFitHelper.COLOR_CYAN : ToolbarFitHelper.COLOR_TEAL);
                    canvas.drawCircle(cx, cy, rMax - p.getStrokeWidth() * 0.5f, p);

                    p.setStyle(Paint.Style.FILL);
                    p.setColor(fillColor);
                    float innerR = rMax * 0.72f;
                    canvas.drawCircle(cx, cy, innerR, p);

                    // 흰색/밝은 색 내부 원이 흰 툴바와 구분되도록 얇은 테두리 추가
                    if (relativeLuminance(fillColor) > 0.75) {
                        p.setStyle(Paint.Style.STROKE);
                        p.setStrokeWidth(Math.max(1.5f, rMax * 0.06f));
                        p.setColor(0xFF94A3B8);
                        canvas.drawCircle(cx, cy, innerR, p);
                    }
                } else {
                    // 미선택: 깔끔한 원형 색상 칩
                    float dotR = rMax * 0.64f;
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(fillColor);
                    canvas.drawCircle(cx, cy, dotR, p);

                    // 밝은 색은 툴바 배경에서 구분되도록 연회색 테두리
                    if (relativeLuminance(fillColor) > 0.70) {
                        p.setStyle(Paint.Style.STROKE);
                        p.setStrokeWidth(Math.max(1.5f, rMax * 0.08f));
                        p.setColor(0xFF94A3B8);
                        canvas.drawCircle(cx, cy, dotR, p);
                    }
                }
            }

            @Override public void setAlpha(int alpha) {}
            @Override public void setColorFilter(ColorFilter colorFilter) {}
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    /**
     * 현재 페이지의 실질적 배경색을 감지합니다.
     * - PDF 페이지도 이제 네이티브 페이지 배경(SolidColorBrush)에 최빈색이 직접 적용되므로
     *   밝은 PDF뿐 아니라 다크모드/유색 PDF까지 실제 배경색을 100% 정확하게 읽습니다.
     */
    private int detectEffectivePageBackgroundColor(Activity activity) {
        try {
            Field fP4a = activity.getClass().getDeclaredField("t");
            fP4a.setAccessible(true);
            Object p4a = fP4a.get(activity);
            if (p4a == null) return 0xFF0E271E;

            Object stateHolder = p4a.getClass().getMethod("a").invoke(p4a);
            if (stateHolder == null) return 0xFF0E271E;

            Object page = stateHolder.getClass().getMethod("m0").invoke(stateHolder);
            if (page == null) return 0xFF0E271E;

            // 페이지 네이티브 배경(SolidColorBrush: com.seewo.easinote.p4.e.b.c -> field 'a') 읽기
            Object bgObj = page.getClass().getMethod("e").invoke(page);
            if (bgObj != null) {
                try {
                    Field fColor = bgObj.getClass().getDeclaredField("a");
                    fColor.setAccessible(true);
                    Object val = fColor.get(bgObj);
                    if (val instanceof Integer) {
                        int c = (Integer) val;
                        if ((c & 0xFF000000) != 0) {
                            return c;
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return 0xFF0E271E; // 기본 칠판 녹색
    }

    private int readCurrentPenColor(Activity activity) {
        try {
            Field fToolbar = activity.getClass().getDeclaredField("e");
            fToolbar.setAccessible(true);
            Object toolbar = fToolbar.get(activity);
            if (toolbar != null) {
                Object pen = toolbar.getClass().getMethod("getPen").invoke(toolbar);
                if (pen != null) {
                    return (Integer) pen.getClass().getMethod("getColor").invoke(pen);
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    /**
     * 주어진 배경색에 대해 WCAG 대비비가 높은 5계열 × 2단계(총 10색) 추천 팔레트를 구성합니다.
     */
    private static void buildContrastPalettes(int bgColor, int[] outPrimary, int[] outAlt) {
        double bgLum = relativeLuminance(bgColor);
        int[] baseP = (bgLum >= 0.45) ? LIGHT_BG_PRIMARY : DARK_BG_PRIMARY;
        int[] baseA = (bgLum >= 0.45) ? LIGHT_BG_ALT : DARK_BG_ALT;
        System.arraycopy(baseP, 0, outPrimary, 0, SLOT_COUNT);
        System.arraycopy(baseA, 0, outAlt, 0, SLOT_COUNT);

        // 유색 배경과 완전히 겹쳐 안 보이는 경우(대비비 1.8 미만)만 고대비 후보로 안전 치환
        // (기본/보조 톤이 서로 같은 색으로 덮어써지지 않도록 primary와 중복 방지)
        ensureArrayContrast(bgColor, outPrimary, null);
        ensureArrayContrast(bgColor, outAlt, outPrimary);
    }

    private static void ensureArrayContrast(int bgColor, int[] palette, int[] otherPalette) {
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (contrastRatio(palette[i], bgColor) < 1.8) {
                int best = palette[i];
                double bestRatio = contrastRatio(best, bgColor);
                for (int cand : FALLBACK_CANDIDATES) {
                    if (otherPalette != null && (otherPalette[i] & 0xFFFFFF) == (cand & 0xFFFFFF)) {
                        continue;
                    }
                    boolean dup = false;
                    for (int j = 0; j < SLOT_COUNT; j++) {
                        if (j != i && (palette[j] & 0xFFFFFF) == (cand & 0xFFFFFF)) {
                            dup = true;
                            break;
                        }
                    }
                    if (dup) continue;
                    double r = contrastRatio(cand, bgColor);
                    if (r > bestRatio) {
                        bestRatio = r;
                        best = cand;
                    }
                }
                palette[i] = best;
            }
        }
    }

    private static double relativeLuminance(int color) {
        double r = Color.red(color) / 255.0;
        double g = Color.green(color) / 255.0;
        double b = Color.blue(color) / 255.0;
        r = (r <= 0.03928) ? (r / 12.92) : Math.pow((r + 0.055) / 1.055, 2.4);
        g = (g <= 0.03928) ? (g / 12.92) : Math.pow((g + 0.055) / 1.055, 2.4);
        b = (b <= 0.03928) ? (b / 12.92) : Math.pow((b + 0.055) / 1.055, 2.4);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double contrastRatio(int c1, int c2) {
        double l1 = relativeLuminance(c1);
        double l2 = relativeLuminance(c2);
        double hi = Math.max(l1, l2);
        double lo = Math.min(l1, l2);
        return (hi + 0.05) / (lo + 0.05);
    }

    private static int dp(android.content.Context ctx, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics());
    }
}

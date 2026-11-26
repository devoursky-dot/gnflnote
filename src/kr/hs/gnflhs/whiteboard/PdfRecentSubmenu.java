package kr.hs.gnflhs.whiteboard;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/**
 * GNFL NOTE V1.5 - 'PDF 불러오기' 옆 확장 서브메뉴
 *
 * - 맨 위: [+ 새로 불러오기] (SAF 문서 선택기 즉시 실행)
 * - 아래: 최근 열었던 PDF 캐시 목록 (최신순, 클릭 시 즉시 열기, 길게 눌러 개별 삭제)
 * - 하단: 현재 캐시 사용량 / 1 GB 표시
 */
public class PdfRecentSubmenu {
    private static final String TAG = "PdfRecentSubmenu";
    private static PopupWindow sPopup;

    public static void dismissIfShowing() {
        try {
            if (sPopup != null && sPopup.isShowing()) {
                sPopup.dismiss();
            }
        } catch (Throwable ignored) {}
        sPopup = null;
    }

    public static void showNextToAnchor(final Activity activity, final View anchorRow) {
        if (activity == null || anchorRow == null) return;
        dismissIfShowing();

        try {
            final Context ctx = activity;
            int widthPx = dp(ctx, 324);

            LinearLayout rootCard = new LinearLayout(ctx);
            rootCard.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setColor(Color.WHITE);
            cardBg.setCornerRadius(dp(ctx, 10));
            cardBg.setStroke(dp(ctx, 1), Color.parseColor("#E2E8F0"));
            rootCard.setBackground(cardBg);
            rootCard.setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), dp(ctx, 8));

            // 1. 맨 위: [+ 새로 불러오기] 버튼
            LinearLayout newBtn = new LinearLayout(ctx);
            newBtn.setOrientation(LinearLayout.HORIZONTAL);
            newBtn.setGravity(Gravity.CENTER_VERTICAL);
            newBtn.setPadding(dp(ctx, 12), dp(ctx, 11), dp(ctx, 12), dp(ctx, 11));
            GradientDrawable newBtnBg = new GradientDrawable();
            newBtnBg.setColor(Color.parseColor("#ECFDF5"));
            newBtnBg.setCornerRadius(dp(ctx, 8));
            newBtnBg.setStroke(dp(ctx, 1), Color.parseColor("#A7F3D0"));
            newBtn.setBackground(newBtnBg);

            TextView plusBadge = new TextView(ctx);
            plusBadge.setText("＋");
            plusBadge.setTextColor(Color.WHITE);
            plusBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            plusBadge.setTypeface(Typeface.DEFAULT_BOLD);
            plusBadge.setGravity(Gravity.CENTER);
            GradientDrawable plusBg = new GradientDrawable();
            plusBg.setColor(Color.parseColor("#00B589"));
            plusBg.setShape(GradientDrawable.OVAL);
            plusBadge.setBackground(plusBg);
            LinearLayout.LayoutParams plusLp = new LinearLayout.LayoutParams(dp(ctx, 24), dp(ctx, 24));
            plusLp.rightMargin = dp(ctx, 10);
            newBtn.addView(plusBadge, plusLp);

            TextView newBtnTxt = new TextView(ctx);
            newBtnTxt.setText("새로 불러오기");
            newBtnTxt.setTextColor(Color.parseColor("#065F46"));
            newBtnTxt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            newBtnTxt.setTypeface(Typeface.DEFAULT_BOLD);
            newBtn.addView(newBtnTxt, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            newBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    PdfCacheManager.clearSessionUnlock();
                    dismissIfShowing();
                    dismissStockMenuPopup(anchorRow);
                    PdfPicker.launchSafPdfPicker(activity);
                }
            });
            rootCard.addView(newBtn, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // 2. 섹션 헤더
            TextView sectionHeader = new TextView(ctx);
            sectionHeader.setText("최근 열었던 PDF");
            sectionHeader.setTextColor(Color.parseColor("#64748B"));
            sectionHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            sectionHeader.setTypeface(Typeface.DEFAULT_BOLD);
            sectionHeader.setPadding(dp(ctx, 6), dp(ctx, 10), dp(ctx, 6), dp(ctx, 4));
            rootCard.addView(sectionHeader, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // 3. 최근 PDF 목록 컨테이너 (스크롤 가능)
            final ScrollView scrollView = new ScrollView(ctx);
            scrollView.setVerticalScrollBarEnabled(true);
            final LinearLayout listContainer = new LinearLayout(ctx);
            listContainer.setOrientation(LinearLayout.VERTICAL);
            scrollView.addView(listContainer, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // 4. 하단 캐시 상태 표시
            View bottomDivider = new View(ctx);
            bottomDivider.setBackgroundColor(Color.parseColor("#E2E8F0"));
            LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1));
            divLp.topMargin = dp(ctx, 6);
            divLp.bottomMargin = dp(ctx, 6);

            LinearLayout footer = new LinearLayout(ctx);
            footer.setOrientation(LinearLayout.HORIZONTAL);
            footer.setGravity(Gravity.CENTER_VERTICAL);
            footer.setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2));

            final TextView cacheStatTxt = new TextView(ctx);
            cacheStatTxt.setTextColor(Color.parseColor("#64748B"));
            cacheStatTxt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            footer.addView(cacheStatTxt, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView hintTxt = new TextView(ctx);
            hintTxt.setText("길게 눌러 잠금/삭제");
            hintTxt.setTextColor(Color.parseColor("#94A3B8"));
            hintTxt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            footer.addView(hintTxt, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // 목록 채우기
            populateRecentList(activity, anchorRow, listContainer, cacheStatTxt);

            // 화면 위치 먼저 계산: [+ 새로 불러오기] 버튼이 절대 위로 올라가지 않도록 상단 Y 고정!
            int[] loc = new int[2];
            anchorRow.getLocationOnScreen(loc);
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();

            int x = loc[0] + anchorRow.getWidth() + dp(ctx, 10);
            if (x + widthPx > dm.widthPixels - dp(ctx, 12)) {
                x = Math.max(dp(ctx, 12), loc[0] - widthPx - dp(ctx, 10));
            }
            int y = Math.max(dp(ctx, 16), loc[1] - dp(ctx, 12));
            int maxBottom = dm.heightPixels - dp(ctx, 92); // 하단 툴바 위로 유지

            // 상단(새로 불러오기 + 헤더) + 하단(구분선 + 캐시 상태)을 제외한 남은 아래 공간만큼만 리스트 최대 높이 제한 -> 초과 시 내부 스크롤!
            int chromeHeight = dp(ctx, 128);
            int maxListHeight = Math.max(dp(ctx, 110), Math.min(dp(ctx, 340), (maxBottom - y) - chromeHeight));
            listContainer.measure(
                    View.MeasureSpec.makeMeasureSpec(widthPx - dp(ctx, 20), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int desiredListHeight = Math.min(listContainer.getMeasuredHeight(), maxListHeight);
            if (listContainer.getMeasuredHeight() > maxListHeight) {
                scrollView.setScrollbarFadingEnabled(false);
            }
            rootCard.addView(scrollView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(dp(ctx, 56), desiredListHeight)));

            rootCard.addView(bottomDivider, divLp);
            rootCard.addView(footer, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            PopupWindow popup = new PopupWindow(
                    rootCard,
                    widthPx,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    true);
            popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            popup.setOutsideTouchable(true);
            if (Build.VERSION.SDK_INT >= 21) {
                popup.setElevation(dp(ctx, 14));
            }
            popup.setOnDismissListener(new PopupWindow.OnDismissListener() {
                @Override
                public void onDismiss() {
                    if (sPopup == popup) {
                        sPopup = null;
                    }
                }
            });

            sPopup = popup;
            popup.showAtLocation(activity.getWindow().getDecorView(), Gravity.NO_GRAVITY, x, y);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to show PdfRecentSubmenu", t);
            // 폴백: 문제 시 기존처럼 바로 SAF 선택기 실행
            PdfPicker.launchSafPdfPicker(activity);
        }
    }

    private static void populateRecentList(
            final Activity activity,
            final View anchorRow,
            final LinearLayout listContainer,
            final TextView cacheStatTxt) {
        listContainer.removeAllViews();
        List<PdfCacheManager.CacheEntry> entries = PdfCacheManager.getRecentEntries(activity);
        long usedBytes = PdfCacheManager.getTotalCacheBytes(activity);
        long maxBytes = PdfCacheManager.getEffectiveMaxBytes(activity);
        cacheStatTxt.setText("캐시 " + PdfCacheManager.formatSize(usedBytes) + " / " + PdfCacheManager.formatSize(maxBytes));

        if (entries.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText("최근에 열었던 PDF가 없습니다");
            empty.setTextColor(Color.parseColor("#94A3B8"));
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(activity, 8), dp(activity, 18), dp(activity, 8), dp(activity, 18));
            listContainer.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }

        for (int i = 0; i < entries.size(); i++) {
            final PdfCacheManager.CacheEntry entry = entries.get(i);
            final LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8));

            final GradientDrawable normalBg = new GradientDrawable();
            normalBg.setColor(Color.TRANSPARENT);
            normalBg.setCornerRadius(dp(activity, 7));

            final GradientDrawable pressedBg = new GradientDrawable();
            pressedBg.setColor(Color.parseColor("#F1F5F9"));
            pressedBg.setCornerRadius(dp(activity, 7));
            row.setBackground(normalBg);

            // 좌측 PDF 배지
            TextView badge = new TextView(activity);
            badge.setText("PDF");
            badge.setTextColor(Color.parseColor("#EA580C"));
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            badge.setGravity(Gravity.CENTER);
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setColor(Color.parseColor("#FFF7ED"));
            badgeBg.setCornerRadius(dp(activity, 5));
            badgeBg.setStroke(dp(activity, 1), Color.parseColor("#FED7AA"));
            badge.setBackground(badgeBg);
            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(dp(activity, 34), dp(activity, 26));
            badgeLp.rightMargin = dp(activity, 10);
            row.addView(badge, badgeLp);

            // 우측 파일명 + 메타정보
            LinearLayout textCol = new LinearLayout(activity);
            textCol.setOrientation(LinearLayout.VERTICAL);

            TextView title = new TextView(activity);
            title.setText(entry.displayName);
            title.setTextColor(Color.parseColor("#1E293B"));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            textCol.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView sub = new TextView(activity);
            String pagePart = (entry.pageCount > 0) ? (entry.pageCount + "페이지 · ") : "";
            String lastPart = (entry.lastPageIndex > 0) ? (" · 최근 " + (entry.lastPageIndex + 1) + "쪽") : "";
            sub.setText(pagePart + PdfCacheManager.formatSize(entry.fileSize) + lastPart);
            sub.setTextColor(Color.parseColor("#64748B"));
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            sub.setSingleLine(true);
            textCol.addView(sub, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            row.addView(textCol, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            // 우측 PIN 잠금 상태 배지 (잠금 시 🔒 / 현재 세션 열람 해제 시 🔓)
            if (entry.isLocked()) {
                boolean unlockedNow = PdfCacheManager.isEntryUnlockedInSession(entry.id);
                TextView lockBadge = new TextView(activity);
                lockBadge.setText(unlockedNow ? "🔓" : "🔒");
                lockBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                lockBadge.setGravity(Gravity.CENTER);
                GradientDrawable lockBg = new GradientDrawable();
                lockBg.setColor(Color.parseColor(unlockedNow ? "#ECFDF5" : "#FEF3C7"));
                lockBg.setCornerRadius(dp(activity, 6));
                lockBg.setStroke(dp(activity, 1), Color.parseColor(unlockedNow ? "#A7F3D0" : "#FDE68A"));
                lockBadge.setBackground(lockBg);
                LinearLayout.LayoutParams lockLp = new LinearLayout.LayoutParams(dp(activity, 28), dp(activity, 26));
                lockLp.leftMargin = dp(activity, 6);
                row.addView(lockBadge, lockLp);
            }

            row.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    int act = event.getActionMasked();
                    if (act == MotionEvent.ACTION_DOWN) {
                        row.setBackground(pressedBg);
                    } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                        row.setBackground(normalBg);
                    }
                    return false;
                }
            });

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final File f = PdfCacheManager.getEntryFile(activity, entry);
                    if (f == null || !f.exists()) {
                        PdfCacheManager.deleteEntry(activity, entry.id);
                        ToolbarFitHelper.showTopToast(activity, "캐시 파일을 찾을 수 없어 목록에서 제거했습니다.");
                        populateRecentList(activity, anchorRow, listContainer, cacheStatTxt);
                        return;
                    }
                    if (!entry.isLocked()) {
                        // 다른 일반 PDF를 여는 순간 이전에 풀어둔 잠긴 PDF는 즉시 자동 재잠금!
                        PdfCacheManager.clearSessionUnlock();
                        dismissIfShowing();
                        dismissStockMenuPopup(anchorRow);
                        PdfCacheManager.touchEntry(activity, entry.id);
                        PdfPicker.showPagePickerFromCachedFile(activity, f, entry.displayName);
                    } else if (PdfCacheManager.isEntryUnlockedInSession(entry.id)) {
                        // 현재 세션에서 이미 인증된 동일 파일 -> 재입력 없이 바로 열기
                        dismissIfShowing();
                        dismissStockMenuPopup(anchorRow);
                        PdfCacheManager.touchEntry(activity, entry.id);
                        PdfPicker.showPagePickerFromCachedFile(activity, f, entry.displayName);
                    } else {
                        // PIN 잠금된 파일 -> 4자리 PIN 확인 후 열기
                        dismissIfShowing();
                        dismissStockMenuPopup(anchorRow);
                        showPinKeypadDialog(
                                activity,
                                "🔒 PDF 잠금 해제",
                                entry.displayName,
                                new PinCallback() {
                                    @Override
                                    public boolean onPinEntered(String pin4) {
                                        if (PdfCacheManager.verifyEntryPin(activity, entry.id, pin4)) {
                                            PdfCacheManager.markEntryUnlockedInSession(entry.id);
                                            PdfCacheManager.touchEntry(activity, entry.id);
                                            PdfPicker.showPagePickerFromCachedFile(activity, f, entry.displayName);
                                            return true;
                                        }
                                        return false;
                                    }
                                }
                        );
                    }
                }
            });

            // 길게 누르기:
            // - PIN 없는 파일 -> [🔒 PIN 잠금 설정] 또는 [🗑️ 목록에서 삭제] 선택 팝업
            // - PIN 있는 파일 -> 기존 4자리 PIN 확인 후 잠금 해제 (잠금 상태에서는 삭제 불가)
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    try { v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); } catch (Throwable ignored) {}
                    if (!entry.isLocked()) {
                        showUnlockedEntryActionDialog(activity, entry, new Runnable() {
                            @Override
                            public void run() {
                                showPinKeypadDialog(
                                        activity,
                                        "🔒 PIN 잠금 설정 (4자리)",
                                        entry.displayName,
                                        new PinCallback() {
                                            @Override
                                            public boolean onPinEntered(String pin4) {
                                                if (PdfCacheManager.setEntryPin(activity, entry.id, pin4)) {
                                                    ToolbarFitHelper.showTopToast(activity, "🔒 PIN 잠금이 설정되었습니다: " + entry.displayName);
                                                    populateRecentList(activity, anchorRow, listContainer, cacheStatTxt);
                                                    return true;
                                                }
                                                return false;
                                            }
                                        }
                                );
                            }
                        }, new Runnable() {
                            @Override
                            public void run() {
                                if (PdfCacheManager.deleteEntry(activity, entry.id)) {
                                    ToolbarFitHelper.showTopToast(activity, "🗑️ 캐시에서 삭제되었습니다: " + entry.displayName);
                                    populateRecentList(activity, anchorRow, listContainer, cacheStatTxt);
                                }
                            }
                        });
                    } else {
                        showPinKeypadDialog(
                                activity,
                                "🔓 PIN 잠금 해제",
                                entry.displayName + "\n기존 4자리 PIN을 입력하세요",
                                new PinCallback() {
                                    @Override
                                    public boolean onPinEntered(String pin4) {
                                        if (PdfCacheManager.clearEntryPin(activity, entry.id, pin4)) {
                                            ToolbarFitHelper.showTopToast(activity, "🔓 PIN 잠금이 해제되었습니다: " + entry.displayName);
                                            populateRecentList(activity, anchorRow, listContainer, cacheStatTxt);
                                            return true;
                                        }
                                        return false;
                                    }
                                }
                        );
                    }
                    return true;
                }
            });

            listContainer.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private static void dismissStockMenuPopup(View anchorRow) {
        if (anchorRow == null) return;
        try {
            // PopupDecorView에 ACTION_OUTSIDE를 보내 순정 메뉴 PopupWindow만 안전하게 닫음
            // (Activity.dispatchKeyEvent(BACK)을 쓰면 액티비티 자체가 종료되므로 절대 사용 금지!)
            View popupRoot = anchorRow.getRootView();
            if (popupRoot != null) {
                long now = android.os.SystemClock.uptimeMillis();
                MotionEvent ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_OUTSIDE, -10f, -10f, 0);
                popupRoot.dispatchTouchEvent(ev);
                ev.recycle();
            }
        } catch (Throwable ignored) {}
    }

    private interface PinCallback {
        boolean onPinEntered(String pin4);
    }

    /**
     * PIN이 설정되지 않은 캐시 파일을 길게 눌렀을 때 표시되는 액션 선택 다이얼로그:
     * - [🔒 PIN 잠금 설정]
     * - [🗑️ 캐시에서 삭제]
     */
    private static void showUnlockedEntryActionDialog(
            final Activity activity,
            final PdfCacheManager.CacheEntry entry,
            final Runnable onSetPin,
            final Runnable onDelete) {
        if (activity == null || entry == null) return;
        try {
            final android.app.Dialog dlg = new android.app.Dialog(activity);
            dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
            dlg.setCanceledOnTouchOutside(true);

            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(activity, 18), dp(activity, 18), dp(activity, 18), dp(activity, 14));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.WHITE);
            bg.setCornerRadius(dp(activity, 14));
            bg.setStroke(dp(activity, 1), Color.parseColor("#E2E8F0"));
            card.setBackground(bg);

            TextView title = new TextView(activity);
            title.setText(entry.displayName);
            title.setTextColor(Color.parseColor("#0F172A"));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setGravity(Gravity.CENTER);
            card.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView sub = new TextView(activity);
            sub.setText("원하는 작업을 선택하세요");
            sub.setTextColor(Color.parseColor("#64748B"));
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            sub.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = dp(activity, 4);
            subLp.bottomMargin = dp(activity, 14);
            card.addView(sub, subLp);

            TextView btnPin = new TextView(activity);
            btnPin.setText("🔒  PIN 잠금 설정 (4자리)");
            btnPin.setTextColor(Color.parseColor("#065F46"));
            btnPin.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
            btnPin.setTypeface(Typeface.DEFAULT_BOLD);
            btnPin.setGravity(Gravity.CENTER);
            GradientDrawable pinBg = new GradientDrawable();
            pinBg.setColor(Color.parseColor("#ECFDF5"));
            pinBg.setCornerRadius(dp(activity, 9));
            pinBg.setStroke(dp(activity, 1), Color.parseColor("#A7F3D0"));
            btnPin.setBackground(pinBg);
            btnPin.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dlg.dismiss();
                    if (onSetPin != null) onSetPin.run();
                }
            });
            LinearLayout.LayoutParams pinLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 46));
            pinLp.bottomMargin = dp(activity, 8);
            card.addView(btnPin, pinLp);

            TextView btnDel = new TextView(activity);
            btnDel.setText("🗑️  캐시에서 삭제");
            btnDel.setTextColor(Color.parseColor("#B91C1C"));
            btnDel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
            btnDel.setTypeface(Typeface.DEFAULT_BOLD);
            btnDel.setGravity(Gravity.CENTER);
            GradientDrawable delBg = new GradientDrawable();
            delBg.setColor(Color.parseColor("#FEF2F2"));
            delBg.setCornerRadius(dp(activity, 9));
            delBg.setStroke(dp(activity, 1), Color.parseColor("#FECACA"));
            btnDel.setBackground(delBg);
            btnDel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dlg.dismiss();
                    if (onDelete != null) onDelete.run();
                }
            });
            LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 46));
            delLp.bottomMargin = dp(activity, 8);
            card.addView(btnDel, delLp);

            TextView btnCancel = new TextView(activity);
            btnCancel.setText("취소");
            btnCancel.setTextColor(Color.parseColor("#64748B"));
            btnCancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            btnCancel.setGravity(Gravity.CENTER);
            GradientDrawable cBg = new GradientDrawable();
            cBg.setColor(Color.parseColor("#F8FAFC"));
            cBg.setCornerRadius(dp(activity, 9));
            cBg.setStroke(dp(activity, 1), Color.parseColor("#E2E8F0"));
            btnCancel.setBackground(cBg);
            btnCancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dlg.dismiss();
                }
            });
            card.addView(btnCancel, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 40)));

            dlg.setContentView(card, new ViewGroup.LayoutParams(dp(activity, 286), ViewGroup.LayoutParams.WRAP_CONTENT));
            if (dlg.getWindow() != null) {
                dlg.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }
            dlg.show();
        } catch (Throwable t) {
            Log.e(TAG, "showUnlockedEntryActionDialog failed", t);
        }
    }

    /**
     * 교실 전자칠판용 컴팩트 4자리 PIN 숫자 키패드 다이얼로그
     * - 대형 시스템 키보드를 띄우지 않아 뒤에서 학생들이 비밀번호를 보기 어렵게 보호
     * - 4번째 자리 입력 즉시 자동 검증/적용
     */
    private static void showPinKeypadDialog(
            final Activity activity,
            final String titleText,
            final String subtitleText,
            final PinCallback callback) {
        if (activity == null || callback == null) return;
        try {
            final android.app.Dialog dlg = new android.app.Dialog(activity);
            dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
            dlg.setCanceledOnTouchOutside(true);

            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setPadding(dp(activity, 18), dp(activity, 18), dp(activity, 18), dp(activity, 16));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.WHITE);
            bg.setCornerRadius(dp(activity, 14));
            bg.setStroke(dp(activity, 1), Color.parseColor("#E2E8F0"));
            card.setBackground(bg);

            TextView title = new TextView(activity);
            title.setText(titleText);
            title.setTextColor(Color.parseColor("#0F172A"));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setGravity(Gravity.CENTER);
            card.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView sub = new TextView(activity);
            sub.setText(subtitleText != null ? subtitleText : "");
            sub.setTextColor(Color.parseColor("#64748B"));
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            sub.setGravity(Gravity.CENTER);
            sub.setMaxLines(2);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = dp(activity, 4);
            card.addView(sub, subLp);

            // 4자리 마스킹 도트 (● ● ● ●)
            LinearLayout dotRow = new LinearLayout(activity);
            dotRow.setOrientation(LinearLayout.HORIZONTAL);
            dotRow.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams dotRowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dotRowLp.topMargin = dp(activity, 14);
            dotRowLp.bottomMargin = dp(activity, 6);

            final View[] dots = new View[4];
            for (int i = 0; i < 4; i++) {
                View d = new View(activity);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(activity, 14), dp(activity, 14));
                dlp.leftMargin = dp(activity, 8);
                dlp.rightMargin = dp(activity, 8);
                dotRow.addView(d, dlp);
                dots[i] = d;
            }
            card.addView(dotRow, dotRowLp);

            final TextView errTxt = new TextView(activity);
            errTxt.setText(" ");
            errTxt.setTextColor(Color.parseColor("#DC2626"));
            errTxt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            errTxt.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams errLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            errLp.bottomMargin = dp(activity, 8);
            card.addView(errTxt, errLp);

            final StringBuilder pinBuf = new StringBuilder();
            final Runnable refreshDots = new Runnable() {
                @Override
                public void run() {
                    int len = pinBuf.length();
                    for (int i = 0; i < 4; i++) {
                        GradientDrawable dg = new GradientDrawable();
                        dg.setShape(GradientDrawable.OVAL);
                        if (i < len) {
                            dg.setColor(Color.parseColor("#00B589"));
                        } else {
                            dg.setColor(Color.parseColor("#E2E8F0"));
                        }
                        dots[i].setBackground(dg);
                    }
                }
            };
            refreshDots.run();

            String[][] keys = new String[][] {
                    {"1", "2", "3"},
                    {"4", "5", "6"},
                    {"7", "8", "9"},
                    {"취소", "0", "⌫"}
            };

            for (int r = 0; r < 4; r++) {
                LinearLayout keyRow = new LinearLayout(activity);
                keyRow.setOrientation(LinearLayout.HORIZONTAL);
                keyRow.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.topMargin = dp(activity, 6);

                for (int c = 0; c < 3; c++) {
                    final String k = keys[r][c];
                    TextView btn = new TextView(activity);
                    btn.setText(k);
                    btn.setGravity(Gravity.CENTER);
                    boolean isAction = "취소".equals(k) || "⌫".equals(k);
                    btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, isAction ? 13.5f : 18f);
                    btn.setTypeface(isAction ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD);
                    btn.setTextColor(Color.parseColor("취소".equals(k) ? "#64748B" : "#1E293B"));

                    final GradientDrawable kBg = new GradientDrawable();
                    kBg.setColor(Color.parseColor(isAction ? "#F8FAFC" : "#F1F5F9"));
                    kBg.setCornerRadius(dp(activity, 9));
                    kBg.setStroke(dp(activity, 1), Color.parseColor("#E2E8F0"));
                    btn.setBackground(kBg);

                    btn.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if ("취소".equals(k)) {
                                dlg.dismiss();
                                return;
                            }
                            if ("⌫".equals(k)) {
                                if (pinBuf.length() > 0) {
                                    pinBuf.deleteCharAt(pinBuf.length() - 1);
                                    errTxt.setText(" ");
                                    refreshDots.run();
                                }
                                return;
                            }
                            if (pinBuf.length() < 4) {
                                pinBuf.append(k);
                                errTxt.setText(" ");
                                refreshDots.run();
                                if (pinBuf.length() == 4) {
                                    String entered = pinBuf.toString();
                                    boolean ok = false;
                                    try {
                                        ok = callback.onPinEntered(entered);
                                    } catch (Throwable ignored) {}
                                    if (ok) {
                                        dlg.dismiss();
                                    } else {
                                        pinBuf.setLength(0);
                                        refreshDots.run();
                                        errTxt.setText("비밀번호가 일치하지 않습니다");
                                    }
                                }
                            }
                        }
                    });

                    LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(activity, 46), 1f);
                    blp.leftMargin = dp(activity, 4);
                    blp.rightMargin = dp(activity, 4);
                    keyRow.addView(btn, blp);
                }
                card.addView(keyRow, rowLp);
            }

            dlg.setContentView(card, new ViewGroup.LayoutParams(dp(activity, 276), ViewGroup.LayoutParams.WRAP_CONTENT));
            if (dlg.getWindow() != null) {
                dlg.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }
            dlg.show();
        } catch (Throwable t) {
            Log.e(TAG, "showPinKeypadDialog failed", t);
        }
    }

    private static int dp(Context ctx, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics());
    }
}

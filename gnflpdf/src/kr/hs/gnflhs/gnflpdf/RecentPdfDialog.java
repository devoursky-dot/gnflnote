package kr.hs.gnflhs.gnflpdf;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 최근 연 PDF 캐시 관리 다이얼로그
 * - 최대 3GB 캐시 사용량 표시 (초과 시 잠금 파일 포함 가장 오래된 파일부터 자동 삭제)
 * - 짧게 탭: 열기 (PIN 잠금 파일은 4자리 PIN 확인)
 * - 꾹 누르기: 캐시에서 삭제 / 4자리 PIN 잠금 설정·해제
 */
public class RecentPdfDialog {

    public interface Callback {
        void onOpenCachedPdf(File file, String displayName);
        void onDismissed();
    }

    private final Activity mActivity;
    private final Callback mCallback;
    private Dialog mDialog;
    private TextView mUsageText;
    private TextView mEmptyText;
    private ListView mListView;
    private final List<PdfCacheManager.CacheEntry> mEntries = new ArrayList<>();
    private EntryAdapter mAdapter;
    private final SimpleDateFormat mDateFmt = new SimpleDateFormat("MM/dd HH:mm", Locale.KOREA);

    public RecentPdfDialog(Activity activity, Callback callback) {
        mActivity = activity;
        mCallback = callback;
    }

    public void show() {
        if (mActivity == null || mActivity.isFinishing()) return;

        mDialog = new Dialog(mActivity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        mDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        mDialog.setCanceledOnTouchOutside(true);
        mDialog.setOnDismissListener(d -> {
            if (mCallback != null) mCallback.onDismissed();
        });

        FrameLayout backdrop = new FrameLayout(mActivity);
        backdrop.setBackgroundColor(0x990F172A);
        backdrop.setOnClickListener(v -> mDialog.dismiss());

        DisplayMetrics dm = mActivity.getResources().getDisplayMetrics();
        int dlgW = Math.round(dm.widthPixels * 0.62f);
        int dlgH = Math.round(dm.heightPixels * 0.74f);

        LinearLayout card = new LinearLayout(mActivity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.WHITE);
        cardBg.setCornerRadius(dp(20));
        cardBg.setStroke(dp(1), Color.parseColor("#CBD5E1"));
        card.setBackground(cardBg);
        card.setElevation(dp(16));

        // 헤더
        LinearLayout header = new LinearLayout(mActivity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(22), dp(16), dp(16), dp(12));

        LinearLayout titleCol = new LinearLayout(mActivity);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(mActivity);
        title.setText("최근 연 PDF 캐시");
        title.setTextColor(Color.parseColor("#0F172A"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleCol.addView(title);

        mUsageText = new TextView(mActivity);
        mUsageText.setTextColor(Color.parseColor("#475569"));
        mUsageText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ulp.topMargin = dp(3);
        titleCol.addView(mUsageText, ulp);

        TextView hint = new TextView(mActivity);
        hint.setText("짧게 탭: 열기  ·  꾹 누르기: 삭제 또는 PIN 잠금 설정");
        hint.setTextColor(Color.parseColor("#00A884"));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        hint.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(3);
        titleCol.addView(hint, hlp);

        header.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnClose = new Button(mActivity);
        btnClose.setText("✕");
        btnClose.setTextColor(Color.parseColor("#64748B"));
        btnClose.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        btnClose.setBackgroundColor(Color.TRANSPARENT);
        btnClose.setOnClickListener(v -> mDialog.dismiss());
        header.addView(btnClose, new LinearLayout.LayoutParams(dp(44), dp(44)));

        card.addView(header);
        card.addView(createHLine());

        // 목록 영역
        FrameLayout listWrap = new FrameLayout(mActivity);
        listWrap.setPadding(dp(14), dp(10), dp(14), dp(10));

        mListView = new ListView(mActivity);
        mListView.setDivider(null);
        mListView.setDividerHeight(dp(8));
        mAdapter = new EntryAdapter();
        mListView.setAdapter(mAdapter);
        mListView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= mEntries.size()) return;
            handleOpenEntry(mEntries.get(position));
        });
        mListView.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= mEntries.size()) return true;
            showLongPressMenu(mEntries.get(position));
            return true;
        });
        listWrap.addView(mListView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mEmptyText = new TextView(mActivity);
        mEmptyText.setText("저장된 PDF 캐시가 없습니다.\n파일을 열 때 '최근 파일 캐시에 저장'을 체크하면 여기에 보관됩니다.");
        mEmptyText.setGravity(Gravity.CENTER);
        mEmptyText.setTextColor(Color.parseColor("#64748B"));
        mEmptyText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        mEmptyText.setVisibility(View.GONE);
        listWrap.addView(mEmptyText, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        card.addView(listWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        backdrop.addView(card, new FrameLayout.LayoutParams(dlgW, dlgH, Gravity.CENTER));
        mDialog.setContentView(backdrop);
        refreshEntries();
        mDialog.show();
    }

    private void refreshEntries() {
        mEntries.clear();
        List<PdfCacheManager.CacheEntry> list = PdfCacheManager.getRecentEntries(mActivity);
        if (list != null) mEntries.addAll(list);

        long used = PdfCacheManager.getTotalCacheBytes(mActivity);
        long max = PdfCacheManager.getEffectiveMaxBytes(mActivity);
        mUsageText.setText("캐시 사용량: " + PdfCacheManager.formatSize(used)
                + " / " + PdfCacheManager.formatSize(max)
                + " (초과 시 잠금 포함 오래된 순 자동 삭제)");

        mEmptyText.setVisibility(mEntries.isEmpty() ? View.VISIBLE : View.GONE);
        mListView.setVisibility(mEntries.isEmpty() ? View.GONE : View.VISIBLE);
        if (mAdapter != null) mAdapter.notifyDataSetChanged();
    }

    private static boolean isLocked(PdfCacheManager.CacheEntry e) {
        return e != null && e.pinHash != null && !e.pinHash.isEmpty();
    }

    private void handleOpenEntry(final PdfCacheManager.CacheEntry entry) {
        final File f = PdfCacheManager.getEntryFile(mActivity, entry);
        if (f == null || !f.exists()) {
            Toast.makeText(mActivity, "캐시 파일을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show();
            refreshEntries();
            return;
        }
        if (isLocked(entry) && !PdfCacheManager.isEntryUnlockedInSession(entry.id)) {
            showPinPrompt("🔒 잠금된 PDF 열기", "4자리 PIN 번호를 입력하세요.", pin -> {
                if (PdfCacheManager.verifyEntryPin(mActivity, entry.id, pin)) {
                    PdfCacheManager.markEntryUnlockedInSession(entry.id);
                    PdfCacheManager.touchEntry(mActivity, entry.id);
                    mDialog.dismiss();
                    if (mCallback != null) mCallback.onOpenCachedPdf(f, entry.displayName);
                } else {
                    Toast.makeText(mActivity, "PIN 번호가 일치하지 않습니다.", Toast.LENGTH_SHORT).show();
                }
            });
            return;
        }
        PdfCacheManager.touchEntry(mActivity, entry.id);
        mDialog.dismiss();
        if (mCallback != null) mCallback.onOpenCachedPdf(f, entry.displayName);
    }

    private void showLongPressMenu(final PdfCacheManager.CacheEntry entry) {
        String lockItem = isLocked(entry) ? "🔓 PIN 잠금 해제" : "🔒 PIN 잠금 설정 (4자리)";
        String[] actions = new String[] { lockItem, "🗑 캐시에서 삭제" };

        new AlertDialog.Builder(mActivity)
                .setTitle(entry.displayName)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        if (isLocked(entry)) {
                            showPinPrompt("🔓 PIN 잠금 해제", "현재 4자리 PIN 번호를 입력하세요.", pin -> {
                                if (PdfCacheManager.clearEntryPin(mActivity, entry.id, pin)) {
                                    Toast.makeText(mActivity, "PIN 잠금이 해제되었습니다.", Toast.LENGTH_SHORT).show();
                                    refreshEntries();
                                } else {
                                    Toast.makeText(mActivity, "PIN 번호가 일치하지 않습니다.", Toast.LENGTH_SHORT).show();
                                }
                            });
                        } else {
                            showPinPrompt("🔒 PIN 잠금 설정", "설정할 4자리 숫자 PIN을 입력하세요.\n(3GB 초과 시 오래된 파일은 자동 삭제됩니다)", pin -> {
                                if (PdfCacheManager.setEntryPin(mActivity, entry.id, pin)) {
                                    Toast.makeText(mActivity, "PIN 잠금이 설정되었습니다.", Toast.LENGTH_SHORT).show();
                                    refreshEntries();
                                } else {
                                    Toast.makeText(mActivity, "4자리 숫자를 입력해 주세요.", Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                    } else if (which == 1) {
                        confirmDeleteEntry(entry);
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void confirmDeleteEntry(final PdfCacheManager.CacheEntry entry) {
        new AlertDialog.Builder(mActivity)
                .setTitle("캐시에서 삭제")
                .setMessage("'" + entry.displayName + "' 파일을 최근 캐시 목록에서 삭제할까요?")
                .setPositiveButton("삭제", (d, w) -> {
                    PdfCacheManager.deleteEntry(mActivity, entry.id);
                    Toast.makeText(mActivity, "캐시에서 삭제했습니다.", Toast.LENGTH_SHORT).show();
                    refreshEntries();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private interface PinSubmitListener {
        void onPinSubmitted(String pin);
    }

    private void showPinPrompt(String title, String message, final PinSubmitListener listener) {
        final EditText input = new EditText(mActivity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setFilters(new InputFilter[] { new InputFilter.LengthFilter(4) });
        input.setHint("4자리 숫자");
        input.setGravity(Gravity.CENTER);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);

        new AlertDialog.Builder(mActivity)
                .setTitle(title)
                .setMessage(message)
                .setView(input)
                .setPositiveButton("확인", (d, w) -> {
                    String pin = input.getText().toString().trim();
                    if (pin.length() != 4) {
                        Toast.makeText(mActivity, "4자리 숫자를 입력해 주세요.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (listener != null) listener.onPinSubmitted(pin);
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private class EntryAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mEntries.size();
        }

        @Override
        public Object getItem(int position) {
            return mEntries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            ImageView icon;
            TextView nameTv;
            TextView metaTv;
            TextView lockBadge;

            if (convertView == null) {
                row = new LinearLayout(mActivity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(14), dp(12), dp(14), dp(12));
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(Color.parseColor("#F8FAFC"));
                bg.setCornerRadius(dp(12));
                bg.setStroke(dp(1), Color.parseColor("#E2E8F0"));
                row.setBackground(bg);

                icon = new ImageView(mActivity);
                icon.setImageResource(R.drawable.explore_edu_menu_pdf);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(30), dp(30));
                ilp.rightMargin = dp(12);
                row.addView(icon, ilp);

                LinearLayout col = new LinearLayout(mActivity);
                col.setOrientation(LinearLayout.VERTICAL);

                nameTv = new TextView(mActivity);
                nameTv.setSingleLine(true);
                nameTv.setTextColor(Color.parseColor("#0F172A"));
                nameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
                nameTv.setTypeface(Typeface.DEFAULT_BOLD);
                col.addView(nameTv);

                metaTv = new TextView(mActivity);
                metaTv.setSingleLine(true);
                metaTv.setTextColor(Color.parseColor("#64748B"));
                metaTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                mlp.topMargin = dp(2);
                col.addView(metaTv, mlp);

                row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                lockBadge = new TextView(mActivity);
                lockBadge.setText("🔒 잠금");
                lockBadge.setTextColor(Color.parseColor("#B45309"));
                lockBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                lockBadge.setTypeface(Typeface.DEFAULT_BOLD);
                lockBadge.setPadding(dp(10), dp(4), dp(10), dp(4));
                GradientDrawable lbBg = new GradientDrawable();
                lbBg.setColor(Color.parseColor("#FEF3C7"));
                lbBg.setCornerRadius(dp(8));
                lockBadge.setBackground(lbBg);
                row.addView(lockBadge);

                row.setTag(new View[] { icon, nameTv, metaTv, lockBadge });
            } else {
                row = (LinearLayout) convertView;
                View[] h = (View[]) row.getTag();
                icon = (ImageView) h[0];
                nameTv = (TextView) h[1];
                metaTv = (TextView) h[2];
                lockBadge = (TextView) h[3];
            }

            PdfCacheManager.CacheEntry e = mEntries.get(position);
            nameTv.setText(e.displayName);
            String pages = (e.pageCount > 0) ? (e.pageCount + "쪽 · ") : "";
            String dateStr = (e.lastOpenedAt > 0L) ? mDateFmt.format(new Date(e.lastOpenedAt)) : "";
            metaTv.setText(PdfCacheManager.formatSize(e.fileSize) + " · " + pages + "최근 열람 " + dateStr);
            lockBadge.setVisibility(isLocked(e) ? View.VISIBLE : View.GONE);
            return row;
        }
    }

    private View createHLine() {
        View v = new View(mActivity);
        v.setBackgroundColor(Color.parseColor("#E2E8F0"));
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return v;
    }

    private int dp(int v) {
        return Math.round(v * mActivity.getResources().getDisplayMetrics().density);
    }
}

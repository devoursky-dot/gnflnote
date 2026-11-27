package kr.hs.gnflhs.gnflpdf;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 좌측: 초고속 페이지 번호 미니 아이콘 그리드 (썸네일 디코딩 지연 0ms)
 * 우측: 선택한 페이지 즉시 고화질 미리보기
 * 하단: 확인 버튼으로 해당 페이지 바로 열기/이동
 */
public class PdfPageSelectorDialog {

    public interface OnPageConfirmedListener {
        void onPageConfirmed(int pageIndex, boolean saveToCache);
        void onCancelled();
    }

    private final Activity mActivity;
    private final String mFilePath;
    private final Uri mUri;
    private final String mDisplayName;
    private final int mInitialPage;
    private final boolean mShowSaveCacheCheckbox;
    private final OnPageConfirmedListener mListener;

    private Dialog mDialog;
    private GridView mGridView;
    private PageGridAdapter mAdapter;
    private ImageView mPreviewImage;
    private ProgressBar mPreviewLoading;
    private TextView mHeaderSub;
    private TextView mPreviewCaption;
    private Button mBtnConfirm;
    private CheckBox mChkSaveCache;
    private EditText mJumpInput;

    private ParcelFileDescriptor mPfd;
    private PdfRenderer mRenderer;
    private int mPageCount = 0;
    private int mSelectedPage = 0;
    private Bitmap mCurrentPreviewBmp;

    private HandlerThread mWorkerThread;
    private Handler mWorkerHandler;
    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger mPreviewSeq = new AtomicInteger(0);
    private boolean mConfirmed = false;
    private boolean mClosed = false;

    public PdfPageSelectorDialog(Activity activity, String filePath, Uri uri,
                                 String displayName, int initialPage,
                                 boolean showSaveCacheCheckbox,
                                 OnPageConfirmedListener listener) {
        mActivity = activity;
        mFilePath = filePath;
        mUri = uri;
        mDisplayName = (displayName != null && !displayName.isEmpty()) ? displayName : "PDF 문서";
        mInitialPage = Math.max(0, initialPage);
        mShowSaveCacheCheckbox = showSaveCacheCheckbox;
        mListener = listener;
    }

    public void show() {
        if (mActivity == null || mActivity.isFinishing()) return;

        mWorkerThread = new HandlerThread("PdfPageSelectorWorker");
        mWorkerThread.start();
        mWorkerHandler = new Handler(mWorkerThread.getLooper());

        mDialog = new Dialog(mActivity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        mDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        mDialog.setCanceledOnTouchOutside(true);
        mDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                cleanup();
                if (!mConfirmed && mListener != null) {
                    mListener.onCancelled();
                }
            }
        });

        FrameLayout backdrop = new FrameLayout(mActivity);
        backdrop.setBackgroundColor(0x990F172A);
        backdrop.setOnClickListener(v -> mDialog.dismiss());

        DisplayMetrics dm = mActivity.getResources().getDisplayMetrics();
        int dlgW = Math.round(dm.widthPixels * 0.84f);
        int dlgH = Math.round(dm.heightPixels * 0.82f);

        LinearLayout card = new LinearLayout(mActivity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.WHITE);
        cardBg.setCornerRadius(dp(20));
        cardBg.setStroke(dp(1), Color.parseColor("#CBD5E1"));
        card.setBackground(cardBg);
        card.setElevation(dp(16));

        // 1. 상단 헤더
        card.addView(buildHeaderBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(createHLine());

        // 2. 중앙 좌/우 분할 영역
        LinearLayout body = new LinearLayout(mActivity);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setPadding(dp(18), dp(14), dp(18), dp(14));

        // 좌측: 페이지 번호 작은 아이콘 그리드 (52%)
        LinearLayout leftPane = new LinearLayout(mActivity);
        leftPane.setOrientation(LinearLayout.VERTICAL);

        TextView leftTitle = new TextView(mActivity);
        leftTitle.setText("페이지 선택");
        leftTitle.setTextColor(Color.parseColor("#475569"));
        leftTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        leftTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams ltlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ltlp.bottomMargin = dp(8);
        leftPane.addView(leftTitle, ltlp);

        mGridView = new GridView(mActivity);
        mGridView.setNumColumns(6);
        mGridView.setHorizontalSpacing(dp(8));
        mGridView.setVerticalSpacing(dp(8));
        mGridView.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        mGridView.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        mAdapter = new PageGridAdapter();
        mGridView.setAdapter(mAdapter);
        leftPane.addView(mGridView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        body.addView(leftPane, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.08f));

        // 세로 구분선
        View vDiv = new View(mActivity);
        vDiv.setBackgroundColor(Color.parseColor("#E2E8F0"));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT);
        vlp.setMargins(dp(16), 0, dp(16), 0);
        body.addView(vDiv, vlp);

        // 우측: 선택한 페이지 고화질 미리보기 (48%)
        LinearLayout rightPane = new LinearLayout(mActivity);
        rightPane.setOrientation(LinearLayout.VERTICAL);
        rightPane.setGravity(Gravity.CENTER_HORIZONTAL);

        mPreviewCaption = new TextView(mActivity);
        mPreviewCaption.setText("미리보기 준비 중...");
        mPreviewCaption.setTextColor(Color.parseColor("#0F172A"));
        mPreviewCaption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        mPreviewCaption.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams pclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pclp.bottomMargin = dp(8);
        rightPane.addView(mPreviewCaption, pclp);

        FrameLayout previewBox = new FrameLayout(mActivity);
        GradientDrawable pBg = new GradientDrawable();
        pBg.setColor(Color.parseColor("#F1F5F9"));
        pBg.setCornerRadius(dp(14));
        pBg.setStroke(dp(1), Color.parseColor("#CBD5E1"));
        previewBox.setBackground(pBg);
        previewBox.setPadding(dp(10), dp(10), dp(10), dp(10));

        mPreviewImage = new ImageView(mActivity);
        mPreviewImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewBox.addView(mPreviewImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mPreviewLoading = new ProgressBar(mActivity);
        mPreviewLoading.setVisibility(View.VISIBLE);
        previewBox.addView(mPreviewLoading, new FrameLayout.LayoutParams(
                dp(40), dp(40), Gravity.CENTER));

        rightPane.addView(previewBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        body.addView(rightPane, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f));

        card.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 3. 하단 확인/취소 바
        card.addView(createHLine());
        card.addView(buildFooterBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        backdrop.addView(card, new FrameLayout.LayoutParams(dlgW, dlgH, Gravity.CENTER));
        mDialog.setContentView(backdrop);
        mDialog.show();

        openPdfMetadataAsync();
    }

    private LinearLayout buildHeaderBar() {
        LinearLayout bar = new LinearLayout(mActivity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(22), dp(14), dp(18), dp(14));

        LinearLayout titleCol = new LinearLayout(mActivity);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(mActivity);
        title.setText(mDisplayName);
        title.setSingleLine(true);
        title.setTextColor(Color.parseColor("#0F172A"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleCol.addView(title);

        mHeaderSub = new TextView(mActivity);
        mHeaderSub.setText("페이지 정보를 불러오는 중...");
        mHeaderSub.setTextColor(Color.parseColor("#64748B"));
        mHeaderSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        titleCol.addView(mHeaderSub);

        bar.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 직접 번호 입력 점프 박스
        mJumpInput = new EditText(mActivity);
        mJumpInput.setHint("쪽 번호");
        mJumpInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        mJumpInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        mJumpInput.setSingleLine(true);
        mJumpInput.setGravity(Gravity.CENTER);
        mJumpInput.setTextColor(Color.parseColor("#0F172A"));
        mJumpInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        mJumpInput.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable inBg = new GradientDrawable();
        inBg.setColor(Color.parseColor("#F8FAFC"));
        inBg.setCornerRadius(dp(10));
        inBg.setStroke(dp(1), Color.parseColor("#CBD5E1"));
        mJumpInput.setBackground(inBg);
        mJumpInput.setOnEditorActionListener((v, actionId, event) -> {
            applyJumpInput();
            return true;
        });
        bar.addView(mJumpInput, new LinearLayout.LayoutParams(dp(84), dp(38)));

        Button btnJump = new Button(mActivity);
        btnJump.setText("찾기");
        btnJump.setAllCaps(false);
        btnJump.setTextColor(Color.WHITE);
        btnJump.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        btnJump.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable jBg = new GradientDrawable();
        jBg.setColor(Color.parseColor("#334155"));
        jBg.setCornerRadius(dp(10));
        btnJump.setBackground(jBg);
        btnJump.setOnClickListener(v -> applyJumpInput());
        LinearLayout.LayoutParams jlp = new LinearLayout.LayoutParams(dp(64), dp(38));
        jlp.leftMargin = dp(6);
        bar.addView(btnJump, jlp);

        Button btnX = new Button(mActivity);
        btnX.setText("✕");
        btnX.setTextColor(Color.parseColor("#64748B"));
        btnX.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        btnX.setBackgroundColor(Color.TRANSPARENT);
        btnX.setOnClickListener(v -> mDialog.dismiss());
        LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(dp(42), dp(38));
        xlp.leftMargin = dp(8);
        bar.addView(btnX, xlp);

        return bar;
    }

    private LinearLayout buildFooterBar() {
        LinearLayout bar = new LinearLayout(mActivity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(20), dp(12), dp(20), dp(12));

        if (mShowSaveCacheCheckbox) {
            mChkSaveCache = new CheckBox(mActivity);
            mChkSaveCache.setText("최근 파일 캐시에 저장 (최대 3GB)");
            mChkSaveCache.setChecked(false); // 기본값 체크 해제 (공용 칠판 실수 방지)
            mChkSaveCache.setTextColor(Color.parseColor("#1E293B"));
            mChkSaveCache.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            mChkSaveCache.setTypeface(Typeface.DEFAULT_BOLD);
            bar.addView(mChkSaveCache, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        bar.addView(new View(mActivity), new LinearLayout.LayoutParams(0, 1, 1f));

        Button btnCancel = new Button(mActivity);
        btnCancel.setText("취소");
        btnCancel.setAllCaps(false);
        btnCancel.setTextColor(Color.parseColor("#475569"));
        btnCancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        btnCancel.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable cBg = new GradientDrawable();
        cBg.setColor(Color.parseColor("#F1F5F9"));
        cBg.setCornerRadius(dp(12));
        btnCancel.setBackground(cBg);
        btnCancel.setOnClickListener(v -> mDialog.dismiss());
        bar.addView(btnCancel, new LinearLayout.LayoutParams(dp(104), dp(44)));

        mBtnConfirm = new Button(mActivity);
        mBtnConfirm.setText("확인");
        mBtnConfirm.setAllCaps(false);
        mBtnConfirm.setTextColor(Color.WHITE);
        mBtnConfirm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        mBtnConfirm.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable okBg = new GradientDrawable();
        okBg.setColor(Color.parseColor("#00A884"));
        okBg.setCornerRadius(dp(12));
        mBtnConfirm.setBackground(okBg);
        mBtnConfirm.setEnabled(false);
        mBtnConfirm.setOnClickListener(v -> {
            if (mPageCount <= 0) return;
            mConfirmed = true;
            int target = Math.max(0, Math.min(mPageCount - 1, mSelectedPage));
            boolean saveToCache = (mChkSaveCache != null && mChkSaveCache.isChecked());
            mDialog.dismiss();
            if (mListener != null) {
                mListener.onPageConfirmed(target, saveToCache);
            }
        });
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(dp(170), dp(44));
        olp.leftMargin = dp(12);
        bar.addView(mBtnConfirm, olp);

        return bar;
    }

    private void applyJumpInput() {
        if (mPageCount <= 0 || mJumpInput == null) return;
        String s = mJumpInput.getText().toString().trim();
        if (s.isEmpty()) return;
        try {
            int p = Integer.parseInt(s) - 1;
            p = Math.max(0, Math.min(mPageCount - 1, p));
            selectPage(p, true);
        } catch (Throwable ignored) {}
    }

    private void openPdfMetadataAsync() {
        if (mWorkerHandler == null) return;
        mWorkerHandler.post(() -> {
            try {
                ParcelFileDescriptor pfd = null;
                if (mFilePath != null && !mFilePath.isEmpty()) {
                    File f = new File(mFilePath);
                    if (f.exists()) {
                        pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                    }
                }
                if (pfd == null && mUri != null) {
                    pfd = mActivity.getContentResolver().openFileDescriptor(mUri, "r");
                }
                if (pfd == null) {
                    mUiHandler.post(() -> {
                        Toast.makeText(mActivity, "PDF 파일을 열 수 없습니다.", Toast.LENGTH_SHORT).show();
                        if (mDialog != null && mDialog.isShowing()) mDialog.dismiss();
                    });
                    return;
                }
                PdfRenderer renderer = new PdfRenderer(pfd);
                int count = renderer.getPageCount();
                synchronized (this) {
                    if (mClosed) {
                        renderer.close();
                        pfd.close();
                        return;
                    }
                    mPfd = pfd;
                    mRenderer = renderer;
                    mPageCount = count;
                }

                final int startIdx = Math.max(0, Math.min(count - 1, mInitialPage));
                mUiHandler.post(() -> {
                    if (mClosed) return;
                    mHeaderSub.setText("총 " + count + "쪽 · 페이지를 선택해 오른쪽에서 미리보기를 확인하세요");
                    mBtnConfirm.setEnabled(count > 0);
                    mAdapter.notifyDataSetChanged();
                    selectPage(startIdx, true);
                });
            } catch (Throwable t) {
                mUiHandler.post(() -> {
                    Toast.makeText(mActivity, "PDF 열기 실패: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                    if (mDialog != null && mDialog.isShowing()) mDialog.dismiss();
                });
            }
        });
    }

    private void selectPage(int pageIndex, boolean scrollGrid) {
        if (mPageCount <= 0) return;
        mSelectedPage = Math.max(0, Math.min(mPageCount - 1, pageIndex));
        mAdapter.notifyDataSetChanged();
        if (scrollGrid && mGridView != null) {
            mGridView.setSelection(Math.max(0, mSelectedPage - 6));
        }
        mPreviewCaption.setText("미리보기 · " + (mSelectedPage + 1) + " / " + mPageCount + " 쪽");
        mBtnConfirm.setText("확인 (" + (mSelectedPage + 1) + "쪽)");
        renderPreviewAsync(mSelectedPage);
    }

    private void renderPreviewAsync(final int pageIndex) {
        if (mWorkerHandler == null || mClosed) return;
        final int seq = mPreviewSeq.incrementAndGet();
        mPreviewLoading.setVisibility(View.VISIBLE);

        mWorkerHandler.post(() -> {
            if (seq != mPreviewSeq.get() || mClosed) return;
            Bitmap bmp = null;
            try {
                synchronized (this) {
                    if (mClosed || mRenderer == null || pageIndex < 0 || pageIndex >= mPageCount) return;
                    PdfRenderer.Page page = mRenderer.openPage(pageIndex);
                    int pw = Math.max(1, page.getWidth());
                    int ph = Math.max(1, page.getHeight());
                    int targetW = 1000;
                    int targetH = Math.max(1, Math.round(targetW * ((float) ph / (float) pw)));
                    if (targetH > 1400) {
                        targetH = 1400;
                        targetW = Math.max(1, Math.round(targetH * ((float) pw / (float) ph)));
                    }
                    bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(bmp);
                    c.drawColor(Color.WHITE);
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    page.close();
                }
            } catch (Throwable t) {
                if (bmp != null && !bmp.isRecycled()) bmp.recycle();
                bmp = null;
            }

            final Bitmap ready = bmp;
            mUiHandler.post(() -> {
                if (mClosed || seq != mPreviewSeq.get()) {
                    if (ready != null && !ready.isRecycled()) ready.recycle();
                    return;
                }
                mPreviewLoading.setVisibility(View.GONE);
                if (ready != null) {
                    mPreviewImage.setImageBitmap(ready);
                    if (mCurrentPreviewBmp != null && !mCurrentPreviewBmp.isRecycled()) {
                        mCurrentPreviewBmp.recycle();
                    }
                    mCurrentPreviewBmp = ready;
                }
            });
        });
    }

    private void cleanup() {
        mPreviewSeq.incrementAndGet();
        synchronized (this) {
            mClosed = true;
            try { if (mRenderer != null) mRenderer.close(); } catch (Throwable ignored) {}
            try { if (mPfd != null) mPfd.close(); } catch (Throwable ignored) {}
            mRenderer = null;
            mPfd = null;
        }
        if (mWorkerThread != null) {
            mWorkerThread.quitSafely();
            mWorkerThread = null;
            mWorkerHandler = null;
        }
        if (mPreviewImage != null) {
            mPreviewImage.setImageDrawable(null);
        }
        if (mCurrentPreviewBmp != null && !mCurrentPreviewBmp.isRecycled()) {
            mCurrentPreviewBmp.recycle();
            mCurrentPreviewBmp = null;
        }
    }

    private class PageGridAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mPageCount;
        }

        @Override
        public Object getItem(int position) {
            return position;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            LinearLayout cell;
            ImageView icon;
            TextView numTv;
            if (convertView == null) {
                cell = new LinearLayout(mActivity);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setPadding(dp(6), dp(10), dp(6), dp(10));

                icon = new ImageView(mActivity);
                icon.setImageResource(R.drawable.explore_edu_menu_pdf);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                cell.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));

                numTv = new TextView(mActivity);
                numTv.setSingleLine(true);
                numTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
                numTv.setTypeface(Typeface.DEFAULT_BOLD);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tlp.topMargin = dp(4);
                cell.addView(numTv, tlp);

                cell.setTag(new View[] { icon, numTv });
            } else {
                cell = (LinearLayout) convertView;
                View[] h = (View[]) cell.getTag();
                icon = (ImageView) h[0];
                numTv = (TextView) h[1];
            }

            boolean selected = (position == mSelectedPage);
            numTv.setText(String.valueOf(position + 1));

            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(12));
            if (selected) {
                bg.setColor(Color.parseColor("#00A884"));
                bg.setStroke(dp(2), Color.parseColor("#047857"));
                numTv.setTextColor(Color.WHITE);
                icon.setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN);
            } else {
                bg.setColor(Color.parseColor("#F8FAFC"));
                bg.setStroke(dp(1), Color.parseColor("#E2E8F0"));
                numTv.setTextColor(Color.parseColor("#1E293B"));
                icon.clearColorFilter();
            }
            cell.setBackground(bg);

            cell.setOnClickListener(v -> selectPage(position, false));
            return cell;
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

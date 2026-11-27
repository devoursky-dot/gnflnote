package kr.hs.gnflhs.gnflpdf;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 전자칠판 대화면 전용 HSV 커스텀 색상 피커
 * - 상단: 2D 채도(S) x 명도(V) 터치 팔레트
 * - 중단: 무지개 색상(Hue) 스펙트럼 슬라이더
 * - 하단: 교육용 추천 프리셋 12색 + 선택 색상 미리보기 & 적용
 */
public class ColorPickerDialog {

    public interface OnColorSelectedListener {
        void onColorSelected(int color);
        void onCancelled();
    }

    private static final int[] PRESETS = new int[] {
            0xFF0F172A, 0xFF475569, 0xFFEF4444, 0xFFF97316,
            0xFFFACC15, 0xFF22C55E, 0xFF06B6D4, 0xFF2563EB,
            0xFF7C3AED, 0xFFEC4899, 0xFF92400E, 0xFFFFFFFF
    };

    private final Activity mActivity;
    private final OnColorSelectedListener mListener;
    private final float[] mHsv = new float[] { 210f, 0.85f, 0.85f };
    private boolean mApplied = false;

    private Dialog mDialog;
    private SatValBoxView mSatValView;
    private HueBarView mHueBarView;
    private View mPreviewSwatch;
    private TextView mHexLabel;

    public ColorPickerDialog(Activity activity, int initialColor, OnColorSelectedListener listener) {
        mActivity = activity;
        mListener = listener;
        Color.colorToHSV(initialColor | 0xFF000000, mHsv);
    }

    public void show() {
        if (mActivity == null || mActivity.isFinishing()) return;

        mDialog = new Dialog(mActivity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        mDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        mDialog.setCanceledOnTouchOutside(true);
        mDialog.setOnDismissListener(d -> {
            if (!mApplied && mListener != null) mListener.onCancelled();
        });

        FrameLayout backdrop = new FrameLayout(mActivity);
        backdrop.setBackgroundColor(0x990F172A);
        backdrop.setOnClickListener(v -> mDialog.dismiss());

        LinearLayout card = new LinearLayout(mActivity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        card.setPadding(dp(22), dp(18), dp(22), dp(18));
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

        TextView title = new TextView(mActivity);
        title.setText("🎨 펜 색상 선택");
        title.setTextColor(Color.parseColor("#0F172A"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mPreviewSwatch = new View(mActivity);
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(dp(44), dp(28));
        swLp.rightMargin = dp(8);
        header.addView(mPreviewSwatch, swLp);

        mHexLabel = new TextView(mActivity);
        mHexLabel.setTextColor(Color.parseColor("#475569"));
        mHexLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        mHexLabel.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        header.addView(mHexLabel);

        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = dp(12);
        card.addView(header, hlp);

        // 2D 채도/명도 박스
        mSatValView = new SatValBoxView(mActivity);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(dp(380), dp(210));
        svLp.bottomMargin = dp(12);
        card.addView(mSatValView, svLp);

        // 무지개 색상(Hue) 바
        mHueBarView = new HueBarView(mActivity);
        LinearLayout.LayoutParams hbLp = new LinearLayout.LayoutParams(dp(380), dp(34));
        hbLp.bottomMargin = dp(14);
        card.addView(mHueBarView, hbLp);

        // 추천 프리셋 12색 (2줄 x 6개)
        TextView presetTitle = new TextView(mActivity);
        presetTitle.setText("추천 색상");
        presetTitle.setTextColor(Color.parseColor("#64748B"));
        presetTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        presetTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams ptlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ptlp.bottomMargin = dp(6);
        card.addView(presetTitle, ptlp);

        for (int row = 0; row < 2; row++) {
            LinearLayout pRow = new LinearLayout(mActivity);
            pRow.setOrientation(LinearLayout.HORIZONTAL);
            pRow.setGravity(Gravity.CENTER_VERTICAL);
            for (int col = 0; col < 6; col++) {
                final int presetColor = PRESETS[row * 6 + col];
                View dot = new View(mActivity);
                GradientDrawable gd = new GradientDrawable();
                gd.setShape(GradientDrawable.OVAL);
                gd.setColor(presetColor);
                gd.setStroke(dp(1), Color.parseColor("#CBD5E1"));
                dot.setBackground(gd);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(36), dp(36));
                dlp.setMargins(dp(6), dp(4), dp(6), dp(4));
                dot.setOnClickListener(v -> {
                    Color.colorToHSV(presetColor, mHsv);
                    syncViews();
                });
                pRow.addView(dot, dlp);
            }
            LinearLayout.LayoutParams prlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            prlp.gravity = Gravity.CENTER_HORIZONTAL;
            card.addView(pRow, prlp);
        }

        // 하단 취소/적용 버튼
        LinearLayout footer = new LinearLayout(mActivity);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.END);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(16);

        Button btnCancel = new Button(mActivity);
        btnCancel.setText("취소");
        btnCancel.setAllCaps(false);
        btnCancel.setTextColor(Color.parseColor("#475569"));
        btnCancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        btnCancel.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable cBg = new GradientDrawable();
        cBg.setColor(Color.parseColor("#F1F5F9"));
        cBg.setCornerRadius(dp(10));
        btnCancel.setBackground(cBg);
        btnCancel.setOnClickListener(v -> mDialog.dismiss());
        footer.addView(btnCancel, new LinearLayout.LayoutParams(dp(92), dp(42)));

        Button btnOk = new Button(mActivity);
        btnOk.setText("적용");
        btnOk.setAllCaps(false);
        btnOk.setTextColor(Color.WHITE);
        btnOk.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        btnOk.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable oBg = new GradientDrawable();
        oBg.setColor(Color.parseColor("#00A884"));
        oBg.setCornerRadius(dp(10));
        btnOk.setBackground(oBg);
        btnOk.setOnClickListener(v -> {
            mApplied = true;
            int picked = Color.HSVToColor(0xFF, mHsv);
            mDialog.dismiss();
            if (mListener != null) mListener.onColorSelected(picked);
        });
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(dp(116), dp(42));
        olp.leftMargin = dp(10);
        footer.addView(btnOk, olp);

        card.addView(footer, flp);

        backdrop.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        mDialog.setContentView(backdrop);
        syncViews();
        mDialog.show();
    }

    private void syncViews() {
        int c = Color.HSVToColor(0xFF, mHsv);
        if (mPreviewSwatch != null) {
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(c);
            gd.setCornerRadius(dp(8));
            gd.setStroke(dp(1), Color.parseColor("#CBD5E1"));
            mPreviewSwatch.setBackground(gd);
        }
        if (mHexLabel != null) {
            mHexLabel.setText(String.format("#%06X", (c & 0xFFFFFF)));
        }
        if (mSatValView != null) mSatValView.invalidate();
        if (mHueBarView != null) mHueBarView.invalidate();
    }

    private class SatValBoxView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();

        SatValBoxView(Context ctx) {
            super(ctx);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            mRingPaint.setStyle(Paint.Style.STROKE);
            mRingPaint.setStrokeWidth(dp(3));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            mRect.set(0, 0, w, h);

            int hueColor = Color.HSVToColor(new float[] { mHsv[0], 1f, 1f });
            Shader satShader = new LinearGradient(0, 0, w, 0, Color.WHITE, hueColor, Shader.TileMode.CLAMP);
            Shader valShader = new LinearGradient(0, 0, 0, h, Color.WHITE, Color.BLACK, Shader.TileMode.CLAMP);
            mPaint.setShader(new ComposeShader(satShader, valShader, PorterDuff.Mode.MULTIPLY));
            canvas.drawRoundRect(mRect, dp(12), dp(12), mPaint);

            float cx = mHsv[1] * w;
            float cy = (1f - mHsv[2]) * h;
            mRingPaint.setColor(Color.WHITE);
            canvas.drawCircle(cx, cy, dp(10), mRingPaint);
            mRingPaint.setColor(0xAA0F172A);
            mRingPaint.setStrokeWidth(dp(1.5f));
            canvas.drawCircle(cx, cy, dp(11.5f), mRingPaint);
            mRingPaint.setStrokeWidth(dp(3));
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            int act = ev.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_MOVE) {
                float w = Math.max(1f, getWidth());
                float h = Math.max(1f, getHeight());
                mHsv[1] = Math.max(0f, Math.min(1f, ev.getX() / w));
                mHsv[2] = Math.max(0f, Math.min(1f, 1f - ev.getY() / h));
                syncViews();
                return true;
            }
            return true;
        }
    }

    private class HueBarView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private final int[] mSpectrum = new int[] {
                0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF,
                0xFF0000FF, 0xFFFF00FF, 0xFFFF0000
        };

        HueBarView(Context ctx) {
            super(ctx);
            mThumbPaint.setStyle(Paint.Style.STROKE);
            mThumbPaint.setStrokeWidth(dp(3));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            float pad = dp(6);
            mRect.set(pad, dp(4), w - pad, h - dp(4));
            mPaint.setShader(new LinearGradient(mRect.left, 0, mRect.right, 0, mSpectrum, null, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(mRect, dp(12), dp(12), mPaint);

            float cx = mRect.left + (mHsv[0] / 360f) * mRect.width();
            mThumbPaint.setColor(Color.WHITE);
            canvas.drawCircle(cx, h * 0.5f, dp(12), mThumbPaint);
            mThumbPaint.setColor(0xAA0F172A);
            mThumbPaint.setStrokeWidth(dp(1.5f));
            canvas.drawCircle(cx, h * 0.5f, dp(13.5f), mThumbPaint);
            mThumbPaint.setStrokeWidth(dp(3));
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            int act = ev.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_MOVE) {
                float pad = dp(6);
                float usable = Math.max(1f, getWidth() - pad * 2f);
                float frac = Math.max(0f, Math.min(1f, (ev.getX() - pad) / usable));
                mHsv[0] = frac * 359.5f;
                syncViews();
                return true;
            }
            return true;
        }
    }

    private int dp(float v) {
        return Math.round(v * mActivity.getResources().getDisplayMetrics().density);
    }
}

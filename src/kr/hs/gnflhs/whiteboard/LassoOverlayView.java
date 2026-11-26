package kr.hs.gnflhs.whiteboard;

import android.content.Context;
import android.graphics.*;
import android.util.TypedValue;
import android.view.View;

import java.util.List;

/**
 * GNFL NOTE V1.3 - 올가미 툴 실시간 선택 오버레이 뷰
 * 
 * 올가미 모드 활성화 시 상단에 안내 배너를 표시하고,
 * 드래그하는 동안 점선 테두리와 반투명 채우기로 선택 영역을 실시간 시각화합니다.
 */
public class LassoOverlayView extends View {
    private final Paint mStrokePaint;
    private final Paint mFillPaint;
    private final Paint mBannerBgPaint;
    private final Paint mBannerTextPaint;

    private final Path mLassoPath = new Path();
    private boolean mActiveMode = false;
    private boolean mHasPath = false;

    public LassoOverlayView(Context context) {
        super(context);

        mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mStrokePaint.setColor(Color.parseColor("#0EA5E9"));
        mStrokePaint.setStyle(Paint.Style.STROKE);
        mStrokePaint.setStrokeWidth(dp(3));
        mStrokePaint.setStrokeCap(Paint.Cap.ROUND);
        mStrokePaint.setStrokeJoin(Paint.Join.ROUND);
        mStrokePaint.setPathEffect(new DashPathEffect(new float[] { dp(10), dp(6) }, 0));

        mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mFillPaint.setColor(Color.argb(48, 14, 165, 233));
        mFillPaint.setStyle(Paint.Style.FILL);

        mBannerBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBannerBgPaint.setColor(Color.argb(220, 15, 23, 42));
        mBannerBgPaint.setStyle(Paint.Style.FILL);

        mBannerTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBannerTextPaint.setColor(Color.WHITE);
        mBannerTextPaint.setTextSize(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 15, context.getResources().getDisplayMetrics()));
        mBannerTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
        mBannerTextPaint.setTextAlign(Paint.Align.CENTER);
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    public void setActiveMode(boolean active) {
        mActiveMode = active;
        if (!active) {
            mLassoPath.reset();
            mHasPath = false;
        }
        postInvalidate();
    }

    public void updatePath(List<PointF> points) {
        mLassoPath.reset();
        if (points != null && points.size() >= 2) {
            PointF p0 = points.get(0);
            mLassoPath.moveTo(p0.x, p0.y);
            for (int i = 1; i < points.size(); i++) {
                PointF pi = points.get(i);
                mLassoPath.lineTo(pi.x, pi.y);
            }
            mHasPath = true;
        } else {
            mHasPath = false;
        }
        postInvalidate();
    }

    public void clearPath() {
        mLassoPath.reset();
        mHasPath = false;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!mActiveMode) return;

        // 1. 선택 중인 올가미 영역 표시
        if (mHasPath) {
            Path closedPreview = new Path(mLassoPath);
            closedPreview.close();
            canvas.drawPath(closedPreview, mFillPaint);
            canvas.drawPath(closedPreview, mStrokePaint);
        }

        // 2. 상단 중앙 올가미 모드 안내 배너
        String msg = "✂️ 올가미 모드: 오려낼 영역을 드래그하세요 (버튼 재클릭 시 취소)";
        float textW = mBannerTextPaint.measureText(msg);
        float padH = dp(18);
        float padV = dp(10);
        float boxW = textW + padH * 2f;
        float boxH = dp(40);
        float cx = getWidth() * 0.5f;
        float top = dp(42);
        RectF pill = new RectF(cx - boxW * 0.5f, top, cx + boxW * 0.5f, top + boxH);
        canvas.drawRoundRect(pill, dp(20), dp(20), mBannerBgPaint);

        Paint.FontMetrics fm = mBannerTextPaint.getFontMetrics();
        float textY = pill.centerY() - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(msg, cx, textY, mBannerTextPaint);
    }
}

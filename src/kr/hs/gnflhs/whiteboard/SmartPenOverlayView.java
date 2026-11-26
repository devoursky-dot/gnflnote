package kr.hs.gnflhs.whiteboard;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/**
 * GNFL NOTE V1.1 - 스마트 펜 프리뷰 오버레이 뷰
 * 
 * 펜으로 드래그하다 멈춘 순간부터 손을 떼기 전까지만 보정된 도형(직선/원/스플라인)의
 * 실시간 미리보기를 표시합니다. 손을 떼면 이 프리뷰는 즉시 사라지고
 * 순정 칠판 레이어에 진짜 판서 객체로 직접 합쳐집니다.
 */
public class SmartPenOverlayView extends View {
    public enum ShapeType { NONE, LINE, CIRCLE, ELLIPSE, POLYGON, SPLINE }

    private final Paint mPaint;
    private ShapeType mActiveType = ShapeType.NONE;
    private float mLineAx, mLineAy, mLineBx, mLineBy;
    private float mCircleCx, mCircleCy, mCircleR;
    private float mEllipseRx, mEllipseRy, mEllipseAngleRad;
    private final RectF mEllipseRect = new RectF();
    private float[] mPolyCorners = null;
    private Path mActiveSplinePath = null;

    public SmartPenOverlayView(Context context) {
        super(context);
        mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        mPaint.setColor(Color.WHITE);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mPaint.setStrokeJoin(Paint.Join.ROUND);
        mPaint.setStrokeWidth(6.0f);
    }

    public void setStrokeColor(int color) {
        if (color == 0 || color == Color.TRANSPARENT) {
            color = Color.WHITE;
        }
        mPaint.setColor(color);
    }

    public void setStrokeWidth(float width) {
        mPaint.setStrokeWidth(Math.max(3.0f, width));
    }

    public void showRecognizedLine(float ax, float ay, float bx, float by) {
        mActiveType = ShapeType.LINE;
        mLineAx = ax; mLineAy = ay;
        mLineBx = bx; mLineBy = by;
        mActiveSplinePath = null;
        postInvalidate();
    }

    public void updateRecognizedLineEnd(float bx, float by) {
        if (mActiveType == ShapeType.LINE) {
            mLineBx = bx; mLineBy = by;
            postInvalidate();
        }
    }

    public void showRecognizedCircle(float cx, float cy, float r) {
        mActiveType = ShapeType.CIRCLE;
        mCircleCx = cx; mCircleCy = cy;
        mCircleR = r;
        mActiveSplinePath = null;
        postInvalidate();
    }

    public void showRecognizedEllipse(float cx, float cy, float rx, float ry, float angleRad) {
        mActiveType = ShapeType.ELLIPSE;
        mCircleCx = cx; mCircleCy = cy;
        mEllipseRx = rx; mEllipseRy = ry;
        mEllipseAngleRad = angleRad;
        mActiveSplinePath = null;
        postInvalidate();
    }

    public void translateRecognizedCircle(float dx, float dy) {
        if (mActiveType == ShapeType.CIRCLE || mActiveType == ShapeType.ELLIPSE) {
            mCircleCx += dx;
            mCircleCy += dy;
            postInvalidate();
        }
    }

    public void showRecognizedPolygon(float[] corners) {
        if (corners == null || corners.length < 6) return;
        mActiveType = ShapeType.POLYGON;
        mPolyCorners = corners.clone();
        mActiveSplinePath = null;
        postInvalidate();
    }

    public void translateRecognizedPolygon(float dx, float dy) {
        if (mActiveType == ShapeType.POLYGON && mPolyCorners != null) {
            for (int i = 0; i < mPolyCorners.length; i += 2) {
                mPolyCorners[i] += dx;
                mPolyCorners[i + 1] += dy;
            }
            postInvalidate();
        }
    }

    public void showRecognizedSpline(Path splinePath) {
        mActiveType = ShapeType.SPLINE;
        mActiveSplinePath = new Path(splinePath);
        mPolyCorners = null;
        postInvalidate();
    }

    public void clearPreview() {
        mActiveType = ShapeType.NONE;
        mActiveSplinePath = null;
        mPolyCorners = null;
        postInvalidate();
    }

    public ShapeType getActiveType() {
        return mActiveType;
    }

    public float getLineAx() { return mLineAx; }
    public float getLineAy() { return mLineAy; }
    public float getLineBx() { return mLineBx; }
    public float getLineBy() { return mLineBy; }
    public float getCircleCx() { return mCircleCx; }
    public float getCircleCy() { return mCircleCy; }
    public float getCircleR() { return mCircleR; }
    public float getEllipseRx() { return mEllipseRx; }
    public float getEllipseRy() { return mEllipseRy; }
    public float getEllipseAngleRad() { return mEllipseAngleRad; }
    public float[] getPolyCorners() { return mPolyCorners; }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (mActiveType == ShapeType.LINE) {
            canvas.drawLine(mLineAx, mLineAy, mLineBx, mLineBy, mPaint);
        } else if (mActiveType == ShapeType.CIRCLE) {
            canvas.drawCircle(mCircleCx, mCircleCy, mCircleR, mPaint);
        } else if (mActiveType == ShapeType.ELLIPSE) {
            canvas.save();
            canvas.translate(mCircleCx, mCircleCy);
            canvas.rotate((float) Math.toDegrees(mEllipseAngleRad));
            mEllipseRect.set(-mEllipseRx, -mEllipseRy, mEllipseRx, mEllipseRy);
            canvas.drawOval(mEllipseRect, mPaint);
            canvas.restore();
        } else if (mActiveType == ShapeType.POLYGON && mPolyCorners != null && mPolyCorners.length >= 6) {
            Paint.Join prevJoin = mPaint.getStrokeJoin();
            mPaint.setStrokeJoin(Paint.Join.MITER);
            mPaint.setStrokeMiter(10f);
            Path p = new Path();
            p.moveTo(mPolyCorners[0], mPolyCorners[1]);
            for (int i = 2; i < mPolyCorners.length; i += 2) {
                p.lineTo(mPolyCorners[i], mPolyCorners[i + 1]);
            }
            p.close();
            canvas.drawPath(p, mPaint);
            mPaint.setStrokeJoin(prevJoin);
        } else if (mActiveType == ShapeType.SPLINE && mActiveSplinePath != null) {
            canvas.drawPath(mActiveSplinePath, mPaint);
        }
    }
}

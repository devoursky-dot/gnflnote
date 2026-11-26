package kr.hs.gnflhs.whiteboard;

import android.app.Activity;
import android.graphics.*;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.*;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * GNFL NOTE V1.1 - 스마트 펜 엔진 관리자 (순정 레이어 완전 통합 아키텍처)
 * 
 * 펜으로 드래그하다 멈추면(pause ~280ms):
 * 1. 시작점 근처로 돌아온 폐곡선 -> 원(Circle)으로 자동 생성
 * 2. 직선에 가까운 궤적 -> 깔끔한 직선(Straight Line)으로 자동 변환
 * 3. 일반 곡선 궤적 -> 균등 리샘플링 + Taubin(λ|μ) 수축 방지 스무딩 + Catmull-Rom 스플라인으로
 *    원래 궤적의 폭과 진폭을 유지하면서 극도로 부드러운 곡선으로 자동 보정
 * 
 * 핵심 아키텍처 (레이어 완전 통합):
 * - 도형 인식 후 손을 떼는 순간, 보정된 정밀 좌표를 순정 칠판 판서 레이어에 직접 주입(Commit)합니다.
 * - 따라서 별도 오버레이에 남지 않고 순정 판서 객체와 완전히 합쳐져,
 *   지우개(부분/오브젝트/손바닥/전체 지우기), 확대/축소, 화면 이동, 선택/이동, Undo/Redo,
 *   페이지 저장 및 내보내기가 100% 순정과 동일하게 완벽히 동작합니다.
 */
public class SmartPenManager {
    private static final String TAG = "SmartPenManager";
    private static final int PAUSE_THRESHOLD_MS = 280;

    private static SmartPenManager sInstance;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private SmartPenOverlayView mOverlayView;
    private Activity mCurrentActivity;

    private final ArrayList<PointF> mPoints = new ArrayList<PointF>();
    private final ArrayList<PointF> mRecognizedSplinePoints = new ArrayList<PointF>();

    private int mActivePointerId = -1;
    private boolean mIsTracking = false;
    private boolean mRecognized = false;
    private boolean mIsReplaying = false;

    private long mDownTime = 0L;
    private float mLastX, mLastY;
    private float mStartX, mStartY;

    // 원본 터치 속성 보존 (순정 레이어 주입 시 동일한 펜 종류/굵기 유지)
    private int mLastToolType = MotionEvent.TOOL_TYPE_FINGER;
    private float mLastPressure = 1.0f;
    private float mLastSize = 0.05f;
    private float mLastTouchMajor = 0.0f;
    private float mLastTouchMinor = 0.0f;

    private int mCurrentColor = Color.WHITE;
    private float mCurrentWidth = 6.0f;
    private int mShapePageCountBefore = -1;
    private Object mShapeLastObjBefore = null;
    private boolean mShapeTouchDown = false;
    private Runnable mPendingShapeWidthTask = null;

    private final Runnable mPauseRunnable = new Runnable() {
        @Override
        public void run() {
            onPauseDetected();
        }
    };

    public static synchronized SmartPenManager getInstance() {
        if (sInstance == null) {
            sInstance = new SmartPenManager();
        }
        return sInstance;
    }

    /**
     * PlainWhiteboardActivity.dispatchTouchEvent 에서 직접 호출되는 진입점 후크
     */
    public static boolean dispatchTouchEvent(final Activity activity, final MotionEvent ev) {
        if (activity == null || ev == null) return false;

        SmartPenManager mgr = SmartPenManager.getInstance();

        // 순정 레이어에 보정된 도형을 주입(Replay) 중인 경우 가로채지 않고 순정으로 직행
        if (mgr.mIsReplaying) {
            return forwardToStock(activity, ev);
        }

        // V1.3 올가미 툴 모드인 경우 올가미 영역 선택 우선 처리
        try {
            if (LassoCaptureManager.getInstance().handleTouch(activity, ev)) {
                return true;
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in LassoCaptureManager.handleTouch", t);
        }

        boolean intercepted = false;
        try {
            intercepted = mgr.handleTouch(activity, ev);
        } catch (Throwable t) {
            Log.e(TAG, "Error in SmartPenManager.handleTouch", t);
        }

        if (intercepted) {
            return true;
        }

        // V1.7 도형 툴 선 두께 슬라이더 연동: 일반 2D/3D 도형 + 손모양(스마트 도형 비동기 변환) 모두 지정한 두께를 즉시 적용!
        // (새 터치 시작 시 이전 예약 작업을 즉시 취소하고, 드래그 중에는 절대 리페인트하지 않아 이중 도형 생성 원천 차단!)
        try {
            int act = ev.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN) {
                mgr.mShapeTouchDown = true;
                if (mgr.mPendingShapeWidthTask != null) {
                    mgr.mHandler.removeCallbacks(mgr.mPendingShapeWidthTask);
                    mgr.mPendingShapeWidthTask = null;
                }
                View shapeBtn = ToolbarFitHelper.findViewByIdName(activity, "explore_edu_toolbar_shape");
                if (shapeBtn != null && shapeBtn.isSelected() && DietManager.getSavedShapeWidth() > 0f) {
                    mgr.mShapePageCountBefore = DietManager.getCurrentPageShapeCount(activity);
                    mgr.mShapeLastObjBefore = DietManager.getLastPageShape(activity);
                } else {
                    mgr.mShapePageCountBefore = -1;
                    mgr.mShapeLastObjBefore = null;
                }
            } else if (act == MotionEvent.ACTION_CANCEL) {
                mgr.mShapeTouchDown = false;
                mgr.mShapePageCountBefore = -1;
                mgr.mShapeLastObjBefore = null;
                if (mgr.mPendingShapeWidthTask != null) {
                    mgr.mHandler.removeCallbacks(mgr.mPendingShapeWidthTask);
                    mgr.mPendingShapeWidthTask = null;
                }
            } else if (act == MotionEvent.ACTION_UP) {
                mgr.mShapeTouchDown = false;
                if (mgr.mShapePageCountBefore >= 0) {
                    final int prevCount = mgr.mShapePageCountBefore;
                    final Object prevLast = mgr.mShapeLastObjBefore;
                    mgr.mShapePageCountBefore = -1;
                    mgr.mShapeLastObjBefore = null;
                    final Runnable applyTask = new Runnable() {
                        @Override
                        public void run() {
                            if (mgr.mShapeTouchDown) return; // 다음 도형 드래그 중이면 절대 실행 금지!
                            boolean applied = DietManager.applySavedWidthToNewShapes(activity, prevCount, prevLast);
                            if (applied && mgr.mPendingShapeWidthTask == this) {
                                // 일반 2D/3D 도형은 55ms에 즉시 적용 완료 -> 남은 예약(400ms/900ms) 즉시 취소!
                                mgr.mHandler.removeCallbacks(this);
                                mgr.mPendingShapeWidthTask = null;
                            }
                        }
                    };
                    mgr.mPendingShapeWidthTask = applyTask;
                    mgr.mHandler.postDelayed(applyTask, 55);
                    mgr.mHandler.postDelayed(applyTask, 400);
                    mgr.mHandler.postDelayed(applyTask, 900);
                }
            }
        } catch (Throwable ignored) {}

        return forwardToStock(activity, ev);
    }

    private static boolean forwardToStock(Activity activity, MotionEvent ev) {
        boolean handled = false;
        try {
            Window win = activity.getWindow();
            if (win != null) {
                handled = win.superDispatchTouchEvent(ev);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in superDispatchTouchEvent", t);
        }

        if (!handled) {
            try {
                handled = activity.onTouchEvent(ev);
            } catch (Throwable ignored) {}
        }
        return handled;
    }

    private void ensureOverlay(Activity activity) {
        if (mOverlayView != null && mCurrentActivity == activity) return;

        mCurrentActivity = activity;
        try {
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            if (mOverlayView != null && mOverlayView.getParent() != null) {
                ((ViewGroup) mOverlayView.getParent()).removeView(mOverlayView);
            }

            mOverlayView = new SmartPenOverlayView(activity);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );
            decor.addView(mOverlayView, lp);
            DietManager.applyDiet(activity);
            Log.i(TAG, "Attached SmartPenOverlayView (preview-only) to DecorView + Applied V1.2 Diet");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to attach SmartPenOverlayView", t);
        }
    }

    private boolean checkEraserOrNonWriteMode(Activity activity, MotionEvent ev) {
        // 1. 툴바의 지우개 버튼이 선택된 경우
        View eraserBtn = findToolbarButton(activity, "explore_edu_toolbar_eraser");
        if (eraserBtn != null && eraserBtn.isSelected()) {
            return true;
        }
        // 2. 툴바의 쓰기 버튼이 선택되지 않은 경우 (선택, 이동 등)
        View writeBtn = findToolbarButton(activity, "explore_edu_toolbar_write");
        if (writeBtn != null && !writeBtn.isSelected()) {
            return true;
        }
        // 3. 멀티터치(확대/축소/화면이동) 또는 지우개 툴타입/손바닥 지우개
        if (ev != null) {
            if (ev.getPointerCount() > 1) {
                return true;
            }
            int idx = ev.getActionIndex();
            if (idx >= 0 && idx < ev.getPointerCount()) {
                if (ev.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER) {
                    return true;
                }
                if (ev.getTouchMajor(idx) > 48.0f) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean handleTouch(Activity activity, MotionEvent ev) {
        ensureOverlay(activity);

        int action = ev.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                mActivePointerId = ev.getPointerId(0);
                mDownTime = ev.getDownTime();
                float x = ev.getX();
                float y = ev.getY();
                mStartX = mLastX = x;
                mStartY = mLastY = y;
                mPoints.clear();
                mPoints.add(new PointF(x, y));
                mRecognized = false;

                // 원본 터치 속성 저장
                mLastToolType = ev.getToolType(0);
                mLastPressure = ev.getPressure(0);
                mLastSize = ev.getSize(0);
                mLastTouchMajor = ev.getTouchMajor(0);
                mLastTouchMinor = ev.getTouchMinor(0);

                // 하단 툴바 영역 터치 시 제외 + V1.2 다이어트 팝업 스캔 예약
                if (mOverlayView != null && y > mOverlayView.getHeight() - 210) {
                    mIsTracking = false;
                    DietManager.schedulePopupDiet(activity);
                    return false;
                }

                // 쓰기 모드가 아니거나 지우개/손바닥인 경우 순정으로 그대로 통과
                if (checkEraserOrNonWriteMode(activity, ev)) {
                    mIsTracking = false;
                    return false;
                }

                // 형광펜 모드에서 팝업 색상표/컬러휠로 색을 바꾼 직후에도 반투명 HIGHLIGHTER + 3배 굵기 즉시 보장!
                if (QuickColorBarManager.getInstance().isHighlighterMode()) {
                    QuickColorBarManager.getInstance().onPopupColorPicked(activity);
                }

                queryStockPenInfo(activity);

                mIsTracking = true;
                if (mOverlayView != null) {
                    mOverlayView.setStrokeColor(mCurrentColor);
                    mOverlayView.setStrokeWidth(mCurrentWidth);
                }

                mHandler.removeCallbacks(mPauseRunnable);
                mHandler.postDelayed(mPauseRunnable, PAUSE_THRESHOLD_MS);
                return false;
            }

            case MotionEvent.ACTION_POINTER_DOWN: {
                // 두 손가락 이상(확대/축소/이동) 감지 시 즉시 스마트 펜 트래킹 취소
                mHandler.removeCallbacks(mPauseRunnable);
                mIsTracking = false;
                mRecognized = false;
                if (mOverlayView != null) {
                    mOverlayView.clearPreview();
                }
                return false;
            }

            case MotionEvent.ACTION_MOVE: {
                if (ev.getPointerCount() > 1 || checkEraserOrNonWriteMode(activity, ev)) {
                    mHandler.removeCallbacks(mPauseRunnable);
                    mIsTracking = false;
                    mRecognized = false;
                    if (mOverlayView != null) {
                        mOverlayView.clearPreview();
                    }
                    return false;
                }

                if (!mIsTracking) return false;

                int pointerIndex = ev.findPointerIndex(mActivePointerId);
                if (pointerIndex < 0) return false;

                float x = ev.getX(pointerIndex);
                float y = ev.getY(pointerIndex);
                mPoints.add(new PointF(x, y));

                // 도형 인식 후 드래그: 직선은 끝점 조절, 원은 위치 이동! (순정 전달 차단)
                if (mRecognized) {
                    if (mOverlayView != null) {
                        SmartPenOverlayView.ShapeType st = mOverlayView.getActiveType();
                        if (st == SmartPenOverlayView.ShapeType.LINE) {
                            mOverlayView.updateRecognizedLineEnd(x, y);
                        } else if (st == SmartPenOverlayView.ShapeType.CIRCLE) {
                            mOverlayView.translateRecognizedCircle(x - mLastX, y - mLastY);
                        } else if (st == SmartPenOverlayView.ShapeType.POLYGON) {
                            mOverlayView.translateRecognizedPolygon(x - mLastX, y - mLastY);
                        }
                    }
                    mLastX = x;
                    mLastY = y;
                    return true;
                }

                float dist = (float) Math.hypot(x - mLastX, y - mLastY);
                if (dist > 8.0f) {
                    mHandler.removeCallbacks(mPauseRunnable);
                    mHandler.postDelayed(mPauseRunnable, PAUSE_THRESHOLD_MS);
                    mLastX = x;
                    mLastY = y;
                }
                return false;
            }

            case MotionEvent.ACTION_UP: {
                mHandler.removeCallbacks(mPauseRunnable);

                if (mOverlayView != null && ev.getY() > mOverlayView.getHeight() - 210) {
                    DietManager.schedulePopupDiet(activity);
                }

                if (mRecognized && mOverlayView != null) {
                    SmartPenOverlayView.ShapeType finalType = mOverlayView.getActiveType();
                    float[] polyCorners = (finalType == SmartPenOverlayView.ShapeType.POLYGON && mOverlayView.getPolyCorners() != null)
                            ? mOverlayView.getPolyCorners().clone() : null;
                    List<PointF> finalPts = (polyCorners == null) ? buildFinalReplayPoints() : null;
                    // 2. 임시 프리뷰 즉시 제거
                    mOverlayView.clearPreview();
                    mRecognized = false;
                    mIsTracking = false;
                    // 3. 순정 칠판 판서 레이어에 합체:
                    //    - 다각형(삼각형/사각형): 각 변을 독립 직선 획으로 주입해 모서리가 둥글게 뭉개지지 않고 칼같이 각지게 완성!
                    //    - 직선/원/스플라인: 단일 정밀 획으로 주입
                    if (polyCorners != null && polyCorners.length >= 6) {
                        replayPolygonEdgesToStock(activity, polyCorners);
                    } else if (finalPts != null && finalPts.size() >= 2) {
                        replayStrokeToStock(activity, finalPts);
                    }
                    if (QuickColorBarManager.getInstance().isHighlighterMode()) {
                        final Activity actRef = activity;
                        mHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                ToolbarFitHelper.forceRepaintWhiteboard(actRef);
                            }
                        }, 75);
                    }
                    return true;
                }

                // 형광펜 모드에서 실제 획(3포인트 이상) 필기 종료 직후에만 벡터 재렌더링으로 세그먼트 금(이음새) 제거 (단순 탭 번쩍임 방지)
                if (mIsTracking && mPoints.size() >= 3 && QuickColorBarManager.getInstance().isHighlighterMode()) {
                    final Activity actRef = activity;
                    mHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            ToolbarFitHelper.forceRepaintWhiteboard(actRef);
                        }
                    }, 75);
                }

                // V1.7: '선택' 툴로 PDF 링크 / QR코드를 짧게 탭하면 연결 확인 팝업 실행
                if (mOverlayView != null && ev.getY() < mOverlayView.getHeight() - 210
                        && ev.getPointerCount() == 1
                        && (ev.getEventTime() - mDownTime) < 450L
                        && Math.hypot(ev.getX() - mStartX, ev.getY() - mStartY) < 28.0f) {
                    View selectBtn = ToolbarFitHelper.findViewByIdName(activity, "explore_edu_toolbar_choice");
                    if (selectBtn != null && selectBtn.isSelected()) {
                        PdfLinkQrManager.getInstance().handleSelectTap(activity, ev.getX(), ev.getY());
                    }
                }

                mIsTracking = false;
                mRecognized = false;
                return false;
            }

            case MotionEvent.ACTION_CANCEL: {
                mHandler.removeCallbacks(mPauseRunnable);
                if (mOverlayView != null) {
                    mOverlayView.clearPreview();
                }
                mIsTracking = false;
                mRecognized = false;
                return false;
            }
        }
        return false;
    }

    /**
     * 일반 판서 획으로 그릴 때 겹침이나 어긋남이 없는 정밀 원주 좌표 생성:
     * - 오버랩 없이 정확히 0° → 360° 1바퀴만 생성 (두 번 겹쳐 그려지는 현상 0%)
     * - 시작 직후 0.5° 접선 가이드 포인트를 두어 순정 엔진의 첫 점 스무딩 지연으로 인한 어긋남 방지
     * - 마지막 점(360°)을 시작점(0°)과 소수점까지 완전히 동일한 좌표로 닫음
     */
    private List<PointF> buildCircleReplayPoints(float cx, float cy, float r) {
        List<PointF> pts = new ArrayList<PointF>();
        int steps = 120; // 3도 간격의 초고밀도 균일 원주
        float startX = cx + r;
        float startY = cy;
        pts.add(new PointF(startX, startY));
        // 시작 접선 방향 안정화 포인트 (0.5도)
        double leadAngle = Math.toRadians(0.5);
        pts.add(new PointF(
                cx + (float) (r * Math.cos(leadAngle)),
                cy + (float) (r * Math.sin(leadAngle))
        ));
        for (int i = 1; i < steps; i++) {
            double angle = 2.0 * Math.PI * i / steps;
            pts.add(new PointF(
                    cx + (float) (r * Math.cos(angle)),
                    cy + (float) (r * Math.sin(angle))
            ));
        }
        // 끝점을 시작점과 완벽히 동일한 좌표로 닫음 (오버랩 없음!)
        pts.add(new PointF(startX, startY));
        return pts;
    }

    /**
     * 삼각형/사각형의 각 변을 독립 직선 획으로 순정 판서 레이어에 순차 주입합니다.
     * - 한 획에 이어 그릴 때 발생하는 순정 베지어 스무딩의 모서리 둥글림을 100% 제거해
     *   자 대고 그은 것처럼 날카로운 각진 꼭짓점을 만듭니다.
     */
    private void replayPolygonEdgesToStock(Activity activity, float[] corners) {
        if (activity == null || corners == null || corners.length < 6) return;
        int n = corners.length / 2;
        for (int i = 0; i < n; i++) {
            float ax = corners[i * 2];
            float ay = corners[i * 2 + 1];
            int nxt = (i + 1) % n;
            float bx = corners[nxt * 2];
            float by = corners[nxt * 2 + 1];
            float edgeLen = (float) Math.hypot(bx - ax, by - ay);
            int steps = Math.max(8, Math.min(48, (int) (edgeLen / 12.0f)));
            List<PointF> edgePts = new ArrayList<PointF>(steps + 1);
            for (int s = 0; s <= steps; s++) {
                float t = (float) s / steps;
                edgePts.add(new PointF(ax + (bx - ax) * t, ay + (by - ay) * t));
            }
            replayStrokeToStock(activity, edgePts);
        }
    }

    /**
     * 확정된 도형(직선/원/삼각형/사각형/스플라인)을 순정 판서 레이어에 주입할 정밀 좌표 리스트로 변환합니다.
     */
    private List<PointF> buildFinalReplayPoints() {
        List<PointF> pts = new ArrayList<PointF>();
        if (mOverlayView == null) return pts;

        SmartPenOverlayView.ShapeType type = mOverlayView.getActiveType();
        if (type == SmartPenOverlayView.ShapeType.LINE) {
            float ax = mOverlayView.getLineAx();
            float ay = mOverlayView.getLineAy();
            float bx = mOverlayView.getLineBx();
            float by = mOverlayView.getLineBy();
            float len = (float) Math.hypot(bx - ax, by - ay);
            int steps = Math.max(8, Math.min(60, (int) (len / 14.0f)));
            for (int i = 0; i <= steps; i++) {
                float t = (float) i / steps;
                pts.add(new PointF(ax + (bx - ax) * t, ay + (by - ay) * t));
            }
        } else if (type == SmartPenOverlayView.ShapeType.CIRCLE) {
            pts.addAll(buildCircleReplayPoints(
                    mOverlayView.getCircleCx(),
                    mOverlayView.getCircleCy(),
                    mOverlayView.getCircleR()
            ));
        } else if (type == SmartPenOverlayView.ShapeType.SPLINE) {
            pts.addAll(mRecognizedSplinePoints);
        }
        return pts;
    }

    /**
     * 보정된 정밀 좌표 시퀀스를 순정 칠판(WhiteboardView)에 직접 주입하여
     * 순정 판서 레이어와 100% 하나로 합칩니다.
     */
    private void replayStrokeToStock(Activity activity, List<PointF> pts) {
        if (activity == null || pts == null || pts.size() < 2) return;
        Window win = activity.getWindow();
        if (win == null) return;

        mIsReplaying = true;
        try {
            long baseTime = SystemClock.uptimeMillis();

            MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[1];
            props[0] = new MotionEvent.PointerProperties();
            props[0].id = 0;
            props[0].toolType = (mLastToolType == MotionEvent.TOOL_TYPE_ERASER)
                    ? MotionEvent.TOOL_TYPE_FINGER : mLastToolType;

            MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
            coords[0] = new MotionEvent.PointerCoords();
            coords[0].pressure = (mLastPressure > 0f) ? mLastPressure : 1.0f;
            coords[0].size = (mLastSize > 0f) ? mLastSize : 0.05f;
            // 손바닥 지우개로 오인되지 않도록 touchMajor 안전 상한 적용
            coords[0].touchMajor = Math.min(mLastTouchMajor, 25.0f);
            coords[0].touchMinor = Math.min(mLastTouchMinor, 25.0f);

            // 1. ACTION_DOWN
            PointF p0 = pts.get(0);
            coords[0].x = p0.x;
            coords[0].y = p0.y;
            MotionEvent downEv = MotionEvent.obtain(
                    baseTime, baseTime, MotionEvent.ACTION_DOWN,
                    1, props, coords, 0, 0, 1.0f, 1.0f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            );
            win.superDispatchTouchEvent(downEv);
            downEv.recycle();

            // 2. ACTION_MOVE (일정한 6ms 간격으로 균일하게 재생하여 고속 예측 튕김/이음새 어긋남 방지)
            long t = baseTime;
            for (int i = 1; i < pts.size(); i++) {
                t += 6;
                PointF pi = pts.get(i);
                coords[0].x = pi.x;
                coords[0].y = pi.y;
                MotionEvent moveEv = MotionEvent.obtain(
                        baseTime, t, MotionEvent.ACTION_MOVE,
                        1, props, coords, 0, 0, 1.0f, 1.0f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
                );
                win.superDispatchTouchEvent(moveEv);
                moveEv.recycle();
            }

            // 3. 마지막 점 정착용 MOVE + ACTION_UP (순정 레이어에 정식 커밋!)
            t += 6;
            PointF pLast = pts.get(pts.size() - 1);
            coords[0].x = pLast.x;
            coords[0].y = pLast.y;
            MotionEvent settleEv = MotionEvent.obtain(
                    baseTime, t, MotionEvent.ACTION_MOVE,
                    1, props, coords, 0, 0, 1.0f, 1.0f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            );
            win.superDispatchTouchEvent(settleEv);
            settleEv.recycle();

            t += 6;
            MotionEvent upEv = MotionEvent.obtain(
                    baseTime, t, MotionEvent.ACTION_UP,
                    1, props, coords, 0, 0, 1.0f, 1.0f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            );
            win.superDispatchTouchEvent(upEv);
            upEv.recycle();
        } catch (Throwable err) {
            Log.e(TAG, "Error in replayStrokeToStock", err);
        } finally {
            mIsReplaying = false;
        }
    }

    /**
     * 손을 멈췄을 때(Hold ~280ms) 도형 인식 및 원래 선 소거
     */
    private void onPauseDetected() {
        if (!mIsTracking || mPoints.size() < 4 || mOverlayView == null || mCurrentActivity == null) return;

        int count = mPoints.size();
        float[] raw = new float[count * 2];
        for (int i = 0; i < count; i++) {
            PointF pt = mPoints.get(i);
            raw[i * 2] = pt.x;
            raw[i * 2 + 1] = pt.y;
        }

        float scale = 1.0f;
        float sx = raw[0], sy = raw[1];
        float ex = raw[(count - 1) * 2], ey = raw[(count - 1) * 2 + 1];
        float closureDist = (float) Math.hypot(ex - sx, ey - sy);

        // 1. 닫힌 도형(삼각형 / 사각형 / 원) 검출
        InkShapeRecognizer.Shape closedShape = InkShapeRecognizer.detectClosed(raw, count, scale);
        if (closedShape != null
                && (closedShape.kind == InkShapeRecognizer.Kind.TRIANGLE
                    || closedShape.kind == InkShapeRecognizer.Kind.RECTANGLE)
                && closedShape.corners != null && closedShape.corners.length >= 6) {
            discardStockStroke(mCurrentActivity);
            mOverlayView.setStrokeColor(mCurrentColor);
            mOverlayView.setStrokeWidth(mCurrentWidth);
            mOverlayView.showRecognizedPolygon(closedShape.corners);
            mRecognized = true;
            String label = (closedShape.kind == InkShapeRecognizer.Kind.TRIANGLE)
                    ? "🔺 삼각형 자동 완성" : "⬜ 사각형 자동 완성";
            Log.i(TAG, "SmartPen recognized " + closedShape.kind
                    + " (" + (closedShape.corners.length / 2) + " corners, err=" + closedShape.confidence + ")");
            showToast(mCurrentActivity, label);
            return;
        }

        boolean isCircle = (closedShape != null && closedShape.kind == InkShapeRecognizer.Kind.CIRCLE)
                || (closedShape == null && count >= 10 && closureDist < 85.0f);

        if (isCircle) {
            float minX = sx, maxX = sx, minY = sy, maxY = sy;
            for (int i = 0; i < count; i++) {
                minX = Math.min(minX, raw[i * 2]);
                maxX = Math.max(maxX, raw[i * 2]);
                minY = Math.min(minY, raw[i * 2 + 1]);
                maxY = Math.max(maxY, raw[i * 2 + 1]);
            }
            float cx = (minX + maxX) * 0.5f;
            float cy = (minY + maxY) * 0.5f;
            float r = (closedShape != null) ? closedShape.radius : ((maxX - minX + maxY - minY) * 0.25f);

            if (r > 15.0f) {
                discardStockStroke(mCurrentActivity);
                mOverlayView.setStrokeColor(mCurrentColor);
                mOverlayView.setStrokeWidth(mCurrentWidth);
                mOverlayView.showRecognizedCircle(cx, cy, r);
                mRecognized = true;
                showToast(mCurrentActivity, "⭕ 원 자동 완성");
                return;
            }
        }

        // 2. 직선(Line) 검출: 궤적이 직선에 가까운 경우
        if (InkShapeRecognizer.looksStraight(raw, count, scale)) {
            discardStockStroke(mCurrentActivity);
            mOverlayView.setStrokeColor(mCurrentColor);
            mOverlayView.setStrokeWidth(mCurrentWidth);
            mOverlayView.showRecognizedLine(sx, sy, ex, ey);
            mRecognized = true;
            showToast(mCurrentActivity, "📏 직선 자동 보정");
            return;
        }

        // 3. 수축 없는 극강 부드러운 스플라인 곡선 (Taubin λ|μ + Catmull-Rom)
        if (count >= 6) {
            mRecognizedSplinePoints.clear();
            Path splinePath = buildVolumePreservingSpline(raw, count, mRecognizedSplinePoints);
            discardStockStroke(mCurrentActivity);
            mOverlayView.setStrokeColor(mCurrentColor);
            mOverlayView.setStrokeWidth(mCurrentWidth);
            mOverlayView.showRecognizedSpline(splinePath);
            mRecognized = true;
            showToast(mCurrentActivity, "〰️ 스플라인 곡선 보정");
        }
    }

    /**
     * 궤적의 폭/진폭(크기)을 유지하면서 극도로 부드럽게 만드는 스플라인 생성기:
     * 1) 아크 길이 균등 리샘플링 (간격 ~38px, 6~18개 제어점) -> 잔떨림 제거 + 형태 디테일 보존
     * 2) Taubin λ|μ 수축 방지 스무딩 (10회 반복: λ=+0.45, μ=-0.47) -> 궤적이 줄어들지 않고 매끄럽게 정제
     * 3) Catmull-Rom 큐빅 스플라인 보간 -> 제어점을 부드럽게 통과하여 원래 궤적 크기 그대로 유지
     */
    private static Path buildVolumePreservingSpline(float[] raw, int count, List<PointF> outPoints) {
        Path output = new Path();
        if (count <= 0 || raw == null) return output;
        if (count <= 2) {
            output.moveTo(raw[0], raw[1]);
            outPoints.add(new PointF(raw[0], raw[1]));
            for (int i = 1; i < count; i++) {
                output.lineTo(raw[i * 2], raw[i * 2 + 1]);
                outPoints.add(new PointF(raw[i * 2], raw[i * 2 + 1]));
            }
            return output;
        }

        // 1단계: 전체 아크 길이 계산
        float[] cumLen = new float[count];
        cumLen[0] = 0f;
        for (int i = 1; i < count; i++) {
            float dx = raw[i * 2] - raw[(i - 1) * 2];
            float dy = raw[i * 2 + 1] - raw[(i - 1) * 2 + 1];
            cumLen[i] = cumLen[i - 1] + (float) Math.hypot(dx, dy);
        }
        float totalLen = cumLen[count - 1];
        if (totalLen < 10.0f) {
            output.moveTo(raw[0], raw[1]);
            output.lineTo(raw[(count - 1) * 2], raw[(count - 1) * 2 + 1]);
            outPoints.add(new PointF(raw[0], raw[1]));
            outPoints.add(new PointF(raw[(count - 1) * 2], raw[(count - 1) * 2 + 1]));
            return output;
        }

        // 핵심 제어점 리샘플링 (6 ~ 18개, 간격 약 38px)
        int numCtrl = Math.max(6, Math.min(18, (int) (totalLen / 38.0f) + 1));
        float[] cx = new float[numCtrl];
        float[] cy = new float[numCtrl];

        cx[0] = raw[0];
        cy[0] = raw[1];
        cx[numCtrl - 1] = raw[(count - 1) * 2];
        cy[numCtrl - 1] = raw[(count - 1) * 2 + 1];

        int seg = 0;
        for (int k = 1; k < numCtrl - 1; k++) {
            float target = totalLen * k / (numCtrl - 1);
            while (seg < count - 2 && cumLen[seg + 1] < target) {
                seg++;
            }
            float segLen = cumLen[seg + 1] - cumLen[seg];
            float t = (segLen > 1e-4f) ? (target - cumLen[seg]) / segLen : 0f;
            cx[k] = raw[seg * 2] + (raw[(seg + 1) * 2] - raw[seg * 2]) * t;
            cy[k] = raw[seg * 2 + 1] + (raw[(seg + 1) * 2 + 1] - raw[seg * 2 + 1]) * t;
        }

        // 2단계: Taubin λ|μ 수축 방지 스무딩 (10회 반복)
        // λ=+0.45로 잔떨림 제거 후 μ=-0.47로 진폭(볼록함) 복원 -> 궤적이 절대 줄어들지 않음!
        final float lambda = 0.45f;
        final float mu = -0.47f;
        float[] tx = new float[numCtrl];
        float[] ty = new float[numCtrl];

        for (int pass = 0; pass < 10; pass++) {
            // Pass A: λ 스무딩
            tx[0] = cx[0]; ty[0] = cy[0];
            tx[numCtrl - 1] = cx[numCtrl - 1]; ty[numCtrl - 1] = cy[numCtrl - 1];
            for (int k = 1; k < numCtrl - 1; k++) {
                float avgX = 0.5f * (cx[k - 1] + cx[k + 1]);
                float avgY = 0.5f * (cy[k - 1] + cy[k + 1]);
                tx[k] = cx[k] + lambda * (avgX - cx[k]);
                ty[k] = cy[k] + lambda * (avgY - cy[k]);
            }
            // Pass B: μ 팽창 복원
            cx[0] = tx[0]; cy[0] = ty[0];
            cx[numCtrl - 1] = tx[numCtrl - 1]; cy[numCtrl - 1] = ty[numCtrl - 1];
            for (int k = 1; k < numCtrl - 1; k++) {
                float avgX = 0.5f * (tx[k - 1] + tx[k + 1]);
                float avgY = 0.5f * (ty[k - 1] + ty[k + 1]);
                cx[k] = tx[k] + mu * (avgX - tx[k]);
                cy[k] = ty[k] + mu * (avgY - ty[k]);
            }
        }

        // 3단계: Catmull-Rom 큐빅 스플라인 평가 (제어점을 매끄럽게 통과하여 궤적 크기 보존!)
        output.moveTo(cx[0], cy[0]);
        outPoints.add(new PointF(cx[0], cy[0]));

        final int stepsPerSeg = 8;
        for (int i = 0; i < numCtrl - 1; i++) {
            int i0 = Math.max(0, i - 1);
            int i1 = i;
            int i2 = i + 1;
            int i3 = Math.min(numCtrl - 1, i + 2);

            float p0x = cx[i0], p0y = cy[i0];
            float p1x = cx[i1], p1y = cy[i1];
            float p2x = cx[i2], p2y = cy[i2];
            float p3x = cx[i3], p3y = cy[i3];

            for (int s = 1; s <= stepsPerSeg; s++) {
                float t = (float) s / stepsPerSeg;
                float t2 = t * t;
                float t3 = t2 * t;

                float evalX = 0.5f * (
                        (2.0f * p1x) +
                        (-p0x + p2x) * t +
                        (2.0f * p0x - 5.0f * p1x + 4.0f * p2x - p3x) * t2 +
                        (-p0x + 3.0f * p1x - 3.0f * p2x + p3x) * t3
                );
                float evalY = 0.5f * (
                        (2.0f * p1y) +
                        (-p0y + p2y) * t +
                        (2.0f * p0y - 5.0f * p1y + 4.0f * p2y - p3y) * t2 +
                        (-p0y + 3.0f * p1y - 3.0f * p2y + p3y) * t3
                );
                output.lineTo(evalX, evalY);
                outPoints.add(new PointF(evalX, evalY));
            }
        }
        return output;
    }

    /**
     * 순정 앱의 현재 진행 중인 획을 취소하고 프론트버퍼 및 커밋된 원래 획을 동기식으로 소거합니다.
     */
    private void discardStockStroke(final Activity activity) {
        if (activity == null) return;
        try {
            View wbView = null;
            int offsetX = 0;
            int offsetY = 0;
            try {
                Field f = activity.getClass().getDeclaredField("c");
                f.setAccessible(true);
                wbView = (View) f.get(activity);
                if (wbView != null) {
                    int[] loc = new int[2];
                    wbView.getLocationInWindow(loc);
                    offsetX = loc[0];
                    offsetY = loc[1];
                }
            } catch (Throwable ignored) {}

            // 1. 순정 앱에 정확한 WhiteboardView 기준 좌표로 ACTION_CANCEL 주입
            long now = SystemClock.uptimeMillis();
            long downTime = (mDownTime > 0) ? mDownTime : now;
            float cancelX = mLastX - offsetX;
            float cancelY = mLastY - offsetY;
            MotionEvent cancelEv = MotionEvent.obtain(downTime, now, MotionEvent.ACTION_CANCEL, cancelX, cancelY, 0);
            Window win = activity.getWindow();
            if (win != null) {
                win.superDispatchTouchEvent(cancelEv);
            }
            cancelEv.recycle();

            // 2. 하드웨어 가속기 프론트버퍼 즉각 소거
            try {
                Class<?> cvtClass = Class.forName("com.seewo.easinote.accelerator.base.CvtRenderNativeInterface");
                Method cleanMethod = cvtClass.getMethod("clean", Rect.class);
                cleanMethod.invoke(null, (Object) null);
            } catch (Throwable ignored) {}

            // 3. 순정 WhiteboardView 새로고침
            if (wbView != null) {
                wbView.invalidate();
                wbView.postInvalidate();
            }

            // 4. 순정 실행 취소(Undo) 동기 실행 -> 원래 삐뚤빼뚤한 획 완벽 제거
            View undoBtn = findToolbarButton(activity, "explore_edu_toolbar_back", "explore_edu_toolbar_undo");
            if (undoBtn != null) {
                undoBtn.performClick();
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in discardStockStroke", t);
        }
    }

    private void queryStockPenInfo(Activity activity) {
        try {
            Object penObj = null;

            try {
                Field f = activity.getClass().getDeclaredField("e");
                f.setAccessible(true);
                Object presenterOrView = f.get(activity);
                if (presenterOrView != null) {
                    Method m = presenterOrView.getClass().getMethod("getPen");
                    penObj = m.invoke(presenterOrView);
                }
            } catch (Throwable ignored) {}

            if (penObj == null) {
                View root = activity.getWindow().getDecorView();
                penObj = findPenFromToolbar(root);
            }

            if (penObj != null) {
                try {
                    Method mColor = penObj.getClass().getMethod("getColor");
                    int c = (Integer) mColor.invoke(penObj);
                    if (c != 0 && c != Color.TRANSPARENT) {
                        mCurrentColor = c;
                    }
                } catch (Throwable ignored) {}

                try {
                    Method mWidth = penObj.getClass().getMethod("getWidth");
                    float w = (Float) mWidth.invoke(penObj);
                    if (w > 0.5f) {
                        mCurrentWidth = w;
                    }
                } catch (Throwable ignored) {}
                return;
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in queryStockPenInfo", t);
        }

        mCurrentColor = Color.WHITE;
        mCurrentWidth = 6.0f;
    }

    private static Object findPenFromToolbar(View root) {
        if (root == null) return null;
        if ("ToolbarView".equals(root.getClass().getSimpleName())) {
            try {
                Method m = root.getClass().getMethod("getPen");
                return m.invoke(root);
            } catch (Throwable ignored) {}
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                Object res = findPenFromToolbar(vg.getChildAt(i));
                if (res != null) return res;
            }
        }
        return null;
    }

    private static View findToolbarButton(Activity activity, String... idNames) {
        if (activity == null) return null;
        for (String idName : idNames) {
            try {
                int resId = activity.getResources().getIdentifier(idName, "id", "com.seewo.easinote");
                if (resId == 0) {
                    resId = activity.getResources().getIdentifier(idName, "id", activity.getPackageName());
                }
                if (resId != 0) {
                    View v = activity.findViewById(resId);
                    if (v != null) return v;
                }
            } catch (Throwable ignored) {}
        }

        try {
            View root = activity.getWindow().getDecorView();
            return searchViewTreeForId(root, idNames);
        } catch (Throwable ignored) {}
        return null;
    }

    private static View searchViewTreeForId(View root, String... idNames) {
        if (root == null) return null;
        int id = root.getId();
        if (id != View.NO_ID) {
            try {
                String entryName = root.getResources().getResourceEntryName(id);
                for (String target : idNames) {
                    if (target.equals(entryName)) {
                        return root;
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View res = searchViewTreeForId(vg.getChildAt(i), idNames);
                if (res != null) return res;
            }
        }
        return null;
    }

    private static void showToast(final Activity activity, final String msg) {
        if (activity == null) return;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    ToolbarFitHelper.showTopToast(activity, msg);
                } catch (Throwable ignored) {}
            }
        });
    }
}

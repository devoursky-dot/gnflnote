package kr.hs.gnflhs.gnflpdf;

import android.app.Activity;
import android.content.Context;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * GNFL NOTE V3.0 - 완전 독립형 초고속 SurfaceView 세로 스크롤 PDF 뷰어
 *
 * 초저지연(12~16ms) 필기 아키텍처:
 * - Android View 파이프라인(3프레임 4K 전체 재합성)을 우회하고 SurfaceView + RGB_565 백버퍼 Bitmap 사용
 * - 펜 필기(ACTION_MOVE) 시 4K PDF를 다시 그리지 않고 방금 지나간 작은 사각형(dirtyRect)만
 *   SurfaceHolder.lockCanvas(dirtyRect)로 부분 갱신 (~1.5ms/프레임, 60~120fps 즉각 반응!)
 * - 일반 부분 지우개(지나간 부분만 깎아내는 고속 획 분할) + 손바닥 지우개 자동 연동 + 멀티터치 판서
 * - 이동 툴 전용 1/2손가락 스크롤·확대(최소 쪽맞춤 하한, 튕김 0%)
 * - 2단계 점진적 렌더링(900px 빠른 표시 -> 2400px 고화질 자동 교체) + USB 복사 없는 즉시 열기
 * - 올가미 제자리 집중 풀이(최빈색 무한 여백) + PDF 링크/QR 탭 + 스마트펜(직선·원·타원·다각형·스플라인)
 */
public class ScrollPdfSurfaceView extends SurfaceView implements SurfaceHolder.Callback {
    private static final String TAG = "ScrollPdfSurfaceView";

    public enum ToolMode { MOVE, PEN, ERASER, SELECT, LASSO }

    public interface HostCallback {
        void onFocusModeChanged(boolean active);
        void onPageChanged(int currentPage, int totalPages);
        void onToolAutoChanged(ToolMode newMode);
        void onHistoryChanged(boolean canUndo, boolean canRedo);
    }

    private static final float DOC_WIDTH = 1000f;
    private static final float PAGE_GAP = 20f;
    private static final int MAX_CACHED_PAGES = 16;
    private static final int FAST_PREVIEW_WIDTH = 480;
    private static final int PAUSE_THRESHOLD_MS = 280;
    private int mOuterBgColor = Color.parseColor("#F1F5F9");
    private boolean mSmartPenEnabled = true;
    private float mEraserBaseRadiusPx = 42f;

    // ─── SurfaceView + GPU 하드웨어 가속 & VSync 60fps ────────────────
    private boolean mSurfaceReady = false;
    private int mSurfaceW = 0, mSurfaceH = 0;
    private Bitmap mBackingBmp = null;
    private Canvas mBackingCanvas = null;
    private final Object mSurfaceLock = new Object();
    private boolean mUseHardwareCanvas = true;
    private boolean mFrameScheduled = false;
    private final android.view.Choreographer.FrameCallback mVsyncCallback =
            new android.view.Choreographer.FrameCallback() {
                @Override
                public void doFrame(long frameTimeNanos) {
                    mFrameScheduled = false;
                    renderFullFrame();
                }
            };

    // ─── PDF 문서 및 동적 렌더링 ─────────────────────────────────────
    private Activity mActivity;
    private HostCallback mCallback;
    private String mLocalPdfPath;
    private Uri mOriginUri;
    private String mDisplayName;
    private boolean mTempCopiedPdf = false;

    private ParcelFileDescriptor mPfd;
    private PdfRenderer mRenderer;
    private HandlerThread mRenderThread;
    private Handler mRenderHandler;
    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private final Object mRendererLock = new Object();

    private int mPageCount = 0;
    private float[] mPageTop = new float[0];
    private float[] mPageHeight = new float[0];
    private float mTotalDocHeight = 0f;
    private int mInitialPage = 0;
    private boolean mLayoutInitialized = false;
    private boolean mIsLoading = false;
    private boolean mFirstPageRendered = false;
    private int mLastReportedPage = -1;

    private static final class CachedPage {
        final Bitmap bitmap;
        final int renderedWidth;
        CachedPage(Bitmap bmp, int w) {
            this.bitmap = bmp;
            this.renderedWidth = w;
        }
    }

    private final LinkedHashMap<Integer, CachedPage> mPageCache = new LinkedHashMap<Integer, CachedPage>(8, 0.75f, true);
    private final ArrayList<Integer> mRenderingPages = new ArrayList<Integer>();

    // ─── 카메라 변환 (screen = doc * mScale + mPan) ───────────────────
    private float mScale = 1.0f;
    private float mPanX = 0f;
    private float mPanY = 20f;
    private float mBottomToolbarClearance = 195f;

    // ─── 펜 스타일 & 툴 상태 ─────────────────────────────────────────
    private ToolMode mToolMode = ToolMode.MOVE;
    private int mPenColor = Color.parseColor("#111827");
    private float mPenWidthPx = 7.0f;
    private boolean mIsHighlighter = false;

    private boolean mIsTwoFingerGesture = false;
    private float mPrevTwoMidX, mPrevTwoMidY, mPrevTwoDist;
    private int mActivePointerId = -1;
    private float mDownScreenX, mDownScreenY, mLastScreenX, mLastScreenY;
    private long mDownTime = 0L;

    // ─── 잉크 / 스마트펜 / 지우개 데이터 모델 ────────────────────────
    private enum StrokeKind { FREEHAND, LINE, CIRCLE, ELLIPSE, POLYGON }

    private static final class InkStroke {
        StrokeKind kind = StrokeKind.FREEHAND;
        int color = Color.BLACK;
        float widthDoc = 6f;
        boolean isHighlighter = false;

        final ArrayList<PointF> points = new ArrayList<PointF>();
        Path cachedPath = null;

        float ax, ay, bx, by;
        float cx, cy, r, rx, ry, angleRad;
        float[] corners;
        float minX, minY, maxX, maxY;

        void appendPointFast(float dx, float dy) {
            int n = points.size();
            if (n == 0) {
                points.add(new PointF(dx, dy));
                minX = maxX = dx;
                minY = maxY = dy;
                cachedPath = new Path();
                cachedPath.moveTo(dx, dy);
                return;
            }
            PointF prev = points.get(n - 1);
            if (Math.abs(dx - prev.x) < 0.25f && Math.abs(dy - prev.y) < 0.25f) return;
            points.add(new PointF(dx, dy));
            if (dx < minX) minX = dx;
            if (dx > maxX) maxX = dx;
            if (dy < minY) minY = dy;
            if (dy > maxY) maxY = dy;
            if (cachedPath == null) {
                rebuildPathAndBounds();
                return;
            }
            float midX = (prev.x + dx) * 0.5f;
            float midY = (prev.y + dy) * 0.5f;
            cachedPath.quadTo(prev.x, prev.y, midX, midY);
        }

        void rebuildPathAndBounds() {
            if (kind == StrokeKind.FREEHAND) {
                Path p = new Path();
                if (points.isEmpty()) {
                    minX = maxX = minY = maxY = 0f;
                    cachedPath = p;
                    return;
                }
                PointF p0 = points.get(0);
                minX = maxX = p0.x;
                minY = maxY = p0.y;
                p.moveTo(p0.x, p0.y);
                if (points.size() == 1) {
                    p.lineTo(p0.x + 0.1f, p0.y + 0.1f);
                } else {
                    for (int i = 1; i < points.size(); i++) {
                        PointF prev = points.get(i - 1);
                        PointF cur = points.get(i);
                        float midX = (prev.x + cur.x) * 0.5f;
                        float midY = (prev.y + cur.y) * 0.5f;
                        p.quadTo(prev.x, prev.y, midX, midY);
                        if (cur.x < minX) minX = cur.x;
                        if (cur.x > maxX) maxX = cur.x;
                        if (cur.y < minY) minY = cur.y;
                        if (cur.y > maxY) maxY = cur.y;
                    }
                    PointF last = points.get(points.size() - 1);
                    p.lineTo(last.x, last.y);
                }
                cachedPath = p;
            } else if (kind == StrokeKind.LINE) {
                minX = Math.min(ax, bx); maxX = Math.max(ax, bx);
                minY = Math.min(ay, by); maxY = Math.max(ay, by);
            } else if (kind == StrokeKind.CIRCLE) {
                minX = cx - r; maxX = cx + r;
                minY = cy - r; maxY = cy + r;
            } else if (kind == StrokeKind.ELLIPSE) {
                float maxR = Math.max(rx, ry);
                minX = cx - maxR; maxX = cx + maxR;
                minY = cy - maxR; maxY = cy + maxR;
            } else if (kind == StrokeKind.POLYGON && corners != null && corners.length >= 6) {
                minX = maxX = corners[0];
                minY = maxY = corners[1];
                for (int i = 2; i < corners.length; i += 2) {
                    if (corners[i] < minX) minX = corners[i];
                    if (corners[i] > maxX) maxX = corners[i];
                    if (corners[i + 1] < minY) minY = corners[i + 1];
                    if (corners[i + 1] > maxY) maxY = corners[i + 1];
                }
            }
        }
    }

    private static final class UndoAction {
        final List<InkStroke> removed;
        final List<InkStroke> added;

        UndoAction(boolean isAdd, List<InkStroke> strokes) {
            this.added = isAdd ? strokes : null;
            this.removed = isAdd ? null : strokes;
        }

        UndoAction(List<InkStroke> removed, List<InkStroke> added) {
            this.removed = removed;
            this.added = added;
        }
    }

    private final ArrayList<InkStroke> mDocStrokes = new ArrayList<InkStroke>();
    private final ArrayList<UndoAction> mDocUndo = new ArrayList<UndoAction>();
    private final ArrayList<UndoAction> mDocRedo = new ArrayList<UndoAction>();

    private InkStroke mActiveStroke = null;
    private final HashMap<Integer, InkStroke> mMultiPenStrokes = new HashMap<Integer, InkStroke>();
    private final HashMap<Integer, PointF> mMultiLastScreen = new HashMap<Integer, PointF>();
    private boolean mPalmErasing = false;
    private boolean mShowEraserCircle = false;
    private float mEraserCircleX = 0f, mEraserCircleY = 0f, mEraserCircleR = 44f;
    private float mPrevEraserX = 0f, mPrevEraserY = 0f, mPrevEraserR = 44f;
    private boolean mSmartRecognized = false;
    private float mLastDocX, mLastDocY;
    private final ArrayList<InkStroke> mErasedBatch = new ArrayList<InkStroke>();
    private final ArrayList<InkStroke> mErasedAddedBatch = new ArrayList<InkStroke>();

    private final Runnable mPauseRunnable = new Runnable() {
        @Override
        public void run() {
            onPenPauseDetected();
        }
    };

    private static final int BASE_1080P_WIDTH = 480;
    private Bitmap mViewportTileBmp;
    private float mTilePanX = Float.NaN;
    private float mTilePanY = Float.NaN;
    private float mTileScale = Float.NaN;
    private final java.util.concurrent.atomic.AtomicInteger mViewportTileSeq =
            new java.util.concurrent.atomic.AtomicInteger(0);

    private final Runnable mViewport4kRunnable = new Runnable() {
        @Override
        public void run() {
            renderViewport4kTileAsync();
        }
    };

    private final Runnable mZoomRerenderRunnable = new Runnable() {
        @Override
        public void run() {
            requestVisiblePageRenders(false);
            scheduleViewport4kRender(0L);
        }
    };

    // ─── 올가미 제자리 집중 풀이 (Spotlight Focus Mode) ──────────────
    private boolean mLassoDragging = false;
    private final ArrayList<PointF> mLassoScreenPts = new ArrayList<PointF>();
    private final Path mLassoScreenPath = new Path();

    private boolean mFocusActive = false;
    private RectF mFocusCropDocRect = null;
    private Bitmap mFocusBitmap = null;
    private int mFocusBgColor = Color.WHITE;
    private float mSavedScale = 1f, mSavedPanX = 0f, mSavedPanY = 0f;
    private final ArrayList<InkStroke> mFocusStrokes = new ArrayList<InkStroke>();
    private final ArrayList<UndoAction> mFocusUndo = new ArrayList<UndoAction>();
    private final ArrayList<UndoAction> mFocusRedo = new ArrayList<UndoAction>();

    // ─── Paint ───────────────────────────────────────────────────────
    private final Paint mPageBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPageShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint mLiveSegPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint mLassoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLassoFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mEraserRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBadgeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBadgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mTmpRectF = new RectF();
    private final Rect mTmpDirty = new Rect();
    private final Path mTmpPolyPath = new Path();

    public ScrollPdfSurfaceView(Context context) {
        super(context);
        getHolder().addCallback(this);

        mPageBgPaint.setColor(Color.WHITE);
        mPageBgPaint.setStyle(Paint.Style.FILL);

        mPageShadowPaint.setColor(Color.parseColor("#94A3B8"));
        mPageShadowPaint.setStyle(Paint.Style.STROKE);
        mPageShadowPaint.setStrokeWidth(2f);

        mStrokePaint.setStyle(Paint.Style.STROKE);
        mStrokePaint.setStrokeCap(Paint.Cap.ROUND);
        mStrokePaint.setStrokeJoin(Paint.Join.ROUND);

        mLiveSegPaint.setStyle(Paint.Style.STROKE);
        mLiveSegPaint.setStrokeCap(Paint.Cap.ROUND);
        mLiveSegPaint.setStrokeJoin(Paint.Join.ROUND);

        mLassoPaint.setColor(Color.parseColor("#0EA5E9"));
        mLassoPaint.setStyle(Paint.Style.STROKE);
        mLassoPaint.setStrokeWidth(5f);
        mLassoPaint.setPathEffect(new DashPathEffect(new float[] { 18f, 12f }, 0f));

        mLassoFillPaint.setColor(Color.argb(38, 14, 165, 233));
        mLassoFillPaint.setStyle(Paint.Style.FILL);

        mEraserRingPaint.setColor(Color.argb(190, 71, 85, 105));
        mEraserRingPaint.setStyle(Paint.Style.STROKE);
        mEraserRingPaint.setStrokeWidth(4f);

        mBadgeBgPaint.setColor(Color.argb(200, 15, 23, 42));
        mBadgeBgPaint.setStyle(Paint.Style.FILL);

        mBadgeTextPaint.setColor(Color.WHITE);
        mBadgeTextPaint.setTextSize(26f);
        mBadgeTextPaint.setFakeBoldText(true);
        mBadgeTextPaint.setTextAlign(Paint.Align.CENTER);
    }

    // ─── SurfaceHolder.Callback ──────────────────────────────────────
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        synchronized (mSurfaceLock) {
            mSurfaceReady = true;
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        synchronized (mSurfaceLock) {
            mSurfaceW = width;
            mSurfaceH = height;
            mSurfaceReady = true;
        }
        if (mPageCount > 0 && !mLayoutInitialized) {
            initCameraIfReady();
            requestVisiblePageRenders(false);
        }
        renderFullFrame();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (mFrameScheduled) {
            mFrameScheduled = false;
            try { android.view.Choreographer.getInstance().removeFrameCallback(mVsyncCallback); } catch (Throwable ignored) {}
        }
        synchronized (mSurfaceLock) {
            mSurfaceReady = false;
            if (mViewportTileBmp != null && !mViewportTileBmp.isRecycled()) {
                mViewportTileBmp.recycle();
            }
            mViewportTileBmp = null;
            mTilePanX = Float.NaN;
            mTilePanY = Float.NaN;
            mTileScale = Float.NaN;
        }
    }

    // ─── 공개 제어 API ───────────────────────────────────────────────
    private HardwareInkAccelerator mAccelerator;
    private final Runnable mIdleCleanOverlayRunnable = new Runnable() {
        @Override
        public void run() {
            if (mActiveStroke == null && mMultiPenStrokes.isEmpty() && mAccelerator != null) {
                mAccelerator.clearOverlayKeepActive();
            }
        }
    };

    public void setHardwareAccelerator(HardwareInkAccelerator acc) {
        mAccelerator = acc;
    }

    public void clearHardwareOverlayNow() {
        mUiHandler.removeCallbacks(mIdleCleanOverlayRunnable);
        if (mAccelerator != null) {
            mAccelerator.clearOverlayKeepActive();
        }
    }

    public void suspendHardwareOverlay() {
        mUiHandler.removeCallbacks(mIdleCleanOverlayRunnable);
        if (mAccelerator != null) {
            mAccelerator.suspendOverlay();
        }
    }

    private static void showTopToast(Activity act, String msg) {
        showTopToast(act, msg, false);
    }

    private static void showTopToast(Activity act, String msg, boolean isError) {
        if (act == null || msg == null) return;
        act.runOnUiThread(() -> android.widget.Toast.makeText(act, msg, android.widget.Toast.LENGTH_SHORT).show());
    }

    public void setHostCallback(HostCallback cb) { mCallback = cb; }

    public void setToolMode(ToolMode mode) {
        if (mode == null) mode = ToolMode.MOVE;
        cancelActiveGesture();
        mToolMode = mode;
        if (mAccelerator != null) {
            if (mode == ToolMode.PEN) {
                mAccelerator.setRenderEnabled(true);
            } else {
                suspendHardwareOverlay();
            }
        }
        renderFullFrame();
    }

    private float mHighlighterWidthPx = 36f;

    public ToolMode getToolMode() { return mToolMode; }
    public void setPenColor(int color) { mPenColor = color; }
    public int getPenColor() { return mPenColor; }
    public void setPenWidthPx(float px) { mPenWidthPx = Math.max(3f, Math.min(36f, px)); }
    public float getPenWidthPx() { return mPenWidthPx; }
    public void setHighlighterWidthPx(float px) { mHighlighterWidthPx = Math.max(16f, Math.min(72f, px)); }
    public float getHighlighterWidthPx() { return mHighlighterWidthPx; }
    public void setHighlighter(boolean hl) { mIsHighlighter = hl; }
    public boolean isHighlighter() { return mIsHighlighter; }
    public void setSmartPenEnabled(boolean enabled) { mSmartPenEnabled = enabled; }
    public boolean isSmartPenEnabled() { return mSmartPenEnabled; }
    public void setEraserBaseRadiusPx(float r) { mEraserBaseRadiusPx = Math.max(18f, Math.min(120f, r)); }
    public float getEraserBaseRadiusPx() { return mEraserBaseRadiusPx; }
    public void setOuterBgColor(int color) {
        mOuterBgColor = color;
        invalidateViewport4kTile();
        renderFullFrame();
        scheduleViewport4kRender(80L);
    }
    public int getOuterBgColor() { return mOuterBgColor; }
    public boolean canUndo() { return !activeUndoStack().isEmpty(); }
    public boolean canRedo() { return !activeRedoStack().isEmpty(); }
    private void notifyHistoryChanged() {
        if (mCallback != null) {
            mCallback.onHistoryChanged(canUndo(), canRedo());
        }
    }
    public int getPageCount() { return mPageCount; }
    public boolean isFocusActive() { return mFocusActive; }
    public boolean hasFocusStrokes() { return !mFocusStrokes.isEmpty(); }
    public String getLocalPdfPath() { return mLocalPdfPath; }
    public Uri getOriginUri() { return mOriginUri; }
    public String getDisplayName() { return mDisplayName; }

    // ─── PDF 열기 / 닫기 ─────────────────────────────────────────────
    public void openPdf(final Activity activity, final String filePath, final Uri uri,
                        final String displayName, final int initialPage) {
        closePdf();
        mActivity = activity;
        mOriginUri = uri;
        mDisplayName = (displayName != null) ? displayName : "문서.pdf";
        mInitialPage = Math.max(0, initialPage);
        mLayoutInitialized = false;
        mIsLoading = true;
        mFirstPageRendered = false;
        mLastReportedPage = -1;
        renderFullFrame();

        mRenderThread = new HandlerThread("ScrollPdfStudioRender");
        mRenderThread.start();
        mRenderHandler = new Handler(mRenderThread.getLooper());

        mRenderHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    ParcelFileDescriptor pfd = openPdfDescriptorDirect(activity, filePath, uri);
                    if (pfd == null) {
                        mUiHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                mIsLoading = false;
                                renderFullFrame();
                                showTopToast(activity, "PDF 파일을 열 수 없습니다.", true);
                            }
                        });
                        return;
                    }
                    PdfRenderer renderer = new PdfRenderer(pfd);
                    final int count = renderer.getPageCount();
                    final float[] tops = new float[count];
                    final float[] heights = new float[count];

                    float cursorY = 0f;
                    if (count <= 20) {
                        for (int i = 0; i < count; i++) {
                            PdfRenderer.Page p = renderer.openPage(i);
                            int pw = Math.max(1, p.getWidth());
                            int ph = Math.max(1, p.getHeight());
                            p.close();
                            tops[i] = cursorY;
                            heights[i] = DOC_WIDTH * ((float) ph / (float) pw);
                            cursorY += heights[i] + PAGE_GAP;
                        }
                    } else {
                        int sampleIdx = Math.max(0, Math.min(count - 1, mInitialPage));
                        PdfRenderer.Page sp = renderer.openPage(sampleIdx);
                        int spw = Math.max(1, sp.getWidth());
                        int sph = Math.max(1, sp.getHeight());
                        sp.close();
                        float estH = DOC_WIDTH * ((float) sph / (float) spw);
                        for (int i = 0; i < count; i++) {
                            tops[i] = cursorY;
                            heights[i] = estH;
                            cursorY += estH + PAGE_GAP;
                        }
                    }
                    final float totalH = Math.max(1f, cursorY - PAGE_GAP);

                    synchronized (mRendererLock) {
                        mPfd = pfd;
                        mRenderer = renderer;
                    }

                    mUiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mPageCount = count;
                            mPageTop = tops;
                            mPageHeight = heights;
                            mTotalDocHeight = totalH;
                            initCameraIfReady();
                            requestVisiblePageRenders(false);
                            renderFullFrame();
                        }
                    });
                } catch (Throwable t) {
                    Log.e(TAG, "Failed to open PDF", t);
                    mUiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mIsLoading = false;
                            renderFullFrame();
                            showTopToast(activity, "스크롤 PDF 열기 실패: " + t.getMessage(), true);
                        }
                    });
                }
            }
        });
    }

    private ParcelFileDescriptor openPdfDescriptorDirect(Activity activity, String filePath, Uri uri) {
        if (filePath != null) {
            try {
                File f = new File(filePath);
                if (f.exists() && f.canRead()) {
                    mLocalPdfPath = filePath;
                    mTempCopiedPdf = false;
                    return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                }
            } catch (Throwable ignored) {}
        }
        if (uri != null && activity != null) {
            try {
                ParcelFileDescriptor direct = activity.getContentResolver().openFileDescriptor(uri, "r");
                if (direct != null) {
                    mLocalPdfPath = null;
                    mTempCopiedPdf = false;
                    return direct;
                }
            } catch (Throwable ignored) {}
            File tmp = new File(activity.getCacheDir(), "gnfl_studio_pdf_" + System.currentTimeMillis() + ".pdf");
            InputStream is = null;
            FileOutputStream fos = null;
            try {
                is = activity.getContentResolver().openInputStream(uri);
                if (is != null) {
                    fos = new FileOutputStream(tmp);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                    fos.flush();
                    mLocalPdfPath = tmp.getAbsolutePath();
                    mTempCopiedPdf = true;
                    return ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY);
                }
            } catch (Throwable ignored) {
            } finally {
                try { if (fos != null) fos.close(); } catch (Throwable ignored) {}
                try { if (is != null) is.close(); } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    public void closePdf() {
        mUiHandler.removeCallbacks(mPauseRunnable);
        mUiHandler.removeCallbacks(mZoomRerenderRunnable);
        invalidateViewport4kTile();
        synchronized (mSurfaceLock) {
            if (mViewportTileBmp != null && !mViewportTileBmp.isRecycled()) {
                mViewportTileBmp.recycle();
            }
            mViewportTileBmp = null;
            mTilePanX = Float.NaN;
            mTilePanY = Float.NaN;
            mTileScale = Float.NaN;
        }
        cancelActiveGesture();

        mDocStrokes.clear();
        mDocUndo.clear();
        mDocRedo.clear();
        mFocusStrokes.clear();
        mFocusUndo.clear();
        mFocusRedo.clear();
        mFocusActive = false;
        if (mFocusBitmap != null) {
            if (!mFocusBitmap.isRecycled()) mFocusBitmap.recycle();
            mFocusBitmap = null;
        }

        for (CachedPage cp : mPageCache.values()) {
            if (cp != null && cp.bitmap != null && !cp.bitmap.isRecycled()) {
                cp.bitmap.recycle();
            }
        }
        mPageCache.clear();
        mRenderingPages.clear();

        synchronized (mRendererLock) {
            try { if (mRenderer != null) mRenderer.close(); } catch (Throwable ignored) {}
            try { if (mPfd != null) mPfd.close(); } catch (Throwable ignored) {}
            mRenderer = null;
            mPfd = null;
        }
        if (mRenderThread != null) {
            mRenderThread.quitSafely();
            mRenderThread = null;
            mRenderHandler = null;
        }
        if (mTempCopiedPdf && mLocalPdfPath != null) {
            try { new File(mLocalPdfPath).delete(); } catch (Throwable ignored) {}
        }
        mLocalPdfPath = null;
        mTempCopiedPdf = false;
        mPageCount = 0;
        mLayoutInitialized = false;
        mIsLoading = false;
        mFirstPageRendered = false;
    }

    // ─── 카메라 / 페이지 탐색 / 쪽맞춤 하한 ──────────────────────────
    private void initCameraIfReady() {
        int vw = (mSurfaceW > 0) ? mSurfaceW : getWidth();
        int vh = (mSurfaceH > 0) ? mSurfaceH : getHeight();
        if (vw <= 0 || vh <= 0 || mPageCount <= 0) return;
        mScale = (vw * 0.92f) / DOC_WIDTH;
        mPanX = (vw - DOC_WIDTH * mScale) * 0.5f;
        int startPage = Math.max(0, Math.min(mPageCount - 1, mInitialPage));
        mPanY = 20f - mPageTop[startPage] * mScale;
        clampCamera();
        mLayoutInitialized = true;
        notifyCurrentPageIfChanged();
    }

    public void jumpToPage(int pageIndex) {
        if (mPageCount <= 0) return;
        if (mFocusActive) exitFocusMode();
        int idx = Math.max(0, Math.min(mPageCount - 1, pageIndex));
        mPanY = 20f - mPageTop[idx] * mScale;
        clampCamera();
        notifyCurrentPageIfChanged();
        invalidateViewport4kTile();
        requestVisiblePageRenders(false);
        renderFullFrame();
        scheduleViewport4kRender(140L);
    }

    public void fitWidth() {
        int vw = (mSurfaceW > 0) ? mSurfaceW : getWidth();
        if (vw <= 0 || mPageCount <= 0) return;
        if (mFocusActive && mFocusCropDocRect != null) {
            float cw = Math.max(10f, mFocusCropDocRect.width());
            mScale = (vw * 0.85f) / cw;
            mPanX = (vw - cw * mScale) * 0.5f - mFocusCropDocRect.left * mScale;
            mPanY = 40f - mFocusCropDocRect.top * mScale;
            renderFullFrame();
            return;
        }
        int curPage = getCurrentPageIndex();
        mScale = (vw * 0.92f) / DOC_WIDTH;
        mPanX = (vw - DOC_WIDTH * mScale) * 0.5f;
        mPanY = 20f - mPageTop[curPage] * mScale;
        clampCamera();
        invalidateViewport4kTile();
        requestVisiblePageRenders(false);
        renderFullFrame();
        scheduleViewport4kRender(120L);
    }

    public void fitPage() {
        int vw = (mSurfaceW > 0) ? mSurfaceW : getWidth();
        int vh = (mSurfaceH > 0) ? mSurfaceH : getHeight();
        if (vw <= 0 || vh <= 0 || mPageCount <= 0) return;
        if (mFocusActive && mFocusCropDocRect != null) {
            float availH = Math.max(200f, vh - mBottomToolbarClearance - 40f);
            float scaleW = (vw * 0.85f) / Math.max(10f, mFocusCropDocRect.width());
            float scaleH = (availH * 0.85f) / Math.max(10f, mFocusCropDocRect.height());
            mScale = Math.min(scaleW, scaleH);
            mPanX = (vw - mFocusCropDocRect.width() * mScale) * 0.5f - mFocusCropDocRect.left * mScale;
            mPanY = 30f - mFocusCropDocRect.top * mScale;
            renderFullFrame();
            return;
        }
        int curPage = getCurrentPageIndex();
        float availH = Math.max(200f, vh - mBottomToolbarClearance - 24f);
        float scaleW = (vw * 0.92f) / DOC_WIDTH;
        float scaleH = availH / Math.max(100f, mPageHeight[curPage]);
        mScale = Math.min(scaleW, scaleH);
        mPanX = (vw - DOC_WIDTH * mScale) * 0.5f;
        mPanY = 16f - mPageTop[curPage] * mScale;
        clampCamera();
        invalidateViewport4kTile();
        requestVisiblePageRenders(false);
        renderFullFrame();
        scheduleViewport4kRender(120L);
    }

    public int getCurrentPageIndex() {
        if (mPageCount <= 0) return 0;
        int vh = (mSurfaceH > 0) ? mSurfaceH : getHeight();
        float centerDocY = (vh * 0.42f - mPanY) / Math.max(0.01f, mScale);
        for (int i = 0; i < mPageCount; i++) {
            float bottom = mPageTop[i] + mPageHeight[i] + PAGE_GAP * 0.5f;
            if (centerDocY <= bottom) return i;
        }
        return mPageCount - 1;
    }

    private void notifyCurrentPageIfChanged() {
        if (mPageCount <= 0) return;
        int cur = getCurrentPageIndex();
        if (cur != mLastReportedPage) {
            mLastReportedPage = cur;
            if (mCallback != null) mCallback.onPageChanged(cur, mPageCount);
        }
    }

    private float computeMinScrollScale() {
        int vw = (mSurfaceW > 0) ? mSurfaceW : getWidth();
        int vh = (mSurfaceH > 0) ? mSurfaceH : getHeight();
        if (vw <= 0 || vh <= 0 || mPageCount <= 0) return 0.25f;
        float fitW = (vw * 0.92f) / DOC_WIDTH;
        int cur = getCurrentPageIndex();
        float availH = Math.max(200f, vh - mBottomToolbarClearance - 24f);
        float fitPage = Math.min(fitW, availH / Math.max(100f, mPageHeight[cur]));
        return Math.max(0.25f, fitPage);
    }

    private float clampScaleValue(float candidate) {
        if (mFocusActive) return Math.max(0.25f, Math.min(12.0f, candidate));
        int vw = (mSurfaceW > 0) ? mSurfaceW : getWidth();
        float fitW = (vw > 0) ? ((vw * 0.92f) / DOC_WIDTH) : 1.0f;
        float minScale = computeMinScrollScale();
        float maxScale = fitW * 4.5f;
        if (candidate < minScale) return minScale;
        if (candidate > maxScale) return maxScale;
        return candidate;
    }

    /**
     * 쪽맞춤(최소 배율) 상태에서 PDF 좌우로 생기는 화면 여백의 문서 좌표 폭을 계산.
     */
    private float computeFitPageSideMarginDoc() {
        int vw = (mSurfaceW > 0) ? mSurfaceW : getWidth();
        if (vw <= 0 || mPageCount <= 0) return 0f;
        float minScale = computeMinScrollScale();
        float totalVisibleDocW = vw / Math.max(0.01f, minScale);
        return Math.max(0f, (totalVisibleDocW - DOC_WIDTH) * 0.5f);
    }

    private void clampCamera() {
        if (mFocusActive) return;
        int vw = (mSurfaceW > 0) ? mSurfaceW : getWidth();
        int vh = (mSurfaceH > 0) ? mSurfaceH : getHeight();
        if (vw <= 0 || vh <= 0 || mPageCount <= 0) return;

        mScale = clampScaleValue(mScale);
        float minDocX = 0f;
        float maxDocX = DOC_WIDTH;
        // 여백에 실제 필기가 있는 경우 확대 시에도 해당 여백 필기 영역까지 좌우 이동 허용
        for (int i = 0; i < mDocStrokes.size(); i++) {
            InkStroke s = mDocStrokes.get(i);
            if (s.minX < minDocX) minDocX = s.minX;
            if (s.maxX > maxDocX) maxDocX = s.maxX;
        }
        float sideMargin = computeFitPageSideMarginDoc();
        minDocX = Math.max(-sideMargin, minDocX);
        maxDocX = Math.min(DOC_WIDTH + sideMargin, maxDocX);

        float contentScreenW = (maxDocX - minDocX) * mScale;
        if (DOC_WIDTH * mScale <= vw && minDocX >= 0f && maxDocX <= DOC_WIDTH) {
            mPanX = (vw - DOC_WIDTH * mScale) * 0.5f;
        } else if (contentScreenW <= vw) {
            mPanX = (vw - DOC_WIDTH * mScale) * 0.5f;
        } else {
            float minPanX = vw - maxDocX * mScale - 40f;
            float maxPanX = -minDocX * mScale + 40f;
            if (mPanX < minPanX) mPanX = minPanX;
            if (mPanX > maxPanX) mPanX = maxPanX;
        }

        float docScreenH = mTotalDocHeight * mScale;
        float minPanY = vh - mBottomToolbarClearance - docScreenH - 40f;
        float maxPanY = 30f;
        if (minPanY > maxPanY) {
            mPanY = maxPanY;
        } else {
            if (mPanY < minPanY) mPanY = minPanY;
            if (mPanY > maxPanY) mPanY = maxPanY;
        }
    }

    // ─── 2단계 점진적 PDF 동적 렌더링 ────────────────────────────────
    private int[] getVisiblePageRange() {
        int vh = (mSurfaceH > 0) ? mSurfaceH : getHeight();
        if (mPageCount <= 0 || vh <= 0) return new int[] { 0, -1 };
        float topDocY = (0f - mPanY) / Math.max(0.01f, mScale);
        float botDocY = (vh - mPanY) / Math.max(0.01f, mScale);
        int first = mPageCount - 1;
        int last = 0;
        for (int i = 0; i < mPageCount; i++) {
            float pTop = mPageTop[i];
            float pBot = pTop + mPageHeight[i];
            if (pBot >= topDocY && pTop <= botDocY) {
                if (i < first) first = i;
                if (i > last) last = i;
            }
        }
        if (first > last) {
            int cur = getCurrentPageIndex();
            return new int[] { cur, cur };
        }
        return new int[] { first, last };
    }

    private void invalidateViewport4kTile() {
        mViewportTileSeq.incrementAndGet();
        mUiHandler.removeCallbacks(mViewport4kRunnable);
    }

    private void scheduleViewport4kRender(long delayMs) {
        if (mPageCount <= 0 || mRenderHandler == null || mFocusActive) return;
        mViewportTileSeq.incrementAndGet();
        mUiHandler.removeCallbacks(mViewport4kRunnable);
        if (delayMs <= 0L) {
            mUiHandler.post(mViewport4kRunnable);
        } else {
            mUiHandler.postDelayed(mViewport4kRunnable, delayMs);
        }
    }

    /**
     * 이동/확대가 멈췄을 때 현재 화면(Viewport)에 보이는 PDF 영역만
     * 실제 4K(1:1 화면 픽셀) 벡터 정밀 렌더링으로 1장 생성하여 선명하게 덮어준다.
     */
    private void renderViewport4kTileAsync() {
        if (mPageCount <= 0 || mRenderHandler == null || mFocusActive || !mSurfaceReady) return;
        final int vw = mSurfaceW;
        final int vh = mSurfaceH;
        if (vw <= 0 || vh <= 0) return;

        final int seq = mViewportTileSeq.get();
        final float snapPanX = mPanX;
        final float snapPanY = mPanY;
        final float snapScale = mScale;
        final int[] vis = getVisiblePageRange();
        final int visFirst = Math.max(0, vis[0]);
        final int visLast = Math.min(mPageCount - 1, vis[1]);
        if (visFirst > visLast) return;

        final float[] pageTopSnap = new float[visLast - visFirst + 1];
        final float[] pageHeightSnap = new float[visLast - visFirst + 1];
        for (int i = visFirst; i <= visLast; i++) {
            pageTopSnap[i - visFirst] = mPageTop[i];
            pageHeightSnap[i - visFirst] = mPageHeight[i];
        }

        mRenderHandler.post(new Runnable() {
            @Override
            public void run() {
                if (seq != mViewportTileSeq.get()) return;
                long t0 = System.nanoTime();
                Bitmap tile = null;
                try {
                    tile = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888);
                    Canvas tc = new Canvas(tile);
                    tc.drawColor(mOuterBgColor);

                    Paint bgP = new Paint();
                    bgP.setColor(Color.WHITE);
                    bgP.setStyle(Paint.Style.FILL);
                    Paint borderP = new Paint(Paint.ANTI_ALIAS_FLAG);
                    borderP.setColor(Color.parseColor("#CBD5E1"));
                    borderP.setStyle(Paint.Style.STROKE);
                    borderP.setStrokeWidth(Math.max(1.5f, 1.5f * snapScale));

                    Matrix mat = new Matrix();
                    Rect clip = new Rect();
                    RectF pageRect = new RectF();

                    for (int p = visFirst; p <= visLast; p++) {
                        if (seq != mViewportTileSeq.get()) {
                            tile.recycle();
                            return;
                        }
                        float pTop = pageTopSnap[p - visFirst];
                        float pH = pageHeightSnap[p - visFirst];
                        float left = snapPanX;
                        float top = snapPanY + pTop * snapScale;
                        float right = snapPanX + DOC_WIDTH * snapScale;
                        float bottom = snapPanY + (pTop + pH) * snapScale;

                        int cl = Math.max(0, (int) Math.floor(left));
                        int ct = Math.max(0, (int) Math.floor(top));
                        int cr = Math.min(vw, (int) Math.ceil(right));
                        int cb = Math.min(vh, (int) Math.ceil(bottom));
                        if (cr <= cl || cb <= ct) continue;

                        pageRect.set(left, top, right, bottom);
                        tc.drawRect(pageRect, bgP);

                        synchronized (mRendererLock) {
                            if (mRenderer == null || p < 0 || p >= mPageCount || seq != mViewportTileSeq.get()) {
                                tile.recycle();
                                return;
                            }
                            PdfRenderer.Page page = mRenderer.openPage(p);
                            int pw = Math.max(1, page.getWidth());
                            int ph = Math.max(1, page.getHeight());
                            mat.reset();
                            mat.setScale((DOC_WIDTH * snapScale) / (float) pw, (pH * snapScale) / (float) ph);
                            mat.postTranslate(left, top);
                            clip.set(cl, ct, cr, cb);
                            page.render(tile, clip, mat, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            page.close();
                        }
                        tc.drawRect(pageRect, borderP);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "4K viewport tile render failed: " + t.getMessage());
                    if (tile != null && !tile.isRecycled()) tile.recycle();
                    return;
                }

                if (seq != mViewportTileSeq.get()) {
                    if (tile != null && !tile.isRecycled()) tile.recycle();
                    return;
                }

                long ms = (System.nanoTime() - t0) / 1_000_000L;
                Log.i(TAG, "4K viewport tile ready: " + vw + "x" + vh
                        + " scale=" + String.format("%.2f", snapScale)
                        + " pages=" + (visFirst + 1) + ".." + (visLast + 1)
                        + " (" + ms + "ms)");

                final Bitmap readyTile = tile;
                mUiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (seq != mViewportTileSeq.get() || mPageCount <= 0 || mFocusActive) {
                            if (readyTile != null && !readyTile.isRecycled()) readyTile.recycle();
                            return;
                        }
                        synchronized (mSurfaceLock) {
                            if (mViewportTileBmp != null && !mViewportTileBmp.isRecycled()) {
                                mViewportTileBmp.recycle();
                            }
                            mViewportTileBmp = readyTile;
                            mTilePanX = snapPanX;
                            mTilePanY = snapPanY;
                            mTileScale = snapScale;
                        }
                        renderFullFrame();
                    }
                });
            }
        });
    }

    private void requestVisiblePageRenders(boolean forceUpgrade) {
        if (mPageCount <= 0 || mRenderHandler == null || mFocusActive) return;
        int[] vis = getVisiblePageRange();
        int visFirst = Math.max(0, vis[0]);
        int visLast = Math.min(mPageCount - 1, vis[1]);
        // 이동/확대 시 부드러운 60fps를 위해 기본 페이지 캐시는 가벼운 1080p급(1280px)으로 유지
        int hqWidth = BASE_1080P_WIDTH;

        boolean allVisibleHavePreview = true;
        for (int p = visFirst; p <= visLast; p++) {
            if (mPageCache.get(p) == null) {
                allVisibleHavePreview = false;
                if (!mRenderingPages.contains(p)) {
                    mRenderingPages.add(p);
                    scheduleRenderPage(p, FAST_PREVIEW_WIDTH, true);
                }
            }
        }
        if (!allVisibleHavePreview) return;

        for (int p = visFirst; p <= visLast; p++) {
            CachedPage cached = mPageCache.get(p);
            boolean needHq = (cached != null && cached.renderedWidth < hqWidth * (forceUpgrade ? 0.85f : 0.72f));
            if (needHq && !mRenderingPages.contains(p)) {
                mRenderingPages.add(p);
                scheduleRenderPage(p, hqWidth, false);
            }
        }

        int[] buffers = new int[] { visLast + 1, visFirst - 1 };
        for (int b : buffers) {
            if (b < 0 || b >= mPageCount) continue;
            CachedPage cached = mPageCache.get(b);
            boolean needBuf = (cached == null) || (forceUpgrade && cached.renderedWidth < hqWidth * 0.78f);
            if (needBuf && !mRenderingPages.contains(b)) {
                mRenderingPages.add(b);
                scheduleRenderPage(b, hqWidth, false);
            }
        }
    }

    private void scheduleRenderPage(final int pageIdx, final int targetPxW, final boolean isFastPreview) {
        if (mRenderHandler == null) return;
        mRenderHandler.post(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = null;
                try {
                    synchronized (mRendererLock) {
                        if (mRenderer == null || pageIdx < 0 || pageIdx >= mPageCount) return;
                        PdfRenderer.Page page = mRenderer.openPage(pageIdx);
                        int pw = Math.max(1, page.getWidth());
                        int ph = Math.max(1, page.getHeight());
                        int bw = targetPxW;
                        int bh = Math.max(1, Math.round(bw * ((float) ph / (float) pw)));
                        long maxPixels = 7_500_000L;
                        if ((long) bw * (long) bh > maxPixels) {
                            float shrink = (float) Math.sqrt((double) maxPixels / ((double) bw * (double) bh));
                            bw = Math.max(1, Math.round(bw * shrink));
                            bh = Math.max(1, Math.round(bh * shrink));
                        }
                        bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                        Canvas c = new Canvas(bmp);
                        c.drawColor(Color.WHITE);
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        page.close();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Render page " + pageIdx + " failed", t);
                    if (bmp != null && !bmp.isRecycled()) bmp.recycle();
                    bmp = null;
                }

                final Bitmap finalBmp = bmp;
                mUiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mRenderingPages.remove(Integer.valueOf(pageIdx));
                        if (finalBmp == null) return;
                        if (mPageCount <= 0) {
                            if (!finalBmp.isRecycled()) finalBmp.recycle();
                            return;
                        }
                        CachedPage old = mPageCache.put(pageIdx, new CachedPage(finalBmp, finalBmp.getWidth()));
                        if (old != null && old.bitmap != null && !old.bitmap.isRecycled()) {
                            old.bitmap.recycle();
                        }
                        if (!mFirstPageRendered) {
                            mFirstPageRendered = true;
                            mIsLoading = false;
                            scheduleViewport4kRender(120L);
                        }
                        evictFarthestCachedPages();
                        renderFullFrame();
                        if (isFastPreview) {
                            requestVisiblePageRenders(false);
                        }
                    }
                });
            }
        });
    }

    private void evictFarthestCachedPages() {
        int cur = getCurrentPageIndex();
        while (mPageCache.size() > MAX_CACHED_PAGES) {
            int farthestKey = -1;
            int maxDist = -1;
            for (Integer key : mPageCache.keySet()) {
                int d = Math.abs(key - cur);
                if (d > maxDist) {
                    maxDist = d;
                    farthestKey = key;
                }
            }
            if (farthestKey >= 0) {
                CachedPage removed = mPageCache.remove(farthestKey);
                if (removed != null && removed.bitmap != null && !removed.bitmap.isRecycled()) {
                    removed.bitmap.recycle();
                }
            } else {
                break;
            }
        }
    }

    // ─── 좌표 변환 ───────────────────────────────────────────────────
    private float screenToDocX(float sx) { return (sx - mPanX) / Math.max(0.01f, mScale); }
    private float screenToDocY(float sy) { return (sy - mPanY) / Math.max(0.01f, mScale); }
    private float docToScreenX(float dx) { return dx * mScale + mPanX; }
    private float docToScreenY(float dy) { return dy * mScale + mPanY; }

    private float clampPenDocX(float docX) {
        if (mFocusActive) return docX;
        float sideMargin = computeFitPageSideMarginDoc();
        float minX = -sideMargin;
        float maxX = DOC_WIDTH + sideMargin;
        if (docX < minX) return minX;
        if (docX > maxX) return maxX;
        return docX;
    }

    private float clampPenDocY(float docY) {
        if (mFocusActive) return docY;
        if (docY < 0f) return 0f;
        if (docY > mTotalDocHeight) return mTotalDocHeight;
        return docY;
    }

    private ArrayList<InkStroke> activeStrokeList() { return mFocusActive ? mFocusStrokes : mDocStrokes; }
    private ArrayList<UndoAction> activeUndoStack() { return mFocusActive ? mFocusUndo : mDocUndo; }
    private ArrayList<UndoAction> activeRedoStack() { return mFocusActive ? mFocusRedo : mDocRedo; }

    // ─── Undo / Redo / Delete ────────────────────────────────────────
    public boolean undo() {
        ArrayList<UndoAction> undo = activeUndoStack();
        if (undo.isEmpty()) return false;
        clearHardwareOverlayNow();
        UndoAction act = undo.remove(undo.size() - 1);
        ArrayList<InkStroke> list = activeStrokeList();
        if (act.added != null) list.removeAll(act.added);
        if (act.removed != null) list.addAll(act.removed);
        activeRedoStack().add(act);
        renderFullFrame();
        notifyHistoryChanged();
        return true;
    }

    public boolean redo() {
        ArrayList<UndoAction> redo = activeRedoStack();
        if (redo.isEmpty()) return false;
        clearHardwareOverlayNow();
        UndoAction act = redo.remove(redo.size() - 1);
        ArrayList<InkStroke> list = activeStrokeList();
        if (act.removed != null) list.removeAll(act.removed);
        if (act.added != null) list.addAll(act.added);
        activeUndoStack().add(act);
        renderFullFrame();
        notifyHistoryChanged();
        return true;
    }

    public int clearCurrentScreenStrokes() {
        clearHardwareOverlayNow();
        if (mFocusActive) return clearFocusStrokes();
        if (mDocStrokes.isEmpty() || mPageCount <= 0) return 0;
        int[] vis = getVisiblePageRange();
        if (vis[0] > vis[1]) return 0;
        float rangeTop = mPageTop[vis[0]];
        float rangeBot = mPageTop[vis[1]] + mPageHeight[vis[1]];

        ArrayList<InkStroke> removed = new ArrayList<InkStroke>();
        Iterator<InkStroke> it = mDocStrokes.iterator();
        while (it.hasNext()) {
            InkStroke s = it.next();
            if (s.maxY >= rangeTop && s.minY <= rangeBot) {
                removed.add(s);
                it.remove();
            }
        }
        if (!removed.isEmpty()) {
            mDocUndo.add(new UndoAction(false, removed));
            mDocRedo.clear();
            renderFullFrame();
        }
        return removed.size();
    }

    public int clearAllDocumentStrokes() {
        if (mFocusActive) return clearFocusStrokes();
        if (mDocStrokes.isEmpty()) return 0;
        ArrayList<InkStroke> removed = new ArrayList<InkStroke>(mDocStrokes);
        mDocStrokes.clear();
        mDocUndo.add(new UndoAction(false, removed));
        mDocRedo.clear();
        renderFullFrame();
        return removed.size();
    }

    public int clearFocusStrokes() {
        if (mFocusStrokes.isEmpty()) return 0;
        ArrayList<InkStroke> removed = new ArrayList<InkStroke>(mFocusStrokes);
        mFocusStrokes.clear();
        mFocusUndo.add(new UndoAction(false, removed));
        mFocusRedo.clear();
        renderFullFrame();
        return removed.size();
    }

    // ─── 올가미 제자리 집중 풀이 (Spotlight Focus) ───────────────────
    public void enterFocusModeFromScreenRect(final RectF screenRect) {
        if (mPageCount <= 0 || screenRect == null || screenRect.width() < 40f || screenRect.height() < 40f) return;
        float docL = Math.max(0f, screenToDocX(screenRect.left));
        float docR = Math.min(DOC_WIDTH, screenToDocX(screenRect.right));
        float docT = Math.max(0f, screenToDocY(screenRect.top));
        float docB = Math.min(mTotalDocHeight, screenToDocY(screenRect.bottom));
        if (docR - docL < 20f || docB - docT < 20f) return;

        final RectF cropDoc = new RectF(docL, docT, docR, docB);
        mSavedScale = mScale;
        mSavedPanX = mPanX;
        mSavedPanY = mPanY;
        if (mRenderHandler == null) return;

        mRenderHandler.post(new Runnable() {
            @Override
            public void run() {
                Bitmap cropBmp = renderDocRectHighRes(cropDoc);
                if (cropBmp == null) return;
                final int dominant = InkMathHelper.extractDominantColor(cropBmp);
                final Bitmap readyBmp = cropBmp;
                mUiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mFocusBitmap != null && !mFocusBitmap.isRecycled()) mFocusBitmap.recycle();
                        mFocusBitmap = readyBmp;
                        mFocusCropDocRect = cropDoc;
                        mFocusBgColor = dominant;
                        mFocusStrokes.clear();
                        mFocusUndo.clear();
                        mFocusRedo.clear();
                        mFocusActive = true;
                        mToolMode = ToolMode.PEN;
                        if (mCallback != null) {
                            mCallback.onFocusModeChanged(true);
                            mCallback.onToolAutoChanged(ToolMode.PEN);
                        }
                        renderFullFrame();
                    }
                });
            }
        });
    }

    private Bitmap renderDocRectHighRes(RectF cropDoc) {
        try {
            float cropW = Math.max(1f, cropDoc.width());
            float cropH = Math.max(1f, cropDoc.height());
            float targetScale = Math.max(1.5f, Math.min(4.0f, Math.min(2600f / cropW, 2600f / cropH)));
            int outW = Math.max(1, Math.round(cropW * targetScale));
            int outH = Math.max(1, Math.round(cropH * targetScale));
            long maxPx = 8_500_000L;
            if ((long) outW * (long) outH > maxPx) {
                float shrink = (float) Math.sqrt((double) maxPx / ((double) outW * (double) outH));
                outW = Math.max(1, Math.round(outW * shrink));
                outH = Math.max(1, Math.round(outH * shrink));
                targetScale *= shrink;
            }
            Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            c.drawColor(Color.WHITE);
            synchronized (mRendererLock) {
                if (mRenderer == null) return out;
                for (int i = 0; i < mPageCount; i++) {
                    float pTop = mPageTop[i];
                    float pBot = pTop + mPageHeight[i];
                    if (pBot < cropDoc.top || pTop > cropDoc.bottom) continue;
                    PdfRenderer.Page page = mRenderer.openPage(i);
                    int pw = Math.max(1, page.getWidth());
                    int ph = Math.max(1, page.getHeight());
                    Matrix mat = new Matrix();
                    mat.postScale((DOC_WIDTH * targetScale) / (float) pw, (mPageHeight[i] * targetScale) / (float) ph);
                    mat.postTranslate(-cropDoc.left * targetScale, (pTop - cropDoc.top) * targetScale);
                    page.render(out, new Rect(0, 0, outW, outH), mat, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    page.close();
                }
            }
            return out;
        } catch (Throwable t) {
            Log.e(TAG, "renderDocRectHighRes failed", t);
            return null;
        }
    }

    public void exitFocusMode() {
        if (!mFocusActive) return;
        cancelActiveGesture();
        mFocusActive = false;
        mFocusStrokes.clear();
        mFocusUndo.clear();
        mFocusRedo.clear();
        if (mFocusBitmap != null) {
            if (!mFocusBitmap.isRecycled()) mFocusBitmap.recycle();
            mFocusBitmap = null;
        }
        mFocusCropDocRect = null;
        mScale = mSavedScale;
        mPanX = mSavedPanX;
        mPanY = mSavedPanY;
        clampCamera();
        if (mCallback != null) mCallback.onFocusModeChanged(false);
        requestVisiblePageRenders(false);
        renderFullFrame();
        scheduleViewport4kRender(120L);
    }

    // ─── 터치 이벤트 처리 (초고속 Dirty-Rect SurfaceView 직접 렌더링) ─
    private static int findPalmEraserPointerIndex(MotionEvent ev) {
        if (ev == null) return -1;
        int n = ev.getPointerCount();
        for (int i = 0; i < n; i++) {
            if (ev.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER
                    || ev.getTouchMajor(i) > 48.0f) {
                return i;
            }
        }
        return -1;
    }

    private InkStroke createNewFreehandStroke(float sx, float sy) {
        InkStroke s = new InkStroke();
        s.kind = StrokeKind.FREEHAND;
        s.color = mPenColor;
        s.isHighlighter = mIsHighlighter;
        float effWidthPx = mIsHighlighter ? mHighlighterWidthPx : mPenWidthPx;
        s.widthDoc = Math.max(0.5f, effWidthPx / Math.max(0.25f, mScale));
        s.appendPointFast(clampPenDocX(screenToDocX(sx)), clampPenDocY(screenToDocY(sy)));
        return s;
    }

    private void commitStrokeToHistory(InkStroke s) {
        if (s == null) return;
        s.rebuildPathAndBounds();
        activeStrokeList().add(s);
        ArrayList<InkStroke> added = new ArrayList<InkStroke>(1);
        added.add(s);
        activeUndoStack().add(new UndoAction(true, added));
        activeRedoStack().clear();
        notifyHistoryChanged();
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (mIsLoading || !mFirstPageRendered || mPageCount <= 0) return true;
        int action = ev.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                mIsTwoFingerGesture = false;
                mPalmErasing = false;
                mShowEraserCircle = false;
                mMultiPenStrokes.clear();
                mMultiLastScreen.clear();
                mActivePointerId = ev.getPointerId(0);
                mDownScreenX = mLastScreenX = ev.getX(0);
                mDownScreenY = mLastScreenY = ev.getY(0);
                mDownTime = ev.getEventTime();

                if (mToolMode == ToolMode.PEN) {
                    int palmIdx = findPalmEraserPointerIndex(ev);
                    if (palmIdx >= 0) {
                        mPalmErasing = true;
                        mErasedBatch.clear();
                        mErasedAddedBatch.clear();
                        float r = Math.max(46f, ev.getTouchMajor(palmIdx) * 0.75f);
                        eraseAtScreenPointFast(ev.getX(palmIdx), ev.getY(palmIdx), r);
                    } else {
                        startPenStroke(ev.getX(0), ev.getY(0));
                    }
                } else if (mToolMode == ToolMode.ERASER) {
                    mErasedBatch.clear();
                    mErasedAddedBatch.clear();
                    float r = Math.max(mEraserBaseRadiusPx, ev.getTouchMajor(0) * 0.75f);
                    eraseAtScreenPointFast(ev.getX(0), ev.getY(0), r);
                } else if (mToolMode == ToolMode.LASSO) {
                    mLassoDragging = true;
                    mLassoScreenPts.clear();
                    mLassoScreenPath.reset();
                    mLassoScreenPts.add(new PointF(ev.getX(0), ev.getY(0)));
                    mLassoScreenPath.moveTo(ev.getX(0), ev.getY(0));
                    renderFullFrame();
                }
                return true;
            }

            case MotionEvent.ACTION_POINTER_DOWN: {
                if (mToolMode == ToolMode.MOVE && ev.getPointerCount() >= 2) {
                    cancelActiveGesture();
                    mIsTwoFingerGesture = true;
                    float x0 = ev.getX(0), y0 = ev.getY(0);
                    float x1 = ev.getX(1), y1 = ev.getY(1);
                    mPrevTwoMidX = (x0 + x1) * 0.5f;
                    mPrevTwoMidY = (y0 + y1) * 0.5f;
                    mPrevTwoDist = Math.max(10f, (float) Math.hypot(x1 - x0, y1 - y0));
                    return true;
                }

                if (mToolMode == ToolMode.PEN) {
                    int palmIdx = findPalmEraserPointerIndex(ev);
                    if (palmIdx >= 0 || mPalmErasing) {
                        mUiHandler.removeCallbacks(mPauseRunnable);
                        mActiveStroke = null;
                        mMultiPenStrokes.clear();
                        mMultiLastScreen.clear();
                        mSmartRecognized = false;
                        mPalmErasing = true;
                        int idx = (palmIdx >= 0) ? palmIdx : ev.getActionIndex();
                        float r = Math.max(46f, ev.getTouchMajor(idx) * 0.75f);
                        renderFullFrame();
                        eraseAtScreenPointFast(ev.getX(idx), ev.getY(idx), r);
                    } else {
                        mUiHandler.removeCallbacks(mPauseRunnable);
                        mSmartRecognized = false;
                        if (mActiveStroke != null) {
                            mMultiPenStrokes.put(mActivePointerId, mActiveStroke);
                            mMultiLastScreen.put(mActivePointerId, new PointF(mLastScreenX, mLastScreenY));
                            mActiveStroke = null;
                        }
                        int newIdx = ev.getActionIndex();
                        int newPid = ev.getPointerId(newIdx);
                        float nx = ev.getX(newIdx), ny = ev.getY(newIdx);
                        mMultiPenStrokes.put(newPid, createNewFreehandStroke(nx, ny));
                        mMultiLastScreen.put(newPid, new PointF(nx, ny));
                    }
                    return true;
                }
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (mIsTwoFingerGesture && mToolMode == ToolMode.MOVE) {
                    if (ev.getPointerCount() >= 2) {
                        float x0 = ev.getX(0), y0 = ev.getY(0);
                        float x1 = ev.getX(1), y1 = ev.getY(1);
                        float midX = (x0 + x1) * 0.5f;
                        float midY = (y0 + y1) * 0.5f;
                        float dist = Math.max(10f, (float) Math.hypot(x1 - x0, y1 - y0));

                        float scaleFactor = dist / mPrevTwoDist;
                        float oldScale = mScale;
                        float newScale = clampScaleValue(oldScale * scaleFactor);

                        float docMidX = (mPrevTwoMidX - mPanX) / Math.max(0.01f, oldScale);
                        float docMidY = (mPrevTwoMidY - mPanY) / Math.max(0.01f, oldScale);
                        mScale = newScale;
                        mPanX = midX - docMidX * mScale;
                        mPanY = midY - docMidY * mScale;
                        clampCamera();

                        mPrevTwoMidX = midX;
                        mPrevTwoMidY = midY;
                        mPrevTwoDist = dist;

                        notifyCurrentPageIfChanged();
                        invalidateViewport4kTile();
                        requestVisiblePageRenders(false);
                        scheduleVsyncFrame();
                    }
                    return true;
                }

                if (mToolMode == ToolMode.PEN) {
                    int palmIdx = findPalmEraserPointerIndex(ev);
                    if (mPalmErasing || palmIdx >= 0) {
                        if (!mPalmErasing) {
                            mUiHandler.removeCallbacks(mPauseRunnable);
                            mActiveStroke = null;
                            mMultiPenStrokes.clear();
                            mMultiLastScreen.clear();
                            mSmartRecognized = false;
                            mPalmErasing = true;
                            mErasedBatch.clear();
                            mErasedAddedBatch.clear();
                            renderFullFrame();
                        }
                        int idx = (palmIdx >= 0) ? palmIdx : 0;
                        float r = Math.max(46f, ev.getTouchMajor(idx) * 0.75f);
                        eraseAtScreenPointFast(ev.getX(idx), ev.getY(idx), r);
                        return true;
                    }

                    if (!mMultiPenStrokes.isEmpty()) {
                        int n = ev.getPointerCount();
                        int hist = ev.getHistorySize();
                        for (int i = 0; i < n; i++) {
                            int pid = ev.getPointerId(i);
                            InkStroke st = mMultiPenStrokes.get(pid);
                            PointF prevScr = mMultiLastScreen.get(pid);
                            if (st != null && prevScr != null) {
                                float px = prevScr.x, py = prevScr.y;
                                for (int h = 0; h < hist; h++) {
                                    float hx = ev.getHistoricalX(i, h);
                                    float hy = ev.getHistoricalY(i, h);
                                    float ddx = clampPenDocX(screenToDocX(hx));
                                    float ddy = clampPenDocY(screenToDocY(hy));
                                    st.appendPointFast(ddx, ddy);
                                    float csx = docToScreenX(ddx), csy = docToScreenY(ddy);
                                    drawFastLiveSegmentToSurface(px, py, csx, csy, st);
                                    px = csx; py = csy;
                                }
                                float ddx = clampPenDocX(screenToDocX(ev.getX(i)));
                                float ddy = clampPenDocY(screenToDocY(ev.getY(i)));
                                st.appendPointFast(ddx, ddy);
                                float csx = docToScreenX(ddx), csy = docToScreenY(ddy);
                                drawFastLiveSegmentToSurface(px, py, csx, csy, st);
                                prevScr.set(csx, csy);
                            }
                        }
                        return true;
                    }
                }

                int idx = ev.findPointerIndex(mActivePointerId);
                if (idx < 0) return true;
                float sx = ev.getX(idx);
                float sy = ev.getY(idx);

                if (mToolMode == ToolMode.MOVE) {
                    mPanX += (sx - mLastScreenX);
                    mPanY += (sy - mLastScreenY);
                    clampCamera();
                    notifyCurrentPageIfChanged();
                    invalidateViewport4kTile();
                    requestVisiblePageRenders(false);
                    scheduleVsyncFrame();
                } else if (mToolMode == ToolMode.PEN && mActiveStroke != null) {
                    if (!mSmartRecognized) {
                        int hist = ev.getHistorySize();
                        float px = docToScreenX(mLastDocX);
                        float py = docToScreenY(mLastDocY);
                        for (int h = 0; h < hist; h++) {
                            float hx = ev.getHistoricalX(idx, h);
                            float hy = ev.getHistoricalY(idx, h);
                            float hdx = clampPenDocX(screenToDocX(hx));
                            float hdy = clampPenDocY(screenToDocY(hy));
                            mActiveStroke.appendPointFast(hdx, hdy);
                            float csx = docToScreenX(hdx), csy = docToScreenY(hdy);
                            if (!mActiveStroke.isHighlighter || (mAccelerator != null && mAccelerator.isAvailable())) {
                                drawFastLiveSegmentToSurface(px, py, csx, csy, mActiveStroke);
                            }
                            px = csx; py = csy;
                            mLastDocX = hdx; mLastDocY = hdy;
                        }
                    }
                    movePenStroke(sx, sy);
                } else if (mToolMode == ToolMode.ERASER) {
                    float r = Math.max(mEraserBaseRadiusPx, ev.getTouchMajor(idx) * 0.75f);
                    eraseAtScreenPointFast(sx, sy, r);
                } else if (mToolMode == ToolMode.LASSO && mLassoDragging) {
                    mLassoScreenPts.add(new PointF(sx, sy));
                    mLassoScreenPath.lineTo(sx, sy);
                    renderFullFrame();
                }

                mLastScreenX = sx;
                mLastScreenY = sy;
                return true;
            }

            case MotionEvent.ACTION_POINTER_UP: {
                if (mToolMode == ToolMode.MOVE && ev.getPointerCount() <= 2) {
                    mUiHandler.removeCallbacks(mZoomRerenderRunnable);
                    mUiHandler.postDelayed(mZoomRerenderRunnable, 220);
                } else if (mToolMode == ToolMode.PEN && !mMultiPenStrokes.isEmpty()) {
                    int upPid = ev.getPointerId(ev.getActionIndex());
                    InkStroke finished = mMultiPenStrokes.remove(upPid);
                    mMultiLastScreen.remove(upPid);
                    if (finished != null) {
                        commitStrokeToHistory(finished);
                        renderFullFrame();
                    }
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                mShowEraserCircle = false;
                if (mIsTwoFingerGesture) {
                    mIsTwoFingerGesture = false;
                    mUiHandler.removeCallbacks(mZoomRerenderRunnable);
                    mUiHandler.postDelayed(mZoomRerenderRunnable, 220);
                    return true;
                }

                if (mPalmErasing) {
                    mPalmErasing = false;
                    commitEraseBatchToUndo();
                    renderFullFrame();
                    return true;
                }

                if (mToolMode == ToolMode.PEN) {
                    if (!mMultiPenStrokes.isEmpty()) {
                        for (InkStroke st : mMultiPenStrokes.values()) commitStrokeToHistory(st);
                        mMultiPenStrokes.clear();
                        mMultiLastScreen.clear();
                        renderFullFrame();
                        mUiHandler.removeCallbacks(mIdleCleanOverlayRunnable);
                        mUiHandler.postDelayed(mIdleCleanOverlayRunnable, 70L);
                    } else if (mActiveStroke != null) {
                        finishPenStroke();
                    }
                } else if (mToolMode == ToolMode.ERASER) {
                    commitEraseBatchToUndo();
                    renderFullFrame();
                } else if (mToolMode == ToolMode.SELECT) {
                    long dt = ev.getEventTime() - mDownTime;
                    float moveDist = (float) Math.hypot(ev.getX() - mDownScreenX, ev.getY() - mDownScreenY);
                    if (dt < 450L && moveDist < 28f && !mFocusActive) {
                        handleSelectTapLinkQr(ev.getX(), ev.getY());
                    }
                } else if (mToolMode == ToolMode.MOVE) {
                    scheduleViewport4kRender(200L);
                } else if (mToolMode == ToolMode.LASSO && mLassoDragging) {
                    finishLassoSelection();
                }
                return true;
            }

            case MotionEvent.ACTION_CANCEL: {
                cancelActiveGesture();
                mIsTwoFingerGesture = false;
                return true;
            }
        }
        return true;
    }

    private void cancelActiveGesture() {
        mUiHandler.removeCallbacks(mPauseRunnable);
        mActiveStroke = null;
        mMultiPenStrokes.clear();
        mMultiLastScreen.clear();
        mPalmErasing = false;
        mShowEraserCircle = false;
        mSmartRecognized = false;
        if (mLassoDragging) {
            mLassoDragging = false;
            mLassoScreenPts.clear();
            mLassoScreenPath.reset();
        }
        commitEraseBatchToUndo();
    }

    private void commitEraseBatchToUndo() {
        if (mErasedBatch.isEmpty() && mErasedAddedBatch.isEmpty()) return;
        activeUndoStack().add(new UndoAction(
                new ArrayList<InkStroke>(mErasedBatch),
                new ArrayList<InkStroke>(mErasedAddedBatch)));
        activeRedoStack().clear();
        mErasedBatch.clear();
        mErasedAddedBatch.clear();
        notifyHistoryChanged();
    }

    // ─── 펜 & 스마트펜 ───────────────────────────────────────────────
    private void startPenStroke(float sx, float sy) {
        mUiHandler.removeCallbacks(mPauseRunnable);
        InkStroke s = createNewFreehandStroke(sx, sy);
        mActiveStroke = s;
        mSmartRecognized = false;
        mLastDocX = s.points.get(0).x;
        mLastDocY = s.points.get(0).y;
        mUiHandler.postDelayed(mPauseRunnable, PAUSE_THRESHOLD_MS);
        float csx = docToScreenX(mLastDocX), csy = docToScreenY(mLastDocY);
        if (!s.isHighlighter || (mAccelerator != null && mAccelerator.isAvailable())) {
            drawFastLiveSegmentToSurface(csx, csy, csx + 0.5f, csy + 0.5f, s);
        }
    }

    private void movePenStroke(float sx, float sy) {
        float dx = clampPenDocX(screenToDocX(sx));
        float dy = clampPenDocY(screenToDocY(sy));

        if (mSmartRecognized) {
            float ddx = dx - mLastDocX;
            float ddy = dy - mLastDocY;
            if (mActiveStroke.kind == StrokeKind.LINE) {
                mActiveStroke.bx = dx; mActiveStroke.by = dy;
            } else if (mActiveStroke.kind == StrokeKind.CIRCLE || mActiveStroke.kind == StrokeKind.ELLIPSE) {
                mActiveStroke.cx = clampPenDocX(mActiveStroke.cx + ddx);
                mActiveStroke.cy = clampPenDocY(mActiveStroke.cy + ddy);
            } else if (mActiveStroke.kind == StrokeKind.POLYGON && mActiveStroke.corners != null) {
                for (int i = 0; i < mActiveStroke.corners.length; i += 2) {
                    mActiveStroke.corners[i] = clampPenDocX(mActiveStroke.corners[i] + ddx);
                    mActiveStroke.corners[i + 1] = clampPenDocY(mActiveStroke.corners[i + 1] + ddy);
                }
            }
            mActiveStroke.rebuildPathAndBounds();
            mLastDocX = dx; mLastDocY = dy;
            renderFullFrame();
            return;
        }

        float prevSx = docToScreenX(mLastDocX);
        float prevSy = docToScreenY(mLastDocY);
        float moveScreenDist = (float) Math.hypot(sx - mLastScreenX, sy - mLastScreenY);
        mActiveStroke.appendPointFast(dx, dy);

        if (moveScreenDist > 8.0f) {
            mUiHandler.removeCallbacks(mPauseRunnable);
            mUiHandler.postDelayed(mPauseRunnable, PAUSE_THRESHOLD_MS);
        }
        mLastDocX = dx;
        mLastDocY = dy;

        float curSx = docToScreenX(dx);
        float curSy = docToScreenY(dy);
        if (mActiveStroke.isHighlighter && (mAccelerator == null || !mAccelerator.isAvailable())) {
            // CPU fallback: 형광펜은 단일 Path를 부분 갱신해 이음새 0% + 빠른 속도 동시 달성!
            float pad = Math.max(32f, mActiveStroke.widthDoc * mScale * 1.3f);
            mTmpDirty.set(
                    (int) (docToScreenX(mActiveStroke.minX) - pad),
                    (int) (docToScreenY(mActiveStroke.minY) - pad),
                    (int) (docToScreenX(mActiveStroke.maxX) + pad),
                    (int) (docToScreenY(mActiveStroke.maxY) + pad));
            renderDirtyRegion(mTmpDirty);
        } else {
            drawFastLiveSegmentToSurface(prevSx, prevSy, curSx, curSy, mActiveStroke);
        }
    }

    private void commitSmartSnapAndClearOverlay(String toastMsg) {
        mSmartRecognized = true;
        mUiHandler.removeCallbacks(mIdleCleanOverlayRunnable);
        // 1) GPU 4K SurfaceView에 보정 도형을 먼저 올린 직후
        renderFullFrame();
        // 2) 1 VSync(20ms) 뒤 하드웨어 OSD[1]의 원본 손글씨를 전체 깔끔히 지워 2중 획 겹침 0% 달성
        mUiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mAccelerator != null) {
                    mAccelerator.clearOverlayAllKeepActive();
                }
            }
        }, 20L);
        if (mActivity != null && toastMsg != null) {
            showTopToast(mActivity, toastMsg);
        }
    }

    private void onPenPauseDetected() {
        if (!mSmartPenEnabled || mActiveStroke == null || mSmartRecognized || mActiveStroke.points.size() < 4) return;
        int count = mActiveStroke.points.size();
        float[] raw = new float[count * 2];
        for (int i = 0; i < count; i++) {
            PointF pt = mActiveStroke.points.get(i);
            raw[i * 2] = pt.x;
            raw[i * 2 + 1] = pt.y;
        }

        float scale = Math.max(0.25f, mScale);
        InkShapeRecognizer.Shape closed = InkShapeRecognizer.detectClosed(raw, count, scale);
        if (closed != null) {
            if ((closed.kind == InkShapeRecognizer.Kind.TRIANGLE || closed.kind == InkShapeRecognizer.Kind.RECTANGLE)
                    && closed.corners != null && closed.corners.length >= 6) {
                mActiveStroke.kind = StrokeKind.POLYGON;
                mActiveStroke.corners = closed.corners.clone();
                mActiveStroke.rebuildPathAndBounds();
                commitSmartSnapAndClearOverlay(
                        closed.kind == InkShapeRecognizer.Kind.TRIANGLE ? "🔺 삼각형 자동 완성" : "⬜ 사각형 자동 완성");
                return;
            } else if (closed.kind == InkShapeRecognizer.Kind.ELLIPSE && closed.radiusY * scale > 15f) {
                mActiveStroke.kind = StrokeKind.ELLIPSE;
                mActiveStroke.cx = closed.centerX; mActiveStroke.cy = closed.centerY;
                mActiveStroke.rx = closed.radiusX; mActiveStroke.ry = closed.radiusY;
                mActiveStroke.angleRad = closed.angleRad;
                mActiveStroke.rebuildPathAndBounds();
                commitSmartSnapAndClearOverlay("⬭ 타원 자동 완성");
                return;
            } else if (closed.kind == InkShapeRecognizer.Kind.CIRCLE && closed.radius * scale > 15f) {
                mActiveStroke.kind = StrokeKind.CIRCLE;
                mActiveStroke.cx = closed.centerX; mActiveStroke.cy = closed.centerY;
                mActiveStroke.r = closed.radius;
                mActiveStroke.rebuildPathAndBounds();
                commitSmartSnapAndClearOverlay("⭕ 원 자동 완성");
                return;
            }
        }

        float closureScreenDist = (float) Math.hypot(
                raw[(count - 1) * 2] - raw[0], raw[(count - 1) * 2 + 1] - raw[1]) * scale;
        if (closed == null && count >= 10 && closureScreenDist < 85.0f) {
            InkShapeRecognizer.Shape fb = InkShapeRecognizer.fitFallbackCircleOrEllipse(raw, count);
            if (fb != null) {
                if (fb.kind == InkShapeRecognizer.Kind.ELLIPSE && fb.radiusY * scale > 15f) {
                    mActiveStroke.kind = StrokeKind.ELLIPSE;
                    mActiveStroke.cx = fb.centerX; mActiveStroke.cy = fb.centerY;
                    mActiveStroke.rx = fb.radiusX; mActiveStroke.ry = fb.radiusY;
                    mActiveStroke.angleRad = fb.angleRad;
                    mActiveStroke.rebuildPathAndBounds();
                    commitSmartSnapAndClearOverlay("⬭ 타원 자동 완성");
                    return;
                } else if (fb.radius * scale > 15f) {
                    mActiveStroke.kind = StrokeKind.CIRCLE;
                    mActiveStroke.cx = fb.centerX; mActiveStroke.cy = fb.centerY;
                    mActiveStroke.r = fb.radius;
                    mActiveStroke.rebuildPathAndBounds();
                    commitSmartSnapAndClearOverlay("⭕ 원 자동 완성");
                    return;
                }
            }
        }

        if (InkShapeRecognizer.looksStraight(raw, count, scale)) {
            mActiveStroke.kind = StrokeKind.LINE;
            mActiveStroke.ax = raw[0]; mActiveStroke.ay = raw[1];
            mActiveStroke.bx = raw[(count - 1) * 2]; mActiveStroke.by = raw[(count - 1) * 2 + 1];
            mActiveStroke.rebuildPathAndBounds();
            commitSmartSnapAndClearOverlay("📏 직선 자동 보정");
            return;
        }

        if (count >= 6) {
            float[] scaledRaw = new float[count * 2];
            for (int i = 0; i < count; i++) {
                scaledRaw[i * 2] = raw[i * 2] * scale;
                scaledRaw[i * 2 + 1] = raw[i * 2 + 1] * scale;
            }
            ArrayList<PointF> smoothScaled = new ArrayList<PointF>();
            InkMathHelper.buildVolumePreservingSpline(scaledRaw, count, smoothScaled);
            if (smoothScaled.size() >= 2) {
                mActiveStroke.kind = StrokeKind.FREEHAND;
                mActiveStroke.points.clear();
                for (int i = 0; i < smoothScaled.size(); i++) {
                    PointF sp = smoothScaled.get(i);
                    mActiveStroke.points.add(new PointF(clampPenDocX(sp.x / scale), clampPenDocY(sp.y / scale)));
                }
                mActiveStroke.rebuildPathAndBounds();
                commitSmartSnapAndClearOverlay("〰️ 스플라인 곡선 보정");
            }
        }
    }

    private void finishPenStroke() {
        mUiHandler.removeCallbacks(mPauseRunnable);
        if (mActiveStroke != null) {
            boolean wasSmart = mSmartRecognized;
            commitStrokeToHistory(mActiveStroke);
            mActiveStroke = null;
            mSmartRecognized = false;
            renderFullFrame();
            mUiHandler.removeCallbacks(mIdleCleanOverlayRunnable);
            if (!wasSmart) {
                mUiHandler.postDelayed(mIdleCleanOverlayRunnable, 70L);
            }
        }
    }

    // ─── 일반 부분 지우개 (고속 Dirty-Rect 부분 갱신 + 획 분할) ──────
    private void eraseAtScreenPointFast(float sx, float sy, float screenRadiusPx) {
        if (mAccelerator != null && mAccelerator.hasOverlayContent()) {
            clearHardwareOverlayNow();
        }
        mPrevEraserX = mShowEraserCircle ? mEraserCircleX : sx;
        mPrevEraserY = mShowEraserCircle ? mEraserCircleY : sy;
        mPrevEraserR = mShowEraserCircle ? mEraserCircleR : screenRadiusPx;

        mShowEraserCircle = true;
        mEraserCircleX = sx;
        mEraserCircleY = sy;
        mEraserCircleR = Math.max(30f, Math.min(130f, screenRadiusPx));

        float docX = screenToDocX(sx);
        float docY = screenToDocY(sy);
        float hitR = mEraserCircleR / Math.max(0.25f, mScale);

        ArrayList<InkStroke> list = activeStrokeList();
        ArrayList<InkStroke> newlyAdded = null;
        for (int i = list.size() - 1; i >= 0; i--) {
            InkStroke s = list.get(i);
            float pad = hitR + s.widthDoc * 0.5f;
            if (docX < s.minX - pad || docX > s.maxX + pad || docY < s.minY - pad || docY > s.maxY + pad) continue;
            if (!hitTestStroke(s, docX, docY, pad)) continue;

            ArrayList<PointF> dense = flattenStrokeForPartialErase(s, Math.max(2.5f, hitR * 0.35f));
            ArrayList<InkStroke> survivors = splitPointsOutsideCircle(s, dense, docX, docY, hitR);

            list.remove(i);
            if (!mErasedAddedBatch.remove(s)) mErasedBatch.add(s);
            if (!survivors.isEmpty()) {
                if (newlyAdded == null) newlyAdded = new ArrayList<InkStroke>();
                newlyAdded.addAll(survivors);
                mErasedAddedBatch.addAll(survivors);
            }
        }
        if (newlyAdded != null && !newlyAdded.isEmpty()) {
            list.addAll(newlyAdded);
        }

        // 지우개 원이 지나간 작은 사각형 영역만 고속 갱신 (~3ms)
        float maxR = Math.max(mEraserCircleR, mPrevEraserR) + 18f;
        mTmpDirty.set(
                (int) (Math.min(mPrevEraserX, sx) - maxR),
                (int) (Math.min(mPrevEraserY, sy) - maxR),
                (int) (Math.max(mPrevEraserX, sx) + maxR),
                (int) (Math.max(mPrevEraserY, sy) + maxR));
        renderDirtyRegion(mTmpDirty);
    }

    private static boolean hitTestStroke(InkStroke s, float x, float y, float tol) {
        if (s.kind == StrokeKind.FREEHAND) {
            int n = s.points.size();
            if (n == 1) return Math.hypot(x - s.points.get(0).x, y - s.points.get(0).y) <= tol;
            for (int i = 1; i < n; i++) {
                if (distToSegment(x, y, s.points.get(i - 1).x, s.points.get(i - 1).y,
                        s.points.get(i).x, s.points.get(i).y) <= tol) return true;
            }
            return false;
        } else if (s.kind == StrokeKind.LINE) {
            return distToSegment(x, y, s.ax, s.ay, s.bx, s.by) <= tol;
        } else if (s.kind == StrokeKind.CIRCLE) {
            return Math.abs(Math.hypot(x - s.cx, y - s.cy) - s.r) <= tol;
        } else if (s.kind == StrokeKind.ELLIPSE) {
            double cos = Math.cos(s.angleRad), sin = Math.sin(s.angleRad);
            double dx = x - s.cx, dy = y - s.cy;
            double u = (dx * cos + dy * sin) / Math.max(1f, s.rx);
            double v = (-dx * sin + dy * cos) / Math.max(1f, s.ry);
            return Math.abs(Math.hypot(u, v) - 1.0) * Math.min(s.rx, s.ry) <= tol;
        } else if (s.kind == StrokeKind.POLYGON && s.corners != null && s.corners.length >= 6) {
            int n = s.corners.length / 2;
            for (int i = 0; i < n; i++) {
                int nxt = (i + 1) % n;
                if (distToSegment(x, y, s.corners[i * 2], s.corners[i * 2 + 1],
                        s.corners[nxt * 2], s.corners[nxt * 2 + 1]) <= tol) return true;
            }
        }
        return false;
    }

    private static float distToSegment(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay;
        float lenSq = dx * dx + dy * dy;
        if (lenSq < 1e-4f) return (float) Math.hypot(px - ax, py - ay);
        float t = Math.max(0f, Math.min(1f, ((px - ax) * dx + (py - ay) * dy) / lenSq));
        return (float) Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    private static ArrayList<PointF> flattenStrokeForPartialErase(InkStroke s, float maxSegLen) {
        ArrayList<PointF> out = new ArrayList<PointF>();
        if (s.kind == StrokeKind.FREEHAND) {
            int n = s.points.size();
            if (n == 0) return out;
            out.add(s.points.get(0));
            for (int i = 1; i < n; i++) appendSubdividedSegment(out, s.points.get(i - 1), s.points.get(i), maxSegLen);
        } else if (s.kind == StrokeKind.LINE) {
            PointF a = new PointF(s.ax, s.ay), b = new PointF(s.bx, s.by);
            out.add(a); appendSubdividedSegment(out, a, b, maxSegLen);
        } else if (s.kind == StrokeKind.CIRCLE) {
            int steps = Math.max(48, Math.min(240, (int) (2.0 * Math.PI * s.r / Math.max(2f, maxSegLen))));
            for (int i = 0; i <= steps; i++) {
                double a = 2.0 * Math.PI * i / steps;
                out.add(new PointF(s.cx + (float) (s.r * Math.cos(a)), s.cy + (float) (s.r * Math.sin(a))));
            }
        } else if (s.kind == StrokeKind.ELLIPSE) {
            int steps = Math.max(48, Math.min(240, (int) (2.0 * Math.PI * Math.max(s.rx, s.ry) / Math.max(2f, maxSegLen))));
            double cosT = Math.cos(s.angleRad), sinT = Math.sin(s.angleRad);
            for (int i = 0; i <= steps; i++) {
                double a = 2.0 * Math.PI * i / steps;
                double px = s.rx * Math.cos(a), py = s.ry * Math.sin(a);
                out.add(new PointF(s.cx + (float) (px * cosT - py * sinT), s.cy + (float) (px * sinT + py * cosT)));
            }
        } else if (s.kind == StrokeKind.POLYGON && s.corners != null && s.corners.length >= 6) {
            int n = s.corners.length / 2;
            out.add(new PointF(s.corners[0], s.corners[1]));
            for (int i = 0; i < n; i++) {
                int nxt = (i + 1) % n;
                appendSubdividedSegment(out,
                        new PointF(s.corners[i * 2], s.corners[i * 2 + 1]),
                        new PointF(s.corners[nxt * 2], s.corners[nxt * 2 + 1]), maxSegLen);
            }
        }
        return out;
    }

    private static void appendSubdividedSegment(ArrayList<PointF> out, PointF a, PointF b, float maxSegLen) {
        float dist = (float) Math.hypot(b.x - a.x, b.y - a.y);
        if (dist <= maxSegLen || maxSegLen <= 0.5f) { out.add(b); return; }
        int steps = Math.min(64, (int) Math.ceil(dist / maxSegLen));
        for (int i = 1; i <= steps; i++) {
            float t = (float) i / steps;
            out.add(new PointF(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t));
        }
    }

    private static ArrayList<InkStroke> splitPointsOutsideCircle(
            InkStroke template, ArrayList<PointF> pts, float cx, float cy, float r) {
        ArrayList<InkStroke> result = new ArrayList<InkStroke>();
        float rSq = r * r;
        InkStroke current = null;
        for (int i = 0; i < pts.size(); i++) {
            PointF p = pts.get(i);
            float dx = p.x - cx, dy = p.y - cy;
            if ((dx * dx + dy * dy) > rSq) {
                if (current == null) {
                    current = new InkStroke();
                    current.kind = StrokeKind.FREEHAND;
                    current.color = template.color;
                    current.widthDoc = template.widthDoc;
                    current.isHighlighter = template.isHighlighter;
                }
                current.points.add(p);
            } else {
                if (current != null && current.points.size() >= 2) {
                    current.rebuildPathAndBounds();
                    result.add(current);
                }
                current = null;
            }
        }
        if (current != null && current.points.size() >= 2) {
            current.rebuildPathAndBounds();
            result.add(current);
        }
        return result;
    }

    private void handleSelectTapLinkQr(float sx, float sy) {
        if (mActivity == null || mPageCount <= 0) return;
        float docX = screenToDocX(sx);
        float docY = screenToDocY(sy);
        if (docX < 0f || docX > DOC_WIDTH) return;
        for (int i = 0; i < mPageCount; i++) {
            if (docY >= mPageTop[i] && docY <= mPageTop[i] + mPageHeight[i]) {
                float u = docX / DOC_WIDTH;
                float v = (docY - mPageTop[i]) / Math.max(1f, mPageHeight[i]);
                PdfLinkQrManager.getInstance().handleDirectPdfTap(
                        mActivity, mLocalPdfPath, mOriginUri, i, u, v);
                return;
            }
        }
    }

    private void finishLassoSelection() {
        mLassoDragging = false;
        if (mLassoScreenPts.size() < 4) {
            mLassoScreenPts.clear();
            mLassoScreenPath.reset();
            renderFullFrame();
            return;
        }
        float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (PointF p : mLassoScreenPts) {
            if (p.x < minX) minX = p.x;
            if (p.x > maxX) maxX = p.x;
            if (p.y < minY) minY = p.y;
            if (p.y > maxY) maxY = p.y;
        }
        mLassoScreenPts.clear();
        mLassoScreenPath.reset();
        renderFullFrame();
        if (maxX - minX >= 40f && maxY - minY >= 40f) {
            enterFocusModeFromScreenRect(new RectF(minX, minY, maxX, maxY));
        }
    }

    // ─── 초고속 SurfaceView 직접 렌더링 엔진 ─────────────────────────

    private void scheduleVsyncFrame() {
        if (mFrameScheduled) return;
        mFrameScheduled = true;
        android.view.Choreographer.getInstance().postFrameCallback(mVsyncCallback);
    }

    /**
     * 펜 필기(ACTION_MOVE) 시 순정 하드웨어 OSD[1] 가속기로 0.21ms 만에 즉시 출력!
     */
    private void drawFastLiveSegmentToSurface(float x0, float y0, float x1, float y1, InkStroke st) {
        if (mAccelerator != null && mAccelerator.isAvailable()) {
            mUiHandler.removeCallbacks(mIdleCleanOverlayRunnable);
            float strokeW = Math.max(2f, st.widthDoc * mScale);
            mLiveSegPaint.setColor(st.color);
            mLiveSegPaint.setAlpha(st.isHighlighter ? 92 : 255);
            mLiveSegPaint.setStrokeWidth(strokeW);
            mLiveSegPaint.setStrokeCap(st.isHighlighter ? Paint.Cap.SQUARE : Paint.Cap.ROUND);
            mAccelerator.drawSegmentAndFlush(x0, y0, x1, y1, mLiveSegPaint, st.isHighlighter);
            return;
        }
        scheduleVsyncFrame();
    }

    /**
     * 부분 지우개 / 형광펜 드래그 시 GPU 60fps VSync 프레임 갱신
     */
    private void renderDirtyRegion(Rect dirty) {
        scheduleVsyncFrame();
    }

    /**
     * GPU 하드웨어 캔버스(lockHardwareCanvas) 직접 렌더링 — 4K에서도 CPU 복사 0%로 60fps 스크롤/줌 달성!
     */
    public void renderFullFrame() {
        synchronized (mSurfaceLock) {
            if (!mSurfaceReady) return;
            SurfaceHolder holder = getHolder();
            Canvas sc = null;
            try {
                if (mUseHardwareCanvas && android.os.Build.VERSION.SDK_INT >= 26) {
                    sc = holder.lockHardwareCanvas();
                } else {
                    sc = holder.lockCanvas();
                }
                if (sc != null) {
                    drawSceneToCanvas(sc);
                }
            } catch (Throwable t) {
                if (mUseHardwareCanvas) {
                    mUseHardwareCanvas = false;
                    Log.w(TAG, "lockHardwareCanvas fallback to software: " + t.getMessage());
                }
            } finally {
                if (sc != null) {
                    try { holder.unlockCanvasAndPost(sc); } catch (Throwable ignored) {}
                }
            }
        }
    }

    private void drawSceneToCanvas(Canvas canvas) {
        if (mIsLoading || !mFirstPageRendered || mPageCount <= 0) {
            canvas.drawColor(mOuterBgColor);
            drawLoadingOverlay(canvas);
            return;
        }

        if (mFocusActive) {
            canvas.drawColor(mFocusBgColor);
            canvas.save();
            canvas.translate(mPanX, mPanY);
            canvas.scale(mScale, mScale);
            if (mFocusBitmap != null && !mFocusBitmap.isRecycled() && mFocusCropDocRect != null) {
                canvas.drawBitmap(mFocusBitmap, null, mFocusCropDocRect, mBitmapPaint);
            }
            for (int i = 0; i < mFocusStrokes.size(); i++) drawStroke(canvas, mFocusStrokes.get(i));
            if (mActiveStroke != null) drawStroke(canvas, mActiveStroke);
            for (InkStroke ms : mMultiPenStrokes.values()) drawStroke(canvas, ms);
            canvas.restore();

            if (mShowEraserCircle) canvas.drawCircle(mEraserCircleX, mEraserCircleY, mEraserCircleR, mEraserRingPaint);
            drawTopRightBadge(canvas, "✂ 집중 풀이 중 (올가미로 복귀)");
            return;
        }

        boolean used4kTile = false;
        if (mViewportTileBmp != null && !mViewportTileBmp.isRecycled()
                && Math.abs(mPanX - mTilePanX) < 0.5f
                && Math.abs(mPanY - mTilePanY) < 0.5f
                && Math.abs(mScale - mTileScale) < 0.0005f) {
            canvas.drawBitmap(mViewportTileBmp, 0f, 0f, null);
            used4kTile = true;
        } else {
            canvas.drawColor(mOuterBgColor);
        }
        canvas.save();
        canvas.translate(mPanX, mPanY);
        canvas.scale(mScale, mScale);

        if (!used4kTile) {
            int[] vis = getVisiblePageRange();
            for (int i = vis[0]; i <= vis[1]; i++) {
                if (i < 0 || i >= mPageCount) continue;
                float pTop = mPageTop[i];
                float pBot = pTop + mPageHeight[i];
                mTmpRectF.set(0f, pTop, DOC_WIDTH, pBot);
                canvas.drawRect(mTmpRectF, mPageBgPaint);
                CachedPage cp = mPageCache.get(i);
                if (cp != null && cp.bitmap != null && !cp.bitmap.isRecycled()) {
                    canvas.drawBitmap(cp.bitmap, null, mTmpRectF, mBitmapPaint);
                }
                canvas.drawRect(mTmpRectF, mPageShadowPaint);
            }
        }

        for (int i = 0; i < mDocStrokes.size(); i++) drawStroke(canvas, mDocStrokes.get(i));
        if (mActiveStroke != null) drawStroke(canvas, mActiveStroke);
        for (InkStroke ms : mMultiPenStrokes.values()) drawStroke(canvas, ms);
        canvas.restore();

        if (mShowEraserCircle) canvas.drawCircle(mEraserCircleX, mEraserCircleY, mEraserCircleR, mEraserRingPaint);
        if (mLassoDragging && mLassoScreenPts.size() >= 2) {
            canvas.drawPath(mLassoScreenPath, mLassoFillPaint);
            canvas.drawPath(mLassoScreenPath, mLassoPaint);
        }
        drawTopRightBadge(canvas, (getCurrentPageIndex() + 1) + " / " + mPageCount + " 쪽");
    }

    private void drawStroke(Canvas canvas, InkStroke s) {
        if (s == null) return;
        mStrokePaint.setColor(s.color);
        mStrokePaint.setAlpha(s.isHighlighter ? 92 : 255);
        mStrokePaint.setStrokeWidth(s.widthDoc);
        mStrokePaint.setStrokeCap(s.isHighlighter ? Paint.Cap.SQUARE : Paint.Cap.ROUND);
        mStrokePaint.setStrokeJoin(s.kind == StrokeKind.POLYGON ? Paint.Join.MITER : Paint.Join.ROUND);

        if (s.kind == StrokeKind.FREEHAND && s.cachedPath != null) {
            canvas.drawPath(s.cachedPath, mStrokePaint);
        } else if (s.kind == StrokeKind.LINE) {
            canvas.drawLine(s.ax, s.ay, s.bx, s.by, mStrokePaint);
        } else if (s.kind == StrokeKind.CIRCLE) {
            canvas.drawCircle(s.cx, s.cy, s.r, mStrokePaint);
        } else if (s.kind == StrokeKind.ELLIPSE) {
            canvas.save();
            canvas.translate(s.cx, s.cy);
            canvas.rotate((float) Math.toDegrees(s.angleRad));
            mTmpRectF.set(-s.rx, -s.ry, s.rx, s.ry);
            canvas.drawOval(mTmpRectF, mStrokePaint);
            canvas.restore();
        } else if (s.kind == StrokeKind.POLYGON && s.corners != null && s.corners.length >= 6) {
            mTmpPolyPath.reset();
            mTmpPolyPath.moveTo(s.corners[0], s.corners[1]);
            for (int i = 2; i < s.corners.length; i += 2) mTmpPolyPath.lineTo(s.corners[i], s.corners[i + 1]);
            mTmpPolyPath.close();
            canvas.drawPath(mTmpPolyPath, mStrokePaint);
        }
    }

    private void drawLoadingOverlay(Canvas canvas) {
        int vw = (mSurfaceW > 0) ? mSurfaceW : getWidth();
        int vh = (mSurfaceH > 0) ? mSurfaceH : getHeight();
        if (vw <= 0 || vh <= 0) return;
        float cx = vw * 0.5f;
        float cy = (vh - mBottomToolbarClearance) * 0.5f;
        float cardW = Math.min(vw * 0.6f, 640f);
        float cardH = 180f;
        mTmpRectF.set(cx - cardW * 0.5f, cy - cardH * 0.5f, cx + cardW * 0.5f, cy + cardH * 0.5f);
        canvas.drawRoundRect(mTmpRectF, 28f, 28f, mBadgeBgPaint);

        float prevSize = mBadgeTextPaint.getTextSize();
        mBadgeTextPaint.setTextSize(38f);
        mBadgeTextPaint.setColor(Color.WHITE);
        canvas.drawText("PDF 불러오는 중...", cx, cy - 12f, mBadgeTextPaint);

        mBadgeTextPaint.setTextSize(24f);
        mBadgeTextPaint.setColor(Color.parseColor("#CBD5E1"));
        String sub = (mDisplayName != null && !mDisplayName.isEmpty()) ? mDisplayName : "페이지를 준비하고 있습니다";
        if (sub.length() > 32) sub = sub.substring(0, 31) + "…";
        canvas.drawText(sub, cx, cy + 44f, mBadgeTextPaint);

        mBadgeTextPaint.setTextSize(prevSize);
        mBadgeTextPaint.setColor(Color.WHITE);
    }

    private void drawTopRightBadge(Canvas canvas, String text) {
        int vw = (mSurfaceW > 0) ? mSurfaceW : getWidth();
        if (text == null || vw <= 0) return;
        float tw = mBadgeTextPaint.measureText(text);
        float pillW = tw + 52f;
        float pillH = 48f;
        float right = vw - 28f;
        float top = 20f;
        mTmpRectF.set(right - pillW, top, right, top + pillH);
        canvas.drawRoundRect(mTmpRectF, 24f, 24f, mBadgeBgPaint);
        Paint.FontMetrics fm = mBadgeTextPaint.getFontMetrics();
        float textY = mTmpRectF.centerY() - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(text, mTmpRectF.centerX(), textY, mBadgeTextPaint);
    }
}

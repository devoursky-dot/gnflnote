package kr.hs.gnflhs.gnflpdf;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;

import com.seewo.easinote.accelerator.base.CvtRenderNativeInterface;

/**
 * 10ms Hardware DRM Front-Buffer Ink Accelerator using Seewo/Amlogic libaccelerator_base.so.
 * Draws live ink segments directly to the hardware OSD[1] overlay plane (0.21ms flush),
 * bypassing SurfaceFlinger/VSync, with flash-free keep-alive overlay management.
 */
public class HardwareInkAccelerator {
    private static final String TAG = "GNFLPDF_ACC";

    private static boolean sLibLoaded = false;
    private static boolean sLibLoadFailed = false;

    private int fbWidth = 3840;
    private int fbHeight = 2160;
    private float viewToFbScaleX = 1.0f;
    private float viewToFbScaleY = 1.0f;
    private float viewOffsetX = 0f;
    private float viewOffsetY = 0f;
    private final int instanceId = System.identityHashCode(this);
    private final Paint scaledPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final PorterDuffXfermode SRC_MODE = new PorterDuffXfermode(PorterDuff.Mode.SRC);

    private boolean initialized = false;
    private boolean renderEnabled = false;
    private boolean preparing = false;

    private Bitmap overlayBitmap;
    private Canvas overlayCanvas;
    private final Rect fullRect = new Rect();
    private final Rect dirtyRect = new Rect();
    private final RectF tempBounds = new RectF();
    private final Rect accumulatedDirty = new Rect();
    private boolean hasOverlayContent = false;

    private long lastFlushMicros = 0L;
    private long totalFlushCount = 0L;

    public static boolean ensureLibraryLoaded() {
        if (sLibLoaded) return true;
        if (sLibLoadFailed) return false;
        try {
            System.loadLibrary("c++_shared");
        } catch (Throwable t) {
            Log.w(TAG, "c++_shared preload warning: " + t.getMessage());
        }
        try {
            System.loadLibrary("mtal");
        } catch (Throwable t) {
            Log.w(TAG, "mtal preload warning: " + t.getMessage());
        }
        try {
            System.loadLibrary("accelerator_base");
            sLibLoaded = true;
            Log.i(TAG, "libaccelerator_base.so loaded successfully!");
            return true;
        } catch (Throwable t) {
            sLibLoadFailed = true;
            Log.e(TAG, "Failed to load libaccelerator_base.so: " + t.getMessage(), t);
            return false;
        }
    }

    public void setViewMapping(int viewScreenX, int viewScreenY, int realDisplayW, int realDisplayH) {
        if (realDisplayW > 0 && realDisplayH > 0) {
            viewToFbScaleX = (float) fbWidth / (float) realDisplayW;
            viewToFbScaleY = (float) fbHeight / (float) realDisplayH;
            viewOffsetX = viewScreenX * viewToFbScaleX;
            viewOffsetY = viewScreenY * viewToFbScaleY;
            Log.i(TAG, "View->FB mapping: scale=(" + viewToFbScaleX + "," + viewToFbScaleY
                    + ") offset=(" + viewOffsetX + "," + viewOffsetY + ")");
        }
    }

    public boolean init(int width, int height) {
        if (initialized && width == fbWidth && height == fbHeight) {
            return true;
        }
        if (initialized) {
            destroy();
        }
        if (!ensureLibraryLoaded()) {
            return false;
        }
        fbWidth = width;
        fbHeight = height;
        fullRect.set(0, 0, fbWidth, fbHeight);
        try {
            long t0 = System.nanoTime();
            boolean ok = CvtRenderNativeInterface.init(
                    "amlogic",
                    false, // isScreenFreeze
                    false, // isDebug (keep logs quiet in production)
                    fbWidth,
                    fbHeight,
                    1,     // players
                    null,  // regTool
                    instanceId
            );
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            Log.i(TAG, "CvtRenderNativeInterface.init(" + fbWidth + "x" + fbHeight + ") -> " + ok + " (" + ms + "ms)");
            if (!ok) {
                return false;
            }

            overlayBitmap = Bitmap.createBitmap(fbWidth, fbHeight, Bitmap.Config.ARGB_8888);
            overlayBitmap.eraseColor(Color.TRANSPARENT);
            overlayCanvas = new Canvas(overlayBitmap);

            CvtRenderNativeInterface.setEnableTransparentMode(true);
            boolean layerOk = CvtRenderNativeInterface.setLayer(0, 0, 0, overlayBitmap);
            boolean boundsOk = CvtRenderNativeInterface.setLayerRenderBounds(0, fullRect);
            CvtRenderNativeInterface.setRenderBounds(fullRect);
            boolean enableOk = CvtRenderNativeInterface.enableRender(true);

            Log.i(TAG, "setLayer=" + layerOk + " setLayerRenderBounds=" + boundsOk + " enableRender=" + enableOk);

            initialized = true;
            renderEnabled = enableOk;
            preparing = false;
            hasOverlayContent = false;
            accumulatedDirty.setEmpty();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "HardwareInkAccelerator init exception", t);
            destroy();
            return false;
        }
    }

    public boolean isAvailable() {
        return initialized && overlayBitmap != null && !overlayBitmap.isRecycled();
    }

    public boolean hasOverlayContent() {
        return hasOverlayContent;
    }

    public long getLastFlushMicros() {
        return lastFlushMicros;
    }

    public long getTotalFlushCount() {
        return totalFlushCount;
    }

    /**
     * Activates hardware OSD[1] plane once for pen/highlighter writing.
     * Staying in prepared state across consecutive strokes avoids OSD enable/disable flash!
     */
    public void beginStroke() {
        if (!isAvailable()) return;
        try {
            if (!renderEnabled) {
                renderEnabled = CvtRenderNativeInterface.enableRender(true);
            }
            if (!preparing) {
                CvtRenderNativeInterface.prepareToRender();
                preparing = true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "beginStroke error: " + t.getMessage());
        }
    }

    public void drawSegmentAndFlush(float x0, float y0, float x1, float y1, Paint paint) {
        drawSegmentAndFlush(x0, y0, x1, y1, paint, false);
    }

    /**
     * Draws a single ink segment onto the hardware overlay bitmap and flushes ONLY the dirty rect
     * directly to the hardware DRM front-buffer (~0.2ms).
     */
    public void drawSegmentAndFlush(float x0, float y0, float x1, float y1, Paint paint, boolean isHighlighter) {
        if (!isAvailable()) return;
        if (!preparing) {
            beginStroke();
        }
        try {
            float fx0 = x0 * viewToFbScaleX + viewOffsetX;
            float fy0 = y0 * viewToFbScaleY + viewOffsetY;
            float fx1 = x1 * viewToFbScaleX + viewOffsetX;
            float fy1 = y1 * viewToFbScaleY + viewOffsetY;
            scaledPaint.set(paint);
            scaledPaint.setStrokeWidth(paint.getStrokeWidth() * viewToFbScaleX);
            if (isHighlighter) {
                // SRC mode prevents overlapping semi-transparent segments from darkening at joints
                scaledPaint.setXfermode(SRC_MODE);
            } else {
                scaledPaint.setXfermode(null);
            }

            overlayCanvas.drawLine(fx0, fy0, fx1, fy1, scaledPaint);
            int pad = (int) Math.ceil(scaledPaint.getStrokeWidth() * 0.75f) + 8;
            int left = Math.max(0, (int) Math.floor(Math.min(fx0, fx1)) - pad);
            int top = Math.max(0, (int) Math.floor(Math.min(fy0, fy1)) - pad);
            int right = Math.min(fbWidth, (int) Math.ceil(Math.max(fx0, fx1)) + pad);
            int bottom = Math.min(fbHeight, (int) Math.ceil(Math.max(fy0, fy1)) + pad);
            if (right > left && bottom > top) {
                dirtyRect.set(left, top, right, bottom);
                accumulatedDirty.union(dirtyRect);
                hasOverlayContent = true;

                long t0 = System.nanoTime();
                CvtRenderNativeInterface.render(0, dirtyRect, false);
                lastFlushMicros = (System.nanoTime() - t0) / 1000L;
                totalFlushCount++;
            }
        } catch (Throwable t) {
            Log.w(TAG, "drawSegmentAndFlush error: " + t.getMessage());
        }
    }

    /**
     * Clears the transparent ink pixels on OSD[1] WITHOUT calling finishRender() or toggling OSD[1].
     * Call this after the underlying SurfaceView/View has presented the committed stroke (~350-500ms idle)
     * so the handoff is 100% invisible with ZERO screen flash!
     */
    public void clearOverlayKeepActive() {
        if (!isAvailable()) return;
        if (!hasOverlayContent) return;
        try {
            if (overlayCanvas != null) {
                overlayCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            }
            if (!accumulatedDirty.isEmpty()) {
                CvtRenderNativeInterface.clean(accumulatedDirty);
            } else {
                CvtRenderNativeInterface.clean(null);
            }
            accumulatedDirty.setEmpty();
            hasOverlayContent = false;
        } catch (Throwable t) {
            Log.w(TAG, "clearOverlayKeepActive error: " + t.getMessage());
        }
    }

    /**
     * Full-screen OSD[1] clear WITHOUT calling finishRender() (used on SmartPen snap so zero
     * residual hand-drawn ink pixels remain anywhere on the overlay).
     */
    public void clearOverlayAllKeepActive() {
        if (!isAvailable()) return;
        try {
            if (overlayCanvas != null) {
                overlayCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            }
            CvtRenderNativeInterface.clean(null);
            accumulatedDirty.setEmpty();
            hasOverlayContent = false;
        } catch (Throwable t) {
            Log.w(TAG, "clearOverlayAllKeepActive error: " + t.getMessage());
        }
    }

    /**
     * Fully clears and suspends the hardware OSD[1] overlay plane.
     * Call ONLY when switching away from Pen/Highlighter (to Move/Scroll/Eraser/Lasso/Select),
     * opening a modal dialog, or in Activity.onPause().
     */
    public void suspendOverlay() {
        if (!initialized) return;
        try {
            if (hasOverlayContent) {
                if (overlayCanvas != null) {
                    overlayCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                }
                if (!accumulatedDirty.isEmpty()) {
                    CvtRenderNativeInterface.clean(accumulatedDirty);
                } else {
                    CvtRenderNativeInterface.clean(null);
                }
                accumulatedDirty.setEmpty();
                hasOverlayContent = false;
            }
            if (preparing) {
                CvtRenderNativeInterface.finishRender();
                preparing = false;
            }
            if (renderEnabled) {
                CvtRenderNativeInterface.enableRender(false);
                renderEnabled = false;
            }
        } catch (Throwable t) {
            Log.w(TAG, "suspendOverlay error: " + t.getMessage());
        }
    }

    public void setRenderEnabled(boolean enable) {
        if (!initialized) return;
        if (!enable) {
            suspendOverlay();
        } else {
            try {
                if (!renderEnabled) {
                    renderEnabled = CvtRenderNativeInterface.enableRender(true);
                }
            } catch (Throwable t) {
                Log.w(TAG, "setRenderEnabled(true) error: " + t.getMessage());
            }
        }
    }

    public void destroy() {
        if (initialized) {
            try {
                suspendOverlay();
                CvtRenderNativeInterface.destroy(instanceId);
            } catch (Throwable t) {
                Log.w(TAG, "destroy error: " + t.getMessage());
            }
            initialized = false;
            renderEnabled = false;
            preparing = false;
        }
        if (overlayBitmap != null && !overlayBitmap.isRecycled()) {
            overlayBitmap.recycle();
        }
        overlayBitmap = null;
        overlayCanvas = null;
    }
}

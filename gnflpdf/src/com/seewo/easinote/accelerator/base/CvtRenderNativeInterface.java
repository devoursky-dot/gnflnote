package com.seewo.easinote.accelerator.base;

import android.graphics.Bitmap;
import android.graphics.Rect;
import java.util.List;

/**
 * Exact JNI bridge matching libaccelerator_base.so exported symbols.
 * Package and class name MUST remain com.seewo.easinote.accelerator.base.CvtRenderNativeInterface.
 */
public class CvtRenderNativeInterface {
    public static void a(Rect dirtyRect, boolean sync) {
        render(0, dirtyRect, sync);
    }

    public static native void clean(Rect rect);

    public static native void destroy(int instanceHash);

    public static native void enableHalfScreenMode(boolean enable);

    public static native boolean enableRender(boolean enable);

    public static native void finishRender();

    public static native boolean init(
            String engineName,
            boolean isScreenFreeze,
            boolean isDebug,
            int fbWidth,
            int fbHeight,
            int players,
            String regTool,
            int instanceHash);

    public static native void initEraserMode(int width, int height);

    public static native void lock();

    public static native void prepareToRender();

    public static native void reIndexLayer(int oldIndex, int newIndex);

    public static native boolean recycleLayer(int layerIndex);

    public static native boolean removePagerEyeCareLayer();

    public static native void render(int layerIndex, Rect dirtyRect, boolean sync);

    public static native void renderEraser(Rect lastRect, Rect curRect);

    public static native void setConnectType(int type);

    public static native void setDRMConntectorID(int id);

    public static native void setDRMCrtcID(int id);

    public static native void setEnableTransparentMode(boolean enable);

    public static native void setEraserMode(boolean enable, int width, int height);

    public static native void setFBAddr(long addr);

    public static native void setForbidRenderLocation(List<Rect> rects);

    public static native boolean setLayer(int layerIndex, int left, int top, Bitmap bitmap);

    public static native boolean setLayerRenderBounds(int layerIndex, Rect bounds);

    public static native boolean setPagerEyeCareLayer(Bitmap bitmap, boolean enable, int alpha);

    public static native void setRenderBounds(Rect bounds);

    public static native void unlock();
}

package com.seewo.easinote.accelerator.base;

import android.graphics.Bitmap;
import android.graphics.Rect;
import java.util.List;

public class CvtRenderNativeInterface {
    private static boolean loaded = false;

    static {
        try {
            System.loadLibrary("accelerator_base");
            loaded = true;
            android.util.Log.i("CvtRender", "Successfully loaded accelerator_base!");
        } catch (Throwable t) {
            android.util.Log.e("CvtRender", "Failed to load accelerator_base", t);
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }

    public static native void clean(Rect rect);
    public static native void destroy(int i);
    public static native void enableHalfScreenMode(boolean z);
    public static native boolean enableRender(boolean z);
    public static native void finishRender();
    public static native boolean init(String str, boolean z, boolean z2, int i, int i2, int i3, String str2, int i4);
    public static native void initEraserMode(int i, int i2);
    public static native void lock();
    public static native void prepareToRender();
    public static native void reIndexLayer(int i, int i2);
    public static native boolean recycleLayer(int i);
    public static native boolean removePagerEyeCareLayer();
    public static native void render(int i, Rect rect, boolean z);
    public static native void renderEraser(Rect rect, Rect rect2);
    public static native void setConnectType(int i);
    public static native void setDRMConntectorID(int i);
    public static native void setDRMCrtcID(int i);
    public static native void setEnableTransparentMode(boolean z);
    public static native void setEraserMode(boolean z, int i, int i2);
    public static native void setFBAddr(long j);
    public static native void setForbidRenderLocation(List list);
    public static native boolean setLayer(int i, int i2, int i3, Bitmap bitmap);
    public static native boolean setLayerRenderBounds(int i, Rect rect);
    public static native boolean setPagerEyeCareLayer(Bitmap bitmap, boolean z, int i);
    public static native void setRenderBounds(Rect rect);
    public static native void unlock();

    public static void renderDirect(Rect rect) {
        if (!loaded) return;
        try {
            prepareToRender();
            render(0, rect, true);
            finishRender();
        } catch (Throwable t) {
            // ignore
        }
    }
}

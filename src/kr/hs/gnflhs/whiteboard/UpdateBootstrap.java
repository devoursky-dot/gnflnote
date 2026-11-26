package kr.hs.gnflhs.whiteboard;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.view.MotionEvent;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

import dalvik.system.DexClassLoader;

/**
 * GNFL NOTE V2.0 - 초경량 GitHub OTA 핫-업데이트 부트스트래퍼
 *
 * - Base APK(280MB)의 PlainWhiteboardActivity 후크 2곳(dispatchTouchEvent, onActivityResult)의
 *   유일한 진입점 역할을 수행합니다.
 * - 실행 시 filesDir/gnfl_ota/ 에 검증된 최신 패치 DEX(~100KB)가 있으면 Child-First DexClassLoader로
 *   즉시 로드하고, 없으면 APK 내장 기본 클래스로 100% 안전하게 폴백합니다.
 * - 백그라운드에서 GitHub(devoursky-dot/gnflnote)의 ota/version.json을 확인해 새 패치가 있으면
 *   SHA-256 검증 후 다운로드하고, 상단 배너로 안내한 뒤 다음 실행 시 자동 적용합니다.
 */
public class UpdateBootstrap {
    private static final String TAG = "UpdateBootstrap";

    public static final int BASE_VERSION_CODE = 20900200;
    public static final int BUILTIN_PATCH_VERSION = 200;
    public static final String BUILTIN_PATCH_NAME = "2.0.0";

    private static final String OTA_DIR_NAME = "gnfl_ota";
    private static final String ACTIVE_META_NAME = "active_patch.json";
    private static final String VERSION_JSON_URL =
            "https://raw.githubusercontent.com/devoursky-dot/gnflnote/main/ota/version.json";

    private static volatile boolean sInitialized = false;
    private static volatile boolean sUpdateCheckStarted = false;
    private static volatile int sActivePatchVersion = BUILTIN_PATCH_VERSION;
    private static volatile String sActivePatchName = BUILTIN_PATCH_NAME;
    private static volatile boolean sUsingOtaDex = false;

    private static ClassLoader sOtaClassLoader = null;
    private static Method sOtaDispatchTouch = null;
    private static Method sOtaOnActivityResult = null;
    private static Method sOtaShowTopToast = null;

    /**
     * Child-First DexClassLoader:
     * kr.hs.gnflhs.whiteboard.* 기능 클래스(UpdateBootstrap 제외)는
     * Base APK보다 다운로드된 OTA 패치 DEX에서 먼저 로드합니다.
     */
    private static final class ChildFirstDexClassLoader extends DexClassLoader {
        public ChildFirstDexClassLoader(String dexPath, String optimizedDirectory, String librarySearchPath, ClassLoader parent) {
            super(dexPath, optimizedDirectory, librarySearchPath, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name != null
                    && name.startsWith("kr.hs.gnflhs.whiteboard.")
                    && !name.equals("kr.hs.gnflhs.whiteboard.UpdateBootstrap")
                    && !name.startsWith("kr.hs.gnflhs.whiteboard.UpdateBootstrap$")) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    try {
                        c = findClass(name);
                    } catch (ClassNotFoundException ignored) {}
                }
                if (c != null) {
                    if (resolve) resolveClass(c);
                    return c;
                }
            }
            return super.loadClass(name, resolve);
        }
    }

    public static int getActivePatchVersion() {
        return sActivePatchVersion;
    }

    public static String getActivePatchName() {
        return sActivePatchName;
    }

    public static boolean isUsingOtaDex() {
        return sUsingOtaDex;
    }

    /**
     * PlainWhiteboardActivity.onActivityResult 후크 진입점
     */
    public static void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
        ensureInitialized(activity);
        if (sOtaOnActivityResult != null) {
            try {
                sOtaOnActivityResult.invoke(null, activity, requestCode, resultCode, data);
                return;
            } catch (Throwable t) {
                Log.e(TAG, "OTA onActivityResult failed, falling back to built-in", t);
            }
        }
        PdfPicker.onActivityResult(activity, requestCode, resultCode, data);
    }

    /**
     * PlainWhiteboardActivity.dispatchTouchEvent 후크 진입점
     */
    public static boolean dispatchTouchEvent(Activity activity, MotionEvent ev) {
        ensureInitialized(activity);
        if (sOtaDispatchTouch != null) {
            try {
                Object res = sOtaDispatchTouch.invoke(null, activity, ev);
                return (res instanceof Boolean) && (Boolean) res;
            } catch (Throwable t) {
                Log.e(TAG, "OTA dispatchTouchEvent failed, falling back to built-in", t);
            }
        }
        return SmartPenManager.dispatchTouchEvent(activity, ev);
    }

    private static synchronized void ensureInitialized(final Activity activity) {
        if (sInitialized) return;
        sInitialized = true;
        if (activity == null) return;

        final Context appCtx = activity.getApplicationContext();
        try {
            loadLocalOtaPatchIfValid(activity, appCtx);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to load local OTA patch, using built-in", t);
            sOtaClassLoader = null;
            sOtaDispatchTouch = null;
            sOtaOnActivityResult = null;
            sUsingOtaDex = false;
        }

        if (!sUpdateCheckStarted) {
            sUpdateCheckStarted = true;
            startBackgroundUpdateCheck(activity, appCtx);
        }
    }

    private static void loadLocalOtaPatchIfValid(final Activity activity, Context appCtx) {
        File otaDir = new File(appCtx.getFilesDir(), OTA_DIR_NAME);
        File metaFile = new File(otaDir, ACTIVE_META_NAME);
        if (!metaFile.exists()) {
            Log.i(TAG, "No local OTA patch; running built-in v" + BUILTIN_PATCH_NAME + " (" + BUILTIN_PATCH_VERSION + ")");
            return;
        }
        try {
            JSONObject meta = new JSONObject(readTextFile(metaFile));
            int patchVer = meta.optInt("patchVersion", 0);
            String patchName = meta.optString("patchName", String.valueOf(patchVer));
            int minBase = meta.optInt("minBaseVersionCode", 0);
            String dexName = meta.optString("dexFile", "");
            String expectedSha = meta.optString("sha256", "");
            boolean justUpdated = meta.optBoolean("justUpdated", false);

            if (patchVer <= BUILTIN_PATCH_VERSION || minBase > BASE_VERSION_CODE || dexName.isEmpty()) {
                Log.i(TAG, "Local OTA patch v" + patchName + " superseded by built-in v" + BUILTIN_PATCH_NAME);
                return;
            }

            File dexFile = new File(otaDir, dexName);
            if (!dexFile.exists() || dexFile.length() < 1024) {
                Log.w(TAG, "OTA dex file missing: " + dexFile.getAbsolutePath());
                return;
            }

            if (!expectedSha.isEmpty()) {
                String actualSha = computeSha256(dexFile);
                if (!expectedSha.equalsIgnoreCase(actualSha)) {
                    Log.e(TAG, "OTA dex SHA-256 mismatch! expected=" + expectedSha + " actual=" + actualSha);
                    dexFile.delete();
                    metaFile.delete();
                    return;
                }
            }

            // Android 14(API 34)+ 동적 코드 로딩 보안 정책 준수: 반드시 read-only 설정
            dexFile.setReadOnly();

            File optDir = new File(appCtx.getCodeCacheDir(), "gnfl_ota_opt");
            if (!optDir.exists()) optDir.mkdirs();

            ClassLoader cl = new ChildFirstDexClassLoader(
                    dexFile.getAbsolutePath(),
                    optDir.getAbsolutePath(),
                    null,
                    UpdateBootstrap.class.getClassLoader());

            Class<?> smartPenCls = cl.loadClass("kr.hs.gnflhs.whiteboard.SmartPenManager");
            Class<?> pdfPickerCls = cl.loadClass("kr.hs.gnflhs.whiteboard.PdfPicker");
            Method mTouch = smartPenCls.getMethod("dispatchTouchEvent", Activity.class, MotionEvent.class);
            Method mActRes = pdfPickerCls.getMethod("onActivityResult", Activity.class, int.class, int.class, Intent.class);

            Method mToast = null;
            try {
                Class<?> helperCls = cl.loadClass("kr.hs.gnflhs.whiteboard.ToolbarFitHelper");
                mToast = helperCls.getMethod("showTopToast", Activity.class, String.class, boolean.class);
            } catch (Throwable ignored) {}

            sOtaClassLoader = cl;
            sOtaDispatchTouch = mTouch;
            sOtaOnActivityResult = mActRes;
            sOtaShowTopToast = mToast;
            sActivePatchVersion = patchVer;
            sActivePatchName = patchName;
            sUsingOtaDex = true;

            Log.i(TAG, "Successfully loaded OTA patch v" + patchName + " (patchVersion=" + patchVer + ", " + dexFile.length() + " bytes)");

            if (justUpdated) {
                meta.put("justUpdated", false);
                writeTextFile(metaFile, meta.toString());
                final String appliedMsg = "✨ GNFLNOTE v" + patchName + " 자동 업데이트가 적용되었습니다!";
                showBannerDelayed(activity, appliedMsg, true, 1200L);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error loading OTA patch", t);
        }
    }

    private static void startBackgroundUpdateCheck(final Activity activity, final Context appCtx) {
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(2500L);
                    String urlStr = VERSION_JSON_URL + "?t=" + System.currentTimeMillis();
                    byte[] metaBytes = httpGetBytes(urlStr, 7000);
                    if (metaBytes == null || metaBytes.length == 0) {
                        Log.i(TAG, "OTA check skipped (offline or empty response)");
                        return;
                    }
                    JSONObject remote = new JSONObject(new String(metaBytes, StandardCharsets.UTF_8));
                    int remoteVer = remote.optInt("patchVersion", 0);
                    String remoteName = remote.optString("patchName", String.valueOf(remoteVer));
                    int minBase = remote.optInt("minBaseVersionCode", 0);
                    String dexUrl = remote.optString("dexUrl", "");
                    String expectedSha = remote.optString("sha256", "");

                    Log.i(TAG, "OTA check: remote=v" + remoteName + "(" + remoteVer + "), active=v"
                            + sActivePatchName + "(" + sActivePatchVersion + "), base=" + BASE_VERSION_CODE);

                    if (remoteVer <= sActivePatchVersion) {
                        return;
                    }
                    if (minBase > BASE_VERSION_CODE) {
                        Log.i(TAG, "Remote patch requires newer base APK (minBase=" + minBase + ")");
                        return;
                    }
                    if (dexUrl.isEmpty() || expectedSha.isEmpty()) {
                        Log.w(TAG, "Remote version.json missing dexUrl or sha256");
                        return;
                    }

                    byte[] dexBytes = httpGetBytes(dexUrl + "?t=" + System.currentTimeMillis(), 15000);
                    if (dexBytes == null || dexBytes.length < 1024) {
                        Log.w(TAG, "OTA dex download failed or too small");
                        return;
                    }

                    File otaDir = new File(appCtx.getFilesDir(), OTA_DIR_NAME);
                    if (!otaDir.exists()) otaDir.mkdirs();

                    File tmpFile = new File(otaDir, "tmp_patch_" + remoteVer + ".dex");
                    FileOutputStream fos = new FileOutputStream(tmpFile);
                    try {
                        fos.write(dexBytes);
                        fos.flush();
                    } finally {
                        try { fos.close(); } catch (Throwable ignored) {}
                    }

                    String actualSha = computeSha256(tmpFile);
                    if (!expectedSha.equalsIgnoreCase(actualSha)) {
                        Log.e(TAG, "Downloaded OTA dex SHA-256 mismatch! expected=" + expectedSha + " actual=" + actualSha);
                        tmpFile.delete();
                        return;
                    }

                    String finalDexName = "gnfl_patch_" + remoteVer + ".dex";
                    File finalDex = new File(otaDir, finalDexName);
                    if (finalDex.exists()) {
                        finalDex.setWritable(true);
                        finalDex.delete();
                    }
                    if (!tmpFile.renameTo(finalDex)) {
                        tmpFile.delete();
                        return;
                    }
                    finalDex.setReadOnly();

                    JSONObject activeMeta = new JSONObject();
                    activeMeta.put("patchVersion", remoteVer);
                    activeMeta.put("patchName", remoteName);
                    activeMeta.put("minBaseVersionCode", minBase);
                    activeMeta.put("dexFile", finalDexName);
                    activeMeta.put("sha256", actualSha);
                    activeMeta.put("justUpdated", true);
                    writeTextFile(new File(otaDir, ACTIVE_META_NAME), activeMeta.toString());

                    // 이전 버전 DEX 파일 정리
                    File[] files = otaDir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            String n = f.getName();
                            if (n.startsWith("gnfl_patch_") && n.endsWith(".dex") && !n.equals(finalDexName)) {
                                f.setWritable(true);
                                f.delete();
                            }
                        }
                    }

                    Log.i(TAG, "Downloaded & verified OTA patch v" + remoteName + " (" + dexBytes.length + " bytes)");
                    showBannerDelayed(activity,
                            "⬇️ 새 업데이트(v" + remoteName + ") 다운로드 완료! 앱을 다시 열면 자동 적용됩니다.",
                            true, 200L);
                } catch (Throwable t) {
                    Log.w(TAG, "Background OTA update check failed: " + t.getMessage());
                }
            }
        }, "GnflOtaUpdater");
        th.setDaemon(true);
        th.start();
    }

    private static void showBannerDelayed(final Activity activity, final String msg, final boolean isLong, long delayMs) {
        if (activity == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (sOtaShowTopToast != null) {
                        sOtaShowTopToast.invoke(null, activity, msg, isLong);
                    } else {
                        ToolbarFitHelper.showTopToast(activity, msg, isLong);
                    }
                } catch (Throwable ignored) {}
            }
        }, delayMs);
    }

    private static byte[] httpGetBytes(String urlStr, int timeoutMs) {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.setRequestProperty("User-Agent", "GNFLNOTE/" + BUILTIN_PATCH_NAME + " (Android " + Build.VERSION.SDK_INT + ")");
            int code = conn.getResponseCode();
            if (code != 200) return null;
            in = new BufferedInputStream(conn.getInputStream());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private static String computeSha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        FileInputStream fis = new FileInputStream(file);
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = fis.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        } finally {
            try { fis.close(); } catch (Throwable ignored) {}
        }
        byte[] dig = md.digest();
        StringBuilder sb = new StringBuilder(dig.length * 2);
        for (byte b : dig) {
            sb.append(String.format(Locale.US, "%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private static String readTextFile(File f) throws Exception {
        FileInputStream fis = new FileInputStream(f);
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            try { fis.close(); } catch (Throwable ignored) {}
        }
    }

    private static void writeTextFile(File f, String text) throws Exception {
        FileOutputStream fos = new FileOutputStream(f);
        try {
            fos.write(text.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } finally {
            try { fos.close(); } catch (Throwable ignored) {}
        }
    }
}

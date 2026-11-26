package kr.hs.gnflhs.whiteboard;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

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
 * GNFL NOTE V2.0 - 초경량 GitHub OTA 핫-업데이트 부트스트래퍼 + 수동 업데이트/자동 재실행 UI
 */
public class UpdateBootstrap {
    private static final String TAG = "UpdateBootstrap";

    public static final int BASE_VERSION_CODE = 20900200;
    public static final int BUILTIN_PATCH_VERSION = 200;
    public static final String BUILTIN_PATCH_NAME = "2.0.0";
    public static final String BUILTIN_BUILD_DATE = "2026-11-26 16:30";

    private static final String OTA_DIR_NAME = "gnfl_ota";
    private static final String ACTIVE_META_NAME = "active_patch.json";
    private static final String VERSION_JSON_URL =
            "https://raw.githubusercontent.com/devoursky-dot/gnflnote/main/ota/version.json";

    private static volatile boolean sInitialized = false;
    private static volatile long sLastCheckMs = 0L;
    private static volatile int sActivePatchVersion = BUILTIN_PATCH_VERSION;
    private static volatile String sActivePatchName = BUILTIN_PATCH_NAME;
    private static volatile String sActiveBuildDate = BUILTIN_BUILD_DATE;
    private static volatile boolean sUsingOtaDex = false;

    private static ClassLoader sOtaClassLoader = null;
    private static Method sOtaDispatchTouch = null;
    private static Method sOtaOnActivityResult = null;
    private static Method sOtaShowTopToast = null;

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

    public static String getActiveBuildDate() {
        return sActiveBuildDate;
    }

    public static boolean isUsingOtaDex() {
        return sUsingOtaDex;
    }

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
        if (activity == null) return;
        final Context appCtx = activity.getApplicationContext();

        if (!sInitialized) {
            sInitialized = true;
            try {
                loadLocalOtaPatchIfValid(activity, appCtx);
            } catch (Throwable t) {
                Log.e(TAG, "Failed to load local OTA patch, using built-in", t);
                sOtaClassLoader = null;
                sOtaDispatchTouch = null;
                sOtaOnActivityResult = null;
                sUsingOtaDex = false;
            }
        }

        // 프로세스가 살아 있는 상태로 재진입해도 60초가 지났으면 백그라운드 체크 재수행
        long now = System.currentTimeMillis();
        if (now - sLastCheckMs > 60_000L) {
            sLastCheckMs = now;
            startBackgroundUpdateCheck(activity, appCtx);
        }
    }

    private static void loadLocalOtaPatchIfValid(final Activity activity, Context appCtx) {
        File otaDir = new File(appCtx.getFilesDir(), OTA_DIR_NAME);
        File metaFile = new File(otaDir, ACTIVE_META_NAME);
        if (!metaFile.exists()) {
            Log.i(TAG, "No local OTA patch; running built-in v" + BUILTIN_PATCH_NAME + " (" + BUILTIN_BUILD_DATE + ")");
            return;
        }
        try {
            JSONObject meta = new JSONObject(readTextFile(metaFile));
            int patchVer = meta.optInt("patchVersion", 0);
            String patchName = meta.optString("patchName", String.valueOf(patchVer));
            String buildDate = meta.optString("buildDate", BUILTIN_BUILD_DATE);
            int minBase = meta.optInt("minBaseVersionCode", 0);
            String dexName = meta.optString("dexFile", "");
            String expectedSha = meta.optString("sha256", "");
            boolean justUpdated = meta.optBoolean("justUpdated", false);

            if (patchVer <= BUILTIN_PATCH_VERSION || minBase > BASE_VERSION_CODE || dexName.isEmpty()) {
                return;
            }

            File dexFile = new File(otaDir, dexName);
            if (!dexFile.exists() || dexFile.length() < 1024) {
                return;
            }

            if (!expectedSha.isEmpty()) {
                String actualSha = computeSha256(dexFile);
                if (!expectedSha.equalsIgnoreCase(actualSha)) {
                    Log.e(TAG, "OTA dex SHA-256 mismatch!");
                    dexFile.delete();
                    metaFile.delete();
                    return;
                }
            }

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
            sActiveBuildDate = buildDate;
            sUsingOtaDex = true;

            Log.i(TAG, "Successfully loaded OTA patch v" + patchName + " (" + buildDate + ", " + dexFile.length() + " bytes)");

            if (justUpdated) {
                meta.put("justUpdated", false);
                writeTextFile(metaFile, meta.toString());
                final String appliedMsg = "✨ GNFLNOTE v" + patchName + " (" + buildDate + ") 업데이트 완료!";
                showBannerDelayed(activity, appliedMsg, true, 900L);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error loading OTA patch", t);
        }
    }

    /**
     * 메뉴 팝업의 '업데이트 확인' 클릭 시 표시되는 수동 업데이트 다이얼로그:
     * - 현재 설치 버전 & 배포일 표시
     * - GitHub 최신 버전 & 배포일 & 변경 내역 실시간 조회
     * - [지금 업데이트 및 재시작] 클릭 시 193KB 다운로드 -> SHA-256 검증 -> 프로세스 자동 재실행!
     */
    public static void showManualUpdateDialog(final Activity activity) {
        if (activity == null) return;
        final Handler ui = new Handler(Looper.getMainLooper());
        ui.post(new Runnable() {
            @Override
            public void run() {
                try {
                    final Dialog dlg = new Dialog(activity);
                    dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
                    dlg.setCanceledOnTouchOutside(true);

                    LinearLayout card = new LinearLayout(activity);
                    card.setOrientation(LinearLayout.VERTICAL);
                    card.setPadding(dp(activity, 22), dp(activity, 20), dp(activity, 22), dp(activity, 18));
                    GradientDrawable bg = new GradientDrawable();
                    bg.setColor(Color.WHITE);
                    bg.setCornerRadius(dp(activity, 14));
                    bg.setStroke(dp(activity, 1), Color.parseColor("#E2E8F0"));
                    card.setBackground(bg);

                    TextView title = new TextView(activity);
                    title.setText("🔄 GNFLNOTE 업데이트");
                    title.setTextColor(Color.parseColor("#0F172A"));
                    title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
                    title.setTypeface(Typeface.DEFAULT_BOLD);
                    card.addView(title);

                    TextView curInfo = new TextView(activity);
                    curInfo.setText("현재 버전: v" + sActivePatchName + "  (" + sActiveBuildDate + ")");
                    curInfo.setTextColor(Color.parseColor("#334155"));
                    curInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
                    curInfo.setTypeface(Typeface.DEFAULT_BOLD);
                    LinearLayout.LayoutParams curLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    curLp.topMargin = dp(activity, 12);
                    card.addView(curInfo, curLp);

                    final TextView statusTxt = new TextView(activity);
                    statusTxt.setText("GitHub에서 최신 배포본을 확인하는 중...");
                    statusTxt.setTextColor(Color.parseColor("#64748B"));
                    statusTxt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
                    statusTxt.setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10));
                    GradientDrawable stBg = new GradientDrawable();
                    stBg.setColor(Color.parseColor("#F8FAFC"));
                    stBg.setCornerRadius(dp(activity, 8));
                    stBg.setStroke(dp(activity, 1), Color.parseColor("#E2E8F0"));
                    statusTxt.setBackground(stBg);
                    LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    stLp.topMargin = dp(activity, 10);
                    stLp.bottomMargin = dp(activity, 16);
                    card.addView(statusTxt, stLp);

                    LinearLayout btnRow = new LinearLayout(activity);
                    btnRow.setOrientation(LinearLayout.HORIZONTAL);
                    btnRow.setGravity(Gravity.END);

                    final TextView btnClose = new TextView(activity);
                    btnClose.setText("닫기");
                    btnClose.setTextColor(Color.parseColor("#475569"));
                    btnClose.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
                    btnClose.setGravity(Gravity.CENTER);
                    btnClose.setPadding(dp(activity, 18), dp(activity, 10), dp(activity, 18), dp(activity, 10));
                    GradientDrawable closeBg = new GradientDrawable();
                    closeBg.setColor(Color.parseColor("#F1F5F9"));
                    closeBg.setCornerRadius(dp(activity, 8));
                    btnClose.setBackground(closeBg);
                    btnClose.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            dlg.dismiss();
                        }
                    });
                    btnRow.addView(btnClose);

                    final TextView btnUpdate = new TextView(activity);
                    btnUpdate.setText("지금 업데이트 및 재시작");
                    btnUpdate.setTextColor(Color.WHITE);
                    btnUpdate.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
                    btnUpdate.setTypeface(Typeface.DEFAULT_BOLD);
                    btnUpdate.setGravity(Gravity.CENTER);
                    btnUpdate.setPadding(dp(activity, 18), dp(activity, 10), dp(activity, 18), dp(activity, 10));
                    GradientDrawable updBg = new GradientDrawable();
                    updBg.setColor(Color.parseColor("#00B589"));
                    updBg.setCornerRadius(dp(activity, 8));
                    btnUpdate.setBackground(updBg);
                    btnUpdate.setVisibility(View.GONE);
                    LinearLayout.LayoutParams updLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    updLp.leftMargin = dp(activity, 10);
                    btnRow.addView(btnUpdate, updLp);

                    card.addView(btnRow, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                    dlg.setContentView(card, new ViewGroup.LayoutParams(dp(activity, 370), ViewGroup.LayoutParams.WRAP_CONTENT));
                    if (dlg.getWindow() != null) {
                        dlg.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                    }
                    dlg.show();

                    // 백그라운드에서 GitHub version.json 실시간 확인
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                byte[] metaBytes = httpGetBytes(VERSION_JSON_URL + "?t=" + System.currentTimeMillis(), 8000);
                                if (metaBytes == null || metaBytes.length == 0) {
                                    ui.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            statusTxt.setText("⚠️ GitHub 서버에 연결할 수 없습니다.\n인터넷 연결 상태를 확인해 주세요.");
                                        }
                                    });
                                    return;
                                }
                                final JSONObject remote = new JSONObject(new String(metaBytes, StandardCharsets.UTF_8));
                                final int remoteVer = remote.optInt("patchVersion", 0);
                                final String remoteName = remote.optString("patchName", String.valueOf(remoteVer));
                                final String remoteDate = remote.optString("buildDate", "-");
                                final int minBase = remote.optInt("minBaseVersionCode", 0);
                                final String notes = remote.optString("releaseNotes", "");
                                final int sizeKb = Math.max(1, remote.optInt("sizeBytes", 0) / 1024);

                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (remoteVer <= sActivePatchVersion) {
                                            statusTxt.setText("✅ 이미 최신 버전입니다.\n최신 배포본: v" + remoteName + " (" + remoteDate + ")");
                                            statusTxt.setTextColor(Color.parseColor("#065F46"));
                                        } else if (minBase > BASE_VERSION_CODE) {
                                            statusTxt.setText("📦 새 앱 설치 파일(APK)이 필요합니다.\n최신: v" + remoteName + " (" + remoteDate + ")");
                                        } else {
                                            String info = "🆕 새 업데이트: v" + remoteName + " (" + remoteDate + ") · " + sizeKb + " KB";
                                            if (!notes.isEmpty()) info += "\n• " + notes;
                                            statusTxt.setText(info);
                                            statusTxt.setTextColor(Color.parseColor("#0F172A"));
                                            btnUpdate.setVisibility(View.VISIBLE);
                                            btnUpdate.setOnClickListener(new View.OnClickListener() {
                                                @Override
                                                public void onClick(View v) {
                                                    btnUpdate.setEnabled(false);
                                                    btnUpdate.setText("다운로드 중...");
                                                    statusTxt.setText("⬇️ v" + remoteName + " (" + sizeKb + " KB) 다운로드 및 검증 중...");
                                                    new Thread(new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            final boolean ok = downloadAndInstallPatch(activity.getApplicationContext(), remote);
                                                            ui.post(new Runnable() {
                                                                @Override
                                                                public void run() {
                                                                    if (ok) {
                                                                        statusTxt.setText("✅ 다운로드 완료! 앱을 자동으로 재시작합니다...");
                                                                        dlg.dismiss();
                                                                        restartAppCleanly(activity);
                                                                    } else {
                                                                        btnUpdate.setEnabled(true);
                                                                        btnUpdate.setText("다시 시도");
                                                                        statusTxt.setText("⚠️ 다운로드 또는 무결성 검증에 실패했습니다.");
                                                                    }
                                                                }
                                                            });
                                                        }
                                                    }, "GnflManualOtaDownload").start();
                                                }
                                            });
                                        }
                                    }
                                });
                            } catch (Throwable t) {
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        statusTxt.setText("⚠️ 업데이트 확인 오류: " + t.getMessage());
                                    }
                                });
                            }
                        }
                    }, "GnflManualOtaCheck").start();
                } catch (Throwable t) {
                    Log.e(TAG, "showManualUpdateDialog failed", t);
                }
            }
        });
    }

    private static boolean downloadAndInstallPatch(Context appCtx, JSONObject remote) {
        try {
            int remoteVer = remote.optInt("patchVersion", 0);
            String remoteName = remote.optString("patchName", String.valueOf(remoteVer));
            String remoteDate = remote.optString("buildDate", BUILTIN_BUILD_DATE);
            int minBase = remote.optInt("minBaseVersionCode", 0);
            String dexUrl = remote.optString("dexUrl", "");
            String expectedSha = remote.optString("sha256", "");
            if (dexUrl.isEmpty() || expectedSha.isEmpty()) return false;

            byte[] dexBytes = httpGetBytes(dexUrl + "?t=" + System.currentTimeMillis(), 15000);
            if (dexBytes == null || dexBytes.length < 1024) return false;

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
                return false;
            }

            String finalDexName = "gnfl_patch_" + remoteVer + ".dex";
            File finalDex = new File(otaDir, finalDexName);
            if (finalDex.exists()) {
                finalDex.setWritable(true);
                finalDex.delete();
            }
            if (!tmpFile.renameTo(finalDex)) {
                tmpFile.delete();
                return false;
            }
            finalDex.setReadOnly();

            JSONObject activeMeta = new JSONObject();
            activeMeta.put("patchVersion", remoteVer);
            activeMeta.put("patchName", remoteName);
            activeMeta.put("buildDate", remoteDate);
            activeMeta.put("minBaseVersionCode", minBase);
            activeMeta.put("dexFile", finalDexName);
            activeMeta.put("sha256", actualSha);
            activeMeta.put("justUpdated", true);
            writeTextFile(new File(otaDir, ACTIVE_META_NAME), activeMeta.toString());

            // 이전 버전 DEX 정리
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
            Log.i(TAG, "Downloaded & verified OTA patch v" + remoteName + " (" + remoteDate + ", " + dexBytes.length + " bytes)");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "downloadAndInstallPatch failed", t);
            return false;
        }
    }

    /**
     * 새 OTA DEX를 깨끗하게 로드하기 위해 앱을 0.45초 뒤 자동 재실행하도록 예약하고 현재 프로세스를 종료합니다.
     */
    private static void restartAppCleanly(final Activity activity) {
        try {
            Context appCtx = activity.getApplicationContext();
            Intent launch = new Intent(appCtx, activity.getClass());
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            int flags = PendingIntent.FLAG_CANCEL_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(appCtx, 0x7A01, launch, flags);
            AlarmManager am = (AlarmManager) appCtx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 450L, pi);
            }
            try { activity.finishAffinity(); } catch (Throwable ignored) { activity.finish(); }
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(0);
                }
            }, 120L);
        } catch (Throwable t) {
            Log.e(TAG, "restartAppCleanly failed", t);
        }
    }

    private static void startBackgroundUpdateCheck(final Activity activity, final Context appCtx) {
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(2500L);
                    byte[] metaBytes = httpGetBytes(VERSION_JSON_URL + "?t=" + System.currentTimeMillis(), 7000);
                    if (metaBytes == null || metaBytes.length == 0) return;
                    JSONObject remote = new JSONObject(new String(metaBytes, StandardCharsets.UTF_8));
                    int remoteVer = remote.optInt("patchVersion", 0);
                    String remoteName = remote.optString("patchName", String.valueOf(remoteVer));
                    String remoteDate = remote.optString("buildDate", "");
                    int minBase = remote.optInt("minBaseVersionCode", 0);

                    Log.i(TAG, "OTA check: remote=v" + remoteName + "(" + remoteVer + "), active=v"
                            + sActivePatchName + "(" + sActivePatchVersion + ")");

                    if (remoteVer <= sActivePatchVersion || minBase > BASE_VERSION_CODE) return;

                    if (downloadAndInstallPatch(appCtx, remote)) {
                        String datePart = remoteDate.isEmpty() ? "" : (" (" + remoteDate + ")");
                        showBannerDelayed(activity,
                                "⬇️ 새 업데이트 v" + remoteName + datePart + " 준비 완료! 메뉴 → 업데이트에서 재시작하세요.",
                                true, 200L);
                    }
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
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
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

    private static int dp(Context ctx, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics());
    }
}

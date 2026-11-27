package kr.hs.gnflhs.gnflpdf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * GNFLPDF GitHub 자동 업데이트 관리자
 * - https://raw.githubusercontent.com/devoursky-dot/gnflnote/main/gnflpdf/ota/version.json 확인
 * - 새 버전 발견 시 APK 다운로드 + SHA-256 무결성 검증 + 안드로이드 원터치 설치
 */
public final class GithubUpdateManager {
    private static final String TAG = "GNFLPDF_OTA";
    public static final String VERSION_JSON_URL =
            "https://raw.githubusercontent.com/devoursky-dot/gnflnote/main/gnflpdf/ota/version.json";

    public interface OverlayHooks {
        void onSuspendOverlay();
        void onRestoreOverlay();
    }

    private GithubUpdateManager() {}

    public static int getCurrentVersionCode(Activity act) {
        try {
            PackageInfo pi = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            return pi.versionCode;
        } catch (Throwable t) {
            return 100;
        }
    }

    public static String getCurrentVersionName(Activity act) {
        try {
            PackageInfo pi = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            return (pi.versionName != null && !pi.versionName.isEmpty()) ? pi.versionName : "1.0.0";
        } catch (Throwable t) {
            return "1.0.0";
        }
    }

    public static void checkForUpdateAsync(final Activity act, final boolean manualCheck,
                                           final OverlayHooks hooks) {
        if (act == null || act.isFinishing()) return;
        final Handler ui = new Handler(Looper.getMainLooper());
        final int curCode = getCurrentVersionCode(act);
        final String curName = getCurrentVersionName(act);

        if (manualCheck) {
            Toast.makeText(act, "GitHub에서 최신 버전을 확인하는 중...", Toast.LENGTH_SHORT).show();
        }

        new Thread(() -> {
            try {
                byte[] raw = httpGetBytes(VERSION_JSON_URL + "?t=" + System.currentTimeMillis(), 8000);
                if (raw == null || raw.length == 0) {
                    throw new RuntimeException("서버 응답이 비어 있습니다.");
                }
                String jsonStr = new String(raw, StandardCharsets.UTF_8).trim();
                if (jsonStr.startsWith("\uFEFF")) jsonStr = jsonStr.substring(1);
                JSONObject obj = new JSONObject(jsonStr);

                final int remoteCode = obj.optInt("versionCode", 0);
                final String remoteName = obj.optString("versionName", "?");
                final String buildDate = obj.optString("buildDate", "");
                final String apkUrl = obj.optString("apkUrl", "");
                final String sha256 = obj.optString("sha256", "");
                final long sizeBytes = obj.optLong("sizeBytes", 0L);
                final String notes = obj.optString("releaseNotes", "");

                Log.i(TAG, "OTA check: local=" + curCode + " (" + curName + "), remote=" + remoteCode + " (" + remoteName + ")");

                ui.post(() -> {
                    if (act.isFinishing()) return;
                    if (remoteCode > curCode && !apkUrl.isEmpty()) {
                        if (hooks != null) hooks.onSuspendOverlay();
                        StringBuilder msg = new StringBuilder();
                        msg.append("현재 버전: v").append(curName).append("\n");
                        msg.append("최신 버전: v").append(remoteName);
                        if (!buildDate.isEmpty()) msg.append(" (").append(buildDate).append(")");
                        msg.append("\n");
                        if (sizeBytes > 0) {
                            msg.append("용량: ").append(PdfCacheManager.formatSize(sizeBytes)).append("\n");
                        }
                        if (!notes.isEmpty()) {
                            msg.append("\n변경사항:\n").append(notes);
                        }
                        AlertDialog d = new AlertDialog.Builder(act)
                                .setTitle("🔄 GNFLPDF 새 버전 업데이트")
                                .setMessage(msg.toString())
                                .setPositiveButton("지금 업데이트", (dialog, which) ->
                                        downloadAndInstallApk(act, apkUrl, sha256, sizeBytes, hooks))
                                .setNegativeButton("나중에", null)
                                .create();
                        d.setOnDismissListener(dialog -> {
                            if (hooks != null) hooks.onRestoreOverlay();
                        });
                        d.show();
                    } else if (manualCheck) {
                        if (hooks != null) hooks.onSuspendOverlay();
                        AlertDialog d = new AlertDialog.Builder(act)
                                .setTitle("✅ 최신 버전입니다")
                                .setMessage("현재 설치된 GNFLPDF v" + curName + " 이(가) 최신 버전입니다."
                                        + (buildDate.isEmpty() ? "" : ("\n빌드 일시: " + buildDate)))
                                .setPositiveButton("확인", null)
                                .create();
                        d.setOnDismissListener(dialog -> {
                            if (hooks != null) hooks.onRestoreOverlay();
                        });
                        d.show();
                    }
                });
            } catch (Throwable t) {
                Log.w(TAG, "OTA check failed: " + t.getMessage());
                if (manualCheck) {
                    ui.post(() -> {
                        if (act.isFinishing()) return;
                        if (hooks != null) hooks.onSuspendOverlay();
                        AlertDialog d = new AlertDialog.Builder(act)
                                .setTitle("업데이트 확인 실패")
                                .setMessage("GitHub 업데이트 정보를 가져올 수 없습니다.\n네트워크 연결을 확인해 주세요.\n\n(" + t.getMessage() + ")")
                                .setPositiveButton("확인", null)
                                .create();
                        d.setOnDismissListener(dialog -> {
                            if (hooks != null) hooks.onRestoreOverlay();
                        });
                        d.show();
                    });
                }
            }
        }, "GnflPdfOtaCheck").start();
    }

    private static void downloadAndInstallApk(final Activity act, final String apkUrl,
                                              final String expectedSha256, final long expectedBytes,
                                              final OverlayHooks hooks) {
        if (act == null || act.isFinishing()) return;
        if (hooks != null) hooks.onSuspendOverlay();

        // Android 8.0+ 출처를 알 수 없는 앱 설치 권한 확인
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                if (!act.getPackageManager().canRequestPackageInstalls()) {
                    new AlertDialog.Builder(act)
                            .setTitle("앱 설치 권한 필요")
                            .setMessage("GNFLPDF 자동 업데이트 설치를 위해 '이 출처 허용'을 켜 주세요.\n허용 후 다시 '업데이트 확인'을 눌러 설치를 진행할 수 있습니다.")
                            .setPositiveButton("설정으로 이동", (d, w) -> {
                                try {
                                    Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                            Uri.parse("package:" + act.getPackageName()));
                                    act.startActivity(intent);
                                } catch (Throwable ignored) {}
                            })
                            .setNegativeButton("취소", (d, w) -> {
                                if (hooks != null) hooks.onRestoreOverlay();
                            })
                            .show();
                    return;
                }
            } catch (Throwable ignored) {}
        }

        final Handler ui = new Handler(Looper.getMainLooper());
        float dens = act.getResources().getDisplayMetrics().density;
        int pad = Math.round(22 * dens);

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);

        final TextView statusTv = new TextView(act);
        statusTv.setText("업데이트 파일 다운로드 준비 중...");
        statusTv.setTextColor(Color.parseColor("#0F172A"));
        statusTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        box.addView(statusTv);

        final ProgressBar pb = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setProgress(0);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = Math.round(14 * dens);
        box.addView(pb, plp);

        final boolean[] cancelled = new boolean[] { false };
        final AlertDialog progressDlg = new AlertDialog.Builder(act)
                .setTitle("⬇️ GNFLPDF 업데이트 다운로드")
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("취소", (d, w) -> cancelled[0] = true)
                .create();
        progressDlg.show();

        new Thread(() -> {
            File outDir = new File(act.getCacheDir(), "ota");
            if (!outDir.exists()) outDir.mkdirs();
            File tmpFile = new File(outDir, "GNFLPDF_update.tmp");
            File apkFile = new File(outDir, "GNFLPDF_update.apk");

            HttpURLConnection conn = null;
            InputStream in = null;
            FileOutputStream fos = null;
            try {
                URL url = new URL(apkUrl + (apkUrl.contains("?") ? "&" : "?") + "t=" + System.currentTimeMillis());
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(20000);
                conn.setInstanceFollowRedirects(true);
                conn.connect();
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new RuntimeException("HTTP " + code);
                }

                long total = conn.getContentLengthLong();
                if (total <= 0) total = expectedBytes;
                final long totalFinal = total;

                in = new BufferedInputStream(conn.getInputStream(), 65536);
                fos = new FileOutputStream(tmpFile);
                MessageDigest md = MessageDigest.getInstance("SHA-256");

                byte[] buf = new byte[32768];
                long downloaded = 0L;
                long lastUiMs = 0L;
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancelled[0]) {
                        throw new RuntimeException("사용자가 다운로드를 취소했습니다.");
                    }
                    fos.write(buf, 0, n);
                    md.update(buf, 0, n);
                    downloaded += n;

                    long now = System.currentTimeMillis();
                    if (now - lastUiMs >= 100L) {
                        lastUiMs = now;
                        final long dl = downloaded;
                        final int pct = (totalFinal > 0) ? (int) Math.min(100, (dl * 100L) / totalFinal) : 0;
                        ui.post(() -> {
                            pb.setProgress(pct);
                            statusTv.setText(String.format(Locale.US, "다운로드 중... %d%% (%s / %s)",
                                    pct, PdfCacheManager.formatSize(dl),
                                    totalFinal > 0 ? PdfCacheManager.formatSize(totalFinal) : "?"));
                        });
                    }
                }
                fos.flush();
                fos.close();
                fos = null;

                String actualSha = bytesToHex(md.digest());
                if (expectedSha256 != null && !expectedSha256.trim().isEmpty()
                        && !expectedSha256.trim().equalsIgnoreCase(actualSha)) {
                    throw new RuntimeException("SHA-256 무결성 검증 실패");
                }

                if (apkFile.exists()) apkFile.delete();
                if (!tmpFile.renameTo(apkFile)) {
                    throw new RuntimeException("APK 파일 저장 실패");
                }

                ui.post(() -> {
                    try { progressDlg.dismiss(); } catch (Throwable ignored) {}
                    launchApkInstaller(act);
                });
            } catch (Throwable t) {
                try { if (tmpFile.exists()) tmpFile.delete(); } catch (Throwable ignored) {}
                ui.post(() -> {
                    try { progressDlg.dismiss(); } catch (Throwable ignored) {}
                    if (!cancelled[0]) {
                        Toast.makeText(act, "업데이트 다운로드 실패: " + t.getMessage(), Toast.LENGTH_LONG).show();
                    }
                    if (hooks != null) hooks.onRestoreOverlay();
                });
            } finally {
                try { if (fos != null) fos.close(); } catch (Throwable ignored) {}
                try { if (in != null) in.close(); } catch (Throwable ignored) {}
                if (conn != null) conn.disconnect();
            }
        }, "GnflPdfOtaDownload").start();
    }

    private static void launchApkInstaller(Activity act) {
        try {
            Uri apkUri = ApkFileProvider.getApkUri();
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(act, "설치 화면을 열 수 없습니다: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static byte[] httpGetBytes(String urlStr, int timeoutMs) throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.connect();
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new RuntimeException("HTTP " + code);
            }
            in = new BufferedInputStream(conn.getInputStream());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.US, "%02x", b & 0xFF));
        }
        return sb.toString();
    }
}

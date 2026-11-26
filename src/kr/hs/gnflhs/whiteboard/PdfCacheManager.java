package kr.hs.gnflhs.whiteboard;

import android.content.Context;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GNFL NOTE V1.5 - 최근 PDF LRU 캐시 관리자
 *
 * - 저장 위치: context.getFilesDir()/gnfl_pdf_cache (OS 임의 삭제 방지, 앱 업그레이드 시 유지)
 * - 기본 상한: 3 GB (3072 MB)
 * - 시스템 보호: 기기 여유 공간이 2 GB 미만이면 상한을 자동으로 256 MB로 축소
 * - 용량 초과 시 가장 오래전에 열었던 파일(LRU)부터 자동 삭제
 * - PDF 파일별 최근 열었던 페이지 위치(lastPageIndex) 기억 및 복원
 */
public class PdfCacheManager {
    private static final String TAG = "PdfCacheManager";
    private static final String CACHE_DIR_NAME = "gnfl_pdf_cache";
    private static final String INDEX_FILE_NAME = "index.json";

    public static final long DEFAULT_MAX_BYTES = 3L * 1024L * 1024L * 1024L;     // 3 GB
    private static final long LOW_STORAGE_THRESHOLD = 2L * 1024L * 1024L * 1024L; // 2 GB
    private static final long LOW_STORAGE_MAX_BYTES = 256L * 1024L * 1024L;       // 256 MB

    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();
    private static final Object sLock = new Object();
    // 메모리 캐시: 디스크 기록 직전에도 즉시 최근 페이지를 돌려주기 위함
    private static final Map<String, Integer> sLastPageByName = new HashMap<String, Integer>();
    // 세션 단위 잠금 해제 상태: 현재 열려 있는 파일 딱 1개만 유지 (다른 파일 열거나 앱 종료 시 즉시 자동 재잠금!)
    private static volatile String sUnlockedEntryId = null;

    public static final class CacheEntry {
        public final String id;
        public final String displayName;
        public final long fileSize;
        public final int pageCount;
        public long lastOpenedAt;
        public final String fingerprint;
        public int lastPageIndex;
        public String pinHash;

        public CacheEntry(String id, String displayName, long fileSize, int pageCount, long lastOpenedAt, String fingerprint, int lastPageIndex, String pinHash) {
            this.id = id;
            this.displayName = displayName;
            this.fileSize = fileSize;
            this.pageCount = pageCount;
            this.lastOpenedAt = lastOpenedAt;
            this.fingerprint = fingerprint;
            this.lastPageIndex = Math.max(0, lastPageIndex);
            this.pinHash = (pinHash != null) ? pinHash : "";
        }

        public boolean isLocked() {
            return pinHash != null && !pinHash.isEmpty();
        }
    }

    public static boolean isEntryUnlockedInSession(String entryId) {
        return entryId != null && entryId.equals(sUnlockedEntryId);
    }

    public static void markEntryUnlockedInSession(String entryId) {
        sUnlockedEntryId = entryId;
    }

    public static void clearSessionUnlock() {
        sUnlockedEntryId = null;
    }

    private static String hashPin(String entryId, String pin) {
        if (entryId == null || pin == null || pin.isEmpty()) return "";
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(("gnfl_pdf_pin_v1:" + entryId + ":" + pin).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) {
                sb.append(String.format(Locale.US, "%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString((entryId + ":" + pin).hashCode());
        }
    }

    public static boolean setEntryPin(Context context, String id, String pin4) {
        if (context == null || id == null || pin4 == null || pin4.length() != 4) return false;
        synchronized (sLock) {
            List<CacheEntry> entries = loadEntriesLocked(context);
            for (CacheEntry e : entries) {
                if (id.equals(e.id)) {
                    e.pinHash = hashPin(id, pin4);
                    saveEntriesLocked(context, entries);
                    if (id.equals(sUnlockedEntryId)) {
                        sUnlockedEntryId = null;
                    }
                    Log.i(TAG, "PIN lock enabled for PDF: " + e.displayName);
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean verifyEntryPin(Context context, String id, String pin4) {
        if (context == null || id == null || pin4 == null || pin4.length() != 4) return false;
        synchronized (sLock) {
            List<CacheEntry> entries = loadEntriesLocked(context);
            for (CacheEntry e : entries) {
                if (id.equals(e.id)) {
                    return e.isLocked() && e.pinHash.equals(hashPin(id, pin4));
                }
            }
        }
        return false;
    }

    public static boolean clearEntryPin(Context context, String id, String pin4) {
        if (context == null || id == null || pin4 == null || pin4.length() != 4) return false;
        synchronized (sLock) {
            List<CacheEntry> entries = loadEntriesLocked(context);
            for (CacheEntry e : entries) {
                if (id.equals(e.id)) {
                    if (!e.isLocked() || !e.pinHash.equals(hashPin(id, pin4))) {
                        return false;
                    }
                    e.pinHash = "";
                    saveEntriesLocked(context, entries);
                    if (id.equals(sUnlockedEntryId)) {
                        sUnlockedEntryId = null;
                    }
                    Log.i(TAG, "PIN lock cleared for PDF: " + e.displayName);
                    return true;
                }
            }
        }
        return false;
    }

    public static File getCacheDir(Context context) {
        File dir = new File(context.getFilesDir(), CACHE_DIR_NAME);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static File getEntryFile(Context context, CacheEntry entry) {
        if (context == null || entry == null || entry.id == null) return null;
        return new File(getCacheDir(context), entry.id);
    }

    public static long getEffectiveMaxBytes(Context context) {
        try {
            File dir = getCacheDir(context);
            long usable = dir.getUsableSpace();
            if (usable > 0 && usable < LOW_STORAGE_THRESHOLD) {
                return LOW_STORAGE_MAX_BYTES;
            }
        } catch (Throwable ignored) {}
        return DEFAULT_MAX_BYTES;
    }

    public static List<CacheEntry> getRecentEntries(Context context) {
        if (context == null) return Collections.emptyList();
        synchronized (sLock) {
            List<CacheEntry> entries = loadEntriesLocked(context);
            boolean dirty = pruneMissingFilesLocked(context, entries);
            sortNewestFirst(entries);
            if (dirty) {
                saveEntriesLocked(context, entries);
            }
            return new ArrayList<CacheEntry>(entries);
        }
    }

    public static long getTotalCacheBytes(Context context) {
        if (context == null) return 0L;
        synchronized (sLock) {
            List<CacheEntry> entries = loadEntriesLocked(context);
            long total = 0L;
            File dir = getCacheDir(context);
            for (CacheEntry e : entries) {
                File f = new File(dir, e.id);
                if (f.exists()) {
                    total += f.length();
                }
            }
            return total;
        }
    }

    public static void touchEntry(Context context, String id) {
        if (context == null || id == null) return;
        synchronized (sLock) {
            List<CacheEntry> entries = loadEntriesLocked(context);
            boolean changed = false;
            for (CacheEntry e : entries) {
                if (id.equals(e.id)) {
                    e.lastOpenedAt = System.currentTimeMillis();
                    changed = true;
                    break;
                }
            }
            if (changed) {
                sortNewestFirst(entries);
                saveEntriesLocked(context, entries);
            }
        }
    }

    /**
     * 해당 PDF 파일에서 마지막으로 열었던 페이지 인덱스(0-based)를 반환합니다.
     */
    public static int getLastPageIndex(Context context, String displayName, String filePath) {
        if (context == null) return 0;
        String safeName = sanitizeDisplayName(displayName);
        String targetId = extractCacheId(context, filePath);

        synchronized (sLock) {
            // 1. 메모리 캐시 우선 확인 (방금 페이지를 넘겼을 때 즉시 반영)
            if (targetId != null && sLastPageByName.containsKey("id:" + targetId)) {
                return Math.max(0, sLastPageByName.get("id:" + targetId));
            }
            if (sLastPageByName.containsKey("name:" + safeName)) {
                return Math.max(0, sLastPageByName.get("name:" + safeName));
            }

            // 2. 영구 저장된 index.json에서 조회
            List<CacheEntry> entries = loadEntriesLocked(context);
            if (targetId != null) {
                for (CacheEntry e : entries) {
                    if (targetId.equals(e.id)) {
                        return Math.max(0, e.lastPageIndex);
                    }
                }
            }
            for (CacheEntry e : entries) {
                if (safeName.equals(e.displayName)) {
                    return Math.max(0, e.lastPageIndex);
                }
            }
        }
        return 0;
    }

    /**
     * 해당 PDF 파일에서 마지막으로 보고 있거나 삽입한 페이지 인덱스(0-based)를 기억합니다.
     */
    public static void saveLastPageIndex(final Context context, final String displayName, final String filePath, final int pageIndex) {
        if (context == null || pageIndex < 0) return;
        final Context appCtx = context.getApplicationContext();
        final String safeName = sanitizeDisplayName(displayName);
        final String targetId = extractCacheId(appCtx, filePath);

        synchronized (sLock) {
            if (targetId != null) {
                sLastPageByName.put("id:" + targetId, pageIndex);
            }
            sLastPageByName.put("name:" + safeName, pageIndex);
        }

        sExecutor.execute(new Runnable() {
            @Override
            public void run() {
                synchronized (sLock) {
                    List<CacheEntry> entries = loadEntriesLocked(appCtx);
                    boolean updated = false;
                    if (targetId != null) {
                        for (CacheEntry e : entries) {
                            if (targetId.equals(e.id)) {
                                if (e.lastPageIndex != pageIndex) {
                                    e.lastPageIndex = pageIndex;
                                    updated = true;
                                }
                                break;
                            }
                        }
                    }
                    if (!updated) {
                        for (CacheEntry e : entries) {
                            if (safeName.equals(e.displayName)) {
                                if (e.lastPageIndex != pageIndex) {
                                    e.lastPageIndex = pageIndex;
                                    updated = true;
                                }
                                break;
                            }
                        }
                    }
                    if (updated) {
                        saveEntriesLocked(appCtx, entries);
                    }
                }
            }
        });
    }

    private static String extractCacheId(Context context, String filePath) {
        if (context == null || filePath == null || filePath.isEmpty()) return null;
        try {
            File f = new File(filePath);
            if (getCacheDir(context).equals(f.getParentFile())) {
                return f.getName();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static boolean deleteEntry(Context context, String id) {
        if (context == null || id == null) return false;
        synchronized (sLock) {
            List<CacheEntry> entries = loadEntriesLocked(context);
            boolean removed = false;
            Iterator<CacheEntry> it = entries.iterator();
            while (it.hasNext()) {
                CacheEntry e = it.next();
                if (id.equals(e.id)) {
                    File f = new File(getCacheDir(context), e.id);
                    if (f.exists()) {
                        f.delete();
                    }
                    it.remove();
                    removed = true;
                    break;
                }
            }
            if (removed) {
                saveEntriesLocked(context, entries);
            }
            return removed;
        }
    }

    public static void cacheFromUriAsync(final Context context, final Uri uri, final String displayName, final Runnable onDone) {
        if (context == null || uri == null) return;
        final Context appCtx = context.getApplicationContext();
        final String safeName = sanitizeDisplayName(displayName);
        sExecutor.execute(new Runnable() {
            @Override
            public void run() {
                File tempFile = null;
                try {
                    File dir = getCacheDir(appCtx);
                    tempFile = new File(dir, "tmp_" + System.currentTimeMillis() + ".pdf");
                    InputStream in = null;
                    BufferedOutputStream out = null;
                    try {
                        in = appCtx.getContentResolver().openInputStream(uri);
                        if (in == null) return;
                        out = new BufferedOutputStream(new FileOutputStream(tempFile), 65536);
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                        }
                        out.flush();
                    } finally {
                        if (in != null) try { in.close(); } catch (Throwable ignored) {}
                        if (out != null) try { out.close(); } catch (Throwable ignored) {}
                    }
                    commitTempFileToCache(appCtx, tempFile, safeName);
                    tempFile = null;
                } catch (Throwable t) {
                    Log.w(TAG, "cacheFromUriAsync failed for " + safeName, t);
                } finally {
                    if (tempFile != null && tempFile.exists()) {
                        tempFile.delete();
                    }
                    if (onDone != null) {
                        try { onDone.run(); } catch (Throwable ignored) {}
                    }
                }
            }
        });
    }

    public static void cacheFromFileAsync(final Context context, final File sourceFile, final String displayName, final Runnable onDone) {
        if (context == null || sourceFile == null || !sourceFile.exists()) return;
        final Context appCtx = context.getApplicationContext();
        final String safeName = sanitizeDisplayName(displayName != null ? displayName : sourceFile.getName());
        sExecutor.execute(new Runnable() {
            @Override
            public void run() {
                File tempFile = null;
                try {
                    File dir = getCacheDir(appCtx);
                    // 이미 캐시 폴더 내의 파일이면 lastOpenedAt만 갱신
                    if (dir.equals(sourceFile.getParentFile())) {
                        touchEntry(appCtx, sourceFile.getName());
                        return;
                    }
                    tempFile = new File(dir, "tmp_" + System.currentTimeMillis() + ".pdf");
                    BufferedInputStream in = null;
                    BufferedOutputStream out = null;
                    try {
                        in = new BufferedInputStream(new FileInputStream(sourceFile), 65536);
                        out = new BufferedOutputStream(new FileOutputStream(tempFile), 65536);
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                        }
                        out.flush();
                    } finally {
                        if (in != null) try { in.close(); } catch (Throwable ignored) {}
                        if (out != null) try { out.close(); } catch (Throwable ignored) {}
                    }
                    commitTempFileToCache(appCtx, tempFile, safeName);
                    tempFile = null;
                } catch (Throwable t) {
                    Log.w(TAG, "cacheFromFileAsync failed for " + safeName, t);
                } finally {
                    if (tempFile != null && tempFile.exists()) {
                        tempFile.delete();
                    }
                    if (onDone != null) {
                        try { onDone.run(); } catch (Throwable ignored) {}
                    }
                }
            }
        });
    }

    private static void commitTempFileToCache(Context appCtx, File tempFile, String displayName) {
        long size = tempFile.length();
        if (size <= 0L) {
            tempFile.delete();
            return;
        }
        int pageCount = probePageCount(tempFile);
        if (pageCount <= 0) {
            // 유효하지 않은 PDF 파일은 캐시하지 않음
            tempFile.delete();
            return;
        }

        String fingerprint = displayName + "|" + size;
        long now = System.currentTimeMillis();

        synchronized (sLock) {
            File dir = getCacheDir(appCtx);
            List<CacheEntry> entries = loadEntriesLocked(appCtx);
            pruneMissingFilesLocked(appCtx, entries);

            // 동일 파일(이름+크기) 이미 캐시에 있으면 중복 저장 방지 & 최근 열람 시각만 갱신
            for (CacheEntry existing : entries) {
                if (fingerprint.equals(existing.fingerprint)) {
                    File existingFile = new File(dir, existing.id);
                    if (existingFile.exists()) {
                        existing.lastOpenedAt = now;
                        tempFile.delete();
                        sortNewestFirst(entries);
                        evictLruLocked(appCtx, entries, existing.id);
                        saveEntriesLocked(appCtx, entries);
                        Log.i(TAG, "Updated existing PDF cache entry: " + displayName + " (" + formatSize(size) + ")");
                        return;
                    }
                }
            }

            String id = "pdf_" + now + ".pdf";
            File dest = new File(dir, id);
            if (!tempFile.renameTo(dest)) {
                tempFile.delete();
                return;
            }

            int memLastPage = 0;
            Integer savedMem = sLastPageByName.get("name:" + displayName);
            if (savedMem != null && savedMem >= 0 && savedMem < pageCount) {
                memLastPage = savedMem;
            }
            CacheEntry added = new CacheEntry(id, displayName, size, pageCount, now, fingerprint, memLastPage, "");
            entries.add(0, added);
            sortNewestFirst(entries);
            evictLruLocked(appCtx, entries, id);
            cleanupOrphanFilesLocked(appCtx, entries);
            saveEntriesLocked(appCtx, entries);
            Log.i(TAG, "Cached new PDF: " + displayName + " (" + pageCount + "p, " + formatSize(size) + ")");
        }
    }

    private static void evictLruLocked(Context appCtx, List<CacheEntry> entries, String protectId) {
        long maxBytes = getEffectiveMaxBytes(appCtx);
        File dir = getCacheDir(appCtx);

        long total = 0L;
        for (CacheEntry e : entries) {
            File f = new File(dir, e.id);
            if (f.exists()) total += f.length();
        }
        if (total <= maxBytes) return;

        // 오래된 순(리스트 뒤쪽)부터 삭제하되, 방금 연 파일(protectId)은 보존
        for (int i = entries.size() - 1; i >= 0 && total > maxBytes; i--) {
            CacheEntry e = entries.get(i);
            if (protectId != null && protectId.equals(e.id)) continue;
            File f = new File(dir, e.id);
            long len = f.exists() ? f.length() : e.fileSize;
            if (f.exists()) {
                f.delete();
            }
            total -= len;
            entries.remove(i);
            Log.i(TAG, "LRU evicted old PDF cache: " + e.displayName + " (" + formatSize(len) + ")");
        }
    }

    private static void cleanupOrphanFilesLocked(Context appCtx, List<CacheEntry> entries) {
        try {
            File dir = getCacheDir(appCtx);
            Set<String> validIds = new HashSet<String>();
            validIds.add(INDEX_FILE_NAME);
            for (CacheEntry e : entries) {
                validIds.add(e.id);
            }
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                String name = f.getName();
                if (!validIds.contains(name) && !name.startsWith("tmp_")) {
                    f.delete();
                }
            }
        } catch (Throwable ignored) {}
    }

    private static boolean pruneMissingFilesLocked(Context appCtx, List<CacheEntry> entries) {
        boolean dirty = false;
        File dir = getCacheDir(appCtx);
        Iterator<CacheEntry> it = entries.iterator();
        while (it.hasNext()) {
            CacheEntry e = it.next();
            File f = new File(dir, e.id);
            if (!f.exists() || f.length() <= 0L) {
                it.remove();
                dirty = true;
            }
        }
        return dirty;
    }

    private static void sortNewestFirst(List<CacheEntry> entries) {
        Collections.sort(entries, new Comparator<CacheEntry>() {
            @Override
            public int compare(CacheEntry a, CacheEntry b) {
                return Long.compare(b.lastOpenedAt, a.lastOpenedAt);
            }
        });
    }

    private static List<CacheEntry> loadEntriesLocked(Context appCtx) {
        List<CacheEntry> list = new ArrayList<CacheEntry>();
        File idxFile = new File(getCacheDir(appCtx), INDEX_FILE_NAME);
        if (!idxFile.exists()) return list;
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(idxFile);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
            String json = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", "");
                String name = o.optString("displayName", "PDF 문서");
                long size = o.optLong("fileSize", 0L);
                int pages = o.optInt("pageCount", 0);
                long last = o.optLong("lastOpenedAt", 0L);
                String fp = o.optString("fingerprint", name + "|" + size);
                int lastPage = o.optInt("lastPageIndex", 0);
                String pinHash = o.optString("pinHash", "");
                if (!id.isEmpty()) {
                    list.add(new CacheEntry(id, name, size, pages, last, fp, lastPage, pinHash));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to load PDF cache index", t);
        } finally {
            if (fis != null) try { fis.close(); } catch (Throwable ignored) {}
        }
        return list;
    }

    private static void saveEntriesLocked(Context appCtx, List<CacheEntry> entries) {
        File idxFile = new File(getCacheDir(appCtx), INDEX_FILE_NAME);
        File tmpFile = new File(getCacheDir(appCtx), INDEX_FILE_NAME + ".tmp");
        FileOutputStream fos = null;
        try {
            JSONArray arr = new JSONArray();
            for (CacheEntry e : entries) {
                JSONObject o = new JSONObject();
                o.put("id", e.id);
                o.put("displayName", e.displayName);
                o.put("fileSize", e.fileSize);
                o.put("pageCount", e.pageCount);
                o.put("lastOpenedAt", e.lastOpenedAt);
                o.put("fingerprint", e.fingerprint);
                o.put("lastPageIndex", e.lastPageIndex);
                if (e.pinHash != null && !e.pinHash.isEmpty()) {
                    o.put("pinHash", e.pinHash);
                }
                arr.put(o);
            }
            byte[] bytes = arr.toString().getBytes(StandardCharsets.UTF_8);
            fos = new FileOutputStream(tmpFile);
            fos.write(bytes);
            fos.flush();
            fos.close();
            fos = null;
            if (!tmpFile.renameTo(idxFile)) {
                // Fallback direct write
                fos = new FileOutputStream(idxFile);
                fos.write(bytes);
                fos.flush();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to save PDF cache index", t);
        } finally {
            if (fos != null) try { fos.close(); } catch (Throwable ignored) {}
            if (tmpFile.exists()) tmpFile.delete();
        }
    }

    private static int probePageCount(File pdfFile) {
        ParcelFileDescriptor pfd = null;
        PdfRenderer renderer = null;
        try {
            pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(pfd);
            return renderer.getPageCount();
        } catch (Throwable t) {
            return 0;
        } finally {
            if (renderer != null) try { renderer.close(); } catch (Throwable ignored) {}
            if (pfd != null) try { pfd.close(); } catch (Throwable ignored) {}
        }
    }

    private static String sanitizeDisplayName(String name) {
        if (name == null || name.trim().isEmpty()) return "PDF 문서.pdf";
        return name.trim();
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format(Locale.US, "%.2f GB", gb);
    }
}

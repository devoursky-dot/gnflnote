package kr.hs.gnflhs.gnflpdf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.widget.Toast;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

/**
 * GNFL NOTE V1.7 - PDF 하이퍼링크 & QR코드 선택 툴 탭 연결 관리자
 *
 * - '선택' 툴로 PDF 페이지(또는 올가미로 오려낸 문제 이미지) 위의 링크/QR코드를 짧게 탭하면:
 *   1) PDF 원본 내장 하이퍼링크(/Subtype /Link, /Rect, /URI) 검사
 *   2) 원본 PDF 4K 벡터 크롭(또는 이미지 크롭) 후 ZXing QRCodeReader로 QR코드 디코딩
 *   3) 링크 발견 시 "링크로 연결할까요?" 확인 팝업 표시 -> 브라우저/앱으로 즉시 연결!
 */
public class PdfLinkQrManager {
    private static final String TAG = "PdfLinkQrManager";
    private static PdfLinkQrManager sInstance;

    private final HandlerThread mWorkerThread;
    private final Handler mWorkerHandler;
    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private volatile boolean mBusy = false;

    private static final class ImageHit {
        final PdfPicker.PdfSourceRef pdfRef; // null이면 일반/올가미 이미지
        final String imagePath;
        final Rect bounds;

        ImageHit(PdfPicker.PdfSourceRef pdfRef, String imagePath, Rect bounds) {
            this.pdfRef = pdfRef;
            this.imagePath = imagePath;
            this.bounds = bounds;
        }
    }

    private static final class PdfUriLink {
        final RectF rect; // PDF 좌표계 (좌하단 원점)
        final String uri;

        PdfUriLink(RectF rect, String uri) {
            this.rect = rect;
            this.uri = uri;
        }
    }

    // PDF 파일 경로 -> 추출된 링크 목록 캐시
    private final Map<String, List<PdfUriLink>> mLinkCache = new HashMap<String, List<PdfUriLink>>();

    public static synchronized PdfLinkQrManager getInstance() {
        if (sInstance == null) {
            sInstance = new PdfLinkQrManager();
        }
        return sInstance;
    }

    private PdfLinkQrManager() {
        mWorkerThread = new HandlerThread("PdfLinkQrWorker");
        mWorkerThread.start();
        mWorkerHandler = new Handler(mWorkerThread.getLooper());
    }

    /**
     * '선택' 툴 상태에서 칠판 위를 짧게 탭했을 때 호출됩니다.
     * PDF 하이퍼링크 또는 QR코드가 감지되면 연결 확인 팝업을 띄웁니다.
     */
    public void handleSelectTap(final Activity activity, final float screenX, final float screenY) {
        if (activity == null || mBusy) return;

        final ImageHit hit = findImageShapeAtPoint(activity, screenX, screenY);
        if (hit == null || hit.bounds.width() <= 0 || hit.bounds.height() <= 0) {
            Log.i(TAG, "Select tap (" + (int) screenX + "," + (int) screenY + "): no ImageShape under point");
            return;
        }

        final float u = (screenX - hit.bounds.left) / (float) hit.bounds.width();
        final float v = (screenY - hit.bounds.top) / (float) hit.bounds.height();
        if (u < 0f || u > 1f || v < 0f || v > 1f) return;
        Log.i(TAG, String.format("Select tap (%.0f,%.0f) -> u=%.3f, v=%.3f, pdf=%s, page=%d",
                screenX, screenY, u, v,
                (hit.pdfRef != null ? (hit.pdfRef.filePath != null ? hit.pdfRef.filePath : String.valueOf(hit.pdfRef.uri)) : "img"),
                (hit.pdfRef != null ? hit.pdfRef.pageIndex + 1 : -1)));

        runLinkQrDetection(activity, hit.pdfRef, hit.imagePath, u, v);
    }

    /**
     * V3.0 세로 스크롤 PDF 모드에서 '선택' 툴로 탭했을 때 원본 PDF 페이지의 (u, v) 좌표를 직접 검사합니다.
     */
    public void handleDirectPdfTap(final Activity activity, final String filePath, final Uri uri,
                                   final int pageIndex, final float u, final float v) {
        if (activity == null || mBusy) return;
        if (u < 0f || u > 1f || v < 0f || v > 1f) return;
        if (filePath == null && uri == null) return;
        PdfPicker.PdfSourceRef ref = new PdfPicker.PdfSourceRef(uri, filePath, pageIndex);
        runLinkQrDetection(activity, ref, null, u, v);
    }

    private void runLinkQrDetection(final Activity activity, final PdfPicker.PdfSourceRef pdfRef,
                                    final String imagePath, final float u, final float v) {
        mBusy = true;
        mWorkerHandler.post(new Runnable() {
            @Override
            public void run() {
                String foundUrl = null;
                boolean isQr = false;
                try {
                    if (pdfRef != null) {
                        // 1. PDF 내장 하이퍼링크(/URI) 우선 검사
                        foundUrl = findPdfHyperlinkAt(activity, pdfRef, u, v);
                        if (foundUrl != null) {
                            Log.i(TAG, "Matched PDF embedded hyperlink: " + foundUrl);
                        } else {
                            // 2. 원본 PDF 4K 벡터 크롭 후 QR코드 디코딩
                            foundUrl = decodeQrFromPdfSource(activity, pdfRef, u, v);
                            if (foundUrl != null) {
                                isQr = true;
                                Log.i(TAG, "Decoded QR from PDF page " + (pdfRef.pageIndex + 1) + ": " + foundUrl);
                            }
                        }
                    }

                    // 3. PDF가 아니거나(예: 올가미 스크랩 이미지) PDF 크롭 실패 시 이미지 파일 자체에서 QR 디코딩
                    if (foundUrl == null && imagePath != null && !imagePath.isEmpty()) {
                        foundUrl = decodeQrFromImageFile(imagePath, u, v);
                        if (foundUrl != null) {
                            isQr = true;
                            Log.i(TAG, "Decoded QR from ImageShape: " + foundUrl);
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "runLinkQrDetection error", t);
                } finally {
                    mBusy = false;
                }

                if (foundUrl != null && !foundUrl.trim().isEmpty()) {
                    final String target = foundUrl.trim();
                    final boolean fromQr = isQr;
                    mUiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            showLinkConfirmDialog(activity, target, fromQr);
                        }
                    });
                }
            }
        });
    }

    private ImageHit findImageShapeAtPoint(Activity activity, float x, float y) {
        try {
            Field fP4a = activity.getClass().getDeclaredField("t");
            fP4a.setAccessible(true);
            Object p4a = fP4a.get(activity);
            if (p4a == null) return null;

            Object stateHolder = p4a.getClass().getMethod("a").invoke(p4a);
            if (stateHolder == null) return null;

            Object page = stateHolder.getClass().getMethod("m0").invoke(stateHolder);
            if (page == null) return null;

            int count = (Integer) page.getClass().getMethod("size").invoke(page);
            Method mGetShape = page.getClass().getMethod("get", int.class);

            int ix = Math.round(x);
            int iy = Math.round(y);
            for (int i = count - 1; i >= 0; i--) {
                Object shape = mGetShape.invoke(page, i);
                if (shape == null) continue;
                if (!shape.getClass().getName().contains("ImageShape")) continue;

                Rect b = (Rect) shape.getClass().getMethod("getBounds").invoke(shape);
                if (b == null || !b.contains(ix, iy)) continue;

                String resPath = readStringField(shape, "mResourcePath");
                String savePath = readStringField(shape, "mSavePath");
                PdfPicker.PdfSourceRef ref = PdfPicker.findPdfSourceForPath(resPath);
                if (ref == null) ref = PdfPicker.findPdfSourceForPath(savePath);

                String bestImgPath = (resPath != null && new File(resPath).exists()) ? resPath : savePath;
                return new ImageHit(ref, bestImgPath, new Rect(b));
            }
        } catch (Throwable t) {
            Log.w(TAG, "findImageShapeAtPoint skipped: " + t.getMessage());
        }
        return null;
    }

    private static String readStringField(Object obj, String fieldName) {
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                Object val = f.get(obj);
                if (val instanceof String) return (String) val;
                return null;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /**
     * 원본 PDF에서 /Subtype /Link + /Rect + /URI 주석을 경량 파싱해 탭 좌표와 매칭합니다.
     */
    private String findPdfHyperlinkAt(Activity activity, PdfPicker.PdfSourceRef ref, float u, float v) {
        ParcelFileDescriptor pfd = null;
        PdfRenderer renderer = null;
        PdfRenderer.Page page = null;
        try {
            pfd = openPdfDescriptor(activity, ref);
            if (pfd == null) return null;
            renderer = new PdfRenderer(pfd);
            if (ref.pageIndex < 0 || ref.pageIndex >= renderer.getPageCount()) return null;
            page = renderer.openPage(ref.pageIndex);
            float pw = page.getWidth();
            float ph = page.getHeight();
            page.close();
            page = null;
            renderer.close();
            renderer = null;
            pfd.close();
            pfd = null;

            String cacheKey = (ref.filePath != null) ? ref.filePath : (ref.uri != null ? ref.uri.toString() : null);
            if (cacheKey == null) return null;

            List<PdfUriLink> links;
            synchronized (mLinkCache) {
                links = mLinkCache.get(cacheKey);
            }
            if (links == null) {
                links = parsePdfUriLinks(activity, ref);
                synchronized (mLinkCache) {
                    if (mLinkCache.size() > 8) mLinkCache.clear();
                    mLinkCache.put(cacheKey, links);
                }
            }
            if (links.isEmpty()) return null;

            // PDF 좌표계: 좌하단 (0, 0), 우상단 (pw, ph)
            float pdfX = u * pw;
            float pdfY = (1f - v) * ph;
            float pad = 12f;
            for (PdfUriLink link : links) {
                if (pdfX >= link.rect.left - pad && pdfX <= link.rect.right + pad
                        && pdfY >= link.rect.bottom - pad && pdfY <= link.rect.top + pad) {
                    return link.uri;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "findPdfHyperlinkAt skipped: " + t.getMessage());
        } finally {
            try { if (page != null) page.close(); } catch (Throwable ignored) {}
            try { if (renderer != null) renderer.close(); } catch (Throwable ignored) {}
            try { if (pfd != null) pfd.close(); } catch (Throwable ignored) {}
        }
        return null;
    }

    private List<PdfUriLink> parsePdfUriLinks(Activity activity, PdfPicker.PdfSourceRef ref) {
        List<PdfUriLink> result = new ArrayList<PdfUriLink>();
        InputStream in = null;
        try {
            // 대용량 PDF에서 메모리 절약을 위해 청크(4MB) 단위로 스캔하며 /URI + /Rect 링크 사전 추출
            if (ref.filePath != null && new File(ref.filePath).exists()) {
                File f = new File(ref.filePath);
                if (f.length() > 60L * 1024L * 1024L) {
                    // 60MB 초과는 스캔 비용이 크므로 스킵 (QR 디코딩이 즉시 처리)
                    return result;
                }
                in = new FileInputStream(f);
            } else if (ref.uri != null) {
                in = activity.getContentResolver().openInputStream(ref.uri);
            }
            if (in == null) return result;

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            int total = 0;
            int limit = 25 * 1024 * 1024;
            while ((n = in.read(buf)) > 0 && total < limit) {
                baos.write(buf, 0, n);
                total += n;
            }
            byte[] fileBytes = baos.toByteArray();
            StringBuilder allText = new StringBuilder(fileBytes.length + 65536);
            allText.append(new String(fileBytes, StandardCharsets.ISO_8859_1));

            // PDF 1.5+ 압축 오브젝트 스트림(/Type /ObjStm + /FlateDecode)도 함께 압축 해제해 링크 주석 추출
            appendDecompressedObjStreams(fileBytes, allText);
            String raw = allText.toString();

            Pattern pRect = Pattern.compile("/Rect\\s*\\[\\s*([0-9.\\-]+)\\s+([0-9.\\-]+)\\s+([0-9.\\-]+)\\s+([0-9.\\-]+)\\s*\\]");
            Pattern pUri = Pattern.compile("/URI\\s*\\(([^\\)]{4,800})\\)");

            // 간접 참조 (/A 123 0 R)용 URI 맵 수집
            Map<String, String> objUriMap = new HashMap<String, String>();
            Pattern pObjUri = Pattern.compile("(\\d+)\\s+0\\s+obj\\s*<<.{0,500}?/URI\\s*\\(([^\\)]{4,800})\\)", Pattern.DOTALL);
            Matcher mObj = pObjUri.matcher(raw);
            while (mObj.find()) {
                objUriMap.put(mObj.group(1), unescapePdfString(mObj.group(2)));
            }
            Pattern pActionRef = Pattern.compile("/A\\s+(\\d+)\\s+0\\s+R");

            // 중첩 사전(/A << /S /URI /URI (...) >>)을 포함해 균형 괄호 << ... >> 단위로 모든 /Subtype /Link 추출
            Pattern pSubtypeLink = Pattern.compile("/Subtype\\s*/Link\\b");
            Matcher mSub = pSubtypeLink.matcher(raw);
            while (mSub.find()) {
                String block = extractEnclosingPdfDict(raw, mSub.start());
                if (block == null) continue;
                Matcher mr = pRect.matcher(block);
                if (!mr.find()) continue;
                float x1 = Float.parseFloat(mr.group(1));
                float y1 = Float.parseFloat(mr.group(2));
                float x2 = Float.parseFloat(mr.group(3));
                float y2 = Float.parseFloat(mr.group(4));
                RectF rect = new RectF(Math.min(x1, x2), Math.max(y1, y2), Math.max(x1, x2), Math.min(y1, y2));

                String uri = null;
                Matcher mu = pUri.matcher(block);
                if (mu.find()) {
                    uri = unescapePdfString(mu.group(1));
                } else {
                    Matcher ma = pActionRef.matcher(block);
                    if (ma.find()) {
                        uri = objUriMap.get(ma.group(1));
                    }
                }
                if (uri != null && !uri.isEmpty()) {
                    result.add(new PdfUriLink(rect, uri));
                }
            }
            Log.i(TAG, "Parsed " + result.size() + " embedded /URI links from PDF");
        } catch (Throwable t) {
            Log.w(TAG, "parsePdfUriLinks skipped: " + t.getMessage());
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
        return result;
    }

    /**
     * pos를 감싸는 가장 바깥쪽 PDF 사전(<< ... >>)을 중첩 깊이까지 추적해 추출합니다.
     */
    private static String extractEnclosingPdfDict(String raw, int pos) {
        int start = -1;
        int depth = 0;
        int minSearch = Math.max(0, pos - 1200);
        for (int i = pos - 1; i >= minSearch; i--) {
            char c = raw.charAt(i);
            if (c == '>' && i > 0 && raw.charAt(i - 1) == '>') {
                depth++;
                i--;
            } else if (c == '<' && i > 0 && raw.charAt(i - 1) == '<') {
                if (depth == 0) {
                    start = i - 1;
                    break;
                }
                depth--;
                i--;
            }
        }
        if (start < 0) return null;

        int end = -1;
        depth = 0;
        int maxSearch = Math.min(raw.length() - 1, start + 2400);
        for (int i = start; i < maxSearch; i++) {
            char c = raw.charAt(i);
            if (c == '<' && raw.charAt(i + 1) == '<') {
                depth++;
                i++;
            } else if (c == '>' && raw.charAt(i + 1) == '>') {
                depth--;
                i++;
                if (depth == 0) {
                    end = i + 1;
                    break;
                }
            }
        }
        if (end <= start) return null;
        return raw.substring(start, end);
    }

    /**
     * PDF 1.5+의 /Type /ObjStm (/FlateDecode) 스트림을 해제해 텍스트 버퍼에 추가합니다.
     */
    private static void appendDecompressedObjStreams(byte[] fileBytes, StringBuilder out) {
        try {
            String raw = out.toString();
            int idx = 0;
            int count = 0;
            while (count < 40 && (idx = raw.indexOf("/ObjStm", idx)) >= 0) {
                int sIdx = raw.indexOf("stream", idx);
                if (sIdx < 0 || sIdx - idx > 500) {
                    idx += 7;
                    continue;
                }
                int dataStart = sIdx + 6;
                if (dataStart < fileBytes.length && fileBytes[dataStart] == '\r') dataStart++;
                if (dataStart < fileBytes.length && fileBytes[dataStart] == '\n') dataStart++;

                int endIdx = raw.indexOf("endstream", dataStart);
                if (endIdx < 0 || endIdx <= dataStart) {
                    idx = dataStart;
                    continue;
                }
                int dataLen = endIdx - dataStart;
                if (dataLen > 0 && dataLen < 2 * 1024 * 1024) {
                    Inflater inflater = new Inflater();
                    try {
                        inflater.setInput(fileBytes, dataStart, dataLen);
                        ByteArrayOutputStream dec = new ByteArrayOutputStream();
                        byte[] buf = new byte[16384];
                        int maxOut = 2 * 1024 * 1024;
                        while (!inflater.finished() && dec.size() < maxOut) {
                            int r = inflater.inflate(buf);
                            if (r <= 0) break;
                            dec.write(buf, 0, r);
                        }
                        if (dec.size() > 0) {
                            out.append('\n').append(new String(dec.toByteArray(), StandardCharsets.ISO_8859_1));
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        inflater.end();
                    }
                }
                count++;
                idx = endIdx + 9;
            }
        } catch (Throwable ignored) {}
    }

    private static String unescapePdfString(String s) {
        if (s == null) return null;
        return s.replace("\\(", "(").replace("\\)", ")").replace("\\\\", "\\").trim();
    }

    /**
     * 원본 PDF에서 탭 위치 주변을 4K 고해상도로 크롭 렌더링하여 QR코드를 디코딩합니다.
     * (작은 문제집 QR코드도 정확히 읽도록 좁은 범위 + 넓은 범위 2단계 시도)
     */
    private String decodeQrFromPdfSource(Activity activity, PdfPicker.PdfSourceRef ref, float u, float v) {
        ParcelFileDescriptor pfd = null;
        PdfRenderer renderer = null;
        PdfRenderer.Page pdfPage = null;
        try {
            pfd = openPdfDescriptor(activity, ref);
            if (pfd == null) return null;
            renderer = new PdfRenderer(pfd);
            if (ref.pageIndex < 0 || ref.pageIndex >= renderer.getPageCount()) return null;
            pdfPage = renderer.openPage(ref.pageIndex);

            int pw = pdfPage.getWidth();
            int ph = pdfPage.getHeight();
            float cx = u * pw;
            float cy = v * ph;

            // 1차: 정밀 크롭(폭 14% -> 640px), 2차: 여유 크롭(폭 26% -> 768px)
            float[] radiiRatios = new float[] { 0.075f, 0.14f };
            int[] outSizes = new int[] { 640, 768 };

            for (int pass = 0; pass < radiiRatios.length; pass++) {
                float half = Math.max(36f, pw * radiiRatios[pass]);
                float left = Math.max(0f, cx - half);
                float top = Math.max(0f, cy - half);
                float right = Math.min(pw, cx + half);
                float bottom = Math.min(ph, cy + half);
                float cw = Math.max(1f, right - left);
                float ch = Math.max(1f, bottom - top);

                int outW = outSizes[pass];
                int outH = Math.max(1, Math.round(outW * (ch / cw)));
                Bitmap bmp = null;
                try {
                    bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bmp);
                    canvas.drawColor(Color.WHITE);
                    Matrix m = new Matrix();
                    m.postTranslate(-left, -top);
                    m.postScale(outW / cw, outH / ch);
                    pdfPage.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

                    String decoded = decodeQrBitmap(bmp);
                    if (decoded != null) return decoded;
                } finally {
                    if (bmp != null && !bmp.isRecycled()) bmp.recycle();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "decodeQrFromPdfSource error: " + t.getMessage());
        } finally {
            try { if (pdfPage != null) pdfPage.close(); } catch (Throwable ignored) {}
            try { if (renderer != null) renderer.close(); } catch (Throwable ignored) {}
            try { if (pfd != null) pfd.close(); } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * 올가미로 오려낸 이미지나 일반 이미지 파일에서 탭 위치 주변을 크롭해 QR코드를 디코딩합니다.
     */
    private String decodeQrFromImageFile(String imagePath, float u, float v) {
        BitmapRegionDecoder decoder = null;
        try {
            File f = new File(imagePath);
            if (!f.exists() || !f.canRead()) return null;

            BitmapFactory.Options boundsOpts = new BitmapFactory.Options();
            boundsOpts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(imagePath, boundsOpts);
            int iw = boundsOpts.outWidth;
            int ih = boundsOpts.outHeight;
            if (iw <= 0 || ih <= 0) return null;

            decoder = BitmapRegionDecoder.newInstance(imagePath, false);
            int cx = Math.round(u * iw);
            int cy = Math.round(v * ih);

            float[] radii = new float[] { 0.14f, 0.28f };
            for (float r : radii) {
                int half = Math.max(80, Math.round(Math.min(iw, ih) * r));
                int l = Math.max(0, cx - half);
                int t = Math.max(0, cy - half);
                int ri = Math.min(iw, cx + half);
                int b = Math.min(ih, cy + half);
                if (ri - l < 24 || b - t < 24) continue;

                Bitmap crop = null;
                try {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    int maxDim = Math.max(ri - l, b - t);
                    if (maxDim > 1024) {
                        opts.inSampleSize = 2;
                    }
                    crop = decoder.decodeRegion(new Rect(l, t, ri, b), opts);
                    if (crop != null) {
                        String decoded = decodeQrBitmap(crop);
                        if (decoded != null) return decoded;
                    }
                } finally {
                    if (crop != null && !crop.isRecycled()) crop.recycle();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "decodeQrFromImageFile error: " + t.getMessage());
        } finally {
            try { if (decoder != null) decoder.recycle(); } catch (Throwable ignored) {}
        }
        return null;
    }

    private String decodeQrBitmap(Bitmap bmp) {
        if (bmp == null || bmp.isRecycled()) return null;
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);

        RGBLuminanceSource source = new RGBLuminanceSource(w, h, pixels);
        Map<DecodeHintType, Object> hints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");

        QRCodeReader reader = new QRCodeReader();
        try {
            Result res = reader.decode(new BinaryBitmap(new HybridBinarizer(source)), hints);
            if (res != null && res.getText() != null && !res.getText().isEmpty()) {
                return res.getText();
            }
        } catch (Throwable ignored) {
            reader.reset();
        }
        try {
            Result res = reader.decode(new BinaryBitmap(new GlobalHistogramBinarizer(source)), hints);
            if (res != null && res.getText() != null && !res.getText().isEmpty()) {
                return res.getText();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private ParcelFileDescriptor openPdfDescriptor(Activity activity, PdfPicker.PdfSourceRef ref) {
        try {
            if (ref.filePath != null && new File(ref.filePath).exists()) {
                return ParcelFileDescriptor.open(new File(ref.filePath), ParcelFileDescriptor.MODE_READ_ONLY);
            }
            if (ref.uri != null) {
                return activity.getContentResolver().openFileDescriptor(ref.uri, "r");
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void showLinkConfirmDialog(final Activity activity, final String content, boolean fromQr) {
        if (activity == null || activity.isFinishing()) return;
        try {
            final boolean isUrl = content.startsWith("http://") || content.startsWith("https://")
                    || content.startsWith("www.") || content.startsWith("mailto:") || content.startsWith("tel:");
            final String launchUrl = content.startsWith("www.") ? ("https://" + content) : content;

            AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            if (isUrl) {
                builder.setTitle(fromQr ? "📱 QR코드 링크 연결" : "🔗 PDF 링크 연결");
                builder.setMessage("해당 링크로 연결할까요?\n\n" + content);
                builder.setPositiveButton("연결하기", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(launchUrl));
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            activity.startActivity(intent);
                        } catch (Throwable t) {
                            android.widget.Toast.makeText(activity, "링크를 열 수 없습니다: " + t.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
                        }
                    }
                });
                builder.setNegativeButton("취소", null);
            } else {
                builder.setTitle("📱 QR코드 내용");
                builder.setMessage(content);
                builder.setPositiveButton("복사", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(ClipData.newPlainText("QR", content));
                                android.widget.Toast.makeText(activity, "클립보드에 복사되었습니다.", android.widget.Toast.LENGTH_SHORT).show();
                            }
                        } catch (Throwable ignored) {}
                    }
                });
                builder.setNegativeButton("닫기", null);
            }
            builder.show();
        } catch (Throwable t) {
            Log.e(TAG, "showLinkConfirmDialog failed", t);
        }
    }
}

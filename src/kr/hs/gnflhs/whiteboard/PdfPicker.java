package kr.hs.gnflhs.whiteboard;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.*;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GNFL NOTE V1.2 - PDF 다중 페이지 선택 팝업 및 페이지별 도형 레이어 삽입 컴포넌트
 * 
 * - 내부 저장소 PDF: 즉시 페이지 선택 팝업 표시
 * - USB 메모리 PDF (EACCES 권한 제한 시): 안드로이드 공식 SAF 문서 선택기(ACTION_OPEN_DOCUMENT)를
 *   자동으로 열어 정식 읽기 권한(ParcelFileDescriptor)을 받아 대용량 PDF도 0초 만에 즉시 오픈!
 * - 좌측 작은 페이지 번호 아이콘 그리드(복수 선택) + 우측 고화질 미리보기
 * - 확인 시 선택된 페이지들을 순정 도형 레이어(presenter.V0)에 페이지별로 순서대로 배치
 */
public class PdfPicker {
    private static final String TAG = "PdfPicker";
    private static final int REQUEST_CODE_SAF_PDF = 0x50DF;

    // 현재 불러온 PDF 정보 (툴바 '목록' 버튼에서 즉시 페이지 선택창을 다시 열기 위해 유지)
    private static Uri sActivePdfUri = null;
    private static String sActivePdfPath = null;
    private static String sActivePdfName = null;
    private static final Set<Integer> sInsertedPages = new HashSet<Integer>();

    // PDF 렌더링 해상도 프리셋: 0 = FHD(빠름), 1 = QHD(기본), 2 = 4K(초고화질)
    private static int sSelectedResPreset = 1;

    // 칠판에 삽입된 PDF 이미지 파일명 -> 원본 PDF 벡터 소스 매핑 (올가미 4K 네이티브 벡터 재렌더링용)
    public static final class PdfSourceRef {
        public final Uri uri;
        public final String filePath;
        public final int pageIndex;

        public PdfSourceRef(Uri uri, String filePath, int pageIndex) {
            this.uri = uri;
            this.filePath = filePath;
            this.pageIndex = pageIndex;
        }
    }

    private static final Map<String, PdfSourceRef> sInsertedPdfSources = new HashMap<String, PdfSourceRef>();

    public static void registerInsertedPdfImage(String imagePath, Uri uri, String filePath, int pageIndex) {
        if (imagePath == null || imagePath.isEmpty()) return;
        PdfSourceRef ref = new PdfSourceRef(uri, filePath, pageIndex);
        synchronized (sInsertedPdfSources) {
            sInsertedPdfSources.put(imagePath, ref);
            String baseName = new File(imagePath).getName();
            if (baseName != null && !baseName.isEmpty()) {
                sInsertedPdfSources.put(baseName, ref);
            }
        }
    }

    public static PdfSourceRef findPdfSourceForPath(String imagePath) {
        if (imagePath == null || imagePath.isEmpty()) return null;
        synchronized (sInsertedPdfSources) {
            PdfSourceRef ref = sInsertedPdfSources.get(imagePath);
            if (ref != null) return ref;
            String baseName = new File(imagePath).getName();
            if (baseName != null) {
                ref = sInsertedPdfSources.get(baseName);
                if (ref != null) return ref;
            }
            // 부분 일치 fallback (EasiNote가 캐시 사본 경로로 바꾼 경우)
            for (Map.Entry<String, PdfSourceRef> entry : sInsertedPdfSources.entrySet()) {
                String k = entry.getKey();
                if (k != null && k.startsWith("gnfl_pdf_") && imagePath.contains(k)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    public static boolean hasActivePdf() {
        return sActivePdfUri != null || (sActivePdfPath != null && !sActivePdfPath.isEmpty());
    }

    private static void setActivePdf(Activity activity, Uri uri, String filePath, String displayName) {
        boolean same = false;
        if (uri != null && uri.equals(sActivePdfUri)) same = true;
        else if (filePath != null && filePath.equals(sActivePdfPath)) same = true;

        if (!same) {
            sInsertedPages.clear();
        }
        sActivePdfUri = uri;
        sActivePdfPath = filePath;
        sActivePdfName = (displayName != null && !displayName.isEmpty()) ? displayName : "PDF 문서";
        updatePdfListButton(activity);
    }

    /**
     * 하단 좌측 툴바의 '목록' 버튼(explore_edu_toolbar_list)을 제어합니다:
     * - PDF를 불러오기 전에는 숨김(GONE)
     * - PDF를 불러온 뒤에는 '메뉴' 버튼 바로 옆에 표시(VISIBLE)하고, 클릭 시 현재 PDF의 페이지 선택창을 즉시 엽니다.
     */
    public static void updatePdfListButton(final Activity activity) {
        if (activity == null) return;
        try {
            View listBtn = ToolbarFitHelper.findViewByIdName(activity, "explore_edu_toolbar_list");
            if (listBtn == null) return;

            boolean show = hasActivePdf();
            int targetVis = show ? View.VISIBLE : View.GONE;
            boolean changed = (listBtn.getVisibility() != targetVis);
            if (changed) {
                listBtn.setVisibility(targetVis);
                if (listBtn.getParent() instanceof View) {
                    ((View) listBtn.getParent()).requestLayout();
                }
            }
            if (show) {
                listBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        reopenActivePdfPicker(activity);
                    }
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "updatePdfListButton failed", t);
        }
    }

    public static void reopenActivePdfPicker(final Activity activity) {
        if (activity == null || !hasActivePdf()) return;
        if (sActivePdfUri != null) {
            showPagePickerFromUri(activity, sActivePdfUri, sActivePdfName, true);
        } else if (sActivePdfPath != null) {
            showPagePickerFromCachedFile(activity, new File(sActivePdfPath), sActivePdfName, true);
        }
    }

    /**
     * PlainWhiteboardActivity.onActivityResult 에서 첫 줄에 호출되는 후크 함수
     */
    public static void onActivityResult(final Activity activity, final int requestCode, final int resultCode, final Intent data) {
        try {
            // 1. SAF 공식 문서 선택기(ACTION_OPEN_DOCUMENT) 결과 처리 (USB 메모리 포함 100% 접근 가능!)
            if (requestCode == REQUEST_CODE_SAF_PDF) {
                if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                    final Uri pdfUri = data.getData();
                    final String displayName = queryDisplayName(activity, pdfUri);
                    Log.i(TAG, "Opening SAF PDF URI: " + pdfUri + " (" + displayName + ")");
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showPagePickerFromUri(activity, pdfUri, displayName);
                        }
                    });
                }
                return;
            }

            // 2. 순정 EasiFinder 결과 처리
            if (resultCode == Activity.RESULT_OK && data != null) {
                // 2-a. content:// URI가 직접 넘어온 경우
                Uri uri = data.getData();
                if (uri != null && "content".equalsIgnoreCase(uri.getScheme())) {
                    final String name = queryDisplayName(activity, uri);
                    if (name.toLowerCase().endsWith(".pdf") || uri.toString().toLowerCase().contains("pdf")) {
                        final Uri targetUri = uri;
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                showPagePickerFromUri(activity, targetUri, name);
                            }
                        });
                        return;
                    }
                }

                // 2-b. 파일 경로 문자열로 넘어온 경우
                File pdfFile = resolvePdfFile(data);
                if (pdfFile != null) {
                    if (pdfFile.exists() && pdfFile.canRead()) {
                        Log.i(TAG, "Opening readable PDF directly: " + pdfFile.getAbsolutePath());
                        final File targetFile = pdfFile;
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                showPagePickerFromFile(activity, targetFile);
                            }
                        });
                        return;
                    } else {
                        // USB 드라이브(/storage/sdf1 등) 권한 제한(EACCES) -> SAF 문서 선택기 자동 실행!
                        Log.w(TAG, "PDF path not directly readable (USB EACCES), launching SAF picker: " + pdfFile.getAbsolutePath());
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                launchSafPdfPicker(activity);
                            }
                        });
                        return;
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in onActivityResult hook", t);
        }

        // Fallback: 순정 E0 호출 (이미지 등 비-PDF 파일)
        try {
            Method m = activity.getClass().getDeclaredMethod("E0", int.class, int.class, Intent.class);
            m.setAccessible(true);
            m.invoke(activity, requestCode, resultCode, data);
        } catch (Throwable t) {
            Log.e(TAG, "Error calling stock E0", t);
        }
    }

    /**
     * 안드로이드 공식 문서 선택기(com.android.documentsui)를 띄워 PDF 파일을 1번만 선택하도록 합니다.
     */
    public static void launchSafPdfPicker(Activity activity) {
        if (activity == null) return;
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/pdf");
            intent.setPackage("com.android.documentsui");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivityForResult(intent, REQUEST_CODE_SAF_PDF);
        } catch (Throwable t) {
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/pdf");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                activity.startActivityForResult(intent, REQUEST_CODE_SAF_PDF);
            } catch (Throwable t2) {
                Log.e(TAG, "Failed to launch SAF PDF picker", t2);
                ToolbarFitHelper.showTopToast(activity, "PDF 선택기를 열 수 없습니다: " + t2.getMessage(), true);
            }
        }
    }

    private static File resolvePdfFile(Intent data) {
        String[] extraKeys = new String[] { "path", "internalPath", "filePath", "file_path", "SELECT_RESULT", "data" };
        for (String key : extraKeys) {
            String p = data.getStringExtra(key);
            if (p != null && p.toLowerCase().endsWith(".pdf")) {
                return new File(p);
            }
        }
        try {
            Bundle extras = data.getExtras();
            if (extras != null) {
                for (String key : extras.keySet()) {
                    Object val = extras.get(key);
                    if (val instanceof String) {
                        String s = (String) val;
                        if (s.toLowerCase().endsWith(".pdf")) return new File(s);
                    } else if (val instanceof List) {
                        for (Object item : (List<?>) val) {
                            if (item instanceof String && ((String) item).toLowerCase().endsWith(".pdf")) {
                                return new File((String) item);
                            }
                        }
                    } else if (val instanceof String[]) {
                        for (String item : (String[]) val) {
                            if (item != null && item.toLowerCase().endsWith(".pdf")) return new File(item);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        Uri uri = data.getData();
        if (uri != null && "file".equalsIgnoreCase(uri.getScheme())) {
            String p = uri.getPath();
            if (p != null && p.toLowerCase().endsWith(".pdf")) return new File(p);
        }
        return null;
    }

    private static String queryDisplayName(Activity activity, Uri uri) {
        String name = "문서.pdf";
        Cursor cursor = null;
        try {
            cursor = activity.getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = cursor.getString(idx);
                    if (n != null && !n.isEmpty()) name = n;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) {
                try { cursor.close(); } catch (Throwable ignored) {}
            }
        }
        return name;
    }

    public static void showPagePickerFromFile(final Activity activity, final File pdfFile) {
        showPagePickerFromFile(activity, pdfFile, false);
    }

    public static void showPagePickerFromFile(final Activity activity, final File pdfFile, final boolean alwaysAddNewPage) {
        openPagePickerFromFile(activity, pdfFile, pdfFile != null ? pdfFile.getName() : "PDF 문서", alwaysAddNewPage, false);
    }

    public static void showPagePickerFromCachedFile(final Activity activity, final File pdfFile, final String displayName) {
        showPagePickerFromCachedFile(activity, pdfFile, displayName, false);
    }

    public static void showPagePickerFromCachedFile(final Activity activity, final File pdfFile, final String displayName, final boolean alwaysAddNewPage) {
        openPagePickerFromFile(activity, pdfFile, displayName, alwaysAddNewPage, true);
    }

    private static void openPagePickerFromFile(final Activity activity, final File pdfFile, final String displayName, final boolean alwaysAddNewPage, final boolean isFromCache) {
        try {
            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
            String title = (displayName != null && !displayName.isEmpty()) ? displayName : pdfFile.getName();
            setActivePdf(activity, null, pdfFile.getAbsolutePath(), title);
            new PagePickerDialog(activity, pfd, title, null, pdfFile.getAbsolutePath(), alwaysAddNewPage, isFromCache).show();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to open PDF file, trying SAF", t);
            launchSafPdfPicker(activity);
        }
    }

    public static void showPagePickerFromUri(final Activity activity, final Uri pdfUri, final String displayName) {
        showPagePickerFromUri(activity, pdfUri, displayName, false);
    }

    public static void showPagePickerFromUri(final Activity activity, final Uri pdfUri, final String displayName, final boolean alwaysAddNewPage) {
        try {
            ParcelFileDescriptor pfd = activity.getContentResolver().openFileDescriptor(pdfUri, "r");
            if (pfd == null) {
                ToolbarFitHelper.showTopToast(activity, "PDF 파일을 열 수 없습니다.", true);
                return;
            }
            setActivePdf(activity, pdfUri, null, displayName);
            new PagePickerDialog(activity, pfd, displayName, pdfUri, null, alwaysAddNewPage, false).show();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to open PDF from URI", t);
            ToolbarFitHelper.showTopToast(activity, "PDF 열기 실패: " + t.getMessage(), true);
        }
    }

    private static int dp(Context ctx, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics());
    }

    private static GradientDrawable createCardBg(int color, int radiusDp, Context ctx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(dp(ctx, radiusDp));
        return gd;
    }

    private static GradientDrawable createStrokeBg(int color, int strokeColor, int strokeDp, int radiusDp, Context ctx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setStroke(dp(ctx, strokeDp), strokeColor);
        gd.setCornerRadius(dp(ctx, radiusDp));
        return gd;
    }

    private static View findAddPageButton(Activity activity) {
        if (activity == null) return null;
        try {
            int resId = activity.getResources().getIdentifier("explore_edu_toolbar_add_page", "id", "com.seewo.easinote");
            if (resId == 0) {
                resId = activity.getResources().getIdentifier("explore_edu_toolbar_add_page", "id", activity.getPackageName());
            }
            if (resId != 0) {
                View v = activity.findViewById(resId);
                if (v != null) return v;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * PDF 다중 페이지 선택 팝업 다이얼로그 구현체
     */
    private static final class PagePickerDialog {
        private final Activity activity;
        private final String displayName;
        private final Uri sourceUri;
        private final String sourceFilePath;
        private final boolean alwaysAddNewPage;
        private final boolean isFromCache;
        private final Dialog dialog;
        private ParcelFileDescriptor pfd;
        private PdfRenderer renderer;
        private final int totalPages;

        private int previewIndex = 0;
        private boolean multiSelectMode = false;
        private final Set<Integer> checkedPages = new HashSet<Integer>();
        private boolean isInserting = false;

        private HandlerThread renderThread;
        private Handler renderHandler;
        private final Handler uiHandler = new Handler(Looper.getMainLooper());

        private GridView gridView;
        private BaseAdapter gridAdapter;
        private ImageView previewImageView;
        private ProgressBar progressBar;
        private TextView totalCountText;
        private TextView modeHintText;
        private TextView indicatorText;
        private Button btnPrev;
        private Button btnNext;
        private Button btnConfirm;
        private Button btnResFhd;
        private Button btnResQhd;
        private Button btnRes4k;
        private CheckBox cbSaveToCache;

        public PagePickerDialog(Activity activity, ParcelFileDescriptor pfd, String displayName, Uri sourceUri, String sourceFilePath, boolean alwaysAddNewPage, boolean isFromCache) throws Exception {
            this.activity = activity;
            this.pfd = pfd;
            this.displayName = (displayName != null) ? displayName : "PDF 문서";
            this.sourceUri = sourceUri;
            this.sourceFilePath = sourceFilePath;
            this.alwaysAddNewPage = alwaysAddNewPage;
            this.isFromCache = isFromCache;
            this.renderer = new PdfRenderer(pfd);
            this.totalPages = renderer.getPageCount();

            if (totalPages > 0) {
                // 이 PDF에서 마지막으로 열었던 페이지 위치 복원
                int savedPage = PdfCacheManager.getLastPageIndex(activity, this.displayName, this.sourceFilePath);
                if (savedPage < 0) savedPage = 0;
                if (savedPage >= totalPages) savedPage = totalPages - 1;
                previewIndex = savedPage;
                checkedPages.add(savedPage);
            }

            this.renderThread = new HandlerThread("PdfRenderWorker");
            this.renderThread.start();
            this.renderHandler = new Handler(renderThread.getLooper());

            this.dialog = new Dialog(activity);
            this.dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            this.dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface d) {
                    if (!isInserting) {
                        cleanup();
                    }
                }
            });

            setupUI();
        }

        private void setupUI() {
            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            int dialogWidth = Math.min((int)(dm.widthPixels * 0.86f), dp(activity, 1020));
            int dialogHeight = Math.min((int)(dm.heightPixels * 0.86f), dp(activity, 700));

            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackground(createCardBg(Color.parseColor("#F8FAFC"), 16, activity));
            root.setPadding(dp(activity, 20), dp(activity, 16), dp(activity, 20), dp(activity, 16));

            // 1. Header bar
            LinearLayout header = new LinearLayout(activity);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(0, 0, 0, dp(activity, 12));

            TextView title = new TextView(activity);
            title.setText("PDF 페이지 선택 - " + displayName);
            title.setTextColor(Color.parseColor("#0F172A"));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            totalCountText = new TextView(activity);
            totalCountText.setTextColor(Color.parseColor("#2563EB"));
            totalCountText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            totalCountText.setTypeface(Typeface.DEFAULT_BOLD);
            header.addView(totalCountText);

            root.addView(header);

            // 2. Center Content
            LinearLayout contentLayout = new LinearLayout(activity);
            contentLayout.setOrientation(LinearLayout.HORIZONTAL);
            root.addView(contentLayout, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            // Left Pane (Small Page Icon Grid)
            LinearLayout leftPane = new LinearLayout(activity);
            leftPane.setOrientation(LinearLayout.VERTICAL);
            leftPane.setBackground(createStrokeBg(Color.parseColor("#FFFFFF"), Color.parseColor("#E2E8F0"), 1, 12, activity));
            leftPane.setPadding(dp(activity, 10), dp(activity, 10), dp(activity, 10), dp(activity, 10));
            LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(dp(activity, 310), ViewGroup.LayoutParams.MATCH_PARENT);
            leftLp.setMargins(0, 0, dp(activity, 16), 0);
            contentLayout.addView(leftPane, leftLp);

            LinearLayout leftTopBar = new LinearLayout(activity);
            leftTopBar.setOrientation(LinearLayout.HORIZONTAL);
            leftTopBar.setGravity(Gravity.CENTER_VERTICAL);
            leftTopBar.setPadding(0, 0, 0, dp(activity, 8));

            TextView leftLabel = new TextView(activity);
            leftLabel.setText("페이지 선택");
            leftLabel.setTextColor(Color.parseColor("#334155"));
            leftLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            leftLabel.setTypeface(Typeface.DEFAULT_BOLD);
            leftTopBar.addView(leftLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button btnAll = new Button(activity);
            btnAll.setText("전체");
            btnAll.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            btnAll.setTextColor(Color.parseColor("#2563EB"));
            btnAll.setPadding(0, 0, 0, 0);
            btnAll.setBackground(createStrokeBg(Color.parseColor("#EFF6FF"), Color.parseColor("#93C5FD"), 1, 6, activity));
            btnAll.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    multiSelectMode = true;
                    for (int i = 0; i < totalPages; i++) checkedPages.add(i);
                    refreshSelectionUI();
                }
            });
            LinearLayout.LayoutParams allLp = new LinearLayout.LayoutParams(dp(activity, 54), dp(activity, 30));
            allLp.setMargins(0, 0, dp(activity, 6), 0);
            leftTopBar.addView(btnAll, allLp);

            Button btnNone = new Button(activity);
            btnNone.setText("해제");
            btnNone.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            btnNone.setTextColor(Color.parseColor("#475569"));
            btnNone.setPadding(0, 0, 0, 0);
            btnNone.setBackground(createStrokeBg(Color.parseColor("#F8FAFC"), Color.parseColor("#CBD5E1"), 1, 6, activity));
            btnNone.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    multiSelectMode = false;
                    checkedPages.clear();
                    refreshSelectionUI();
                }
            });
            leftTopBar.addView(btnNone, new LinearLayout.LayoutParams(dp(activity, 54), dp(activity, 30)));
            leftPane.addView(leftTopBar);

            // 단일 선택 / 다중 선택(1초 길게 누름) 모드 안내 배지
            modeHintText = new TextView(activity);
            modeHintText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            modeHintText.setGravity(Gravity.CENTER);
            modeHintText.setPadding(dp(activity, 8), dp(activity, 5), dp(activity, 8), dp(activity, 5));
            modeHintText.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (multiSelectMode) {
                        multiSelectMode = false;
                        checkedPages.clear();
                        checkedPages.add(previewIndex);
                        refreshSelectionUI();
                        ToolbarFitHelper.showTopToast(activity, "단일 선택 모드로 전환되었습니다.");
                    }
                }
            });
            LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hintLp.setMargins(0, 0, 0, dp(activity, 8));
            leftPane.addView(modeHintText, hintLp);

            gridView = new GridView(activity);
            gridView.setNumColumns(4);
            gridView.setHorizontalSpacing(dp(activity, 8));
            gridView.setVerticalSpacing(dp(activity, 8));
            gridView.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
            gridView.setGravity(Gravity.CENTER);

            gridAdapter = new BaseAdapter() {
                @Override
                public int getCount() {
                    return totalPages;
                }

                @Override
                public Object getItem(int position) {
                    return position;
                }

                @Override
                public long getItemId(int position) {
                    return position;
                }

                @Override
                public View getView(final int position, View convertView, ViewGroup parent) {
                    TextView tv;
                    if (convertView instanceof TextView) {
                        tv = (TextView) convertView;
                    } else {
                        tv = new TextView(activity);
                        tv.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 48)));
                        tv.setGravity(Gravity.CENTER);
                        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                        tv.setTypeface(Typeface.DEFAULT_BOLD);
                    }

                    boolean isChecked = checkedPages.contains(position);
                    boolean isPreviewing = (position == previewIndex);
                    boolean isInserted = sInsertedPages.contains(position);

                    if (isChecked) {
                        tv.setText("✓ " + (position + 1));
                    } else if (isInserted) {
                        tv.setText((position + 1) + "•");
                    } else {
                        tv.setText(String.valueOf(position + 1));
                    }

                    if (isChecked && isPreviewing) {
                        tv.setBackground(createStrokeBg(Color.parseColor("#2563EB"), Color.parseColor("#1E3A8A"), 2, 8, activity));
                        tv.setTextColor(Color.WHITE);
                    } else if (isChecked) {
                        tv.setBackground(createCardBg(Color.parseColor("#3B82F6"), 8, activity));
                        tv.setTextColor(Color.WHITE);
                    } else if (isPreviewing) {
                        tv.setBackground(createStrokeBg(Color.parseColor("#EFF6FF"), Color.parseColor("#2563EB"), 2, 8, activity));
                        tv.setTextColor(Color.parseColor("#1D4ED8"));
                    } else if (isInserted) {
                        tv.setBackground(createStrokeBg(Color.parseColor("#ECFDF5"), Color.parseColor("#10B981"), 1, 8, activity));
                        tv.setTextColor(Color.parseColor("#047857"));
                    } else {
                        tv.setBackground(createStrokeBg(Color.parseColor("#F1F5F9"), Color.parseColor("#CBD5E1"), 1, 8, activity));
                        tv.setTextColor(Color.parseColor("#334155"));
                    }

                    // 기본 단일 선택(탭) + 정확히 1초(1000ms) 길게 누름 시 다중 선택 모드 진입
                    tv.setOnTouchListener(new View.OnTouchListener() {
                        private float downX, downY;
                        private boolean longPressTriggered = false;
                        private boolean moved = false;
                        private final Runnable longPressTask = new Runnable() {
                            @Override
                            public void run() {
                                longPressTriggered = true;
                                multiSelectMode = true;
                                tv.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                                checkedPages.add(position);
                                selectPreviewPage(position);
                                ToolbarFitHelper.showTopToast(activity, "다중 선택 모드: 여러 페이지를 탭하여 선택하세요");
                            }
                        };

                        @Override
                        public boolean onTouch(View v, MotionEvent event) {
                            int act = event.getActionMasked();
                            if (act == MotionEvent.ACTION_DOWN) {
                                downX = event.getX();
                                downY = event.getY();
                                longPressTriggered = false;
                                moved = false;
                                uiHandler.removeCallbacks(longPressTask);
                                uiHandler.postDelayed(longPressTask, 1000);
                                return true;
                            } else if (act == MotionEvent.ACTION_MOVE) {
                                if (Math.hypot(event.getX() - downX, event.getY() - downY) > dp(activity, 12)) {
                                    moved = true;
                                    uiHandler.removeCallbacks(longPressTask);
                                }
                                return false;
                            } else if (act == MotionEvent.ACTION_UP) {
                                uiHandler.removeCallbacks(longPressTask);
                                if (!longPressTriggered && !moved) {
                                    if (multiSelectMode) {
                                        if (checkedPages.contains(position)) {
                                            checkedPages.remove(position);
                                        } else {
                                            checkedPages.add(position);
                                        }
                                    } else {
                                        checkedPages.clear();
                                        checkedPages.add(position);
                                    }
                                    selectPreviewPage(position);
                                }
                                return true;
                            } else if (act == MotionEvent.ACTION_CANCEL) {
                                uiHandler.removeCallbacks(longPressTask);
                                return false;
                            }
                            return false;
                        }
                    });
                    return tv;
                }
            };
            gridView.setAdapter(gridAdapter);

            leftPane.addView(gridView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            // Right Pane (High-Res Preview)
            LinearLayout rightPane = new LinearLayout(activity);
            rightPane.setOrientation(LinearLayout.VERTICAL);
            rightPane.setBackground(createStrokeBg(Color.parseColor("#FFFFFF"), Color.parseColor("#E2E8F0"), 1, 12, activity));
            rightPane.setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12));
            contentLayout.addView(rightPane, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

            LinearLayout previewBar = new LinearLayout(activity);
            previewBar.setOrientation(LinearLayout.HORIZONTAL);
            previewBar.setGravity(Gravity.CENTER_VERTICAL);
            previewBar.setPadding(0, 0, 0, dp(activity, 8));

            btnPrev = new Button(activity);
            btnPrev.setText("◀ 이전");
            btnPrev.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            btnPrev.setTextColor(Color.parseColor("#1E293B"));
            btnPrev.setBackground(createStrokeBg(Color.parseColor("#F8FAFC"), Color.parseColor("#CBD5E1"), 1, 6, activity));
            btnPrev.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (previewIndex > 0) {
                        int target = previewIndex - 1;
                        if (!multiSelectMode) {
                            checkedPages.clear();
                            checkedPages.add(target);
                        }
                        selectPreviewPage(target);
                    }
                }
            });
            previewBar.addView(btnPrev, new LinearLayout.LayoutParams(dp(activity, 80), dp(activity, 38)));

            indicatorText = new TextView(activity);
            indicatorText.setGravity(Gravity.CENTER);
            indicatorText.setTextColor(Color.parseColor("#0F172A"));
            indicatorText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            indicatorText.setTypeface(Typeface.DEFAULT_BOLD);
            previewBar.addView(indicatorText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            btnNext = new Button(activity);
            btnNext.setText("다음 ▶");
            btnNext.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            btnNext.setTextColor(Color.parseColor("#1E293B"));
            btnNext.setBackground(createStrokeBg(Color.parseColor("#F8FAFC"), Color.parseColor("#CBD5E1"), 1, 6, activity));
            btnNext.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (previewIndex < totalPages - 1) {
                        int target = previewIndex + 1;
                        if (!multiSelectMode) {
                            checkedPages.clear();
                            checkedPages.add(target);
                        }
                        selectPreviewPage(target);
                    }
                }
            });
            previewBar.addView(btnNext, new LinearLayout.LayoutParams(dp(activity, 80), dp(activity, 38)));
            rightPane.addView(previewBar);

            FrameLayout previewFrame = new FrameLayout(activity);
            previewFrame.setBackground(createCardBg(Color.parseColor("#334155"), 8, activity));
            rightPane.addView(previewFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            previewImageView = new ImageView(activity);
            previewImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            previewFrame.addView(previewImageView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ));

            progressBar = new ProgressBar(activity);
            previewFrame.addView(progressBar, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            ));

            // 3. Bottom Action Bar (좌측: 해상도 선택 FHD / QHD / 4K, 우측: 취소 / 삽입)
            LinearLayout bottomBar = new LinearLayout(activity);
            bottomBar.setOrientation(LinearLayout.HORIZONTAL);
            bottomBar.setGravity(Gravity.CENTER_VERTICAL);
            bottomBar.setPadding(0, dp(activity, 14), 0, 0);

            LinearLayout resGroup = new LinearLayout(activity);
            resGroup.setOrientation(LinearLayout.HORIZONTAL);
            resGroup.setGravity(Gravity.CENTER_VERTICAL);

            TextView resLabel = new TextView(activity);
            resLabel.setText("화질:");
            resLabel.setTextColor(Color.parseColor("#334155"));
            resLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            resLabel.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams resLblLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            resLblLp.setMargins(0, 0, dp(activity, 8), 0);
            resGroup.addView(resLabel, resLblLp);

            btnResFhd = createResButton("FHD", 0);
            btnResQhd = createResButton("QHD", 1);
            btnRes4k  = createResButton("4K", 2);

            LinearLayout.LayoutParams resBtnLp = new LinearLayout.LayoutParams(dp(activity, 68), dp(activity, 38));
            resBtnLp.setMargins(0, 0, dp(activity, 6), 0);
            resGroup.addView(btnResFhd, resBtnLp);
            resGroup.addView(btnResQhd, resBtnLp);
            resGroup.addView(btnRes4k, new LinearLayout.LayoutParams(dp(activity, 68), dp(activity, 38)));

            // 새로 불러온 PDF인 경우에만 '캐시에 저장' 체크박스 표시 (기본값: 체크 해제 = 일회성 사용)
            if (!isFromCache) {
                cbSaveToCache = new CheckBox(activity);
                cbSaveToCache.setText("캐시에 저장");
                cbSaveToCache.setChecked(false);
                cbSaveToCache.setTextColor(Color.parseColor("#334155"));
                cbSaveToCache.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
                cbSaveToCache.setTypeface(Typeface.DEFAULT_BOLD);
                LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 38));
                cbLp.setMargins(dp(activity, 18), 0, 0, 0);
                resGroup.addView(cbSaveToCache, cbLp);
            }

            bottomBar.addView(resGroup, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            updateResolutionButtonsUI();

            Button btnCancel = new Button(activity);
            btnCancel.setText("취소");
            btnCancel.setTextColor(Color.parseColor("#475569"));
            btnCancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            btnCancel.setBackground(createStrokeBg(Color.parseColor("#FFFFFF"), Color.parseColor("#CBD5E1"), 1, 8, activity));
            btnCancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                }
            });
            LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(dp(activity, 110), dp(activity, 46));
            cancelLp.setMargins(0, 0, dp(activity, 12), 0);
            bottomBar.addView(btnCancel, cancelLp);

            btnConfirm = new Button(activity);
            btnConfirm.setTextColor(Color.WHITE);
            btnConfirm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            btnConfirm.setTypeface(Typeface.DEFAULT_BOLD);
            btnConfirm.setBackground(createCardBg(Color.parseColor("#2563EB"), 8, activity));
            btnConfirm.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmAndInsertSelectedPages();
                }
            });
            bottomBar.addView(btnConfirm, new LinearLayout.LayoutParams(dp(activity, 210), dp(activity, 46)));
            root.addView(bottomBar);

            dialog.setContentView(root);
            Window w = dialog.getWindow();
            if (w != null) {
                w.setLayout(dialogWidth, dialogHeight);
                w.setBackgroundDrawableResource(android.R.color.transparent);
            }

            selectPreviewPage(previewIndex);
            final int initScrollTarget = Math.max(0, previewIndex - 4);
            gridView.post(new Runnable() {
                @Override
                public void run() {
                    gridView.setSelection(initScrollTarget);
                }
            });
        }

        private Button createResButton(String text, final int preset) {
            Button b = new Button(activity);
            b.setText(text);
            b.setAllCaps(false);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setPadding(0, 0, 0, 0);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    sSelectedResPreset = preset;
                    updateResolutionButtonsUI();
                    if (preset == 2 && checkedPages.size() >= 6) {
                        ToolbarFitHelper.showTopToast(activity, "4K 화질은 다수 페이지 삽입 시 시간이 다소 걸릴 수 있습니다.");
                    }
                }
            });
            return b;
        }

        private void updateResolutionButtonsUI() {
            Button[] btns = new Button[] { btnResFhd, btnResQhd, btnRes4k };
            for (int i = 0; i < btns.length; i++) {
                if (btns[i] == null) continue;
                boolean active = (sSelectedResPreset == i);
                if (active) {
                    int activeColor = (i == 2) ? Color.parseColor("#0EA5E9") : Color.parseColor("#2563EB");
                    btns[i].setBackground(createCardBg(activeColor, 8, activity));
                    btns[i].setTextColor(Color.WHITE);
                } else {
                    btns[i].setBackground(createStrokeBg(Color.parseColor("#FFFFFF"), Color.parseColor("#CBD5E1"), 1, 8, activity));
                    btns[i].setTextColor(Color.parseColor("#475569"));
                }
            }
        }

        public void show() {
            if (dialog != null && !activity.isFinishing()) {
                dialog.show();
            }
        }

        private void refreshSelectionUI() {
            int cnt = checkedPages.size();
            totalCountText.setText("총 " + totalPages + "쪽 | 선택 " + cnt + "쪽");
            if (modeHintText != null) {
                if (multiSelectMode) {
                    modeHintText.setText("✓ 다중 선택 모드 (탭: 추가/해제 · 여기를 눌러 단일로)");
                    modeHintText.setTextColor(Color.WHITE);
                    modeHintText.setBackground(createCardBg(Color.parseColor("#2563EB"), 6, activity));
                } else {
                    modeHintText.setText("탭: 1쪽 선택  |  1초 길게 누름: 다중 선택");
                    modeHintText.setTextColor(Color.parseColor("#475569"));
                    modeHintText.setBackground(createCardBg(Color.parseColor("#F1F5F9"), 6, activity));
                }
            }
            String checkBadge = checkedPages.contains(previewIndex) ? " (선택됨 ✓)" : "";
            indicatorText.setText((previewIndex + 1) + " / " + totalPages + " 쪽" + checkBadge);
            btnConfirm.setText(cnt > 0 ? ("선택한 " + cnt + "쪽 페이지별 삽입") : "페이지를 선택하세요");
            btnConfirm.setEnabled(cnt > 0);
            btnConfirm.setAlpha(cnt > 0 ? 1f : 0.45f);
            gridAdapter.notifyDataSetChanged();
        }

        private void selectPreviewPage(int index) {
            if (index < 0 || index >= totalPages) return;
            this.previewIndex = index;
            PdfCacheManager.saveLastPageIndex(activity, displayName, sourceFilePath, index);

            btnPrev.setEnabled(previewIndex > 0);
            btnNext.setEnabled(previewIndex < totalPages - 1);
            btnPrev.setAlpha(previewIndex > 0 ? 1f : 0.4f);
            btnNext.setAlpha(previewIndex < totalPages - 1 ? 1f : 0.4f);

            refreshSelectionUI();
            gridView.smoothScrollToPosition(previewIndex);
            renderPreviewAsync(previewIndex);
        }

        private void renderPreviewAsync(final int page) {
            progressBar.setVisibility(View.VISIBLE);
            renderHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (renderer == null) return;
                        PdfRenderer.Page p = renderer.openPage(page);
                        int pw = p.getWidth();
                        int ph = p.getHeight();
                        float scale = Math.min(1200f / pw, 900f / ph);
                        int bw = Math.max(1, Math.round(pw * scale));
                        int bh = Math.max(1, Math.round(ph * scale));

                        final Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                        Canvas c = new Canvas(bmp);
                        c.drawColor(Color.WHITE);
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        p.close();

                        uiHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                progressBar.setVisibility(View.GONE);
                                if (previewIndex == page) {
                                    previewImageView.setImageBitmap(bmp);
                                } else {
                                    bmp.recycle();
                                }
                            }
                        });
                    } catch (Throwable t) {
                        Log.e(TAG, "Failed to render preview page " + page, t);
                        uiHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                progressBar.setVisibility(View.GONE);
                            }
                        });
                    }
                }
            });
        }

        private static final class RenderedPdfPage {
            final String pngPath;
            final String originPdfPath;
            final int pageIndex;
            final int pageWidth;
            final int pageHeight;
            final int dominantColor;

            RenderedPdfPage(String pngPath, String originPdfPath, int pageIndex, int pageWidth, int pageHeight, int dominantColor) {
                this.pngPath = pngPath;
                this.originPdfPath = originPdfPath;
                this.pageIndex = pageIndex;
                this.pageWidth = pageWidth;
                this.pageHeight = pageHeight;
                this.dominantColor = dominantColor;
            }
        }

        private String ensureLocalOriginPdf(File cacheDir, long stamp) {
            if (sourceFilePath != null) {
                File f = new File(sourceFilePath);
                if (f.exists() && f.canRead()) {
                    return sourceFilePath;
                }
            }
            if (sourceUri != null) {
                File localPdf = new File(cacheDir, "gnfl_pdf_origin_" + stamp + ".pdf");
                java.io.InputStream is = null;
                FileOutputStream fos = null;
                try {
                    is = activity.getContentResolver().openInputStream(sourceUri);
                    if (is != null) {
                        fos = new FileOutputStream(localPdf);
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = is.read(buf)) > 0) {
                            fos.write(buf, 0, n);
                        }
                        fos.flush();
                        return localPdf.getAbsolutePath();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to copy origin PDF to cache", t);
                } finally {
                    try { if (fos != null) fos.close(); } catch (Throwable ignored) {}
                    try { if (is != null) is.close(); } catch (Throwable ignored) {}
                }
            }
            return null;
        }

        private void confirmAndInsertSelectedPages() {
            if (checkedPages.isEmpty() || isInserting) return;
            isInserting = true;

            final List<Integer> sortedPages = new ArrayList<Integer>(checkedPages);
            Collections.sort(sortedPages);
            // 사용자가 '캐시에 저장'을 체크한 경우에만 내부 캐시에 보관 (체크 해제 시 일회성 사용)
            if (!isFromCache && cbSaveToCache != null && cbSaveToCache.isChecked()) {
                if (sourceUri != null) {
                    PdfCacheManager.cacheFromUriAsync(activity, sourceUri, displayName, null);
                } else if (sourceFilePath != null) {
                    PdfCacheManager.cacheFromFileAsync(activity, new File(sourceFilePath), displayName, null);
                }
            }
            // 마지막 삽입 페이지 위치를 이 PDF의 최근 페이지로 기억 (캐시에 있는 경우만 디스크 반영)
            PdfCacheManager.saveLastPageIndex(activity, displayName, sourceFilePath, sortedPages.get(sortedPages.size() - 1));

            ToolbarFitHelper.showTopToast(activity, "선택한 " + sortedPages.size() + "개 페이지를 배경으로 배치하는 중...");
            dialog.dismiss();

            renderHandler.post(new Runnable() {
                @Override
                public void run() {
                    final List<RenderedPdfPage> renderedList = new ArrayList<RenderedPdfPage>();
                    try {
                        File cacheDir = activity.getCacheDir();
                        long stamp = System.currentTimeMillis();

                        for (int idx = 0; idx < sortedPages.size(); idx++) {
                            int pageIdx = sortedPages.get(idx);
                            PdfRenderer.Page p = renderer.openPage(pageIdx);
                            int pw = p.getWidth();
                            int ph = p.getHeight();
                            // 사용자가 선택한 해상도 프리셋 적용 (0=FHD, 1=QHD, 2=4K)
                            float maxW, maxH;
                            int jpegQ;
                            long maxPixels;
                            if (sSelectedResPreset == 0) {
                                // FHD (빠름)
                                maxW = 1440f; maxH = 2040f; jpegQ = 90; maxPixels = 3_200_000L;
                            } else if (sSelectedResPreset == 2) {
                                // 4K (초고화질, 32비트 메모리 안전 상한 14.5MP)
                                maxW = 3840f; maxH = 4520f; jpegQ = 94; maxPixels = 14_500_000L;
                            } else {
                                // QHD (기본, 1440p급)
                                maxW = 2048f; maxH = 2900f; jpegQ = 92; maxPixels = 6_200_000L;
                            }
                            float scale = Math.min(maxW / pw, maxH / ph);
                            int bw = Math.max(1, Math.round(pw * scale));
                            int bh = Math.max(1, Math.round(ph * scale));
                            if ((long) bw * (long) bh > maxPixels) {
                                float shrink = (float) Math.sqrt((double) maxPixels / ((double) bw * (double) bh));
                                bw = Math.max(1, Math.round(bw * shrink));
                                bh = Math.max(1, Math.round(bh * shrink));
                            }

                            Bitmap fullBmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                            Canvas c = new Canvas(fullBmp);
                            c.drawColor(Color.WHITE);
                            p.render(fullBmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            p.close();

                            // 페이지에서 가장 많이 사용된 색(최빈색) 추출 -> 해당 칠판 페이지의 무한 배경색으로 사용
                            int dominantColor = LassoCaptureManager.extractDominantColor(fullBmp);

                            File outFile = new File(cacheDir, "gnfl_pdf_" + stamp + "_p" + (pageIdx + 1) + ".jpg");
                            FileOutputStream fos = null;
                            try {
                                fos = new FileOutputStream(outFile);
                                fullBmp.compress(Bitmap.CompressFormat.JPEG, jpegQ, fos);
                                fos.flush();
                            } finally {
                                try { if (fos != null) fos.close(); } catch (Throwable ignored) {}
                                if (!fullBmp.isRecycled()) fullBmp.recycle();
                            }
                            Log.i(TAG, String.format("Rendered PDF page %d at %dx%d (preset=%d, bg=#%06X)",
                                    (pageIdx + 1), bw, bh, sSelectedResPreset, (dominantColor & 0xFFFFFF)));

                            renderedList.add(new RenderedPdfPage(
                                    outFile.getAbsolutePath(), null, pageIdx, bw, bh, dominantColor
                            ));
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "Failed to render selected PDF pages", t);
                    } finally {
                        cleanup();
                    }

                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (renderedList.isEmpty()) {
                                ToolbarFitHelper.showTopToast(activity, "PDF 페이지 변환에 실패했습니다.", true);
                            } else {
                                insertPagesSequentially(renderedList, 0);
                            }
                        }
                    });
                }
            });
        }

        private void insertPagesSequentially(final List<RenderedPdfPage> pages, final int index) {
            if (index >= pages.size()) {
                ToolbarFitHelper.showTopToast(activity, "총 " + pages.size() + "개 페이지가 배경으로 배치되었습니다.");
                // 마지막 페이지 V0 렌더링 완료 후(850ms) 바로 스크롤/확대할 수 있게 '이동' 툴 자동 선택
                uiHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            View moveBtn = ToolbarFitHelper.findViewByIdName(activity, "explore_edu_toolbar_move");
                            if (moveBtn != null && !moveBtn.isSelected()) {
                                moveBtn.performClick();
                                Log.i(TAG, "Auto-selected Move tool after PDF insertion");
                            }
                            QuickColorBarManager.getInstance().syncWithCurrentPage(activity);
                        } catch (Throwable t) {
                            Log.w(TAG, "Auto-select Move tool warning", t);
                        }
                    }
                }, 850);
                return;
            }

            final long nextDelayMs = (sSelectedResPreset == 2) ? 680L : 520L;
            // 모든 PDF 페이지는 기존 페이지와 겹치지 않도록 무조건 다음 새 페이지를 생성해 삽입
            View addPageBtn = findAddPageButton(activity);
            if (addPageBtn != null) {
                addPageBtn.performClick();
            }
            uiHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    sInsertedPages.add(pages.get(index).pageIndex);
                    insertPdfPageAsBackground(pages.get(index));
                    uiHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            insertPagesSequentially(pages, index + 1);
                        }
                    }, nextDelayMs);
                }
            }, 420);
        }

        private void insertPdfPageAsBackground(final RenderedPdfPage page) {
            try {
                Field fPres = activity.getClass().getDeclaredField("j");
                fPres.setAccessible(true);
                Object presenter = fPres.get(activity);
                if (presenter == null) return;

                // 1. 해당 페이지의 무한 캔버스 전체 배경색을 PDF 페이지의 최빈색으로 자동 설정
                LassoCaptureManager.applyInfinitePageBackgroundColor(activity, page.dominantColor);

                // 2. 올가미 4K 벡터 재렌더링을 위해 원본 PDF 페이지 소스 등록
                registerInsertedPdfImage(page.pngPath, sourceUri, sourceFilePath, page.pageIndex);

                // 3. 순정 이미지 삽입(presenter.V0)으로 100% 확실하게 칠판에 추가
                Method m = presenter.getClass().getDeclaredMethod("V0", String.class);
                m.setAccessible(true);
                m.invoke(presenter, page.pngPath);
                Log.i(TAG, "Inserted PDF page via presenter.V0: " + page.pngPath);

                // 2. 삽입 직후 방금 추가된 ImageShape를 기본 배율(100%) 가로폭 맞춤(92%)으로 배치
                uiHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        fitLastInsertedImageToWidth(page);
                        QuickColorBarManager.getInstance().syncWithCurrentPage(activity);
                    }
                }, 160);
            } catch (Throwable t) {
                Log.e(TAG, "Failed to insert PDF page via V0: " + page.pngPath, t);
            }
        }

        private void fitLastInsertedImageToWidth(RenderedPdfPage page) {
            try {
                Field fWb = activity.getClass().getDeclaredField("c");
                fWb.setAccessible(true);
                View wbView = (View) fWb.get(activity);
                if (wbView == null || wbView.getWidth() <= 0) return;

                int wbW = wbView.getWidth();
                Field fR3 = activity.getClass().getDeclaredField("o");
                fR3.setAccessible(true);
                Object r3 = fR3.get(activity);
                if (r3 == null) return;

                Method mGetImg = r3.getClass().getDeclaredMethod("c");
                mGetImg.setAccessible(true);
                Object imgShape = mGetImg.invoke(r3);
                if (imgShape == null) return;

                float targetW = wbW * 0.92f;
                float ratio = (page.pageWidth > 0) ? ((float) page.pageHeight / page.pageWidth) : 1.414f;
                float targetH = targetW * ratio;
                float left = (wbW - targetW) * 0.5f;
                float top = 24f;
                android.graphics.RectF dstRect = new android.graphics.RectF(left, top, left + targetW, top + targetH);

                Method mGetMat = imgShape.getClass().getMethod("getMatrix");
                android.graphics.Matrix mat = (android.graphics.Matrix) mGetMat.invoke(imgShape);
                if (mat != null) {
                    mat.setRectToRect(
                            new android.graphics.RectF(0f, 0f, page.pageWidth, page.pageHeight),
                            dstRect,
                            android.graphics.Matrix.ScaleToFit.FILL
                    );
                }

                try {
                    Method mReset = imgShape.getClass().getMethod("resetSize", android.graphics.RectF.class);
                    mReset.invoke(imgShape, dstRect);
                } catch (Throwable ignored) {}

                wbView.invalidate();
                wbView.postInvalidate();
                Log.i(TAG, "Fitted PDF page to width (" + (int) targetW + "x" + (int) targetH + ")");
            } catch (Throwable t) {
                Log.w(TAG, "fitLastInsertedImageToWidth skipped: " + t.getMessage());
            }
        }

        private void cleanup() {
            try {
                if (renderer != null) {
                    renderer.close();
                    renderer = null;
                }
            } catch (Throwable ignored) {}
            try {
                if (pfd != null) {
                    pfd.close();
                    pfd = null;
                }
            } catch (Throwable ignored) {}
            try {
                if (renderThread != null) {
                    renderThread.quitSafely();
                    renderThread = null;
                }
            } catch (Throwable ignored) {}
        }
    }
}

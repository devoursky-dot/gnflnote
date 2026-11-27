package kr.hs.gnflhs.gnflpdf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/**
 * GNFLPDF — 완전 독립형 초고속 세로 스크롤 PDF 판서 앱 (GNFLNOTE 수준 툴바 + 순정 0.21ms 하드웨어 가속)
 */
public class MainActivity extends Activity {
    private static final String TAG = "GNFLPDF";
    private static final int REQ_OPEN_PDF = 2001;
    private static final int REQ_STORAGE_PERM = 2002;

    private static final int[] PALETTE = new int[] {
            0xFF111827, // 검정
            0xFFDC2626, // 빨강
            0xFFEA580C, // 주황
            0xFF15803D, // 초록
            0xFF1D4ED8, // 파랑
            0xFF6D28D9, // 보라
            0xFFDB2777  // 분홍
    };
    private static final float[] PEN_WIDTH_PRESETS = new float[] { 4f, 8f, 12f, 18f, 26f };
    private static final float[] HL_WIDTH_PRESETS = new float[] { 20f, 28f, 38f, 50f, 64f };
    private static final float PEN_MIN_PX = 3f, PEN_MAX_PX = 32f;
    private static final float HL_MIN_PX = 16f, HL_MAX_PX = 72f;
    private static final float[] ERASER_PRESETS = new float[] { 26f, 42f, 72f };
    private static final String[] ERASER_LABELS = new String[] { "소", "중", "대" };

    private static final int[] MARGIN_BG_COLORS = new int[] {
            0xFFF1F5F9, // 연회색 (기본)
            0xFFFFFFFF, // 순백
            0xFFFAF8F0, // 연미색
            0xFF1E293B  // 다크
    };
    private static final String[] MARGIN_BG_NAMES = new String[] {
            "밝은 연회색 (기본)", "순백 화이트", "따뜻한 연미색", "다크 칠판"
    };

    private HardwareInkAccelerator mAccelerator;
    private ScrollPdfSurfaceView mSurfaceView;
    private LinearLayout mWelcomeOverlay;
    private LinearLayout mBottomToolbar;
    private PopupWindow mActivePopup;

    private int mSelectedColorIdx = 0; // -1이면 커스텀 색상 선택됨
    private int mCustomColor = 0xFF0891B2; // 기본 청록 커스텀 슬롯
    private boolean mHasCustomColor = false;
    private float mCurrentPenWidthPx = 8f;
    private float mCurrentHlWidthPx = 36f;
    private int mEraserPresetIdx = 1; // 기본 중(42px)
    private int mMarginBgIdx = 0;
    private boolean mHasPdfLoaded = false;

    // 아이콘 + 라벨 툴바 버튼
    private ToolItem mBtnMenu, mBtnPdfOpen, mBtnLasso;
    private ToolItem mBtnSelect, mBtnPen, mBtnHighlighter, mBtnEraser, mBtnClean, mBtnMove;
    private ToolItem mBtnFitW, mBtnFitP, mBtnUndo, mBtnRedo;
    private ToolItem mBtnPre, mBtnNext;
    private TextView mTxtPageNum;
    private final View[] mColorDots = new View[PALETTE.length];
    private TextView mCustomColorBtn;

    private static class ToolItem {
        final LinearLayout container;
        final ImageView icon;
        final TextView label;

        ToolItem(LinearLayout container, ImageView icon, TextView label) {
            this.container = container;
            this.icon = icon;
            this.label = label;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        mAccelerator = new HardwareInkAccelerator();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(MARGIN_BG_COLORS[mMarginBgIdx]);

        mSurfaceView = new ScrollPdfSurfaceView(this);
        mSurfaceView.setHardwareAccelerator(mAccelerator);
        mSurfaceView.setPenColor(getActivePenColor());
        mSurfaceView.setPenWidthPx(mCurrentPenWidthPx);
        mSurfaceView.setHighlighterWidthPx(mCurrentHlWidthPx);
        mSurfaceView.setEraserBaseRadiusPx(ERASER_PRESETS[mEraserPresetIdx]);
        mSurfaceView.setHighlighter(false);
        mSurfaceView.setSmartPenEnabled(true);
        mSurfaceView.setToolMode(ScrollPdfSurfaceView.ToolMode.MOVE);
        mSurfaceView.setHostCallback(new ScrollPdfSurfaceView.HostCallback() {
            @Override
            public void onFocusModeChanged(boolean active) {
                refreshToolbarVisuals();
            }

            @Override
            public void onPageChanged(int currentPage, int totalPages) {
                updatePageNavUI(currentPage, totalPages);
                if (mSurfaceView != null) {
                    PdfCacheManager.saveLastPageIndex(
                            MainActivity.this, mSurfaceView.getDisplayName(),
                            mSurfaceView.getLocalPdfPath(), currentPage);
                }
            }

            @Override
            public void onToolAutoChanged(ScrollPdfSurfaceView.ToolMode newMode) {
                refreshToolbarVisuals();
            }

            @Override
            public void onHistoryChanged(boolean canUndo, boolean canRedo) {
                updateUndoRedoIcons(canUndo, canRedo);
            }
        });
        root.addView(mSurfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mWelcomeOverlay = buildWelcomeOverlay();
        root.addView(mWelcomeOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mBottomToolbar = buildBottomToolbar();
        FrameLayout.LayoutParams tbLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        tbLp.setMargins(dp(14), 0, dp(14), dp(10));
        root.addView(mBottomToolbar, tbLp);

        setContentView(root);
        hideSystemUi();
        requestStoragePermissionIfNeeded();

        mSurfaceView.post(new Runnable() {
            @Override
            public void run() {
                initHardwareAccelerator();
            }
        });

        refreshToolbarVisuals();
        updateUndoRedoIcons(false, false);
        handleIncomingIntent(getIntent());

        // 앱 실행 2.5초 후 백그라운드에서 GitHub 최신 버전 자동 확인
        mSurfaceView.postDelayed(() -> GithubUpdateManager.checkForUpdateAsync(
                MainActivity.this, false, mOtaOverlayHooks), 2500L);
    }

    private final GithubUpdateManager.OverlayHooks mOtaOverlayHooks = new GithubUpdateManager.OverlayHooks() {
        @Override
        public void onSuspendOverlay() {
            if (mSurfaceView != null) mSurfaceView.suspendHardwareOverlay();
        }

        @Override
        public void onRestoreOverlay() {
            restoreOverlayIfPenMode();
        }
    };

    private void initHardwareAccelerator() {
        if (mAccelerator == null || mSurfaceView == null) return;
        boolean ok = mAccelerator.init(3840, 2160);
        Point realSize = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(realSize);
        int[] loc = new int[2];
        mSurfaceView.getLocationOnScreen(loc);
        mAccelerator.setViewMapping(loc[0], loc[1], realSize.x, realSize.y);
        if (!mHasPdfLoaded || mSurfaceView.getToolMode() != ScrollPdfSurfaceView.ToolMode.PEN) {
            mAccelerator.suspendOverlay();
        }
        Log.i(TAG, "Hardware accelerator ready=" + ok + " realDisplay=" + realSize.x + "x" + realSize.y);
    }

    private void requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] {
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, REQ_STORAGE_PERM);
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String extraPath = intent.getStringExtra("pdf_path");
        if (extraPath != null && !extraPath.isEmpty() && new File(extraPath).exists()) {
            File f = new File(extraPath);
            openPdfDocument(f.getAbsolutePath(), null, f.getName());
            return;
        }
        Uri data = intent.getData();
        if (data != null) {
            String name = resolveDisplayName(data);
            String path = "file".equalsIgnoreCase(data.getScheme()) ? data.getPath() : null;
            openPdfDocument(path, data, name);
        }
    }

    private void openPdfDocument(final String filePath, final Uri uri, final String displayName) {
        openPdfDocument(filePath, uri, displayName, true);
    }

    private void openPdfDocument(final String filePath, final Uri uri, final String displayName,
                                 final boolean showSaveCacheCheckbox) {
        if (mSurfaceView == null) return;
        dismissActivePopup();
        mSurfaceView.suspendHardwareOverlay();
        int lastPage = Math.max(0, PdfCacheManager.getLastPageIndex(this, displayName, filePath));
        new PdfPageSelectorDialog(this, filePath, uri, displayName, lastPage, showSaveCacheCheckbox,
                new PdfPageSelectorDialog.OnPageConfirmedListener() {
                    @Override
                    public void onPageConfirmed(int pageIndex, boolean saveToCache) {
                        openPdfDocumentAtPage(filePath, uri, displayName, pageIndex, saveToCache);
                    }

                    @Override
                    public void onCancelled() {
                        restoreOverlayIfPenMode();
                    }
                }).show();
    }

    private void openPdfDocumentAtPage(String filePath, Uri uri, String displayName,
                                       int startPage, boolean saveToCache) {
        if (mSurfaceView == null) return;
        dismissActivePopup();
        mSurfaceView.suspendHardwareOverlay();
        mHasPdfLoaded = true;
        if (mWelcomeOverlay != null) {
            mWelcomeOverlay.setVisibility(View.GONE);
        }
        // 사용자가 체크한 경우에만 최근 연 PDF 캐시(최대 3GB, LRU 자동 정리)에 저장
        if (saveToCache) {
            if (filePath != null && !filePath.isEmpty()) {
                File f = new File(filePath);
                if (f.exists()) PdfCacheManager.cacheFromFileAsync(this, f, displayName, null);
            } else if (uri != null) {
                PdfCacheManager.cacheFromUriAsync(this, uri, displayName, null);
            }
            Toast.makeText(this, "최근 파일 캐시에 저장됩니다.", Toast.LENGTH_SHORT).show();
        }
        mSurfaceView.openPdf(this, filePath, uri, displayName, Math.max(0, startPage));
        mSurfaceView.setToolMode(ScrollPdfSurfaceView.ToolMode.PEN);
        refreshToolbarVisuals();
        updateUndoRedoIcons(false, false);
    }

    private void openPdfBrowser() {
        dismissActivePopup();
        if (mSurfaceView != null) mSurfaceView.suspendHardwareOverlay();
        PdfBrowserDialog browser = new PdfBrowserDialog(this, new PdfBrowserDialog.OnPdfSelectedListener() {
            @Override
            public void onPdfSelected(File file) {
                if (file != null && file.exists()) {
                    openPdfDocument(file.getAbsolutePath(), null, file.getName());
                }
            }

            @Override
            public void onOpenSystemPicker() {
                launchSystemPdfPicker();
            }
        });
        browser.show();
    }

    private void launchSystemPdfPicker() {
        dismissActivePopup();
        if (mSurfaceView != null) mSurfaceView.suspendHardwareOverlay();
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/pdf");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, REQ_OPEN_PDF);
        } catch (Throwable t) {
            Toast.makeText(this, "파일 선택기를 열 수 없습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OPEN_PDF && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    getContentResolver().takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable ignored) {}
                String name = resolveDisplayName(uri);
                openPdfDocument(null, uri, name);
            }
        }
    }

    private String resolveDisplayName(Uri uri) {
        if (uri == null) return "문서.pdf";
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            String p = uri.getPath();
            if (p != null) return new File(p).getName();
        }
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
        }
        String last = uri.getLastPathSegment();
        return (last != null && !last.isEmpty()) ? last : "문서.pdf";
    }

    private void hideSystemUi() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        restoreOverlayIfPenMode();
    }

    @Override
    protected void onPause() {
        super.onPause();
        dismissActivePopup();
        if (mSurfaceView != null) {
            mSurfaceView.suspendHardwareOverlay();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        dismissActivePopup();
        if (mSurfaceView != null) {
            try { mSurfaceView.closePdf(); } catch (Throwable ignored) {}
        }
        if (mAccelerator != null) {
            mAccelerator.destroy();
        }
    }

    @Override
    public void onBackPressed() {
        if (mActivePopup != null && mActivePopup.isShowing()) {
            dismissActivePopup();
            return;
        }
        if (mSurfaceView != null && mSurfaceView.isFocusActive()) {
            handleLassoToggle();
            return;
        }
        confirmAndExit();
    }

    private void dismissActivePopup() {
        if (mActivePopup != null) {
            try {
                if (mActivePopup.isShowing()) mActivePopup.dismiss();
            } catch (Throwable ignored) {}
            mActivePopup = null;
        }
    }

    // ─── 초기 안내 화면 ───────────────────────────────────────────────
    private LinearLayout buildWelcomeOverlay() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER);
        wrap.setBackgroundColor(Color.parseColor("#F1F5F9"));
        wrap.setClickable(true);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(44), dp(36), dp(44), dp(36));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.WHITE);
        cardBg.setCornerRadius(dp(24));
        cardBg.setStroke(dp(1), Color.parseColor("#CBD5E1"));
        card.setBackground(cardBg);
        card.setElevation(dp(12));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(64), dp(64));
        logoLp.bottomMargin = dp(12);
        card.addView(logo, logoLp);

        TextView title = new TextView(this);
        title.setText("GNFLPDF");
        title.setGravity(Gravity.CENTER);
        title.setTextColor(Color.parseColor("#0F172A"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView sub = new TextView(this);
        sub.setText("초고속 4K 하드웨어 가속 세로 스크롤 PDF 판서");
        sub.setTextColor(Color.parseColor("#475569"));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(6);
        subLp.bottomMargin = dp(24);
        card.addView(sub, subLp);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);

        Button openBtn = new Button(this);
        openBtn.setText("📂  PDF 파일 열기");
        openBtn.setAllCaps(false);
        openBtn.setTextColor(Color.WHITE);
        openBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        openBtn.setTypeface(Typeface.DEFAULT_BOLD);
        openBtn.setPadding(dp(28), dp(13), dp(28), dp(13));
        GradientDrawable openBg = new GradientDrawable();
        openBg.setColor(Color.parseColor("#00A884"));
        openBg.setCornerRadius(dp(14));
        openBtn.setBackground(openBg);
        openBtn.setOnClickListener(v -> openPdfBrowser());
        btnRow.addView(openBtn);

        Button recentBtn = new Button(this);
        recentBtn.setText("🕘  최근 연 PDF");
        recentBtn.setAllCaps(false);
        recentBtn.setTextColor(Color.parseColor("#0F172A"));
        recentBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        recentBtn.setTypeface(Typeface.DEFAULT_BOLD);
        recentBtn.setPadding(dp(24), dp(13), dp(24), dp(13));
        GradientDrawable recBg = new GradientDrawable();
        recBg.setColor(Color.parseColor("#E2E8F0"));
        recBg.setCornerRadius(dp(14));
        recentBtn.setBackground(recBg);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.leftMargin = dp(12);
        recentBtn.setOnClickListener(v -> showRecentPdfDialog());
        btnRow.addView(recentBtn, rlp);

        card.addView(btnRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    // ─── GNFLNOTE 수준 3단 플로팅 독 툴바 ────────────────────────────
    private LinearLayout buildBottomToolbar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        // 1. 좌측 카드: 메뉴 / PDF 열기 / 올가미
        LinearLayout leftCard = createToolbarCard();

        mBtnMenu = createIconToolItem("메뉴", R.drawable.explore_edu_toolbar_menu);
        mBtnMenu.container.setOnClickListener(v -> showMainMenuPopup(mBtnMenu.container));
        leftCard.addView(mBtnMenu.container);

        mBtnPdfOpen = createIconToolItem("페이지", R.drawable.explore_edu_toolbar_list);
        mBtnPdfOpen.container.setOnClickListener(v -> showPageJumpDialog());
        leftCard.addView(mBtnPdfOpen.container);

        mBtnLasso = createIconToolItem("올가미", R.drawable.ic_lasso_tool);
        mBtnLasso.container.setOnClickListener(v -> handleLassoToggle());
        leftCard.addView(mBtnLasso.container);

        row.addView(leftCard);
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        // 2. 중앙 메인 카드: 선택 / 쓰기 / 형광펜 / 지우개 / 삭제 / 이동 | 7색 | 폭맞춤 / 쪽맞춤 | 실행취소 / 되돌리기
        LinearLayout centerCard = createToolbarCard();

        mBtnSelect = createIconToolItem("선택", R.drawable.explore_edu_toolbar_selection_normal);
        mBtnSelect.container.setOnClickListener(v -> {
            dismissActivePopup();
            if (mSurfaceView != null) mSurfaceView.setToolMode(ScrollPdfSurfaceView.ToolMode.SELECT);
            refreshToolbarVisuals();
        });
        centerCard.addView(mBtnSelect.container);

        mBtnPen = createIconToolItem("쓰기", R.drawable.explore_edu_toolbar_pencil_normal);
        mBtnPen.container.setOnClickListener(v -> {
            if (mSurfaceView == null) return;
            boolean alreadyPen = (mSurfaceView.getToolMode() == ScrollPdfSurfaceView.ToolMode.PEN
                    && !mSurfaceView.isHighlighter());
            if (alreadyPen) {
                showPenSettingsPopup(mBtnPen.container);
            } else {
                dismissActivePopup();
                mSurfaceView.setHighlighter(false);
                mSurfaceView.setToolMode(ScrollPdfSurfaceView.ToolMode.PEN);
                refreshToolbarVisuals();
            }
        });
        centerCard.addView(mBtnPen.container);

        mBtnHighlighter = createIconToolItem("형광펜", R.drawable.explore_edu_pen_highlight_normal);
        mBtnHighlighter.container.setOnClickListener(v -> {
            if (mSurfaceView == null) return;
            boolean alreadyHl = (mSurfaceView.getToolMode() == ScrollPdfSurfaceView.ToolMode.PEN
                    && mSurfaceView.isHighlighter());
            if (alreadyHl) {
                showPenSettingsPopup(mBtnHighlighter.container);
            } else {
                dismissActivePopup();
                mSurfaceView.setHighlighter(true);
                mSurfaceView.setToolMode(ScrollPdfSurfaceView.ToolMode.PEN);
                refreshToolbarVisuals();
            }
        });
        centerCard.addView(mBtnHighlighter.container);

        mBtnEraser = createIconToolItem("지우개", R.drawable.explore_edu_toolbar_eraser_normal);
        mBtnEraser.container.setOnClickListener(v -> {
            if (mSurfaceView == null) return;
            if (mSurfaceView.getToolMode() == ScrollPdfSurfaceView.ToolMode.ERASER) {
                showEraserSettingsPopup(mBtnEraser.container);
            } else {
                dismissActivePopup();
                mSurfaceView.setToolMode(ScrollPdfSurfaceView.ToolMode.ERASER);
                refreshToolbarVisuals();
            }
        });
        centerCard.addView(mBtnEraser.container);

        mBtnClean = createIconToolItem("삭제", R.drawable.explore_edu_toolbar_clear);
        mBtnClean.container.setOnClickListener(v -> handleCleanClick());
        centerCard.addView(mBtnClean.container);

        mBtnMove = createIconToolItem("이동", R.drawable.explore_edu_toolbar_roam_normal);
        mBtnMove.container.setOnClickListener(v -> {
            dismissActivePopup();
            if (mSurfaceView != null) mSurfaceView.setToolMode(ScrollPdfSurfaceView.ToolMode.MOVE);
            refreshToolbarVisuals();
        });
        centerCard.addView(mBtnMove.container);

        centerCard.addView(createDivider());

        // GNFLNOTE 7색 퀵 팔레트
        int dotSize = dp(27);
        int dotGap = dp(3);
        for (int i = 0; i < PALETTE.length; i++) {
            final int idx = i;
            View dot = new View(this);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dotSize, dotSize);
            dlp.setMargins(dotGap, 0, dotGap, 0);
            dot.setOnClickListener(v -> {
                dismissActivePopup();
                mSelectedColorIdx = idx;
                if (mSurfaceView != null) {
                    mSurfaceView.setPenColor(PALETTE[idx]);
                    if (mSurfaceView.getToolMode() != ScrollPdfSurfaceView.ToolMode.PEN) {
                        mSurfaceView.setToolMode(ScrollPdfSurfaceView.ToolMode.PEN);
                    }
                }
                refreshToolbarVisuals();
            });
            centerCard.addView(dot, dlp);
            mColorDots[i] = dot;
        }

        // 커스텀 색상 피커 버튼 (+)
        mCustomColorBtn = new TextView(this);
        mCustomColorBtn.setText("+");
        mCustomColorBtn.setGravity(Gravity.CENTER);
        mCustomColorBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        mCustomColorBtn.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams cclp = new LinearLayout.LayoutParams(dotSize, dotSize);
        cclp.setMargins(dotGap + dp(2), 0, dotGap, 0);
        mCustomColorBtn.setOnClickListener(v -> {
            dismissActivePopup();
            if (mHasCustomColor && mSelectedColorIdx != -1) {
                // 이미 고른 커스텀 색상이 있으면 1탭으로 즉시 전환 (다시 탭하면 피커 열기)
                mSelectedColorIdx = -1;
                applyActivePenColor();
            } else {
                openCustomColorPicker();
            }
        });
        mCustomColorBtn.setOnLongClickListener(v -> {
            dismissActivePopup();
            openCustomColorPicker();
            return true;
        });
        centerCard.addView(mCustomColorBtn, cclp);

        centerCard.addView(createDivider());

        mBtnFitW = createIconToolItem("폭맞춤", R.drawable.ic_fit_width);
        mBtnFitW.container.setOnClickListener(v -> {
            dismissActivePopup();
            if (mSurfaceView != null) {
                mSurfaceView.clearHardwareOverlayNow();
                mSurfaceView.fitWidth();
            }
        });
        centerCard.addView(mBtnFitW.container);

        mBtnFitP = createIconToolItem("쪽맞춤", R.drawable.ic_fit_page);
        mBtnFitP.container.setOnClickListener(v -> {
            dismissActivePopup();
            if (mSurfaceView != null) {
                mSurfaceView.clearHardwareOverlayNow();
                mSurfaceView.fitPage();
            }
        });
        centerCard.addView(mBtnFitP.container);

        centerCard.addView(createDivider());

        mBtnUndo = createIconToolItem("실행취소", R.drawable.explore_edu_toolbar_undo_disable);
        mBtnUndo.container.setOnClickListener(v -> {
            if (mSurfaceView != null && !mSurfaceView.undo()) {
                Toast.makeText(this, "되돌릴 판서가 없습니다.", Toast.LENGTH_SHORT).show();
            }
        });
        centerCard.addView(mBtnUndo.container);

        mBtnRedo = createIconToolItem("되돌리기", R.drawable.explore_edu_toolbar_redo_disable);
        mBtnRedo.container.setOnClickListener(v -> {
            if (mSurfaceView != null && !mSurfaceView.redo()) {
                Toast.makeText(this, "다시 실행할 판서가 없습니다.", Toast.LENGTH_SHORT).show();
            }
        });
        centerCard.addView(mBtnRedo.container);

        row.addView(centerCard);
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        // 3. 우측 페이지 카드: 이전쪽 / 1/344 / 다음쪽
        LinearLayout rightCard = createToolbarCard();

        mBtnPre = createIconToolItem("이전쪽", R.drawable.explore_edu_toolbar_prev_page_disable);
        mBtnPre.container.setOnClickListener(v -> {
            dismissActivePopup();
            if (mSurfaceView != null) {
                mSurfaceView.clearHardwareOverlayNow();
                int c = mSurfaceView.getCurrentPageIndex();
                if (c > 0) mSurfaceView.jumpToPage(c - 1);
            }
        });
        rightCard.addView(mBtnPre.container);

        mTxtPageNum = new TextView(this);
        mTxtPageNum.setText("0 / 0");
        mTxtPageNum.setTextColor(Color.parseColor("#0F172A"));
        mTxtPageNum.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        mTxtPageNum.setTypeface(Typeface.DEFAULT_BOLD);
        mTxtPageNum.setGravity(Gravity.CENTER);
        mTxtPageNum.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable pageBg = new GradientDrawable();
        pageBg.setColor(Color.parseColor("#F1F5F9"));
        pageBg.setCornerRadius(dp(10));
        mTxtPageNum.setBackground(pageBg);
        mTxtPageNum.setOnClickListener(v -> showPageJumpDialog());
        rightCard.addView(mTxtPageNum);

        mBtnNext = createIconToolItem("다음쪽", R.drawable.explore_edu_toolbar_next_page_disable);
        mBtnNext.container.setOnClickListener(v -> {
            dismissActivePopup();
            if (mSurfaceView != null) {
                mSurfaceView.clearHardwareOverlayNow();
                int c = mSurfaceView.getCurrentPageIndex();
                if (c < mSurfaceView.getPageCount() - 1) mSurfaceView.jumpToPage(c + 1);
            }
        });
        rightCard.addView(mBtnNext.container);

        row.addView(rightCard);
        return row;
    }

    private int getActivePenColor() {
        if (mSelectedColorIdx >= 0 && mSelectedColorIdx < PALETTE.length) {
            return PALETTE[mSelectedColorIdx];
        }
        return mCustomColor;
    }

    private void applyActivePenColor() {
        if (mSurfaceView != null) {
            mSurfaceView.setPenColor(getActivePenColor());
            if (mSurfaceView.getToolMode() != ScrollPdfSurfaceView.ToolMode.PEN) {
                mSurfaceView.setToolMode(ScrollPdfSurfaceView.ToolMode.PEN);
            }
        }
        refreshToolbarVisuals();
    }

    private void openCustomColorPicker() {
        dismissActivePopup();
        if (mSurfaceView != null) mSurfaceView.suspendHardwareOverlay();
        new ColorPickerDialog(this, getActivePenColor(), new ColorPickerDialog.OnColorSelectedListener() {
            @Override
            public void onColorSelected(int color) {
                mCustomColor = color;
                mHasCustomColor = true;
                mSelectedColorIdx = -1;
                applyActivePenColor();
                restoreOverlayIfPenMode();
            }

            @Override
            public void onCancelled() {
                restoreOverlayIfPenMode();
            }
        }).show();
    }

    // ─── GNFLNOTE 스타일 펜 설정 팝업 (1:1 굵기 + 막대 슬라이더 + 색상 피커) ──
    private void showPenSettingsPopup(View anchor) {
        if (mActivePopup != null && mActivePopup.isShowing()) {
            dismissActivePopup();
            return;
        }
        if (mSurfaceView != null) mSurfaceView.suspendHardwareOverlay();

        final LinearLayout panel = createPopupCard();
        panel.setPadding(dp(18), dp(14), dp(18), dp(16));

        final boolean isHl = (mSurfaceView != null && mSurfaceView.isHighlighter());
        final boolean isSmart = (mSurfaceView != null && mSurfaceView.isSmartPenEnabled());
        final float minW = isHl ? HL_MIN_PX : PEN_MIN_PX;
        final float maxW = isHl ? HL_MAX_PX : PEN_MAX_PX;
        final float[] presets = isHl ? HL_WIDTH_PRESETS : PEN_WIDTH_PRESETS;
        final float curW = isHl ? mCurrentHlWidthPx : mCurrentPenWidthPx;
        final int activeColor = getActivePenColor();

        // 1행: 일반펜 / 형광펜 | 도형보정 | 색상 피커
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        ToolItem pNormal = createIconToolItem("일반펜",
                !isHl ? R.drawable.explore_edu_pen_pencil_selected : R.drawable.explore_edu_pen_pencil_normal);
        styleToolHighlight(pNormal, !isHl);
        pNormal.container.setOnClickListener(v -> {
            if (mSurfaceView != null) {
                mSurfaceView.setHighlighter(false);
                mSurfaceView.setToolMode(ScrollPdfSurfaceView.ToolMode.PEN);
            }
            refreshToolbarVisuals();
            dismissActivePopup();
            showPenSettingsPopup(anchor);
        });
        topRow.addView(pNormal.container);

        ToolItem pHl = createIconToolItem("형광펜",
                isHl ? R.drawable.explore_edu_pen_highlight_selected : R.drawable.explore_edu_pen_highlight_normal);
        styleToolHighlight(pHl, isHl);
        pHl.container.setOnClickListener(v -> {
            if (mSurfaceView != null) {
                mSurfaceView.setHighlighter(true);
                mSurfaceView.setToolMode(ScrollPdfSurfaceView.ToolMode.PEN);
            }
            refreshToolbarVisuals();
            dismissActivePopup();
            showPenSettingsPopup(anchor);
        });
        topRow.addView(pHl.container);

        topRow.addView(createDivider());

        ToolItem pSmart = createIconToolItem(isSmart ? "도형보정 ON" : "도형보정 OFF",
                isSmart ? R.drawable.explore_edu_pen_smart_selected : R.drawable.explore_edu_pen_smart_normal);
        styleToolHighlight(pSmart, isSmart);
        pSmart.container.setOnClickListener(v -> {
            if (mSurfaceView != null) {
                boolean next = !mSurfaceView.isSmartPenEnabled();
                mSurfaceView.setSmartPenEnabled(next);
                Toast.makeText(this, next ? "스마트펜 도형 보정 켜짐" : "스마트펜 도형 보정 꺼짐", Toast.LENGTH_SHORT).show();
            }
            dismissActivePopup();
        });
        topRow.addView(pSmart.container);

        Button colorPickBtn = new Button(this);
        colorPickBtn.setText("🎨 색상");
        colorPickBtn.setAllCaps(false);
        colorPickBtn.setTextColor(Color.parseColor("#0F172A"));
        colorPickBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        colorPickBtn.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable cpBg = new GradientDrawable();
        cpBg.setColor(Color.parseColor("#F1F5F9"));
        cpBg.setCornerRadius(dp(10));
        cpBg.setStroke(dp(1), Color.parseColor("#CBD5E1"));
        colorPickBtn.setBackground(cpBg);
        colorPickBtn.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams cplp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        cplp.leftMargin = dp(8);
        colorPickBtn.setOnClickListener(v -> openCustomColorPicker());
        topRow.addView(colorPickBtn, cplp);

        panel.addView(topRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 2행: 실제 1:1 두께 실시간 선 미리보기 + 굵기 라벨
        final TextView wTitle = new TextView(this);
        wTitle.setText((isHl ? "형광펜 굵기: " : "쓰기펜 굵기: ") + Math.round(curW) + "px");
        wTitle.setTextColor(Color.parseColor("#475569"));
        wTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        wTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams wtLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wtLp.topMargin = dp(12);
        wtLp.bottomMargin = dp(4);
        panel.addView(wTitle, wtLp);

        final float[] liveWidthHolder = new float[] { curW };
        final View livePreview = new View(this) {
            private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override
            protected void onDraw(android.graphics.Canvas canvas) {
                float w = getWidth();
                float h = getHeight();
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeCap(isHl ? android.graphics.Paint.Cap.SQUARE : android.graphics.Paint.Cap.ROUND);
                p.setColor(activeColor);
                p.setAlpha(isHl ? 92 : 255);
                p.setStrokeWidth(liveWidthHolder[0]);
                float pad = Math.max(dp(16), liveWidthHolder[0] * 0.6f);
                canvas.drawLine(pad, h * 0.5f, w - pad, h * 0.5f, p);
            }
        };
        GradientDrawable prevBg = new GradientDrawable();
        prevBg.setColor(Color.parseColor("#F8FAFC"));
        prevBg.setCornerRadius(dp(10));
        prevBg.setStroke(dp(1), Color.parseColor("#E2E8F0"));
        livePreview.setBackground(prevBg);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(276), dp(46));
        plp.bottomMargin = dp(8);
        panel.addView(livePreview, plp);

        // 3행: 막대 슬라이더 (SeekBar)
        final SeekBar slider = new SeekBar(this);
        slider.setMax(Math.round(maxW - minW));
        slider.setProgress(Math.max(0, Math.min(slider.getMax(), Math.round(curW - minW))));
        LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(dp(276), ViewGroup.LayoutParams.WRAP_CONTENT);
        sblp.bottomMargin = dp(10);
        panel.addView(slider, sblp);

        // 4행: 5단계 1:1 원형 프리셋 (실제 화면 픽셀 크기 그대로 표시!)
        final FrameLayout[] presetCells = new FrameLayout[presets.length];
        LinearLayout wRow = new LinearLayout(this);
        wRow.setOrientation(LinearLayout.HORIZONTAL);
        wRow.setGravity(Gravity.CENTER_VERTICAL);
        final int boxPx = dp(46);

        final Runnable refreshPresetHighlight = () -> {
            float nowW = liveWidthHolder[0];
            for (int i = 0; i < presets.length; i++) {
                boolean match = Math.abs(presets[i] - nowW) <= (isHl ? 3.5f : 1.5f);
                GradientDrawable cBg = new GradientDrawable();
                cBg.setCornerRadius(dp(10));
                cBg.setColor(match ? Color.parseColor("#E6F7F2") : Color.parseColor("#F8FAFC"));
                cBg.setStroke(dp(match ? 2 : 1),
                        match ? Color.parseColor("#00A884") : Color.parseColor("#E2E8F0"));
                presetCells[i].setBackground(cBg);
            }
        };

        for (int i = 0; i < presets.length; i++) {
            final int idx = i;
            FrameLayout cell = new FrameLayout(this);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(boxPx, boxPx);
            clp.setMargins(dp(4), 0, dp(4), 0);

            View circle = new View(this);
            // 실제 화면 픽셀 크기(presets[i])와 1:1 일치! (큰 형광펜만 셀 안쪽에 안전하게 클램프)
            int diamPx = Math.max(4, Math.min(boxPx - dp(10), Math.round(presets[i])));
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(isHl ? ((activeColor & 0x00FFFFFF) | 0x66000000) : activeColor);
            circle.setBackground(dot);
            cell.addView(circle, new FrameLayout.LayoutParams(diamPx, diamPx, Gravity.CENTER));

            cell.setOnClickListener(v -> {
                float chosen = presets[idx];
                liveWidthHolder[0] = chosen;
                if (isHl) {
                    mCurrentHlWidthPx = chosen;
                    if (mSurfaceView != null) mSurfaceView.setHighlighterWidthPx(chosen);
                } else {
                    mCurrentPenWidthPx = chosen;
                    if (mSurfaceView != null) mSurfaceView.setPenWidthPx(chosen);
                }
                slider.setProgress(Math.max(0, Math.min(slider.getMax(), Math.round(chosen - minW))));
                wTitle.setText((isHl ? "형광펜 굵기: " : "쓰기펜 굵기: ") + Math.round(chosen) + "px");
                livePreview.invalidate();
                refreshPresetHighlight.run();
            });
            wRow.addView(cell, clp);
            presetCells[i] = cell;
        }
        refreshPresetHighlight.run();
        panel.addView(wRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                float chosen = minW + progress;
                liveWidthHolder[0] = chosen;
                if (isHl) {
                    mCurrentHlWidthPx = chosen;
                    if (mSurfaceView != null) mSurfaceView.setHighlighterWidthPx(chosen);
                } else {
                    mCurrentPenWidthPx = chosen;
                    if (mSurfaceView != null) mSurfaceView.setPenWidthPx(chosen);
                }
                wTitle.setText((isHl ? "형광펜 굵기: " : "쓰기펜 굵기: ") + Math.round(chosen) + "px");
                livePreview.invalidate();
                refreshPresetHighlight.run();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        showPopupAbove(panel, anchor);
    }

    // ─── 지우개 모드(영역 선택 / 부분 문지르기) 및 크기 팝업 ──────────
    private void showEraserSettingsPopup(View anchor) {
        if (mActivePopup != null && mActivePopup.isShowing()) {
            dismissActivePopup();
            return;
        }
        if (mSurfaceView != null) mSurfaceView.suspendHardwareOverlay();

        final boolean isArea = (mSurfaceView == null || mSurfaceView.isEraserAreaMode());
        LinearLayout panel = createPopupCard();
        panel.setPadding(dp(16), dp(12), dp(16), dp(14));

        TextView modeTitle = new TextView(this);
        modeTitle.setText("지우개 방식");
        modeTitle.setTextColor(Color.parseColor("#64748B"));
        modeTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        modeTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams mtlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mtlp.bottomMargin = dp(6);
        panel.addView(modeTitle, mtlp);

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);

        Button btnArea = new Button(this);
        btnArea.setText("⬚ 영역 선택");
        btnArea.setAllCaps(false);
        btnArea.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        btnArea.setTypeface(Typeface.DEFAULT_BOLD);
        btnArea.setTextColor(isArea ? Color.WHITE : Color.parseColor("#334155"));
        GradientDrawable aBg = new GradientDrawable();
        aBg.setCornerRadius(dp(10));
        aBg.setColor(isArea ? Color.parseColor("#00A884") : Color.parseColor("#F1F5F9"));
        btnArea.setBackground(aBg);
        btnArea.setPadding(dp(12), dp(6), dp(12), dp(6));
        btnArea.setOnClickListener(v -> {
            if (mSurfaceView != null) mSurfaceView.setEraserAreaMode(true);
            dismissActivePopup();
            Toast.makeText(this, "영역 선택 지우개 (드래그 영역에 걸친 판서 삭제)", Toast.LENGTH_SHORT).show();
        });
        modeRow.addView(btnArea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));

        Button btnRub = new Button(this);
        btnRub.setText("● 부분 문지르기");
        btnRub.setAllCaps(false);
        btnRub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        btnRub.setTypeface(Typeface.DEFAULT_BOLD);
        btnRub.setTextColor(!isArea ? Color.WHITE : Color.parseColor("#334155"));
        GradientDrawable rBg = new GradientDrawable();
        rBg.setCornerRadius(dp(10));
        rBg.setColor(!isArea ? Color.parseColor("#00A884") : Color.parseColor("#F1F5F9"));
        btnRub.setBackground(rBg);
        btnRub.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        rlp.leftMargin = dp(8);
        btnRub.setOnClickListener(v -> {
            if (mSurfaceView != null) mSurfaceView.setEraserAreaMode(false);
            dismissActivePopup();
            Toast.makeText(this, "부분 문지르기 지우개", Toast.LENGTH_SHORT).show();
        });
        modeRow.addView(btnRub, rlp);
        panel.addView(modeRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (!isArea) {
            TextView title = new TextView(this);
            title.setText("부분 지우개 크기");
            title.setTextColor(Color.parseColor("#64748B"));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = dp(10);
            tlp.bottomMargin = dp(6);
            panel.addView(title, tlp);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            for (int i = 0; i < ERASER_PRESETS.length; i++) {
                final int idx = i;
                Button b = new Button(this);
                b.setText(ERASER_LABELS[i]);
                b.setAllCaps(false);
                b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
                b.setTypeface(Typeface.DEFAULT_BOLD);
                b.setTextColor(i == mEraserPresetIdx ? Color.WHITE : Color.parseColor("#334155"));
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(10));
                bg.setColor(i == mEraserPresetIdx ? Color.parseColor("#00A884") : Color.parseColor("#F1F5F9"));
                b.setBackground(bg);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(54), dp(38));
                blp.setMargins(dp(4), 0, dp(4), 0);
                b.setOnClickListener(v -> {
                    mEraserPresetIdx = idx;
                    if (mSurfaceView != null) mSurfaceView.setEraserBaseRadiusPx(ERASER_PRESETS[idx]);
                    dismissActivePopup();
                });
                row.addView(b, blp);
            }
            panel.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        showPopupAbove(panel, anchor);
    }

    // ─── PDF 열기 서브 팝업 (방안 A: 새 PDF / 최근 연 PDF 캐시) ───────
    private void showPdfOpenPopup(View anchor) {
        if (mActivePopup != null && mActivePopup.isShowing()) {
            dismissActivePopup();
            return;
        }
        if (mSurfaceView != null) mSurfaceView.suspendHardwareOverlay();

        List<PdfCacheManager.CacheEntry> cached = PdfCacheManager.getRecentEntries(this);
        int count = (cached != null) ? cached.size() : 0;

        LinearLayout panel = createPopupCard();
        panel.setPadding(dp(10), dp(10), dp(10), dp(10));

        panel.addView(createMenuRow("📂  새 PDF 파일 열기 (USB / 저장소)", Color.parseColor("#0F172A"), v -> {
            dismissActivePopup();
            openPdfBrowser();
        }));

        View sep = new View(this);
        sep.setBackgroundColor(Color.parseColor("#E2E8F0"));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(260), dp(1));
        slp.setMargins(dp(12), dp(2), dp(12), dp(2));
        panel.addView(sep, slp);

        panel.addView(createMenuRow("🕘  최근 연 PDF 캐시 (" + count + "개)", Color.parseColor("#00A884"), v -> {
            dismissActivePopup();
            showRecentPdfDialog();
        }));

        showPopupAbove(panel, anchor);
    }

    // ─── 좌측 메뉴 팝업 (PDF 열기 / 최근 PDF / 배경색 / 종료) ──────────
    private void showMainMenuPopup(View anchor) {
        if (mActivePopup != null && mActivePopup.isShowing()) {
            dismissActivePopup();
            return;
        }
        if (mSurfaceView != null) mSurfaceView.suspendHardwareOverlay();

        List<PdfCacheManager.CacheEntry> cached = PdfCacheManager.getRecentEntries(this);
        int count = (cached != null) ? cached.size() : 0;

        LinearLayout panel = createPopupCard();
        panel.setPadding(dp(10), dp(10), dp(10), dp(10));

        panel.addView(createMenuRow("📂  PDF 파일 열기 (USB / 저장소)", Color.parseColor("#0F172A"), v -> {
            dismissActivePopup();
            openPdfBrowser();
        }));
        panel.addView(createMenuRow("🕘  최근 연 PDF 캐시 (" + count + "개)", Color.parseColor("#00A884"), v -> {
            dismissActivePopup();
            showRecentPdfDialog();
        }));
        panel.addView(createMenuRow("🎨  여백 배경색 변경", Color.parseColor("#0F172A"), v -> {
            dismissActivePopup();
            showMarginBgDialog();
        }));
        String verStr = GithubUpdateManager.getCurrentVersionName(this);
        panel.addView(createMenuRow("🔄  업데이트 확인 (v" + verStr + ")", Color.parseColor("#1D4ED8"), v -> {
            dismissActivePopup();
            GithubUpdateManager.checkForUpdateAsync(MainActivity.this, true, mOtaOverlayHooks);
        }));
        panel.addView(createMenuRow("🚪  GNFLPDF 종료", Color.parseColor("#DC2626"), v -> {
            dismissActivePopup();
            confirmAndExit();
        }));

        showPopupAbove(panel, anchor);
    }

    private TextView createMenuRow(String text, int color, View.OnClickListener listener) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(16), dp(12), dp(20), dp(12));
        tv.setOnClickListener(listener);
        return tv;
    }

    private void showRecentPdfDialog() {
        dismissActivePopup();
        if (mSurfaceView != null) mSurfaceView.suspendHardwareOverlay();
        new RecentPdfDialog(this, new RecentPdfDialog.Callback() {
            @Override
            public void onOpenCachedPdf(File file, String displayName) {
                if (file != null && file.exists()) {
                    // 이미 캐시에 저장된 파일이므로 저장 체크박스 생략
                    openPdfDocument(file.getAbsolutePath(), null, displayName, false);
                }
            }

            @Override
            public void onDismissed() {
                restoreOverlayIfPenMode();
            }
        }).show();
    }

    private void showMarginBgDialog() {
        if (mSurfaceView != null) mSurfaceView.suspendHardwareOverlay();
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("좌우 여백 배경색")
                .setSingleChoiceItems(MARGIN_BG_NAMES, mMarginBgIdx, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        mMarginBgIdx = which;
                        if (mSurfaceView != null) {
                            mSurfaceView.setOuterBgColor(MARGIN_BG_COLORS[which]);
                        }
                        dialog.dismiss();
                    }
                })
                .setNegativeButton("취소", null)
                .create();
        d.setOnDismissListener(dialog -> restoreOverlayIfPenMode());
        d.show();
    }

    private void showPopupAbove(LinearLayout content, View anchor) {
        content.measure(
                View.MeasureSpec.makeMeasureSpec(dp(1200), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(dp(800), View.MeasureSpec.AT_MOST));
        int pw = content.getMeasuredWidth();
        int ph = content.getMeasuredHeight();

        PopupWindow popup = new PopupWindow(content, pw, ph, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(12));
        popup.setOnDismissListener(() -> {
            mActivePopup = null;
            restoreOverlayIfPenMode();
        });
        mActivePopup = popup;

        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int x = Math.max(dp(14), loc[0] + (anchor.getWidth() - pw) / 2);
        int y = Math.max(dp(14), loc[1] - ph - dp(12));
        popup.showAtLocation(mBottomToolbar, Gravity.NO_GRAVITY, x, y);
    }

    private LinearLayout createPopupCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), Color.parseColor("#CBD5E1"));
        card.setBackground(bg);
        card.setElevation(dp(10));
        return card;
    }

    // ─── 다이얼로그 / 기능 핸들러 ─────────────────────────────────────
    private void showPageJumpDialog() {
        dismissActivePopup();
        if (mSurfaceView == null || mSurfaceView.getPageCount() <= 0) {
            openPdfBrowser();
            return;
        }
        mSurfaceView.suspendHardwareOverlay();
        new PdfPageSelectorDialog(
                this,
                mSurfaceView.getLocalPdfPath(),
                mSurfaceView.getOriginUri(),
                mSurfaceView.getDisplayName(),
                mSurfaceView.getCurrentPageIndex(),
                false,
                new PdfPageSelectorDialog.OnPageConfirmedListener() {
                    @Override
                    public void onPageConfirmed(int pageIndex, boolean saveToCache) {
                        if (mSurfaceView != null) {
                            mSurfaceView.clearHardwareOverlayNow();
                            mSurfaceView.jumpToPage(pageIndex);
                        }
                        restoreOverlayIfPenMode();
                    }

                    @Override
                    public void onCancelled() {
                        restoreOverlayIfPenMode();
                    }
                }).show();
    }

    private void confirmAndExit() {
        dismissActivePopup();
        if (mSurfaceView != null) mSurfaceView.suspendHardwareOverlay();
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("GNFLPDF 종료")
                .setMessage("GNFLPDF를 종료할까요?")
                .setPositiveButton("종료", (dialog, which) -> finish())
                .setNegativeButton("취소", null)
                .create();
        d.setOnDismissListener(dialog -> restoreOverlayIfPenMode());
        d.show();
    }

    private void handleLassoToggle() {
        dismissActivePopup();
        if (mSurfaceView == null || !mHasPdfLoaded) return;
        if (mSurfaceView.isFocusActive()) {
            if (mSurfaceView.hasFocusStrokes()) {
                mSurfaceView.suspendHardwareOverlay();
                AlertDialog d = new AlertDialog.Builder(this)
                        .setTitle("집중 풀이 종료")
                        .setMessage("풀이 판서를 지우고 원래 PDF 화면으로 돌아갈까요?")
                        .setPositiveButton("돌아가기", (dialog, which) -> {
                            if (mSurfaceView != null) mSurfaceView.exitFocusMode();
                        })
                        .setNegativeButton("취소", null)
                        .create();
                d.setOnDismissListener(dialog -> restoreOverlayIfPenMode());
                d.show();
            } else {
                mSurfaceView.exitFocusMode();
            }
            return;
        }
        if (mSurfaceView.getToolMode() == ScrollPdfSurfaceView.ToolMode.LASSO) {
            mSurfaceView.setToolMode(ScrollPdfSurfaceView.ToolMode.MOVE);
        } else {
            mSurfaceView.setToolMode(ScrollPdfSurfaceView.ToolMode.LASSO);
            Toast.makeText(this, "✂ 집중 풀이할 문제 영역을 드래그하세요", Toast.LENGTH_SHORT).show();
        }
        refreshToolbarVisuals();
    }

    private void handleCleanClick() {
        dismissActivePopup();
        if (mSurfaceView == null || !mHasPdfLoaded) return;
        if (mSurfaceView.isFocusActive()) {
            int n = mSurfaceView.clearFocusStrokes();
            Toast.makeText(this,
                    n > 0 ? ("집중 풀이 판서 " + n + "획을 삭제했습니다.") : "삭제할 풀이 판서가 없습니다.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        mSurfaceView.suspendHardwareOverlay();
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("판서 삭제")
                .setMessage("삭제할 범위를 선택하세요.\n('현재 화면 삭제'는 현재 페이지에 걸쳐 있는 판서를 모두 지웁니다)")
                .setPositiveButton("현재 화면 삭제", (dialog, which) -> {
                    if (mSurfaceView == null) return;
                    int n = mSurfaceView.clearCurrentScreenStrokes();
                    Toast.makeText(MainActivity.this,
                            n > 0 ? ("현재 화면 판서 " + n + "획을 삭제했습니다.") : "현재 화면에 삭제할 판서가 없습니다.",
                            Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("전체 삭제", (dialog, which) -> {
                    if (mSurfaceView == null) return;
                    int n = mSurfaceView.clearAllDocumentStrokes();
                    Toast.makeText(MainActivity.this,
                            n > 0 ? ("문서 전체 판서 " + n + "획을 삭제했습니다.") : "삭제할 판서가 없습니다.",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("취소", null)
                .create();
        d.setOnDismissListener(dialog -> restoreOverlayIfPenMode());
        d.show();
    }

    private void restoreOverlayIfPenMode() {
        if (mAccelerator != null && mSurfaceView != null
                && mHasPdfLoaded && mSurfaceView.getToolMode() == ScrollPdfSurfaceView.ToolMode.PEN) {
            mAccelerator.setRenderEnabled(true);
        }
    }

    private void updatePageNavUI(int cur, int total) {
        if (mTxtPageNum != null) mTxtPageNum.setText((cur + 1) + " / " + total);
        boolean hasPrev = cur > 0;
        boolean hasNext = cur < total - 1;
        if (mBtnPre != null) {
            mBtnPre.icon.setImageResource(hasPrev
                    ? R.drawable.explore_edu_toolbar_prev_page_normal
                    : R.drawable.explore_edu_toolbar_prev_page_disable);
            mBtnPre.container.setEnabled(hasPrev);
            mBtnPre.label.setAlpha(hasPrev ? 1f : 0.4f);
        }
        if (mBtnNext != null) {
            mBtnNext.icon.setImageResource(hasNext
                    ? R.drawable.explore_edu_toolbar_next_page_normal
                    : R.drawable.explore_edu_toolbar_next_page_disable);
            mBtnNext.container.setEnabled(hasNext);
            mBtnNext.label.setAlpha(hasNext ? 1f : 0.4f);
        }
    }

    private void updateUndoRedoIcons(boolean canUndo, boolean canRedo) {
        if (mBtnUndo != null) {
            mBtnUndo.icon.setImageResource(canUndo
                    ? R.drawable.explore_edu_toolbar_undo_normal
                    : R.drawable.explore_edu_toolbar_undo_disable);
            mBtnUndo.label.setAlpha(canUndo ? 1f : 0.4f);
        }
        if (mBtnRedo != null) {
            mBtnRedo.icon.setImageResource(canRedo
                    ? R.drawable.explore_edu_toolbar_redo_normal
                    : R.drawable.explore_edu_toolbar_redo_disable);
            mBtnRedo.label.setAlpha(canRedo ? 1f : 0.4f);
        }
    }

    private void refreshToolbarVisuals() {
        if (mSurfaceView == null) return;
        ScrollPdfSurfaceView.ToolMode m = mSurfaceView.getToolMode();
        boolean hl = mSurfaceView.isHighlighter();
        boolean focus = mSurfaceView.isFocusActive();

        boolean lassoOn = focus || m == ScrollPdfSurfaceView.ToolMode.LASSO;
        styleToolHighlight(mBtnLasso, lassoOn);
        mBtnLasso.icon.setColorFilter(lassoOn ? Color.WHITE : Color.parseColor("#334155"),
                PorterDuff.Mode.SRC_IN);

        boolean selOn = m == ScrollPdfSurfaceView.ToolMode.SELECT;
        mBtnSelect.icon.setImageResource(selOn
                ? R.drawable.explore_edu_toolbar_selection_selected
                : R.drawable.explore_edu_toolbar_selection_normal);
        styleToolHighlight(mBtnSelect, selOn);

        boolean penOn = m == ScrollPdfSurfaceView.ToolMode.PEN && !hl;
        mBtnPen.icon.setImageResource(penOn
                ? R.drawable.explore_edu_toolbar_pencil_selected
                : R.drawable.explore_edu_toolbar_pencil_normal);
        styleToolHighlight(mBtnPen, penOn);

        boolean hlOn = m == ScrollPdfSurfaceView.ToolMode.PEN && hl;
        mBtnHighlighter.icon.setImageResource(hlOn
                ? R.drawable.explore_edu_pen_highlight_selected
                : R.drawable.explore_edu_pen_highlight_normal);
        styleToolHighlight(mBtnHighlighter, hlOn);

        boolean erOn = m == ScrollPdfSurfaceView.ToolMode.ERASER;
        mBtnEraser.icon.setImageResource(erOn
                ? R.drawable.explore_edu_toolbar_eraser_selected
                : R.drawable.explore_edu_toolbar_eraser_normal);
        styleToolHighlight(mBtnEraser, erOn);

        boolean mvOn = m == ScrollPdfSurfaceView.ToolMode.MOVE;
        mBtnMove.icon.setImageResource(mvOn
                ? R.drawable.explore_edu_toolbar_roam_selected
                : R.drawable.explore_edu_toolbar_roam_normal);
        styleToolHighlight(mBtnMove, mvOn);

        for (int i = 0; i < PALETTE.length; i++) {
            View dot = mColorDots[i];
            if (dot == null) continue;
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(PALETTE[i]);
            if (i == mSelectedColorIdx) {
                gd.setStroke(dp(3), Color.parseColor("#00A884"));
            } else {
                gd.setStroke(dp(1), Color.parseColor("#CBD5E1"));
            }
            dot.setBackground(gd);
        }

        if (mCustomColorBtn != null) {
            GradientDrawable cgd = new GradientDrawable();
            cgd.setShape(GradientDrawable.OVAL);
            if (mHasCustomColor) {
                cgd.setColor(mCustomColor);
                // 밝은 색상이면 어두운 '+', 어두운 색상이면 흰색 '+'
                double lum = (0.299 * Color.red(mCustomColor)
                        + 0.587 * Color.green(mCustomColor)
                        + 0.114 * Color.blue(mCustomColor));
                mCustomColorBtn.setTextColor(lum > 165 ? Color.parseColor("#0F172A") : Color.WHITE);
                mCustomColorBtn.setText("+");
            } else {
                cgd.setColor(Color.parseColor("#F1F5F9"));
                mCustomColorBtn.setTextColor(Color.parseColor("#334155"));
                mCustomColorBtn.setText("+");
            }
            if (mSelectedColorIdx == -1) {
                cgd.setStroke(dp(3), Color.parseColor("#00A884"));
            } else {
                cgd.setStroke(dp(2), Color.parseColor("#94A3B8"));
            }
            mCustomColorBtn.setBackground(cgd);
        }
    }

    private void styleToolHighlight(ToolItem item, boolean active) {
        if (item == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        if (active) {
            bg.setColor(Color.parseColor("#00A884"));
            item.label.setTextColor(Color.WHITE);
        } else {
            bg.setColor(Color.TRANSPARENT);
            item.label.setTextColor(Color.parseColor("#334155"));
        }
        item.container.setBackground(bg);
    }

    private LinearLayout createToolbarCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(248, 255, 255, 255));
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), Color.parseColor("#CBD5E1"));
        card.setBackground(bg);
        card.setPadding(dp(8), dp(5), dp(8), dp(5));
        card.setElevation(dp(8));
        return card;
    }

    private ToolItem createIconToolItem(String labelText, int iconResId) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(9), dp(4), dp(9), dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(2), 0, dp(2), 0);
        box.setLayoutParams(lp);
        box.setMinimumWidth(dp(48));

        ImageView iv = new ImageView(this);
        iv.setImageResource(iconResId);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(26), dp(26));
        box.addView(iv, ilp);

        TextView tv = new TextView(this);
        tv.setText(labelText);
        tv.setSingleLine(true);
        tv.setTextColor(Color.parseColor("#334155"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(2);
        box.addView(tv, tlp);

        return new ToolItem(box, iv, tv);
    }

    private View createDivider() {
        View v = new View(this);
        v.setBackgroundColor(Color.parseColor("#E2E8F0"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(1), dp(34));
        lp.setMargins(dp(6), 0, dp(6), 0);
        v.setLayoutParams(lp);
        return v;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

package kr.hs.gnflhs.whiteboard;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageButton;
import android.widget.PopupWindow;
import android.widget.SeekBar;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * GNFLHS 전자칠판 v2.19.0 (v2.16.0 안정판 기반 + 고속펜 실험 + 숨김 메뉴)
 *
 * 변경 내용:
 * 1. 명시적으로 지우개를 선택했을 때 실제 삭제 폭과 동일한 커서를 접촉 지점에 표시.
 * 2. 두 손가락이 확대/이동 후보 또는 실행 상태일 때 두 접촉 위치에 인식 원 표시.
 * 3. 손가락을 가만히 댔다는 이유만으로 지우개로 바꾸거나 이전 획을 제거하지 않음.
 * 4. 첫 접촉의 폭이 손바닥으로 측정되면 기존처럼 즉시 지우개로 분류.
 *    (접촉 크기가 펜촉과 동일하게 보고되는 하드웨어는 소프트웨어만으로 구분 불가.)
 * 5. 기본 필기 Path / 캐시 / 예측 렌더링 경로는 변경하지 않음.
 * 6. 지우개 지름은 화면 짧은 변에 비례하며 현재 줌 배율로 나누어 적용.
 * 7. 네 가지 그리기 도구를 1초 누르면 크기 팝업 표시, 도구별 크기 영구 저장.
 * 8. 전체삭제는 지우개 오른쪽으로 이동, 공용 굵기 버튼 제거.
 * 9. 보정펜/형광펜 크기 팝업에 독립 1~4단계 곡선 보정 강도.
 * 10. 획을 그리다 350ms 멈추면 선/원/회전 직사각형을 인식하며, 직선은
 *     펜을 떼지 않고 끝점을 끌어 수평/수직으로 스냅할 수 있다.
 * 11. 기본 빠른펜의 비트맵 캐시/입력 예측은 변경하지 않는다.
 * 12. 보정펜/형광펜은 독립된 8단계 거리 기반 적응형 스플라인을 획 완료 시 적용한다.
 * 13. 지우개에 화면 좌표 고정 십자선을 표시하고 길이/표시 여부를 독립 저장한다.
 * 14. 팝업의 긴 설명 문장을 제거하고 항목별 ⓘ 툴팁으로 제공한다.
 * 15. 넓은 접촉일 때 손바닥 영역의 최상단을 추정하여 지우개 중심을 위쪽에 배치한다.
 *     삭제 경로와 화면 지우개 원은 같은 좌표를 사용한다. 일반 손가락은 중앙 지우개 유지.
 * 16. 모든 획 추가·전체삭제를 하나의 편집 기록 스택에서 처리해 전체삭제 후 실행취소/재실행 지원.
 * 17. 버전 업그레이드 시 과거 PEN, 보정펜, 형광펜, PDF 및 줌 처리 경로 유지.
 * 18. 빠른펜의 스마트 보정은 기본 OFF: OFF일 때 v2.8.0 필기 처리 경로를 유지한다.
 * 19. ON이면 손을 움직이는 동안 기존 64구간 비트맵·tip 예측을 사용하며,
 *     정지 시에만 원/사각형/직선/강한 스플라인을 계산한다.
 * 20. 직선은 정지 후 전체 이동, 곡선/닫힌 도형은 즉시 확정. 그 상태에서 다시 움직이면 다음 획이 시작된다.
 * 21. PDF 불러오기는 독립 자체 탐색기로 이동, 목록은 별도 스레드에서 안전하게 조회한다.
 * 22. 올가미 버튼을 1초 길게 누르면: 새 판서 유지/새 판서 취소/다음 페이지로 저장.
 * 23. 올가미 시작 직전의 획+실행취소 이력을 체크포인트로 저장하고 안전하게 복원한다.
 * 24. 새 페이지는 원본 PDF를 수정하지 않는 앱 내부 페이지이다. 화면 스냅샷은 제한된 해상도로 만든다.
 * 25. PDF 페이지 사이에 앱 내부 페이지를 삽입할 수 있으며 페이지 이동 시 PDF 원본 번호를 유지한다.
 * 26. v2.12: PDF 파일은 실제 작동했던 시스템 ACTION_OPEN_DOCUMENT를 사용한다.
 *     앱 전용 파일 탐색기와 폴더 접근 권한 요청은 실행하지 않는다.
 * 27. 올가미는 1초 길게 누르기로 기본 모드를 한 번 설정하고 계속 재사용한다.
 *     판서 유지/삭제는 완료 클릭 시 적용하고, 새 페이지는 선택 종료 직후 자동 삽입.
 * 28. 새 판서 페이지의 선택 영역 바깥은 선택 안쪽의 대표 배경색으로 채운다.
 * 29. v2.16: 이미지 캡처/재렌더링 없이 PDF 페이지 참조와 기존 올가미 가림 경로만 복제한다.
 * 30. 복제 페이지는 원본 필기를 복사하지 않고 독립적인 필기를 기록한다.
 * 31. 페이지를 다시 열어도 가림 경로를 유지하며, 올가미의 기존 색상 분석 결과를 그대로 쓴다.
 * 32. [v2.19 실험] PEN 도구 설정에서 기존펜/고속펜을 선택 가능. 기존펜은 변경하지 않음.
 *     고속펜은 v2.18 목탄펜처럼 실측 좌표 사이를 즉시 inkCanvas에 선분으로 남기되
 *     질감, 1 Euro 보정, 스탬프 생성, 별도 Bitmap/Shader 등은 사용하지 않는다.
 * 33. 고속펜은 실험용이며 자동으로 속도 향상을 보장하지 않는다. 되돌리기·지우개는 기존 획 기록을 사용.
 * 34. 툴바 오른쪽 고정 삼선 메뉴로 앱 종료/배경색/배경선 설정을 이동.

 */
public class MainActivity extends Activity {

    private static final String TAG = "GNFLHS_Whiteboard";
    private static final int REQUEST_CODE_PICK_PDF = 1001;
    // 시스템 문서 선택기만 사용한다. 폴더 전체 접근을 요구하지 않는다.

    private FrameLayout rootLayout;
    private NativeBoardView boardView;
    private HorizontalScrollView toolbarScrollView;
    private LinearLayout bottomToolbar;
    private PagePicker pagePicker;
    private TextView txtPageIndicator;
    private View btnPrevPage;
    private View btnNextPage;
    private View btnAddBlankPage;
    private View btnPdfList;
    private View btnClosePdf;

    private View btnToolHand;
    private View btnToolPen;
    private View btnToolSmooth;
    private View btnToolHighlighter;
    private View btnToolEraser;
    private View btnToolLasso;
    private TextView focusDoneButton;
    private ToolMode focusPen = ToolMode.PEN;
    private View btnMoreMenu;
    private PopupWindow morePopup;
    private PopupWindow toolSizePopup;
    private PopupWindow lassoActionsPopup;
    // 1초 길게 누르기는 실행 명령이 아닌 기본 모드 선택이며 앱 재실행 후에도 유지된다.
    private int lassoMode = LassoSessionPolicy.KEEP;
    // 집중 세션 도중 사용자가 기본 모드를 바꾸더라도 현재 세션의 의미는 바뀌지 않는다.
    private int activeLassoMode = LassoSessionPolicy.KEEP;
    private PopupWindow settingHelpPopup;
    private final Handler settingHelpHandler = new Handler(Looper.getMainLooper());
    private final Runnable dismissSettingHelp = () -> {
        if (settingHelpPopup != null) {
            settingHelpPopup.dismiss();
            settingHelpPopup = null;
        }
    };

    private PdfRenderer pdfRenderer;
    private Uri documentUri;
    private final android.os.HandlerThread pdfThread = new android.os.HandlerThread("PdfDetail");
    private Handler pdfWorker;
    private volatile int detailVersion;
    private int currentPage = 0;
    private int totalPages = 0;
    private Bitmap currentPdfBitmap;
    private RectF pdfDestRect = new RectF();
    // [v2.11] 화면상의 페이지 순서와 원본 PDF 페이지 번호를 분리한다.
    // pdfIndex == -1이면 앱 내부 판서 페이지(원본 PDF는 수정되지 않음).
    private static final class PageSlot {
        final int pdfIndex;
        final Bitmap noteImage;
        // 기존 올가미의 완성된 가림 상태만 보관. Bitmap이나 기존 판서는 복제하지 않는다.
        final ArrayList<LassoFocus.Mask> lassoMasks;
        ArrayList<NativeBoardView.Stroke> strokes = new ArrayList<>();
        PageSlot(int pdfIndex, Bitmap noteImage) {
            this(pdfIndex, noteImage, new ArrayList<LassoFocus.Mask>());
        }
        PageSlot(int pdfIndex, Bitmap noteImage, ArrayList<LassoFocus.Mask> masks) {
            this.pdfIndex = pdfIndex;
            this.noteImage = noteImage;
            this.lassoMasks = new ArrayList<>(masks);
        }
        boolean isNote() { return pdfIndex < 0; }
    }
    private final ArrayList<PageSlot> pages = new ArrayList<>();

    private int[] boardColors = {0xFF0F172A, 0xFF143326, 0xFFFFFFFF};
    private int currentBoardIdx = 0;

    public enum ToolMode { HAND, PEN, SMOOTH, HIGHLIGHTER, ERASER, LASSO }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // [필기감 최우선] 시스템 렉 방지를 위한 고정 모드 설정
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyImmersiveMode();

        lassoMode = LassoSessionPolicy.normalize(getSharedPreferences("lasso_settings", MODE_PRIVATE)
                .getInt("mode", LassoSessionPolicy.KEEP));
        pdfThread.start();
        pdfWorker = new Handler(pdfThread.getLooper());
        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(boardColors[currentBoardIdx]);

        // EB-86 하드웨어 최적화 뷰 생성
        boardView = new NativeBoardView(this, boardColors[currentBoardIdx]);
        boardView.focusChanged = () -> {
            if (boardView.focus.isActive() &&
                    LassoSessionPolicy.createNewPageOnSelect(activeLassoMode)) {
                // 올가미 UP 이벤트가 완료된 뒤 별도 UI 작업에서 새 페이지를 생성한다.
                // 필기 MOVE 경로에는 이미지 생성/파일 작업을 추가하지 않는다.
                boardView.post(() -> {
                    if (boardView.focus.isActive() &&
                            LassoSessionPolicy.createNewPageOnSelect(activeLassoMode))
                        createLassoNotePage();
                });
            } else {
                selectTool(focusPen);
                updateFocusDoneButton();
            }
        };
        boardView.focusWorker = new Handler(pdfThread.getLooper());
        boardView.viewportSettled = this::requestPdfDetail;
        pages.add(new PageSlot(-1, null)); // PDF가 없더라도 첫 번째 판서 페이지는 존재
        rootLayout.addView(boardView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        toolbarScrollView = new HorizontalScrollView(this);
        toolbarScrollView.setHorizontalScrollBarEnabled(false);
        bottomToolbar = createFloatingToolbar();
        toolbarScrollView.addView(bottomToolbar, new FrameLayout.LayoutParams(-2, dp(58)));

        // 삼선 메뉴는 스크롤 영역 밖에 고정한다. 가로가 짧아도 메뉴가 사라지지 않는다.
        FrameLayout.LayoutParams tbParams = new FrameLayout.LayoutParams(-1, dp(58));
        tbParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        tbParams.setMargins(dp(12), 0, dp(12), dp(14));
        LinearLayout dock = new LinearLayout(this);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setBackground(createButtonBg(0xFA1E293B));
        dock.addView(createPdfPagerBar());
        dock.addView(toolbarScrollView, new LinearLayout.LayoutParams(0, -1, 1f));
        btnMoreMenu = createIconButton(R.drawable.ic_menu, "추가 메뉴: 배경색 · 배경선 · 앱 종료", 0x33475569,
                v -> showMorePopup(v));
        dock.addView(btnMoreMenu);
        rootLayout.addView(dock, tbParams);

        // 집중 공간에서는 툴바를 찾아가지 않아도 오른쪽 위에서 바로 작업을 마칠 수 있다.
        focusDoneButton = new TextView(this);
        focusDoneButton.setText("✓  완료");
        focusDoneButton.setTextColor(Color.WHITE);
        focusDoneButton.setTextSize(16);
        focusDoneButton.setGravity(Gravity.CENTER);
        focusDoneButton.setPadding(dp(18),0,dp(18),0);
        focusDoneButton.setBackground(settingRoundedBg(0xFF047857,16,0xFF34D399));
        focusDoneButton.setOnClickListener(v -> finishLassoSession());
        focusDoneButton.setVisibility(View.GONE);
        FrameLayout.LayoutParams doneLp = new FrameLayout.LayoutParams(-2,dp(48),Gravity.TOP|Gravity.RIGHT);
        doneLp.setMargins(dp(20),dp(18),dp(20),0);
        rootLayout.addView(focusDoneButton,doneLp);

        setContentView(rootLayout);
        setToolHint(btnToolPen,boardView.isHighSpeedPenEnabled()
                ? "고속펜 (실험) · 길게 눌러 변경" : "빠른펜 · 길게 눌러 변경");
        checkStoragePermissions();
        handleIncomingIntent(getIntent());
    }

    private void checkStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (!Environment.isExternalStorageManager()) {
                    try {
                        Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    } catch (Exception e) {
                        Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        startActivity(intent);
                    }
                }
            } catch (Exception ignored) {}
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, 100);
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
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action) || Intent.ACTION_EDIT.equals(action)) {
            final Uri data = intent.getData();
            if (data != null && boardView != null) {
                boardView.post(() -> loadPdfFromUri(data));
            }
        }
    }

    private void applyImmersiveMode() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersiveMode();
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private LinearLayout createFloatingToolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(4), dp(12), dp(4));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF01E293B);
        bg.setCornerRadius(dp(30));
        bg.setStroke(dp(1), 0x33FFFFFF);
        bar.setBackground(bg);

        // [필기감 최우선] Hand 모드 도구 추가 (이동 및 확대 전용)
        btnToolHand = createIconButton(R.drawable.ic_hand, "이동 및 확대 (Hand)", 0x33475569, v -> selectTool(ToolMode.HAND));
        btnToolPen = createIconButton(R.drawable.ic_pen, "빠른펜 (Pen)", 0x882563EB, v -> selectTool(ToolMode.PEN));
        btnToolSmooth = createIconButton(R.drawable.ic_ruler, "보정펜 (Smooth)", 0x33475569, v -> selectTool(ToolMode.SMOOTH));
        btnToolHighlighter = createIconButton(R.drawable.ic_highlighter, "형광펜", 0x33475569, v -> selectTool(ToolMode.HIGHLIGHTER));
        btnToolEraser = createIconButton(R.drawable.ic_eraser, "지우개", 0x33475569, v -> selectTool(ToolMode.ERASER));
        btnToolLasso = createIconButton(R.drawable.ic_lasso, "올가미 집중 영역 선택", 0x33475569, v -> toggleLassoFocus());
        installLassoLongPress(btnToolLasso);

        // [v2.5.0] 짧게 눌러 선택, 1초간 눌러 각 도구만의 크기 조절 창을 연다.
        installToolSizeLongPress(btnToolPen, ToolMode.PEN);
        installToolSizeLongPress(btnToolSmooth, ToolMode.SMOOTH);
        installToolSizeLongPress(btnToolHighlighter, ToolMode.HIGHLIGHTER);
        installToolSizeLongPress(btnToolEraser, ToolMode.ERASER);

        bar.addView(btnToolHand);
        bar.addView(btnToolPen);
        bar.addView(btnToolSmooth);
        bar.addView(btnToolHighlighter);
        bar.addView(btnToolEraser);
        // [v2.5.0] 기존 맨 끝의 전체삭제 버튼을 지우개 바로 오른쪽으로 이동.
        bar.addView(createIconButton(R.drawable.ic_delete, "화면 전체 삭제", 0x88DC2626,
                v -> boardView.clearCurrentPage()));
        bar.addView(btnToolLasso);
        addDivider(bar);

        int[] colors = {0xFFFFFFFF, 0xFFF59E0B, 0xFFEF4444, 0xFF38BDF8, 0xFF10B981, 0xFFF97316, 0xFFA855F7, 0xFF000000};
        String[] colorNames = {"흰색","노란색","빨간색","하늘색","초록색","주황색","보라색","검은색"};
        int colorIndex = 0;
        for (int c : colors) {
            Button cb = new Button(this);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(30), dp(30));
            p.setMargins(dp(3), 0, dp(3), 0);
            cb.setLayoutParams(p);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL); dot.setColor(c); dot.setStroke(dp(1), 0x44FFFFFF);
            cb.setBackground(dot);
            setToolHint(cb, colorNames[colorIndex++] + " 펜");
            cb.setOnClickListener(v -> {
                boardView.setColor(c);
                if (boardView.currentTool == ToolMode.ERASER || boardView.currentTool == ToolMode.HAND) selectTool(ToolMode.PEN);
            });
            bar.addView(cb);
        }
        addDivider(bar);

        // [v2.5.0] 공용 굵기 버튼을 없앴다. 크기는 위 네 도구의 1초 길게 누르기에서 조절.

        bar.addView(createIconButton(R.drawable.ic_undo, "실행 취소", 0x33475569, v -> boardView.undo()));
        bar.addView(createIconButton(R.drawable.ic_redo, "다시 실행", 0x33475569, v -> boardView.redo()));
        bar.addView(createIconButton(R.drawable.ic_zoom_reset, "100% 복구", 0x33475569, v -> { selectTool(ToolMode.HAND); boardView.resetZoom(); }));
        bar.addView(createIconButton(R.drawable.ic_save, "이미지 저장", 0x88059669, v -> saveBoardToGallery()));
        // 배경색/배경선/종료는 오른쪽 고정 삼선 메뉴에만 둔다.

        return bar;
    }

    /** 오른쪽 삼선 메뉴. 필기 뷰 위에서 열리지만 필기 입력 처리 코드는 건드리지 않는다. */
    private void showMorePopup(View anchor) {
        if (morePopup != null && morePopup.isShowing()) { morePopup.dismiss(); return; }
        if (toolSizePopup != null) toolSizePopup.dismiss();
        if (lassoActionsPopup != null) lassoActionsPopup.dismiss();
        dismissSettingHelp.run();

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10),dp(10),dp(10),dp(10));
        panel.setBackground(settingRoundedBg(0xFF172537,16,0xFF43556C));

        TextView heading = new TextView(this);
        heading.setText("추가 메뉴");
        heading.setTextColor(0xFFE2E8F0);
        heading.setTypeface(null,android.graphics.Typeface.BOLD);
        heading.setTextSize(15);
        heading.setPadding(dp(12),dp(8),dp(8),dp(10));
        panel.addView(heading);

        TextView bg = addMoreItem(panel, R.drawable.ic_palette, "배경색 바꾸기", 0xFFDFE8F3);
        TextView grid = addMoreItem(panel, R.drawable.ic_grid,
                boardView.isGridEnabled() ? "배경선 끄기" : "배경선 켜기", 0xFFDFE8F3);
        TextView clearAll = addMoreItem(panel, R.drawable.ic_delete, "페이지 전체 지우기", 0xFFFCA5A5);
        TextView exit = addMoreItem(panel, R.drawable.ic_close, "앱 종료", 0xFFFDA4AF);

        final int popupWidth = Math.min(dp(250),getResources().getDisplayMetrics().widthPixels-dp(24));
        final int popupHeight = dp(295);
        PopupWindow popup = new PopupWindow(panel,popupWidth,popupHeight,true);
        popup.setBackgroundDrawable(settingRoundedBg(0xFF172537,16,0xFF43556C));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(14));
        popup.setOnDismissListener(() -> { if (morePopup == popup) morePopup=null; });
        morePopup=popup;
        bg.setOnClickListener(v -> { popup.dismiss(); cycleBoardTheme(); });
        grid.setOnClickListener(v -> { popup.dismiss(); boardView.toggleGrid(); });
        clearAll.setOnClickListener(v -> {
            popup.dismiss();
            boardView.clearCurrentPage();
            Toast.makeText(this, "현재 페이지 판서를 모두 지웠습니다 (되돌리기 가능)", Toast.LENGTH_SHORT).show();
        });
        exit.setOnClickListener(v -> { popup.dismiss(); confirmAppExit(); });
        int[] anchorPos = new int[2];
        anchor.getLocationOnScreen(anchorPos);
        int sw=getResources().getDisplayMetrics().widthPixels;
        int sh=getResources().getDisplayMetrics().heightPixels;
        int x=Math.max(dp(8),Math.min(anchorPos[0]+anchor.getWidth()-popupWidth,sw-popupWidth-dp(8)));
        int y=Math.max(dp(8),Math.min(anchorPos[1]-popupHeight-dp(8),sh-popupHeight-dp(8)));
        popup.showAtLocation(rootLayout,Gravity.TOP|Gravity.LEFT,x,y);
    }

    private TextView addMoreItem(LinearLayout panel,int icon,String title,int textColor) {
        TextView item = new TextView(this);
        item.setText(title);
        item.setTextColor(textColor);
        item.setTextSize(16);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setCompoundDrawablesWithIntrinsicBounds(icon,0,0,0);
        item.setCompoundDrawablePadding(dp(16));
        item.setPadding(dp(12),0,dp(12),0);
        item.setBackground(settingRoundedBg(0xFF25374D,10,0xFF33465F));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(51));
        lp.bottomMargin=dp(6);
        panel.addView(item,lp);
        return item;
    }

    private void confirmAppExit() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("앱 종료")
                .setMessage("전자칠판 필기앱을 종료할까요? 저장하지 않은 판서는 사라질 수 있습니다.")
                .setNegativeButton("취소",(d,w) -> d.dismiss())
                .setPositiveButton("종료",(d,w) -> finish())
                .show();
    }

    /**
     * [v2.5.0] 기본 Android long click(약 0.5초) 대신 1000ms를 직접 측정한다.
     * 필기 이벤트와 무관한 툴바 영역에서만 타이머를 사용하므로 판서 지연에 영향이 없다.
     * 스크롤/드래그/취소는 타이머를 취소하고, 길게 누른 뒤 ACTION_UP 클릭은 막는다.
     */
    private void installToolSizeLongPress(View button, ToolMode mode) {
        final Handler handler = new Handler(Looper.getMainLooper());
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        button.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private boolean canceled, popupOpened;
            private final Runnable openPopup = () -> {
                if (canceled || !button.isAttachedToWindow()) return;
                popupOpened = true;
                button.setPressed(false);
                selectTool(mode);
                showToolSizePopup(button, mode);
            };
            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        handler.removeCallbacks(openPopup);
                        downX = event.getX(); downY = event.getY();
                        canceled = false; popupOpened = false;
                        v.setPressed(true);
                        handler.postDelayed(openPopup, 1000L);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(event.getX() - downX) > slop
                                || Math.abs(event.getY() - downY) > slop) {
                            canceled = true;
                            handler.removeCallbacks(openPopup);
                            v.setPressed(false);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        handler.removeCallbacks(openPopup);
                        v.setPressed(false);
                        // 시스템의 긴 누르기 뒤 click 발행을 방지한다.
                        if (!canceled && !popupOpened) v.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        canceled = true;
                        handler.removeCallbacks(openPopup);
                        v.setPressed(false);
                        return true;
                    default:
                        return true;
                }
            }
        });
        // 접근성 서비스의 long-click 동작도 동일한 크기 창에 연결한다.
        button.setOnLongClickListener(v -> {
            selectTool(mode);
            showToolSizePopup(button, mode);
            return true;
        });
    }

    /**
     * [v2.7.0] 설정 창: 필요한 컨트롤만 보여 주고 장문의 도움말은 ⓘ 툴팁으로 이동한다.
     * 설정은 도구별 SharedPreferences에 저장하며, 창 바깥을 누르면 닫힌다.
     */
    private void showToolSizePopup(View anchor, ToolMode mode) {
        dismissSettingHelp.run();
        if (toolSizePopup != null) { toolSizePopup.dismiss(); toolSizePopup = null; }
        final int min, max;
        switch (mode) {
            case ERASER: min = 25; max = 250; break;
            case HIGHLIGHTER: min = 4; max = 72; break;
            default: min = 1; max = 40; break;
        }
        final String name;
        switch (mode) {
            case SMOOTH: name = "보정펜"; break;
            case HIGHLIGHTER: name = "형광펜"; break;
            case ERASER: name = "지우개"; break;
            default: name = "빠른펜"; break;
        }
        final boolean hasCorrection = mode == ToolMode.SMOOTH || mode == ToolMode.HIGHLIGHTER;
        final boolean hasSmartPen = mode == ToolMode.PEN;
        final boolean hasCrosshair = mode == ToolMode.ERASER;
        final int accent = mode == ToolMode.HIGHLIGHTER ? 0xFFFBBF24
                : mode == ToolMode.SMOOTH ? 0xFFA78BFA : 0xFF38BDF8;

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(14), dp(18), dp(16));
        panel.setBackground(settingRoundedBg(0xFF172537, 18, 0xFF43556C));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(19);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1f));
        header.addView(settingInfo("도구를 1초간 누르면 설정 창이 열립니다. "
                + "도구별 설정은 자동 저장되며 바깥을 누르면 닫힙니다."));
        TextView close = new TextView(this);
        close.setText("×");
        close.setTextSize(23);
        close.setGravity(Gravity.CENTER);
        close.setTextColor(0xFFCBD5E1);
        close.setContentDescription("설정 닫기");
        close.setOnClickListener(v -> { if (toolSizePopup != null) toolSizePopup.dismiss(); });
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(38),dp(38));
        closeLp.leftMargin = dp(4);
        header.addView(close, closeLp);
        panel.addView(header);

        View divider = new View(this);
        divider.setBackgroundColor(0x334B6079);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(-1, dp(1));
        divLp.setMargins(0, dp(2), 0, dp(12));
        panel.addView(divider, divLp);

        LinearLayout sizeRow = new LinearLayout(this);
        sizeRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView sizeLabel = settingLabel(hasCrosshair ? "지우개 크기" : "획 굵기");
        sizeRow.addView(sizeLabel);
        sizeRow.addView(settingInfo(hasCrosshair
                ? "화면의 짧은 변을 기준으로 계산한 지우개 원의 지름입니다. "
                  + "문서를 확대하거나 축소해도 화면상의 크기는 그대로 유지됩니다."
                : "이 도구에만 적용되는 획의 굵기입니다. 다른 도구의 굵기는 변경되지 않습니다."));
        TextView value = new TextView(this);
        value.setTextColor(accent);
        value.setTextSize(15);
        value.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        value.setTypeface(null, android.graphics.Typeface.BOLD);
        sizeRow.addView(value, new LinearLayout.LayoutParams(0, dp(36), 1f));
        panel.addView(sizeRow);

        final View preview = new View(this) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                final float cx = getWidth()*.5f, cy = getHeight()*.5f;
                final float sz = boardView.getToolSize(mode);
                if (hasCrosshair) {
                    float radius = Math.min(dp(25), dp(12)*sz/100f);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(0x337DD3FC);
                    canvas.drawCircle(cx, cy, radius, paint);
                    paint.setColor(accent);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(2));
                    canvas.drawCircle(cx, cy, radius, paint);
                    if (boardView.isEraserCrosshairEnabled()) {
                        // 크기 미리보기에서도 원과 십자선은 독립된 크기.
                        float gap = Math.max(radius+dp(5),dp(15));
                        float length = dp(24) + boardView.getEraserCrosshairLength()*dp(13);
                        paint.setColor(0xFF22D3EE);
                        paint.setStrokeWidth(dp(2));
                        canvas.drawLine(cx-length,cy,cx-gap,cy,paint);
                        canvas.drawLine(cx+gap,cy,cx+length,cy,paint);
                        canvas.drawLine(cx,cy-length,cx,cy-gap,paint);
                        canvas.drawLine(cx,cy+gap,cx,cy+length,paint);
                    }
                } else {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeCap(Paint.Cap.ROUND);
                    paint.setColor(mode == ToolMode.HIGHLIGHTER ? 0x99FBBF24 : accent);
                    paint.setStrokeWidth(Math.min(dp(28), dp((int) sz)));
                    canvas.drawLine(dp(24), cy, getWidth()-dp(24), cy, paint);
                }
            }
        };
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(-1,dp(65));
        previewLp.setMargins(0, dp(2), 0, dp(4));
        preview.setBackground(settingRoundedBg(0xFF111D2D, 10, 0xFF304258));
        panel.addView(preview, previewLp);

        SeekBar sizeSlider = new SeekBar(this);
        sizeSlider.setMax(max-min);
        sizeSlider.setProgress(Math.max(0,Math.min(max-min,boardView.getToolSize(mode)-min)));
        panel.addView(sizeSlider,new LinearLayout.LayoutParams(-1,dp(45)));
        Runnable updateSize = () -> {
            int size = boardView.getToolSize(mode);
            value.setText(hasCrosshair ? size+"%" : size+" px");
            preview.invalidate();
        };
        updateSize.run();
        sizeSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean fromUser) {
                if (!fromUser) return;
                boardView.setToolSize(mode,min+progress);
                updateSize.run();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { boardView.persistToolSize(mode); }
        });

        if (hasSmartPen) {
            // [v2.20.0] EasiNote 역추적 분석 기반 적응형 예측 초저지연 고속펜 및 기존 빠른펜
            LinearLayout engineLabelRow=new LinearLayout(this);
            engineLabelRow.setGravity(Gravity.CENTER_VERTICAL);
            engineLabelRow.addView(settingLabel("펜 표시 방식"));
            engineLabelRow.addView(settingInfo("초저지연 고속펜(추천)은 EasiNote2015의 역추적 분석을 바탕으로 "
                    + "속도/곡률 적응형 예측(FastInkPredictor)과 직접 GPU 캔버스 스트리밍을 결합하여 "
                    + "펜촉 지연 시간(Lag)을 최소화합니다. "
                    + "기존 빠른펜은 v2.16.0 기존 64구간 캐시 방식입니다."));
            panel.addView(engineLabelRow);
            LinearLayout engineChoices=new LinearLayout(this);
            LinearLayout.LayoutParams engineLp=new LinearLayout.LayoutParams(-1,dp(43));
            engineLp.bottomMargin=dp(10);
            panel.addView(engineChoices,engineLp);
            TextView[] engineButtons=new TextView[2];
            Runnable refreshEngines=() -> {
                int selected=boardView.isHighSpeedPenEnabled()?1:0;
                for (int i=0;i<engineButtons.length;i++) {
                    engineButtons[i].setBackground(settingRoundedBg(i==selected?0xFF0E7490:0xFF2B3B50,
                            10,i==selected?0xFF22D3EE:0xFF435269));
                    engineButtons[i].setTextColor(i==selected?Color.WHITE:0xFFCBD5E1);
                }
                setToolHint(btnToolPen,selected==1?"초저지연 고속펜 · 길게 눌러 변경":"빠른펜 · 길게 눌러 변경");
            };
            for (int i=0;i<2;i++) {
                final boolean highSpeed=i==1;
                TextView choice=settingOption(highSpeed?"초저지연 고속펜 (추천)":"기존 빠른펜");
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1f);
                lp.setMargins(dp(3),0,dp(3),0);
                engineChoices.addView(choice,lp);
                engineButtons[i]=choice;
                choice.setOnClickListener(v -> {
                    boardView.setHighSpeedPenEnabled(highSpeed);
                    refreshEngines.run();
                });
            }
            refreshEngines.run();
        }

        if (hasSmartPen) {
            LinearLayout smartRow = new LinearLayout(this);
            smartRow.setGravity(Gravity.CENTER_VERTICAL);
            smartRow.addView(settingLabel("스마트 보정"));
            smartRow.addView(settingInfo("기본 꺼짐: 기존 빠른펜 속도와 결과가 그대로 유지됩니다. "
                    + "켜짐: 펜이 멈췄을 때 직선·원·직사각형을 판단하고, "
                    + "다른 긴 곡선은 최고 강도(8단계)로 부드럽게 정리합니다. "
                    + "직선은 펜을 떼지 않고 끌면 길이·기울기를 유지한 채 전체 이동합니다. "
                    + "원·곡선은 정지 시 확정되며 다시 움직이면 새 획이 시작됩니다."));
            View smartSpacer = new View(this);
            smartRow.addView(smartSpacer, new LinearLayout.LayoutParams(0,dp(35),1f));
            TextView smartToggle = settingOption("");
            smartRow.addView(smartToggle, new LinearLayout.LayoutParams(dp(78),dp(34)));
            panel.addView(smartRow);
            Runnable updateSmartToggle = () -> {
                boolean enabled = boardView.isSmartPenEnabled();
                smartToggle.setText(enabled ? "켜짐" : "꺼짐");
                smartToggle.setBackground(settingRoundedBg(enabled ? 0xFF0E7490 : 0xFF334155,
                        10,enabled ? 0xFF22D3EE : 0xFF64748B));
                smartToggle.setTextColor(enabled ? Color.WHITE : 0xFFCBD5E1);
            };
            smartToggle.setOnClickListener(v -> {
                boardView.setSmartPenEnabled(!boardView.isSmartPenEnabled());
                updateSmartToggle.run();
            });
            updateSmartToggle.run();

            LinearLayout pauseRow = new LinearLayout(this);
            pauseRow.setGravity(Gravity.CENTER_VERTICAL);
            pauseRow.addView(settingLabel("멈춤 감지"));
            pauseRow.addView(settingInfo("정해진 시간 동안 움직임이 없을 때만 보정합니다. "
                    + "일반 글씨와 짧은 획은 오인식 방지를 위해 보정하지 않습니다."));
            panel.addView(pauseRow);
            LinearLayout pauseChoices = new LinearLayout(this);
            LinearLayout.LayoutParams choicesLp = new LinearLayout.LayoutParams(-1,dp(40));
            choicesLp.topMargin = dp(3);
            panel.addView(pauseChoices,choicesLp);
            TextView[] pauseButtons = new TextView[4];
            Runnable redrawPause = () -> {
                int current = boardView.getSmartPauseMillis();
                for (int j=0;j<pauseButtons.length;j++) {
                    boolean selected = current == (j+3)*100;
                    pauseButtons[j].setBackground(settingRoundedBg(selected ? 0xFF0E7490 : 0xFF2B3B50,
                            10,selected ? 0xFF22D3EE : 0xFF435269));
                    pauseButtons[j].setTextColor(selected ? Color.WHITE : 0xFFCBD5E1);
                }
            };
            for (int j=0;j<4;j++) {
                int millis = (j+3)*100;
                TextView option=settingOption(String.format(Locale.KOREA,"%.1f초",millis/1000f));
                LinearLayout.LayoutParams optionLp=new LinearLayout.LayoutParams(0,-1,1f);
                optionLp.setMargins(dp(3),0,dp(3),0);
                pauseChoices.addView(option,optionLp);
                pauseButtons[j]=option;
                option.setOnClickListener(v -> {
                    boardView.setSmartPauseMillis(millis);
                    redrawPause.run();
                });
            }
            redrawPause.run();
        }

        if (hasCorrection) {
            LinearLayout correctionRow = new LinearLayout(this);
            correctionRow.setGravity(Gravity.CENTER_VERTICAL);
            correctionRow.addView(settingLabel("곡선 보정"));
            correctionRow.addView(settingInfo("1단계는 원본 필기 형태를 유지합니다. "
                    + "2단계는 기존 최고 강도와 비슷한 약한 보정이며 5~8단계는 강하게 부드럽게 합니다. "
                    + "보정은 획을 마친 뒤 적용됩니다. 직선·원·직사각형 인식은 원래 좌표로 판단합니다."));
            TextView selectedLabel = new TextView(this);
            selectedLabel.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            selectedLabel.setTextColor(accent);
            selectedLabel.setTextSize(14);
            correctionRow.addView(selectedLabel, new LinearLayout.LayoutParams(0,dp(38),1f));
            panel.addView(correctionRow);
            // 터치가 편리한 4개 x 2줄 배치. 8단계를 숫자만으로 간결하게 표현.
            final TextView[] stageButtons = new TextView[8];
            Runnable repaint = () -> {
                int selected = boardView.getCorrectionLevel(mode);
                selectedLabel.setText(selected + " / 8");
                for (int k=0;k<8;k++) {
                    stageButtons[k].setBackground(settingRoundedBg(k+1==selected ? 0xFF2563EB : 0xFF2B3B50,
                            9, k+1==selected ? accent : 0xFF435269));
                    stageButtons[k].setTextColor(k+1==selected ? Color.WHITE : 0xFFCBD5E1);
                }
            };
            for (int row=0;row<2;row++) {
                LinearLayout stages = new LinearLayout(this);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1,dp(42));
                rowLp.bottomMargin = dp(row==0?5:0);
                panel.addView(stages,rowLp);
                for(int col=0;col<4;col++) {
                    int selectedLevel = row*4+col+1;
                    TextView choice = settingOption(String.valueOf(selectedLevel));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,-1,1f);
                    lp.setMargins(dp(3),0,dp(3),0);
                    stages.addView(choice,lp);
                    stageButtons[selectedLevel-1]=choice;
                    choice.setContentDescription("곡선 보정 " + selectedLevel + "단계");
                    choice.setOnClickListener(v -> {
                        boardView.setCorrectionLevel(mode,selectedLevel);
                        boardView.persistCorrectionLevel(mode);
                        repaint.run();
                    });
                }
            }
            repaint.run();
        }

        if (hasCrosshair) {
            // [v2.20.0] EasiNote OBJECT_ERASER 역추적 분석 기반: 획 지우개 vs 영역 지우개 모드 선택
            LinearLayout modeRow = new LinearLayout(this);
            modeRow.setGravity(Gravity.CENTER_VERTICAL);
            modeRow.addView(settingLabel("지우개 방식"));
            modeRow.addView(settingInfo("획 지우개(추천, EasiNote 방식): 선을 스치기만 해도 닿은 획 전체를 즉시 삭제합니다. 판서 수정이 가장 편리합니다. "
                    + "영역 지우개: 원형 반경 안쪽의 픽셀만 부분적으로 지웁니다."));
            panel.addView(modeRow);

            LinearLayout eraserModeChoices = new LinearLayout(this);
            LinearLayout.LayoutParams emcLp = new LinearLayout.LayoutParams(-1, dp(40));
            emcLp.topMargin = dp(2);
            emcLp.bottomMargin = dp(8);
            panel.addView(eraserModeChoices, emcLp);

            TextView[] eraserModeButtons = new TextView[2];
            Runnable updateEraserModeUI = () -> {
                int selected = boardView.getEraserMode() == NativeBoardView.EraserMode.STROKE ? 0 : 1;
                for (int i = 0; i < 2; i++) {
                    boolean sel = i == selected;
                    eraserModeButtons[i].setBackground(settingRoundedBg(sel ? 0xFF0E7490 : 0xFF2B3B50,
                            10, sel ? 0xFF22D3EE : 0xFF435269));
                    eraserModeButtons[i].setTextColor(sel ? Color.WHITE : 0xFFCBD5E1);
                }
                setToolHint(btnToolEraser, selected == 0 ? "획 지우개 · 길게 눌러 변경" : "영역 지우개 · 길게 눌러 변경");
            };

            String[] eraserModeNames = {"획 지우개 (추천)", "영역 지우개"};
            for (int i = 0; i < 2; i++) {
                final NativeBoardView.EraserMode chosen = (i == 0) ? NativeBoardView.EraserMode.STROKE : NativeBoardView.EraserMode.AREA;
                TextView option = settingOption(eraserModeNames[i]);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
                lp.setMargins(dp(3), 0, dp(3), 0);
                eraserModeChoices.addView(option, lp);
                eraserModeButtons[i] = option;
                option.setOnClickListener(v -> {
                    boardView.setEraserMode(chosen);
                    updateEraserModeUI.run();
                });
            }
            updateEraserModeUI.run();

            // [v2.20.0] 페이지 전체 지우기 버튼
            TextView clearAllBtn = new TextView(this);
            clearAllBtn.setText("현재 페이지 판서 전체 지우기");
            clearAllBtn.setGravity(Gravity.CENTER);
            clearAllBtn.setTextColor(0xFFFDA4AF);
            clearAllBtn.setTextSize(14);
            clearAllBtn.setTypeface(null, android.graphics.Typeface.BOLD);
            clearAllBtn.setBackground(settingRoundedBg(0xFF4C1D24, 10, 0xFF991B1B));
            LinearLayout.LayoutParams caLp = new LinearLayout.LayoutParams(-1, dp(40));
            caLp.topMargin = dp(4);
            caLp.bottomMargin = dp(8);
            panel.addView(clearAllBtn, caLp);
            clearAllBtn.setOnClickListener(v -> {
                if (toolSizePopup != null) toolSizePopup.dismiss();
                boardView.clearCurrentPage();
                Toast.makeText(this, "현재 페이지 판서를 모두 지웠습니다 (되돌리기 가능)", Toast.LENGTH_SHORT).show();
            });

            LinearLayout crossRow = new LinearLayout(this);
            crossRow.setGravity(Gravity.CENTER_VERTICAL);
            crossRow.addView(settingLabel("십자선 표시"));
            crossRow.addView(settingInfo("지우개를 움직이는 동안 손바닥 바깥에 십자선 네 팔을 표시합니다. "
                    + "십자선은 위치 안내일 뿐, 실제로 지워지는 범위는 중앙 원 안쪽입니다."));
            TextView enabled = settingOption("");
            enabled.setTextSize(14);
            LinearLayout.LayoutParams enabledLp = new LinearLayout.LayoutParams(dp(78),dp(34));
            enabledLp.leftMargin = dp(10);
            // 마지막 토글 버튼은 일정한 폭으로 오른쪽에 고정.
            View spacer = new View(this);
            crossRow.addView(spacer,new LinearLayout.LayoutParams(0,dp(36),1f));
            crossRow.addView(enabled, enabledLp);
            panel.addView(crossRow);
            Runnable updateSwitch = () -> {
                boolean on = boardView.isEraserCrosshairEnabled();
                enabled.setText(on ? "켜짐" : "꺼짐");
                enabled.setBackground(settingRoundedBg(on ? 0xFF0E7490 : 0xFF334155,10,
                        on ? 0xFF22D3EE : 0xFF64748B));
                enabled.setTextColor(on ? Color.WHITE : 0xFFCBD5E1);
                preview.invalidate();
            };
            enabled.setOnClickListener(v -> {
                boardView.setEraserCrosshairEnabled(!boardView.isEraserCrosshairEnabled());
                updateSwitch.run();
            });
            updateSwitch.run();

            LinearLayout lengthRow = new LinearLayout(this);
            lengthRow.setGravity(Gravity.CENTER_VERTICAL);
            lengthRow.addView(settingLabel("십자선 길이"));
            lengthRow.addView(settingInfo("짧게·보통·길게 중에서 선택합니다. "
                    + "손바닥보다 바깥으로 보이도록 중앙 빈 공간을 두며, "
                    + "문서를 확대해도 화면상의 길이는 변하지 않습니다."));
            panel.addView(lengthRow);
            LinearLayout modes = new LinearLayout(this);
            LinearLayout.LayoutParams modesLp = new LinearLayout.LayoutParams(-1,dp(40));
            modesLp.topMargin = dp(2);
            panel.addView(modes,modesLp);
            TextView[] modeButtons = new TextView[3];
            Runnable updateModes = () -> {
                int selected = boardView.getEraserCrosshairLength();
                for (int i=0;i<3;i++) {
                    modeButtons[i].setBackground(settingRoundedBg(i+1==selected ? 0xFF0E7490 : 0xFF2B3B50,
                            10, i+1==selected ? 0xFF22D3EE : 0xFF435269));
                    modeButtons[i].setTextColor(i+1==selected ? Color.WHITE : 0xFFCBD5E1);
                }
                preview.invalidate();
            };
            String[] modeNames = {"짧게", "보통", "길게"};
            for (int i=0;i<3;i++) {
                final int selectedLength=i+1;
                TextView option = settingOption(modeNames[i]);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,-1,1f);
                lp.setMargins(dp(3),0,dp(3),0);
                modes.addView(option,lp);
                modeButtons[i]=option;
                option.setOnClickListener(v -> {
                    boardView.setEraserCrosshairLength(selectedLength);
                    updateModes.run();
                });
            }
            updateModes.run();

            // [v2.8] 실제 접촉면이 넓게 판정된 경우만 지우는 원을 손바닥 위에 배치.
            // 작은 손가락으로 도구 지우개를 사용할 때는 항상 접촉 중심에서 지운다.
            LinearLayout positionHeader = new LinearLayout(this);
            positionHeader.setGravity(Gravity.CENTER_VERTICAL);
            positionHeader.addView(settingLabel("지우개 위치"));
            positionHeader.addView(settingInfo("손바닥 위쪽: 넓은 접촉의 크기로 최상단을 추정하고, "
                    + "지우개 원 전체가 그 위에 보이게 이동합니다. 실제 지워지는 곳도 원과 같습니다. "
                    + "손가락 끝은 중앙 방식 그대로이며, 화면 상단에서는 원이 화면 밖으로 나가지 않게 제한됩니다."));
            LinearLayout.LayoutParams posHeadLp = new LinearLayout.LayoutParams(-1,dp(34));
            posHeadLp.topMargin = dp(7);
            panel.addView(positionHeader,posHeadLp);
            LinearLayout positionOptions = new LinearLayout(this);
            LinearLayout.LayoutParams posOptionsLp = new LinearLayout.LayoutParams(-1,dp(40));
            panel.addView(positionOptions,posOptionsLp);
            TextView[] positionButtons = new TextView[2];
            Runnable updatePosition = () -> {
                boolean top=boardView.isEraserPalmTopEnabled();
                for (int i=0;i<positionButtons.length;i++) {
                    boolean selected=(i==1)==top;
                    positionButtons[i].setBackground(settingRoundedBg(selected ? 0xFF0E7490 : 0xFF2B3B50,
                            10,selected ? 0xFF22D3EE : 0xFF435269));
                    positionButtons[i].setTextColor(selected ? Color.WHITE : 0xFFCBD5E1);
                }
            };
            String[] positionLabels={"중앙", "손바닥 위쪽"};
            for (int i=0;i<2;i++) {
                final boolean top=i==1;
                TextView option=settingOption(positionLabels[i]);
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1f);
                lp.setMargins(dp(3),0,dp(3),0);
                positionOptions.addView(option,lp);
                positionButtons[i]=option;
                option.setOnClickListener(v -> {
                    boardView.setEraserPalmTopEnabled(top);
                    updatePosition.run();
                });
            }
            updatePosition.run();
        }

        final int screenWidth = getResources().getDisplayMetrics().widthPixels;
        final int screenHeight = getResources().getDisplayMetrics().heightPixels;
        final int popupWidth = Math.min(dp(380),screenWidth-dp(24));
        panel.measure(View.MeasureSpec.makeMeasureSpec(popupWidth,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        final int popupHeight = panel.getMeasuredHeight();
        PopupWindow popup = new PopupWindow(panel,popupWidth,popupHeight,true);
        popup.setBackgroundDrawable(settingRoundedBg(0xFF172537,18,0xFF43556C));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(14));
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        popup.setOnDismissListener(() -> {
            settingHelpHandler.removeCallbacks(dismissSettingHelp);
            dismissSettingHelp.run();
            boardView.persistToolSize(mode);
            if (toolSizePopup == popup) toolSizePopup = null;
        });
        toolSizePopup=popup;
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int desiredX = loc[0]+(anchor.getWidth()-popupWidth)/2;
        int x = Math.max(dp(8),Math.min(desiredX, screenWidth-popupWidth-dp(8)));
        int y = Math.max(dp(8),Math.min(loc[1]-popupHeight-dp(10),screenHeight-popupHeight-dp(8)));
        popup.showAtLocation(rootLayout,Gravity.TOP|Gravity.LEFT,x,y);
    }

    /** 미세한 테두리와 충분한 라운드를 가진 일관된 다크 UI. */
    private GradientDrawable settingRoundedBg(int color,int radius,int border) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radius));
        bg.setStroke(dp(1),border);
        return bg;
    }

    private TextView settingLabel(String name) {
        TextView v = new TextView(this);
        v.setText(name);
        v.setTextColor(0xFFE2E8F0);
        v.setTextSize(14);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    private TextView settingOption(String label) {
        TextView v = new TextView(this);
        v.setText(label);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(0xFFE2E8F0);
        v.setTextSize(15);
        v.setTypeface(null,android.graphics.Typeface.BOLD);
        v.setBackground(settingRoundedBg(0xFF2B3B50,10,0xFF435269));
        v.setFocusable(true);
        return v;
    }

    private TextView settingInfo(String explanation) {
        TextView help = new TextView(this);
        help.setText("ⓘ");
        help.setTextSize(18);
        help.setTextColor(0xFF7DD3FC);
        help.setGravity(Gravity.CENTER);
        help.setContentDescription("설명 보기");
        help.setFocusable(true);
        help.setOnClickListener(v -> showSettingHelp(help,explanation));
        help.setOnLongClickListener(v -> { showSettingHelp(help,explanation); return true; });
        help.setLayoutParams(new LinearLayout.LayoutParams(dp(36),dp(36)));
        return help;
    }

    /** 설명은 별도의 작고 비모달인 도움말 버블로 표시. 다른 설정은 그대로 조작할 수 있다. */
    private void showSettingHelp(View anchor,String explanation) {
        settingHelpHandler.removeCallbacks(dismissSettingHelp);
        dismissSettingHelp.run();
        TextView message = new TextView(this);
        message.setText(explanation);
        message.setTextColor(0xFFF1F5F9);
        message.setTextSize(13);
        message.setLineSpacing(dp(2),1.05f);
        message.setPadding(dp(14),dp(12),dp(14),dp(12));
        message.setBackground(settingRoundedBg(0xFF283D55,12,0xFF60A5FA));
        int w = Math.min(dp(285),getResources().getDisplayMetrics().widthPixels-dp(24));
        message.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        int h=message.getMeasuredHeight();
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int x = Math.max(dp(8),Math.min(loc[0]-w+anchor.getWidth(),screenW-w-dp(8)));
        int proposedY=loc[1]+anchor.getHeight()+dp(4);
        int y = proposedY+h+dp(6) <= screenH ? proposedY : Math.max(dp(8),loc[1]-h-dp(5));
        PopupWindow tip = new PopupWindow(message,w,h,false);
        tip.setBackgroundDrawable(settingRoundedBg(0xFF283D55,12,0xFF60A5FA));
        tip.setOutsideTouchable(true);
        tip.setElevation(dp(15));
        tip.setOnDismissListener(() -> { if (settingHelpPopup == tip) settingHelpPopup=null; });
        settingHelpPopup=tip;
        tip.showAtLocation(rootLayout,Gravity.TOP|Gravity.LEFT,x,y);
        settingHelpHandler.postDelayed(dismissSettingHelp,5000L);
    }

    public void cycleBoardTheme() {
        currentBoardIdx = (currentBoardIdx + 1) % boardColors.length;
        rootLayout.setBackgroundColor(boardColors[currentBoardIdx]);
        boardView.setBoardColor(boardColors[currentBoardIdx]);
    }

    public void selectTool(ToolMode mode) {
        boardView.setToolMode(mode);
        if (btnToolHand != null) btnToolHand.setBackground(createButtonBg(mode == ToolMode.HAND ? 0x882563EB : 0x33475569));
        if (btnToolPen != null) {
            btnToolPen.setBackground(createButtonBg(mode == ToolMode.PEN ? 0x882563EB : 0x33475569));
            setToolHint(btnToolPen, boardView.isHighSpeedPenEnabled()
                    ? "초저지연 고속펜 · 길게 눌러 설정"
                    : "빠른펜 · 길게 눌러 설정");
        }
        if (btnToolSmooth != null) btnToolSmooth.setBackground(createButtonBg(mode == ToolMode.SMOOTH ? 0x887C3AED : 0x33475569));
        if (btnToolHighlighter != null) btnToolHighlighter.setBackground(createButtonBg(mode == ToolMode.HIGHLIGHTER ? 0x88F59E0B : 0x33475569));
        if (btnToolEraser != null) {
            btnToolEraser.setBackground(createButtonBg(mode == ToolMode.ERASER ? 0x88EF4444 : 0x33475569));
            setToolHint(btnToolEraser, boardView.getEraserMode() == NativeBoardView.EraserMode.STROKE
                    ? "획 지우개 (스침 삭제) · 길게 눌러 설정"
                    : "영역 지우개 (부분 삭제) · 길게 눌러 설정");
        }
        if (btnToolLasso != null) {
            boolean on = mode == ToolMode.LASSO || boardView.focus.isActive();
            btnToolLasso.setSelected(on);
            btnToolLasso.setBackground(createButtonBg(on ? 0xFF047857 : 0x33475569));
            setToolHint(btnToolLasso, "올가미 · " + lassoModeName(lassoMode)
                    + (on ? " · 완료" : " · 영역 선택"));
        }
    }

    /** 툴바 입력만 처리: 기존 펜 터치 이벤트와 타이머를 공유하지 않는다. */
    private void installLassoLongPress(View button) {
        final Handler handler = new Handler(Looper.getMainLooper());
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        button.setOnTouchListener(new View.OnTouchListener() {
            float x, y;
            boolean canceled, opened;
            final Runnable open = () -> {
                if (canceled || !button.isAttachedToWindow()) return;
                opened = true;
                button.setPressed(false);
                showLassoActionsPopup(button);
            };
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        x = e.getX(); y = e.getY();
                        canceled = false; opened = false;
                        v.setPressed(true);
                        handler.removeCallbacks(open);
                        handler.postDelayed(open, 1000L);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(e.getX()-x) > slop || Math.abs(e.getY()-y) > slop) {
                            canceled = true; handler.removeCallbacks(open); v.setPressed(false);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        handler.removeCallbacks(open); v.setPressed(false);
                        if (!canceled && !opened) v.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        canceled = true; handler.removeCallbacks(open); v.setPressed(false);
                        return true;
                    default: return true;
                }
            }
        });
        button.setOnLongClickListener(v -> { showLassoActionsPopup(v); return true; });
    }

    /** 1초 길게 누르기는 아래 세 가지 중 하나를 '기본 모드'로 고정한다. */
    private void showLassoActionsPopup(View anchor) {
        dismissSettingHelp.run();
        if (lassoActionsPopup != null) lassoActionsPopup.dismiss();
        if (toolSizePopup != null) toolSizePopup.dismiss();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(12), dp(14), dp(14));
        panel.setBackground(settingRoundedBg(0xFF172537, 16, 0xFF43556C));
        TextView title = settingLabel("올가미 기본 모드");
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextSize(17);
        panel.addView(title, new LinearLayout.LayoutParams(-1, dp(39)));
        addLassoModeOption(panel, "판서 유지",
                "영역 선택 후 판서한 내용을 남기고 완료를 누르면 원본 화면으로 돌아갑니다.",
                LassoSessionPolicy.KEEP);
        addLassoModeOption(panel, "판서 삭제",
                "영역 선택 후 판서한 내용만 버리고 완료를 누르면 원본 상태로 돌아갑니다.",
                LassoSessionPolicy.DISCARD);
        addLassoModeOption(panel, "새 페이지",
                "영역 선택을 끝내면 원본은 그대로 두고 다음에 새 페이지를 즉시 삽입합니다.",
                LassoSessionPolicy.NEW_PAGE);
        int width = Math.min(dp(355), getResources().getDisplayMetrics().widthPixels - dp(16));
        int height = dp(225);
        PopupWindow popup = new PopupWindow(panel, width, height, true);
        popup.setBackgroundDrawable(settingRoundedBg(0xFF172537, 16, 0xFF43556C));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(14));
        popup.setOnDismissListener(() -> {
            dismissSettingHelp.run();
            if (lassoActionsPopup == popup) lassoActionsPopup = null;
        });
        lassoActionsPopup = popup;
        int[] loc = new int[2]; anchor.getLocationOnScreen(loc);
        int maxW = getResources().getDisplayMetrics().widthPixels;
        int maxH = getResources().getDisplayMetrics().heightPixels;
        int x = Math.max(dp(8), Math.min(loc[0] - (width-anchor.getWidth())/2, maxW-width-dp(8)));
        int y = Math.max(dp(8), Math.min(loc[1]-height-dp(8), maxH-height-dp(8)));
        popup.showAtLocation(rootLayout, Gravity.TOP | Gravity.LEFT, x, y);
    }

    private void addLassoModeOption(LinearLayout panel, String label, String tooltip, int mode) {
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView radio = settingOption((lassoMode == mode ? "◉   " : "○   ") + label);
        radio.setTextSize(15);
        radio.setTextColor(lassoMode == mode ? 0xFF6EE7B7 : Color.WHITE);
        radio.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        radio.setPadding(dp(12), 0, dp(6), 0);
        line.addView(radio, new LinearLayout.LayoutParams(0, dp(46), 1f));
        line.addView(settingInfo(tooltip));
        View.OnClickListener saveMode = v -> {
            lassoMode = LassoSessionPolicy.normalize(mode);
            getSharedPreferences("lasso_settings", MODE_PRIVATE).edit()
                    .putInt("mode", lassoMode).apply();
            if (lassoActionsPopup != null) lassoActionsPopup.dismiss();
            setToolHint(btnToolLasso, "올가미: " + lassoModeName(lassoMode));
        };
        radio.setOnClickListener(saveMode);
        line.setOnClickListener(saveMode);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, dp(48));
        rowParams.bottomMargin = dp(3);
        panel.addView(line, rowParams);
    }

    private String lassoModeName(int mode) {
        if (mode == LassoSessionPolicy.DISCARD) return "판서 삭제";
        if (mode == LassoSessionPolicy.NEW_PAGE) return "새 페이지";
        return "판서 유지";
    }

    /** 기존 집중 화면을 종료할 때 선택한 '작업 모드'를 자동 적용한다. */
    private void finishLassoSession() {
        if (!boardView.focus.isActive()) return;
        boardView.finishFocus(LassoSessionPolicy.keepInkOnFinish(activeLassoMode));
        selectTool(focusPen);
        updateFocusDoneButton();
        boardView.invalidate();
    }

    /**
     * NEW_PAGE: 원본 PDF 페이지 번호와 기존 올가미의 완성된 마스크만 복제한다.
     * 이미 있는 PDF 페이지 렌더러/올가미 엔진을 재사용한다.
     * - 기존 필기(currentStrokes)는 새 페이지에 복사하지 않는다.
     * - 배경색/선택영역/좌표를 새로 계산하거나 스크린샷을 만들지 않는다.
     * - 원본을 복원한 후 별도 페이지로 전환하므로 원본에 지우개 영향이 없다.
     */
    private void createLassoNotePage() {
        if (!boardView.focus.isActive() || currentPage < 0 || currentPage >= pages.size()) return;
        final LassoFocus.Mask selection = boardView.copyActiveLassoMask();
        if (selection == null) {
            Toast.makeText(this, "선택 영역이 없어 새 페이지를 만들지 않았습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        final PageSlot source = pages.get(currentPage);
        // 이전 복제 페이지에서 재선택할 때도 보이는 모양을 그대로 계승한다.
        final ArrayList<LassoFocus.Mask> masks = new ArrayList<>(source.lassoMasks);
        masks.add(selection);
        // noteImage=null: 기존 판서 이미지/고해상도 Bitmap을 절대 복제하지 않는다.
        final PageSlot cloned = new PageSlot(source.pdfIndex, null, masks);
        boardView.finishFocus(false);
        final int origin = currentPage;
        final int destination = origin + 1;
        pages.add(destination, cloned);
        totalPages = pages.size();
        goToPage(destination);
        if (currentPage != destination) {
            pages.remove(destination);
            totalPages = pages.size();
            updatePdfControls();
            Toast.makeText(this, "새 페이지 이동에 실패해 원본을 유지했습니다.", Toast.LENGTH_LONG).show();
        }
        selectTool(focusPen);
        updateFocusDoneButton();
    }

    private void updateFocusDoneButton() {
        if (focusDoneButton == null || boardView == null) return;
        boolean active = boardView.focus.isActive();
        focusDoneButton.setVisibility(active ? View.VISIBLE : View.GONE);
        if (active) {
            focusDoneButton.setText(activeLassoMode == LassoSessionPolicy.DISCARD
                    ? "↩  판서 삭제" : activeLassoMode == LassoSessionPolicy.NEW_PAGE
                    ? "↩  선택 취소" : "✓  판서 유지");
        }
    }

    private void toggleLassoFocus() {
        if (boardView.focus.isActive()) {
            finishLassoSession();
        } else if (boardView.currentTool == ToolMode.LASSO) {
            selectTool(focusPen);
        } else {
            ToolMode tool = boardView.currentTool;
            focusPen = tool == ToolMode.SMOOTH || tool == ToolMode.HIGHLIGHTER ? tool : ToolMode.PEN;
            activeLassoMode = lassoMode; // 세션 시작할 때 모드를 고정한다.
            boardView.lassoAutoPage = LassoSessionPolicy.createNewPageOnSelect(activeLassoMode);
            selectTool(ToolMode.LASSO);
        }
        updateFocusDoneButton();
        boardView.invalidate();
    }

    public void saveBoardToGallery() {
        final Bitmap fullBmp = boardView.createFullExportBitmap();
        new Thread(() -> {
            try {
                File picturesDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "GNFLHS_Whiteboard");
                if (!picturesDir.exists()) picturesDir.mkdirs();
                String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(new Date());
                File file = new File(picturesDir, "GNFLHS_판서_" + timeStamp + ".png");
                FileOutputStream fos = new FileOutputStream(file);
                fullBmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.flush(); fos.close();
                Intent mediaScan = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                mediaScan.setData(Uri.fromFile(file)); sendBroadcast(mediaScan);
                runOnUiThread(() -> Toast.makeText(this, "이미지가 저장되었습니다.", Toast.LENGTH_SHORT).show());
            } catch (Exception e) { runOnUiThread(() -> Toast.makeText(this, "저장 실패", Toast.LENGTH_SHORT).show()); }
        }).start();
    }

    private GradientDrawable createButtonBg(int color) {
        GradientDrawable gd = new GradientDrawable(); gd.setColor(color); gd.setCornerRadius(dp(12)); return gd;
    }

    private LinearLayout createPdfPagerBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), 0, dp(4), 0);
        btnPrevPage = createIconButton(R.drawable.ic_chevron_left, "이전 페이지", 0x00475569, v -> goToPage(currentPage - 1));
        btnNextPage = createIconButton(R.drawable.ic_chevron_right, "다음 페이지", 0x00475569, v -> goToPage(currentPage + 1));
        txtPageIndicator = new TextView(this);
        txtPageIndicator.setGravity(Gravity.CENTER);
        txtPageIndicator.setMinimumWidth(dp(48));
        txtPageIndicator.setPadding(dp(12), 0, dp(12), 0);
        txtPageIndicator.setTextSize(15);
        txtPageIndicator.setTextColor(Color.WHITE);
        txtPageIndicator.setBackground(createButtonBg(0x4438BDF8));
        txtPageIndicator.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_pdf, 0, 0, 0);
        txtPageIndicator.setOnClickListener(v -> {
            if (pdfRenderer == null && pages.size() == 1) {
                showPdfBrowserDialog();
            } else if (pagePicker == null) {
                pagePicker = new PagePicker();
                pagePicker.show();
            }
        });

        btnAddBlankPage = createIconButton(R.drawable.ic_add, "설명용 빈 판서 페이지 추가", 0x00475569, v -> insertBlankPage());
        btnPdfList = createIconButton(R.drawable.ic_folder_open, "다른 PDF 교과서/문서 열기", 0x00475569, v -> showPdfBrowserDialog());
        btnClosePdf = createIconButton(R.drawable.ic_file_close, "PDF 닫기 (칠판 복귀)", 0x00475569, v -> closePdf());

        bar.addView(btnPrevPage);
        bar.addView(txtPageIndicator, new LinearLayout.LayoutParams(-2, dp(48)));
        bar.addView(btnNextPage);
        bar.addView(btnAddBlankPage);
        bar.addView(btnPdfList);
        bar.addView(btnClosePdf);

        addDivider(bar);
        updatePdfControls();
        return bar;
    }

    private void updatePdfControls() {
        boolean open = pdfRenderer != null || pages.size() > 1;
        txtPageIndicator.setText(open ? (currentPage + 1) + " / " + totalPages : "PDF 열기");
        txtPageIndicator.setCompoundDrawablePadding(dp(6));
        setToolHint(txtPageIndicator, open ? "페이지 찾아보기 (터치 시 쪽 이동)" : "PDF 교과서/학습지 열기");

        btnPrevPage.setVisibility(open ? View.VISIBLE : View.GONE);
        btnNextPage.setVisibility(open ? View.VISIBLE : View.GONE);
        if (btnAddBlankPage != null) btnAddBlankPage.setVisibility(open ? View.VISIBLE : View.GONE);
        if (btnPdfList != null) btnPdfList.setVisibility(open ? View.VISIBLE : View.GONE);
        if (btnClosePdf != null) btnClosePdf.setVisibility(open ? View.VISIBLE : View.GONE);

        enableAction(btnPrevPage, currentPage > 0);
        enableAction(btnNextPage, currentPage + 1 < totalPages);
    }

    private void insertBlankPage() {
        int insertIdx = currentPage + 1;
        pages.add(insertIdx, new PageSlot(-1, null));
        totalPages = pages.size();
        goToPage(insertIdx);
        Toast.makeText(this, "설명용 빈 판서 페이지가 추가되었습니다. (수업 후 다음 쪽 이동 가능)", Toast.LENGTH_SHORT).show();
    }

    public void showPdfBrowserDialog() {
        new PdfBrowserDialog(this, new PdfBrowserDialog.OnPdfSelectedListener() {
            @Override
            public void onPdfSelected(File file) {
                loadPdfFromFile(file);
            }

            @Override
            public void onOpenSystemPicker() {
                openPdfPicker();
            }
        }).show();
    }

    public void loadPdfFromFile(File file) {
        if (file == null || !file.exists()) {
            Toast.makeText(this, "파일을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        loadPdfFromUri(Uri.fromFile(file));
    }

    private void enableAction(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.3f);
    }

    private final class PagePicker {
        final android.app.Dialog dialog = new android.app.Dialog(MainActivity.this);
        final android.widget.GridView grid = new android.widget.GridView(MainActivity.this);
        final android.widget.ImageView image = new android.widget.ImageView(MainActivity.this);
        final TextView indicator = new TextView(MainActivity.this);
        final Handler worker = new Handler(pdfThread.getLooper());
        final Uri uri = documentUri;
        int selected = currentPage;
        volatile int revision;
        Bitmap previewBitmap;
        View previous, next, apply;

        void show() {
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            LinearLayout panel = new LinearLayout(MainActivity.this);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(dp(12), dp(12), dp(12), dp(8));
            panel.setBackground(createButtonBg(0xFF1E293B));
            LinearLayout body = new LinearLayout(MainActivity.this);
            panel.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));
            grid.setNumColumns(android.widget.GridView.AUTO_FIT);
            grid.setColumnWidth(dp(46));
            grid.setVerticalSpacing(dp(4));
            grid.setHorizontalSpacing(dp(4));
            grid.setAdapter(new android.widget.BaseAdapter() {
                public int getCount() { return totalPages; }
                public Object getItem(int i) { return i; }
                public long getItemId(int i) { return i; }
                public View getView(int i, View recycled, android.view.ViewGroup parent) {
                    TextView cell = recycled == null ? new TextView(MainActivity.this) : (TextView) recycled;
                    cell.setGravity(Gravity.CENTER);
                    cell.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, dp(48)));
                    cell.setText(String.valueOf(i + 1));
                    cell.setTextColor(Color.WHITE);
                    cell.setBackground(createButtonBg(i == selected ? 0xFF2563EB : 0xFF334155));
                    cell.setSelected(i == selected);
                    setToolHint(cell, (i + 1) + "쪽 미리보기");
                    cell.setOnClickListener(v -> select(i, false));
                    return cell;
                }
            });
            body.addView(grid, new LinearLayout.LayoutParams(0, -1, 0.42f));
            image.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            image.setBackground(createButtonBg(0xFF0F172A));
            LinearLayout.LayoutParams picture = new LinearLayout.LayoutParams(0, -1, 0.58f);
            picture.leftMargin = dp(12);
            body.addView(image, picture);
            LinearLayout actions = new LinearLayout(MainActivity.this);
            actions.setGravity(Gravity.CENTER_VERTICAL);
            panel.addView(actions);
            actions.addView(createIconButton(R.drawable.ic_folder_open, "다른 PDF 열기", 0, v -> { dialog.dismiss(); showPdfBrowserDialog(); }));
            actions.addView(createIconButton(R.drawable.ic_file_close, "PDF 닫고 칠판으로 돌아가기", 0, v -> { dialog.dismiss(); closePdf(); }));
            actions.addView(new View(MainActivity.this), new LinearLayout.LayoutParams(0, 1, 1f));
            previous = createIconButton(R.drawable.ic_chevron_left, "이전 쪽 미리보기", 0, v -> select(selected - 1, true));
            next = createIconButton(R.drawable.ic_chevron_right, "다음 쪽 미리보기", 0, v -> select(selected + 1, true));
            actions.addView(previous);
            indicator.setTextColor(Color.WHITE);
            actions.addView(indicator);
            actions.addView(next);
            actions.addView(new View(MainActivity.this), new LinearLayout.LayoutParams(0, 1, 1f));
            actions.addView(createIconButton(R.drawable.ic_close, "미리보기 취소", 0, v -> dialog.dismiss()));
            apply = createIconButton(R.drawable.ic_check, "선택한 쪽으로 이동", 0xFF2563EB, v -> { dialog.dismiss(); goToPage(selected); });
            actions.addView(apply);
            dialog.setContentView(panel);
            dialog.setOnDismissListener(d -> {
                ++revision;
                worker.removeCallbacksAndMessages(null);
                image.setImageDrawable(null);
                if (previewBitmap != null && !previewBitmap.isRecycled()) previewBitmap.recycle();
                previewBitmap = null;
                pagePicker = null;
            });
            Window w = dialog.getWindow();
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility());
            w.setLayout(Math.min(dp(750), rootLayout.getWidth() * 96 / 100),
                    Math.min(dp(440), rootLayout.getHeight() * 88 / 100));
            dialog.show();
            select(currentPage, true);
        }

        void select(int page, boolean scroll) {
            if (page < 0 || page >= totalPages) return;
            selected = page;
            final int token = ++revision;
            worker.removeCallbacksAndMessages(null);
            indicator.setText((page + 1) + "/" + totalPages);
            grid.invalidateViews();
            if (scroll && (grid.getChildCount() == 0 || page < grid.getFirstVisiblePosition() || page > grid.getLastVisiblePosition())) grid.setSelection(page);
            enableAction(previous, page > 0);
            enableAction(next, page + 1 < totalPages);
            enableAction(apply, false);
            setToolHint(apply, (page + 1) + "쪽으로 이동");
            image.setImageDrawable(null);
            if (previewBitmap != null && !previewBitmap.isRecycled()) previewBitmap.recycle();
            previewBitmap = null;
            final PageSlot target = pages.get(page);
            if (target.isNote()) {
                image.setImageBitmap(target.noteImage);
                image.setContentDescription((page + 1) + "쪽 판서 페이지");
                enableAction(apply,true);
                return;
            }
            worker.post(() -> {
                try (ParcelFileDescriptor fd = openFileDescriptorSafely(uri);
                     PdfRenderer renderer = new PdfRenderer(fd);
                     PdfRenderer.Page source = renderer.openPage(target.pdfIndex)) {
                    if (token != revision) return;
                    float scale = 640f / Math.max(source.getWidth(), source.getHeight());
                    Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.round(source.getWidth() * scale)),
                            Math.max(1, Math.round(source.getHeight() * scale)), Bitmap.Config.ARGB_8888);
                    renderOnPaper(source, bitmap, null);
                    drawLassoPreviewMasks(bitmap, source, target.lassoMasks);
                    runOnUiThread(() -> {
                        if (token != revision) { bitmap.recycle(); return; }
                        previewBitmap = bitmap;
                        image.setImageBitmap(bitmap);
                        image.setContentDescription((page + 1) + "쪽 미리보기");
                        enableAction(apply, true);
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> { if (token == revision) Toast.makeText(MainActivity.this, "이 페이지를 미리볼 수 없습니다.", Toast.LENGTH_SHORT).show(); });
                }
            });
        }
    }

    private void setToolHint(View view, String label) {
        view.setContentDescription(label);
        if (Build.VERSION.SDK_INT >= 26) view.setTooltipText(label);
        else view.setOnLongClickListener(v -> {
            Toast.makeText(this, label, Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    private ImageButton createIconButton(int icon, String label, int color, View.OnClickListener listener) {
        ImageButton button = new ImageButton(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(48), dp(48));
        params.setMargins(dp(2), 0, dp(2), 0);
        button.setLayoutParams(params);
        button.setBackground(createButtonBg(color));
        button.setImageResource(icon);
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        setToolHint(button, label);
        button.setOnClickListener(listener);
        return button;
    }

    private void addDivider(LinearLayout bar) {
        View div = new View(this); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(1), dp(22));
        p.setMargins(dp(8), 0, dp(8), 0); div.setLayoutParams(p); div.setBackgroundColor(0x33FFFFFF); bar.addView(div);
    }

    // v2.12: 안정적으로 파일 선택이 되던 v2.9.0 시스템 문서 선택기 복구.
    // 폴더 전체 권한 요구/앱 자체 탐색기를 사용하지 않는다.
    private void openPdfPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/pdf");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, REQUEST_CODE_PICK_PDF);
        } catch (RuntimeException e) {
            android.util.Log.w(TAG, "System PDF picker unavailable", e);
            Toast.makeText(this, "PDF 선택 창을 열 수 없습니다.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CODE_PICK_PDF || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException | IllegalArgumentException ignored) {
            // 읽기 권한만 가진 일회성 제공자에서도 PDF를 열 수 있도록 유지.
        }
        loadPdfFromUri(uri);
    }

    private ParcelFileDescriptor openFileDescriptorSafely(Uri uri) throws Exception {
        if (uri == null) return null;
        if ("file".equalsIgnoreCase(uri.getScheme()) || (uri.getPath() != null && uri.getPath().startsWith("/"))) {
            File f = new File(uri.getPath());
            if (f.exists()) {
                return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
            }
        }
        return getContentResolver().openFileDescriptor(uri, "r");
    }

    private String getFileNameFromUri(Uri uri) {
        if (uri == null) return "";
        try {
            if ("file".equalsIgnoreCase(uri.getScheme()) || uri.getPath() != null) {
                return new File(uri.getPath()).getName();
            }
            String last = uri.getLastPathSegment();
            if (last != null) return last;
        } catch (Exception ignored) {}
        return "PDF 문서";
    }

    private void loadPdfFromUri(Uri uri) {
        if (uri == null) return;
        PdfRenderer candidate = null;
        ParcelFileDescriptor opened = null;
        try {
            opened = openFileDescriptorSafely(uri);
            if (opened == null) {
                throw new java.io.FileNotFoundException("Cannot open file: " + uri);
            }
            candidate = new PdfRenderer(opened);
            opened = null; // PdfRenderer owns the descriptor.
            Bitmap first = renderPage(candidate, 0);
            closePdf();
            pdfRenderer = candidate;
            candidate = null;
            documentUri = uri;
            pages.clear();
            for (int i = 0; i < pdfRenderer.getPageCount(); i++) pages.add(new PageSlot(i, null));
            totalPages = pages.size();
            displayPage(0, first);
            String docName = getFileNameFromUri(uri);
            Toast.makeText(this, "PDF 열기 완료: " + docName + " (" + totalPages + "쪽)", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            android.util.Log.w(TAG, "PDF open failed", e);
            Toast.makeText(this, "PDF를 열 수 없습니다: " + e.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            if (candidate != null) candidate.close();
            if (opened != null) try { opened.close(); } catch (java.io.IOException ignored) {}
        }
    }

    private Bitmap renderPage(PdfRenderer renderer, int index) {
        try (PdfRenderer.Page page = renderer.openPage(index)) {
            int bw = boardView != null ? boardView.getWidth() : 0;
            int bh = boardView != null ? boardView.getHeight() : 0;
            if (bw <= 0 || bh <= 0) {
                android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                bw = dm.widthPixels;
                bh = dm.heightPixels;
            }
            float scale = Math.min((float) bw / page.getWidth(), (float) bh / page.getHeight());
            Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.round(page.getWidth() * scale)),
                    Math.max(1, Math.round(page.getHeight() * scale)), Bitmap.Config.ARGB_8888);
            renderOnPaper(page, bitmap, null);
            return bitmap;
        }
    }

    private void displayPage(int index, Bitmap bitmap) {
        final Bitmap previousBitmap = currentPdfBitmap;
        final boolean previousWasNote = currentPage >= 0 && currentPage < pages.size()
                && pages.get(currentPage).isNote();
        currentPage = index;
        currentPdfBitmap = bitmap;
        PageSlot destination = pages.get(index);
        if (bitmap != null && destination.isNote()) {
            // 스냅샷은 화면 좌표로 저장된다. 확대 상태는 페이지 입장 전에 초기화한다.
            pdfDestRect.set(0,0,boardView.getWidth(),boardView.getHeight());
        } else if (bitmap != null) {
            float left = (boardView.getWidth() - bitmap.getWidth()) / 2f;
            float top = (boardView.getHeight() - bitmap.getHeight()) / 2f;
            pdfDestRect.set(left, top, left + bitmap.getWidth(), top + bitmap.getHeight());
        } else pdfDestRect.setEmpty();
        boardView.setPdfBackground(bitmap, new RectF(pdfDestRect),
                destination.strokes, destination.lassoMasks);
        if (previousBitmap != null && previousBitmap != bitmap && !previousWasNote
                && !previousBitmap.isRecycled()) previousBitmap.recycle();
        updatePdfControls();
    }

    private static void renderOnPaper(PdfRenderer.Page page, Bitmap bitmap, Matrix matrix) {
        // Render text masks and partially transparent images against opaque paper from the outset.
        bitmap.setHasAlpha(false);
        bitmap.eraseColor(Color.WHITE);
        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
    }


    /** 페이지 선택 미리보기에서도 복제 페이지의 올가미 가림 상태를 똑같이 보여준다. */
    private void drawLassoPreviewMasks(Bitmap preview, PdfRenderer.Page pdf,
                                       ArrayList<LassoFocus.Mask> masks) {
        if (masks == null || masks.isEmpty() || preview == null) return;
        final float bw = boardView.getWidth(), bh = boardView.getHeight();
        if (bw <= 0 || bh <= 0 || pdf.getWidth() <= 0 || pdf.getHeight() <= 0) return;
        final float paperScale = Math.min(bw / pdf.getWidth(), bh / pdf.getHeight());
        final float paperW = pdf.getWidth() * paperScale;
        final float paperH = pdf.getHeight() * paperScale;
        Matrix map = new Matrix();
        map.setTranslate(-(bw - paperW) / 2f, -(bh - paperH) / 2f);
        map.postScale(preview.getWidth() / paperW, preview.getHeight() / paperH);
        Canvas c = new Canvas(preview);
        for (LassoFocus.Mask mask : masks) mask.drawTransformed(c, map);
    }

    private void requestPdfDetail() {
        final int version = ++detailVersion;
        pdfWorker.removeCallbacksAndMessages(null);
        boardView.detailBitmap = null;
        final Uri uri = documentUri;
        if (currentPage < 0 || currentPage >= pages.size() || pages.get(currentPage).isNote()) return;
        final int index = pages.get(currentPage).pdfIndex;
        final float zoom = boardView.scaleFactor;
        if (uri == null || zoom <= 1f || boardView.pdfBitmap == null) return;
        RectF visible = new RectF(0, 0, boardView.getWidth(), boardView.getHeight());
        boardView.inverseMatrix.mapRect(visible);
        if (!visible.intersect(pdfDestRect)) return;
        final RectF paper = new RectF(pdfDestRect);
        final int width = Math.max(1, Math.round(visible.width() * zoom));
        final int height = Math.max(1, Math.round(visible.height() * zoom));
        pdfWorker.post(() -> {
            if (version != detailVersion) return;
            try (ParcelFileDescriptor fd = openFileDescriptorSafely(uri)) {
                if (fd == null) return;
                try (PdfRenderer renderer = new PdfRenderer(fd);
                     PdfRenderer.Page page = renderer.openPage(index)) {
                    Matrix m = new Matrix();
                    float sx = width / visible.width(), sy = height / visible.height();
                    m.setScale(sx * paper.width() / page.getWidth(), sy * paper.height() / page.getHeight());
                    m.postTranslate((paper.left - visible.left) * sx, (paper.top - visible.top) * sy);
                    Bitmap detail = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    renderOnPaper(page, detail, m);
                    runOnUiThread(() -> {
                        if (version != detailVersion) { detail.recycle(); return; }
                        boardView.detailRect.set(visible);
                        boardView.detailBitmap = detail;
                        boardView.invalidate();
                    });
                }
            } catch (Exception e) { android.util.Log.w(TAG, "PDF detail render", e); }
        });
    }

    public void goToPage(int newPage) {
        if (newPage < 0 || newPage >= totalPages || newPage == currentPage) return;
        try {
            PageSlot destination = pages.get(newPage);
            Bitmap bitmap;
            if (destination.isNote()) bitmap = destination.noteImage;
            else if (currentPage >= 0 && currentPage < pages.size()
                    && pages.get(currentPage).pdfIndex == destination.pdfIndex
                    && currentPdfBitmap != null && !currentPdfBitmap.isRecycled()) {
                // 원본 ↔ 올가미 복제 페이지는 PDF 배경을 재렌더링하지 않고 재사용한다.
                bitmap = currentPdfBitmap;
            } else bitmap = renderPage(pdfRenderer, destination.pdfIndex);
            // 렌더링에 실패했다면 현재 페이지/필기는 전혀 변경하지 않는다.
            boardView.finishFocus(true);
            pages.get(currentPage).strokes = boardView.getCopyOfCurrentStrokes();
            boardView.resetZoom();
            displayPage(newPage, bitmap);
        } catch (RuntimeException | OutOfMemoryError e) {
            android.util.Log.w(TAG, "Page change failed",e);
            Toast.makeText(this, "페이지를 열 수 없습니다. 메모리 또는 PDF 상태를 확인하세요.", Toast.LENGTH_SHORT).show();
        }
    }

    public void closePdf() {
        documentUri = null;
        ++detailVersion;
        if (lassoActionsPopup != null) { lassoActionsPopup.dismiss(); lassoActionsPopup = null; }
        if (pdfWorker != null) pdfWorker.removeCallbacksAndMessages(null);
        if (boardView != null) boardView.clearPdfBackground();
        if (currentPdfBitmap != null && !isCurrentNoteBitmap()
                && !currentPdfBitmap.isRecycled()) currentPdfBitmap.recycle();
        currentPdfBitmap = null;
        for (PageSlot slot : pages) {
            if (slot.noteImage != null && !slot.noteImage.isRecycled()) slot.noteImage.recycle();
        }
        pages.clear();
        pages.add(new PageSlot(-1,null));
        currentPage = 0; totalPages = 1;
        if (pdfRenderer != null) { try { pdfRenderer.close(); } catch (Exception e) {} pdfRenderer = null; }
        if (pagePicker != null) pagePicker.dialog.dismiss();
        if (txtPageIndicator != null) updatePdfControls();
    }

    private boolean isCurrentNoteBitmap() {
        return currentPage >= 0 && currentPage < pages.size()
                && pages.get(currentPage).noteImage == currentPdfBitmap;
    }

    @Override
    protected void onDestroy() {
        if (toolSizePopup != null) { toolSizePopup.dismiss(); toolSizePopup = null; }
        if (lassoActionsPopup != null) { lassoActionsPopup.dismiss(); lassoActionsPopup = null; }
        if (morePopup != null) { morePopup.dismiss(); morePopup = null; }
        super.onDestroy(); closePdf(); pdfThread.quitSafely();
    }

    // =========================================================================
    // [필기감 최우선] EB-86 Series 맞춤형 네이티브 캔버스 뷰 (Hand/Pen 분리형)
    // =========================================================================
    public static class NativeBoardView extends View {

        public static class Stroke {
            public Path path; public int color; public float width; public boolean isEraser, isHighlighter;
            public RectF bounds;
            public Stroke(Path p, int c, float w, boolean e, boolean h) {
                this.path = new Path(p); this.color = c; this.width = w; this.isEraser = e; this.isHighlighter = h;
                this.bounds = new RectF();
                this.path.computeBounds(this.bounds, true);
            }
        }

        public enum EraserMode { STROKE, AREA }
        private EraserMode eraserMode = EraserMode.STROKE;
        public EraserMode getEraserMode() { return eraserMode; }
        public void setEraserMode(EraserMode mode) {
            this.eraserMode = mode;
            sizePrefs.edit().putString("eraser_mode", mode == EraserMode.AREA ? "area" : "stroke").apply();
            invalidate();
        }

        private final FastInkPredictor fastPredictor = new FastInkPredictor();
        private final ArrayList<Stroke> erasedInGesture = new ArrayList<>();

        private Bitmap inkBitmap;
        private final Matrix inkToBoard = new Matrix();
        private final Matrix inkViewport = new Matrix();
        private Bitmap detailBitmap;
        private final RectF detailRect = new RectF();
        private Runnable viewportSettled;
        private Canvas inkCanvas;
        private final Paint bitmapPaint = new Paint(Paint.DITHER_FLAG | Paint.FILTER_BITMAP_FLAG);

        /** [필기감 최우선] 안티앨리어싱 및 디더링을 활성화하여 4K에서도 부드러운 획 구현 */
        private final Paint drawPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Paint highlighterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint eraserPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gridPaint = new Paint();

        private final Path currentStrokePath = new Path();
        private final Path segmentPath = new Path();

        public int currentColor = 0xFFFFFFFF;
        // [v2.5.0] 네 도구의 굵기는 별도로 저장한다. 펜/형광펜은 화면 px,
        // 지우개는 '짧은 화면 변 × 140/1080'을 100%로 한 상대적인 지름이다.
        private static final String SIZE_PREFS = "GNFLHS_tool_sizes_v2_5";
        private final SharedPreferences sizePrefs;
        private int penSize, smoothSize, highlighterSize, eraserPercent;
        // 기하학적 보정은 보정펜과 형광펜에서만 수행하며 각각 독립 저장한다.
        private int smoothLevel, highlighterLevel;
        // [v2.9] 스마트 빠른펜은 옵트인. 기존 빠른펜의 입력/캐시 경로를 기본값에서 유지.
        private boolean smartPenEnabled;
        private int smartPauseMillis;
        // opt-in: v2.16 기존펜 동작은 변경하지 않고, 고속펜이 선택된 획만 즉시 선분 비트맵 경로를 쓴다.
        private boolean highSpeedPenEnabled;
        private boolean highSpeedStroke;
        private boolean highSpeedMoved;
        private boolean eraserCrosshairEnabled = true;
        private int eraserCrosshairLength = 3; // 1 짧게 · 2 보통 · 3 길게
        private boolean eraserPalmTopEnabled = true; // 손바닥 접촉에만 적용, 기본 켜짐
        private int boardBgColor;
        public ToolMode currentTool = ToolMode.PEN; // 기본 모드는 필기
        private boolean showGrid = false;

        private float lastX, lastY, lastMidX, lastMidY;
        private float handX, handY;
        private int activePointer = -1;
        private boolean erasing, highlighting;
        private final Paint strokePaint = new Paint();
        private final Path preview = new Path();
        /*
         * EB-86(Android 11) 필기감 기준 — 2026-11-12, 버전 2.2.10.
         * 사용자 실기 피드백: “판서감도 좋구요”. 이는 체감 평가이며 지연 시간 실측값은 없다.
         * 2.2.4의 펜촉 추적·짧은 예측·64구간 캐시에 임시 지우개와 두 손가락 이동을 결합한 버전이다.
         * currentStrokePath는 실행 취소·복원에 쓰는 전체 획이다.
         * pendingInk는 아직 비트맵에 합치지 않은 꼬리이며, 필기 중 매 프레임 그리는 범위다.
         * tip은 마지막 유효 입력, lead는 화면에 잠시 덧그리는 예측 변위다.
         * 도구를 추가할 때도 일반 펜의 MOVE/onDraw에서는 Path를 재사용하고,
         * 전체 획 복사, 저장 작업, 추가 입력을 기다리는 처리는 이 경로에 넣지 않는다.
         * 입력 또는 렌더러 변경 후 같은 EB-86에서 2.2.10과 소수점·짧은 첨자·빠른 분수선·긴 곡선을 비교한다.
         * 획을 쓰는 중 손바닥 추가, 임시 지우개 및 두 손가락 확대 직후의 첫 획도 함께 확인한다.
         */
        private final Path pendingInk = new Path();
        private int pendingSegments;
        private float tipX, tipY, priorDx, priorDy, leadX, leadY;
        private long tipTime;
        private boolean livePen() {
            return currentTool == ToolMode.PEN && !erasing;
        }

        private void updateTip(float x, float y, long time) {
            float dx = x - tipX, dy = y - tipY;
            long elapsed = time - tipTime;
            leadX = leadY = 0;
            float length = (float)Math.hypot(dx, dy);
            float previous = (float)Math.hypot(priorDx, priorDy);
            // 입력 간격이 32ms 이하이고 2화면픽셀 이상 움직였으며 두 방향이 거의 같을 때만 예측한다.
            // factor는 입력 변위를 최대 8ms의 이동량, 최대 8화면픽셀로 제한한다.
            if (elapsed > 0 && elapsed <= 32 && length * scaleFactor >= 2
                    && dx * priorDx + dy * priorDy > 0.94f * length * previous) {
                float factor = Math.min(8f / elapsed, 8f / (length * scaleFactor));
                leadX = dx * factor;
                leadY = dy * factor;
            }
            tipX = x; tipY = y; tipTime = time;
            priorDx = dx; priorDy = dy;
        }
        // [v2.6.0] 도형/스플라인은 보정펜·형광펜에만 적용한다.
        // 1~8단계: 기존 빠른펜과 분리된 획 완료 시 적응형 보정.
        // 좌표는 단일 float 배열(x,y 반복)에 저장. 빠른펜 MOVE 루프에는 이 로직이 없다.
        private float[] shapePoints = new float[512];
        private int shapeCount;
        private boolean shapeStroke;
        // 스마트 펜의 정지 인식은 입력 중 매 프레임 재계산하지 않는다.
        private boolean smartStroke;
        private boolean smartJustCommitted;
        private boolean smartHasFlushedInk;
        private float smartLineAX, smartLineAY, smartLineBX, smartLineBY;
        private float smartLinePauseX, smartLinePauseY;
        private float smartLineMoveX, smartLineMoveY;
        // 빠른펜의 MOVE에서 Handler 예약/취소를 반복하지 않기 위한 단일 스마트 타이머.
        private boolean smartTimerPending;
        private long smartLastMotionTime;
        private InkShapeRecognizer.Shape detectedShape;
        private boolean straightLocked;
        private final Path recognizedPath = new Path();
        private static final long SHAPE_PAUSE_MS = 350L;
        // [v2.6.0 build fix] Runnable field initializer references this zoom value.
        // Declare it before the lambda to avoid javac illegal forward reference.
        private float scaleFactor = 1.0f;
        private final Runnable smartPauseTicker = new Runnable() {
            @Override public void run() {
                smartTimerPending=false;
                if (activePointer < 0 || !smartStroke || !shapeStroke || straightLocked
                        || detectedShape != null || smartJustCommitted) return;
                long elapsed=android.os.SystemClock.uptimeMillis()-smartLastMotionTime;
                if (elapsed < smartPauseMillis) {
                    smartTimerPending=true;
                    postDelayed(this,Math.max(1L,smartPauseMillis-elapsed));
                } else {
                    runSmartPauseRecognition();
                }
            }
        };
        private final Runnable recognizeAfterPause = () -> {
            if (activePointer < 0 || !shapeStroke || shapeCount < 4 || erasing) return;
            if (straightLocked || detectedShape != null || smartJustCommitted) return;
            if (smartStroke) {
                // [v2.9] 오직 멈춤 시점에만 분류. MOVE의 빠른펜 캐시/예측 경로 그대로 유지.
                SmartPenRecognizer.Action action = SmartPenRecognizer.classify(
                        shapePoints,shapeCount,scaleFactor);
                if (action == SmartPenRecognizer.Action.NONE) return;
                if (action == SmartPenRecognizer.Action.LINE) {
                    lockSmartLine();
                    // 이미 그려진 64구간 캐시를 한번만 되감고 직선 오버레이로 교체.
                    if (smartHasFlushedInk) refreshCanvas();
                    rebuildRecognizedPath();
                } else if (action == SmartPenRecognizer.Action.CLOSED) {
                    detectedShape=InkShapeRecognizer.detectClosed(shapePoints,shapeCount,scaleFactor);
                    if (detectedShape != null) {
                        rebuildRecognizedPath();
                        commitSmartSegment(recognizedPath);
                    }
                } else if (action == SmartPenRecognizer.Action.CURVE) {
                    commitSmartSegment(correctedSpline());
                }
                invalidate();
                return;
            }
            // 기존 보정펜/형광펜 경로는 유지한다.
            InkShapeRecognizer.Shape closed = InkShapeRecognizer.detectClosed(shapePoints,shapeCount,scaleFactor);
            if (closed != null) {
                detectedShape = closed;
                rebuildRecognizedPath();
                invalidate();
            } else if (InkShapeRecognizer.looksStraight(shapePoints,shapeCount,scaleFactor)) {
                straightLocked = true;
                rebuildRecognizedPath();
                invalidate();
            }
        };

        /** 정지 이후 움직임은 길이 조절이 아닌 직선 전체 평행 이동이다. */
        private void runSmartPauseRecognition() { recognizeAfterPause.run(); }

        private void lockSmartLine() {
            straightLocked = true;
            smartLineAX = shapePoints[0]; smartLineAY = shapePoints[1];
            smartLineBX = shapePoints[(shapeCount-1)*2];
            smartLineBY = shapePoints[(shapeCount-1)*2+1];
            float dx=smartLineBX-smartLineAX,dy=smartLineBY-smartLineAY;
            // 최초 직선 변환 시에만 수평/수직 스냅. 이동 중 기울기·길이는 고정.
            if (Math.abs(dy)<=Math.abs(dx)*.16f) smartLineBY=smartLineAY;
            else if (Math.abs(dx)<=Math.abs(dy)*.16f) smartLineBX=smartLineAX;
            smartLinePauseX=lastX;
            smartLinePauseY=lastY;
            smartLineMoveX=smartLineMoveY=0;
        }

        /** 원/직사각형/곡선은 멈춘 즉시 편집 기록에 저장한다.
         *  펜이 눌린 채여도 다음 움직임을 새 획으로 처리하므로 기존 도형은 바뀌지 않는다. */
        private void commitSmartSegment(Path path) {
            if (!smartStroke || smartJustCommitted) return;
            // 필기 중 64구간씩 합친 이전 원본 궤적만 제거한 다음 최종 도형을 표시한다.
            // 전체 리빌드는 정지에 딱 한번만, MOVE에서는 발생하지 않는다.
            if (smartHasFlushedInk) refreshCanvas();
            inkCanvas.drawPath(path,strokePaint);
            Stroke completed=new Stroke(path,strokePaint.getColor(),
                    strokePaint.getStrokeWidth(),false,false);
            currentStrokes.add(completed);
            editHistory.recordAdd(completed);
            removeCallbacks(recognizeAfterPause);
            removeCallbacks(smartPauseTicker);
            smartTimerPending=false;
            shapeStroke=false;
            smartStroke=false;
            straightLocked=false;
            detectedShape=null;
            smartJustCommitted=true;
            smartHasFlushedInk=false;
            pendingInk.rewind();
            leadX=leadY=0;
            invalidate();
        }

        private void resetShapeStroke() {
            removeCallbacks(recognizeAfterPause);
            removeCallbacks(smartPauseTicker);
            smartTimerPending=false;
            shapeCount = 0;
            straightLocked = false;
            detectedShape = null;
            recognizedPath.rewind();
            shapeStroke = false;
            smartStroke = false;
        }

        private void appendShapePoint(float x, float y) {
            if (!shapeStroke) return;
            // 약 1 화면 px 이하의 중복 입력은 저장하지 않음 (메모리와 스플라인 비용 제한).
            if (shapeCount > 0) {
                float dx=(x-shapePoints[(shapeCount-1)*2])*scaleFactor;
                float dy=(y-shapePoints[(shapeCount-1)*2+1])*scaleFactor;
                if (dx*dx+dy*dy < 1f) return;
            }
            if (shapeCount >= 4096) {
                // 아주 긴 획의 뒤쪽이 잘리지 않게 이전 샘플을 1/2로 다운샘플링한다.
                // 빠른펜에는 적용되지 않으며 4096샘플에 한 번만 실행된다.
                for (int i=1;i<2048;i++) {
                    shapePoints[i*2]=shapePoints[i*4];
                    shapePoints[i*2+1]=shapePoints[i*4+1];
                }
                shapeCount = 2048;
            }
            if (shapeCount*2+1 >= shapePoints.length) {
                float[] bigger = new float[Math.min(8192, shapePoints.length*2)];
                System.arraycopy(shapePoints,0,bigger,0,shapeCount*2);
                shapePoints = bigger;
            }
            shapePoints[shapeCount*2]=x;
            shapePoints[shapeCount*2+1]=y;
            shapeCount++;
            if (straightLocked) {
                rebuildRecognizedPath();
            } else if (detectedShape == null) {
                if (smartStroke) {
                    // 원본 입력 루프에서는 현재 시각 갱신 및 부울 검사만 수행.
                    smartLastMotionTime=android.os.SystemClock.uptimeMillis();
                    if (!smartTimerPending) {
                        smartTimerPending=true;
                        postDelayed(smartPauseTicker,smartPauseMillis);
                    }
                } else {
                    removeCallbacks(recognizeAfterPause);
                    postDelayed(recognizeAfterPause,SHAPE_PAUSE_MS);
                }
            }
        }

        private void rebuildRecognizedPath() {
            recognizedPath.rewind();
            if (straightLocked && smartStroke) {
                // MOVE 당 float 배열 생성하지 않음: 획의 전체 평행 이동.
                recognizedPath.moveTo(smartLineAX+smartLineMoveX,smartLineAY+smartLineMoveY);
                recognizedPath.lineTo(smartLineBX+smartLineMoveX,smartLineBY+smartLineMoveY);
            } else if (straightLocked && shapeCount >= 2) {
                float x0=shapePoints[0],y0=shapePoints[1];
                float x1=shapePoints[(shapeCount-1)*2],y1=shapePoints[(shapeCount-1)*2+1];
                float dx=x1-x0,dy=y1-y0;
                // 수평/수직 방향에서 약 ±9도 안으로 들어오면 끝점의 각도를 고정한다.
                if (Math.abs(dy) <= Math.abs(dx)*0.16f) y1=y0;
                else if (Math.abs(dx) <= Math.abs(dy)*0.16f) x1=x0;
                recognizedPath.moveTo(x0,y0);
                recognizedPath.lineTo(x1,y1);
            } else if (detectedShape != null) {
                if (detectedShape.kind == InkShapeRecognizer.Kind.CIRCLE) {
                    float r=detectedShape.radius;
                    recognizedPath.addCircle(detectedShape.centerX,detectedShape.centerY,r,Path.Direction.CW);
                } else {
                    float[] c=detectedShape.corners;
                    recognizedPath.moveTo(c[0],c[1]);
                    for (int i=1;i<4;i++) recognizedPath.lineTo(c[i*2],c[i*2+1]);
                    recognizedPath.close();
                }
            }
        }

        /** 단순 평균 후 Catmull-Rom -> cubic Bezier. 획 끝점은 반드시 실제 접촉점에 유지. */
        private Path correctedSpline() {
            Path output = new Path();
            if (shapeCount <= 0) return output;
            if (shapeCount == 1) {
                output.moveTo(shapePoints[0],shapePoints[1]);
                output.rLineTo(.01f,0); return output;
            }
            int strength = smartStroke ? 8 :
                    (currentTool == ToolMode.HIGHLIGHTER ? highlighterLevel : smoothLevel);
            // 획 완료 시에만 실행. 필기 좌표의 불균일한 밀도에 영향받지 않도록
            // 화면상의 호 길이를 기준으로 손떨림을 보정하며 시작/끝점을 고정한다.
            float[] filtered = AdaptiveStrokeSmoother.smooth(shapePoints,shapeCount,scaleFactor,strength);
            output.moveTo(filtered[0],filtered[1]);
            if (strength == 1 || shapeCount <= 2) {
                for (int i=1;i<shapeCount;i++) output.lineTo(filtered[i*2],filtered[i*2+1]);
                return output;
            }
            // 스플라인 연속성과 원래 선 위치 간 균형: 강도에 따라 접선 영향량 차등.
            // Catmull-Rom의 표준 접선 비율을 유지해 강한 보정 단계에서도 튀지 않도록 한다.
            float tangent = .166667f;
            for (int i=0;i<shapeCount-1;i++) {
                int a=Math.max(0,i-1), b=i, c=i+1, d=Math.min(shapeCount-1,i+2);
                float ax=filtered[a*2],ay=filtered[a*2+1];
                float bx=filtered[b*2],by=filtered[b*2+1];
                float cx=filtered[c*2],cy=filtered[c*2+1];
                float dx=filtered[d*2],dy=filtered[d*2+1];
                output.cubicTo(bx+(cx-ax)*tangent, by+(cy-ay)*tangent,
                        cx-(dx-bx)*tangent,cy-(dy-by)*tangent,cx,cy);
            }
            return output;
        }

        private final Matrix viewMatrix = new Matrix();
        private final Matrix inverseMatrix = new Matrix();
        private float panX = 0f, panY = 0f;
        private ScaleGestureDetector scaleDetector;

        private final ArrayList<Stroke> currentStrokes = new ArrayList<>();
        // [v2.8.0] 실제 화면 비트맵을 복사하지 않는 획 기반 편집 이력.
        // 별도 순수 Java 클래스로 분리하여 전체삭제·복원 회귀 테스트를 가능하게 했다.
        private final StrokeEditHistory<Stroke> editHistory = new StrokeEditHistory<>();

        private Bitmap pdfBitmap;
        private RectF pdfRect;

        public NativeBoardView(Context context, int bgColor) {
            super(context);
            this.boardBgColor = bgColor;
            sizePrefs = context.getSharedPreferences(SIZE_PREFS, Context.MODE_PRIVATE);
            penSize = limit(sizePrefs.getInt("pen", 8), 1, 40);
            smoothSize = limit(sizePrefs.getInt("smooth", 8), 1, 40);
            highlighterSize = limit(sizePrefs.getInt("highlighter", 20), 4, 72);
            eraserPercent = limit(sizePrefs.getInt("eraser", 100), 25, 250);
            // 저장된 1~4 기존 단계 값은 계속 읽어 기존 사용자 설정을 유지한다.
            smoothLevel = limit(sizePrefs.getInt("smooth_level", 2), 1, 8);
            highlighterLevel = limit(sizePrefs.getInt("highlighter_level", 2), 1, 8);
            smartPenEnabled=sizePrefs.getBoolean("smart_pen_enabled",false);
            smartPauseMillis=SmartPenRecognizer.clampPause(sizePrefs.getInt("smart_pause_ms",400));
            highSpeedPenEnabled=sizePrefs.getBoolean("high_speed_pen_enabled",false);
            eraserCrosshairEnabled = sizePrefs.getBoolean("eraser_crosshair_on",true);
            eraserCrosshairLength = limit(sizePrefs.getInt("eraser_crosshair_length",3),1,3);
            eraserPalmTopEnabled = sizePrefs.getBoolean("eraser_palm_top_enabled",true);
            eraserMode = "area".equals(sizePrefs.getString("eraser_mode", "stroke")) ? EraserMode.AREA : EraserMode.STROKE;
            initPaints();
            initScaleDetector(context);
        }

        /** [필기감 최우선] ROUND 조인/캡 설정을 통해 자연스러운 필기 끝처리 구현 */
        private void initPaints() {
            drawPaint.setColor(currentColor);
            drawPaint.setStyle(Paint.Style.STROKE);
            drawPaint.setStrokeJoin(Paint.Join.ROUND);
            drawPaint.setStrokeCap(Paint.Cap.ROUND);
            drawPaint.setStrokeWidth(penSize);

            highlighterPaint.setStyle(Paint.Style.STROKE);
            highlighterPaint.setStrokeJoin(Paint.Join.ROUND);
            highlighterPaint.setStrokeCap(Paint.Cap.SQUARE);
            highlighterPaint.setColor(currentColor);
            highlighterPaint.setStrokeWidth(highlighterSize);
            highlighterPaint.setAlpha(90);

            eraserPaint.setStyle(Paint.Style.STROKE);
            eraserPaint.setStrokeJoin(Paint.Join.ROUND);
            eraserPaint.setStrokeCap(Paint.Cap.ROUND);
            eraserPaint.setStrokeWidth(getEraserScreenDiameter()); // 화면 크기에 비례, 줌 배율과 무관
            eraserPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

            gridPaint.setColor(0x1A94A3B8); gridPaint.setStrokeWidth(1.2f);
            crosshairOutline.setStyle(Paint.Style.STROKE);
            crosshairOutline.setStrokeCap(Paint.Cap.ROUND);
            crosshairOutline.setStrokeWidth(5f);
            crosshairOutline.setColor(0xFF102334);
            crosshairInk.setStyle(Paint.Style.STROKE);
            crosshairInk.setStrokeCap(Paint.Cap.ROUND);
            crosshairInk.setStrokeWidth(2.8f);
            crosshairInk.setColor(0xFF22D3EE);
        }

        private void initScaleDetector(Context context) {
            scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    // [필기감 최우선] Hand 모드일 때만 핀치 줌 허용
                    if (currentTool != ToolMode.HAND) return false;
                    float previous = scaleFactor;
                    scaleFactor = Math.max(0.5f, Math.min(scaleFactor * d.getScaleFactor(), 4.0f));
                    float ratio = scaleFactor / previous;
                    panX = d.getFocusX() + (panX - d.getFocusX()) * ratio;
                    panY = d.getFocusY() + (panY - d.getFocusY()) * ratio;
                    updateMatrices(); invalidate(); return true;
                }
            });
        }

        private void updateMatrices() {
            viewMatrix.reset(); viewMatrix.postScale(scaleFactor, scaleFactor);
            viewMatrix.postTranslate(panX, panY); viewMatrix.invert(inverseMatrix);
        }

        private void settleViewport() {
            if (inkCanvas != null && !inkViewport.equals(viewMatrix)) {
                inkViewport.set(viewMatrix);
                inkViewport.invert(inkToBoard);
                inkCanvas.setMatrix(inkViewport);
                refreshCanvas();
            }
            if (viewportSettled != null) viewportSettled.run();
        }

        public void resetZoom() { scaleFactor = 1.0f; panX = 0; panY = 0; updateMatrices(); settleViewport(); invalidate(); }
        public void setToolMode(ToolMode m) {
            finishGesture();
            handVisualCount = 0;
            if (currentTool == ToolMode.LASSO && m != currentTool) focus.cancelDrag();
            this.currentTool = m;
            applyToolPaints();
            invalidate();
        }
        public void setColor(int c) { this.currentColor = c; drawPaint.setColor(c); highlighterPaint.setColor(c); highlighterPaint.setAlpha(90); }

        private static int limit(int value, int minimum, int maximum) {
            return Math.max(minimum, Math.min(maximum, value));
        }

        private void applyToolPaints() {
            drawPaint.setStrokeWidth(currentTool == ToolMode.SMOOTH ? smoothSize : penSize);
            highlighterPaint.setStrokeWidth(highlighterSize);
            eraserPaint.setStrokeWidth(getEraserScreenDiameter());
        }

        public int getToolSize(ToolMode mode) {
            switch (mode) {
                case PEN: return penSize;
                case SMOOTH: return smoothSize;
                case HIGHLIGHTER: return highlighterSize;
                case ERASER: return eraserPercent;
                default: return penSize;
            }
        }

        public void setToolSize(ToolMode mode, int width) {
            // 크기를 바꾸기 전 진행 중인 획을 끝내야 획 중간에 굵기가 달라지지 않는다.
            endStroke();
            switch (mode) {
                case PEN:
                    penSize = limit(width, 1, 40);
                    break;
                case SMOOTH:
                    smoothSize = limit(width, 1, 40);
                    break;
                case HIGHLIGHTER:
                    highlighterSize = limit(width, 4, 72);
                    break;
                case ERASER:
                    eraserPercent = limit(width, 25, 250);
                    break;
                default: return;
            }
            applyToolPaints();
            invalidate();
        }

        /** 현재 크기를 도구별로 독립 저장. SeekBar 조작 완료/팝업 닫힘에만 호출. */
        public void persistToolSize(ToolMode mode) {
            switch (mode) {
                case PEN: sizePrefs.edit().putInt("pen", penSize).apply(); break;
                case SMOOTH: sizePrefs.edit().putInt("smooth", smoothSize).apply(); break;
                case HIGHLIGHTER: sizePrefs.edit().putInt("highlighter", highlighterSize).apply(); break;
                case ERASER: sizePrefs.edit().putInt("eraser", eraserPercent).apply(); break;
                default: break;
            }
        }

        public boolean isSmartPenEnabled() { return smartPenEnabled; }
        public int getSmartPauseMillis() { return smartPauseMillis; }
        public void setSmartPenEnabled(boolean enabled) {
            finishGesture();
            smartPenEnabled=enabled;
            sizePrefs.edit().putBoolean("smart_pen_enabled",enabled).apply();
        }
        public void setSmartPauseMillis(int milliseconds) {
            finishGesture();
            smartPauseMillis=SmartPenRecognizer.clampPause(milliseconds);
            sizePrefs.edit().putInt("smart_pause_ms",smartPauseMillis).apply();
        }

        public int getCorrectionLevel(ToolMode mode) {
            return mode == ToolMode.HIGHLIGHTER ? highlighterLevel : smoothLevel;
        }

        public void setCorrectionLevel(ToolMode mode, int level) {
            endStroke();
            if (mode == ToolMode.SMOOTH) smoothLevel = limit(level, 1, 8);
            else if (mode == ToolMode.HIGHLIGHTER) highlighterLevel = limit(level, 1, 8);
        }

        public void persistCorrectionLevel(ToolMode mode) {
            if (mode == ToolMode.SMOOTH) sizePrefs.edit().putInt("smooth_level", smoothLevel).apply();
            else if (mode == ToolMode.HIGHLIGHTER)
                sizePrefs.edit().putInt("highlighter_level", highlighterLevel).apply();
        }

        /** 십자선 옵션은 화면 오버레이 전용이며 필기/지우기 히스토리에 남지 않는다. */
        public boolean isEraserCrosshairEnabled() { return eraserCrosshairEnabled; }
        public int getEraserCrosshairLength() { return eraserCrosshairLength; }
        public boolean isEraserPalmTopEnabled() { return eraserPalmTopEnabled; }
        public void setEraserPalmTopEnabled(boolean enabled) {
            // 활성 지우기 도중 변경하면 삭제 궤적이 튈 수 있으므로 현재 획을 확정한다.
            finishGesture();
            eraserPalmTopEnabled=enabled;
            sizePrefs.edit().putBoolean("eraser_palm_top_enabled",enabled).apply();
            invalidate();
        }
        public void setEraserCrosshairEnabled(boolean enabled) {
            eraserCrosshairEnabled=enabled;
            sizePrefs.edit().putBoolean("eraser_crosshair_on",enabled).apply();
            invalidate();
        }
        public void setEraserCrosshairLength(int length) {
            eraserCrosshairLength=limit(length,1,3);
            sizePrefs.edit().putInt("eraser_crosshair_length",eraserCrosshairLength).apply();
            invalidate();
        }

        public float getEraserScreenDiameter() {
            int shortSide = Math.min(getWidth(), getHeight());
            if (shortSide <= 0) {
                android.util.DisplayMetrics display = getResources().getDisplayMetrics();
                shortSide = Math.min(display.widthPixels, display.heightPixels);
            }
            // 1080px 짧은 화면에서 기본 지름 140px; 4K/다른 패널에도 비례.
            // 현재 확대 배율(scaleFactor)은 여기서 사용하지 않는다.
            return Math.max(24f, shortSide * (140f / 1080f) * eraserPercent / 100f);
        }
        public void setBoardColor(int c) { this.boardBgColor = c; invalidate(); }
        public boolean isGridEnabled() { return showGrid; }
        public boolean toggleGrid() { showGrid = !showGrid; invalidate(); return showGrid; }
        public boolean isHighSpeedPenEnabled() { return highSpeedPenEnabled; }
        public void setHighSpeedPenEnabled(boolean enabled) {
            if (enabled==highSpeedPenEnabled) return;
            finishGesture(); // 획의 중간에서는 엔진을 바꾸지 않는다.
            highSpeedPenEnabled=enabled;
            sizePrefs.edit().putBoolean("high_speed_pen_enabled",enabled).apply();
            invalidate();
        }
        private final LassoFocus focus = new LassoFocus();
        // NEW_PAGE는 기존 올가미의 비동기 배경색 측정이 끝난 뒤 복제해야 한다.
        private boolean lassoAutoPage;
        private final ArrayList<LassoFocus.Mask> retainedPageMasks = new ArrayList<>();
        // 기존 획만 선택 영역으로 잘라 캐시에 넣고 이후의 판서는 전체 화면에 그린다.
        private final java.util.HashSet<Stroke> beforeFocus = new java.util.HashSet<>();
        private ArrayList<Stroke> focusStartStrokes;
        private StrokeEditHistory.Snapshot<Stroke> focusStartHistory;
        /** 집중 작업 시작 이전 상태를 보존한다. 삭제/되돌리기도 안전하게 원복된다. */
        private void beginFocusEdit() {
            finishGesture();
            focusStartStrokes = new ArrayList<>(currentStrokes);
            focusStartHistory = editHistory.snapshot();
        }

        private void finishFocus(boolean keepNewInk) {
            finishGesture();
            boolean rebuild = focus.isActive();
            if (!keepNewInk && focusStartStrokes != null && focusStartHistory != null) {
                currentStrokes.clear();
                currentStrokes.addAll(focusStartStrokes);
                editHistory.restore(focusStartHistory);
            }
            focus.clear();
            beforeFocus.clear();
            focusStartStrokes = null;
            focusStartHistory = null;
            if (rebuild) refreshCanvas();
        }

        /** 기존 올가미에서 이미 계산된 선택 영역과 배경색을 불변 사본으로 만든다. */
        private LassoFocus.Mask copyActiveLassoMask() {
            return focus.snapshotMask();
        }

        private Runnable focusChanged;
        private Handler focusWorker;

        private void resetFocus() {
            boolean changed = focus.isActive() || currentTool == ToolMode.LASSO;
            focus.clear();
            beforeFocus.clear();
            focusStartStrokes = null;
            focusStartHistory = null;
            if (changed && focusChanged != null) focusChanged.run();
        }

        public void setPdfBackground(Bitmap bm, RectF r, ArrayList<Stroke> saved,
                                     ArrayList<LassoFocus.Mask> masks) {
            finishGesture(); resetFocus(); editHistory.reset();
            retainedPageMasks.clear();
            if (masks != null) retainedPageMasks.addAll(masks);
            this.pdfBitmap = bm; this.pdfRect = r; clearInkCanvas(); currentStrokes.clear();
            if (saved != null) {
                // 기존 v2.7의 동작 보존: PDF 페이지 재방문 후에도 저장된 획을 되돌릴 수 있다.
                // 한 페이지의 이력은 페이지 교체 시 초기화되지만 현재 획은 개별 취소 가능.
                for (Stroke s : saved) {
                    currentStrokes.add(s);
                    editHistory.recordAdd(s);
                    drawStrokeOnCanvas(s);
                }
            }
            if (viewportSettled != null) viewportSettled.run();
            invalidate();
        }

        public void clearPdfBackground() {
            // PDF 닫기는 전체삭제 버튼과 다르다: 문서를 닫는 동안 이전 실행취소 기록을 남기지 않는다.
            finishGesture(); resetFocus();
            detailBitmap = null; this.pdfBitmap = null; this.pdfRect = null;
            retainedPageMasks.clear();
            currentStrokes.clear(); editHistory.reset();
            clearInkCanvas(); invalidate();
        }
        public ArrayList<Stroke> getCopyOfCurrentStrokes() { endStroke(); return new ArrayList<>(currentStrokes); }
        private void clearInkCanvas() { if (inkCanvas != null) inkCanvas.drawColor(0, PorterDuff.Mode.CLEAR); }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            if (w > 0 && h > 0) {
                // [필기감 최우선] onDraw 시점의 계산을 최소화하기 위해 오프스크린 비트맵 캐싱 사용
                eraserPaint.setStrokeWidth(getEraserScreenDiameter());
                inkBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                inkCanvas = new Canvas(inkBitmap);
                inkViewport.set(viewMatrix);
                inkViewport.invert(inkToBoard);
                inkCanvas.setMatrix(inkViewport);
                refreshCanvas();
            }
        }

        public void undo() {
            finishGesture();
            if (editHistory.undo(currentStrokes)) refreshCanvas();
        }
        public void redo() {
            finishGesture();
            if (editHistory.redo(currentStrokes)) refreshCanvas();
        }
        private void refreshCanvas() { clearInkCanvas(); for (Stroke s : currentStrokes) drawStrokeOnCanvas(s); invalidate(); }

        private void drawStrokeOnCanvas(Stroke s) {
            if (focus.isActive() && beforeFocus.contains(s)) {
                inkCanvas.save();
                focus.clipInterior(inkCanvas);
                drawStroke(inkCanvas, s);
                inkCanvas.restore();
            } else {
                drawStroke(inkCanvas, s);
            }
        }

        private void drawStroke(Canvas target, Stroke s) {
            Paint p = s.isEraser ? eraserPaint : (s.isHighlighter ? highlighterPaint : drawPaint);
            float ow = p.getStrokeWidth(); int oc = p.getColor();
            p.setStrokeWidth(s.width); p.setColor(s.color);
            if (s.isHighlighter) p.setAlpha(90);
            target.drawPath(s.path, p);
            p.setStrokeWidth(ow); p.setColor(oc);
        }

        public void clearCurrentPage() {
            finishGesture();
            if (currentStrokes.isEmpty()) return; // 빈 화면에서는 히스토리 유지
            if (focus.isActive()) resetFocus();
            // 작업 기록을 지우지 않는다. 전체삭제를 단일 작업으로 기록한다.
            editHistory.recordClear(currentStrokes);
            currentStrokes.clear();
            clearInkCanvas();
            invalidate();
        }

        public Bitmap createFullExportBitmap() {
            if (getWidth() <= 0) return null;
            endStroke();
            Bitmap b = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.drawColor(boardBgColor);
            // 현재 화면과 동일한 PDF/올가미 가림/필기 캐시 순서로 저장한다.
            // 지우개는 이미 inkBitmap에 반영돼 있어 임시 비트맵 중복 생성이 없다.
            drawPaper(c);
            drawInkCache(c);
            return b;
        }

        /** [필기감 최우선] EB-86 IR 센서 지글거림 및 오작동 차단 필기 엔진 */
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (currentTool == ToolMode.LASSO) {
                if (focus.drag(event, inverseMatrix, viewMatrix, getWidth(), getHeight())) {
                    // 선택이 끝난 바로 이 시점을 작업 체크포인트로 저장한다.
                    beginFocusEdit();
                    focus.measure(this, boardBgColor, viewMatrix,
                            c -> { drawPaper(c); drawInkCache(c); }, focusWorker,
                            lassoAutoPage ? () -> {
                                if (focusChanged != null) focusChanged.run();
                            } : null);
                    beforeFocus.addAll(currentStrokes);
                    // 보류 중인 편집 중 화면에 다시 나타날 수 있는 획도 클리핑 목록에 넣는다.
                    beforeFocus.addAll(editHistory.referencedByRedo());
                    refreshCanvas();
                    // 일회성 캡처가 아니라 기존 가림 색 분석이 끝날 때만 새 페이지 생성.
                    if (!lassoAutoPage && focusChanged != null) focusChanged.run();
                }
                invalidate();
                return true;
            }
            if (currentTool == ToolMode.HAND) {
                navigate(event, action);
                return true;
            }
            if (action == MotionEvent.ACTION_DOWN) {
                finishGesture();
                if (inkCanvas == null) return true;
                int id = event.getPointerId(0);
                holdX = cursorX = event.getX();
                holdY = event.getY();
                boolean isBroad = broad(event, 0);
                eraserOffsetY = EraserPlacement.offsetY(eraserPalmTopEnabled, isBroad,
                        event.getTouchMajor(0), event.getSize(0),
                        getEraserScreenDiameter(), Math.min(getWidth(),getHeight()));
                cursorY = EraserPlacement.eraserY(holdY, eraserOffsetY,
                        getEraserScreenDiameter(), getHeight());
                if (isBroad) {
                    // [EB-86 실측 최적화] 첫 접촉이 손바닥이면 펜 선을 긋지 않고 즉시 지우개 모드로 진입
                    canJoin = false;
                    hold = Hold.ERASE;
                    eraserPointer = id;
                    beginInk(id, cursorX, cursorY, event.getEventTime(), true);
                } else {
                    // [v2.4.0] 첫 점은 즉시 해당 도구로 기록한다. 두 손가락 진입만 별도 판단.
                    // 잘못된 '장시간 누름 => 지우개' 전환이 기존 필기를 삭제하지 않도록 제거.
                    canJoin = currentTool != ToolMode.ERASER && finger(event, 0);
                    beginInk(id, holdX, currentTool == ToolMode.ERASER ? cursorY : holdY,
                            event.getEventTime(), currentTool == ToolMode.ERASER);
                }
                requestUnbufferedDispatch(event);
                invalidate();
                return true;
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                finishGesture();
                return true;
            }
            if (hold == Hold.RELEASE) return true;
            if (hold == Hold.PAIR || hold == Hold.NAVIGATE) {
                if (event.getPointerCount() != 2 || action != MotionEvent.ACTION_MOVE) {
                    finishGesture();
                    hold = Hold.RELEASE;
                } else {
                    trackPair(event);
                }
                return true;
            }
            int index = event.findPointerIndex(activePointer >= 0 ? activePointer : eraserPointer);
            if (index < 0) return true;
            if (action == MotionEvent.ACTION_POINTER_DOWN) {
                int second = event.getActionIndex();
                if (!erasing && broad(event, second)) {
                    // [EasiNote 제스처 지우개] 필기 중 손바닥/손날 접촉 시 즉시 손바닥 지우개 전환
                    finishGesture();
                    hold = Hold.ERASE;
                    eraserPointer = event.getPointerId(second);
                    holdX = cursorX = event.getX(second);
                    holdY = event.getY(second);
                    eraserOffsetY = EraserPlacement.offsetY(eraserPalmTopEnabled, true,
                            event.getTouchMajor(second), event.getSize(second),
                            getEraserScreenDiameter(), Math.min(getWidth(), getHeight()));
                    cursorY = EraserPlacement.eraserY(holdY, eraserOffsetY,
                            getEraserScreenDiameter(), getHeight());
                    beginInk(eraserPointer, cursorX, cursorY, event.getEventTime(), true);
                    invalidate();
                    return true;
                }
                if (canJoin && event.getPointerCount() == 2 && finger(event, second)
                        && !moved(event.getX(index), event.getY(index), holdX, holdY)) {
                    // 고속펜은 실측 선분을 이미 비트맵에 그렸을 수 있다.
                    // 2손가락으로 전환 시 미완성 잔선을 지우기 위해 확정 획만 다시 그린다.
                    // 2손가락 전환은 예외적 이벤트이므로 이때만 캐시 전체를 갱신한다.
                    if (highSpeedStroke && highSpeedMoved) refreshCanvas();
                    highSpeedStroke=false;
                    highSpeedMoved=false;
                    // 두 손가락 후보가 되면 첫 임시 점만 거둔다. 쓰던 획은 원래 포인터 ID로 끝까지 추적한다.
                    activePointer = -1;
                    resetShapeStroke();
                    canJoin = false;
                    pairX1 = event.getX(index); pairY1 = event.getY(index);
                    pairX2 = event.getX(second); pairY2 = event.getY(second);
                    pairId = event.getPointerId(index);
                    visualX1 = pairX1; visualY1 = pairY1;
                    visualX2 = pairX2; visualY2 = pairY2;
                    rememberPair(pairX1, pairY1, pairX2, pairY2);
                    arm(Hold.PAIR, 150); // 150ms 내 즉시 줌/팬 모드 준비
                    invalidate();
                } else {
                    canJoin = false;
                }
                return true;
            }
            boolean lifted = action == MotionEvent.ACTION_UP ||
                    (action == MotionEvent.ACTION_POINTER_UP && event.getActionIndex() == index);
            if (action != MotionEvent.ACTION_MOVE && !lifted) return true;
            if (lifted && smartJustCommitted) {
                // 원/곡선이 이미 확정된 뒤 UP에 포함된 위치 잡음으로 점이 생기지 않도록 방지.
                finishGesture();
                hold=Hold.RELEASE;
                invalidate();
                return true;
            }
            for (int i = 0; i < event.getHistorySize(); i++)
                feed(event.getHistoricalX(index, i), event.getHistoricalY(index, i), event.getHistoricalEventTime(i));
            feed(event.getX(index), event.getY(index), event.getEventTime());
            if (lifted) {
                finishGesture();
                hold = Hold.RELEASE;
            }
            invalidate();
            return true;
        }

        private void beginInk(int id, float screenX, float screenY, long time, boolean erase) {
            resetShapeStroke();
            smartJustCommitted=false;
            smartHasFlushedInk=false;
            activePointer = id;
            erasing = erase;
            highlighting = currentTool == ToolMode.HIGHLIGHTER && !erase;
            highSpeedStroke = !erase && currentTool==ToolMode.PEN && highSpeedPenEnabled;
            highSpeedMoved=false;
            // 고속펜에서는 멈춤 시 새 도형 계산을 실행하지 않아 비교 조건을 단순화한다.
            smartStroke=!highSpeedStroke && !erase && currentTool==ToolMode.PEN && smartPenEnabled;
            shapeStroke = !erase && (smartStroke || currentTool == ToolMode.SMOOTH
                    || currentTool == ToolMode.HIGHLIGHTER);
            strokePaint.set(erasing ? eraserPaint : highlighting ? highlighterPaint : drawPaint);
            // [v2.5.0] 지우개를 포함한 모든 도구의 선택 굵기는 화면 픽셀이다.
            // 내부 Path는 칠판 좌표이므로 매 획 시작 시 1/zoom으로 환산해야
            // 확대(0.5~4배) 상태에서도 '화면상의 지우개 지름'이 항상 동일하다.
            // 확정된 삭제 궤적 자체는 문서에 붙어 저장되므로 이후 줌에는 문서와 함께 변한다.
            strokePaint.setStrokeWidth(strokePaint.getStrokeWidth() / scaleFactor);
            lastX = lastMidX = (screenX - panX) / scaleFactor;
            lastY = lastMidY = (screenY - panY) / scaleFactor;
            if (shapeStroke) appendShapePoint(lastX,lastY);
            currentStrokePath.rewind();
            currentStrokePath.moveTo(lastX, lastY);
            // A tiny capped segment also represents a tap (decimal point).
            currentStrokePath.rLineTo(0.01f, 0);
            pendingInk.set(currentStrokePath);
            pendingSegments = 0;
            tipX = lastX; tipY = lastY; tipTime = time;
            priorDx = priorDy = leadX = leadY = 0;
            fastPredictor.start(lastX, lastY, time);
            erasedInGesture.clear();
            if (erasing && (eraserMode != EraserMode.STROKE || hold == Hold.ERASE)) {
                inkCanvas.drawPath(currentStrokePath, strokePaint);
            }
        }

        // PAIR는 두 손가락 후보(150ms 이내 이동 시 즉시 NAVIGATE 전환),
        // ERASE는 넓게 시작한 접촉, RELEASE는 남은 접촉이 끝날 때까지 입력 무시.
        // 단일 손가락을 오래 눌렀다고 지우개로 바꾸지 않는다.
        private enum Hold { NONE, PAIR, ERASE, NAVIGATE, RELEASE }
        private Hold hold = Hold.NONE;
        private boolean canJoin;
        private float holdX, holdY, cursorX, cursorY;
        // 지우기 시작할 때 한 번 결정된 오프셋. 접촉 크기 잡음 때문에 원이 튀지 않도록 획 동안 고정.
        private float eraserOffsetY;
        private int eraserPointer = -1, pairId;
        private float pairX1, pairY1, pairX2, pairY2, pairMidX, pairMidY, pairSpan;
        // 인식 원은 오직 표시만 담당한다. 필기용 비트맵/Path에는 기록하지 않는다.
        // 좌표는 화면 좌표이며, Canvas의 viewMatrix를 복원한 뒤 그린다.
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint crosshairOutline = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint crosshairInk = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint touchIndicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float visualX1, visualY1, visualX2, visualY2;
        private int handVisualCount = 0;
        private final Runnable activateHold = () -> {
            if (hold == Hold.PAIR) hold = Hold.NAVIGATE;
            invalidate();
        };

        private boolean finger(MotionEvent e, int i) {
            return e.getToolType(i) == MotionEvent.TOOL_TYPE_FINGER;
        }

        private boolean broad(MotionEvent e, int i) {
            // [EB-86 IR 센서 실측 최적화]
            // 펜촉/손가락 끝은 TouchMajor 3.2px, Size 0.00107 고정.
            // 손가락 옆면은 22~82px, 손바닥은 47~82px (중앙값 68.8px, Size 0.015~0.026).
            // 35px 이상 또는 size 0.012 이상 시 손바닥으로 즉시 판정.
            return e.getTouchMajor(i) >= 35.0f || e.getSize(i) >= 0.012f;
        }

        private boolean moved(float x, float y, float originX, float originY) {
            float dx = x - originX, dy = y - originY;
            return dx * dx + dy * dy > 16f;
        }

        private void arm(Hold kind, int delayMs) {
            removeCallbacks(activateHold);
            hold = kind;
            postDelayed(activateHold, delayMs);
        }

        private void disarm() {
            if (hold == Hold.PAIR) removeCallbacks(activateHold);
            hold = Hold.NONE;
        }

        private void finishGesture() {
            boolean navigated = hold == Hold.NAVIGATE;
            disarm();
            endStroke();
            eraserPointer = -1;
            eraserOffsetY = 0f;
            canJoin = false;
            if (navigated) settleViewport();
            invalidate();
        }

        private void feed(float x, float y, long time) {
            if (canJoin && moved(x, y, holdX, holdY)) canJoin = false;
            // 도구 버튼으로 선택한 ERASER도 실제 브러시와 동일 위치에 커서를 따라오게 한다.
            // 커서 값 갱신은 O(1)이며 기본 PEN의 Path 렌더링에는 영향을 주지 않는다.
            if (erasing) {
                cursorX = x;
                cursorY = EraserPlacement.eraserY(y, eraserOffsetY,
                        getEraserScreenDiameter(), getHeight());
                sample(cursorX, cursorY, time);
            } else {
                sample(x, y, time);
            }
        }

        private void rememberPair(float x1, float y1, float x2, float y2) {
            pairMidX = (x1 + x2) * 0.5f;
            pairMidY = (y1 + y2) * 0.5f;
            pairSpan = (float)Math.hypot(x2 - x1, y2 - y1);
        }

        private void trackPair(MotionEvent e) {
            int first = e.findPointerIndex(pairId);
            if (first < 0) { finishGesture(); hold = Hold.RELEASE; return; }
            float x1 = e.getX(first), y1 = e.getY(first);
            float x2 = e.getX(1 - first), y2 = e.getY(1 - first);
            visualX1 = x1; visualY1 = y1;
            visualX2 = x2; visualY2 = y2;
            float oldX = pairMidX, oldY = pairMidY, oldSpan = pairSpan;
            rememberPair(x1, y1, x2, y2);
            if (hold == Hold.PAIR) {
                if (Math.hypot(x1 - pairX1, y1 - pairY1) > 16 ||
                        Math.hypot(x2 - pairX2, y2 - pairY2) > 16) {
                    disarm();
                    hold = Hold.NAVIGATE; // 손가락 이동 감지 시 취소하지 않고 즉시 네비게이션으로 전환
                } else {
                    return;
                }
            }
            float oldZoom = scaleFactor;
            if (oldSpan >= 1 && pairSpan >= 1)
                scaleFactor = Math.max(0.5f, Math.min(4f, oldZoom * pairSpan / oldSpan));
            panX = pairMidX + (panX - oldX) * (scaleFactor / oldZoom);
            panY = pairMidY + (panY - oldY) * (scaleFactor / oldZoom);
            updateMatrices();
            invalidate();
        }

        @Override public void onWindowFocusChanged(boolean focused) {
            super.onWindowFocusChanged(focused);
            if (!focused) { finishGesture(); handVisualCount = 0; }
        }

        @Override protected void onDetachedFromWindow() {
            finishGesture();
            removeCallbacks(expireTip);
            removeCallbacks(recognizeAfterPause);
            removeCallbacks(smartPauseTicker);
            smartTimerPending=false;
            tipRefreshPending = false;
            super.onDetachedFromWindow();
        }

        private void navigate(MotionEvent event, int action) {
            scaleDetector.onTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                handVisualCount = 0;
                settleViewport();
                invalidate();
                return;
            }
            int skip = action == MotionEvent.ACTION_POINTER_UP ? event.getActionIndex() : -1;
            int count = event.getPointerCount() - (skip >= 0 ? 1 : 0);
            handVisualCount = count == 2 ? 2 : 0;
            if (count == 0) { invalidate(); return; }
            float x = 0, y = 0;
            int j = 0;
            for (int i = 0; i < event.getPointerCount(); i++) {
                if (i == skip) continue;
                float px = event.getX(i), py = event.getY(i);
                if (j == 0) { visualX1 = px; visualY1 = py; }
                else if (j == 1) { visualX2 = px; visualY2 = py; }
                j++;
                x += px;
                y += py;
            }
            x /= count;
            y /= count;
            if (action == MotionEvent.ACTION_MOVE) {
                panX += x - handX;
                panY += y - handY;
                updateMatrices();
            }
            handX = x;
            handY = y;
            invalidate(); // 한 손가락에서도 이전 두 점 표시를 즉시 지운다.
        }

        private void sample(float screenX, float screenY, long time) {
            if (activePointer == -1) return;
            float x = (screenX - panX) / scaleFactor;
            float y = (screenY - panY) / scaleFactor;
            float dx = x - lastX, dy = y - lastY;
            float distance = (float) Math.hypot(dx, dy) * scaleFactor;
            if (smartJustCommitted) {
                // 원/곡선 확정 후 손을 떼지 않고 움직이면 별도의 다음 획으로 시작.
                // 미세 센서 잡음(3px)은 새로운 획으로 취급하지 않는다.
                if (distance < 6f) return;
                int nextPointer=activePointer;
                float startScreenX=lastX*scaleFactor+panX;
                float startScreenY=lastY*scaleFactor+panY;
                beginInk(nextPointer,startScreenX,startScreenY,time,false);
                sample(screenX,screenY,time);
                return;
            }

            // 순서가 중요하다. 비정상적으로 먼 좌표는 tip 갱신 전에 걸러야 가짜 직선이 생기지 않는다.
            if (distance > Math.max(96f, Math.min(getWidth(), getHeight()) * 0.14f)) {
                if (hold == Hold.ERASE) { eraserPointer = -1; hold = Hold.RELEASE; }
                endStroke();
                return;
            }
            // [v2.20.0] 획 지우개 (STROKE 모드) 처리 - 스치면 닿은 획 전체를 즉시 삭제
            if (erasing && eraserMode == EraserMode.STROKE && hold != Hold.ERASE) {
                float eraserR = getEraserScreenDiameter() * 0.5f / scaleFactor;
                float boardCursorX = (cursorX - panX) / scaleFactor;
                float boardCursorY = (cursorY - panY) / scaleFactor;
                boolean hitAny = false;
                for (int i = currentStrokes.size() - 1; i >= 0; i--) {
                    Stroke s = currentStrokes.get(i);
                    if (s.isEraser) continue;
                    if (EraserCollision.checkHit(s.path, s.width, s.bounds, boardCursorX, boardCursorY, eraserR)) {
                        erasedInGesture.add(s);
                        currentStrokes.remove(i);
                        hitAny = true;
                    }
                }
                if (hitAny) {
                    refreshCanvas();
                }
                lastX = lastMidX = x;
                lastY = lastMidY = y;
                return;
            }
            if (smartStroke && straightLocked) {
                // 좌표는 O(1)만 갱신: 위치 이동 시 펜 입력과 비트맵 캐시를 다시 그리지 않는다.
                if (distance < 0.8f) return;
                smartLineMoveX=x-smartLinePauseX;
                smartLineMoveY=y-smartLinePauseY;
                rebuildRecognizedPath();
                lastX=lastMidX=x; lastY=lastMidY=y;
                invalidate();
                return;
            }
            if (highSpeedStroke) {
                // [v2.20.0 초저지연 고속펜] HWUI 직접 렌더링 + FastInkPredictor + 24구간 동기화
                if (distance < 0.8f) return;
                currentStrokePath.lineTo(x, y);
                pendingInk.lineTo(x, y);
                fastPredictor.update(x, y, time, scaleFactor, strokePaint.getStrokeWidth());
                tipX = x; tipY = y; tipTime = time;
                lastX = lastMidX = x;
                lastY = lastMidY = y;
                highSpeedMoved = true;
                if (++pendingSegments >= 24) {
                    inkCanvas.drawPath(pendingInk, strokePaint);
                    pendingInk.rewind();
                    pendingInk.moveTo(x, y);
                    pendingSegments = 0;
                }
                return;
            }
            // 미세 이동도 펜 끝에는 즉시 반영한다. 아래 거리 임계값과 보정 계수는 곡선 몸통에만 적용한다.
            if (livePen()) {
                fastPredictor.update(x, y, time, scaleFactor, strokePaint.getStrokeWidth());
                updateTip(x, y, time);
            }
            if (distance < 0.8f) return;
            if (shapeStroke) {
                appendShapePoint(x,y);
                if (straightLocked || detectedShape != null) {
                    // 직선 확정 후에는 스플라인을 다시 계산하지 않고 끝점만 O(1) 변경.
                    // 원/직사각형은 정지 시 인식한 형상을 놓을 때까지 유지.
                    lastX=x; lastY=y; lastMidX=x; lastMidY=y;
                    invalidate();
                    return;
                }
            }
            float gain = .72f;
            if (shapeStroke && !smartStroke) {
                switch (getCorrectionLevel(currentTool)) {
                    case 1: gain = .86f; break;
                    case 2: gain = .73f; break;
                    case 3: gain = .58f; break;
                    case 4: gain = .44f; break;
                    // 강한 보정은 '획 완료 시'에 처리하므로 이동 중 좌표 지연은 억제한다.
                    default: gain = .56f; break;
                }
            }
            if (distance > 10f) gain = 1f;
            x = lastX + dx * gain;
            y = lastY + dy * gain;
            float midX = (lastX + x) * 0.5f, midY = (lastY + y) * 0.5f;
            currentStrokePath.quadTo(lastX, lastY, midX, midY);
            if (erasing) {
                segmentPath.rewind();
                segmentPath.moveTo(lastMidX, lastMidY);
                segmentPath.quadTo(lastX, lastY, midX, midY);
                inkCanvas.drawPath(segmentPath, strokePaint);
            }
            if (livePen()) {
                pendingInk.quadTo(lastX, lastY, midX, midY);
                // 64구간마다 확정된 몸통을 한 번 합친다. 긴 획에서도 다음 프레임은 남은 꼬리만 복사한다.
                if (++pendingSegments >= 64) {
                    canJoin = false;
                    inkCanvas.drawPath(pendingInk, strokePaint);
                    if (smartStroke) smartHasFlushedInk=true;
                    pendingInk.rewind();
                    pendingInk.moveTo(midX, midY);
                    pendingSegments = 0;
                }
            }
            lastMidX = midX;
            lastMidY = midY;
            lastX = x;
            lastY = y;
        }

        private void endStroke() {
            if (activePointer == -1) { resetShapeStroke(); smartJustCommitted=false; return; }
            removeCallbacks(recognizeAfterPause);
            removeCallbacks(smartPauseTicker);
            smartTimerPending=false;
            if (smartJustCommitted) {
                // 멈춤 시 이미 히스토리에 확정된 원/곡선을 UP에서 두 번 기록하지 않는다.
                activePointer=-1;
                smartJustCommitted=false;
                resetShapeStroke();
                invalidate();
                return;
            }
            // [v2.20.0] 획 지우개 히스토리 저장
            if (erasing && eraserMode == EraserMode.STROKE && hold != Hold.ERASE) {
                if (!erasedInGesture.isEmpty()) {
                    editHistory.recordRemove(new ArrayList<>(erasedInGesture));
                    erasedInGesture.clear();
                }
                activePointer = -1;
                resetShapeStroke();
                smartJustCommitted = false;
                invalidate();
                return;
            }
            if (highSpeedStroke) {
                if (!highSpeedMoved) {
                    currentStrokePath.rLineTo(0.01f, 0);
                    inkCanvas.drawPoint(lastX, lastY, strokePaint);
                } else if (pendingSegments > 0) {
                    inkCanvas.drawPath(pendingInk, strokePaint);
                    pendingInk.rewind();
                    pendingSegments = 0;
                }
                Stroke completed = new Stroke(currentStrokePath, strokePaint.getColor(),
                        strokePaint.getStrokeWidth(), false, false);
                currentStrokes.add(completed);
                editHistory.recordAdd(completed);
                activePointer = -1;
                highSpeedStroke = false;
                highSpeedMoved = false;
                resetShapeStroke();
                smartJustCommitted = false;
                invalidate();
                return;
            }
            // 기존 보정펜/형광펜만 UP에서도 닫힌 도형 인식을 허용한다.
            // 스마트 빠른펜은 멈추지 않고 뗀 경우 원래 빠른펜 획 그대로 저장한다.
            if (shapeStroke && !smartStroke && !straightLocked && detectedShape == null)
                detectedShape = InkShapeRecognizer.detectClosed(shapePoints,shapeCount,scaleFactor);
            if (shapeStroke && (straightLocked || detectedShape != null)) rebuildRecognizedPath();

            // [필기감 보호] 빠른펜의 예측/캐시/구간 확정 로직은 기존과 완전히 동일.
            float endX = livePen() ? tipX : lastX;
            float endY = livePen() ? tipY : lastY;
            currentStrokePath.lineTo(endX, endY);
            Path result = currentStrokePath;
            if (shapeStroke && (!smartStroke || straightLocked || detectedShape != null)) {
                result = (straightLocked || detectedShape != null)
                        ? recognizedPath : correctedSpline();
                inkCanvas.drawPath(result,strokePaint);
            } else if (erasing) {
                inkCanvas.drawLine(lastMidX, lastMidY, lastX, lastY, strokePaint);
            } else if (livePen()) {
                pendingInk.lineTo(endX, endY);
                inkCanvas.drawPath(pendingInk, strokePaint);
            } else {
                inkCanvas.drawPath(currentStrokePath, strokePaint);
            }
            Stroke completed = new Stroke(result, strokePaint.getColor(),
                    strokePaint.getStrokeWidth(), erasing, highlighting);
            currentStrokes.add(completed);
            editHistory.recordAdd(completed);
            activePointer = -1;
            resetShapeStroke();
            smartJustCommitted=false;
            invalidate();
        }

        // 예측 끝점을 지우는 예약은 최대 하나만 둔다. 실행 시 최신 입력 시각을 확인해 남은 시간만 다시 예약한다.
        private boolean tipRefreshPending;
        private final Runnable expireTip = new Runnable() {
            @Override public void run() {
                if (activePointer >= 0 && livePen() && !smartJustCommitted && !straightLocked
                        && (leadX != 0 || leadY != 0)) {
                    long remaining = 32 - (android.os.SystemClock.uptimeMillis() - tipTime);
                    if (remaining > 0) { postDelayed(this, remaining); return; }
                    invalidate();
                }
                tipRefreshPending = false;
            }
        };

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            /** [필기감 최우선] 60fps 유지를 위해 미리 그려진 비트맵만 출력함 */
            canvas.save();
            canvas.concat(viewMatrix);
            drawPaper(canvas);
            if (focus.isActive()) focus.draw(canvas, scaleFactor);
            drawInkCache(canvas);
            if (activePointer != -1 && !erasing && !smartJustCommitted) {
                if (highSpeedStroke) {
                    preview.set(pendingInk);
                    preview.lineTo(tipX, tipY);
                    if (fastPredictor.hasLead() && android.os.SystemClock.uptimeMillis() - tipTime < 35) {
                        float px = tipX + fastPredictor.getLeadX();
                        float py = tipY + fastPredictor.getLeadY();
                        preview.quadTo(tipX, tipY, px, py);
                    }
                    canvas.drawPath(preview, strokePaint);
                } else if (shapeStroke && (straightLocked || detectedShape != null)) {
                    preview.set(recognizedPath);
                    canvas.drawPath(preview, strokePaint);
                } else if (livePen()) {
                    // 보정된 몸통 뒤에 최신 실측점을 바로 잇는다. 예측은 이 임시 Path에만 덧그린다.
                    // 입력이 멎으면 예약된 갱신에서 예측 부분이 사라지고 실측점만 남는다.
                    preview.set(pendingInk);
                    preview.lineTo(tipX, tipY);
                    if (fastPredictor.hasLead() && android.os.SystemClock.uptimeMillis() - tipTime < 35) {
                        float px = tipX + fastPredictor.getLeadX();
                        float py = tipY + fastPredictor.getLeadY();
                        preview.quadTo(tipX, tipY, px, py);
                        if (!tipRefreshPending) {
                            tipRefreshPending = true;
                            postDelayed(expireTip, 32);
                        }
                    } else if ((leadX != 0 || leadY != 0)
                            && android.os.SystemClock.uptimeMillis() - tipTime < 32) {
                        preview.lineTo(tipX + leadX, tipY + leadY);
                        if (!tipRefreshPending) {
                            tipRefreshPending = true;
                            postDelayed(expireTip, 32);
                        }
                    }
                    canvas.drawPath(preview, strokePaint);
                } else {
                    preview.set(currentStrokePath);
                    preview.lineTo(lastX, lastY);
                    canvas.drawPath(preview, strokePaint);
                }
            }
            // 지우개 커서와 십자선은 화면 좌표에서 렌더링해야 줌과 무관한 크기다.
            // 실제 지워진 획은 별도의 CLEAR Path에만 들어간다. (화면 오버레이 저장 금지)
            if (!focus.isActive()) focus.draw(canvas, scaleFactor);
            canvas.restore();
            if (activePointer >= 0 && erasing) drawEraserIndicator(canvas);

            // 화면 좌표 기준 고정된 반경(16px). 확대/축소에서도 접촉 중심을 정확히 보여 준다.
            // PEN 모드에서 손가락 두 개가 인정된 경우와 HAND 모드에서 두 접촉이 있는 경우에만 표시.
            if (hold == Hold.PAIR || hold == Hold.NAVIGATE ||
                    (currentTool == ToolMode.HAND && handVisualCount == 2)) {
                drawTouchIndicator(canvas, visualX1, visualY1);
                drawTouchIndicator(canvas, visualX2, visualY2);
            }
        }

        /** 지우개 실제 지름은 확대/축소 전과 동일. 손을 가리는 중앙부는 비워 둔다. */
        private void drawEraserIndicator(Canvas canvas) {
            float x=cursorX, y=cursorY;
            float radius=getEraserScreenDiameter()*.5f;
            ring.setStyle(Paint.Style.STROKE);
            if (eraserMode == EraserMode.STROKE && hold != Hold.ERASE) {
                // 획 지우개(스침 삭제): 강조 타깃 링
                ring.setColor(0xFF0284C7);
                ring.setStrokeWidth(5f);
                canvas.drawCircle(x,y,radius,ring);
                ring.setStyle(Paint.Style.FILL);
                ring.setColor(0x280284C7);
                canvas.drawCircle(x,y,radius,ring);
                ring.setStyle(Paint.Style.STROKE);
            } else {
                ring.setColor(0xFF102334);
                ring.setStrokeWidth(4f);
                canvas.drawCircle(x,y,radius,ring);
                ring.setColor(0xFFE2F7FF);
                ring.setStrokeWidth(2f);
                canvas.drawCircle(x,y,radius,ring);
            }
            if (!eraserCrosshairEnabled) return;

            float side=Math.min(getWidth(),getHeight());
            float inner=Math.max(radius+8f,side*.061f);
            float base = eraserCrosshairLength == 1 ? .11f
                    : eraserCrosshairLength == 2 ? .14f : .18f;
            float outer=Math.max(side*base,inner+side*.06f);
            drawCrosshairArms(canvas,x,y,inner,outer,crosshairOutline);
            drawCrosshairArms(canvas,x,y,inner,outer,crosshairInk);
        }

        private void drawCrosshairArms(Canvas canvas,float x,float y,float inner,float outer,Paint paint) {
            canvas.drawLine(x-outer,y,x-inner,y,paint);
            canvas.drawLine(x+inner,y,x+outer,y,paint);
            canvas.drawLine(x,y-outer,x,y-inner,paint);
            canvas.drawLine(x,y+inner,x,y+outer,paint);
        }

        private void drawTouchIndicator(Canvas canvas, float x, float y) {
            touchIndicatorPaint.setStyle(Paint.Style.FILL);
            touchIndicatorPaint.setColor(0x447DD3FC);
            canvas.drawCircle(x, y, 16f, touchIndicatorPaint);
            touchIndicatorPaint.setStyle(Paint.Style.STROKE);
            touchIndicatorPaint.setStrokeWidth(2.5f);
            touchIndicatorPaint.setColor(0xFF0284C7);
            canvas.drawCircle(x, y, 16f, touchIndicatorPaint);
            touchIndicatorPaint.setStyle(Paint.Style.FILL);
            touchIndicatorPaint.setColor(Color.WHITE);
            canvas.drawCircle(x, y, 3f, touchIndicatorPaint);
        }

        private void drawPaper(Canvas canvas) {
            if (showGrid) drawGrid(canvas);
            if (pdfBitmap != null) canvas.drawBitmap(pdfBitmap, null, pdfRect, null);
            if (detailBitmap != null) canvas.drawBitmap(detailBitmap, null, detailRect, bitmapPaint);
            // PDF/배경만 가린다. 이후 onDraw의 잉크는 마스크 위에 그려지므로 새 필기를 가리지 않는다.
            for (LassoFocus.Mask mask : retainedPageMasks) mask.draw(canvas);
        }

        private void drawInkCache(Canvas canvas) {
            if (inkBitmap != null) {
                canvas.save();
                canvas.concat(inkToBoard);
                canvas.drawBitmap(inkBitmap, 0, 0, bitmapPaint);
                canvas.restore();
            }
        }

        private void drawGrid(Canvas canvas) {
            float step = 80f;
            for (float i = 0; i < getWidth(); i += step) canvas.drawLine(i, 0, i, getHeight(), gridPaint);
            for (float i = 0; i < getHeight(); i += step) canvas.drawLine(0, i, getWidth(), i, gridPaint);
        }
    }
}

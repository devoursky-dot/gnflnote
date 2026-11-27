package kr.hs.gnflhs.gnflpdf;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 전자칠판 대화면 전용 내장 PDF 파일 탐색기.
 * 교과서, 문제집, 시험지 등 /sdcard/Download, /sdcard/Documents, USB 드라이브에
 * 저장된 PDF/PDD 문서를 빠르게 검색하여 원터치로 판서 배경에 적재한다.
 */
public class PdfBrowserDialog {

    public interface OnPdfSelectedListener {
        void onPdfSelected(File file);
        void onOpenSystemPicker();
    }

    private final Activity activity;
    private final OnPdfSelectedListener listener;
    private Dialog dialog;
    private ListView listView;
    private ProgressBar progressBar;
    private TextView emptyText;
    private EditText searchInput;

    private final List<PdfItem> allItems = new ArrayList<>();
    private final List<PdfItem> filteredItems = new ArrayList<>();
    private PdfAdapter adapter;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Thread scanThread;

    public static class PdfItem {
        public final File file;
        public final String name;
        public final String path;
        public final long size;
        public final long lastModified;

        public PdfItem(File file) {
            this.file = file;
            this.name = file.getName();
            this.path = file.getAbsolutePath();
            this.size = file.length();
            this.lastModified = file.lastModified();
        }
    }

    public PdfBrowserDialog(Activity activity, OnPdfSelectedListener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    public void show() {
        if (activity == null || activity.isFinishing()) return;

        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        int dp12 = dp(12);
        int dp16 = dp(16);
        int dp20 = dp(20);

        // Root container
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(createRoundedBg(0xFF1E293B, 0xFF334155, dp(16), dp(1)));
        root.setPadding(dp20, dp20, dp20, dp16);

        // Header Title Bar
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.drawable.ic_launcher);
        header.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));

        LinearLayout titleCol = new LinearLayout(activity);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setPadding(dp12, 0, 0, 0);

        TextView txtTitle = new TextView(activity);
        txtTitle.setText("교과서 / 학습지 PDF 열기");
        txtTitle.setTextColor(Color.WHITE);
        txtTitle.setTextSize(20);
        txtTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        titleCol.addView(txtTitle);

        TextView txtSubtitle = new TextView(activity);
        txtSubtitle.setText("원하는 문서를 선택하면 칠판 배경으로 즉시 펼쳐져 판서할 수 있습니다.");
        txtSubtitle.setTextColor(0xFF94A3B8);
        txtSubtitle.setTextSize(13);
        titleCol.addView(txtSubtitle);

        header.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnClose = createStyledButton("✕", 0x33FFFFFF, Color.WHITE, dp(10));
        btnClose.setOnClickListener(v -> dialog.dismiss());
        header.addView(btnClose, new LinearLayout.LayoutParams(dp(42), dp(42)));

        root.addView(header);

        // Search bar
        LinearLayout searchBar = new LinearLayout(activity);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setBackground(createRoundedBg(0xFF0F172A, 0xFF334155, dp(8), dp(1)));
        searchBar.setPadding(dp16, dp(8), dp16, dp(8));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchLp.setMargins(0, dp16, 0, dp12);
        searchBar.setLayoutParams(searchLp);

        searchInput = new EditText(activity);
        searchInput.setHint("문서 제목 검색 (예: 미적분, 수학, 자이스토리)...");
        searchInput.setHintTextColor(0xFF64748B);
        searchInput.setTextColor(Color.WHITE);
        searchInput.setTextSize(15);
        searchInput.setBackground(null);
        searchInput.setSingleLine(true);
        searchBar.addView(searchInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(searchBar);

        // List & Progress view
        LinearLayout contentArea = new LinearLayout(activity);
        contentArea.setOrientation(LinearLayout.VERTICAL);
        contentArea.setGravity(Gravity.CENTER);

        progressBar = new ProgressBar(activity);
        contentArea.addView(progressBar, new LinearLayout.LayoutParams(dp(48), dp(48)));

        emptyText = new TextView(activity);
        emptyText.setText("PDF 문서를 검색하는 중...");
        emptyText.setTextColor(0xFF94A3B8);
        emptyText.setTextSize(14);
        emptyText.setPadding(0, dp12, 0, 0);
        contentArea.addView(emptyText);

        listView = new ListView(activity);
        listView.setVisibility(View.GONE);
        listView.setDivider(createDividerDrawable());
        listView.setDividerHeight(dp(1));
        listView.setSelector(createRoundedBg(0x3338BDF8, 0, dp(8), 0));
        contentArea.addView(listView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(contentArea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Footer Action Bar
        LinearLayout footer = new LinearLayout(activity);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        footer.setPadding(0, dp12, 0, 0);

        Button btnSystemPicker = createStyledButton("📁 시스템 파일 선택기 열기", 0xFF334155, 0xFFE2E8F0, dp(8));
        btnSystemPicker.setOnClickListener(v -> {
            dialog.dismiss();
            if (listener != null) listener.onOpenSystemPicker();
        });
        footer.addView(btnSystemPicker, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));

        View spacer = new View(activity);
        footer.addView(spacer, new LinearLayout.LayoutParams(dp12, dp(1)));

        Button btnDismiss = createStyledButton("닫기", 0xFF475569, Color.WHITE, dp(8));
        btnDismiss.setOnClickListener(v -> dialog.dismiss());
        footer.addView(btnDismiss, new LinearLayout.LayoutParams(dp(80), dp(44)));

        root.addView(footer);

        // Set Dialog content and size
        int width = Math.min(dp(920), activity.getResources().getDisplayMetrics().widthPixels - dp(80));
        int height = Math.min(dp(620), activity.getResources().getDisplayMetrics().heightPixels - dp(60));
        dialog.setContentView(root, new ViewGroup.LayoutParams(width, height));
        dialog.show();

        // Setup adapter
        adapter = new PdfAdapter();
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < filteredItems.size()) {
                PdfItem item = filteredItems.get(position);
                dialog.dismiss();
                if (listener != null) {
                    listener.onPdfSelected(item.file);
                }
            }
        });

        // Search text watcher
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterItems(s != null ? s.toString() : "");
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        // Start background scan
        startScan();
    }

    private void filterItems(String query) {
        filteredItems.clear();
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            filteredItems.addAll(allItems);
        } else {
            for (PdfItem item : allItems) {
                if (item.name.toLowerCase(Locale.ROOT).contains(q) ||
                    item.path.toLowerCase(Locale.ROOT).contains(q)) {
                    filteredItems.add(item);
                }
            }
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        if (filteredItems.isEmpty()) {
            emptyText.setText(q.isEmpty() ? "발견된 PDF 문서가 없습니다." : "'" + query + "' 검색 결과가 없습니다.");
            emptyText.setVisibility(View.VISIBLE);
        } else {
            emptyText.setVisibility(View.GONE);
        }
    }

    private void startScan() {
        scanThread = new Thread(() -> {
            List<PdfItem> found = new ArrayList<>();
            List<File> searchDirs = new ArrayList<>();

            // 1. Primary directories
            File sdcard = Environment.getExternalStorageDirectory();
            if (sdcard != null && sdcard.exists()) {
                searchDirs.add(new File(sdcard, "Download"));
                searchDirs.add(new File(sdcard, "Documents"));
                searchDirs.add(new File(sdcard, "Note"));
                searchDirs.add(sdcard);
            }

            // 2. Storage root (for USB and external drives)
            File storage = new File("/storage");
            if (storage.exists() && storage.isDirectory()) {
                File[] subs = storage.listFiles();
                if (subs != null) {
                    for (File sub : subs) {
                        if (sub.isDirectory() && !sub.getName().equalsIgnoreCase("emulated") && !sub.getName().equalsIgnoreCase("self")) {
                            searchDirs.add(sub);
                        }
                    }
                }
            }

            // 3. Scan directories
            List<String> visitedPaths = new ArrayList<>();
            for (File dir : searchDirs) {
                scanRecursive(dir, found, visitedPaths, 0, 4);
            }

            // 4. Sort by latest modified date
            Collections.sort(found, (a, b) -> Long.compare(b.lastModified, a.lastModified));

            mainHandler.post(() -> {
                if (dialog == null || !dialog.isShowing()) return;
                allItems.clear();
                allItems.addAll(found);
                filterItems(searchInput != null ? searchInput.getText().toString() : "");

                progressBar.setVisibility(View.GONE);
                listView.setVisibility(View.VISIBLE);
                if (found.isEmpty()) {
                    emptyText.setText("발견된 PDF 문서가 없습니다. 시스템 파일 선택기를 사용해 보세요.");
                    emptyText.setVisibility(View.VISIBLE);
                } else {
                    emptyText.setVisibility(View.GONE);
                }
            });
        });
        scanThread.start();
    }

    private void scanRecursive(File dir, List<PdfItem> out, List<String> visited, int depth, int maxDepth) {
        if (dir == null || !dir.exists() || !dir.canRead() || depth > maxDepth) return;
        String absPath = dir.getAbsolutePath();
        if (visited.contains(absPath)) return;
        visited.add(absPath);

        // Ignore Android internal cache directories to save time
        String name = dir.getName();
        if (name.startsWith(".") || name.equalsIgnoreCase("cache") || name.equalsIgnoreCase("Android")) {
            return;
        }

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isDirectory()) {
                scanRecursive(f, out, visited, depth + 1, maxDepth);
            } else if (f.isFile()) {
                String fname = f.getName().toLowerCase(Locale.ROOT);
                if (fname.endsWith(".pdf") || fname.endsWith(".pdd")) {
                    out.add(new PdfItem(f));
                }
            }
        }
    }

    private class PdfAdapter extends BaseAdapter {
        private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

        @Override
        public int getCount() { return filteredItems.size(); }
        @Override
        public Object getItem(int position) { return filteredItems.get(position); }
        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
            } else {
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(12), dp(16), dp(12));

                ImageView icon = new ImageView(activity);
                icon.setImageResource(R.drawable.ic_launcher);
                icon.setId(101);
                row.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));

                LinearLayout col = new LinearLayout(activity);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(dp(14), 0, 0, 0);

                TextView name = new TextView(activity);
                name.setId(102);
                name.setTextColor(Color.WHITE);
                name.setTextSize(17);
                name.setTypeface(null, android.graphics.Typeface.BOLD);
                col.addView(name);

                TextView sub = new TextView(activity);
                sub.setId(103);
                sub.setTextColor(0xFF94A3B8);
                sub.setTextSize(12);
                col.addView(sub);

                row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }

            PdfItem item = filteredItems.get(position);
            TextView name = row.findViewById(102);
            TextView sub = row.findViewById(103);

            name.setText(item.name);
            String sizeStr = formatSize(item.size);
            String dateStr = dateFormat.format(new Date(item.lastModified));
            sub.setText(String.format("%s  •  %s  •  %s", sizeStr, dateStr, item.path));

            return row;
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format(Locale.getDefault(), "%.1f %cB", bytes / Math.pow(1024, exp), pre);
    }

    private Button createStyledButton(String text, int bgColor, int textColor, int cornerRadiusDp) {
        Button btn = new Button(activity);
        btn.setText(text);
        btn.setTextColor(textColor);
        btn.setTextSize(14);
        btn.setTypeface(null, android.graphics.Typeface.BOLD);
        btn.setBackground(createRoundedBg(bgColor, 0, dp(cornerRadiusDp), 0));
        btn.setPadding(dp(14), 0, dp(14), 0);
        return btn;
    }

    private GradientDrawable createRoundedBg(int bgColor, int strokeColor, int cornerRadiusPx, int strokeWidthPx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(cornerRadiusPx);
        if (strokeWidthPx > 0 && strokeColor != 0) {
            gd.setStroke(strokeWidthPx, strokeColor);
        }
        return gd;
    }

    private GradientDrawable createDividerDrawable() {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(0x22FFFFFF);
        gd.setSize(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        return gd;
    }

    private int dp(float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}

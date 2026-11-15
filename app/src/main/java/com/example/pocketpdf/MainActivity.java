package com.example.pocketpdf;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int OPEN_PDF = 10, PICK_IMAGES = 11, PDF_TO_IMAGES = 12, DELETE_PDF = 13, MERGE_FIRST = 14, MERGE_SECOND = 15, REQ_STORAGE = 20;
    private LinearLayout root;
    private PageView pageView;
    private Button pageBtn, zoomBtn;
    private Uri firstMergeUri;
    private String firstMergeName;
    private Runnable pendingAfterPermission;

    private interface Job { Storage.Saved run() throws Exception; }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        PdfOps.init(this);
        buildHome();
        handleIncoming(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleIncoming(intent); }

    private void handleIncoming(Intent intent) {
        if (intent == null) return;
        Uri uri = intent.getData();
        if (uri == null && Intent.ACTION_SEND.equals(intent.getAction())) uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri != null) openPdf(uri);
    }

    // ---------------------------------------------------------------- Home

    private void buildHome() {
        closeRenderer();
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.rgb(248, 249, 252));
        TextView title = new TextView(this); title.setText("Pocket PDF"); title.setTextSize(25); title.setTextColor(Color.rgb(25, 35, 55)); title.setPadding(20, 24, 20, 8); title.setGravity(Gravity.CENTER_VERTICAL); root.addView(title);
        TextView subtitle = new TextView(this); subtitle.setText("A fast, simple PDF reader\nCreated files are saved in Download/PocketPDF"); subtitle.setTextSize(14); subtitle.setTextColor(Color.GRAY); subtitle.setPadding(20, 0, 20, 18); root.addView(subtitle);
        addAction("Open PDF", v -> choose(OPEN_PDF, "application/pdf"));
        addAction("Images to PDF", v -> choose(PICK_IMAGES, "image/*"));
        addAction("PDF to Images", v -> choose(PDF_TO_IMAGES, "application/pdf"));
        addAction("Delete PDF Pages", v -> choose(DELETE_PDF, "application/pdf"));
        addAction("Merge Two PDFs", v -> choose(MERGE_FIRST, "application/pdf"));
        ScrollView scroll = new ScrollView(this); scroll.setFitsSystemWindows(true); scroll.addView(root); setContentView(scroll);
    }

    private void addAction(String label, View.OnClickListener listener) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(16); b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(16, 6, 16, 6); root.addView(b, lp);
    }

    private void choose(int request, String type) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType(type);
        if (request == PICK_IMAGES) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, request);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null && request != PICK_IMAGES) return;
        try {
            if (request == OPEN_PDF) openPdf(uri);
            else if (request == PICK_IMAGES) {
                ArrayList<Uri> images = new ArrayList<>();
                if (data.getClipData() != null) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++) images.add(data.getClipData().getItemAt(i).getUri());
                } else if (uri != null) {
                    images.add(uri);
                }
                if (!images.isEmpty()) doImagesToPdf(images);
            } else if (request == PDF_TO_IMAGES) doPdfToImages(uri);
            else if (request == DELETE_PDF) askDeletePages(uri);
            else if (request == MERGE_FIRST) {
                firstMergeUri = uri; firstMergeName = Storage.displayName(this, uri);
                toast("Now choose the second PDF");
                choose(MERGE_SECOND, "application/pdf");
            } else if (request == MERGE_SECOND) doMerge(firstMergeUri, uri);
        } catch (Exception e) { toast("Operation failed: " + e.getMessage()); }
    }

    // ---------------------------------------------------------------- Operations (saved to Download/PocketPDF)

    private void doImagesToPdf(final ArrayList<Uri> images) {
        final String name = "images_" + stamp() + ".pdf";
        runJob("Creating PDF…", () -> {
            File tmp = PdfOps.imagesToPdf(this, images);
            try { return Storage.save(this, tmp, name, "application/pdf", null); } finally { tmp.delete(); }
        });
    }

    private void doPdfToImages(final Uri uri) {
        final String sub = baseName(Storage.displayName(this, uri)) + "_pages_" + stamp();
        runJob("Converting pages…", () -> {
            int n = PdfOps.pdfToImages(this, uri, sub);
            Storage.Saved s = new Storage.Saved(null, Storage.SHOWN_ROOT + "/" + sub);
            s.count = n;
            return s;
        });
    }

    private void askDeletePages(final Uri uri) {
        final EditText input = new EditText(this);
        input.setHint("e.g. 2, 4-6 (page numbers start at 1)");
        new AlertDialog.Builder(this).setTitle("Pages to delete").setView(wrap(input))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    final String expr = input.getText().toString();
                    final String name = baseName(Storage.displayName(this, uri)) + "_edited.pdf";
                    runJob("Editing PDF…", () -> {
                        File tmp = PdfOps.deletePages(this, uri, expr);
                        try { return Storage.save(this, tmp, name, "application/pdf", null); } finally { tmp.delete(); }
                    });
                }).show();
    }

    private void doMerge(final Uri first, final Uri second) {
        final String name = baseName(firstMergeName) + "_merged.pdf";
        runJob("Merging PDFs…", () -> {
            File tmp = PdfOps.merge(this, first, second);
            try { return Storage.save(this, tmp, name, "application/pdf", null); } finally { tmp.delete(); }
        });
    }

    private void runJob(final String message, final Job job) {
        ensureStorage(() -> {
            final ProgressDialog pd = new ProgressDialog(this);
            pd.setMessage(message); pd.setCancelable(false); pd.show();
            new Thread(() -> {
                Storage.Saved s = null; String err = null;
                try { s = job.run(); } catch (Throwable t) { err = t.getMessage() != null ? t.getMessage() : t.toString(); }
                final Storage.Saved fs = s; final String ferr = err;
                runOnUiThread(() -> {
                    try { pd.dismiss(); } catch (Exception ignored) {}
                    if (isFinishing()) return;
                    if (ferr != null) toast("Operation failed: " + ferr); else showSaved(fs);
                });
            }).start();
        });
    }

    private void ensureStorage(Runnable r) {
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingAfterPermission = r;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        } else r.run();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != REQ_STORAGE) return;
        Runnable r = pendingAfterPermission; pendingAfterPermission = null;
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED && r != null) r.run();
        else toast("Storage permission is required to save files to Downloads");
    }

    private void showSaved(final Storage.Saved s) {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        if (s.uri == null) {
            b.setTitle("Done").setMessage(s.count + " page image(s) saved in:\n" + s.where).setPositiveButton("OK", null);
        } else {
            b.setTitle("Saved").setMessage(s.where)
                    .setPositiveButton("Open", (d, w) -> openPdf(s.uri))
                    .setNeutralButton("Share", (d, w) -> share(s.uri))
                    .setNegativeButton("Close", null);
        }
        b.show();
    }

    private void share(Uri uri) {
        Intent i = new Intent(Intent.ACTION_SEND); i.setType("application/pdf");
        i.putExtra(Intent.EXTRA_STREAM, uri); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(i, "Share PDF")); } catch (Exception e) { toast("No app can share this file"); }
    }

    // ---------------------------------------------------------------- Reader

    private void openPdf(Uri uri) {
        ParcelFileDescriptor fd = null;
        try {
            closeRenderer();
            fd = getContentResolver().openFileDescriptor(uri, "r");
            if (fd == null) throw new Exception("File cannot be opened");
            PdfRenderer r = new PdfRenderer(fd);
            if (r.getPageCount() == 0) { r.close(); throw new Exception("PDF has no pages"); }
            showReader();
            pageView.setDocument(fd, r);
        } catch (Exception e) {
            try { if (fd != null) fd.close(); } catch (Exception ignored) {}
            toast("Unable to open PDF: " + e.getMessage());
            buildHome();
        }
    }

    private void showReader() {
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setBackgroundColor(Color.rgb(245, 246, 249)); col.setFitsSystemWindows(true);

        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); top.setPadding(dp(6), dp(4), dp(6), dp(4));
        top.addView(smallButton("Home", v -> buildHome()));
        pageBtn = smallButton("- / -", v -> askPage());
        top.addView(pageBtn, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(smallButton("‹", v -> { if (pageView != null) pageView.goTo(pageView.getPageIndex() - 1, false); }));
        top.addView(smallButton("›", v -> { if (pageView != null) pageView.goTo(pageView.getPageIndex() + 1, false); }));
        col.addView(top);

        pageView = new PageView(this);
        pageView.setListener(new PageView.Listener() {
            @Override public void onChanged() { updateLabels(); }
            @Override public void onError(String message) { toast(message); }
        });
        col.addView(pageView, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bottom = new LinearLayout(this); bottom.setGravity(Gravity.CENTER_VERTICAL); bottom.setPadding(dp(6), dp(4), dp(6), dp(4));
        bottom.addView(smallButton("−", v -> { if (pageView != null) pageView.zoomBy(1f / 1.25f); }), new LinearLayout.LayoutParams(0, -2, 1));
        zoomBtn = smallButton("100%", v -> askZoom());
        bottom.addView(zoomBtn, new LinearLayout.LayoutParams(0, -2, 1.4f));
        bottom.addView(smallButton("+", v -> { if (pageView != null) pageView.zoomBy(1.25f); }), new LinearLayout.LayoutParams(0, -2, 1));
        bottom.addView(smallButton("Fit page", v -> { if (pageView != null) pageView.fitPage(); }), new LinearLayout.LayoutParams(0, -2, 1.4f));
        col.addView(bottom);

        setContentView(col);
    }

    private void updateLabels() {
        if (pageView == null || pageBtn == null || zoomBtn == null) return;
        pageBtn.setText((pageView.getPageIndex() + 1) + " / " + pageView.getPageCount());
        zoomBtn.setText(Math.round(pageView.getZoom() * 100) + "%");
    }

    private void askPage() {
        if (pageView == null) return;
        final int count = pageView.getPageCount();
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("1 - " + count);
        input.setText(String.valueOf(pageView.getPageIndex() + 1));
        input.selectAll();
        new AlertDialog.Builder(this).setTitle("Go to page (1 - " + count + ")").setView(wrap(input))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Go", (d, w) -> {
                    try {
                        int n = Integer.parseInt(input.getText().toString().trim());
                        if (n < 1 || n > count) { toast("Page must be between 1 and " + count); return; }
                        if (pageView != null) pageView.goTo(n - 1, false);
                    } catch (NumberFormatException e) { toast("Enter a page number"); }
                }).show();
    }

    private void askZoom() {
        if (pageView == null) return;
        final String[] items = {"Fit width (100%)", "Fit page", "150%", "200%", "300%", "Custom…"};
        new AlertDialog.Builder(this).setTitle("Zoom").setItems(items, (d, i) -> {
            if (pageView == null) return;
            if (i == 0) pageView.setZoom(1f);
            else if (i == 1) pageView.fitPage();
            else if (i == 2) pageView.setZoom(1.5f);
            else if (i == 3) pageView.setZoom(2f);
            else if (i == 4) pageView.setZoom(3f);
            else askCustomZoom();
        }).show();
    }

    private void askCustomZoom() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("25 - 600");
        new AlertDialog.Builder(this).setTitle("Zoom percent").setView(wrap(input))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("OK", (d, w) -> {
                    try {
                        int p = Integer.parseInt(input.getText().toString().trim());
                        if (pageView != null) pageView.setZoom(p / 100f);
                    } catch (NumberFormatException e) { toast("Enter a number"); }
                }).show();
    }

    @Override public void onBackPressed() {
        if (pageView != null) buildHome(); else super.onBackPressed();
    }

    // ---------------------------------------------------------------- Helpers

    private View wrap(View v) {
        FrameLayout f = new FrameLayout(this); f.setPadding(dp(20), dp(8), dp(20), 0); f.addView(v); return f;
    }

    private Button smallButton(String label, View.OnClickListener l) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false);
        b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(0); b.setMinimumHeight(dp(44));
        b.setPadding(dp(14), 0, dp(14), 0); b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static String stamp() { return new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()); }

    private static String baseName(String n) {
        if (n == null || n.isEmpty()) return "document";
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_");
        return n.length() > 60 ? n.substring(0, 60) : n;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private void closeRenderer() { if (pageView != null) { pageView.release(); pageView = null; } pageBtn = null; zoomBtn = null; }

    @Override protected void onDestroy() { closeRenderer(); super.onDestroy(); }
}

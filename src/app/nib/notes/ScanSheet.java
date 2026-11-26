package app.nib.notes;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfDocument;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

/**
 * Multi-page document scan review sheet:
 * previews captured camera pages, lets the user switch Document/Color/Original enhancement,
 * rotate or remove pages, add more pages via camera, and compile everything into a crisp PDF notebook.
 */
final class ScanSheet {
    static final int MODE_DOC = 0;   // high-contrast grayscale cleanup for crisp paper & text
    static final int MODE_COLOR = 1; // brightness & contrast boost keeping ink colors vivid
    static final int MODE_ORIG = 2;  // unedited photo

    static final class Page {
        final File file;
        int rotation; // 0, 90, 180, 270
        int mode = MODE_DOC;
        Page(File file) { this.file = file; }
    }

    interface Callbacks {
        void onAddAnotherPage();
        void onSaveScannedPdf(String title, File pdfFile);
    }

    private final Activity activity;
    private final ExecutorService bg;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Callbacks callbacks;
    private final ArrayList<Page> pages = new ArrayList<>();
    private int selected = 0;

    private Dialog dialog;
    private EditText titleInput;
    private ImageView previewImg;
    private TextView pageBadge;
    private LinearLayout thumbStrip;
    private LinearLayout modeSegHost;
    private TextView saveBtn;
    private boolean saving = false;

    ScanSheet(Activity activity, ExecutorService bg, Callbacks callbacks) {
        this.activity = activity;
        this.bg = bg;
        this.callbacks = callbacks;
    }

    static File scanDir(Context c) {
        File d = new File(c.getCacheDir(), "scans");
        d.mkdirs();
        return d;
    }

    static File createCaptureFile(Context c) {
        return new File(scanDir(c), "scan_" + System.currentTimeMillis() + ".jpg");
    }

    static Uri captureUri(File f) {
        return Uri.parse("content://" + ExportProvider.AUTHORITY + "/scans/" + Uri.encode(f.getName()));
    }

    void addCapturedFile(File f) {
        if (f == null || !f.isFile() || f.length() == 0) return;
        pages.add(new Page(f));
        selected = pages.size() - 1;
        showOrRefresh();
    }

    boolean hasPages() {
        return !pages.isEmpty();
    }

    void showOrRefresh() {
        if (pages.isEmpty()) {
            dismissAndCleanup();
            return;
        }
        if (dialog == null || !dialog.isShowing()) {
            buildDialog();
            dialog.show();
        }
        if (selected >= pages.size()) selected = pages.size() - 1;
        if (selected < 0) selected = 0;
        refreshUi();
    }

    void dismissAndCleanup() {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
        dialog = null;
        cleanupFiles();
    }

    private void cleanupFiles() {
        for (Page p : pages) {
            try { if (p.file != null) p.file.delete(); } catch (Exception ignored) { }
        }
        pages.clear();
    }

    private void buildDialog() {
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(d -> cleanupFiles());

        int sw = activity.getResources().getDisplayMetrics().widthPixels;
        int sh = activity.getResources().getDisplayMetrics().heightPixels;
        int pad = Ui.dp(activity, sw < Ui.dp(activity, 480) ? 16 : 22);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, Ui.dp(activity, 18), pad, Ui.dp(activity, 18));
        root.setBackground(Ui.round(Ui.color(activity, R.color.surface), Ui.dp(activity, 24)));
        root.setClipToOutline(true);

        // Header: title + Cancel + Save
        LinearLayout head = new LinearLayout(activity);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(activity, "Scanned document", 21, Ui.color(activity, R.color.on_surface), Theme.titleFace(activity)),
                new LinearLayout.LayoutParams(0, -2, 1f));

        TextView cancel = Ui.text(activity, "Cancel", 14.5f, Ui.color(activity, R.color.on_surface_variant), Ui.medium());
        cancel.setPadding(Ui.dp(activity, 10), Ui.dp(activity, 10), Ui.dp(activity, 10), Ui.dp(activity, 10));
        cancel.setOnClickListener(v -> dismissAndCleanup());
        head.addView(cancel);

        saveBtn = Ui.text(activity, "Save PDF", 14.5f, Ui.color(activity, R.color.on_accent), Ui.bold());
        saveBtn.setGravity(Gravity.CENTER);
        saveBtn.setPadding(Ui.dp(activity, 16), 0, Ui.dp(activity, 16), 0);
        saveBtn.setBackground(Ui.ripple(activity, Ui.round(Ui.color(activity, R.color.accent), Ui.dp(activity, 20)), Ui.dp(activity, 20)));
        saveBtn.setOnClickListener(v -> compileAndSave());
        head.addView(saveBtn, new LinearLayout.LayoutParams(-2, Ui.dp(activity, 40)));
        root.addView(head);

        // Editable notebook name
        titleInput = new EditText(activity);
        String defName = "Scan " + new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(new Date());
        titleInput.setText(defName);
        titleInput.setHint("Document title");
        titleInput.setSingleLine(true);
        titleInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        titleInput.setTextSize(15f);
        titleInput.setTextColor(Ui.color(activity, R.color.on_surface));
        titleInput.setPadding(Ui.dp(activity, 14), 0, Ui.dp(activity, 14), 0);
        titleInput.setBackground(Ui.round(Ui.color(activity, R.color.surface_variant), Ui.dp(activity, 12)));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, Ui.dp(activity, 44));
        tlp.topMargin = Ui.dp(activity, 12);
        root.addView(titleInput, tlp);

        // Preview frame
        FrameLayout previewBox = new FrameLayout(activity);
        GradientDrawable pbg = Ui.round(Ui.color(activity, R.color.surface_variant), Ui.dp(activity, 14));
        pbg.setStroke(Ui.hairline(activity), Ui.color(activity, R.color.outline));
        previewBox.setBackground(pbg);
        previewBox.setClipToOutline(true);

        previewImg = new ImageView(activity);
        previewImg.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewImg.setContentDescription("Scanned page preview");
        previewImg.setPadding(Ui.dp(activity, 8), Ui.dp(activity, 8), Ui.dp(activity, 8), Ui.dp(activity, 8));
        previewBox.addView(previewImg, new FrameLayout.LayoutParams(-1, -1));

        pageBadge = Ui.text(activity, "Page 1 of 1", 12f, Color.WHITE, Ui.medium());
        pageBadge.setPadding(Ui.dp(activity, 10), Ui.dp(activity, 5), Ui.dp(activity, 10), Ui.dp(activity, 5));
        pageBadge.setBackground(Ui.round(0xB3181A1F, Ui.dp(activity, 12)));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        blp.topMargin = Ui.dp(activity, 10);
        blp.rightMargin = Ui.dp(activity, 10);
        previewBox.addView(pageBadge, blp);

        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, 0, 1f);
        plp.topMargin = Ui.dp(activity, 12);
        root.addView(previewBox, plp);

        // Filter segmented control host
        modeSegHost = new LinearLayout(activity);
        modeSegHost.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        mlp.topMargin = Ui.dp(activity, 12);
        root.addView(modeSegHost, mlp);

        // Action row: Rotate, Remove, Add page
        LinearLayout actions = new LinearLayout(activity);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = Ui.dp(activity, 10);

        TextView rotateBtn = Ui.button(activity, "Rotate", R.drawable.ic_rotate_cw, false);
        rotateBtn.setOnClickListener(v -> {
            if (pages.isEmpty()) return;
            Page p = pages.get(selected);
            p.rotation = (p.rotation + 90) % 360;
            refreshUi();
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, Ui.dp(activity, 44), 1f);
        rlp.rightMargin = Ui.dp(activity, 8);
        actions.addView(rotateBtn, rlp);

        TextView delBtn = Ui.button(activity, "Remove", R.drawable.ic_trash, false);
        delBtn.setOnClickListener(v -> {
            if (pages.isEmpty()) return;
            Page removed = pages.remove(selected);
            try { removed.file.delete(); } catch (Exception ignored) { }
            if (pages.isEmpty()) {
                dismissAndCleanup();
                return;
            }
            if (selected >= pages.size()) selected = pages.size() - 1;
            refreshUi();
        });
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, Ui.dp(activity, 44), 1f);
        dlp.rightMargin = Ui.dp(activity, 8);
        actions.addView(delBtn, dlp);

        TextView addBtn = Ui.button(activity, "Add page", R.drawable.ic_plus, false);
        addBtn.setOnClickListener(v -> {
            if (!saving) callbacks.onAddAnotherPage();
        });
        actions.addView(addBtn, new LinearLayout.LayoutParams(0, Ui.dp(activity, 44), 1f));
        root.addView(actions, alp);

        // Thumbnail strip
        HorizontalScrollView hsv = new HorizontalScrollView(activity);
        hsv.setHorizontalScrollBarEnabled(false);
        thumbStrip = new LinearLayout(activity);
        thumbStrip.setOrientation(LinearLayout.HORIZONTAL);
        thumbStrip.setGravity(Gravity.CENTER_VERTICAL);
        hsv.addView(thumbStrip);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, Ui.dp(activity, 74));
        hlp.topMargin = Ui.dp(activity, 10);
        root.addView(hsv, hlp);

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setLayout(Math.min(Ui.dp(activity, 560), sw - Ui.dp(activity, 24)),
                    Math.min(Ui.dp(activity, 760), sh - Ui.dp(activity, 48)));
        }
    }

    private void refreshUi() {
        if (pages.isEmpty() || previewImg == null) return;
        Page cur = pages.get(selected);
        pageBadge.setText("Page " + (selected + 1) + " of " + pages.size());

        // Rebuild segmented filter control to reflect selected page's mode
        modeSegHost.removeAllViews();
        modeSegHost.addView(Ui.segmented(activity, new String[]{"Document", "Color", "Original"}, cur.mode, m -> {
            cur.mode = m;
            loadPreview(cur);
            refreshThumbnails();
        }));

        loadPreview(cur);
        refreshThumbnails();
    }

    private void loadPreview(Page page) {
        final File f = page.file;
        final int rot = page.rotation;
        final int mode = page.mode;
        bg.execute(() -> {
            Bitmap bm = renderPageBitmap(f, rot, mode, 1280);
            ui.post(() -> {
                if (previewImg != null && !pages.isEmpty() && pages.get(selected) == page) {
                    previewImg.setImageBitmap(bm);
                } else if (bm != null) {
                    bm.recycle();
                }
            });
        });
    }

    private void refreshThumbnails() {
        if (thumbStrip == null) return;
        thumbStrip.removeAllViews();
        for (int i = 0; i < pages.size(); i++) {
            final int idx = i;
            Page p = pages.get(i);
            FrameLayout item = new FrameLayout(activity);
            GradientDrawable bgd = Ui.round(Ui.color(activity, R.color.surface_variant), Ui.dp(activity, 10));
            bgd.setStroke(Ui.dp(activity, i == selected ? 2 : 1),
                    Ui.color(activity, i == selected ? R.color.accent : R.color.outline));
            item.setBackground(bgd);
            item.setClipToOutline(true);
            item.setContentDescription("Scanned page " + (i + 1));

            ImageView iv = new ImageView(activity);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            item.addView(iv, new FrameLayout.LayoutParams(-1, -1));

            TextView num = Ui.text(activity, String.valueOf(i + 1), 10.5f, Color.WHITE, Ui.bold());
            num.setPadding(Ui.dp(activity, 5), Ui.dp(activity, 2), Ui.dp(activity, 5), Ui.dp(activity, 2));
            num.setBackground(Ui.round(0xB3181A1F, Ui.dp(activity, 6)));
            FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
            nlp.rightMargin = Ui.dp(activity, 4);
            nlp.bottomMargin = Ui.dp(activity, 4);
            item.addView(num, nlp);

            item.setOnClickListener(v -> {
                selected = idx;
                refreshUi();
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(activity, 52), Ui.dp(activity, 68));
            lp.rightMargin = Ui.dp(activity, 8);
            thumbStrip.addView(item, lp);

            final File f = p.file;
            final int rot = p.rotation;
            final int mode = p.mode;
            bg.execute(() -> {
                Bitmap thumb = renderPageBitmap(f, rot, mode, 180);
                ui.post(() -> iv.setImageBitmap(thumb));
            });
        }
    }

    private void compileAndSave() {
        if (saving || pages.isEmpty()) return;
        saving = true;
        saveBtn.setEnabled(false);
        saveBtn.setAlpha(0.6f);
        saveBtn.setText("Saving…");

        String rawTitle = titleInput != null ? titleInput.getText().toString().trim() : "";
        String title = rawTitle.isEmpty()
                ? "Scan " + new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(new Date())
                : rawTitle;
        List<Page> snapshot = new ArrayList<>(pages);

        bg.execute(() -> {
            File pdfFile = new File(scanDir(activity), "compiled_" + System.currentTimeMillis() + ".pdf");
            try {
                writePdf(snapshot, pdfFile);
                ui.post(() -> {
                    saving = false;
                    if (dialog != null && dialog.isShowing()) dialog.dismiss();
                    dialog = null;
                    cleanupFiles();
                    callbacks.onSaveScannedPdf(title, pdfFile);
                });
            } catch (Exception e) {
                try { pdfFile.delete(); } catch (Exception ignored) { }
                ui.post(() -> {
                    saving = false;
                    if (saveBtn != null) {
                        saveBtn.setEnabled(true);
                        saveBtn.setAlpha(1f);
                        saveBtn.setText("Save PDF");
                    }
                    Toast.makeText(activity, "Could not save scanned document", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private static void writePdf(List<Page> scanPages, File outPdf) throws IOException {
        PdfDocument doc = new PdfDocument();
        try {
            for (int i = 0; i < scanPages.size(); i++) {
                Page sp = scanPages.get(i);
                Bitmap bm = renderPageBitmap(sp.file, sp.rotation, sp.mode, 2000);
                if (bm == null) continue;
                try {
                    // Fit to standard 595pt width (A4 width) preserving scanned page aspect ratio
                    int pw = 595;
                    int ph = Math.max(200, Math.round(595f * bm.getHeight() / (float) bm.getWidth()));
                    PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(pw, ph, i + 1).create();
                    PdfDocument.Page pg = doc.startPage(info);
                    Canvas cv = pg.getCanvas();
                    cv.drawColor(Color.WHITE);
                    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                    cv.drawBitmap(bm, null, new android.graphics.RectF(0, 0, pw, ph), p);
                    doc.finishPage(pg);
                } finally {
                    bm.recycle();
                }
            }
            if (doc.getPages().isEmpty()) throw new IOException("No valid pages to save");
            try (FileOutputStream fos = new FileOutputStream(outPdf)) {
                doc.writeTo(fos);
            }
        } finally {
            doc.close();
        }
    }

    /** Decodes captured photo with EXIF orientation, user rotation, and optional scan enhancement. */
    static Bitmap renderPageBitmap(File file, int extraRotation, int mode, int maxDim) {
        if (file == null || !file.isFile()) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int sample = 1;
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            while (longest / (sample * 2) >= maxDim) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap raw = BitmapFactory.decodeFile(file.getPath(), opts);
            if (raw == null) return null;

            int exifDeg = exifDegrees(file);
            int totalDeg = (exifDeg + extraRotation) % 360;

            float scale = Math.min(1f, maxDim / (float) Math.max(raw.getWidth(), raw.getHeight()));
            Bitmap oriented;
            if (totalDeg != 0 || scale < 0.99f) {
                Matrix m = new Matrix();
                if (scale < 0.99f) m.postScale(scale, scale);
                if (totalDeg != 0) m.postRotate(totalDeg);
                oriented = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
                if (oriented != raw) raw.recycle();
            } else {
                oriented = raw;
            }

            if (mode == MODE_ORIG) return oriented;

            Bitmap out = Bitmap.createBitmap(oriented.getWidth(), oriented.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(out);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            paint.setColorFilter(new ColorMatrixColorFilter(filterMatrix(mode)));
            cv.drawBitmap(oriented, 0, 0, paint);
            oriented.recycle();
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static ColorMatrix filterMatrix(int mode) {
        ColorMatrix cm = new ColorMatrix();
        if (mode == MODE_DOC) {
            // Desaturate and push paper background toward crisp white while keeping dark ink sharp
            cm.setSaturation(0f);
            float c = 1.65f;
            float t = (-0.5f * c + 0.5f) * 255f + 24f;
            ColorMatrix contrast = new ColorMatrix(new float[]{
                    c, 0, 0, 0, t,
                    0, c, 0, 0, t,
                    0, 0, c, 0, t,
                    0, 0, 0, 1, 0
            });
            cm.postConcat(contrast);
        } else if (mode == MODE_COLOR) {
            // Vivid color document cleanup
            cm.setSaturation(1.18f);
            float c = 1.28f;
            float t = (-0.5f * c + 0.5f) * 255f + 16f;
            ColorMatrix contrast = new ColorMatrix(new float[]{
                    c, 0, 0, 0, t,
                    0, c, 0, 0, t,
                    0, 0, c, 0, t,
                    0, 0, 0, 1, 0
            });
            cm.postConcat(contrast);
        }
        return cm;
    }

    private static int exifDegrees(File file) {
        try {
            ExifInterface exif = new ExifInterface(file.getPath());
            int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (o == ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (o == ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (Exception ignored) { }
        return 0;
    }
}

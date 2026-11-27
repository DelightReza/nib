package app.nib.notes;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/** PDF / PNG export (vector ink in PDFs), saving via Files picker, and sharing through ExportProvider. */
final class Exporter {
    private Exporter() {}

    static File exportDir(Context c) {
        File d = new File(c.getCacheDir(), "exports");
        d.mkdirs();
        return d;
    }

    static String safeName(String title) {
        String s = title.replaceAll("[\\\\/:*?\"<>|\\n\\r]+", " ").trim();
        return s.isEmpty() ? "Notebook" : s;
    }

    /** All pages (or one, if page >= 0) as a PDF; A4 width in points. */
    static File pdf(Context c, Notebook nb, File dir, int page) throws IOException {
        return pdf(c, nb, dir, page, null);
    }

    /** All pages (or one, if page >= 0) as a PDF with optional progress callbacks. */
    static File pdf(Context c, Notebook nb, File dir, int page, ProgressPopup.Listener cb) throws IOException {
        File out = new File(exportDir(c), safeName(nb.title) + (page >= 0 ? " p" + (page + 1) : "") + ".pdf");
        PdfDocument doc = new PdfDocument();
        Render.Pdf src = Render.Pdf.open(dir);
        int totalPages = page >= 0 ? 1 : Math.max(1, nb.pages.size());
        int rendered = 0;
        try {
            float k = 595f / Notebook.PW;
            for (int i = 0; i < nb.pages.size(); i++) {
                if (page >= 0 && i != page) continue;
                rendered++;
                if (cb != null) {
                    cb.onProgress(rendered, totalPages,
                            totalPages == 1 ? "Rendering page…" : "Rendering page " + rendered + " of " + totalPages + "…");
                }
                Notebook.Page p = nb.pages.get(i);
                PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(595, Math.round(p.h * k), i + 1).create();
                PdfDocument.Page pg = doc.startPage(info);
                Canvas cv = pg.getCanvas();
                cv.scale(k, k);
                Notebook.Page prev = i > 0 ? nb.pages.get(i - 1) : null, next = i + 1 < nb.pages.size() ? nb.pages.get(i + 1) : null;
                Render.page(cv, nb, dir, p, prev, next, src, 1600, true); // with ink running over from the neighbours
                doc.finishPage(pg);
                if (prev != null) prev.unload(); // a long notebook is exported a few pages at a time, not all in memory
                if (page >= 0) { p.unload(); if (next != null) next.unload(); }
            }
            if (cb != null) cb.onProgress(totalPages, totalPages, "Writing PDF file…");
            try (FileOutputStream o = new FileOutputStream(out)) {
                doc.writeTo(o);
            }
        } finally {
            doc.close();
            if (src != null) src.close();
        }
        return out;
    }

    static File png(Context c, Notebook nb, File dir, int page) throws IOException {
        File out = new File(exportDir(c), safeName(nb.title) + " p" + (page + 1) + ".png");
        Render.Pdf src = nb.pages.get(page).pdf >= 0 ? Render.Pdf.open(dir) : null;
        try {
            Bitmap b = Render.pageBitmap(nb, dir, nb.pages.get(page), page > 0 ? nb.pages.get(page - 1) : null,
                    page + 1 < nb.pages.size() ? nb.pages.get(page + 1) : null, src, 2000);
            try (FileOutputStream o = new FileOutputStream(out)) {
                b.compress(Bitmap.CompressFormat.PNG, 100, o);
            }
            b.recycle();
        } finally {
            if (src != null) src.close();
        }
        return out;
    }

    /** Opens the system Files / document picker so the user saves the exported file first. */
    static boolean launchSavePicker(Activity a, File f, String mime, int reqCode) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime)
                .putExtra(Intent.EXTRA_TITLE, f.getName());
        try {
            a.startActivityForResult(i, reqCode);
            return true;
        } catch (ActivityNotFoundException e) {
            share(a, f, mime);
            return false;
        }
    }

    /** Opens the system folder picker to save multiple exported files into one folder. */
    static boolean launchFolderPicker(Activity a, List<File> files, String mime, int reqCode) {
        try {
            a.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), reqCode);
            return true;
        } catch (ActivityNotFoundException e) {
            shareMany(a, files, mime);
            return false;
        }
    }

    static void saveToUri(Context c, File src, Uri dst) throws IOException {
        OutputStream os = c.getContentResolver().openOutputStream(dst);
        if (os == null) throw new IOException("Cannot open destination");
        Io.copy(new FileInputStream(src), os);
    }

    static int saveManyToTree(Context c, List<File> files, String mime, Uri treeUri) throws IOException {
        return saveManyToTree(c, files, mime, treeUri, null);
    }

    static int saveManyToTree(Context c, List<File> files, String mime, Uri treeUri, ProgressPopup.Listener cb) throws IOException {
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri));
        int saved = 0;
        int total = files.size();
        for (File src : files) {
            if (cb != null) {
                cb.onProgress(saved, total, "Saving " + src.getName() + " (" + (saved + 1) + " of " + total + ")…");
            }
            Uri created = DocumentsContract.createDocument(c.getContentResolver(), parent, mime, src.getName());
            if (created == null) throw new IOException("Could not create " + src.getName());
            saveToUri(c, src, created);
            saved++;
            if (cb != null) {
                cb.onProgress(saved, total, "Saved " + saved + " of " + total + "…");
            }
        }
        return saved;
    }

    /** Several files at once (multi-select share). */
    static void shareMany(Activity a, java.util.List<File> files, String mime) {
        if (files.size() == 1) { share(a, files.get(0), mime); return; }
        java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
        ClipData clip = null;
        for (File f : files) {
            Uri u = Uri.parse("content://" + ExportProvider.AUTHORITY + "/" + Uri.encode(f.getName()));
            uris.add(u);
            if (clip == null) clip = ClipData.newRawUri(f.getName(), u); else clip.addItem(new ClipData.Item(u));
        }
        Intent send = new Intent(Intent.ACTION_SEND_MULTIPLE).setType(mime).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        send.setClipData(clip);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        a.startActivity(Intent.createChooser(send, "Share " + files.size() + " notebooks"));
    }

    static void share(Activity a, File f, String mime) {
        Uri u = Uri.parse("content://" + ExportProvider.AUTHORITY + "/" + Uri.encode(f.getName()));
        Intent send = new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, u)
                .putExtra(Intent.EXTRA_TITLE, f.getName());
        send.setClipData(ClipData.newRawUri(f.getName(), u));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        a.startActivity(Intent.createChooser(send, "Share " + f.getName()));
    }
}

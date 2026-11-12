package app.nib.notes;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** PDF / PNG export (vector ink in PDFs) and sharing through ExportProvider. */
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
        File out = new File(exportDir(c), safeName(nb.title) + (page >= 0 ? " p" + (page + 1) : "") + ".pdf");
        PdfDocument doc = new PdfDocument();
        Render.Pdf src = Render.Pdf.open(dir);
        try {
            float k = 595f / Notebook.PW;
            for (int i = 0; i < nb.pages.size(); i++) {
                if (page >= 0 && i != page) continue;
                Notebook.Page p = nb.pages.get(i);
                PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(595, Math.round(p.h * k), i + 1).create();
                PdfDocument.Page pg = doc.startPage(info);
                Canvas cv = pg.getCanvas();
                cv.scale(k, k);
                Render.page(cv, nb, dir, p, src, 1600, true);
                doc.finishPage(pg);
            }
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
            Bitmap b = Render.pageBitmap(nb, dir, nb.pages.get(page), src, 2000);
            try (FileOutputStream o = new FileOutputStream(out)) {
                b.compress(Bitmap.CompressFormat.PNG, 100, o);
            }
            b.recycle();
        } finally {
            if (src != null) src.close();
        }
        return out;
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

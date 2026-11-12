package app.nib.notes;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** CPU rendering of pages (thumbnails, page overview, PNG/PDF export). Mirrors the GL look. */
final class Render {
    private Render() {}

    // paper palette: paper, line, grid, dot (ARGB)
    static final int[][] PAPER = {
            {0xffffffff, 0xffc2d1e8, 0xffd9e0ec, 0xffa8b2c4},
            {0xfffffbef, 0xffd9cdb4, 0xffe6dccb, 0xffbfb39c},
            {0xff1f2125, 0xff3a404b, 0xff30353e, 0xff545b68}};

    static boolean dark(Notebook nb) { return nb.paper == Notebook.PAPER_DARK; }

    /** Very dark ink is shown light on dark paper (and the GL shader does the same). */
    static int inkColor(int c, boolean darkPaper) {
        if (!darkPaper) return c;
        float l = (0.2126f * ((c >> 16) & 255) + 0.7152f * ((c >> 8) & 255) + 0.0722f * (c & 255)) / 255f;
        return l < 0.22f ? 0xffe8eaed : c;
    }

    /** PDF source of a notebook; one page rendered at a time (PdfRenderer isn't thread-safe). */
    static final class Pdf implements AutoCloseable {
        private final ParcelFileDescriptor fd;
        final PdfRenderer r;

        Pdf(File f) throws IOException {
            fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
            r = new PdfRenderer(fd);
        }

        static Pdf open(File dir) {
            File f = new File(dir, "source.pdf");
            if (!f.exists()) return null;
            try {
                return new Pdf(f);
            } catch (IOException e) {
                return null;
            }
        }

        synchronized Bitmap render(int index, int widthPx) {
            if (index < 0 || index >= r.getPageCount()) return null;
            try (PdfRenderer.Page p = r.openPage(index)) {
                int h = Math.max(1, Math.round(widthPx * p.getHeight() / (float) p.getWidth()));
                Bitmap b = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888);
                b.eraseColor(0xffffffff);
                p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return b;
            }
        }

        @Override public synchronized void close() {
            r.close();
            try { fd.close(); } catch (IOException ignored) {}
        }
    }

    static void paper(Canvas c, Notebook nb, Notebook.Page p) {
        int[] pal = PAPER[Math.max(0, Math.min(2, nb.paper))];
        Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
        f.setColor(pal[0]);
        c.drawRect(0, 0, Notebook.PW, p.h, f);
        if (p.pdf >= 0) return;
        float PW = Notebook.PW, h = p.h, k = p.spacing > 0 ? p.spacing : 1f;
        float bold = Math.max(1f, p.strength);
        int alpha = Math.round(255 * Math.max(0.05f, Math.min(1f, p.strength)));
        // line colours: the paper's own, or the chosen colour for every line
        int c1 = look(p.lineColor != 0 ? p.lineColor : pal[1], alpha), c2 = look(p.lineColor != 0 ? p.lineColor : pal[2], alpha),
                c3 = look(p.lineColor != 0 ? p.lineColor : pal[3], alpha);
        Paint l = new Paint(Paint.ANTI_ALIAS_FLAG);
        l.setStrokeWidth(1.4f * bold);
        switch (p.bg) {
            case Notebook.BG_LINED:
            case Notebook.BG_NARROW:
            case Notebook.BG_CORNELL: {
                float sp = (p.bg == Notebook.BG_NARROW ? 30 : 40) * k;
                l.setColor(c1);
                for (float y = 120; y < h - 40; y += sp) c.drawLine(0, y, PW, y, l);
                if (p.bg == Notebook.BG_CORNELL) {
                    l.setStrokeWidth(2f * bold);
                    c.drawLine(250, 0, 250, h - 260, l);
                    c.drawLine(0, h - 260, PW, h - 260, l);
                }
                break;
            }
            case Notebook.BG_GRID: {
                float sp = 25 * k;
                l.setColor(c2);
                for (float x = sp; x < PW; x += sp) c.drawLine(x, 0, x, h, l);
                for (float y = sp; y < h; y += sp) c.drawLine(0, y, PW, y, l);
                break;
            }
            case Notebook.BG_DOTS: {
                float sp = 25 * k;
                l.setColor(c3);
                for (float x = sp; x < PW; x += sp) for (float y = sp; y < h; y += sp) c.drawCircle(x, y, 1.6f * bold, l);
                break;
            }
            case Notebook.BG_MUSIC: {
                float st = 10 * k;
                l.setColor(c1);
                for (float top = 120; top + 4 * st < h - 60; top += 110 * k)
                    for (int i = 0; i < 5; i++) c.drawLine(60, top + i * st, PW - 60, top + i * st, l);
                break;
            }
            case Notebook.BG_ENGINEERING: {
                float sp = 23.81f * k;
                for (int i = 1; i * sp < PW; i++) {
                    boolean major = i % 5 == 0;
                    l.setColor(major ? c1 : c2);
                    l.setStrokeWidth((major ? 2f : 1.2f) * bold);
                    c.drawLine(i * sp, 0, i * sp, h, l);
                }
                for (int i = 1; i * sp < h; i++) {
                    boolean major = i % 5 == 0;
                    l.setColor(major ? c1 : c2);
                    l.setStrokeWidth((major ? 2f : 1.2f) * bold);
                    c.drawLine(0, i * sp, PW, i * sp, l);
                }
                break;
            }
            case Notebook.BG_ISO: {
                l.setColor(c2);
                l.setStrokeWidth(1.2f * bold);
                float hh = 26f * k, t = (float) Math.tan(Math.toRadians(30));
                for (float x = 0; x < PW; x += hh) c.drawLine(x, 0, x, h, l);
                // lines at ±30°: y = ±t·x + n·(hh / cos30), the same lattice as the live page
                float step = hh / (float) Math.cos(Math.toRadians(30));
                for (float n = -(float) Math.ceil(PW * t / step) * step; n < h + PW * t; n += step) {
                    c.drawLine(0, n, PW, n + PW * t, l);
                    c.drawLine(0, n, PW, n - PW * t, l);
                }
                break;
            }
            case Notebook.BG_SITE: {
                l.setColor(c1);
                l.setStrokeWidth(2f * bold);
                c.drawRect(40, 40, 960, 300, strokeOf(l));
                c.drawRect(40, h - 260, 960, h - 40, strokeOf(l));
                l.setStrokeWidth(1.4f * bold);
                c.drawLine(40, 100, 960, 100, l);
                c.drawLine(40, 200, 960, 200, l);
                c.drawLine(347, 100, 347, 300, l);
                c.drawLine(653, 100, 653, 300, l);
                c.drawLine(653, h - 260, 653, h - 40, l);
                for (float y = 380 + 40 * k; y < h - 300; y += 40 * k) c.drawLine(40, y, 960, y, l);
                siteLabels(c, nb.paper, h, true);
                siteLabels(c, nb.paper, h, false);
                break;
            }
        }
        if (!nb.canvas && p.margins != 0) {
            Paint m = new Paint(Paint.ANTI_ALIAS_FLAG);
            m.setStrokeWidth(1.6f * bold);
            if ((p.margins & Notebook.MARGIN_LEFT) != 0) { // red rule
                m.setColor(look(nb.paper == Notebook.PAPER_DARK ? 0xff9e545c : 0xffe57878, alpha));
                c.drawLine(125, 0, 125, h, m);
            }
            m.setColor(c1);
            m.setStrokeWidth(2f * bold);
            if ((p.margins & Notebook.MARGIN_HEADER) != 0) c.drawLine(0, 90, PW, 90, m);
            if ((p.margins & Notebook.MARGIN_BORDER) != 0) c.drawRect(30, 30, PW - 30, h - 30, strokeOf(m));
        }
    }

    private static int look(int rgb, int alpha) { return (rgb & 0x00ffffff) | (alpha << 24); }

    private static Paint strokeOf(Paint l) {
        Paint s = new Paint(l);
        s.setStyle(Paint.Style.STROKE);
        return s;
    }

    /** Printed labels of the site-inspection template: the header band (top) or the sign-off box (bottom). */
    static void siteLabels(Canvas c, int paper, float h, boolean top) {
        int strong = paper == Notebook.PAPER_DARK ? 0xffc9ced8 : paper == Notebook.PAPER_CREAM ? 0xff4d4434 : 0xff3a4250;
        int soft = paper == Notebook.PAPER_DARK ? 0xff8f97a6 : paper == Notebook.PAPER_CREAM ? 0xff8a7c63 : 0xff7a8396;
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTypeface(Typeface.create(Typeface.DEFAULT, 600, false));
        if (top) {
            t.setColor(strong);
            t.setTextSize(30);
            c.drawText("Site inspection", 58, 82, t);
            t.setTypeface(Typeface.DEFAULT);
            t.setTextSize(15);
            t.setColor(soft);
            c.drawText("Sheet ____ of ____", 800, 80, t);
            String[] labels = {"Project", "Location / chainage", "Date", "Inspected by", "Weather", "Reference no."};
            float[] xs = {40, 347, 653};
            for (int i = 0; i < 6; i++) c.drawText(labels[i], xs[i % 3] + 14, (i < 3 ? 100 : 200) + 26, t);
            t.setTypeface(Typeface.create(Typeface.DEFAULT, 600, false));
            t.setTextSize(18);
            t.setColor(strong);
            c.drawText("Observations", 42, 366, t);
        } else {
            t.setTextSize(15);
            t.setColor(soft);
            c.drawText("Action required", 54, h - 260 + 26, t);
            c.drawText("Signature", 667, h - 260 + 26, t);
        }
    }

    /** Label band as a transparent bitmap for the GPU page (top: y 0–380, bottom: the sign-off box). */
    static Bitmap siteBand(int paper, float h, boolean top) {
        float k = 2f, bandH = top ? 380 : 220;
        Bitmap b = Bitmap.createBitmap(Math.round(Notebook.PW * k), Math.round(bandH * k), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(k, k);
        if (!top) c.translate(0, -(h - 260));
        siteLabels(c, paper, h, top);
        return b;
    }

    /** Draws a page in page units onto c. forPdf avoids blend modes PdfDocument can't express. */
    static void page(Canvas c, Notebook nb, File dir, Notebook.Page p, Pdf pdf, int pdfPx, boolean forPdf) {
        int saved = c.save();
        c.clipRect(0, 0, Notebook.PW, p.h); // ink past the page edge isn't shown (same as the editor)
        try { pageInner(c, nb, dir, p, pdf, pdfPx, forPdf); } finally { c.restoreToCount(saved); }
    }

    private static void pageInner(Canvas c, Notebook nb, File dir, Notebook.Page p, Pdf pdf, int pdfPx, boolean forPdf) {
        paper(c, nb, p);
        if (nb.canvas) {
            // infinite canvas: fit whatever was drawn (anywhere, even at negative coordinates) onto the page
            RectF b = canvasBounds(p);
            if (!b.isEmpty()) {
                float m = 40f, s = Math.min(1f, Math.min((Notebook.PW - 2 * m) / b.width(), (p.h - 2 * m) / b.height()));
                c.translate(Notebook.PW / 2f, p.h / 2f);
                c.scale(s, s);
                c.translate(-b.centerX(), -b.centerY());
            }
        }
        if (p.pdf >= 0 && pdf != null) {
            Bitmap b = pdf.render(p.pdf, pdfPx);
            if (b != null) {
                c.drawBitmap(b, null, new RectF(0, 0, Notebook.PW, p.h), new Paint(Paint.FILTER_BITMAP_FLAG));
                b.recycle();
            }
        }
        for (Notebook.Item it : p.items) item(c, dir, it, dark(nb));
        boolean dk = dark(nb);
        SegBuf tmp = new SegBuf();
        Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        ink.setStrokeCap(Paint.Cap.ROUND);
        Paint hl = new Paint(ink);
        if (!forPdf) hl.setXfermode(new PorterDuffXfermode(dk ? PorterDuff.Mode.LIGHTEN : PorterDuff.Mode.DARKEN));
        for (int pass = 0; pass < 2; pass++) {
            for (Stroke s : p.strokes) {
                boolean isHl = s.tool == Stroke.HL;
                if ((pass == 0) != isHl) continue;
                Paint pt = isHl ? hl : ink;
                int col = isHl ? s.color : inkColor(s.color, dk);
                if (isHl && forPdf) col = (col & 0x00ffffff) | 0x70000000;
                else if (isHl && dk) col = scale(col, 0.6f);
                pt.setColor(col);
                tmp.clear();
                s.emitAll(0, tmp);
                for (int i = 0; i < tmp.count; i++) {
                    pt.setStrokeWidth(tmp.get(i, 4) + tmp.get(i, 5));
                    c.drawLine(tmp.get(i, 0), tmp.get(i, 1), tmp.get(i, 2), tmp.get(i, 3), pt);
                }
            }
        }
    }

    private static int scale(int c, float k) {
        return 0xff000000 | (Math.round(((c >> 16) & 255) * k) << 16) | (Math.round(((c >> 8) & 255) * k) << 8) | Math.round((c & 255) * k);
    }

    static void item(Canvas c, File dir, Notebook.Item it, boolean darkPaper) {
        if (it.type == Notebook.Item.IMAGE) {
            Bitmap b = image(dir, it.text, 1600);
            if (b != null) {
                c.drawBitmap(b, null, new RectF(it.x, it.y, it.x + it.w, it.y + it.h), new Paint(Paint.FILTER_BITMAP_FLAG));
                b.recycle();
            }
        } else {
            StaticLayout sl = textLayout(it.text, it.size, inkColor(it.color, darkPaper), it.w);
            c.save();
            c.translate(it.x, it.y);
            sl.draw(c);
            c.restore();
        }
    }

    static StaticLayout textLayout(String text, float size, int color, float width) {
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setTextSize(size);
        tp.setColor(color);
        return StaticLayout.Builder.obtain(text, 0, text.length(), tp, Math.max(1, (int) Math.ceil(width)))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build();
    }

    static Bitmap image(File dir, String name, int maxPx) {
        File f = new File(new File(dir, "images"), name);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) return null;
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / sample > maxPx) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /** A page rendered to a bitmap widthPx wide. */
    /** Bounding box of everything on a page (stroke points padded by their width, plus items). */
    static RectF canvasBounds(Notebook.Page p) {
        RectF r = new RectF();
        for (Stroke st : p.strokes) {
            float pad = st.width * 2;
            for (int i = 0; i < st.n; i++) {
                float x = st.p[4 * i], y = st.p[4 * i + 1];
                if (r.isEmpty() && r.left == 0 && r.right == 0) r.set(x - pad, y - pad, x + pad, y + pad);
                else r.union(x - pad, y - pad, x + pad, y + pad);
            }
        }
        for (Notebook.Item it : p.items) {
            if (r.isEmpty() && r.left == 0 && r.right == 0) r.set(it.x, it.y, it.x + it.w, it.y + it.h);
            else r.union(it.x, it.y, it.x + it.w, it.y + it.h);
        }
        return r;
    }

    static Bitmap pageBitmap(Notebook nb, File dir, Notebook.Page p, Pdf pdf, int widthPx) {
        float s = widthPx / Notebook.PW;
        Bitmap bm = Bitmap.createBitmap(widthPx, Math.max(1, Math.round(p.h * s)), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        c.scale(s, s);
        page(c, nb, dir, p, pdf, widthPx, false);
        return bm;
    }

    static void writeThumb(Notebook nb, File dir, File out) {
        Pdf pdf = nb.pages.get(0).pdf >= 0 ? Pdf.open(dir) : null;
        try {
            Bitmap bm = pageBitmap(nb, dir, nb.pages.get(0), pdf, 360);
            File tmp = new File(out.getPath() + ".tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                bm.compress(Bitmap.CompressFormat.PNG, 100, o);
            } catch (IOException ignored) {
            }
            tmp.renameTo(out);
            bm.recycle();
        } finally {
            if (pdf != null) pdf.close();
        }
    }
}

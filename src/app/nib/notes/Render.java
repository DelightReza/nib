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
        return l < 0.22f ? ((c & 0xff000000) | 0x00e8eaed) : c;
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
            case Notebook.BG_GRAPH: {
                float sp = 23.81f * k;
                for (int i = 1; i * sp < PW; i++) {
                    boolean major = i % 2 == 0;
                    l.setColor(major ? c1 : c2);
                    l.setStrokeWidth((major ? 1.6f : 1.1f) * bold);
                    c.drawLine(i * sp, 0, i * sp, h, l);
                }
                for (int i = 1; i * sp < h; i++) {
                    boolean major = i % 2 == 0;
                    l.setColor(major ? c1 : c2);
                    l.setStrokeWidth((major ? 1.6f : 1.1f) * bold);
                    c.drawLine(0, i * sp, PW, i * sp, l);
                }
                break;
            }
            case Notebook.BG_FINE_DOTS: {
                float sp = 11.905f * k;
                l.setColor(c3);
                for (float x = sp; x < PW; x += sp) for (float y = sp; y < h; y += sp) c.drawCircle(x, y, 1.1f * bold, l);
                break;
            }
            case Notebook.BG_HEX: { // pointy-top hexagons, flat-to-flat F; each hex draws its three right-hand edges
                float F = 36f * k, sd = F / (float) Math.sqrt(3);
                l.setColor(c2);
                l.setStrokeWidth(1.1f * bold);
                float[] ang = {-90, -30, 30, 90};
                for (int row = 0; row * 0.8660254f * F < h + F; row++) {
                    float cy = row * 0.8660254f * F, off = (row & 1) == 1 ? F / 2 : 0;
                    for (float cx = off - F; cx < PW + F; cx += F)
                        for (int e = 0; e < 3; e++) {
                            double a0 = Math.toRadians(ang[e]), a1 = Math.toRadians(ang[e + 1]);
                            c.drawLine(cx + sd * (float) Math.cos(a0), cy + sd * (float) Math.sin(a0),
                                    cx + sd * (float) Math.cos(a1), cy + sd * (float) Math.sin(a1), l);
                        }
                }
                break;
            }
            case Notebook.BG_DAILY: {
                l.setColor(c1);
                l.setStrokeWidth(1.4f * bold);
                Paint s = strokeOf(l);
                c.drawLine(100, 84, 520, 84, l);
                for (int i = 0; i < 7; i++) c.drawCircle(620 + i * 52, 72, 17, s);
                for (int i = 0; i < 3; i++) {
                    float yy = 195 + i * 45;
                    c.drawLine(76, yy, 956, yy, l);
                    c.drawRoundRect(44, yy - 22, 64, yy - 2, 2, 2, s);
                }
                float y0 = 370, y1 = h - 300, rh = (y1 - y0) / 17f;
                Paint dash = new Paint(l);
                dash.setColor(c2);
                dash.setPathEffect(new android.graphics.DashPathEffect(new float[]{7, 7}, 40 % 14));
                for (int j = 0; j <= 17; j++) {
                    c.drawLine(40, y0 + j * rh, 960, y0 + j * rh, l);
                    if (j < 17) c.drawLine(40, y0 + j * rh + rh / 2, 960, y0 + j * rh + rh / 2, dash);
                }
                c.drawLine(120, y0, 120, y1, l);
                for (float y = h - 215; y < h - 30; y += 40) c.drawLine(40, y, 960, y, l);
                plannerLabels(c, p.bg, nb.paper, h);
                break;
            }
            case Notebook.BG_WEEKLY: {
                l.setColor(c1);
                l.setStrokeWidth(1.4f * bold);
                c.drawLine(130, 84, 560, 84, l);
                float y0 = 120, bh = (h - 40 - y0) / 4f;
                Paint outer = strokeOf(l);
                outer.setStrokeWidth(2f * bold);
                c.drawRect(40, y0, 960, h - 40, outer);
                c.drawLine(500, y0, 500, h - 40, l);
                for (int j = 1; j < 4; j++) c.drawLine(40, y0 + j * bh, 960, y0 + j * bh, l);
                Paint wl = new Paint(l);
                wl.setColor(c2);
                for (int j = 0; j < 4; j++)
                    for (float iy = 60; iy < bh - 8; iy += 36) {
                        c.drawLine(52, y0 + j * bh + iy, 488, y0 + j * bh + iy, wl);
                        c.drawLine(512, y0 + j * bh + iy, 948, y0 + j * bh + iy, wl);
                    }
                plannerLabels(c, p.bg, nb.paper, h);
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

    // ---- planner labels: drawn into the page on the Canvas path; on screen, small transparent textures per band ------

    private static final String[] WEEK_BOXES = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday", "Notes"};

    /** Where a planner's printed labels are (page units), so the GPU path can draw each as a small texture; null = none. */
    static android.graphics.RectF[] labelBands(int bg, float h) {
        if (bg == Notebook.BG_DAILY) {
            float y1 = h - 300;
            return new android.graphics.RectF[]{new android.graphics.RectF(0, 40, 1000, 362), new android.graphics.RectF(0, 362, 118, y1 + 4),
                    new android.graphics.RectF(0, h - 262, 260, h - 222)};
        }
        if (bg == Notebook.BG_WEEKLY) {
            float y0 = 120, bh = (h - 40 - y0) / 4f;
            android.graphics.RectF[] r = new android.graphics.RectF[9];
            r[0] = new android.graphics.RectF(0, 56, 140, 100);
            for (int b = 0; b < 8; b++) {
                float bx = b % 2 == 0 ? 40 : 500, by = y0 + (b / 2) * bh;
                r[b + 1] = new android.graphics.RectF(bx, by, bx + 220, by + 44);
            }
            return r;
        }
        return null;
    }

    /** One label band as a transparent bitmap (2 px per unit), for the GPU path. */
    static Bitmap labelBand(int bg, int paper, float h, android.graphics.RectF r) {
        float k = 2f;
        Bitmap b = Bitmap.createBitmap(Math.max(1, Math.round(r.width() * k)), Math.max(1, Math.round(r.height() * k)), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(k, k);
        c.translate(-r.left, -r.top);
        plannerLabels(c, bg, paper, h);
        return b;
    }

    /** The printed text of the daily / weekly planner templates. */
    static void plannerLabels(Canvas c, int bg, int paper, float h) {
        int strong = paper == Notebook.PAPER_DARK ? 0xffc9ced8 : paper == Notebook.PAPER_CREAM ? 0xff4d4434 : 0xff3a4250;
        int soft = paper == Notebook.PAPER_DARK ? 0xff8f97a6 : paper == Notebook.PAPER_CREAM ? 0xff8a7c63 : 0xff7a8396;
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (bg == Notebook.BG_DAILY) {
            t.setTypeface(Ui.font(400));
            t.setTextSize(15);
            t.setColor(soft);
            c.drawText("Date", 44, 89, t);
            t.setTextAlign(Paint.Align.CENTER);
            t.setTypeface(Ui.font(600));
            t.setColor(strong);
            String[] days = {"M", "T", "W", "T", "F", "S", "S"};
            for (int i = 0; i < 7; i++) c.drawText(days[i], 620 + i * 52, 77.5f, t);
            t.setTextAlign(Paint.Align.LEFT);
            t.setTextSize(18);
            c.drawText("Top priorities", 44, 150, t);
            c.drawText("Schedule", 44, 350, t);
            c.drawText("Notes", 44, h - 232, t);
            float y0 = 370, rh = (h - 300 - y0) / 17f;
            t.setTypeface(Ui.font(400));
            t.setTextSize(13);
            t.setColor(soft);
            t.setTextAlign(Paint.Align.RIGHT);
            for (int j = 0; j < 17; j++) {
                int hr = 6 + j;
                String s = hr < 12 ? hr + " am" : hr == 12 ? "12 pm" : (hr - 12) + " pm";
                c.drawText(s, 112, y0 + j * rh + 18, t);
            }
        } else if (bg == Notebook.BG_WEEKLY) {
            t.setTypeface(Ui.font(400));
            t.setTextSize(15);
            t.setColor(soft);
            c.drawText("Week of", 44, 89, t);
            t.setTypeface(Ui.font(600));
            t.setTextSize(16);
            t.setColor(strong);
            float y0 = 120, bh = (h - 40 - y0) / 4f;
            for (int b = 0; b < 8; b++) c.drawText(WEEK_BOXES[b], (b % 2 == 0 ? 40 : 500) + 14, y0 + (b / 2) * bh + 30, t);
        }
    }

    /** Printed labels of the site-inspection template: the header band (top) or the sign-off box (bottom). */
    static void siteLabels(Canvas c, int paper, float h, boolean top) {
        int strong = paper == Notebook.PAPER_DARK ? 0xffc9ced8 : paper == Notebook.PAPER_CREAM ? 0xff4d4434 : 0xff3a4250;
        int soft = paper == Notebook.PAPER_DARK ? 0xff8f97a6 : paper == Notebook.PAPER_CREAM ? 0xff8a7c63 : 0xff7a8396;
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTypeface(Ui.font(600));
        if (top) {
            t.setColor(strong);
            t.setTextSize(30);
            c.drawText("Site inspection", 58, 82, t);
            t.setTypeface(Ui.font(400));
            t.setTextSize(15);
            t.setColor(soft);
            c.drawText("Sheet ____ of ____", 800, 80, t);
            String[] labels = {"Project", "Location / chainage", "Date", "Inspected by", "Weather", "Reference no."};
            float[] xs = {40, 347, 653};
            for (int i = 0; i < 6; i++) c.drawText(labels[i], xs[i % 3] + 14, (i < 3 ? 100 : 200) + 26, t);
            t.setTypeface(Ui.font(600));
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
        page(c, nb, dir, p, null, null, pdf, pdfPx, forPdf);
    }

    /**
     * Page p; prev / next are the pages above and below it (or null). Ink written on a neighbour that runs over onto
     * this page is drawn here too, as the editor shows it (pages sit edge to edge there).
     */
    static void page(Canvas c, Notebook nb, File dir, Notebook.Page p, Notebook.Page prev, Notebook.Page next, Pdf pdf, int pdfPx, boolean forPdf) {
        int saved = c.save();
        c.clipRect(0, 0, Notebook.PW, p.h); // ink past the page edge isn't shown (same as the editor)
        try { pageInner(c, nb, dir, p, nb.canvas ? null : prev, nb.canvas ? null : next, pdf, pdfPx, forPdf); } finally { c.restoreToCount(saved); }
    }

    private static void pageInner(Canvas c, Notebook nb, File dir, Notebook.Page p, Notebook.Page prev, Notebook.Page next, Pdf pdf, int pdfPx, boolean forPdf) {
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
        // layer by layer (bottom first); hidden layers aren't drawn, so not exported either
        boolean dk = dark(nb);
        java.util.List<Notebook.Item> ordered = nb.itemsInDrawOrder(p.items());
        // Pass 1: Behind-ink items (IMAGE, TEXT, and SHAPE items with zOrder < 0 — default behind handwriting!)
        java.util.ArrayList<Notebook.Item> behindShapes = new java.util.ArrayList<>();
        java.util.ArrayList<Notebook.Item> frontShapes = new java.util.ArrayList<>();
        for (Notebook.Item it : ordered) {
            if (!nb.layerVisible(it.layer)) continue;
            if (it.type == Notebook.Item.TAPE) continue;
            if (it.type == Notebook.Item.SHAPE) {
                if (ShapeSpec.isBehindInk(it)) behindShapes.add(it); else frontShapes.add(it);
            } else {
                item(c, dir, it, dk);
            }
        }
        behindShapes.sort((a, b) -> Integer.compare(ShapeSpec.zOrderOf(a), ShapeSpec.zOrderOf(b)));
        for (Notebook.Item it : behindShapes) ShapeSpec.draw(c, it, dk);

        SegBuf tmp = new SegBuf();
        Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        ink.setStrokeCap(Paint.Cap.ROUND);
        Paint hl = new Paint(ink);
        if (!forPdf) hl.setXfermode(new PorterDuffXfermode(dk ? PorterDuff.Mode.LIGHTEN : PorterDuff.Mode.DARKEN));
        // this page's strokes, then the parts of the neighbours' strokes that run onto it (shifted into its coordinates)
        java.util.ArrayList<Stroke> all = new java.util.ArrayList<>();
        java.util.ArrayList<Float> dys = new java.util.ArrayList<>();
        for (Stroke s : nb.inDrawOrder(p.strokes())) if (nb.layerVisible(s.layer)) { all.add(s); dys.add(0f); }
        if (prev != null) for (Stroke s : prev.strokes()) if (s.b > prev.h && nb.layerVisible(s.layer)) { all.add(s); dys.add(-prev.h); }
        if (next != null) for (Stroke s : next.strokes()) if (s.t < 0 && nb.layerVisible(s.layer)) { all.add(s); dys.add(p.h); }
        for (int pass = 0; pass < 2; pass++) {
            for (int si = 0; si < all.size(); si++) {
                Stroke s = all.get(si);
                float dy = dys.get(si);
                boolean isHl = s.hl();
                if ((pass == 0) != isHl) continue;
                Paint pt = isHl ? hl : ink;
                int rawA = (s.color >>> 24) & 0xff;
                if (rawA == 0) continue;
                float af = rawA / 255f;
                int col;
                if (isHl) {
                    if (forPdf) {
                        int pa = Math.round(af * 0.55f * 255f);
                        col = (s.color & 0x00ffffff) | (Math.max(1, Math.min(255, pa)) << 24);
                    } else if (dk) {
                        col = scale(s.color, 0.65f * af);
                    } else {
                        col = mix(0xffffffff, s.color | 0xff000000, af);
                    }
                } else {
                    col = inkColor(s.color, dk);
                }
                tmp.clear();
                s.emitAll(dy, tmp);
                if (!isHl && rawA < 255 && !forPdf) {
                    int sv = c.saveLayerAlpha(null, rawA);
                    pt.setColor(col | 0xff000000);
                    segs(c, tmp, pt);
                    c.restoreToCount(sv);
                } else {
                    pt.setColor(col);
                    segs(c, tmp, pt);
                }
            }
        }
        // Pass 2: Front-of-ink SHAPE items (explicitly brought in front of handwriting via Bring to Front / Forward)
        frontShapes.sort((a, b) -> Integer.compare(ShapeSpec.zOrderOf(a), ShapeSpec.zOrderOf(b)));
        for (Notebook.Item it : frontShapes) ShapeSpec.draw(c, it, dk);

        // Pass 3: Study tape on top
        for (Notebook.Item it : ordered)
            if (it.type == Notebook.Item.TAPE && nb.layerVisible(it.layer)) tape(c, it, it.revealed() ? TAPE_OPEN_ALPHA : 1f);
    }

    /** Draws emitted segments: round capsules, or for the chisel highlighter one filled outline (no overlaps darken). */
    static void segs(Canvas c, SegBuf b, Paint pt) {
        if (b.count > 0 && b.get(0, 5) < 0) {
            android.graphics.Path path = new android.graphics.Path();
            for (int i = 0; i < b.count; i++) {
                float ax = b.get(i, 0), ay = b.get(i, 1), bx = b.get(i, 2), by = b.get(i, 3), hx = b.get(i, 4), hy = -b.get(i, 5);
                path.addRect(ax - hx, ay - hy, ax + hx, ay + hy, android.graphics.Path.Direction.CW);
                float dx = bx - ax, dy = by - ay;
                if (dx * dx + dy * dy < 1e-6f) continue;
                path.addRect(bx - hx, by - hy, bx + hx, by + hy, android.graphics.Path.Direction.CW);
                float cx = -dy >= 0 ? hx : -hx, cy = dx >= 0 ? hy : -hy; // the corner furthest to the left of travel
                float[] q = {ax + cx, ay + cy, bx + cx, by + cy, bx - cx, by - cy, ax - cx, ay - cy};
                float area = 0;
                for (int k = 0; k < 4; k++) area += q[2 * k] * q[(2 * k + 3) % 8] - q[(2 * k + 2) % 8] * q[2 * k + 1];
                if (area < 0) q = new float[]{q[6], q[7], q[4], q[5], q[2], q[3], q[0], q[1]}; // same turning as the rects
                path.moveTo(q[0], q[1]);
                path.lineTo(q[2], q[3]);
                path.lineTo(q[4], q[5]);
                path.lineTo(q[6], q[7]);
                path.close();
            }
            Paint f = new Paint(pt);
            f.setStyle(Paint.Style.FILL);
            c.drawPath(path, f);
            return;
        }
        for (int i = 0; i < b.count; i++) {
            pt.setStrokeWidth(b.get(i, 4) + b.get(i, 5));
            c.drawLine(b.get(i, 0), b.get(i, 1), b.get(i, 2), b.get(i, 3), pt);
        }
    }

    private static int scale(int c, float k) {
        return 0xff000000 | (Math.round(((c >> 16) & 255) * k) << 16) | (Math.round(((c >> 8) & 255) * k) << 8) | Math.round((c & 255) * k);
    }

    /** How much of a peeled-back tape still shows (a tinted ghost, so you know there's tape there). */
    static final float TAPE_OPEN_ALPHA = 0.2f;

    /**
     * Washi-style tape along the item's centre line: torn zigzag ends, a faint pattern, a hairline edge.
     * alpha < 1 draws the peeled-back ghost. Plain paths only (no layers / blend modes), so PDFs get it too.
     */
    static void tape(Canvas c, Notebook.Item it, float alpha) {
        if (it.pts != null) tape(c, it.pts, it.size, it.color, it.pattern(), alpha);
    }

    /**
     * Tape looks: "flat" (a solid colour, zigzag ends: Nib ≤ 2.3) and the paper tapes "masking", "washi" and "kraft"
     * (torn ends, fibres, a soft shadow). Stored in front of the pattern as "look~pattern" ("masking~stripes"); a
     * pattern without a look is flat, so older notebooks and older Nib read each other's tape.
     */
    static final String[] TAPE_LOOKS = {"flat", "masking", "washi", "kraft"};

    static String tapeLook(String pattern) {
        int i = pattern.indexOf('~');
        return i > 0 ? pattern.substring(0, i) : "flat";
    }

    static String tapePattern(String pattern) {
        int i = pattern.indexOf('~');
        return i > 0 ? pattern.substring(i + 1) : pattern;
    }

    /** Same tape, same fibres and tears every time it's drawn: seeded by where it starts. */
    private static int tapeSeed(float[] pts) {
        return Float.floatToIntBits(pts[0]) * 31 + Float.floatToIntBits(pts[1]) * 17 + pts.length;
    }

    /** Small deterministic random numbers (0..1). */
    private static final class Rnd {
        private long st;
        Rnd(long seed) { st = seed * 0x9E3779B97F4A7C15L + 1; }
        float next() { st = st * 6364136223846793005L + 1442695040888963407L; return ((st >>> 40) & 0xffffff) / (float) 0x1000000; }
    }

    private static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xff) * (1 - t) + ((b >> 16) & 0xff) * t);
        int g = Math.round(((a >> 8) & 0xff) * (1 - t) + ((b >> 8) & 0xff) * t);
        int bl = Math.round((a & 0xff) * (1 - t) + (b & 0xff) * t);
        return 0xff000000 | (r << 16) | (g << 8) | bl;
    }

    static void tape(Canvas c, float[] pts, float thick, int color, String pattern, float alpha) {
        String look = tapeLook(pattern);
        pattern = tapePattern(pattern);
        boolean paper = !look.equals("flat");
        int seed = tapeSeed(pts);
        android.graphics.Path outline = tapeOutline(pts, thick, paper ? look : null, seed);
        if (outline == null) return;
        int a = Math.round(alpha * 255);
        if (paper && alpha >= 1f) { // a soft shadow under the strip (a few faint offset copies: no blur, so PDFs get it)
            Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
            sh.setColor(0xff000000);
            sh.setAlpha(14);
            for (int i = 1; i <= 3; i++) {
                int sv = c.save();
                c.translate(thick * 0.006f * i, thick * 0.014f * i);
                c.drawPath(outline, sh);
                c.restoreToCount(sv);
            }
        }
        int fill = look.equals("kraft") ? mix(color, 0xffb5895b, 0.72f) : look.equals("masking") ? mix(color, 0xffe8dcc0, 0.25f) : color;
        Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
        f.setColor(fill | 0xff000000);
        f.setAlpha(paper ? a : Math.round(a * 0.97f));
        c.drawPath(outline, f);
        int saved = c.save();
        c.clipPath(outline);
        if (paper) tapeFibres(c, pts, thick, look, seed, a);
        // pattern in white, anchored where the tape starts and laid along its opening direction, so stripes that are
        // already down never move while the tape is drawn longer (or bends)
        int n = pts.length / 2;
        float sx = pts[0], sy = pts[1], ang = 0;
        for (int i = 1; i < n; i++) {
            float dx = pts[2 * i] - sx, dy = pts[2 * i + 1] - sy;
            if (Math.hypot(dx, dy) >= thick || i == n - 1) { ang = (float) Math.toDegrees(Math.atan2(dy, dx)); break; }
        }
        if (Float.isNaN(ang)) ang = 0;
        // the strip's extent in the pattern's frame (rotated to the opening direction, origin at the start), so marks
        // are drawn only over the strip, not over a square as wide as the tape is long
        android.graphics.Matrix unrot = new android.graphics.Matrix();
        unrot.setRotate(-ang, sx, sy);
        android.graphics.Path rp = new android.graphics.Path(outline);
        rp.transform(unrot);
        RectF rb = new RectF();
        rp.computeBounds(rb, true);
        float step = Math.max(8f, thick / 3.2f);
        float x0 = rb.left - sx - step, x1 = rb.right - sx + step, y0 = rb.top - sy - step, y1 = rb.bottom - sy + step;
        int k0 = (int) Math.floor(x0 / step), k1 = (int) Math.ceil(x1 / step);
        int j0 = (int) Math.floor(y0 / step), j1 = (int) Math.ceil(y1 / step);
        c.rotate(ang, sx, sy);
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        pt.setColor(0xffffffff);
        pt.setAlpha(Math.round(a * (look.equals("washi") ? 0.5f : look.equals("kraft") ? 0.22f : 0.32f)));
        pt.setStrokeWidth(Math.max(1.2f, thick * 0.08f));
        // marks sit at whole steps from the start point, so the ones already down never move as the tape grows
        switch (pattern) {
            case "stripes": { // slanted lines through (start + k·step, start's height)
                float slope = 0.7f, reach = Math.max(Math.abs(y0), Math.abs(y1)) * slope;
                int ks0 = (int) Math.floor((x0 - reach) / step), ks1 = (int) Math.ceil((x1 + reach) / step);
                for (int k = ks0; k <= ks1; k++) {
                    float d = k * step;
                    c.drawLine(sx + d - y1 * slope, sy + y1, sx + d - y0 * slope, sy + y0, pt);
                }
                break;
            }
            case "grid":
                pt.setStrokeWidth(Math.max(0.8f, thick * 0.035f));
                pt.setAlpha(Math.round(a * 0.4f));
                for (int k = k0; k <= k1; k++) c.drawLine(sx + k * step + step / 2, sy + y0, sx + k * step + step / 2, sy + y1, pt);
                for (int j = j0; j <= j1; j++) c.drawLine(sx + x0, sy + j * step + step / 2, sx + x1, sy + j * step + step / 2, pt);
                break;
            case "dots":
                pt.setAlpha(Math.round(a * 0.45f));
                for (int j = j0; j <= j1; j++)
                    for (int k = k0; k <= k1; k++)
                        c.drawCircle(sx + k * step + (Math.abs(j) % 2) * step / 2 + step / 4, sy + j * step + step / 2, Math.max(1.2f, thick * 0.05f), pt);
                break;
            default:
                break;
        }
        c.restoreToCount(saved);
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeJoin(Paint.Join.ROUND);
        edge.setStrokeWidth(Math.max(0.8f, thick * 0.025f));
        edge.setColor(0xff000000);
        edge.setAlpha(Math.round(a * (alpha < 1f ? 0.9f : paper ? 0.07f : 0.12f)));
        if (alpha < 1f) edge.setPathEffect(new android.graphics.DashPathEffect(new float[]{6, 5}, 0));
        c.drawPath(outline, edge);
    }

    /**
     * Paper fibres along the strip, laid segment by segment from its start (so a tape being drawn longer keeps the
     * fibres it has): short thin streaks, light and dark, and on washi a faint sheen down the middle.
     */
    private static void tapeFibres(Canvas c, float[] pts, float thick, String look, int seed, int a) {
        boolean kraft = look.equals("kraft"), washi = look.equals("washi");
        Paint fp = new Paint(Paint.ANTI_ALIAS_FLAG);
        fp.setStrokeCap(Paint.Cap.ROUND);
        float density = kraft ? 30f : washi ? 60f : 36f; // square units of tape per fibre
        int n = pts.length / 2;
        for (int sgi = 0; sgi + 1 < n; sgi++) {
            float x0 = pts[2 * sgi], y0 = pts[2 * sgi + 1], x1 = pts[2 * sgi + 2], y1 = pts[2 * sgi + 3];
            float dx = x1 - x0, dy = y1 - y0, len = (float) Math.hypot(dx, dy);
            if (len < 1e-3f) continue;
            dx /= len; dy /= len;
            float nx = -dy, ny = dx;
            Rnd r = new Rnd(seed * 1000003L + sgi);
            int count = Math.max(1, Math.round(len * thick / density));
            for (int i = 0; i < count; i++) {
                float u = r.next() * len, v = (r.next() - 0.5f) * thick;
                float fl = (kraft ? 2f : 3f) + r.next() * (kraft ? 7f : washi ? 8f : 9f);
                float ang = (r.next() - 0.5f) * (washi ? 0.5f : 0.25f);
                float cx = x0 + dx * u + nx * v, cy = y0 + dy * u + ny * v;
                float ex = dx * (float) Math.cos(ang) - dy * (float) Math.sin(ang), ey = dy * (float) Math.cos(ang) + dx * (float) Math.sin(ang);
                boolean dark = r.next() < (kraft ? 0.6f : washi ? 0.25f : 0.45f);
                fp.setColor(dark ? 0xff000000 : 0xffffffff);
                fp.setAlpha(Math.round(a * (dark ? (kraft ? 0.08f : 0.05f) : (washi ? 0.16f : 0.12f)) * (0.5f + r.next())));
                fp.setStrokeWidth((washi ? 0.25f : 0.35f) + r.next() * (kraft ? 0.5f : 0.4f));
                c.drawLine(cx - ex * fl / 2, cy - ey * fl / 2, cx + ex * fl / 2, cy + ey * fl / 2, fp);
                if (kraft && r.next() < 0.5f) { // recycled-paper specks
                    fp.setColor(0xff000000);
                    fp.setAlpha(Math.round(a * 0.12f));
                    c.drawCircle(x0 + dx * r.next() * len + nx * (r.next() - 0.5f) * thick, y0 + dy * r.next() * len + ny * (r.next() - 0.5f) * thick,
                            0.3f + r.next() * 0.5f, fp);
                }
            }
            if (washi) { // a soft sheen a little above the middle
                Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
                sheen.setColor(0xffffffff);
                sheen.setAlpha(Math.round(a * 0.10f));
                sheen.setStrokeWidth(thick * 0.18f);
                sheen.setStrokeCap(Paint.Cap.BUTT);
                c.drawLine(x0 - nx * thick * 0.18f, y0 - ny * thick * 0.18f, x1 - nx * thick * 0.18f, y1 - ny * thick * 0.18f, sheen);
            }
        }
    }

    static android.graphics.Path tapeOutline(float[] pts, float thick) { return tapeOutline(pts, thick, null, 0); }

    /**
     * The strip's shape: the centre line stroked thick wide (round bends), with both ends cut: a zigzag for flat
     * tape (look null), or torn paper (uneven teeth, slightly slanted) for the paper looks.
     */
    static android.graphics.Path tapeOutline(float[] pts, float thick, String look, int seed) {
        int n = pts.length / 2;
        if (n < 2) return null;
        android.graphics.Path line = new android.graphics.Path();
        line.moveTo(pts[0], pts[1]);
        if (n == 2) line.lineTo(pts[2], pts[3]);
        else { // smooth through the midpoints
            for (int i = 1; i < n - 1; i++)
                line.quadTo(pts[2 * i], pts[2 * i + 1], (pts[2 * i] + pts[2 * i + 2]) / 2, (pts[2 * i + 1] + pts[2 * i + 3]) / 2);
            line.lineTo(pts[2 * n - 2], pts[2 * n - 1]);
        }
        Paint st = new Paint(Paint.ANTI_ALIAS_FLAG);
        st.setStyle(Paint.Style.STROKE);
        st.setStrokeWidth(thick);
        st.setStrokeCap(Paint.Cap.BUTT);
        st.setStrokeJoin(Paint.Join.ROUND);
        android.graphics.Path out = new android.graphics.Path();
        st.getFillPath(line, out);
        out.setFillType(android.graphics.Path.FillType.WINDING);
        android.graphics.Path solid = new android.graphics.Path();
        solid.op(out, android.graphics.Path.Op.UNION); // self-overlaps become one area (no darker crossings)
        // zigzag: cut teeth into each end
        for (int end = 0; end < 2; end++) {
            int k = end == 0 ? 0 : n - 1, dir = end == 0 ? 1 : -1;
            float ex = pts[2 * k], ey = pts[2 * k + 1];
            // direction from a point a little way in, so a wobble at the very end doesn't skew the cut
            int j = k;
            float qx = ex, qy = ey;
            while (j + dir >= 0 && j + dir < n) {
                j += dir;
                qx = pts[2 * j]; qy = pts[2 * j + 1];
                if (Math.hypot(qx - ex, qy - ey) >= thick * 0.5f) break;
            }
            float dx = ex - qx, dy = ey - qy, len = (float) Math.hypot(dx, dy);
            if (len < 1e-3f) continue;
            dx /= len; dy /= len; // outward
            float nx = -dy, ny = dx;
            float hw = thick / 2 + 1.5f;
            android.graphics.Path cut = new android.graphics.Path();
            if (look == null) {
                int teeth = Math.max(3, Math.round(thick / 9f));
                float depth = Math.min(len * 0.6f, Math.max(2.5f, thick * 0.07f));
                cut.moveTo(ex + nx * hw + dx * 4, ey + ny * hw + dy * 4);
                cut.lineTo(ex - nx * hw + dx * 4, ey - ny * hw + dy * 4);
                for (int i = 0; i <= teeth * 2; i++) {
                    float v = -hw + Math.min(2 * hw, i * (2 * hw) / (teeth * 2));
                    float u = (i % 2 == 1) ? -depth : 0;
                    cut.lineTo(ex + nx * v + dx * u, ey + ny * v + dy * u);
                }
            } else { // torn by hand: many uneven little teeth, the whole tear a little slanted
                Rnd r = new Rnd(seed * 7919L + end);
                boolean washi = look.equals("washi"), kraft = look.equals("kraft");
                int steps = Math.max(6, Math.round(thick / (washi ? 1.6f : kraft ? 3.2f : 2.4f)));
                float depth = Math.min(len * 0.6f, Math.max(1.5f, thick * (washi ? 0.05f : kraft ? 0.12f : 0.10f)));
                float slant = (r.next() - 0.5f) * thick * 0.12f;
                float back = depth + Math.abs(slant) + 4;
                cut.moveTo(ex + nx * hw + dx * back, ey + ny * hw + dy * back);
                cut.lineTo(ex - nx * hw + dx * back, ey - ny * hw + dy * back);
                float prevU = 0;
                for (int i = 0; i <= steps; i++) {
                    float t = i / (float) steps, v = -hw + t * 2 * hw;
                    float uu = -depth * r.next() + slant * (t - 0.5f) * 2;
                    float u = prevU * 0.35f + uu * 0.65f; // a little smoothing: fibres, not a saw
                    prevU = u;
                    cut.lineTo(ex + nx * v + dx * u, ey + ny * v + dy * u);
                }
            }
            cut.close();
            solid.op(cut, android.graphics.Path.Op.DIFFERENCE);
        }
        return solid;
    }

    /** A tape as a texture of its box: k px per unit (capped so the bitmap stays small). */
    static Bitmap tapeBitmap(Notebook.Item it, float k) {
        k = Math.min(k, 2048f / Math.max(it.w, it.h));
        int bw = Math.max(1, Math.round(it.w * k)), bh = Math.max(1, Math.round(it.h * k));
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(bw / it.w, bh / it.h);
        c.translate(-it.x, -it.y);
        tape(c, it, 1f);
        return b;
    }

    static void item(Canvas c, File dir, Notebook.Item it, boolean darkPaper) {
        if (it.type == Notebook.Item.TAPE) { tape(c, it, it.revealed() ? TAPE_OPEN_ALPHA : 1f); return; }
        if (it.type == Notebook.Item.IMAGE) {
            Bitmap b = image(dir, it.text, 1600);
            if (b != null) {
                c.drawBitmap(b, null, new RectF(it.x, it.y, it.x + it.w, it.y + it.h), new Paint(Paint.FILTER_BITMAP_FLAG));
                b.recycle();
            }
        } else if (it.type == Notebook.Item.SHAPE) {
            ShapeSpec.draw(c, it, darkPaper);
        } else {
            StaticLayout sl = textLayout(it.text, it.size, inkColor(it.color, darkPaper), it.w, it.hand(), it.style, darkPaper);
            c.save();
            c.translate(it.x, it.y);
            sl.draw(c);
            c.restore();
        }
    }

    static StaticLayout textLayout(String text, float size, int color, float width) { return textLayout(text, size, color, width, false, null); }

    static StaticLayout textLayout(String text, float size, int color, float width, boolean hand) { return textLayout(text, size, color, width, hand, null); }

    /**
     * A text box: its style's font (Inter by default; converted handwriting defaults to the handwriting face,
     * hand = true), bold / italic, underline / strike-through and alignment.
     */
    static StaticLayout textLayout(String text, float size, int color, float width, boolean hand, String style) {
        return textLayout(text, size, color, width, hand, style, false);
    }

    /** dark: on dark paper (per-word colours get the same light-ink mapping as the box colour). */
    static StaticLayout textLayout(String text, float size, int color, float width, boolean hand, String style, boolean dark) {
        Fonts.Style st = Fonts.Style.parse(style);
        CharSequence cs = st.runs.isEmpty() ? text : st.spanned(text, hand, 1f, dark ? c -> inkColor(c, true) : null);
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setTypeface(st.face(hand));
        tp.setTextSize(size);
        tp.setColor(color);
        tp.setUnderlineText(st.underline);
        tp.setStrikeThruText(st.strike);
        return StaticLayout.Builder.obtain(cs, 0, cs.length(), tp, Math.max(1, (int) Math.ceil(width)))
                .setAlignment(st.alignment()).setIncludePad(false).build();
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

    /** Bounding box of everything on a page (stroke points padded by their width, plus items). */
    static RectF canvasBounds(Notebook.Page p) {
        RectF r = new RectF();
        for (Stroke st : p.strokes()) {
            float pad = st.width * 2;
            for (int i = 0; i < st.n; i++) {
                float x = st.p[4 * i], y = st.p[4 * i + 1];
                if (r.isEmpty() && r.left == 0 && r.right == 0) r.set(x - pad, y - pad, x + pad, y + pad);
                else r.union(x - pad, y - pad, x + pad, y + pad);
            }
        }
        for (Notebook.Item it : p.items()) {
            RectF ib = it.type == Notebook.Item.SHAPE ? ShapeSpec.renderBounds(it) : new RectF(it.x, it.y, it.x + it.w, it.y + it.h);
            if (r.isEmpty() && r.left == 0 && r.right == 0) r.set(ib);
            else r.union(ib);
        }
        return r;
    }

    /** A page rendered to a bitmap widthPx wide. */
    static Bitmap pageBitmap(Notebook nb, File dir, Notebook.Page p, Pdf pdf, int widthPx) {
        return pageBitmap(nb, dir, p, null, null, pdf, widthPx);
    }

    static Bitmap pageBitmap(Notebook nb, File dir, Notebook.Page p, Notebook.Page prev, Notebook.Page next, Pdf pdf, int widthPx) {
        float s = widthPx / Notebook.PW;
        Bitmap bm = Bitmap.createBitmap(widthPx, Math.max(1, Math.round(p.h * s)), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        c.scale(s, s);
        page(c, nb, dir, p, prev, next, pdf, widthPx, false);
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

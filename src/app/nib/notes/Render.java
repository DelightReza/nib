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
        for (Notebook.Item it : p.items) if (it.type != Notebook.Item.TAPE) item(c, dir, it, dark(nb));
        boolean dk = dark(nb);
        SegBuf tmp = new SegBuf();
        Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        ink.setStrokeCap(Paint.Cap.ROUND);
        Paint hl = new Paint(ink);
        if (!forPdf) hl.setXfermode(new PorterDuffXfermode(dk ? PorterDuff.Mode.LIGHTEN : PorterDuff.Mode.DARKEN));
        for (int pass = 0; pass < 2; pass++) {
            for (Stroke s : p.strokes) {
                boolean isHl = s.hl();
                if ((pass == 0) != isHl) continue;
                Paint pt = isHl ? hl : ink;
                int col = isHl ? s.color : inkColor(s.color, dk);
                if (isHl && forPdf) col = (col & 0x00ffffff) | 0x70000000;
                else if (isHl && dk) col = scale(col, 0.6f);
                pt.setColor(col);
                tmp.clear();
                s.emitAll(0, tmp);
                segs(c, tmp, pt);
            }
        }
        for (Notebook.Item it : p.items) if (it.type == Notebook.Item.TAPE) tape(c, it, it.revealed() ? TAPE_OPEN_ALPHA : 1f);
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

    static void tape(Canvas c, float[] pts, float thick, int color, String pattern, float alpha) {
        android.graphics.Path outline = tapeOutline(pts, thick);
        if (outline == null) return;
        int a = Math.round(alpha * 255);
        Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
        f.setColor(color | 0xff000000);
        f.setAlpha(Math.round(a * 0.97f));
        c.drawPath(outline, f);
        int saved = c.save();
        c.clipPath(outline);
        // pattern in white, laid along the tape's overall direction
        int n = pts.length / 2;
        float ang = (float) Math.toDegrees(Math.atan2(pts[2 * n - 1] - pts[1], pts[2 * n - 2] - pts[0]));
        if (n < 2 || Float.isNaN(ang)) ang = 0;
        RectF bb = new RectF();
        outline.computeBounds(bb, true);
        float cx = bb.centerX(), cy = bb.centerY(), half = (float) Math.hypot(bb.width(), bb.height()) / 2 + thick;
        c.rotate(ang, cx, cy);
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        pt.setColor(0xffffffff);
        pt.setAlpha(Math.round(a * 0.32f));
        pt.setStrokeWidth(Math.max(1.2f, thick * 0.08f));
        float step = Math.max(8f, thick / 3.2f);
        switch (pattern) {
            case "stripes":
                for (float d = -half; d < half; d += step) c.drawLine(cx + d, cy + half, cx + d + 2 * half * 0.7f, cy - half, pt);
                break;
            case "grid":
                pt.setStrokeWidth(Math.max(0.8f, thick * 0.035f));
                pt.setAlpha(Math.round(a * 0.4f));
                for (float d = -half; d < half; d += step) {
                    c.drawLine(cx + d, cy - half, cx + d, cy + half, pt);
                    c.drawLine(cx - half, cy + d, cx + half, cy + d, pt);
                }
                break;
            case "dots":
                pt.setAlpha(Math.round(a * 0.45f));
                for (float dy = -half; dy < half; dy += step)
                    for (float dx = -half + ((int) ((dy + half) / step) % 2) * step / 2; dx < half; dx += step)
                        c.drawCircle(cx + dx, cy + dy, Math.max(1.2f, thick * 0.05f), pt);
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
        edge.setAlpha(Math.round(a * (alpha < 1f ? 0.9f : 0.12f)));
        if (alpha < 1f) edge.setPathEffect(new android.graphics.DashPathEffect(new float[]{6, 5}, 0));
        c.drawPath(outline, edge);
    }

    /** The strip's shape: the centre line stroked thick wide (round bends), with both ends torn in a zigzag. */
    static android.graphics.Path tapeOutline(float[] pts, float thick) {
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
            int teeth = Math.max(3, Math.round(thick / 9f));
            float tooth = thick / teeth, depth = Math.min(len * 0.6f, Math.max(2.5f, thick * 0.07f)), hw = thick / 2 + 1.5f;
            android.graphics.Path cut = new android.graphics.Path();
            cut.moveTo(ex + nx * hw + dx * 4, ey + ny * hw + dy * 4);
            cut.lineTo(ex - nx * hw + dx * 4, ey - ny * hw + dy * 4);
            for (int i = 0; i <= teeth * 2; i++) {
                float v = -hw + Math.min(2 * hw, i * (2 * hw) / (teeth * 2));
                float u = (i % 2 == 1) ? -depth : 0;
                cut.lineTo(ex + nx * v + dx * u, ey + ny * v + dy * u);
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

    /** A page rendered to a bitmap widthPx wide. */
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

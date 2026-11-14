package app.nib.notes;

/** One ink stroke in page-local units. Immutable once committed to a page. */
final class Stroke {
    static final int PEN = 0, HL = 1, BALL = 2, FINE = 3, HL_FLAT = 4; // PEN = fountain pen; HL_FLAT = chisel highlighter
    /** Chisel tip: a flat nib held upright, FLAT_T of the width thick (half-size, ×width). */
    static final float FLAT_T = 0.12f;

    static boolean isHl(int tool) { return tool == HL || tool == HL_FLAT; }

    boolean hl() { return isHl(tool); }

    final int tool, color;
    final float width;
    /** Clean shape this stroke is: 0 freehand, LINE, or CLOSED (snapped circle / polygon). Shapes keep handles. */
    int shape;
    static final int LINE = 1, CLOSED = 2;
    float[] p = new float[4 * 64]; // x, y, pressure(smoothed), radius
    int n;
    float l = Float.MAX_VALUE, t = Float.MAX_VALUE, r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;

    Stroke(int tool, int color, float width) {
        this.tool = tool;
        this.color = color;
        this.width = width;
    }

    float radius(float pr) {
        if (isHl(tool)) return width * 0.5f;
        // Xiaomi Smart Pen: light writing ~0.15, normal ~0.5, firm ~0.65 (measured). Map that span
        // onto the full width range so normal handwriting sits near the nominal width.
        float pn = Math.max(0f, Math.min(1f, pr / 0.65f));
        if (tool == FINE) return width * 0.5f;
        if (tool == BALL) return width * 0.5f * (0.62f + 0.38f * (float) Math.pow(pn, 0.9));
        return width * 0.5f * (0.28f + 0.72f * (float) Math.pow(pn, 0.9));
    }

    void add(float x, float y, float pr) {
        if (4 * (n + 1) > p.length) {
            float[] np = new float[p.length * 2];
            System.arraycopy(p, 0, np, 0, 4 * n);
            p = np;
        }
        float rad = radius(pr);
        int o = 4 * n++;
        p[o] = x;
        p[o + 1] = y;
        p[o + 2] = pr;
        p[o + 3] = rad;
        if (x - rad < l) l = x - rad;
        if (y - rad < t) t = y - rad;
        if (x + rad > r) r = x + rad;
        if (y + rad > b) b = y + rad;
    }

    /** One piece of ink; the chisel highlighter draws a swept box (flagged by a negative second radius). */
    private void seg(SegBuf buf, float x0, float y0, float x1, float y1, float r0, float r1, int c) {
        if (tool == HL_FLAT) buf.put(x0, y0, x1, y1, width * FLAT_T, -width * 0.5f, c);
        else buf.put(x0, y0, x1, y1, r0, r1, c);
    }

    float x(int i) { return p[4 * i]; }
    float y(int i) { return p[4 * i + 1]; }
    float rad(int i) { return p[4 * i + 3]; }

    /** Dot at the first point (covers single taps). */
    void emitDot(float top, SegBuf buf) {
        seg(buf, x(0), y(0) + top, x(0), y(0) + top, rad(0), rad(0), color);
        bl = x(0) - rad(0); bt = y(0) + top - rad(0); br = x(0) + rad(0); bb = y(0) + top + rad(0);
    }

    /** Bounds (doc units, includes radius) of the last emitDot/emitInterval call. */
    float bl, bt, br, bb;

    /**
     * Emits the curved piece between points k and k+1 as short capsules, using a centripetal
     * Catmull-Rom spline through the neighbours (points past {@code last} are treated as missing,
     * i.e. the curve is clamped there). Same inputs always give the same geometry, so live ink
     * and re-rendered ink match exactly.
     */
    void emitInterval(int k, int last, float top, SegBuf buf) {
        float x1 = x(k), y1 = y(k), x2 = x(k + 1), y2 = y(k + 1);
        float x0, y0, x3, y3;
        if (k > 0) { x0 = x(k - 1); y0 = y(k - 1); } else { x0 = 2 * x1 - x2; y0 = 2 * y1 - y2; }
        if (k + 2 <= last) { x3 = x(k + 2); y3 = y(k + 2); } else { x3 = 2 * x2 - x1; y3 = 2 * y2 - y1; }
        float d01 = (float) Math.sqrt(Math.hypot(x1 - x0, y1 - y0)), d12 = (float) Math.sqrt(Math.hypot(x2 - x1, y2 - y1)),
                d23 = (float) Math.sqrt(Math.hypot(x3 - x2, y3 - y2));
        float r1 = rad(k), r2 = rad(k + 1);
        float len = (float) Math.hypot(x2 - x1, y2 - y1);
        int segs = Math.max(1, Math.min(24, (int) Math.ceil(len / 0.7f)));
        bl = Math.min(x1, x2); br = Math.max(x1, x2); bt = Math.min(y1, y2); bb = Math.max(y1, y2);
        float px = x1, py = y1, pr = r1;
        if (d12 < 1e-4f || segs == 1) {
            seg(buf, x1, y1 + top, x2, y2 + top, r1, r2, color);
        } else {
            if (d01 < 1e-4f) d01 = d12;
            if (d23 < 1e-4f) d23 = d12;
            // Hermite tangents of the centripetal (alpha = 0.5) Catmull-Rom spline, per unit of d12
            float m1x = (x1 - x0) / d01 - (x2 - x0) / (d01 + d12) + (x2 - x1) / d12;
            float m1y = (y1 - y0) / d01 - (y2 - y0) / (d01 + d12) + (y2 - y1) / d12;
            float m2x = (x2 - x1) / d12 - (x3 - x1) / (d12 + d23) + (x3 - x2) / d23;
            float m2y = (y2 - y1) / d12 - (y3 - y1) / (d12 + d23) + (y3 - y2) / d23;
            m1x *= d12; m1y *= d12; m2x *= d12; m2y *= d12;
            for (int j = 1; j <= segs; j++) {
                float t = j / (float) segs, t2 = t * t, t3 = t2 * t;
                float h00 = 2 * t3 - 3 * t2 + 1, h10 = t3 - 2 * t2 + t, h01 = -2 * t3 + 3 * t2, h11 = t3 - t2;
                float qx = h00 * x1 + h10 * m1x + h01 * x2 + h11 * m2x;
                float qy = h00 * y1 + h10 * m1y + h01 * y2 + h11 * m2y;
                float qr = r1 + (r2 - r1) * t;
                seg(buf, px, py + top, qx, qy + top, pr, qr, color);
                if (qx < bl) bl = qx; if (qx > br) br = qx; if (qy < bt) bt = qy; if (qy > bb) bb = qy;
                px = qx; py = qy; pr = qr;
            }
        }
        float m = Math.max(r1, r2);
        bl -= m; br += m; bt += top - m; bb += top + m;
    }

    void emitAll(float top, SegBuf buf) {
        if (n == 0) return;
        emitDot(top, buf);
        for (int k = 0; k + 1 < n; k++) emitInterval(k, n - 1, top, buf);
    }

    /** Copy scaled by s around (px,py) then shifted by (tx,ty); width scales too. */
    Stroke transformed(float px, float py, float sc, float tx, float ty) {
        Stroke o = new Stroke(tool, color, width * sc);
        o.shape = shape;
        for (int i = 0; i < n; i++) o.add((x(i) - px) * sc + px + tx, (y(i) - py) * sc + py + ty, p[4 * i + 2]);
        return o;
    }

    Stroke withColor(int c) {
        Stroke o = new Stroke(tool, c, width);
        o.shape = shape;
        for (int i = 0; i < n; i++) o.add(x(i), y(i), p[4 * i + 2]);
        return o;
    }

    /** Same stroke on another page (dy shifts it into that page's coordinates). */
    Stroke shifted(float dx, float dy) { return transformed(0, 0, 1, dx, dy); }

    /**
     * Partial erase: the pieces of this stroke left after removing every point within er of the
     * segment (ax,ay)-(bx,by). Returns null when nothing was touched.
     */
    java.util.ArrayList<Stroke> cut(float ax, float ay, float bx, float by, float er) {
        if (!hit(ax, ay, bx, by, er)) return null;
        float dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
        java.util.ArrayList<Stroke> out = new java.util.ArrayList<>();
        Stroke cur = null;
        for (int i = 0; i < n; i++) {
            float px = x(i) - ax, py = y(i) - ay;
            float h = len2 > 1e-6f ? Math.max(0f, Math.min(1f, (px * dx + py * dy) / len2)) : 0f;
            float qx = px - dx * h, qy = py - dy * h;
            boolean gone = qx * qx + qy * qy < er * er;
            if (gone) {
                if (cur != null && cur.n > 0) out.add(cur);
                cur = null;
            } else {
                if (cur == null) cur = new Stroke(tool, color, width);
                cur.add(x(i), y(i), p[4 * i + 2]);
            }
        }
        if (cur != null && cur.n > 0) out.add(cur);
        return out;
    }

    /** Fraction of points inside the polygon (xs, ys). */
    float insideFraction(float[] xs, float[] ys, int m) {
        if (n == 0) return 0;
        int in = 0;
        for (int i = 0; i < n; i++) if (Geo.inside(x(i), y(i), xs, ys, m)) in++;
        return in / (float) n;
    }

    /** Does any point come within er of the segment (ax,ay)-(bx,by)? Page-local coords. */
    boolean hit(float ax, float ay, float bx, float by, float er) {
        if (Math.max(ax, bx) + er < l || Math.min(ax, bx) - er > r
                || Math.max(ay, by) + er < t || Math.min(ay, by) - er > b) return false;
        float dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
        for (int i = 0; i < n; i++) {
            float px = x(i) - ax, py = y(i) - ay;
            float h = len2 > 1e-6f ? Math.max(0f, Math.min(1f, (px * dx + py * dy) / len2)) : 0f;
            float qx = px - dx * h, qy = py - dy * h, rr = er + rad(i);
            if (qx * qx + qy * qy < rr * rr) return true;
        }
        return false;
    }
}

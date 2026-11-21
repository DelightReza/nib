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
    /** The notebook layer it's on (Notebook.Layer id); 0 = the first layer. Copies keep it. */
    int layer;
    static final int LINE = 1, CLOSED = 2;
    float[] p = new float[4 * 64]; // x, y, pressure(smoothed), radius
    int n;
    float l = Float.MAX_VALUE, t = Float.MAX_VALUE, r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;

    Stroke(int tool, int color, float width) {
        this.tool = tool;
        this.color = color;
        this.width = width;
    }

    /** A stroke that will get exactly `points` points (read from a file, copied): no spare room. */
    Stroke(int tool, int color, float width, int points) {
        this(tool, color, width);
        p = new float[4 * Math.max(1, points)];
    }

    /** Drops the spare room left from growing while it was written (a finished stroke never grows again). */
    Stroke trim() {
        if (p.length > 4 * n + 4) p = java.util.Arrays.copyOf(p, 4 * Math.max(1, n));
        return this;
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

    /**
     * Pressure for a stroke that has none (a finger, a stylus without pressure): full pressure p, eased in over
     * the first stretch of the stroke so the fountain pen starts thin. t = distance so far / taper length.
     */
    static float taperP(float p, float t) {
        t = Math.max(0f, Math.min(1f, t));
        float s = t * t * (3 - 2 * t);
        return p * (0.12f + 0.88f * s);
    }

    /** Taper length in units for a pen of this width: a few nib widths, never longer than half the stroke. */
    float taperLen() { return Math.max(6f, Math.min(20f, width * 5f)); }

    /** The same easing at the end of the stroke, once it's finished (the live tail can't know where it ends). */
    void taperEnd(float len) {
        if (n < 3 || len <= 0) return;
        float d = 0;
        for (int i = n - 1; i >= 0; i--) {
            if (i < n - 1) d += (float) Math.hypot(x(i + 1) - x(i), y(i + 1) - y(i));
            if (d >= len) break;
            int o = 4 * i;
            float f = taperP(1f, d / len);
            p[o + 2] *= f;
            p[o + 3] = radius(p[o + 2]);
        }
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

    private static float len(float dx, float dy) { return (float) Math.sqrt(dx * dx + dy * dy); }

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
        // plain sqrt, not Math.hypot: hypot is a slow library call on ART and this runs for every point of every stroke
        float len = len(x2 - x1, y2 - y1);
        float d01 = (float) Math.sqrt(len(x1 - x0, y1 - y0)), d12 = (float) Math.sqrt(len),
                d23 = (float) Math.sqrt(len(x3 - x2, y3 - y2));
        float r1 = rad(k), r2 = rad(k + 1);
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
        Stroke o = new Stroke(tool, color, width * sc, n);
        o.shape = shape;
        o.layer = layer;
        for (int i = 0; i < n; i++) o.add((x(i) - px) * sc + px + tx, (y(i) - py) * sc + py + ty, p[4 * i + 2]);
        return o;
    }

    Stroke withColor(int c) {
        Stroke o = new Stroke(tool, c, width, n);
        o.shape = shape;
        o.layer = layer;
        for (int i = 0; i < n; i++) o.add(x(i), y(i), p[4 * i + 2]);
        return o;
    }

    /** The same stroke on another layer. */
    Stroke onLayer(int l) {
        Stroke o = transformed(0, 0, 1, 0, 0);
        o.layer = l;
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
                if (cur != null && cur.n > 0) out.add(cur.trim());
                cur = null;
            } else {
                if (cur == null) { cur = new Stroke(tool, color, width); cur.layer = layer; }
                cur.add(x(i), y(i), p[4 * i + 2]);
            }
        }
        if (cur != null && cur.n > 0) out.add(cur.trim());
        if (out.size() == 1 && out.get(0).n == n) return null; // passed between two points without taking any: untouched
        return out;
    }

    /** Fraction of points inside the polygon (xs, ys). */
    float insideFraction(float[] xs, float[] ys, int m) {
        if (n == 0) return 0;
        int in = 0;
        for (int i = 0; i < n; i++) if (Geo.inside(x(i), y(i), xs, ys, m)) in++;
        return in / (float) n;
    }

    /**
     * Does the ink come within er of the segment (ax,ay)-(bx,by)? Page-local coords. Measured to the lines between
     * the points, not just the points: a scaled-up shape has its points far apart, and a tap between two of them
     * must still find it.
     */
    boolean hit(float ax, float ay, float bx, float by, float er) {
        if (Math.max(ax, bx) + er < l || Math.min(ax, bx) - er > r
                || Math.max(ay, by) + er < t || Math.min(ay, by) - er > b) return false;
        if (n == 1) {
            float rr = er + rad(0);
            return ptSeg2(x(0), y(0), ax, ay, bx, by) < rr * rr;
        }
        for (int i = 0; i + 1 < n; i++) {
            float rr = er + Math.max(rad(i), rad(i + 1));
            if (segDist2(x(i), y(i), x(i + 1), y(i + 1), ax, ay, bx, by) < rr * rr) return true;
        }
        return false;
    }

    /** Squared distance from point (px,py) to segment (ax,ay)-(bx,by). */
    static float ptSeg2(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
        float h = len2 > 1e-9f ? Math.max(0f, Math.min(1f, ((px - ax) * dx + (py - ay) * dy) / len2)) : 0f;
        float qx = px - ax - dx * h, qy = py - ay - dy * h;
        return qx * qx + qy * qy;
    }

    /** Squared distance between segments p0-p1 and q0-q1 (0 when they cross). */
    static float segDist2(float p0x, float p0y, float p1x, float p1y, float q0x, float q0y, float q1x, float q1y) {
        float d1 = cross(q0x, q0y, q1x, q1y, p0x, p0y), d2 = cross(q0x, q0y, q1x, q1y, p1x, p1y);
        float d3 = cross(p0x, p0y, p1x, p1y, q0x, q0y), d4 = cross(p0x, p0y, p1x, p1y, q1x, q1y);
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) return 0;
        return Math.min(Math.min(ptSeg2(p0x, p0y, q0x, q0y, q1x, q1y), ptSeg2(p1x, p1y, q0x, q0y, q1x, q1y)),
                Math.min(ptSeg2(q0x, q0y, p0x, p0y, p1x, p1y), ptSeg2(q1x, q1y, p0x, p0y, p1x, p1y)));
    }

    private static float cross(float ax, float ay, float bx, float by, float cx, float cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }
}

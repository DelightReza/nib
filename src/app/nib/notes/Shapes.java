package app.nib.notes;

import java.util.ArrayList;

/**
 * "Hold to snap": turns a rough stroke into a clean line, circle/ellipse, triangle, rectangle or
 * quadrilateral. Returns the clean outline as dense x,y pairs (page units), or null if the stroke
 * isn't confidently a shape.
 */
final class Shapes {
    private Shapes() {}

    static float[] recognize(Stroke s) {
        int n = s.n;
        if (n < 6) return null;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, len = 0;
        for (int i = 0; i < n; i++) {
            minX = Math.min(minX, s.x(i)); maxX = Math.max(maxX, s.x(i));
            minY = Math.min(minY, s.y(i)); maxY = Math.max(maxY, s.y(i));
            if (i > 0) len += (float) Math.hypot(s.x(i) - s.x(i - 1), s.y(i) - s.y(i - 1));
        }
        float w = maxX - minX, h = maxY - minY, diag = (float) Math.hypot(w, h);
        if (diag < 18) return null;
        float x0 = s.x(0), y0 = s.y(0), x1 = s.x(n - 1), y1 = s.y(n - 1);
        float ends = (float) Math.hypot(x1 - x0, y1 - y0);

        // straight line
        if (ends / len > 0.92f) {
            float ang = (float) Math.toDegrees(Math.atan2(y1 - y0, x1 - x0));
            float a = ((ang % 90) + 90) % 90;
            if (a < 6 || a > 84) { // snap to horizontal / vertical
                if (Math.abs(x1 - x0) > Math.abs(y1 - y0)) y1 = y0; else x1 = x0;
            }
            return densify(new float[]{x0, y0, x1, y1}, false);
        }
        if (ends > 0.22f * len) return null; // open squiggle: leave it alone

        // ellipse / circle
        float cx = (minX + maxX) / 2, cy = (minY + maxY) / 2, rx = w / 2, ry = h / 2;
        if (rx > 4 && ry > 4) {
            float dev = 0;
            for (int i = 0; i < n; i++) {
                float ux = (s.x(i) - cx) / rx, uy = (s.y(i) - cy) / ry;
                dev += Math.abs((float) Math.sqrt(ux * ux + uy * uy) - 1f);
            }
            dev /= n;
            // corners: a polygon has a few sharp turns; an ellipse has none
            float[] poly = rdp(s, 0.06f * diag);
            int corners = poly.length / 2 - 1;
            if (dev < 0.075f && corners >= 5 || dev < 0.05f) {
                if (Math.abs(rx - ry) < 0.12f * Math.max(rx, ry)) rx = ry = (rx + ry) / 2;
                int m = Math.max(24, Math.min(240, (int) (Math.PI * (rx + ry) / 2.5f)));
                float[] out = new float[(m + 1) * 2];
                for (int i = 0; i <= m; i++) {
                    double t = -Math.PI / 2 + 2 * Math.PI * i / m;
                    out[2 * i] = cx + rx * (float) Math.cos(t);
                    out[2 * i + 1] = cy + ry * (float) Math.sin(t);
                }
                return out;
            }
        }

        // polygons
        float[] poly = rdp(s, 0.07f * diag);
        int k = poly.length / 2;
        // drop the closing vertex if it sits on the first one
        if (k > 2 && Math.hypot(poly[2 * k - 2] - poly[0], poly[2 * k - 1] - poly[1]) < 0.2f * diag) k--;
        if (k == 3) {
            return densify(new float[]{poly[0], poly[1], poly[2], poly[3], poly[4], poly[5], poly[0], poly[1]}, true);
        }
        if (k == 4) {
            boolean axis = true;
            for (int i = 0; i < 4; i++) {
                float ax = poly[2 * i], ay = poly[2 * i + 1], bx = poly[2 * ((i + 1) % 4)], by = poly[2 * ((i + 1) % 4) + 1];
                float a = (float) Math.toDegrees(Math.atan2(by - ay, bx - ax));
                a = ((a % 90) + 90) % 90;
                if (a > 12 && a < 78) axis = false;
            }
            if (axis) return densify(new float[]{minX, minY, maxX, minY, maxX, maxY, minX, maxY, minX, minY}, true);
            return densify(new float[]{poly[0], poly[1], poly[2], poly[3], poly[4], poly[5], poly[6], poly[7], poly[0], poly[1]}, true);
        }
        return null;
    }

    /** Ramer–Douglas–Peucker on the stroke's points. */
    private static float[] rdp(Stroke s, float eps) {
        boolean[] keep = new boolean[s.n];
        keep[0] = keep[s.n - 1] = true;
        rdp(s, 0, s.n - 1, eps, keep);
        ArrayList<Float> out = new ArrayList<>();
        for (int i = 0; i < s.n; i++) if (keep[i]) { out.add(s.x(i)); out.add(s.y(i)); }
        float[] r = new float[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    private static void rdp(Stroke s, int a, int b, float eps, boolean[] keep) {
        if (b <= a + 1) return;
        float ax = s.x(a), ay = s.y(a), bx = s.x(b), by = s.y(b);
        float dx = bx - ax, dy = by - ay, l = (float) Math.hypot(dx, dy);
        int best = -1;
        float bd = 0;
        for (int i = a + 1; i < b; i++) {
            float d = l < 1e-3f ? (float) Math.hypot(s.x(i) - ax, s.y(i) - ay)
                    : Math.abs(dy * s.x(i) - dx * s.y(i) + bx * ay - by * ax) / l;
            if (d > bd) { bd = d; best = i; }
        }
        if (bd > eps) {
            keep[best] = true;
            rdp(s, a, best, eps, keep);
            rdp(s, best, b, eps, keep);
        }
    }

    /** Polyline → points every ~2.5 units, so the spline renderer keeps corners crisp. */
    static float[] densify(float[] v, boolean closed) {
        ArrayList<Float> out = new ArrayList<>();
        int m = v.length / 2;
        for (int i = 0; i + 1 < m; i++) {
            float ax = v[2 * i], ay = v[2 * i + 1], bx = v[2 * i + 2], by = v[2 * i + 3];
            int steps = Math.max(1, (int) (Math.hypot(bx - ax, by - ay) / 2.5f));
            for (int k = 0; k < steps; k++) {
                float t = k / (float) steps;
                out.add(ax + (bx - ax) * t);
                out.add(ay + (by - ay) * t);
            }
            // repeat the corner so the curve doesn't round it off
            if (i + 2 < m) { out.add(bx); out.add(by); }
        }
        out.add(v[2 * m - 2]);
        out.add(v[2 * m - 1]);
        float[] r = new float[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }
}

package app.nib.notes;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Editable vector shape & design model stored inside {@link Notebook.Item} (type = {@link Notebook.Item#SHAPE}).
 *
 * Coordinate convention:
 * - {@code item.x, item.y, item.w, item.h}: unrotated local geometry bounding box in page units.
 * - {@code item.pts}: page-local vertex / control / tail / freeform path coordinates (unrotated around center cx,cy).
 * - {@code rotation}: clockwise rotation in degrees around (cx, cy) = (item.x + item.w / 2, item.y + item.h / 2).
 * - {@code zOrder}: stacking order relative to handwritten ink on the same layer:
 *     zOrder < 0 => rendered BEHIND handwritten ink (DEFAULT = -1, preserving handwriting visibility!)
 *     zOrder > 0 => rendered IN FRONT OF handwritten ink (when user selects Bring to Front / Bring Forward)
 */
final class ShapeSpec {
    // ---- Categories & 14 shape kinds -------------------------------------------------------
    // A. Freeform
    static final String KIND_FREEFORM = "freeform";
    // B. Basic Geometric
    static final String KIND_RECT = "rect";
    static final String KIND_ROUND_RECT = "round_rect";
    static final String KIND_CIRCLE = "circle";
    static final String KIND_ELLIPSE = "ellipse";
    static final String KIND_TRIANGLE = "triangle";
    static final String KIND_POLYGON = "polygon";
    // C. Lines & Arrows
    static final String KIND_LINE = "line";
    static final String KIND_ARROW = "arrow";
    static final String KIND_DOUBLE_ARROW = "double_arrow";
    static final String KIND_CURVE = "curve";
    // D. Annotation & Special
    static final String KIND_SPEECH_BUBBLE = "speech_bubble";
    static final String KIND_BRACKET = "bracket";
    static final String KIND_BRACE = "brace";

    static final String[] ALL_KINDS = {
            KIND_FREEFORM,
            KIND_RECT, KIND_ROUND_RECT, KIND_CIRCLE, KIND_ELLIPSE, KIND_TRIANGLE, KIND_POLYGON,
            KIND_LINE, KIND_ARROW, KIND_DOUBLE_ARROW, KIND_CURVE,
            KIND_SPEECH_BUBBLE, KIND_BRACKET, KIND_BRACE
    };

    // ---- 4 Appearance / Fill Styles --------------------------------------------------------
    static final int STYLE_OUTLINE = 0;      // Transparent / Outline Only
    static final int STYLE_SOLID = 1;        // Solid Fill (100% opaque fill + border)
    static final int STYLE_TRANSPARENT = 2;  // Transparent Fill (adjustable opacity fill + border)
    static final int STYLE_CUSTOM = 3;       // Custom Outline + Fill (independent colours & opacities)

    static final String[] STYLE_NAMES = {"No fill", "Solid", "Translucent", "Custom"};

    /** Default zOrder places every newly created shape BEHIND handwritten ink. */
    static final int DEFAULT_Z_BEHIND_INK = -1;

    // ---- Handle type constants (for interactive editing) -----------------------------------
    static final int HANDLE_BOX_TL = 0;
    static final int HANDLE_BOX_TR = 1;
    static final int HANDLE_BOX_BR = 2;
    static final int HANDLE_BOX_BL = 3;
    static final int HANDLE_ROTATE = 4;
    static final int HANDLE_POINT_BASE = 10; // HANDLE_POINT_BASE + idx => vertex / endpoint / ctrl / tail

    // ---- Properties ------------------------------------------------------------------------
    String kind = KIND_RECT;
    int fillStyle = STYLE_OUTLINE;
    int strokeColor = 0xff2f5bff;   // opaque RGB (0xffRRGGBB)
    int strokeAlpha = 100;          // 0..100 %
    float strokeWidth = 3.8f;       // page units
    int fillColor = 0xfff5c400;     // opaque RGB (0xffRRGGBB)
    int fillAlpha = 0;              // 0..100 %
    float cornerRadius = 0.22f;     // 0..0.5 fraction of min(w,h)
    int sides = 5;                  // 3..12 (polygon)
    float arrowSize = 1.0f;         // 0.5..2.5 multiplier
    float rotation = 0f;            // degrees clockwise around center (cx, cy)
    boolean closed = false;         // freeform: true if closed path (eligible for fill)
    int zOrder = DEFAULT_Z_BEHIND_INK; // < 0 behind ink (default), > 0 in front of ink

    static boolean isValidKind(String k) {
        if (k == null) return false;
        for (String a : ALL_KINDS) if (a.equals(k)) return true;
        return false;
    }

    static String labelOf(String kind) {
        if (kind == null) return "Shape";
        switch (kind) {
            case KIND_FREEFORM: return "Freeform";
            case KIND_RECT: return "Rectangle";
            case KIND_ROUND_RECT: return "Rounded";
            case KIND_CIRCLE: return "Circle";
            case KIND_ELLIPSE: return "Ellipse";
            case KIND_TRIANGLE: return "Triangle";
            case KIND_POLYGON: return "Polygon";
            case KIND_LINE: return "Line";
            case KIND_ARROW: return "Arrow";
            case KIND_DOUBLE_ARROW: return "Double Arrow";
            case KIND_CURVE: return "Curve";
            case KIND_SPEECH_BUBBLE: return "Speech";
            case KIND_BRACKET: return "Bracket";
            case KIND_BRACE: return "Brace";
            default: return "Shape";
        }
    }

    boolean supportsFill() {
        switch (kind) {
            case KIND_RECT:
            case KIND_ROUND_RECT:
            case KIND_CIRCLE:
            case KIND_ELLIPSE:
            case KIND_TRIANGLE:
            case KIND_POLYGON:
            case KIND_SPEECH_BUBBLE:
                return true;
            case KIND_FREEFORM:
                return closed;
            default:
                return false;
        }
    }

    boolean isLineLike() {
        return KIND_LINE.equals(kind) || KIND_ARROW.equals(kind)
                || KIND_DOUBLE_ARROW.equals(kind) || KIND_CURVE.equals(kind);
    }

    boolean isArrow() {
        return KIND_ARROW.equals(kind) || KIND_DOUBLE_ARROW.equals(kind);
    }

    boolean hasCornerRadius() {
        return KIND_ROUND_RECT.equals(kind) || KIND_SPEECH_BUBBLE.equals(kind);
    }

    ShapeSpec copy() {
        ShapeSpec o = new ShapeSpec();
        o.kind = kind;
        o.fillStyle = fillStyle;
        o.strokeColor = strokeColor;
        o.strokeAlpha = strokeAlpha;
        o.strokeWidth = strokeWidth;
        o.fillColor = fillColor;
        o.fillAlpha = fillAlpha;
        o.cornerRadius = cornerRadius;
        o.sides = sides;
        o.arrowSize = arrowSize;
        o.rotation = rotation;
        o.closed = closed;
        o.zOrder = zOrder;
        return o;
    }

    // ---- Serialization (stored in Notebook.Item.style) -------------------------------------

    String encode() {
        JSONObject j = new JSONObject();
        try {
            j.put("k", kind);
            j.put("fs", fillStyle);
            j.put("sc", strokeColor);
            j.put("sa", strokeAlpha);
            j.put("sw", Math.round(strokeWidth * 100f) / 100.0);
            j.put("fc", fillColor);
            j.put("fa", fillAlpha);
            j.put("cr", Math.round(cornerRadius * 1000f) / 1000.0);
            j.put("sd", sides);
            j.put("as", Math.round(arrowSize * 100f) / 100.0);
            j.put("rot", Math.round(rotation * 100f) / 100.0);
            if (closed) j.put("cl", 1);
            j.put("z", zOrder);
        } catch (Exception ignored) { }
        return j.toString();
    }

    static ShapeSpec parse(String style) {
        ShapeSpec s = new ShapeSpec();
        if (style == null || style.isEmpty()) return s;
        try {
            JSONObject j = new JSONObject(style);
            String k = j.optString("k", KIND_RECT);
            s.kind = isValidKind(k) ? k : KIND_RECT;
            s.fillStyle = DrawPrefs.clamp(j.optInt("fs", STYLE_OUTLINE), 0, 3);
            s.strokeColor = 0xff000000 | (j.optInt("sc", 0xff2f5bff) & 0x00ffffff);
            s.strokeAlpha = DrawPrefs.clamp(j.optInt("sa", 100), 0, 100);
            s.strokeWidth = Math.max(0.5f, (float) j.optDouble("sw", 3.8));
            s.fillColor = 0xff000000 | (j.optInt("fc", s.strokeColor) & 0x00ffffff);
            s.fillAlpha = DrawPrefs.clamp(j.optInt("fa", s.fillStyle == STYLE_SOLID ? 100 : s.fillStyle == STYLE_OUTLINE ? 0 : 35), 0, 100);
            s.cornerRadius = DrawPrefs.clampF((float) j.optDouble("cr", 0.22), 0f, 0.5f);
            s.sides = DrawPrefs.clamp(j.optInt("sd", 5), 3, 12);
            s.arrowSize = DrawPrefs.clampF((float) j.optDouble("as", 1.0), 0.5f, 2.5f);
            s.rotation = (float) j.optDouble("rot", 0.0);
            s.closed = j.optInt("cl", 0) != 0;
            s.zOrder = j.optInt("z", DEFAULT_Z_BEHIND_INK);
            if (s.zOrder == 0) s.zOrder = DEFAULT_Z_BEHIND_INK; // 0 is reserved for ink layer boundary
        } catch (Exception ignored) { }
        return s;
    }

    static ShapeSpec of(Notebook.Item it) {
        if (it == null || it.type != Notebook.Item.SHAPE) return new ShapeSpec();
        return parse(it.style);
    }

    /** True if this shape renders behind handwritten ink (zOrder < 0, the default). */
    static boolean isBehindInk(Notebook.Item it) {
        if (it == null || it.type != Notebook.Item.SHAPE) return true;
        return parse(it.style).zOrder < 0;
    }

    static int zOrderOf(Notebook.Item it) {
        if (it == null || it.type != Notebook.Item.SHAPE) return DEFAULT_Z_BEHIND_INK;
        return parse(it.style).zOrder;
    }

    // ---- Factory methods for creating Notebook.Item(SHAPE) ---------------------------------

    /** Creates a drag-defined shape item from drag start (x0,y0) to current (x1,y1) in page-local coordinates. */
    static Notebook.Item createFromDrag(ShapeSpec base, float x0, float y0, float x1, float y1, boolean lockCircle, int layer) {
        ShapeSpec spec = base.copy();
        if (spec.isLineLike()) {
            if (KIND_CURVE.equals(spec.kind)) {
                float mx = (x0 + x1) * 0.5f, my = (y0 + y1) * 0.5f;
                float dx = x1 - x0, dy = y1 - y0, len = (float) Math.hypot(dx, dy);
                // Gentle default arc perpendicular to the chord so curvature is immediately visible & editable
                float nx = len > 1e-3f ? -dy / len : 0f;
                float ny = len > 1e-3f ? dx / len : -1f;
                float bulge = Math.min(len * 0.25f, 90f);
                float[] pts = {x0, y0, mx + nx * bulge, my + ny * bulge, x1, y1};
                return fromPoints(spec, pts, layer);
            }
            float[] pts = {x0, y0, x1, y1};
            return fromPoints(spec, pts, layer);
        }
        float l = Math.min(x0, x1), t = Math.min(y0, y1);
        float w = Math.abs(x1 - x0), h = Math.abs(y1 - y0);
        if (KIND_CIRCLE.equals(spec.kind) && lockCircle) {
            float d = Math.max(w, h);
            l = x1 >= x0 ? x0 : x0 - d;
            t = y1 >= y0 ? y0 : y0 - d;
            w = d;
            h = d;
        }
        w = Math.max(4f, w);
        h = Math.max(4f, h);

        float[] pts = null;
        if (KIND_TRIANGLE.equals(spec.kind)) {
            // Default isosceles triangle with 3 editable vertices
            pts = new float[]{l + w * 0.5f, t, l + w, t + h, l, t + h};
        } else if (KIND_POLYGON.equals(spec.kind)) {
            pts = regularPolygonPts(l, t, w, h, spec.sides);
        } else if (KIND_SPEECH_BUBBLE.equals(spec.kind)) {
            // Tail tip defaults below bottom-left of the bubble, freely draggable afterwards
            pts = new float[]{l + w * 0.22f, t + h + Math.max(24f, h * 0.28f)};
        }
        return new Notebook.Item(Notebook.Item.SHAPE, l, t, w, h, spec.kind, spec.strokeWidth, spec.strokeColor, pts, spec.encode()).lay(layer);
    }

    /** Creates an unrestricted Freeform editable shape from raw page-local points. Auto-detects closed paths. */
    static Notebook.Item createFreeform(ShapeSpec base, float[] rawPts, int count, int layer) {
        if (rawPts == null || count < 2) return null;
        // Light simplification so editable freeform shapes stay crisp without losing irregular fidelity
        float[] simp = simplifyFreeform(rawPts, count, 1.4f);
        if (simp.length < 4) return null;
        ShapeSpec spec = base.copy();
        spec.kind = KIND_FREEFORM;
        spec.closed = detectClosedPath(simp);
        return fromPoints(spec, simp, layer);
    }

    /** Auto-detects if a freeform path forms a closed shape (endpoints near each other relative to path size). */
    static boolean detectClosedPath(float[] pts) {
        if (pts == null || pts.length < 10) return false; // need at least 5 points
        int n = pts.length / 2;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float pathLen = 0f;
        for (int i = 0; i < n; i++) {
            float x = pts[2 * i], y = pts[2 * i + 1];
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            if (i > 0) pathLen += (float) Math.hypot(x - pts[2 * i - 2], y - pts[2 * i - 1]);
        }
        float diag = (float) Math.hypot(maxX - minX, maxY - minY);
        if (diag < 18f || pathLen < diag * 1.8f) return false;
        float endGap = (float) Math.hypot(pts[0] - pts[2 * n - 2], pts[1] - pts[2 * n - 1]);
        float thresh = Math.max(24f, Math.min(60f, diag * 0.22f));
        return endGap <= thresh;
    }

    private static float[] simplifyFreeform(float[] raw, int nPts, float minDist) {
        ArrayList<Float> out = new ArrayList<>(nPts * 2);
        float lx = raw[0], ly = raw[1];
        out.add(lx); out.add(ly);
        for (int i = 1; i < nPts; i++) {
            float x = raw[2 * i], y = raw[2 * i + 1];
            if (i == nPts - 1 || Math.hypot(x - lx, y - ly) >= minDist) {
                out.add(x); out.add(y);
                lx = x; ly = y;
            }
        }
        float[] res = new float[out.size()];
        for (int i = 0; i < res.length; i++) res[i] = out.get(i);
        return res;
    }

    static float[] regularPolygonPts(float l, float t, float w, float h, int sides) {
        int n = DrawPrefs.clamp(sides, 3, 12);
        float cx = l + w * 0.5f, cy = t + h * 0.5f;
        float rx = Math.max(2f, w * 0.5f), ry = Math.max(2f, h * 0.5f);
        float[] pts = new float[n * 2];
        double start = -Math.PI / 2.0; // flat-bottom or pointy-top regular polygon
        for (int i = 0; i < n; i++) {
            double a = start + (2.0 * Math.PI * i) / n;
            pts[2 * i] = cx + (float) Math.cos(a) * rx;
            pts[2 * i + 1] = cy + (float) Math.sin(a) * ry;
        }
        return pts;
    }

    static Notebook.Item fromPoints(ShapeSpec spec, float[] pts, int layer) {
        float l = Float.MAX_VALUE, t = Float.MAX_VALUE, r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
        for (int i = 0; i + 1 < pts.length; i += 2) {
            l = Math.min(l, pts[i]); r = Math.max(r, pts[i]);
            t = Math.min(t, pts[i + 1]); b = Math.max(b, pts[i + 1]);
        }
        float w = Math.max(4f, r - l);
        float h = Math.max(4f, b - t);
        return new Notebook.Item(Notebook.Item.SHAPE, l, t, w, h, spec.kind, spec.strokeWidth, spec.strokeColor, pts, spec.encode()).lay(layer);
    }

    /** Returns an updated Item with new ShapeSpec (also refreshes polygon vertices if sides changed). */
    static Notebook.Item withSpec(Notebook.Item it, ShapeSpec next) {
        float[] pts = it.pts;
        if (KIND_POLYGON.equals(next.kind)) {
            ShapeSpec old = of(it);
            if (old.sides != next.sides || pts == null || pts.length != next.sides * 2) {
                pts = regularPolygonPts(it.x, it.y, it.w, it.h, next.sides);
            }
        }
        return new Notebook.Item(Notebook.Item.SHAPE, it.x, it.y, it.w, it.h, next.kind, next.strokeWidth, next.strokeColor, pts, next.encode()).lay(it.layer);
    }

    // ---- Bounds & Coordinate Transforms ----------------------------------------------------

    /**
     * Axis-aligned bounding rectangle in page coordinates enclosing the rotated shape,
     * stroke width, arrowheads, and speech-bubble pointer tail.
     */
    static RectF renderBounds(Notebook.Item it) {
        ShapeSpec s = of(it);
        float cx = it.x + it.w * 0.5f, cy = it.y + it.h * 0.5f;
        float pad = Math.max(8f, s.strokeWidth * 2.5f);
        if (s.isArrow()) pad += arrowLength(s) * 1.4f;

        RectF local = new RectF(it.x, it.y, it.x + it.w, it.y + it.h);
        if (it.pts != null) {
            for (int i = 0; i + 1 < it.pts.length; i += 2) {
                local.left = Math.min(local.left, it.pts[i]);
                local.right = Math.max(local.right, it.pts[i]);
                local.top = Math.min(local.top, it.pts[i + 1]);
                local.bottom = Math.max(local.bottom, it.pts[i + 1]);
            }
        }
        local.inset(-pad, -pad);
        if (Math.abs(s.rotation) < 0.01f) return local;
        Matrix m = new Matrix();
        m.setRotate(s.rotation, cx, cy);
        RectF out = new RectF();
        m.mapRect(out, local);
        return out;
    }

    static float[] toLocal(Notebook.Item it, float px, float py) {
        ShapeSpec s = of(it);
        if (Math.abs(s.rotation) < 0.01f) return new float[]{px, py};
        float cx = it.x + it.w * 0.5f, cy = it.y + it.h * 0.5f;
        double rad = Math.toRadians(-s.rotation);
        float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
        float dx = px - cx, dy = py - cy;
        return new float[]{cx + dx * cos - dy * sin, cy + dx * sin + dy * cos};
    }

    static float[] toPage(Notebook.Item it, float lx, float ly) {
        ShapeSpec s = of(it);
        if (Math.abs(s.rotation) < 0.01f) return new float[]{lx, ly};
        float cx = it.x + it.w * 0.5f, cy = it.y + it.h * 0.5f;
        double rad = Math.toRadians(s.rotation);
        float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
        float dx = lx - cx, dy = ly - cy;
        return new float[]{cx + dx * cos - dy * sin, cy + dx * sin + dy * cos};
    }

    // ---- Interactive Handles ---------------------------------------------------------------

    static final class Handle {
        final int id;
        final float x, y; // page-local coordinates
        Handle(int id, float x, float y) { this.id = id; this.x = x; this.y = y; }
    }

    /** Returns all interactive handles for a selected shape item in page-local coordinates. */
    static ArrayList<Handle> handles(Notebook.Item it, float zoom) {
        ArrayList<Handle> out = new ArrayList<>();
        ShapeSpec s = of(it);
        float cx = it.x + it.w * 0.5f;
        float rotGap = Math.max(22f, 28f / Math.max(0.25f, zoom));

        if (s.isLineLike() && it.pts != null && it.pts.length >= 4) {
            // Endpoints (and middle control point for Curve)
            int n = it.pts.length / 2;
            for (int i = 0; i < n; i++) {
                float[] p = toPage(it, it.pts[2 * i], it.pts[2 * i + 1]);
                out.add(new Handle(HANDLE_POINT_BASE + i, p[0], p[1]));
            }
            return out;
        }

        // 4 box resize corners (rotated with the shape)
        float[][] corners = {
                {it.x, it.y},
                {it.x + it.w, it.y},
                {it.x + it.w, it.y + it.h},
                {it.x, it.y + it.h}
        };
        for (int i = 0; i < 4; i++) {
            float[] p = toPage(it, corners[i][0], corners[i][1]);
            out.add(new Handle(i, p[0], p[1]));
        }

        // Top rotation knob
        float[] rp = toPage(it, cx, it.y - rotGap);
        out.add(new Handle(HANDLE_ROTATE, rp[0], rp[1]));

        // Extra shape-specific vertex / tail handles
        if ((KIND_TRIANGLE.equals(s.kind) || KIND_POLYGON.equals(s.kind)) && it.pts != null) {
            int n = it.pts.length / 2;
            for (int i = 0; i < n; i++) {
                float[] p = toPage(it, it.pts[2 * i], it.pts[2 * i + 1]);
                out.add(new Handle(HANDLE_POINT_BASE + i, p[0], p[1]));
            }
        } else if (KIND_SPEECH_BUBBLE.equals(s.kind) && it.pts != null && it.pts.length >= 2) {
            float[] p = toPage(it, it.pts[0], it.pts[1]);
            out.add(new Handle(HANDLE_POINT_BASE, p[0], p[1]));
        }
        return out;
    }

    /** Applies a handle drag in page-local coordinates and returns the updated immutable Item. */
    static Notebook.Item dragHandle(Notebook.Item orig, int handleId, float px, float py, boolean lockCircle) {
        ShapeSpec s = of(orig);
        if (handleId == HANDLE_ROTATE) {
            float cx = orig.x + orig.w * 0.5f, cy = orig.y + orig.h * 0.5f;
            float deg = (float) Math.toDegrees(Math.atan2(py - cy, px - cx)) + 90f;
            // Gentle 15-degree snap if within 3 degrees
            float nearest = Math.round(deg / 15f) * 15f;
            if (Math.abs(deg - nearest) < 3f) deg = nearest;
            deg = ((deg % 360f) + 360f) % 360f;
            ShapeSpec ns = s.copy();
            ns.rotation = deg;
            return withSpec(orig, ns);
        }

        // Point handle: line/arrow endpoint, curve control point, triangle/polygon vertex, or speech bubble tail
        if (handleId >= HANDLE_POINT_BASE && orig.pts != null) {
            int idx = handleId - HANDLE_POINT_BASE;
            if (idx * 2 + 1 >= orig.pts.length) return orig;
            float[] loc = toLocal(orig, px, py);
            float[] np = orig.pts.clone();
            np[idx * 2] = loc[0];
            np[idx * 2 + 1] = loc[1];

            if (KIND_SPEECH_BUBBLE.equals(s.kind)) {
                return new Notebook.Item(Notebook.Item.SHAPE, orig.x, orig.y, orig.w, orig.h, orig.text, orig.size, orig.color, np, orig.style).lay(orig.layer);
            }
            if (s.isLineLike() && Math.abs(s.rotation) < 0.01f) {
                // Snap horizontal/vertical/45 deg for straight line / arrow endpoints
                if ((KIND_LINE.equals(s.kind) || s.isArrow()) && np.length == 4) {
                    int other = idx == 0 ? 1 : 0;
                    float ox = np[other * 2], oy = np[other * 2 + 1];
                    float dx = np[idx * 2] - ox, dy = np[idx * 2 + 1] - oy;
                    float len = (float) Math.hypot(dx, dy);
                    double ang = Math.toDegrees(Math.atan2(dy, dx));
                    double near = Math.round(ang / 45.0) * 45.0;
                    if (Math.abs(ang - near) < 4.0 && len > 10f) {
                        double rad = Math.toRadians(near);
                        np[idx * 2] = ox + (float) Math.cos(rad) * len;
                        np[idx * 2 + 1] = oy + (float) Math.sin(rad) * len;
                    }
                }
                return fromPoints(s, np, orig.layer);
            }
            // Triangle / Polygon vertex edit: recompute local bounds while keeping unrotated frame consistent
            return fromPoints(s, np, orig.layer);
        }

        // Corner resize handle (0=TL, 1=TR, 2=BR, 3=BL) in shape's local (unrotated) coordinate frame
        if (handleId >= 0 && handleId <= 3) {
            float[] loc = toLocal(orig, px, py);
            float l = orig.x, t = orig.y, r = orig.x + orig.w, b = orig.y + orig.h;
            float min = 12f;
            boolean left = (handleId == HANDLE_BOX_TL || handleId == HANDLE_BOX_BL);
            boolean top = (handleId == HANDLE_BOX_TL || handleId == HANDLE_BOX_TR);
            float nl = left ? Math.min(loc[0], r - min) : l;
            float nr = left ? r : Math.max(loc[0], l + min);
            float nt = top ? Math.min(loc[1], b - min) : t;
            float nb = top ? b : Math.max(loc[1], t + min);

            if (KIND_CIRCLE.equals(s.kind) && lockCircle) {
                float nw = nr - nl, nh = nb - nt;
                float d = Math.max(nw, nh);
                if (left) nl = r - d; else nr = l + d;
                if (top) nt = b - d; else nb = t + d;
            }

            float nw = Math.max(min, nr - nl), nh = Math.max(min, nb - nt);
            // Keep center shift mapped cleanly back to page space if rotated
            float oldCx = orig.x + orig.w * 0.5f, oldCy = orig.y + orig.h * 0.5f;
            float newLocalCx = nl + nw * 0.5f, newLocalCy = nt + nh * 0.5f;
            float[] newPageC = toPage(orig, newLocalCx, newLocalCy);
            float shiftX = newPageC[0] - newLocalCx;
            float shiftY = newPageC[1] - newLocalCy;

            float sx = orig.w > 1e-3f ? nw / orig.w : 1f;
            float sy = orig.h > 1e-3f ? nh / orig.h : 1f;
            float[] np = null;
            if (orig.pts != null) {
                np = new float[orig.pts.length];
                for (int i = 0; i + 1 < orig.pts.length; i += 2) {
                    np[i] = nl + (orig.pts[i] - orig.x) * sx + shiftX;
                    np[i + 1] = nt + (orig.pts[i + 1] - orig.y) * sy + shiftY;
                }
            }
            return new Notebook.Item(Notebook.Item.SHAPE, nl + shiftX, nt + shiftY, nw, nh,
                    orig.text, orig.size, orig.color, np, orig.style).lay(orig.layer);
        }
        return orig;
    }

    // ---- Hit Testing -----------------------------------------------------------------------

    static boolean hitTest(Notebook.Item it, float px, float py, float pad) {
        if (it == null || it.type != Notebook.Item.SHAPE) return false;
        ShapeSpec s = of(it);
        float[] loc = toLocal(it, px, py);
        float x = loc[0], y = loc[1];
        float tol = Math.max(12f, s.strokeWidth * 1.5f + pad);

        if (s.isLineLike() && it.pts != null && it.pts.length >= 4) {
            if (KIND_CURVE.equals(s.kind) && it.pts.length >= 6) {
                return hitQuadCurve(it.pts[0], it.pts[1], it.pts[2], it.pts[3], it.pts[4], it.pts[5], x, y, tol);
            }
            return distToSegment(x, y, it.pts[0], it.pts[1], it.pts[2], it.pts[3]) <= tol;
        }

        if (KIND_FREEFORM.equals(s.kind) && it.pts != null && it.pts.length >= 4) {
            int n = it.pts.length / 2;
            for (int i = 0; i + 1 < n; i++) {
                if (distToSegment(x, y, it.pts[2 * i], it.pts[2 * i + 1], it.pts[2 * i + 2], it.pts[2 * i + 3]) <= tol) return true;
            }
            if (s.closed) {
                if (distToSegment(x, y, it.pts[2 * n - 2], it.pts[2 * n - 1], it.pts[0], it.pts[1]) <= tol) return true;
                if (s.fillStyle != STYLE_OUTLINE && pointInPolygon(x, y, it.pts)) return true;
            }
            return false;
        }

        if ((KIND_TRIANGLE.equals(s.kind) || KIND_POLYGON.equals(s.kind)) && it.pts != null && it.pts.length >= 6) {
            int n = it.pts.length / 2;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                if (distToSegment(x, y, it.pts[2 * i], it.pts[2 * i + 1], it.pts[2 * j], it.pts[2 * j + 1]) <= tol) return true;
            }
            return pointInPolygon(x, y, it.pts);
        }

        if (KIND_SPEECH_BUBBLE.equals(s.kind)) {
            if (x >= it.x - tol && x <= it.x + it.w + tol && y >= it.y - tol && y <= it.y + it.h + tol) return true;
            if (it.pts != null && it.pts.length >= 2) {
                float cx = it.x + it.w * 0.5f, cy = it.y + it.h * 0.5f;
                if (distToSegment(x, y, cx, cy, it.pts[0], it.pts[1]) <= tol * 1.4f) return true;
            }
            return false;
        }

        // Rect, RoundRect, Circle, Ellipse, Bracket, Brace
        return x >= it.x - tol && x <= it.x + it.w + tol && y >= it.y - tol && y <= it.y + it.h + tol;
    }

    /**
     * Border-only hit test for closed shapes (used in Pen/Highlighter mode so dragging the outline
     * moves the shape while writing inside an open area deselects and lets the user label inside).
     */
    static boolean hitBorder(Notebook.Item it, float px, float py, float pad) {
        if (it == null || it.type != Notebook.Item.SHAPE) return false;
        ShapeSpec s = of(it);
        if (s.isLineLike() || KIND_FREEFORM.equals(s.kind) || KIND_BRACKET.equals(s.kind) || KIND_BRACE.equals(s.kind)) {
            return hitTest(it, px, py, pad);
        }
        float[] loc = toLocal(it, px, py);
        float x = loc[0], y = loc[1];
        float tol = Math.max(14f, s.strokeWidth * 1.5f + pad);

        if ((KIND_TRIANGLE.equals(s.kind) || KIND_POLYGON.equals(s.kind)) && it.pts != null && it.pts.length >= 6) {
            int n = it.pts.length / 2;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                if (distToSegment(x, y, it.pts[2 * i], it.pts[2 * i + 1], it.pts[2 * j], it.pts[2 * j + 1]) <= tol) return true;
            }
            return false;
        }

        if (KIND_CIRCLE.equals(s.kind) || KIND_ELLIPSE.equals(s.kind)) {
            float cx = it.x + it.w * 0.5f, cy = it.y + it.h * 0.5f;
            float rx = it.w * 0.5f, ry = it.h * 0.5f;
            float dx = x - cx, dy = y - cy;
            float orx = rx + tol, ory = ry + tol;
            if ((dx * dx) / (orx * orx) + (dy * dy) / (ory * ory) > 1f) return false;
            float irx = rx - tol, iry = ry - tol;
            if (irx <= 2f || iry <= 2f) return true;
            return (dx * dx) / (irx * irx) + (dy * dy) / (iry * iry) >= 1f;
        }

        if (KIND_SPEECH_BUBBLE.equals(s.kind) && it.pts != null && it.pts.length >= 2) {
            float cx = it.x + it.w * 0.5f, cy = it.y + it.h * 0.5f;
            if (distToSegment(x, y, cx, cy, it.pts[0], it.pts[1]) <= tol * 1.4f) return true;
        }

        // Rect, RoundRect, SpeechBubble frame border
        boolean inOuter = x >= it.x - tol && x <= it.x + it.w + tol && y >= it.y - tol && y <= it.y + it.h + tol;
        if (!inOuter) return false;
        if (it.w <= tol * 2f || it.h <= tol * 2f) return true;
        boolean inInner = x > it.x + tol && x < it.x + it.w - tol && y > it.y + tol && y < it.y + it.h - tol;
        return !inInner;
    }

    private static boolean hitQuadCurve(float x0, float y0, float cx, float cy, float x1, float y1, float px, float py, float tol) {
        float prevX = x0, prevY = y0;
        int steps = 20;
        for (int i = 1; i <= steps; i++) {
            float t = i / (float) steps, mt = 1f - t;
            float qx = mt * mt * x0 + 2 * mt * t * cx + t * t * x1;
            float qy = mt * mt * y0 + 2 * mt * t * cy + t * t * y1;
            if (distToSegment(px, py, prevX, prevY, qx, qy) <= tol) return true;
            prevX = qx; prevY = qy;
        }
        return false;
    }

    private static float distToSegment(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
        float h = l2 > 1e-6f ? Math.max(0f, Math.min(1f, ((px - ax) * dx + (py - ay) * dy) / l2)) : 0f;
        return (float) Math.hypot(px - (ax + dx * h), py - (ay + dy * h));
    }

    private static boolean pointInPolygon(float px, float py, float[] pts) {
        int n = pts.length / 2;
        boolean in = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            float xi = pts[2 * i], yi = pts[2 * i + 1];
            float xj = pts[2 * j], yj = pts[2 * j + 1];
            boolean intersect = ((yi > py) != (yj > py))
                    && (px < (xj - xi) * (py - yi) / ((yj - yi) == 0 ? 1e-6f : (yj - yi)) + xi);
            if (intersect) in = !in;
        }
        return in;
    }

    // ---- Layer Stacking Order Operations ---------------------------------------------------

    static final int Z_BRING_TO_FRONT = 0;
    static final int Z_BRING_FORWARD = 1;
    static final int Z_SEND_BACKWARD = 2;
    static final int Z_SEND_TO_BACK = 3;

    /**
     * Reorders {@code target} inside {@code pageItems} according to {@code op} (Bring to Front,
     * Bring Forward, Send Backward, Send to Back), updating sibling zOrders in {@code pageItems}
     * so every single step between shapes and across the handwriting boundary is exact and deterministic.
     *
     * Rules:
     * - zOrder < 0 => behind handwritten ink (default = -1)
     * - zOrder > 0 => in front of handwritten ink
     * - Bring to Front: moves target to the top of front-of-ink (above handwriting & all shapes).
     * - Send to Back: moves target to the bottom of behind-ink (behind handwriting & all shapes).
     * - Bring Forward: steps one slot forward; when topmost behind ink, crosses above handwriting.
     * - Send Backward: steps one slot backward; when bottommost in front of ink, crosses behind handwriting.
     */
    static Notebook.Item reorder(List<Notebook.Item> pageItems, Notebook.Item target, int op) {
        if (pageItems == null || target == null || target.type != Notebook.Item.SHAPE) return target;
        if (!pageItems.contains(target)) return target;

        ArrayList<Notebook.Item> behind = new ArrayList<>();
        ArrayList<Notebook.Item> front = new ArrayList<>();
        for (Notebook.Item it : pageItems) {
            if (it.type != Notebook.Item.SHAPE || it.layer != target.layer) continue;
            if (isBehindInk(it)) behind.add(it);
            else front.add(it);
        }
        behind.sort((a, b) -> Integer.compare(zOrderOf(a), zOrderOf(b)));
        front.sort((a, b) -> Integer.compare(zOrderOf(a), zOrderOf(b)));

        int bIdx = behind.indexOf(target);
        int fIdx = front.indexOf(target);
        boolean changed = false;

        switch (op) {
            case Z_BRING_TO_FRONT: {
                if (bIdx >= 0) {
                    behind.remove(bIdx);
                    front.add(target);
                    changed = true;
                } else if (fIdx >= 0 && fIdx < front.size() - 1) {
                    front.remove(fIdx);
                    front.add(target);
                    changed = true;
                }
                break;
            }
            case Z_SEND_TO_BACK: {
                if (fIdx >= 0) {
                    front.remove(fIdx);
                    behind.add(0, target);
                    changed = true;
                } else if (bIdx > 0) {
                    behind.remove(bIdx);
                    behind.add(0, target);
                    changed = true;
                }
                break;
            }
            case Z_BRING_FORWARD: {
                if (bIdx >= 0) {
                    if (bIdx < behind.size() - 1) {
                        behind.remove(bIdx);
                        behind.add(bIdx + 1, target);
                    } else {
                        // Topmost behind handwriting -> cross in front of handwriting
                        behind.remove(bIdx);
                        front.add(0, target);
                    }
                    changed = true;
                } else if (fIdx >= 0 && fIdx < front.size() - 1) {
                    front.remove(fIdx);
                    front.add(fIdx + 1, target);
                    changed = true;
                }
                break;
            }
            case Z_SEND_BACKWARD: {
                if (fIdx >= 0) {
                    if (fIdx > 0) {
                        front.remove(fIdx);
                        front.add(fIdx - 1, target);
                    } else {
                        // Bottommost in front of handwriting -> cross behind handwriting
                        front.remove(fIdx);
                        behind.add(target);
                    }
                    changed = true;
                } else if (bIdx > 0) {
                    behind.remove(bIdx);
                    behind.add(bIdx - 1, target);
                    changed = true;
                }
                break;
            }
            default:
                return target;
        }
        if (!changed) return target;

        Notebook.Item updatedTarget = target;
        int nb = behind.size();
        for (int i = 0; i < nb; i++) {
            Notebook.Item orig = behind.get(i);
            int wantZ = -(nb - i); // -nb .. -1 (strictly < 0, ascending)
            ShapeSpec sp = of(orig);
            if (sp.zOrder != wantZ || orig == target) {
                ShapeSpec ns = sp.copy();
                ns.zOrder = wantZ;
                Notebook.Item rep = withSpec(orig, ns);
                int pi = pageItems.indexOf(orig);
                if (pi >= 0) pageItems.set(pi, rep);
                if (orig == target) updatedTarget = rep;
            }
        }
        int nf = front.size();
        for (int i = 0; i < nf; i++) {
            Notebook.Item orig = front.get(i);
            int wantZ = i + 1; // +1 .. +nf (strictly > 0, ascending)
            ShapeSpec sp = of(orig);
            if (sp.zOrder != wantZ || orig == target) {
                ShapeSpec ns = sp.copy();
                ns.zOrder = wantZ;
                Notebook.Item rep = withSpec(orig, ns);
                int pi = pageItems.indexOf(orig);
                if (pi >= 0) pageItems.set(pi, rep);
                if (orig == target) updatedTarget = rep;
            }
        }
        return updatedTarget;
    }

    /** Returns shapes in stable ascending zOrder for either behind-ink (behind == true) or front-of-ink (behind == false). */
    static ArrayList<Notebook.Item> filterAndSort(List<Notebook.Item> items, boolean behindInk) {
        ArrayList<Notebook.Item> out = new ArrayList<>();
        for (Notebook.Item it : items) {
            if (it.type != Notebook.Item.SHAPE) continue;
            boolean isBehind = isBehindInk(it);
            if (isBehind == behindInk) out.add(it);
        }
        out.sort((a, b) -> Integer.compare(zOrderOf(a), zOrderOf(b)));
        return out;
    }

    // ---- Vector Rendering (Canvas, PDF, PNG, & GPU Texture Bitmap) -------------------------

    private static float arrowLength(ShapeSpec s) {
        return Math.max(12f, s.strokeWidth * 3.8f) * s.arrowSize;
    }

    /** Renders a SHAPE item directly in page coordinates onto Canvas (used for PDF/PNG export AND live overlay). */
    static void draw(Canvas c, Notebook.Item it, boolean darkPaper) {
        if (it == null || it.type != Notebook.Item.SHAPE) return;
        ShapeSpec s = of(it);
        int saved = c.save();
        float cx = it.x + it.w * 0.5f, cy = it.y + it.h * 0.5f;
        if (Math.abs(s.rotation) >= 0.01f) c.rotate(s.rotation, cx, cy);

        // Effective fill & stroke paints according to the 4 Appearance Styles
        boolean doFill = s.supportsFill() && s.fillStyle != STYLE_OUTLINE;
        int effFillAlpha = s.fillStyle == STYLE_SOLID ? 100 : s.fillStyle == STYLE_OUTLINE ? 0 : s.fillAlpha;
        int effFillRgb = s.fillColor;
        int effStrokeRgb = Render.inkColor(s.strokeColor, darkPaper);
        int effStrokeAlpha = s.strokeAlpha;

        Paint fillP = null;
        if (doFill && effFillAlpha > 0) {
            fillP = new Paint(Paint.ANTI_ALIAS_FLAG);
            fillP.setStyle(Paint.Style.FILL);
            fillP.setColor(DrawPrefs.withOpacity(effFillRgb, effFillAlpha));
        }

        Paint strokeP = null;
        if (effStrokeAlpha > 0 && s.strokeWidth > 0f) {
            strokeP = new Paint(Paint.ANTI_ALIAS_FLAG);
            strokeP.setStyle(Paint.Style.STROKE);
            strokeP.setStrokeWidth(s.strokeWidth);
            strokeP.setStrokeCap(Paint.Cap.ROUND);
            strokeP.setStrokeJoin(Paint.Join.ROUND);
            strokeP.setColor(DrawPrefs.withOpacity(effStrokeRgb, effStrokeAlpha));
        }

        Path path = buildPath(it, s);
        if (path != null) {
            if (fillP != null) c.drawPath(path, fillP);
            if (strokeP != null) c.drawPath(path, strokeP);
        }

        // Arrowheads (drawn filled or crisp stroked in stroke colour & opacity)
        if (s.isArrow() && it.pts != null && it.pts.length >= 4 && strokeP != null) {
            float x0 = it.pts[0], y0 = it.pts[1], x1 = it.pts[2], y1 = it.pts[3];
            drawArrowHead(c, x0, y0, x1, y1, s, strokeP);
            if (KIND_DOUBLE_ARROW.equals(s.kind)) {
                drawArrowHead(c, x1, y1, x0, y0, s, strokeP);
            }
        }

        c.restoreToCount(saved);
    }

    private static Path buildPath(Notebook.Item it, ShapeSpec s) {
        Path p = new Path();
        float l = it.x, t = it.y, r = it.x + it.w, b = it.y + it.h;
        float w = it.w, h = it.h;

        switch (s.kind) {
            case KIND_RECT:
                p.addRect(l, t, r, b, Path.Direction.CW);
                return p;

            case KIND_ROUND_RECT: {
                float rad = Math.min(w, h) * DrawPrefs.clampF(s.cornerRadius, 0f, 0.5f);
                p.addRoundRect(l, t, r, b, rad, rad, Path.Direction.CW);
                return p;
            }

            case KIND_CIRCLE:
            case KIND_ELLIPSE:
                p.addOval(l, t, r, b, Path.Direction.CW);
                return p;

            case KIND_TRIANGLE:
            case KIND_POLYGON: {
                float[] pts = it.pts;
                if (pts == null || pts.length < 6) {
                    pts = KIND_TRIANGLE.equals(s.kind)
                            ? new float[]{l + w * 0.5f, t, r, b, l, b}
                            : regularPolygonPts(l, t, w, h, s.sides);
                }
                p.moveTo(pts[0], pts[1]);
                for (int i = 2; i + 1 < pts.length; i += 2) p.lineTo(pts[i], pts[i + 1]);
                p.close();
                return p;
            }

            case KIND_FREEFORM: {
                float[] pts = it.pts;
                if (pts == null || pts.length < 4) return null;
                int n = pts.length / 2;
                p.moveTo(pts[0], pts[1]);
                if (n == 2) {
                    p.lineTo(pts[2], pts[3]);
                } else {
                    for (int i = 1; i < n - 1; i++) {
                        float mx = (pts[2 * i] + pts[2 * i + 2]) * 0.5f;
                        float my = (pts[2 * i + 1] + pts[2 * i + 3]) * 0.5f;
                        p.quadTo(pts[2 * i], pts[2 * i + 1], mx, my);
                    }
                    p.lineTo(pts[2 * n - 2], pts[2 * n - 1]);
                }
                if (s.closed) p.close();
                return p;
            }

            case KIND_LINE:
            case KIND_ARROW:
            case KIND_DOUBLE_ARROW: {
                if (it.pts == null || it.pts.length < 4) return null;
                p.moveTo(it.pts[0], it.pts[1]);
                p.lineTo(it.pts[2], it.pts[3]);
                return p;
            }

            case KIND_CURVE: {
                if (it.pts == null || it.pts.length < 6) return null;
                p.moveTo(it.pts[0], it.pts[1]);
                p.quadTo(it.pts[2], it.pts[3], it.pts[4], it.pts[5]);
                return p;
            }

            case KIND_SPEECH_BUBBLE:
                return buildSpeechBubblePath(l, t, r, b, s.cornerRadius, it.pts);

            case KIND_BRACKET:
                return buildBracketPath(l, t, r, b);

            case KIND_BRACE:
                return buildBracePath(l, t, r, b);

            default:
                p.addRect(l, t, r, b, Path.Direction.CW);
                return p;
        }
    }

    /**
     * Builds a clean unified speech bubble path: rounded rectangle fused seamlessly with a pointer triangle
     * anchored on the closest edge facing the customizable tail point {@code pts[0], pts[1]}.
     */
    private static Path buildSpeechBubblePath(float l, float t, float r, float b, float cornerFrac, float[] pts) {
        float w = r - l, h = b - t;
        float rad = Math.min(w, h) * DrawPrefs.clampF(cornerFrac, 0.06f, 0.4f);
        Path body = new Path();
        body.addRoundRect(l, t, r, b, rad, rad, Path.Direction.CW);
        if (pts == null || pts.length < 2) return body;

        float tx = pts[0], ty = pts[1];
        float cx = (l + r) * 0.5f, cy = (t + b) * 0.5f;
        float dx = tx - cx, dy = ty - cy;
        float dist = (float) Math.hypot(dx, dy);
        if (dist < 1f) return body;

        // Anchor the pointer base slightly inside the bubble center-line so Path.Op.UNION fuses seamlessly without gaps
        float nx = -dy / dist, ny = dx / dist;
        float halfBase = Math.max(8f, Math.min(26f, Math.min(w, h) * 0.14f));
        // Base midpoint sits partway from center toward the edge
        float bx = Math.max(l + rad, Math.min(r - rad, cx + dx * 0.35f));
        float by = Math.max(t + rad, Math.min(b - rad, cy + dy * 0.35f));

        Path tail = new Path();
        tail.moveTo(tx, ty);
        tail.lineTo(bx + nx * halfBase, by + ny * halfBase);
        tail.lineTo(bx - nx * halfBase, by - ny * halfBase);
        tail.close();

        Path combined = new Path();
        combined.op(body, tail, Path.Op.UNION);
        return combined;
    }

    /** Square bracket `[` oriented vertically when h >= w, horizontally when w > h (and rotatable to any angle!). */
    private static Path buildBracketPath(float l, float t, float r, float b) {
        Path p = new Path();
        float w = r - l, h = b - t;
        if (h >= w) {
            float arm = Math.min(w, Math.max(10f, h * 0.18f));
            p.moveTo(l + arm, t);
            p.lineTo(l, t);
            p.lineTo(l, b);
            p.lineTo(l + arm, b);
        } else {
            float arm = Math.min(h, Math.max(10f, w * 0.18f));
            p.moveTo(l, t + arm);
            p.lineTo(l, t);
            p.lineTo(r, t);
            p.lineTo(r, t + arm);
        }
        return p;
    }

    /** Smooth typographic curly brace `{` oriented vertically when h >= w, horizontally when w > h (rotatable!). */
    private static Path buildBracePath(float l, float t, float r, float b) {
        Path p = new Path();
        float w = r - l, h = b - t;
        if (h >= w) {
            float midY = (t + b) * 0.5f;
            float midX = l + w * 0.5f;
            float tipX = l;
            float endX = r;
            float q = Math.min(h * 0.18f, w * 1.1f);
            p.moveTo(endX, t);
            p.cubicTo(midX, t, midX, t + q, midX, t + q * 1.5f);
            p.lineTo(midX, midY - q);
            p.cubicTo(midX, midY - q * 0.2f, tipX + w * 0.15f, midY, tipX, midY);
            p.cubicTo(tipX + w * 0.15f, midY, midX, midY + q * 0.2f, midX, midY + q);
            p.lineTo(midX, b - q * 1.5f);
            p.cubicTo(midX, b - q, midX, b, endX, b);
        } else {
            float midX = (l + r) * 0.5f;
            float midY = t + h * 0.5f;
            float tipY = t;
            float endY = b;
            float q = Math.min(w * 0.18f, h * 1.1f);
            p.moveTo(l, endY);
            p.cubicTo(l, midY, l + q, midY, l + q * 1.5f, midY);
            p.lineTo(midX - q, midY);
            p.cubicTo(midX - q * 0.2f, midY, midX, tipY + h * 0.15f, midX, tipY);
            p.cubicTo(midX, tipY + h * 0.15f, midX + q * 0.2f, midY, midX + q, midY);
            p.lineTo(r - q * 1.5f, midY);
            p.cubicTo(r - q, midY, r, midY, r, endY);
        }
        return p;
    }

    private static void drawArrowHead(Canvas c, float x0, float y0, float x1, float y1, ShapeSpec s, Paint strokeP) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.hypot(dx, dy);
        if (len < 1e-3f) return;
        float ux = dx / len, uy = dy / len;
        float nx = -uy, ny = ux;
        float headLen = arrowLength(s);
        float headHalfW = headLen * 0.52f;
        float bx = x1 - ux * headLen, by = y1 - uy * headLen;
        Path head = new Path();
        head.moveTo(bx + nx * headHalfW, by + ny * headHalfW);
        head.lineTo(x1, y1);
        head.lineTo(bx - nx * headHalfW, by - ny * headHalfW);
        c.drawPath(head, strokeP);
    }

    /** Rasterises a SHAPE item into a transparent ARGB_8888 bitmap for GPU texture caching. */
    static Bitmap toBitmap(Notebook.Item it, int scale, boolean darkPaper) {
        RectF rb = renderBounds(it);
        int bw = Math.max(2, Math.min(4096, (int) Math.ceil(rb.width() * scale)));
        int bh = Math.max(2, Math.min(4096, (int) Math.ceil(rb.height() * scale)));
        float sx = bw / Math.max(1f, rb.width());
        float sy = bh / Math.max(1f, rb.height());
        Bitmap bm = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        c.scale(sx, sy);
        c.translate(-rb.left, -rb.top);
        draw(c, it, darkPaper);
        return bm;
    }

    /** Fast bounded-size rasterisation of a live SHAPE preview for real-time recording frames. */
    static Bitmap toPreviewBitmap(Notebook.Item it, float scale, int maxDim, boolean darkPaper) {
        RectF rb = renderBounds(it);
        float rw = Math.max(1f, rb.width()), rh = Math.max(1f, rb.height());
        float sc = Math.max(0.5f, scale);
        float maxSide = Math.max(rw, rh) * sc;
        if (maxSide > maxDim) sc *= maxDim / maxSide;
        int bw = Math.max(2, Math.min(maxDim, (int) Math.ceil(rw * sc)));
        int bh = Math.max(2, Math.min(maxDim, (int) Math.ceil(rh * sc)));
        float sx = bw / rw;
        float sy = bh / rh;
        Bitmap bm = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        c.scale(sx, sy);
        c.translate(-rb.left, -rb.top);
        draw(c, it, darkPaper);
        return bm;
    }
}

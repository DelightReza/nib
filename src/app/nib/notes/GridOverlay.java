package app.nib.notes;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONObject;

/**
 * Temporary, non-interactive visual grid overlay, per-document grid configuration,
 * shared grid geometry, live preview widget, and optional coordinate snapping.
 *
 * Never mutates saved pages, ink, templates, PDF content, or exports.
 */
final class GridOverlay {

    /** A4 width in Nib page units (Notebook.PW = 1000) corresponds to 210 mm. */
    static final float PAGE_WIDTH_MM = 210f;
    static final float UNITS_PER_MM = Notebook.PW / PAGE_WIDTH_MM;

    // Grid types
    static final int TYPE_CUSTOM_SIZE = 0;
    static final int TYPE_CUSTOM_ROW_COL = 1;
    static final int TYPE_STANDARD = 2;
    static final int TYPE_AVATAR = 3;
    static final int TYPE_CARD_VIEW = 4;
    static final int TYPE_NAV_DRAWER = 5;
    static final int TYPE_TYPOGRAPHIC = 6;
    static final int TYPE_COUNT = 7;

    // Line styles
    static final int STYLE_SOLID = 0;
    static final int STYLE_DASHED = 1;
    static final int STYLE_DOTTED = 2;

    static final String[] TYPE_NAMES = {
            "Custom Size",
            "Custom Row/Col",
            "Standard",
            "Avatar",
            "Card View",
            "Nav Drawer",
            "Typographic"
    };

    static final String[] TYPE_DIMENSIONS = {
            "Cell width & height in mm",
            "Independent rows & columns (0 = none)",
            "10 × 10 mm · major every 5",
            "24 × 24 mm · square portrait/icon",
            "3 cols × 4 rows · 10 mm margin",
            "1 col × 12 rows · 8 mm margin",
            "12 × 6 mm · baseline + columns"
    };

    static final String[] STYLE_NAMES = {"Solid", "Dashed", "Dotted"};

    static final int[] QUICK_COLORS = {
            0xff2f5bff, // Grid blue
            0xff64748b, // Slate grey
            0xff0ea5e9, // Cyan
            0xff10b981, // Emerald
            0xffe11d48, // Rose
            0xfff59e0b, // Amber
            0xff8b5cf6, // Violet
            0xff1d1f24  // Ink charcoal
    };

    private GridOverlay() {}

    /** Per-notebook / per-PDF grid configuration, persisted in SharedPreferences. */
    static final class Config {
        boolean enabled = false;
        int type = TYPE_CUSTOM_ROW_COL;

        // Custom Size (mm)
        float cellWidthMm = 10f;
        float cellHeightMm = 10f;
        boolean lockSquare = true;

        // Custom Row/Col (0 is valid and means NO internal lines on that axis!)
        int cols = 8;
        int rows = 12;

        // Visual style
        float lineThicknessDp = 1.0f;
        int colorRgb = 0xff2f5bff;
        int opacity = 35; // 5..100 %
        int lineStyle = STYLE_SOLID;

        // Layout
        int majorEvery = 5; // 0 = off, 2..10
        float marginHMm = 0f;
        float marginVMm = 0f;
        float offsetXMm = 0f;
        float offsetYMm = 0f;

        // Snapping (disabled by default)
        boolean snapEnabled = false;

        Config copy() {
            Config c = new Config();
            c.enabled = enabled;
            c.type = type;
            c.cellWidthMm = cellWidthMm;
            c.cellHeightMm = cellHeightMm;
            c.lockSquare = lockSquare;
            c.cols = cols;
            c.rows = rows;
            c.lineThicknessDp = lineThicknessDp;
            c.colorRgb = colorRgb;
            c.opacity = opacity;
            c.lineStyle = lineStyle;
            c.majorEvery = majorEvery;
            c.marginHMm = marginHMm;
            c.marginVMm = marginVMm;
            c.offsetXMm = offsetXMm;
            c.offsetYMm = offsetYMm;
            c.snapEnabled = snapEnabled;
            return c;
        }

        /** Restores defaults for the current notebook or PDF, preserving visibility only if requested. */
        void resetDefaults(boolean keepEnabled) {
            boolean wasEnabled = enabled;
            Config d = new Config();
            enabled = keepEnabled && wasEnabled;
            type = d.type;
            cellWidthMm = d.cellWidthMm;
            cellHeightMm = d.cellHeightMm;
            lockSquare = d.lockSquare;
            cols = d.cols;
            rows = d.rows;
            lineThicknessDp = d.lineThicknessDp;
            colorRgb = d.colorRgb;
            opacity = d.opacity;
            lineStyle = d.lineStyle;
            majorEvery = d.majorEvery;
            marginHMm = d.marginHMm;
            marginVMm = d.marginVMm;
            offsetXMm = d.offsetXMm;
            offsetYMm = d.offsetYMm;
            snapEnabled = d.snapEnabled;
        }

        /** Applies a preset's sensible dimensions and layout defaults when switching to that preset. */
        void applyTypeSelection(int newType) {
            type = Math.max(0, Math.min(TYPE_COUNT - 1, newType));
            switch (type) {
                case TYPE_STANDARD:
                    cellWidthMm = 10f;
                    cellHeightMm = 10f;
                    lockSquare = true;
                    majorEvery = 5;
                    marginHMm = 0f;
                    marginVMm = 0f;
                    offsetXMm = 0f;
                    offsetYMm = 0f;
                    break;
                case TYPE_AVATAR:
                    cellWidthMm = 24f;
                    cellHeightMm = 24f;
                    lockSquare = true;
                    majorEvery = 2;
                    marginHMm = 12f;
                    marginVMm = 12f;
                    offsetXMm = 0f;
                    offsetYMm = 0f;
                    break;
                case TYPE_CARD_VIEW:
                    cols = 3;
                    rows = 4;
                    majorEvery = 0;
                    marginHMm = 10f;
                    marginVMm = 10f;
                    offsetXMm = 0f;
                    offsetYMm = 0f;
                    break;
                case TYPE_NAV_DRAWER:
                    cols = 1;
                    rows = 12;
                    majorEvery = 4;
                    marginHMm = 8f;
                    marginVMm = 8f;
                    offsetXMm = 0f;
                    offsetYMm = 0f;
                    break;
                case TYPE_TYPOGRAPHIC:
                    cellWidthMm = 12f;
                    cellHeightMm = 6f;
                    lockSquare = false;
                    majorEvery = 4;
                    marginHMm = 14f;
                    marginVMm = 16f;
                    offsetXMm = 0f;
                    offsetYMm = 0f;
                    break;
                case TYPE_CUSTOM_SIZE:
                    if (lockSquare) cellHeightMm = cellWidthMm;
                    break;
                case TYPE_CUSTOM_ROW_COL:
                    // Preserve user's exact rows & cols (including 0)
                    break;
            }
        }

        boolean isRowColMode() {
            return type == TYPE_CUSTOM_ROW_COL || type == TYPE_CARD_VIEW || type == TYPE_NAV_DRAWER;
        }

        int effectiveCols() {
            if (type == TYPE_CARD_VIEW) return 3;
            if (type == TYPE_NAV_DRAWER) return 1;
            return Math.max(0, Math.min(100, cols)); // 0 allowed, never clamped to positive!
        }

        int effectiveRows() {
            if (type == TYPE_CARD_VIEW) return 4;
            if (type == TYPE_NAV_DRAWER) return 12;
            return Math.max(0, Math.min(100, rows)); // 0 allowed, never clamped to positive!
        }

        float effectiveCellWidthMm() {
            switch (type) {
                case TYPE_STANDARD: return 10f;
                case TYPE_AVATAR: return 24f;
                case TYPE_TYPOGRAPHIC: return 12f;
                default: return Math.max(2f, Math.min(100f, cellWidthMm));
            }
        }

        float effectiveCellHeightMm() {
            switch (type) {
                case TYPE_STANDARD: return 10f;
                case TYPE_AVATAR: return 24f;
                case TYPE_TYPOGRAPHIC: return 6f;
                default: return lockSquare ? effectiveCellWidthMm() : Math.max(2f, Math.min(100f, cellHeightMm));
            }
        }

        String summary() {
            if (!enabled) return "Off · tap to configure";
            String dim;
            if (isRowColMode()) {
                dim = effectiveCols() + " cols × " + effectiveRows() + " rows";
            } else {
                dim = fmtMm(effectiveCellWidthMm()) + " × " + fmtMm(effectiveCellHeightMm()) + " mm";
            }
            return TYPE_NAMES[Math.max(0, Math.min(TYPE_COUNT - 1, type))] + " · " + dim
                    + (snapEnabled ? " · Snap on" : "");
        }

        String liveDimensionBadge() {
            if (isRowColMode()) {
                int c = effectiveCols(), r = effectiveRows();
                String cs = c == 0 ? "0 cols (none)" : (c == 1 ? "1 col" : c + " cols");
                String rs = r == 0 ? "0 rows (none)" : (r == 1 ? "1 row" : r + " rows");
                return cs + " × " + rs;
            }
            return fmtMm(effectiveCellWidthMm()) + " × " + fmtMm(effectiveCellHeightMm()) + " mm";
        }

        static String fmtMm(float v) {
            if (Math.abs(v - Math.round(v)) < 0.05f) return String.valueOf(Math.round(v));
            return String.format(java.util.Locale.US, "%.1f", v);
        }

        static Config load(SharedPreferences prefs, String docKey) {
            Config c = new Config();
            if (prefs == null || docKey == null) return c;
            String raw = prefs.getString("grid:" + docKey, null);
            if (raw == null || raw.isEmpty()) return c;
            try {
                JSONObject o = new JSONObject(raw);
                c.enabled = o.optBoolean("enabled", false);
                c.type = Math.max(0, Math.min(TYPE_COUNT - 1, o.optInt("type", TYPE_CUSTOM_ROW_COL)));
                c.cellWidthMm = clampF((float) o.optDouble("cw", 10.0), 2f, 100f);
                c.cellHeightMm = clampF((float) o.optDouble("ch", 10.0), 2f, 100f);
                c.lockSquare = o.optBoolean("sq", true);
                // Critical: allow 0 for cols and rows; never clamp 0 to a positive number.
                c.cols = Math.max(0, Math.min(100, o.optInt("cols", 8)));
                c.rows = Math.max(0, Math.min(100, o.optInt("rows", 12)));
                c.lineThicknessDp = clampF((float) o.optDouble("th", 1.0), 0.5f, 4.0f);
                c.colorRgb = o.optInt("col", 0xff2f5bff) | 0xff000000;
                c.opacity = Math.max(5, Math.min(100, o.optInt("op", 35)));
                c.lineStyle = Math.max(0, Math.min(2, o.optInt("st", STYLE_SOLID)));
                int maj = o.optInt("maj", 5);
                c.majorEvery = maj <= 1 ? 0 : Math.min(10, maj);
                c.marginHMm = clampF((float) o.optDouble("mh", 0.0), 0f, 60f);
                c.marginVMm = clampF((float) o.optDouble("mv", 0.0), 0f, 60f);
                c.offsetXMm = clampF((float) o.optDouble("ox", 0.0), -50f, 50f);
                c.offsetYMm = clampF((float) o.optDouble("oy", 0.0), -50f, 50f);
                c.snapEnabled = o.optBoolean("snap", false);
            } catch (Exception ignored) { }
            return c;
        }

        void save(SharedPreferences prefs, String docKey) {
            if (prefs == null || docKey == null) return;
            try {
                JSONObject o = new JSONObject();
                o.put("enabled", enabled);
                o.put("type", type);
                o.put("cw", cellWidthMm);
                o.put("ch", cellHeightMm);
                o.put("sq", lockSquare);
                o.put("cols", Math.max(0, cols));
                o.put("rows", Math.max(0, rows));
                o.put("th", lineThicknessDp);
                o.put("col", colorRgb | 0xff000000);
                o.put("op", opacity);
                o.put("st", lineStyle);
                o.put("maj", majorEvery);
                o.put("mh", marginHMm);
                o.put("mv", marginVMm);
                o.put("ox", offsetXMm);
                o.put("oy", offsetYMm);
                o.put("snap", snapEnabled);
                prefs.edit().putString("grid:" + docKey, o.toString()).apply();
            } catch (Exception ignored) { }
        }
    }

    /** Resolved page-space grid metrics for a single page. */
    static final class Metrics {
        float contentLeft, contentTop, contentRight, contentBottom;
        float originX, originY;
        /** <= 0 means NO lines along that axis (e.g. 0 columns or 0 rows). */
        float stepX, stepY;
        boolean drawVerticalEdges;
        boolean drawHorizontalEdges;
    }

    /**
     * Computes grid spacing and content bounds in page-local coordinates for a page of size (pageW, pageH).
     * When rows == 0 or cols == 0 in row/col mode, the corresponding step is 0f (no lines, no snapping on that axis).
     */
    static void computeMetrics(Config cfg, float pageW, float pageH, Metrics out) {
        float mh = Math.min(cfg.marginHMm * UNITS_PER_MM, pageW * 0.45f);
        float mv = Math.min(cfg.marginVMm * UNITS_PER_MM, pageH * 0.45f);
        out.contentLeft = mh;
        out.contentTop = mv;
        out.contentRight = Math.max(mh, pageW - mh);
        out.contentBottom = Math.max(mv, pageH - mv);
        float cw = out.contentRight - out.contentLeft;
        float ch = out.contentBottom - out.contentTop;
        out.originX = out.contentLeft + cfg.offsetXMm * UNITS_PER_MM;
        out.originY = out.contentTop + cfg.offsetYMm * UNITS_PER_MM;
        if (cfg.isRowColMode()) {
            int c = cfg.effectiveCols();
            int r = cfg.effectiveRows();
            out.stepX = (c > 0 && cw > 1f) ? (cw / c) : 0f;
            out.stepY = (r > 0 && ch > 1f) ? (ch / r) : 0f;
            out.drawVerticalEdges = c > 0;
            out.drawHorizontalEdges = r > 0;
        } else {
            out.stepX = Math.max(1f, cfg.effectiveCellWidthMm() * UNITS_PER_MM);
            out.stepY = Math.max(1f, cfg.effectiveCellHeightMm() * UNITS_PER_MM);
            out.drawVerticalEdges = true;
            out.drawHorizontalEdges = true;
        }
    }

    /**
     * Snaps a page-local coordinate (x, y) to the nearest grid intersection if grid + snapping are enabled.
     * If an axis has zero rows or columns (step <= 0), leaves that coordinate completely unchanged!
     */
    static void snapPoint(Config cfg, float pageW, float pageH, float x, float y, float[] outXY) {
        outXY[0] = x;
        outXY[1] = y;
        if (cfg == null || !cfg.enabled || !cfg.snapEnabled) return;
        Metrics m = new Metrics();
        computeMetrics(cfg, pageW, pageH, m);
        if (m.stepX > 0.001f) {
            float kx = Math.round((x - m.originX) / m.stepX);
            float sx = m.originX + kx * m.stepX;
            if (m.contentRight > m.contentLeft) {
                sx = Math.max(m.contentLeft, Math.min(m.contentRight, sx));
            }
            outXY[0] = sx;
        }
        if (m.stepY > 0.001f) {
            float ky = Math.round((y - m.originY) / m.stepY);
            float sy = m.originY + ky * m.stepY;
            if (m.contentBottom > m.contentTop) {
                sy = Math.max(m.contentTop, Math.min(m.contentBottom, sy));
            }
            outXY[1] = sy;
        }
    }

    /** Builds minor & major Paint objects for the current Config and screen density. */
    private static void configurePaints(Config cfg, float density, float thicknessScale,
                                        Paint minor, Paint major) {
        float basePx = Math.max(0.75f, cfg.lineThicknessDp * density * thicknessScale);
        int rgb = cfg.colorRgb & 0x00ffffff;
        int minorAlpha = Math.max(8, Math.min(255, Math.round(cfg.opacity * 2.55f)));
        int majorAlpha = Math.min(255, Math.round(minorAlpha * 1.45f));

        minor.setStyle(Paint.Style.STROKE);
        minor.setAntiAlias(true);
        minor.setStrokeWidth(basePx);
        minor.setColor((minorAlpha << 24) | rgb);

        major.setStyle(Paint.Style.STROKE);
        major.setAntiAlias(true);
        major.setStrokeWidth(basePx * 1.85f);
        major.setColor((majorAlpha << 24) | rgb);

        if (cfg.lineStyle == STYLE_DASHED) {
            minor.setStrokeCap(Paint.Cap.BUTT);
            major.setStrokeCap(Paint.Cap.BUTT);
            minor.setPathEffect(new DashPathEffect(new float[]{Math.max(4f, basePx * 6f), Math.max(3f, basePx * 4f)}, 0f));
            major.setPathEffect(new DashPathEffect(new float[]{Math.max(6f, basePx * 8f), Math.max(3.5f, basePx * 4f)}, 0f));
        } else if (cfg.lineStyle == STYLE_DOTTED) {
            minor.setStrokeCap(Paint.Cap.ROUND);
            major.setStrokeCap(Paint.Cap.ROUND);
            minor.setPathEffect(new DashPathEffect(new float[]{Math.max(1f, basePx * 0.9f), Math.max(4f, basePx * 4.2f)}, 0f));
            major.setPathEffect(new DashPathEffect(new float[]{Math.max(1.5f, basePx * 1.3f), Math.max(5f, basePx * 5.0f)}, 0f));
        } else {
            minor.setStrokeCap(Paint.Cap.BUTT);
            major.setStrokeCap(Paint.Cap.BUTT);
            minor.setPathEffect(null);
            major.setPathEffect(null);
        }
    }

    /**
     * Draws one page's grid clipped strictly to the visible intersection of the page rect and screen rect.
     * Shared by both the live non-interactive OverlayView and the settings PreviewView.
     */
    static void drawPageGrid(Canvas canvas, Config cfg,
                             float pageW, float pageH,
                             float ox, float oy, float z, float pageTopDoc,
                             boolean infiniteCanvas,
                             RectF visibleScreenRect,
                             Paint minorPaint, Paint majorPaint,
                             Path minorPath, Path majorPath,
                             Metrics m) {
        if (cfg == null) return;
        computeMetrics(cfg, pageW, pageH, m);

        float clipLeft, clipTop, clipRight, clipBottom;
        float docMinX, docMaxX, docMinY, docMaxY;
        if (infiniteCanvas) {
            clipLeft = visibleScreenRect.left;
            clipTop = visibleScreenRect.top;
            clipRight = visibleScreenRect.right;
            clipBottom = visibleScreenRect.bottom;
            docMinX = ox + clipLeft / z;
            docMaxX = ox + clipRight / z;
            docMinY = oy + clipTop / z - pageTopDoc;
            docMaxY = oy + clipBottom / z - pageTopDoc;
        } else {
            float pageScreenL = (0f - ox) * z;
            float pageScreenT = (pageTopDoc - oy) * z;
            float pageScreenR = (pageW - ox) * z;
            float pageScreenB = (pageTopDoc + pageH - oy) * z;
            if (pageScreenR <= visibleScreenRect.left || pageScreenL >= visibleScreenRect.right
                    || pageScreenB <= visibleScreenRect.top || pageScreenT >= visibleScreenRect.bottom) {
                return;
            }
            float contScreenL = (m.contentLeft - ox) * z;
            float contScreenT = (pageTopDoc + m.contentTop - oy) * z;
            float contScreenR = (m.contentRight - ox) * z;
            float contScreenB = (pageTopDoc + m.contentBottom - oy) * z;

            // Clip to the visible page bounds (and respect margins inside the page)
            clipLeft = Math.max(visibleScreenRect.left, Math.max(pageScreenL, contScreenL));
            clipTop = Math.max(visibleScreenRect.top, Math.max(pageScreenT, contScreenT));
            clipRight = Math.min(visibleScreenRect.right, Math.min(pageScreenR, contScreenR));
            clipBottom = Math.min(visibleScreenRect.bottom, Math.min(pageScreenB, contScreenB));
            if (clipRight <= clipLeft || clipBottom <= clipTop) return;

            docMinX = Math.max(m.contentLeft, ox + clipLeft / z);
            docMaxX = Math.min(m.contentRight, ox + clipRight / z);
            docMinY = Math.max(m.contentTop, oy + clipTop / z - pageTopDoc);
            docMaxY = Math.min(m.contentBottom, oy + clipBottom / z - pageTopDoc);
        }

        minorPath.rewind();
        majorPath.rewind();
        int majorEvery = cfg.majorEvery >= 2 ? cfg.majorEvery : 0;
        final float eps = 0.05f;
        final int maxLinesPerAxis = 400; // safety guard at extreme zoom-out

        // Vertical lines (only if stepX > 0; 0 columns => stepX == 0 => draw NO vertical lines)
        if (m.stepX > 0.001f) {
            float stepScreenX = m.stepX * z;
            int stride = stepScreenX < 3f ? Math.max(1, (int) Math.ceil(3f / Math.max(0.01f, stepScreenX))) : 1;
            int kStart = (int) Math.floor((docMinX - m.originX) / m.stepX) - 1;
            int kEnd = (int) Math.ceil((docMaxX - m.originX) / m.stepX) + 1;
            if (kEnd - kStart > maxLinesPerAxis * stride) {
                stride = Math.max(stride, (kEnd - kStart) / maxLinesPerAxis + 1);
            }
            for (int k = kStart; k <= kEnd; k += stride) {
                float px = m.originX + k * m.stepX;
                if (!infiniteCanvas && (px < m.contentLeft - eps || px > m.contentRight + eps)) continue;
                // In Custom Row/Col with 0 margin and 0 offset, keep boundary lines crisp inside clip
                float sx = (px - ox) * z;
                if (sx < clipLeft - 2f || sx > clipRight + 2f) continue;
                boolean isMaj = majorEvery >= 2 && floorMod(k, majorEvery) == 0;
                Path target = isMaj ? majorPath : minorPath;
                target.moveTo(sx, clipTop);
                target.lineTo(sx, clipBottom);
            }
        }

        // Horizontal lines (only if stepY > 0; 0 rows => stepY == 0 => draw NO horizontal lines)
        if (m.stepY > 0.001f) {
            float stepScreenY = m.stepY * z;
            int stride = stepScreenY < 3f ? Math.max(1, (int) Math.ceil(3f / Math.max(0.01f, stepScreenY))) : 1;
            int kStart = (int) Math.floor((docMinY - m.originY) / m.stepY) - 1;
            int kEnd = (int) Math.ceil((docMaxY - m.originY) / m.stepY) + 1;
            if (kEnd - kStart > maxLinesPerAxis * stride) {
                stride = Math.max(stride, (kEnd - kStart) / maxLinesPerAxis + 1);
            }
            for (int k = kStart; k <= kEnd; k += stride) {
                float py = m.originY + k * m.stepY;
                if (!infiniteCanvas && (py < m.contentTop - eps || py > m.contentBottom + eps)) continue;
                float sy = (pageTopDoc + py - oy) * z;
                if (sy < clipTop - 2f || sy > clipBottom + 2f) continue;
                boolean isMaj = majorEvery >= 2 && floorMod(k, majorEvery) == 0;
                Path target = isMaj ? majorPath : minorPath;
                target.moveTo(clipLeft, sy);
                target.lineTo(clipRight, sy);
            }
        }

        if (minorPath.isEmpty() && majorPath.isEmpty()) return;

        // Small 1px pad on clip so edge lines at 0 margin aren't half-clipped
        canvas.save();
        if (!infiniteCanvas) {
            float pageScreenL = Math.max(visibleScreenRect.left, (0f - ox) * z);
            float pageScreenT = Math.max(visibleScreenRect.top, (pageTopDoc - oy) * z);
            float pageScreenR = Math.min(visibleScreenRect.right, (pageW - ox) * z);
            float pageScreenB = Math.min(visibleScreenRect.bottom, (pageTopDoc + pageH - oy) * z);
            canvas.clipRect(pageScreenL, pageScreenT, pageScreenR, pageScreenB);
        } else {
            canvas.clipRect(visibleScreenRect);
        }
        if (!minorPath.isEmpty()) canvas.drawPath(minorPath, minorPaint);
        if (!majorPath.isEmpty()) canvas.drawPath(majorPath, majorPaint);
        canvas.restore();
    }

    private static int floorMod(int a, int b) {
        int r = a % b;
        return r < 0 ? r + b : r;
    }

    private static float clampF(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * Non-interactive temporary visual overlay placed inside each document Pane.
     * Completely transparent to pen input and gestures; draws nothing when grid is off.
     */
    static final class OverlayView extends View {
        private Config cfg = new Config();
        private float ox, oy, z = 1f;
        private boolean infiniteCanvas;
        private float[] pageTops = new float[0];
        private float[] pageHeights = new float[0];
        private boolean hasViewport;

        private final Paint minorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint majorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path minorPath = new Path();
        private final Path majorPath = new Path();
        private final RectF screenRect = new RectF();
        private final Metrics metrics = new Metrics();
        private final float density;

        OverlayView(Context c) {
            super(c);
            density = c.getResources().getDisplayMetrics().density;
            setClickable(false);
            setFocusable(false);
            setFocusableInTouchMode(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setVisibility(GONE);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            return false; // strictly non-interactive
        }

        void setConfig(Config config) {
            this.cfg = config != null ? config.copy() : new Config();
            if (!this.cfg.enabled) {
                setVisibility(GONE);
                return;
            }
            configurePaints(this.cfg, density, 1f, minorPaint, majorPaint);
            setVisibility(VISIBLE);
            invalidate();
        }

        void updateViewport(float ox, float oy, float z, boolean infiniteCanvas,
                            float[] tops, float[] heights) {
            this.ox = ox;
            this.oy = oy;
            this.z = Math.max(0.01f, z);
            this.infiniteCanvas = infiniteCanvas;
            this.pageTops = tops != null ? tops : new float[0];
            this.pageHeights = heights != null ? heights : new float[0];
            this.hasViewport = true;
            if (cfg.enabled) invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            if (!cfg.enabled || !hasViewport) return;
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            screenRect.set(0f, 0f, w, h);

            if (infiniteCanvas) {
                drawPageGrid(canvas, cfg, Notebook.PW, Notebook.PH, ox, oy, z, 0f,
                        true, screenRect, minorPaint, majorPaint, minorPath, majorPath, metrics);
                return;
            }

            int n = Math.min(pageTops.length, pageHeights.length);
            for (int i = 0; i < n; i++) {
                float pTop = pageTops[i];
                float pH = pageHeights[i];
                float sTop = (pTop - oy) * z;
                float sBot = (pTop + pH - oy) * z;
                if (sBot <= 0f) continue;
                if (sTop >= h) break;
                drawPageGrid(canvas, cfg, Notebook.PW, pH, ox, oy, z, pTop,
                        false, screenRect, minorPaint, majorPaint, minorPath, majorPath, metrics);
            }
        }
    }

    /**
     * Live miniature page preview inside the Grid settings panel.
     * Updates immediately whenever the grid type or any setting changes.
     */
    static final class PreviewView extends View {
        private Config cfg = new Config();
        private float pageAspect = Notebook.PH / Notebook.PW;
        private int paperBg = 0xffffffff;
        private int deskBg;
        private int borderColor;

        private final Paint minorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint majorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint marginPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path minorPath = new Path();
        private final Path majorPath = new Path();
        private final RectF pageRect = new RectF();
        private final RectF marginRect = new RectF();
        private final Metrics metrics = new Metrics();
        private final float density;

        PreviewView(Context c) {
            super(c);
            density = c.getResources().getDisplayMetrics().density;
            deskBg = Ui.color(c, R.color.surface_variant);
            borderColor = Ui.color(c, R.color.outline);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(Math.max(1f, density));
            borderPaint.setColor(borderColor);
            marginPaint.setStyle(Paint.Style.STROKE);
            marginPaint.setStrokeWidth(Math.max(1f, density * 0.8f));
            marginPaint.setPathEffect(new DashPathEffect(new float[]{density * 3f, density * 3f}, 0f));
        }

        void setPageContext(float pageAspect, int paperBg) {
            this.pageAspect = Math.max(0.5f, Math.min(2.0f, pageAspect));
            this.paperBg = paperBg;
            invalidate();
        }

        void setConfig(Config config) {
            this.cfg = config != null ? config.copy() : new Config();
            configurePaints(this.cfg, density, 0.78f, minorPaint, majorPaint);
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;

            // Soft desk backdrop
            float rDesk = density * 10f;
            fillPaint.setColor(deskBg);
            canvas.drawRoundRect(0f, 0f, w, h, rDesk, rDesk, fillPaint);

            float pad = density * 12f;
            float availW = Math.max(10f, w - 2f * pad);
            float availH = Math.max(10f, h - 2f * pad);
            float drawH = availH;
            float drawW = drawH / pageAspect;
            if (drawW > availW) {
                drawW = availW;
                drawH = drawW * pageAspect;
            }
            float left = (w - drawW) * 0.5f;
            float top = (h - drawH) * 0.5f;
            pageRect.set(left, top, left + drawW, top + drawH);

            // Paper sheet with subtle drop shadow
            fillPaint.setColor(0x16000000);
            canvas.drawRoundRect(left + density, top + density * 2f, left + drawW + density, top + drawH + density * 2f,
                    density * 3f, density * 3f, fillPaint);
            fillPaint.setColor(paperBg);
            canvas.drawRect(pageRect, fillPaint);

            // Render live grid mapped exactly into pageRect
            float pageH = Notebook.PW * pageAspect;
            float z = drawW / Notebook.PW;
            float ox = -left / z;
            float oy = -top / z;
            drawPageGrid(canvas, cfg, Notebook.PW, pageH, ox, oy, z, 0f,
                    false, pageRect, minorPaint, majorPaint, minorPath, majorPath, metrics);

            // Subtle margin hint if margins > 0
            if (cfg.marginHMm > 0.1f || cfg.marginVMm > 0.1f) {
                marginPaint.setColor((0x44 << 24) | (cfg.colorRgb & 0x00ffffff));
                marginRect.set(
                        (metrics.contentLeft - ox) * z,
                        (metrics.contentTop - oy) * z,
                        (metrics.contentRight - ox) * z,
                        (metrics.contentBottom - oy) * z
                );
                canvas.drawRect(marginRect, marginPaint);
            }

            // Crisp page outline
            canvas.drawRect(pageRect, borderPaint);
        }
    }
}

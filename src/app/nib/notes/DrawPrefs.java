package app.nib.notes;

import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Persistent drawing preferences for Nib:
 * - Exactly 5 customisable colour slots shared across Pen, Highlighter, and Shape tools,
 *   with independent last-selected colour/slot memory per tool.
 * - Exactly 3 user-definable stroke thickness slots per tool (Pen, Highlighter, Shapes),
 *   customisable by the user just like the 5 colour slots and persisted permanently.
 * - Independent opacity controls (0..100%) for Pen, Highlighter, Shape Border, and Shape Fill.
 * - Default Shape & Design tool settings.
 */
final class DrawPrefs {
    static final int SLOT_COUNT = 5;
    static final int THICK_COUNT = 3;

    static final int THICK_THIN = 0;
    static final int THICK_MEDIUM = 1;
    static final int THICK_THICK = 2;
    static final String[] THICK_LABELS = {"Slot 1", "Slot 2", "Slot 3"};

    /** Default 5 customisable colour slots: Ink Charcoal, Cobalt Blue, Crimson Red, Emerald Green, Golden Amber. */
    static final int[] DEFAULT_SLOTS = {
            0xff1d1f24,
            0xff2f5bff,
            0xffe23b3b,
            0xff139a57,
            0xfff5c400
    };

    /** Min / Max and default 3 customisable thickness slots per tool. */
    static final float PEN_MIN = 0.5f, PEN_MAX = 16.0f;
    static final float HL_MIN = 6.0f, HL_MAX = 64.0f;
    static final float SHAPE_MIN = 0.8f, SHAPE_MAX = 20.0f;
    static final float ERASER_MIN = 4.0f, ERASER_MAX = 36.0f;

    static final float[] DEFAULT_PEN_WIDTHS = {1.3f, 2.8f, 6.0f};
    static final float[] DEFAULT_HL_WIDTHS = {14.0f, 24.0f, 40.0f};
    static final float[] DEFAULT_SHAPE_WIDTHS = {1.8f, 3.8f, 7.2f};
    static final float[] DEFAULT_ERASER_WIDTHS = {7.0f, 13.0f, 24.0f};

    final int[] slots = DEFAULT_SLOTS.clone();

    // User-definable 3 thickness slots per tool
    final float[] penWidths = DEFAULT_PEN_WIDTHS.clone();
    final float[] hlWidths = DEFAULT_HL_WIDTHS.clone();
    final float[] shapeWidths = DEFAULT_SHAPE_WIDTHS.clone();
    final float[] eraserWidths = DEFAULT_ERASER_WIDTHS.clone();

    // Independent last-selected colour slot (0..4) per tool
    int penSlot = 0;
    int hlSlot = 4;
    int shapeStrokeSlot = 1;
    int shapeFillSlot = 4;

    // Independent active thickness slot (0..2) per tool
    int penThick = THICK_MEDIUM;
    int hlThick = THICK_MEDIUM;
    int shapeThick = THICK_MEDIUM;
    int eraserThick = THICK_MEDIUM;

    // Independent opacity (0..100 %) per tool / channel
    int penOpacity = 100;
    int hlOpacity = 42;
    int shapeStrokeOpacity = 100;
    int shapeFillOpacity = 35;

    // Shape & Design tool settings
    String shapeKind = ShapeSpec.KIND_RECT;
    int shapeFillStyle = ShapeSpec.STYLE_OUTLINE; // No colour fill by default
    float shapeCornerRadius = 0.22f; // 0..0.5 fraction of min(w,h)
    int shapePolygonSides = 5;       // 3..12
    float shapeArrowSize = 1.0f;     // 0.5..2.5
    boolean shapeLockCircle = true;

    private static final String K_SLOT = "dp_slot_";
    private static final String K_MIGRATED = "dp_migrated_v1";
    private static final String K_FILL_NONE_DEF = "dp_fillNoneDefault_v1";

    static DrawPrefs load(SharedPreferences p) {
        DrawPrefs d = new DrawPrefs();
        if (p == null) return d;
        boolean migrated = p.getBoolean(K_MIGRATED, false);
        for (int i = 0; i < SLOT_COUNT; i++) {
            int def = DEFAULT_SLOTS[i];
            if (!migrated && i < 4 && p.contains("pc" + i)) {
                def = p.getInt("pc" + i, def);
            }
            d.slots[i] = 0xff000000 | (p.getInt(K_SLOT + i, def) & 0x00ffffff);
        }
        for (int i = 0; i < THICK_COUNT; i++) {
            d.penWidths[i] = clampF(p.getFloat("dp_penW_" + i, DEFAULT_PEN_WIDTHS[i]), PEN_MIN, PEN_MAX);
            d.hlWidths[i] = clampF(p.getFloat("dp_hlW_" + i, DEFAULT_HL_WIDTHS[i]), HL_MIN, HL_MAX);
            d.shapeWidths[i] = clampF(p.getFloat("dp_shW_" + i, DEFAULT_SHAPE_WIDTHS[i]), SHAPE_MIN, SHAPE_MAX);
            d.eraserWidths[i] = clampF(p.getFloat("dp_erW_" + i, DEFAULT_ERASER_WIDTHS[i]), ERASER_MIN, ERASER_MAX);
        }
        d.penSlot = clamp(p.getInt("dp_penSlot", clamp(p.getInt("penColor", 0), 0, SLOT_COUNT - 1)), 0, SLOT_COUNT - 1);
        d.hlSlot = clamp(p.getInt("dp_hlSlot", 4), 0, SLOT_COUNT - 1);
        d.shapeStrokeSlot = clamp(p.getInt("dp_shStrokeSlot", 1), 0, SLOT_COUNT - 1);
        d.shapeFillSlot = clamp(p.getInt("dp_shFillSlot", 4), 0, SLOT_COUNT - 1);

        d.penThick = clamp(p.getInt("dp_penThick", nearestPreset(p.getFloat("penW", d.penWidths[1]), d.penWidths)), 0, THICK_COUNT - 1);
        d.hlThick = clamp(p.getInt("dp_hlThick", nearestPreset(p.getFloat("hlW", d.hlWidths[1]), d.hlWidths)), 0, THICK_COUNT - 1);
        d.shapeThick = clamp(p.getInt("dp_shThick", THICK_MEDIUM), 0, THICK_COUNT - 1);
        d.eraserThick = clamp(p.getInt("dp_erThick", nearestPreset(p.getFloat("eraserDp", d.eraserWidths[1]), d.eraserWidths)), 0, THICK_COUNT - 1);

        d.penOpacity = clamp(p.getInt("dp_penOp", 100), 0, 100);
        d.hlOpacity = clamp(p.getInt("dp_hlOp", 42), 0, 100);
        d.shapeStrokeOpacity = clamp(p.getInt("dp_shStrokeOp", 100), 0, 100);
        d.shapeFillOpacity = clamp(p.getInt("dp_shFillOp", 35), 0, 100);

        d.shapeKind = p.getString("dp_shKind", ShapeSpec.KIND_RECT);
        if (!ShapeSpec.isValidKind(d.shapeKind)) d.shapeKind = ShapeSpec.KIND_RECT;
        boolean fillDefaultMigrated = p.getBoolean(K_FILL_NONE_DEF, false);
        d.shapeFillStyle = fillDefaultMigrated
                ? clamp(p.getInt("dp_shStyle", ShapeSpec.STYLE_OUTLINE), 0, 3)
                : ShapeSpec.STYLE_OUTLINE;
        d.shapeCornerRadius = clampF(p.getFloat("dp_shCorner", 0.22f), 0f, 0.5f);
        d.shapePolygonSides = clamp(p.getInt("dp_shSides", 5), 3, 12);
        d.shapeArrowSize = clampF(p.getFloat("dp_shArrow", 1.0f), 0.5f, 2.5f);
        d.shapeLockCircle = p.getBoolean("dp_shLockCircle", true);
        return d;
    }

    void save(SharedPreferences p) {
        if (p == null) return;
        SharedPreferences.Editor e = p.edit();
        e.putBoolean(K_MIGRATED, true);
        e.putBoolean(K_FILL_NONE_DEF, true);
        for (int i = 0; i < SLOT_COUNT; i++) {
            e.putInt(K_SLOT + i, slots[i]);
            if (i < 4) e.putInt("pc" + i, slots[i]);
        }
        for (int i = 0; i < THICK_COUNT; i++) {
            e.putFloat("dp_penW_" + i, penWidths[i]);
            e.putFloat("dp_hlW_" + i, hlWidths[i]);
            e.putFloat("dp_shW_" + i, shapeWidths[i]);
            e.putFloat("dp_erW_" + i, eraserWidths[i]);
        }
        e.putInt("dp_penSlot", penSlot);
        e.putInt("dp_hlSlot", hlSlot);
        e.putInt("dp_shStrokeSlot", shapeStrokeSlot);
        e.putInt("dp_shFillSlot", shapeFillSlot);
        e.putInt("dp_penThick", penThick);
        e.putInt("dp_hlThick", hlThick);
        e.putInt("dp_shThick", shapeThick);
        e.putInt("dp_erThick", eraserThick);
        e.putInt("dp_penOp", penOpacity);
        e.putInt("dp_hlOp", hlOpacity);
        e.putInt("dp_shStrokeOp", shapeStrokeOpacity);
        e.putInt("dp_shFillOp", shapeFillOpacity);
        e.putString("dp_shKind", shapeKind);
        e.putInt("dp_shStyle", shapeFillStyle);
        e.putFloat("dp_shCorner", shapeCornerRadius);
        e.putInt("dp_shSides", shapePolygonSides);
        e.putFloat("dp_shArrow", shapeArrowSize);
        e.putBoolean("dp_shLockCircle", shapeLockCircle);
        e.apply();
    }

    /** Restores the 5 custom colour slots to their factory defaults. */
    void resetColors() {
        System.arraycopy(DEFAULT_SLOTS, 0, slots, 0, SLOT_COUNT);
    }

    void resetPenWidths() { System.arraycopy(DEFAULT_PEN_WIDTHS, 0, penWidths, 0, THICK_COUNT); }
    void resetHlWidths() { System.arraycopy(DEFAULT_HL_WIDTHS, 0, hlWidths, 0, THICK_COUNT); }
    void resetShapeWidths() { System.arraycopy(DEFAULT_SHAPE_WIDTHS, 0, shapeWidths, 0, THICK_COUNT); }
    void resetEraserWidths() { System.arraycopy(DEFAULT_ERASER_WIDTHS, 0, eraserWidths, 0, THICK_COUNT); }

    int penColorRgb() { return slots[clamp(penSlot, 0, SLOT_COUNT - 1)] | 0xff000000; }
    int hlColorRgb() { return slots[clamp(hlSlot, 0, SLOT_COUNT - 1)] | 0xff000000; }
    int shapeStrokeRgb() { return slots[clamp(shapeStrokeSlot, 0, SLOT_COUNT - 1)] | 0xff000000; }
    int shapeFillRgb() { return slots[clamp(shapeFillSlot, 0, SLOT_COUNT - 1)] | 0xff000000; }

    /** ARGB stroke colour combining the selected slot RGB with the tool's independent opacity (0..100). */
    int penColorArgb() { return withOpacity(penColorRgb(), penOpacity); }

    /** ARGB highlighter colour combining the selected slot RGB with the highlighter's opacity (0..100). */
    int hlColorArgb() { return withOpacity(hlColorRgb(), hlOpacity); }

    float penWidth() { return penWidths[clamp(penThick, 0, THICK_COUNT - 1)]; }
    float hlWidth() { return hlWidths[clamp(hlThick, 0, THICK_COUNT - 1)]; }
    float shapeWidth() { return shapeWidths[clamp(shapeThick, 0, THICK_COUNT - 1)]; }
    float eraserWidth() { return eraserWidths[clamp(eraserThick, 0, THICK_COUNT - 1)]; }

    /** Builds a default ShapeSpec reflecting the user's current Shape tool settings. */
    ShapeSpec makeShapeSpec() {
        ShapeSpec s = new ShapeSpec();
        s.kind = shapeKind;
        s.fillStyle = shapeFillStyle;
        s.strokeColor = shapeStrokeRgb();
        s.strokeAlpha = shapeStrokeOpacity;
        s.strokeWidth = shapeWidth();
        s.fillColor = shapeFillStyle == ShapeSpec.STYLE_CUSTOM ? shapeFillRgb() : shapeStrokeRgb();
        if (shapeFillStyle == ShapeSpec.STYLE_SOLID) s.fillColor = shapeFillRgb();
        if (shapeFillStyle == ShapeSpec.STYLE_TRANSPARENT) s.fillColor = shapeFillRgb();
        s.fillAlpha = shapeFillStyle == ShapeSpec.STYLE_SOLID ? 100
                : shapeFillStyle == ShapeSpec.STYLE_OUTLINE ? 0 : shapeFillOpacity;
        s.cornerRadius = shapeCornerRadius;
        s.sides = shapePolygonSides;
        s.arrowSize = shapeArrowSize;
        s.zOrder = ShapeSpec.DEFAULT_Z_BEHIND_INK; // behind handwriting by default
        return s;
    }

    /** Logarithmic slider (0..100) -> page-unit width so fine & bold widths both feel smooth. */
    static float sliderToWidth(int slider, float min, float max) {
        int s = clamp(slider, 0, 100);
        return (float) (min * Math.pow(max / min, s / 100.0));
    }

    static int widthToSlider(float w, float min, float max) {
        float c = clampF(w, min, max);
        return clamp((int) Math.round(Math.log(c / min) / Math.log(max / min) * 100.0), 0, 100);
    }

    /** Normalised 0..1 visual fraction for SizeDot on the toolbar. */
    static float widthFrac(float w, float min, float max) {
        return widthToSlider(w, min, max) / 100f;
    }

    /** Human-readable width on an A4-wide page (1000 units = 210 mm). */
    static String formatMm(float pageUnits) {
        float mm = Math.max(0.1f, pageUnits * 0.21f);
        return String.format(Locale.US, "%.1f mm", mm);
    }

    /** Human-readable eraser radius in dp. */
    static String formatDp(float dpVal) {
        return Math.round(dpVal) + " dp";
    }

    static int withOpacity(int rgb, int pct) {
        int a = Math.round(clamp(pct, 0, 100) * 255f / 100f);
        return (a << 24) | (rgb & 0x00ffffff);
    }

    static int nearestPreset(float w, float[] presets) {
        int best = 1;
        float bd = Float.MAX_VALUE;
        for (int i = 0; i < presets.length; i++) {
            float d = Math.abs(w - presets[i]);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    static float clampF(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}

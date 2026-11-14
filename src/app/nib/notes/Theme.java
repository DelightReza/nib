package app.nib.notes;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;

/**
 * App theme (Settings → Theme): mode System / Light / Dark, one accent colour (8 presets or a custom one),
 * and dark style Graphite or Black. One typeface everywhere: Inter (Ui.font). The accent is applied at runtime: every
 * {@code Ui.color(R.color.accent…)} goes through here, so nothing else needs to know.
 */
final class Theme {
    private Theme() {}

    static final String[] ACC_NAMES = {"Terracotta", "Ink blue", "Teal", "Moss", "Plum", "Berry", "Ochre", "Graphite"};
    static final int[] ACC_LIGHT = {0xFFC2603F, 0xFF3C6FD8, 0xFF1F7F7C, 0xFF5E8C5B, 0xFF7F5BD6, 0xFFC2457A, 0xFFB7861F, 0xFF45433F};
    static final int[] ACC_DARK = {0xFFE58A68, 0xFF8AB0F5, 0xFF58C2BA, 0xFF97C291, 0xFFB7A3F3, 0xFFEE8DB5, 0xFFE9BE5B, 0xFFD3D0CB};

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("nib", Context.MODE_PRIVATE); }

    // ---- mode -------------------------------------------------------------------------------

    /** 0 System, 1 Light, 2 Dark. */
    static int get(Context c) { return prefs(c).getInt("theme", 0); }

    /** Re-applies the saved choice (Android keeps it per app ID; a renamed or restored app starts on System). */
    static void restore(Context c) {
        int i = get(c);
        if (i != 0) c.getSystemService(UiModeManager.class).setApplicationNightMode(i == 1 ? UiModeManager.MODE_NIGHT_NO : UiModeManager.MODE_NIGHT_YES);
    }

    static void set(Context c, int i) {
        prefs(c).edit().putInt("theme", i).apply();
        c.getSystemService(UiModeManager.class).setApplicationNightMode(
                i == 1 ? UiModeManager.MODE_NIGHT_NO : i == 2 ? UiModeManager.MODE_NIGHT_YES : UiModeManager.MODE_NIGHT_AUTO);
    }

    // ---- accent -----------------------------------------------------------------------------

    /** Preset index, or -1 for the custom colour. */
    static int accentIndex(Context c) { return prefs(c).getInt("accent", 0); }

    static int customAccent(Context c) { return prefs(c).getInt("accentCustom", 0xFFC2603F); }

    static void setAccent(Context c, int index, int custom) {
        SharedPreferences.Editor e = prefs(c).edit().putInt("accent", index);
        if (index < 0) e.putInt("accentCustom", custom | 0xFF000000);
        e.apply();
    }

    static int accent(Context c) {
        int i = accentIndex(c);
        boolean dark = Ui.dark(c);
        if (i < 0) {
            int cu = customAccent(c);
            return dark ? lighten(cu) : cu;
        }
        return (dark ? ACC_DARK : ACC_LIGHT)[Math.min(i, ACC_LIGHT.length - 1)];
    }

    /** Text/icons drawn on the accent: dark on light accents, white otherwise. */
    static int onAccent(Context c) { return luminance(accent(c)) > 0.42 ? 0xFF161616 : 0xFFFFFFFF; }

    /** A soft tint of the accent over the surface (selected chips, pressed tools). */
    static int accentContainer(Context c) {
        int s = Ui.dark(c) ? (black(c) ? 0xFF141414 : c.getColor(R.color.surface)) : c.getColor(R.color.surface);
        return mix(s, accent(c), Ui.dark(c) ? 0.24f : 0.14f);
    }

    /** The preset nearest to a colour (for the few system widgets that only take a theme colour). */
    static int nearestPreset(int color) {
        float[] a = new float[3], b = new float[3];
        Color.colorToHSV(color, a);
        int best = 0;
        float bd = Float.MAX_VALUE;
        for (int i = 0; i < ACC_LIGHT.length; i++) {
            Color.colorToHSV(ACC_LIGHT[i], b);
            float dh = Math.abs(a[0] - b[0]); dh = Math.min(dh, 360 - dh) / 180f;
            float d = dh * dh * (a[1] * b[1]) + (a[1] - b[1]) * (a[1] - b[1]) + 0.5f * (a[2] - b[2]) * (a[2] - b[2]);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    /** Dialog buttons, cursors and switches follow the accent through a theme overlay. Call before setContentView. */
    static void applyTo(Activity a) {
        Ui.initFonts(a);
        int i = accentIndex(a);
        if (i < 0) i = nearestPreset(customAccent(a));
        int[] styles = {R.style.Accent0, R.style.Accent1, R.style.Accent2, R.style.Accent3, R.style.Accent4, R.style.Accent5, R.style.Accent6, R.style.Accent7};
        a.getTheme().applyStyle(styles[Math.min(i, styles.length - 1)], true);
        if (black(a) && Ui.dark(a)) {
            a.getWindow().setStatusBarColor(0xFF000000);
            a.getWindow().setNavigationBarColor(0xFF000000);
        }
    }

    // ---- dark style & titles ----------------------------------------------------------------

    /** Dark style "Black": true black backgrounds instead of graphite. */
    static boolean black(Context c) { return prefs(c).getInt("darkStyle", 0) == 1; }

    static void setBlack(Context c, boolean on) { prefs(c).edit().putInt("darkStyle", on ? 1 : 0).apply(); }

    /** Colours that change with the dark style (null = use the resource). */
    static Integer override(Context c, int res) {
        if (!black(c) || !Ui.dark(c)) return null;
        if (res == R.color.bg) return 0xFF000000;
        if (res == R.color.surface) return 0xFF141414;
        if (res == R.color.surface_variant) return 0xFF1E1E1E;
        if (res == R.color.desk) return 0xFF000000;
        if (res == R.color.outline) return 0xFF262626;
        return null;
    }

    /** Titles: Inter semibold (one typeface across the app). */
    static Typeface titleFace(Context c) { Ui.initFonts(c); return Ui.font(600); }

    // ---- colour maths -------------------------------------------------------------------------

    static double luminance(int c) {
        double[] v = {Color.red(c) / 255.0, Color.green(c) / 255.0, Color.blue(c) / 255.0};
        for (int i = 0; i < 3; i++) v[i] = v[i] <= 0.03928 ? v[i] / 12.92 : Math.pow((v[i] + 0.055) / 1.055, 2.4);
        return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
    }

    static int mix(int a, int b, float t) {
        return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /** A custom accent made readable on dark backgrounds. */
    private static int lighten(int c) {
        float[] h = new float[3];
        Color.colorToHSV(c, h);
        h[2] = Math.max(h[2], 0.85f);
        h[1] = Math.min(h[1], 0.6f);
        return Color.HSVToColor(h);
    }
}

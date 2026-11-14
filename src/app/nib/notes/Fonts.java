package app.nib.notes;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * Fonts for text boxes: four built in (Inter, Serif, Mono, Handwriting) plus fonts the owner imports (.ttf / .otf).
 * Imported fonts are copied into files/fonts (outside the notebooks folder, included in backups as "_fonts/").
 * A font id is "" (Inter), "serif", "mono", "hand", or "u:" + the file name of an imported font.
 * A text box whose font is missing (removed, or a notebook from another tablet) falls back to Inter.
 */
final class Fonts {
    static final String INTER = "", SERIF = "serif", MONO = "mono", HAND = "hand", USER = "u:";
    static final String BACKUP_DIR = "_fonts/";
    /** Page units per typographic point (a page is 1000 units across an A4 width of 210 mm; 1 pt = 0.3528 mm). */
    static final float UNITS_PER_PT = Notebook.PW / 210f * 0.35278f;
    static final float PT_MIN = 6, PT_MAX = 96;
    private static final String[] BUILT_IN = {INTER, SERIF, MONO, HAND};
    private static final String[] BUILT_IN_NAMES = {"Inter", "Serif", "Mono", "Handwriting"};

    private static File dir;
    private static final HashMap<String, Typeface> cache = new HashMap<>();

    static void init(Context c) { if (dir == null) dir = new File(c.getApplicationContext().getFilesDir(), "fonts"); }

    static File dir(Context c) { init(c); return dir; }

    /** Built-in fonts first, then imported ones by name. */
    static List<String> list(Context c) {
        ArrayList<String> out = new ArrayList<>(Arrays.asList(BUILT_IN));
        String[] files = dir(c).list();
        if (files != null) {
            Arrays.sort(files, String.CASE_INSENSITIVE_ORDER);
            for (String f : files) if (!f.startsWith(".")) out.add(USER + f);
        }
        return out;
    }

    static boolean imported(String id) { return id != null && id.startsWith(USER); }

    static String name(String id) {
        if (id == null) id = INTER;
        for (int i = 0; i < BUILT_IN.length; i++) if (BUILT_IN[i].equals(id)) return BUILT_IN_NAMES[i];
        String f = id.substring(USER.length());
        int dot = f.lastIndexOf('.');
        if (dot > 0) f = f.substring(0, dot);
        return f.replace('_', ' ').replace('-', ' ').replaceAll("\\s+", " ").trim();
    }

    /** The base face of a font (regular); null when an imported font is missing. */
    private static Typeface base(String id) {
        if (id == null || id.isEmpty()) return null;
        switch (id) {
            case SERIF: return Typeface.SERIF;
            case MONO: return Typeface.MONOSPACE;
            case HAND: return Ui.hand();
        }
        if (!imported(id) || dir == null) return null;
        synchronized (cache) {
            if (cache.containsKey(id)) return cache.get(id);
            Typeface t = null;
            File f = new File(dir, id.substring(USER.length()));
            if (f.isFile()) {
                try {
                    // fall back to Inter for characters the font doesn't have (Gurmukhi, Devanagari, Arabic…)
                    android.graphics.fonts.Font font = new android.graphics.fonts.Font.Builder(f).build();
                    t = new Typeface.CustomFallbackBuilder(new android.graphics.fonts.FontFamily.Builder(font).build())
                            .setSystemFallback("sans-serif").build();
                } catch (Exception e) {
                    t = null;
                }
            }
            cache.put(id, t);
            return t;
        }
    }

    /**
     * The face to draw a text box with. An empty font id means the box's usual face: Inter, or the handwriting
     * face for text converted from handwriting.
     */
    static Typeface face(String id, boolean converted, boolean bold, boolean italic) {
        Typeface b = base(id);
        if (b == null) b = converted ? Ui.hand() : Ui.font(400);
        if (!bold && !italic) return b;
        return Typeface.create(b, bold ? 700 : 400, italic);
    }

    /** Copies a font the owner picked into Nib; returns its id. Throws when it isn't a font Android can use. */
    static String importFont(Context c, Uri uri) throws IOException {
        String display = "Font.ttf";
        try (Cursor cur = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && cur.getString(0) != null) display = cur.getString(0);
        } catch (Exception ignored) { }
        String lower = display.toLowerCase(java.util.Locale.ROOT);
        String ext = lower.endsWith(".otf") ? ".otf" : ".ttf";
        String stem = display.replaceAll("(?i)\\.(ttf|otf)$", "").replaceAll("[^A-Za-z0-9 ._-]", "").trim();
        if (stem.isEmpty()) stem = "Font";
        File d = dir(c);
        d.mkdirs();
        File out = new File(d, stem + ext);
        for (int k = 2; out.exists(); k++) out = new File(d, stem + " " + k + ext);
        File tmp = new File(d, ".import.tmp");
        try (InputStream in = c.getContentResolver().openInputStream(uri); FileOutputStream o = new FileOutputStream(tmp)) {
            if (in == null) throw new IOException("can't open");
            Io.pipe(in, o);
        }
        try {
            new android.graphics.fonts.Font.Builder(tmp).build(); // throws if it isn't a usable font
        } catch (Exception e) {
            tmp.delete();
            throw new IOException("not a font", e);
        }
        if (!tmp.renameTo(out)) { tmp.delete(); throw new IOException("rename failed"); }
        return USER + out.getName();
    }

    static void remove(Context c, String id) {
        if (!imported(id)) return;
        new File(dir(c), id.substring(USER.length())).delete();
        synchronized (cache) { cache.remove(id); }
    }

    /** Restoring a backup: an imported font comes back unless one with that name is already here. */
    static void restoreFile(Context c, String name, InputStream in) throws IOException {
        if (name.isEmpty() || name.contains("/") || name.startsWith(".")) return;
        File d = dir(c);
        d.mkdirs();
        File f = new File(d, name);
        if (f.exists()) return;
        try (FileOutputStream o = new FileOutputStream(f)) { Io.pipe(in, o); }
        synchronized (cache) { cache.remove(USER + name); }
    }

    /** A style with its per-word sizes scaled (a box resized with the lasso). */
    static String scaled(String style, float s) {
        if (style == null || s == 1f || !style.contains(",z=")) return style;
        Style st = Style.parse(style);
        for (Run r : st.runs) if (r.size != null) r.size = r.size * s;
        return st.encode();
    }

    // ---- text style: the box's look, plus per-word overrides ---------------------------------------

    /**
     * How a text box looks besides its size and colour: font, bold / italic / underline / strike, alignment,
     * and runs: stretches of the text (words you selected) that override any of font, bold, italic, underline,
     * strike, size and colour. What a run doesn't set comes from the box, so restyling the whole box still
     * reaches them.
     */
    static final class Style {
        static final int LEFT = 0, CENTRE = 1, RIGHT = 2;
        String font = INTER;
        boolean bold, italic, underline, strike;
        int align = LEFT;
        final ArrayList<Run> runs = new ArrayList<>();

        /**
         * Stored as "font|flags|align" (flags: b i u s), then one line per run:
         * "start,end" followed by ",f=font" ",b=1" ",i=0" ",u=1" ",s=0" ",z=size" ",c=colour(hex)" for what it sets.
         * null for the default look.
         */
        static Style parse(String s) {
            Style st = new Style();
            if (s == null || s.isEmpty()) return st;
            String[] lines = s.split("\n");
            String[] p = lines[0].split("\\|", -1);
            st.font = p[0];
            if (p.length > 1) {
                st.bold = p[1].indexOf('b') >= 0;
                st.italic = p[1].indexOf('i') >= 0;
                st.underline = p[1].indexOf('u') >= 0;
                st.strike = p[1].indexOf('s') >= 0;
            }
            if (p.length > 2) try { st.align = Math.max(0, Math.min(2, Integer.parseInt(p[2]))); } catch (NumberFormatException ignored) { }
            for (int i = 1; i < lines.length; i++) {
                Run r = Run.parse(lines[i]);
                if (r != null) st.runs.add(r);
            }
            return st;
        }

        String encode() {
            String f = (bold ? "b" : "") + (italic ? "i" : "") + (underline ? "u" : "") + (strike ? "s" : "");
            StringBuilder b = new StringBuilder();
            for (Run r : runs) if (r.end > r.start && !r.empty()) b.append('\n').append(r.encode());
            if ((font == null || font.isEmpty()) && f.isEmpty() && align == LEFT && b.length() == 0) return null;
            return (font == null ? "" : font) + "|" + f + "|" + align + b;
        }

        Style copy() { return parse(encode()); }

        /** The box without its runs (the look new boxes start from). */
        Style boxOnly() { Style c = copy(); c.runs.clear(); return c; }

        Typeface face(boolean converted) { return Fonts.face(font, converted, bold, italic); }

        android.text.Layout.Alignment alignment() {
            return align == CENTRE ? android.text.Layout.Alignment.ALIGN_CENTER
                    : align == RIGHT ? android.text.Layout.Alignment.ALIGN_OPPOSITE : android.text.Layout.Alignment.ALIGN_NORMAL;
        }

        /** text with its runs as spans (sizes × scale: 1 on the page, the zoom in the editor; colours via ink). */
        android.text.SpannableStringBuilder spanned(String text, boolean converted, float scale, java.util.function.IntUnaryOperator ink) {
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(text);
            for (Run r : runs) {
                int a = Math.max(0, Math.min(text.length(), r.start)), e = Math.max(0, Math.min(text.length(), r.end));
                if (e > a) sb.setSpan(new RunSpan(r.copy(), this, converted, scale, ink), a, e, android.text.Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
            }
            return sb;
        }
    }

    /** One stretch of text with its own look; null fields come from the box. */
    static final class Run {
        int start, end;
        String font;
        Boolean bold, italic, underline, strike;
        Float size;     // units
        Integer color;

        boolean empty() { return font == null && bold == null && italic == null && underline == null && strike == null && size == null && color == null; }

        Run copy() {
            Run r = new Run();
            r.start = start; r.end = end; r.font = font; r.bold = bold; r.italic = italic;
            r.underline = underline; r.strike = strike; r.size = size; r.color = color;
            return r;
        }

        /** Same look (positions aside). */
        boolean sameLook(Run o) {
            return o != null && java.util.Objects.equals(font, o.font) && java.util.Objects.equals(bold, o.bold)
                    && java.util.Objects.equals(italic, o.italic) && java.util.Objects.equals(underline, o.underline)
                    && java.util.Objects.equals(strike, o.strike) && java.util.Objects.equals(size, o.size)
                    && java.util.Objects.equals(color, o.color);
        }

        String encode() {
            StringBuilder b = new StringBuilder().append(start).append(',').append(end);
            if (font != null) b.append(",f=").append(font);
            if (bold != null) b.append(",b=").append(bold ? 1 : 0);
            if (italic != null) b.append(",i=").append(italic ? 1 : 0);
            if (underline != null) b.append(",u=").append(underline ? 1 : 0);
            if (strike != null) b.append(",s=").append(strike ? 1 : 0);
            if (size != null) b.append(",z=").append(size);
            if (color != null) b.append(",c=").append(Integer.toHexString(color));
            return b.toString();
        }

        static Run parse(String s) {
            try {
                String[] p = s.split(",");
                Run r = new Run();
                r.start = Integer.parseInt(p[0]);
                r.end = Integer.parseInt(p[1]);
                for (int i = 2; i < p.length; i++) {
                    if (p[i].length() < 2 || p[i].charAt(1) != '=') continue;
                    String v = p[i].substring(2);
                    switch (p[i].charAt(0)) {
                        case 'f': r.font = v; break;
                        case 'b': r.bold = v.equals("1"); break;
                        case 'i': r.italic = v.equals("1"); break;
                        case 'u': r.underline = v.equals("1"); break;
                        case 's': r.strike = v.equals("1"); break;
                        case 'z': r.size = Float.parseFloat(v); break;
                        case 'c': r.color = (int) Long.parseLong(v, 16); break;
                    }
                }
                return r.end > r.start ? r : null;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** Draws a run: whatever it doesn't set is taken from its box (live, so box changes reach it). */
    static final class RunSpan extends android.text.style.MetricAffectingSpan {
        final Run run;
        final Style box;
        final boolean converted;
        final float scale;
        final java.util.function.IntUnaryOperator ink;

        RunSpan(Run run, Style box, boolean converted, float scale, java.util.function.IntUnaryOperator ink) {
            this.run = run; this.box = box; this.converted = converted; this.scale = scale; this.ink = ink;
        }

        boolean bold() { return run.bold != null ? run.bold : box.bold; }
        boolean italic() { return run.italic != null ? run.italic : box.italic; }
        boolean underline() { return run.underline != null ? run.underline : box.underline; }
        boolean strike() { return run.strike != null ? run.strike : box.strike; }
        String font() { return run.font != null ? run.font : box.font; }

        private void apply(android.text.TextPaint tp) {
            tp.setTypeface(Fonts.face(font(), converted, bold(), italic()));
            if (run.size != null) tp.setTextSize(run.size * scale);
        }

        @Override public void updateMeasureState(android.text.TextPaint tp) { apply(tp); }

        @Override public void updateDrawState(android.text.TextPaint tp) {
            apply(tp);
            tp.setUnderlineText(underline());
            tp.setStrikeThruText(strike());
            if (run.color != null) tp.setColor(ink != null ? ink.applyAsInt(run.color) : run.color);
        }
    }
}

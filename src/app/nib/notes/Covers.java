package app.nib.notes;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

/**
 * Notebook covers: a design (pattern) in a colour, with the notebook's title on a label. Stored in meta.properties as
 * "cover=design:colour" (no cover = no key), so ink.bin doesn't change and older Nib simply ignores it. Shown on the
 * notes screen instead of the first page, and inside the notebook as a page before page 1 (not on infinite canvases).
 */
final class Covers {
    private Covers() {}

    /** Drawn here: plain … chevron. Then patterns from Hero Patterns (heropatterns.com, CC BY 4.0: licenses/), as tiles. */
    static final String[] DESIGNS = {"plain", "linen", "stripes", "dots", "grid", "chevron",
            "overlappingHexagons", "wiggle", "jigsaw", "overlappingCircles", "hexagons", "moroccan", "endlessClouds", "leaf", "bubbles",
            "temple", "glamorous", "linesInMotion", "happyIntersection", "morphingDiamonds", "lisbon", "autumn", "bamboo", "aztec",
            "houndstooth", "fourPointStars", "circuitBoard", "connections", "randomShapes", "zigZag", "formalInvitation",
            "bankNote", "jupiter", "kiwi"};
    private static final int HERO_FROM = 6;
    private static final int[] HERO_RES = {R.drawable.hp_overlapping_hexagons, R.drawable.hp_wiggle, R.drawable.hp_jigsaw,
            R.drawable.hp_overlapping_circles, R.drawable.hp_hexagons, R.drawable.hp_moroccan, R.drawable.hp_endless_clouds,
            R.drawable.hp_leaf, R.drawable.hp_bubbles, R.drawable.hp_temple, R.drawable.hp_glamorous, R.drawable.hp_lines_in_motion,
            R.drawable.hp_happy_intersection, R.drawable.hp_morphing_diamonds, R.drawable.hp_lisbon, R.drawable.hp_autumn,
            R.drawable.hp_bamboo, R.drawable.hp_aztec, R.drawable.hp_houndstooth, R.drawable.hp_four_point_stars,
            R.drawable.hp_circuit_board, R.drawable.hp_connections, R.drawable.hp_random_shapes, R.drawable.hp_zig_zag,
            R.drawable.hp_formal_invitation, R.drawable.hp_bank_note, R.drawable.hp_jupiter, R.drawable.hp_kiwi};
    /** Hero tiles are sized in CSS px; this many page units per px reads like the website on a notebook cover. */
    private static final float HERO_SCALE = 2.4f;

    /** "endlessClouds" → "Endless clouds". */
    static String designName(int i) {
        String n = DESIGNS[i].replaceAll("([A-Z])", " $1").toLowerCase(java.util.Locale.US);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
    static final int[] COLORS = {0xff2d3e63, 0xff2f5d50, 0xff7a2e3b, 0xff2b6f7e, 0xffc8952e, 0xffd06a4e, 0xff5e3f73,
            0xff55606e, 0xffcdbb98, 0xff2b2d31};

    /** A cover value: design index and colour index, or null for "no cover". */
    static int[] parse(String cover) {
        if (cover == null || cover.isEmpty()) return null;
        String[] f = cover.split(":");
        int d = 0, c = 0;
        for (int i = 0; i < DESIGNS.length; i++) if (DESIGNS[i].equals(f[0])) d = i;
        try { if (f.length > 1) c = Math.floorMod(Integer.parseInt(f[1]), COLORS.length); } catch (NumberFormatException ignored) { }
        return new int[]{d, c};
    }

    static String value(int design, int color) { return DESIGNS[design] + ":" + color; }

    static String random() { return value(0, (int) (Math.random() * COLORS.length)); }

    /** The cover at wPx wide (page proportions), with the title on its label (null title = no label). */
    static Bitmap bitmap(Context ctx, String cover, String title, int wPx) {
        int hPx = Math.round(wPx * Notebook.PH / Notebook.PW);
        Bitmap b = Bitmap.createBitmap(Math.max(1, wPx), Math.max(1, hPx), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float k = wPx / Notebook.PW;
        c.scale(k, k);
        draw(ctx, c, cover, title, Notebook.PH, k);
        return b;
    }

    /** Draws the cover into page units (PW wide, h high); k = device px per unit (for the pattern tiles). */
    static void draw(Context ctx, Canvas c, String cover, String title, float h, float k) {
        int[] v = parse(cover);
        if (v == null) return;
        float W = Notebook.PW;
        int base = COLORS[v[1]];
        boolean light = lum(base) > 0.55f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(base);
        c.drawRect(0, 0, W, h, p);
        // the pattern, in a lighter (or, on light colours, darker) tone of the colour
        Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
        q.setColor(light ? 0x1a000000 : 0x1fffffff);
        q.setStrokeWidth(3);
        switch (DESIGNS[v[0]]) {
            case "linen":
                q.setStrokeWidth(1.2f);
                q.setColor(light ? 0x12000000 : 0x14ffffff);
                for (float x = 0; x < W; x += 6) c.drawLine(x, 0, x, h, q);
                for (float y = 0; y < h; y += 6) c.drawLine(0, y, W, y, q);
                break;
            case "stripes":
                q.setStrokeWidth(34);
                for (float x = -h; x < W + h; x += 96) c.drawLine(x, 0, x + h, h, q);
                break;
            case "dots":
                for (float y = 40, row = 0; y < h; y += 70, row++)
                    for (float x = (row % 2 == 0 ? 40 : 75); x < W; x += 70) c.drawCircle(x, y, 9, q);
                break;
            case "grid":
                q.setStrokeWidth(2);
                for (float x = 50; x < W; x += 50) c.drawLine(x, 0, x, h, q);
                for (float y = 50; y < h; y += 50) c.drawLine(0, y, W, y, q);
                break;
            case "chevron": {
                q.setStyle(Paint.Style.STROKE);
                q.setStrokeWidth(14);
                q.setStrokeJoin(Paint.Join.MITER);
                for (float y = 30; y < h + 60; y += 90) {
                    Path path = new Path();
                    path.moveTo(-60, y);
                    for (float x = -60; x < W + 60; x += 120) { path.lineTo(x + 60, y - 45); path.lineTo(x + 120, y); }
                    c.drawPath(path, q);
                }
                break;
            }
            default: // plain, or a Hero pattern laid as repeating tiles
                if (v[0] >= HERO_FROM) heroTiles(ctx, c, HERO_RES[v[0] - HERO_FROM], light ? 0x1f000000 : 0x2effffff, W, h, k);
                break;
        }
        // the spine: a darker band with a thin highlight, like a bound notebook
        p.setColor(shade(base, 0.78f));
        c.drawRect(0, 0, 64, h, p);
        p.setColor(light ? 0x22000000 : 0x26ffffff);
        c.drawRect(64, 0, 67, h, p);
        if (title == null) return;
        // the label, with the title
        RectF lab = new RectF(190, 390, 810, 560);
        p.setColor(0xf7fbfaf7);
        p.setShadowLayer(10, 0, 3, 0x40000000);
        c.drawRoundRect(lab, 16, 16, p);
        p.clearShadowLayer();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2);
        p.setColor(shade(base, 1f) & 0x55ffffff);
        c.drawRoundRect(new RectF(lab.left + 12, lab.top + 12, lab.right - 12, lab.bottom - 12), 10, 10, p);
        p.setStyle(Paint.Style.FILL);
        TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        t.setTypeface(Ui.font(650));
        t.setColor(0xff23262d);
        t.setTextAlign(Paint.Align.CENTER);
        float size = 58;
        t.setTextSize(size);
        while (size > 34 && t.measureText(title) > lab.width() - 70) { size -= 2; t.setTextSize(size); }
        CharSequence s = TextUtils.ellipsize(title, t, lab.width() - 70, TextUtils.TruncateAt.END);
        Paint.FontMetrics fm = t.getFontMetrics();
        c.drawText(s, 0, s.length(), lab.centerX(), lab.centerY() - (fm.ascent + fm.descent) / 2, t);
    }

    private static void heroTiles(Context ctx, Canvas c, int res, int argb, float W, float h, float k) {
        android.graphics.drawable.Drawable d = ctx.getDrawable(res);
        if (d == null) return;
        // its own copy: covers are drawn on several threads at once, and tint, alpha and the vector's native
        // render cache would otherwise be shared between them
        d = d.mutate();
        float tw = d.getIntrinsicWidth() / ctx.getResources().getDisplayMetrics().density * HERO_SCALE; // tile, page units
        float th = d.getIntrinsicHeight() / ctx.getResources().getDisplayMetrics().density * HERO_SCALE;
        int bw = Math.max(1, Math.round(tw * k)), bh = Math.max(1, Math.round(th * k));
        Bitmap tile = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, bw, bh);
        d.setTint(argb | 0xff000000);
        d.setAlpha(Color.alpha(argb));
        d.draw(new Canvas(tile));
        android.graphics.BitmapShader sh = new android.graphics.BitmapShader(tile, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT);
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(tw / bw, th / bh); // tile pixels → page units
        sh.setLocalMatrix(m);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(sh);
        c.drawRect(0, 0, W, h, p);
    }

    private static float lum(int c) { return (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f; }

    private static int shade(int c, float k) {
        return Color.argb(255, Math.round(Color.red(c) * k), Math.round(Color.green(c) * k), Math.round(Color.blue(c) * k));
    }

    // ---- picker --------------------------------------------------------------------------------------------------

    /** Colour swatches, design tiles and "No cover"; each choice is applied at once through pick (null = no cover). */
    static void showPicker(Context c, View anchor, String current, String title, java.util.function.Consumer<String> pick) {
        Ui.Popover p = new Ui.Popover(c, 360);
        p.title("Cover");
        p.add(picker(c, current, pick));
        p.add(Ui.actionRow(c, R.drawable.ic_close, "No cover", "The notes screen shows the first page", v -> { p.dismiss(); pick.accept(null); }));
        p.show(anchor);
    }

    /** The colour + design chooser on its own (also used by the new-notebook sheet). */
    static View picker(Context c, String current, java.util.function.Consumer<String> pick) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int[] cur = parse(current);
        final int[] sel = cur != null ? cur.clone() : new int[]{0, 0};
        ImageView[] tiles = new ImageView[DESIGNS.length];
        Runnable refresh = () -> {
            for (int i = 0; i < tiles.length; i++) {
                tiles[i].setImageBitmap(bitmap(c, value(i, sel[1]), null, Ui.dp(c, 44)));
                tiles[i].setBackground(Ui.round(i == sel[0] ? Ui.color(c, R.color.accent) : 0x00000000, Ui.dp(c, 6)));
            }
        };
        col.addView(label(c, "Colour"));
        col.addView(Ui.colorGrid(c, COLORS, cur != null ? COLORS[cur[1]] : 0, colr -> {
            for (int i = 0; i < COLORS.length; i++) if (COLORS[i] == colr) sel[1] = i;
            pick.accept(value(sel[0], sel[1]));
            refresh.run();
        }));
        col.addView(label(c, "Design"));
        Ui.Grid grid = new Ui.Grid(c, 46, 6);
        for (int i = 0; i < DESIGNS.length; i++) {
            final int d = i;
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setContentDescription(designName(i));
            iv.setTooltipText(designName(i));
            iv.setPadding(Ui.dp(c, 3), Ui.dp(c, 3), Ui.dp(c, 3), Ui.dp(c, 3));
            iv.setOnClickListener(v -> { sel[0] = d; pick.accept(value(sel[0], sel[1])); refresh.run(); });
            iv.setLayoutParams(new android.view.ViewGroup.LayoutParams(-1, Ui.dp(c, 64)));
            grid.addView(iv);
            tiles[i] = iv;
        }
        refresh.run();
        col.addView(grid);
        return col;
    }

    private static View label(Context c, String t) {
        android.widget.TextView tv = Ui.text(c, t, 12.5f, Ui.color(c, R.color.on_surface_variant), Ui.medium());
        tv.setPadding(0, Ui.dp(c, 12), 0, Ui.dp(c, 8));
        return tv;
    }
}

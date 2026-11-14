package app.nib.notes;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Settings → Theme: mode (System / Light / Dark) with little previews, accent colour (8 + custom),
 * and dark style (Graphite / Black). Each change is applied at once: the screen
 * restyles itself and the sheet comes back on top, so what you see is the real thing.
 */
final class ThemeSheet {
    private ThemeSheet() {}

    private static boolean reopen;

    /** Call from onResume: shows the sheet again after a change restyled the activity. */
    static void reopenIfPending(Activity a, Runnable onChanged) {
        if (!reopen) return;
        reopen = false;
        show(a, onChanged);
    }

    static void show(Activity a, Runnable onChanged) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(a, 28), Ui.dp(a, 22), Ui.dp(a, 28), Ui.dp(a, 26));
        box.setBackground(Ui.round(Ui.color(a, R.color.surface), Ui.dp(a, 28)));
        Runnable apply = () -> { reopen = true; d.dismiss(); onChanged.run(); };

        // header
        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(a, "Theme", 26, Ui.color(a, R.color.on_surface), Theme.titleFace(a));
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView done = Ui.text(a, "Done", 16, Ui.color(a, R.color.accent), Ui.bold());
        done.setPadding(Ui.dp(a, 10), Ui.dp(a, 8), Ui.dp(a, 4), Ui.dp(a, 8));
        done.setOnClickListener(v -> d.dismiss());
        head.addView(done);
        box.addView(head);

        // mode
        LinearLayout modes = new LinearLayout(a);
        String[] mn = {"System", "Light", "Dark"};
        int cur = Theme.get(a);
        for (int i = 0; i < 3; i++) {
            final int m = i;
            LinearLayout tile = new LinearLayout(a);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setPadding(Ui.dp(a, 10), Ui.dp(a, 10), Ui.dp(a, 10), Ui.dp(a, 10));
            GradientDrawable g = Ui.round(Ui.color(a, R.color.surface_variant), Ui.dp(a, 18));
            if (cur == i) g.setStroke(Ui.dp(a, 2), Ui.color(a, R.color.accent));
            tile.setBackground(Ui.ripple(a, g, Ui.dp(a, 18)));
            tile.addView(new Preview(a, i, Theme.black(a)), new LinearLayout.LayoutParams(-1, Ui.dp(a, 88)));
            TextView l = Ui.text(a, mn[i], 14.5f, Ui.color(a, R.color.on_surface), Ui.bold());
            l.setPadding(Ui.dp(a, 2), Ui.dp(a, 10), 0, 0);
            tile.addView(l);
            tile.setOnClickListener(v -> {
                if (m == Theme.get(a)) return;
                reopen = true;
                d.dismiss();
                Theme.set(a, m); // Android restyles the activity itself
            });
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) tl.leftMargin = Ui.dp(a, 12);
            modes.addView(tile, tl);
        }
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2);
        ml.topMargin = Ui.dp(a, 18);
        box.addView(modes, ml);

        // accent
        box.addView(caption(a, "Accent"), capLp(a));
        LinearLayout sw = new LinearLayout(a);
        int ai = Theme.accentIndex(a);
        for (int i = 0; i < Theme.ACC_LIGHT.length; i++) {
            final int k = i;
            Swatch s = new Swatch(a, (Ui.dark(a) ? Theme.ACC_DARK : Theme.ACC_LIGHT)[i], false, ai == i);
            s.setContentDescription(Theme.ACC_NAMES[i]);
            s.setOnClickListener(v -> { if (k != Theme.accentIndex(a)) { Theme.setAccent(a, k, 0); apply.run(); } });
            sw.addView(s, swLp(a, i));
        }
        Swatch custom = new Swatch(a, Theme.customAccent(a), true, ai < 0);
        custom.setContentDescription("Custom colour");
        custom.setOnClickListener(v -> pickCustom(a, apply));
        sw.addView(custom, swLp(a, 1));
        box.addView(sw);
        TextView name = Ui.text(a, (ai < 0 ? "Custom" : Theme.ACC_NAMES[Math.min(ai, Theme.ACC_NAMES.length - 1)])
                + " · selected items, buttons and links. Your ink colours stay as they are.", 13.5f, Ui.color(a, R.color.on_surface_variant), null);
        name.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, -2);
        nl.topMargin = Ui.dp(a, 12);
        box.addView(name, nl);

        // dark style
        box.addView(caption(a, "Dark style"), capLp(a));
        box.addView(seg(a, new String[]{"Graphite", "Black"}, Theme.black(a) ? 1 : 0, i -> { Theme.setBlack(a, i == 1); apply.run(); }), new LinearLayout.LayoutParams(-2, -2));

        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(Math.min(Ui.dp(a, 560), a.getResources().getDisplayMetrics().widthPixels - Ui.dp(a, 32)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
    }

    private static TextView caption(Context c, String s) {
        TextView t = Ui.text(c, s.toUpperCase(java.util.Locale.ROOT), 12.5f, Ui.color(c, R.color.on_surface_variant), Ui.medium());
        t.setLetterSpacing(0.08f);
        return t;
    }

    private static LinearLayout.LayoutParams capLp(Context c) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
        l.topMargin = Ui.dp(c, 24);
        l.bottomMargin = Ui.dp(c, 12);
        return l;
    }

    private static LinearLayout.LayoutParams swLp(Context c, int i) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44));
        if (i > 0) l.leftMargin = Ui.dp(c, 10);
        return l;
    }

    interface IntCb { void on(int i); }

    /** Segmented choice: grey track, the chosen option raised. */
    private static View seg(Context c, String[] opts, int sel, IntCb cb) {
        LinearLayout s = new LinearLayout(c);
        s.setPadding(Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4));
        s.setBackground(Ui.round(Ui.color(c, R.color.surface_variant), Ui.dp(c, 14)));
        for (int i = 0; i < opts.length; i++) {
            final int k = i;
            TextView t = Ui.text(c, opts[i], 14.5f, Ui.color(c, i == sel ? R.color.on_surface : R.color.on_surface_variant), Ui.bold());
            t.setGravity(Gravity.CENTER);
            t.setPadding(Ui.dp(c, 18), 0, Ui.dp(c, 18), 0);
            if (i == sel) {
                t.setBackground(Ui.round(Ui.color(c, R.color.surface), Ui.dp(c, 10)));
                t.setElevation(Ui.dp(c, 1));
            }
            t.setOnClickListener(v -> { if (k != sel) cb.on(k); });
            s.addView(t, new LinearLayout.LayoutParams(-2, Ui.dp(c, 40)));
        }
        return s;
    }

    /** Custom accent: hue, colourfulness and brightness, with a live dot. */
    private static void pickCustom(Activity a, Runnable apply) {
        float[] hsv = new float[3];
        Color.colorToHSV(Theme.customAccent(a), hsv);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(a, 24), Ui.dp(a, 12), Ui.dp(a, 24), 0);
        View dot = new View(a);
        GradientDrawable dg = new GradientDrawable();
        dg.setShape(GradientDrawable.OVAL);
        dg.setColor(Color.HSVToColor(hsv));
        dot.setBackground(dg);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Ui.dp(a, 56), Ui.dp(a, 56));
        dl.gravity = Gravity.CENTER_HORIZONTAL;
        dl.bottomMargin = Ui.dp(a, 12);
        box.addView(dot, dl);
        String[] labels = {"Hue", "Colourfulness", "Brightness"};
        int[] max = {359, 100, 100};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            box.addView(Ui.text(a, labels[i], 13, Ui.color(a, R.color.on_surface_variant), Ui.medium()));
            SeekBar sb = new SeekBar(a);
            sb.setMax(max[i]);
            sb.setProgress(Math.round(i == 0 ? hsv[0] : hsv[i] * 100));
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                    hsv[k] = k == 0 ? p : Math.max(0.15f, p / 100f);
                    dg.setColor(Color.HSVToColor(hsv));
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });
            box.addView(sb, new LinearLayout.LayoutParams(-1, Ui.dp(a, 44)));
        }
        new AlertDialog.Builder(a).setTitle("Custom accent").setView(box)
                .setPositiveButton("Use", (dd, w) -> { Theme.setAccent(a, -1, Color.HSVToColor(hsv)); apply.run(); })
                .setNegativeButton("Cancel", null).show();
    }

    /** A colour dot; the chosen one gets a ring. The custom dot is a colour wheel until it's in use. */
    private static final class Swatch extends View {
        private final int color;
        private final boolean custom, sel;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Swatch(Context c, int color, boolean custom, boolean sel) {
            super(c);
            this.color = color;
            this.custom = custom;
            this.sel = sel;
            setClickable(true);
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, r = Math.min(w, h) / 2;
            float inner = sel ? r - Ui.dp(getContext(), 5) : r - Ui.dp(getContext(), 1);
            if (sel) {
                p.setShader(null);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Ui.dp(getContext(), 2.2f));
                p.setColor(Ui.color(getContext(), R.color.on_surface));
                cv.drawCircle(cx, cy, r - Ui.dp(getContext(), 1.2f), p);
            }
            p.setStyle(Paint.Style.FILL);
            if (custom && !sel) {
                p.setShader(new SweepGradient(cx, cy, new int[]{0xFFC2603F, 0xFFC99A2E, 0xFF5E8C5B, 0xFF2A8C8A, 0xFF3C6FD8, 0xFF7F5BD6, 0xFFC2457A, 0xFFC2603F}, null));
            } else {
                p.setShader(null);
                p.setColor(color);
            }
            cv.drawCircle(cx, cy, inner, p);
            p.setShader(null);
            if (sel) { // tick
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Ui.dp(getContext(), 2.2f));
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                p.setColor(Theme.luminance(color) > 0.42 ? 0xFF161616 : 0xFFFFFFFF);
                Path t = new Path();
                float u = inner * 0.36f;
                t.moveTo(cx - u, cy + u * 0.05f);
                t.lineTo(cx - u * 0.25f, cy + u * 0.75f);
                t.lineTo(cx + u, cy - u * 0.6f);
                cv.drawPath(t, p);
            }
        }
    }

    /** Thumbnail of the notes screen in light, dark, or both (System). */
    private static final class Preview extends View {
        private final int kind;
        private final boolean black;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();

        Preview(Context c, int kind, boolean black) { super(c); this.kind = kind; this.black = black; }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), r = Ui.dp(getContext(), 12);
            clip.reset();
            clip.addRoundRect(new RectF(0, 0, w, h), r, r, Path.Direction.CW);
            cv.save();
            cv.clipPath(clip);
            if (kind == 0) {
                draw(cv, w, h, false);
                Path half = new Path();
                half.moveTo(w * 0.62f, 0); half.lineTo(w, 0); half.lineTo(w, h); half.lineTo(w * 0.38f, h); half.close();
                cv.save();
                cv.clipPath(half);
                draw(cv, w, h, true);
                cv.restore();
            } else draw(cv, w, h, kind == 2);
            cv.restore();
        }

        private void draw(Canvas cv, float w, float h, boolean dark) {
            int bg = dark ? (black ? 0xFF000000 : 0xFF161616) : 0xFFF6F4EF;
            int bar = dark ? 0xFF2C2C2C : 0xFFE3DED5, card = dark ? (black ? 0xFF18181A : 0xFF232325) : 0xFFFFFFFF;
            float u = Ui.dp(getContext(), 1);
            p.setColor(bg);
            cv.drawRect(0, 0, w, h, p);
            p.setColor(bar);
            cv.drawRoundRect(new RectF(12 * u, 12 * u, w * 0.5f, 21 * u), 5 * u, 5 * u, p);
            p.setColor(card);
            cv.drawRoundRect(new RectF(12 * u, h - 52 * u, 12 * u + w * 0.34f, h - 10 * u), 6 * u, 6 * u, p);
            cv.drawRoundRect(new RectF(w - 12 * u - w * 0.34f, h - 52 * u, w - 12 * u, h - 10 * u), 6 * u, 6 * u, p);
            p.setColor(Ui.color(getContext(), R.color.accent));
            cv.drawCircle(w - 19 * u, 17 * u, 7 * u, p);
        }
    }
}

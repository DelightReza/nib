package app.nib.notes;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** Small hand-rolled widget kit (no AndroidX): pills, icon buttons, swatches. */
final class Ui {
    private Ui() {}

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static int color(Context c, int res) {
        if (res == R.color.accent) return Theme.accent(c);
        if (res == R.color.on_accent) return Theme.onAccent(c);
        if (res == R.color.accent_container) return Theme.accentContainer(c);
        Integer o = Theme.override(c, res);
        return o != null ? o : c.getColor(res);
    }

    static boolean dark(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    static GradientDrawable round(int fill, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        return g;
    }

    static Drawable ripple(Context c, Drawable content, float radius) {
        int rc = dark(c) ? 0x33ffffff : 0x1f000000;
        return new RippleDrawable(ColorStateList.valueOf(rc), content, round(Color.WHITE, radius));
    }

    private static Typeface inter;

    /** Loads Inter, the one typeface Nib uses everywhere (also set as the theme font). Call once per activity. */
    static void initFonts(Context c) {
        Fonts.init(c);
        if (inter != null) return;
        try { inter = c.getResources().getFont(R.font.inter); } catch (Exception e) { inter = Typeface.DEFAULT; }
        try {
            android.content.res.Resources r = c.getResources();
            // handwriting look for converted text: Kalam (Latin + Devanagari), Baloo Paaji 2 (Gurmukhi), Aref Ruqaa (Arabic, Ruqʿah)
            android.graphics.fonts.FontFamily latin = new android.graphics.fonts.FontFamily.Builder(new android.graphics.fonts.Font.Builder(r, R.font.hand_kalam).build()).build();
            android.graphics.fonts.FontFamily guru = new android.graphics.fonts.FontFamily.Builder(new android.graphics.fonts.Font.Builder(r, R.font.hand_gurmukhi)
                    .setFontVariationSettings("'wght' 450").build()).build();
            android.graphics.fonts.FontFamily arab = new android.graphics.fonts.FontFamily.Builder(new android.graphics.fonts.Font.Builder(r, R.font.hand_arabic).build()).build();
            hand = new Typeface.CustomFallbackBuilder(latin).addCustomFallback(guru).addCustomFallback(arab).setSystemFallback("sans-serif").build();
        } catch (Exception e) { hand = inter; }
    }

    private static Typeface hand;

    /** The handwriting-style face converted text uses, in every script Nib reads. */
    static Typeface hand() { return hand != null ? hand : font(400); }

    /** Inter at a weight (400 regular, 500 medium, 600 semibold, 700 bold). */
    static Typeface font(int weight) { return Typeface.create(inter != null ? inter : Typeface.DEFAULT, weight, false); }

    static Typeface medium() { return font(500); }

    /** Semibold: headings read as confident without looking heavy. */
    static Typeface bold() { return font(600); }

    static int hairline(Context c) { return Math.max(1, Math.round(c.getResources().getDisplayMetrics().density * 0.6f)); }

    /** Rounded-rectangle button (primary = filled accent, else hairline outline). */
    static TextView button(Context c, String label, int icon, boolean primary) {
        int fg = color(c, primary ? R.color.on_accent : R.color.on_surface);
        TextView t = text(c, label, 14, fg, medium());
        t.setGravity(android.view.Gravity.CENTER);
        if (icon != 0) {
            t.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
            t.setCompoundDrawableTintList(ColorStateList.valueOf(fg));
            t.setCompoundDrawablePadding(dp(c, 6));
        }
        t.setPadding(dp(c, icon != 0 ? 12 : 16), 0, dp(c, 16), 0);
        GradientDrawable g = round(color(c, primary ? R.color.accent : R.color.surface), dp(c, 10));
        if (!primary) g.setStroke(hairline(c), color(c, R.color.outline));
        t.setBackground(ripple(c, g, dp(c, 10)));
        return t;
    }

    static TextView text(Context c, String s, float sp, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (tf != null) t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** A floating bar (selection actions): hairline card, soft shadow. */
    static void pill(View v, Context c) {
        GradientDrawable g = round(color(c, R.color.surface), dp(c, 14));
        g.setStroke(hairline(c), color(c, R.color.outline));
        v.setBackground(g);
        v.setElevation(dp(c, 8));
        int p = dp(c, 4);
        v.setPadding(p, p, p, p);
    }

    static final class IconButton extends ImageView {
        private boolean sel;
        IconButton(Context c, int icon, String desc) {
            super(c);
            setImageResource(icon);
            setScaleType(ScaleType.CENTER);
            setContentDescription(desc);
            setLayoutParams(new ViewGroup.MarginLayoutParams(dp(c, 44), dp(c, 44)));
            setSel(false);
        }
        void setSel(boolean s) {
            sel = s;
            Context c = getContext();
            int bg = s ? color(c, R.color.accent_container) : Color.TRANSPARENT;
            setBackground(ripple(c, round(bg, dp(c, 10)), dp(c, 10)));
            setImageTintList(ColorStateList.valueOf(color(c, s ? R.color.accent : R.color.icon)));
        }
        boolean isSel() { return sel; }
        void setEnabledLook(boolean on) {
            setEnabled(on);
            animate().alpha(on ? 1f : 0.32f).setDuration(120).start();
        }
    }

    static final class Swatch extends View {
        int color;
        boolean sel;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Swatch(Context c) {
            super(c);
            setLayoutParams(new ViewGroup.MarginLayoutParams(dp(c, 34), dp(c, 44)));
            setBackground(ripple(c, null, dp(c, 17)));
        }
        void set(int col, boolean s) { color = col; sel = s; invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            if (sel) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(c, 1.6f));
                p.setColor(color(c, R.color.on_surface));
                cv.drawCircle(cx, cy, dp(c, 12.5f), p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            float r = dp(c, 9);
            cv.drawCircle(cx, cy, r, p);
            // hairline so white / pale colours stay visible
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(hairline(c));
            p.setColor(dark(c) ? 0x40ffffff : 0x22000000);
            cv.drawCircle(cx, cy, r, p);
        }
    }

    /** Swatch representing "No colour" (transparent / unfilled) with a clean diagonal slash and matching active ring. */
    static final class NoColorSwatch extends View {
        boolean sel;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        NoColorSwatch(Context c) {
            super(c);
            setLayoutParams(new ViewGroup.MarginLayoutParams(dp(c, 42), dp(c, 44)));
            setBackground(ripple(c, null, dp(c, 19)));
            setContentDescription("No colour");
            setTooltipText("No colour");
        }
        void setSel(boolean s) { sel = s; invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            if (sel) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(c, 1.6f));
                p.setColor(color(c, R.color.on_surface));
                cv.drawCircle(cx, cy, dp(c, 12.5f), p);
            }
            float r = dp(c, 9);
            p.setStyle(Paint.Style.FILL);
            p.setColor(color(c, R.color.surface));
            cv.drawCircle(cx, cy, r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(c, 1.1f));
            p.setColor(dark(c) ? 0x66ffffff : 0x44000000);
            cv.drawCircle(cx, cy, r, p);
            float d = r * 0.68f;
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(dp(c, 1.8f));
            p.setColor(0xffd93025);
            cv.drawLine(cx - d, cy + d, cx + d, cy - d, p);
        }
    }

    static final class SizeDot extends View {
        float frac = 0.5f;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        SizeDot(Context c) {
            super(c);
            setLayoutParams(new ViewGroup.MarginLayoutParams(dp(c, 44), dp(c, 44)));
            setBackground(ripple(c, null, dp(c, 22)));
            setContentDescription("Stroke size");
        }
        int tint;
        float diam = -1; // ink diameter in px as it lands on the page now; -1 = a relative size (frac)
        boolean chosen;
        void set(float f) { frac = f; diam = -1; invalidate(); }
        /** Shows the real thickness: a dot as wide as the ink this size writes at the current zoom. */
        void ink(float px) { diam = px; invalidate(); }
        void chosen(boolean on) { if (chosen != on) { chosen = on; invalidate(); } }
        void tint(int c) { tint = c; invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            float cx = getWidth() / 2f, cy = getHeight() / 2f, ring = dp(c, 13f);
            float r = diam >= 0 ? Math.max(1.2f, Math.min(diam / 2f, dp(c, 9.5f))) : dp(c, 2.0f + 7.4f * frac);
            if (chosen) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(color(c, R.color.accent_container));
                cv.drawCircle(cx, cy, ring, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(c, 1.8f));
                p.setColor(color(c, R.color.accent));
                cv.drawCircle(cx, cy, ring, p);
            } else {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1f, hairline(c)));
                p.setColor(color(c, R.color.outline));
                cv.drawCircle(cx, cy, ring, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(tint != 0 ? tint : color(c, R.color.icon));
            cv.drawCircle(cx, cy, r, p);
            // Subtle hairline so pale / white tints stay visible on light surfaces
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(hairline(c));
            p.setColor(dark(c) ? 0x40ffffff : 0x26000000);
            cv.drawCircle(cx, cy, r, p);
        }
    }

    static View divider(Context c) {
        View v = new View(c);
        ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(hairline(c), dp(c, 20));
        lp.leftMargin = lp.rightMargin = dp(c, 10);
        v.setLayoutParams(lp);
        v.setBackgroundColor(color(c, R.color.outline));
        return v;
    }

    /** FrameLayout that keeps a height/width ratio. */
    static final class Aspect extends FrameLayout {
        float ratio;
        Aspect(Context c, float ratio) { super(c); this.ratio = ratio; }
        void setRatio(float r) { if (Math.abs(r - ratio) > 0.001f) { ratio = r; requestLayout(); } }
        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.round(width * ratio), MeasureSpec.EXACTLY));
        }
    }

    /** Columns of equal width; each child goes to the shortest column (a "masonry" flow), in order. */
    static final class Masonry extends ViewGroup {
        private int minCol, gap;
        private int[] colOf = new int[0], yOf = new int[0];
        Masonry(Context c, int minColDp, int gapDp) { super(c); minCol = dp(c, minColDp); gap = dp(c, gapDp); }
        void setMinCol(int dpW) { minCol = dp(getContext(), dpW); requestLayout(); }
        void setGap(int dpG) { gap = dp(getContext(), dpG); requestLayout(); }
        private int cols(int w) { return Math.max(1, (w + gap) / (minCol + gap)); } // portrait: small 3, medium 2, large 1
        @Override protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws), cols = cols(w), cw = (w - gap * (cols - 1)) / cols, n = getChildCount();
            int[] h = new int[cols];
            colOf = new int[n];
            yOf = new int[n];
            for (int i = 0; i < n; i++) {
                View ch = getChildAt(i);
                ch.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                int best = 0;
                for (int k = 1; k < cols; k++) if (h[k] < h[best] - 1) best = k;
                colOf[i] = best;
                yOf[i] = h[best];
                h[best] += ch.getMeasuredHeight() + gap;
            }
            int total = 0;
            for (int v : h) total = Math.max(total, v);
            setMeasuredDimension(w, Math.max(0, total - gap));
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int w = r - l, cols = cols(w), cw = (w - gap * (cols - 1)) / cols;
            for (int i = 0; i < getChildCount() && i < colOf.length; i++) {
                View ch = getChildAt(i);
                int x = colOf[i] * (cw + gap);
                ch.layout(x, yOf[i], x + cw, yOf[i] + ch.getMeasuredHeight());
            }
        }
    }

    /** Equal-width responsive grid. */
    static final class Grid extends ViewGroup {
        private final int minCol, gap;
        Grid(Context c) { this(c, 168, 22); }
        Grid(Context c, int minColDp, int gapDp) { super(c); minCol = dp(c, minColDp); gap = dp(c, gapDp); }
        private int cols(int w) { return Math.max(2, (w + gap) / (minCol + gap)); }
        @Override protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws), cols = cols(w), cw = (w - gap * (cols - 1)) / cols;
            int total = 0, rowH = 0, n = getChildCount();
            for (int i = 0; i < n; i++) {
                View ch = getChildAt(i);
                ch.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                rowH = Math.max(rowH, ch.getMeasuredHeight());
                if (i % cols == cols - 1 || i == n - 1) {
                    total += rowH;
                    if (i != n - 1) total += gap;
                    rowH = 0;
                }
            }
            setMeasuredDimension(w, total);
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int w = r - l, cols = cols(w), cw = (w - gap * (cols - 1)) / cols;
            int y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                int col = i % cols;
                if (col == 0 && i > 0) { y += rowH + gap; rowH = 0; }
                int x = col * (cw + gap);
                ch.layout(x, y, x + cw, y + ch.getMeasuredHeight());
                rowH = Math.max(rowH, ch.getMeasuredHeight());
            }
        }
    }

    // ---- panels ------------------------------------------------------------------------------

    /** Floating card anchored under a button (Samsung Notes / Notewise style tool panel). */
    static final class Popover {
        final android.widget.PopupWindow pw;
        final android.widget.LinearLayout body;
        final Context c;
        final int width;
        private final MaxScroll scroll;

        /** Scrolls the card's content when it's taller than the room it has (phones, a bar docked at the top). */
        private static final class MaxScroll extends android.widget.ScrollView {
            int maxH = Integer.MAX_VALUE;
            MaxScroll(Context c) { super(c); }
            @Override protected void onMeasure(int ws, int hs) {
                int mode = MeasureSpec.getMode(hs), size = MeasureSpec.getSize(hs);
                int cap = mode == MeasureSpec.UNSPECIFIED ? maxH : Math.min(size, maxH);
                super.onMeasure(ws, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
            }
        }

        Popover(Context c, int widthDp) {
            this.c = c;
            // never wider than the screen less a margin either side (a 470 dp panel on a 360 dp phone)
            width = Math.min(dp(c, widthDp), c.getResources().getDisplayMetrics().widthPixels - dp(c, 24));
            body = new android.widget.LinearLayout(c);
            body.setOrientation(android.widget.LinearLayout.VERTICAL);
            int p = dp(c, 16);
            body.setPadding(p, dp(c, 14), p, dp(c, 16));
            GradientDrawable g = round(color(c, R.color.surface), dp(c, 14));
            g.setStroke(hairline(c), color(c, R.color.outline));
            scroll = new MaxScroll(c);
            scroll.setBackground(g);
            scroll.setClipToOutline(true);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
            scroll.addView(body, new ViewGroup.LayoutParams(-1, -2));
            pw = new android.widget.PopupWindow(scroll, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
            pw.setElevation(dp(c, 12));
            pw.setOutsideTouchable(true);
            pw.setAnimationStyle(android.R.style.Animation_Dialog);
        }

        Popover title(String t) {
            TextView tv = text(c, t, 15, color(c, R.color.on_surface), bold());
            tv.setPadding(0, 0, 0, dp(c, 2));
            body.addView(tv);
            return this;
        }

        Popover section(String t) {
            TextView tv = text(c, t, 12.5f, color(c, R.color.on_surface_variant), medium());
            tv.setPadding(0, dp(c, 14), 0, dp(c, 8));
            body.addView(tv);
            return this;
        }

        Popover add(View v) { body.addView(v); return this; }

        /** Opens under the anchor, or above it when the anchor sits in the lower half (the bottom tool capsule). */
        void show(View anchor) {
            int[] loc = new int[2];
            anchor.getLocationOnScreen(loc);
            int screenW = c.getResources().getDisplayMetrics().widthPixels, screenH = c.getResources().getDisplayMetrics().heightPixels;
            int x = loc[0] + anchor.getWidth() / 2 - width / 2;
            x = Math.max(dp(c, 12), Math.min(screenW - width - dp(c, 12), x));
            int y, m = dp(c, 12);
            scroll.maxH = screenH - 2 * m; // narrowed below to the room on the chosen side
            View bar = (View) anchor.getParent(); // the floating capsule the button sits in
            while (bar != null && bar.getParent() instanceof android.widget.LinearLayout) bar = (View) bar.getParent();
            if (bar instanceof android.widget.LinearLayout && ((android.widget.LinearLayout) bar).getOrientation() == android.widget.LinearLayout.VERTICAL
                    && bar.getHeight() > bar.getWidth() * 2) { // a tool column on the left / right edge: open beside it
                int[] bl = new int[2];
                bar.getLocationOnScreen(bl);
                body.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                boolean leftEdge = bl[0] + bar.getWidth() / 2 < screenW / 2;
                x = leftEdge ? bl[0] + bar.getWidth() + dp(c, 10) : bl[0] - width - dp(c, 10);
                int bh = Math.min(body.getMeasuredHeight(), scroll.maxH);
                y = loc[1] + anchor.getHeight() / 2 - bh / 2;
                y = Math.max(m, Math.min(screenH - bh - m, y));
            } else if (loc[1] > screenH / 2) {
                // measure so the card's bottom edge lands just above the capsule
                View host = (View) anchor.getParent(); // climb to the floating capsule itself
                while (host != null && host.getParent() instanceof android.widget.LinearLayout) host = (View) host.getParent();
                int[] hl = new int[2];
                if (host != null) host.getLocationOnScreen(hl); else hl[1] = loc[1];
                body.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                scroll.maxH = Math.max(dp(c, 120), hl[1] - dp(c, 10) - m - topInset(anchor));
                y = Math.max(m + topInset(anchor), hl[1] - Math.min(body.getMeasuredHeight(), scroll.maxH) - dp(c, 10));
            } else {
                y = loc[1] + anchor.getHeight() + dp(c, 8);
                scroll.maxH = Math.max(dp(c, 120), screenH - y - m);
            }
            pw.showAtLocation(anchor, android.view.Gravity.TOP | android.view.Gravity.START, x, y);
        }

        void dismiss() { pw.dismiss(); }

        /** Status bar height, so a tall card opening upwards stops below it. */
        private static int topInset(View v) {
            android.view.WindowInsets wi = v.getRootWindowInsets();
            return wi == null ? 0 : wi.getInsets(android.view.WindowInsets.Type.statusBars()).top;
        }
    }

    /** Segmented control (pill group). */
    static android.widget.LinearLayout segmented(Context c, String[] labels, int sel, java.util.function.IntConsumer pick) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(c);
        row.setBackground(round(color(c, R.color.surface_variant), dp(c, 10)));
        int p = dp(c, 3);
        row.setPadding(p, p, p, p);
        TextView[] items = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView t = text(c, labels[i], 13f, 0, medium());
            t.setGravity(android.view.Gravity.CENTER);
            t.setOnClickListener(v -> {
                for (int k = 0; k < items.length; k++) styleSeg(c, items[k], k == idx);
                pick.accept(idx);
            });
            items[i] = t;
            styleSeg(c, t, i == sel);
            row.addView(t, new android.widget.LinearLayout.LayoutParams(0, dp(c, 32), 1f));
        }
        return row;
    }

    private static void styleSeg(Context c, TextView t, boolean on) {
        t.setTextColor(color(c, on ? R.color.on_surface : R.color.on_surface_variant));
        t.setBackground(on ? round(color(c, R.color.surface), dp(c, 8)) : null);
        t.setElevation(on ? dp(c, 1) : 0);
    }

    static void setSegmented(android.widget.LinearLayout row, int sel) {
        if (row == null) return;
        Context c = row.getContext();
        for (int i = 0; i < row.getChildCount(); i++) {
            View ch = row.getChildAt(i);
            if (ch instanceof TextView) styleSeg(c, (TextView) ch, i == sel);
        }
    }

    /** Title + subtitle + switch row. */
    static View switchRow(Context c, String title, String sub, boolean on, java.util.function.Consumer<Boolean> cb) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(c);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(c, 8), 0, dp(c, 8));
        android.widget.LinearLayout txt = new android.widget.LinearLayout(c);
        txt.setOrientation(android.widget.LinearLayout.VERTICAL);
        txt.addView(text(c, title, 14.5f, color(c, R.color.on_surface), null));
        if (sub != null) {
            TextView s = text(c, sub, 12.5f, color(c, R.color.on_surface_variant), null);
            s.setPadding(0, dp(c, 3), dp(c, 12), 0);
            txt.addView(s);
        }
        row.addView(txt, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
        android.widget.Switch sw = new android.widget.Switch(c);
        sw.setChecked(on);
        int acc = color(c, R.color.accent);
        sw.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{acc, 0xfff4f4f6}));
        sw.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{(acc & 0x00ffffff) | 0x66000000, 0x33888c99}));
        sw.setOnCheckedChangeListener((b, v) -> cb.accept(v));
        row.addView(sw);
        row.setOnClickListener(v -> sw.toggle());
        return row;
    }

    static android.widget.SeekBar slider(Context c, int max, int value, java.util.function.IntConsumer cb) {
        android.widget.SeekBar sb = new android.widget.SeekBar(c);
        sb.setMax(max);
        sb.setProgress(value);
        int acc = color(c, R.color.accent);
        sb.setProgressTintList(ColorStateList.valueOf(acc));
        sb.setThumbTintList(ColorStateList.valueOf(acc));
        sb.setProgressBackgroundTintList(ColorStateList.valueOf(color(c, R.color.outline)));
        sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int v, boolean user) { if (user) cb.accept(v); }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) {}
        });
        return sb;
    }

    /** Big row: icon, title, subtitle (share sheet, settings). */
    static View actionRow(Context c, int icon, String title, String sub, View.OnClickListener l) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(c);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, 8), dp(c, 10), dp(c, 8), dp(c, 10));
        row.setBackground(ripple(c, null, dp(c, 10)));
        ImageView iv = new ImageView(c);
        iv.setImageResource(icon);
        iv.setImageTintList(ColorStateList.valueOf(color(c, R.color.icon)));
        iv.setScaleType(ImageView.ScaleType.CENTER);
        row.addView(iv, new android.widget.LinearLayout.LayoutParams(dp(c, 28), dp(c, 28)));
        android.widget.LinearLayout txt = new android.widget.LinearLayout(c);
        txt.setOrientation(android.widget.LinearLayout.VERTICAL);
        txt.setPadding(dp(c, 14), 0, 0, 0);
        txt.addView(text(c, title, 14.5f, color(c, R.color.on_surface), null));
        if (sub != null) {
            TextView s = text(c, sub, 12.5f, color(c, R.color.on_surface_variant), null);
            s.setPadding(0, dp(c, 3), 0, 0);
            txt.addView(s);
        }
        row.addView(txt, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
        row.setOnClickListener(l);
        return row;
    }

    /** Wrap-to-width grid of colour dots; selected gets a ring. */
    static View colorGrid(Context c, int[] colors, int selected, java.util.function.IntConsumer pick) {
        android.widget.GridLayout g = new android.widget.GridLayout(c);
        g.setColumnCount(7);
        for (int col : colors) {
            Swatch s = new Swatch(c);
            s.setLayoutParams(new android.widget.GridLayout.LayoutParams(new ViewGroup.LayoutParams(dp(c, 40), dp(c, 38))));
            s.set(col, col == selected);
            s.setOnClickListener(v -> pick.accept(col));
            g.addView(s);
        }
        return g;
    }

    /** Live preview of a stroke with the given pen type / width / colour. */
    static final class StrokePreview extends View {
        int tool, color;
        float width;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        StrokePreview(Context c) {
            super(c);
            p.setStrokeCap(Paint.Cap.ROUND);
            setBackground(round(color(c, R.color.surface_variant), dp(c, 10)));
        }
        float zoom; // screen px per page unit (the page's zoom now), 0 = a typical fit-width zoom
        void set(int tool, int color, float width) { this.tool = tool; this.color = color; this.width = width; invalidate(); }
        void zoom(float z) { zoom = z; invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            // drawn at the page's zoom, so the sample is exactly as thick as the ink will be
            float w = getWidth(), h = getHeight(), k = zoom > 0 ? zoom : 1.7f * getResources().getDisplayMetrics().density / 2.5f;
            k = Math.min(k, h * 0.4f / Math.max(1f, width)); // zoomed far in: keep a thick pen inside the box
            Stroke s = new Stroke(tool, color, width);
            int n = 90;
            for (int i = 0; i <= n; i++) {
                float t = i / (float) n;
                float x = w * 0.08f + t * w * 0.84f;
                float y = h / 2 + (float) Math.sin(t * Math.PI * 2.2) * h * 0.24f;
                float pr = 0.15f + 0.5f * (float) Math.sin(t * Math.PI); // light → firm → light
                s.add(x / k, y / k, pr);
            }
            SegBuf b = new SegBuf();
            s.emitAll(0, b);
            int rawA = (color >>> 24) & 0xff;
            if (Stroke.isHl(tool)) {
                p.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DARKEN));
                float af = rawA / 255f;
                int r = Math.round(255 * (1f - af) + ((color >> 16) & 255) * af);
                int g = Math.round(255 * (1f - af) + ((color >> 8) & 255) * af);
                int bl = Math.round(255 * (1f - af) + (color & 255) * af);
                p.setColor(0xff000000 | (r << 16) | (g << 8) | bl);
            } else {
                p.setXfermode(null);
                p.setColor(color | 0xff000000);
            }
            cv.save();
            int layerSv = (!Stroke.isHl(tool) && rawA < 255) ? cv.saveLayerAlpha(0, 0, w, h, rawA) : -1;
            cv.scale(k, k);
            Render.segs(cv, b, p);
            if (layerSv >= 0) cv.restoreToCount(layerSv);
            cv.restore();
        }
    }

    /** Floating capsule: surface card with hairline edge and a soft shadow; clips its children (the pen tips). */
    static void capsule(View v, Context c, float radiusDp) {
        GradientDrawable g = round(color(c, R.color.surface), dp(c, radiusDp));
        g.setStroke(hairline(c), color(c, R.color.outline));
        v.setBackground(g);
        v.setElevation(dp(c, 6));
        v.setClipToOutline(true);
        v.setOutlineAmbientShadowColor(0x55000000);
        v.setOutlineSpotShadowColor(0x40000000);
    }

    /**
     * Round tool button (Notability-style). Selected: a solid disc in the tool's current ink colour with a
     * contrasting icon. Unselected pen/highlighter show their colour as a small dot underneath.
     * The laser pointer draws its own glowing red dot instead of an icon.
     */
    static final class ToolDisc extends ImageView {
        private boolean sel;
        private int disc, dot;
        private final boolean laser;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        ToolDisc(Context c, int icon, String desc, boolean laser) {
            super(c);
            this.laser = laser;
            if (icon != 0) setImageResource(icon);
            setScaleType(ScaleType.CENTER);
            setContentDescription(desc);
            setLayoutParams(new ViewGroup.MarginLayoutParams(dp(c, 44), dp(c, 46)));
            setBackground(ripple(c, null, dp(c, 23)));
            set(false, 0, 0);
        }

        /** disc: colour when selected (0 = neutral); dot: colour hint when not selected (0 = none). */
        void set(boolean s, int discColor, int dotColor) {
            sel = s;
            Context c = getContext();
            disc = discColor != 0 ? discColor : color(c, R.color.on_surface);
            dot = dotColor;
            int icon = s ? (lum(disc) > 0.62f ? 0xff1a1c20 : 0xffffffff) : color(c, R.color.icon);
            setImageTintList(ColorStateList.valueOf(icon));
            invalidate();
        }

        private static float lum(int c) {
            return (0.2126f * ((c >> 16) & 255) + 0.7152f * ((c >> 8) & 255) + 0.0722f * (c & 255)) / 255f;
        }

        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            if (sel) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(disc);
                p.setShadowLayer(dp(c, 5), 0, dp(c, 1.5f), (disc & 0x00ffffff) | 0x55000000);
                cv.drawCircle(cx, cy, dp(c, 19), p);
                p.clearShadowLayer();
                if (lum(disc) > 0.85f) { // very pale ink (white, pastel highlighter): keep an edge
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(hairline(c));
                    p.setColor(0x33000000);
                    cv.drawCircle(cx, cy, dp(c, 19), p);
                    p.setStyle(Paint.Style.FILL);
                }
            }
            if (laser) {
                int red = disc;
                p.setStyle(Paint.Style.FILL);
                if (sel) {
                    p.setColor(0xffffffff);
                    cv.drawCircle(cx, cy, dp(c, 4.5f), p);
                } else {
                    p.setColor((red & 0x00ffffff) | 0x40000000);
                    cv.drawCircle(cx, cy, dp(c, 8), p);
                    p.setColor(red);
                    cv.drawCircle(cx, cy, dp(c, 4.2f), p);
                }
                return;
            }
            super.onDraw(cv);
            if (!sel && dot != 0) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(dot);
                cv.drawCircle(cx, cy + dp(c, 15), dp(c, 2.4f), p);
                if (lum(dot) > 0.85f) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(hairline(c));
                    p.setColor(0x40000000);
                    cv.drawCircle(cx, cy + dp(c, 15), dp(c, 2.4f), p);
                }
            }
        }
    }

    /** A saved pen: a short stroke sample in its colour, width and tool (ink or translucent highlighter). */
    static final class FavDot extends View {
        final boolean hl;
        final int color;
        final float width; // page units
        boolean sel;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();

        FavDot(Context c, boolean hl, int color, float width) {
            super(c);
            this.hl = hl;
            this.color = color;
            this.width = width;
            setLayoutParams(new ViewGroup.MarginLayoutParams(dp(c, 38), dp(c, 44)));
            setBackground(ripple(c, null, dp(c, 19)));
            setContentDescription("Favourite pen · long-press to remove");
        }

        void setSel(boolean s) { sel = s; invalidate(); }

        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = dp(c, 15);
            p.setStyle(Paint.Style.FILL);
            p.setColor(color(c, R.color.surface_variant));
            cv.drawCircle(cx, cy, r, p);
            if (sel) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(c, 1.6f));
                p.setColor(color(c, R.color.on_surface));
                cv.drawCircle(cx, cy, r + dp(c, 2.5f), p);
            }
            // stroke sample: an S-curve; thickness follows the pen width (clamped to fit the dot)
            float sw = hl ? Math.min(dp(c, 9), Math.max(dp(c, 4), width * 0.25f * dp(c, 1))) : Math.min(dp(c, 4.5f), Math.max(dp(c, 1.4f), width * 0.55f * dp(c, 1)));
            path.reset();
            path.moveTo(cx - dp(c, 8), cy + dp(c, 4));
            path.cubicTo(cx - dp(c, 3), cy - dp(c, 8), cx + dp(c, 3), cy + dp(c, 8), cx + dp(c, 8), cy - dp(c, 4));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(hl ? Paint.Cap.SQUARE : Paint.Cap.ROUND);
            p.setStrokeWidth(sw);
            p.setColor(hl ? (color & 0x00ffffff) | 0xb0000000 : color);
            cv.save();
            cv.clipRect(cx - r, cy - r, cx + r, cy + r);
            cv.drawPath(path, p);
            cv.restore();
            p.setStrokeCap(Paint.Cap.BUTT);
        }
    }

    /** Rainbow ring button that opens the full palette. */
    static final class PaletteDot extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        PaletteDot(Context c) {
            super(c);
            setLayoutParams(new ViewGroup.MarginLayoutParams(dp(c, 36), dp(c, 40)));
            setBackground(ripple(c, null, dp(c, 18)));
            setContentDescription("More colours");
        }
        @Override protected void onDraw(Canvas cv) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = dp(getContext(), 9);
            p.setShader(new android.graphics.SweepGradient(cx, cy,
                    new int[]{0xffff4d4d, 0xffffc93c, 0xff3ddc84, 0xff2fb5ff, 0xff7b5cff, 0xffff4dd2, 0xffff4d4d}, null));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(getContext(), 3));
            cv.drawCircle(cx, cy, r, p);
            p.setShader(null);
        }
    }

    /** A short strip of the current tape on a paper-coloured card. */
    static final class TapePreview extends View {
        private int color;
        private String pattern = "plain";
        private float thick = 40;

        TapePreview(Context c) { super(c); }

        private boolean free;

        void set(int col, String pat, float th, boolean freeform) { color = col; pattern = pat; thick = th; free = freeform; invalidate(); }

        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            Paint bgP = new Paint(Paint.ANTI_ALIAS_FLAG);
            bgP.setColor(color(c, R.color.surface_variant));
            cv.drawRoundRect(0, 0, getWidth(), getHeight(), dp(c, 10), dp(c, 10), bgP);
            // a line of "writing" under the tape
            Paint ln = new Paint(Paint.ANTI_ALIAS_FLAG);
            ln.setColor(color(c, R.color.on_surface_variant));
            ln.setStrokeWidth(dp(c, 2));
            ln.setStrokeCap(Paint.Cap.ROUND);
            float cy = getHeight() / 2f;
            cv.drawLine(dp(c, 18), cy, getWidth() - dp(c, 18), cy, ln);
            float k = dp(c, 0.55f), w = getWidth() * 0.62f, x0 = (getWidth() - w) / 2f;
            float[] pts = free
                    ? new float[]{x0, cy + dp(c, 6), x0 + w * 0.25f, cy - dp(c, 8), x0 + w * 0.5f, cy + dp(c, 4), x0 + w * 0.75f, cy - dp(c, 6), x0 + w, cy + dp(c, 2)}
                    : new float[]{x0, cy, x0 + w, cy};
            Render.tape(cv, pts, thick * k, color, pattern, 1f);
        }
    }
}

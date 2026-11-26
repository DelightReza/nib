package app.nib.notes;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Custom colour picker supporting:
 * - Selecting & replacing any of the 5 customisable palette slots
 * - Interactive 2D Saturation/Value + Hue picker
 * - Bidirectional HEX (#RRGGBB) input
 * - Bidirectional RGB (0..255) sliders with numeric readouts
 * - Reset-to-default option for the 5-slot palette
 */
final class ColorPickerDialog {
    interface Callback {
        void onApply(int slotIndex, int rgbColor);
        void onResetDefaults();
    }

    private static final int[] QUICK_SWATCHES = {
            0xff1d1f24, 0xff5b6170, 0xffffffff, 0xff2f5bff, 0xff00a3ff,
            0xff00a99d, 0xff139a57, 0xff84cc16, 0xfff5c400, 0xffff7a1a,
            0xffe23b3b, 0xffe8437a, 0xffa855f7, 0xff6366f1, 0xff8a5a2b
    };

    private ColorPickerDialog() {}

    static void show(Context c, int[] currentSlots, int initialSlot, String title, Callback cb) {
        final int[] slots = currentSlots.clone();
        final int[] activeSlot = {DrawPrefs.clamp(initialSlot, 0, DrawPrefs.SLOT_COUNT - 1)};
        final float[] hsv = new float[3];
        Color.colorToHSV(slots[activeSlot[0]] | 0xff000000, hsv);
        final boolean[] syncing = {false};

        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(c, 18);
        root.setPadding(pad, Ui.dp(c, 16), pad, Ui.dp(c, 14));
        root.setBackgroundColor(Ui.color(c, R.color.surface));

        // Title + subtitle
        TextView tvTitle = Ui.text(c, title != null ? title : "Customise colour palette", 17, Ui.color(c, R.color.on_surface), Ui.bold());
        root.addView(tvTitle);
        TextView tvSub = Ui.text(c, "Tap a slot to edit · pick with spectrum, HEX or RGB", 12.5f, Ui.color(c, R.color.on_surface_variant), null);
        tvSub.setPadding(0, Ui.dp(c, 3), 0, Ui.dp(c, 12));
        root.addView(tvSub);

        // 5-slot selector strip
        LinearLayout slotRow = new LinearLayout(c);
        slotRow.setGravity(Gravity.CENTER);
        slotRow.setPadding(Ui.dp(c, 6), Ui.dp(c, 4), Ui.dp(c, 6), Ui.dp(c, 4));
        slotRow.setBackground(Ui.round(Ui.color(c, R.color.surface_variant), Ui.dp(c, 12)));
        final Ui.Swatch[] slotViews = new Ui.Swatch[DrawPrefs.SLOT_COUNT];

        // Preview + HEX row
        LinearLayout prevHexRow = new LinearLayout(c);
        prevHexRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams phLp = new LinearLayout.LayoutParams(-1, -2);
        phLp.topMargin = Ui.dp(c, 12);
        prevHexRow.setLayoutParams(phLp);

        View preview = new View(c);
        LinearLayout.LayoutParams pvLp = new LinearLayout.LayoutParams(Ui.dp(c, 46), Ui.dp(c, 38));
        pvLp.rightMargin = Ui.dp(c, 12);
        preview.setLayoutParams(pvLp);

        TextView hexLbl = Ui.text(c, "HEX", 12.5f, Ui.color(c, R.color.on_surface_variant), Ui.medium());
        hexLbl.setPadding(0, 0, Ui.dp(c, 8), 0);

        EditText hexEdit = new EditText(c);
        hexEdit.setSingleLine(true);
        hexEdit.setTextSize(14);
        hexEdit.setTypeface(Ui.medium());
        hexEdit.setTextColor(Ui.color(c, R.color.on_surface));
        hexEdit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7)});
        hexEdit.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 8));
        GradientDrawable hexBg = Ui.round(Ui.color(c, R.color.surface_variant), Ui.dp(c, 9));
        hexBg.setStroke(Ui.hairline(c), Ui.color(c, R.color.outline));
        hexEdit.setBackground(hexBg);
        hexEdit.setContentDescription("HEX colour code");

        prevHexRow.addView(preview);
        prevHexRow.addView(hexLbl);
        prevHexRow.addView(hexEdit, new LinearLayout.LayoutParams(0, -2, 1f));

        // Saturation/Value box & Hue bar
        final SvBox svBox = new SvBox(c);
        final HueBar hueBar = new HueBar(c);

        // RGB Sliders
        final SeekBar[] rgbBars = new SeekBar[3];
        final TextView[] rgbVals = new TextView[3];
        final String[] rgbNames = {"R", "G", "B"};
        final int[] rgbTints = {0xffe23b3b, 0xff139a57, 0xff2f5bff};

        final Runnable[] refreshUi = new Runnable[1];
        refreshUi[0] = () -> {
            syncing[0] = true;
            int col = Color.HSVToColor(0xff, hsv);
            slots[activeSlot[0]] = col;
            for (int i = 0; i < DrawPrefs.SLOT_COUNT; i++) {
                slotViews[i].set(slots[i], i == activeSlot[0]);
            }
            GradientDrawable pg = Ui.round(col, Ui.dp(c, 10));
            pg.setStroke(Ui.hairline(c), Ui.color(c, R.color.outline));
            preview.setBackground(pg);
            svBox.setHsv(hsv[0], hsv[1], hsv[2]);
            hueBar.setHue(hsv[0]);
            int r = Color.red(col), g = Color.green(col), b = Color.blue(col);
            int[] ch = {r, g, b};
            for (int i = 0; i < 3; i++) {
                rgbBars[i].setProgress(ch[i]);
                rgbVals[i].setText(String.valueOf(ch[i]));
            }
            String hex = String.format("#%02X%02X%02X", r, g, b);
            if (!hex.equalsIgnoreCase(hexEdit.getText().toString().trim())) {
                hexEdit.setText(hex);
                hexEdit.setSelection(hex.length());
            }
            syncing[0] = false;
        };

        for (int i = 0; i < DrawPrefs.SLOT_COUNT; i++) {
            final int idx = i;
            Ui.Swatch sw = new Ui.Swatch(c);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(c, 48), Ui.dp(c, 44));
            sw.setLayoutParams(lp);
            sw.setContentDescription("Colour slot " + (i + 1));
            sw.setOnClickListener(v -> {
                activeSlot[0] = idx;
                Color.colorToHSV(slots[idx] | 0xff000000, hsv);
                refreshUi[0].run();
            });
            slotViews[i] = sw;
            slotRow.addView(sw);
        }
        root.addView(slotRow, new LinearLayout.LayoutParams(-1, -2));

        // 2D SV Picker
        svBox.onChange = (s, v) -> {
            hsv[1] = s;
            hsv[2] = v;
            refreshUi[0].run();
        };
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 148));
        svLp.topMargin = Ui.dp(c, 12);
        root.addView(svBox, svLp);

        // Hue Bar
        hueBar.onChange = h -> {
            hsv[0] = h;
            refreshUi[0].run();
        };
        LinearLayout.LayoutParams hbLp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 28));
        hbLp.topMargin = Ui.dp(c, 10);
        root.addView(hueBar, hbLp);

        // Preview + HEX
        root.addView(prevHexRow);

        hexEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (syncing[0]) return;
                String raw = s.toString().trim();
                if (raw.startsWith("#")) raw = raw.substring(1);
                if (raw.length() == 6) {
                    try {
                        int parsed = 0xff000000 | Integer.parseInt(raw, 16);
                        Color.colorToHSV(parsed, hsv);
                        syncing[0] = true;
                        int col = Color.HSVToColor(0xff, hsv);
                        slots[activeSlot[0]] = col;
                        for (int i = 0; i < DrawPrefs.SLOT_COUNT; i++) slotViews[i].set(slots[i], i == activeSlot[0]);
                        GradientDrawable pg = Ui.round(col, Ui.dp(c, 10));
                        pg.setStroke(Ui.hairline(c), Ui.color(c, R.color.outline));
                        preview.setBackground(pg);
                        svBox.setHsv(hsv[0], hsv[1], hsv[2]);
                        hueBar.setHue(hsv[0]);
                        int[] ch = {Color.red(col), Color.green(col), Color.blue(col)};
                        for (int i = 0; i < 3; i++) {
                            rgbBars[i].setProgress(ch[i]);
                            rgbVals[i].setText(String.valueOf(ch[i]));
                        }
                        syncing[0] = false;
                    } catch (NumberFormatException ignored) { }
                }
            }
        });

        // RGB Sliders
        LinearLayout rgbBox = new LinearLayout(c);
        rgbBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rblp = new LinearLayout.LayoutParams(-1, -2);
        rblp.topMargin = Ui.dp(c, 10);
        for (int i = 0; i < 3; i++) {
            final int ch = i;
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView lbl = Ui.text(c, rgbNames[i], 12.5f, rgbTints[i], Ui.bold());
            lbl.setGravity(Gravity.CENTER);
            row.addView(lbl, new LinearLayout.LayoutParams(Ui.dp(c, 22), -2));

            SeekBar sb = Ui.slider(c, 255, 0, val -> {
                if (syncing[0]) return;
                int cur = Color.HSVToColor(0xff, hsv);
                int r = ch == 0 ? val : Color.red(cur);
                int g = ch == 1 ? val : Color.green(cur);
                int b = ch == 2 ? val : Color.blue(cur);
                Color.RGBToHSV(r, g, b, hsv);
                refreshUi[0].run();
            });
            sb.setProgressTintList(android.content.res.ColorStateList.valueOf(rgbTints[i]));
            sb.setThumbTintList(android.content.res.ColorStateList.valueOf(rgbTints[i]));
            rgbBars[i] = sb;
            row.addView(sb, new LinearLayout.LayoutParams(0, Ui.dp(c, 32), 1f));

            TextView valTv = Ui.text(c, "0", 12.5f, Ui.color(c, R.color.on_surface_variant), Ui.medium());
            valTv.setGravity(Gravity.END);
            rgbVals[i] = valTv;
            row.addView(valTv, new LinearLayout.LayoutParams(Ui.dp(c, 34), -2));
            rgbBox.addView(row);
        }
        root.addView(rgbBox, rblp);

        // Quick swatches strip
        HorizontalScrollView qScroll = new HorizontalScrollView(c);
        qScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout qRow = new LinearLayout(c);
        qRow.setGravity(Gravity.CENTER_VERTICAL);
        for (int col : QUICK_SWATCHES) {
            Ui.Swatch qs = new Ui.Swatch(c);
            qs.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36)));
            qs.set(col, false);
            qs.setOnClickListener(v -> {
                Color.colorToHSV(col, hsv);
                refreshUi[0].run();
            });
            qRow.addView(qs);
        }
        qScroll.addView(qRow);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(-1, -2);
        qlp.topMargin = Ui.dp(c, 8);
        root.addView(qScroll, qlp);

        refreshUi[0].run();

        ScrollView scroll = new ScrollView(c);
        scroll.addView(root, new ViewGroup.LayoutParams(-1, -2));

        final AlertDialog dlg = new AlertDialog.Builder(c)
                .setView(scroll)
                .create();

        // Action buttons row inside content for clean Nib styling
        LinearLayout actions = new LinearLayout(c);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = Ui.dp(c, 14);
        actions.setLayoutParams(alp);

        TextView resetBtn = Ui.text(c, "Reset defaults", 13.5f, Ui.color(c, R.color.on_surface_variant), Ui.medium());
        resetBtn.setPadding(Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 10));
        resetBtn.setBackground(Ui.ripple(c, null, Ui.dp(c, 8)));
        resetBtn.setOnClickListener(v -> {
            if (cb != null) cb.onResetDefaults();
            dlg.dismiss();
        });
        actions.addView(resetBtn);

        View spacer = new View(c);
        actions.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView cancelBtn = Ui.button(c, "Cancel", 0, false);
        cancelBtn.setOnClickListener(v -> dlg.dismiss());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, Ui.dp(c, 40));
        clp.rightMargin = Ui.dp(c, 8);
        actions.addView(cancelBtn, clp);

        TextView applyBtn = Ui.button(c, "Apply", 0, true);
        applyBtn.setOnClickListener(v -> {
            if (cb != null) cb.onApply(activeSlot[0], Color.HSVToColor(0xff, hsv));
            dlg.dismiss();
        });
        actions.addView(applyBtn, new LinearLayout.LayoutParams(-2, Ui.dp(c, 40)));
        root.addView(actions);

        dlg.show();
        if (dlg.getWindow() != null) {
            int maxW = Math.min(Ui.dp(c, 420), c.getResources().getDisplayMetrics().widthPixels - Ui.dp(c, 28));
            dlg.getWindow().setLayout(maxW, ViewGroup.LayoutParams.WRAP_CONTENT);
            GradientDrawable bg = Ui.round(Ui.color(c, R.color.surface), Ui.dp(c, 18));
            bg.setStroke(Ui.hairline(c), Ui.color(c, R.color.outline));
            dlg.getWindow().setBackgroundDrawable(bg);
        }
    }

    // ---- Interactive 2D Saturation / Value square ------------------------------------------

    private static final class SvBox extends View {
        interface OnChange { void on(float s, float v); }
        OnChange onChange;
        private float hue, sat = 1f, val = 1f;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private float lastW, lastH, lastHue = -1;

        SvBox(Context c) {
            super(c);
            setLayerType(LAYER_TYPE_SOFTWARE, null); // ensures ComposeShader blends accurately everywhere
        }

        void setHsv(float h, float s, float v) {
            hue = h; sat = s; val = v;
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float r = Ui.dp(getContext(), 10);
            box.set(0, 0, w, h);
            if (w != lastW || h != lastH || Math.abs(hue - lastHue) > 0.05f) {
                lastW = w; lastH = h; lastHue = hue;
                int pure = Color.HSVToColor(new float[]{hue, 1f, 1f});
                Shader satS = new LinearGradient(0, 0, w, 0, Color.WHITE, pure, Shader.TileMode.CLAMP);
                Shader valS = new LinearGradient(0, 0, 0, h, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP);
                fill.setShader(new ComposeShader(satS, valS, PorterDuff.Mode.SRC_OVER));
            }
            cv.drawRoundRect(box, r, r, fill);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Ui.hairline(getContext()));
            ring.setColor(Ui.color(getContext(), R.color.outline));
            cv.drawRoundRect(box, r, r, ring);

            float px = sat * w, py = (1f - val) * h;
            ring.setStrokeWidth(Ui.dp(getContext(), 2.4f));
            ring.setColor(Color.WHITE);
            cv.drawCircle(px, py, Ui.dp(getContext(), 8.5f), ring);
            ring.setStrokeWidth(Ui.dp(getContext(), 1.2f));
            ring.setColor(0x99000000);
            cv.drawCircle(px, py, Ui.dp(getContext(), 10f), ring);
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
            float s = Math.max(0f, Math.min(1f, ev.getX() / Math.max(1f, getWidth())));
            float v = 1f - Math.max(0f, Math.min(1f, ev.getY() / Math.max(1f, getHeight())));
            sat = s; val = v;
            invalidate();
            if (onChange != null) onChange.on(s, v);
            return true;
        }
    }

    // ---- Interactive Hue bar ---------------------------------------------------------------

    private static final class HueBar extends View {
        interface OnChange { void on(float h); }
        OnChange onChange;
        private float hue;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private float lastW;

        HueBar(Context c) { super(c); }

        void setHue(float h) { hue = h; invalidate(); }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float pad = Ui.dp(getContext(), 6);
            box.set(0, pad, w, h - pad);
            if (w != lastW) {
                lastW = w;
                int[] spectrum = {0xffff0000, 0xffffff00, 0xff00ff00, 0xff00ffff, 0xff0000ff, 0xffff00ff, 0xffff0000};
                fill.setShader(new LinearGradient(0, 0, w, 0, spectrum, null, Shader.TileMode.CLAMP));
            }
            float rad = box.height() * 0.5f;
            cv.drawRoundRect(box, rad, rad, fill);
            float px = Math.max(rad, Math.min(w - rad, (hue / 360f) * w));
            ring.setStyle(Paint.Style.FILL);
            ring.setColor(Color.HSVToColor(new float[]{hue, 1f, 1f}));
            cv.drawCircle(px, h * 0.5f, Ui.dp(getContext(), 9.5f), ring);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Ui.dp(getContext(), 2.5f));
            ring.setColor(Color.WHITE);
            cv.drawCircle(px, h * 0.5f, Ui.dp(getContext(), 9.5f), ring);
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
            float h = Math.max(0f, Math.min(359.9f, (ev.getX() / Math.max(1f, getWidth())) * 360f));
            hue = h;
            invalidate();
            if (onChange != null) onChange.on(h);
            return true;
        }
    }
}

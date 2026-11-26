package app.nib.notes;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Interactive Grid Settings panel anchored inside a Ui.Popover.
 * Includes a live page preview, all grid types & named presets (with dimensions),
 * visual styling (thickness, colour, opacity, solid/dashed/dotted), layout controls
 * (major-line frequency, square-cell lock, margins, H/V offsets), optional snapping,
 * and per-document reset.
 */
final class GridSettingsPanel {

    interface Host {
        void onGridChanged(GridOverlay.Config cfg);
        void onGridReset(GridOverlay.Config cfg);
        int[] paletteSlots();
        void onAddMoveRow(Ui.Popover p);
    }

    private GridSettingsPanel() {}

    static Ui.Popover show(Context c, View anchor, GridOverlay.Config initial,
                           float pageAspect, int paperBg, String docTitle, Host host) {
        final GridOverlay.Config cfg = initial.copy();
        final Ui.Popover pop = new Ui.Popover(c, 356);
        pop.title("Grid settings");

        TextView sub = Ui.text(c, docTitle != null && !docTitle.isEmpty()
                        ? "Saved for \"" + docTitle + "\" · temporary visual overlay only"
                        : "Saved per notebook / PDF · temporary visual overlay only",
                12f, Ui.color(c, R.color.on_surface_variant), null);
        sub.setPadding(0, 0, 0, Ui.dp(c, 8));
        pop.add(sub);

        // Live preview card
        final GridOverlay.PreviewView preview = new GridOverlay.PreviewView(c);
        preview.setPageContext(pageAspect, paperBg);
        preview.setConfig(cfg);
        LinearLayout.LayoutParams prevLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 152));
        preview.setLayoutParams(prevLp);
        pop.add(preview);

        final TextView dimBadge = Ui.text(c, cfg.liveDimensionBadge(), 12f,
                Ui.color(c, R.color.accent), Ui.medium());
        dimBadge.setGravity(Gravity.CENTER);
        dimBadge.setPadding(0, Ui.dp(c, 5), 0, Ui.dp(c, 6));
        pop.add(dimBadge);

        // Primary switches: Show Grid + Snap to Grid (disabled by default)
        final Switch swEnable = new Switch(c);
        final Switch swSnap = new Switch(c);

        // Containers that toggle visibility based on Grid Type
        final LinearLayout customSizeBox = new LinearLayout(c);
        customSizeBox.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout rowColBox = new LinearLayout(c);
        rowColBox.setOrientation(LinearLayout.VERTICAL);
        final TextView presetInfo = Ui.text(c, "", 12.5f,
                Ui.color(c, R.color.on_surface_variant), null);

        // Forward references for programmatic sync (preset change / reset)
        final boolean[] syncing = {false};
        final Runnable[] syncAll = new Runnable[1];
        final Runnable notify = () -> {
            preview.setConfig(cfg);
            dimBadge.setText(cfg.liveDimensionBadge() + (cfg.enabled ? "" : " · Grid off"));
            host.onGridChanged(cfg.copy());
        };

        // --- Show Grid switch row ---
        pop.add(switchRow(c, "Show grid overlay", "Not saved in pages or exports", swEnable, syncing, on -> {
            cfg.enabled = on;
            notify.run();
        }));

        // --- Snap to Grid switch row ---
        pop.add(switchRow(c, "Snap to grid", "Snap moved items, shapes & PDF handwriting", swSnap, syncing, on -> {
            cfg.snapEnabled = on;
            if (on && !cfg.enabled) {
                cfg.enabled = true;
                syncing[0] = true;
                swEnable.setChecked(true);
                syncing[0] = false;
            }
            notify.run();
        }));

        // --- Grid Type & Presets (with exact dimensions shown) ---
        pop.section("Grid type & presets");
        final LinearLayout typeList = new LinearLayout(c);
        typeList.setOrientation(LinearLayout.VERTICAL);
        final View[] typeCards = new View[GridOverlay.TYPE_COUNT];
        final TextView[] typeTitleViews = new TextView[GridOverlay.TYPE_COUNT];
        final TextView[] typeSubViews = new TextView[GridOverlay.TYPE_COUNT];

        // 2-column compact grid of cards for all 7 types so every preset & its dimensions are immediately visible
        LinearLayout rowPair = null;
        for (int i = 0; i < GridOverlay.TYPE_COUNT; i++) {
            final int t = i;
            if (i % 2 == 0) {
                rowPair = new LinearLayout(c);
                rowPair.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = Ui.dp(c, 6);
                rowPair.setLayoutParams(rlp);
                typeList.addView(rowPair);
            }
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            int pH = Ui.dp(c, 10), pV = Ui.dp(c, 8);
            card.setPadding(pH, pV, pH, pV);
            TextView tvT = Ui.text(c, GridOverlay.TYPE_NAMES[t], 13f, Ui.color(c, R.color.on_surface), Ui.bold());
            TextView tvS = Ui.text(c, GridOverlay.TYPE_DIMENSIONS[t], 11f, Ui.color(c, R.color.on_surface_variant), null);
            tvS.setPadding(0, Ui.dp(c, 2), 0, 0);
            card.addView(tvT);
            card.addView(tvS);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (i % 2 == 1) clp.leftMargin = Ui.dp(c, 6);
            card.setLayoutParams(clp);
            card.setOnClickListener(v -> {
                cfg.applyTypeSelection(t);
                if (!cfg.enabled) {
                    cfg.enabled = true;
                }
                if (syncAll[0] != null) syncAll[0].run();
                notify.run();
            });
            typeCards[t] = card;
            typeTitleViews[t] = tvT;
            typeSubViews[t] = tvS;
            rowPair.addView(card);
        }
        // If odd count (7), add an invisible spacer so last card aligns neatly in 2 columns
        if (GridOverlay.TYPE_COUNT % 2 == 1 && rowPair != null) {
            View spacer = new View(c);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, 1, 1f);
            slp.leftMargin = Ui.dp(c, 6);
            rowPair.addView(spacer, slp);
        }
        pop.add(typeList);

        // --- Contextual controls: Custom Size (mm + square lock) ---
        final Switch swSquare = new Switch(c);
        final SliderHandle slCellW = buildSlider(c, "Cell width", 200 - 4, 16, v -> {
            float mm = 2f + v * 0.5f;
            cfg.cellWidthMm = mm;
            if (cfg.lockSquare) cfg.cellHeightMm = mm;
            return GridOverlay.Config.fmtMm(mm) + " mm";
        });
        final SliderHandle slCellH = buildSlider(c, "Cell height", 200 - 4, 16, v -> {
            float mm = 2f + v * 0.5f;
            cfg.cellHeightMm = mm;
            if (cfg.lockSquare) cfg.cellWidthMm = mm;
            return GridOverlay.Config.fmtMm(mm) + " mm";
        });
        slCellW.onChanged = v -> {
            float mm = 2f + v * 0.5f;
            cfg.cellWidthMm = mm;
            if (cfg.lockSquare) {
                cfg.cellHeightMm = mm;
                slCellH.setProgressSilent(v, GridOverlay.Config.fmtMm(mm) + " mm");
            }
            notify.run();
        };
        slCellH.onChanged = v -> {
            float mm = 2f + v * 0.5f;
            cfg.cellHeightMm = mm;
            if (cfg.lockSquare) {
                cfg.cellWidthMm = mm;
                slCellW.setProgressSilent(v, GridOverlay.Config.fmtMm(mm) + " mm");
            }
            notify.run();
        };

        customSizeBox.addView(sectionHeader(c, "Cell size (mm)"));
        customSizeBox.addView(switchRow(c, "Lock square cells", "Keep width and height equal", swSquare, syncing, on -> {
            cfg.lockSquare = on;
            if (on) {
                cfg.cellHeightMm = cfg.cellWidthMm;
                slCellH.setProgressSilent(Math.round((cfg.cellHeightMm - 2f) * 2f),
                        GridOverlay.Config.fmtMm(cfg.cellHeightMm) + " mm");
            }
            notify.run();
        }));
        customSizeBox.addView(slCellW.row);
        customSizeBox.addView(slCellH.row);
        pop.add(customSizeBox);

        // --- Contextual controls: Custom Row/Col (0..60, strictly allowing 0!) ---
        rowColBox.addView(sectionHeader(c, "Rows & columns"));
        TextView zeroHint = Ui.text(c, "Set either axis to 0 to draw no internal lines along that axis.",
                11.5f, Ui.color(c, R.color.on_surface_variant), null);
        zeroHint.setPadding(0, 0, 0, Ui.dp(c, 6));
        rowColBox.addView(zeroHint);

        final StepperSliderHandle slCols = buildIntStepperSlider(c, "Columns", 0, 60, cfg.cols, v -> {
            cfg.cols = v; // 0 is preserved, never clamped!
            notify.run();
        });
        final StepperSliderHandle slRows = buildIntStepperSlider(c, "Rows", 0, 60, cfg.rows, v -> {
            cfg.rows = v; // 0 is preserved, never clamped!
            notify.run();
        });
        rowColBox.addView(slCols.row);
        rowColBox.addView(slRows.row);
        pop.add(rowColBox);

        presetInfo.setPadding(0, Ui.dp(c, 8), 0, 0);
        pop.add(presetInfo);

        // --- Appearance: Line style (Solid / Dashed / Dotted), Thickness, Opacity, Colour ---
        pop.section("Line style & appearance");
        final LinearLayout[] styleSegHolder = new LinearLayout[1];
        styleSegHolder[0] = new LinearLayout(c);
        styleSegHolder[0].setOrientation(LinearLayout.VERTICAL);
        pop.add(styleSegHolder[0]);

        final SliderHandle slThick = buildSlider(c, "Line thickness", 35, 5, v -> {
            float dpVal = 0.5f + v * 0.1f;
            return String.format(java.util.Locale.US, "%.1f dp", dpVal);
        });
        slThick.onChanged = v -> {
            cfg.lineThicknessDp = 0.5f + v * 0.1f;
            notify.run();
        };
        pop.add(slThick.row);

        final SliderHandle slOpacity = buildSlider(c, "Opacity", 95, 30, v -> (5 + v) + "%");
        slOpacity.onChanged = v -> {
            cfg.opacity = 5 + v;
            notify.run();
        };
        pop.add(slOpacity.row);

        // Colour palette strip + Custom HEX/RGB/HSV picker
        TextView colLabel = Ui.text(c, "Line colour", 12.5f, Ui.color(c, R.color.on_surface_variant), Ui.medium());
        colLabel.setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 6));
        pop.add(colLabel);

        HorizontalScrollView colScroll = new HorizontalScrollView(c);
        colScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout colRow = new LinearLayout(c);
        colRow.setGravity(Gravity.CENTER_VERTICAL);
        final View[] swatchViews = new View[GridOverlay.QUICK_COLORS.length + 1];
        final Runnable[] refreshSwatches = new Runnable[1];

        for (int i = 0; i < GridOverlay.QUICK_COLORS.length; i++) {
            final int col = GridOverlay.QUICK_COLORS[i];
            View sw = new View(c);
            int sz = Ui.dp(c, 30);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
            if (i > 0) lp.leftMargin = Ui.dp(c, 7);
            sw.setLayoutParams(lp);
            sw.setOnClickListener(v -> {
                cfg.colorRgb = col;
                if (refreshSwatches[0] != null) refreshSwatches[0].run();
                notify.run();
            });
            swatchViews[i] = sw;
            colRow.addView(sw);
        }

        // Custom colour button using Nib's full HEX/RGB/HSV ColorPickerDialog
        TextView customColB = Ui.text(c, "Custom…", 12f, Ui.color(c, R.color.on_surface), Ui.medium());
        customColB.setGravity(Gravity.CENTER);
        customColB.setPadding(Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 10), Ui.dp(c, 6));
        LinearLayout.LayoutParams customLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(c, 30));
        customLp.leftMargin = Ui.dp(c, 8);
        customColB.setLayoutParams(customLp);
        customColB.setOnClickListener(v -> {
            int[] base = host.paletteSlots();
            int[] slots = base != null && base.length == DrawPrefs.SLOT_COUNT ? base.clone() : new int[DrawPrefs.SLOT_COUNT];
            slots[0] = cfg.colorRgb | 0xff000000;
            ColorPickerDialog.show(c, slots, 0, "Grid line colour", new ColorPickerDialog.Callback() {
                @Override public void onApply(int slotIndex, int rgbColor) {
                    cfg.colorRgb = rgbColor | 0xff000000;
                    if (refreshSwatches[0] != null) refreshSwatches[0].run();
                    notify.run();
                }
                @Override public void onResetDefaults() {
                    cfg.colorRgb = 0xff2f5bff;
                    if (refreshSwatches[0] != null) refreshSwatches[0].run();
                    notify.run();
                }
            });
        });
        swatchViews[GridOverlay.QUICK_COLORS.length] = customColB;
        colRow.addView(customColB);
        colScroll.addView(colRow);
        pop.add(colScroll);

        refreshSwatches[0] = () -> {
            boolean matchedQuick = false;
            int selRgb = cfg.colorRgb & 0x00ffffff;
            for (int i = 0; i < GridOverlay.QUICK_COLORS.length; i++) {
                boolean on = (GridOverlay.QUICK_COLORS[i] & 0x00ffffff) == selRgb;
                if (on) matchedQuick = true;
                GradientDrawable gd = new GradientDrawable();
                gd.setShape(GradientDrawable.OVAL);
                gd.setColor(GridOverlay.QUICK_COLORS[i]);
                gd.setStroke(Ui.dp(c, on ? 3 : 1),
                        on ? Ui.color(c, R.color.on_surface) : Ui.color(c, R.color.outline));
                swatchViews[i].setBackground(gd);
            }
            GradientDrawable customBg = Ui.round(
                    matchedQuick ? Ui.color(c, R.color.surface_variant) : (cfg.colorRgb | 0xff000000),
                    Ui.dp(c, 15));
            customBg.setStroke(Ui.dp(c, matchedQuick ? 1 : 2), Ui.color(c, R.color.outline));
            customColB.setBackground(customBg);
            if (!matchedQuick) {
                double lum = (0.299 * Color.red(cfg.colorRgb) + 0.587 * Color.green(cfg.colorRgb)
                        + 0.114 * Color.blue(cfg.colorRgb)) / 255.0;
                customColB.setTextColor(lum > 0.6 ? 0xff1d1f24 : 0xffffffff);
            } else {
                customColB.setTextColor(Ui.color(c, R.color.on_surface));
            }
        };

        // --- Layout: Major-line frequency, Page Margins (H/V), Offsets (H/V) ---
        pop.section("Major lines, margins & offsets");

        // Major line frequency: 0 (Off), 2..10
        final SliderHandle slMajor = buildSlider(c, "Major line every", 9, 4, v -> v == 0 ? "Off" : ("Every " + (v + 1)));
        slMajor.onChanged = v -> {
            cfg.majorEvery = v == 0 ? 0 : (v + 1);
            notify.run();
        };
        pop.add(slMajor.row);

        final SliderHandle slMarginH = buildSlider(c, "Horizontal margin", 50, 0, v -> v + " mm");
        slMarginH.onChanged = v -> {
            cfg.marginHMm = v;
            notify.run();
        };
        pop.add(slMarginH.row);

        final SliderHandle slMarginV = buildSlider(c, "Vertical margin", 50, 0, v -> v + " mm");
        slMarginV.onChanged = v -> {
            cfg.marginVMm = v;
            notify.run();
        };
        pop.add(slMarginV.row);

        final SliderHandle slOffX = buildSlider(c, "Horizontal offset", 100, 50, v -> fmtSignedMm(v - 50));
        slOffX.onChanged = v -> {
            cfg.offsetXMm = v - 50;
            notify.run();
        };
        pop.add(slOffX.row);

        final SliderHandle slOffY = buildSlider(c, "Vertical offset", 100, 50, v -> fmtSignedMm(v - 50));
        slOffY.onChanged = v -> {
            cfg.offsetYMm = v - 50;
            notify.run();
        };
        pop.add(slOffY.row);

        // --- Reset grid settings button ---
        View resetRow = Ui.actionRow(c, R.drawable.ic_undo, "Reset grid settings",
                "Restore defaults for this notebook or PDF", v -> {
                    cfg.resetDefaults(true);
                    if (syncAll[0] != null) syncAll[0].run();
                    preview.setConfig(cfg);
                    dimBadge.setText(cfg.liveDimensionBadge() + (cfg.enabled ? "" : " · Grid off"));
                    host.onGridReset(cfg.copy());
                });
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resetLp.topMargin = Ui.dp(c, 12);
        resetRow.setLayoutParams(resetLp);
        pop.add(resetRow);

        // Optional "Move to tool bar / more tools" row when opened from the Grid disc
        host.onAddMoveRow(pop);

        // Master UI sync helper
        syncAll[0] = () -> {
            syncing[0] = true;
            try {
                swEnable.setChecked(cfg.enabled);
                swSnap.setChecked(cfg.snapEnabled);
                swSquare.setChecked(cfg.lockSquare);
            } finally {
                syncing[0] = false;
            }

            for (int t = 0; t < GridOverlay.TYPE_COUNT; t++) {
                boolean sel = cfg.type == t;
                int accentSoft = (Ui.color(c, R.color.accent) & 0x00ffffff) | 0x22000000;
                GradientDrawable bg = Ui.round(
                        sel ? accentSoft : Ui.color(c, R.color.surface_variant),
                        Ui.dp(c, 10));
                if (sel) bg.setStroke(Ui.dp(c, 1.5f), Ui.color(c, R.color.accent));
                typeCards[t].setBackground(bg);
                typeTitleViews[t].setTextColor(Ui.color(c, sel ? R.color.accent : R.color.on_surface));
                typeSubViews[t].setTextColor(Ui.color(c, sel ? R.color.on_surface : R.color.on_surface_variant));
            }

            customSizeBox.setVisibility(cfg.type == GridOverlay.TYPE_CUSTOM_SIZE ? View.VISIBLE : View.GONE);
            rowColBox.setVisibility(cfg.type == GridOverlay.TYPE_CUSTOM_ROW_COL ? View.VISIBLE : View.GONE);
            boolean isPreset = cfg.type != GridOverlay.TYPE_CUSTOM_SIZE && cfg.type != GridOverlay.TYPE_CUSTOM_ROW_COL;
            presetInfo.setVisibility(isPreset ? View.VISIBLE : View.GONE);
            if (isPreset) {
                presetInfo.setText(GridOverlay.TYPE_NAMES[cfg.type] + " preset · " + GridOverlay.TYPE_DIMENSIONS[cfg.type]);
            }

            slCellW.setProgressSilent(Math.round((cfg.cellWidthMm - 2f) * 2f),
                    GridOverlay.Config.fmtMm(cfg.cellWidthMm) + " mm");
            slCellH.setProgressSilent(Math.round((cfg.effectiveCellHeightMm() - 2f) * 2f),
                    GridOverlay.Config.fmtMm(cfg.effectiveCellHeightMm()) + " mm");
            slCols.setValueSilent(cfg.cols);
            slRows.setValueSilent(cfg.rows);

            styleSegHolder[0].removeAllViews();
            styleSegHolder[0].addView(Ui.segmented(c, GridOverlay.STYLE_NAMES, cfg.lineStyle, pick -> {
                cfg.lineStyle = pick;
                notify.run();
            }));

            slThick.setProgressSilent(Math.round((cfg.lineThicknessDp - 0.5f) * 10f),
                    String.format(java.util.Locale.US, "%.1f dp", cfg.lineThicknessDp));
            slOpacity.setProgressSilent(Math.max(0, Math.min(95, cfg.opacity - 5)), cfg.opacity + "%");

            int majProg = cfg.majorEvery >= 2 ? Math.min(9, cfg.majorEvery - 1) : 0;
            slMajor.setProgressSilent(majProg, majProg == 0 ? "Off" : ("Every " + (majProg + 1)));
            slMarginH.setProgressSilent(Math.round(cfg.marginHMm), Math.round(cfg.marginHMm) + " mm");
            slMarginV.setProgressSilent(Math.round(cfg.marginVMm), Math.round(cfg.marginVMm) + " mm");
            slOffX.setProgressSilent(Math.round(cfg.offsetXMm) + 50, fmtSignedMm(Math.round(cfg.offsetXMm)));
            slOffY.setProgressSilent(Math.round(cfg.offsetYMm) + 50, fmtSignedMm(Math.round(cfg.offsetYMm)));

            if (refreshSwatches[0] != null) refreshSwatches[0].run();
            preview.setConfig(cfg);
            dimBadge.setText(cfg.liveDimensionBadge() + (cfg.enabled ? "" : " · Grid off"));
        };

        syncAll[0].run();
        pop.show(anchor);
        return pop;
    }

    private static String fmtSignedMm(int mm) {
        if (mm == 0) return "0 mm";
        return (mm > 0 ? "+" : "") + mm + " mm";
    }

    private static TextView sectionHeader(Context c, String title) {
        TextView tv = Ui.text(c, title, 12.5f, Ui.color(c, R.color.on_surface_variant), Ui.medium());
        tv.setPadding(0, Ui.dp(c, 12), 0, Ui.dp(c, 6));
        return tv;
    }

    private interface BoolConsumer { void accept(boolean v); }
    private interface IntFormatter { String format(int progress); }
    private interface IntCallback { void accept(int value); }

    private static View switchRow(Context c, String title, String subtitle, Switch sw,
                                  boolean[] syncing, BoolConsumer onToggle) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 6));

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(c, title, 13.5f, Ui.color(c, R.color.on_surface), Ui.medium()));
        if (subtitle != null && !subtitle.isEmpty()) {
            texts.addView(Ui.text(c, subtitle, 11.5f, Ui.color(c, R.color.on_surface_variant), null));
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        sw.setOnCheckedChangeListener((b, checked) -> {
            if (!syncing[0]) onToggle.accept(checked);
        });
        row.setOnClickListener(v -> sw.toggle());
        row.addView(sw);
        return row;
    }

    private static final class SliderHandle {
        final LinearLayout row;
        final SeekBar bar;
        final TextView valueView;
        boolean suppress;
        IntCallback onChanged;

        SliderHandle(LinearLayout row, SeekBar bar, TextView valueView) {
            this.row = row;
            this.bar = bar;
            this.valueView = valueView;
        }

        void setProgressSilent(int progress, String label) {
            suppress = true;
            bar.setProgress(Math.max(0, Math.min(bar.getMax(), progress)));
            valueView.setText(label);
            suppress = false;
        }
    }

    private static SliderHandle buildSlider(Context c, String label, int max, int init, IntFormatter fmt) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 2));

        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView lbl = Ui.text(c, label, 12.5f, Ui.color(c, R.color.on_surface), Ui.medium());
        TextView val = Ui.text(c, fmt.format(init), 12.5f, Ui.color(c, R.color.accent), Ui.bold());
        top.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(val);
        box.addView(top);

        SeekBar sb = new SeekBar(c);
        sb.setMax(max);
        sb.setProgress(init);
        box.addView(sb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        SliderHandle handle = new SliderHandle(box, sb, val);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                val.setText(fmt.format(progress));
                if (!handle.suppress && fromUser && handle.onChanged != null) {
                    handle.onChanged.accept(progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        return handle;
    }

    private static final class StepperSliderHandle {
        final LinearLayout row;
        final SeekBar bar;
        final TextView valueView;
        final int min, max;
        boolean suppress;

        StepperSliderHandle(LinearLayout row, SeekBar bar, TextView valueView, int min, int max) {
            this.row = row;
            this.bar = bar;
            this.valueView = valueView;
            this.min = min;
            this.max = max;
        }

        void setValueSilent(int value) {
            int clamped = Math.max(min, Math.min(max, value));
            suppress = true;
            bar.setProgress(clamped - min);
            valueView.setText(clamped == 0 ? "0 (none)" : String.valueOf(clamped));
            suppress = false;
        }
    }

    /** Integer slider with - / + stepper buttons so 0 or any exact row/column count is effortless to pick. */
    private static StepperSliderHandle buildIntStepperSlider(Context c, String label, int min, int max,
                                                             int init, IntCallback onChanged) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 2));

        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView lbl = Ui.text(c, label, 12.5f, Ui.color(c, R.color.on_surface), Ui.medium());
        TextView val = Ui.text(c, init == 0 ? "0 (none)" : String.valueOf(init),
                12.5f, Ui.color(c, R.color.accent), Ui.bold());
        val.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 8), 0);

        TextView minus = stepBtn(c, "−");
        TextView plus = stepBtn(c, "+");

        top.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(minus);
        top.addView(val);
        top.addView(plus);
        box.addView(top);

        SeekBar sb = new SeekBar(c);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, Math.min(max - min, init - min)));
        box.addView(sb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        StepperSliderHandle handle = new StepperSliderHandle(box, sb, val, min, max);
        minus.setOnClickListener(v -> {
            int next = Math.max(min, (sb.getProgress() + min) - 1);
            handle.setValueSilent(next);
            onChanged.accept(next);
        });
        plus.setOnClickListener(v -> {
            int next = Math.min(max, (sb.getProgress() + min) + 1);
            handle.setValueSilent(next);
            onChanged.accept(next);
        });
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int actual = progress + min;
                val.setText(actual == 0 ? "0 (none)" : String.valueOf(actual));
                if (!handle.suppress && fromUser) onChanged.accept(actual);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        return handle;
    }

    private static TextView stepBtn(Context c, String text) {
        TextView b = Ui.text(c, text, 16f, Ui.color(c, R.color.on_surface), Ui.bold());
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.round(Ui.color(c, R.color.surface_variant), Ui.dp(c, 8)));
        int sz = Ui.dp(c, 28);
        b.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
        return b;
    }
}

package app.nib.notes;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Modern Template / Page Style modal dialog:
 * - Built-in & Custom template tabs
 * - Expandable Page Size picker (A3, A4, A5, A6, A7, Letter, Legal, Square, 16:9, Custom W×H, aspect lock, swap portrait/landscape)
 * - 6 circular Paper Colour swatches (White, Cream, Tan, Ice, Dark, Pure Black) with live preview redraw
 * - Stepped 1–10 dotted sliders for Density and Line width
 * - Optional collapsible Line colour & Margins controls
 * - Categorised collapsible template galleries (Basic, Education, Music, Planners & Forms)
 * - Floating split action pill ("Apply to current page" + chevron menu for "Apply to all pages" & "Set as default template")
 */
final class PageStyleDialog {

    interface Host {
        void onApply(boolean allPages, int bg, int paper, float spacing, float strength,
                     int lineColor, int margins, float pageH, String bgImage, boolean setAsDefault);
        void onSaveCustomTemplate(String name, int bg, int paper, float spacing, float strength,
                                  int lineColor, int margins, float pageH, String bgImage);
        void onImportCustomImage(java.util.function.Consumer<JSONObject> onImported);
        void onImportCustomPdf(java.util.function.Consumer<JSONObject> onImported);
        void onRenameCustomTemplate(int index, String newName);
        void onDeleteCustomTemplate(int index);
        JSONArray getCustomTemplates();
    }

    static final class SizePreset {
        final String label;
        final String shortName;
        final int wPx, hPx; // reference pixel/mm dimensions (300 DPI equivalent)
        final float aspectH; // Notebook page height (PW = 1000)

        SizePreset(String label, String shortName, int wPx, int hPx) {
            this.label = label;
            this.shortName = shortName;
            this.wPx = wPx;
            this.hPx = hPx;
            this.aspectH = Notebook.PW * hPx / (float) Math.max(1, wPx);
        }
    }

    static final SizePreset[] SIZE_PRESETS = {
            new SizePreset("A3", "A3", 3508, 4961),
            new SizePreset("A4", "A4", 2480, 3508),
            new SizePreset("A5", "A5", 1748, 2480),
            new SizePreset("A6", "A6", 1240, 1748),
            new SizePreset("A7 (Suitable for phones)", "A7", 874, 1240),
            new SizePreset("Letter", "Letter", 2550, 3300),
            new SizePreset("Legal", "Legal", 2550, 4200),
            new SizePreset("Square", "Square", 2480, 2480),
            new SizePreset("16:9 Slide", "16:9", 3200, 1800)
    };

    private static final int[] CAT_BASIC = {
            Notebook.BG_BLANK, Notebook.BG_LINED, Notebook.BG_GRID,
            Notebook.BG_DOTS, Notebook.BG_NARROW, Notebook.BG_FINE_DOTS
    };
    private static final int[] CAT_EDUCATION = {
            Notebook.BG_HEX, Notebook.BG_CORNELL, Notebook.BG_CORNELL_B,
            Notebook.BG_LEGAL, Notebook.BG_ENGINEERING, Notebook.BG_GRAPH, Notebook.BG_ISO
    };
    private static final int[] CAT_MUSIC = {
            Notebook.BG_MUSIC, Notebook.BG_GUITAR_TAB, Notebook.BG_GUITAR_SCORE
    };
    private static final int[] CAT_PLANNERS = {
            Notebook.BG_DAILY, Notebook.BG_WEEKLY, Notebook.BG_SITE
    };
    private static final String[] CAT_TITLES = {"Basic", "Education", "Music", "Planners & Forms"};
    private static final int[][] CAT_BGS = {CAT_BASIC, CAT_EDUCATION, CAT_MUSIC, CAT_PLANNERS};

    private static final int[] LINE_COLORS = {0, 0xff2b59c3, 0xff168a5c, 0xffc83e3b, 0xff8c5a2b, 0xff6b46c1, 0xff64748b};

    private PageStyleDialog() {}

    /** Maps 1..10 Density step (5 = default) to Nib pattern spacing multiplier (higher density = smaller spacing). */
    static float spacingForDensity(int d) {
        int c = Math.max(1, Math.min(10, d));
        if (c <= 5) return 1.0f + (5 - c) * 0.20f; // 1 -> 1.80, 5 -> 1.00
        return 1.0f - (c - 5) * 0.10f;             // 10 -> 0.50
    }

    static int densityForSpacing(float sp) {
        if (sp >= 1.0f) return Math.max(1, Math.min(5, Math.round(5f - (sp - 1.0f) / 0.20f)));
        return Math.max(5, Math.min(10, Math.round(5f + (1.0f - sp) / 0.10f)));
    }

    /** Maps 1..10 Line width step (5 = default) to Nib line strength multiplier. */
    static float strengthForWidth(int w) {
        int c = Math.max(1, Math.min(10, w));
        if (c <= 5) return 1.0f - (5 - c) * 0.16f; // 1 -> 0.36, 5 -> 1.00
        return 1.0f + (c - 5) * 0.25f;             // 10 -> 2.25
    }

    static int widthForStrength(float st) {
        if (st <= 1.0f) return Math.max(1, Math.min(5, Math.round(5f - (1.0f - st) / 0.16f)));
        return Math.max(5, Math.min(10, Math.round(5f + (st - 1.0f) / 0.25f)));
    }

    static void show(Activity a, Engine.State st, Host host) {
        if (st == null) return;
        final boolean canvasNb = st.canvas;
        final int[] selBg = {st.bg};
        final String[] selBgImage = {st.bgImage != null && !st.bgImage.isEmpty() ? st.bgImage : null};
        final int[] selPaper = {Math.max(0, Math.min(Render.PAPER.length - 1, st.paper))};
        final int[] densityStep = {densityForSpacing(st.spacing > 0 ? st.spacing : 1f)};
        final int[] widthStep = {widthForStrength(st.strength > 0 ? st.strength : 1f)};
        final int[] lineColor = {st.lineColor};
        final int[] margins = {st.margins};
        final boolean[] setAsDefault = {false};
        final boolean[] customTab = {selBgImage[0] != null};
        final boolean[] sizeOpen = {false};
        final boolean[] lockRatio = {true};
        final boolean[] extraOpen = {false};
        final int[] selCustomIdx = {-1};

        // Resolve initial W×H from current page height (defaulting standard ISO ratio to A4, never A3)
        final float initH = st.pageH > 100f ? st.pageH : Notebook.PH;
        int matchedPreset = 1; // A4 default (2480 × 3508)
        int initW = SIZE_PRESETS[1].wPx;
        int initHPx = SIZE_PRESETS[1].hPx;
        if (Math.abs(SIZE_PRESETS[1].aspectH - initH) < 25f) {
            matchedPreset = 1; // A4
            initW = SIZE_PRESETS[1].wPx;
            initHPx = SIZE_PRESETS[1].hPx;
        } else {
            matchedPreset = -1;
            for (int i = 0; i < SIZE_PRESETS.length; i++) {
                if (i == 0 || i == 2 || i == 3 || i == 4) continue; // other A-series share A4 aspect ratio
                if (Math.abs(SIZE_PRESETS[i].aspectH - initH) < 25f) {
                    matchedPreset = i;
                    initW = SIZE_PRESETS[i].wPx;
                    initHPx = SIZE_PRESETS[i].hPx;
                    break;
                }
            }
            if (matchedPreset < 0) {
                // Check landscape A4 or other landscape presets
                if (Math.abs((Notebook.PW * SIZE_PRESETS[1].wPx / (float) SIZE_PRESETS[1].hPx) - initH) < 25f) {
                    matchedPreset = 1;
                    initW = SIZE_PRESETS[1].hPx;
                    initHPx = SIZE_PRESETS[1].wPx;
                } else {
                    initW = SIZE_PRESETS[1].wPx;
                    initHPx = Math.round(initW * initH / Notebook.PW);
                }
            }
        }
        final int[] presetIdx = {matchedPreset};
        final int[] sizeW = {initW};
        final int[] sizeH = {initHPx};
        final float[] pageH = {initH};

        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout outer = new FrameLayout(a);
        GradientDrawable shellBg = Ui.round(Ui.color(a, R.color.surface), Ui.dp(a, 22));
        shellBg.setStroke(Ui.hairline(a), Ui.color(a, R.color.outline));
        outer.setBackground(shellBg);
        outer.setClipToOutline(true);

        LinearLayout mainCol = new LinearLayout(a);
        mainCol.setOrientation(LinearLayout.VERTICAL);

        // ---- Header: centered title + circular close button ----
        FrameLayout header = new FrameLayout(a);
        header.setPadding(Ui.dp(a, 18), Ui.dp(a, 14), Ui.dp(a, 14), Ui.dp(a, 8));
        TextView titleTv = Ui.text(a, "Page style", 18.5f, Ui.color(a, R.color.on_surface), Ui.bold());
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        header.addView(titleTv, tlp);

        ImageView closeBtn = new ImageView(a);
        closeBtn.setImageResource(R.drawable.ic_close);
        closeBtn.setColorFilter(Ui.color(a, R.color.on_surface_variant));
        closeBtn.setBackground(Ui.ripple(a, Ui.round(Ui.color(a, R.color.surface_variant), Ui.dp(a, 16)), Ui.dp(a, 16)));
        int cp = Ui.dp(a, 7);
        closeBtn.setPadding(cp, cp, cp, cp);
        closeBtn.setContentDescription("Close");
        closeBtn.setOnClickListener(v -> d.dismiss());
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(Ui.dp(a, 32), Ui.dp(a, 32), Gravity.END | Gravity.CENTER_VERTICAL);
        header.addView(closeBtn, clp);
        mainCol.addView(header, new LinearLayout.LayoutParams(-1, -2));

        // ---- Tabs + Size chip row ----
        LinearLayout topStrip = new LinearLayout(a);
        topStrip.setOrientation(LinearLayout.HORIZONTAL);
        topStrip.setGravity(Gravity.CENTER);
        topStrip.setPadding(Ui.dp(a, 14), Ui.dp(a, 2), Ui.dp(a, 14), Ui.dp(a, 10));

        TextView tabBuiltIn = pillBtn(a, "✓ Built-in");
        TextView tabCustom = pillBtn(a, "Custom");
        TextView sizeChip = pillBtn(a, "");
        sizeChip.setVisibility(canvasNb ? View.GONE : View.VISIBLE);

        LinearLayout.LayoutParams tabLp1 = new LinearLayout.LayoutParams(-2, Ui.dp(a, 34));
        LinearLayout.LayoutParams tabLp2 = new LinearLayout.LayoutParams(-2, Ui.dp(a, 34));
        tabLp2.leftMargin = Ui.dp(a, 6);
        LinearLayout.LayoutParams szLp = new LinearLayout.LayoutParams(-2, Ui.dp(a, 34));
        szLp.leftMargin = Ui.dp(a, 10);
        topStrip.addView(tabBuiltIn, tabLp1);
        topStrip.addView(tabCustom, tabLp2);
        topStrip.addView(sizeChip, szLp);
        mainCol.addView(topStrip, new LinearLayout.LayoutParams(-1, -2));

        // ---- Scrollable content container ----
        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(a, 18), Ui.dp(a, 4), Ui.dp(a, 18), Ui.dp(a, 86)); // bottom room for floating apply pill
        sv.addView(content, new ViewGroup.LayoutParams(-1, -2));
        mainCol.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        // ---- Expandable inline Size Bar (matching Screenshot 1!) ----
        LinearLayout sizePanel = new LinearLayout(a);
        sizePanel.setOrientation(LinearLayout.HORIZONTAL);
        sizePanel.setGravity(Gravity.CENTER_VERTICAL);
        int spPad = Ui.dp(a, 14);
        sizePanel.setPadding(spPad, Ui.dp(a, 12), spPad, Ui.dp(a, 12));
        sizePanel.setBackground(Ui.round(Ui.color(a, R.color.surface_variant), Ui.dp(a, 14)));
        LinearLayout.LayoutParams splp = new LinearLayout.LayoutParams(-1, -2);
        splp.bottomMargin = Ui.dp(a, 12);
        sizePanel.setLayoutParams(splp);
        sizePanel.setVisibility(View.GONE);

        final SizePreviewView sizeThumb = new SizePreviewView(a);
        sizePanel.addView(sizeThumb, new LinearLayout.LayoutParams(Ui.dp(a, 56), Ui.dp(a, 74)));

        LinearLayout sizeControls = new LinearLayout(a);
        sizeControls.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sclp = new LinearLayout.LayoutParams(0, -2, 1f);
        sclp.leftMargin = Ui.dp(a, 14);
        sizePanel.addView(sizeControls, sclp);

        TextView presetDrop = Ui.text(a, "", 14f, Ui.color(a, R.color.on_surface), Ui.medium());
        presetDrop.setPadding(Ui.dp(a, 12), Ui.dp(a, 8), Ui.dp(a, 12), Ui.dp(a, 8));
        GradientDrawable dropBg = Ui.round(Ui.color(a, R.color.surface), Ui.dp(a, 10));
        dropBg.setStroke(Ui.hairline(a), Ui.color(a, R.color.outline));
        presetDrop.setBackground(dropBg);
        sizeControls.addView(presetDrop, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout dimsRow = new LinearLayout(a);
        dimsRow.setOrientation(LinearLayout.HORIZONTAL);
        dimsRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams drlp = new LinearLayout.LayoutParams(-1, -2);
        drlp.topMargin = Ui.dp(a, 8);
        sizeControls.addView(dimsRow, drlp);

        EditText etW = dimInput(a);
        EditText etH = dimInput(a);
        TextView xSep = Ui.text(a, "×", 14f, Ui.color(a, R.color.on_surface_variant), Ui.medium());
        xSep.setPadding(Ui.dp(a, 6), 0, Ui.dp(a, 6), 0);
        ImageView lockBtn = smallActionIcon(a, R.drawable.ic_lock, "Lock aspect ratio");
        ImageView swapBtn = smallActionIcon(a, R.drawable.ic_swap, "Swap portrait / landscape");

        dimsRow.addView(etW, new LinearLayout.LayoutParams(0, Ui.dp(a, 34), 1f));
        dimsRow.addView(xSep);
        dimsRow.addView(etH, new LinearLayout.LayoutParams(0, Ui.dp(a, 34), 1f));
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(Ui.dp(a, 34), Ui.dp(a, 34));
        icLp.leftMargin = Ui.dp(a, 6);
        dimsRow.addView(lockBtn, icLp);
        dimsRow.addView(swapBtn, icLp);
        content.addView(sizePanel);

        // ---- Built-in Container (Color, Density, Line width, Margins + Categorised Gallery) ----
        LinearLayout builtInContainer = new LinearLayout(a);
        builtInContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(builtInContainer);

        // ---- 6 Paper Colour Swatches Row (Built-in only) ----
        LinearLayout paperRow = new LinearLayout(a);
        paperRow.setOrientation(LinearLayout.HORIZONTAL);
        paperRow.setGravity(Gravity.CENTER);
        paperRow.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 14));
        final View[] paperDots = new View[Notebook.PAPER_ORDER.length];
        for (int i = 0; i < Notebook.PAPER_ORDER.length; i++) {
            final int pid = Notebook.PAPER_ORDER[i];
            View dot = new View(a);
            int sz = Ui.dp(a, 36);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(sz, sz);
            if (i > 0) dlp.leftMargin = Ui.dp(a, 10);
            dot.setLayoutParams(dlp);
            dot.setContentDescription(Notebook.PAPER_NAMES[pid] + " paper");
            paperDots[i] = dot;
            paperRow.addView(dot);
        }
        builtInContainer.addView(paperRow);

        // ---- Stepped Sliders: Density & Line width (Built-in only) ----
        final Runnable[] refreshGallery = new Runnable[1];
        SteppedSliderView densitySlider = new SteppedSliderView(a, "Density", 1, 10, densityStep[0], v -> {
            densityStep[0] = v;
            if (refreshGallery[0] != null) refreshGallery[0].run();
        });
        builtInContainer.addView(densitySlider, new LinearLayout.LayoutParams(-1, Ui.dp(a, 42)));

        SteppedSliderView widthSlider = new SteppedSliderView(a, "Line width", 1, 10, widthStep[0], v -> {
            widthStep[0] = v;
            if (refreshGallery[0] != null) refreshGallery[0].run();
        });
        LinearLayout.LayoutParams wslp = new LinearLayout.LayoutParams(-1, Ui.dp(a, 42));
        wslp.topMargin = Ui.dp(a, 2);
        builtInContainer.addView(widthSlider, wslp);

        // ---- Optional collapsible Line Colour & Margins row (Built-in only) ----
        LinearLayout extraHeader = new LinearLayout(a);
        extraHeader.setOrientation(LinearLayout.HORIZONTAL);
        extraHeader.setGravity(Gravity.CENTER_VERTICAL);
        extraHeader.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 4));
        TextView extraLbl = Ui.text(a, "Line colour & margins", 12.5f, Ui.color(a, R.color.on_surface_variant), Ui.medium());
        TextView extraArrow = Ui.text(a, "▾", 14f, Ui.color(a, R.color.on_surface_variant), Ui.medium());
        extraHeader.addView(extraLbl, new LinearLayout.LayoutParams(0, -2, 1f));
        extraHeader.addView(extraArrow);
        builtInContainer.addView(extraHeader);

        LinearLayout extraBody = new LinearLayout(a);
        extraBody.setOrientation(LinearLayout.VERTICAL);
        extraBody.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 8));
        extraBody.setVisibility(View.GONE);

        LinearLayout lcRow = new LinearLayout(a);
        lcRow.setGravity(Gravity.CENTER_VERTICAL);
        final Ui.Swatch[] lcSwatches = new Ui.Swatch[LINE_COLORS.length];
        for (int i = 0; i < LINE_COLORS.length; i++) {
            final int col = LINE_COLORS[i];
            Ui.Swatch sw = new Ui.Swatch(a);
            sw.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(a, 34), Ui.dp(a, 34)));
            sw.set(col == 0 ? Render.PAPER[selPaper[0]][1] : col, col == lineColor[0]);
            sw.setOnClickListener(v -> {
                lineColor[0] = col;
                for (int k = 0; k < lcSwatches.length; k++) {
                    lcSwatches[k].set(LINE_COLORS[k] == 0 ? Render.PAPER[selPaper[0]][1] : LINE_COLORS[k], LINE_COLORS[k] == col);
                }
                if (refreshGallery[0] != null) refreshGallery[0].run();
            });
            lcSwatches[i] = sw;
            lcRow.addView(sw);
        }
        extraBody.addView(lcRow);

        if (!canvasNb) {
            LinearLayout mgRow = new LinearLayout(a);
            mgRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams mrlp = new LinearLayout.LayoutParams(-1, -2);
            mrlp.topMargin = Ui.dp(a, 8);
            String[] mgNames = {"Left margin", "Header line", "Border"};
            int[] mgBits = {Notebook.MARGIN_LEFT, Notebook.MARGIN_HEADER, Notebook.MARGIN_BORDER};
            for (int i = 0; i < 3; i++) {
                final int bit = mgBits[i];
                TextView chip = Ui.text(a, mgNames[i], 12.5f, 0, Ui.medium());
                chip.setGravity(Gravity.CENTER);
                Runnable paintChip = () -> {
                    boolean on = (margins[0] & bit) != 0;
                    chip.setTextColor(Ui.color(a, on ? R.color.on_accent : R.color.on_surface));
                    chip.setBackground(Ui.ripple(a, Ui.round(Ui.color(a, on ? R.color.accent : R.color.surface_variant), Ui.dp(a, 8)), Ui.dp(a, 8)));
                };
                paintChip.run();
                chip.setOnClickListener(v -> {
                    margins[0] ^= bit;
                    paintChip.run();
                    if (refreshGallery[0] != null) refreshGallery[0].run();
                });
                LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, Ui.dp(a, 32), 1f);
                if (i > 0) mlp.leftMargin = Ui.dp(a, 6);
                mgRow.addView(chip, mlp);
            }
            extraBody.addView(mgRow, mrlp);
        }
        builtInContainer.addView(extraBody);

        extraHeader.setOnClickListener(v -> {
            extraOpen[0] = !extraOpen[0];
            extraBody.setVisibility(extraOpen[0] ? View.VISIBLE : View.GONE);
            extraArrow.setText(extraOpen[0] ? "▴" : "▾");
        });

        LinearLayout customContainer = new LinearLayout(a);
        customContainer.setOrientation(LinearLayout.VERTICAL);
        customContainer.setVisibility(View.GONE);
        content.addView(customContainer);

        // Track built-in template tiles for instant redraw & selection highlight
        final ArrayList<TileRef> builtInTiles = new ArrayList<>();
        final View[] catDots = new View[CAT_TITLES.length];
        final boolean[] catExpanded = {true, true, true, true};

        for (int ci = 0; ci < CAT_TITLES.length; ci++) {
            final int catIdx = ci;
            LinearLayout catHeader = new LinearLayout(a);
            catHeader.setOrientation(LinearLayout.HORIZONTAL);
            catHeader.setGravity(Gravity.CENTER_VERTICAL);
            catHeader.setPadding(0, Ui.dp(a, 14), 0, Ui.dp(a, 8));

            TextView catName = Ui.text(a, CAT_TITLES[ci], 14.5f, Ui.color(a, R.color.on_surface), Ui.bold());
            catHeader.addView(catName);

            View activeDot = new View(a);
            activeDot.setBackground(Ui.round(Ui.color(a, R.color.accent), Ui.dp(a, 3)));
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(Ui.dp(a, 6), Ui.dp(a, 6));
            dotLp.leftMargin = Ui.dp(a, 7);
            catHeader.addView(activeDot, dotLp);
            catDots[ci] = activeDot;

            View flex = new View(a);
            catHeader.addView(flex, new LinearLayout.LayoutParams(0, 1, 1f));

            TextView chev = Ui.text(a, "⌃", 15f, Ui.color(a, R.color.on_surface_variant), Ui.bold());
            catHeader.addView(chev);

            Ui.Grid catGrid = new Ui.Grid(a, 96, 12);
            for (int bgId : CAT_BGS[ci]) {
                TileRef tr = createTemplateTile(a, Notebook.BG_NAMES[bgId], bgId);
                tr.root.setOnClickListener(v -> {
                    selBg[0] = bgId;
                    selBgImage[0] = null;
                    selCustomIdx[0] = -1;
                    if (refreshGallery[0] != null) refreshGallery[0].run();
                });
                builtInTiles.add(tr);
                catGrid.addView(tr.root);
            }

            catHeader.setOnClickListener(v -> {
                catExpanded[catIdx] = !catExpanded[catIdx];
                catGrid.setVisibility(catExpanded[catIdx] ? View.VISIBLE : View.GONE);
                chev.setText(catExpanded[catIdx] ? "⌃" : "⌄");
            });

            builtInContainer.addView(catHeader);
            builtInContainer.addView(catGrid);
        }

        // ---- Sync & Refresh Helpers ----
        final boolean[] syncingDims = {false};
        Runnable syncSizeUi = () -> {
            pageH[0] = Math.max(400f, Math.min(3500f, Notebook.PW * sizeH[0] / (float) Math.max(1, sizeW[0])));
            String label = "Size: " + sizeW[0] + " × " + sizeH[0] + (sizeOpen[0] ? "  ×" : " ⌄");
            sizeChip.setText(label);
            int accentSoft = (Ui.color(a, R.color.accent) & 0x00ffffff) | 0x26000000;
            sizeChip.setBackground(Ui.round(sizeOpen[0] ? Ui.color(a, R.color.accent) : Ui.color(a, R.color.surface_variant), Ui.dp(a, 17)));
            sizeChip.setTextColor(Ui.color(a, sizeOpen[0] ? R.color.on_accent : R.color.on_surface));
            String presetTitle = presetIdx[0] >= 0 && presetIdx[0] < SIZE_PRESETS.length
                    ? SIZE_PRESETS[presetIdx[0]].label : "Custom";
            presetDrop.setText(presetTitle + "   ▾");
            syncingDims[0] = true;
            etW.setText(String.valueOf(sizeW[0]));
            etH.setText(String.valueOf(sizeH[0]));
            syncingDims[0] = false;
            sizeThumb.setAspect(sizeW[0], sizeH[0], Render.PAPER[selPaper[0]][0]);
            lockBtn.setImageResource(lockRatio[0] ? R.drawable.ic_lock : R.drawable.ic_lock_open);
            lockBtn.setColorFilter(Ui.color(a, lockRatio[0] ? R.color.accent : R.color.on_surface_variant));
            if (accentSoft == 0) sizeChip.invalidate();
        };

        Runnable syncPaperSwatches = () -> {
            for (int i = 0; i < Notebook.PAPER_ORDER.length; i++) {
                int pid = Notebook.PAPER_ORDER[i];
                boolean sel = pid == selPaper[0];
                GradientDrawable gd = new GradientDrawable();
                gd.setShape(GradientDrawable.OVAL);
                gd.setColor(Render.PAPER[pid][0]);
                gd.setStroke(Ui.dp(a, sel ? 3 : 1),
                        sel ? Ui.color(a, R.color.accent) : Ui.color(a, R.color.outline));
                paperDots[i].setBackground(gd);
            }
            if (lcSwatches[0] != null) {
                lcSwatches[0].set(Render.PAPER[selPaper[0]][1], lineColor[0] == 0);
            }
            sizeThumb.setAspect(sizeW[0], sizeH[0], Render.PAPER[selPaper[0]][0]);
        };

        final Runnable[] rebuildCustomTab = new Runnable[1];
        java.util.function.Consumer<JSONObject> selectCustomObject = o -> {
            if (o == null || !d.isShowing()) return;
            Notebook.Page look = EditorActivity.lookOf(o);
            selBg[0] = look.bg;
            selBgImage[0] = look.bgImage != null && !look.bgImage.isEmpty() ? look.bgImage : null;
            if (o.has("p")) selPaper[0] = Math.max(0, Math.min(Render.PAPER.length - 1, o.optInt("p", selPaper[0])));
            densityStep[0] = densityForSpacing(look.spacing);
            widthStep[0] = widthForStrength(look.strength);
            lineColor[0] = look.lineColor;
            margins[0] = look.margins;
            if (look.h > 100f) {
                pageH[0] = look.h;
                sizeH[0] = Math.round(sizeW[0] * look.h / Notebook.PW);
                presetIdx[0] = -1;
            }
            densitySlider.setValue(densityStep[0]);
            widthSlider.setValue(widthStep[0]);
            syncPaperSwatches.run();
            syncSizeUi.run();
            if (refreshGallery[0] != null) refreshGallery[0].run();
            if (rebuildCustomTab[0] != null) rebuildCustomTab[0].run();
        };

        rebuildCustomTab[0] = () -> {
            customContainer.removeAllViews();

            // Import Image & Import PDF action cards
            LinearLayout importRow = new LinearLayout(a);
            importRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams irp = new LinearLayout.LayoutParams(-1, -2);
            irp.topMargin = Ui.dp(a, 10);
            irp.bottomMargin = Ui.dp(a, 10);

            View imgImportBtn = importActionCard(a, R.drawable.ic_image, "Import image", "PNG, JPG as page style", v ->
                    host.onImportCustomImage(imported -> {
                        if (imported != null) selectCustomObject.accept(imported);
                    }));
            LinearLayout.LayoutParams i1 = new LinearLayout.LayoutParams(0, -2, 1f);
            i1.rightMargin = Ui.dp(a, 6);
            importRow.addView(imgImportBtn, i1);

            View pdfImportBtn = importActionCard(a, R.drawable.ic_import, "Import PDF", "PDF page as page style", v ->
                    host.onImportCustomPdf(imported -> {
                        if (imported != null) selectCustomObject.accept(imported);
                    }));
            LinearLayout.LayoutParams i2 = new LinearLayout.LayoutParams(0, -2, 1f);
            i2.leftMargin = Ui.dp(a, 6);
            importRow.addView(pdfImportBtn, i2);

            customContainer.addView(importRow, irp);

            View saveRow = Ui.actionRow(a, R.drawable.ic_plus, "Save current style",
                    "Save template, paper colour, density & line width", v -> {
                        promptSaveCustom(a, selBg[0], name -> {
                            host.onSaveCustomTemplate(name, selBg[0], selPaper[0],
                                    spacingForDensity(densityStep[0]), strengthForWidth(widthStep[0]),
                                    lineColor[0], margins[0], pageH[0], selBgImage[0]);
                            rebuildCustomTab[0].run();
                        });
                    });
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
            slp.bottomMargin = Ui.dp(a, 12);
            customContainer.addView(saveRow, slp);

            JSONArray arr = host.getCustomTemplates();
            if (arr == null || arr.length() == 0) {
                TextView empty = Ui.text(a, "No custom page styles yet. Import an image or PDF above, or save your current style.",
                        13f, Ui.color(a, R.color.on_surface_variant), null);
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(Ui.dp(a, 16), Ui.dp(a, 20), Ui.dp(a, 16), Ui.dp(a, 24));
                customContainer.addView(empty);
                return;
            }
            Ui.Grid cg = new Ui.Grid(a, 96, 12);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                final int idx = i;
                Notebook.Page look = EditorActivity.lookOf(o);
                int pCol = Math.max(0, Math.min(Render.PAPER.length - 1, o.optInt("p", selPaper[0])));
                String nm = o.optString("n", "Custom style");
                boolean hasImg = look.bgImage != null && !look.bgImage.isEmpty();
                boolean isSelected = hasImg
                        ? look.bgImage.equals(selBgImage[0])
                        : (selBgImage[0] == null && selCustomIdx[0] == idx);
                TileRef tr = createTemplateTile(a, nm, look.bg);
                renderTile(a, tr, look, pCol, isSelected);
                tr.root.setOnClickListener(v -> {
                    selCustomIdx[0] = idx;
                    selectCustomObject.accept(o);
                });
                tr.root.setOnLongClickListener(v -> {
                    PopupMenu pm = new PopupMenu(a, v);
                    pm.getMenu().add(0, 1, 0, "Rename");
                    pm.getMenu().add(0, 2, 1, "Delete");
                    pm.setOnMenuItemClickListener(mi -> {
                        if (mi.getItemId() == 1) {
                            promptRenameCustom(a, nm, newName -> {
                                host.onRenameCustomTemplate(idx, newName);
                                rebuildCustomTab[0].run();
                            });
                        } else if (mi.getItemId() == 2) {
                            if (hasImg && look.bgImage.equals(selBgImage[0])) selBgImage[0] = null;
                            if (selCustomIdx[0] == idx) selCustomIdx[0] = -1;
                            host.onDeleteCustomTemplate(idx);
                            rebuildCustomTab[0].run();
                            if (refreshGallery[0] != null) refreshGallery[0].run();
                        }
                        return true;
                    });
                    pm.show();
                    return true;
                });
                cg.addView(tr.root);
            }
            customContainer.addView(cg);
        };

        refreshGallery[0] = () -> {
            float sp = spacingForDensity(densityStep[0]);
            float stVal = strengthForWidth(widthStep[0]);
            boolean builtInActive = selBgImage[0] == null;

            for (TileRef tr : builtInTiles) {
                Notebook.Page pg = new Notebook.Page(tr.bgId);
                pg.spacing = sp;
                pg.strength = stVal;
                pg.lineColor = lineColor[0];
                pg.margins = margins[0];
                pg.h = pageH[0];
                renderTile(a, tr, pg, selPaper[0], builtInActive && tr.bgId == selBg[0]);
            }
            for (int ci = 0; ci < CAT_BGS.length; ci++) {
                boolean hasSel = false;
                if (builtInActive) {
                    for (int b : CAT_BGS[ci]) if (b == selBg[0]) { hasSel = true; break; }
                }
                catDots[ci].setVisibility(hasSel ? View.VISIBLE : View.INVISIBLE);
            }
        };

        Runnable syncTabs = () -> {
            int accBg = (Ui.color(a, R.color.accent) & 0x00ffffff) | 0x26000000;
            boolean cTab = customTab[0];
            tabBuiltIn.setText(cTab ? "Built-in" : "✓ Built-in");
            tabBuiltIn.setBackground(Ui.round(cTab ? Ui.color(a, R.color.surface_variant) : accBg, Ui.dp(a, 10)));
            tabBuiltIn.setTextColor(Ui.color(a, cTab ? R.color.on_surface_variant : R.color.accent));
            tabCustom.setText(cTab ? "✓ Custom" : "Custom");
            tabCustom.setBackground(Ui.round(cTab ? accBg : Ui.color(a, R.color.surface_variant), Ui.dp(a, 10)));
            tabCustom.setTextColor(Ui.color(a, cTab ? R.color.accent : R.color.on_surface_variant));
            builtInContainer.setVisibility(cTab ? View.GONE : View.VISIBLE);
            customContainer.setVisibility(cTab ? View.VISIBLE : View.GONE);
            if (cTab) rebuildCustomTab[0].run();
        };

        tabBuiltIn.setOnClickListener(v -> { customTab[0] = false; syncTabs.run(); });
        tabCustom.setOnClickListener(v -> { customTab[0] = true; syncTabs.run(); });

        sizeChip.setOnClickListener(v -> {
            sizeOpen[0] = !sizeOpen[0];
            sizePanel.setVisibility(sizeOpen[0] ? View.VISIBLE : View.GONE);
            syncSizeUi.run();
        });

        presetDrop.setOnClickListener(v -> {
            PopupMenu pm = new PopupMenu(a, presetDrop);
            for (int i = 0; i < SIZE_PRESETS.length; i++) {
                pm.getMenu().add(0, i, i, SIZE_PRESETS[i].label);
            }
            pm.setOnMenuItemClickListener(mi -> {
                int id = mi.getItemId();
                if (id >= 0 && id < SIZE_PRESETS.length) {
                    presetIdx[0] = id;
                    sizeW[0] = SIZE_PRESETS[id].wPx;
                    sizeH[0] = SIZE_PRESETS[id].hPx;
                    syncSizeUi.run();
                    refreshGallery[0].run();
                }
                return true;
            });
            pm.show();
        });

        lockBtn.setOnClickListener(v -> {
            lockRatio[0] = !lockRatio[0];
            syncSizeUi.run();
        });

        swapBtn.setOnClickListener(v -> {
            int tmp = sizeW[0];
            sizeW[0] = sizeH[0];
            sizeH[0] = tmp;
            syncSizeUi.run();
            refreshGallery[0].run();
        });

        View.OnFocusChangeListener commitDims = (v, hasFocus) -> {
            if (hasFocus || syncingDims[0]) return;
            try {
                int nw = Math.max(400, Math.min(8000, Integer.parseInt(etW.getText().toString().trim())));
                int nh = Math.max(400, Math.min(8000, Integer.parseInt(etH.getText().toString().trim())));
                if (lockRatio[0] && v == etW && sizeW[0] > 0) {
                    nh = Math.max(400, Math.min(8000, Math.round(nw * (sizeH[0] / (float) sizeW[0]))));
                } else if (lockRatio[0] && v == etH && sizeH[0] > 0) {
                    nw = Math.max(400, Math.min(8000, Math.round(nh * (sizeW[0] / (float) sizeH[0]))));
                }
                sizeW[0] = nw;
                sizeH[0] = nh;
                presetIdx[0] = -1;
                syncSizeUi.run();
                refreshGallery[0].run();
            } catch (NumberFormatException ignored) {
                syncSizeUi.run();
            }
        };
        etW.setOnFocusChangeListener(commitDims);
        etH.setOnFocusChangeListener(commitDims);

        for (int i = 0; i < Notebook.PAPER_ORDER.length; i++) {
            final int pid = Notebook.PAPER_ORDER[i];
            paperDots[i].setOnClickListener(v -> {
                selPaper[0] = pid;
                syncPaperSwatches.run();
                refreshGallery[0].run();
            });
        }

        // ---- Floating Split Primary Action Pill ("Apply to current page | ⌃") ----
        LinearLayout splitPill = new LinearLayout(a);
        splitPill.setOrientation(LinearLayout.HORIZONTAL);
        splitPill.setGravity(Gravity.CENTER_VERTICAL);
        splitPill.setBackground(Ui.round(Ui.color(a, R.color.accent), Ui.dp(a, 24)));
        splitPill.setElevation(Ui.dp(a, 6));

        TextView applyMain = Ui.text(a, canvasNb ? "Apply" : "Apply to current page", 14f,
                Ui.color(a, R.color.on_accent), Ui.bold());
        applyMain.setGravity(Gravity.CENTER);
        applyMain.setPadding(Ui.dp(a, 20), Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 12));
        applyMain.setOnClickListener(v -> {
            host.onApply(false, selBg[0], selPaper[0],
                    spacingForDensity(densityStep[0]), strengthForWidth(widthStep[0]),
                    lineColor[0], margins[0], pageH[0], selBgImage[0], setAsDefault[0]);
            d.dismiss();
        });
        splitPill.addView(applyMain);

        View sep = new View(a);
        sep.setBackgroundColor((Ui.color(a, R.color.on_accent) & 0x00ffffff) | 0x44000000);
        splitPill.addView(sep, new LinearLayout.LayoutParams(Math.max(1, Ui.dp(a, 1)), Ui.dp(a, 22)));

        TextView applyMenuBtn = Ui.text(a, "⌃", 16f, Ui.color(a, R.color.on_accent), Ui.bold());
        applyMenuBtn.setGravity(Gravity.CENTER);
        applyMenuBtn.setPadding(Ui.dp(a, 14), Ui.dp(a, 10), Ui.dp(a, 18), Ui.dp(a, 10));
        applyMenuBtn.setContentDescription("More apply options");
        applyMenuBtn.setOnClickListener(v -> showApplyOptionsPopup(a, splitPill, canvasNb, setAsDefault, () -> {
            host.onApply(true, selBg[0], selPaper[0],
                    spacingForDensity(densityStep[0]), strengthForWidth(widthStep[0]),
                    lineColor[0], margins[0], pageH[0], selBgImage[0], setAsDefault[0]);
            d.dismiss();
        }));
        splitPill.addView(applyMenuBtn);

        outer.addView(mainCol, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(-2, Ui.dp(a, 46), Gravity.BOTTOM | Gravity.END);
        pillLp.rightMargin = Ui.dp(a, 18);
        pillLp.bottomMargin = Ui.dp(a, 16);
        outer.addView(splitPill, pillLp);

        // Initial UI sync
        syncPaperSwatches.run();
        syncSizeUi.run();
        syncTabs.run();
        refreshGallery[0].run();

        d.setContentView(outer);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            int sw = a.getResources().getDisplayMetrics().widthPixels;
            int sh = a.getResources().getDisplayMetrics().heightPixels;
            w.setLayout(Math.min(Ui.dp(a, 620), sw - Ui.dp(a, 24)),
                    Math.min(Ui.dp(a, 780), (int) (sh * 0.86f)));
        }
        d.show();
    }

    /** Floating card directly above the split Apply pill (Screenshot 5). */
    private static void showApplyOptionsPopup(Activity a, View anchor, boolean canvasNb,
                                              boolean[] setAsDefault, Runnable onApplyAll) {
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(a, 14);
        card.setPadding(p, Ui.dp(a, 12), p, Ui.dp(a, 12));
        GradientDrawable bg = Ui.round(Ui.color(a, R.color.surface), Ui.dp(a, 14));
        bg.setStroke(Ui.hairline(a), Ui.color(a, R.color.outline));
        card.setBackground(bg);

        PopupWindow pw = new PopupWindow(card, Ui.dp(a, 240), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pw.setElevation(Ui.dp(a, 12));
        pw.setOutsideTouchable(true);

        if (!canvasNb) {
            TextView allRow = Ui.text(a, "Apply to all pages", 15f, Ui.color(a, R.color.on_surface), Ui.medium());
            allRow.setPadding(Ui.dp(a, 6), Ui.dp(a, 8), Ui.dp(a, 6), Ui.dp(a, 10));
            allRow.setBackground(Ui.ripple(a, null, Ui.dp(a, 8)));
            allRow.setOnClickListener(v -> {
                pw.dismiss();
                onApplyAll.run();
            });
            card.addView(allRow, new LinearLayout.LayoutParams(-1, -2));
        }

        LinearLayout defRow = new LinearLayout(a);
        defRow.setOrientation(LinearLayout.HORIZONTAL);
        defRow.setGravity(Gravity.CENTER_VERTICAL);
        defRow.setPadding(Ui.dp(a, 2), Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6));
        defRow.setBackground(Ui.ripple(a, null, Ui.dp(a, 8)));
        CheckBox cb = new CheckBox(a);
        cb.setChecked(setAsDefault[0]);
        cb.setOnCheckedChangeListener((b, ch) -> setAsDefault[0] = ch);
        TextView defTv = Ui.text(a, "Set as default page style", 14.5f, Ui.color(a, R.color.on_surface), null);
        defTv.setPadding(Ui.dp(a, 6), 0, 0, 0);
        defRow.addView(cb);
        defRow.addView(defTv);
        defRow.setOnClickListener(v -> cb.toggle());
        card.addView(defRow, new LinearLayout.LayoutParams(-1, -2));

        card.measure(View.MeasureSpec.makeMeasureSpec(Ui.dp(a, 250), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int x = loc[0] + anchor.getWidth() - Ui.dp(a, 250);
        int y = loc[1] - card.getMeasuredHeight() - Ui.dp(a, 10);
        pw.setWidth(Ui.dp(a, 250));
        pw.showAtLocation(anchor, Gravity.TOP | Gravity.START, Math.max(Ui.dp(a, 12), x), Math.max(Ui.dp(a, 24), y));
    }

    private static View importActionCard(Context c, int iconRes, String title, String sub, View.OnClickListener onClick) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12));
        GradientDrawable bg = Ui.round(Ui.color(c, R.color.surface_variant), Ui.dp(c, 14));
        bg.setStroke(Ui.hairline(c), Ui.color(c, R.color.outline));
        card.setBackground(Ui.ripple(c, bg, Ui.dp(c, 14)));

        ImageView iv = new ImageView(c);
        iv.setImageResource(iconRes);
        iv.setColorFilter(Ui.color(c, R.color.accent));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22));
        ilp.rightMargin = Ui.dp(c, 10);
        card.addView(iv, ilp);

        LinearLayout textCol = new LinearLayout(c);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = Ui.text(c, title, 13.5f, Ui.color(c, R.color.on_surface), Ui.bold());
        t1.setSingleLine(true);
        t1.setEllipsize(TextUtils.TruncateAt.END);
        TextView t2 = Ui.text(c, sub, 11.5f, Ui.color(c, R.color.on_surface_variant), null);
        t2.setSingleLine(true);
        t2.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(t1);
        textCol.addView(t2);
        card.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1f));

        card.setOnClickListener(onClick);
        return card;
    }

    private static void promptSaveCustom(Activity a, int bg, java.util.function.Consumer<String> onName) {
        EditText et = new EditText(a);
        et.setHint("e.g. Dark Cornell, Study grid");
        et.setSingleLine(true);
        FrameLayout w = new FrameLayout(a);
        int pd = Ui.dp(a, 22);
        w.setPadding(pd, Ui.dp(a, 8), pd, 0);
        w.addView(et);
        new AlertDialog.Builder(a).setTitle("Save custom page style").setView(w)
                .setPositiveButton("Save", (dg, x) -> {
                    String n = et.getText().toString().trim();
                    if (n.isEmpty()) n = Notebook.BG_NAMES[Math.max(0, Math.min(Notebook.BG_NAMES.length - 1, bg))] + " (custom)";
                    onName.accept(n);
                })
                .setNegativeButton("Cancel", null).show();
        et.requestFocus();
    }

    private static void promptRenameCustom(Activity a, String currentName, java.util.function.Consumer<String> onName) {
        EditText et = new EditText(a);
        et.setText(currentName);
        et.setSelection(currentName.length());
        et.setSingleLine(true);
        FrameLayout w = new FrameLayout(a);
        int pd = Ui.dp(a, 22);
        w.setPadding(pd, Ui.dp(a, 8), pd, 0);
        w.addView(et);
        new AlertDialog.Builder(a).setTitle("Rename page style").setView(w)
                .setPositiveButton("Save", (dg, x) -> {
                    String n = et.getText().toString().trim();
                    if (!n.isEmpty()) onName.accept(n);
                })
                .setNegativeButton("Cancel", null).show();
        et.requestFocus();
    }

    private static TextView pillBtn(Context c, String text) {
        TextView tv = Ui.text(c, text, 13f, Ui.color(c, R.color.on_surface), Ui.medium());
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 14), 0);
        return tv;
    }

    private static EditText dimInput(Context c) {
        EditText et = new EditText(c);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setSingleLine(true);
        et.setGravity(Gravity.CENTER);
        et.setTextSize(13f);
        et.setTextColor(Ui.color(c, R.color.on_surface));
        et.setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 6), 0);
        GradientDrawable bg = Ui.round(Ui.color(c, R.color.surface), Ui.dp(c, 8));
        bg.setStroke(Ui.hairline(c), Ui.color(c, R.color.outline));
        et.setBackground(bg);
        return et;
    }

    private static ImageView smallActionIcon(Context c, int resId, String desc) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(resId);
        iv.setColorFilter(Ui.color(c, R.color.on_surface_variant));
        int p = Ui.dp(c, 7);
        iv.setPadding(p, p, p, p);
        iv.setBackground(Ui.ripple(c, Ui.round(Ui.color(c, R.color.surface), Ui.dp(c, 8)), Ui.dp(c, 8)));
        iv.setContentDescription(desc);
        return iv;
    }

    // ---- Template preview tile ----

    private static final class TileRef {
        final LinearLayout root;
        final ImageView thumb;
        final TextView label;
        final int bgId;

        TileRef(LinearLayout root, ImageView thumb, TextView label, int bgId) {
            this.root = root;
            this.thumb = thumb;
            this.label = label;
            this.bgId = bgId;
        }
    }

    private static TileRef createTemplateTile(Context c, String name, int bgId) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(Ui.dp(c, 2), Ui.dp(c, 2), Ui.dp(c, 2), Ui.dp(c, 6));

        ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.FIT_XY);
        iv.setClipToOutline(true);
        iv.setBackground(Ui.round(0xffffffff, Ui.dp(c, 8)));
        root.addView(iv, new LinearLayout.LayoutParams(Ui.dp(c, 92), Ui.dp(c, 128)));

        TextView tv = Ui.text(c, name, 12f, Ui.color(c, R.color.on_surface_variant), Ui.medium());
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, Ui.dp(c, 6), 0, 0);
        root.addView(tv, new LinearLayout.LayoutParams(-1, -2));

        root.setBackground(Ui.ripple(c, null, Ui.dp(c, 10)));
        return new TileRef(root, iv, tv, bgId);
    }

    private static void renderTile(Context c, TileRef tr, Notebook.Page look, int paper, boolean selected) {
        float aspect = look.h > 100f ? (look.h / Notebook.PW) : (Notebook.PH / Notebook.PW);
        int w = Ui.dp(c, 92);
        int h = Math.max(Ui.dp(c, 58), Math.min(Ui.dp(c, 138), Math.round(w * aspect)));
        ViewGroup.LayoutParams lp = tr.thumb.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            tr.thumb.setLayoutParams(lp);
        }

        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        if (look.bgImage != null && !look.bgImage.isEmpty()) {
            int[] pal = Render.PAPER[Math.max(0, Math.min(Render.PAPER.length - 1, paper))];
            cv.drawColor(pal[0]);
            Bitmap img = Render.imageFile(Notebook.bgImageFile(c, null, look.bgImage), Math.max(w, h) * 2);
            if (img != null) {
                cv.drawBitmap(img, null, new RectF(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG));
                img.recycle();
            }
        } else {
            boolean whole = look.bg == Notebook.BG_CORNELL || look.bg == Notebook.BG_CORNELL_B
                    || look.bg == Notebook.BG_LEGAL || look.bg == Notebook.BG_ENGINEERING
                    || look.bg == Notebook.BG_MUSIC || look.bg == Notebook.BG_GUITAR_TAB
                    || look.bg == Notebook.BG_GUITAR_SCORE || look.bg == Notebook.BG_DAILY
                    || look.bg == Notebook.BG_WEEKLY || look.bg == Notebook.BG_SITE;
            float scale = whole ? (w / Notebook.PW) : (w / Notebook.PW * 1.9f);
            cv.scale(scale, scale);
            Notebook nb = new Notebook();
            nb.paper = paper;
            Render.paper(cv, nb, look);
        }
        tr.thumb.setImageBitmap(b);

        GradientDrawable fr = Ui.round(0, Ui.dp(c, 8));
        fr.setStroke(selected ? Ui.dp(c, 2.5f) : Ui.hairline(c),
                Ui.color(c, selected ? R.color.accent : R.color.outline));
        tr.thumb.setForeground(fr);
        tr.label.setTextColor(Ui.color(c, selected ? R.color.accent : R.color.on_surface_variant));
    }

    // ---- Miniature page aspect preview inside Size bar ----

    private static final class SizePreviewView extends View {
        private int wVal = 2480, hVal = 3508;
        private int fillCol = 0xffffffff;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float d;

        SizePreviewView(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(Math.max(1f, d));
            stroke.setColor(Ui.color(c, R.color.outline));
        }

        void setAspect(int w, int h, int paperFill) {
            wVal = Math.max(1, w);
            hVal = Math.max(1, h);
            fillCol = paperFill;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float vw = getWidth() - d * 4f, vh = getHeight() - d * 4f;
            if (vw <= 0 || vh <= 0) return;
            float asp = hVal / (float) wVal;
            float dw = vh / asp, dh = vh;
            if (dw > vw) { dw = vw; dh = vw * asp; }
            float l = (getWidth() - dw) * 0.5f, t = (getHeight() - dh) * 0.5f;
            r.set(l, t, l + dw, t + dh);
            fill.setColor(fillCol);
            canvas.drawRoundRect(r, d * 3f, d * 3f, fill);
            canvas.drawRoundRect(r, d * 3f, d * 3f, stroke);
        }
    }

    // ---- Stepped dotted pill slider (1..10) matching Density & Line width in screenshots ----

    private static final class SteppedSliderView extends LinearLayout {
        private final TrackView track;
        private final TextView valTv;

        SteppedSliderView(Context c, String label, int min, int max, int init,
                          java.util.function.IntConsumer onChange) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);

            TextView lbl = Ui.text(c, label, 13.5f, Ui.color(c, R.color.on_surface_variant), Ui.medium());
            lbl.setMinWidth(Ui.dp(c, 82));
            addView(lbl, new LayoutParams(Ui.dp(c, 86), -2));

            valTv = Ui.text(c, String.valueOf(init), 13.5f, Ui.color(c, R.color.on_surface), Ui.bold());
            valTv.setGravity(Gravity.END);

            track = new TrackView(c, min, max, init, v -> {
                valTv.setText(String.valueOf(v));
                onChange.accept(v);
            });
            LayoutParams tlp = new LayoutParams(0, Ui.dp(c, 36), 1f);
            tlp.leftMargin = Ui.dp(c, 8);
            tlp.rightMargin = Ui.dp(c, 10);
            addView(track, tlp);

            addView(valTv, new LayoutParams(Ui.dp(c, 26), -2));
        }

        void setValue(int v) {
            track.setValueSilent(v);
            valTv.setText(String.valueOf(track.value));
        }

        private static final class TrackView extends View {
            final int min, max;
            int value;
            private final java.util.function.IntConsumer cb;
            private final Paint pTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint pDotOn = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint pDotOff = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint pThumb = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF r = new RectF();
            private final float d;

            TrackView(Context c, int min, int max, int init, java.util.function.IntConsumer cb) {
                super(c);
                this.min = min;
                this.max = max;
                this.value = Math.max(min, Math.min(max, init));
                this.cb = cb;
                d = c.getResources().getDisplayMetrics().density;
                int acc = Ui.color(c, R.color.accent);
                pTrack.setColor(Ui.color(c, R.color.surface_variant));
                pFill.setColor(acc);
                pThumb.setColor(acc);
                pDotOn.setColor((Ui.color(c, R.color.on_accent) & 0x00ffffff) | 0x99000000);
                pDotOff.setColor((Ui.color(c, R.color.on_surface_variant) & 0x00ffffff) | 0x66000000);
            }

            void setValueSilent(int v) {
                value = Math.max(min, Math.min(max, v));
                invalidate();
            }

            @Override public boolean onTouchEvent(MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE || a == MotionEvent.ACTION_UP) {
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    float pad = d * 10f;
                    float span = Math.max(1f, getWidth() - 2f * pad);
                    float frac = Math.max(0f, Math.min(1f, (e.getX() - pad) / span));
                    int nv = min + Math.round(frac * (max - min));
                    if (nv != value) {
                        value = nv;
                        invalidate();
                        cb.accept(nv);
                    }
                    return true;
                }
                return super.onTouchEvent(e);
            }

            @Override protected void onDraw(Canvas canvas) {
                float w = getWidth(), h = getHeight();
                if (w <= 0 || h <= 0) return;
                float pad = d * 10f;
                float th = d * 10f;
                float cy = h * 0.5f;
                float span = Math.max(1f, w - 2f * pad);
                float frac = (value - min) / (float) Math.max(1, max - min);
                float thumbX = pad + frac * span;

                // Full background pill track
                r.set(pad - th * 0.5f, cy - th * 0.5f, w - pad + th * 0.5f, cy + th * 0.5f);
                canvas.drawRoundRect(r, th * 0.5f, th * 0.5f, pTrack);

                // Active filled left track
                float fillRight = Math.max(pad, thumbX - d * 4f);
                r.set(pad - th * 0.5f, cy - th * 0.5f, fillRight, cy + th * 0.5f);
                canvas.drawRoundRect(r, th * 0.5f, th * 0.5f, pFill);

                // Discrete dots along the track
                int steps = max - min;
                float dotR = d * 1.4f;
                for (int i = 0; i <= steps; i++) {
                    float dx = pad + (i / (float) Math.max(1, steps)) * span;
                    if (Math.abs(dx - thumbX) < d * 6f) continue;
                    canvas.drawCircle(dx, cy, dotR, dx < thumbX ? pDotOn : pDotOff);
                }

                // Vertical capsule thumb (matching screenshots)
                float tw = d * 3.6f, tvh = d * 24f;
                r.set(thumbX - tw * 0.5f, cy - tvh * 0.5f, thumbX + tw * 0.5f, cy + tvh * 0.5f);
                canvas.drawRoundRect(r, tw * 0.5f, tw * 0.5f, pThumb);
            }
        }
    }
}

package app.nib.notes;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * "New" on the notes screen: name, notebook or infinite canvas, page style (every template and your own), paper,
 * and cover, chosen before the notebook is made. Opens with the default style ("Use for new notebooks").
 */
final class NewNotebookSheet {
    private NewNotebookSheet() {}

    interface Create {
        void create(String title, boolean canvas, Notebook.Page proto, int paper, String cover);
    }

    private static final class Choice {
        final String name;
        final Notebook.Page look;
        final int paper; // -1 = keep the paper chosen below
        Choice(String name, Notebook.Page look, int paper) { this.name = name; this.look = look; this.paper = paper; }
    }

    static void show(Activity a, String title, boolean startCanvas, Notebook.Page defLook, int defPaper, org.json.JSONArray mine,
                     java.util.function.Consumer<org.json.JSONObject> saveDefault, Create create) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        int sw = a.getResources().getDisplayMetrics().widthPixels, sh = a.getResources().getDisplayMetrics().heightPixels;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(a, sw < Ui.dp(a, 480) ? 18 : 26);
        box.setPadding(pad, Ui.dp(a, 20), pad, Ui.dp(a, 20));

        // header: title and Create
        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = Ui.text(a, startCanvas ? "New infinite canvas" : "New notebook", 24, Ui.color(a, R.color.on_surface), Theme.titleFace(a));
        head.addView(heading, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView cancel = Ui.text(a, "Cancel", 15, Ui.color(a, R.color.on_surface_variant), Ui.medium());
        cancel.setPadding(Ui.dp(a, 10), Ui.dp(a, 10), Ui.dp(a, 10), Ui.dp(a, 10));
        cancel.setOnClickListener(v -> d.dismiss());
        head.addView(cancel);
        TextView make = Ui.text(a, "Create", 15, Ui.color(a, R.color.on_accent), Ui.bold());
        make.setGravity(Gravity.CENTER);
        make.setPadding(Ui.dp(a, 18), 0, Ui.dp(a, 18), 0);
        make.setBackground(Ui.ripple(a, Ui.round(Ui.color(a, R.color.accent), Ui.dp(a, 20)), Ui.dp(a, 20)));
        head.addView(make, new LinearLayout.LayoutParams(-2, Ui.dp(a, 40)));
        box.addView(head);

        // name
        EditText name = new EditText(a);
        name.setText(title);
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setTextSize(16);
        name.setTextColor(Ui.color(a, R.color.on_surface));
        name.setPadding(Ui.dp(a, 14), 0, Ui.dp(a, 14), 0);
        GradientDrawable nbg = Ui.round(Ui.color(a, R.color.surface_variant), Ui.dp(a, 12));
        name.setBackground(nbg);
        name.setSelectAllOnFocus(true);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, Ui.dp(a, 46));
        nl.topMargin = Ui.dp(a, 14);
        box.addView(name, nl);

        // creation state
        final boolean[] canvas = {startCanvas};
        final int[] paper = {defPaper};
        final String[] cover = {Covers.random()};
        final boolean[] withCover = {true};
        final Notebook.Page[] look = {defLook};
        LinearLayout coverBox = new LinearLayout(a);
        coverBox.setOrientation(LinearLayout.VERTICAL);
        coverBox.setVisibility(startCanvas ? View.GONE : View.VISIBLE); // a canvas has no pages to put a cover before

        // Page size (defaults to A4: 2480 × 3508)
        int initPresetIdx = 1; // A4
        boolean initLandscape = false;
        if (defLook != null && defLook.h > 100f) {
            float dh = defLook.h;
            if (Math.abs(PageStyleDialog.SIZE_PRESETS[1].aspectH - dh) < 25f) {
                initPresetIdx = 1;
            } else if (Math.abs((Notebook.PW * PageStyleDialog.SIZE_PRESETS[1].wPx / (float) PageStyleDialog.SIZE_PRESETS[1].hPx) - dh) < 25f) {
                initPresetIdx = 1;
                initLandscape = true;
            } else {
                for (int i = 0; i < PageStyleDialog.SIZE_PRESETS.length; i++) {
                    if (i == 0 || i == 2 || i == 3 || i == 4) continue;
                    PageStyleDialog.SizePreset sp = PageStyleDialog.SIZE_PRESETS[i];
                    if (Math.abs(sp.aspectH - dh) < 25f) {
                        initPresetIdx = i;
                        break;
                    }
                    float lh = Notebook.PW * sp.wPx / (float) Math.max(1, sp.hPx);
                    if (Math.abs(lh - dh) < 25f) {
                        initPresetIdx = i;
                        initLandscape = true;
                        break;
                    }
                }
            }
        }
        final int[] sizePresetIdx = {initPresetIdx};
        final boolean[] sizeLandscape = {initLandscape};
        final float[] chosenPageH = {
                defLook != null && defLook.h > 100f ? defLook.h : PageStyleDialog.SIZE_PRESETS[1].aspectH
        };

        // page style: every template, then your own
        ArrayList<Choice> choices = new ArrayList<>();
        for (int i = 0; i < Notebook.BG_NAMES.length; i++) {
            Notebook.Page pg = new Notebook.Page(i);
            if (defLook != null && defLook.bg == i) pg.copyStyle(defLook); // the default keeps its spacing, colours…
            choices.add(new Choice(Notebook.BG_NAMES[i], pg, -1));
        }
        for (int i = 0; mine != null && i < mine.length(); i++) {
            org.json.JSONObject t = mine.optJSONObject(i);
            if (t != null) choices.add(new Choice(t.optString("n", "My template"), EditorActivity.lookOf(t), t.optInt("p", -1)));
        }
        box.addView(caption(a, "Page style"));
        Ui.Grid grid = new Ui.Grid(a, 78, 10);
        ArrayList<ImageView> previews = new ArrayList<>();
        ArrayList<View> tiles = new ArrayList<>();
        final int[] picked = {defLook != null ? Math.max(0, Math.min(Notebook.BG_NAMES.length - 1, defLook.bg)) : Notebook.BG_DOTS};
        Runnable mark = () -> {
            for (int i = 0; i < tiles.size(); i++) {
                GradientDrawable g = Ui.round(0x00000000, Ui.dp(a, 10));
                if (i == picked[0]) g.setStroke(Ui.dp(a, 2), Ui.color(a, R.color.accent));
                tiles.get(i).setBackground(g);
            }
        };
        Runnable redraw = () -> {
            for (int i = 0; i < previews.size(); i++) {
                Choice ch = choices.get(i);
                previews.get(i).setImageBitmap(preview(a, ch.look, ch.paper >= 0 && i >= Notebook.BG_NAMES.length ? ch.paper : paper[0]));
            }
        };
        final Runnable[] syncPaperSwatches = {null};
        for (int i = 0; i < choices.size(); i++) {
            final int k = i;
            LinearLayout tile = new LinearLayout(a);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            tile.setPadding(Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 6));
            ImageView iv = new ImageView(a);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setElevation(Ui.dp(a, 1));
            tile.addView(iv, new LinearLayout.LayoutParams(-1, Ui.dp(a, 100)));
            TextView tn = Ui.text(a, choices.get(i).name, 12, Ui.color(a, R.color.on_surface_variant), null);
            tn.setSingleLine(true);
            tn.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tn.setGravity(Gravity.CENTER);
            tn.setPadding(0, Ui.dp(a, 5), 0, 0);
            tile.addView(tn, new LinearLayout.LayoutParams(-1, -2));
            tile.setOnClickListener(v -> {
                picked[0] = k;
                Choice ch = choices.get(k);
                look[0] = ch.look;
                if (ch.paper >= 0 && ch.paper != paper[0]) {
                    paper[0] = Math.max(0, Math.min(Render.PAPER.length - 1, ch.paper));
                    if (syncPaperSwatches[0] != null) syncPaperSwatches[0].run();
                    redraw.run();
                }
                mark.run();
            });
            previews.add(iv);
            tiles.add(tile);
            grid.addView(tile);
        }
        mark.run();
        redraw.run();
        box.addView(grid);

        // page size (defaults to A4; hidden for infinite canvas)
        if (!startCanvas) {
            box.addView(caption(a, "Page size"));
            LinearLayout sizeRow = new LinearLayout(a);
            sizeRow.setOrientation(LinearLayout.HORIZONTAL);
            sizeRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView sizeDrop = Ui.text(a, "", 14f, Ui.color(a, R.color.on_surface), Ui.medium());
            sizeDrop.setPadding(Ui.dp(a, 14), Ui.dp(a, 10), Ui.dp(a, 14), Ui.dp(a, 10));
            GradientDrawable sbg = Ui.round(Ui.color(a, R.color.surface_variant), Ui.dp(a, 12));
            sbg.setStroke(Ui.hairline(a), Ui.color(a, R.color.outline));
            sizeDrop.setBackground(Ui.ripple(a, sbg, Ui.dp(a, 12)));

            ImageView orientBtn = new ImageView(a);
            orientBtn.setImageResource(R.drawable.ic_swap);
            orientBtn.setColorFilter(Ui.color(a, R.color.on_surface_variant));
            int op = Ui.dp(a, 9);
            orientBtn.setPadding(op, op, op, op);
            GradientDrawable obg = Ui.round(Ui.color(a, R.color.surface_variant), Ui.dp(a, 12));
            obg.setStroke(Ui.hairline(a), Ui.color(a, R.color.outline));
            orientBtn.setBackground(Ui.ripple(a, obg, Ui.dp(a, 12)));
            orientBtn.setContentDescription("Swap portrait and landscape");

            Runnable syncSizeLabel = () -> {
                PageStyleDialog.SizePreset sp = PageStyleDialog.SIZE_PRESETS[sizePresetIdx[0]];
                int wPx = sizeLandscape[0] ? sp.hPx : sp.wPx;
                int hPx = sizeLandscape[0] ? sp.wPx : sp.hPx;
                chosenPageH[0] = Math.max(400f, Math.min(3500f, Notebook.PW * hPx / (float) Math.max(1, wPx)));
                String orient = wPx > hPx ? "Landscape" : (wPx == hPx ? "Square" : "Portrait");
                sizeDrop.setText(sp.shortName + "  ·  " + wPx + " × " + hPx + "  (" + orient + ")   ▾");
            };
            syncSizeLabel.run();

            sizeDrop.setOnClickListener(v -> {
                PopupMenu pm = new PopupMenu(a, sizeDrop);
                for (int i = 0; i < PageStyleDialog.SIZE_PRESETS.length; i++) {
                    PageStyleDialog.SizePreset sp = PageStyleDialog.SIZE_PRESETS[i];
                    pm.getMenu().add(0, i, i, sp.label + " (" + sp.wPx + " × " + sp.hPx + ")");
                }
                pm.setOnMenuItemClickListener(mi -> {
                    int id = mi.getItemId();
                    if (id >= 0 && id < PageStyleDialog.SIZE_PRESETS.length) {
                        sizePresetIdx[0] = id;
                        sizeLandscape[0] = false;
                        syncSizeLabel.run();
                    }
                    return true;
                });
                pm.show();
            });

            orientBtn.setOnClickListener(v -> {
                sizeLandscape[0] = !sizeLandscape[0];
                syncSizeLabel.run();
            });

            sizeRow.addView(sizeDrop, new LinearLayout.LayoutParams(0, -2, 1f));
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(Ui.dp(a, 42), Ui.dp(a, 42));
            olp.leftMargin = Ui.dp(a, 8);
            sizeRow.addView(orientBtn, olp);
            box.addView(sizeRow);
        }

        // paper (6 circular paper swatches matching PageStyleDialog)
        box.addView(caption(a, "Paper colour"));
        LinearLayout paperRow = new LinearLayout(a);
        paperRow.setOrientation(LinearLayout.HORIZONTAL);
        paperRow.setGravity(Gravity.CENTER_VERTICAL);
        final View[] paperDots = new View[Notebook.PAPER_ORDER.length];
        syncPaperSwatches[0] = () -> {
            for (int i = 0; i < Notebook.PAPER_ORDER.length; i++) {
                int pid = Notebook.PAPER_ORDER[i];
                boolean sel = pid == paper[0];
                GradientDrawable gd = new GradientDrawable();
                gd.setShape(GradientDrawable.OVAL);
                gd.setColor(Render.PAPER[pid][0]);
                gd.setStroke(Ui.dp(a, sel ? 3 : 1),
                        sel ? Ui.color(a, R.color.accent) : Ui.color(a, R.color.outline));
                paperDots[i].setBackground(gd);
            }
        };
        for (int i = 0; i < Notebook.PAPER_ORDER.length; i++) {
            final int pid = Notebook.PAPER_ORDER[i];
            View dot = new View(a);
            int sz = Ui.dp(a, 36);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(sz, sz);
            if (i > 0) dlp.leftMargin = Ui.dp(a, 10);
            dot.setLayoutParams(dlp);
            dot.setContentDescription(Notebook.PAPER_NAMES[pid] + " paper");
            dot.setOnClickListener(v -> {
                paper[0] = pid;
                syncPaperSwatches[0].run();
                redraw.run();
            });
            paperDots[i] = dot;
            paperRow.addView(dot);
        }
        syncPaperSwatches[0].run();
        box.addView(paperRow);

        // cover
        coverBox.addView(Ui.switchRow(a, "Cover", "Shown on the notes screen and before page 1", true, on -> {
            withCover[0] = on;
            coverBox.getChildAt(1).setVisibility(on ? View.VISIBLE : View.GONE);
        }));
        coverBox.addView(Covers.picker(a, cover[0], cv -> cover[0] = cv));
        box.addView(coverBox);

        // remember
        final boolean[] asDefault = {false};
        box.addView(Ui.switchRow(a, "Make this the default", "New notebooks start with this page style and paper", false, on -> asDefault[0] = on));

        make.setOnClickListener(v -> {
            String t = name.getText().toString().trim();
            if (t.isEmpty()) t = title;
            Notebook.Page chosen = look[0] != null ? look[0] : new Notebook.Page(Notebook.BG_DOTS);
            Notebook.Page pg = new Notebook.Page(chosen.bg);
            pg.copyStyle(chosen);
            pg.h = !canvas[0] ? chosenPageH[0] : Notebook.PH;
            if (asDefault[0]) {
                try {
                    org.json.JSONObject defObj = new org.json.JSONObject().put("bg", pg.bg).put("s", pg.spacing).put("t", pg.strength)
                            .put("c", pg.lineColor).put("m", pg.margins).put("p", paper[0]).put("h", pg.h);
                    if (pg.bgImage != null && !pg.bgImage.isEmpty()) defObj.put("img", pg.bgImage);
                    saveDefault.accept(defObj);
                } catch (org.json.JSONException ignored) { }
            }
            d.dismiss();
            create.create(t, canvas[0], pg, paper[0],
                    canvas[0] || !withCover[0] ? null : cover[0]);
        });

        ScrollView sv = new ScrollView(a);
        sv.setBackground(Ui.round(Ui.color(a, R.color.surface), Ui.dp(a, 24)));
        sv.setClipToOutline(true);
        sv.addView(box);
        d.setContentView(sv);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setLayout(Math.min(Ui.dp(a, 600), sw - Ui.dp(a, 24)), Math.min(Ui.dp(a, 860), sh - Ui.dp(a, 48)));
        }
        d.show();
    }

    private static TextView caption(Activity a, String t) {
        TextView tv = Ui.text(a, t, 13, Ui.color(a, R.color.on_surface_variant), Ui.medium());
        tv.setPadding(0, Ui.dp(a, 18), 0, Ui.dp(a, 8));
        return tv;
    }

    /** A page-style preview: the top of the page, zoomed so the pattern reads (planners: the whole page). */
    private static Bitmap preview(Activity a, Notebook.Page look, int paper) {
        int w = Ui.dp(a, 70), h = Ui.dp(a, 99);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        if (look.bgImage != null && !look.bgImage.isEmpty()) {
            int[] pal = Render.PAPER[Math.max(0, Math.min(Render.PAPER.length - 1, paper))];
            c.drawColor(pal[0]);
            Bitmap img = Render.imageFile(Notebook.bgImageFile(a, null, look.bgImage), Math.max(w, h) * 2);
            if (img != null) {
                c.drawBitmap(img, null, new android.graphics.RectF(0, 0, w, h), new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
                img.recycle();
            }
            return b;
        }
        boolean whole = look.bg == Notebook.BG_DAILY || look.bg == Notebook.BG_WEEKLY || look.bg == Notebook.BG_CORNELL
                || look.bg == Notebook.BG_CORNELL_B || look.bg == Notebook.BG_LEGAL || look.bg == Notebook.BG_ENGINEERING
                || look.bg == Notebook.BG_MUSIC || look.bg == Notebook.BG_GUITAR_TAB || look.bg == Notebook.BG_GUITAR_SCORE
                || look.bg == Notebook.BG_SITE;
        float k = w / Notebook.PW * (whole ? 1f : 2.2f);
        c.scale(k, k);
        Notebook nb = new Notebook();
        nb.paper = paper;
        Notebook.Page pg = new Notebook.Page(look.bg);
        pg.copyStyle(look);
        Render.paper(c, nb, pg);
        return b;
    }
}

package app.nib.notes;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * "Page+" modal sheet for inserting pages Before / After the current page or as the Last Page:
 * quick paper carousel, full template picker (including My templates), importing pages from
 * another notebook, adding pages from gallery images, or importing pages from a PDF file.
 */
final class AddPageSheet {
    private AddPageSheet() {}

    interface Callback {
        void onAddTemplate(int placement, Notebook.Page look);
        void onImportFromNotebook(int placement, File srcDir, List<Integer> pageIndices, String srcTitle);
        void onPickImages(int placement);
        void onPickPdf(int placement);
    }

    private static final int[] QUICK_BGS = {
            Notebook.BG_BLANK,
            Notebook.BG_LINED,
            Notebook.BG_GRID,
            Notebook.BG_DAILY,
            Notebook.BG_DOTS,
            Notebook.BG_WEEKLY,
            Notebook.BG_CORNELL
    };

    private static final String[] POS_LABELS = {"Before", "After", "Last Page"};

    static void show(Activity a, File currentDir, int paper, Notebook.Page curStyle,
                     JSONArray myTemplates, int initialPos, ExecutorService bg, Callback cb) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Handler ui = new Handler(Looper.getMainLooper());
        int sw = a.getResources().getDisplayMetrics().widthPixels;
        int sh = a.getResources().getDisplayMetrics().heightPixels;
        boolean compact = sw < Ui.dp(a, 540);
        int pad = Ui.dp(a, compact ? 18 : 22);

        final int[] placement = {Math.max(Engine.POS_BEFORE, Math.min(Engine.POS_LAST, initialPos))};
        final boolean[] dismissed = {false};
        d.setOnDismissListener(x -> dismissed[0] = true);

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable sheetBg = new GradientDrawable();
        sheetBg.setColor(Ui.color(a, R.color.surface));
        float r = Ui.dp(a, 24);
        if (compact) sheetBg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        else sheetBg.setCornerRadius(r);
        root.setBackground(sheetBg);
        root.setClipToOutline(true);

        // Header: optional back arrow + title + close button
        LinearLayout header = new LinearLayout(a);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(pad, Ui.dp(a, 10), Ui.dp(a, 8), Ui.dp(a, 10));

        Ui.IconButton backBtn = new Ui.IconButton(a, R.drawable.ic_back, "Back");
        backBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 44));
        blp.rightMargin = Ui.dp(a, 4);
        header.addView(backBtn, blp);

        TextView headerTitle = Ui.text(a, "Add a page", 20, Ui.color(a, R.color.on_surface), Ui.bold());
        headerTitle.setSingleLine(true);
        headerTitle.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(headerTitle, new LinearLayout.LayoutParams(0, -2, 1f));

        Ui.IconButton closeBtn = new Ui.IconButton(a, R.drawable.ic_close, "Close");
        closeBtn.setOnClickListener(v -> d.dismiss());
        header.addView(closeBtn, new LinearLayout.LayoutParams(Ui.dp(a, 48), Ui.dp(a, 48)));
        root.addView(header);

        View divider = new View(a);
        divider.setBackgroundColor(Ui.color(a, R.color.outline));
        root.addView(divider, new LinearLayout.LayoutParams(-1, Ui.hairline(a)));

        FrameLayout contentHost = new FrameLayout(a);
        root.addView(contentHost, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Navigation helpers inside the sheet
        final Runnable[] showHomeRef = {null};
        final Runnable[] showNotebooksRef = {null};

        Runnable showTemplates = () -> {
            headerTitle.setText("More templates");
            backBtn.setVisibility(View.VISIBLE);
            backBtn.setOnClickListener(v -> showHomeRef[0].run());
            contentHost.removeAllViews();
            contentHost.addView(buildMoreTemplatesView(a, pad, paper, curStyle, myTemplates, placement, look -> {
                d.dismiss();
                cb.onAddTemplate(placement[0], look);
            }));
        };

        final java.util.function.Consumer<Notebook.Entry> showNotebookPages = entry -> {
            headerTitle.setText(entry.title);
            backBtn.setVisibility(View.VISIBLE);
            backBtn.setOnClickListener(v -> showNotebooksRef[0].run());
            contentHost.removeAllViews();
            contentHost.addView(buildNotebookPagesView(a, pad, entry, placement, bg, ui, dismissed, indices -> {
                d.dismiss();
                cb.onImportFromNotebook(placement[0], entry.dir, indices, entry.title);
            }));
        };

        showNotebooksRef[0] = () -> {
            headerTitle.setText("From another notebook");
            backBtn.setVisibility(View.VISIBLE);
            backBtn.setOnClickListener(v -> showHomeRef[0].run());
            contentHost.removeAllViews();
            contentHost.addView(buildNotebookListView(a, pad, currentDir, bg, ui, dismissed, showNotebookPages));
        };

        showHomeRef[0] = () -> {
            headerTitle.setText("Add a page");
            backBtn.setVisibility(View.GONE);
            contentHost.removeAllViews();
            contentHost.addView(buildMainView(a, pad, paper, curStyle, placement,
                    look -> { d.dismiss(); cb.onAddTemplate(placement[0], look); },
                    showTemplates,
                    showNotebooksRef[0],
                    () -> { d.dismiss(); cb.onPickImages(placement[0]); },
                    () -> { d.dismiss(); cb.onPickPdf(placement[0]); }));
        };

        showHomeRef[0].run();

        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = compact ? ViewGroup.LayoutParams.MATCH_PARENT : Math.min(Ui.dp(a, 540), sw - Ui.dp(a, 32));
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(compact ? Gravity.BOTTOM : Gravity.CENTER);
        }
        // Cap overall sheet height so it stays comfortable in landscape / split screen
        int maxH = Math.min(Ui.dp(a, 760), (int) (sh * 0.88f));
        root.addOnLayoutChangeListener((v, l, t, rt, b, ol, ot, or, ob) -> {
            if (b - t > maxH && d.getWindow() != null) {
                d.getWindow().setLayout(compact ? ViewGroup.LayoutParams.MATCH_PARENT : Math.min(Ui.dp(a, 540), sw - Ui.dp(a, 32)), maxH);
            }
        });
        d.show();
    }

    // ---- main "Page+" view -------------------------------------------------------------------

    private static View buildMainView(Activity a, int pad, int paper, Notebook.Page curStyle, int[] placement,
                                      java.util.function.Consumer<Notebook.Page> onPickTemplate,
                                      Runnable onMoreTemplates, Runnable onFromNotebook,
                                      Runnable onFromImages, Runnable onFromPdf) {
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(pad, Ui.dp(a, 14), pad, Ui.dp(a, 16));

        LinearLayout pills = buildPlacementRow(a, placement);
        body.addView(pills, new LinearLayout.LayoutParams(-1, -2));

        TextView sub = Ui.text(a, "Tap a paper to add the page", 13.5f, Ui.color(a, R.color.on_surface_variant), null);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = Ui.dp(a, 18);
        slp.bottomMargin = Ui.dp(a, 12);
        body.addView(sub, slp);

        // Horizontal carousel of quick paper cards
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        LinearLayout carousel = new LinearLayout(a);
        carousel.setOrientation(LinearLayout.HORIZONTAL);
        int cardW = Ui.dp(a, 114);
        int cardGap = Ui.dp(a, 10);
        for (int i = 0; i < QUICK_BGS.length; i++) {
            int bgId = QUICK_BGS[i];
            Notebook.Page pg = new Notebook.Page(bgId);
            if (curStyle != null) {
                if (curStyle.bg == bgId) pg.copyStyle(curStyle);
                if (curStyle.h > 100f) pg.h = curStyle.h;
            }
            View card = buildPaperCard(a, Notebook.BG_NAMES[bgId], pg, paper, () -> onPickTemplate.accept(pg));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(cardW, -2);
            if (i > 0) clp.leftMargin = cardGap;
            carousel.addView(card, clp);
        }
        hs.addView(carousel);
        body.addView(hs, new LinearLayout.LayoutParams(-1, -2));

        // Action rows matching Page+ layout
        LinearLayout actions = new LinearLayout(a);
        actions.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = Ui.dp(a, 14);
        actions.addView(buildMenuRow(a, R.drawable.ic_template, "More templates", onMoreTemplates));
        actions.addView(buildMenuRow(a, R.drawable.ic_pages, "From another notebook", onFromNotebook));
        actions.addView(buildMenuRow(a, R.drawable.ic_image, "From images", onFromImages));
        actions.addView(buildMenuRow(a, R.drawable.ic_import, "From files (PDF)", onFromPdf));
        body.addView(actions, alp);

        ScrollView sv = new ScrollView(a);
        sv.addView(body);
        return sv;
    }

    // ---- "More templates" sub-view -----------------------------------------------------------

    private static View buildMoreTemplatesView(Activity a, int pad, int paper, Notebook.Page curStyle,
                                               JSONArray myTemplates, int[] placement,
                                               java.util.function.Consumer<Notebook.Page> onPickTemplate) {
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(pad, Ui.dp(a, 14), pad, Ui.dp(a, 20));

        body.addView(buildPlacementRow(a, placement));

        String[] catNames = {"Basic", "Education", "Music", "Planners & Forms"};
        int[][] catBgs = {
                {Notebook.BG_BLANK, Notebook.BG_LINED, Notebook.BG_GRID, Notebook.BG_DOTS, Notebook.BG_NARROW, Notebook.BG_FINE_DOTS},
                {Notebook.BG_HEX, Notebook.BG_CORNELL, Notebook.BG_CORNELL_B, Notebook.BG_LEGAL, Notebook.BG_ENGINEERING, Notebook.BG_GRAPH, Notebook.BG_ISO},
                {Notebook.BG_MUSIC, Notebook.BG_GUITAR_TAB, Notebook.BG_GUITAR_SCORE},
                {Notebook.BG_DAILY, Notebook.BG_WEEKLY, Notebook.BG_SITE}
        };
        for (int ci = 0; ci < catNames.length; ci++) {
            TextView cap = Ui.text(a, catNames[ci], 13.5f, Ui.color(a, R.color.on_surface_variant), Ui.medium());
            cap.setPadding(0, Ui.dp(a, 16), 0, Ui.dp(a, 10));
            body.addView(cap);

            Ui.Grid grid = new Ui.Grid(a, 108, 10);
            for (int bgId : catBgs[ci]) {
                Notebook.Page pg = new Notebook.Page(bgId);
                if (curStyle != null) {
                    if (curStyle.bg == bgId) pg.copyStyle(curStyle);
                    if (curStyle.h > 100f) pg.h = curStyle.h;
                }
                grid.addView(buildPaperCard(a, Notebook.BG_NAMES[bgId], pg, paper, () -> onPickTemplate.accept(pg)));
            }
            body.addView(grid);
        }

        if (myTemplates != null && myTemplates.length() > 0) {
            TextView mineCap = Ui.text(a, "My templates", 13, Ui.color(a, R.color.on_surface_variant), Ui.medium());
            mineCap.setPadding(0, Ui.dp(a, 20), 0, Ui.dp(a, 10));
            body.addView(mineCap);

            Ui.Grid mineGrid = new Ui.Grid(a, 108, 10);
            for (int i = 0; i < myTemplates.length(); i++) {
                JSONObject t = myTemplates.optJSONObject(i);
                if (t == null) continue;
                Notebook.Page look = EditorActivity.lookOf(t);
                int p = t.optInt("p", paper);
                String name = t.optString("n", "My template");
                mineGrid.addView(buildPaperCard(a, name, look, p >= 0 ? p : paper, () -> onPickTemplate.accept(look)));
            }
            body.addView(mineGrid);
        }

        ScrollView sv = new ScrollView(a);
        sv.addView(body);
        return sv;
    }

    // ---- "From another notebook" sub-views ---------------------------------------------------

    private static View buildNotebookListView(Activity a, int pad, File currentDir, ExecutorService bg,
                                              Handler ui, boolean[] dismissed,
                                              java.util.function.Consumer<Notebook.Entry> onPickNotebook) {
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(pad, Ui.dp(a, 12), pad, Ui.dp(a, 20));

        List<Notebook.Entry> all = Notebook.list(a);
        List<Notebook.Entry> others = new ArrayList<>();
        for (Notebook.Entry e : all) {
            if (e.canvas) continue;
            if (currentDir != null && e.dir.equals(currentDir)) continue;
            others.add(e);
        }

        if (others.isEmpty()) {
            TextView empty = Ui.text(a, "No other notebooks found. Create another notebook first to import pages from it.",
                    14, Ui.color(a, R.color.on_surface_variant), null);
            empty.setPadding(0, Ui.dp(a, 24), 0, Ui.dp(a, 24));
            empty.setGravity(Gravity.CENTER);
            body.addView(empty);
        } else {
            TextView sub = Ui.text(a, "Choose a notebook to pick pages from", 13.5f, Ui.color(a, R.color.on_surface_variant), null);
            sub.setPadding(0, Ui.dp(a, 2), 0, Ui.dp(a, 10));
            body.addView(sub);

            for (Notebook.Entry e : others) {
                LinearLayout row = new LinearLayout(a);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 8));
                row.setBackground(Ui.ripple(a, null, Ui.dp(a, 12)));

                ImageView thumb = new ImageView(a);
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                GradientDrawable tbg = Ui.round(Ui.color(a, R.color.surface_variant), Ui.dp(a, 6));
                tbg.setStroke(Ui.hairline(a), Ui.color(a, R.color.outline));
                thumb.setBackground(tbg);
                thumb.setClipToOutline(true);
                thumb.setContentDescription(e.title);
                row.addView(thumb, new LinearLayout.LayoutParams(Ui.dp(a, 42), Ui.dp(a, 56)));

                bg.execute(() -> {
                    Bitmap bm = null;
                    File tf = new File(e.dir, "thumb.png");
                    if (tf.exists()) bm = android.graphics.BitmapFactory.decodeFile(tf.getPath());
                    if (bm == null && e.cover != null) bm = Covers.bitmap(a, e.cover, e.title, Ui.dp(a, 84));
                    final Bitmap res = bm;
                    ui.post(() -> { if (!dismissed[0] && res != null) thumb.setImageBitmap(res); });
                });

                LinearLayout texts = new LinearLayout(a);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.setPadding(Ui.dp(a, 14), 0, Ui.dp(a, 8), 0);
                TextView title = Ui.text(a, e.title, 15.5f, Ui.color(a, R.color.on_surface), Ui.medium());
                title.setSingleLine(true);
                title.setEllipsize(TextUtils.TruncateAt.END);
                texts.addView(title);
                String meta = e.pages + (e.pages == 1 ? " page" : " pages")
                        + (e.folder != null && !e.folder.isEmpty() ? " · " + e.folder : "");
                TextView subTv = Ui.text(a, meta, 12.5f, Ui.color(a, R.color.on_surface_variant), null);
                subTv.setPadding(0, Ui.dp(a, 3), 0, 0);
                texts.addView(subTv);
                row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

                ImageView chev = new ImageView(a);
                chev.setImageResource(R.drawable.ic_chevron);
                chev.setImageTintList(ColorStateList.valueOf(Ui.color(a, R.color.on_surface_variant)));
                row.addView(chev, new LinearLayout.LayoutParams(Ui.dp(a, 18), Ui.dp(a, 18)));

                row.setOnClickListener(v -> onPickNotebook.accept(e));
                body.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
        }

        ScrollView sv = new ScrollView(a);
        sv.addView(body);
        return sv;
    }

    private static View buildNotebookPagesView(Activity a, int pad, Notebook.Entry entry, int[] placement,
                                               ExecutorService bg, Handler ui, boolean[] dismissed,
                                               java.util.function.Consumer<List<Integer>> onImport) {
        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);

        LinearLayout topControls = new LinearLayout(a);
        topControls.setOrientation(LinearLayout.VERTICAL);
        topControls.setPadding(pad, Ui.dp(a, 14), pad, Ui.dp(a, 8));
        topControls.addView(buildPlacementRow(a, placement));

        LinearLayout selBar = new LinearLayout(a);
        selBar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(-1, -2);
        sblp.topMargin = Ui.dp(a, 12);
        TextView hint = Ui.text(a, "Loading pages…", 13.5f, Ui.color(a, R.color.on_surface_variant), null);
        selBar.addView(hint, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView selectAll = Ui.text(a, "Select all", 13.5f, Ui.color(a, R.color.accent), Ui.medium());
        selectAll.setPadding(Ui.dp(a, 10), Ui.dp(a, 8), Ui.dp(a, 4), Ui.dp(a, 8));
        selectAll.setVisibility(View.INVISIBLE);
        selBar.addView(selectAll);
        topControls.addView(selBar, sblp);
        wrap.addView(topControls);

        Ui.Grid grid = new Ui.Grid(a, 118, 12);
        LinearLayout gridWrap = new LinearLayout(a);
        gridWrap.setOrientation(LinearLayout.VERTICAL);
        gridWrap.setPadding(pad, Ui.dp(a, 4), pad, Ui.dp(a, 12));
        gridWrap.addView(grid);
        ScrollView sv = new ScrollView(a);
        sv.addView(gridWrap);
        wrap.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Footer CTA button
        LinearLayout footer = new LinearLayout(a);
        footer.setPadding(pad, Ui.dp(a, 10), pad, Ui.dp(a, 16));
        TextView importBtn = Ui.button(a, "Select pages to add", 0, true);
        importBtn.setEnabled(false);
        importBtn.setAlpha(0.45f);
        footer.addView(importBtn, new LinearLayout.LayoutParams(-1, Ui.dp(a, 48)));
        wrap.addView(footer);

        final Set<Integer> selected = new HashSet<>();
        final List<Runnable> tileRefreshers = new ArrayList<>();
        final int[] totalPages = {0};

        Runnable updateBar = () -> {
            int n = selected.size();
            importBtn.setEnabled(n > 0);
            importBtn.setAlpha(n > 0 ? 1f : 0.45f);
            importBtn.setText(n == 0 ? "Select pages to add"
                    : "Add " + n + (n == 1 ? " page" : " pages") + " (" + POS_LABELS[placement[0]] + ")");
            selectAll.setText(n == totalPages[0] && n > 0 ? "Clear" : "Select all");
        };

        selectAll.setOnClickListener(v -> {
            if (selected.size() == totalPages[0]) selected.clear();
            else for (int i = 0; i < totalPages[0]; i++) selected.add(i);
            for (Runnable r : tileRefreshers) r.run();
            updateBar.run();
        });

        importBtn.setOnClickListener(v -> {
            if (selected.isEmpty()) return;
            List<Integer> ordered = new ArrayList<>(selected);
            Collections.sort(ordered);
            onImport.accept(ordered);
        });

        // Re-sync CTA text if placement pill changes
        for (int i = 0; i < ((ViewGroup) topControls.getChildAt(0)).getChildCount(); i++) {
            View pill = ((ViewGroup) topControls.getChildAt(0)).getChildAt(i);
            pill.setOnClickListener(v -> {
                placement[0] = (Integer) v.getTag();
                stylePlacementPills(a, (LinearLayout) topControls.getChildAt(0), placement[0]);
                updateBar.run();
            });
        }

        // Load notebook metadata and render page thumbnails off main thread
        bg.execute(() -> {
            Notebook nb = Notebook.load(entry.dir);
            int count = nb.pages.size();
            ui.post(() -> {
                if (dismissed[0]) return;
                totalPages[0] = count;
                hint.setText("Tap pages to select (" + count + (count == 1 ? " page)" : " pages)"));
                selectAll.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
                grid.removeAllViews();
                tileRefreshers.clear();
                List<ImageView> thumbViews = new ArrayList<>(count);

                for (int i = 0; i < count; i++) {
                    final int idx = i;
                    LinearLayout card = new LinearLayout(a);
                    card.setOrientation(LinearLayout.VERTICAL);
                    card.setGravity(Gravity.CENTER_HORIZONTAL);

                    Ui.Aspect frame = new Ui.Aspect(a, Notebook.PH / Notebook.PW);
                    frame.setClipToOutline(true);
                    frame.setElevation(Ui.dp(a, 1));

                    ImageView iv = new ImageView(a);
                    iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    frame.addView(iv, new FrameLayout.LayoutParams(-1, -1));
                    thumbViews.add(iv);

                    FrameLayout badge = new FrameLayout(a);
                    badge.setBackground(Ui.round(Ui.color(a, R.color.accent), Ui.dp(a, 14)));
                    ImageView chk = new ImageView(a);
                    chk.setImageResource(R.drawable.ic_check);
                    chk.setImageTintList(ColorStateList.valueOf(Ui.color(a, R.color.on_accent)));
                    badge.addView(chk, new FrameLayout.LayoutParams(Ui.dp(a, 15), Ui.dp(a, 15), Gravity.CENTER));
                    FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(Ui.dp(a, 26), Ui.dp(a, 26), Gravity.TOP | Gravity.END);
                    blp.topMargin = Ui.dp(a, 6);
                    blp.rightMargin = Ui.dp(a, 6);
                    frame.addView(badge, blp);

                    View hit = new View(a);
                    hit.setBackground(Ui.ripple(a, null, Ui.dp(a, 8)));
                    frame.addView(hit, new FrameLayout.LayoutParams(-1, -1));

                    TextView label = Ui.text(a, "Page " + (i + 1), 12.5f, Ui.color(a, R.color.on_surface_variant), Ui.medium());
                    label.setPadding(0, Ui.dp(a, 6), 0, 0);

                    Runnable refreshTile = () -> {
                        boolean on = selected.contains(idx);
                        GradientDrawable fg = Ui.round(Ui.color(a, R.color.surface), Ui.dp(a, 8));
                        fg.setStroke(on ? Ui.dp(a, 2) : Ui.hairline(a), Ui.color(a, on ? R.color.accent : R.color.outline));
                        frame.setBackground(fg);
                        badge.setVisibility(on ? View.VISIBLE : View.GONE);
                        label.setTextColor(Ui.color(a, on ? R.color.accent : R.color.on_surface_variant));
                    };
                    refreshTile.run();
                    tileRefreshers.add(refreshTile);

                    hit.setOnClickListener(v -> {
                        if (selected.contains(idx)) selected.remove(idx);
                        else selected.add(idx);
                        refreshTile.run();
                        updateBar.run();
                    });

                    card.addView(frame, new LinearLayout.LayoutParams(-1, -2));
                    card.addView(label);
                    grid.addView(card);
                }
                updateBar.run();

                // Render thumbnails progressively on background thread
                bg.execute(() -> {
                    try (Render.Pdf pdf = Render.Pdf.open(entry.dir)) {
                        for (int i = 0; i < count && !dismissed[0]; i++) {
                            Notebook.Page p = nb.pages.get(i);
                            Notebook.Page prev = i > 0 ? nb.pages.get(i - 1) : null;
                            Notebook.Page next = i + 1 < count ? nb.pages.get(i + 1) : null;
                            Bitmap bm = Render.pageBitmap(nb, entry.dir, p, prev, next, p.pdf >= 0 ? pdf : null, 240);
                            p.unload();
                            final int pi = i;
                            ui.post(() -> { if (!dismissed[0] && pi < thumbViews.size()) thumbViews.get(pi).setImageBitmap(bm); });
                        }
                    }
                });
            });
        });

        return wrap;
    }

    // ---- reusable UI pieces ------------------------------------------------------------------

    private static LinearLayout buildPlacementRow(Activity a, int[] placement) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < POS_LABELS.length; i++) {
            final int pos = i;
            TextView pill = Ui.text(a, POS_LABELS[i], 14, Ui.color(a, R.color.on_surface_variant), Ui.medium());
            pill.setTag(pos);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(Ui.dp(a, 18), 0, Ui.dp(a, 18), 0);
            pill.setMinHeight(Ui.dp(a, 44));
            pill.setOnClickListener(v -> {
                placement[0] = pos;
                stylePlacementPills(a, row, pos);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Ui.dp(a, 44));
            if (i > 0) lp.leftMargin = Ui.dp(a, 10);
            row.addView(pill, lp);
        }
        stylePlacementPills(a, row, placement[0]);
        return row;
    }

    private static void stylePlacementPills(Activity a, LinearLayout row, int active) {
        for (int i = 0; i < row.getChildCount(); i++) {
            TextView pill = (TextView) row.getChildAt(i);
            boolean on = i == active;
            GradientDrawable g = Ui.round(Ui.color(a, on ? R.color.accent_container : R.color.surface), Ui.dp(a, 14));
            if (!on) g.setStroke(Ui.dp(a, 1), Ui.color(a, R.color.outline));
            pill.setBackground(Ui.ripple(a, g, Ui.dp(a, 14)));
            pill.setTextColor(Ui.color(a, on ? R.color.accent : R.color.on_surface_variant));
            pill.setTypeface(on ? Ui.bold() : Ui.medium());
        }
    }

    private static View buildPaperCard(Activity a, String name, Notebook.Page look, int paper, Runnable onClick) {
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 10));
        GradientDrawable cardBg = Ui.round(Ui.color(a, R.color.surface), Ui.dp(a, 14));
        cardBg.setStroke(Ui.dp(a, 1), Ui.color(a, R.color.outline));
        card.setBackground(Ui.ripple(a, cardBg, Ui.dp(a, 14)));

        // Paper thumbnail container + centered circular accent "+" badge
        FrameLayout previewWrap = new FrameLayout(a);
        GradientDrawable pvBg = Ui.round(Ui.color(a, R.color.surface_variant), Ui.dp(a, 8));
        pvBg.setStroke(Ui.hairline(a), Ui.color(a, R.color.outline));
        previewWrap.setBackground(pvBg);
        previewWrap.setClipToOutline(true);

        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setImageBitmap(previewBitmap(a, look, paper));
        iv.setContentDescription(name);
        previewWrap.addView(iv, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout plusBadge = new FrameLayout(a);
        plusBadge.setBackground(Ui.round(Ui.color(a, R.color.accent), Ui.dp(a, 18)));
        plusBadge.setElevation(Ui.dp(a, 2));
        ImageView plusIcon = new ImageView(a);
        plusIcon.setImageResource(R.drawable.ic_plus);
        plusIcon.setImageTintList(ColorStateList.valueOf(Ui.color(a, R.color.on_accent)));
        plusBadge.addView(plusIcon, new FrameLayout.LayoutParams(Ui.dp(a, 18), Ui.dp(a, 18), Gravity.CENTER));
        previewWrap.addView(plusBadge, new FrameLayout.LayoutParams(Ui.dp(a, 34), Ui.dp(a, 34), Gravity.CENTER));

        card.addView(previewWrap, new LinearLayout.LayoutParams(-1, Ui.dp(a, 128)));

        TextView label = Ui.text(a, name, 13.5f, Ui.color(a, R.color.on_surface), Ui.medium());
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setPadding(Ui.dp(a, 2), Ui.dp(a, 10), Ui.dp(a, 2), 0);
        card.addView(label, new LinearLayout.LayoutParams(-1, -2));

        card.setOnClickListener(v -> onClick.run());
        return card;
    }

    private static View buildMenuRow(Activity a, int iconRes, String label, Runnable onClick) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(a, 4), 0, Ui.dp(a, 4), 0);
        row.setBackground(Ui.ripple(a, null, Ui.dp(a, 12)));
        row.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(a, 54)));

        ImageView ic = new ImageView(a);
        ic.setImageResource(iconRes);
        ic.setImageTintList(ColorStateList.valueOf(Ui.color(a, R.color.on_surface)));
        row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(a, 22), Ui.dp(a, 22)));

        TextView tv = Ui.text(a, label, 15.5f, Ui.color(a, R.color.on_surface), Ui.medium());
        tv.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 8), 0);
        row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));

        ImageView chev = new ImageView(a);
        chev.setImageResource(R.drawable.ic_chevron);
        chev.setImageTintList(ColorStateList.valueOf(Ui.color(a, R.color.on_surface_variant)));
        row.addView(chev, new LinearLayout.LayoutParams(Ui.dp(a, 18), Ui.dp(a, 18)));

        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    /** Live preview of a paper template (whole page for planners/structured templates, zoomed for lines/grid/dots). */
    private static Bitmap previewBitmap(Activity a, Notebook.Page look, int paper) {
        int w = Ui.dp(a, 96), h = Ui.dp(a, 128);
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
        boolean whole = look.bg == Notebook.BG_DAILY || look.bg == Notebook.BG_WEEKLY
                || look.bg == Notebook.BG_CORNELL || look.bg == Notebook.BG_CORNELL_B
                || look.bg == Notebook.BG_LEGAL || look.bg == Notebook.BG_ENGINEERING
                || look.bg == Notebook.BG_MUSIC || look.bg == Notebook.BG_GUITAR_TAB
                || look.bg == Notebook.BG_GUITAR_SCORE || look.bg == Notebook.BG_SITE;
        float k = w / Notebook.PW * (whole ? 1f : 1.7f);
        c.scale(k, k);
        Notebook nb = new Notebook();
        nb.paper = paper;
        Notebook.Page pg = new Notebook.Page(look.bg);
        pg.copyStyle(look);
        if (look.h > 100f) pg.h = look.h;
        Render.paper(c, nb, pg);
        if (pg.bg == Notebook.BG_DAILY || pg.bg == Notebook.BG_WEEKLY) {
            Render.plannerLabels(c, pg.bg, paper, pg.h);
        }
        return b;
    }
}

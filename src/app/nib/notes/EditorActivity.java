package app.nib.notes;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.widget.PopupMenu;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class EditorActivity extends Activity implements Engine.Listener, Ruler.Host {
    private static final int[] PEN_COLORS = {0xff1d1f24, 0xff2f5bff, 0xffe23b3b, 0xff139a57};
    private static final int[] HL_COLORS = {0xffffe066, 0xffb2f2a6, 0xffffbfe0, 0xffaedcff};
    static final int[] INK_PALETTE = {
            0xff1d1f24, 0xff5b6170, 0xff9aa0ac, 0xffffffff, 0xff8a5a2b, 0xff2f5bff, 0xff00a3ff,
            0xff00a99d, 0xff139a57, 0xff7cb518, 0xfff2b705, 0xffff7a1a, 0xffe23b3b, 0xffe8437a,
            0xffd63cff, 0xff7b4dff, 0xff3b2fa8, 0xff0b4f6c, 0xff5c1a1a, 0xff6b7a2f, 0xffb08968};
    static final int[] HL_PALETTE = {
            0xffffe066, 0xfffff3a0, 0xffffd0a6, 0xffffbfe0, 0xfff3c4ff, 0xffd9c8ff, 0xffaedcff,
            0xffa8f0e8, 0xffb2f2a6, 0xffd8f5a2, 0xffe6e6e6, 0xffffc2c2, 0xffc2e0ff, 0xfffde2b8};
    private static final float PEN_MIN = 0.5f, PEN_MAX = 16f, HL_MIN = 6f, HL_MAX = 64f;
    private static final float[] PEN_PRESETS = {1.2f, 2.6f, 6f}, HL_PRESETS = {12f, 22f, 40f};

    /** Thickness slider 0..100 ↔ width: even steps feel even (each step is the same ratio, not the same amount). */
    private static float sliderToW(int v, float min, float max) { return (float) (min * Math.pow(max / min, v / 100.0)); }

    private static int wToSlider(float w, float min, float max) {
        w = Math.max(min, Math.min(max, w));
        return (int) Math.round(Math.log(w / min) / Math.log(max / min) * 100);
    }

    /** Ink diameter in screen px for this pen at normal writing pressure, at the current zoom. */
    private float inkPx(int tool, float w) {
        float z = state != null && state.z > 0 ? state.z : 1.8f;
        return new Stroke(tool, 0, w).radius(0.5f) * 2 * z;
    }
    private static final String[] PEN_TYPES = {"Fountain", "Ballpoint", "Fineliner"};
    private static final int[] PEN_TYPE_IDS = {Stroke.PEN, Stroke.BALL, Stroke.FINE};
    private static final int REQ_IMAGE = 11, REQ_SAVE_PDF = 12, REQ_FONT = 13, REQ_STICKERS = 14, REQ_PAGE_IMAGES = 15, REQ_PAGE_PDF = 16;
    private File pendingSave; // exported PDF waiting for the user to pick where to save it
    private int pendingPagePos = Engine.POS_AFTER;
    private Engine pendingPageEngine;
    private Runnable afterPageImport;

    private Engine engine;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private int tool, prevTool = Engine.TOOL_PEN;
    private final int[] penColors = PEN_COLORS.clone(), hlColors = HL_COLORS.clone();
    private int penSlot, hlSlot, penType, prediction;
    private float textPt;          // size of new text boxes, in points
    private String textStyleEnc;   // font + style of new text boxes (Fonts.Style, encoded)
    private Engine.TextEdit fontImportFor; // the box being edited when the font picker opened
    private Engine fontImportEngine;
    private Runnable afterFontImport;      // refreshes the panel the import came from
    private float penW, hlW, eraserDp;
    private boolean fingerDraw, hud, partialErase, shapeSnap, penButton, taper;
    private boolean hlFlat, holdErase, erasePreview;
    private int hlStraight;
    private static final int[] LASER_COLORS = {0xffff3b30, 0xff34c759, 0xff0a84ff, 0xffaf52de, 0xffff9f0a, 0xffff2d92};
    private static final String[] LASER_COLOR_NAMES = {"Red", "Green", "Blue", "Purple", "Orange", "Pink"};
    private int laserCol, laserMode, laserSizeIdx = 1, laserTrail, laserInkWait;
    private boolean laserHover;
    private static final int[] TAPE_COLORS = {0xff9ecbf2, 0xffa8e6c8, 0xfff5dc7a, 0xfff7b89a, 0xfff3a6c8, 0xffc5b3f0, 0xffc9ccd3, 0xffd9b98c, 0xffeee3c8};
    private static final String[] TAPE_COLOR_NAMES = {"Sky", "Mint", "Butter", "Peach", "Pink", "Lavender", "Grey", "Kraft", "Cream"};
    private static final float[] TAPE_THICK = {28f, 42f, 64f};
    private int tapeCol, tapePat = 1, tapeThickIdx = 1, tapeLook = 1; // look: Render.TAPE_LOOKS, masking by default
    private boolean tapeFree;

    // Zen layout: no bars; three floating capsules that fade while the pen is writing.
    private static final int CAP_DP = 48, TOOLCAP_DP = 60, EDGE_DP = 16;
    private FrameLayout root;
    private RateKeeper rateKeeper;
    private float fastestRate = 144f;
    private LinearLayout capLeft, capRight, capTools;
    private TextView hudView, toastView, pasteChip;
    private Ui.ToolDisc penB, hlB, eraserB, lassoB, laserB, textB, tapeB;
    private Ui.IconButton undoB, redoB, addPageB, pagesB, bookmarkB;
    private LinearLayout favRow;
    private Ruler ruler;
    private Ui.ToolDisc rulerB, fingerB, moreToolsB;
    private Ui.IconButton imgB;
    private int drawScale; // 1:N for this notebook; 0 = none
    private View favSep;
    private final java.util.ArrayList<int[]> favs = new java.util.ArrayList<>(); // tool, penType, colour, width bits
    private final Ui.Swatch[] sw = new Ui.Swatch[4];
    private Ui.SizeDot sizeB;
    private LinearLayout palette;
    private SelectionView selView;
    private LinearLayout selBar;
    private View editTextB, textTools;
    private Engine.State state;
    private Ui.Popover pop;
    private boolean autoHide;

    private EditText textEdit;
    private Engine.TextEdit textReq;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.applyTo(this);
        String dirPath = getIntent().getStringExtra("dir");
        if (dirPath == null) { finish(); return; }
        if (!new File(dirPath, "ink.bin").exists() && !new File(dirPath, "ink.bin.old").exists()) {
            // a notebook that's gone (deleted, moved to the trash): don't show an empty one that writing would bring back
            Diag.init(this);
            Diag.warn("asked to open a missing notebook: " + dirPath);
            android.widget.Toast.makeText(this, "That notebook isn't there any more", android.widget.Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        prefs = getSharedPreferences("nib", MODE_PRIVATE);
        tool = prefs.getInt("tool", Engine.TOOL_PEN);
        if (tool == Engine.TOOL_LASSO || tool == Engine.TOOL_TEXT || tool == Engine.TOOL_LASER || tool == Engine.TOOL_TAPE) tool = Engine.TOOL_PEN;
        penSlot = prefs.getInt("penColor", 0);
        hlSlot = prefs.getInt("hlColor", 0);
        penW = prefs.getFloat("penW", 2.6f);
        hlW = prefs.getFloat("hlW", 22f);
        penType = prefs.getInt("penType", Stroke.PEN);
        textPt = prefs.getFloat("textPt", new float[]{12, 17, 25}[Math.max(0, Math.min(2, prefs.getInt("textSize", 1)))]);
        textStyleEnc = prefs.getString("textStyle", null);
        eraserDp = prefs.getFloat("eraserDp", 11f);
        for (int i = 0; i < 4; i++) {
            penColors[i] = prefs.getInt("pc" + i, PEN_COLORS[i]);
            hlColors[i] = prefs.getInt("hc" + i, HL_COLORS[i]);
        }
        prediction = prefs.getInt("predLevel", 1);
        fingerDraw = prefs.getBoolean("finger", false);
        taper = prefs.getBoolean("taper", true);
        hud = prefs.getBoolean("hud", false);
        partialErase = prefs.getBoolean("partialErase", false);
        shapeSnap = prefs.getBoolean("shapeSnap", true);
        penButton = prefs.getBoolean("penButton", true);
        autoHide = prefs.getBoolean("autoHide", true);
        hlFlat = prefs.getBoolean("hlFlat", false);
        hlStraight = prefs.getInt("hlStraight", 1);
        holdErase = prefs.getBoolean("holdErase", true);
        erasePreview = prefs.getBoolean("erasePreview", true);
        laserCol = prefs.getInt("laserCol", 0);
        tapeCol = Math.min(TAPE_COLORS.length - 1, prefs.getInt("tapeCol", 0));
        tapePat = prefs.getInt("tapePat", 1);
        tapeLook = prefs.getInt("tapeLook", 1);
        tapeThickIdx = prefs.getInt("tapeThick", 1);
        tapeFree = prefs.getBoolean("tapeFree", false);
        laserMode = prefs.getInt("laserMode", Engine.LASER_TRAIL_MODE);
        laserSizeIdx = prefs.getInt("laserSize", 1);
        laserTrail = prefs.getInt("laserTrail", 520);
        laserInkWait = prefs.getInt("laserInkWait", 1500);
        laserHover = prefs.getBoolean("laserHover", true);
        maxZoomPct = prefs.getInt("maxZoom", 800);
        loadFavs();

        preferFastestDisplayMode();
        getWindow().setDecorFitsSystemWindows(false);

        root = new FrameLayout(this);
        rateKeeper = new RateKeeper(this);
        rateKeeper.setRate(fastestRate);
        paneRow = new LinearLayout(this);
        panes[0] = new Pane(0);
        panes[1] = new Pane(1);
        splitDivider = new View(this);
        splitDivider.setBackgroundColor(Ui.color(this, R.color.outline));
        paneRow.addView(panes[0]);
        paneRow.addView(splitDivider);
        paneRow.addView(panes[1]);
        splitVert = prefs.getBoolean("splitVert", false);
        splitRatio = prefs.getFloat("splitRatio", 0.5f);
        layoutSplit();
        splitDivider.setVisibility(View.GONE);
        panes[1].setVisibility(View.GONE);
        root.addView(paneRow, new FrameLayout.LayoutParams(-1, -1));
        buildSplitKnob();
        restoreTabs(new File(dirPath), getIntent().getIntExtra("page", -1), getIntent().getBooleanExtra("split", false));
        ruler = new Ruler(this, this);
        root.addView(ruler, new FrameLayout.LayoutParams(-1, -1));
        buildTopCapsules();
        buildToolCapsule();
        buildBottom();
        dock = prefs.getInt("toolDock", DOCK_BOTTOM);
        applyDock();
        buildSelectionBar();
        buildZoomChip();
        root.addView(rateKeeper, new FrameLayout.LayoutParams(1, 1));
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
            if (ime != imeBottom) { imeBottom = ime; updateImeLift(); }
            return insets;
        });
        activate(cur);
        root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> { if (r - l != or - ol) ui.post(this::fitCapsule); });
    }

    private void pushOptions() {
        engine.setOptions(prediction, fingerDraw, hud, partialErase, shapeSnap);
        engine.setEraserSize(eraserDp);
        engine.setPenType(penType);
        engine.setHighlighter(hlFlat, hlStraight);
        engine.setEraseOptions(holdErase, erasePreview);
        engine.setTaper(taper);
        engine.setZoomLimit(maxZoomPct / 100f);
        pushLaser();
        pushTape();
    }

    /** The tape's pattern string: "look~pattern" for the paper looks, just the pattern for flat tape (as before 2.3.1). */
    private String tapePatternStr() {
        String pat = Notebook.Item.TAPE_PATTERNS[tapePat];
        return tapeLook == 0 ? pat : Render.TAPE_LOOKS[tapeLook] + "~" + pat;
    }

    private void pushTape() {
        engine.setTape(TAPE_COLORS[tapeCol], tapePatternStr(), TAPE_THICK[tapeThickIdx], tapeFree);
        prefs.edit().putInt("tapeCol", tapeCol).putInt("tapePat", tapePat).putInt("tapeThick", tapeThickIdx).putBoolean("tapeFree", tapeFree)
                .putInt("tapeLook", tapeLook).apply();
    }

    private void showTapePanel(View anchor) {
        Ui.Popover p = newPop(372).title("Tape");
        p.add(Ui.text(this, "Draw over answers to cover them. Tap tape to peel it back, tap again to cover. Erase or lasso it like ink.",
                12.5f, Ui.color(this, R.color.on_surface_variant), null));
        Ui.TapePreview prev = new Ui.TapePreview(this);
        Runnable refresh = () -> prev.set(TAPE_COLORS[tapeCol], tapePatternStr(), TAPE_THICK[tapeThickIdx], tapeFree);
        refresh.run();
        LinearLayout.LayoutParams pl = fullW(64);
        pl.topMargin = Ui.dp(this, 12);
        prev.setLayoutParams(pl);
        p.add(prev);
        p.section("Look");
        p.add(Ui.segmented(this, new String[]{"Flat", "Masking", "Washi", "Kraft"}, tapeLook, i -> { tapeLook = i; pushTape(); refresh.run(); }));
        p.section("Shape");
        p.add(Ui.segmented(this, new String[]{"Straight", "Freeform"}, tapeFree ? 1 : 0, i -> { tapeFree = i == 1; pushTape(); refresh.run(); }));
        p.section("Pattern");
        p.add(Ui.segmented(this, new String[]{"Plain", "Stripes", "Grid", "Dots"}, tapePat, i -> { tapePat = i; pushTape(); refresh.run(); }));
        p.section("Thickness");
        p.add(Ui.segmented(this, new String[]{"Thin", "Medium", "Thick"}, tapeThickIdx, i -> { tapeThickIdx = i; pushTape(); refresh.run(); }));
        p.section("Colour");
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Ui.Swatch[] dots = new Ui.Swatch[TAPE_COLORS.length];
        for (int i = 0; i < TAPE_COLORS.length; i++) {
            final int idx = i;
            dots[i] = new Ui.Swatch(this);
            dots[i].set(TAPE_COLORS[i], i == tapeCol);
            dots[i].setContentDescription(TAPE_COLOR_NAMES[i]);
            dots[i].setOnClickListener(v -> {
                tapeCol = idx;
                for (int k = 0; k < dots.length; k++) dots[k].set(TAPE_COLORS[k], k == idx);
                pushTape();
                applyTool();
                refresh.run();
                if (state != null && state.hasSelection) engine.selRecolor(TAPE_COLORS[idx]);
            });
            row.addView(dots[i], new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f));
        }
        p.add(row);
        p.section("This page");
        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.VERTICAL);
        acts.addView(Ui.actionRow(this, R.drawable.ic_tape, "Cover all", "Puts every tape on this page back", v -> { pop.dismiss(); engine.tapeAll(0); }));
        acts.addView(Ui.actionRow(this, R.drawable.ic_search, "Reveal all", "Peels back every tape on this page", v -> { pop.dismiss(); engine.tapeAll(1); }));
        acts.addView(Ui.actionRow(this, R.drawable.ic_trash, "Remove all tape", "Takes the tape off this page (undo brings it back)", v -> { pop.dismiss(); engine.tapeAll(2); }));
        p.add(acts);
        addMoveRow(p);
        p.show(anchor);
    }

    private static final float[] LASER_SIZES = {0.65f, 1f, 1.6f};

    private void pushLaser() {
        engine.setLaser(LASER_COLORS[laserCol], laserMode, laserMode == Engine.LASER_INK ? laserInkWait : laserTrail,
                LASER_SIZES[laserSizeIdx], laserHover);
        prefs.edit().putInt("laserCol", laserCol).putInt("laserMode", laserMode).putInt("laserSize", laserSizeIdx)
                .putInt("laserTrail", laserTrail).putInt("laserInkWait", laserInkWait).putBoolean("laserHover", laserHover).apply();
    }

    private void showLaserPanel(View anchor) {
        Ui.Popover p = newPop(360).title("Laser pointer");
        p.add(Ui.text(this, "Never saved: it's for showing, not writing.", 12.5f, Ui.color(this, R.color.on_surface_variant), null));
        p.section("Style");
        TextView[] lenLabel = new TextView[1];
        android.widget.SeekBar[] lenBar = new android.widget.SeekBar[1];
        Runnable lenText = () -> lenLabel[0].setText(laserMode == Engine.LASER_INK
                ? String.format(java.util.Locale.US, "Vanishes %.1f s after you stop", laserInkWait / 1000f)
                : laserMode == Engine.LASER_DOT ? "Dot only: no trail"
                : String.format(java.util.Locale.US, "Trail lasts %.1f s", laserTrail / 1000f));
        p.add(Ui.segmented(this, new String[]{"Trail", "Dot", "Vanishing ink"}, laserMode, i -> {
            laserMode = i;
            pushLaser();
            lenText.run();
            lenBar[0].setEnabled(i != Engine.LASER_DOT);
            lenBar[0].setAlpha(i == Engine.LASER_DOT ? 0.4f : 1f);
            lenBar[0].setProgress(i == Engine.LASER_INK ? (laserInkWait - 500) / 100 : (laserTrail - 200) / 50);
            applyTool();
        }));
        lenLabel[0] = Ui.text(this, "", 12.5f, Ui.color(this, R.color.on_surface_variant), Ui.medium());
        lenLabel[0].setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 4));
        p.add(lenLabel[0]);
        // trail: 0.2–2.2 s in 50 ms steps; vanishing ink: 0.5–4.5 s in 100 ms steps (both 40 steps)
        lenBar[0] = Ui.slider(this, 40, laserMode == Engine.LASER_INK ? (laserInkWait - 500) / 100 : (laserTrail - 200) / 50, v -> {
            if (laserMode == Engine.LASER_INK) laserInkWait = 500 + v * 100; else laserTrail = 200 + v * 50;
            pushLaser();
            lenText.run();
        });
        lenBar[0].setEnabled(laserMode != Engine.LASER_DOT);
        lenBar[0].setAlpha(laserMode == Engine.LASER_DOT ? 0.4f : 1f);
        p.add(lenBar[0]);
        lenText.run();
        p.section("Size");
        p.add(Ui.segmented(this, new String[]{"Small", "Medium", "Large"}, laserSizeIdx, i -> { laserSizeIdx = i; pushLaser(); }));
        p.section("Colour");
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Ui.Swatch[] dots = new Ui.Swatch[LASER_COLORS.length];
        for (int i = 0; i < LASER_COLORS.length; i++) {
            final int idx = i;
            dots[i] = new Ui.Swatch(this);
            dots[i].set(LASER_COLORS[i], i == laserCol);
            dots[i].setContentDescription(LASER_COLOR_NAMES[i]);
            dots[i].setOnClickListener(v -> {
                laserCol = idx;
                for (int k = 0; k < dots.length; k++) dots[k].set(LASER_COLORS[k], k == idx);
                pushLaser();
                applyTool();
            });
            row.addView(dots[i], new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        }
        p.add(row);
        LinearLayout.LayoutParams hl = fullW(-1);
        hl.topMargin = Ui.dp(this, 10);
        View hov = Ui.switchRow(this, "Point without touching", "The dot follows the pen while it hovers over the screen",
                laserHover, on -> { laserHover = on; pushLaser(); });
        hov.setLayoutParams(hl);
        p.add(hov);
        addMoveRow(p);
        p.show(anchor);
    }

    // ---- documents: tabs, and two panes for split view inside this window ------------------------

    /** One open notebook: its own engine (ink surface, undo, autosave) and selection overlay. */
    private final class Doc implements Engine.Listener {
        final File dir;
        Engine engine;      // created when the tab is first shown (hidden tabs cost only their title)
        InkView ink;
        SelectionView sel;
        Engine.State state;
        int scale;
        int page;           // page to open at (a search hit, 0-based); -1: where it was left
        String title;

        Doc(File dir, int page, boolean lazy) {
            this.dir = dir;
            this.page = page;
            title = Notebook.meta(dir).getProperty("title", dir.getName());
            if (!lazy) ensure();
        }

        void ensure() {
            if (engine != null) return;
            engine = new Engine(EditorActivity.this, dir, this);
            // the page fills the screen; the insets only let its ends scroll clear of the floating bars
            engine.setInsets(insetTop(), insetBottom());
            engine.setTheme(Ui.color(EditorActivity.this, R.color.desk), Ui.dark(EditorActivity.this) ? 0.45f : 0.11f);
            if (page >= 0) { engine.openAtPage(page); engine.setZoomLock(savedZoomLock(this)); } // a search hit: that page
            else restoreView(this);                                                              // else where it was left
            page = -1;
            ink = new InkView(EditorActivity.this, engine);
            ink.penListener = EditorActivity.this::onPen;
            ink.rateKeeper = rateKeeper;
            sel = new SelectionView(EditorActivity.this);
            scale = prefs.getInt("scale:" + dir.getName(), 0);
        }

        private boolean on() { return this == cur; }
        @Override public void onState(Engine.State s) {
            state = s;
            if (s.title != null) title = s.title;
            if (on()) EditorActivity.this.onState(s); else renderTabs();
        }
        @Override public void onStats(String s) { if (on()) EditorActivity.this.onStats(s); }
        @Override public void onSelection(RectF r) { sel.set(r); if (on()) EditorActivity.this.onSelection(r); }
        @Override public void onEditText(Engine.TextEdit e) { if (!on()) focusDoc(this); EditorActivity.this.onEditText(e); }
        @Override public void onDismissText() { if (on()) EditorActivity.this.onDismissText(); }
        @Override public void onToast(String s) { if (on()) toast(s); }
        @Override public void onTool(int t) { if (on()) EditorActivity.this.onTool(t); }
        @Override public void onRulerLength(float u, boolean done) { if (on()) EditorActivity.this.onRulerLength(u, done); }
        @Override public void onHandles(float[] hs, boolean line, int box) { sel.setHandles(hs, line, box); }
        @Override public void onHaptic() { root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); }
        @Override public void onZoom(int pct) { if (on()) showZoomChip(pct, state != null && state.zoomLocked); }
    }

    // ---- where each notebook was left: page, scroll, zoom and zoom lock (prefs "view:<folder>") -----------------------

    private void saveView(Doc d) {
        Engine.State s = d.state;
        if (d.engine == null || s == null || !s.viewKnown) return;
        prefs.edit().putString("view:" + d.dir.getName(), s.topPage + "," + s.topFrac + "," + s.zRel + "," + s.viewX + "," + (s.zoomLocked ? 1 : 0)).apply();
    }

    /** The zoom lock saved with d's view (kept whichever way the notebook is opened). */
    private boolean savedZoomLock(Doc d) {
        String[] f = prefs.getString("view:" + d.dir.getName(), "").split(",");
        return f.length > 4 && "1".equals(f[4]);
    }

    private void restoreView(Doc d) {
        String v = prefs.getString("view:" + d.dir.getName(), null);
        if (v == null) return;
        try {
            String[] f = v.split(",");
            d.engine.openAtView(Integer.parseInt(f[0]), Float.parseFloat(f[1]), Float.parseFloat(f[2]), Float.parseFloat(f[3]), "1".equals(f[4]));
        } catch (RuntimeException ignored) { }
    }

    // ---- zoom chip: shows the zoom while pinching (and a few seconds after) with a lock; stays while locked ----------

    private LinearLayout zoomChip;
    private TextView zoomPct;
    private ImageView zoomLock;
    private int maxZoomPct;
    private boolean chipLocked; // the chip shows a lock (and so has no hide pending)
    private final Runnable hideZoomChip = () -> {
        if (zoomChip == null || (state != null && state.zoomLocked)) return;
        zoomChip.animate().alpha(0f).setDuration(200).withEndAction(() -> zoomChip.setVisibility(View.GONE)).start();
    };

    private void buildZoomChip() {
        zoomChip = new LinearLayout(this);
        zoomChip.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pill(zoomChip, this);
        zoomChip.setElevation(Ui.dp(this, 6));
        zoomChip.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        zoomPct = Ui.text(this, "100%", 14, Ui.color(this, R.color.on_surface), Ui.medium());
        zoomPct.setGravity(Gravity.CENTER);
        zoomPct.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 6), 0);
        zoomPct.setContentDescription("Zoom · tap to fit the page width");
        zoomPct.setOnClickListener(v -> { if (state == null || !state.zoomLocked) engine.fitWidth(); });
        zoomChip.addView(zoomPct, new LinearLayout.LayoutParams(-2, Ui.dp(this, 40)));
        zoomLock = new ImageView(this);
        zoomLock.setScaleType(ImageView.ScaleType.CENTER);
        zoomLock.setBackground(Ui.ripple(this, null, Ui.dp(this, 18)));
        zoomLock.setOnClickListener(v -> {
            boolean on = !(state != null && state.zoomLocked);
            engine.setZoomLock(on);
            showZoomChip(state != null ? state.zoom : 100, on);
            toast(on ? "Zoom locked · pinching only scrolls" : "Zoom unlocked");
        });
        zoomChip.addView(zoomLock, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        zoomChip.setVisibility(View.GONE);
        root.addView(zoomChip, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        placeZoomChip();
    }

    /**
     * Under the top bars (or under the tool bar when that's docked at the top), centred over the notebook being
     * zoomed: in split view that's the pane in use, not the middle of the window.
     */
    private void placeZoomChip() {
        if (zoomChip == null) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) zoomChip.getLayoutParams();
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.topMargin = 0;
        zoomChip.setLayoutParams(lp);
        int below = Ui.dp(this, EDGE_DP + CAP_DP + 10 + (dock == DOCK_TOP ? TOOLCAP_DP + 12 + shelfDp() : 0));
        float left = 0, width = root.getWidth(), top = below;
        Pane pn = shown[1] != null ? panes[focusPane] : null;
        if (pn != null && pn.getWidth() > 0) {
            left = paneRow.getLeft() + pn.getLeft();
            width = pn.getWidth();
            float pt = paneRow.getTop() + pn.getTop();
            if (pt > 0) top = pt + Ui.dp(this, 12); // the lower of two stacked panes: just inside its top edge
        }
        zoomChip.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        zoomChip.setTranslationX(left + (width - zoomChip.getMeasuredWidth()) / 2f);
        zoomChip.setTranslationY(top);
    }

    private void setZoomChipLook(int pct, boolean locked) {
        chipLocked = locked;
        zoomPct.setText(pct + "%");
        zoomLock.setImageResource(locked ? R.drawable.ic_lock : R.drawable.ic_lock_open);
        zoomLock.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, locked ? R.color.accent : R.color.icon)));
        zoomLock.setContentDescription(locked ? "Unlock zoom" : "Lock zoom");
        zoomPct.setAlpha(locked ? 0.6f : 1f);
    }

    private void showZoomChip(int pct, boolean locked) {
        if (zoomChip == null) return;
        setZoomChipLook(pct, locked);
        placeZoomChip();
        if (zoomChip.getVisibility() != View.VISIBLE) {
            zoomChip.setAlpha(0f);
            zoomChip.setVisibility(View.VISIBLE);
        }
        zoomChip.animate().cancel();
        zoomChip.animate().alpha(1f).setDuration(120).start();
        ui.removeCallbacks(hideZoomChip);
        if (!locked) ui.postDelayed(hideZoomChip, 3000);
    }

    /** A side of the window. Touching it makes its notebook the one the tools act on. */
    private final class Pane extends FrameLayout {
        final int idx;
        final View bar = new View(EditorActivity.this);

        Pane(int idx) {
            super(EditorActivity.this);
            this.idx = idx;
            bar.setBackgroundColor(Ui.color(EditorActivity.this, R.color.accent));
            bar.setVisibility(GONE);
            addView(bar, new FrameLayout.LayoutParams(-1, Ui.dp(EditorActivity.this, 3), Gravity.TOP));
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN && focusPane != idx && shown[idx] != null) focusPaneIdx(idx);
            return false;
        }

        void show(Doc d) {
            for (int i = getChildCount() - 1; i >= 0; i--) if (getChildAt(i) != bar) removeViewAt(i);
            if (d == null) return;
            d.ensure();
            for (View v : new View[]{d.ink, d.sel}) if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
            addView(d.ink, 0, new FrameLayout.LayoutParams(-1, -1));
            addView(d.sel, 1, new FrameLayout.LayoutParams(-1, -1));
        }
    }

    private LinearLayout paneRow, tabRow;
    private android.widget.HorizontalScrollView tabScroll;
    private final Pane[] panes = new Pane[2];
    private View splitDivider;
    private final java.util.ArrayList<Doc> docs = new java.util.ArrayList<>();
    private final Doc[] shown = new Doc[2]; // what each pane shows; shown[1] == null: no split
    private Doc cur;                         // the notebook the tools act on
    private int focusPane;

    /** The same folder can be named two ways (/data/data/… and /data/user/0/…): compare where it really is. */
    private static String realPath(File f) {
        try { return f.getCanonicalPath(); } catch (java.io.IOException e) { return f.getAbsolutePath(); }
    }

    private Doc findDoc(File dir) {
        String want = realPath(dir);
        for (Doc d : docs) if (realPath(d.dir).equals(want)) return d;
        return null;
    }

    private Doc openDoc(File dir, int page) {
        Doc d = findDoc(dir);
        if (d != null) {
            if (page >= 0) { if (d.engine != null) d.engine.gotoPage(page); else d.page = page; }
            return d;
        }
        d = new Doc(dir, page, true);
        docs.add(d);
        return d;
    }

    /** Opens the tabs from last time plus the requested notebook (in the right pane when split was asked for). */
    private void restoreTabs(File want, int page, boolean split) {
        String prevCur = prefs.getString("tabCur", null), prevSplit = prefs.getString("tabSplit", null);
        try {
            org.json.JSONArray a = new org.json.JSONArray(prefs.getString("tabs", "[]"));
            for (int i = 0; i < a.length() && docs.size() < 12; i++) {
                File f = new File(a.getString(i));
                if (new File(f, "ink.bin").exists() && !realPath(f).equals(realPath(want)) && findDoc(f) == null) docs.add(new Doc(f, -1, true));
            }
        } catch (Exception ignored) { }
        Doc w = openDoc(want, page);
        Doc left = w;
        if (split) {
            Doc prev = prevCur != null ? findDoc(new File(prevCur)) : null;
            if (prev != null && prev != w) left = prev;
        }
        shown[0] = left;
        panes[0].show(left);
        cur = left;
        if (split && left != w) openSplit(w);
        else if (!split && prevSplit != null && prevCur != null && prevCur.equals(want.getPath())) {
            Doc r = findDoc(new File(prevSplit));
            if (r != null && r != w) { openSplit(r); focusPaneIdx(0); }
        }
    }

    private void saveTabs() {
        org.json.JSONArray a = new org.json.JSONArray();
        for (Doc d : docs) a.put(d.dir.getPath());
        prefs.edit().putString("tabs", a.toString()).putString("tabCur", cur != null ? cur.dir.getPath() : null)
                .putString("tabSplit", shown[1] != null ? shown[1].dir.getPath() : null).apply();
    }

    /** Points every control at d: tools, undo, pages, bookmarks, ruler. */
    private void activate(Doc d) {
        if (d == null) return;
        d.ensure();
        commitTextEdit();
        if (selView != null && cur != d) { selView.set(null); if (selBar != null) selBar.setVisibility(View.GONE); }
        cur = d;
        engine = d.engine;
        state = d.state;
        selView = d.sel;
        drawScale = d.scale;
        pushOptions();
        if (penB != null) applyTool();
        if (state != null && undoB != null) onState(state);
        if (ruler != null) { ruler.invalidate(); rulerChanged(); }
        updatePaneBars();
        renderTabs();
        saveTabs();
        placeZoomChip();
    }

    private void focusPaneIdx(int p) {
        if (shown[p] == null) return;
        if (p != focusPane) Diag.log("pane " + p + " in use: " + shown[p].dir.getName());
        focusPane = p;
        activate(shown[p]);
    }

    private void focusDoc(Doc d) {
        for (int p = 0; p < 2; p++) if (shown[p] == d) { focusPaneIdx(p); return; }
        showInPane(focusPane, d);
    }

    /** Shows d in pane p (swapping sides if it's already showing in the other pane) and makes it active. */
    private void showInPane(int p, Doc d) {
        int other = 1 - p;
        if (shown[other] == d) {
            shown[other] = shown[p];
            panes[other].show(shown[other]);
        }
        shown[p] = d;
        panes[p].show(d);
        focusPane = p;
        activate(d);
    }

    private void openSplit(Doc d) {
        if (shown[0] == d) { toast("That notebook is already on screen"); return; }
        // a phone in portrait has no room for two columns: stack them (the saved choice stays for wider screens)
        if (!splitVert && root.getWidth() < Ui.dp(this, 520)) { splitVert = true; layoutSplit(); }
        panes[1].setVisibility(View.VISIBLE);
        splitDivider.setVisibility(View.VISIBLE);
        showInPane(1, d);
        placeKnob();
    }

    // ---- the split separator: a thin rule with a small handle (orientation, swap, close; drag to resize) ----

    private boolean splitVert;   // true: notebooks stacked top and bottom; false: side by side
    private float splitRatio;    // share of the window the first pane gets
    private LinearLayout splitKnob;
    private ImageView knobGrip;
    private View splitGrab;      // invisible strip along the rule: drag it to resize
    private View splitGuide;     // accent line that follows the finger while the handle is dragged

    private void layoutSplit() {
        paneRow.setOrientation(splitVert ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        int t = Math.max(2, Ui.dp(this, 1));
        float r = Math.max(0.2f, Math.min(0.8f, splitRatio));
        panes[0].setLayoutParams(splitVert ? new LinearLayout.LayoutParams(-1, 0, r) : new LinearLayout.LayoutParams(0, -1, r));
        splitDivider.setLayoutParams(splitVert ? new LinearLayout.LayoutParams(-1, t) : new LinearLayout.LayoutParams(t, -1));
        panes[1].setLayoutParams(splitVert ? new LinearLayout.LayoutParams(-1, 0, 1f - r) : new LinearLayout.LayoutParams(0, -1, 1f - r));
    }

    private void buildSplitKnob() {
        splitGuide = new View(this);
        splitGuide.setBackgroundColor(Ui.color(this, R.color.accent));
        splitGuide.setVisibility(View.GONE);
        root.addView(splitGuide, new FrameLayout.LayoutParams(Ui.dp(this, 3), -1));
        splitKnob = new LinearLayout(this) {
            float downX, downY;
            boolean drag;
            @Override public boolean onInterceptTouchEvent(MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { downX = e.getRawX(); downY = e.getRawY(); drag = false; }
                else if (e.getActionMasked() == MotionEvent.ACTION_MOVE && Math.hypot(e.getRawX() - downX, e.getRawY() - downY) > Ui.dp(EditorActivity.this, 8)) drag = true;
                return drag;
            }
            @Override public boolean onTouchEvent(MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) { downX = e.getRawX(); downY = e.getRawY(); drag = false; return true; }
                if (a == MotionEvent.ACTION_MOVE && Math.hypot(e.getRawX() - downX, e.getRawY() - downY) > Ui.dp(EditorActivity.this, 6)) drag = true;
                if (a == MotionEvent.ACTION_UP && !drag) { showSplitMenu(this); return true; } // a tap: the split's options
                splitDrag(e, drag);
                return true;
            }
        };
        // the rule between the panes is a grab strip too (fingers only: the pen writes right up to it)
        splitGrab = new View(this) {
            boolean drag;
            float downX, downY;
            long lastTap;
            @Override public boolean onTouchEvent(MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    int tt = e.getToolType(0);
                    if (tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER) return false;
                    downX = e.getRawX(); downY = e.getRawY(); drag = false;
                    return true;
                }
                if (a == MotionEvent.ACTION_MOVE && Math.hypot(e.getRawX() - downX, e.getRawY() - downY) > Ui.dp(EditorActivity.this, 4)) drag = true;
                if (a == MotionEvent.ACTION_UP && !drag) { // double-tap: back to equal halves
                    if (e.getEventTime() - lastTap < 330) {
                        lastTap = 0;
                        setSplitRatio(0.5f);
                    } else lastTap = e.getEventTime();
                    return true;
                }
                splitDrag(e, drag);
                return true;
            }
        };
        splitGrab.setContentDescription("Drag to resize the panes, double-tap for equal halves");
        splitGrab.setVisibility(View.GONE);
        root.addView(splitGrab, new FrameLayout.LayoutParams(Ui.dp(this, 28), -1));
        // a slim handle sitting on the rule: drag it to resize, tap it for the split's options
        splitKnob.setGravity(Gravity.CENTER);
        Ui.capsule(splitKnob, this, 8);
        splitKnob.setContentDescription("Split view: drag to resize, tap for options");
        knobGrip = new ImageView(this);
        knobGrip.setImageResource(R.drawable.ic_grip);
        knobGrip.setScaleType(ImageView.ScaleType.FIT_CENTER);
        knobGrip.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, R.color.on_surface_variant)));
        splitKnob.addView(knobGrip, new LinearLayout.LayoutParams(Ui.dp(this, 14), Ui.dp(this, 20)));
        splitKnob.setVisibility(View.GONE);
        root.addView(splitKnob, new FrameLayout.LayoutParams(-2, -2));
        paneRow.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> placeKnob());
    }

    private void showSplitMenu(View anchor) {
        Ui.Popover p = newPop(280);
        p.add(Ui.actionRow(this, splitVert ? R.drawable.ic_split_cols : R.drawable.ic_split_rows, splitVert ? "Side by side" : "Stack top and bottom", null,
                v -> { pop.dismiss(); setSplitVert(!splitVert); }));
        p.add(Ui.actionRow(this, R.drawable.ic_swap, "Swap sides", null, v -> { pop.dismiss(); swapPanes(); }));
        p.add(Ui.actionRow(this, R.drawable.ic_grip, "Equal halves", null, v -> { pop.dismiss(); setSplitRatio(0.5f); }));
        p.add(Ui.actionRow(this, R.drawable.ic_close, "Close split view", "Keeps the notebook you're writing in", v -> { pop.dismiss(); closeSplit(focusPane); }));
        p.show(anchor);
    }

    /** Drag on the handle or the rule: a guide line follows, the panes take the new sizes on lift. */
    private void splitDrag(MotionEvent e, boolean drag) {
        int a = e.getActionMasked();
        float total = splitVert ? paneRow.getHeight() : paneRow.getWidth();
        float pos = splitVert ? e.getRawY() - rootTop() : e.getRawX();
        float r = Math.max(0.2f, Math.min(0.8f, pos / Math.max(1f, total)));
        if (a == MotionEvent.ACTION_MOVE && drag) showGuide(r * total);
        else if (a == MotionEvent.ACTION_UP && drag) setSplitRatio(Math.abs(r - 0.5f) < 0.03f ? 0.5f : r); // snaps to equal halves
        else if (a == MotionEvent.ACTION_CANCEL) splitGuide.setVisibility(View.GONE);
    }

    private void setSplitRatio(float r) {
        splitGuide.setVisibility(View.GONE);
        splitRatio = r;
        prefs.edit().putFloat("splitRatio", splitRatio).apply();
        layoutSplit();
        paneRow.post(paneRow::requestLayout);
        paneRow.post(this::placeKnob);
    }

    private float rootTop() {
        int[] loc = new int[2];
        root.getLocationOnScreen(loc);
        return loc[1];
    }

    private void showGuide(float at) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) splitGuide.getLayoutParams();
        int t = Ui.dp(this, 3);
        lp.width = splitVert ? -1 : t;
        lp.height = splitVert ? t : -1;
        splitGuide.setLayoutParams(lp);
        splitGuide.setTranslationX(splitVert ? 0 : at - t / 2f);
        splitGuide.setTranslationY(splitVert ? at - t / 2f : 0);
        splitGuide.setVisibility(View.VISIBLE);
    }

    private void setSplitVert(boolean v) {
        splitVert = v;
        prefs.edit().putBoolean("splitVert", v).apply();
        layoutSplit();
        placeKnob();
        toast(v ? "Stacked top and bottom" : "Side by side");
    }

    private void swapPanes() {
        Doc a = shown[0], b = shown[1];
        if (b == null) return;
        Doc act = cur;
        shown[0] = b;
        shown[1] = a;
        panes[0].show(b);
        panes[1].show(a);
        focusPane = act == b ? 0 : 1;
        activate(act);
    }

    /** Centres the handle on the rule between the panes; it lies along the rule. */
    private void placeKnob() {
        if (splitKnob == null) return;
        boolean on = shown[1] != null;
        splitKnob.setVisibility(on ? View.VISIBLE : View.GONE);
        if (splitGrab != null) splitGrab.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) return;
        knobGrip.setRotation(splitVert ? 90 : 0);
        int g = Ui.dp(this, 28);
        FrameLayout.LayoutParams gl = (FrameLayout.LayoutParams) splitGrab.getLayoutParams();
        gl.width = splitVert ? -1 : g;
        gl.height = splitVert ? g : -1;
        splitGrab.setLayoutParams(gl);
        if (splitVert) {
            splitGrab.setTranslationX(0);
            splitGrab.setTranslationY(splitDivider.getTop() + splitDivider.getHeight() / 2f - g / 2f);
        } else {
            splitGrab.setTranslationX(splitDivider.getLeft() + splitDivider.getWidth() / 2f - g / 2f);
            splitGrab.setTranslationY(0);
        }
        // lies along the rule: 16 × 48 dp across a vertical rule, 48 × 16 dp along a horizontal one
        int thin = Ui.dp(this, 16), lng = Ui.dp(this, 48);
        FrameLayout.LayoutParams kl = (FrameLayout.LayoutParams) splitKnob.getLayoutParams();
        kl.width = splitVert ? lng : thin;
        kl.height = splitVert ? thin : lng;
        splitKnob.setLayoutParams(kl);
        splitKnob.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int kw = kl.width, kh = kl.height;
        if (splitVert) {
            float y = splitDivider.getTop() + splitDivider.getHeight() / 2f;
            // off-centre to the right so it never sits on the tool capsule's column or the middle of the writing
            splitKnob.setTranslationX(root.getWidth() - kw - Ui.dp(this, EDGE_DP));
            splitKnob.setTranslationY(y - kh / 2f);
        } else {
            float x = splitDivider.getLeft() + splitDivider.getWidth() / 2f;
            splitKnob.setTranslationX(x - kw / 2f);
            splitKnob.setTranslationY((root.getHeight() - kh) / 2f);
        }
    }

    private float paneTop() { return panes[focusPane] == null ? 0 : panes[focusPane].getTop(); }

    /** Leaves split view, keeping pane `keep`'s notebook full width. */
    private void closeSplit(int keep) {
        if (shown[1] == null) return;
        Doc k = shown[keep];
        shown[1] = null;
        panes[1].show(null);
        panes[1].setVisibility(View.GONE);
        splitDivider.setVisibility(View.GONE);
        splitKnob.setVisibility(View.GONE);
        shown[0] = k;
        panes[0].show(k);
        focusPane = 0;
        activate(k);
    }

    private void closeDoc(Doc d) {
        commitTextEdit();
        if (d.engine != null) {
            saveView(d); // reopen where it was left, like leaving the app does
            d.engine.saveNow();
            Handwriting.indexLater(getApplicationContext(), d.dir);
        }
        int i = docs.indexOf(d);
        docs.remove(d);
        if (docs.isEmpty()) { if (d.engine != null) d.engine.release(); finish(); return; }
        if (shown[1] == d) closeSplit(0);
        else if (shown[0] == d) {
            if (shown[1] != null) closeSplit(1);
            else {
                Doc next = docs.get(Math.max(0, Math.min(i, docs.size() - 1)));
                shown[0] = next;
                panes[0].show(next);
                activate(next);
            }
        }
        if (d.engine != null) {
            for (View v : new View[]{d.ink, d.sel}) if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
            d.engine.release();
        }
        renderTabs();
        saveTabs();
    }

    private float paneLeft() { return panes[focusPane] == null ? 0 : panes[focusPane].getLeft(); }

    private void updatePaneBars() {
        boolean split = shown[1] != null;
        for (int p = 0; p < 2; p++) panes[p].bar.setVisibility(split && p == focusPane ? View.VISIBLE : View.GONE);
    }

    /** The tab strip: every open notebook; the active one shows its page and a close button. */
    private void renderTabs() {
        if (tabRow == null) return;
        tabRow.removeAllViews();
        for (Doc d : docs) {
            boolean act = d == cur, vis = d == shown[0] || d == shown[1];
            LinearLayout chip = new LinearLayout(this);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, act ? 2 : 10), 0);
            GradientDrawable bgD = Ui.round(act ? Ui.color(this, R.color.surface_variant) : 0, Ui.dp(this, 10));
            if (!act && vis) bgD.setStroke(Ui.hairline(this), Ui.color(this, R.color.outline));
            chip.setBackground(Ui.ripple(this, bgD, Ui.dp(this, 10)));
            LinearLayout txt = new LinearLayout(this);
            txt.setOrientation(LinearLayout.VERTICAL);
            String title = d.title != null ? d.title : "…";
            TextView t = Ui.text(this, title, 13.5f, Ui.color(this, act ? R.color.on_surface : R.color.on_surface_variant), act ? Ui.bold() : null);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setMaxWidth(Ui.dp(this, act ? 200 : 150));
            txt.addView(t);
            if (act && d.state != null) {
                Engine.State st = d.state;
                String layerName = null;
                if (st.layerIds.length > 1) for (int li = 0; li < st.layerIds.length; li++) if (st.layerIds[li] == st.activeLayer) layerName = st.layerNames[li];
                TextView sub = Ui.text(this, (st.canvas ? "Infinite canvas" : "Page " + (st.page + 1) + " of " + st.pages)
                        + (Math.abs(st.zoom - 100) > 3 ? "  ·  " + st.zoom + "%" : "") + (layerName != null ? "  ·  " + layerName : ""),
                        11f, Ui.color(this, R.color.on_surface_variant), null);
                sub.setPadding(0, Ui.dp(this, 1), 0, 0);
                txt.addView(sub);
            }
            chip.addView(txt);
            if (act) {
                Ui.IconButton x = new Ui.IconButton(this, R.drawable.ic_close, "Close tab");
                x.setScaleX(0.75f);
                x.setScaleY(0.75f);
                x.setOnClickListener(v -> closeDoc(d));
                chip.addView(x, new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32)));
            }
            chip.setOnClickListener(v -> {
                if (!act) { focusDoc(d); return; }
                if (state != null && (state.canvas || Math.abs(state.zoom - 100) > 3)) engine.fitWidth();
                else showPages();
            });
            chip.setOnLongClickListener(v -> { tabMenu(v, d); return true; });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, Ui.dp(this, 40));
            cl.rightMargin = Ui.dp(this, 2);
            tabRow.addView(chip, cl);
            if (act) ui.post(() -> { if (chip.getParent() == tabRow) tabScroll.smoothScrollTo(Math.max(0, chip.getLeft() - Ui.dp(this, 8)), 0); });
        }
        fitTabs();
    }

    private void tabMenu(View anchor, Doc d) {
        PopupMenu pm = new PopupMenu(this, anchor);
        if (d == cur) pm.getMenu().add(0, 1, 0, "Rename");
        if (shown[0] != d && shown[1] != d) pm.getMenu().add(0, 2, 1, "Open beside this (split view)");
        pm.getMenu().add(0, 3, 2, "Close tab");
        pm.setOnMenuItemClickListener(it -> {
            if (it.getItemId() == 1) renameNotebook();
            else if (it.getItemId() == 2) openSplit(d);
            else closeDoc(d);
            return true;
        });
        pm.show();
    }

    /** Keeps the tab strip clear of the right-hand capsule (it scrolls when there are many tabs). */
    private void fitTabs() {
        if (tabScroll == null || root.getWidth() == 0 || capRight == null) return;
        capRight.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(Ui.dp(this, CAP_DP), View.MeasureSpec.EXACTLY));
        tabRow.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(Ui.dp(this, 40), View.MeasureSpec.EXACTLY));
        int avail = root.getWidth() - capRight.getMeasuredWidth() - Ui.dp(this, 2 * EDGE_DP + 16 + 2 * 40 + 12);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) tabScroll.getLayoutParams();
        int want = tabRow.getMeasuredWidth() > avail ? Math.max(Ui.dp(this, 120), avail) : ViewGroup.LayoutParams.WRAP_CONTENT;
        if (lp.width != want) { lp.width = want; tabScroll.setLayoutParams(lp); }
    }

    // ---- fade the capsules while writing ---------------------------------------------------

    private final Runnable showChrome = () -> {
        for (View v : new View[]{capLeft, capRight, capTools, this.capAdv}) if (v != null) v.animate().alpha(1f).setDuration(110).start();
    };

    private void onPen(boolean down) {
        if (down && shelfOpen && !shelfPinned) ui.post(() -> setShelfOpen(false)); // writing on the page: the shelf steps aside
        if (!autoHide) return;
        ui.removeCallbacks(showChrome);
        boolean writing = tool == Engine.TOOL_PEN || tool == Engine.TOOL_HL || tool == Engine.TOOL_ERASER || tool == Engine.TOOL_LASER
                || tool == Engine.TOOL_TAPE || tool == Engine.TOOL_SHAPE;
        if (down && writing) {
            for (View v : new View[]{capLeft, capRight, capTools, capAdv}) if (v != null) v.animate().alpha(0f).setDuration(90).start();
        } else if (!down) {
            ui.postDelayed(showChrome, 200); // short enough to feel instant, long enough not to flash between words
        }
    }

    // ---- top capsules: back + title (left), history + pages + more (right) -------------------

    private void buildTopCapsules() {
        capLeft = new LinearLayout(this);
        capLeft.setGravity(Gravity.CENTER_VERTICAL);
        capLeft.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        Ui.capsule(capLeft, this, 14);
        Ui.IconButton back = icon(R.drawable.ic_back, "Back to notebooks");
        back.setOnClickListener(v -> finish());
        capLeft.addView(back);
        tabScroll = new android.widget.HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.setHorizontalFadingEdgeEnabled(true); // a tab half scrolled out fades instead of being cut off
        tabScroll.setFadingEdgeLength(Ui.dp(this, 28));
        tabRow = new LinearLayout(this);
        tabRow.setGravity(Gravity.CENTER_VERTICAL);
        tabScroll.addView(tabRow);
        capLeft.addView(tabScroll, new LinearLayout.LayoutParams(-2, -1));
        Ui.IconButton addTab = icon(R.drawable.ic_plus, "Open another notebook in a tab");
        addTab.setOnClickListener(v -> pickNotebook(v, "Open in a new tab", d -> showInPane(focusPane, d)));
        capLeft.addView(addTab);
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(-2, Ui.dp(this, CAP_DP), Gravity.TOP | Gravity.START);
        ll.leftMargin = ll.topMargin = Ui.dp(this, EDGE_DP);
        root.addView(capLeft, ll);

        capRight = new LinearLayout(this);
        capRight.setGravity(Gravity.CENTER_VERTICAL);
        capRight.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        Ui.capsule(capRight, this, 14);
        undoB = icon(R.drawable.ic_undo, "Undo · long-press for history");
        undoB.setOnClickListener(v -> engine.undo());
        undoB.setOnLongClickListener(v -> { showHistory(v); return true; });
        redoB = icon(R.drawable.ic_redo, "Redo");
        redoB.setOnClickListener(v -> engine.redo());
        redoB.setOnLongClickListener(v -> { showHistory(v); return true; });
        bookmarkB = icon(R.drawable.ic_bookmark, "Bookmark this page · long-press to name it");
        bookmarkB.setOnClickListener(v -> {
            if (state == null || state.marks == null) return;
            boolean on = state.marks[state.page] != null;
            engine.setBookmark(-2, on ? null : "");
            toast(on ? "Bookmark removed" : "Page " + (state.page + 1) + " bookmarked · long-press to name it");
        });
        bookmarkB.setOnLongClickListener(v -> { if (state != null) nameBookmark(state.page, null); return true; });
        addPageB = icon(R.drawable.ic_page_add, "Add page");
        addPageB.setOnClickListener(v -> showAddPage(Engine.POS_AFTER, null));
        pagesB = icon(R.drawable.ic_pages, "All pages");
        pagesB.setOnClickListener(v -> showPages());
        Ui.IconButton more = icon(R.drawable.ic_more, "More");
        more.setOnClickListener(this::showMore);
        capRight.addView(undoB);
        capRight.addView(redoB);
        capRight.addView(bookmarkB);
        capRight.addView(addPageB);
        capRight.addView(pagesB);
        capRight.addView(more);
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(-2, Ui.dp(this, CAP_DP), Gravity.TOP | Gravity.END);
        rl.rightMargin = rl.topMargin = Ui.dp(this, EDGE_DP);
        root.addView(capRight, rl);
    }

    private void showMore(View anchor) {
        Ui.Popover p = newPop(300);
        boolean canvas = state != null && state.canvas;
        if (!canvas) {
            String pagesSub = state != null ? state.pages + (state.pages == 1 ? " page" : " pages") + " · overview, reorder and bookmarks" : "Overview, reorder and bookmarks";
            p.add(Ui.actionRow(this, R.drawable.ic_pages, "Pages", pagesSub, v -> { pop.dismiss(); showPages(); }));
            p.add(Ui.actionRow(this, R.drawable.ic_page_add, "Add page", "Templates, another notebook, images or PDF", v -> { pop.dismiss(); showAddPage(Engine.POS_AFTER, null); }));
        }
        if (!canvas && state != null && state.marks != null) {
            boolean on = state.marks[state.page] != null;
            p.add(Ui.actionRow(this, on ? R.drawable.ic_bookmark_filled : R.drawable.ic_bookmark, on ? "Rename or remove bookmark" : "Bookmark this page",
                    "Bookmarks list under All pages → Bookmarks", v -> { pop.dismiss(); nameBookmark(state.page, null); }));
        }
        p.add(Ui.actionRow(this, R.drawable.ic_text, "Handwriting tools", "To text language · convert as I write · beautify",
                v -> { pop.dismiss(); showLassoPanel(anchor); }));
        p.add(Ui.actionRow(this, R.drawable.ic_layers, "Layers", layersSummary(), v -> { pop.dismiss(); showLayers(anchor); }));
        p.add(Ui.actionRow(this, R.drawable.ic_template, canvas ? "Background" : "Page style", "Pattern and paper colour", v -> { pop.dismiss(); showStyle(anchor); }));
        p.add(Ui.actionRow(this, R.drawable.ic_book, "Cover", canvas ? "Shown on the notes screen" : "Design and colour · shown before page 1", v -> {
            pop.dismiss();
            final Doc d = cur;
            Covers.showPicker(this, anchor, Notebook.meta(d.dir).getProperty("cover"), d.title, cv -> {
                try { Notebook.setCover(d.dir, cv); } catch (java.io.IOException ex) { toast("Couldn't change the cover"); return; }
                if (d.engine != null) d.engine.setCover(cv);
            });
        }));
        p.add(Ui.actionRow(this, R.drawable.ic_ruler, "Drawing scale", drawScale > 0 ? "1:" + drawScale + " · the ruler measures real lengths" : "None · the ruler measures the paper",
                v -> { pop.dismiss(); showScale(anchor); }));
        p.add(Ui.actionRow(this, R.drawable.ic_share, "Export & share", "PDF, image or .nib", v -> { pop.dismiss(); showShare(anchor); }));
        if (shown[1] != null) p.add(Ui.actionRow(this, R.drawable.ic_sidebar, "Close split view", "Keep only this side", v -> { pop.dismiss(); closeSplit(focusPane); }));
        else p.add(Ui.actionRow(this, R.drawable.ic_sidebar, "Split view",
                (splitVert || root.getWidth() < Ui.dp(this, 520) ? "Two notebooks, one above the other" : "Two notebooks side by side") + " · both editable",
                v -> { pop.dismiss(); pickNotebook(anchor, "Open beside this", this::openSplit); }));
        p.add(Ui.actionRow(this, R.drawable.ic_settings, "Settings", null, v -> { pop.dismiss(); showSettings(); }));
        p.show(anchor);
    }

    private void renameNotebook() {
        EditText et = new EditText(this);
        et.setText(state == null ? "" : state.title);
        et.setSelectAllOnFocus(true);
        et.setSingleLine(true);
        FrameLayout w = new FrameLayout(this);
        int p = Ui.dp(this, 22);
        w.setPadding(p, Ui.dp(this, 8), p, 0);
        w.addView(et);
        new AlertDialog.Builder(this).setTitle("Notebook name").setView(w)
                .setPositiveButton("Save", (d, x) -> engine.setTitle(et.getText().toString()))
                .setNegativeButton("Cancel", null).show();
        et.requestFocus();
    }

    // ---- bottom tool capsule: drawn pen tips, text/image, colours, size ----------------------

    private void buildToolCapsule() {
        capTools = new LinearLayout(this);
        capTools.setGravity(Gravity.CENTER_VERTICAL);
        capTools.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 10), 0);
        Ui.capsule(capTools, this, 20);
        capTools.setClipToOutline(false); // the selected disc's soft shadow may spill a little
        // basic: the tools written with all day
        penB = toolDisc(R.drawable.ic_pen, "Pen", Engine.TOOL_PEN, false);
        hlB = toolDisc(R.drawable.ic_highlighter, "Highlighter", Engine.TOOL_HL, false);
        eraserB = toolDisc(R.drawable.ic_eraser, "Eraser", Engine.TOOL_ERASER, false);
        lassoB = toolDisc(R.drawable.ic_lasso, "Lasso select", Engine.TOOL_LASSO, false);
        // advanced: the shelf that slides out above the bar (built here, placed by applyDock)
        advRow = new LinearLayout(this);
        advRow.setGravity(Gravity.CENTER_VERTICAL);
        textB = toShelf(toolDisc(R.drawable.ic_text, "Text", Engine.TOOL_TEXT, false));
        shapeB = toShelf(toolDisc(R.drawable.ic_shapes, "Shapes", Engine.TOOL_SHAPE, false));
        tapeB = toShelf(toolDisc(R.drawable.ic_tape, "Tape", Engine.TOOL_TAPE, false));
        laserB = toShelf(toolDisc(0, "Laser pointer", Engine.TOOL_LASER, true));
        Ui.IconButton img = icon(R.drawable.ic_image, "Image or sticker");
        img.setOnClickListener(v -> showInsert(shelfAnchor(v)));
        advRow.addView(img);
        imgB = img;
        rulerB = new Ui.ToolDisc(this, R.drawable.ic_ruler, "Ruler", false);
        rulerB.setOnClickListener(v -> { toggleRuler(); if (inShelf(v)) shelfUsed(); });
        rulerB.setTooltipText("Ruler");
        advRow.addView(rulerB);
        fingerB = new Ui.ToolDisc(this, R.drawable.ic_touch, "Write with finger", false);
        fingerB.setTooltipText("Write with finger");
        fingerB.setOnClickListener(v -> { toggleFinger(); if (inShelf(v)) shelfUsed(); });
        fingerB.set(fingerDraw, 0, 0);
        advRow.addView(fingerB);
        Ui.IconButton beaut = icon(R.drawable.ic_sparkle, "Beautify · the selection, or the whole page");
        beaut.setOnClickListener(v -> { beautify(state == null || !state.hasSelection); if (inShelf(v)) shelfUsed(); });
        advRow.addView(beaut);
        Ui.IconButton toText = icon(R.drawable.ic_to_text, "Handwriting to text");
        toText.setOnClickListener(this::toTextFromShelf);
        advRow.addView(toText);
        layersB = icon(R.drawable.ic_layers, "Layers");
        layersB.setOnClickListener(v -> showLayers(shelfAnchor(v)));
        advRow.addView(layersB);
        advDiv1 = Ui.divider(this);
        advDiv2 = Ui.divider(this);
        pinB = icon(R.drawable.ic_pin, "Keep these tools open");
        pinB.setOnClickListener(v -> {
            shelfPinned = !shelfPinned;
            prefs.edit().putBoolean("shelfPin", shelfPinned).apply();
            styleShelf();
            toast(shelfPinned ? "These tools stay open" : "These tools close after you pick one");
        });
        shelfOpen = prefs.getBoolean("shelfOpen", false);
        shelfPinned = prefs.getBoolean("shelfPin", false);
        // narrow screens (phones): favourites that don't fit fold into this disc (fitCapsule decides)
        moreToolsB = new Ui.ToolDisc(this, R.drawable.ic_more, "Favourite pens", false);
        moreToolsB.setTooltipText("Favourite pens");
        moreToolsB.setOnClickListener(this::showMoreTools);
        moreToolsB.setVisibility(View.GONE);
        capTools.addView(moreToolsB);
        favSep = Ui.divider(this);
        capTools.addView(favSep);
        favRow = new LinearLayout(this);
        favRow.setGravity(Gravity.CENTER_VERTICAL);
        capTools.addView(favRow);
        capTools.addView(Ui.divider(this));
        palette = new LinearLayout(this);
        palette.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            sw[i] = new Ui.Swatch(this);
            sw[i].setLayoutParams(new ViewGroup.MarginLayoutParams(Ui.dp(this, 34), Ui.dp(this, 44)));
            sw[i].setOnClickListener(v -> pickSlot(idx));
            sw[i].setOnLongClickListener(v -> { pickSlot(idx); showColors(v); return true; });
            palette.addView(sw[i]);
        }
        Ui.PaletteDot moreCol = new Ui.PaletteDot(this);
        moreCol.setOnClickListener(this::showColors);
        palette.addView(moreCol);
        sizeB = new Ui.SizeDot(this);
        sizeB.setLayoutParams(new ViewGroup.MarginLayoutParams(Ui.dp(this, 40), Ui.dp(this, 44)));
        sizeB.setOnClickListener(v -> openToolPanel(v)); // no longer in the bar: tapping the active tool again opens the same panel
        capTools.addView(palette);
        capTools.addView(Ui.divider(this));
        // opens / closes the shelf; while it's closed and one of its tools is in use, it shows that tool
        shelfB = new Ui.ToolDisc(this, R.drawable.ic_chevron, "More tools", false);
        shelfB.setOnClickListener(v -> setShelfOpen(!shelfOpen));
        shelfB.setOnLongClickListener(v -> { if (toolInShelf(tool)) openToolPanel(v); else setShelfOpen(!shelfOpen); return true; });
        capTools.addView(shelfB);
        grip = new Ui.IconButton(this, R.drawable.ic_grip, "Move tool bar: drag to any edge");
        grip.setTooltipText("Drag to move the tool bar");
        grip.setLayoutParams(new ViewGroup.MarginLayoutParams(Ui.dp(this, 22), Ui.dp(this, 44)));
        grip.setOnTouchListener(this::dragToolbar);
        capTools.addView(grip);
        root.addView(capTools, new FrameLayout.LayoutParams(-2, Ui.dp(this, TOOLCAP_DP), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        View[] tv = {penB, hlB, eraserB, lassoB, textB, shapeB, tapeB, laserB, imgB, rulerB, fingerB, beaut, toText, layersB};
        System.arraycopy(tv, 0, toolViews, 0, tv.length);
        for (View v : new View[]{imgB, rulerB, fingerB, beaut, toText, layersB}) v.setOnLongClickListener(this::showMoveMenu);
        applyToolLayout();
    }

    // ---- the advanced shelf: a second capsule beside the bar (above it at the bottom) ------------------

    private static final int SHELF_DP = 52;
    private LinearLayout advRow;
    private FrameLayout capAdv;          // HorizontalScrollView / ScrollView holding advRow, by dock
    private Ui.ToolDisc shapeB, shelfB;
    private Ui.IconButton pinB;
    private boolean shelfOpen, shelfPinned;
    private View pendingToText;          // "To text" asked with nothing selected: convert the next lasso

    private <T extends View> T toShelf(T v) {
        ((ViewGroup) v.getParent()).removeView(v);
        advRow.addView(v);
        return v;
    }

    private static int toolIcon(int t) {
        switch (t) {
            case Engine.TOOL_PEN: return R.drawable.ic_pen;
            case Engine.TOOL_HL: return R.drawable.ic_highlighter;
            case Engine.TOOL_ERASER: return R.drawable.ic_eraser;
            case Engine.TOOL_LASSO: return R.drawable.ic_lasso;
            case Engine.TOOL_TEXT: return R.drawable.ic_text;
            case Engine.TOOL_SHAPE: return R.drawable.ic_shapes;
            case Engine.TOOL_TAPE: return R.drawable.ic_tape;
            default: return R.drawable.ic_laser;
        }
    }

    /** Something on the shelf was used: unless it's pinned open, it gets out of the way. */
    private void shelfUsed() { if (!shelfPinned && shelfOpen) setShelfOpen(false); }

    /** Closes the shelf (unless pinned) and gives the view a popover should point at: v, or the bar's shelf disc. */
    private View shelfAnchor(View v) {
        if (!inShelf(v)) return v; // it lives in the bar
        shelfUsed();
        return shelfOpen ? v : shelfB;
    }

    private void setShelfOpen(boolean open) {
        shelfOpen = open;
        prefs.edit().putBoolean("shelfOpen", open).apply();
        placeShelf(true);
        styleShelf();
        for (Doc dd : docs) if (dd.engine != null) dd.engine.setInsets(insetTop(), insetBottom());
        placeZoomChip();
        placeChips();
    }

    private void styleShelf() {
        if (shelfB == null) return;
        boolean adv = toolInShelf(tool);
        int icon = !shelfOpen && adv ? toolIcon(tool) : R.drawable.ic_chevron;
        shelfB.setImageResource(icon);
        // the chevron points toward the shelf when closed, away from it when open
        float toward = dock == DOCK_BOTTOM ? -90 : dock == DOCK_TOP ? 90 : dock == DOCK_LEFT ? 0 : 180;
        shelfB.setRotation(icon == R.drawable.ic_chevron ? (shelfOpen ? toward + 180 : toward) : 0);
        int c = tool == Engine.TOOL_TAPE ? TAPE_COLORS[tapeCol] : tool == Engine.TOOL_LASER ? LASER_COLORS[laserCol]
                : tool == Engine.TOOL_SHAPE || tool == Engine.TOOL_PEN ? penColors[penSlot] : tool == Engine.TOOL_HL ? hlColors[hlSlot] : 0;
        shelfB.set(!shelfOpen && adv, c, 0);
        shelfB.setContentDescription(shelfOpen ? "Hide more tools" : "More tools");
        if (pinB != null) {
            pinB.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, shelfPinned ? R.color.accent : R.color.icon)));
            pinB.setBackground(shelfPinned ? Ui.round(Ui.color(this, R.color.accent_container), Ui.dp(this, 20)) : null);
        }
    }

    /** Puts the shelf beside the bar for the current dock (a row along the top / bottom, a column at the sides). */
    private void placeShelf(boolean animate) {
        if (advRow == null) return;
        boolean vert = dockVertical();
        boolean rebuild = capAdv == null || (vert != (capAdv instanceof ScrollView));
        if (rebuild) {
            if (capAdv != null) { capAdv.removeAllViews(); root.removeView(capAdv); }
            if (advRow.getParent() != null) ((ViewGroup) advRow.getParent()).removeView(advRow);
            if (vert) { ScrollView v = new ScrollView(this); v.setVerticalScrollBarEnabled(false); capAdv = v; }
            else { android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false); capAdv = hs; }
            Ui.capsule(capAdv, this, 20);
            capAdv.addView(advRow);
            root.addView(capAdv, root.indexOfChild(capTools));
        }
        advRow.setOrientation(vert ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        advRow.setGravity(Gravity.CENTER);
        int pad = Ui.dp(this, 6);
        if (vert) advRow.setPadding(0, pad, 0, pad); else advRow.setPadding(pad, 0, pad, 0);
        for (int i = 0; i < advRow.getChildCount(); i++) orient(advRow.getChildAt(i), vert);
        int sh = Ui.dp(this, SHELF_DP), edge = Ui.dp(this, EDGE_DP), gap = Ui.dp(this, 10), tc = Ui.dp(this, TOOLCAP_DP);
        FrameLayout.LayoutParams lp;
        switch (dock) {
            case DOCK_TOP:
                lp = new FrameLayout.LayoutParams(-2, sh, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                lp.topMargin = Ui.dp(this, EDGE_DP + CAP_DP + 12) + tc + gap;
                break;
            case DOCK_LEFT:
                lp = new FrameLayout.LayoutParams(sh, -2, Gravity.START | Gravity.CENTER_VERTICAL);
                lp.leftMargin = edge + tc + gap;
                break;
            case DOCK_RIGHT:
                lp = new FrameLayout.LayoutParams(sh, -2, Gravity.END | Gravity.CENTER_VERTICAL);
                lp.rightMargin = edge + tc + gap;
                break;
            default:
                lp = new FrameLayout.LayoutParams(-2, sh, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                lp.bottomMargin = Ui.dp(this, EDGE_DP + 4) + tc + gap;
        }
        // never wider (taller) than the window: it scrolls instead
        if (vert) lp.topMargin = lp.bottomMargin = Ui.dp(this, EDGE_DP + CAP_DP + 12);
        else lp.leftMargin = lp.rightMargin = edge;
        capAdv.setLayoutParams(lp);
        float off = Ui.dp(this, 12) * (dock == DOCK_BOTTOM ? 1 : dock == DOCK_TOP ? -1 : 0);
        float offX = Ui.dp(this, 12) * (dock == DOCK_LEFT ? -1 : dock == DOCK_RIGHT ? 1 : 0);
        capAdv.animate().cancel();
        if (shelfOpen) {
            if (capAdv.getVisibility() != View.VISIBLE) {
                capAdv.setVisibility(View.VISIBLE);
                if (animate) { capAdv.setAlpha(0f); capAdv.setTranslationY(off); capAdv.setTranslationX(offX); }
            }
            if (animate) capAdv.animate().alpha(1f).translationY(0).translationX(0).setDuration(150).start();
            else { capAdv.setAlpha(1f); capAdv.setTranslationY(0); capAdv.setTranslationX(0); }
        } else if (animate && capAdv.getVisibility() == View.VISIBLE) {
            capAdv.animate().alpha(0f).translationY(off).translationX(offX).setDuration(120)
                    .withEndAction(() -> { if (!shelfOpen) capAdv.setVisibility(View.GONE); }).start();
        } else capAdv.setVisibility(View.GONE);
    }

    /** Shelf space taken next to the bar, when it's open (dp). */
    private int shelfDp() { return shelfOpen ? SHELF_DP + 10 : 0; }

    // ---- which tools sit in the bar and which on the shelf: the owner's choice (long-press a tool → Move …) -------

    /** Every movable button in one fixed order; each side shows its own in this order. */
    private static final String[] TOOL_IDS = {"pen", "hl", "eraser", "lasso", "text", "shape", "tape", "laser", "image", "ruler", "finger", "beautify", "totext", "layers"};
    private static final String[] TOOL_NAMES = {"Pen", "Highlighter", "Eraser", "Lasso", "Text", "Shapes", "Tape", "Laser pointer", "Image or sticker",
            "Ruler", "Write with finger", "Beautify", "Handwriting to text", "Layers"};
    private static final String BASIC_DEFAULT = "pen,hl,eraser,lasso";
    private final View[] toolViews = new View[TOOL_IDS.length];
    private final java.util.HashSet<String> basicIds = new java.util.HashSet<>();
    private View advDiv1, advDiv2;
    private View moveRowFor; // the panel being opened is that button's: it gets a Move row

    private View toolView(int t) {
        switch (t) {
            case Engine.TOOL_PEN: return penB;
            case Engine.TOOL_HL: return hlB;
            case Engine.TOOL_ERASER: return eraserB;
            case Engine.TOOL_LASSO: return lassoB;
            case Engine.TOOL_TEXT: return textB;
            case Engine.TOOL_SHAPE: return shapeB;
            case Engine.TOOL_TAPE: return tapeB;
            case Engine.TOOL_LASER: return laserB;
        }
        return null;
    }

    private int toolIndex(View v) {
        for (int i = 0; i < toolViews.length; i++) if (toolViews[i] == v) return i;
        return -1;
    }

    private boolean inShelf(View v) { return v != null && v.getParent() == advRow; }

    private boolean toolInShelf(int t) { return inShelf(toolView(t)); }

    /** Puts every movable button on its side (bar or shelf), in the fixed order, and saves nothing. */
    private void applyToolLayout() {
        basicIds.clear();
        for (String id : prefs.getString("toolBasic", BASIC_DEFAULT).split(",")) if (!id.isEmpty()) basicIds.add(id);
        for (View v : toolViews) if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
        for (View v : new View[]{advDiv1, advDiv2, pinB}) if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
        int at = 0;
        boolean anyTool = false, anyAction = false;
        for (int i = 0; i < TOOL_IDS.length; i++) {
            View v = toolViews[i];
            if (basicIds.contains(TOOL_IDS[i])) { capTools.addView(v, at++); continue; }
            boolean action = TOOL_IDS[i].equals("beautify") || TOOL_IDS[i].equals("totext") || TOOL_IDS[i].equals("layers");
            if (action && anyTool && !anyAction) advRow.addView(advDiv1); // tools, then actions on the selection / page
            advRow.addView(v);
            if (action) anyAction = true; else anyTool = true;
        }
        advRow.addView(advDiv2);
        advRow.addView(pinB);
        boolean vert = dockVertical();
        for (View v : toolViews) orient(v, vert);
        for (View v : new View[]{advDiv1, advDiv2, pinB}) orient(v, vert);
        // nothing left on the shelf: no button to open it
        boolean shelfEmpty = basicIds.size() >= TOOL_IDS.length;
        shelfB.setVisibility(shelfEmpty ? View.GONE : View.VISIBLE);
        if (shelfEmpty && shelfOpen) setShelfOpen(false);
    }

    /** Moves a button to the other side and remembers it. */
    private void moveTool(View v) {
        int i = toolIndex(v);
        if (i < 0) return;
        boolean toBar = inShelf(v);
        if (toBar) basicIds.add(TOOL_IDS[i]); else basicIds.remove(TOOL_IDS[i]);
        StringBuilder b = new StringBuilder();
        for (String id : TOOL_IDS) if (basicIds.contains(id)) b.append(b.length() > 0 ? "," : "").append(id);
        prefs.edit().putString("toolBasic", b.toString()).apply();
        applyToolLayout();
        styleShelf();
        ui.post(this::fitCapsule);
        placeShelf(false);
        toast(TOOL_NAMES[i] + (toBar ? " is in the tool bar now" : " moved to more tools · tap the arrow to reach it"));
    }

    /** The "Move to …" row at the end of a tool's panel (when the panel was opened from that tool). */
    private void addMoveRow(Ui.Popover p) {
        View v = moveRowFor;
        moveRowFor = null;
        if (v == null || toolIndex(v) < 0 || v.getParent() == null) return;
        boolean shelf = inShelf(v);
        View row = Ui.actionRow(this, R.drawable.ic_swap, shelf ? "Move to the tool bar" : "Move to more tools",
                shelf ? "Always on screen" : "One tap away, behind the arrow", x -> { pop.dismiss(); moveTool(v); });
        LinearLayout.LayoutParams lp = fullW(-1);
        lp.topMargin = Ui.dp(this, 8);
        row.setLayoutParams(lp);
        p.add(row);
    }

    /** Long-press on a button without options (image, ruler, finger writing, beautify, to text): where it lives. */
    private boolean showMoveMenu(View v) {
        int i = toolIndex(v);
        if (i < 0) return false;
        Ui.Popover p = newPop(300).title(TOOL_NAMES[i]);
        moveRowFor = v;
        addMoveRow(p);
        p.show(v);
        return true;
    }

    // ---- layers: one list for the whole notebook; new writing goes on the active one -----------------------------

    private Ui.IconButton layersB;
    private View toLayerB;
    private LinearLayout layersBox; // the open Layers panel's list (redrawn when the engine reports a change)

    private String layersSummary() {
        if (state == null || state.layerIds.length < 2) return "Keep writing apart: e.g. lecture, notes, answers";
        String act = "";
        for (int i = 0; i < state.layerIds.length; i++) if (state.layerIds[i] == state.activeLayer) act = state.layerNames[i];
        return state.layerIds.length + " layers · writing on " + act;
    }

    private void showLayers(View anchor) {
        Ui.Popover p = newPop(340).title("Layers");
        TextView hint = Ui.text(this, "New writing goes on the highlighted layer, and only that layer can be erased or selected. "
                + "Hidden layers aren't shown or exported.", 12.5f, Ui.color(this, R.color.on_surface_variant), null);
        hint.setPadding(0, 0, 0, Ui.dp(this, 8));
        p.add(hint);
        layersBox = new LinearLayout(this);
        layersBox.setOrientation(LinearLayout.VERTICAL);
        p.add(layersBox);
        renderLayers(layersBox);
        p.add(Ui.actionRow(this, R.drawable.ic_plus, "New layer", "Added above the highlighted one", v -> {
            int n = state == null ? 1 : state.layerIds.length;
            engine.addLayer("Layer " + (n + 1));
        }));
        p.show(anchor);
    }

    /** One row per layer, top layer first (the order they're drawn in, read from the top). */
    private void renderLayers(LinearLayout box) {
        box.removeAllViews();
        if (state == null) return;
        int n = state.layerIds.length;
        int acc = Ui.color(this, R.color.accent);
        for (int k = n - 1; k >= 0; k--) {
            final int id = state.layerIds[k], rank = k;
            final String name = state.layerNames[k];
            final boolean vis = state.layerVisible[k], act = id == state.activeLayer;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0);
            row.setBackground(act ? Ui.round(Ui.color(this, R.color.accent_container), Ui.dp(this, 12)) : Ui.ripple(this, null, Ui.dp(this, 12)));
            Ui.IconButton eye = icon(vis ? R.drawable.ic_eye : R.drawable.ic_eye_off, vis ? "Hide " + name : "Show " + name);
            eye.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, vis ? R.color.icon : R.color.on_surface_variant)));
            eye.setOnClickListener(v -> engine.setLayerVisible(id, !vis));
            row.addView(eye);
            TextView t = Ui.text(this, name, 15, Ui.color(this, act ? R.color.on_surface : vis ? R.color.on_surface : R.color.on_surface_variant),
                    act ? Ui.bold() : null);
            t.setPadding(Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12));
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            if (act) {
                TextView tag = Ui.text(this, "Writing", 11.5f, acc, Ui.medium());
                tag.setPadding(0, 0, Ui.dp(this, 4), 0);
                row.addView(tag);
            }
            Ui.IconButton more = icon(R.drawable.ic_more, "Options for " + name);
            more.setOnClickListener(v -> layerMenu(v, id, name, rank, n));
            row.addView(more);
            row.setOnClickListener(v -> engine.setActiveLayer(id));
            row.setOnLongClickListener(v -> { renameLayer(id, name); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = Ui.dp(this, 2);
            box.addView(row, lp);
        }
    }

    private void layerMenu(View anchor, int id, String name, int rank, int n) {
        Ui.Popover m = new Ui.Popover(this, 260); // over the Layers panel, which stays open
        m.title(name);
        java.util.List<Ui.Popover> self = new java.util.ArrayList<>();
        self.add(m);
        m.add(Ui.actionRow(this, R.drawable.ic_text, "Rename", null, v -> { m.dismiss(); renameLayer(id, name); }));
        if (rank < n - 1) m.add(Ui.actionRow(this, R.drawable.ic_chevron, "Move up", "Drawn over the layer above", v -> { m.dismiss(); engine.moveLayer(id, +1); }));
        if (rank > 0) {
            m.add(Ui.actionRow(this, R.drawable.ic_chevron, "Move down", null, v -> { m.dismiss(); engine.moveLayer(id, -1); }));
            m.add(Ui.actionRow(this, R.drawable.ic_layers, "Merge down", "Puts everything on it onto the layer below", v -> { m.dismiss(); engine.mergeLayerDown(id); }));
        }
        if (n > 1) m.add(Ui.actionRow(this, R.drawable.ic_trash, "Delete layer", "Removes it and its writing from every page", v -> {
            m.dismiss();
            new AlertDialog.Builder(this).setTitle("Delete \"" + name + "\"?")
                    .setMessage("Everything on this layer, on every page, goes with it. Undo brings it back.")
                    .setPositiveButton("Delete", (d, x) -> engine.deleteLayer(id))
                    .setNegativeButton("Cancel", null).show();
        }));
        m.show(anchor);
    }

    private void renameLayer(int id, String name) {
        EditText et = new EditText(this);
        et.setText(name);
        et.setSelectAllOnFocus(true);
        et.setSingleLine(true);
        FrameLayout w = new FrameLayout(this);
        int pd = Ui.dp(this, 22);
        w.setPadding(pd, Ui.dp(this, 8), pd, 0);
        w.addView(et);
        new AlertDialog.Builder(this).setTitle("Layer name").setView(w)
                .setPositiveButton("Save", (d, x) -> engine.renameLayer(id, et.getText().toString()))
                .setNegativeButton("Cancel", null).show();
        et.requestFocus();
    }

    /** Selection bar → Layer: move what's selected onto another layer. */
    private void pickLayerForSelection(View anchor) {
        if (state == null || state.layerIds.length < 2) return;
        Ui.Popover p = newPop(280).title("Move to layer");
        for (int k = state.layerIds.length - 1; k >= 0; k--) {
            final int id = state.layerIds[k];
            if (id == state.activeLayer) continue;
            p.add(Ui.actionRow(this, state.layerVisible[k] ? R.drawable.ic_layers : R.drawable.ic_eye_off, state.layerNames[k],
                    state.layerVisible[k] ? null : "Hidden", v -> { pop.dismiss(); engine.selToLayer(id); }));
        }
        p.show(anchor);
    }

    /** "To text" on the shelf: converts the selection, or with nothing selected, the next thing lassoed. */
    private void toTextFromShelf(View btn) {
        View v = shelfAnchor(btn);
        if (state != null && state.hasSelection) { engine.selInk(ink -> showConvert(v, ink)); return; }
        pendingToText = v;
        commitTextEdit();
        tool = Engine.TOOL_LASSO;
        applyTool();
        toast("Circle the writing to turn into text");
    }

    // ---- the tool bar can sit on any edge: drag its grip there, or pick in Settings ---------------

    static final int DOCK_BOTTOM = 0, DOCK_TOP = 1, DOCK_LEFT = 2, DOCK_RIGHT = 3;
    private int dock;
    private Ui.IconButton grip;
    private float dragX, dragY;

    private boolean dockVertical() { return dock == DOCK_LEFT || dock == DOCK_RIGHT; }

    /** How far the page may scroll past the floating bars at its top and bottom. */
    private int insetTop() { return Ui.dp(this, EDGE_DP + CAP_DP + 8 + (dock == DOCK_TOP ? TOOLCAP_DP + 12 + shelfDp() : 0)); }

    private int insetBottom() { return Ui.dp(this, EDGE_DP + 12 + (dock == DOCK_BOTTOM ? TOOLCAP_DP + shelfDp() : 0)); }

    private boolean dragToolbar(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragX = e.getRawX();
                dragY = e.getRawY();
                capTools.animate().cancel();
                capTools.animate().scaleX(1.04f).scaleY(1.04f).alpha(0.9f).setDuration(100).start();
                return true;
            case MotionEvent.ACTION_MOVE:
                trackFinger(e);
                capTools.setTranslationX(e.getRawX() - dragX);
                capTools.setTranslationY(e.getRawY() - dragY);
                if (Math.hypot(e.getRawX() - dragX, e.getRawY() - dragY) > Ui.dp(this, 24)) showDockHint(dropTarget());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                trackFinger(e);
                boolean moved = Math.hypot(e.getRawX() - dragX, e.getRawY() - dragY) > Ui.dp(this, 24);
                int to = moved && e.getActionMasked() == MotionEvent.ACTION_UP ? dropTarget() : dock;
                if (!moved) toast("Drag the grip to the top, bottom, left or right edge");
                if (dockHint != null) dockHint.setVisibility(View.GONE);
                capTools.setTranslationX(0);
                capTools.setTranslationY(0);
                capTools.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start();
                if (to != dock) setDock(to);
                return true;
            }
        }
        return false;
    }

    private View dockHint;

    private float fingerX, fingerY; // where the finger holding the grip is (root px)

    /** Where the bar would land: finger in the top quarter → top, bottom quarter → bottom, else the side it's on. */
    private int dropTarget() {
        float w = root.getWidth(), h = root.getHeight();
        if (fingerY < h * 0.25f) return DOCK_TOP;
        if (fingerY > h * 0.75f) return DOCK_BOTTOM;
        return fingerX < w / 2 ? DOCK_LEFT : DOCK_RIGHT;
    }

    private void trackFinger(MotionEvent e) {
        int[] o = new int[2];
        root.getLocationOnScreen(o);
        fingerX = e.getRawX() - o[0];
        fingerY = e.getRawY() - o[1];
    }

    /** A faint outline where the bar will land when it's let go. */
    private void showDockHint(int d) {
        if (dockHint == null) {
            dockHint = new View(this);
            int acc = Ui.color(this, R.color.accent);
            GradientDrawable g = Ui.round((acc & 0x00ffffff) | 0x1f000000, Ui.dp(this, 20));
            g.setStroke(Ui.dp(this, 2), (acc & 0x00ffffff) | 0x99000000, Ui.dp(this, 8), Ui.dp(this, 6));
            dockHint.setBackground(g);
            root.addView(dockHint, root.indexOfChild(capTools)); // under the bar being dragged
        }
        int tc = Ui.dp(this, TOOLCAP_DP), edge = Ui.dp(this, EDGE_DP);
        int len = Math.min(Math.max(capTools.getWidth(), capTools.getHeight()),
                (d == DOCK_LEFT || d == DOCK_RIGHT) ? root.getHeight() - Ui.dp(this, 2 * (EDGE_DP + CAP_DP + 12)) : root.getWidth() - 2 * edge);
        FrameLayout.LayoutParams lp;
        switch (d) {
            case DOCK_TOP: lp = new FrameLayout.LayoutParams(len, tc, Gravity.TOP | Gravity.CENTER_HORIZONTAL); lp.topMargin = Ui.dp(this, EDGE_DP + CAP_DP + 12); break;
            case DOCK_LEFT: lp = new FrameLayout.LayoutParams(tc, len, Gravity.START | Gravity.CENTER_VERTICAL); lp.leftMargin = edge; break;
            case DOCK_RIGHT: lp = new FrameLayout.LayoutParams(tc, len, Gravity.END | Gravity.CENTER_VERTICAL); lp.rightMargin = edge; break;
            default: lp = new FrameLayout.LayoutParams(len, tc, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); lp.bottomMargin = Ui.dp(this, EDGE_DP + 4);
        }
        dockHint.setLayoutParams(lp);
        dockHint.setVisibility(View.VISIBLE);
    }

    private void setDock(int d) {
        dock = d;
        prefs.edit().putInt("toolDock", d).apply();
        applyDock();
    }

    /** Lays the tool bar along its edge: a row at the top / bottom, a column on the left / right. */
    private void applyDock() {
        boolean vert = dockVertical();
        capTools.setOrientation(vert ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        capTools.setGravity(Gravity.CENTER);
        if (vert) capTools.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 6));
        else capTools.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 6), 0);
        for (LinearLayout g : new LinearLayout[]{palette, favRow}) {
            g.setOrientation(vert ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            g.setGravity(Gravity.CENTER);
        }
        for (LinearLayout g : new LinearLayout[]{capTools, palette, favRow})
            for (int i = 0; i < g.getChildCount(); i++) if (!(g.getChildAt(i) == palette || g.getChildAt(i) == favRow)) orient(g.getChildAt(i), vert);
        orient(grip, vert);
        FrameLayout.LayoutParams lp;
        int tc = Ui.dp(this, TOOLCAP_DP), edge = Ui.dp(this, EDGE_DP);
        switch (dock) {
            case DOCK_TOP:
                lp = new FrameLayout.LayoutParams(-2, tc, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                lp.topMargin = Ui.dp(this, EDGE_DP + CAP_DP + 12); // under the tabs / undo bar
                break;
            case DOCK_LEFT:
                lp = new FrameLayout.LayoutParams(tc, -2, Gravity.START | Gravity.CENTER_VERTICAL);
                lp.leftMargin = edge;
                break;
            case DOCK_RIGHT:
                lp = new FrameLayout.LayoutParams(tc, -2, Gravity.END | Gravity.CENTER_VERTICAL);
                lp.rightMargin = edge;
                break;
            default:
                lp = new FrameLayout.LayoutParams(-2, tc, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                lp.bottomMargin = Ui.dp(this, EDGE_DP + 4);
        }
        capTools.setLayoutParams(lp);
        placeShelf(false);
        styleShelf();
        placeZoomChip();
        placeChips();
        for (Doc dd : docs) if (dd.engine != null) dd.engine.setInsets(insetTop(), insetBottom());
        ui.post(this::fitCapsule);
    }

    /** Toast and paste chip sit above the bar (and an open shelf) when it's at the bottom, else near the bottom edge. */
    private void placeChips() {
        int above = Ui.dp(this, dock == DOCK_BOTTOM ? EDGE_DP + TOOLCAP_DP + 16 + shelfDp() : EDGE_DP + 8);
        if (pasteChip != null) { FrameLayout.LayoutParams c = (FrameLayout.LayoutParams) pasteChip.getLayoutParams(); c.bottomMargin = above; pasteChip.setLayoutParams(c); }
        if (toastView != null) { FrameLayout.LayoutParams c = (FrameLayout.LayoutParams) toastView.getLayoutParams(); c.bottomMargin = above + Ui.dp(this, 2); toastView.setLayoutParams(c); }
    }

    /** Lays a bar item out for a row (as built) or a column (width / height and margins swapped). */
    private static void orient(View v, boolean vert) {
        ViewGroup.LayoutParams cur = v.getLayoutParams();
        int[] row = (int[]) v.getTag(R.id.nib_row_lp);
        if (row == null) { // first time: remember the row layout it was built with
            ViewGroup.MarginLayoutParams m = cur instanceof ViewGroup.MarginLayoutParams ? (ViewGroup.MarginLayoutParams) cur : null;
            row = new int[]{cur == null ? -2 : cur.width, cur == null ? -2 : cur.height,
                    m == null ? 0 : m.leftMargin, m == null ? 0 : m.topMargin, m == null ? 0 : m.rightMargin, m == null ? 0 : m.bottomMargin};
            v.setTag(R.id.nib_row_lp, row);
        }
        LinearLayout.LayoutParams lp = vert ? new LinearLayout.LayoutParams(row[1], row[0]) : new LinearLayout.LayoutParams(row[0], row[1]);
        if (vert) lp.setMargins(row[3], row[2], row[5], row[4]); else lp.setMargins(row[2], row[3], row[4], row[5]);
        v.setLayoutParams(lp);
    }

    private Ui.ToolDisc toolDisc(int icon, String desc, int t, boolean laser) {
        Ui.ToolDisc b = new Ui.ToolDisc(this, icon, desc + " · tap again for options", laser);
        b.setOnClickListener(v -> {
            if (tool == t) { openToolPanel(v); return; }
            commitTextEdit();
            tool = t;
            applyTool();
            if (inShelf(b)) shelfUsed();
        });
        b.setOnLongClickListener(v -> {
            if (tool != t) { tool = t; applyTool(); }
            openToolPanel(v);
            return true;
        });
        b.setTooltipText(desc);
        capTools.addView(b);
        return b;
    }

    private Ui.IconButton icon(int res, String desc) {
        Ui.IconButton b = new Ui.IconButton(this, res, desc);
        b.setLayoutParams(new ViewGroup.MarginLayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        b.setTooltipText(desc);
        return b;
    }

    private void openToolPanel(View anchor) {
        moveRowFor = toolView(tool);
        switch (tool) {
            case Engine.TOOL_PEN: case Engine.TOOL_SHAPE: showPenPanel(anchor, false); break;
            case Engine.TOOL_HL: showPenPanel(anchor, true); break;
            case Engine.TOOL_ERASER: showEraserPanel(anchor); break;
            case Engine.TOOL_TEXT: showTextPanel(anchor); break;
            case Engine.TOOL_LASSO: showLassoPanel(anchor); break;
            case Engine.TOOL_LASER: showLaserPanel(anchor); break;
            case Engine.TOOL_TAPE: showTapePanel(anchor); break;
        }
    }

    private int[] slotColors() { return tool == Engine.TOOL_HL ? hlColors : penColors; }

    private int curColor() { return tool == Engine.TOOL_HL ? hlColors[hlSlot] : penColors[penSlot]; }

    private void pickSlot(int i) {
        if (tool == Engine.TOOL_HL) hlSlot = i;
        else penSlot = i;
        if (tool == Engine.TOOL_ERASER || tool == Engine.TOOL_LASER || tool == Engine.TOOL_TAPE) tool = Engine.TOOL_PEN;
        if (state != null && state.hasSelection) engine.selRecolor(slotColors()[i]);
        applyTool();
    }

    /** Puts a colour into the current slot. */
    private void setColor(int c) {
        if (tool == Engine.TOOL_HL) hlColors[hlSlot] = c;
        else penColors[penSlot] = c;
        if (state != null && state.hasSelection) engine.selRecolor(c);
        applyTool();
    }

    private int appliedTool = -1;

    private void applyTool() {
        penB.set(tool == Engine.TOOL_PEN, penColors[penSlot], penColors[penSlot]);
        hlB.set(tool == Engine.TOOL_HL, hlColors[hlSlot], hlColors[hlSlot]);
        eraserB.set(tool == Engine.TOOL_ERASER, 0, 0);
        lassoB.set(tool == Engine.TOOL_LASSO, 0, 0);
        laserB.set(tool == Engine.TOOL_LASER, LASER_COLORS[laserCol], 0);
        tapeB.set(tool == Engine.TOOL_TAPE, TAPE_COLORS[tapeCol], 0);
        textB.set(tool == Engine.TOOL_TEXT, 0, 0);
        shapeB.set(tool == Engine.TOOL_SHAPE, penColors[penSlot], 0);
        if (tool != Engine.TOOL_LASSO) pendingToText = null;
        styleShelf();
        boolean hl = tool == Engine.TOOL_HL;
        int[] cols = slotColors();
        int sel = hl ? hlSlot : penSlot;
        for (int i = 0; i < 4; i++) sw[i].set(cols[i], tool != Engine.TOOL_ERASER && tool != Engine.TOOL_LASSO && tool != Engine.TOOL_LASER && tool != Engine.TOOL_TAPE && i == sel);
        float frac = hl ? (hlW - HL_MIN) / (HL_MAX - HL_MIN) : tool == Engine.TOOL_TEXT ? (textPt - Fonts.PT_MIN) / (40 - Fonts.PT_MIN)
                : tool == Engine.TOOL_ERASER ? (eraserDp - 6) / 24f : (penW - PEN_MIN) / (PEN_MAX - PEN_MIN);
        sizeB.set(Math.max(0, Math.min(1, frac)));
        sizeB.setVisibility(tool == Engine.TOOL_LASSO || tool == Engine.TOOL_LASER || tool == Engine.TOOL_TAPE ? View.GONE : View.VISIBLE);
        palette.animate().alpha(tool == Engine.TOOL_ERASER || tool == Engine.TOOL_LASER || tool == Engine.TOOL_TAPE ? 0.4f : 1f).setDuration(120).start();
        engine.setTool(tool);
        engine.setColor(Engine.TOOL_PEN, penColors[penSlot]);
        engine.setColor(Engine.TOOL_HL, hlColors[hlSlot]);
        engine.setWidth(Engine.TOOL_PEN, penW);
        engine.setWidth(Engine.TOOL_HL, hlW);
        engine.setTextSize(textPt * Fonts.UNITS_PER_PT);
        engine.setTextLook(textStyleEnc, 0);
        engine.setPenType(penType);
        engine.setEraserSize(eraserDp);
        // only a tool change drops the selection (colour / size changes recolour or keep a fresh shape's handles)
        if (tool != appliedTool && tool != Engine.TOOL_LASSO) hideSelection();
        // a tool was picked (from the bar or the shelf): the shelf closes unless it's pinned open
        if (tool != appliedTool && appliedTool != -1 && shelfOpen && !shelfPinned && pendingToText == null) setShelfOpen(false);
        appliedTool = tool;
        updatePaste();
        renderFavs();
        SharedPreferences.Editor e = prefs.edit().putInt("tool", tool).putInt("penColor", penSlot).putInt("hlColor", hlSlot)
                .putFloat("penW", penW).putFloat("hlW", hlW).putFloat("textPt", textPt).putString("textStyle", textStyleEnc).putInt("penType", penType)
                .putFloat("eraserDp", eraserDp);
        for (int i = 0; i < 4; i++) e.putInt("pc" + i, penColors[i]).putInt("hc" + i, hlColors[i]);
        e.apply();
    }

    // ---- tool panels -------------------------------------------------------------------------

    private Ui.Popover newPop(int widthDp) {
        if (pop != null) pop.dismiss();
        pop = new Ui.Popover(this, widthDp);
        return pop;
    }

    private LinearLayout.LayoutParams fullW(int hDp) {
        return new LinearLayout.LayoutParams(-1, hDp < 0 ? -2 : Ui.dp(this, hDp));
    }

    private void showPenPanel(View anchor, boolean hl) {
        Ui.Popover p = newPop(372).title(hl ? "Highlighter" : tool == Engine.TOOL_SHAPE ? "Shapes · drawn strokes become clean shapes" : "Pen");
        Ui.StrokePreview prev = new Ui.StrokePreview(this);
        java.util.ArrayList<Ui.SizeDot> dots = new java.util.ArrayList<>();
        float[] pr = hl ? HL_PRESETS : PEN_PRESETS;
        Runnable refresh = () -> {
            int tl = hl ? (hlFlat ? Stroke.HL_FLAT : Stroke.HL) : penType;
            float cw = hl ? hlW : penW;
            prev.set(tl, curColor(), cw);
            prev.zoom(state != null ? state.z : 0);
            for (int i = 0; i < dots.size(); i++) {
                dots.get(i).ink(inkPx(tl, pr[i]));
                dots.get(i).chosen(Math.abs(pr[i] - cw) < 0.05f);
            }
        };
        if (hl) {
            p.section("Tip");
            p.add(Ui.segmented(this, new String[]{"Round", "Flat"}, hlFlat ? 1 : 0, i -> { hlFlat = i == 1; saveOptions(); refresh.run(); }));
        }
        if (!hl) {
            p.section("Type");
            int sel = 0;
            for (int i = 0; i < PEN_TYPE_IDS.length; i++) if (PEN_TYPE_IDS[i] == penType) sel = i;
            p.add(Ui.segmented(this, PEN_TYPES, sel, i -> { penType = PEN_TYPE_IDS[i]; applyTool(); refresh.run(); }));
        }
        LinearLayout.LayoutParams pl = fullW(84);
        pl.topMargin = Ui.dp(this, 14);
        prev.setLayoutParams(pl);
        p.add(prev);
        p.section("Thickness");
        float min = hl ? HL_MIN : PEN_MIN, max = hl ? HL_MAX : PEN_MAX;
        TextView wLabel = Ui.text(this, "", 12.5f, Ui.color(this, R.color.on_surface_variant), null);
        // on an A4-wide page (1000 units = 210 mm), at normal writing pressure: the same width the dots show
        Runnable label = () -> wLabel.setText(String.format(java.util.Locale.US, "%.1f mm",
                new Stroke(hl ? (hlFlat ? Stroke.HL_FLAT : Stroke.HL) : penType, 0, hl ? hlW : penW).radius(0.5f) * 2 * 0.21f));
        SeekBar sb = Ui.slider(this, 100, wToSlider(hl ? hlW : penW, min, max), v -> {
            float w = sliderToW(v, min, max);
            if (hl) hlW = w; else penW = w;
            applyTool();
            refresh.run();
            label.run();
        });
        LinearLayout presets = new LinearLayout(this);
        presets.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < pr.length; i++) {
            final float w = pr[i];
            Ui.SizeDot d = new Ui.SizeDot(this);
            d.tint(curColor());
            d.setOnClickListener(v -> {
                if (hl) hlW = w; else penW = w;
                sb.setProgress(wToSlider(w, min, max));
                applyTool();
                refresh.run();
                label.run();
            });
            dots.add(d);
            presets.addView(d);
        }
        presets.addView(sb, new LinearLayout.LayoutParams(0, -2, 1f));
        label.run();
        presets.addView(wLabel, new LinearLayout.LayoutParams(Ui.dp(this, 52), -2));
        wLabel.setGravity(Gravity.END);
        p.add(presets);
        refresh.run();
        if (hl) {
            p.section("Straighten on lift");
            p.add(Ui.segmented(this, new String[]{"Off", "Auto", "Always"}, hlStraight,
                    i -> { hlStraight = i; saveOptions(); }));
            TextView sh = Ui.text(this, "Auto: a nearly straight swipe becomes a straight line when you lift", 12f,
                    Ui.color(this, R.color.on_surface_variant), null);
            sh.setPadding(0, Ui.dp(this, 6), 0, 0);
            p.add(sh);
        }
        p.section("Colour");
        p.add(Ui.colorGrid(this, hl ? HL_PALETTE : INK_PALETTE, curColor(), c -> { setColor(c); refresh.run(); pop.dismiss(); }));
        LinearLayout.LayoutParams fl = fullW(-1);
        fl.topMargin = Ui.dp(this, 8);
        View fav = Ui.actionRow(this, R.drawable.ic_star, "Save as favourite", "Puts this pen in the tool bar for one-tap switching",
                v -> { pop.dismiss(); addFav(); });
        fav.setLayoutParams(fl);
        p.add(fav);
        addMoveRow(p);
        p.show(anchor);
    }

    // ---- side by side ------------------------------------------------------------------------

    /** Notebook picker: open tabs first, then the rest by recent edit. */
    private void pickNotebook(View anchor, String title, java.util.function.Consumer<Doc> then) {
        java.util.List<Notebook.Entry> all = Notebook.list(this);
        all.sort((a, b2) -> Long.compare(b2.modified, a.modified));
        Ui.Popover p = newPop(380).title(title);
        p.add(Ui.actionRow(this, R.drawable.ic_notebook_new, "New notebook", "Choose page style, paper, and cover",
                v -> { pop.dismiss(); newNotebook(false, then); }));
        p.add(Ui.actionRow(this, R.drawable.ic_infinity, "New infinite canvas", "Unbounded canvas with your chosen paper",
                v -> { pop.dismiss(); newNotebook(true, then); }));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int shownN = 0;
        for (Notebook.Entry e : all) {
            if (realPath(e.dir).equals(realPath(engine.dir))) continue;
            boolean isTab = findDoc(e.dir) != null;
            String meta = (e.canvas ? "Canvas" : e.pages + (e.pages == 1 ? " page" : " pages")) + (isTab ? " · open in a tab" : "");
            View row = Ui.actionRow(this, e.canvas ? R.drawable.ic_maximize : R.drawable.ic_book, e.title, meta,
                    v -> { pop.dismiss(); then.accept(openDoc(e.dir, -1)); });
            if (isTab) list.addView(row, 0); else list.addView(row);
            shownN++;
        }
        if (shownN == 0) list.addView(Ui.text(this, "No other notebooks yet.", 14, Ui.color(this, R.color.on_surface_variant), null));
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        p.add(sv);
        sv.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.min(Ui.dp(this, 64) * Math.max(1, shownN), getResources().getDisplayMetrics().heightPixels / 2)));
        p.show(anchor);
    }

    /**
     * New notebook or infinite canvas from a tab's + or the split view menu,
     * made in the current notebook's folder and opened where asked.
     */
    private void newNotebook(boolean startCanvas, java.util.function.Consumer<Doc> then) {
        String title = new java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()).format(new java.util.Date());
        Notebook.Page proto = new Notebook.Page(Notebook.BG_DOTS);
        int paper = Ui.dark(this) ? Notebook.PAPER_DARK : Notebook.PAPER_WHITE;
        try {
            String def = prefs.getString("defStyle", null);
            if (def != null) {
                org.json.JSONObject o = new org.json.JSONObject(def);
                proto = lookOf(o);
                paper = o.optInt("p", paper);
            }
        } catch (org.json.JSONException ignored) { }
        org.json.JSONArray mine;
        try { mine = new org.json.JSONArray(prefs.getString("myTpl", "[]")); } catch (org.json.JSONException e) { mine = new org.json.JSONArray(); }
        final String folder = engine != null ? Notebook.meta(engine.dir).getProperty("folder", "") : "";
        NewNotebookSheet.show(this, title, startCanvas, proto, paper, mine,
                o -> prefs.edit().putString("defStyle", o.toString()).apply(),
                (t, canvas, look, pp, cover) -> {
                    try {
                        File dir = Notebook.create(this, t, look, folder, canvas, pp);
                        if (cover != null) {
                            Notebook.setCover(dir, cover);
                            prefs.edit().putString("view:" + dir.getName(), "0,0,1,0,0").apply(); // opens at page 1, ready to write
                        }
                        then.accept(openDoc(dir, -1));
                    } catch (Exception ex) {
                        toast("Couldn't create the notebook");
                    }
                });
    }

    /** Narrow windows (split screen): drop the least-needed controls until the tool capsule fits. */
    private void pickImage() {
        startActivityForResult(new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"), REQ_IMAGE);
    }

    // ---- stickers: an app-wide library (Stickers) shown under the image button ----------------------------------

    private void showInsert(View anchor) {
        Ui.Popover p = newPop(360);
        p.add(Ui.actionRow(this, R.drawable.ic_image, "Photo or image", "From your gallery", v -> { pop.dismiss(); pickImage(); }));
        p.section("Stickers");
        java.util.List<File> all = Stickers.list(this);
        if (all.isEmpty()) {
            TextView none = Ui.text(this, "Import PNG images or a .zip sticker pack once, then place them on any page", 12.5f,
                    Ui.color(this, R.color.on_surface_variant), null);
            none.setPadding(0, 0, 0, Ui.dp(this, 6));
            p.add(none);
        } else {
            Ui.Grid g = new Ui.Grid(this, 52, 8);
            for (File f : all) {
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
                iv.setBackground(Ui.ripple(this, Ui.round(Ui.color(this, R.color.surface_variant), Ui.dp(this, 8)), Ui.dp(this, 8)));
                iv.setLayoutParams(new ViewGroup.LayoutParams(-1, Ui.dp(this, 56)));
                iv.setContentDescription("Sticker");
                bg.execute(() -> { Bitmap b = Stickers.thumb(f, Ui.dp(this, 96)); ui.post(() -> iv.setImageBitmap(b)); });
                iv.setOnClickListener(v -> { pop.dismiss(); placeSticker(f); });
                iv.setOnLongClickListener(v -> {
                    new android.app.AlertDialog.Builder(this).setTitle("Remove this sticker?")
                            .setMessage("It goes from your sticker library. Pages it's already on keep it.")
                            .setPositiveButton("Remove", (d, w) -> { f.delete(); pop.dismiss(); showInsert(anchor); })
                            .setNegativeButton("Cancel", null).show();
                    return true;
                });
                g.addView(iv);
            }
            p.add(g);
        }
        p.add(Ui.actionRow(this, R.drawable.ic_import, "Import stickers", "Images or a .zip pack · long-press a sticker to remove it", v -> {
            pop.dismiss();
            stickerAnchor = anchor;
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/zip"})
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQ_STICKERS);
        }));
        p.show(anchor);
    }

    private View stickerAnchor;

    private void placeSticker(File f) {
        final Engine target = engine;
        bg.execute(() -> {
            try {
                Object[] r = Stickers.place(f, target.dir);
                if (r == null) throw new java.io.IOException("unreadable");
                ui.post(() -> {
                    target.insertImage((String) r[0], (Integer) r[1], (Integer) r[2], 160f);
                    if (target == engine) { tool = Engine.TOOL_LASSO; applyTool(); }
                });
            } catch (java.io.IOException e) {
                ui.post(() -> toast("Couldn't place that sticker"));
            }
        });
    }

    private void toggleFinger() {
        fingerDraw = !fingerDraw;
        saveOptions();
        toast(fingerDraw ? "Fingers write · use two fingers to scroll and zoom" : "Only the pen writes · fingers scroll");
    }

    /** Favourite pens that didn't fit in a narrow bar. */
    private void showMoreTools(View anchor) {
        Ui.Popover p = newPop(300);
        p.title("Favourite pens");
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (int[] f : favs) {
            Ui.FavDot d = new Ui.FavDot(this, f[0] == Engine.TOOL_HL, f[2], Float.intBitsToFloat(f[3]));
            d.setSel(favMatches(f));
            d.setOnClickListener(v -> { pop.dismiss(); applyFav(f); });
            row.addView(d);
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row);
        p.add(hs);
        p.show(anchor);
    }

    private void fitCapsule() {
        if (capTools == null || root.getWidth() == 0) return;
        // dropped in this order until the bar fits (favourites stay reachable under their own disc); the shelf scrolls
        View[] order = {favRow, favSep, sw[0], sw[1], sw[2], sw[3]};
        boolean vert = dockVertical();
        // a column must clear the top bars; a row must fit the width
        int avail = vert ? root.getHeight() - Ui.dp(this, 2 * (EDGE_DP + CAP_DP + 12)) : root.getWidth() - Ui.dp(this, 2 * EDGE_DP);
        for (View v : order) if (v != null && v != favRow && v != favSep) v.setVisibility(View.VISIBLE);
        moreToolsB.setVisibility(View.GONE);
        favsFolded = false;
        renderFavs();
        int tc = View.MeasureSpec.makeMeasureSpec(Ui.dp(this, TOOLCAP_DP), View.MeasureSpec.EXACTLY), free = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        for (View v : order) {
            if (vert) capTools.measure(tc, free); else capTools.measure(free, tc);
            if ((vert ? capTools.getMeasuredHeight() : capTools.getMeasuredWidth()) <= avail) break;
            if (v != null) v.setVisibility(View.GONE);
            if (v == favRow && !favs.isEmpty()) { favsFolded = true; moreToolsB.setVisibility(View.VISIBLE); }
        }
        // narrow window: bookmark + pages leave the top-right capsule (both stay in ⋯ / the page counter)
        boolean narrow = root.getWidth() < Ui.dp(this, 520);
        boolean canvas = state != null && state.canvas;
        bookmarkB.setVisibility(narrow || canvas ? View.GONE : View.VISIBLE);
        addPageB.setVisibility(narrow || canvas ? View.GONE : View.VISIBLE);
        pagesB.setVisibility(narrow || canvas ? View.GONE : View.VISIBLE);
        capRight.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(Ui.dp(this, CAP_DP), View.MeasureSpec.EXACTLY));
        fitTabs();
        if (ruler.getVisibility() == View.VISIBLE) { ruler.place(root.getWidth(), root.getHeight()); ruler.invalidate(); rulerChanged(); }
    }

    // ---- ruler + drawing scale ---------------------------------------------------------------

    private void toggleRuler() {
        boolean on = ruler.getVisibility() != View.VISIBLE;
        if (on && ruler.halfLen == 0) ruler.place(root.getWidth(), root.getHeight());
        ruler.setVisibility(on ? View.VISIBLE : View.GONE);
        rulerB.set(on, 0, 0);
        rulerChanged();
        if (on) toast("Drag with one finger, turn with two · draw along an edge for straight lines");
    }

    @Override public void rulerChanged() {
        engine.setRuler(ruler.getVisibility() == View.VISIBLE, ruler.cx - paneLeft(), ruler.cy - paneTop(), ruler.angle, ruler.halfLen, ruler.halfThick);
    }

    @Override public float pxPerUnit() { return state != null && state.z > 0 ? state.z : 1f; }

    @Override public int scale() { return drawScale; }

    @Override public void onRulerLength(float units, boolean done) { if (ruler.getVisibility() == View.VISIBLE) ruler.showLength(units, done); }

    private void showScale(View anchor) {
        Ui.Popover p = newPop(360).title("Drawing scale");
        TextView hint = Ui.text(this, "Used by the ruler: its ticks and the length of ruled lines show real-world units.", 12.5f,
                Ui.color(this, R.color.on_surface_variant), null);
        hint.setPadding(0, 0, 0, Ui.dp(this, 12));
        p.add(hint);
        int[] opts = {0, 1, 5, 10, 20, 50, 100, 200, 500, 1000, 2500, 5000};
        GridLayout g = new GridLayout(this);
        g.setColumnCount(4);
        for (int o : opts) {
            TextView t = Ui.text(this, o == 0 ? "None" : "1:" + o, 13.5f, Ui.color(this, o == drawScale ? R.color.on_accent : R.color.on_surface), Ui.medium());
            t.setGravity(Gravity.CENTER);
            GradientDrawable bgD = Ui.round(Ui.color(this, o == drawScale ? R.color.accent : R.color.surface_variant), Ui.dp(this, 8));
            t.setBackground(Ui.ripple(this, bgD, Ui.dp(this, 8)));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = Ui.dp(this, 74);
            lp.height = Ui.dp(this, 36);
            lp.setMargins(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
            t.setOnClickListener(v -> {
                drawScale = o;
                prefs.edit().putInt("scale:" + engine.dir.getName(), o).apply();
                cur.scale = o;
                ruler.invalidate();
                pop.dismiss();
                toast(o == 0 ? "No scale · the ruler measures the paper" : "Scale 1:" + o);
            });
            g.addView(t, lp);
        }
        p.add(g);
        p.show(anchor);
    }

    // ---- favourite pens --------------------------------------------------------------------

    private void loadFavs() {
        favs.clear();
        try {
            org.json.JSONArray a = new org.json.JSONArray(prefs.getString("favs", "[]"));
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONArray f = a.getJSONArray(i);
                favs.add(new int[]{f.getInt(0), f.getInt(1), f.getInt(2), Float.floatToIntBits((float) f.getDouble(3))});
            }
        } catch (Exception ignored) { }
    }

    private void saveFavs() {
        org.json.JSONArray a = new org.json.JSONArray();
        try {
            for (int[] f : favs) a.put(new org.json.JSONArray().put(f[0]).put(f[1]).put(f[2]).put((double) Float.intBitsToFloat(f[3])));
        } catch (Exception ignored) { }
        prefs.edit().putString("favs", a.toString()).apply();
        renderFavs();
    }

    private boolean favMatches(int[] f) {
        boolean hl = f[0] == Engine.TOOL_HL;
        if (tool != f[0] || curColor() != f[2]) return false;
        return Math.abs((hl ? hlW : penW) - Float.intBitsToFloat(f[3])) < 0.01f && (hl || penType == f[1]);
    }

    private void renderFavs() {
        if (favRow == null) return;
        favRow.removeAllViews();
        for (int[] f : favs) {
            Ui.FavDot d = new Ui.FavDot(this, f[0] == Engine.TOOL_HL, f[2], Float.intBitsToFloat(f[3]));
            d.setSel(favMatches(f));
            d.setOnClickListener(v -> applyFav(f));
            d.setOnLongClickListener(v -> {
                PopupMenu pm = new PopupMenu(this, v);
                pm.getMenu().add("Remove favourite");
                pm.setOnMenuItemClickListener(it -> { favs.remove(f); saveFavs(); return true; });
                pm.show();
                return true;
            });
            favRow.addView(d);
            if (capTools != null && dockVertical()) orient(d, true);
        }
        int vis = favs.isEmpty() || favsFolded ? View.GONE : View.VISIBLE; // folded: fitCapsule moved them to "More tools"
        favRow.setVisibility(vis);
        favSep.setVisibility(vis);
    }

    /** Favourite pens didn't fit in the bar (they're listed under "More tools" instead). */
    private boolean favsFolded;

    private void applyFav(int[] f) {
        commitTextEdit();
        tool = f[0];
        boolean hl = tool == Engine.TOOL_HL;
        int[] cols = hl ? hlColors : penColors;
        int slot = -1;
        for (int i = 0; i < 4; i++) if (cols[i] == f[2]) slot = i;
        if (slot < 0) { slot = hl ? hlSlot : penSlot; cols[slot] = f[2]; }
        float w = Float.intBitsToFloat(f[3]);
        if (hl) { hlSlot = slot; hlW = w; } else { penSlot = slot; penW = w; penType = f[1]; }
        applyTool();
    }

    private void addFav() {
        boolean hl = tool == Engine.TOOL_HL;
        int[] f = {hl ? Engine.TOOL_HL : Engine.TOOL_PEN, penType, curColor(), Float.floatToIntBits(hl ? hlW : penW)};
        for (int[] g : favs) if (java.util.Arrays.equals(g, f)) { toast("Already a favourite"); return; }
        if (favs.size() >= 4) { toast("Up to 4 favourites · long-press one in the tool bar to remove it"); return; }
        favs.add(f);
        saveFavs();
        toast("Added to the tool bar");
    }

    // ---- bookmarks -------------------------------------------------------------------------

    /** Name (or create) the bookmark on page i. after: runs once saved (pages view refresh). */
    private void nameBookmark(int i, Runnable after) {
        String cur = state != null && state.marks != null && i < state.marks.length ? state.marks[i] : null;
        EditText et = new EditText(this);
        et.setText(cur == null ? "" : cur);
        et.setHint("Page " + (i + 1));
        et.setSelectAllOnFocus(true);
        et.setSingleLine(true);
        FrameLayout w = new FrameLayout(this);
        int pd = Ui.dp(this, 22);
        w.setPadding(pd, Ui.dp(this, 8), pd, 0);
        w.addView(et);
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(cur == null ? "Bookmark page " + (i + 1) : "Bookmark name").setView(w)
                .setPositiveButton("Save", (d, x) -> { engine.setBookmark(i, et.getText().toString().trim()); if (after != null) ui.postDelayed(after, 150); })
                .setNegativeButton("Cancel", null);
        if (cur != null) b.setNeutralButton("Remove", (d, x) -> { engine.setBookmark(i, null); if (after != null) ui.postDelayed(after, 150); });
        b.show();
        et.requestFocus();
    }

    private void showColors(View anchor) {
        if (tool == Engine.TOOL_ERASER || tool == Engine.TOOL_LASSO && (state == null || !state.hasSelection)) {
            tool = prevToolForColor();
            applyTool();
        }
        boolean hl = tool == Engine.TOOL_HL;
        Ui.Popover p = newPop(340).title(hl ? "Highlighter colour" : "Ink colour");
        TextView hint = Ui.text(this, "Replaces the selected colour slot · long-press a slot to edit it", 12.5f,
                Ui.color(this, R.color.on_surface_variant), null);
        hint.setPadding(0, 0, 0, Ui.dp(this, 10));
        p.add(hint);
        p.add(Ui.colorGrid(this, hl ? HL_PALETTE : INK_PALETTE, curColor(), c -> { setColor(c); pop.dismiss(); }));
        p.show(anchor);
    }

    private int prevToolForColor() { return prevTool == Engine.TOOL_HL ? Engine.TOOL_HL : Engine.TOOL_PEN; }

    private void showEraserPanel(View anchor) {
        Ui.Popover p = newPop(340).title("Eraser");
        p.section("Mode");
        p.add(Ui.segmented(this, new String[]{"Whole stroke", "Partial"}, partialErase ? 1 : 0, i -> {
            partialErase = i == 1;
            prefs.edit().putBoolean("partialErase", partialErase).apply();
            pushOptions();
        }));
        p.section("Size");
        p.add(Ui.slider(this, 24, Math.round(eraserDp - 6), v -> { eraserDp = 6 + v; applyTool(); }));
        LinearLayout.LayoutParams ol = fullW(-1);
        ol.topMargin = Ui.dp(this, 6);
        View prevRow = Ui.switchRow(this, "Grey out, delete on lift", "See what you're erasing before it goes",
                erasePreview, v -> { erasePreview = v; saveOptions(); });
        prevRow.setLayoutParams(ol);
        p.add(prevRow);
        p.add(Ui.switchRow(this, "Hold pen to erase", "Rest the pen where it lands for half a second: it erases until you lift",
                holdErase, v -> { holdErase = v; saveOptions(); }));
        LinearLayout.LayoutParams sp = fullW(-1);
        sp.topMargin = Ui.dp(this, 12);
        View clear = Ui.actionRow(this, R.drawable.ic_trash, "Clear this page", "Removes all ink, text and images on it",
                v -> { pop.dismiss(); engine.clearPage(); });
        clear.setLayoutParams(sp);
        p.add(clear);
        addMoveRow(p);
        p.show(anchor);
    }

    private void showTextPanel(View anchor) {
        Ui.Popover p = newPop(360).title("Text");
        Fonts.Style st = Fonts.Style.parse(textStyleEnc);
        p.section("Font");
        p.add(fontList(st.font, id -> {
            Fonts.Style s2 = Fonts.Style.parse(textStyleEnc);
            s2.font = id;
            textStyleEnc = s2.encode();
            applyTool();
        }, null, () -> showTextPanel(anchor)));
        p.section("Size");
        p.add(sizeRow(textPt, true, v -> { textPt = v; applyTool(); }));
        p.section("Style");
        p.add(styleRow(st, s2 -> { textStyleEnc = s2.encode(); applyTool(); }));
        p.section("Colour");
        p.add(Ui.colorGrid(this, INK_PALETTE, penColors[penSlot], c -> { penColors[penSlot] = c; applyTool(); pop.dismiss(); }));
        TextView hint = Ui.text(this, "Tap the page to add a text box · tap a box to edit it", 12.5f,
                Ui.color(this, R.color.on_surface_variant), null);
        hint.setPadding(0, Ui.dp(this, 12), 0, 0);
        p.add(hint);
        addMoveRow(p);
        p.show(anchor);
    }

    // ---- text look: font list, size, style toggles (Text panel and the format bar over a box) ----

    /**
     * Fonts, each name drawn in its own face, the chosen one ticked; "Import font" at the end. Long-press an
     * imported font to remove it. forEdit: the box being edited when Import is tapped (it gets the new font).
     */
    private View fontList(String selected, java.util.function.Consumer<String> pick, Engine.TextEdit forEdit, Runnable reopen) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        java.util.List<String> ids = Fonts.list(this);
        java.util.ArrayList<ImageView> ticks = new java.util.ArrayList<>();
        for (String id : ids) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 8), 0);
            row.setBackground(Ui.ripple(this, null, Ui.dp(this, 10)));
            TextView name = Ui.text(this, Fonts.name(id), 16.5f, Ui.color(this, R.color.on_surface), Fonts.face(id, false, false, false));
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            if (Fonts.imported(id)) {
                TextView tag = Ui.text(this, "Imported", 11.5f, Ui.color(this, R.color.on_surface_variant), null);
                tag.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
                row.addView(tag);
            }
            ImageView tick = new ImageView(this);
            tick.setImageResource(R.drawable.ic_check);
            tick.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, R.color.accent)));
            tick.setVisibility(id.equals(selected == null ? Fonts.INTER : selected) ? View.VISIBLE : View.INVISIBLE);
            ticks.add(tick);
            row.addView(tick, new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));
            row.setOnClickListener(v -> {
                for (ImageView t : ticks) t.setVisibility(t == tick ? View.VISIBLE : View.INVISIBLE);
                pick.accept(id);
            });
            if (Fonts.imported(id)) row.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this).setTitle("Remove " + Fonts.name(id) + "?")
                        .setMessage("Text in this font goes back to Inter.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Remove", (d, w) -> {
                            Fonts.remove(this, id);
                            if (id.equals(Fonts.Style.parse(textStyleEnc).font)) {
                                Fonts.Style s2 = Fonts.Style.parse(textStyleEnc);
                                s2.font = Fonts.INTER;
                                textStyleEnc = s2.encode();
                                applyTool();
                            }
                            for (Doc d2 : docs) if (d2.engine != null) d2.engine.refreshText();
                            if (pop != null) pop.dismiss();
                            if (reopen != null) reopen.run();
                        }).show();
                return true;
            });
            col.addView(row, fullW(44));
        }
        TextView add = Ui.text(this, "+  Import font (.ttf, .otf)", 14.5f, Ui.color(this, R.color.accent), Ui.medium());
        add.setGravity(Gravity.CENTER_VERTICAL);
        add.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        add.setBackground(Ui.ripple(this, null, Ui.dp(this, 10)));
        add.setOnClickListener(v -> {
            if (pop != null) pop.dismiss();
            startFontImport(forEdit, reopen);
        });
        col.addView(add, fullW(44));
        ScrollView sv = new ScrollView(this) {
            @Override protected void onMeasure(int w, int h) {
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(Ui.dp(getContext(), 232), View.MeasureSpec.AT_MOST));
            }
        };
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(col);
        return sv;
    }

    /** − 17 pt +, and a slider (6–96 pt) when there's room. */
    private View sizeRow(float pt, boolean slider, java.util.function.Consumer<Float> set) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        float[] cur = {Math.round(pt)};
        TextView val = Ui.text(this, "", 14.5f, Ui.color(this, R.color.on_surface), Ui.medium());
        val.setGravity(Gravity.CENTER);
        SeekBar[] bar = new SeekBar[1];
        Runnable show = () -> {
            val.setText(String.format(Locale.ROOT, "%.0f pt", cur[0]));
            if (bar[0] != null) bar[0].setProgress(Math.round(cur[0] - Fonts.PT_MIN));
        };
        TextView minus = stepButton("−"), plus = stepButton("+");
        minus.setOnClickListener(v -> { cur[0] = Math.max(Fonts.PT_MIN, cur[0] - 1); show.run(); set.accept(cur[0]); });
        plus.setOnClickListener(v -> { cur[0] = Math.min(Fonts.PT_MAX, cur[0] + 1); show.run(); set.accept(cur[0]); });
        row.addView(minus, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
        row.addView(val, new LinearLayout.LayoutParams(Ui.dp(this, slider ? 58 : 50), -2));
        row.addView(plus, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
        if (slider) {
            bar[0] = Ui.slider(this, Math.round(Fonts.PT_MAX - Fonts.PT_MIN), Math.round(cur[0] - Fonts.PT_MIN), v -> {
                cur[0] = Fonts.PT_MIN + v;
                val.setText(String.format(Locale.ROOT, "%.0f pt", cur[0]));
                set.accept(cur[0]);
            });
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, -2, 1f);
            bl.leftMargin = Ui.dp(this, 6);
            row.addView(bar[0], bl);
        }
        show.run();
        return row;
    }

    private TextView stepButton(String t) {
        TextView b = Ui.text(this, t, 18, Ui.color(this, R.color.on_surface), Ui.medium());
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.ripple(this, Ui.round(Ui.color(this, R.color.surface_variant), Ui.dp(this, 18)), Ui.dp(this, 18)));
        return b;
    }

    /** B I U S toggles, then left / centre / right. Changes are made on st and handed to onChange. */
    private View styleRow(Fonts.Style st, java.util.function.Consumer<Fonts.Style> onChange) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        String[] labels = {"B", "I", "U", "S"};
        String[] descs = {"Bold", "Italic", "Underline", "Strike-through"};
        TextView[] tg = new TextView[4];
        for (int i = 0; i < 4; i++) {
            final int k = i;
            TextView t = Ui.text(this, labels[i], 16, Ui.color(this, R.color.on_surface), Fonts.face(Fonts.SERIF, false, i == 0, i == 1));
            t.setGravity(Gravity.CENTER);
            t.setContentDescription(descs[i]);
            if (i == 2) t.setPaintFlags(t.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
            if (i == 3) t.setPaintFlags(t.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            tg[i] = t;
            t.setOnClickListener(v -> {
                if (k == 0) st.bold = !st.bold; else if (k == 1) st.italic = !st.italic;
                else if (k == 2) st.underline = !st.underline; else st.strike = !st.strike;
                styleToggle(t, k == 0 ? st.bold : k == 1 ? st.italic : k == 2 ? st.underline : st.strike);
                onChange.accept(st);
            });
            styleToggle(t, k == 0 ? st.bold : k == 1 ? st.italic : k == 2 ? st.underline : st.strike);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 36));
            lp.rightMargin = Ui.dp(this, 3);
            row.addView(t, lp);
        }
        View gap = new View(this);
        row.addView(gap, new LinearLayout.LayoutParams(Ui.dp(this, 10), 1));
        int[] icons = {R.drawable.ic_align_left, R.drawable.ic_align_center, R.drawable.ic_align_right};
        String[] an = {"Align left", "Centre", "Align right"};
        ImageView[] al = new ImageView[3];
        for (int i = 0; i < 3; i++) {
            final int k = i;
            ImageView b = new ImageView(this);
            b.setImageResource(icons[i]);
            b.setScaleType(ImageView.ScaleType.CENTER);
            b.setContentDescription(an[i]);
            al[i] = b;
            b.setOnClickListener(v -> {
                st.align = k;
                for (int j = 0; j < 3; j++) styleToggle(al[j], j == k);
                onChange.accept(st);
            });
            styleToggle(b, st.align == i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 36));
            lp.rightMargin = Ui.dp(this, 3);
            row.addView(b, lp);
        }
        return row;
    }

    private void styleToggle(View v, boolean on) {
        int r = Ui.dp(this, 9);
        v.setBackground(Ui.ripple(this, on ? Ui.round(Ui.color(this, R.color.accent_container), r) : null, r));
        int fg = Ui.color(this, on ? R.color.accent : R.color.icon);
        if (v instanceof TextView) ((TextView) v).setTextColor(on ? fg : Ui.color(this, R.color.on_surface));
        else ((ImageView) v).setImageTintList(android.content.res.ColorStateList.valueOf(fg));
    }

    /** Opens the file picker for a .ttf / .otf; forEdit is the box being typed in (the picker closes it). */
    private void startFontImport(Engine.TextEdit forEdit, Runnable after) {
        fontImportFor = forEdit;
        fontImportEngine = forEdit != null ? engine : null;
        afterFontImport = after;
        Intent in = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf",
                        "application/x-font-otf", "application/font-sfnt", "application/vnd.ms-opentype", "application/octet-stream"});
        try {
            startActivityForResult(in, REQ_FONT);
        } catch (android.content.ActivityNotFoundException e) {
            toast("No file picker on this tablet");
        }
    }

    private void fontPicked(Uri uri) {
        final Engine.TextEdit forEdit = fontImportFor;
        final Engine target = fontImportEngine;
        final Runnable after = afterFontImport;
        fontImportFor = null;
        fontImportEngine = null;
        afterFontImport = null;
        bg.execute(() -> {
            try {
                String id = Fonts.importFont(this, uri);
                ui.post(() -> {
                    Fonts.Style s2 = Fonts.Style.parse(textStyleEnc);
                    s2.font = id;
                    textStyleEnc = s2.encode();
                    applyTool();
                    if (forEdit != null && target != null) {
                        Fonts.Style es = Fonts.Style.parse(forEdit.style);
                        es.font = id;
                        target.setTextStyleAt(forEdit.page, forEdit.docX, forEdit.docY, es.encode());
                    }
                    toast("Added " + Fonts.name(id));
                    if (after != null && forEdit == null) after.run();
                });
            } catch (Exception e) {
                ui.post(() -> toast("That file isn't a font Nib can use (.ttf or .otf)"));
            }
        });
    }

    // ---- page style panel ------------------------------------------------------------------

    // ---- page style panel: template, lines, margins, paper; my templates; default for new notebooks ----

    /** Pattern units of each template's line spacing (for showing the spacing slider in mm); 0 = no spacing. */
    private static final float[] BASE_UNITS = {0, 40, 25, 25, 30, 40, 10, 23.81f, 26, 40};
    private static final int[] LINE_COLORS = {0, 0xff8a919e, 0xff3a3f48, 0xff6f8fd8, 0xff79b98a, 0xffe08585, 0xffa58bd8, 0xffb59473, 0xffd9b44a};

    private void showStyle(View anchor) {
        if (state == null) return;
        final int[] bg = {state.bg};
        final float[] sp = {state.spacing}, st = {state.strength};
        final int[] col = {state.lineColor}, mg = {state.margins};
        final boolean canvasNb = state.canvas;
        Runnable apply = () -> engine.setLook(false, sp[0], st[0], col[0], mg[0]);

        Ui.Popover p = newPop(470).title(canvasNb ? "Background" : "Page style");
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);

        // templates
        body.addView(section("Template"));
        GridLayout g = new GridLayout(this);
        g.setColumnCount(4);
        for (int i = 0; i < Notebook.BG_NAMES.length; i++) {
            final int bgId = i;
            View tile = styleTile(Notebook.BG_NAMES[i], i, state.paper, null, i == bg[0]);
            tile.setOnClickListener(v -> { engine.setBackground(bgId); pop.dismiss(); });
            tile.setOnLongClickListener(v -> { engine.pageOp("bgAll", 0, bgId); toast("Applied to all pages"); pop.dismiss(); return true; });
            g.addView(tile);
        }
        body.addView(g);
        body.addView(hint("Tap: this page · long-press: every page"));

        // my templates
        org.json.JSONArray mine = myTemplates();
        if (mine.length() > 0) {
            body.addView(section("My templates"));
            GridLayout mg2 = new GridLayout(this);
            mg2.setColumnCount(4);
            for (int i = 0; i < mine.length(); i++) {
                org.json.JSONObject t = mine.optJSONObject(i);
                if (t == null) continue;
                final int idx = i;
                Notebook.Page look = lookOf(t);
                View tile = styleTile(t.optString("n", "Template"), look.bg, t.optInt("p", state.paper), look, false);
                tile.setOnClickListener(v -> { applyTemplate(t, false); pop.dismiss(); });
                tile.setOnLongClickListener(v -> {
                    PopupMenu pm = new PopupMenu(this, v);
                    pm.getMenu().add(0, 1, 0, "Apply to every page");
                    pm.getMenu().add(0, 2, 1, "Delete template");
                    pm.setOnMenuItemClickListener(it -> {
                        if (it.getItemId() == 1) applyTemplate(t, true);
                        else { mine.remove(idx); prefs.edit().putString("myTpl", mine.toString()).apply(); toast("Template deleted"); }
                        pop.dismiss();
                        return true;
                    });
                    pm.show();
                    return true;
                });
                mg2.addView(tile);
            }
            body.addView(mg2);
        }

        // lines
        body.addView(section("Lines"));
        float base = BASE_UNITS[Math.max(0, Math.min(BASE_UNITS.length - 1, bg[0]))];
        if (base > 0) {
            TextView spLabel = Ui.text(this, "", 13, Ui.color(this, R.color.on_surface), null);
            Runnable spText = () -> spLabel.setText(String.format(java.util.Locale.US, "Spacing  ·  %.1f mm", base * sp[0] * Ruler.MM_PER_UNIT));
            spText.run();
            body.addView(spLabel);
            body.addView(liveSlider(150, Math.round((sp[0] - 0.5f) * 100), v -> { sp[0] = 0.5f + v / 100f; spText.run(); }, apply));
        }
        TextView stLabel = Ui.text(this, "", 13, Ui.color(this, R.color.on_surface), null);
        Runnable stText = () -> stLabel.setText(st[0] < 0.75f ? "Strength  ·  faint" : st[0] > 1.4f ? "Strength  ·  bold" : "Strength  ·  normal");
        stText.run();
        stLabel.setPadding(0, Ui.dp(this, 6), 0, 0);
        body.addView(stLabel);
        body.addView(liveSlider(220, Math.round((st[0] - 0.3f) * 100), v -> { st[0] = 0.3f + v / 100f; stText.run(); }, apply));
        TextView cl = Ui.text(this, "Colour", 13, Ui.color(this, R.color.on_surface), null);
        cl.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 4));
        body.addView(cl);
        LinearLayout colors = new LinearLayout(this);
        colors.setGravity(Gravity.CENTER_VERTICAL);
        Ui.Swatch[] sws = new Ui.Swatch[LINE_COLORS.length];
        for (int i = 0; i < LINE_COLORS.length; i++) {
            final int c = LINE_COLORS[i];
            Ui.Swatch s2 = new Ui.Swatch(this);
            s2.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
            s2.set(c == 0 ? Render.PAPER[Math.max(0, Math.min(2, state.paper))][1] : c, c == col[0]);
            s2.setContentDescription(c == 0 ? "Paper's own colour" : "Line colour");
            sws[i] = s2;
            s2.setOnClickListener(v -> {
                col[0] = c;
                for (int k2 = 0; k2 < sws.length; k2++) sws[k2].set(sws[k2].color, LINE_COLORS[k2] == c);
                apply.run();
            });
            colors.addView(s2);
        }
        body.addView(colors);
        body.addView(hint("First dot: the paper's own line colour"));

        // margins
        if (!canvasNb) {
            body.addView(section("Margins"));
            LinearLayout mrow = new LinearLayout(this);
            String[] names = {"Left margin", "Header line", "Border"};
            int[] bits = {Notebook.MARGIN_LEFT, Notebook.MARGIN_HEADER, Notebook.MARGIN_BORDER};
            for (int i = 0; i < 3; i++) {
                final int bit = bits[i];
                TextView chip = Ui.text(this, names[i], 13, 0, Ui.medium());
                chip.setGravity(Gravity.CENTER);
                Runnable paint = () -> {
                    boolean on = (mg[0] & bit) != 0;
                    chip.setTextColor(Ui.color(this, on ? R.color.on_accent : R.color.on_surface));
                    chip.setBackground(Ui.ripple(this, Ui.round(Ui.color(this, on ? R.color.accent : R.color.surface_variant), Ui.dp(this, 8)), Ui.dp(this, 8)));
                };
                paint.run();
                chip.setOnClickListener(v -> { mg[0] ^= bit; paint.run(); apply.run(); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 36), 1f);
                lp.rightMargin = Ui.dp(this, 6);
                mrow.addView(chip, lp);
            }
            body.addView(mrow);
        }

        // paper
        body.addView(section("Paper colour"));
        LinearLayout papers = new LinearLayout(this);
        String[] pn = {"White", "Cream", "Dark"};
        for (int i = 0; i < 3; i++) {
            final int pid = i;
            LinearLayout pc = new LinearLayout(this);
            pc.setOrientation(LinearLayout.VERTICAL);
            pc.setGravity(Gravity.CENTER_HORIZONTAL);
            pc.setPadding(Ui.dp(this, 8), Ui.dp(this, 2), Ui.dp(this, 8), 0);
            Ui.Swatch s2 = new Ui.Swatch(this);
            s2.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
            s2.set(Render.PAPER[i][0], i == state.paper);
            s2.setOnClickListener(v -> { engine.setPaper(pid); pop.dismiss(); });
            pc.addView(s2);
            pc.addView(Ui.text(this, pn[i], 12, Ui.color(this, R.color.on_surface_variant), null));
            papers.addView(pc);
        }
        body.addView(papers);

        // actions
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2);
        al.topMargin = Ui.dp(this, 10);
        View all = Ui.actionRow(this, R.drawable.ic_pages, "Apply to all pages", "This template and look on every page", v -> {
            engine.pageOp("bgAll", 0, bg[0]);
            engine.setLook(true, sp[0], st[0], col[0], mg[0]);
            toast("Applied to all pages");
            pop.dismiss();
        });
        all.setLayoutParams(al);
        body.addView(all);
        body.addView(Ui.actionRow(this, R.drawable.ic_star, "Save as my template", "Keep this look to reuse in any notebook",
                v -> { pop.dismiss(); saveTemplate(bg[0], sp[0], st[0], col[0], mg[0]); }));
        body.addView(Ui.actionRow(this, R.drawable.ic_page_add, "Use for new notebooks", "New notebooks start with this page style",
                v -> {
                    prefs.edit().putString("defStyle", lookJson("Default", bg[0], sp[0], st[0], col[0], mg[0], state.paper).toString()).apply();
                    toast("New notebooks will use this style");
                    pop.dismiss();
                }));
        if (prefs.contains("defStyle")) body.addView(Ui.actionRow(this, R.drawable.ic_undo, "Reset new-notebook style",
                "New notebooks go back to plain dotted paper", v -> {
                    prefs.edit().remove("defStyle").apply();
                    toast("New notebooks: standard style");
                    pop.dismiss();
                }));

        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        p.add(sv);
        sv.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.min(Ui.dp(this, 900), (int) (getResources().getDisplayMetrics().heightPixels * 0.72f))));
        p.show(anchor);
    }

    private TextView section(String t) {
        TextView v = Ui.text(this, t, 12.5f, Ui.color(this, R.color.on_surface_variant), Ui.medium());
        v.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 8));
        return v;
    }

    private TextView hint(String t) {
        TextView v = Ui.text(this, t, 12f, Ui.color(this, R.color.on_surface_variant), null);
        v.setPadding(0, Ui.dp(this, 2), 0, 0);
        return v;
    }

    /** Slider that updates its label while dragging and applies once, when let go (one undo step). */
    private SeekBar liveSlider(int max, int value, java.util.function.IntConsumer onMove, Runnable onDone) {
        SeekBar sb = Ui.slider(this, max, value, onMove);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) { if (user) onMove.accept(v); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { onDone.run(); }
        });
        return sb;
    }

    private View styleTile(String name, int bg, int paper, Notebook.Page look, boolean sel) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(Ui.dp(this, 5), Ui.dp(this, 4), Ui.dp(this, 5), Ui.dp(this, 8));
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(templateBitmap(bg, paper, look));
        GradientDrawable fr = Ui.round(0, Ui.dp(this, 6));
        fr.setStroke(sel ? Ui.dp(this, 2) : Ui.hairline(this), Ui.color(this, sel ? R.color.accent : R.color.outline));
        iv.setForeground(fr);
        iv.setClipToOutline(true);
        iv.setBackground(Ui.round(0xffffffff, Ui.dp(this, 6)));
        tile.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 92), Ui.dp(this, 130)));
        TextView l = Ui.text(this, name, 12, Ui.color(this, sel ? R.color.accent : R.color.on_surface_variant), Ui.medium());
        l.setSingleLine(true);
        l.setEllipsize(android.text.TextUtils.TruncateAt.END);
        l.setMaxWidth(Ui.dp(this, 96));
        l.setPadding(0, Ui.dp(this, 6), 0, 0);
        tile.addView(l);
        tile.setBackground(Ui.ripple(this, null, Ui.dp(this, 12)));
        return tile;
    }

    // ---- my templates ------------------------------------------------------------------------

    private org.json.JSONArray myTemplates() {
        try { return new org.json.JSONArray(prefs.getString("myTpl", "[]")); } catch (Exception e) { return new org.json.JSONArray(); }
    }

    static org.json.JSONObject lookJson(String name, int bg, float sp, float st, int col, int mg, int paper) {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("n", name).put("bg", bg).put("s", (double) sp).put("t", (double) st).put("c", col).put("m", mg).put("p", paper);
        } catch (Exception ignored) { }
        return o;
    }

    static Notebook.Page lookOf(org.json.JSONObject t) {
        Notebook.Page pg = new Notebook.Page(t.optInt("bg", Notebook.BG_DOTS));
        pg.spacing = (float) t.optDouble("s", 1);
        pg.strength = (float) t.optDouble("t", 1);
        pg.lineColor = t.optInt("c", 0);
        pg.margins = t.optInt("m", 0);
        return pg;
    }

    private void applyTemplate(org.json.JSONObject t, boolean everyPage) {
        Notebook.Page look = lookOf(t);
        if (everyPage) engine.pageOp("bgAll", 0, look.bg); else engine.setBackground(look.bg);
        engine.setLook(everyPage, look.spacing, look.strength, look.lineColor, look.margins);
        if (t.has("p") && state != null && t.optInt("p") != state.paper) engine.setPaper(t.optInt("p"));
        toast("“" + t.optString("n") + "”" + (everyPage ? " on every page" : ""));
    }

    private void saveTemplate(int bg, float sp, float st, int col, int mg) {
        EditText et = new EditText(this);
        et.setHint("e.g. Site notes, College ruled");
        et.setSingleLine(true);
        FrameLayout w = new FrameLayout(this);
        int pd = Ui.dp(this, 22);
        w.setPadding(pd, Ui.dp(this, 8), pd, 0);
        w.addView(et);
        new AlertDialog.Builder(this).setTitle("Name this template").setView(w)
                .setPositiveButton("Save", (d, x) -> {
                    String n = et.getText().toString().trim();
                    if (n.isEmpty()) n = Notebook.BG_NAMES[Math.max(0, Math.min(Notebook.BG_NAMES.length - 1, bg))] + " (mine)";
                    org.json.JSONArray a = myTemplates();
                    a.put(lookJson(n, bg, sp, st, col, mg, state != null ? state.paper : 0));
                    prefs.edit().putString("myTpl", a.toString()).apply();
                    toast("Saved · it's under My templates in Page style");
                })
                .setNegativeButton("Cancel", null).show();
        et.requestFocus();
    }

    private Bitmap templateBitmap(int bg, int paper, Notebook.Page look) {
        int w = Ui.dp(this, 92), h = Ui.dp(this, 130);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float k = w / Notebook.PW * 2.2f; // zoomed in so the pattern reads at tile size
        c.scale(k, k);
        Notebook nb = new Notebook();
        nb.paper = paper;
        Notebook.Page pg = new Notebook.Page(bg);
        if (look != null) pg.copyStyle(look);
        else if (state != null && bg == state.bg) { pg.spacing = state.spacing; pg.strength = state.strength; pg.lineColor = state.lineColor; pg.margins = state.margins; }
        Render.paper(c, nb, pg);
        return b;
    }

    // ---- share panel -------------------------------------------------------------------------

    private void showShare(View anchor) {
        Ui.Popover p = newPop(360).title("Export & share");
        p.add(Ui.actionRow(this, R.drawable.ic_copy, "Whole notebook as PDF", "Vector ink, every page", v -> { pop.dismiss(); export(-1, false); }));
        p.add(Ui.actionRow(this, R.drawable.ic_template, "This page as PDF", null, v -> { pop.dismiss(); export(state == null ? 0 : state.page, false); }));
        p.add(Ui.actionRow(this, R.drawable.ic_image, "This page as image", "PNG, 2000 px wide", v -> { pop.dismiss(); export(state == null ? 0 : state.page, true); }));
        p.add(Ui.actionRow(this, R.drawable.ic_nib_archive, "Export as .nib", "Editable Nib notebook archive", v -> { pop.dismiss(); exportNib(); }));
        p.add(Ui.actionRow(this, R.drawable.ic_import, "Save PDF to…", "Pick a folder, e.g. Downloads or Drive", v -> { pop.dismiss(); saveTo(); }));
        p.show(anchor);
    }

    private void exportNib() {
        commitTextEdit();
        toast("Packaging notebook…");
        final File dir = engine.dir;
        final String title = state != null && state.title != null && !state.title.trim().isEmpty()
                ? state.title : (cur != null && cur.title != null ? cur.title : "Notebook");
        engine.flush(() -> bg.execute(() -> {
            try {
                File f = Backup.exportNotebook(this, dir, title);
                ui.post(() -> Exporter.share(this, f, "application/octet-stream"));
            } catch (Exception e) {
                Log.e(Gl.TAG, "export nib", e);
                ui.post(() -> toast("Export failed"));
            }
        }));
    }

    private void saveTo() {
        toast("Preparing PDF…");
        final File dir = engine.dir;
        engine.snapshot(nb -> bg.execute(() -> {
            try {
                File f = Exporter.pdf(this, nb, dir, -1);
                ui.post(() -> {
                    pendingSave = f;
                    startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/pdf").putExtra(Intent.EXTRA_TITLE, f.getName()), REQ_SAVE_PDF);
                });
            } catch (Exception e) {
                Log.e(Gl.TAG, "export", e);
                ui.post(() -> toast("Export failed"));
            }
        }));
    }

    private void export(int page, boolean png) {
        toast("Exporting…");
        final File dir = engine.dir;
        engine.snapshot(nb -> bg.execute(() -> {
            try {
                File f = png ? Exporter.png(this, nb, dir, page) : Exporter.pdf(this, nb, dir, page);
                ui.post(() -> Exporter.share(this, f, png ? "image/png" : "application/pdf"));
            } catch (Exception e) {
                Log.e(Gl.TAG, "export", e);
                ui.post(() -> toast("Export failed"));
            }
        }));
    }

    // ---- settings sheet ----------------------------------------------------------------------

    private FrameLayout sheet;

    private void showSettings() {
        commitTextEdit();
        if (sheet != null) return;
        sheet = new FrameLayout(this);
        sheet.setBackgroundColor(0x55000000);
        sheet.setOnClickListener(v -> closeSettings());
        sheet.setElevation(Ui.dp(this, 20)); // above the app bar, dock and page chip
        ScrollView sv = new ScrollView(this);
        GradientDrawable sheetBg = Ui.round(Ui.color(this, R.color.surface), Ui.dp(this, 16));
        sheetBg.setStroke(Ui.hairline(this), Ui.color(this, R.color.outline));
        sv.setBackground(sheetBg);
        sv.setElevation(Ui.dp(this, 12));
        sv.setClickable(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pd = Ui.dp(this, 24);
        col.setPadding(pd, pd, pd, pd);
        sv.addView(col);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(this, "Settings", 20, Ui.color(this, R.color.on_surface), Ui.bold()), new LinearLayout.LayoutParams(0, -2, 1f));
        Ui.IconButton close = icon(R.drawable.ic_close, "Close");
        close.setOnClickListener(v -> closeSettings());
        head.addView(close);
        col.addView(head);

        col.addView(sectionLabel("Writing"));
        col.addView(Ui.text(this, "Stroke prediction", 14.5f, Ui.color(this, R.color.on_surface), null));
        TextView ph = Ui.text(this, "Draws a short guess ahead of the pen tip to hide the last bit of lag", 12.5f,
                Ui.color(this, R.color.on_surface_variant), null);
        ph.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 10));
        col.addView(ph);
        col.addView(Ui.segmented(this, new String[]{"Off", "Gentle", "Strong"}, prediction, i -> { prediction = i; saveOptions(); }));
        col.addView(Ui.switchRow(this, "Shape snapping", "Hold the pen still at the end of a line or shape to straighten it, then keep moving to stretch or resize it",
                shapeSnap, v -> { shapeSnap = v; saveOptions(); }));
        col.addView(Ui.switchRow(this, "Write with finger", "Fingers write like the pen; two fingers scroll and zoom. Also in the tool bar (hand button)",
                fingerDraw, v -> { fingerDraw = v; saveOptions(); }));
        col.addView(Ui.switchRow(this, "Hide tools while writing", "The floating bars fade while the pen is on the page",
                autoHide, v -> { autoHide = v; if (!v) showChrome.run(); prefs.edit().putBoolean("autoHide", v).apply(); }));
        col.addView(Ui.text(this, "Maximum zoom", 14.5f, Ui.color(this, R.color.on_surface), null));
        TextView zh = Ui.text(this, "How far pinching can zoom in · the zoom chip's lock freezes it", 12.5f,
                Ui.color(this, R.color.on_surface_variant), null);
        zh.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 10));
        col.addView(zh);
        int[] zooms = {400, 600, 800};
        int zsel = maxZoomPct <= 400 ? 0 : maxZoomPct <= 600 ? 1 : 2;
        col.addView(Ui.segmented(this, new String[]{"400%", "600%", "800%"}, zsel, i -> {
            maxZoomPct = zooms[i];
            prefs.edit().putInt("maxZoom", maxZoomPct).apply();
            for (Doc d : docs) if (d.engine != null) d.engine.setZoomLimit(maxZoomPct / 100f);
        }));
        col.addView(Ui.switchRow(this, "Reopen where I left off", "Starting Nib brings back the notebooks and page you were on",
                prefs.getBoolean("reopenLast", true), v -> prefs.edit().putBoolean("reopenLast", v).apply()));
        col.addView(sectionLabel("Tool bar"));
        col.addView(Ui.segmented(this, new String[]{"Bottom", "Top", "Left", "Right"}, dock, this::setDock));
        TextView th = Ui.text(this, "Or drag the grip at the end of the tool bar to any edge", 12.5f, Ui.color(this, R.color.on_surface_variant), null);
        th.setPadding(0, Ui.dp(this, 6), 0, 0);
        col.addView(th);
        col.addView(sectionLabel("Appearance"));
        col.addView(Ui.actionRow(this, R.drawable.ic_palette, "Theme", "Light or dark, accent colour, dark style", v -> ThemeSheet.show(this, this::recreate)));
        col.addView(sectionLabel("Pen"));
        col.addView(Ui.switchRow(this, "Pen buttons", "First button: eraser · second button: lasso · press again to go back",
                penButton, v -> { penButton = v; saveOptions(); }));
        col.addView(Ui.switchRow(this, "Taper strokes without pressure", "With a finger (or a stylus that has no pressure) the fountain pen starts and ends thin, like a real nib",
                taper, v -> { taper = v; saveOptions(); }));
        col.addView(Ui.switchRow(this, "Partial eraser", "Erase just the part of a stroke you touch",
                partialErase, v -> { partialErase = v; saveOptions(); }));
        col.addView(Ui.switchRow(this, "Hold pen to erase", "Rest the pen where it lands for half a second: it erases until you lift",
                holdErase, v -> { holdErase = v; saveOptions(); }));
        col.addView(Ui.switchRow(this, "Grey out, delete on lift", "Erased ink turns grey and goes when the pen lifts",
                erasePreview, v -> { erasePreview = v; saveOptions(); }));
        col.addView(sectionLabel("Gestures"));
        TextView gest = Ui.text(this, "Three-finger tap  ·  redo\nLong-press undo  ·  history slider\n"
                + "One finger  ·  scroll      Pinch  ·  zoom\nDouble-tap  ·  fit page width", 14,
                Ui.color(this, R.color.on_surface_variant), null);
        gest.setLineSpacing(Ui.dp(this, 6), 1f);
        col.addView(gest);
        col.addView(sectionLabel("Handwriting search"));
        col.addView(Ui.switchRow(this, "Search my handwriting", "Reads your writing on the tablet so the notes-screen search finds it. "
                        + "Models download once (needs internet), then it works offline.",
                Handwriting.enabled(this), v -> { prefs.edit().putBoolean("hwSearch", v).apply(); if (v) Handwriting.indexAll(this, null); }));
        for (int i = 0; i < Handwriting.LANGS.length; i++) {
            final int li = i;
            col.addView(Ui.switchRow(this, Handwriting.LANG_NAMES[i], Handwriting.LANG_NOTES[i] + " · also helps Beautify",
                    Handwriting.langOn(this, i), v -> { prefs.edit().putBoolean("hwLang" + li, v).apply(); Handwriting.reset(this); }));
        }
        String hs = Handwriting.status();
        if (!hs.isEmpty()) {
            TextView st = Ui.text(this, hs, 12.5f, Ui.color(this, R.color.on_surface_variant), null);
            st.setPadding(0, Ui.dp(this, 4), 0, 0);
            col.addView(st);
        }
        col.addView(sectionLabel("Writing while charging"));
        TextView chg = Ui.text(this, "If strokes come out uneven or with gaps while the tablet is plugged in, that is electrical "
                + "noise from the charger reaching the pen sensor. It affects every app, not just Nib. Use the original "
                + "charger in a wall socket (a grounded three-pin socket is best), unplug the laptop's own charger if the "
                + "tablet charges from a laptop, try another cable, or write unplugged.", 13,
                Ui.color(this, R.color.on_surface_variant), null);
        chg.setLineSpacing(Ui.dp(this, 3), 1f);
        col.addView(chg);
        col.addView(sectionLabel("Diagnostics"));
        col.addView(Ui.switchRow(this, "Latency overlay", "Shows front-buffer state and input-to-pixel timings",
                hud, v -> { hud = v; if (!hud) hudView.setVisibility(View.GONE); saveOptions(); }));

        // a side sheet on tablets; on a phone it takes the width, less the margins
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.min(Ui.dp(this, 420), root.getWidth() - Ui.dp(this, 24)), -1, Gravity.END);
        lp.setMargins(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        sheet.addView(sv, lp);
        root.addView(sheet, new FrameLayout.LayoutParams(-1, -1));
        sheet.setAlpha(0f);
        sheet.animate().alpha(1f).setDuration(150).start();
        sv.setTranslationX(Ui.dp(this, 60));
        sv.animate().translationX(0).setDuration(200).start();
    }

    private TextView sectionLabel(String s) {
        TextView t = Ui.text(this, s, 13, Ui.color(this, R.color.on_surface_variant), Ui.medium());
        t.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 8));
        return t;
    }

    private void saveOptions() {
        prefs.edit().putBoolean("hlFlat", hlFlat).putInt("hlStraight", hlStraight).putBoolean("holdErase", holdErase)
                .putBoolean("erasePreview", erasePreview).apply();
        prefs.edit().putInt("predLevel", prediction).putBoolean("finger", fingerDraw).putBoolean("hud", hud)
                .putBoolean("partialErase", partialErase).putBoolean("shapeSnap", shapeSnap)
                .putBoolean("penButton", penButton).putBoolean("taper", taper).apply();
        pushOptions();
        if (fingerB != null) fingerB.set(fingerDraw, 0, 0);
    }

    private void closeSettings() {
        if (sheet == null) return;
        final View s = sheet;
        sheet = null;
        s.animate().alpha(0f).setDuration(140).withEndAction(() -> root.removeView(s)).start();
    }

    // ---- bottom: page chip, paste, toast, hud ----------------------------------------------

    private void buildBottom() {
        pasteChip = Ui.text(this, "Paste", 14, Ui.color(this, R.color.on_accent), Ui.medium());
        pasteChip.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_copy, 0, 0, 0);
        pasteChip.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, R.color.on_accent)));
        pasteChip.setCompoundDrawablePadding(Ui.dp(this, 8));
        pasteChip.setGravity(Gravity.CENTER);
        pasteChip.setBackground(Ui.ripple(this, Ui.round(Ui.color(this, R.color.accent), Ui.dp(this, 10)), Ui.dp(this, 10)));
        pasteChip.setPadding(Ui.dp(this, 16), Ui.dp(this, 9), Ui.dp(this, 18), Ui.dp(this, 9));
        pasteChip.setElevation(Ui.dp(this, 4));
        pasteChip.setVisibility(View.GONE);
        pasteChip.setOnClickListener(v -> engine.paste());
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        cl.bottomMargin = Ui.dp(this, EDGE_DP + TOOLCAP_DP + 16); // above the tool capsule
        cl.rightMargin = Ui.dp(this, EDGE_DP);
        root.addView(pasteChip, cl);

        toastView = Ui.text(this, "", 14, Ui.color(this, R.color.bg), Ui.medium());
        toastView.setBackground(Ui.round(Ui.color(this, R.color.on_surface), Ui.dp(this, 10)));
        toastView.setPadding(Ui.dp(this, 16), Ui.dp(this, 9), Ui.dp(this, 16), Ui.dp(this, 9));
        toastView.setElevation(Ui.dp(this, 30));
        toastView.setAlpha(0f);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        tl.bottomMargin = Ui.dp(this, EDGE_DP + TOOLCAP_DP + 18);
        root.addView(toastView, tl);

        hudView = Ui.text(this, "", 12, 0xffe8ffe8, Typeface.MONOSPACE);
        hudView.setLineSpacing(Ui.dp(this, 3), 1f);
        hudView.setBackground(Ui.round(0xd0101214, Ui.dp(this, 10)));
        hudView.setPadding(Ui.dp(this, 12), Ui.dp(this, 9), Ui.dp(this, 12), Ui.dp(this, 9));
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        hl.leftMargin = Ui.dp(this, 16);
        hl.topMargin = Ui.dp(this, EDGE_DP + CAP_DP + 10); // under the title capsule
        root.addView(hudView, hl);
        hudView.setVisibility(View.GONE);
    }

    private void updatePaste() {
        if (pasteChip == null) return;
        boolean show = tool == Engine.TOOL_LASSO && state != null && state.hasClipboard;
        pasteChip.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // ---- history slider ------------------------------------------------------------------

    private void showHistory(View anchor) {
        if (state == null || state.undoCount + state.redoCount == 0) {
            toast("Nothing to undo yet");
            return;
        }
        int total = state.undoCount + state.redoCount;
        Ui.Popover p = newPop(360).title("History");
        TextView label = Ui.text(this, historyLabel(state.undoCount, total), 13, Ui.color(this, R.color.on_surface_variant), null);
        label.setPadding(0, 0, 0, Ui.dp(this, 10));
        p.add(label);
        SeekBar sb = Ui.slider(this, total, state.undoCount, v -> {
            label.setText(historyLabel(v, total));
            engine.historyTo(v);
        });
        p.add(sb);
        p.show(anchor);
    }

    private static String historyLabel(int v, int total) {
        return v == total ? "Latest version · drag left to step back through your edits"
                : (total - v) + " step" + (total - v == 1 ? "" : "s") + " back · drag right to return";
    }

    // ---- page overview -----------------------------------------------------------------------

    private FrameLayout pagesPanel;

    private void showPages() {
        commitTextEdit();
        if (pagesPanel != null) root.removeView(pagesPanel);
        pagesPanel = new FrameLayout(this);
        pagesPanel.setBackgroundColor(Ui.color(this, R.color.bg));
        pagesPanel.setClickable(true);
        pagesPanel.setElevation(Ui.dp(this, 20)); // above the app bar, dock and page chip
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        boolean narrow = root.getWidth() < Ui.dp(this, 520);
        int side = Ui.dp(this, narrow ? 16 : 28);
        android.view.WindowInsets wi = root.getRootWindowInsets(); // full screen: keep clear of the status and nav bars
        android.graphics.Insets bars = wi == null ? android.graphics.Insets.NONE : wi.getInsets(android.view.WindowInsets.Type.systemBars());
        col.setPadding(side, Math.max(Ui.dp(this, narrow ? 12 : 28), bars.top + Ui.dp(this, 8)), side,
                Math.max(Ui.dp(this, narrow ? 12 : 28), bars.bottom + Ui.dp(this, 8)));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(this, "Pages", 22, Ui.color(this, R.color.on_surface), Ui.bold()), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView hint = Ui.text(this, narrow ? "Hold a page for options" : "Long-press a page for options", 13, Ui.color(this, R.color.on_surface_variant), null);
        hint.setPadding(0, 0, Ui.dp(this, 8), 0);
        hint.setSingleLine(true);
        hint.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(hint, narrow ? new LinearLayout.LayoutParams(0, -2, 1.6f) : new LinearLayout.LayoutParams(-2, -2));
        Ui.IconButton close = icon(R.drawable.ic_close, "Close");
        close.setOnClickListener(v -> closePages());
        head.addView(close);
        col.addView(head);
        Ui.Grid grid = new Ui.Grid(this, 150, 18);
        LinearLayout marks = new LinearLayout(this);
        marks.setOrientation(LinearLayout.VERTICAL);
        marks.setVisibility(View.GONE);
        LinearLayout tabs = Ui.segmented(this, new String[]{"All pages", "Bookmarks"}, 0, i -> {
            grid.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
            marks.setVisibility(i == 1 ? View.VISIBLE : View.GONE);
            if (i == 1) fillMarks(marks, grid); else fillPages(grid);
        });
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(Math.min(Ui.dp(this, 300), root.getWidth() - 2 * side), -2);
        tl.topMargin = Ui.dp(this, 16);
        col.addView(tabs, tl);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(grid);
        body.addView(marks);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, -2);
        gl.topMargin = Ui.dp(this, 20);
        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        col.addView(sv, gl);
        pagesPanel.addView(col, new FrameLayout.LayoutParams(-1, -1));
        root.addView(pagesPanel, new FrameLayout.LayoutParams(-1, -1));
        pagesPanel.setAlpha(0f);
        pagesPanel.animate().alpha(1f).setDuration(150).start();
        fillPages(grid);
    }

    /** The outline: bookmarked pages in order, with their names. */
    private void fillMarks(LinearLayout list, Ui.Grid grid) {
        list.removeAllViews();
        int n = 0;
        if (state != null && state.marks != null) for (int i = 0; i < state.marks.length; i++) {
            if (state.marks[i] == null) continue;
            n++;
            final int idx = i;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
            row.setBackground(Ui.ripple(this, null, Ui.dp(this, 10)));
            ImageView rib = new ImageView(this);
            rib.setImageResource(R.drawable.ic_bookmark_filled);
            rib.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, R.color.accent)));
            row.addView(rib, new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20)));
            String title = state.marks[i].isEmpty() ? "Page " + (i + 1) : state.marks[i];
            TextView t = Ui.text(this, title, 15, Ui.color(this, R.color.on_surface), null);
            t.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 8), 0);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(Ui.text(this, "p. " + (i + 1), 13, Ui.color(this, R.color.on_surface_variant), null));
            row.setOnClickListener(v -> { engine.gotoPage(idx); closePages(); });
            row.setOnLongClickListener(v -> { nameBookmark(idx, () -> fillMarks(list, grid)); return true; });
            list.addView(row, new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)));
            View hair = new View(this);
            hair.setBackgroundColor(Ui.color(this, R.color.outline));
            list.addView(hair, new LinearLayout.LayoutParams(-1, Ui.hairline(this)));
        }
        if (n == 0) {
            TextView e = Ui.text(this, "No bookmarks yet. Tap the ribbon at the top-right to bookmark the page you're on; long-press it to give it a name.",
                    14, Ui.color(this, R.color.on_surface_variant), null);
            e.setPadding(Ui.dp(this, 4), Ui.dp(this, 20), Ui.dp(this, 4), 0);
            list.addView(e);
        }
    }

    private void fillPages(Ui.Grid grid) {
        grid.removeAllViews();
        int n = state == null ? 1 : state.pages, cur = state == null ? 0 : state.page;
        for (int i = 0; i < n; i++) {
            final int idx = i;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            Ui.Aspect frame = new Ui.Aspect(this, Notebook.PH / Notebook.PW);
            GradientDrawable g = Ui.round(0xffffffff, Ui.dp(this, 6));
            g.setStroke(i == cur ? Ui.dp(this, 2) : Ui.hairline(this), Ui.color(this, i == cur ? R.color.accent : R.color.outline));
            frame.setBackground(g);
            frame.setClipToOutline(true);
            frame.setElevation(Ui.dp(this, 1));
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            frame.addView(img, new FrameLayout.LayoutParams(-1, -1));
            View hit = new View(this);
            hit.setBackground(Ui.ripple(this, null, Ui.dp(this, 10)));
            frame.addView(hit, new FrameLayout.LayoutParams(-1, -1));
            hit.setOnClickListener(v -> { engine.gotoPage(idx); closePages(); });
            hit.setOnLongClickListener(v -> { pageMenu(v, idx, grid); return true; });
            if (state != null && state.marks != null && i < state.marks.length && state.marks[i] != null) {
                ImageView rib = new ImageView(this);
                rib.setImageResource(R.drawable.ic_bookmark_filled);
                rib.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, R.color.accent)));
                FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22), Gravity.TOP | Gravity.END);
                rl.topMargin = 0;
                rl.rightMargin = Ui.dp(this, 8);
                frame.addView(rib, rl);
            }
            card.addView(frame, new LinearLayout.LayoutParams(-1, -2));
            String mk = state != null && state.marks != null && i < state.marks.length ? state.marks[i] : null;
            TextView num = Ui.text(this, (i + 1) + (mk != null && !mk.isEmpty() ? " · " + mk : ""), 13,
                    Ui.color(this, i == cur ? R.color.accent : R.color.on_surface_variant), Ui.medium());
            num.setSingleLine(true);
            num.setEllipsize(android.text.TextUtils.TruncateAt.END);
            num.setPadding(0, Ui.dp(this, 8), 0, 0);
            card.addView(num);
            grid.addView(card);
            engine.pageThumb(i, 320, img::setImageBitmap);
        }
        // "add page" tile
        LinearLayout addCard = new LinearLayout(this);
        addCard.setOrientation(LinearLayout.VERTICAL);
        Ui.Aspect af = new Ui.Aspect(this, Notebook.PH / Notebook.PW);
        GradientDrawable ag = Ui.round(Ui.color(this, R.color.surface_variant), Ui.dp(this, 6));
        ag.setStroke(Ui.hairline(this), Ui.color(this, R.color.outline));
        af.setBackground(Ui.ripple(this, ag, Ui.dp(this, 6)));
        ImageView plus = new ImageView(this);
        plus.setImageResource(R.drawable.ic_plus);
        plus.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, R.color.on_surface_variant)));
        af.addView(plus, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        af.setOnClickListener(v -> showAddPage(Engine.POS_LAST, () ->
                ui.postDelayed(() -> { if (pagesPanel != null) fillPages(grid); }, 150)));
        addCard.addView(af, new LinearLayout.LayoutParams(-1, -2));
        grid.addView(addCard);
    }

    private void pageMenu(View anchor, int i, Ui.Grid grid) {
        PopupMenu pm = new PopupMenu(this, anchor);
        android.view.Menu m = pm.getMenu();
        m.add(0, 1, 0, "Insert blank page after");
        m.add(0, 2, 1, "Duplicate");
        m.add(0, 3, 2, "Move earlier").setEnabled(i > 0);
        m.add(0, 4, 3, "Move later").setEnabled(state != null && i < state.pages - 1);
        boolean marked = state != null && state.marks != null && i < state.marks.length && state.marks[i] != null;
        m.add(0, 7, 4, marked ? "Rename or remove bookmark…" : "Bookmark…");
        m.add(0, 6, 4, "Clear page");
        m.add(0, 5, 5, "Delete page").setEnabled(state != null && state.pages > 1);
        pm.setOnMenuItemClickListener(it -> {
            int id = it.getItemId();
            if (id == 1) engine.pageOp("insert", i, 0);
            else if (id == 2) engine.pageOp("duplicate", i, 0);
            else if (id == 3) engine.pageOp("move", i, i - 1);
            else if (id == 4) engine.pageOp("move", i, i + 1);
            else if (id == 5) engine.pageOp("delete", i, 0);
            else if (id == 6) engine.pageOp("clear", i, 0);
            else if (id == 7) { nameBookmark(i, () -> { if (pagesPanel != null) fillPages(grid); }); return true; }
            else return false;
            ui.postDelayed(() -> { if (pagesPanel != null) fillPages(grid); }, 150);
            return true;
        });
        pm.show();
    }

    private void closePages() {
        if (pagesPanel == null) return;
        final View p = pagesPanel;
        pagesPanel = null;
        p.animate().alpha(0f).setDuration(120).withEndAction(() -> root.removeView(p)).start();
    }

    // ---- selection -------------------------------------------------------------------------

    /** Draws the selection frame; never takes touches (the engine handles drags). */
    private static final class SelectionView extends View {
        RectF r;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        SelectionView(EditorActivity a) { super(a); }
        float[] handles; // a lone shape's stretch handles (view px)
        boolean line;
        void set(RectF nr) { r = nr; if (nr == null) handles = null; invalidate(); }
        int box = -1; // handles from here on are the scaling box's corners
        void setHandles(float[] h, boolean ln, int bx) { handles = h; line = ln; box = bx; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            if (r == null) return;
            float d = getResources().getDisplayMetrics().density;
            int acc = Ui.color(getContext(), R.color.accent);
            if (handles != null) { // a shape: knobs on its own corners / ends, plus a light box whose corners scale it
                if (box >= 0 && box + 7 < handles.length) {
                    RectF bx = new RectF(handles[box], handles[box + 1], handles[box + 4], handles[box + 5]);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(1.2f * d);
                    p.setColor((acc & 0x00ffffff) | 0x99000000);
                    p.setPathEffect(new DashPathEffect(new float[]{5 * d, 4 * d}, 0));
                    c.drawRect(bx, p);
                    p.setPathEffect(null);
                    float hs = 6.5f * d;
                    for (int i = box; i + 1 < handles.length; i += 2) {
                        RectF q = new RectF(handles[i] - hs, handles[i + 1] - hs, handles[i] + hs, handles[i + 1] + hs);
                        p.setStyle(Paint.Style.FILL);
                        p.setColor(0xffffffff);
                        c.drawRoundRect(q, 2.5f * d, 2.5f * d, p);
                        p.setStyle(Paint.Style.STROKE);
                        p.setStrokeWidth(2f * d);
                        p.setColor(acc);
                        c.drawRoundRect(q, 2.5f * d, 2.5f * d, p);
                    }
                }
                int own = box >= 0 ? box : handles.length;
                for (int i = 0; i + 1 < own; i += 2) {
                    float rad = 9 * d;
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(0xffffffff);
                    c.drawCircle(handles[i], handles[i + 1], rad, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2.2f * d);
                    p.setColor(acc);
                    c.drawCircle(handles[i], handles[i + 1], rad, p);
                }
                return;
            }
            RectF b = new RectF(r.left - 8 * d, r.top - 8 * d, r.right + 8 * d, r.bottom + 8 * d);
            p.setStyle(Paint.Style.FILL);
            p.setColor((acc & 0x00ffffff) | 0x10000000);
            c.drawRoundRect(b, 8 * d, 8 * d, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f * d);
            p.setColor(acc);
            p.setPathEffect(new DashPathEffect(new float[]{6 * d, 4 * d}, 0));
            c.drawRoundRect(b, 8 * d, 8 * d, p);
            p.setPathEffect(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xffffffff);
            c.drawCircle(b.right, b.bottom, 10 * d, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.5f * d);
            p.setColor(acc);
            c.drawCircle(b.right, b.bottom, 10 * d, p);
        }
        @Override public boolean onTouchEvent(MotionEvent e) { return false; }
    }

    private void buildSelectionBar() {
        selBar = new LinearLayout(this);
        selBar.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pill(selBar, this);
        selBar.setVisibility(View.GONE);
        selBar.addView(selAction(R.drawable.ic_copy, "Copy", v -> engine.selCopy()));
        selBar.addView(selAction(R.drawable.ic_cut, "Cut", v -> engine.selCut()));
        selBar.addView(selAction(R.drawable.ic_duplicate, "Duplicate", v -> engine.selDuplicate()));
        selBar.addView(selAction(R.drawable.ic_text, "To text", v -> engine.selInk(ink -> showConvert(v, ink))));
        editTextB = selAction(R.drawable.ic_text, "Edit text", v -> engine.editSelectedText());
        editTextB.setVisibility(View.GONE);
        selBar.addView(editTextB, 0);
        selBar.addView(selAction(R.drawable.ic_sparkle, "Beautify", v -> beautify(false)));
        toLayerB = selAction(R.drawable.ic_layers, "Layer", this::pickLayerForSelection);
        toLayerB.setVisibility(View.GONE);
        selBar.addView(toLayerB);
        selBar.addView(selAction(R.drawable.ic_trash, "Delete", v -> engine.selDelete()));
        root.addView(selBar, new FrameLayout.LayoutParams(-2, -2));
    }

    /**
     * Convert to text: reads the lassoed writing in the chosen language, shows what it read (editable, with other
     * readings to pick from), then swaps the ink for a text box. Undo brings the handwriting back.
     */
    private void showConvert(View anchor, java.util.ArrayList<Stroke> ink) {
        if (ink.isEmpty()) { toast("Lasso some handwriting first"); return; }
        Ui.Popover p = newPop(440).title("Convert to text");
        int choice = langChoice();
        TextView status = Ui.text(this, "Reading…", 13, Ui.color(this, R.color.on_surface_variant), null);
        EditText out = new EditText(this);
        out.setTextSize(17);
        out.setTextColor(Ui.color(this, R.color.on_surface));
        out.setMinLines(2);
        out.setMaxLines(8);
        out.setGravity(Gravity.TOP | Gravity.START);
        out.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        out.setBackground(Ui.round(Ui.color(this, R.color.surface_variant), Ui.dp(this, 12)));
        LinearLayout alts = new LinearLayout(this);
        float[] lineH = {0};
        Runnable[] read = new Runnable[1];
        int[] cur = {Handwriting.convertLang(this)};
        read[0] = () -> {
            status.setText("Reading…");
            out.setText("");
            alts.removeAllViews();
            Handwriting.convert(ink, cur[0], status::setText, r -> {
                if (pop != p) return; // closed meanwhile
                if (r.error != null) { status.setText(r.error); return; }
                status.setText((cur[0] == Handwriting.AUTO && r.lang >= 0 ? "Detected " + Handwriting.LANG_NAMES[r.lang] + " · " : "")
                        + "Check it, fix anything, then replace the handwriting.");
                out.setText(r.text);
                out.setSelection(out.length());
                lineH[0] = r.lineHeight;
                for (String a : r.alternatives) {
                    TextView chip = Ui.text(this, a, 14, Ui.color(this, R.color.on_surface), Ui.medium());
                    chip.setPadding(Ui.dp(this, 12), Ui.dp(this, 7), Ui.dp(this, 12), Ui.dp(this, 7));
                    chip.setBackground(Ui.ripple(this, Ui.round(Ui.color(this, R.color.surface_variant), Ui.dp(this, 16)), Ui.dp(this, 16)));
                    chip.setOnClickListener(v -> { out.setText(a); out.setSelection(out.length()); });
                    LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
                    cl.rightMargin = Ui.dp(this, 8);
                    alts.addView(chip, cl);
                }
            });
        };
        p.add(Ui.segmented(this, LANG_CHOICES, choice, i -> { setLangChoice(i); cur[0] = Handwriting.convertLang(this); read[0].run(); }));
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(-1, -2);
        ol.topMargin = Ui.dp(this, 12);
        out.setLayoutParams(ol);
        p.add(out);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(alts);
        hs.setPadding(0, Ui.dp(this, 8), 0, 0);
        p.add(hs);
        status.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 12));
        p.add(status);
        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.END);
        TextView copy = Ui.button(this, "Copy", 0, false);
        copy.setOnClickListener(v -> {
            ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("Nib", out.getText()));
            toast("Copied");
        });
        TextView rep = Ui.button(this, "Replace handwriting", 0, true);
        rep.setOnClickListener(v -> {
            String t = out.getText().toString().trim();
            if (t.isEmpty()) return;
            pop.dismiss();
            engine.selToText(t, lineH[0]);
            toast("Converted · undo to get the handwriting back");
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, Ui.dp(this, 40));
        bl.leftMargin = Ui.dp(this, 8);
        btns.addView(copy, new LinearLayout.LayoutParams(-2, Ui.dp(this, 40)));
        btns.addView(rep, bl);
        p.add(btns);
        p.show(anchor);
        read[0].run();
    }

    /**
     * Lasso & handwriting panel (tap the lasso tool again, or ⋯ → Handwriting tools): everything about turning writing
     * into text and tidying it, in one place.
     */
    /** Language choices for converting: Auto first, then the four languages (stored as convertAuto + convertLang). */
    private static final String[] LANG_CHOICES = {"Auto", "English", "ਪੰਜਾਬੀ", "हिन्दी", "عربي"};

    private int langChoice() { return prefs.getBoolean("convertAuto", false) ? 0 : 1 + Math.min(prefs.getInt("convertLang", 0), 3); }

    private void setLangChoice(int i) {
        if (i == 0) prefs.edit().putBoolean("convertAuto", true).apply();
        else prefs.edit().putBoolean("convertAuto", false).putInt("convertLang", i - 1).apply();
    }

    private void showLassoPanel(View anchor) {
        Ui.Popover p = newPop(420).title("Lasso & handwriting");
        TextView hint = Ui.text(this, "Circle writing, then use To text or Beautify in the bar that appears.", 13,
                Ui.color(this, R.color.on_surface_variant), null);
        hint.setPadding(0, 0, 0, Ui.dp(this, 6));
        p.add(hint);
        p.section("Handwriting to text");
        String[] ln = {"English", "Punjabi", "Hindi", "Arabic"};
        android.widget.LinearLayout langSeg = Ui.segmented(this, LANG_CHOICES, langChoice(), this::setLangChoice);
        p.add(langSeg);
        p.add(Ui.switchRow(this, "Convert as I write", "Writing turns into text after a short pause · undo brings the ink back",
                prefs.getBoolean("autoText", false), v -> {
                    prefs.edit().putBoolean("autoText", v).apply();
                    int c = Handwriting.convertLang(this);
                    toast(v ? "Writing now turns into " + (c < 0 ? "text (language detected)" : ln[c] + " text") : "Handwriting stays as ink");
                }));
        p.section("Beautify");
        TextView bh = Ui.text(this, "Keeps your handwriting, makes it neater · 1 gentle, 5 strong", 13,
                Ui.color(this, R.color.on_surface_variant), null);
        bh.setPadding(0, 0, 0, Ui.dp(this, 8));
        p.add(bh);
        p.add(Ui.segmented(this, new String[]{"1", "2", "3", "4", "5"}, prefs.getInt("beautify", 3) - 1, i -> prefs.edit().putInt("beautify", i + 1).apply()));
        p.add(Ui.actionRow(this, R.drawable.ic_sparkle, "Beautify this page", "Straighter lines, even size and spacing", v -> { pop.dismiss(); beautify(true); }));
        addMoveRow(p);
        p.show(anchor);
    }

    private void beautify(boolean wholePage) {
        toast(wholePage ? "Beautifying the page…" : "Beautifying…");
        engine.beautify(wholePage, prefs.getInt("beautify", 3), Handwriting.reader(this), this::toast);
    }

    private View selAction(int icon, String label, View.OnClickListener l) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 2));
        b.setBackground(Ui.ripple(this, null, Ui.dp(this, 14)));
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, R.color.on_surface)));
        b.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));
        TextView t = Ui.text(this, label, 11.5f, Ui.color(this, R.color.on_surface_variant), Ui.medium());
        t.setPadding(0, Ui.dp(this, 3), 0, 0);
        b.addView(t, new LinearLayout.LayoutParams(-2, -2)); // vertical default is match_parent → squeezed to icon width
        b.setOnClickListener(l);
        b.setMinimumWidth(Ui.dp(this, 72)); // labels like "Duplicate" were clipped to "Dup"
        b.setContentDescription(label);
        b.setTooltipText(label);
        t.setSingleLine(true);
        return b;
    }

    private int selFitKey = -1;

    /** Fits the selection bar into avail px: full size, then tighter with smaller labels, then icons only (phones). */
    private void fitSelBar(int avail) {
        for (int level = 0; level < 3; level++) {
            for (int i = 0; i < selBar.getChildCount(); i++) {
                View c = selBar.getChildAt(i);
                if (!(c instanceof LinearLayout)) continue;
                LinearLayout b = (LinearLayout) c;
                int pad = Ui.dp(this, level == 0 ? 12 : 4);
                b.setPadding(pad, Ui.dp(this, level == 2 ? 10 : 4), pad, Ui.dp(this, level == 2 ? 10 : 2));
                b.setMinimumWidth(Ui.dp(this, level == 0 ? 72 : level == 1 ? 48 : 44));
                TextView t = (TextView) b.getChildAt(1);
                t.setTextSize(level == 0 ? 11.5f : 10.5f);
                t.setVisibility(level == 2 ? View.GONE : View.VISIBLE);
            }
            selBar.measure(0, 0);
            if (selBar.getMeasuredWidth() <= avail) return;
        }
    }

    @Override
    public void onSelection(RectF r) {
        selView.set(r);
        if (r == null) {
            selBar.setVisibility(View.GONE);
            return;
        }
        if (pendingToText != null) {
            View anchor = pendingToText;
            pendingToText = null;
            engine.selInk(ink -> { if (!ink.isEmpty()) showConvert(anchor, ink); });
        }
        selBar.setVisibility(View.VISIBLE);
        int availW = panes[focusPane].getWidth() - Ui.dp(this, 24), fitKey = availW * 2 + (editTextB.getVisibility() == View.VISIBLE ? 1 : 0);
        if (fitKey != selFitKey) { selFitKey = fitKey; fitSelBar(availW); } // not on every frame of a drag
        else selBar.measure(0, 0);
        float bw = selBar.getMeasuredWidth(), bh = selBar.getMeasuredHeight();
        float px = paneLeft();
        float x = Math.max(px + Ui.dp(this, 12), Math.min(px + panes[focusPane].getWidth() - bw - Ui.dp(this, 12), px + r.centerX() - bw / 2));
        float pt = paneTop();
        float y = pt + r.top - bh - Ui.dp(this, 24);
        if (y < Math.max(pt, Ui.dp(this, EDGE_DP + CAP_DP + 8))) y = Math.min(pt + r.bottom + Ui.dp(this, 26), root.getHeight() - bh - Ui.dp(this, 70));
        selBar.setTranslationX(x);
        selBar.setTranslationY(y);
    }

    private void hideSelection() {
        selView.set(null);
        selBar.setVisibility(View.GONE);
    }

    // ---- text editing ----------------------------------------------------------------------

    @Override
    public void onEditText(Engine.TextEdit e) {
        commitTextEdit();
        textReq = e;
        editBox = Fonts.Style.parse(e.style);
        textEdit = new EditText(this) {
            @Override protected void onSelectionChanged(int a, int b) {
                super.onSelectionChanged(a, b);
                if (textEdit == this) refreshBar();
            }
        };
        textEdit.setText(editBox.spanned(e.item != null ? e.item.text : "", editHand(), e.zoom, editInk()));
        textEdit.setIncludeFontPadding(false);
        textEdit.setPadding(0, 0, 0, 0);
        textEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        textEdit.setHint("Type…");
        GradientDrawable g = Ui.round(0x00000000, Ui.dp(this, 4));
        g.setStroke(Ui.dp(this, 1.5f), Ui.color(this, R.color.accent), Ui.dp(this, 5), Ui.dp(this, 4));
        textEdit.setBackground(g);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(e.width * e.zoom) + 4, -2);
        lp.leftMargin = Math.round(e.screenX + paneLeft());
        lp.topMargin = Math.round(e.screenY + paneTop());
        root.addView(textEdit, lp);
        applyEditLook();
        addTextTools(e, lp.leftMargin, lp.topMargin);
        textEdit.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> { if (b - t != ob - ot) updateImeLift(); }); // grows as you type
        textEdit.requestFocus();
        textEdit.setSelection(textEdit.getText().length());
        ui.postDelayed(() -> {
            if (textEdit != null) getSystemService(InputMethodManager.class).showSoftInput(textEdit, 0);
        }, 80);
    }

    @Override
    public void onDismissText() { commitTextEdit(); }

    /** The box being typed in, drawn the way it will look on the page: font, style, size, colour, alignment. */
    private void applyEditLook() {
        if (textEdit == null || textReq == null) return;
        Engine.TextEdit e = textReq;
        Fonts.Style st = editBox;
        textEdit.setTypeface(st.face(editHand())); // converted text keeps its handwriting look
        textEdit.setTextSize(TypedValue.COMPLEX_UNIT_PX, e.size * e.zoom);
        int fl = textEdit.getPaintFlags() & ~(Paint.UNDERLINE_TEXT_FLAG | Paint.STRIKE_THRU_TEXT_FLAG);
        if (st.underline) fl |= Paint.UNDERLINE_TEXT_FLAG;
        if (st.strike) fl |= Paint.STRIKE_THRU_TEXT_FLAG;
        textEdit.setPaintFlags(fl);
        textEdit.setGravity(Gravity.TOP | (st.align == Fonts.Style.CENTRE ? Gravity.CENTER_HORIZONTAL
                : st.align == Fonts.Style.RIGHT ? Gravity.END : Gravity.START));
        textEdit.setTextColor(state != null && state.paper == Notebook.PAPER_DARK ? Render.inkColor(e.color, true) : e.color);
    }

    /** After a format popover closes: back to typing. */
    private void refocusEdit() {
        EditText et = textEdit;
        if (et == null) return;
        et.requestFocus();
        ui.postDelayed(() -> { if (textEdit == et) getSystemService(InputMethodManager.class).showSoftInput(et, 0); }, 80);
    }

    /**
     * Over the box while you type: a format bar (font, size, bold / italic / underline / strike, alignment, colour;
     * font, size and style also become the default for new boxes). For converted text, below it: read the original
     * handwriting again in another language, or turn it back into handwriting.
     */
    private void addTextTools(Engine.TextEdit e, int x, int y) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(formatBar(e));
        if (e.item != null && e.item.hasInk()) col.addView(readAsRow(e));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        hs.setPadding(0, Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        hs.addView(col);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
        lp.leftMargin = Math.max(Ui.dp(this, 8), x - Ui.dp(this, 6));
        lp.rightMargin = Ui.dp(this, 8);
        hs.setVisibility(View.INVISIBLE);
        root.addView(hs, lp);
        textTools = hs;
        // above the box, or under it when there's no room above
        hs.post(() -> {
            if (textTools != hs || textEdit == null) return;
            int h = hs.getHeight(), top = y - h;
            if (top < Ui.dp(this, 8)) top = y + textEdit.getHeight() + Ui.dp(this, 2);
            FrameLayout.LayoutParams l2 = (FrameLayout.LayoutParams) hs.getLayoutParams();
            l2.topMargin = top;
            hs.setLayoutParams(l2);
            hs.setVisibility(View.VISIBLE);
        });
    }

    private LinearLayout pill() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4));
        GradientDrawable g = Ui.round(Ui.color(this, R.color.surface), Ui.dp(this, 22));
        g.setStroke(Ui.hairline(this), Ui.color(this, R.color.outline));
        row.setBackground(g);
        row.setElevation(Ui.dp(this, 6));
        return row;
    }

    private View barSep() {
        View sep = new View(this);
        sep.setBackgroundColor(Ui.color(this, R.color.outline));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(Ui.hairline(this), Ui.dp(this, 22));
        sl.leftMargin = sl.rightMargin = Ui.dp(this, 5);
        sep.setLayoutParams(sl);
        return sep;
    }

    // ---- per-word styling while typing ---------------------------------------------------------
    // With words selected the format bar styles just them (runs: Fonts.Run, drawn by Fonts.RunSpan); with nothing
    // selected it styles the whole box (and clears that setting from the runs so the whole box follows).

    private Fonts.Style editBox;  // the box's own look while it's being edited (runs live as spans in the EditText)
    private TextView barFont, barSize;
    private final TextView[] barTog = new TextView[4];
    private final ImageView[] barAlign = new ImageView[3];
    private Ui.Swatch barDot;

    private boolean editHand() { return textReq != null && textReq.item != null && textReq.item.hand(); }

    private java.util.function.IntUnaryOperator editInk() {
        return state != null && state.paper == Notebook.PAPER_DARK ? c -> Render.inkColor(c, true) : null;
    }

    /** The box's style with the runs as they now stand in the editor. */
    private String editStyle(android.text.Editable ed) {
        Fonts.Style out = editBox.boxOnly();
        Fonts.RunSpan[] sp = ed.getSpans(0, ed.length(), Fonts.RunSpan.class);
        java.util.Arrays.sort(sp, (x, y) -> ed.getSpanStart(x) - ed.getSpanStart(y));
        for (Fonts.RunSpan r : sp) {
            Fonts.Run c = r.run.copy();
            c.start = ed.getSpanStart(r);
            c.end = ed.getSpanEnd(r);
            if (c.end > c.start && !c.empty()) out.runs.add(c);
        }
        return out.encode();
    }

    /**
     * Changes the runs over [a, b) (the whole text when a == b == -1) and lays the spans out again,
     * merging neighbours that look the same.
     */
    private void editRuns(int a, int b, java.util.function.Consumer<Fonts.Run> change) {
        EditText et = textEdit;
        if (et == null) return;
        android.text.Editable ed = et.getText();
        int n = ed.length();
        if (a < 0) { a = 0; b = n; }
        Fonts.Run[] per = new Fonts.Run[n];
        for (Fonts.RunSpan sp : ed.getSpans(0, n, Fonts.RunSpan.class)) {
            int s0 = Math.max(0, ed.getSpanStart(sp)), s1 = Math.min(n, ed.getSpanEnd(sp));
            for (int i = s0; i < s1; i++) per[i] = sp.run;
            ed.removeSpan(sp);
        }
        java.util.IdentityHashMap<Fonts.Run, Fonts.Run> done = new java.util.IdentityHashMap<>();
        Fonts.Run fresh = null;
        for (int i = Math.max(0, a); i < Math.min(n, b); i++) {
            Fonts.Run o = per[i], m;
            if (o == null) {
                if (fresh == null) { fresh = new Fonts.Run(); change.accept(fresh); }
                m = fresh;
            } else {
                m = done.get(o);
                if (m == null) { m = o.copy(); change.accept(m); done.put(o, m); }
            }
            per[i] = m.empty() ? null : m;
        }
        for (int i = 0; i < n; ) {
            if (per[i] == null) { i++; continue; }
            int j = i + 1;
            while (j < n && per[j] != null && per[j].sameLook(per[i])) j++;
            ed.setSpan(new Fonts.RunSpan(per[i].copy(), editBox, editHand(), textReq.zoom, editInk()), i, j,
                    android.text.Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
            i = j;
        }
    }

    /** The run at a character (the one before the cursor when nothing is selected), or null. */
    private Fonts.RunSpan runAt(int i) {
        if (textEdit == null || i < 0) return null;
        android.text.Editable ed = textEdit.getText();
        if (i >= ed.length()) return null;
        Fonts.RunSpan[] sp = ed.getSpans(i, i + 1, Fonts.RunSpan.class);
        return sp.length > 0 ? sp[sp.length - 1] : null;
    }

    private int[] selection() {
        EditText et = textEdit;
        if (et == null) return new int[]{0, 0};
        int a = Math.max(0, et.getSelectionStart()), b = Math.max(0, et.getSelectionEnd());
        return new int[]{Math.min(a, b), Math.max(a, b)};
    }

    /** Effective flag k (0 bold, 1 italic, 2 underline, 3 strike) at character i. */
    private boolean flagAt(int i, int k) {
        Fonts.RunSpan r = runAt(i);
        if (r != null) return k == 0 ? r.bold() : k == 1 ? r.italic() : k == 2 ? r.underline() : r.strike();
        return k == 0 ? editBox.bold : k == 1 ? editBox.italic : k == 2 ? editBox.underline : editBox.strike;
    }

    private float sizeAt(int i) {
        Fonts.RunSpan r = runAt(i);
        return r != null && r.run.size != null ? r.run.size : textReq.size;
    }

    private String fontAt(int i) {
        Fonts.RunSpan r = runAt(i);
        return r != null ? r.font() : editBox.font;
    }

    private int colorAt(int i) {
        Fonts.RunSpan r = runAt(i);
        return r != null && r.run.color != null ? r.run.color : textReq.color;
    }

    private int probe(int[] sel) { return sel[1] > sel[0] ? sel[0] : sel[0] - 1; }

    /** Puts the bar's buttons in step with the selection (or the cursor). */
    private void refreshBar() {
        if (textEdit == null || textReq == null || barFont == null) return;
        int i = probe(selection());
        String f = fontAt(i);
        barFont.setText(Fonts.name(f));
        barFont.setTypeface(Fonts.face(f, false, false, false));
        barSize.setText(String.format(Locale.ROOT, "%.0f", sizeAt(i) / Fonts.UNITS_PER_PT));
        for (int k = 0; k < 4; k++) styleToggle(barTog[k], flagAt(i, k));
        for (int k = 0; k < 3; k++) styleToggle(barAlign[k], editBox.align == k);
        barDot.set(colorAt(i), false);
    }

    /** Box-level change: relayout the spans (they read the box live) and the base look. */
    private void boxChanged() {
        editRuns(-1, -1, r -> { });
        applyEditLook();
        textStyleEnc = editBox.boxOnly().encode(); // new boxes start like this one
        applyTool();
        refreshBar();
    }

    private void toggleFlag(int k) {
        int[] sel = selection();
        if (sel[1] > sel[0]) {
            boolean all = true;
            for (int i = sel[0]; i < sel[1] && all; i++) all = flagAt(i, k);
            boolean v = !all;
            boolean boxV = k == 0 ? editBox.bold : k == 1 ? editBox.italic : k == 2 ? editBox.underline : editBox.strike;
            Boolean set = v == boxV ? null : v;
            editRuns(sel[0], sel[1], r -> {
                if (k == 0) r.bold = set; else if (k == 1) r.italic = set; else if (k == 2) r.underline = set; else r.strike = set;
            });
            refreshBar();
            return;
        }
        if (k == 0) editBox.bold = !editBox.bold; else if (k == 1) editBox.italic = !editBox.italic;
        else if (k == 2) editBox.underline = !editBox.underline; else editBox.strike = !editBox.strike;
        editRuns(-1, -1, r -> { if (k == 0) r.bold = null; else if (k == 1) r.italic = null; else if (k == 2) r.underline = null; else r.strike = null; });
        boxChanged();
    }

    private void stepSize(int dir) {
        int[] sel = selection();
        if (sel[1] > sel[0]) {
            float box = textReq.size;
            editRuns(sel[0], sel[1], r -> {
                float cur = r.size != null ? r.size : box;
                float pt = Math.max(Fonts.PT_MIN, Math.min(Fonts.PT_MAX, Math.round(cur / Fonts.UNITS_PER_PT) + dir));
                float u = pt * Fonts.UNITS_PER_PT;
                r.size = Math.abs(u - box) < 0.01f ? null : u;
            });
            refreshBar();
            return;
        }
        float pt = Math.max(Fonts.PT_MIN, Math.min(Fonts.PT_MAX, Math.round(textReq.size / Fonts.UNITS_PER_PT) + dir));
        textReq.size = pt * Fonts.UNITS_PER_PT;
        textPt = pt;
        editRuns(-1, -1, r -> r.size = null);
        boxChanged();
    }

    private void setFont(String id, int[] sel) {
        if (sel[1] > sel[0]) {
            String set = id.equals(editBox.font) ? null : id;
            editRuns(sel[0], sel[1], r -> r.font = set);
            refreshBar();
            return;
        }
        editBox.font = id;
        editRuns(-1, -1, r -> r.font = null);
        boxChanged();
    }

    private void setColour(int c, int[] sel) {
        if (sel[1] > sel[0]) {
            Integer set = c == textReq.color ? null : c;
            editRuns(sel[0], sel[1], r -> r.color = set);
            refreshBar();
            return;
        }
        textReq.color = c;
        editRuns(-1, -1, r -> r.color = null);
        applyEditLook();
        refreshBar();
    }

    /** A popover over the bar took focus: put the selection back and keep typing. */
    private void refocusEdit(int[] sel) {
        EditText et = textEdit;
        if (et == null) return;
        int n = et.getText().length();
        et.setSelection(Math.min(sel[0], n), Math.min(sel[1], n));
        refocusEdit();
    }

    private View formatBar(Engine.TextEdit e) {
        LinearLayout row = pill();
        // font
        TextView font = Ui.text(this, "", 14.5f, Ui.color(this, R.color.on_surface), null);
        font.setSingleLine(true);
        font.setMaxWidth(Ui.dp(this, 150));
        font.setEllipsize(android.text.TextUtils.TruncateAt.END);
        font.setGravity(Gravity.CENTER_VERTICAL);
        font.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_chevron, 0);
        font.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, R.color.icon)));
        font.setCompoundDrawablePadding(Ui.dp(this, 2));
        font.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 6), 0);
        font.setBackground(Ui.ripple(this, null, Ui.dp(this, 16)));
        font.setOnClickListener(v -> {
            int[] sel = selection();
            Ui.Popover p = newPop(300).title(sel[1] > sel[0] ? "Font for selected text" : "Font");
            p.add(fontList(fontAt(probe(sel)), id -> {
                setFont(id, sel);
                if (pop != null) pop.dismiss();
            }, e, null));
            p.pw.setOnDismissListener(() -> refocusEdit(sel));
            p.show(font);
        });
        barFont = font;
        row.addView(font, new LinearLayout.LayoutParams(-2, Ui.dp(this, 36)));
        row.addView(barSep());
        // size
        TextView minus = stepButton("−"), plus = stepButton("+");
        barSize = Ui.text(this, "", 14.5f, Ui.color(this, R.color.on_surface), Ui.medium());
        barSize.setGravity(Gravity.CENTER);
        minus.setContentDescription("Smaller");
        plus.setContentDescription("Bigger");
        minus.setOnClickListener(v -> stepSize(-1));
        plus.setOnClickListener(v -> stepSize(1));
        row.addView(minus, new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34)));
        row.addView(barSize, new LinearLayout.LayoutParams(Ui.dp(this, 36), -2));
        row.addView(plus, new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34)));
        row.addView(barSep());
        // B I U S
        String[] labels = {"B", "I", "U", "S"}, descs = {"Bold", "Italic", "Underline", "Strike-through"};
        for (int i = 0; i < 4; i++) {
            final int k = i;
            TextView t = Ui.text(this, labels[i], 16, Ui.color(this, R.color.on_surface), Fonts.face(Fonts.SERIF, false, i == 0, i == 1));
            t.setGravity(Gravity.CENTER);
            t.setContentDescription(descs[i]);
            if (i == 2) t.setPaintFlags(t.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
            if (i == 3) t.setPaintFlags(t.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            t.setOnClickListener(v -> toggleFlag(k));
            barTog[i] = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 34));
            lp.rightMargin = Ui.dp(this, 2);
            row.addView(t, lp);
        }
        row.addView(barSep());
        // alignment (the whole box)
        int[] icons = {R.drawable.ic_align_left, R.drawable.ic_align_center, R.drawable.ic_align_right};
        String[] an = {"Align left", "Centre", "Align right"};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            ImageView b = new ImageView(this);
            b.setImageResource(icons[i]);
            b.setScaleType(ImageView.ScaleType.CENTER);
            b.setContentDescription(an[i]);
            b.setOnClickListener(v -> { editBox.align = k; boxChanged(); });
            barAlign[i] = b;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 34));
            lp.rightMargin = Ui.dp(this, 2);
            row.addView(b, lp);
        }
        row.addView(barSep());
        // colour
        Ui.Swatch dot = new Ui.Swatch(this);
        dot.setContentDescription("Colour");
        dot.setOnClickListener(v -> {
            int[] sel = selection();
            Ui.Popover p = newPop(320).title(sel[1] > sel[0] ? "Colour for selected text" : "Colour");
            p.add(Ui.colorGrid(this, INK_PALETTE, colorAt(probe(sel)), c -> {
                setColour(c, sel);
                if (pop != null) pop.dismiss();
            }));
            p.pw.setOnDismissListener(() -> refocusEdit(sel));
            p.show(dot);
        });
        barDot = dot;
        row.addView(dot, new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 36)));
        row.post(this::refreshBar);
        return row;
    }

    private View readAsRow(Engine.TextEdit e) {
        LinearLayout row = pill();
        TextView lab = Ui.text(this, "Read as", 13, Ui.color(this, R.color.on_surface_variant), Ui.medium());
        lab.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 4), 0);
        row.addView(lab);
        String[] names = {"Auto", "English", "ਪੰਜਾਬੀ", "हिन्दी", "عربي"};
        for (int i = 0; i < names.length; i++) {
            final int lang = i == 0 ? Handwriting.AUTO : i - 1;
            TextView c = Ui.text(this, names[i], 14, Ui.color(this, R.color.on_surface), Ui.medium());
            c.setGravity(Gravity.CENTER);
            c.setPadding(Ui.dp(this, 11), 0, Ui.dp(this, 11), 0);
            c.setBackground(Ui.ripple(this, null, Ui.dp(this, 16)));
            c.setOnClickListener(v -> {
                EditText et = textEdit;
                if (et == null) return;
                toast(lang == Handwriting.AUTO ? "Reading again…" : "Reading as " + Handwriting.LANG_NAMES[lang] + "…");
                Handwriting.convert(e.item.ink(), lang, "", this::toast, r -> {
                    if (textEdit != et) return; // closed meanwhile
                    if (r.error != null) { toast(r.error); return; }
                    et.setText(r.text.trim().replace('\n', ' '));
                    et.setSelection(et.length());
                });
            });
            row.addView(c, new LinearLayout.LayoutParams(-2, Ui.dp(this, 34)));
        }
        View sep = new View(this);
        sep.setBackgroundColor(Ui.color(this, R.color.outline));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(Ui.hairline(this), Ui.dp(this, 22));
        sl.leftMargin = sl.rightMargin = Ui.dp(this, 4);
        row.addView(sep, sl);
        TextView back = Ui.text(this, "Back to handwriting", 14, Ui.color(this, R.color.accent), Ui.bold());
        back.setGravity(Gravity.CENTER);
        back.setPadding(Ui.dp(this, 11), 0, Ui.dp(this, 11), 0);
        back.setBackground(Ui.ripple(this, null, Ui.dp(this, 16)));
        back.setOnClickListener(v -> {
            EditText et = textEdit;
            if (et == null) return;
            textEdit = null;
            textReq = null;
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(et.getWindowToken(), 0);
            root.removeView(et);
            if (textTools != null) { root.removeView(textTools); textTools = null; }
            engine.restoreInk(e);
            ui.postDelayed(this::immersive, 300);
        });
        row.addView(back, new LinearLayout.LayoutParams(-2, Ui.dp(this, 34)));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2);
        rl.topMargin = Ui.dp(this, 6);
        row.setLayoutParams(rl);
        return row;
    }

    private void commitTextEdit() {
        if (textEdit == null) return;
        EditText et = textEdit;
        Engine.TextEdit req = textReq;
        textEdit = null;
        textReq = null;
        req.style = editStyle(et.getText());
        engine.commitText(req, et.getText().toString());
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(et.getWindowToken(), 0);
        root.removeView(et);
        if (textTools != null) { root.removeView(textTools); textTools = null; }
        updateImeLift();
        ui.postDelayed(this::immersive, 300);
    }

    // ---- keyboard: the window doesn't resize for it (adjustNothing keeps the ink surface still), so while a text box
    // is being typed in, the page and the box slide up just enough to keep the box above the keyboard (phones) --------

    private int imeBottom;

    private void updateImeLift() {
        float lift = 0;
        if (textEdit != null && imeBottom > 0 && root.getHeight() > 0) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) textEdit.getLayoutParams();
            int boxBottom = lp.topMargin + textEdit.getHeight();
            lift = Math.max(0, boxBottom - (root.getHeight() - imeBottom - Ui.dp(this, 16)));
        }
        for (View v : new View[]{paneRow, textEdit, textTools}) {
            if (v == null) continue;
            if (v.getTranslationY() != -lift) v.animate().translationY(-lift).setDuration(160).start();
        }
    }

    // ---- Page+ sheet: add templates, pages from another notebook, images, or PDFs ------------

    private void showAddPage(int initialPos, Runnable after) {
        if (state != null && state.canvas) return;
        commitTextEdit();
        final Engine target = engine;
        int paper = state != null ? state.paper : Notebook.PAPER_WHITE;
        Notebook.Page curStyle = null;
        if (state != null) {
            curStyle = new Notebook.Page(state.bg);
            curStyle.spacing = state.spacing;
            curStyle.strength = state.strength;
            curStyle.lineColor = state.lineColor;
            curStyle.margins = state.margins;
        }
        AddPageSheet.show(this, target.dir, paper, curStyle, myTemplates(), initialPos, bg, new AddPageSheet.Callback() {
            @Override
            public void onAddTemplate(int placement, Notebook.Page look) {
                target.insertPage(placement, look);
                toast("Page added");
                if (after != null) after.run();
            }

            @Override
            public void onImportFromNotebook(int placement, File srcDir, java.util.List<Integer> pageIndices, String srcTitle) {
                importNotebookPages(target, placement, srcDir, pageIndices, srcTitle, after);
            }

            @Override
            public void onPickImages(int placement) {
                pendingPagePos = placement;
                pendingPageEngine = target;
                afterPageImport = after;
                Intent in = new Intent(MediaStore.ACTION_PICK_IMAGES)
                        .setType("image/*")
                        .putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit());
                startActivityForResult(in, REQ_PAGE_IMAGES);
            }

            @Override
            public void onPickPdf(int placement) {
                pendingPagePos = placement;
                pendingPageEngine = target;
                afterPageImport = after;
                Intent in = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/pdf");
                startActivityForResult(in, REQ_PAGE_PDF);
            }
        });
    }

    private void importNotebookPages(Engine target, int pos, File srcDir, java.util.List<Integer> indices, String srcTitle, Runnable after) {
        if (indices == null || indices.isEmpty()) return;
        toast("Importing " + indices.size() + (indices.size() == 1 ? " page…" : " pages…"));
        bg.execute(() -> {
            java.util.ArrayList<Notebook.Page> out = new java.util.ArrayList<>();
            File dstImgDir = new File(target.dir, "images");
            File srcImgDir = new File(srcDir, "images");
            try (Render.Pdf srcPdf = Render.Pdf.open(srcDir)) {
                Notebook srcNb = Notebook.load(srcDir);
                for (int idx : indices) {
                    if (idx < 0 || idx >= srcNb.pages.size()) continue;
                    Notebook.Page sp = srcNb.pages.get(idx);
                    Notebook.Page np = new Notebook.Page(sp.pdf >= 0 ? Notebook.BG_BLANK : sp.bg);
                    np.h = sp.h;
                    np.copyStyle(sp);
                    np.mark = sp.mark;

                    java.util.ArrayList<Stroke> strokes = new java.util.ArrayList<>();
                    for (Stroke s : sp.strokes()) strokes.add(s.shifted(0, 0));
                    np.setStrokes(strokes);

                    java.util.ArrayList<Notebook.Item> items = new java.util.ArrayList<>();
                    if (sp.pdf >= 0 && srcPdf != null) {
                        Bitmap bm = srcPdf.render(sp.pdf, 1800);
                        if (bm != null) {
                            dstImgDir.mkdirs();
                            String name = UUID.randomUUID() + ".jpg";
                            try (FileOutputStream o = new FileOutputStream(new File(dstImgDir, name))) {
                                bm.compress(Bitmap.CompressFormat.JPEG, 92, o);
                            }
                            bm.recycle();
                            items.add(new Notebook.Item(Notebook.Item.IMAGE, 0, 0, Notebook.PW, np.h, name, 0, 0));
                        }
                    }
                    for (Notebook.Item it : sp.items()) {
                        if (it.type == Notebook.Item.IMAGE && it.text != null) {
                            File srcFile = new File(srcImgDir, it.text);
                            String newName = it.text;
                            if (srcFile.isFile()) {
                                dstImgDir.mkdirs();
                                int dot = it.text.lastIndexOf('.');
                                String ext = dot >= 0 ? it.text.substring(dot) : ".png";
                                newName = UUID.randomUUID() + ext;
                                Io.copy(new java.io.FileInputStream(srcFile), new FileOutputStream(new File(dstImgDir, newName)));
                            }
                            items.add(new Notebook.Item(it.type, it.x, it.y, it.w, it.h, newName, it.size, it.color, it.pts, it.style).lay(it.layer));
                        } else {
                            float[] ptsCopy = it.pts == null ? null : it.pts.clone();
                            items.add(new Notebook.Item(it.type, it.x, it.y, it.w, it.h, it.text, it.size, it.color, ptsCopy, it.style).lay(it.layer));
                        }
                    }
                    np.setItems(items);
                    sp.unload();
                    out.add(np);
                }
            } catch (Exception e) {
                Log.e(Gl.TAG, "import notebook pages", e);
                ui.post(() -> toast("Couldn't import pages from " + srcTitle));
                return;
            }
            if (out.isEmpty()) {
                ui.post(() -> toast("No pages imported"));
                return;
            }
            ui.post(() -> {
                target.insertPages(pos, out);
                int n = out.size();
                toast("Added " + n + (n == 1 ? " page" : " pages") + " from " + srcTitle);
                if (after != null) after.run();
            });
        });
    }

    private void importImagePages(Engine target, int pos, java.util.List<Uri> uris, Runnable after) {
        if (uris == null || uris.isEmpty()) return;
        toast("Adding " + uris.size() + (uris.size() == 1 ? " image page…" : " image pages…"));
        bg.execute(() -> {
            java.util.ArrayList<Notebook.Page> pages = new java.util.ArrayList<>();
            File imgDir = new File(target.dir, "images");
            imgDir.mkdirs();
            for (Uri uri : uris) {
                try {
                    Bitmap b = ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(), uri), (dec, info, src) -> {
                        int w = info.getSize().getWidth(), h = info.getSize().getHeight(), m = Math.max(w, h);
                        if (m > 2400) dec.setTargetSize(w * 2400 / m, h * 2400 / m);
                        dec.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    });
                    boolean alpha = b.hasAlpha();
                    String name = UUID.randomUUID() + (alpha ? ".png" : ".jpg");
                    try (FileOutputStream o = new FileOutputStream(new File(imgDir, name))) {
                        b.compress(alpha ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 90, o);
                    }
                    int bw = Math.max(1, b.getWidth()), bh = Math.max(1, b.getHeight());
                    b.recycle();

                    Notebook.Page pg = new Notebook.Page(Notebook.BG_BLANK);
                    float pad = 36f;
                    float maxW = Notebook.PW - 2 * pad, maxH = pg.h - 2 * pad;
                    float scale = Math.min(maxW / bw, maxH / bh);
                    float iw = bw * scale, ih = bh * scale;
                    float ix = (Notebook.PW - iw) * 0.5f, iy = (pg.h - ih) * 0.5f;
                    java.util.ArrayList<Notebook.Item> items = new java.util.ArrayList<>();
                    items.add(new Notebook.Item(Notebook.Item.IMAGE, ix, iy, iw, ih, name, 0, 0));
                    pg.setItems(items);
                    pages.add(pg);
                } catch (Exception e) {
                    Log.e(Gl.TAG, "image page", e);
                }
            }
            if (pages.isEmpty()) {
                ui.post(() -> toast("Couldn't add those images"));
                return;
            }
            ui.post(() -> {
                target.insertPages(pos, pages);
                int n = pages.size();
                toast("Added " + n + (n == 1 ? " image page" : " image pages"));
                if (after != null) after.run();
            });
        });
    }

    private void importPdfPages(Engine target, int pos, Uri uri, Runnable after) {
        toast("Importing PDF pages…");
        bg.execute(() -> {
            File tmp = new File(getCacheDir(), "import-" + UUID.randomUUID() + ".pdf");
            java.util.ArrayList<Notebook.Page> pages = new java.util.ArrayList<>();
            File imgDir = new File(target.dir, "images");
            imgDir.mkdirs();
            try {
                Io.copy(getContentResolver().openInputStream(uri), new FileOutputStream(tmp));
                try (Render.Pdf pdf = new Render.Pdf(tmp)) {
                    int count = pdf.r.getPageCount();
                    for (int i = 0; i < count; i++) {
                        float ph;
                        try (android.graphics.pdf.PdfRenderer.Page rp = pdf.r.openPage(i)) {
                            ph = Notebook.PW * rp.getHeight() / (float) Math.max(1, rp.getWidth());
                        }
                        Bitmap bm = pdf.render(i, 1800);
                        if (bm == null) continue;
                        String name = UUID.randomUUID() + ".jpg";
                        try (FileOutputStream o = new FileOutputStream(new File(imgDir, name))) {
                            bm.compress(Bitmap.CompressFormat.JPEG, 92, o);
                        }
                        bm.recycle();
                        Notebook.Page pg = new Notebook.Page(Notebook.BG_BLANK);
                        pg.h = ph;
                        java.util.ArrayList<Notebook.Item> items = new java.util.ArrayList<>();
                        items.add(new Notebook.Item(Notebook.Item.IMAGE, 0, 0, Notebook.PW, ph, name, 0, 0));
                        pg.setItems(items);
                        pages.add(pg);
                    }
                }
            } catch (Exception e) {
                Log.e(Gl.TAG, "pdf page import", e);
                ui.post(() -> toast("Couldn't import that PDF"));
                return;
            } finally {
                tmp.delete();
            }
            if (pages.isEmpty()) {
                ui.post(() -> toast("No pages found in that PDF"));
                return;
            }
            ui.post(() -> {
                target.insertPages(pos, pages);
                int n = pages.size();
                toast("Added " + n + (n == 1 ? " PDF page" : " PDF pages"));
                if (after != null) after.run();
            });
        });
    }

    // ---- images --------------------------------------------------------------------------

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_SAVE_PDF) {
            File f = pendingSave;
            pendingSave = null;
            if (res != RESULT_OK || data == null || data.getData() == null || f == null) return;
            final Uri out = data.getData();
            bg.execute(() -> {
                try {
                    Io.copy(new java.io.FileInputStream(f), getContentResolver().openOutputStream(out));
                    ui.post(() -> toast("Saved"));
                } catch (Exception e) {
                    ui.post(() -> toast("Couldn't save there"));
                }
            });
            return;
        }
        if (req == REQ_FONT) {
            if (res == RESULT_OK && data != null && data.getData() != null) fontPicked(data.getData());
            else { fontImportFor = null; fontImportEngine = null; afterFontImport = null; }
            return;
        }
        if (req == REQ_STICKERS) {
            if (res != RESULT_OK || data == null) return;
            java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
            if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            else if (data.getData() != null) uris.add(data.getData());
            toast("Adding stickers…");
            bg.execute(() -> {
                int n = Stickers.importUris(this, uris);
                ui.post(() -> {
                    toast(n == 0 ? "No images found to add" : "Added " + n + (n == 1 ? " sticker" : " stickers"));
                    if (n > 0 && stickerAnchor != null && stickerAnchor.isAttachedToWindow()) showInsert(stickerAnchor);
                });
            });
            return;
        }
        if (req == REQ_PAGE_IMAGES) {
            Engine target = pendingPageEngine != null ? pendingPageEngine : engine;
            int pos = pendingPagePos;
            Runnable after = afterPageImport;
            pendingPageEngine = null;
            afterPageImport = null;
            if (res != RESULT_OK || data == null) return;
            java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
            if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            else if (data.getData() != null) uris.add(data.getData());
            importImagePages(target, pos, uris, after);
            return;
        }
        if (req == REQ_PAGE_PDF) {
            Engine target = pendingPageEngine != null ? pendingPageEngine : engine;
            int pos = pendingPagePos;
            Runnable after = afterPageImport;
            pendingPageEngine = null;
            afterPageImport = null;
            if (res != RESULT_OK || data == null || data.getData() == null) return;
            importPdfPages(target, pos, data.getData(), after);
            return;
        }
        if (req != REQ_IMAGE || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final Engine target = engine; // the notebook you were in when you picked it, even if you switch tabs meanwhile
        toast("Adding image…");
        bg.execute(() -> {
            try {
                Bitmap b = ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(), uri), (dec, info, src) -> {
                    int w = info.getSize().getWidth(), h = info.getSize().getHeight(), m = Math.max(w, h);
                    if (m > 2400) dec.setTargetSize(w * 2400 / m, h * 2400 / m);
                    dec.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
                File dir = new File(target.dir, "images");
                dir.mkdirs();
                boolean alpha = b.hasAlpha();
                String name = UUID.randomUUID() + (alpha ? ".png" : ".jpg");
                try (FileOutputStream o = new FileOutputStream(new File(dir, name))) {
                    b.compress(alpha ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 90, o);
                }
                int w = b.getWidth(), h = b.getHeight();
                b.recycle();
                ui.post(() -> {
                    target.insertImage(name, w, h);
                    if (target == engine) { tool = Engine.TOOL_LASSO; applyTool(); }
                });
            } catch (Exception e) {
                Log.e(Gl.TAG, "image", e);
                ui.post(() -> toast("Couldn't add that image"));
            }
        });
    }

    // ---- pen side button: single press toggles the eraser -----------------------------------

    private static boolean fromPen(KeyEvent e) {
        InputDevice d = e.getDevice();
        return d != null && d.getName() != null && d.getName().toLowerCase(Locale.US).contains("pen");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean pen = fromPen(e);
        boolean secondary = k == KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY || (pen && k == KeyEvent.KEYCODE_PAGE_UP);
        boolean primary = !secondary && (k == KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY || pen);
        if ((primary || secondary) && textEdit == null) {
            // HyperOS delivers a short press as down+up together on release; long presses are
            // kept by the system (Quick Notes). Act once, on release.
            if (e.getAction() == KeyEvent.ACTION_UP && penButton) togglePenTool(secondary ? Engine.TOOL_LASSO : Engine.TOOL_ERASER);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** Pen side buttons: primary toggles the eraser, secondary the lasso; pressing again goes back. */
    private void togglePenTool(int target) {
        commitTextEdit();
        if (tool == target) {
            tool = prevTool == target ? Engine.TOOL_PEN : prevTool;
            toast(tool == Engine.TOOL_HL ? "Highlighter" : tool == Engine.TOOL_PEN ? "Pen" : "Back to previous tool");
        } else {
            if (tool != Engine.TOOL_ERASER && tool != Engine.TOOL_LASSO) prevTool = tool;
            tool = target;
            toast(target == Engine.TOOL_LASSO ? "Lasso · press the second button again to go back"
                    : "Eraser · press the button again to go back");
        }
        applyTool();
    }

    // ---- engine callbacks ------------------------------------------------------------------

    @Override
    public void onState(Engine.State s) {
        if (editTextB != null) editTextB.setVisibility(s.selText ? View.VISIBLE : View.GONE);
        if (toLayerB != null) toLayerB.setVisibility(s.layerIds.length > 1 ? View.VISIBLE : View.GONE);
        if (layersBox != null && pop != null && layersBox.isAttachedToWindow()) ui.post(() -> renderLayers(layersBox));
        state = s;
        undoB.setEnabledLook(s.canUndo);
        redoB.setEnabledLook(s.canRedo);
        renderTabs();
        if (zoomChip != null) {
            if (s.zoomLocked) showZoomChip(s.zoom, true); // a locked notebook always shows its lock
            else if (zoomChip.getVisibility() == View.VISIBLE) {
                if (chipLocked) showZoomChip(s.zoom, false); // was a locked notebook's: now hides after a while
                else setZoomChipLook(s.zoom, false);
            }
        }
        boolean narrowW = root.getWidth() > 0 && root.getWidth() < Ui.dp(this, 520);
        addPageB.setVisibility(s.canvas || narrowW ? View.GONE : View.VISIBLE);
        pagesB.setVisibility(s.canvas || narrowW ? View.GONE : View.VISIBLE);
        if (ruler.getVisibility() == View.VISIBLE) ruler.invalidate(); // ticks follow the zoom
        bookmarkB.setVisibility(s.canvas || narrowW ? View.GONE : View.VISIBLE);
        boolean marked = s.marks != null && s.page < s.marks.length && s.marks[s.page] != null;
        bookmarkB.setImageResource(marked ? R.drawable.ic_bookmark_filled : R.drawable.ic_bookmark);
        bookmarkB.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, marked ? R.color.accent : R.color.icon)));
        updatePaste();
    }

    @Override
    public void onStats(String s) {
        if (!hud) return;
        hudView.setText(s);
        hudView.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onToast(String s) { toast(s); }

    @Override
    public void onTool(int t) {
        if (tool == t) return;
        tool = t;
        applyTool();
    }

    private final Runnable hideToast = () -> toastView.animate().alpha(0f).setDuration(220).start();

    private void toast(String s) {
        toastView.setText(s);
        toastView.animate().alpha(1f).setDuration(120).start();
        ui.removeCallbacks(hideToast);
        ui.postDelayed(hideToast, 1400);
    }

    // ---- window ------------------------------------------------------------------------------

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    private void immersive() {
        WindowInsetsController ic = getWindow().getInsetsController();
        if (ic != null && textEdit == null) {
            ic.hide(WindowInsets.Type.systemBars());
            ic.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    private void preferFastestDisplayMode() {
        Display d = getDisplay();
        if (d == null) return;
        Display.Mode cur = d.getMode(), best = cur;
        for (Display.Mode md : d.getSupportedModes()) {
            if (md.getPhysicalWidth() == cur.getPhysicalWidth() && md.getPhysicalHeight() == cur.getPhysicalHeight()
                    && md.getRefreshRate() > best.getRefreshRate()) best = md;
        }
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.preferredDisplayModeId = best.getModeId();
        lp.preferredRefreshRate = best.getRefreshRate();
        fastestRate = best.getRefreshRate();
        if (rateKeeper != null) rateKeeper.setRate(fastestRate);
        getWindow().setAttributes(lp);
    }

    @Override
    public void onBackPressed() {
        if (textEdit != null) { commitTextEdit(); return; }
        if (sheet != null) { closeSettings(); return; }
        if (pagesPanel != null) { closePages(); return; }
        if (state != null && state.hasSelection) { engine.clearSelectionAsync(); return; }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        commitTextEdit();
        if (pop != null) pop.dismiss();
        for (Doc d : docs) {
            if (d.engine == null) continue; // never opened this session: nothing to save
            d.engine.saveNow();
            final File dir = d.dir;
            ui.postDelayed(() -> Handwriting.indexLater(getApplicationContext(), dir), 1500); // read the new handwriting once saved
            saveView(d);
        }
        saveTabs();
        // left for the notes screen (back, last tab closed): start there next time; else (home, recents, killed) come back here
        prefs.edit().putBoolean("resumeEditor", !isFinishing()).apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        for (Doc d : docs) if (d.engine != null) d.engine.release();
        bg.shutdown();
    }
}

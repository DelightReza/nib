package app.nib.notes;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.text.StaticLayout;
import android.util.Log;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.MotionPredictor;
import android.view.Surface;
import android.view.VelocityTracker;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Owns the document, the view transform and the GL renderer, all on one high-priority thread.
 * The UI thread only forwards copied MotionEvents and commands, so nothing on the UI side can
 * stall the ink path. Rendering: the window surface stays in EGL front-buffer mode while ink is
 * added; full-scene changes (scroll, zoom, selection drags) temporarily use normal buffering.
 */
final class Engine implements Choreographer.FrameCallback {
    static final float PW = Notebook.PW, GAP = 0f; // continuous pages: each one starts where the last ends
    static final int TOOL_PEN = 0, TOOL_HL = 1, TOOL_ERASER = 2, TOOL_LASSO = 3, TOOL_TEXT = 4, TOOL_LASER = 5, TOOL_TAPE = 6;
    /** Writes like the pen, but each stroke becomes a clean shape when the pen lifts (or sooner if it pauses). */
    static final int TOOL_SHAPE = 7;
    static final float[] TEXT_SIZES = {20f, 28f, 42f};

    static final class State {
        boolean canUndo, canRedo, front, hasSelection, hasClipboard, canvas, selText;
        int page, pages, bg, zoom, paper, undoCount, redoCount;
        String[] marks; // per page: bookmark title ("" untitled) or null
        float spacing = 1f, strength = 1f; // current page's look
        int lineColor, margins;
        float z;        // screen px per page unit
        String title;
        boolean zoomLocked, viewKnown;
        // where the view is, to reopen the notebook there: page at the top of the screen, how far down it (× its height),
        // zoom relative to fit-width, and the left edge (doc units)
        int topPage;
        float topFrac, zRel = 1f, viewX;
        // layers, bottom to top, and the active one
        int[] layerIds = new int[0];
        String[] layerNames = new String[0];
        boolean[] layerVisible = new boolean[0];
        int activeLayer;
    }

    /** Request for the UI to show a text editor over the page. */
    static final class TextEdit {
        int page;
        Notebook.Item item; // null = new box
        float docX, docY, width, size;
        int color;
        String style; // Fonts.Style, encoded
        float screenX, screenY, zoom;
    }

    interface Listener {
        void onState(State s);
        void onStats(String s);
        void onSelection(RectF screen); // null = none
        void onEditText(TextEdit e);
        void onDismissText();
        void onToast(String s);
        void onTool(int tool);
        /** Length (page units) of the line being drawn along the ruler; < 0 when the line is finished. */
        default void onRulerLength(float units, boolean done) {}
        default void onHaptic() {}
        /** Stretch handles of a lone selected shape, in view px (null = none); line: just its two ends. */
        /** A lone shape's handles (screen px); from index box on (if >= 0) they are its scaling box's corners. */
        default void onHandles(float[] screen, boolean line, int box) {}
        /** Zoom (% of fit-width) while pinching; a few times a second at most. */
        default void onZoom(int pct) {}
    }

    private final HandlerThread thread = new HandlerThread("nib-ink");
    private final Handler h;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Context app;
    private final float density;
    private final Listener listener;
    final File dir;

    private Gl gl;
    private Choreographer choreo;
    private MotionPredictor predictor;
    private boolean frameRequested;
    private int W, H;
    private Notebook book;
    private float[] tops = new float[0];
    private float docH;

    // view transform: screen = (doc - o) * z
    private float ox, oy, z = 1f, fitZ = 1f;
    private boolean viewInit;
    private float topPad, botPad, sidePad;

    // render state
    private boolean front, wantFront = true, needRebuild, needCache, needPresent;
    private boolean gesturing, flinging, overlay;
    private int deskColor = 0xffe7e9ee, predMode;
    private float shadow = 0.10f;
    private String frontWhy = "";

    // tools (engine thread)
    private int tool = TOOL_PEN;
    private int penColor = 0xff1d1f24, hlColor = 0xffffe066;
    private float penWidth = 2.6f, hlWidth = 22f, textSize = TEXT_SIZES[1];
    private String textStyle;
    private int textColor;
    private int predLevel = 1, penType = Stroke.PEN;
    private float eraserDp = 11f;
    private boolean fingerDraw, hud, partialErase, shapeSnap = true, tempEraser;
    private boolean hlFlat, holdErase = true, erasePreview = true;
    private int hlStraight = 1; // straighten highlighter on lift: 0 off, 1 when nearly straight, 2 always
    private int tapeColor = 0xff9ecbf2;
    private String tapePattern = "stripes";
    private float tapeThick = 40f;
    private boolean tapeFree;
    private int tapePage;
    private float tx0, ty0, tx1, ty1; // tape being laid down (doc units): start, pen now
    private float[] tpts = new float[256]; // freeform: centre line so far (doc units)
    private int tn;

    // gestures
    private static final int G_NONE = 0, G_INK = 1, G_ERASE = 2, G_PAN = 3, G_IGNORE = 4, G_LASSO = 5, G_SEL = 6, G_TAP = 7, G_LASER = 8, G_TAPE = 9;
    private int gest = G_NONE;
    private volatile long penNear, stylusAt; // stylusAt: only the real pen (hover / touch), so fingers don't write next to it
    private boolean fingerGest;             // the current gesture was started by a finger (finger drawing on)
    private static final float FINGER_P = 0.5f; // fingers report contact size, not pressure: draw at the nominal width
    /** This stroke has no real pressure (finger, or a stylus without it): the fountain pen tapers in and out instead. */
    private boolean synthP;
    private float synthD;      // distance written so far (units), for the taper in
    private boolean taper = true;

    // live stroke
    private Stroke live;
    private int livePage, liveFirst, newFirst;
    private float liveTop, pf;
    private boolean snapped;
    private float holdX, holdY;
    private final RectF dirty = new RectF(), predCur = new RectF(), predPrev = new RectF(), tmp = new RectF();

    // erase
    private IdentityHashMap<Notebook.Page, Object[]> eraseBefore;
    // erase preview: what this eraser stroke has touched; drawn grey, removed when the pen lifts
    private final java.util.Set<Stroke> doomed = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final java.util.Set<Notebook.Item> doomedItems = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final IdentityHashMap<Stroke, ArrayList<Stroke>> pieces = new IdentityHashMap<>(); // partial: what's left of a touched stroke
    private float ex, ey;

    // lasso + selection (selection coordinates are page-local)
    private float[] lx = new float[256], ly = new float[256];
    private int ln, lassoPage;
    private int selPage = -1;
    private ArrayList<Stroke> selStrokes = new ArrayList<>();
    private ArrayList<Notebook.Item> selItems = new ArrayList<>();
    private final RectF selBounds = new RectF();
    private float spx, spy, ss = 1, stx, sty; // live transform while dragging
    private boolean selScale;
    private float sDownX, sDownY;
    private ArrayList<Stroke> clipStrokes = new ArrayList<>();
    private ArrayList<Notebook.Item> clipItems = new ArrayList<>();

    // tap (text tool)
    private float tapX, tapY;
    private long tapT;
    private Notebook.Item editing;

    // pan / zoom / fling
    private VelocityTracker vt;
    private float sOx, sOy, sZ, sDist, sMidX, sMidY, vx, vy;
    private int maxPtr;
    private long lastTapT;
    private float lastTapX, lastTapY;
    private long downT, lastFrame;
    private boolean moved;

    // undo
    private final ArrayDeque<Act> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private int lastPage = -1;

    // textures
    private static final class Tex { int id; long bytes; long used; }
    private final HashMap<String, Tex> texs = new HashMap<>();
    private final HashSet<String> texPending = new HashSet<>();
    private long texBytes, frameNo;
    private Render.Pdf pdf; // worker thread only
    private boolean pdfTried;

    // stats
    private final float[] latIn = new float[128], latFlush = new float[128], latGpu = new float[128];
    private int latN;
    private float downDelay;
    private long lastHud;
    private float stPmin, stPmax, stPsum, stStep, stLx, stLy;
    private int stN, stFront, stSwap, stFrames, stScenes;
    private long stFrameNs, stFrameMax;
    private long stT0;

    Engine(Context c, File dir, Listener l) {
        app = c.getApplicationContext();
        Diag.init(app);
        Diag.log("open " + dir.getName());
        density = c.getResources().getDisplayMetrics().density;
        listener = l;
        this.dir = dir;
        thread.start();
        h = new Handler(thread.getLooper());
        h.post(() -> {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
            } catch (Exception e) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
            }
            choreo = Choreographer.getInstance();
            predictor = new MotionPredictor(app);
            gl = new Gl();
            gl.init();
            // diagnostics (no UI): "frontTest" 1 = behave as if the GPU has no front-buffer mode, 2 = as if it never enters it
            int ft = app.getSharedPreferences("nib", Context.MODE_PRIVATE).getInt("frontTest", 0);
            if (ft == 1) gl.mutable = false;
            if (ft == 2) gl.frontHonoured = false;
            if (ft != 0) Log.w(Gl.TAG, "front buffer test mode " + ft);
            book = Notebook.load(dir);
            if (book.loadFailed) toast("Part of this notebook couldn't be read · the original file was kept, nothing is lost yet");
            if (!book.legacy && !book.loadFailed) { // remove page files no page refers to (on the io thread, before any save)
                final java.util.Set<String> keep = book.pageFiles();
                io.execute(() -> Notebook.tidy(dir, keep));
            }
            if (book.legacy) { dirtyDoc = true; h.postDelayed(saveR, 1500); } // an older notebook: switch it to page files once
            gl.clipX0 = book.canvas ? -1e9f : 0f;
            gl.clipX1 = book.canvas ? 1e9f : PW;
            ensureTrailingPage();
            relayout();
            applyPaper();
            notifyState();
        });
    }

    private float dp(float v) { return v * density; }

    // ---- API (any thread) ------------------------------------------------------------------

    void setInsets(float top, float bottom) { h.post(() -> { topPad = top; botPad = bottom; }); }

    void setTheme(int desk, float shadowAlpha) {
        h.post(() -> { deskColor = desk; shadow = shadowAlpha; applyPaper(); needCache = true; requestFrame(); });
    }

    void setTool(int t) {
        h.post(() -> {
            if (t != tool) {
                if (t != TOOL_LASSO) clearSelection();
                if (t != TOOL_LASER && hovering) { hovering = false; laserUpAt = SystemClock.uptimeMillis(); requestFrame(); }
                tool = t;
            }
        });
    }

    /** Tape tool: colour, pattern (Notebook.Item.TAPE_PATTERNS) and strip thickness in page units. */
    void setTape(int color, String pattern, float thick, boolean freeform) {
        h.post(() -> { tapeColor = color; tapePattern = pattern; tapeThick = thick; tapeFree = freeform; });
    }

    /** Tape on the current page: 0 = cover all, 1 = reveal all, 2 = remove all (undoable). */
    void tapeAll(int op) {
        h.post(() -> {
            if (book == null) return;
            Notebook.Page p = book.pages.get(currentPage());
            Object[] before = pageState(p);
            boolean any = false;
            for (int i = p.items().size() - 1; i >= 0; i--) {
                Notebook.Item it = p.items().get(i);
                if (it.type != Notebook.Item.TAPE || !book.layerVisible(it.layer)) continue;
                if (op == 2 && it.layer != book.activeLayer) continue; // removing is editing: the active layer only
                any = true;
                if (op == 2) p.items().remove(i);
                else p.items().set(i, it.withRevealed(op == 1));
            }
            if (!any) { toast("No tape on this page"); return; }
            if (op == 2) push(new PageEdit(p, before, pageState(p)));
            needCache = true;
            wantFront = true;
            requestFrame();
            docChanged();
        });
    }

    void setColor(int t, int c) { h.post(() -> { if (t == TOOL_HL) hlColor = c; else penColor = c; }); }

    void setWidth(int t, float w) { h.post(() -> { if (t == TOOL_HL) hlWidth = w; else penWidth = w; }); }

    void setTextSize(float s) { h.post(() -> textSize = s); }

    /** Look of new text boxes: style (Fonts.Style, encoded) and colour (0 = the pen's colour). */
    void setTextLook(String style, int color) { h.post(() -> { textStyle = style; textColor = color; }); }

    void setPenType(int t) { h.post(() -> penType = t); }

    void setEraserSize(float dpRadius) { h.post(() -> eraserDp = dpRadius); }

    void setTitle(String t) {
        h.post(() -> {
            if (book == null || t.trim().isEmpty()) return;
            book.title = t.trim();
            dirtyDoc = true;
            save();
            notifyState();
        });
    }

    void setOptions(int pred, boolean finger, boolean showHud, boolean partial, boolean snap) {
        h.post(() -> {
            predLevel = pred; fingerDraw = finger; hud = showHud; partialErase = partial; shapeSnap = snap;
            latN = 0;
            notifyState();
        });
    }

    /** Highlighter tip (flat = chisel) and straighten-on-lift (0 off, 1 when nearly straight, 2 always). */
    void setHighlighter(boolean flat, int straighten) { h.post(() -> { hlFlat = flat; hlStraight = straighten; }); }

    /** Hold the pen still at the start of a stroke to erase; erased ink greys out until the pen lifts. */
    void setEraseOptions(boolean hold, boolean preview) { h.post(() -> { holdErase = hold; erasePreview = preview; }); }

    /** Held pen side button (or any eraser key) → erase while held. */
    void setTempEraser(boolean on) { h.post(() -> tempEraser = on); }

    /** Highest zoom, × fit-width (8 = 800%). */
    void setZoomLimit(float rel) {
        h.post(() -> {
            maxZoomRel = Math.max(2f, rel);
            if (W > 0 && z > fitZ * maxZoomRel) { clampView(); needCache = true; wantFront = true; requestFrame(); notifyState(); }
        });
    }

    /** Locked zoom: pinching only scrolls, double-tap doesn't fit. */
    void setZoomLock(boolean on) { h.post(() -> { zoomLocked = on; notifyState(); }); }

    /** Reopen where the notebook was left (before the first frame): see State.topPage etc. */
    void openAtView(int page, float frac, float zRel, float viewX, boolean locked) {
        h.post(() -> { pendingPage = page; pendingFrac = frac; pendingZ = zRel; pendingX = viewX; zoomLocked = locked; });
    }

    void undo() { h.post(() -> doUndo(false)); }

    void redo() { h.post(() -> doRedo(false)); }

    void historyTo(int pos) {
        h.post(() -> {
            if (book == null || live != null) return;
            clearSelection();
            while (undo.size() > pos && !undo.isEmpty()) stepUndo();
            while (undo.size() < pos && !redo.isEmpty()) stepRedo();
            applyPaper();
            edited();
        });
    }

    void addPage() { h.post(() -> doAddPage(currentPage())); }

    void clearPage() { pageOp("clear", -2, 0); }

    void setBackground(int bg) { h.post(() -> doSetBg(currentPage(), bg)); }

    /** Page look (spacing, strength, line colour, margins) for the current page, or every page. Undoable. */
    void setLook(boolean all, float spacing, float strength, int lineColor, int margins) {
        h.post(() -> {
            if (book == null) return;
            Notebook.Page want = new Notebook.Page(0);
            want.spacing = spacing; want.strength = strength; want.lineColor = lineColor; want.margins = margins;
            ArrayList<Act> acts = new ArrayList<>();
            int c = currentPage();
            for (int i = 0; i < book.pages.size(); i++) {
                Notebook.Page p = book.pages.get(i);
                if ((!all && i != c) || p.pdf >= 0 || p.sameStyle(want)) continue;
                Notebook.Page from = new Notebook.Page(0);
                from.copyStyle(p);
                acts.add(new SetLook(p, from, want));
                p.copyStyle(want);
            }
            if (acts.isEmpty()) return;
            push(acts.size() == 1 ? acts.get(0) : new MultiAct(acts));
            edited();
        });
    }

    void setPaper(int paper) {
        h.post(() -> {
            if (book == null || book.paper == paper) return;
            final int from = book.paper;
            push(new Act() {
                void undo(Notebook b) { b.paper = from; }
                void redo(Notebook b) { b.paper = paper; }
            });
            book.paper = paper;
            applyPaper();
            dropTextTextures();
            edited();
        });
    }

    // page overview
    void gotoPage(int i) {
        h.post(() -> {
            if (book == null || i < 0 || i >= book.pages.size()) return;
            oy = tops[i] - topPad / z;
            clampView();
            needCache = true;
            wantFront = true;
            requestFrame();
            notifyState();
        });
    }

    /** Bookmark the page (title may be ""), or remove its bookmark with title == null. i = -2: current page. */
    void setBookmark(int i0, String title) {
        h.post(() -> {
            int i = i0 == -2 ? currentPage() : i0;
            if (book == null || i < 0 || i >= book.pages.size()) return;
            book.pages.get(i).mark = title;
            notifyState();
            docChanged();
        });
    }

    /** Page operation; i == -2 means the page currently in view. */
    void pageOp(String op, int i0, int arg) {
        h.post(() -> {
            int i = i0 == -2 ? currentPage() : i0;
            if (book == null || i < 0 || i >= book.pages.size()) return;
            clearSelection();
            ArrayList<Notebook.Page> before = new ArrayList<>(book.pages);
            switch (op) {
                case "delete":
                    if (book.pages.size() <= 1) { toast("A notebook needs at least one page"); return; }
                    book.pages.remove(i);
                    break;
                case "duplicate": { // its own copies of the ink: edits on one page never touch the other
                    Notebook.Page d = book.pages.get(i).copyShallow();
                    ArrayList<Stroke> own = new ArrayList<>();
                    for (Stroke s : d.strokes()) own.add(s.shifted(0, 0));
                    d.setStrokes(own);
                    book.pages.add(i + 1, d);
                    break;
                }
                case "move": {
                    int to = Math.max(0, Math.min(book.pages.size() - 1, arg));
                    if (to == i) return;
                    book.pages.add(to, book.pages.remove(i));
                    break;
                }
                case "insert":
                    book.pages.add(i + 1, new Notebook.Page(book.pages.get(i).pdf >= 0 ? Notebook.BG_LINED : book.pages.get(i).bg));
                    break;
                case "bg":
                    doSetBg(i, arg);
                    return;
                case "clear": { // with layers: only the active layer's ink and items on this page
                    Notebook.Page p = book.pages.get(i);
                    final int al = book.activeLayer;
                    boolean all = book.layers.size() < 2;
                    ArrayList<Stroke> ks = new ArrayList<>();
                    ArrayList<Notebook.Item> ki = new ArrayList<>();
                    if (!all) {
                        for (Stroke st : p.strokes()) if (st.layer != al) ks.add(st);
                        for (Notebook.Item it : p.items()) if (it.layer != al) ki.add(it);
                    }
                    if (ks.size() == p.strokes().size() && ki.size() == p.items().size()) return;
                    Object[] b4 = pageState(p);
                    p.setStrokes(ks);
                    p.setItems(ki);
                    push(new PageEdit(p, b4, pageState(p)));
                    edited();
                    toast((all ? "Page cleared" : "Layer cleared on this page") + " · undo brings it back");
                    return;
                }
                case "bgAll": {
                    for (Notebook.Page p : book.pages) if (p.pdf < 0 && p.bg != arg) { push(new SetBg(p, p.bg, arg)); p.bg = arg; }
                    edited();
                    return;
                }
                default:
                    return;
            }
            push(new PagesEdit(before, new ArrayList<>(book.pages)));
            edited();
        });
    }

    /** Renders page i to a bitmap off the ink thread; result delivered on the main thread. */
    void pageThumb(int i, int widthPx, java.util.function.Consumer<Bitmap> cb) {
        h.post(() -> {
            if (book == null || i >= book.pages.size()) return;
            final Notebook.Page p = book.pages.get(i).snapCopy(); // an unread page is read on the worker
            final Notebook.Page prev = i > 0 ? book.pages.get(i - 1).snapCopy() : null;
            final Notebook.Page next = i + 1 < book.pages.size() ? book.pages.get(i + 1).snapCopy() : null;
            final Notebook nb = book.metaCopy();
            worker.execute(() -> {
                Bitmap b = Render.pageBitmap(nb, dir, p, prev, next, p.pdf >= 0 ? pdf() : null, widthPx);
                main.post(() -> cb.accept(b));
            });
        });
    }

    /** Snapshot of the notebook for export, delivered on the main thread. */
    void snapshot(java.util.function.Consumer<Notebook> cb) {
        h.post(() -> { if (book != null) { Notebook s = book.snapshot(); main.post(() -> cb.accept(s)); } });
    }

    // ---- layers (the whole notebook's; see Notebook.Layer) -----------------------------------------

    /** The layer list changed in a way that redraws (visibility, order, contents). */
    private void layersChanged() {
        needRebuild = true;
        needCache = true;
        dropTextTextures();
        requestFrame();
        notifyState();
        docChanged();
    }

    void addLayer(String name) {
        h.post(() -> {
            if (book == null) return;
            int id = book.newLayerId();
            if (id < 0) { toast("That's as many layers as a notebook can have"); return; }
            int at = book.layerRank(book.activeLayer) + 1; // just above the active one
            Notebook.Layer l = new Notebook.Layer(id, name);
            book.layers.add(Math.min(at, book.layers.size()), l);
            book.activeLayer = id;
            clearSelection();
            layersChanged();
        });
    }

    void setActiveLayer(int id) {
        h.post(() -> {
            if (book == null || book.layer(id) == null || book.activeLayer == id) return;
            clearSelection(); // a selection belongs to the layer it was made on
            book.activeLayer = id;
            notifyState();
            docChanged();
        });
    }

    void setLayerVisible(int id, boolean visible) {
        h.post(() -> {
            Notebook.Layer l = book == null ? null : book.layer(id);
            if (l == null || l.visible == visible) return;
            l.visible = visible;
            if (!visible && hasSel()) clearSelection();
            layersChanged();
        });
    }

    void renameLayer(int id, String name) {
        h.post(() -> {
            Notebook.Layer l = book == null ? null : book.layer(id);
            if (l == null || name == null || name.trim().isEmpty()) return;
            l.name = name.trim();
            notifyState();
            docChanged();
        });
    }

    /** dir +1 = up (drawn later, on top), -1 = down. */
    void moveLayer(int id, int dir) {
        h.post(() -> {
            if (book == null) return;
            int i = book.layerRank(id), j = i + dir;
            if (book.layer(id) == null || j < 0 || j >= book.layers.size()) return;
            java.util.Collections.swap(book.layers, i, j);
            layersChanged();
        });
    }

    /** Deletes a layer and everything on it, on every page (one undo step). */
    void deleteLayer(int id) {
        h.post(() -> {
            if (book == null || book.layers.size() < 2 || book.layer(id) == null) return;
            clearSelection();
            ArrayList<Notebook.Layer> before = Notebook.copyLayers(book.layers);
            int activeBefore = book.activeLayer;
            ArrayList<PageEdit> edits = new ArrayList<>();
            for (Notebook.Page p : book.pages) {
                boolean has = false;
                for (Stroke st : p.strokes()) if (st.layer == id) { has = true; break; }
                if (!has) for (Notebook.Item it : p.items()) if (it.layer == id) { has = true; break; }
                if (!has) continue;
                Object[] b4 = pageState(p);
                p.strokes().removeIf(st -> st.layer == id);
                p.items().removeIf(it -> it.layer == id);
                edits.add(new PageEdit(p, b4, pageState(p)));
            }
            int rank = book.layerRank(id);
            book.layers.remove(rank);
            if (book.activeLayer == id) book.activeLayer = book.layers.get(Math.max(0, rank - 1)).id;
            ArrayList<Act> acts = new ArrayList<>();
            acts.add(new Multi(edits));
            acts.add(new LayersEdit(before, activeBefore, Notebook.copyLayers(book.layers), book.activeLayer));
            push(new MultiAct(acts));
            layersChanged();
        });
    }

    /** Moves everything on a layer onto the layer below it and removes the layer (one undo step). */
    void mergeLayerDown(int id) {
        h.post(() -> {
            if (book == null || book.layer(id) == null) return;
            int rank = book.layerRank(id);
            if (rank == 0) return;
            int into = book.layers.get(rank - 1).id;
            clearSelection();
            ArrayList<Notebook.Layer> before = Notebook.copyLayers(book.layers);
            int activeBefore = book.activeLayer;
            ArrayList<PageEdit> edits = new ArrayList<>();
            for (Notebook.Page p : book.pages) {
                boolean has = false;
                for (Stroke st : p.strokes()) if (st.layer == id) { has = true; break; }
                if (!has) for (Notebook.Item it : p.items()) if (it.layer == id) { has = true; break; }
                if (!has) continue;
                Object[] b4 = pageState(p);
                ArrayList<Stroke> ns = new ArrayList<>(p.strokes().size());
                for (Stroke st : p.strokes()) ns.add(st.layer == id ? st.onLayer(into) : st);
                ArrayList<Notebook.Item> ni = new ArrayList<>(p.items().size());
                for (Notebook.Item it : p.items()) ni.add(it.layer == id ? it.onLayer(into) : it);
                p.setStrokes(ns);
                p.setItems(ni);
                edits.add(new PageEdit(p, b4, pageState(p)));
            }
            book.layers.remove(rank);
            if (book.activeLayer == id) book.activeLayer = into;
            ArrayList<Act> acts = new ArrayList<>();
            acts.add(new Multi(edits));
            acts.add(new LayersEdit(before, activeBefore, Notebook.copyLayers(book.layers), book.activeLayer));
            push(new MultiAct(acts));
            layersChanged();
        });
    }

    /** Moves the selection onto another layer (it stays selected only if that's the active one). */
    void selToLayer(int id) {
        h.post(() -> {
            if (!hasSel() || book.layer(id) == null) return;
            ArrayList<Stroke> ns = new ArrayList<>();
            ArrayList<Notebook.Item> ni = new ArrayList<>();
            for (Stroke st : selStrokes) ns.add(st.onLayer(id));
            for (Notebook.Item it : selItems) ni.add(it.onLayer(id));
            replaceSelection(ns, ni, id == book.activeLayer);
            if (id != book.activeLayer) clearSelection();
            needRebuild = true;
            requestFrame();
            Notebook.Layer l = book.layer(id);
            toast("Moved to \"" + l.name + "\"" + (l.visible ? "" : " (hidden)"));
        });
    }

    // selection actions
    void selDelete() { h.post(() -> { if (hasSel()) replaceSelection(new ArrayList<>(), new ArrayList<>(), false); }); }

    /**
     * Beautify the selection (or the whole current page): straighten lines, lay them on the ruled lines, even size
     * and spacing, smooth wobble. Worked out on the worker thread; applied as one undo step only if those strokes
     * are still on the page untouched. {@code done} gets a short summary on the main thread.
     */
    void beautify(boolean wholePage, int strength, Beautify.Reader reader, java.util.function.Consumer<String> done) {
        h.post(() -> {
            if (!wholePage && !hasSel()) return;
            final int pg = wholePage ? currentPage() : selPage;
            Notebook.Page p = book.pages.get(pg);
            final ArrayList<Stroke> src = new ArrayList<>(wholePage ? p.strokes() : selStrokes);
            if (wholePage) src.removeIf(st -> st.layer != book.activeLayer); // the active layer's writing
            if (src.isEmpty()) { main.post(() -> done.accept("Nothing to beautify on this page")); return; }
            final Beautify.Paper paper = paperOf(p);
            worker.execute(() -> {
                Beautify.Report rep = new Beautify.Report();
                java.util.List<Stroke> out = Beautify.run(src, strength, paper, reader, rep);
                Log.i(Gl.TAG, "beautify (" + (wholePage ? "page" : "selection") + ", " + strength + "): " + rep);
                h.post(() -> {
                    Notebook.Page cur = book.pages.get(pg);
                    IdentityHashMap<Stroke, Stroke> map = new IdentityHashMap<>();
                    for (int i = 0; i < src.size(); i++) map.put(src.get(i), out.get(i));
                    int found = 0;
                    for (Stroke s : cur.strokes()) if (map.containsKey(s)) found++;
                    if (found != src.size()) { main.post(() -> done.accept("The page changed, try again")); return; }
                    Object[] before = pageState(cur);
                    ArrayList<Stroke> next = new ArrayList<>(cur.strokes().size());
                    for (Stroke s : cur.strokes()) { Stroke r = map.get(s); next.add(r != null ? r : s); }
                    cur.setStrokes(next);
                    push(new PageEdit(cur, before, pageState(cur)));
                    if (!wholePage && selPage == pg) {
                        ArrayList<Stroke> ns = new ArrayList<>();
                        for (Stroke s : selStrokes) { Stroke r = map.get(s); ns.add(r != null ? r : s); }
                        select(pg, ns, selItems);
                    }
                    needCache = true;
                    edited();
                    requestFrame();
                    String msg = rep.lines == 0 ? "No handwriting found to beautify"
                            : "Tidied " + rep.lines + (rep.lines == 1 ? " line" : " lines") + " · undo to go back";
                    main.post(() -> done.accept(msg));
                });
            });
        });
    }

    // ---- convert as I write ------------------------------------------------------------------

    private final ArrayList<Stroke> autoPending = new ArrayList<>();
    private int autoPage = -1;
    private Notebook.Item autoLast; // the text box the last conversion made (next words on its line join it)
    private ArrayList<Stroke> autoLastInk = new ArrayList<>(); // the handwriting behind autoLast, to read it again
    private int autoLastPage = -1;
    private final Runnable autoRun = this::autoConvert;
    private static final long AUTO_PAUSE_MS = 1200;

    private boolean autoTextOn() { return app.getSharedPreferences("nib", Context.MODE_PRIVATE).getBoolean("autoText", false); }

    /** "Convert as I write": collect pen strokes; after a pause in writing they become text. */
    private void queueAutoText(Stroke s, int page) {
        if (!autoTextOn()) { autoPending.clear(); return; }
        if (page != autoPage) { autoPending.clear(); autoPage = page; }
        autoPending.add(s);
        h.removeCallbacks(autoRun);
        h.postDelayed(autoRun, AUTO_PAUSE_MS);
    }

    private void autoConvert() {
        if (autoPending.isEmpty() || !autoTextOn()) return;
        ArrayList<Stroke> ink = new ArrayList<>(autoPending);
        int pg = autoPage;
        autoPending.clear();
        int lang = Handwriting.convertLang(app);
        Notebook.Item prev = autoLastPage == pg && autoLast != null && book != null && pg < book.pages.size()
                && book.pages.get(pg).items().contains(autoLast) ? autoLast : null;
        // strokes added onto the last converted words (a matra, a dot, the top line drawn last): read that ink again with them
        if (prev != null && !autoLastInk.isEmpty()) {
            float l = Float.MAX_VALUE, t = Float.MAX_VALUE, rr = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
            for (Stroke s : ink) { l = Math.min(l, s.l); t = Math.min(t, s.t); rr = Math.max(rr, s.r); b = Math.max(b, s.b); }
            RectF box = new RectF(prev.x - prev.size * 0.4f, prev.y - prev.size * 0.7f, prev.x + prev.w + prev.size * 0.4f, prev.y + prev.h + prev.size * 0.7f);
            if (RectF.intersects(box, new RectF(l, t, rr, b))) {
                ArrayList<Stroke> all = new ArrayList<>(autoLastInk);
                all.addAll(ink);
                Notebook.Item target = prev;
                Handwriting.convert(all, lang, "", msg -> main.post(() -> listener.onToast(msg)), r -> h.post(() -> applyAutoRedo(pg, ink, all, target, r)));
                return;
            }
        }
        // the box this writing probably continues: its text helps the recognizer
        String pre = prev != null ? prev.text : "";
        Handwriting.convert(ink, lang, pre, msg -> main.post(() -> listener.onToast(msg)), r -> h.post(() -> applyAutoText(pg, ink, r, lang)));
    }

    /** Re-read of the last converted words with strokes added onto them: the box's text is replaced (one undo step). */
    private void applyAutoRedo(int pg, ArrayList<Stroke> added, ArrayList<Stroke> all, Notebook.Item target, Handwriting.Converted r) {
        if (book == null || pg >= book.pages.size()) return;
        Notebook.Page p = book.pages.get(pg);
        if (!p.items().contains(target) || r.error != null || r.text.trim().isEmpty()) return;
        IdentityHashMap<Stroke, Boolean> gone = new IdentityHashMap<>();
        for (Stroke s : added) gone.put(s, Boolean.TRUE);
        int found = 0;
        for (Stroke s : p.strokes()) if (gone.containsKey(s)) found++;
        if (found != added.size()) return;
        String text = r.text.trim().replace('\n', ' ');
        float maxW = canvas() ? Math.max(400, target.w + 300) : Math.max(120, PW - target.x - 20);
        StaticLayout sl = Render.textLayout(text, target.size, target.color, maxW, true, target.style);
        float w = 0;
        for (int i = 0; i < sl.getLineCount(); i++) w = Math.max(w, sl.getLineWidth(i));
        float bw = Math.max(40, Math.min(maxW, w + 6));
        sl = Render.textLayout(text, target.size, target.color, bw, true, target.style);
        Object[] before = pageState(p);
        p.strokes().removeIf(gone::containsKey);
        p.items().remove(target);
        Notebook.Item it = new Notebook.Item(Notebook.Item.TEXT, target.x, target.y, bw, sl.getHeight(), text, target.size, target.color, Notebook.Item.handWithInk(all), target.style).lay(target.layer);
        p.items().add(it);
        autoLast = it;
        autoLastInk = all;
        push(new PageEdit(p, before, pageState(p)));
        dropTextTextures();
        needCache = true;
        edited();
        requestFrame();
    }

    /** Swaps the written strokes for text (one undo step): joins the previous box when the words continue its line. */
    private void applyAutoText(int pg, ArrayList<Stroke> ink, Handwriting.Converted r, int lang) {
        if (book == null || pg >= book.pages.size()) return;
        if (r.error != null || r.text.trim().isEmpty()) { if (r.error != null && !r.error.startsWith("Couldn't read any")) main.post(() -> listener.onToast(r.error)); return; }
        Notebook.Page p = book.pages.get(pg);
        IdentityHashMap<Stroke, Boolean> gone = new IdentityHashMap<>();
        for (Stroke s : ink) gone.put(s, Boolean.TRUE);
        int found = 0;
        for (Stroke s : p.strokes()) if (gone.containsKey(s)) found++;
        if (found != ink.size()) return; // erased or moved meanwhile: leave it
        float l = Float.MAX_VALUE, t = Float.MAX_VALUE, rr = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
        java.util.HashMap<Integer, Integer> colours = new java.util.HashMap<>();
        for (Stroke s : ink) { l = Math.min(l, s.l); t = Math.min(t, s.t); rr = Math.max(rr, s.r); b = Math.max(b, s.b); colours.merge(s.color, s.n, Integer::sum); }
        int colour = penColor, most = -1;
        for (java.util.Map.Entry<Integer, Integer> e : colours.entrySet()) if (e.getValue() > most) { most = e.getValue(); colour = e.getKey(); }
        float size = Math.max(12, Math.min(90, r.lineHeight > 0 ? r.lineHeight / 1.4f : textSize));
        String text = r.text.trim();
        float x = l, y = t;
        int read = r.lang >= 0 ? r.lang : lang;
        boolean rtl = read >= 0 && Handwriting.LANG_SCRIPT[read] == Beautify.ARABIC;
        Notebook.Item prev = autoLastPage == pg && autoLast != null && p.items().contains(autoLast) ? autoLast : null;
        if (prev != null) {
            float cy = (t + b) / 2, near = 3.5f * prev.size;
            boolean sameLine = cy > prev.y - prev.size * 0.3f && cy < prev.y + prev.h + prev.size * 0.3f;
            boolean follows = rtl ? (rr <= prev.x + prev.size && prev.x - rr < near) : (l >= prev.x + prev.w - prev.size && l - (prev.x + prev.w) < near);
            if (sameLine && follows) {
                text = prev.text + " " + text;
                x = Math.min(prev.x, l);
                y = prev.y;
                size = prev.size;
                colour = prev.color;
            } else prev = null;
        }
        float maxW = canvas() ? Math.max(400, rr - x + 200) : Math.max(120, PW - x - 20);
        StaticLayout sl = Render.textLayout(text, size, colour, maxW, true);
        float w = 0;
        for (int i = 0; i < sl.getLineCount(); i++) w = Math.max(w, sl.getLineWidth(i));
        float bw = Math.max(40, Math.min(maxW, w + 6));
        sl = Render.textLayout(text, size, colour, bw, true);
        Object[] before = pageState(p);
        p.strokes().removeIf(gone::containsKey);
        if (prev != null) p.items().remove(prev);
        ArrayList<Stroke> boxInk = new ArrayList<>(prev != null ? autoLastInk : new ArrayList<>());
        boxInk.addAll(ink);
        Notebook.Item it = new Notebook.Item(Notebook.Item.TEXT, x, y, bw, sl.getHeight(), text, size, colour, Notebook.Item.handWithInk(boxInk)).lay(book.activeLayer);
        p.items().add(it);
        autoLastInk = boxInk;
        autoLast = it;
        autoLastPage = pg;
        push(new PageEdit(p, before, pageState(p)));
        dropTextTextures();
        needCache = true;
        edited();
        requestFrame();
    }

    /** Turns converted text back into the handwriting it came from, where the text box now is (one undo step). */
    void restoreInk(TextEdit e) {
        h.post(() -> {
            editing = null;
            if (book == null || e.item == null || e.page >= book.pages.size()) return;
            Notebook.Page p = book.pages.get(e.page);
            if (!p.items().contains(e.item)) return;
            java.util.List<Stroke> ink = e.item.ink();
            if (ink.isEmpty()) return;
            float l = Float.MAX_VALUE, t = Float.MAX_VALUE;
            for (Stroke s : ink) { l = Math.min(l, s.l); t = Math.min(t, s.t); }
            float dx = e.item.x - l, dy = e.item.y - t; // the box may have been moved since
            Object[] before = pageState(p);
            p.items().remove(e.item);
            for (Stroke s : ink) { Stroke q = s.shifted(dx, dy); q.layer = e.item.layer; p.strokes().add(q); }
            push(new PageEdit(p, before, pageState(p)));
            if (autoLast == e.item) { autoLast = null; autoLastInk = new ArrayList<>(); }
            dropTextTextures();
            needCache = true;
            edited();
            requestFrame();
        });
    }

    /** Is there a text box under this screen point? */
    private boolean textAt(float sx, float sy) {
        float x = ox + sx / z, y = oy + sy / z;
        int pg = pageAt(y);
        if (pg < 0 || pg >= book.pages.size()) return false;
        for (Notebook.Item it : book.pages.get(pg).items())
            if (it.type == Notebook.Item.TEXT && book.layerVisible(it.layer) && it.contains(x, y - tops[pg], 4)) return true;
        return false;
    }

    /** Selection bar → Edit text: opens the one selected text box for typing. */
    void editSelectedText() {
        h.post(() -> {
            if (!hasSel() || !selStrokes.isEmpty() || selItems.size() != 1 || selItems.get(0).type != Notebook.Item.TEXT) return;
            Notebook.Item it = selItems.get(0);
            int pg = selPage;
            clearSelection();
            textTap((it.x + 2 - ox) * z, (it.y + 2 + tops[pg] - oy) * z);
        });
    }

    /** The selected ink (no highlighter), for Convert to text; on the main thread. */
    void selInk(java.util.function.Consumer<ArrayList<Stroke>> cb) {
        h.post(() -> {
            if (!hasSel()) return;
            ArrayList<Stroke> ink = new ArrayList<>();
            for (Stroke s : selStrokes) if (!s.hl()) ink.add(s);
            main.post(() -> cb.accept(ink));
        });
    }

    /**
     * Convert to text: the selected handwriting becomes a text box where it was, sized like the writing and in its
     * ink colour (one undo step; undo brings the handwriting back). Highlighter and other items stay.
     */
    void selToText(String text, float lineHeight) {
        h.post(() -> {
            if (!hasSel() || text.trim().isEmpty()) return;
            float l = Float.MAX_VALUE, t = Float.MAX_VALUE, r = -Float.MAX_VALUE;
            java.util.HashMap<Integer, Integer> colours = new java.util.HashMap<>();
            ArrayList<Stroke> keep = new ArrayList<>();
            for (Stroke s : selStrokes) {
                if (s.hl()) { keep.add(s); continue; }
                l = Math.min(l, s.l); t = Math.min(t, s.t); r = Math.max(r, s.r);
                colours.merge(s.color, s.n, Integer::sum);
            }
            if (l == Float.MAX_VALUE) return;
            int colour = penColor;
            int most = -1;
            for (java.util.Map.Entry<Integer, Integer> e : colours.entrySet()) if (e.getValue() > most) { most = e.getValue(); colour = e.getKey(); }
            // a line of handwriting is about 1.4× the font size (ascenders, descenders)
            float size = Math.max(12, Math.min(90, lineHeight > 0 ? lineHeight / 1.4f : textSize));
            float maxW = canvas() ? Math.max(200, r - l) : Math.max(120, PW - l - 20);
            StaticLayout sl = Render.textLayout(text, size, colour, maxW, true);
            float w = 0;
            for (int i = 0; i < sl.getLineCount(); i++) w = Math.max(w, sl.getLineWidth(i));
            float bw = Math.max(40, Math.min(maxW, Math.max(w + 6, r - l)));
            sl = Render.textLayout(text, size, colour, bw, true);
            ArrayList<Notebook.Item> ni = new ArrayList<>(selItems);
            ArrayList<Stroke> ink = new ArrayList<>();
            for (Stroke s : selStrokes) if (!s.hl()) ink.add(s);
            ni.add(new Notebook.Item(Notebook.Item.TEXT, l, t, bw, sl.getHeight(), text, size, colour, Notebook.Item.handWithInk(ink)).lay(book.activeLayer));
            dropTextTextures();
            replaceSelection(keep, ni, true);
        });
    }

    /** The page's ruled lines for Beautify (lined, narrow lined and Cornell paper; everything else has none). */
    private static Beautify.Paper paperOf(Notebook.Page p) {
        if (p.pdf >= 0) return Beautify.Paper.NONE;
        float k = p.spacing > 0 ? p.spacing : 1f;
        switch (p.bg) {
            case Notebook.BG_LINED: case Notebook.BG_CORNELL: return new Beautify.Paper(120, 40 * k);
            case Notebook.BG_NARROW: return new Beautify.Paper(120, 30 * k);
            default: return Beautify.Paper.NONE;
        }
    }

    void selDuplicate() {
        h.post(() -> {
            if (!hasSel()) return;
            Notebook.Page p = book.pages.get(selPage);
            Object[] before = pageState(p);
            ArrayList<Stroke> ns = new ArrayList<>();
            ArrayList<Notebook.Item> ni = new ArrayList<>();
            for (Stroke s : selStrokes) ns.add(s.shifted(24, 24));
            for (Notebook.Item it : selItems) ni.add(it.moved(0, 0, 1, 24, 24));
            p.strokes().addAll(ns);
            p.items().addAll(ni);
            push(new PageEdit(p, before, pageState(p)));
            select(selPage, ns, ni);
            edited();
        });
    }

    void selCopy() {
        h.post(() -> {
            if (!hasSel()) return;
            clipStrokes = new ArrayList<>(selStrokes);
            clipItems = new ArrayList<>(selItems);
            toast("Copied");
            notifyState();
        });
    }

    void selCut() {
        selCopy();
        selDelete();
    }

    void selRecolor(int color) {
        h.post(() -> {
            if (!hasSel()) return;
            ArrayList<Stroke> ns = new ArrayList<>();
            ArrayList<Notebook.Item> ni = new ArrayList<>();
            for (Stroke s : selStrokes) ns.add(s.hl() ? s : s.withColor(color));
            for (Notebook.Item it : selItems) ni.add(it.type == Notebook.Item.TEXT || it.type == Notebook.Item.TAPE ? it.withColor(color) : it);
            replaceSelection(ns, ni, true);
        });
    }

    void paste() {
        h.post(() -> {
            if (book == null || (clipStrokes.isEmpty() && clipItems.isEmpty())) return;
            int pg = currentPage();
            Notebook.Page p = book.pages.get(pg);
            RectF b = bounds(clipStrokes, clipItems);
            float cx = ox + W / (2 * z), cy = oy + H / (2 * z) - tops[pg];
            float dx = cx - b.centerX(), dy = cy - b.centerY();
            Object[] before = pageState(p);
            ArrayList<Stroke> ns = new ArrayList<>();
            ArrayList<Notebook.Item> ni = new ArrayList<>();
            for (Stroke s : clipStrokes) { Stroke q = s.shifted(dx, dy); q.layer = book.activeLayer; ns.add(q); } // pasted onto the active layer
            for (Notebook.Item it : clipItems) ni.add(it.moved(0, 0, 1, dx, dy).lay(book.activeLayer));
            p.strokes().addAll(ns);
            p.items().addAll(ni);
            push(new PageEdit(p, before, pageState(p)));
            if (tool != TOOL_LASSO) {
                tool = TOOL_LASSO;
                main.post(() -> listener.onTool(TOOL_LASSO));
            }
            select(pg, ns, ni);
            edited();
        });
    }

    void clearSelectionAsync() { h.post(this::clearSelection); }

    // text + images
    void commitText(TextEdit e, String text) {
        h.post(() -> {
            if (book == null || e.page >= book.pages.size()) return;
            editing = null;
            Notebook.Page p = book.pages.get(e.page);
            Object[] before = pageState(p);
            if (e.item != null) p.items().remove(e.item);
            String t = text.replaceAll("\\s+$", "");
            if (!t.isEmpty()) {
                boolean hand = e.item != null && e.item.hand(); // editing converted text keeps its handwriting face
                StaticLayout sl = Render.textLayout(t, e.size, e.color, e.width, hand, e.style);
                float w = 0;
                for (int i = 0; i < sl.getLineCount(); i++) w = Math.max(w, sl.getLineWidth(i));
                boolean aligned = Fonts.Style.parse(e.style).align != Fonts.Style.LEFT; // centred / right text keeps the box it's aligned in
                float bw = e.item != null || aligned ? e.width : Math.max(40, Math.min(e.width, w + 6));
                p.items().add(new Notebook.Item(Notebook.Item.TEXT, e.docX, e.docY, bw, sl.getHeight(), t, e.size, e.color, hand ? e.item.pts : null, e.style).lay(e.item != null ? e.item.layer : book.activeLayer)); // keeps its handwriting
            }
            if (!Arrays.deepEquals(before, pageState(p))) {
                push(new PageEdit(p, before, pageState(p)));
                edited();
            } else {
                needCache = true;
                requestFrame();
            }
        });
    }

    /** Redraw every text box (a font was removed). */
    void refreshText() { h.post(() -> { dropTextTextures(); needCache = true; requestFrame(); }); }

    /** Restyle the text box at (x, y) on a page (a font imported while it was being edited). One undo step. */
    void setTextStyleAt(int page, float x, float y, String style) {
        h.post(() -> {
            if (book == null || page >= book.pages.size()) return;
            Notebook.Page p = book.pages.get(page);
            for (int i = p.items().size() - 1; i >= 0; i--) {
                Notebook.Item it = p.items().get(i);
                if (it.type != Notebook.Item.TEXT || Math.abs(it.x - x) > 0.5f || Math.abs(it.y - y) > 0.5f) continue;
                Object[] before = pageState(p);
                StaticLayout sl = Render.textLayout(it.text, it.size, it.color, it.w, it.hand(), style);
                p.items().set(i, new Notebook.Item(it.type, it.x, it.y, it.w, sl.getHeight(), it.text, it.size, it.color, it.pts, style).lay(it.layer));
                push(new PageEdit(p, before, pageState(p)));
                edited();
                return;
            }
        });
    }

    void insertImage(String name, int pxW, int pxH) { insertImage(name, pxW, pxH, PW * 0.6f); }

    /** Places an image from images/ in the middle of the view, at most maxW units wide (stickers: smaller). */
    void insertImage(String name, int pxW, int pxH, float maxW) {
        h.post(() -> {
            if (book == null) return;
            int pg = currentPage();
            Notebook.Page p = book.pages.get(pg);
            float w = Math.min(maxW, pxW / 2f), hh = w * pxH / Math.max(1f, pxW);
            if (hh > p.h * 0.6f) { hh = p.h * 0.6f; w = hh * pxW / Math.max(1f, pxH); }
            float cx = canvas() ? ox + W / (2 * z) : Math.max(w / 2, Math.min(PW - w / 2, ox + W / (2 * z)));
            float cy = canvas() ? oy + H / (2 * z) : Math.max(hh / 2, Math.min(p.h - hh / 2, oy + H / (2 * z) - tops[pg]));
            Object[] before = pageState(p);
            Notebook.Item it = new Notebook.Item(Notebook.Item.IMAGE, cx - w / 2, cy - hh / 2, w, hh, name, 0, 0).lay(book.activeLayer);
            p.items().add(it);
            push(new PageEdit(p, before, pageState(p)));
            tool = TOOL_LASSO;
            main.post(() -> listener.onTool(TOOL_LASSO));
            ArrayList<Notebook.Item> one = new ArrayList<>();
            one.add(it);
            select(pg, new ArrayList<>(), one);
            edited();
        });
    }

    void surfaceChanged(Surface s, int w, int hh) {
        h.post(() -> {
            Diag.log(dir.getName() + ": surface " + w + "x" + hh);
            boolean sizeChanged = w != W || hh != H;
            float cx = ox + W / (2 * z), cy = oy + H / (2 * z);
            gl.setSurface(s, w, hh);
            dropGpu();
            frontRefused = 0; // a new surface may allow it
            setFront(false, "surface changed");
            W = w;
            H = hh;
            if (!viewInit) {
                fit();
                viewInit = true;
            } else if (sizeChanged) {
                float oldFit = fitZ;
                fitZ = (W - 2 * sidePad) / PW;
                z = z / oldFit * fitZ;
                ox = cx - W / (2 * z);
                oy = cy - H / (2 * z);
                clampView();
            }
            needRebuild = true;
            wantFront = true;
            renderNow();
            try {
                s.setFrameRate(144f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
            } catch (Exception ignored) {
            }
        });
    }

    void surfaceDestroyed() {
        CountDownLatch done = new CountDownLatch(1);
        h.post(() -> {
            Diag.log(dir.getName() + ": surface gone");
            if (gl != null) {
                if (gl.hasSurface()) dropGpu(); // a hidden tab or the app in the background keeps no ink or textures
                gl.dropSurface();
            }
            setFront(false, "surface destroyed");
            done.countDown();
        });
        try {
            done.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    /** ev is an owned copy; it is recycled here. */
    void touch(MotionEvent ev, long recvNs) {
        h.post(() -> {
            try {
                if (gl != null && gl.hasSurface() && book != null) handle(ev, recvNs);
            } catch (RuntimeException e) {
                Log.e(Gl.TAG, "touch", e);
            } finally {
                ev.recycle();
            }
        });
    }

    void hover() { penNear = stylusAt = SystemClock.uptimeMillis(); }

    // ---- ruler: a straight edge in screen space; strokes started next to an edge follow it ----------

    private boolean rulerOn;
    private float rcx, rcy, rux, ruy, rhl, rht; // centre, unit direction, half length, half thickness (screen px)
    private int rulerEdge;                       // 0 = free stroke, ±1 = following the top/bottom edge
    private float edgeX, edgeY, rulerX0, rulerY0;  // a point on the followed edge; first projected sample
    private long rulerLenAt;

    void setRuler(boolean on, float cx, float cy, float angle, float halfLen, float halfThick) {
        h.post(() -> {
            rulerOn = on;
            rcx = cx; rcy = cy;
            rux = (float) Math.cos(angle); ruy = (float) Math.sin(angle);
            rhl = halfLen; rht = halfThick;
        });
    }

    /** At pen-down: follow an edge if the pen lands within reach of one (and along the ruler's length). */
    private void rulerPick(float sx, float sy) {
        rulerEdge = 0;
        if (!rulerOn) return;
        float dx = sx - rcx, dy = sy - rcy;
        float along = dx * rux + dy * ruy, across = -dx * ruy + dy * rux; // across > 0: below the centre line
        if (Math.abs(along) > rhl + dp(12)) return;
        float reach = dp(26);
        if (Math.abs(across + rht) < reach) rulerEdge = -1;
        else if (Math.abs(across - rht) < reach) rulerEdge = 1;
        if (rulerEdge == 0) return;
        float off = rht + dp(2.5f) + penWidth * z * 0.5f; // the line lands just outside the edge, like a pen against a ruler
        edgeX = rcx - ruy * off * rulerEdge;
        edgeY = rcy + rux * off * rulerEdge;
    }

    private float[] rulerProject(float sx, float sy) {
        float t = (sx - edgeX) * rux + (sy - edgeY) * ruy;
        return new float[]{edgeX + t * rux, edgeY + t * ruy};
    }

    /** Double tap: back to page width, keeping the tapped spot where it is on screen. */
    private void fitAt(float x, float y) {
        if (W == 0 || zoomLocked) return;
        float dx = ox + x / z, dy = oy + y / z;
        z = fitZ;
        ox = canvas() ? dx - x / z : -sidePad / z;
        oy = dy - y / z;
        clampView();
        needCache = true;
        wantFront = true;
        requestFrame();
        notifyState();
    }

    void fitWidth() {
        h.post(() -> {
            if (W == 0) return;
            z = fitZ;
            ox = -sidePad / z;
            clampView();
            needCache = true;
            wantFront = true;
            requestFrame();
            notifyState();
        });
    }

    void saveNow() { h.post(() -> { h.removeCallbacks(saveR); save(); }); }

    void release() {
        h.post(() -> {
            h.removeCallbacks(saveR);
            if (book != null && dirtyDoc) save();
            if (choreo != null) choreo.removeFrameCallback(this);
            if (gl != null) gl.release();
            io.shutdown();
            inkAhead.shutdownNow();
            worker.execute(() -> { if (pdf != null) pdf.close(); });
            worker.shutdown();
            thread.quitSafely();
        });
    }

    // ---- layout + view ---------------------------------------------------------------------

    private void relayout() {
        int n = book.pages.size();
        tops = new float[n];
        float y = 0;
        for (int i = 0; i < n; i++) {
            tops[i] = y;
            y += book.pages.get(i).h + GAP;
        }
        docH = y - GAP;
    }

    private int pageAt(float docY) {
        int i = 0;
        while (i + 1 < tops.length && docY >= tops[i + 1] - GAP / 2) i++;
        return i;
    }

    private void applyPaper() {
        if (gl == null || book == null) return;
        gl.setPaper(Render.PAPER[Math.max(0, Math.min(2, book.paper))], shadow, book.paper == Notebook.PAPER_DARK);
    }

    private void fit() {
        sidePad = 0; // the page fills the screen edge to edge
        fitZ = (W - 2 * sidePad) / PW;
        z = fitZ;
        ox = -sidePad / z;
        oy = 0; // start full-screen; topPad only lets the page be pulled down clear of the floating bars
        if (pendingZ > 0 && pendingPage >= 0 && pendingPage < tops.length) { // reopened where it was left
            z = fitZ * Math.max(minZ() / fitZ, Math.min(maxZoomRel, pendingZ));
            ox = pendingX;
            oy = tops[pendingPage] + pendingFrac * book.pages.get(pendingPage).h;
        } else if (pendingPage >= 0 && pendingPage < tops.length) // opened from a search hit
            oy = pendingPage == 0 ? 0 : tops[pendingPage] - topPad / z;
        else if (coverOn()) oy = -Notebook.PH; // nothing saved: open at the cover, like a real notebook
        pendingPage = -1;
        pendingZ = 0;
        clampView();
    }

    private int pendingPage = -1;
    private float pendingFrac, pendingZ, pendingX;
    private float maxZoomRel = 8f;
    private boolean zoomLocked;
    private long zoomPostedAt;

    /** Open the notebook at page i (before the first frame). */
    void openAtPage(int i) { h.post(() -> pendingPage = i); }

    private boolean canvas() { return book != null && book.canvas; }

    // ---- cover: a page above page 1 showing the notebook's cover (Covers); not written on, not counted, not exported ----

    private boolean coverOn() { return book != null && book.cover != null && !book.canvas; }

    /** The notebook's cover changed (null = none). */
    void setCover(String cover) {
        h.post(() -> {
            if (book == null) return;
            book.cover = cover;
            for (Iterator<Map.Entry<String, Tex>> it = texs.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, Tex> e = it.next();
                if (e.getKey().startsWith("cover:")) { gl.deleteTex(e.getValue().id); texBytes -= e.getValue().bytes; it.remove(); }
            }
            clampView();
            needCache = true;
            wantFront = true;
            requestFrame();
            notifyState();
        });
    }

    private void drawCover() {
        if (!coverOn() || oy >= 0) return;
        final String cv = book.cover, title = book.title;
        Tex t = tex("cover:" + cv + ":" + title.hashCode(), () -> Covers.bitmap(app, cv, title, 1200));
        if (t != null) gl.tex(t.id, 0, -Notebook.PH, PW, Notebook.PH, 1);
        else gl.page(0, -Notebook.PH, PW, Notebook.PH, 0, -Notebook.PH, Notebook.BG_BLANK, true, false); // until it's ready
    }

    private float minZ() { return fitZ * (canvas() ? 0.1f : 0.45f); }

    private void clampView() {
        z = Math.max(minZ(), Math.min(fitZ * maxZoomRel, z));
        if (canvas()) return; // infinite canvas: go anywhere
        float vw = W / z, vh = H / z;
        if (PW * z <= W) ox = (PW - vw) / 2;
        else ox = Math.max(-sidePad / z, Math.min(PW - vw + sidePad / z, ox));
        float minY = -topPad / z - (coverOn() ? Notebook.PH : 0), maxY = docH - vh + botPad / z; // the cover sits above page 1
        if (maxY < minY) maxY = minY;
        oy = Math.max(minY, Math.min(maxY, oy));
    }

    private int currentPage() {
        if (book == null || W == 0) return 0;
        return pageAt(oy + H / (2 * z));
    }

    // ---- rendering -------------------------------------------------------------------------

    private void requestFrame() {
        if (!frameRequested && choreo != null) {
            frameRequested = true;
            choreo.postFrameCallback(this);
        }
    }

    private boolean dragging() { return gest == G_SEL; }

    @Override
    public void doFrame(long frameTimeNanos) {
        frameRequested = false;
        if (gl == null || !gl.hasSurface() || book == null) return;
        long t0 = System.nanoTime();
        try { frame(frameTimeNanos); } finally {
            if (gest == G_INK) { long d = System.nanoTime() - t0; stFrames++; stFrameNs += d; stFrameMax = Math.max(stFrameMax, d); }
        }
    }

    private void frame(long frameTimeNanos) {
        frameNo++;
        if (flinging) stepFling(frameTimeNanos);
        if (needRebuild) rebuild();
        boolean laser = laserActive(), taping = gest == G_TAPE;
        if (gesturing || flinging || dragging() || laser || taping) {
            if (front) gl.requestFront(false);
            wantFront = false;
            gl.setView(ox, oy, z);
            gl.bindWindow();
            gl.noScissor();
            drawScene();
            if (laser) drawLaser();
            if (taping) drawTapeDraft();
            gl.swap();
            setFront(gl.isFront(), dragging() ? "moving selection" : flinging ? "fling" : "pan/zoom");
            checkPage();
            if (dragging() || hasSel()) postSelection();
            if (flinging) requestFrame();
        } else if (needCache || needRebuild || !cacheValid()) {
            renderNow();
        } else if (needPresent || (wantFront && !front && canFront())) {
            present(); // the cache already holds the scene (and any new ink): just show it
        }
        evictTextures();
        if (hud) postStats();
    }

    // ---- laser pointer: a glowing trail that fades; never saved --------------------------------

    static final int LASER_TRAIL_MODE = 0, LASER_DOT = 1, LASER_INK = 2;
    private static final long LASER_TIP = 350, INK_FADE = 380;
    private int laserColor = 0xff3b30, laserMode = LASER_TRAIL_MODE;
    private long laserLen = 520;       // trail: how long a point stays; vanishing ink: wait after the last lift
    private float laserSize = 1f;
    private boolean laserHover;
    private final ArrayList<float[]> laserPts = new ArrayList<>(); // doc x, doc y, time (ms); x = NaN breaks the line
    private boolean laserDown, hovering;
    private float[] hoverPt;
    private long laserUpAt;

    /** colour (rgb), style, trail length / ink wait in ms, size factor, follow the hovering pen. */
    void setLaser(int rgb, int mode, int lenMs, float size, boolean hover) {
        h.post(() -> {
            laserColor = rgb & 0xffffff;
            laserMode = mode;
            laserLen = lenMs;
            laserSize = size;
            laserHover = hover;
            laserPts.clear();
        });
    }

    /** Pen hovering over the page (x,y in view px); exit = it moved away. */
    void hoverAt(float x, float y, boolean exit) {
        penNear = stylusAt = SystemClock.uptimeMillis();
        h.post(() -> {
            if (!laserHover || tool != TOOL_LASER || gest == G_LASER || gl == null || book == null) return;
            if (exit) {
                if (hovering) { hovering = false; laserUpAt = SystemClock.uptimeMillis(); laserBreak(); requestFrame(); }
                return;
            }
            hovering = true;
            float[] pt = {ox + x / z, oy + y / z, SystemClock.uptimeMillis()};
            if (laserMode == LASER_TRAIL_MODE) laserPts.add(pt); else hoverPt = pt; // dot / vanishing ink: hover only points

            requestFrame();
        });
    }

    private void laserBreak() {
        if (!laserPts.isEmpty() && !Float.isNaN(laserPts.get(laserPts.size() - 1)[0]))
            laserPts.add(new float[]{Float.NaN, Float.NaN, SystemClock.uptimeMillis()});
    }

    private void laserAdd(MotionEvent ev) {
        int hs = ev.getHistorySize();
        for (int i = 0; i < hs; i++)
            laserPts.add(new float[]{ox + ev.getHistoricalX(i) / z, oy + ev.getHistoricalY(i) / z, ev.getHistoricalEventTime(i)});
        laserPts.add(new float[]{ox + ev.getX() / z, oy + ev.getY() / z, ev.getEventTime()});
        requestFrame();
    }

    private boolean laserActive() {
        if (hovering && SystemClock.uptimeMillis() - penNear > 400) { hovering = false; laserUpAt = penNear; } // no hover-exit came
        if (!laserDown && !hovering && hoverPt != null && SystemClock.uptimeMillis() - laserUpAt >= LASER_TIP) hoverPt = null;
        return laserDown || hovering || !laserPts.isEmpty() || SystemClock.uptimeMillis() - laserUpAt < LASER_TIP;
    }

    private void drawLaser() {
        long now = SystemClock.uptimeMillis();
        boolean live = laserDown || hovering;
        float inkA = 1f; // vanishing ink: everything fades together once the pen has rested long enough
        if (laserMode == LASER_INK) {
            if (!live && now - laserUpAt > laserLen) {
                inkA = Math.max(0f, 1f - (now - laserUpAt - laserLen) / (float) INK_FADE);
                if (inkA <= 0f) laserPts.clear();
            }
        } else {
            long keep = laserMode == LASER_DOT ? 0 : laserLen;
            while (laserPts.size() > 1 && now - laserPts.get(0)[2] > keep) laserPts.remove(0);
            if (!live && laserPts.size() == 1 && now - laserPts.get(0)[2] > Math.max(keep, LASER_TIP)) laserPts.clear();
        }
        while (!laserPts.isEmpty() && Float.isNaN(laserPts.get(laserPts.size() - 1)[0]) && !live && laserMode != LASER_INK)
            laserPts.remove(laserPts.size() - 1);
        float tipA = live ? 1f : Math.max(0f, 1f - (now - laserUpAt) / (float) LASER_TIP);
        SegBuf b = gl.laser;
        b.clear();
        int n = laserPts.size();
        float px = laserSize / z;
        // three passes: soft glow, coloured core, hot white centre
        float[][] pass = laserMode == LASER_INK ? new float[][]{{dp(6), 0.25f}, {dp(2.2f), 1f}, {dp(0.8f), 0.85f}}
                : new float[][]{{dp(9), 0.22f}, {dp(2.6f), 1f}, {dp(1.1f), 0.9f}};
        int[] col = {laserColor, laserColor, 0xffffff};
        float[] last = null;
        for (int i = n - 1; i >= 0; i--) if (!Float.isNaN(laserPts.get(i)[0])) { last = laserPts.get(i); break; }
        boolean tipOnLine = n > 0 && !Float.isNaN(laserPts.get(n - 1)[0]);
        if (hovering && hoverPt != null && laserMode != LASER_TRAIL_MODE) { last = hoverPt; tipOnLine = true; }
        else if (!hovering && hoverPt != null && !laserDown && laserMode != LASER_TRAIL_MODE && now - laserUpAt < LASER_TIP && (n == 0 || laserPts.get(n - 1)[2] < hoverPt[2])) { last = hoverPt; tipOnLine = true; }
        for (int k = 0; k < 3; k++) {
            float r = pass[k][0] * px, a0 = pass[k][1];
            // each point's width follows its age, so neighbouring pieces meet at the same width (a smooth taper,
            // not a string of beads); overlaps are taken care of by the MAX blend below
            if (laserMode != LASER_DOT) for (int i = 1; i < n; i++) {
                float[] p0 = laserPts.get(i - 1), p1 = laserPts.get(i);
                if (Float.isNaN(p0[0]) || Float.isNaN(p1[0])) continue;
                boolean ink = laserMode == LASER_INK;
                float f0 = ink ? 1f : Math.max(0f, 1f - (now - p0[2]) / (float) laserLen);
                float f1 = ink ? 1f : Math.max(0f, 1f - (now - p1[2]) / (float) laserLen);
                float a = ink ? a0 * inkA : a0 * f1;
                if (a <= 0.01f) continue;
                b.put(p0[0], p0[1], p1[0], p1[1], ink ? r : r * (0.45f + 0.55f * f0), ink ? r : r * (0.45f + 0.55f * f1),
                        ((int) (a * 255) << 24) | col[k]);
            }
            if (last != null && tipOnLine && tipA > 0) {
                float tr = r * (k == 0 ? 1.6f : 1.5f);
                b.put(last[0], last[1], last[0], last[1], tr, tr, ((int) (a0 * tipA * 255) << 24) | col[k]);
            }
        }
        // drawn into a transparent overlay with MAX blending, then laid over the page: where the pieces' round ends
        // overlap, the glow no longer doubles up into darker dots
        gl.bindOverlay();
        gl.drawSegs(b, 0, b.count, 4);
        gl.bindWindow();
        gl.noScissor();
        gl.blitOverlay();
        if (laserActive()) requestFrame();
        else { wantFront = true; needPresent = true; requestFrame(); } // back to front-buffer inking
    }

    // ---- tape: strips that hide what's under them until tapped -----------------------------------

    /** Straight tape's far end: snaps level / upright when the pen is within ~8° of it. */
    private float[] straightEnd() {
        float dx = tx1 - tx0, dy = ty1 - ty0;
        double ang = Math.toDegrees(Math.atan2(dy, dx)), m = ((ang % 90) + 90) % 90;
        if (m < 8 || m > 82) {
            if (Math.abs(dx) >= Math.abs(dy)) return new float[]{tx1, ty0};
            return new float[]{tx0, ty1};
        }
        return new float[]{tx1, ty1};
    }

    // While tape is being laid, the real tape (pattern, torn ends) is rendered on the worker a few times a second and
    // shown; only the newest bit behind the pen, not yet in that picture, is drawn as a plain strip meanwhile.
    private int draftTex, draftCovered, draftGen;
    private boolean draftBusy;
    private final RectF draftBox = new RectF();

    /** The tape as it stands now (doc units), the same way it will be committed. */
    private float[] draftPts() {
        if (!tapeFree) {
            float[] e = straightEnd();
            return new float[]{tx0, ty0, e[0], e[1]};
        }
        float[] raw = java.util.Arrays.copyOf(tpts, 2 * tn + 2);
        raw[2 * tn] = tx1;
        raw[2 * tn + 1] = ty1;
        return simplify(raw, tn + 1, 1.2f);
    }

    private void requestDraftTexture() {
        if (draftBusy || gest != G_TAPE) return;
        final float[] pts = draftPts();
        if (pts.length < 4) return;
        int key = java.util.Arrays.hashCode(pts) * 31 + draftGen;
        if (key == draftKey && draftTex != 0) return; // the pen hasn't moved since the last preview
        draftKey = key;
        final Notebook.Item it = Notebook.Item.tape(pts, tapeThick, tapePattern, tapeColor);
        final int covered = tapeFree ? tn : 2, gen = draftGen;
        final float k = Math.min(z, 2048f / Math.max(1f, Math.max(it.w, it.h)));
        draftBusy = true;
        final long queued = System.nanoTime();
        worker.execute(() -> {
            Bitmap b = null;
            long t0 = System.nanoTime();
            try { b = Render.tapeBitmap(it, k); } catch (Exception e) { Log.w(Gl.TAG, "tape draft", e); }
            long took = (System.nanoTime() - t0) / 1_000_000;
            if (b != null && took >= 30) Log.w(Gl.TAG, String.format(Locale.US, "slow tape preview %dx%d: waited %d ms, drawn in %d ms", b.getWidth(), b.getHeight(),
                    (t0 - queued) / 1_000_000, took));
            final Bitmap bm = b;
            h.post(() -> {
                draftBusy = false;
                if (bm == null) return;
                if (gen != draftGen || gest != G_TAPE || gl == null || !gl.hasSurface()) { bm.recycle(); return; }
                if (draftTex != 0) gl.deleteTex(draftTex);
                draftTex = gl.upload(bm);
                bm.recycle();
                draftBox.set(it.x, it.y, it.x + it.w, it.y + it.h);
                draftCovered = covered;
                requestFrame();
                requestDraftTexture(); // the pen has moved on meanwhile: render again
            });
        });
    }

    /** A just-laid tape's preview texture, shown for it until its own texture is ready (no blank moment). */
    private int handoffTex;
    private final RectF handoffBox = new RectF();
    private Notebook.Item handoffItem;

    private void dropHandoff() {
        if (handoffTex != 0 && gl != null) gl.deleteTex(handoffTex);
        handoffTex = 0;
        handoffItem = null;
    }

    private int draftKey; // the points (and gesture) the current preview was drawn for

    private void dropDraftTexture() {
        draftGen++;
        if (draftTex != 0 && gl != null) gl.deleteTex(draftTex);
        draftTex = 0;
        draftCovered = 0;
    }

    private void drawTapeDraft() {
        gl.resetXform();
        SegBuf b = gl.laser;
        b.clear();
        float r = tapeThick / 2;
        int col = 0xff000000 | (tapeColor & 0xffffff);
        if (!tapeFree) {
            float[] e = straightEnd();
            if (draftTex == 0) b.put(tx0, ty0, e[0], e[1], r, r, col);
        } else {
            int from = draftTex != 0 ? Math.max(1, draftCovered) : 1; // only what the rendered tape doesn't cover yet
            if (tn == 1 && draftTex == 0) b.put(tpts[0], tpts[1], tpts[0], tpts[1], r, r, col);
            for (int i = from; i < tn; i++) b.put(tpts[2 * i - 2], tpts[2 * i - 1], tpts[2 * i], tpts[2 * i + 1], r, r, col);
            if (tn > 0 && (draftTex == 0 || draftCovered < tn || Math.hypot(tx1 - tpts[2 * tn - 2], ty1 - tpts[2 * tn - 1]) > r))
                b.put(tpts[2 * tn - 2], tpts[2 * tn - 1], tx1, ty1, r, r, col);
        }
        if (b.count > 0) gl.drawSegs(b, 0, b.count, 0);
        if (draftTex != 0) gl.tex(draftTex, draftBox.left, draftBox.top, draftBox.width(), draftBox.height(), 1f);
    }

    private void tapeAdd(float x, float y, boolean force) {
        if (tn > 0 && !force && Math.hypot(x - tpts[2 * tn - 2], y - tpts[2 * tn - 1]) < Math.max(2.5f, tapeThick * 0.12f)) return;
        if (2 * tn + 2 > tpts.length) tpts = java.util.Arrays.copyOf(tpts, tpts.length * 2);
        tpts[2 * tn] = x;
        tpts[2 * tn + 1] = y;
        tn++;
    }

    private void tapeBegin(MotionEvent ev) {
        tx0 = tx1 = ox + ev.getX() / z;
        ty0 = ty1 = oy + ev.getY() / z;
        tn = 0;
        dropDraftTexture();
        tapeAdd(tx0, ty0, true);
        tapePage = pageAt(ty0);
        requestFrame();
    }

    private void tapeEvent(MotionEvent ev, int a) {
        int hs = ev.getHistorySize();
        for (int i = 0; i < hs; i++) tapeAdd(ox + ev.getHistoricalX(i) / z, oy + ev.getHistoricalY(i) / z, false);
        tx1 = ox + ev.getX() / z;
        ty1 = oy + ev.getY() / z;
        tapeAdd(tx1, ty1, false);
        if (a == MotionEvent.ACTION_CANCEL) { gest = G_NONE; endTapeFrame(); return; }
        if (a != MotionEvent.ACTION_UP) { requestDraftTexture(); requestFrame(); return; }
        gest = G_NONE;
        float far = 0; // how far the pen got from where it started
        for (int i = 0; i < tn; i++) far = Math.max(far, (float) Math.hypot(tpts[2 * i] - tx0, tpts[2 * i + 1] - ty0));
        if (far * z < dp(8)) { // a tap: peel / re-cover the tape under the pen
            if (!toggleTapeAt(tx0, ty0, false)) toast("Drag across what you want to cover");
            endTapeFrame();
            return;
        }
        float top = tops[tapePage];
        float[] pts;
        if (!tapeFree) {
            float[] e = straightEnd();
            pts = new float[]{tx0, ty0 - top, e[0], e[1] - top};
        } else {
            if (Math.hypot(tx1 - tpts[2 * tn - 2], ty1 - tpts[2 * tn - 1]) > 0.5f) tapeAdd(tx1, ty1, true);
            pts = simplify(tpts, tn, 1.2f);
            for (int i = 1; i < pts.length; i += 2) pts[i] -= top;
        }
        float len = 0;
        for (int i = 2; i + 1 < pts.length; i += 2) len += (float) Math.hypot(pts[i] - pts[i - 2], pts[i + 1] - pts[i - 1]);
        if (len < tapeThick * 0.8f) { endTapeFrame(); return; } // too short to be a strip
        Notebook.Page p = book.pages.get(tapePage);
        Object[] before = pageState(p);
        Notebook.Item made = Notebook.Item.tape(pts, tapeThick, tapePattern, tapeColor).lay(book.activeLayer);
        p.items().add(made);
        push(new PageEdit(p, before, pageState(p)));
        // the finished tape's own texture is drawn on the worker: until it's there, show the preview in its place
        if (draftTex != 0) {
            dropHandoff();
            handoffTex = draftTex;
            draftTex = 0;
            handoffBox.set(draftBox);
            handoffItem = made;
        }
        if (tapePage == book.pages.size() - 1) ensureTrailingPage();
        docChanged();
        notifyState();
        endTapeFrame();
    }

    /** Ramer–Douglas–Peucker: drops points that lie within tol of the line through their neighbours. */
    private static float[] simplify(float[] p, int n, float tol) {
        if (n <= 2) return java.util.Arrays.copyOf(p, 2 * n);
        boolean[] keep = new boolean[n];
        keep[0] = keep[n - 1] = true;
        java.util.ArrayDeque<int[]> st = new java.util.ArrayDeque<>();
        st.push(new int[]{0, n - 1});
        while (!st.isEmpty()) {
            int[] r = st.pop();
            float ax = p[2 * r[0]], ay = p[2 * r[0] + 1], bx = p[2 * r[1]], by = p[2 * r[1] + 1];
            float dx = bx - ax, dy = by - ay, l = (float) Math.hypot(dx, dy);
            int best = -1;
            float bd = tol;
            for (int i = r[0] + 1; i < r[1]; i++) {
                float d = l < 1e-4f ? (float) Math.hypot(p[2 * i] - ax, p[2 * i + 1] - ay)
                        : Math.abs((p[2 * i] - ax) * dy - (p[2 * i + 1] - ay) * dx) / l;
                if (d > bd) { bd = d; best = i; }
            }
            if (best >= 0) {
                keep[best] = true;
                st.push(new int[]{r[0], best});
                st.push(new int[]{best, r[1]});
            }
        }
        int m = 0;
        for (boolean k : keep) if (k) m++;
        float[] out = new float[2 * m];
        int j = 0;
        for (int i = 0; i < n; i++) if (keep[i]) { out[j++] = p[2 * i]; out[j++] = p[2 * i + 1]; }
        return out;
    }

    private void endTapeFrame() {
        gl.laser.clear();
        dropDraftTexture();
        needCache = true;
        wantFront = true;
        needPresent = true;
        requestFrame();
        penNear = SystemClock.uptimeMillis();
    }

    /** Topmost tape at doc point x,y: {page index, item index}, or null. */
    private int[] tapeAt(float x, float y) {
        if (book == null || tops.length == 0) return null;
        float pad = dp(3) / z;
        int p0 = Math.max(0, pageAt(y) - 1), p1 = Math.min(book.pages.size() - 1, pageAt(y) + 1);
        for (int pi = p1; pi >= p0; pi--) {
            ArrayList<Notebook.Item> items = book.pages.get(pi).items();
            for (int i = items.size() - 1; i >= 0; i--) {
                Notebook.Item it = items.get(i);
                if (it.type == Notebook.Item.TAPE && book.layerVisible(it.layer) && it.contains(x, y - tops[pi], pad)) return new int[]{pi, i};
            }
        }
        return null;
    }

    /** Flips the tape at x,y between covering and peeled back (not an undo step: it's how you study). */
    private boolean toggleTapeAt(float x, float y, boolean onlyCovered) {
        int[] hit = tapeAt(x, y);
        if (hit == null) return false;
        ArrayList<Notebook.Item> items = book.pages.get(hit[0]).items();
        Notebook.Item it = items.get(hit[1]);
        if (onlyCovered && it.revealed()) return false;
        items.set(hit[1], it.withRevealed(!it.revealed()));
        needCache = true;
        wantFront = true;
        requestFrame();
        docChanged();
        return true;
    }

    private void drawScene() {
        int first = drawFirst(), last = drawLast();
        if (needRebuild || !inkCovers(pageAt(oy), pageAt(oy + H / z))) rebuild(); // on screen but not built (yet)
        gl.clear(deskColor);
        gl.resetXform();
        drawCover();
        boolean hiRes = z > fitZ * 1.5f;
        for (int i = first; i <= last; i++) {
            Notebook.Page p = book.pages.get(i);
            gl.setLook(p.spacing, p.strength, p.lineColor, canvas() ? 0 : p.margins);
            if (canvas()) gl.page(ox, oy, W / z, H / z, 0, 0, p.bg, true, false); // paper everywhere you look
            else gl.page(0, tops[i], PW, p.h, 0, tops[i], p.pdf >= 0 ? 0 : p.bg, true, i > 0);
            if (p.bg == Notebook.BG_SITE && p.pdf < 0 && !canvas()) { // printed labels of the inspection form
                final int pap = book.paper;
                final float ph = p.h;
                Tex lt = tex("site:top:" + pap, () -> Render.siteBand(pap, ph, true));
                if (lt != null) gl.tex(lt.id, 0, tops[i], PW, 380, 1);
                Tex lb = tex("site:bot:" + pap + ":" + Math.round(ph), () -> Render.siteBand(pap, ph, false));
                if (lb != null) gl.tex(lb.id, 0, tops[i] + ph - 260, PW, 220, 1);
            }
            RectF[] bands = p.pdf < 0 && !canvas() ? Render.labelBands(p.bg, p.h) : null;
            if (bands != null) { // printed labels of the planner templates
                final int pap = book.paper, bgv = p.bg;
                final float ph = p.h;
                for (int bi = 0; bi < bands.length; bi++) {
                    final RectF r = bands[bi];
                    Tex lt = tex("lbl:" + bgv + ":" + pap + ":" + bi + ":" + Math.round(ph), () -> Render.labelBand(bgv, pap, ph, r));
                    if (lt != null) gl.tex(lt.id, r.left, tops[i] + r.top, r.width(), r.height(), 1);
                }
            }
            if (p.pdf >= 0) {
                final int idx = p.pdf;
                Tex t = hiRes ? tex("pdf:" + idx + ":3600", () -> pdfPage(idx, 3600)) : null;
                if (t == null) t = tex("pdf:" + idx + ":1800", () -> pdfPage(idx, 1800));
                if (t != null) gl.tex(t.id, 0, tops[i], PW, p.h, 1);
            }
            for (Notebook.Item it : book.itemsInDrawOrder(p.items())) {
                if (it == editing || it.type == Notebook.Item.TAPE || (i == selPage && selItems.contains(it)) || !book.layerVisible(it.layer)) continue;
                drawItem(it, tops[i]);
            }
        }
        // the notebook never ends on screen: paper continues below the last page (it becomes real when written on)
        if (!canvas() && !book.pages.isEmpty() && docH < oy + H / z) {
            Notebook.Page lastP = book.pages.get(book.pages.size() - 1);
            gl.setLook(lastP.spacing, lastP.strength, lastP.lineColor, lastP.margins);
            gl.page(0, docH, PW, Notebook.PH, 0, docH, lastP.pdf >= 0 ? Notebook.BG_LINED : lastP.bg, true, true);
        }
        // highlighter under ink: every page's highlighter, then the recent one, then every page's ink, then the recent ink
        for (int i = first; i <= last; i++) {
            PageInk pk = inks.get(book.pages.get(i));
            if (pk == null || pk.hl.count == 0) continue;
            gl.setXform(0, 0, 1, 0, tops[i]);
            gl.drawSegs(pk.hl, 0, pk.hl.count, 1);
        }
        gl.resetXform();
        gl.drawSegs(gl.hl, 0, gl.hl.count, 1);
        for (int i = first; i <= last; i++) {
            PageInk pk = inks.get(book.pages.get(i));
            if (pk == null || pk.pen.count == 0) continue;
            gl.setXform(0, 0, 1, 0, tops[i]);
            gl.drawSegs(pk.pen, 0, pk.pen.count, 0);
        }
        gl.resetXform();
        gl.drawSegs(gl.pen, 0, gl.pen.count, 0);
        buildAhead();
        // tape goes over the ink it hides
        for (int i = first; i <= last; i++)
            for (Notebook.Item it : book.itemsInDrawOrder(book.pages.get(i).items()))
                if (it.type == Notebook.Item.TAPE && !(i == selPage && selItems.contains(it)) && book.layerVisible(it.layer)) drawItem(it, tops[i]);
        if (hasSel()) {
            // selection buffers are page-local; the transform adds the page top
            gl.setXform(spx, spy, ss, stx, sty + tops[selPage]);
            for (Notebook.Item it : selItems) if (it.type != Notebook.Item.TAPE) drawItem(it, 0);
            gl.drawSegs(gl.selHl, 0, gl.selHl.count, 1);
            gl.drawSegs(gl.selPen, 0, gl.selPen.count, 0);
            for (Notebook.Item it : selItems) if (it.type == Notebook.Item.TAPE) drawItem(it, 0);
            gl.resetXform();
        }
    }

    private void drawItem(Notebook.Item it, float top) {
        Tex t;
        if (it.type == Notebook.Item.TAPE) {
            final int lvl = z * 1.3f <= 2 ? 2 : z * 1.3f <= 4 ? 4 : 6;
            final Notebook.Item item = it;
            // keyed by look, not identity: covering / revealing reuses the same texture
            String base = "tape:" + Integer.toHexString(it.color) + ":" + it.pattern() + ":" + java.util.Arrays.hashCode(it.pts) + ":" + it.size + ":";
            t = tex(base + lvl, () -> Render.tapeBitmap(item, lvl));
            if (t == null) for (int l : new int[]{2, 4, 6}) {
                Tex o = texs.get(base + l);
                if (o != null) { t = o; break; }
            }
            float al = doomedItems.contains(it) ? 0.28f : it.revealed() ? Render.TAPE_OPEN_ALPHA : 1f;
            if (it == handoffItem) {
                if (t != null) dropHandoff(); // its own texture is ready
                else if (handoffTex != 0) { gl.tex(handoffTex, handoffBox.left, handoffBox.top, handoffBox.width(), handoffBox.height(), al); return; }
            }
            if (t != null) gl.tex(t.id, it.x, it.y + top, it.w, it.h, al);
            return;
        }
        if (it.type == Notebook.Item.IMAGE) {
            final String name = it.text;
            t = tex("img:" + name, () -> Render.image(dir, name, 2048));
        } else {
            final int lvl = z * 1.3f <= 2 ? 2 : z * 1.3f <= 4 ? 4 : 6;
            final Notebook.Item item = it;
            final boolean dk = book.paper == Notebook.PAPER_DARK;
            String base = "txt:" + System.identityHashCode(it) + ":";
            t = tex(base + lvl, () -> textBitmap(item, lvl, dk));
            if (t == null) for (int l : new int[]{2, 4, 6}) {
                Tex o = texs.get(base + l);
                if (o != null) { t = o; break; }
            }
        }
        if (t != null) gl.tex(t.id, it.x, it.y + top, it.w, it.h, 1);
    }

    private static Bitmap textBitmap(Notebook.Item it, int k, boolean dark) {
        StaticLayout sl = Render.textLayout(it.text, it.size, Render.inkColor(it.color, dark), it.w, it.hand(), it.style, dark);
        int bw = Math.max(1, (int) Math.ceil(it.w * k)), bh = Math.max(1, (int) Math.ceil(Math.max(it.h, sl.getHeight()) * k));
        Bitmap b = Bitmap.createBitmap(Math.min(bw, 8192), Math.min(bh, 8192), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(k, k);
        sl.draw(c);
        return b;
    }

    private void dropTextTextures() {
        Iterator<Map.Entry<String, Tex>> it = texs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Tex> e = it.next();
            if (e.getKey().startsWith("txt:")) {
                gl.deleteTex(e.getValue().id);
                texBytes -= e.getValue().bytes;
                it.remove();
            }
        }
    }

    private Render.Pdf pdf() {
        if (!pdfTried) {
            pdfTried = true;
            pdf = Render.Pdf.open(dir);
        }
        return pdf;
    }

    private Bitmap pdfPage(int idx, int w) {
        Render.Pdf p = pdf();
        return p == null ? null : p.render(idx, w);
    }

    /** Cached texture, or null after scheduling it on the worker (redraws when ready). */
    private Tex tex(String key, Callable<Bitmap> make) {
        Tex t = texs.get(key);
        if (t != null) {
            t.used = frameNo;
            return t;
        }
        if (make != null && texPending.add(key)) {
            final long queued = System.nanoTime();
            worker.execute(() -> {
                Bitmap b = null;
                long t0 = System.nanoTime();
                try {
                    b = make.call();
                } catch (Exception e) {
                    Log.e(Gl.TAG, "texture " + key, e);
                }
                if (key.startsWith("tape:") && b != null && System.nanoTime() - queued > 30_000_000L)
                    Log.w(Gl.TAG, String.format(Locale.US, "tape texture %dx%d: waited %d ms, drawn in %d ms", b.getWidth(), b.getHeight(),
                            (t0 - queued) / 1_000_000, (System.nanoTime() - t0) / 1_000_000));
                final Bitmap bm = b;
                h.post(() -> {
                    texPending.remove(key);
                    if (bm == null) return;
                    if (gl == null || !gl.hasSurface()) { bm.recycle(); return; }
                    Tex nt = new Tex();
                    nt.id = gl.upload(bm);
                    nt.bytes = (long) bm.getByteCount() * 4 / 3;
                    nt.used = frameNo;
                    bm.recycle();
                    texs.put(key, nt);
                    texBytes += nt.bytes;
                    needCache = true;
                    wantFront = true;
                    kickDisplay = true; // arrives with no pen on the screen: make sure the display picks it up
                    requestFrame();
                });
            });
        }
        return null;
    }

    private void evictTextures() {
        if (texBytes < 280L << 20) return;
        Iterator<Map.Entry<String, Tex>> it = texs.entrySet().iterator();
        while (it.hasNext() && texBytes > 200L << 20) {
            Tex t = it.next().getValue();
            if (frameNo - t.used > 2) {
                gl.deleteTex(t.id);
                texBytes -= t.bytes;
                it.remove();
            }
        }
    }

    /** Re-render the cache and present it (switching into front-buffer mode when allowed). */
    private void renderNow() {
        if (gest == G_INK) stScenes++;
        if (needRebuild) rebuild();
        gl.setView(ox, oy, z);
        gl.noScissor();
        gl.bindCache();
        drawScene();
        needCache = false;
        cacheOx = ox; cacheOy = oy; cacheZ = z; cacheW = W; cacheH = H;
        present();
    }

    // ---- without front-buffer ink (a GPU that lacks it, or a system that won't switch to it) -----------------------
    // Ink is still drawn into the cache piece by piece; each frame then only copies the cache to the screen. Redrawing
    // the whole scene for every frame of a stroke (as before) made writing lag on such devices, worse on dense pages.

    private float cacheOx = Float.NaN, cacheOy, cacheZ;
    private int cacheW, cacheH;
    /** Front-buffer requests this surface ignored in a row; after a few, stop asking (each try costs an extra swap). */
    private int frontRefused;
    private static final int FRONT_TRIES = 3;

    /** The cache holds the scene for the current view. */
    private boolean cacheValid() {
        return !needCache && !needRebuild && ox == cacheOx && oy == cacheOy && z == cacheZ && W == cacheW && H == cacheH;
    }

    /** Front-buffer ink is possible here (the GPU offers it and the system hasn't kept refusing it). */
    private boolean canFront() { return gl.mutable && frontRefused < FRONT_TRIES; }

    /**
     * Set when the picture changes without a pen on the screen (a texture finished on the worker). In front-buffer
     * mode a flush alone may not reach the display then (it can sit idle with nothing new queued), so that present
     * queues the buffer with a swap instead.
     */
    private boolean kickDisplay;

    private void present() {
        needPresent = false;
        gl.bindWindow();
        gl.noScissor();
        gl.blit();
        if (overlay) gl.drawSegs(gl.pred, 0, gl.pred.count, predMode);
        if (front) {
            gl.flush(); // never swap a front-buffer surface (stale buffer on the Pad 6: black patches)
            kickDisplay = false;
        } else {
            kickDisplay = false;
            boolean ask = wantFront && canFront();
            if (ask) gl.requestFront(true);
            gl.swap();
            boolean f = gl.isFront();
            if (!f && ask) {
                gl.blit();
                if (overlay) gl.drawSegs(gl.pred, 0, gl.pred.count, predMode);
                gl.swap();
                f = gl.isFront();
                if (!f && ++frontRefused >= FRONT_TRIES)
                    Diag.warn("front buffer requests not honoured " + FRONT_TRIES + " times: ink is drawn double-buffered on this surface");
            }
            if (f) {
                frontRefused = 0; // it does honour the mode: only refusals in a row count
                // Just entered front-buffer mode: the buffer now on screen may still hold stale memory. Paint the
                // whole page into it once, so later partial redraws (eraser ring, ink) never reveal the rest.
                gl.noScissor();
                gl.blit();
                if (overlay) gl.drawSegs(gl.pred, 0, gl.pred.count, predMode);
                gl.flush();
            }
            setFront(f, "present");
        }
        checkPage();
        if (hasSel()) postSelection();
    }

    private void setFront(boolean f, String why) {
        if (f != front) {
            Diag.log(dir.getName() + ": front buffer " + (f ? "ON" : "off") + " (" + why + ")");
            frontWhy = why;
        }
        front = f;
    }

    /**
     * The pen is going down: make sure the surface is still in front-buffer mode before drawing straight into it.
     * The system can take a surface out of it (seen in split view on HyperOS: one pane's ink stopped showing while
     * the strokes were still saved); ink drawn into what is then a back buffer never reaches the screen.
     */
    private void checkFront() {
        if (!front || gl == null || !gl.hasSurface()) return;
        if (!gl.isFront()) {
            Diag.warn(dir.getName() + ": front buffer was turned off by the system; drawing frames normally");
            setFront(false, "lost");
            needPresent = true;
        }
        // (re-requesting the mode here, or queueing the buffer with a swap on lift, made the Pad 6 flip to a stale
        // buffer: black patches and eraser-ring leftovers. Front-buffer surfaces are only ever flushed.)
    }

    /**
     * The pen lifted: queue the front buffer once, so the compositor shows the finished stroke even when it has
     * stopped refreshing the buffer by itself (auto refresh lapsed, display idle).
     */
    private void showFront() {
        // intentionally nothing: a swap on a front-buffer surface flips to a stale buffer on the Pad 6 (see checkFront)
    }

    /** Patch the visible buffer with the overlay (pred buffer) without touching the cache. */
    private void patchOverlay(RectF extra) {
        if (!front) {
            needPresent = true;
            requestFrame();
            return;
        }
        RectF d = new RectF();
        if (extra != null && !extra.isEmpty()) d.set(extra);
        if (!predPrev.isEmpty()) { if (d.isEmpty()) d.set(predPrev); else d.union(predPrev); }
        if (!predCur.isEmpty()) { if (d.isEmpty()) d.set(predCur); else d.union(predCur); }
        gl.setView(ox, oy, z);
        gl.bindWindow();
        if (!d.isEmpty()) {
            gl.scissor(d);
            gl.blit();
            if (overlay) gl.drawSegs(gl.pred, 0, gl.pred.count, predMode);
        }
        gl.noScissor();
        gl.flush();
    }

    // ---- ink buffers: one per page near the screen ---------------------------------------------
    //
    // Each page's ink is emitted once, page-local, into its own pair of buffers and drawn with the page's top as
    // a transform. Only pages around the view are built; the rest hold nothing. A change on a page is applied by
    // walking its stroke list against what was built: strokes that are unchanged stay, removed ones are hidden in
    // place, greyed (erase preview) or lifted (selected) ones are recoloured in place, and new ones are appended —
    // so writing, erasing, selecting and undo cost what they touch, not what the notebook holds. A page is emitted
    // again only when a stroke appears in the middle of its list (undo of an erase, a partial erase lifting), or
    // when hidden segments outweigh the rest. gl.pen / gl.hl now hold just the live stroke, strokes written since
    // the last sync, and the pieces a partial erase would leave (all in doc coordinates).

    private static final byte LOOK_INK = 0, LOOK_GREY = 1, LOOK_LIFTED = 2, LOOK_GONE = 3, LOOK_HIDDEN = 4; // hidden: its layer is

    /** The built ink of one page (page-local coordinates). */
    private static final class PageInk {
        final SegBuf pen = new SegBuf(4096), hl = new SegBuf(256);
        Stroke[] s = new Stroke[64];
        int[] from = new int[64], to = new int[64];
        byte[] look = new byte[64];
        boolean[] made = new boolean[64]; // segments were emitted for entry i (a stroke built lifted has none)
        int n;
        int gone; // segments of removed strokes still taking room

        int total() { return pen.count + hl.count; }

        void reset() { pen.clear(); hl.clear(); n = 0; gone = 0; java.util.Arrays.fill(s, null); }

        void free() { pen.free(); hl.free(); s = new Stroke[0]; from = to = new int[0]; look = new byte[0]; made = new boolean[0]; n = 0; gone = 0; }

        void add(Stroke st, byte lk, int greyArgb) {
            if (n == s.length) {
                int c = Math.max(64, n * 2);
                s = Arrays.copyOf(s, c); from = Arrays.copyOf(from, c); to = Arrays.copyOf(to, c); look = Arrays.copyOf(look, c);
                made = Arrays.copyOf(made, c);
            }
            SegBuf b = st.hl() ? hl : pen;
            int f = b.count;
            boolean emit = lk != LOOK_LIFTED && lk != LOOK_HIDDEN; // lifted: drawn by the selection; hidden: its layer is
            if (emit) st.emitAll(0, b);
            s[n] = st; from[n] = f; to[n] = b.count; look[n] = lk; made[n] = emit;
            if (lk == LOOK_GREY) b.recolor(f, b.count, greyArgb);
            n++;
        }

        /** Entry i should now look lk; returns false if it can't be done in place (a stroke never emitted coming back). */
        boolean setLook(int i, byte lk, int greyArgb) {
            if (look[i] == lk) return true;
            if (!made[i]) { // built while lifted / hidden, so no segments: hiding it is free, showing it needs emitting
                if (lk != LOOK_GONE && lk != LOOK_LIFTED && lk != LOOK_HIDDEN) return false;
                look[i] = lk;
                return true;
            }
            SegBuf b = s[i].hl() ? hl : pen;
            b.recolor(from[i], to[i], lk == LOOK_INK ? s[i].color : lk == LOOK_GREY ? greyArgb : SegBuf.HIDDEN); // gone / lifted / hidden
            if (lk == LOOK_GONE) gone += to[i] - from[i];
            look[i] = lk;
            return true;
        }
    }

    private final IdentityHashMap<Notebook.Page, PageInk> inks = new IdentityHashMap<>();
    /** Builds the ink of pages just outside the view ahead of time, so scrolling onto them doesn't stall. */
    private final ExecutorService inkAhead = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nib-ink-ahead");
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    });
    private final java.util.Set<Notebook.Page> inkPending = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    private int greyFor(Stroke s) {
        boolean dk = book.paper == Notebook.PAPER_DARK;
        return s.hl() ? (dk ? 0xff3a3d44 : 0xffe6e8ec) : (dk ? 0xff5a5f69 : 0xffc6cad2);
    }

    /** Pages drawn for the current view: the visible ones and one either side. */
    private int drawFirst() { return Math.max(0, pageAt(oy) - 1); }

    private int drawLast() { return Math.min(tops.length - 1, pageAt(oy + H / z) + 1); }

    /** Pages whose ink is kept (and built ahead): two more either side of the drawn ones. */
    private int keepFirst() { return Math.max(0, drawFirst() - 2); }

    private int keepLast() { return Math.min(tops.length - 1, drawLast() + 2); }

    private void rebuild() {
        long t0 = System.nanoTime();
        int[] work = new int[2];
        rebuildInk(work);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (ms >= 8 || work[1] > 0) {
            long bytes = 0;
            for (PageInk pk : inks.values()) bytes += pk.pen.bytes() + pk.hl.bytes();
            Log.i(Gl.TAG, String.format(Locale.US, "ink sync %d ms: %d pages built, %d emitted in full, %d kept · %.1f MB",
                    ms, work[0], work[1], inks.size(), bytes / 1048576f));
        }
    }

    private void rebuildInk(int[] work) {
        needRebuild = false;
        gl.pen.clear();
        gl.hl.clear();
        gl.selPen.clear();
        gl.selHl.clear();
        IdentityHashMap<Stroke, Boolean> sel = new IdentityHashMap<>();
        for (Stroke s : selStrokes) sel.put(s, Boolean.TRUE);
        int n = book.pages.size();
        int first = keepFirst(), last = keepLast(), vf = pageAt(oy), vl = pageAt(oy + H / z);
        // pages on screen are built now; built pages nearby are brought up to date; the rest is left to inkAhead
        for (int i = first; i <= last; i++) if ((i >= vf && i <= vl) || inks.containsKey(book.pages.get(i))) syncPage(i, sel, work);
        // pages well away from the view give their memory back
        java.util.Set<Notebook.Page> keep = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = first; i <= last; i++) keep.add(book.pages.get(i));
        for (Iterator<Map.Entry<Notebook.Page, PageInk>> it = inks.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Notebook.Page, PageInk> e = it.next();
            if (!keep.contains(e.getKey())) { e.getValue().free(); it.remove(); }
        }
        unloadFar(first, last);
        if (selPage >= 0 && selPage < n && !sel.isEmpty()) // in page order, like they were drawn on the page
            for (Stroke s : book.pages.get(selPage).strokes()) if (sel.containsKey(s)) s.emitAll(0, s.hl() ? gl.selHl : gl.selPen);
        if (gest == G_ERASE) // what a partial erase would leave of the strokes it touched
            for (int i = first; i <= last; i++)
                for (Stroke s : book.pages.get(i).strokes()) {
                    ArrayList<Stroke> ps = pieces.get(s);
                    if (ps != null) for (Stroke q : ps) q.emitAll(tops[i], q.hl() ? gl.hl : gl.pen);
                }
        if (live != null) {
            SegBuf b = buf();
            liveFirst = b.count;
            if (live.n > 0) live.emitDot(liveTop, b);
            for (int k = 0; k + 2 < live.n; k++) live.emitInterval(k, live.n - 1, liveTop, b);
        }
        needCache = true;
    }

    /** Brings page i's built ink up to date with its stroke list (see the note above). */
    private void syncPage(int i, IdentityHashMap<Stroke, Boolean> sel, int[] work) {
        Notebook.Page p = book.pages.get(i);
        PageInk pk = inks.get(p);
        boolean fresh = pk == null;
        if (fresh) { pk = new PageInk(); inks.put(p, pk); work[0]++; }
        ArrayList<Stroke> list = book.inDrawOrder(p.strokes()); // layer by layer, bottom first
        if (!fresh && !patchPage(pk, i, list, sel)) fresh = true;
        if (!fresh && pk.gone > 4096 && pk.gone * 2 > pk.total()) fresh = true; // mostly holes: compact
        if (fresh) {
            if (inks.get(p) == pk && pk.n > 0) work[1]++;
            pk.reset();
            for (Stroke s : list) pk.add(s, lookOf(s, i, sel), greyFor(s));
        }
    }

    private byte lookOf(Stroke s, int page, IdentityHashMap<Stroke, Boolean> sel) {
        if (book.layered() && !book.layerVisible(s.layer)) return LOOK_HIDDEN;
        if (page == selPage && sel.containsKey(s)) return LOOK_LIFTED;
        if (gest == G_ERASE && (doomed.contains(s) || pieces.containsKey(s))) return LOOK_GREY;
        return LOOK_INK;
    }

    /** In-place update of a built page; false = emit it again in full. */
    private boolean patchPage(PageInk pk, int page, ArrayList<Stroke> list, IdentityHashMap<Stroke, Boolean> sel) {
        int j = 0, oldN = pk.n;
        IdentityHashMap<Stroke, Integer> at = null; // built index of each stroke, made only when the lists part ways
        for (int k = 0; k < list.size(); k++) {
            Stroke s = list.get(k);
            byte lk = lookOf(s, page, sel);
            int idx = -1;
            if (j < oldN && pk.s[j] == s) idx = j;
            else if (j < oldN) {
                if (at == null) { at = new IdentityHashMap<>(); for (int m = 0; m < oldN; m++) at.put(pk.s[m], m); }
                Integer f = at.get(s);
                if (f != null && f >= j) idx = f;
            }
            if (idx >= 0) {
                for (int m = j; m < idx; m++) pk.setLook(m, LOOK_GONE, 0); // removed (or moved later in the list)
                if (!pk.setLook(idx, lk, greyFor(s))) return false;
                j = idx + 1;
                continue;
            }
            if (lk == LOOK_LIFTED) continue; // new and selected: nothing to draw here yet
            // a new stroke: everything after it in the list must draw after it, so what's left of the old order goes
            for (int m = j; m < oldN; m++) pk.setLook(m, LOOK_GONE, 0);
            j = oldN;
            pk.add(s, lk, greyFor(s));
        }
        for (int m = j; m < oldN; m++) pk.setLook(m, LOOK_GONE, 0);
        return true;
    }

    /**
     * Pages this far from the view (beyond the kept ones) give back their strokes once they're saved; they're read
     * from their file again when the view comes near. Memory then follows what's on screen, not the notebook's size.
     */
    private static final int RESIDENT = 4;

    private void unloadFar(int first, int last) {
        if (gest == G_ERASE || live != null) return;
        int lo = first - RESIDENT, hi = last + RESIDENT, n = book.pages.size(), freed = 0;
        for (int i = 0; i < n; i++) {
            if (i >= lo && i <= hi) continue;
            if (i == selPage || i == autoLastPage || i == livePage) continue;
            Notebook.Page p = book.pages.get(i);
            if (p.loaded() && p.unload()) freed++;
        }
        if (freed > 0) Log.i(Gl.TAG, "let go of " + freed + " pages far from the view");
    }

    /** Hands the pages near the view that have no ink yet to inkAhead (reading their file there if not read yet). */
    private void buildAhead() {
        for (int i = keepFirst(), last = keepLast(); i <= last; i++) {
            Notebook.Page p = book.pages.get(i);
            if (inks.containsKey(p) || (p.loaded() && p.strokes().isEmpty()) || !inkPending.add(p)) continue;
            final boolean read = !p.loaded();
            final File src = p.src;
            final ArrayList<Stroke> list = read ? new ArrayList<>() : new ArrayList<>(p.strokes()); // strokes never change; the list may
            final ArrayList<Notebook.Item> items = new ArrayList<>();
            final Notebook layersNow = book.metaCopy(); // the layer list as it is now, for the other thread
            try {
                inkAhead.execute(() -> {
                    boolean ok = true;
                    if (read) {
                        try { Notebook.readBody(src, list, items); }
                        catch (Exception e) { ok = false; } // read on the ink thread instead (and marked broken there)
                    }
                    PageInk pk = new PageInk();
                    if (ok) for (Stroke s : layersNow.inDrawOrder(list)) pk.add(s, layersNow.layerVisible(s.layer) ? LOOK_INK : LOOK_HIDDEN, 0);
                    final boolean good = ok;
                    h.post(() -> {
                        inkPending.remove(p);
                        if (book == null || !good) { pk.free(); return; }
                        if (read) {
                            if (p.loaded() || p.src != src) { pk.free(); return; } // read meanwhile: these are other objects
                            p.install(list, items);
                        }
                        if (gl == null || !gl.hasSurface() || inks.containsKey(p)) { pk.free(); return; } // hidden tab: keep nothing
                        int at = book.pages.indexOf(p);
                        if (at < keepFirst() || at > keepLast()) { pk.free(); return; } // scrolled away meanwhile
                        inks.put(p, pk);
                        needRebuild = true; // the next sync checks it against the page as it is by then
                    });
                });
            } catch (java.util.concurrent.RejectedExecutionException e) { inkPending.remove(p); return; } // released
        }
    }

    /** Every page in [first, last] has built ink (else a sync is due before drawing). */
    private boolean inkCovers(int first, int last) {
        for (int i = first; i <= last; i++) if (!inks.containsKey(book.pages.get(i))) return false;
        return true;
    }

    /** Frees all GPU memory this engine holds except what's needed to draw again (surface gone / changed). */
    private void dropGpu() {
        dropHandoff();
        for (PageInk pk : inks.values()) pk.free();
        inks.clear();
        gl.pen.lost();
        gl.hl.lost();
        gl.pred.lost();
        gl.selPen.lost();
        gl.selHl.lost();
        gl.laser.lost();
        for (Tex t : texs.values()) gl.deleteTex(t.id);
        texs.clear();
        texBytes = 0;
        needRebuild = true;
    }

    // ---- input -----------------------------------------------------------------------------

    private static boolean isStylus(int tt) { return tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER; }

    /** A finger gesture became a two-finger one: hand it to scrolling / zooming (and two-finger-tap undo). */
    private void toPan(MotionEvent ev, int a) {
        gest = G_PAN;
        panBegin(ev);
        panEvent(ev, a);
    }

    private void handle(MotionEvent ev, long recv) {
        int a = ev.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            int tt = ev.getToolType(0);
            boolean pen = isStylus(tt) || (fingerDraw && tt == MotionEvent.TOOL_TYPE_FINGER);
            fingerGest = pen && !isStylus(tt);
            if (fingerGest && (SystemClock.uptimeMillis() - stylusAt < 450 || ev.getTouchMajor() > dp(40))) {
                gest = G_IGNORE; // the pen is near, or a palm: fingers don't write now
                return;
            }
            if (pen) h.removeCallbacks(autoRun); // still writing: convert after the next pause
            if (editing != null || tool == TOOL_TEXT) main.post(listener::onDismissText);
            // touching the current selection moves/scales it (pen or finger)
            if (hasSel() && (tool == TOOL_LASSO || shapeMode()) && selHit(ev.getX(), ev.getY())) {
                stopFling();
                gest = G_SEL;
                selBegin(ev);
                return;
            }
            if (pen && !fingerGest && coverOn() && oy + ev.getY() / z < 0 && tool != TOOL_LASER) {
                gest = G_IGNORE; // the pen doesn't write on the cover
                return;
            }
            if (pen && fingerGest && coverOn() && oy + ev.getY() / z < 0) { gest = G_PAN; panBegin(ev); return; } // a finger scrolls it
            if (pen) {
                stopFling();
                gesturing = false;
                if (hasSel() && tool != TOOL_LASSO) clearSelection(); // writing next to a shape just carries on
                checkFront();
                // ink is drawn into the cache, so it must hold this view; then back into front-buffer mode if we can
                if (!cacheValid()) renderNow();
                else if (!front && canFront()) present();
                int btn = ev.getButtonState();
                boolean erase = tt == MotionEvent.TOOL_TYPE_ERASER || tool == TOOL_ERASER || tempEraser
                        || (btn & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0;
                boolean lassoBtn = (btn & MotionEvent.BUTTON_STYLUS_SECONDARY) != 0;
                if (lassoBtn && !erase && tool != TOOL_LASSO) {
                    tool = TOOL_LASSO; // so the selection it makes can be moved straight away
                    main.post(() -> listener.onTool(TOOL_LASSO));
                }
                if (erase) {
                    gest = G_ERASE;
                    eraseBegin(ev);
                } else if (tool == TOOL_LASER) {
                    gest = G_LASER;
                    laserDown = true;
                    hovering = false;
                    hoverPt = null;
                    if (laserMode == LASER_INK || laserMode == LASER_DOT) laserBreak();
                    laserAdd(ev);
                } else if (tool == TOOL_TAPE) {
                    gest = G_TAPE;
                    tapeBegin(ev);
                } else if (tool == TOOL_LASSO) {
                    clearSelection();
                    gest = G_LASSO;
                    lassoBegin(ev);
                } else if (tool == TOOL_TEXT) {
                    gest = G_TAP;
                    tapX = ev.getX();
                    tapY = ev.getY();
                    tapT = ev.getEventTime();
                } else {
                    gest = G_INK;
                    downDelay = (recv - ev.getEventTimeNanos()) / 1e6f;
                    inkBegin(ev, recv);
                }
            } else if (SystemClock.uptimeMillis() - penNear < 450 || ev.getTouchMajor() > dp(40)) {
                gest = G_IGNORE; // pen hovering / just lifted, or palm-sized contact
            } else if (tool == TOOL_TEXT) {
                gest = G_TAP; // a finger tap places text too; a finger drag scrolls
                tapX = ev.getX();
                tapY = ev.getY();
                tapT = ev.getEventTime();
                panBegin(ev);
            } else {
                gest = G_PAN;
                panBegin(ev);
            }
            return;
        }
        switch (gest) {
            case G_INK:
                if (a == MotionEvent.ACTION_POINTER_DOWN && fingerGest) { // a second finger: scroll / zoom instead
                    inkCancel();
                    toPan(ev, a);
                } else inkEvent(ev, a, recv);
                break;
            case G_ERASE:
                if (a == MotionEvent.ACTION_POINTER_DOWN && fingerGest) {
                    eraseEvent(ev, MotionEvent.ACTION_UP); // keep what was erased so far
                    toPan(ev, a);
                } else eraseEvent(ev, a);
                break;
            case G_LASER:
                if (a == MotionEvent.ACTION_POINTER_DOWN && fingerGest) {
                    laserDown = false;
                    laserUpAt = SystemClock.uptimeMillis();
                    toPan(ev, a);
                    break;
                }
                laserAdd(ev);
                if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    laserDown = false;
                    laserUpAt = SystemClock.uptimeMillis();
                    if (laserMode == LASER_INK) laserBreak();
                    gest = G_NONE;
                }
                break;
            case G_LASSO:
                if (a == MotionEvent.ACTION_POINTER_DOWN && fingerGest) {
                    lassoEvent(ev, MotionEvent.ACTION_CANCEL);
                    toPan(ev, a);
                } else lassoEvent(ev, a);
                break;
            case G_TAPE:
                if (a == MotionEvent.ACTION_POINTER_DOWN && fingerGest) {
                    tapeEvent(ev, MotionEvent.ACTION_CANCEL);
                    toPan(ev, a);
                } else tapeEvent(ev, a);
                break;
            case G_SEL:
                selEvent(ev, a);
                break;
            case G_TAP: {
                boolean far = Math.hypot(ev.getX() - tapX, ev.getY() - tapY) > dp(10);
                if (vt != null && (far || a == MotionEvent.ACTION_POINTER_DOWN)) {
                    gest = G_PAN;
                    panEvent(ev, a);
                    break;
                }
                if (a == MotionEvent.ACTION_UP) {
                    if (!far && ev.getEventTime() - tapT < 500) textTap(tapX, tapY);
                    gest = G_NONE;
                    if (vt != null) { vt.recycle(); vt = null; }
                } else if (a == MotionEvent.ACTION_CANCEL) gest = G_NONE;
                break;
            }
            case G_PAN:
                if (a == MotionEvent.ACTION_POINTER_DOWN && SystemClock.uptimeMillis() - penNear < 450) {
                    gest = G_IGNORE;
                    endGesture();
                } else panEvent(ev, a);
                break;
            case G_IGNORE:
                if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) gest = G_NONE;
                break;
        }
    }

    // ink ---------------------------------------------------------------------------------

    private SegBuf buf() { return live.hl() ? gl.hl : gl.pen; }

    private void inkBegin(MotionEvent ev, long recv) {
        boolean hlTool = tool == TOOL_HL;
        live = new Stroke(hlTool ? (hlFlat ? Stroke.HL_FLAT : Stroke.HL) : penType, hlTool ? hlColor : penColor, hlTool ? hlWidth : penWidth);
        live.layer = book.activeLayer;
        Notebook.Layer al = book.layer(book.activeLayer);
        if (al != null && !al.visible) { // writing on a hidden layer: show it, or the ink would vanish
            al.visible = true;
            needRebuild = true;
            needCache = true;
            toast("Showing \"" + al.name + "\": you're writing on it");
            notifyState();
            docChanged();
        }
        predMode = hlTool ? 1 : 0;
        float docY = oy + ev.getY() / z;
        if (!canvas() && docY > docH && !book.pages.isEmpty()) { // writing on the paper after the last page: make it real
            Notebook.Page lastP = book.pages.get(book.pages.size() - 1);
            Notebook.Page np = new Notebook.Page(lastP.pdf >= 0 ? Notebook.BG_LINED : lastP.bg);
            np.copyStyle(lastP);
            book.pages.add(np);
            relayout();
            main.post(this::notifyStateLater);
        }
        livePage = pageAt(docY);
        liveTop = tops[livePage];
        pf = -1;
        snapped = false;
        stN = 0;
        stFront = stSwap = stFrames = stScenes = 0;
        stFrameNs = stFrameMax = 0;
        stT0 = ev.getEventTime();
        predCur.setEmpty();
        predPrev.setEmpty();
        gl.pred.clear();
        overlay = true;
        SegBuf b = buf();
        liveFirst = b.count;
        if (predictor != null) predictor.record(ev);
        newFirst = b.count;
        dirty.setEmpty();
        holdX = startX = lastX = ev.getX();
        holdY = startY = lastY = ev.getY();
        movedFromStart = false;
        rulerPick(ev.getX(), ev.getY());
        synthP = fingerGest || noPressure(ev);
        synthD = 0;
        addSample(ev.getX(), ev.getY(), synthP ? FINGER_P : ev.getPressure());
        if (rulerEdge == 0) armSnap();
        if (holdErase && rulerEdge == 0) h.postDelayed(holdEraseR, HOLD_ERASE_MS);
        predict(ev);
        inkRender(ev, recv);
    }

    // ---- hold the pen still where it lands: the stroke turns into an eraser until it lifts --------

    private static final long HOLD_ERASE_MS = 450;
    private float startX, startY, lastX, lastY; // screen px
    private boolean movedFromStart;
    private final Runnable holdEraseR = this::holdToErase;

    private void holdToErase() {
        if (gest != G_INK || live == null || movedFromStart || snapped) return;
        h.removeCallbacks(snapR);
        buf().truncate(liveFirst); // the dot under the tip goes
        live = null;
        gl.pred.clear();
        overlay = false;
        needCache = true;
        renderNow();
        gest = G_ERASE;
        eraseBeginAt(lastX, lastY);
        main.post(listener::onHaptic);
    }

    private final Runnable snapR = this::trySnap;

    private void armSnap() {
        h.removeCallbacks(snapR);
        if (tool == TOOL_SHAPE && !snapped) h.postDelayed(snapR, 250); // shapes tool: a short pause is enough
        else if (shapeSnap && !snapped) h.postDelayed(snapR, 480);
    }

    /** Pen held still mid-stroke: replace the stroke with a clean shape if it looks like one. */
    private void trySnap() {
        if (gest != G_INK || live == null || snapped || !movedFromStart) return; // a pen resting where it landed erases instead
        float[] pts = Shapes.recognize(live);
        if (pts == null) return;
        float avgP = 0;
        for (int i = 0; i < live.n; i++) avgP += live.p[4 * i + 2];
        avgP /= live.n;
        // remember the clean shape and where the pen is: moving on now stretches / resizes it
        snapPts = pts;
        snapP = avgP;
        snapRefX = live.x(live.n - 1);
        snapRefY = live.y(live.n - 1);
        snapAdjusting = false;
        float pathLen = 0;
        for (int i = 2; i + 1 < pts.length; i += 2) pathLen += (float) Math.hypot(pts[i] - pts[i - 2], pts[i + 1] - pts[i - 1]);
        snapLine = pathLen > 0 && Math.hypot(pts[pts.length - 2] - pts[0], pts[pts.length - 1] - pts[1]) / pathLen > 0.995f;
        float l = Float.MAX_VALUE, t = Float.MAX_VALUE, r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
        for (int i = 0; i + 1 < pts.length; i += 2) { l = Math.min(l, pts[i]); r = Math.max(r, pts[i]); t = Math.min(t, pts[i + 1]); b = Math.max(b, pts[i + 1]); }
        snapCx = (l + r) / 2;
        snapCy = (t + b) / 2;
        Stroke s = new Stroke(live.tool, live.color, live.width);
        s.layer = live.layer;
        s.shape = snapLine ? Stroke.LINE : Stroke.CLOSED;
        for (int i = 0; i < pts.length; i += 2) s.add(pts[i], pts[i + 1], avgP);
        buf().truncate(liveFirst);
        live = s;
        live.emitAll(liveTop, buf());
        gl.pred.clear();
        snapped = true;
        needCache = true;
        renderNow();
        Log.i(Gl.TAG, "shape snapped: " + (pts.length / 2) + " points");
    }

    // ---- a snapped shape follows the pen until it lifts: lines stretch / turn, shapes resize --------

    private float[] snapPts;
    private float snapP, snapRefX, snapRefY, snapCx, snapCy;
    private boolean snapLine, snapAdjusting;

    private void adjustSnap(float sx, float sy) {
        if (snapPts == null || live == null) return;
        float px = ox + sx / z, py = oy + sy / z - liveTop;
        if (!snapAdjusting && Math.hypot(px - snapRefX, py - snapRefY) * z < dp(4)) return; // ignore the hand's tremble
        snapAdjusting = true;
        float[] pts;
        if (snapLine) {
            float ax = snapPts[0], ay = snapPts[1], dx = px - ax, dy = py - ay, len = (float) Math.hypot(dx, dy);
            double ang = Math.toDegrees(Math.atan2(dy, dx)), near = Math.round(ang / 45.0) * 45.0;
            if (Math.abs(ang - near) < 5) { // level, upright or 45°: lock to it
                double rad = Math.toRadians(near);
                dx = (float) Math.cos(rad) * len;
                dy = (float) Math.sin(rad) * len;
            }
            pts = Shapes.densify(new float[]{ax, ay, ax + dx, ay + dy}, false);
        } else {
            float r0 = (float) Math.hypot(snapRefX - snapCx, snapRefY - snapCy), r1 = (float) Math.hypot(px - snapCx, py - snapCy);
            float k = r0 < 1 ? 1 : Math.max(0.2f, Math.min(6f, r1 / r0));
            pts = new float[snapPts.length];
            for (int i = 0; i + 1 < pts.length; i += 2) { pts[i] = snapCx + (snapPts[i] - snapCx) * k; pts[i + 1] = snapCy + (snapPts[i + 1] - snapCy) * k; }
        }
        replaceLive(pts, snapP, snapLine ? Stroke.LINE : Stroke.CLOSED);
    }

    /** Swaps the stroke being drawn for clean points (page-local) and redraws. */
    private void replaceLive(float[] pts, float pressure, int shape) {
        Stroke s = new Stroke(live.tool, live.color, live.width);
        s.layer = live.layer;
        s.shape = shape;
        for (int i = 0; i + 1 < pts.length; i += 2) s.add(pts[i], pts[i + 1], pressure);
        buf().truncate(liveFirst);
        live = s;
        live.emitAll(liveTop, buf());
        gl.pred.clear();
        needCache = true;
        requestFrame();
    }

    /** Highlighter: a nearly straight swipe (or any, set to always) becomes a ruler-straight line. */
    private void straightenHighlight() {
        if (live == null || !live.hl() || snapped || hlStraight == 0 || live.n < 2) return;
        float x0 = live.x(0), y0 = live.y(0), x1 = live.x(live.n - 1), y1 = live.y(live.n - 1), len = 0, avgP = 0;
        for (int i = 1; i < live.n; i++) len += (float) Math.hypot(live.x(i) - live.x(i - 1), live.y(i) - live.y(i - 1));
        for (int i = 0; i < live.n; i++) avgP += live.p[4 * i + 2];
        avgP /= live.n;
        float ends = (float) Math.hypot(x1 - x0, y1 - y0);
        if (ends * z < dp(14) || len <= 0) return;
        if (hlStraight == 1 && ends / len < 0.9f) return;
        float ang = (float) Math.toDegrees(Math.atan2(y1 - y0, x1 - x0)), m = ((ang % 90) + 90) % 90;
        if (m < 6 || m > 84) { if (Math.abs(x1 - x0) >= Math.abs(y1 - y0)) y1 = y0; else x1 = x0; }
        replaceLive(Shapes.densify(new float[]{x0, y0, x1, y1}, false), avgP, Stroke.LINE);
    }

    private void inkEvent(MotionEvent ev, int a, long recv) {
        if (live == null) return;
        if (a == MotionEvent.ACTION_CANCEL || (ev.getFlags() & MotionEvent.FLAG_CANCELED) != 0) {
            inkCancel();
            return;
        }
        if (a != MotionEvent.ACTION_MOVE && a != MotionEvent.ACTION_UP) return;
        if (snapped) {
            adjustSnap(ev.getX(), ev.getY());
            if (a == MotionEvent.ACTION_UP) inkCommit(ev);
            return;
        }
        if (predictor != null) predictor.record(ev);
        newFirst = buf().count;
        dirty.setEmpty();
        int hs = ev.getHistorySize();
        for (int i = 0; i < hs; i++) addSample(ev.getHistoricalX(i), ev.getHistoricalY(i), synthP ? FINGER_P : ev.getHistoricalPressure(i));
        addSample(ev.getX(), ev.getY(), synthP ? FINGER_P : ev.getPressure());
        lastX = ev.getX();
        lastY = ev.getY();
        if (!movedFromStart && Math.hypot(lastX - startX, lastY - startY) > dp(5)) {
            movedFromStart = true;
            h.removeCallbacks(holdEraseR);
        }
        if (Math.hypot(ev.getX() - holdX, ev.getY() - holdY) > dp(3)) {
            holdX = ev.getX();
            holdY = ev.getY();
            if (rulerEdge == 0) armSnap();
        }
        if (a == MotionEvent.ACTION_UP) {
            if (live.n >= 2) {
                live.emitInterval(live.n - 2, live.n - 1, liveTop, buf());
                unionDoc(dirty, live);
            }
            gl.pred.clear();
            overlay = false;
            predPrev.set(predCur);
            predCur.setEmpty();
            inkRender(ev, recv);
            inkCommit(ev);
        } else {
            predict(ev);
            inkRender(ev, recv);
        }
    }

    private void inkCommit(MotionEvent ev) {
        h.removeCallbacks(snapR);
        h.removeCallbacks(holdEraseR);
        // shapes tool: the stroke becomes its clean shape on lift (a scribble that's no shape stays as drawn)
        if (tool == TOOL_SHAPE && !snapped && rulerEdge == 0 && live != null && live.n > 3) { movedFromStart = true; trySnap(); }
        if (rulerEdge != 0 && live.n > 0) {
            final float len = (float) Math.hypot(live.x(live.n - 1) - live.x(0), live.y(live.n - 1) - live.y(0));
            main.post(() -> listener.onRulerLength(len, true));
        }
        boolean ruled = rulerEdge != 0; // ruled lines keep a square end
        rulerEdge = 0;
        penNear = SystemClock.uptimeMillis();
        long dur = Math.max(1, ev.getEventTime() - stT0);
        Log.i(Gl.TAG, String.format(Locale.US,
                "stroke samples=%d kept=%d dur=%dms rate=%.0fHz step=%.1fpx pressure min=%.3f avg=%.3f max=%.3f zoom=%.0f%% drawn front=%d swap=%d"
                        + " frames=%d avg=%.1fms max=%.1fms full-redraws=%d%s",
                stN, live.n, dur, stN * 1000f / dur, stN > 1 ? stStep / (stN - 1) : 0, stPmin, stPsum / Math.max(1, stN), stPmax,
                z / fitZ * 100, stFront, stSwap, stFrames, stFrames > 0 ? stFrameNs / 1e6 / stFrames : 0, stFrameMax / 1e6, stScenes,
                snapped ? " (shape)" : ""));
        Notebook.Page p = book.pages.get(livePage);
        if (!snapped && dur < 260 && live.n > 0 && live.r - live.l < live.width + 6 && live.b - live.t < live.width + 6
                && toggleTapeAt(live.x(0), live.y(0) + liveTop, true)) {
            buf().truncate(liveFirst); // the tap only peeled the tape back; no dot
            live = null;
            overlay = false;
            gest = G_NONE;
            needCache = true;
            requestFrame();
            return;
        }
        straightenHighlight();
        if (synthP && tapers() && !snapped && !ruled) {
            float len = 0;
            for (int i = 1; i < live.n; i++) len += (float) Math.hypot(live.x(i) - live.x(i - 1), live.y(i) - live.y(i - 1));
            live.taperEnd(Math.min(live.taperLen(), len / 2));
            // the live tail went to the GPU at full width: replace the stroke's pieces with the tapered ones
            buf().truncate(liveFirst);
            live.emitAll(liveTop, buf());
            gl.pred.clear();
            needCache = true;
            requestFrame();
        }
        Object[] before = pageState(p);
        live.trim();
        p.strokes().add(live);
        for (Notebook.Item it : p.items()) // ink written over covering tape goes under it
            if (it.type == Notebook.Item.TAPE && !it.revealed() && RectF.intersects(new RectF(it.x, it.y, it.x + it.w, it.y + it.h), new RectF(live.l, live.t, live.r, live.b))) {
                needCache = true;
                requestFrame();
                break;
            }
        push(new PageEdit(p, before, pageState(p)));
        if (livePage == book.pages.size() - 1 && ensureTrailingPage()) {
            needCache = true;
            requestFrame();
            notifyState();
        }
        Stroke done = live;
        int donePage = livePage;
        // snapped strokes count too when they're letter-sized (a pause at the end of an "l" snaps it straight)
        if (!done.hl() && (!snapped || Math.max(done.r - done.l, done.b - done.t) < 80)) queueAutoText(done, donePage);
        live = null;
        overlay = false;
        gest = G_NONE;
        if (snapped) {
            needCache = true;
            requestFrame();
            ArrayList<Stroke> one = new ArrayList<>();
            one.add(done);
            select(donePage, one, new ArrayList<>()); // handles appear: stretch it now or come back later
        }
        showFront();
        docChanged();
    }

    private boolean tapers() { return taper && live != null && live.tool == Stroke.PEN; }

    /** A stylus that doesn't report pressure (passive capacitive pens). */
    private static boolean noPressure(MotionEvent ev) {
        if (!isStylus(ev.getToolType(0))) return false;
        android.view.InputDevice d = ev.getDevice();
        android.view.InputDevice.MotionRange r = d == null ? null : d.getMotionRange(MotionEvent.AXIS_PRESSURE, ev.getSource());
        return r == null || r.getRange() <= 0;
    }

    void setTaper(boolean on) { h.post(() -> taper = on); }

    private void inkCancel() {
        h.removeCallbacks(snapR);
        h.removeCallbacks(holdEraseR);
        if (live != null) {
            buf().truncate(liveFirst);
            live = null;
        }
        gl.pred.clear();
        overlay = false;
        gest = G_NONE;
        needCache = true;
        requestFrame();
    }

    private void addSample(float sx, float sy, float pressure) {
        if (rulerEdge != 0) {
            float[] q = rulerProject(sx, sy);
            sx = q[0];
            sy = q[1];
            if (live.n == 0) { rulerX0 = sx; rulerY0 = sy; }
            long now = SystemClock.uptimeMillis();
            if (now - rulerLenAt > 40) {
                rulerLenAt = now;
                final float len = (float) Math.hypot(sx - rulerX0, sy - rulerY0) / z;
                main.post(() -> listener.onRulerLength(len, false));
            }
        }
        float x = ox + sx / z, y = oy + sy / z - liveTop;
        if (live.n > 0) {
            float dx = x - live.x(live.n - 1), dy = y - live.y(live.n - 1), min = 0.35f / z;
            if (dx * dx + dy * dy < min * min) return;
            synthD += (float) Math.sqrt(dx * dx + dy * dy);
        }
        if (synthP && tapers()) pressure = Stroke.taperP(pressure, synthD / live.taperLen());
        pf = pf < 0 ? pressure : pf + 0.5f * (pressure - pf);
        live.add(x, y, pf);
        statSample(pressure, sx, sy);
        int i = live.n - 1;
        if (i == 0) {
            live.emitDot(liveTop, buf());
            unionDoc(dirty, live);
        } else if (i >= 2) {
            live.emitInterval(i - 2, i, liveTop, buf());
            unionDoc(dirty, live);
        }
    }

    private void unionDoc(RectF r, Stroke s) {
        tmp.set((s.bl - ox) * z - 3, (s.bt - oy) * z - 3, (s.br - ox) * z + 3, (s.bb - oy) * z + 3);
        if (r.isEmpty()) r.set(tmp);
        else r.union(tmp);
    }

    private void unionSeg(RectF r, float x0, float y0, float x1, float y1, float rad) {
        float pad = rad * z + 3f;
        float ax = (x0 - ox) * z, ay = (y0 - oy) * z, bx = (x1 - ox) * z, by = (y1 - oy) * z;
        tmp.set(Math.min(ax, bx) - pad, Math.min(ay, by) - pad, Math.max(ax, bx) + pad, Math.max(ay, by) + pad);
        if (r.isEmpty()) r.set(tmp);
        else r.union(tmp);
    }

    private void statSample(float p, float sx, float sy) {
        if (stN == 0) { stPmin = stPmax = p; stPsum = 0; stStep = 0; }
        else stStep += (float) Math.hypot(sx - stLx, sy - stLy);
        stPmin = Math.min(stPmin, p);
        stPmax = Math.max(stPmax, p);
        stPsum += p;
        stLx = sx;
        stLy = sy;
        stN++;
    }

    private void predict(MotionEvent ev) {
        gl.pred.clear();
        predPrev.set(predCur);
        predCur.setEmpty();
        if (rulerEdge != 0) return; // ruled lines: no guessing ahead of the pen
        if (live.n >= 2) {
            // newest piece: drawn curved-as-if-ending-here, window only; finalized on the next sample
            live.emitInterval(live.n - 2, live.n - 1, liveTop, gl.pred);
            unionDoc(predCur, live);
        }
        if (predLevel == 0 || predictor == null || live.n == 0) return;
        boolean strong = predLevel == 2;
        MotionEvent pe = predictor.predict(ev.getEventTimeNanos() + (strong ? 18_000_000L : 8_000_000L));
        if (pe == null) return;
        float scale = strong ? 1f : 0.5f;
        int last = live.n - 1;
        float rx = live.x(last), ry = live.y(last);
        float px = rx, py = ry, lr = live.rad(last);
        float budget = (strong ? 48f : 14f) / z;
        int hs = pe.getHistorySize();
        for (int i = 0; i <= hs && budget > 0; i++) {
            float sx = i < hs ? pe.getHistoricalX(i) : pe.getX();
            float sy = i < hs ? pe.getHistoricalY(i) : pe.getY();
            float x = rx + (ox + sx / z - rx) * scale, y = ry + (oy + sy / z - liveTop - ry) * scale;
            float dx = x - px, dy = y - py, d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d > budget) {
                x = px + dx * budget / d;
                y = py + dy * budget / d;
                d = budget;
            }
            budget -= d;
            gl.pred.put(px, py + liveTop, x, y + liveTop, lr, lr, live.color);
            unionSeg(predCur, px, py + liveTop, x, y + liveTop, lr);
            px = x;
            py = y;
        }
        pe.recycle();
    }

    private void inkRender(MotionEvent ev, long recv) {
        SegBuf b = buf();
        gl.setView(ox, oy, z);
        gl.bindCache();
        gl.scissor(dirty);
        gl.drawSegs(b, newFirst, b.count - newFirst, predMode);
        gl.noScissor();
        if (front) {
            patchOverlay(dirty);
            stFront++;
            stat(ev, recv);
        } else {
            stSwap++;
            needPresent = true;
            requestFrame();
        }
    }

    private void stat(MotionEvent ev, long recv) {
        if (!hud) return;
        long t = ev.getEventTimeNanos();
        long flushed = System.nanoTime();
        android.opengl.GLES20.glFinish();
        long done = System.nanoTime();
        int i = latN++ % latIn.length;
        latIn[i] = (recv - t) / 1e6f;
        latFlush[i] = (flushed - t) / 1e6f;
        latGpu[i] = (done - t) / 1e6f;
    }

    private void postStats() {
        long now = SystemClock.uptimeMillis();
        if (now - lastHud < 250) return;
        lastHud = now;
        int n = Math.min(latN, latIn.length);
        String mode = front ? "front buffer ON" : "front buffer off (" + frontWhy + ")";
        String s = n == 0 ? mode + " · draw something"
                : String.format(Locale.US, "%s · %d samples\ninput %.1f ms · cpu %.1f ms · gpu done %.1f ms\npen-down delivered after %.1f ms",
                mode, n, med(latIn, n), med(latFlush, n), med(latGpu, n), downDelay);
        main.post(() -> listener.onStats(s));
        requestFrame();
    }

    private static float med(float[] a, int n) {
        float[] c = Arrays.copyOf(a, n);
        Arrays.sort(c);
        return c[n / 2];
    }

    // erase -------------------------------------------------------------------------------

    private void eraseBegin(MotionEvent ev) { eraseBeginAt(ev.getX(), ev.getY()); }

    private void eraseBeginAt(float x, float y) {
        eraseBefore = new IdentityHashMap<>();
        doomed.clear();
        doomedItems.clear();
        pieces.clear();
        ex = x;
        ey = y;
        predCur.setEmpty();
        predPrev.setEmpty();
        eraseAlong(ex, ey, ex, ey);
    }

    private void eraseEvent(MotionEvent ev, int a) {
        if (a == MotionEvent.ACTION_MOVE || a == MotionEvent.ACTION_UP) {
            int hs = ev.getHistorySize();
            for (int i = 0; i <= hs; i++) {
                float x = i < hs ? ev.getHistoricalX(i) : ev.getX(), y = i < hs ? ev.getHistoricalY(i) : ev.getY();
                eraseAlong(ex, ey, x, y);
                ex = x;
                ey = y;
            }
        }
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            boolean previewed = !doomed.isEmpty() || !doomedItems.isEmpty() || !pieces.isEmpty();
            if (previewed && a == MotionEvent.ACTION_UP) { // the pen lifted: now the greyed ink really goes
                for (Notebook.Page pg : eraseBefore.keySet()) {
                    ArrayList<Stroke> keep = new ArrayList<>();
                    for (Stroke st : pg.strokes()) {
                        if (doomed.contains(st)) continue;
                        ArrayList<Stroke> ps = pieces.get(st);
                        if (ps != null) keep.addAll(ps); else keep.add(st);
                    }
                    pg.setStrokes(keep);
                    pg.items().removeIf(doomedItems::contains);
                }
            } else if (previewed) eraseBefore.clear(); // cancelled: nothing was changed
            doomed.clear();
            doomedItems.clear();
            pieces.clear();
            if (previewed) { needRebuild = true; needCache = true; requestFrame(); }
            if (!eraseBefore.isEmpty()) {
                ArrayList<PageEdit> edits = new ArrayList<>();
                for (Map.Entry<Notebook.Page, Object[]> e : eraseBefore.entrySet())
                    edits.add(new PageEdit(e.getKey(), e.getValue(), pageState(e.getKey())));
                push(new Multi(edits));
                docChanged();
            }
            eraseBefore = null;
            gest = G_NONE;
            gl.pred.clear();
            overlay = false;
            predPrev.set(predCur);
            predCur.setEmpty();
            patchOverlay(null);
            showFront();
            penNear = SystemClock.uptimeMillis();
        }
    }

    private void eraseAlong(float sx0, float sy0, float sx1, float sy1) {
        float x0 = ox + sx0 / z, y0 = oy + sy0 / z, x1 = ox + sx1 / z, y1 = oy + sy1 / z;
        float er = dp(eraserDp) / z;
        int[] pr = inkPages(Math.min(y0, y1) - er, Math.max(y0, y1) + er);
        int p0 = pr[0], p1 = pr[1];
        boolean any = false;
        for (int pi = p0; pi <= p1; pi++) {
            float top = tops[pi];
            Notebook.Page page = book.pages.get(pi);
            ArrayList<Stroke> list = page.strokes();
            for (int i = list.size() - 1; i >= 0; i--) {
                Stroke s = list.get(i);
                if (s.layer != book.activeLayer) continue; // the eraser only touches the active layer
                if (erasePreview) {
                    if (partialErase) {
                        ArrayList<Stroke> cur = pieces.get(s), out = new ArrayList<>();
                        boolean touched = false;
                        for (Stroke q : cur != null ? cur : java.util.Collections.singletonList(s)) {
                            ArrayList<Stroke> parts = q.cut(x0, y0 - top, x1, y1 - top, er);
                            if (parts == null) out.add(q);
                            else { out.addAll(parts); touched = true; }
                        }
                        if (!touched) continue;
                        if (!eraseBefore.containsKey(page)) eraseBefore.put(page, pageState(page));
                        pieces.put(s, out);
                        any = true;
                    } else if (!doomed.contains(s) && s.hit(x0, y0 - top, x1, y1 - top, er)) {
                        if (!eraseBefore.containsKey(page)) eraseBefore.put(page, pageState(page));
                        doomed.add(s);
                        any = true;
                    }
                    continue;
                }
                if (partialErase) {
                    ArrayList<Stroke> parts = s.cut(x0, y0 - top, x1, y1 - top, er);
                    if (parts == null) continue;
                    if (!eraseBefore.containsKey(page)) eraseBefore.put(page, pageState(page));
                    list.remove(i);
                    list.addAll(i, parts);
                    any = true;
                } else if (s.hit(x0, y0 - top, x1, y1 - top, er)) {
                    if (!eraseBefore.containsKey(page)) eraseBefore.put(page, pageState(page));
                    list.remove(i);
                    any = true;
                }
            }
        }
        for (int pi = p0; pi <= p1; pi++) { // tape comes off whole
            float top = tops[pi];
            Notebook.Page page = book.pages.get(pi);
            for (int i = page.items().size() - 1; i >= 0; i--) {
                Notebook.Item it = page.items().get(i);
                if (it.type != Notebook.Item.TAPE || it.layer != book.activeLayer) continue;
                float len = (float) Math.hypot(x1 - x0, y1 - y0);
                int steps = Math.max(1, (int) Math.ceil(len / Math.max(1f, er / 2)));
                for (int k = 0; k <= steps; k++) {
                    float t = k / (float) steps;
                    if (it.contains(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t - top, er * 0.6f)) {
                        if (erasePreview && doomedItems.contains(it)) break;
                        if (!eraseBefore.containsKey(page)) eraseBefore.put(page, pageState(page));
                        if (erasePreview) doomedItems.add(it); else page.items().remove(i);
                        any = true;
                        needCache = true;
                        break;
                    }
                }
            }
        }
        // eraser ring (window-only overlay)
        gl.pred.clear();
        predPrev.set(predCur);
        predCur.setEmpty();
        predMode = 0;
        overlay = true;
        float lw = dp(0.8f) / z;
        int seg = 28, ringColor = book.paper == Notebook.PAPER_DARK ? 0xccffffff : 0x99606878;
        for (int k = 0; k < seg; k++) {
            double a0 = 2 * Math.PI * k / seg, a1 = 2 * Math.PI * (k + 1) / seg;
            gl.pred.put(x1 + er * (float) Math.cos(a0), y1 + er * (float) Math.sin(a0),
                    x1 + er * (float) Math.cos(a1), y1 + er * (float) Math.sin(a1), lw, lw, ringColor);
        }
        predCur.set((x1 - er - ox) * z - 4, (y1 - er - oy) * z - 4, (x1 + er - ox) * z + 4, (y1 + er - oy) * z + 4);
        if (any) {
            needRebuild = true;
            requestFrame();
        } else {
            patchOverlay(null);
        }
    }

    // ink that runs across a page edge ---------------------------------------------------------
    //
    // A stroke belongs to the page it was started on, in that page's coordinates, but it can run on over the edge
    // into the next (or previous) page. Hit tests therefore look at the neighbours too, with coordinates made
    // relative to each page's own top.

    /** Furthest any stroke of page i reaches below its top (units), and above it (negative = above). */
    private float inkBottom(int i) {
        float b = -Float.MAX_VALUE;
        for (Stroke s : book.pages.get(i).strokes()) if (s.b > b) b = s.b;
        return b;
    }

    private float inkTop(int i) {
        float t = Float.MAX_VALUE;
        for (Stroke s : book.pages.get(i).strokes()) if (s.t < t) t = s.t;
        return t;
    }

    /** Pages whose ink can reach doc y range [y0, y1]: the pages there plus neighbours whose strokes spill over. */
    private int[] inkPages(float y0, float y1) {
        int p0 = pageAt(y0), p1 = pageAt(y1), a = p0, b = p1;
        for (int j = p0 - 1; j >= Math.max(0, p0 - 3); j--) if (tops[j] + inkBottom(j) >= y0) a = j;
        for (int j = p1 + 1; j <= Math.min(tops.length - 1, p1 + 3); j++) if (tops[j] + inkTop(j) <= y1) b = j;
        return new int[]{a, b};
    }

    /**
     * Strokes of other pages picked by a lasso on page `to` move into that page (same place on screen), so one
     * selection can hold them. The move only becomes an undo step together with whatever is then done to the
     * selection; if the selection is dropped untouched, the strokes go back where they were.
     */
    private Act rehome;
    private ArrayList<Notebook.Page> rehomePages;
    private ArrayList<Object[]> rehomeAfter;

    private ArrayList<Stroke> adopt(int to, java.util.LinkedHashMap<Integer, ArrayList<Stroke>> from) {
        ArrayList<Stroke> out = new ArrayList<>();
        if (from.isEmpty()) return out;
        Notebook.Page dst = book.pages.get(to);
        ArrayList<PageEdit> edits = new ArrayList<>();
        Object[] dstBefore = pageState(dst);
        for (Map.Entry<Integer, ArrayList<Stroke>> e : from.entrySet()) {
            Notebook.Page src = book.pages.get(e.getKey());
            Object[] before = pageState(src);
            IdentityHashMap<Stroke, Boolean> take = new IdentityHashMap<>();
            for (Stroke st : e.getValue()) take.put(st, Boolean.TRUE);
            ArrayList<Stroke> keep = new ArrayList<>(src.strokes().size());
            for (Stroke st : src.strokes()) if (!take.containsKey(st)) keep.add(st);
            src.setStrokes(keep);
            float dy = tops[e.getKey()] - tops[to];
            for (Stroke st : e.getValue()) { Stroke m = st.shifted(0, dy); dst.strokes().add(m); out.add(m); }
            edits.add(new PageEdit(src, before, pageState(src)));
        }
        edits.add(new PageEdit(dst, dstBefore, pageState(dst)));
        rehome = new Multi(edits);
        rehomePages = new ArrayList<>();
        rehomeAfter = new ArrayList<>();
        for (PageEdit pe : edits) { rehomePages.add(pe.p); rehomeAfter.add(pe.after); }
        needRebuild = true;
        return out;
    }

    /** The selection was dropped with nothing done to it: put adopted strokes back (if their pages are unchanged). */
    private void undoRehome() {
        if (rehome == null) return;
        Act r = rehome;
        rehome = null;
        for (int i = 0; i < rehomePages.size(); i++) {
            Notebook.Page pg = rehomePages.get(i);
            @SuppressWarnings("unchecked") ArrayList<Stroke> after = (ArrayList<Stroke>) rehomeAfter.get(i)[0];
            if (!sameRefs(pg.strokes(), after) || !book.pages.contains(pg)) { pushPlain(r); return; } // changed since: keep it, undoable
        }
        r.undo(book);
        needRebuild = true;
        docChanged();
    }

    private static boolean sameRefs(ArrayList<?> a, ArrayList<?> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (a.get(i) != b.get(i)) return false;
        return true;
    }

    /** Does the stroke lie (partly) in the loop? A part of it is enough: a few points, or a fifth of a short one. */
    private boolean lassoed(Stroke s, float[] xs, float[] ys, int m) {
        if (s.layer != book.activeLayer) return false; // the lasso only picks from the active layer
        if (s.r < min(xs, m) || s.l > max(xs, m) || s.b < min(ys, m) || s.t > max(ys, m)) return false;
        int in = 0;
        for (int i = 0; i < s.n; i++)
            if (Geo.inside(s.x(i), s.y(i), xs, ys, m) && (++in >= 4 || in >= 0.2f * s.n)) return true;
        return false;
    }

    private static float min(float[] a, int n) { float v = Float.MAX_VALUE; for (int i = 0; i < n; i++) v = Math.min(v, a[i]); return v; }

    private static float max(float[] a, int n) { float v = -Float.MAX_VALUE; for (int i = 0; i < n; i++) v = Math.max(v, a[i]); return v; }

    // lasso + selection ---------------------------------------------------------------------

    private boolean hasSel() { return selPage >= 0 && (!selStrokes.isEmpty() || !selItems.isEmpty()); }

    private void lassoBegin(MotionEvent ev) {
        ln = 0;
        lassoDash = 0;
        lassoPage = pageAt(oy + ev.getY() / z);
        predCur.setEmpty();
        predPrev.setEmpty();
        gl.pred.clear();
        predMode = 0;
        overlay = true;
        lassoAdd(ev.getX(), ev.getY());
    }

    private void lassoAdd(float sx, float sy) {
        float x = ox + sx / z, y = oy + sy / z;
        if (ln > 0 && Math.hypot(x - lx[ln - 1], y - ly[ln - 1]) < 2.5f / z) return;
        if (ln == lx.length) {
            lx = Arrays.copyOf(lx, ln * 2);
            ly = Arrays.copyOf(ly, ln * 2);
        }
        lx[ln] = x;
        ly[ln] = y;
        ln++;
        if (ln >= 2) {
            float w = dp(1.1f) / z;
            if ((ln / 3) % 2 == 0) gl.pred.put(lx[ln - 2], ly[ln - 2], x, y, w, w, 0xff3d5afe); // dashed
            unionSeg(predCur, lx[ln - 2], ly[ln - 2], x, y, w);
        }
    }

    private int lassoDash; // gl.pred segments of the loop itself; after them comes the closing line

    private void lassoEvent(MotionEvent ev, int a) {
        if (a == MotionEvent.ACTION_MOVE || a == MotionEvent.ACTION_UP) {
            gl.pred.truncate(lassoDash);
            int hs = ev.getHistorySize();
            for (int i = 0; i < hs; i++) lassoAdd(ev.getHistoricalX(i), ev.getHistoricalY(i));
            lassoAdd(ev.getX(), ev.getY());
            lassoDash = gl.pred.count;
            // a faint line back to the start shows how an open loop will be closed (it stays inside the loop's box)
            if (ln >= 3) {
                float w = dp(0.9f) / z, x0 = lx[ln - 1], y0 = ly[ln - 1], x1 = lx[0], y1 = ly[0];
                float len = (float) Math.hypot(x1 - x0, y1 - y0), step = dp(5) / z;
                int k = (int) (len / step);
                for (int i = 0; i < k; i += 2) {
                    float t0 = i / (float) k, t1 = Math.min(1f, (i + 1) / (float) k);
                    gl.pred.put(x0 + (x1 - x0) * t0, y0 + (y1 - y0) * t0, x0 + (x1 - x0) * t1, y0 + (y1 - y0) * t1, w, w, 0x993d5afe);
                }
            }
            patchOverlay(null);
        }
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            gest = G_NONE;
            gl.pred.clear();
            overlay = false;
            predPrev.set(predCur);
            predCur.setEmpty();
            patchOverlay(null);
            penNear = SystemClock.uptimeMillis();
            if (a == MotionEvent.ACTION_CANCEL || ln < 1) return;
            float top = tops[lassoPage];
            float[] xs = new float[ln], ys = new float[ln];
            for (int i = 0; i < ln; i++) {
                xs[i] = lx[i];
                ys[i] = ly[i] - top;
            }
            Notebook.Page p = book.pages.get(lassoPage);
            ArrayList<Stroke> ss = new ArrayList<>();
            ArrayList<Notebook.Item> si = new ArrayList<>();
            java.util.LinkedHashMap<Integer, ArrayList<Stroke>> other = new java.util.LinkedHashMap<>();
            if (ln >= 3) {
                // the loop is closed with a straight line from where the pen lifted back to where it started
                for (Stroke s : p.strokes()) if (lassoed(s, xs, ys, ln)) ss.add(s);
                for (Notebook.Item it : p.items()) if (it.layer == book.activeLayer && Geo.inside(it.x + it.w / 2, it.y + it.h / 2, xs, ys, ln)) si.add(it);
                // strokes started on a neighbouring page that run into this one
                int[] pr = inkPages(min(ly, ln), max(ly, ln));
                for (int j = pr[0]; j <= pr[1]; j++) {
                    if (j == lassoPage) continue;
                    float dy = tops[j] - top;
                    float[] ysj = new float[ln];
                    for (int i = 0; i < ln; i++) ysj[i] = ys[i] - dy;
                    for (Stroke s : book.pages.get(j).strokes())
                        if (lassoed(s, xs, ysj, ln)) other.computeIfAbsent(j, k -> new ArrayList<>()).add(s);
                }
            }
            // a tap (or tiny lasso) selects what's under it: a picture, text box or tape, else a stroke (a shape gets its handles)
            if (ss.isEmpty() && si.isEmpty()) {
                float cx = xs[0], cy = ys[0];
                for (int i = p.items().size() - 1; i >= 0; i--) {
                    Notebook.Item it = p.items().get(i);
                    if (it.layer == book.activeLayer && it.contains(cx, cy, 0)) {
                        si.add(it);
                        break;
                    }
                }
                if (si.isEmpty() && other.isEmpty()) {
                    float reach = dp(10) / z;
                    for (int i = p.strokes().size() - 1; i >= 0; i--) {
                        Stroke s = p.strokes().get(i);
                        if (s.layer == book.activeLayer && s.hit(cx, cy, cx, cy, reach)) { ss.add(s); break; }
                    }
                    if (ss.isEmpty()) { // a stroke from a neighbouring page under the tap
                        int[] pr = inkPages(cy + top - reach, cy + top + reach);
                        for (int j = pr[0]; j <= pr[1] && other.isEmpty(); j++) {
                            if (j == lassoPage) continue;
                            ArrayList<Stroke> list = book.pages.get(j).strokes();
                            float ly0 = cy + top - tops[j];
                            for (int i = list.size() - 1; i >= 0; i--)
                                if (list.get(i).layer == book.activeLayer && list.get(i).hit(cx, ly0, cx, ly0, reach)) {
                                    ArrayList<Stroke> one = new ArrayList<>();
                                    one.add(list.get(i));
                                    other.put(j, one);
                                    break;
                                }
                        }
                    }
                }
            }
            if (!other.isEmpty()) ss.addAll(adopt(lassoPage, other));
            if (!ss.isEmpty() || !si.isEmpty()) select(lassoPage, ss, si);
        }
    }

    private void select(int page, ArrayList<Stroke> sst, ArrayList<Notebook.Item> si) {
        selPage = page;
        selStrokes = sst;
        selItems = si;
        selBounds.set(bounds(sst, si));
        spx = spy = stx = sty = 0;
        ss = 1;
        needRebuild = true;
        requestFrame();
        notifyState();
    }

    private static RectF bounds(ArrayList<Stroke> sst, ArrayList<Notebook.Item> si) {
        RectF b = new RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
        for (Stroke s : sst) {
            b.left = Math.min(b.left, s.l); b.top = Math.min(b.top, s.t);
            b.right = Math.max(b.right, s.r); b.bottom = Math.max(b.bottom, s.b);
        }
        for (Notebook.Item it : si) {
            b.left = Math.min(b.left, it.x); b.top = Math.min(b.top, it.y);
            b.right = Math.max(b.right, it.x + it.w); b.bottom = Math.max(b.bottom, it.y + it.h);
        }
        if (b.left > b.right) b.set(0, 0, 0, 0);
        return b;
    }

    private void clearSelection() {
        undoRehome();
        if (selPage < 0) return;
        selPage = -1;
        selStrokes = new ArrayList<>();
        selItems = new ArrayList<>();
        needRebuild = true;
        wantFront = true;
        requestFrame();
        main.post(() -> { listener.onSelection(null); listener.onHandles(null, false, -1); });
        notifyState();
    }

    /** Selection rectangle in screen px (with the live transform). */
    private RectF selScreen() {
        float top = tops[selPage];
        float l = (selBounds.left - spx) * ss + spx + stx, t = (selBounds.top - spy) * ss + spy + sty;
        float r = (selBounds.right - spx) * ss + spx + stx, b = (selBounds.bottom - spy) * ss + spy + sty;
        return new RectF((l - ox) * z, (t + top - oy) * z, (r - ox) * z, (b + top - oy) * z);
    }

    private void postSelection() {
        if (!hasSel()) return;
        final RectF r = selScreen();
        final float[] hs = shapeMode() ? handlesScreen() : null;
        final boolean line = hs != null && selStrokes.get(0).shape == Stroke.LINE;
        final int box = hs != null && !line ? handlesLocal().length : -1; // where the box corners start in hs
        if (box >= 0) r.inset(-boxPad() * z - dp(8), -boxPad() * z - dp(8)); // the selection bar clears the scaling box
        main.post(() -> { listener.onSelection(r); listener.onHandles(hs, line, box); });
    }

    // ---- shape handles: a lone snapped shape can be stretched any time -------------------------

    private int shapeHandle = -1;   // handle being dragged (-1: moving the whole selection)
    private Stroke shapeOrig;
    private Object[] shapeBefore;
    private int shapeIdx;

    private boolean shapeMode() { return hasSel() && selItems.isEmpty() && selStrokes.size() == 1 && selStrokes.get(0).shape != 0; }

    private static RectF ptsBox(Stroke s) {
        RectF b = new RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
        for (int i = 0; i < s.n; i++) { b.left = Math.min(b.left, s.x(i)); b.right = Math.max(b.right, s.x(i)); b.top = Math.min(b.top, s.y(i)); b.bottom = Math.max(b.bottom, s.y(i)); }
        return b;
    }

    /**
     * A snapped polygon's corners (point indices): snapping repeats each corner point so it stays sharp, and that
     * repeat marks it. Null for an ellipse, which has none.
     */
    private static int[] corners(Stroke s) {
        ArrayList<Integer> c = new ArrayList<>();
        c.add(0);
        for (int i = 1; i + 1 < s.n - 1; i++) if (s.x(i) == s.x(i + 1) && s.y(i) == s.y(i + 1)) c.add(i);
        if (c.size() < 3 || c.size() > 12) return null;
        int[] r = new int[c.size()];
        for (int i = 0; i < r.length; i++) r[i] = c.get(i);
        return r;
    }

    /** Handle positions, page-local: a line's ends, a polygon's corners, or an ellipse's top, right, bottom and left. */
    private float[] handlesLocal() {
        Stroke s = selStrokes.get(0);
        if (s.shape == Stroke.LINE) return new float[]{s.x(0), s.y(0), s.x(s.n - 1), s.y(s.n - 1)};
        int[] c = corners(s);
        if (c != null) {
            float[] h = new float[2 * c.length];
            for (int i = 0; i < c.length; i++) { h[2 * i] = s.x(c[i]); h[2 * i + 1] = s.y(c[i]); }
            return h;
        }
        RectF b = ptsBox(s);
        float cx = b.centerX(), cy = b.centerY();
        return new float[]{cx, b.top, b.right, cy, cx, b.bottom, b.left, cy};
    }

    /** Padding (units) between a shape and its scaling box: keeps the box corners off the shape's own handles. */
    private float boxPad() { return dp(20) / z; }

    /**
     * The shape's own handles, then (not for a line) the four corners of a box around it: top-left, top-right,
     * bottom-right, bottom-left. Dragging a box corner scales the whole shape, keeping its proportions.
     */
    private float[] allHandlesLocal() {
        float[] h = handlesLocal();
        Stroke s = selStrokes.get(0);
        if (s.shape == Stroke.LINE) return h;
        RectF b = ptsBox(s);
        float pad = boxPad();
        float[] o = Arrays.copyOf(h, h.length + 8);
        int k = h.length;
        o[k] = b.left - pad; o[k + 1] = b.top - pad;
        o[k + 2] = b.right + pad; o[k + 3] = b.top - pad;
        o[k + 4] = b.right + pad; o[k + 5] = b.bottom + pad;
        o[k + 6] = b.left - pad; o[k + 7] = b.bottom + pad;
        return o;
    }

    private int shapeBox = -1; // box corner being dragged (0..3), or -1

    /** The same handles in screen px, following a selection that is being moved. */
    private float[] handlesScreen() {
        float[] h = allHandlesLocal();
        float top = tops[selPage];
        for (int i = 0; i + 1 < h.length; i += 2) {
            h[i] = (((h[i] - spx) * ss + spx + stx) - ox) * z;
            h[i + 1] = (((h[i + 1] - spy) * ss + spy + sty) + top - oy) * z;
        }
        return h;
    }

    private void shapeDrag(float sx, float sy) {
        float px = ox + sx / z, py = oy + sy / z - tops[selPage];
        Stroke o = shapeOrig;
        float pr = o.n > 0 ? o.p[2] : 0.5f;
        float[] pts;
        if (shapeBox >= 0) { // a box corner: the whole shape scales from the opposite corner, same proportions
            RectF b = ptsBox(o);
            float pad = boxPad();
            boolean left = shapeBox == 0 || shapeBox == 3, top = shapeBox < 2;
            float gx = left ? b.left - pad : b.right + pad, gy = top ? b.top - pad : b.bottom + pad;      // the grabbed corner
            float apx = left ? b.right + pad : b.left - pad, apy = top ? b.bottom + pad : b.top - pad;   // the opposite one
            float ax = left ? b.right : b.left, ay = top ? b.bottom : b.top;                             // the shape's own corner there
            float vx = gx - apx, vy = gy - apy;
            float k = ((px - apx) * vx + (py - apy) * vy) / Math.max(1e-4f, vx * vx + vy * vy); // along the diagonal
            float len = Math.max(b.width(), b.height()), lp = len + 2 * pad;
            float sc = Math.max(0.05f, Math.min(20f, (k * lp - 2 * pad) / Math.max(0.5f, len)));
            pts = new float[2 * o.n];
            for (int i = 0; i < o.n; i++) { pts[2 * i] = ax + (o.x(i) - ax) * sc; pts[2 * i + 1] = ay + (o.y(i) - ay) * sc; }
        } else if (o.shape == Stroke.LINE) {
            boolean startMoves = shapeHandle == 0;
            float fx = startMoves ? o.x(o.n - 1) : o.x(0), fy = startMoves ? o.y(o.n - 1) : o.y(0);
            float dx = px - fx, dy = py - fy, len = (float) Math.hypot(dx, dy);
            double ang = Math.toDegrees(Math.atan2(dy, dx)), near = Math.round(ang / 45.0) * 45.0;
            if (Math.abs(ang - near) < 5) { double r = Math.toRadians(near); dx = (float) Math.cos(r) * len; dy = (float) Math.sin(r) * len; }
            pts = startMoves ? new float[]{fx + dx, fy + dy, fx, fy} : new float[]{fx, fy, fx + dx, fy + dy};
            pts = Shapes.densify(pts, false);
        } else if (corners(o) != null) { // polygon: that corner goes where the pen is, the others stay
            int[] c = corners(o);
            float[] v = new float[2 * c.length + 2];
            for (int i = 0; i < c.length; i++) { v[2 * i] = o.x(c[i]); v[2 * i + 1] = o.y(c[i]); }
            v[2 * shapeHandle] = px;
            v[2 * shapeHandle + 1] = py;
            v[2 * c.length] = v[0];
            v[2 * c.length + 1] = v[1];
            pts = Shapes.densify(v, true);
        } else { // ellipse: top / right / bottom / left handles move that side, the opposite side stays
            RectF b = ptsBox(o);
            float l = b.left, t = b.top, r = b.right, bt = b.bottom, min = 6;
            int hI = shapeHandle;
            if (hI == 3) l = Math.min(px, r - min);
            if (hI == 1) r = Math.max(px, l + min);
            if (hI == 0) t = Math.min(py, bt - min);
            if (hI == 2) bt = Math.max(py, t + min);
            float kx = b.width() > 0.5f ? (r - l) / b.width() : 1, ky = b.height() > 0.5f ? (bt - t) / b.height() : 1;
            pts = new float[2 * o.n];
            for (int i = 0; i < o.n; i++) { pts[2 * i] = l + (o.x(i) - b.left) * kx; pts[2 * i + 1] = t + (o.y(i) - b.top) * ky; }
        }
        Stroke ns = new Stroke(o.tool, o.color, o.width);
        ns.layer = o.layer;
        ns.shape = o.shape;
        for (int i = 0; i + 1 < pts.length; i += 2) ns.add(pts[i], pts[i + 1], pr);
        book.pages.get(selPage).strokes().set(shapeIdx, ns);
        selStrokes = new ArrayList<>();
        selStrokes.add(ns);
        selBounds.set(bounds(selStrokes, selItems));
        needRebuild = true;
        requestFrame();
    }

    private boolean selHit(float sx, float sy) {
        RectF r = selScreen();
        shapeHandle = -1;
        if (shapeMode()) {
            float[] h = handlesScreen();
            float best = dp(26);
            for (int i = 0; i + 1 < h.length; i += 2) {
                float d = (float) Math.hypot(sx - h[i], sy - h[i + 1]);
                if (d < best) { best = d; shapeHandle = i / 2; }
            }
            selScale = false;
            if (shapeHandle >= 0) return true;
            if (tool != TOOL_LASSO) return false; // pen: inside a shape you write (labels in a box); only handles grab
            float pad = dp(18);
            return sx > r.left - pad && sx < r.right + pad && sy > r.top - pad && sy < r.bottom + pad;
        }
        float pad = dp(18);
        selScale = Math.hypot(sx - r.right, sy - r.bottom) < dp(30);
        return selScale || (sx > r.left - pad && sx < r.right + pad && sy > r.top - pad && sy < r.bottom + pad);
    }

    private void selBegin(MotionEvent ev) {
        sDownX = ev.getX();
        sDownY = ev.getY();
        shapeBox = -1;
        if (shapeHandle >= 0) {
            int own = handlesLocal().length / 2; // handles after the shape's own are the box corners
            if (shapeHandle >= own) shapeBox = shapeHandle - own;
            shapeOrig = selStrokes.get(0);
            Notebook.Page p = book.pages.get(selPage);
            shapeBefore = pageState(p);
            shapeIdx = p.strokes().indexOf(shapeOrig);
            if (shapeIdx < 0) shapeHandle = -1;
        }
        spx = selScale ? selBounds.left : 0;
        spy = selScale ? selBounds.top : 0;
        ss = 1;
        stx = sty = 0;
    }

    private void selEvent(MotionEvent ev, int a) {
        if (shapeHandle >= 0) {
            Notebook.Page p = book.pages.get(selPage);
            if (a == MotionEvent.ACTION_MOVE) {
                shapeDrag(ev.getX(), ev.getY());
                postSelection();
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                gest = G_NONE;
                penNear = SystemClock.uptimeMillis();
                Stroke now = p.strokes().get(shapeIdx);
                if (a == MotionEvent.ACTION_UP && now != shapeOrig) {
                    push(new PageEdit(p, shapeBefore, pageState(p)));
                    edited();
                } else if (now != shapeOrig) { // cancelled: put it back
                    p.strokes().set(shapeIdx, shapeOrig);
                    selStrokes = new ArrayList<>();
                    selStrokes.add(shapeOrig);
                    selBounds.set(bounds(selStrokes, selItems));
                }
                shapeHandle = -1;
                needRebuild = true;
                wantFront = true;
                requestFrame();
                postSelection();
            }
            return;
        }
        if (a == MotionEvent.ACTION_MOVE) {
            float dx = (ev.getX() - sDownX) / z, dy = (ev.getY() - sDownY) / z;
            if (selScale) {
                float w0 = Math.max(4, selBounds.width()), h0 = Math.max(4, selBounds.height());
                ss = Math.max(0.15f, Math.min(8f, Math.max((w0 + dx) / w0, (h0 + dy) / h0)));
            } else {
                stx = dx;
                sty = dy;
            }
            requestFrame();
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            gest = G_NONE;
            penNear = SystemClock.uptimeMillis();
            boolean changed = a == MotionEvent.ACTION_UP && (Math.abs(stx) > 0.01f || Math.abs(sty) > 0.01f || Math.abs(ss - 1) > 0.001f);
            if (changed) {
                ArrayList<Stroke> ns = new ArrayList<>();
                ArrayList<Notebook.Item> ni = new ArrayList<>();
                for (Stroke s : selStrokes) ns.add(s.transformed(spx, spy, ss, stx, sty));
                for (Notebook.Item it : selItems) ni.add(it.moved(spx, spy, ss, stx, sty));
                spx = spy = stx = sty = 0;
                ss = 1;
                dropTextTextures();
                replaceSelection(ns, ni, true);
            } else {
                spx = spy = stx = sty = 0;
                ss = 1;
                needCache = true;
                wantFront = true;
                requestFrame();
            }
        }
    }

    /** Replace the selected strokes/items on their page with new ones (one undo step). */
    private void replaceSelection(ArrayList<Stroke> ns, ArrayList<Notebook.Item> ni, boolean keepSelected) {
        Notebook.Page p = book.pages.get(selPage);
        Object[] before = pageState(p);
        IdentityHashMap<Object, Boolean> old = new IdentityHashMap<>();
        for (Stroke s : selStrokes) old.put(s, Boolean.TRUE);
        for (Notebook.Item it : selItems) old.put(it, Boolean.TRUE);
        int si = -1; // keep z-order: replacements go where the first replaced stroke was
        for (int i = 0; i < p.strokes().size(); i++) if (old.containsKey(p.strokes().get(i))) { si = i; break; }
        p.strokes().removeIf(old::containsKey);
        p.items().removeIf(old::containsKey);
        p.strokes().addAll(si < 0 ? p.strokes().size() : Math.min(si, p.strokes().size()), ns);
        p.items().addAll(ni);
        push(new PageEdit(p, before, pageState(p)));
        int pg = selPage;
        if (keepSelected && (!ns.isEmpty() || !ni.isEmpty())) select(pg, ns, ni);
        else clearSelection();
        edited();
    }

    // text --------------------------------------------------------------------------------

    private void textTap(float sx, float sy) {
        float x = ox + sx / z, y = oy + sy / z;
        if (coverOn() && y < 0) return; // the cover takes no text
        int pg = pageAt(y);
        Notebook.Page p = book.pages.get(pg);
        float py = y - tops[pg];
        TextEdit e = new TextEdit();
        e.page = pg;
        for (int i = p.items().size() - 1; i >= 0; i--) {
            Notebook.Item it = p.items().get(i);
            if (it.type == Notebook.Item.TEXT && book.layerVisible(it.layer) && x >= it.x - 6 && x <= it.x + it.w + 6 && py >= it.y - 6 && py <= it.y + it.h + 6) {
                e.item = it;
                break;
            }
        }
        if (e.item != null) {
            e.docX = e.item.x;
            e.docY = e.item.y;
            e.width = e.item.w;
            e.size = e.item.size;
            e.color = e.item.color;
            e.style = e.item.style;
            editing = e.item;
            needCache = true;
            requestFrame();
        } else {
            e.docX = canvas() ? x : Math.max(0, Math.min(PW - 60, x));
            e.docY = canvas() ? py - textSize * 0.6f : Math.max(0, py - textSize * 0.6f);
            e.width = canvas() ? 640 : Math.max(120, Math.min(640, PW - e.docX - 30));
            e.size = textSize;
            e.color = textColor != 0 ? textColor : penColor;
            e.style = textStyle;
        }
        e.zoom = z;
        e.screenX = (e.docX - ox) * z;
        e.screenY = (e.docY + tops[pg] - oy) * z;
        main.post(() -> listener.onEditText(e));
    }

    // pan / zoom ----------------------------------------------------------------------------

    private void panBegin(MotionEvent ev) {
        if (vt != null) vt.recycle();
        vt = VelocityTracker.obtain();
        vt.addMovement(ev);
        downT = ev.getEventTime();
        maxPtr = ev.getPointerCount();
        moved = false;
        stopFling();
        baseline(ev, -1);
    }

    private void baseline(MotionEvent ev, int skip) {
        float[] m = mid(ev, skip);
        sMidX = m[0];
        sMidY = m[1];
        sDist = m[2];
        sOx = ox;
        sOy = oy;
        sZ = z;
    }

    private static float[] mid(MotionEvent ev, int skip) {
        float x0 = 0, y0 = 0, x1 = 0, y1 = 0;
        int k = 0;
        for (int i = 0; i < ev.getPointerCount() && k < 2; i++) {
            if (i == skip) continue;
            if (k == 0) { x0 = ev.getX(i); y0 = ev.getY(i); } else { x1 = ev.getX(i); y1 = ev.getY(i); }
            k++;
        }
        if (k < 2) return new float[]{x0, y0, 0};
        return new float[]{(x0 + x1) / 2, (y0 + y1) / 2, (float) Math.hypot(x1 - x0, y1 - y0)};
    }

    private boolean zooming;

    private void panEvent(MotionEvent ev, int a) {
        if (vt != null) vt.addMovement(ev);
        switch (a) {
            case MotionEvent.ACTION_POINTER_DOWN:
                maxPtr = Math.max(maxPtr, ev.getPointerCount());
                baseline(ev, -1);
                break;
            case MotionEvent.ACTION_POINTER_UP:
                baseline(ev, ev.getActionIndex());
                break;
            case MotionEvent.ACTION_MOVE: {
                float[] m = mid(ev, -1);
                if (!moved) {
                    float slop = dp(8);
                    if (Math.abs(m[0] - sMidX) < slop && Math.abs(m[1] - sMidY) < slop && Math.abs(m[2] - sDist) < slop) break;
                    moved = true;
                    zooming = false;
                    gesturing = true;
                    main.post(listener::onDismissText);
                    baseline(ev, -1);
                    break;
                }
                float nz = sZ;
                if (ev.getPointerCount() >= 2 && sDist > 0 && m[2] > 0) {
                    // scrolling with two fingers never holds their gap perfectly: only a clear pinch zooms
                    if (!zooming && !zoomLocked && Math.abs(m[2] - sDist) > Math.max(dp(36), sDist * 0.14f)) {
                        zooming = true;
                        baseline(ev, -1); // zoom starts from here, no jump
                        break;
                    }
                    if (zooming) nz = sZ * m[2] / sDist;
                }
                nz = Math.max(minZ(), Math.min(fitZ * maxZoomRel, nz));
                float ax = sOx + sMidX / sZ, ay = sOy + sMidY / sZ;
                if (nz != z) {
                    long now = SystemClock.uptimeMillis();
                    if (now - zoomPostedAt > 50) { zoomPostedAt = now; final int pct = Math.round(nz / fitZ * 100); main.post(() -> listener.onZoom(pct)); }
                }
                z = nz;
                ox = ax - m[0] / nz;
                oy = ay - m[1] / nz;
                clampView();
                requestFrame();
                break;
            }
            case MotionEvent.ACTION_UP: {
                if (!moved && ev.getEventTime() - downT < 300) {
                    if (maxPtr == 3) doRedo(true); // two-finger tap undo removed (owner's choice): it fired by accident
                    else if (maxPtr == 1 && toggleTapeAt(ox + ev.getX() / z, oy + ev.getY() / z, false)) {
                        lastTapT = 0; // a tap on tape peels it; it doesn't start a double-tap
                    } else if (maxPtr == 1 && textAt(ev.getX(), ev.getY())) {
                        lastTapT = 0;
                        textTap(ev.getX(), ev.getY()); // tap a text box (typed or converted) to edit it
                    } else if (maxPtr == 1) {
                        float x = ev.getX(), y = ev.getY();
                        if (ev.getEventTime() - lastTapT < 330 && Math.hypot(x - lastTapX, y - lastTapY) < dp(40)) {
                            lastTapT = 0;
                            fitAt(x, y);
                        } else {
                            lastTapT = ev.getEventTime();
                            lastTapX = x;
                            lastTapY = y;
                        }
                    }
                } else if (moved && vt != null) {
                    vt.computeCurrentVelocity(1000, dp(8000));
                    vx = vt.getXVelocity(ev.getPointerId(0));
                    vy = vt.getYVelocity(ev.getPointerId(0));
                    if (maxPtr == 1 && Math.hypot(vx, vy) > dp(250)) {
                        flinging = true;
                        lastFrame = 0;
                        requestFrame();
                    }
                }
                endGesture();
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                endGesture();
                break;
        }
    }

    private void endGesture() {
        gest = G_NONE;
        gesturing = false;
        notifyState();
        if (!flinging) {
            needCache = true;
            wantFront = true;
            requestFrame();
        }
    }

    private void stepFling(long t) {
        float dt = lastFrame == 0 ? 1 / 144f : Math.min(0.05f, (t - lastFrame) / 1e9f);
        lastFrame = t;
        float px = ox, py = oy;
        ox -= vx * dt / z;
        oy -= vy * dt / z;
        clampView();
        if (ox == px) vx = 0;
        if (oy == py) vy = 0;
        float k = (float) Math.exp(-dt * 3.0);
        vx *= k;
        vy *= k;
        if (Math.hypot(vx, vy) < dp(20)) stopFling();
    }

    private void stopFling() {
        if (!flinging) return;
        flinging = false;
        needCache = true;
        wantFront = true;
        requestFrame();
        notifyState();
    }

    private void checkPage() {
        int p = currentPage();
        if (p != lastPage) {
            lastPage = p;
            notifyState();
        }
    }

    // ---- document edits -------------------------------------------------------------------

    private abstract static class Act {
        abstract void undo(Notebook b);
        abstract void redo(Notebook b);
    }

    private static Object[] pageState(Notebook.Page p) {
        return new Object[]{new ArrayList<>(p.strokes()), new ArrayList<>(p.items())};
    }

    /** Before/after snapshot of one page's strokes and items. */
    private static final class PageEdit extends Act {
        final Notebook.Page p;
        final Object[] before, after;
        PageEdit(Notebook.Page p, Object[] before, Object[] after) { this.p = p; this.before = before; this.after = after; }
        @SuppressWarnings("unchecked")
        private void set(Object[] s) {
            p.setStrokes(new ArrayList<>((ArrayList<Stroke>) s[0]));
            p.setItems(new ArrayList<>((ArrayList<Notebook.Item>) s[1]));
        }
        void undo(Notebook b) { set(before); }
        void redo(Notebook b) { set(after); }
    }

    private static final class Multi extends Act {
        final ArrayList<PageEdit> edits;
        Multi(ArrayList<PageEdit> e) { edits = e; }
        void undo(Notebook b) { for (PageEdit e : edits) e.undo(b); }
        void redo(Notebook b) { for (PageEdit e : edits) e.redo(b); }
    }

    private static final class PagesEdit extends Act {
        final ArrayList<Notebook.Page> before, after;
        PagesEdit(ArrayList<Notebook.Page> b, ArrayList<Notebook.Page> a) { before = b; after = a; }
        void undo(Notebook b) { b.pages = new ArrayList<>(before); }
        void redo(Notebook b) { b.pages = new ArrayList<>(after); }
    }

    private static final class SetLook extends Act {
        final Notebook.Page p, from, to;
        SetLook(Notebook.Page p, Notebook.Page from, Notebook.Page to) { this.p = p; this.from = from; this.to = to; }
        void undo(Notebook b) { p.copyStyle(from); }
        void redo(Notebook b) { p.copyStyle(to); }
    }

    private static final class MultiAct extends Act {
        final ArrayList<Act> acts;
        MultiAct(ArrayList<Act> acts) { this.acts = acts; }
        void undo(Notebook b) { for (int i = acts.size() - 1; i >= 0; i--) acts.get(i).undo(b); }
        void redo(Notebook b) { for (Act a : acts) a.redo(b); }
    }

    private static final class LayersEdit extends Act {
        final ArrayList<Notebook.Layer> before, after;
        final int activeBefore, activeAfter;
        LayersEdit(ArrayList<Notebook.Layer> b, int ab, ArrayList<Notebook.Layer> a, int aa) { before = b; activeBefore = ab; after = a; activeAfter = aa; }
        void undo(Notebook n) { n.layers = Notebook.copyLayers(before); n.activeLayer = activeBefore; }
        void redo(Notebook n) { n.layers = Notebook.copyLayers(after); n.activeLayer = activeAfter; }
    }

    private static final class SetBg extends Act {
        final Notebook.Page p; final int from, to;
        SetBg(Notebook.Page p, int from, int to) { this.p = p; this.from = from; this.to = to; }
        void undo(Notebook b) { p.bg = from; }
        void redo(Notebook b) { p.bg = to; }
    }

    private void push(Act a) {
        if (rehome != null) {
            ArrayList<Act> both = new ArrayList<>();
            both.add(rehome);
            both.add(a);
            rehome = null;
            a = new MultiAct(both);
        }
        pushPlain(a);
    }

    private void pushPlain(Act a) {
        undo.push(a);
        if (undo.size() > 500) undo.removeLast();
        redo.clear();
        notifyState();
    }

    private void stepUndo() {
        Act a = undo.pop();
        a.undo(book);
        redo.push(a);
    }

    private void stepRedo() {
        Act a = redo.pop();
        a.redo(book);
        undo.push(a);
    }

    private void doUndo(boolean gesture) {
        if (book == null || undo.isEmpty() || live != null) return;
        clearSelection();
        stepUndo();
        applyPaper();
        dropTextTextures();
        edited();
        if (gesture) toast("Undo");
    }

    private void doRedo(boolean gesture) {
        if (book == null || redo.isEmpty() || live != null) return;
        clearSelection();
        stepRedo();
        applyPaper();
        dropTextTextures();
        edited();
        if (gesture) toast("Redo");
    }

    private void toast(String s) { main.post(() -> listener.onToast(s)); }

    /** Keeps one blank page after the last written one, so the notebook simply continues as you write. */
    private boolean ensureTrailingPage() {
        if (book == null || book.canvas || book.pages.isEmpty()) return false;
        Notebook.Page last = book.pages.get(book.pages.size() - 1);
        if (last.pdf >= 0 || (last.strokes().isEmpty() && last.items().isEmpty())) return false;
        Notebook.Page np = new Notebook.Page(last.bg);
        np.copyStyle(last); // the next page looks like this one
        book.pages.add(np);
        relayout();
        return true;
    }

    private void notifyStateLater() { h.post(this::notifyState); }

    private void doAddPage(int after) {
        if (book == null) return;
        ArrayList<Notebook.Page> before = new ArrayList<>(book.pages);
        Notebook.Page cur = book.pages.get(after);
        Notebook.Page np = new Notebook.Page(cur.pdf >= 0 ? Notebook.BG_LINED : cur.bg);
        np.copyStyle(cur);
        book.pages.add(after + 1, np);
        push(new PagesEdit(before, new ArrayList<>(book.pages)));
        relayout();
        oy = tops[after + 1] - topPad / z;
        edited();
    }

    private void doSetBg(int i, int bg) {
        if (book == null) return;
        Notebook.Page p = book.pages.get(i);
        if (p.bg == bg) return;
        push(new SetBg(p, p.bg, bg));
        p.bg = bg;
        edited();
    }

    private void edited() {
        relayout();
        clampView();
        needRebuild = true;
        wantFront = true;
        requestFrame();
        notifyState();
        docChanged();
    }

    private boolean dirtyDoc;

    private void docChanged() {
        dirtyDoc = true;
        h.removeCallbacks(saveR);
        h.postDelayed(saveR, 400);
    }

    private final Runnable saveR = this::save;

    private void save() {
        if (book == null || !dirtyDoc) return;
        dirtyDoc = false;
        final Notebook snap = book.snapshot(true);
        final Notebook owner = book;
        io.execute(() -> {
            try {
                long t0 = System.nanoTime();
                snap.write(dir);
                final ArrayList<Notebook.Page> done = new ArrayList<>(snap.written);
                if (!done.isEmpty() || snap.pages.size() > 50)
                    Log.i(Gl.TAG, String.format(Locale.US, "saved %d of %d pages in %d ms", done.size(), snap.pages.size(), (System.nanoTime() - t0) / 1_000_000));
                // the originals now match their files (unless they changed meanwhile: then they're still dirty)
                h.post(() -> {
                    if (book != owner) return;
                    owner.legacy = false; // converted: it's page files now
                    for (Notebook.Page c : done) {
                        Notebook.Page o = c.origin;
                        if (o == null) continue;
                        if (o.id == c.id) o.markSaved(c.strokes().toArray(), c.items().toArray(), c.src);
                    }
                });
            } catch (Exception e) {
                Log.e(Gl.TAG, "save failed", e);
                Diag.warn(dir.getName() + ": save failed: " + e);
                toast("Couldn't save · will try again (is storage full?)");
                h.post(() -> { dirtyDoc = true; h.removeCallbacks(saveR); h.postDelayed(saveR, 5000); });
            }
        });
    }

    private void notifyState() {
        if (book == null) return;
        State s = new State();
        s.canUndo = !undo.isEmpty();
        s.canRedo = !redo.isEmpty();
        s.undoCount = undo.size();
        s.redoCount = redo.size();
        s.front = front;
        s.pages = book.pages.size();
        s.page = Math.min(W > 0 ? currentPage() : 0, s.pages - 1);
        s.bg = book.pages.get(s.page).bg;
        Notebook.Page cp = book.pages.get(s.page);
        s.spacing = cp.spacing;
        s.strength = cp.strength;
        s.lineColor = cp.lineColor;
        s.margins = cp.margins;
        s.marks = new String[s.pages];
        for (int i = 0; i < s.pages; i++) s.marks[i] = book.pages.get(i).mark;
        s.zoom = fitZ > 0 ? Math.round(z / fitZ * 100) : 100;
        s.zoomLocked = zoomLocked;
        if (W > 0 && tops.length > 0) {
            int tp = Math.max(0, Math.min(tops.length - 1, pageAt(oy)));
            s.topPage = tp;
            s.topFrac = (oy - tops[tp]) / book.pages.get(tp).h;
            s.zRel = fitZ > 0 ? z / fitZ : 1f;
            s.viewX = ox;
            s.viewKnown = true;
        }
        s.z = z;
        s.paper = book.paper;
        s.canvas = book.canvas;
        s.title = book.title;
        int ln = book.layers.size();
        s.layerIds = new int[ln];
        s.layerNames = new String[ln];
        s.layerVisible = new boolean[ln];
        for (int i = 0; i < ln; i++) {
            Notebook.Layer l = book.layers.get(i);
            s.layerIds[i] = l.id;
            s.layerNames[i] = l.name;
            s.layerVisible[i] = l.visible;
        }
        s.activeLayer = book.activeLayer;
        s.hasSelection = hasSel();
        s.selText = hasSel() && selStrokes.isEmpty() && selItems.size() == 1 && selItems.get(0).type == Notebook.Item.TEXT;
        s.hasClipboard = !clipStrokes.isEmpty() || !clipItems.isEmpty();
        main.post(() -> listener.onState(s));
        if (!hasSel()) main.post(() -> listener.onSelection(null));
    }
}

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
    static final float[] TEXT_SIZES = {20f, 28f, 42f};

    static final class State {
        boolean canUndo, canRedo, front, hasSelection, hasClipboard, canvas;
        int page, pages, bg, zoom, paper, undoCount, redoCount;
        String[] marks; // per page: bookmark title ("" untitled) or null
        float spacing = 1f, strength = 1f; // current page's look
        int lineColor, margins;
        float z;        // screen px per page unit
        String title;
    }

    /** Request for the UI to show a text editor over the page. */
    static final class TextEdit {
        int page;
        Notebook.Item item; // null = new box
        float docX, docY, width, size;
        int color;
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
        default void onHandles(float[] screen, boolean line) {}
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
    private volatile long penNear, stylusAt; // stylusAt: only the real pen (hover / touch), for palm rejection
    private boolean fingerGest;             // the current gesture was started by a finger (finger drawing on)
    private static final float FINGER_P = 0.5f; // fingers report contact size, not pressure: draw at the nominal width

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
    private int stN, stFront, stSwap;
    private long stT0;

    Engine(Context c, File dir, Listener l) {
        app = c.getApplicationContext();
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
            book = Notebook.load(dir);
            if (book.loadFailed) toast("Part of this notebook couldn't be read · the original file was kept, nothing is lost yet");
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
            for (int i = p.items.size() - 1; i >= 0; i--) {
                Notebook.Item it = p.items.get(i);
                if (it.type != Notebook.Item.TAPE) continue;
                any = true;
                if (op == 2) p.items.remove(i);
                else p.items.set(i, it.withRevealed(op == 1));
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
                    for (Stroke s : d.strokes) own.add(s.shifted(0, 0));
                    d.strokes = own;
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
                case "clear": {
                    Notebook.Page p = book.pages.get(i);
                    if (p.strokes.isEmpty() && p.items.isEmpty()) return;
                    Object[] b4 = pageState(p);
                    p.strokes = new ArrayList<>();
                    p.items = new ArrayList<>();
                    push(new PageEdit(p, b4, pageState(p)));
                    edited();
                    toast("Page cleared · two-finger tap to undo");
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
            final Notebook.Page p = book.pages.get(i).copyShallow();
            final Notebook nb = book.snapshot();
            worker.execute(() -> {
                Bitmap b = Render.pageBitmap(nb, dir, p, p.pdf >= 0 ? pdf() : null, widthPx);
                main.post(() -> cb.accept(b));
            });
        });
    }

    /** Snapshot of the notebook for export, delivered on the main thread. */
    void snapshot(java.util.function.Consumer<Notebook> cb) {
        h.post(() -> { if (book != null) { Notebook s = book.snapshot(); main.post(() -> cb.accept(s)); } });
    }

    // selection actions
    void selDelete() { h.post(() -> { if (hasSel()) replaceSelection(new ArrayList<>(), new ArrayList<>(), false); }); }

    void selDuplicate() {
        h.post(() -> {
            if (!hasSel()) return;
            Notebook.Page p = book.pages.get(selPage);
            Object[] before = pageState(p);
            ArrayList<Stroke> ns = new ArrayList<>();
            ArrayList<Notebook.Item> ni = new ArrayList<>();
            for (Stroke s : selStrokes) ns.add(s.shifted(24, 24));
            for (Notebook.Item it : selItems) ni.add(it.moved(0, 0, 1, 24, 24));
            p.strokes.addAll(ns);
            p.items.addAll(ni);
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
            for (Stroke s : clipStrokes) ns.add(s.shifted(dx, dy));
            for (Notebook.Item it : clipItems) ni.add(it.moved(0, 0, 1, dx, dy));
            p.strokes.addAll(ns);
            p.items.addAll(ni);
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
            if (e.item != null) p.items.remove(e.item);
            String t = text.replaceAll("\\s+$", "");
            if (!t.isEmpty()) {
                StaticLayout sl = Render.textLayout(t, e.size, e.color, e.width);
                float w = 0;
                for (int i = 0; i < sl.getLineCount(); i++) w = Math.max(w, sl.getLineWidth(i));
                float bw = e.item != null ? e.width : Math.max(40, Math.min(e.width, w + 6));
                p.items.add(new Notebook.Item(Notebook.Item.TEXT, e.docX, e.docY, bw, sl.getHeight(), t, e.size, e.color));
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

    void insertImage(String name, int pxW, int pxH) {
        h.post(() -> {
            if (book == null) return;
            int pg = currentPage();
            Notebook.Page p = book.pages.get(pg);
            float w = Math.min(PW * 0.6f, pxW / 2f), hh = w * pxH / Math.max(1f, pxW);
            if (hh > p.h * 0.6f) { hh = p.h * 0.6f; w = hh * pxW / Math.max(1f, pxH); }
            float cx = canvas() ? ox + W / (2 * z) : Math.max(w / 2, Math.min(PW - w / 2, ox + W / (2 * z)));
            float cy = canvas() ? oy + H / (2 * z) : Math.max(hh / 2, Math.min(p.h - hh / 2, oy + H / (2 * z) - tops[pg]));
            Object[] before = pageState(p);
            Notebook.Item it = new Notebook.Item(Notebook.Item.IMAGE, cx - w / 2, cy - hh / 2, w, hh, name, 0, 0);
            p.items.add(it);
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
            boolean sizeChanged = w != W || hh != H;
            float cx = ox + W / (2 * z), cy = oy + H / (2 * z);
            gl.setSurface(s, w, hh);
            gl.pen.lost();
            gl.hl.lost();
            gl.pred.lost();
            gl.selPen.lost();
            gl.selHl.lost();
            gl.laser.lost();
            for (Tex t : texs.values()) gl.deleteTex(t.id);
            texs.clear();
            texBytes = 0;
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
            if (gl != null) gl.dropSurface();
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
        if (W == 0) return;
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
        if (pendingPage > 0 && pendingPage < tops.length) oy = tops[pendingPage] - topPad / z; // opened from a search hit
        pendingPage = -1;
        clampView();
    }

    private int pendingPage = -1;

    /** Open the notebook at page i (before the first frame). */
    void openAtPage(int i) { h.post(() -> pendingPage = i); }

    private boolean canvas() { return book != null && book.canvas; }

    private float minZ() { return fitZ * (canvas() ? 0.1f : 0.45f); }

    private void clampView() {
        z = Math.max(minZ(), Math.min(fitZ * 8f, z));
        if (canvas()) return; // infinite canvas: go anywhere
        float vw = W / z, vh = H / z;
        if (PW * z <= W) ox = (PW - vw) / 2;
        else ox = Math.max(-sidePad / z, Math.min(PW - vw + sidePad / z, ox));
        float minY = -topPad / z, maxY = docH - vh + botPad / z;
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
        } else if (needCache || needPresent || (wantFront && !front)) {
            renderNow();
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
            if (laserMode != LASER_DOT) for (int i = 1; i < n; i++) {
                float[] p0 = laserPts.get(i - 1), p1 = laserPts.get(i);
                if (Float.isNaN(p0[0]) || Float.isNaN(p1[0])) continue;
                float a = laserMode == LASER_INK ? a0 * inkA : a0 * Math.max(0f, 1f - (now - p1[2]) / (float) laserLen);
                if (a <= 0.01f) continue;
                b.put(p0[0], p0[1], p1[0], p1[1], laserMode == LASER_INK ? r : r * 0.8f, r, ((int) (a * 255) << 24) | col[k]);
            }
            if (last != null && tipOnLine && tipA > 0) {
                float tr = r * (k == 0 ? 1.6f : 1.5f);
                b.put(last[0], last[1], last[0], last[1], tr, tr, ((int) (a0 * tipA * 255) << 24) | col[k]);
            }
        }
        gl.drawSegs(b, 0, b.count, 0);
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
        final Notebook.Item it = Notebook.Item.tape(pts, tapeThick, tapePattern, tapeColor);
        final int covered = tapeFree ? tn : 2, gen = draftGen;
        final float k = Math.min(z, 2048f / Math.max(1f, Math.max(it.w, it.h)));
        draftBusy = true;
        worker.execute(() -> {
            Bitmap b = null;
            try { b = Render.tapeBitmap(it, k); } catch (Exception e) { Log.w(Gl.TAG, "tape draft", e); }
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
        p.items.add(Notebook.Item.tape(pts, tapeThick, tapePattern, tapeColor));
        push(new PageEdit(p, before, pageState(p)));
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
            ArrayList<Notebook.Item> items = book.pages.get(pi).items;
            for (int i = items.size() - 1; i >= 0; i--) {
                Notebook.Item it = items.get(i);
                if (it.type == Notebook.Item.TAPE && it.contains(x, y - tops[pi], pad)) return new int[]{pi, i};
            }
        }
        return null;
    }

    /** Flips the tape at x,y between covering and peeled back (not an undo step: it's how you study). */
    private boolean toggleTapeAt(float x, float y, boolean onlyCovered) {
        int[] hit = tapeAt(x, y);
        if (hit == null) return false;
        ArrayList<Notebook.Item> items = book.pages.get(hit[0]).items;
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
        gl.clear(deskColor);
        gl.resetXform();
        int first = Math.max(0, pageAt(oy) - 1), last = Math.min(tops.length - 1, pageAt(oy + H / z) + 1);
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
            if (p.pdf >= 0) {
                final int idx = p.pdf;
                Tex t = hiRes ? tex("pdf:" + idx + ":3600", () -> pdfPage(idx, 3600)) : null;
                if (t == null) t = tex("pdf:" + idx + ":1800", () -> pdfPage(idx, 1800));
                if (t != null) gl.tex(t.id, 0, tops[i], PW, p.h, 1);
            }
            for (Notebook.Item it : p.items) {
                if (it == editing || it.type == Notebook.Item.TAPE || (i == selPage && selItems.contains(it))) continue;
                drawItem(it, tops[i]);
            }
        }
        // the notebook never ends on screen: paper continues below the last page (it becomes real when written on)
        if (!canvas() && !book.pages.isEmpty() && docH < oy + H / z) {
            Notebook.Page lastP = book.pages.get(book.pages.size() - 1);
            gl.setLook(lastP.spacing, lastP.strength, lastP.lineColor, lastP.margins);
            gl.page(0, docH, PW, Notebook.PH, 0, docH, lastP.pdf >= 0 ? Notebook.BG_LINED : lastP.bg, true, true);
        }
        gl.drawSegs(gl.hl, 0, gl.hl.count, 1);
        gl.drawSegs(gl.pen, 0, gl.pen.count, 0);
        // tape goes over the ink it hides
        for (int i = first; i <= last; i++)
            for (Notebook.Item it : book.pages.get(i).items)
                if (it.type == Notebook.Item.TAPE && !(i == selPage && selItems.contains(it))) drawItem(it, tops[i]);
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
        StaticLayout sl = Render.textLayout(it.text, it.size, Render.inkColor(it.color, dark), it.w);
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
            worker.execute(() -> {
                Bitmap b = null;
                try {
                    b = make.call();
                } catch (Exception e) {
                    Log.e(Gl.TAG, "texture " + key, e);
                }
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
        if (needRebuild) rebuild();
        gl.setView(ox, oy, z);
        gl.noScissor();
        gl.bindCache();
        drawScene();
        needCache = false;
        present();
    }

    private void present() {
        needPresent = false;
        gl.bindWindow();
        gl.noScissor();
        gl.blit();
        if (overlay) gl.drawSegs(gl.pred, 0, gl.pred.count, predMode);
        if (front) {
            gl.flush();
        } else {
            if (wantFront) gl.requestFront(true);
            gl.swap();
            boolean f = gl.isFront();
            if (!f && wantFront && gl.mutable) {
                gl.blit();
                if (overlay) gl.drawSegs(gl.pred, 0, gl.pred.count, predMode);
                gl.swap();
                f = gl.isFront();
                if (!f) Log.w(Gl.TAG, "front buffer request not honoured after 2 swaps");
            }
            if (f) {
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
            Log.i(Gl.TAG, "front buffer " + (f ? "ON" : "off") + " (" + why + ")");
            frontWhy = why;
        }
        front = f;
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

    private void rebuild() {
        needRebuild = false;
        gl.pen.clear();
        gl.hl.clear();
        gl.selPen.clear();
        gl.selHl.clear();
        IdentityHashMap<Stroke, Boolean> sel = new IdentityHashMap<>();
        for (Stroke s : selStrokes) sel.put(s, Boolean.TRUE);
        for (int i = 0; i < book.pages.size(); i++) {
            float top = tops[i];
            for (Stroke s : book.pages.get(i).strokes) {
                if (gest == G_ERASE && (doomed.contains(s) || pieces.containsKey(s))) {
                    boolean dk = book.paper == Notebook.PAPER_DARK;
                    int grey = s.hl() ? (dk ? 0xff3a3d44 : 0xffe6e8ec) : (dk ? 0xff5a5f69 : 0xffc6cad2);
                    s.withColor(grey).emitAll(top, s.hl() ? gl.hl : gl.pen); // greyed: goes when the pen lifts
                    ArrayList<Stroke> ps = pieces.get(s);
                    if (ps != null) for (Stroke q : ps) q.emitAll(top, q.hl() ? gl.hl : gl.pen);
                    continue;
                }
                if (i == selPage && sel.containsKey(s)) s.emitAll(0, s.hl() ? gl.selHl : gl.selPen);
                else s.emitAll(top, s.hl() ? gl.hl : gl.pen);
            }
        }
        if (live != null) {
            SegBuf b = buf();
            liveFirst = b.count;
            if (live.n > 0) live.emitDot(liveTop, b);
            for (int k = 0; k + 2 < live.n; k++) live.emitInterval(k, live.n - 1, liveTop, b);
        }
        needCache = true;
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
            if (editing != null || tool == TOOL_TEXT) main.post(listener::onDismissText);
            // touching the current selection moves/scales it (pen or finger)
            if (hasSel() && (tool == TOOL_LASSO || shapeMode()) && selHit(ev.getX(), ev.getY())) {
                stopFling();
                gest = G_SEL;
                selBegin(ev);
                return;
            }
            if (pen) {
                stopFling();
                gesturing = false;
                if (hasSel() && tool != TOOL_LASSO) clearSelection(); // writing next to a shape just carries on
                if (!front) renderNow(); // back into front-buffer mode before drawing
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
        stFront = stSwap = 0;
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
        addSample(ev.getX(), ev.getY(), fingerGest ? FINGER_P : ev.getPressure());
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
        if (shapeSnap && !snapped) h.postDelayed(snapR, 480);
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
        for (int i = 0; i < hs; i++) addSample(ev.getHistoricalX(i), ev.getHistoricalY(i), fingerGest ? FINGER_P : ev.getHistoricalPressure(i));
        addSample(ev.getX(), ev.getY(), fingerGest ? FINGER_P : ev.getPressure());
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
        if (rulerEdge != 0 && live.n > 0) {
            final float len = (float) Math.hypot(live.x(live.n - 1) - live.x(0), live.y(live.n - 1) - live.y(0));
            main.post(() -> listener.onRulerLength(len, true));
        }
        rulerEdge = 0;
        penNear = SystemClock.uptimeMillis();
        long dur = Math.max(1, ev.getEventTime() - stT0);
        Log.i(Gl.TAG, String.format(Locale.US,
                "stroke samples=%d kept=%d dur=%dms rate=%.0fHz step=%.1fpx pressure min=%.3f avg=%.3f max=%.3f zoom=%.0f%% drawn front=%d swap=%d%s",
                stN, live.n, dur, stN * 1000f / dur, stN > 1 ? stStep / (stN - 1) : 0, stPmin, stPsum / Math.max(1, stN), stPmax,
                z / fitZ * 100, stFront, stSwap, snapped ? " (shape)" : ""));
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
        Object[] before = pageState(p);
        p.strokes.add(live);
        for (Notebook.Item it : p.items) // ink written over covering tape goes under it
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
        docChanged();
    }

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
        }
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
                    for (Stroke st : pg.strokes) {
                        if (doomed.contains(st)) continue;
                        ArrayList<Stroke> ps = pieces.get(st);
                        if (ps != null) keep.addAll(ps); else keep.add(st);
                    }
                    pg.strokes = keep;
                    pg.items.removeIf(doomedItems::contains);
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
            penNear = SystemClock.uptimeMillis();
        }
    }

    private void eraseAlong(float sx0, float sy0, float sx1, float sy1) {
        float x0 = ox + sx0 / z, y0 = oy + sy0 / z, x1 = ox + sx1 / z, y1 = oy + sy1 / z;
        float er = dp(eraserDp) / z;
        int p0 = pageAt(Math.min(y0, y1) - er), p1 = pageAt(Math.max(y0, y1) + er);
        boolean any = false;
        for (int pi = p0; pi <= p1; pi++) {
            float top = tops[pi];
            Notebook.Page page = book.pages.get(pi);
            ArrayList<Stroke> list = page.strokes;
            for (int i = list.size() - 1; i >= 0; i--) {
                Stroke s = list.get(i);
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
            for (int i = page.items.size() - 1; i >= 0; i--) {
                Notebook.Item it = page.items.get(i);
                if (it.type != Notebook.Item.TAPE) continue;
                float len = (float) Math.hypot(x1 - x0, y1 - y0);
                int steps = Math.max(1, (int) Math.ceil(len / Math.max(1f, er / 2)));
                for (int k = 0; k <= steps; k++) {
                    float t = k / (float) steps;
                    if (it.contains(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t - top, er * 0.6f)) {
                        if (erasePreview && doomedItems.contains(it)) break;
                        if (!eraseBefore.containsKey(page)) eraseBefore.put(page, pageState(page));
                        if (erasePreview) doomedItems.add(it); else page.items.remove(i);
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

    // lasso + selection ---------------------------------------------------------------------

    private boolean hasSel() { return selPage >= 0 && (!selStrokes.isEmpty() || !selItems.isEmpty()); }

    private void lassoBegin(MotionEvent ev) {
        ln = 0;
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

    private void lassoEvent(MotionEvent ev, int a) {
        if (a == MotionEvent.ACTION_MOVE || a == MotionEvent.ACTION_UP) {
            int hs = ev.getHistorySize();
            for (int i = 0; i < hs; i++) lassoAdd(ev.getHistoricalX(i), ev.getHistoricalY(i));
            lassoAdd(ev.getX(), ev.getY());
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
            if (ln >= 3) {
                for (Stroke s : p.strokes) if (s.insideFraction(xs, ys, ln) >= 0.5f) ss.add(s);
                for (Notebook.Item it : p.items) if (Geo.inside(it.x + it.w / 2, it.y + it.h / 2, xs, ys, ln)) si.add(it);
            }
            // a tap (or tiny lasso) selects what's under it: a picture, text box or tape, else a stroke (a shape gets its handles)
            if (ss.isEmpty() && si.isEmpty()) {
                float cx = xs[0], cy = ys[0];
                for (int i = p.items.size() - 1; i >= 0; i--) {
                    Notebook.Item it = p.items.get(i);
                    if (it.contains(cx, cy, 0)) {
                        si.add(it);
                        break;
                    }
                }
                if (si.isEmpty()) {
                    float reach = dp(10) / z;
                    for (int i = p.strokes.size() - 1; i >= 0; i--) {
                        Stroke s = p.strokes.get(i);
                        if (s.hit(cx, cy, cx, cy, reach)) { ss.add(s); break; }
                    }
                }
            }
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
        if (selPage < 0) return;
        selPage = -1;
        selStrokes = new ArrayList<>();
        selItems = new ArrayList<>();
        needRebuild = true;
        wantFront = true;
        requestFrame();
        main.post(() -> { listener.onSelection(null); listener.onHandles(null, false); });
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
        main.post(() -> { listener.onSelection(r); listener.onHandles(hs, line); });
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

    /** The same handles in screen px, following a selection that is being moved. */
    private float[] handlesScreen() {
        float[] h = handlesLocal();
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
        if (o.shape == Stroke.LINE) {
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
        ns.shape = o.shape;
        for (int i = 0; i + 1 < pts.length; i += 2) ns.add(pts[i], pts[i + 1], pr);
        book.pages.get(selPage).strokes.set(shapeIdx, ns);
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
        if (shapeHandle >= 0) {
            shapeOrig = selStrokes.get(0);
            Notebook.Page p = book.pages.get(selPage);
            shapeBefore = pageState(p);
            shapeIdx = p.strokes.indexOf(shapeOrig);
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
                Stroke now = p.strokes.get(shapeIdx);
                if (a == MotionEvent.ACTION_UP && now != shapeOrig) {
                    push(new PageEdit(p, shapeBefore, pageState(p)));
                    edited();
                } else if (now != shapeOrig) { // cancelled: put it back
                    p.strokes.set(shapeIdx, shapeOrig);
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
        for (int i = 0; i < p.strokes.size(); i++) if (old.containsKey(p.strokes.get(i))) { si = i; break; }
        p.strokes.removeIf(old::containsKey);
        p.items.removeIf(old::containsKey);
        p.strokes.addAll(si < 0 ? p.strokes.size() : Math.min(si, p.strokes.size()), ns);
        p.items.addAll(ni);
        push(new PageEdit(p, before, pageState(p)));
        int pg = selPage;
        if (keepSelected && (!ns.isEmpty() || !ni.isEmpty())) select(pg, ns, ni);
        else clearSelection();
        edited();
    }

    // text --------------------------------------------------------------------------------

    private void textTap(float sx, float sy) {
        float x = ox + sx / z, y = oy + sy / z;
        int pg = pageAt(y);
        Notebook.Page p = book.pages.get(pg);
        float py = y - tops[pg];
        TextEdit e = new TextEdit();
        e.page = pg;
        for (int i = p.items.size() - 1; i >= 0; i--) {
            Notebook.Item it = p.items.get(i);
            if (it.type == Notebook.Item.TEXT && x >= it.x - 6 && x <= it.x + it.w + 6 && py >= it.y - 6 && py <= it.y + it.h + 6) {
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
            editing = e.item;
            needCache = true;
            requestFrame();
        } else {
            e.docX = canvas() ? x : Math.max(0, Math.min(PW - 60, x));
            e.docY = canvas() ? py - textSize * 0.6f : Math.max(0, py - textSize * 0.6f);
            e.width = canvas() ? 640 : Math.max(120, Math.min(640, PW - e.docX - 30));
            e.size = textSize;
            e.color = penColor;
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
                    gesturing = true;
                    main.post(listener::onDismissText);
                    baseline(ev, -1);
                    break;
                }
                float nz = sZ;
                if (ev.getPointerCount() >= 2 && sDist > 0 && m[2] > 0) nz = sZ * m[2] / sDist;
                nz = Math.max(minZ(), Math.min(fitZ * 8f, nz));
                float ax = sOx + sMidX / sZ, ay = sOy + sMidY / sZ;
                z = nz;
                ox = ax - m[0] / nz;
                oy = ay - m[1] / nz;
                clampView();
                requestFrame();
                break;
            }
            case MotionEvent.ACTION_UP: {
                if (!moved && ev.getEventTime() - downT < 300) {
                    if (maxPtr == 2) doUndo(true);
                    else if (maxPtr == 3) doRedo(true);
                    else if (maxPtr == 1 && toggleTapeAt(ox + ev.getX() / z, oy + ev.getY() / z, false)) {
                        lastTapT = 0; // a tap on tape peels it; it doesn't start a double-tap
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
        return new Object[]{new ArrayList<>(p.strokes), new ArrayList<>(p.items)};
    }

    /** Before/after snapshot of one page's strokes and items. */
    private static final class PageEdit extends Act {
        final Notebook.Page p;
        final Object[] before, after;
        PageEdit(Notebook.Page p, Object[] before, Object[] after) { this.p = p; this.before = before; this.after = after; }
        @SuppressWarnings("unchecked")
        private void set(Object[] s) {
            p.strokes = new ArrayList<>((ArrayList<Stroke>) s[0]);
            p.items = new ArrayList<>((ArrayList<Notebook.Item>) s[1]);
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

    private static final class SetBg extends Act {
        final Notebook.Page p; final int from, to;
        SetBg(Notebook.Page p, int from, int to) { this.p = p; this.from = from; this.to = to; }
        void undo(Notebook b) { p.bg = from; }
        void redo(Notebook b) { p.bg = to; }
    }

    private void push(Act a) {
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
        if (last.pdf >= 0 || (last.strokes.isEmpty() && last.items.isEmpty())) return false;
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
        final Notebook snap = book.snapshot();
        io.execute(() -> {
            try {
                snap.write(dir);
            } catch (Exception e) {
                Log.e(Gl.TAG, "save failed", e);
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
        s.z = z;
        s.paper = book.paper;
        s.canvas = book.canvas;
        s.title = book.title;
        s.hasSelection = hasSel();
        s.hasClipboard = !clipStrokes.isEmpty() || !clipItems.isEmpty();
        main.post(() -> listener.onState(s));
        if (!hasSel()) main.post(() -> listener.onSelection(null));
    }
}

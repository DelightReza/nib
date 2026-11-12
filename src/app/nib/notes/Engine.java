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
    static final int TOOL_PEN = 0, TOOL_HL = 1, TOOL_ERASER = 2, TOOL_LASSO = 3, TOOL_TEXT = 4, TOOL_LASER = 5;
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

    // gestures
    private static final int G_NONE = 0, G_INK = 1, G_ERASE = 2, G_PAN = 3, G_IGNORE = 4, G_LASSO = 5, G_SEL = 6, G_TAP = 7, G_LASER = 8;
    private int gest = G_NONE;
    private volatile long penNear;

    // live stroke
    private Stroke live;
    private int livePage, liveFirst, newFirst;
    private float liveTop, pf;
    private boolean snapped;
    private float holdX, holdY;
    private final RectF dirty = new RectF(), predCur = new RectF(), predPrev = new RectF(), tmp = new RectF();

    // erase
    private IdentityHashMap<Notebook.Page, Object[]> eraseBefore;
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
                tool = t;
            }
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

    /** Page operation; i == -2 means the page currently in view. */
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
                case "duplicate":
                    book.pages.add(i + 1, book.pages.get(i).copyShallow());
                    break;
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
            for (Stroke s : selStrokes) ns.add(s.tool == Stroke.HL ? s : s.withColor(color));
            for (Notebook.Item it : selItems) ni.add(it.type == Notebook.Item.TEXT ? it.withColor(color) : it);
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

    void hover() { penNear = SystemClock.uptimeMillis(); }

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
        boolean laser = laserActive();
        if (gesturing || flinging || dragging() || laser) {
            if (front) gl.requestFront(false);
            wantFront = false;
            gl.setView(ox, oy, z);
            gl.bindWindow();
            gl.noScissor();
            drawScene();
            if (laser) drawLaser();
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

    private static final long LASER_TRAIL = 520, LASER_TIP = 350;
    private final ArrayList<float[]> laserPts = new ArrayList<>(); // doc x, doc y, time (ms)
    private boolean laserDown;
    private long laserUpAt;

    private void laserAdd(MotionEvent ev) {
        int hs = ev.getHistorySize();
        for (int i = 0; i < hs; i++)
            laserPts.add(new float[]{ox + ev.getHistoricalX(i) / z, oy + ev.getHistoricalY(i) / z, ev.getHistoricalEventTime(i)});
        laserPts.add(new float[]{ox + ev.getX() / z, oy + ev.getY() / z, ev.getEventTime()});
        requestFrame();
    }

    private boolean laserActive() {
        return laserDown || !laserPts.isEmpty() || SystemClock.uptimeMillis() - laserUpAt < LASER_TIP;
    }

    private void drawLaser() {
        long now = SystemClock.uptimeMillis();
        while (laserPts.size() > 1 && now - laserPts.get(0)[2] > LASER_TRAIL) laserPts.remove(0);
        if (!laserDown && laserPts.size() == 1 && now - laserPts.get(0)[2] > LASER_TRAIL) laserPts.clear();
        float tipA = laserDown ? 1f : Math.max(0f, 1f - (now - laserUpAt) / (float) LASER_TIP);
        SegBuf b = gl.laser;
        b.clear();
        int n = laserPts.size();
        float px = 1f / z;
        // three passes: soft glow, red core, hot white centre
        float[][] pass = {{dp(9), 0.22f}, {dp(2.6f), 1f}, {dp(1.1f), 0.9f}};
        int[] col = {0xff3b30, 0xff3b30, 0xffffff};
        for (int k = 0; k < 3; k++) {
            float r = pass[k][0] * px, a0 = pass[k][1];
            for (int i = 1; i < n; i++) {
                float[] p0 = laserPts.get(i - 1), p1 = laserPts.get(i);
                float a = a0 * Math.max(0f, 1f - (now - p1[2]) / (float) LASER_TRAIL);
                if (a <= 0.01f) continue;
                b.put(p0[0], p0[1], p1[0], p1[1], r * 0.8f, r, ((int) (a * 255) << 24) | col[k]);
            }
            if (n > 0 && tipA > 0) {
                float[] t = laserPts.get(n - 1);
                float tr = r * (k == 0 ? 1.6f : 1.5f);
                b.put(t[0], t[1], t[0], t[1], tr, tr, ((int) (a0 * tipA * 255) << 24) | col[k]);
            }
        }
        gl.drawSegs(b, 0, b.count, 0);
        if (laserActive()) requestFrame();
        else { wantFront = true; needPresent = true; requestFrame(); } // back to front-buffer inking
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
                if (it == editing || (i == selPage && selItems.contains(it))) continue;
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
        if (hasSel()) {
            // selection buffers are page-local; the transform adds the page top
            gl.setXform(spx, spy, ss, stx, sty + tops[selPage]);
            for (Notebook.Item it : selItems) drawItem(it, 0);
            gl.drawSegs(gl.selHl, 0, gl.selHl.count, 1);
            gl.drawSegs(gl.selPen, 0, gl.selPen.count, 0);
            gl.resetXform();
        }
    }

    private void drawItem(Notebook.Item it, float top) {
        Tex t;
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
                if (i == selPage && sel.containsKey(s)) s.emitAll(0, s.tool == Stroke.HL ? gl.selHl : gl.selPen);
                else s.emitAll(top, s.tool == Stroke.HL ? gl.hl : gl.pen);
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

    private void handle(MotionEvent ev, long recv) {
        int a = ev.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            int tt = ev.getToolType(0);
            boolean pen = isStylus(tt) || (fingerDraw && tt == MotionEvent.TOOL_TYPE_FINGER);
            if (editing != null || tool == TOOL_TEXT) main.post(listener::onDismissText);
            // touching the current selection moves/scales it (pen or finger)
            if (hasSel() && tool == TOOL_LASSO && selHit(ev.getX(), ev.getY())) {
                stopFling();
                gest = G_SEL;
                selBegin(ev);
                return;
            }
            if (pen) {
                stopFling();
                gesturing = false;
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
                    laserAdd(ev);
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
                if (a == MotionEvent.ACTION_POINTER_DOWN && fingerDraw) {
                    inkCancel();
                    gest = G_PAN;
                    panBegin(ev);
                    panEvent(ev, a);
                } else inkEvent(ev, a, recv);
                break;
            case G_ERASE:
                eraseEvent(ev, a);
                break;
            case G_LASER:
                laserAdd(ev);
                if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    laserDown = false;
                    laserUpAt = SystemClock.uptimeMillis();
                    gest = G_NONE;
                }
                break;
            case G_LASSO:
                lassoEvent(ev, a);
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

    private SegBuf buf() { return live.tool == Stroke.HL ? gl.hl : gl.pen; }

    private void inkBegin(MotionEvent ev, long recv) {
        boolean hlTool = tool == TOOL_HL;
        live = new Stroke(hlTool ? Stroke.HL : penType, hlTool ? hlColor : penColor, hlTool ? hlWidth : penWidth);
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
        holdX = ev.getX();
        holdY = ev.getY();
        rulerPick(ev.getX(), ev.getY());
        addSample(ev.getX(), ev.getY(), ev.getPressure());
        if (rulerEdge == 0) armSnap();
        predict(ev);
        inkRender(ev, recv);
    }

    private final Runnable snapR = this::trySnap;

    private void armSnap() {
        h.removeCallbacks(snapR);
        if (shapeSnap && !snapped) h.postDelayed(snapR, 480);
    }

    /** Pen held still mid-stroke: replace the stroke with a clean shape if it looks like one. */
    private void trySnap() {
        if (gest != G_INK || live == null || snapped) return;
        float[] pts = Shapes.recognize(live);
        if (pts == null) return;
        float avgP = 0;
        for (int i = 0; i < live.n; i++) avgP += live.p[4 * i + 2];
        avgP /= live.n;
        Stroke s = new Stroke(live.tool, live.color, live.width);
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

    private void inkEvent(MotionEvent ev, int a, long recv) {
        if (live == null) return;
        if (a == MotionEvent.ACTION_CANCEL || (ev.getFlags() & MotionEvent.FLAG_CANCELED) != 0) {
            inkCancel();
            return;
        }
        if (a != MotionEvent.ACTION_MOVE && a != MotionEvent.ACTION_UP) return;
        if (snapped) {
            if (a == MotionEvent.ACTION_UP) inkCommit(ev);
            return;
        }
        if (predictor != null) predictor.record(ev);
        newFirst = buf().count;
        dirty.setEmpty();
        int hs = ev.getHistorySize();
        for (int i = 0; i < hs; i++) addSample(ev.getHistoricalX(i), ev.getHistoricalY(i), ev.getHistoricalPressure(i));
        addSample(ev.getX(), ev.getY(), ev.getPressure());
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
        Object[] before = pageState(p);
        p.strokes.add(live);
        push(new PageEdit(p, before, pageState(p)));
        if (livePage == book.pages.size() - 1 && ensureTrailingPage()) {
            needCache = true;
            requestFrame();
            notifyState();
        }
        live = null;
        overlay = false;
        gest = G_NONE;
        if (snapped) {
            needCache = true;
            requestFrame();
        }
        docChanged();
    }

    private void inkCancel() {
        h.removeCallbacks(snapR);
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

    private void eraseBegin(MotionEvent ev) {
        eraseBefore = new IdentityHashMap<>();
        ex = ev.getX();
        ey = ev.getY();
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
            // a tap (or tiny lasso) on a picture/text box selects it
            if (ss.isEmpty() && si.isEmpty()) {
                float cx = xs[0], cy = ys[0];
                for (int i = p.items.size() - 1; i >= 0; i--) {
                    Notebook.Item it = p.items.get(i);
                    if (cx >= it.x && cx <= it.x + it.w && cy >= it.y && cy <= it.y + it.h) {
                        si.add(it);
                        break;
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
        main.post(() -> listener.onSelection(null));
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
        main.post(() -> listener.onSelection(r));
    }

    private boolean selHit(float sx, float sy) {
        RectF r = selScreen();
        float pad = dp(18);
        selScale = Math.hypot(sx - r.right, sy - r.bottom) < dp(30);
        return selScale || (sx > r.left - pad && sx < r.right + pad && sy > r.top - pad && sy < r.bottom + pad);
    }

    private void selBegin(MotionEvent ev) {
        sDownX = ev.getX();
        sDownY = ev.getY();
        spx = selScale ? selBounds.left : 0;
        spy = selScale ? selBounds.top : 0;
        ss = 1;
        stx = sty = 0;
    }

    private void selEvent(MotionEvent ev, int a) {
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
                    else if (maxPtr == 1) {
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

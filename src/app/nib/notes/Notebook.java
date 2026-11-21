package app.nib.notes;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/**
 * A notebook: a vertical stack of pages, all PW units wide (heights vary for imported PDFs).
 * Stored as dir/{ink.bin, meta.properties, thumb.png, source.pdf?, images/*}.
 * Strokes and items are immutable once committed; edits replace them (cheap undo snapshots).
 */
final class Notebook {
    static final float PW = 1000f, PH = 1414f;
    static final int BG_BLANK = 0, BG_LINED = 1, BG_GRID = 2, BG_DOTS = 3, BG_NARROW = 4, BG_CORNELL = 5, BG_MUSIC = 6,
            BG_ENGINEERING = 7, BG_ISO = 8, BG_SITE = 9, BG_GRAPH = 10, BG_FINE_DOTS = 11, BG_HEX = 12, BG_DAILY = 13, BG_WEEKLY = 14;
    static final String[] BG_NAMES = {"Blank", "Lined", "Grid", "Dotted", "Narrow lined", "Cornell notes", "Music staff",
            "Engineering", "Isometric", "Site inspection", "Graph 5 mm", "Fine dots", "Hexagon", "Daily planner", "Weekly planner"};
    static final int PAPER_WHITE = 0, PAPER_CREAM = 1, PAPER_DARK = 2;
    static final String[] PAPER_NAMES = {"White paper", "Cream paper", "Dark paper"};
    private static final int MAGIC1 = 0x4E494231, MAGIC2 = 0x4E494232, MAGIC3 = 0x4E494233, MAGIC4 = 0x4E494234, MAGIC5 = 0x4E494235, MAGIC6 = 0x4E494236, MAGIC7 = 0x4E494237;
    // NIB8 (Nib 2.3): ink.bin is only an index; each page's ink and items are in pages/<id>.bin ("NIBP")
    private static final int MAGIC8 = 0x4E494238, MAGIC_PAGE = 0x4E494250;
    // NIB3 +bookmarks, NIB4 +page style, NIB5 +tape paths, NIB6 +text style, NIB7 = NIB6 with int-length strings.
    // NIB7 is written only when a string is too long for writeUTF (64 KB), so other notebooks stay readable by older Nib.
    static final int MARGIN_LEFT = 1, MARGIN_HEADER = 2, MARGIN_BORDER = 4;

    /** Text box or picture placed on a page (page-local units). */
    static final class Item {
        static final int TEXT = 0, IMAGE = 1, TAPE = 2;
        static final String[] TAPE_PATTERNS = {"plain", "stripes", "grid", "dots"};
        final int type;
        final float x, y, w, h;
        final String text;   // TEXT: the text; IMAGE: file name under images/
        final float size;    // TEXT: font size in units
        final int color;     // TEXT / TAPE colour
        final float[] pts;   // TAPE: the centre line x0,y0,x1,y1,… (page-local); size = its thickness
        final String style;  // TEXT: font, bold/italic/underline/strike, alignment (Fonts.Style); null = default

        Item(int type, float x, float y, float w, float h, String text, float size, int color) {
            this(type, x, y, w, h, text, size, color, null);
        }

        Item(int type, float x, float y, float w, float h, String text, float size, int color, float[] pts) {
            this(type, x, y, w, h, text, size, color, pts, null);
        }

        Item(int type, float x, float y, float w, float h, String text, float size, int color, float[] pts, String style) {
            this.type = type; this.x = x; this.y = y; this.w = w; this.h = h;
            this.text = text; this.size = size; this.color = color; this.pts = pts;
            this.style = style == null || style.isEmpty() ? null : style;
        }

        Fonts.Style textStyle() { return Fonts.Style.parse(style); }

        /** A tape along centre line pts, thick units wide; its box covers the whole strip. */
        static Item tape(float[] pts, float thick, String pattern, int color) {
            float l = Float.MAX_VALUE, t = Float.MAX_VALUE, r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
            for (int i = 0; i + 1 < pts.length; i += 2) {
                l = Math.min(l, pts[i]); r = Math.max(r, pts[i]);
                t = Math.min(t, pts[i + 1]); b = Math.max(b, pts[i + 1]);
            }
            float m = thick / 2 + 1;
            return new Item(TAPE, l - m, t - m, r - l + 2 * m, b - t + 2 * m, pattern, thick, color, pts);
        }

        /**
         * TEXT converted from handwriting: shown in the handwriting face, and it keeps the original ink so it can be read
         * again in another language or turned back into handwriting. Stored in pts (no file format change):
         * {1, strokeCount, per stroke: n, tool, colourHi, colourLo, width, shape, then x, y, pressure × n}.
         */
        boolean hand() { return type == TEXT && pts != null && pts.length >= 1 && pts[0] == 1f; }

        boolean hasInk() { return hand() && pts.length > 2 && pts[1] > 0; }

        static final float[] HAND = {1f};

        static float[] handWithInk(java.util.List<Stroke> ink) {
            int len = 2;
            for (Stroke s : ink) len += 6 + 3 * s.n;
            float[] o = new float[len];
            o[0] = 1f;
            o[1] = ink.size();
            int j = 2;
            for (Stroke s : ink) {
                o[j++] = s.n; o[j++] = s.tool;
                o[j++] = (s.color >>> 16) & 0xffff; o[j++] = s.color & 0xffff; // two halves: a float can't hold every int
                o[j++] = s.width; o[j++] = s.shape;
                for (int k = 0; k < s.n; k++) { o[j++] = s.x(k); o[j++] = s.y(k); o[j++] = s.p[4 * k + 2]; }
            }
            return o;
        }

        /** The handwriting this text was converted from (where it was written), or an empty list. */
        java.util.List<Stroke> ink() {
            java.util.ArrayList<Stroke> out = new java.util.ArrayList<>();
            if (!hasInk()) return out;
            try {
                int j = 2;
                for (int i = 0; i < (int) pts[1]; i++) {
                    int n = (int) pts[j++], tool = (int) pts[j++];
                    int color = ((int) pts[j++] << 16) | (int) pts[j++];
                    Stroke s = new Stroke(tool, color, pts[j++]);
                    s.shape = (int) pts[j++];
                    for (int k = 0; k < n; k++) { s.add(pts[j], pts[j + 1], pts[j + 2]); j += 3; }
                    out.add(s);
                }
            } catch (ArrayIndexOutOfBoundsException e) { out.clear(); }
            return out;
        }

        Item moved(float px, float py, float s, float tx, float ty) {
            float[] np = null;
            if (type == TEXT) np = pts; // a style flag, not points
            else if (pts != null) {
                np = new float[pts.length];
                for (int i = 0; i + 1 < pts.length; i += 2) { np[i] = (pts[i] - px) * s + px + tx; np[i + 1] = (pts[i + 1] - py) * s + py + ty; }
            }
            return new Item(type, (x - px) * s + px + tx, (y - py) * s + py + ty, w * s, h * s, text, size * s, color, np, Fonts.scaled(style, s));
        }

        Item withText(String t, float nh) { return new Item(type, x, y, w, nh, t, size, color, pts, style); }

        Item withStyle(String st) { return new Item(type, x, y, w, h, text, size, color, pts, st); }

        Item withColor(int c) { return new Item(type, x, y, w, h, text, size, c, pts, style); }

        /** TAPE: text holds the pattern, plus "|open" while the tape is peeled back to show what's under it. */
        boolean revealed() { return type == TAPE && text.endsWith("|open"); }

        String pattern() { return type == TAPE ? (revealed() ? text.substring(0, text.length() - 5) : text) : ""; }

        Item withRevealed(boolean r) { return new Item(type, x, y, w, h, pattern() + (r ? "|open" : ""), size, color, pts, style); }

        boolean contains(float px, float py, float pad) {
            if (px < x - pad || px > x + w + pad || py < y - pad || py > y + h + pad) return false;
            if (type != TAPE || pts == null) return true;
            float rr = size / 2 + pad; // on the strip: near its centre line
            if (pts.length == 2) return Math.hypot(px - pts[0], py - pts[1]) <= rr;
            for (int i = 0; i + 3 < pts.length; i += 2) {
                float ax = pts[i], ay = pts[i + 1], dx = pts[i + 2] - ax, dy = pts[i + 3] - ay, l2 = dx * dx + dy * dy;
                float h = l2 > 1e-6f ? Math.max(0f, Math.min(1f, ((px - ax) * dx + (py - ay) * dy) / l2)) : 0f;
                if (Math.hypot(px - ax - dx * h, py - ay - dy * h) <= rr) return true;
            }
            return false;
        }

        String key() { return type + ":" + System.identityHashCode(this); }
    }

    /**
     * One page. Its look (template, size, style, bookmark) lives in the notebook's index; its ink and items live in
     * their own file, pages/<id>.bin, and are read only when something asks for them, so a notebook of hundreds of
     * pages opens with just the index in memory, and pages far from the screen can be let go again (unload).
     * A page is written back only when its content changed: savedS / savedI hold the very objects its file holds.
     */
    static final class Page {
        int bg;
        float h = PH;
        int pdf = -1; // page index in source.pdf, or -1
        String mark;  // bookmark title ("" = untitled bookmark), null = not bookmarked
        // page style on top of the template: pattern spacing (×template default), line colour (0 = paper's own),
        // line strength (×, 1 = normal), margins (MARGIN_* bits)
        float spacing = 1f, strength = 1f;
        int lineColor, margins;

        int id;            // its file is pages/<id>.bin; 0 = not given one yet
        File src;          // that file, once it holds this page (null: the page exists only in memory so far)
        boolean broken;    // src couldn't be read: shown empty, and the file is kept aside before it's ever replaced
        private ArrayList<Stroke> strokes = new ArrayList<>(); // null = not read yet (see ensure)
        private ArrayList<Item> items = new ArrayList<>();
        private Object[] savedS, savedI; // what src holds; null = nothing written yet

        void copyStyle(Page o) { spacing = o.spacing; strength = o.strength; lineColor = o.lineColor; margins = o.margins; }

        boolean sameStyle(Page o) {
            return spacing == o.spacing && strength == o.strength && lineColor == o.lineColor && margins == o.margins;
        }

        Page(int bg) { this.bg = bg; }

        /** A page whose content is read from f when first needed. */
        static Page onDisk(int bg, int id, File f) {
            Page p = new Page(bg);
            p.id = id;
            p.src = f;
            p.strokes = null;
            p.items = null;
            return p;
        }

        ArrayList<Stroke> strokes() { ensure(); return strokes; }

        ArrayList<Item> items() { ensure(); return items; }

        void setStrokes(ArrayList<Stroke> s) { ensure(); strokes = s; }

        void setItems(ArrayList<Item> i) { ensure(); items = i; }

        boolean loaded() { return strokes != null; }

        private void ensure() {
            if (strokes != null) return;
            ArrayList<Stroke> st = new ArrayList<>();
            ArrayList<Item> it = new ArrayList<>();
            try {
                readBody(src, st, it);
            } catch (IOException | RuntimeException e) {
                android.util.Log.e(Gl.TAG, "page " + id + " unreadable", e);
                st.clear();
                it.clear();
                broken = true;
            }
            install(st, it);
        }

        /** Content read elsewhere (a background thread) for a page that's still unread. */
        void install(ArrayList<Stroke> st, ArrayList<Item> it) {
            strokes = st;
            items = it;
            savedS = st.toArray();
            savedI = it.toArray();
        }

        /** Changed since its file was written (or never written). An unread page hasn't changed. */
        boolean dirty() {
            if (strokes == null) return false;
            return savedS == null || !same(savedS, strokes) || !same(savedI, items);
        }

        private static boolean same(Object[] a, ArrayList<?> b) {
            if (a.length != b.size()) return false;
            for (int i = 0; i < a.length; i++) if (a[i] != b.get(i)) return false;
            return true;
        }

        /** Gives back the memory of a page whose file is up to date (it's read again when needed). */
        boolean unload() {
            if (strokes == null || src == null || broken || dirty()) return false;
            strokes = null;
            items = null;
            savedS = savedI = null;
            return true;
        }

        /** Its file now holds exactly these objects (called after a save). */
        void markSaved(Object[] s, Object[] i, File f) {
            if (strokes == null) return;
            savedS = s;
            savedI = i;
            src = f;
            broken = false;
        }

        /** A new page with the same look and content (a duplicate): it gets its own file when saved. */
        Page copyShallow() {
            Page p = new Page(bg);
            p.h = h;
            p.pdf = pdf;
            p.mark = mark;
            p.copyStyle(this);
            p.strokes = new ArrayList<>(strokes());
            p.items = new ArrayList<>(items());
            return p;
        }

        /** The same page for another thread (a save, an export): unread pages stay unread and read their file there. */
        Page snapCopy() {
            Page p = new Page(bg);
            p.h = h;
            p.pdf = pdf;
            p.mark = mark;
            p.copyStyle(this);
            p.id = id;
            p.src = src;
            p.broken = broken;
            p.origin = this;
            if (strokes == null) { p.strokes = null; p.items = null; }
            else {
                p.strokes = new ArrayList<>(strokes);
                p.items = new ArrayList<>(items);
                p.savedS = savedS;
                p.savedI = savedI;
            }
            return p;
        }

        Page origin; // snapCopy: the page it was copied from
    }

    ArrayList<Page> pages = new ArrayList<>();
    String title = "Untitled", folder = "";
    String cover; // Covers value ("design:colour"), null = none; kept in meta.properties, written by setCover only
    boolean favorite;
    boolean canvas; // infinite canvas: one unbounded page you can pan in every direction
    boolean loadFailed; // ink.bin couldn't be read in full (a copy of it was kept)
    boolean legacy;     // read from a single-file notebook (NIB1–7): the first save converts it
    int nextId = 1;     // next page file id
    File dir;           // where it was read from (null for one made in memory)
    private String thumbKey;
    boolean thumbWanted = true;
    /** Pages whose files the last write() wrote (snapshot copies; each knows its origin). */
    final ArrayList<Page> written = new ArrayList<>();
    int paper = PAPER_WHITE;
    long created, modified;

    // ---- io ------------------------------------------------------------------------------

    static Notebook load(File dir) {
        Notebook nb = new Notebook();
        nb.dir = dir;
        Properties m = meta(dir);
        nb.title = m.getProperty("title", "Untitled");
        nb.folder = m.getProperty("folder", "");
        nb.cover = m.getProperty("cover");
        nb.favorite = Boolean.parseBoolean(m.getProperty("favorite", "false"));
        nb.canvas = Boolean.parseBoolean(m.getProperty("canvas", "false"));
        nb.paper = Integer.parseInt(m.getProperty("paper", "0"));
        nb.created = Long.parseLong(m.getProperty("created", "0"));
        nb.modified = Long.parseLong(m.getProperty("modified", "0"));
        File f = new File(dir, "ink.bin");
        if (!f.exists() && new File(dir, "ink.bin.old").exists()) f = new File(dir, "ink.bin.old"); // a conversion cut short
        if (f.exists()) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 16))) {
                int magic = in.readInt();
                if (magic == MAGIC8) readIndex(nb, dir, in);
                else readWhole(nb, in, magic);
            } catch (IOException | RuntimeException e) {
                // Keep the unreadable file: the next save would otherwise replace it with only the pages read so far.
                android.util.Log.e(Gl.TAG, "load failed", e);
                File keep = new File(dir, "ink.bin.unreadable-" + System.currentTimeMillis());
                try { Io.copy(new FileInputStream(f), new FileOutputStream(keep)); } catch (IOException ignored) { }
                nb.loadFailed = true;
            }
        }
        if (nb.pages.isEmpty()) nb.pages.add(new Page(BG_DOTS));
        for (Page p : nb.pages) nb.nextId = Math.max(nb.nextId, p.id + 1);
        return nb;
    }

    /** NIB8: the index. Each page's look plus the id of its file; the ink stays on disk until it's needed. */
    private static void readIndex(Notebook nb, File dir, DataInputStream in) throws IOException {
        int version = in.readInt();
        if (version > 1) throw new IOException("index from a newer Nib (" + version + ")");
        nb.nextId = in.readInt();
        int pages = in.readInt();
        File pd = new File(dir, "pages");
        for (int i = 0; i < pages; i++) {
            int id = in.readInt();
            Page p = Page.onDisk(in.readInt(), id, new File(pd, id + ".bin"));
            p.h = in.readFloat();
            p.pdf = in.readInt();
            if (in.readBoolean()) p.mark = readStr(in, true);
            p.spacing = in.readFloat();
            p.lineColor = in.readInt();
            p.strength = in.readFloat();
            p.margins = in.readInt();
            nb.pages.add(p);
        }
    }

    /** NIB1–7: everything in one file. Read in full; the first save turns it into the index + page files. */
    private static void readWhole(Notebook nb, DataInputStream in, int magic) throws IOException {
        if (magic < MAGIC1 || magic > MAGIC7) throw new IOException("bad magic");
        nb.legacy = true;
        boolean v2 = magic >= MAGIC2, v3 = magic >= MAGIC3, v4 = magic >= MAGIC4, v5 = magic >= MAGIC5, v6 = magic >= MAGIC6;
        boolean v7 = magic >= MAGIC7;
        int pages = in.readInt();
        for (int i = 0; i < pages; i++) {
            Page p = new Page(in.readInt());
            if (v2) {
                p.h = in.readFloat();
                p.pdf = in.readInt();
            }
            readStrokes(in, p.strokes);
            if (v2) readItems(in, p.items, v5, v6, v7);
            if (v3 && in.readBoolean()) p.mark = readStr(in, v7);
            if (v4) {
                p.spacing = in.readFloat();
                p.lineColor = in.readInt();
                p.strength = in.readFloat();
                p.margins = in.readInt();
            }
            nb.pages.add(p);
        }
    }

    private static void readStrokes(DataInputStream in, ArrayList<Stroke> out) throws IOException {
        int ns = in.readInt();
        if (ns < 0 || ns > 10_000_000) throw new IOException("bad stroke count " + ns);
        out.ensureCapacity(ns);
        for (int s = 0; s < ns; s++) {
            int tw = in.readInt();
            int color = in.readInt();
            float width = in.readFloat();
            int n = in.readInt();
            if (n < 0 || n > 50_000_000) throw new IOException("bad point count " + n);
            Stroke st = new Stroke(tw & 0xffff, color, width, n); // exactly as big as it needs
            st.shape = (tw >>> 16) & 0xff;
            for (int k = 0; k < n; k++) st.add(in.readFloat(), in.readFloat(), in.readFloat());
            out.add(st);
        }
    }

    private static void readItems(DataInputStream in, ArrayList<Item> out, boolean v5, boolean v6, boolean v7) throws IOException {
        int ni = in.readInt();
        for (int k = 0; k < ni; k++) {
            int type = in.readInt();
            float x = in.readFloat(), y = in.readFloat(), w = in.readFloat(), h = in.readFloat();
            String text = readStr(in, v7);
            float size = in.readFloat();
            int color = in.readInt();
            float[] pts = null;
            if (v5) {
                int np = in.readInt();
                if (np > 0) { pts = new float[np]; for (int q = 0; q < np; q++) pts[q] = in.readFloat(); }
            }
            if (type == Item.TAPE && pts == null) { // first tape build: an axis-aligned box
                boolean hz = w >= h;
                pts = hz ? new float[]{x, y + h / 2, x + w, y + h / 2} : new float[]{x + w / 2, y, x + w / 2, y + h};
                size = hz ? h : w;
            }
            String style = v6 && in.readBoolean() ? readStr(in, v7) : null;
            out.add(new Item(type, x, y, w, h, text, size, color, pts, style));
        }
    }

    /** A page file: "NIBP", version, strokes, items (as in NIB7). */
    static void readBody(File f, ArrayList<Stroke> st, ArrayList<Item> it) throws IOException {
        if (f == null) throw new IOException("no page file");
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 16))) {
            if (in.readInt() != MAGIC_PAGE) throw new IOException("not a page file");
            if (in.readInt() > 1) throw new IOException("page from a newer Nib");
            readStrokes(in, st);
            readItems(in, it, true, true, true);
        }
    }

    private static void writeBody(File f, ArrayList<Stroke> st, ArrayList<Item> it) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream fo = new FileOutputStream(tmp);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fo, 1 << 16))) {
            out.writeInt(MAGIC_PAGE);
            out.writeInt(1);
            out.writeInt(st.size());
            for (Stroke s : st) {
                out.writeInt(s.tool | (s.shape << 16));
                out.writeInt(s.color);
                out.writeFloat(s.width);
                out.writeInt(s.n);
                for (int k = 0; k < s.n; k++) {
                    out.writeFloat(s.p[4 * k]);
                    out.writeFloat(s.p[4 * k + 1]);
                    out.writeFloat(s.p[4 * k + 2]);
                }
            }
            out.writeInt(it.size());
            for (Item i : it) {
                out.writeInt(i.type);
                out.writeFloat(i.x);
                out.writeFloat(i.y);
                out.writeFloat(i.w);
                out.writeFloat(i.h);
                writeStr(out, i.text, true);
                out.writeFloat(i.size);
                out.writeInt(i.color);
                out.writeInt(i.pts == null ? 0 : i.pts.length);
                if (i.pts != null) for (float v : i.pts) out.writeFloat(v);
                out.writeBoolean(i.style != null);
                if (i.style != null) writeStr(out, i.style, true);
            }
            out.flush();
            fo.getFD().sync(); // on disk before the index names it
        }
        if (!tmp.renameTo(f)) throw new IOException("rename failed: " + f);
    }

    /**
     * Copy safe to write or read on another thread (strokes/items are immutable). Call on the thread that owns this
     * notebook: it gives new pages their file ids. Pages not read yet stay unread in the copy.
     */
    Notebook snapshot() { return snapshot(false); }

    /** Just the notebook's own settings, no pages (enough to draw a page copied with snapCopy). */
    Notebook metaCopy() {
        Notebook n = new Notebook();
        n.dir = dir;
        n.title = title;
        n.canvas = canvas;
        n.paper = paper;
        return n;
    }

    /** forSave: the copy is about to be written, so it also decides whether the thumbnail needs redrawing. */
    Notebook snapshot(boolean forSave) {
        Notebook n = new Notebook();
        n.dir = dir;
        n.title = title;
        n.folder = folder;
        n.cover = cover;
        n.favorite = favorite;
        n.canvas = canvas;
        n.paper = paper;
        n.created = created;
        n.modified = System.currentTimeMillis();
        for (Page p : pages) {
            if (p.id == 0) p.id = nextId++;
            n.pages.add(p.snapCopy());
        }
        n.nextId = nextId;
        n.legacy = legacy;
        n.loadFailed = loadFailed;
        // the notes screen's picture of page 1: only redrawn when page 1 or its look changed
        Page first = pages.get(0);
        String key = paper + "|" + first.bg + "|" + first.spacing + "|" + first.strength + "|" + first.lineColor + "|" + first.margins
                + "|" + first.pdf + "|" + first.h + "|" + System.identityHashCode(first);
        n.thumbWanted = first.dirty() || !key.equals(thumbKey);
        if (forSave) thumbKey = key;
        return n;
    }

    /**
     * Saves: the files of pages that changed, then the index (page order, looks and file ids), meta and, when page 1
     * changed, the thumbnail. Pages that weren't touched cost nothing, whatever the notebook's size.
     */
    void write(File dir) throws IOException {
        dir.mkdirs();
        File pd = new File(dir, "pages");
        pd.mkdirs();
        written.clear();
        for (Page p : pages) {
            if (p.id == 0) p.id = nextId++;
            nextId = Math.max(nextId, p.id + 1);
            File f = new File(pd, p.id + ".bin");
            boolean need = p.loaded() && (p.dirty() || p.src == null || !p.src.equals(f) || !f.exists());
            if (!need) continue;
            if (p.broken && f.exists()) // never lose an unreadable page file: keep it beside the new one
                Io.copy(new FileInputStream(f), new FileOutputStream(new File(dir, "page-" + p.id + ".unreadable-" + System.currentTimeMillis())));
            ArrayList<Stroke> st = p.strokes();
            ArrayList<Item> it = p.items();
            writeBody(f, st, it);
            p.markSaved(st.toArray(), it.toArray(), f);
            written.add(p);
        }
        File tmp = new File(dir, "ink.bin.tmp");
        try (FileOutputStream fo = new FileOutputStream(tmp);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fo, 1 << 16))) {
            out.writeInt(MAGIC8);
            out.writeInt(1);
            out.writeInt(nextId);
            out.writeInt(pages.size());
            for (Page p : pages) {
                out.writeInt(p.id);
                out.writeInt(p.bg);
                out.writeFloat(p.h);
                out.writeInt(p.pdf);
                out.writeBoolean(p.mark != null);
                if (p.mark != null) writeStr(out, p.mark, true);
                out.writeFloat(p.spacing);
                out.writeInt(p.lineColor);
                out.writeFloat(p.strength);
                out.writeInt(p.margins);
            }
            out.flush();
            fo.getFD().sync();
        }
        File ink = new File(dir, "ink.bin");
        if (legacy && ink.exists()) { // the old single-file notebook stays as a backup until the new one has loaded once
            File old = new File(dir, "ink.bin.old");
            if (!old.exists()) ink.renameTo(old);
        }
        if (!tmp.renameTo(ink)) throw new IOException("rename failed");
        legacy = false;
        writeMetaFields(dir);
        File thumb = new File(dir, "thumb.png");
        if (thumbWanted || !thumb.exists()) Render.writeThumb(this, dir, thumb);
    }

    /** The page file names the index refers to (take on the owning thread, then tidy on any). */
    java.util.Set<String> pageFiles() {
        java.util.HashSet<String> keep = new java.util.HashSet<>();
        for (Page p : pages) if (p.id != 0) keep.add(p.id + ".bin");
        return keep;
    }

    /**
     * Tidies a notebook folder when its editor opens: page files no page refers to (deleted pages, a save cut
     * short) and leftovers. The old single-file copy goes once every page file of the new format is there.
     */
    static void tidy(File dir, java.util.Set<String> keep) {
        File pd = new File(dir, "pages");
        boolean all = true;
        for (String n : keep) if (!new File(pd, n).exists()) { all = false; break; }
        File[] fs = pd.listFiles();
        if (fs != null) for (File f : fs) if (!keep.contains(f.getName())) f.delete();
        new File(dir, "ink.bin.tmp").delete();
        if (all) new File(dir, "ink.bin.old").delete();
    }

    /** Lets go of every page that is saved (an export or index pass over a whole notebook keeps one page at a time). */
    void unloadAll() { for (Page p : pages) p.unload(); }

    // ---- strings: writeUTF holds at most 65535 bytes; a longer text box made every save fail ----------

    /** Bytes writeUTF would need (modified UTF-8). */
    static int utfLength(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            n += c >= 1 && c <= 0x7f ? 1 : c <= 0x7ff ? 2 : 3;
        }
        return n;
    }

    private static boolean tooLong(String s) { return s != null && s.length() > 65535 / 3 && utfLength(s) > 65535; }

    private static void writeStr(DataOutputStream out, String s, boolean v7) throws IOException {
        if (!v7) { out.writeUTF(s); return; }
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    private static String readStr(DataInputStream in, boolean v7) throws IOException {
        if (!v7) return in.readUTF();
        int n = in.readInt();
        if (n < 0 || n > 64 << 20) throw new IOException("bad string length " + n);
        byte[] b = new byte[n];
        in.readFully(b);
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    void writeMetaFields(File dir) throws IOException {
        Properties m = meta(dir);
        // folder and favourite belong to the notes screen: an editor save must not undo a move or a star made there
        m.setProperty("title", title);
        m.setProperty("canvas", Boolean.toString(canvas));
        m.setProperty("paper", Integer.toString(paper));
        m.setProperty("created", Long.toString(created));
        m.setProperty("modified", Long.toString(modified));
        m.setProperty("pages", Integer.toString(pages.size()));
        writeMeta(dir, m);
    }

    // ---- library ---------------------------------------------------------------------------

    static File root(Context c) {
        File f = new File(c.getFilesDir(), "notebooks");
        f.mkdirs();
        return f;
    }

    static Properties meta(File dir) {
        Properties p = new Properties();
        File f = new File(dir, "meta.properties");
        if (f.exists()) {
            try (FileInputStream in = new FileInputStream(f)) {
                p.load(in);
            } catch (IOException ignored) {
            }
        }
        return p;
    }

    static void writeMeta(File dir, Properties p) throws IOException {
        File tmp = new File(dir, "meta.properties.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            p.store(out, null);
        }
        tmp.renameTo(new File(dir, "meta.properties"));
    }

    /** Sets or (null) removes a notebook's cover. */
    static void setCover(File dir, String cover) throws IOException {
        Properties m = meta(dir);
        if (cover == null) m.remove("cover"); else m.setProperty("cover", cover);
        writeMeta(dir, m);
    }

    static void setMeta(File dir, String key, String value) throws IOException {
        Properties m = meta(dir);
        m.setProperty(key, value);
        writeMeta(dir, m);
    }

    static final class Entry {
        File dir;
        String title, folder, cover;
        boolean favorite, canvas;
        long modified, created, trashedAt;
        int pages;
    }

    static List<Entry> list(Context c) { return listIn(root(c)); }

    // ---- trash: deleted notebooks wait here for 30 days ----------------------------------------

    static final long TRASH_KEEP_MS = 30L * 24 * 3600_000;

    static File trashRoot(Context c) {
        File f = new File(c.getFilesDir(), "trash");
        f.mkdirs();
        return f;
    }

    static List<Entry> listTrash(Context c) {
        List<Entry> out = listIn(trashRoot(c));
        out.sort(Comparator.comparingLong((Entry e) -> e.trashedAt).reversed());
        return out;
    }

    /** Moves a notebook to the trash (same filesystem, so it's a rename). */
    static boolean trash(Context c, File dir) {
        try { setMeta(dir, "trashedAt", Long.toString(System.currentTimeMillis())); } catch (IOException ignored) { }
        File to = new File(trashRoot(c), dir.getName());
        if (to.exists()) delete(to);
        return dir.renameTo(to);
    }

    static File restore(Context c, File dir) {
        File to = new File(root(c), dir.getName());
        if (to.exists()) to = newDir(c);
        to.delete(); // newDir creates it; renameTo needs it gone
        if (!dir.renameTo(to)) return null;
        try {
            Properties m = meta(to);
            m.remove("trashedAt");
            writeMeta(to, m);
        } catch (IOException ignored) { }
        return to;
    }

    /** Deletes for good whatever has been in the trash longer than 30 days. */
    static void purgeTrash(Context c) {
        long cut = System.currentTimeMillis() - TRASH_KEEP_MS;
        for (Entry e : listIn(trashRoot(c))) if (e.trashedAt > 0 && e.trashedAt < cut) delete(e.dir);
    }

    private static List<Entry> listIn(File root) {
        ArrayList<Entry> out = new ArrayList<>();
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) return out;
        for (File d : dirs) {
            if (!new File(d, "meta.properties").exists()) continue;
            Properties m = meta(d);
            Entry e = new Entry();
            e.dir = d;
            e.title = m.getProperty("title", "Untitled");
            e.folder = m.getProperty("folder", "");
            e.cover = m.getProperty("cover");
            e.favorite = Boolean.parseBoolean(m.getProperty("favorite", "false"));
            e.modified = parseLong(m.getProperty("modified"), d.lastModified());
            e.created = parseLong(m.getProperty("created"), e.modified);
            e.pages = (int) parseLong(m.getProperty("pages"), 1);
            e.canvas = Boolean.parseBoolean(m.getProperty("canvas", "false"));
            e.trashedAt = parseLong(m.getProperty("trashedAt"), 0);
            out.add(e);
        }
        out.sort(Comparator.comparingLong((Entry e) -> e.modified).reversed());
        return out;
    }

    private static long parseLong(String s, long def) {
        try {
            return s == null ? def : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static File newDir(Context c) {
        File d;
        do d = new File(root(c), "nb-" + System.currentTimeMillis() + "-" + (int) (Math.random() * 1000));
        while (d.exists());
        return d;
    }

    static File create(Context c, String title, int bg, String folder) throws IOException {
        return create(c, title, bg, folder, false);
    }

    static File create(Context c, String title, int bg, String folder, boolean canvas) throws IOException {
        Page proto = new Page(bg);
        return create(c, title, proto, folder, canvas, -1);
    }

    /** New notebook whose first page copies proto's template and style; paper -1 = default white. */
    static File create(Context c, String title, Page proto, String folder, boolean canvas, int paper) throws IOException {
        int bg = proto.bg;
        File dir = newDir(c);
        Notebook nb = new Notebook();
        nb.canvas = canvas;
        if (paper >= 0) nb.paper = paper;
        nb.title = title;
        nb.folder = folder == null ? "" : folder;
        nb.created = nb.modified = System.currentTimeMillis();
        Page first = new Page(bg);
        first.copyStyle(proto);
        nb.pages.add(first);
        nb.write(dir);
        return dir;
    }

    static void delete(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) delete(k);
        f.delete();
    }

    static void copyDir(File from, File to) throws IOException {
        to.mkdirs();
        File[] kids = from.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            File t = new File(to, k.getName());
            if (k.isDirectory()) copyDir(k, t);
            else Io.copy(new FileInputStream(k), new FileOutputStream(t));
        }
    }
}

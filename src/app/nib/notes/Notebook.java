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
            BG_ENGINEERING = 7, BG_ISO = 8, BG_SITE = 9;
    static final String[] BG_NAMES = {"Blank", "Lined", "Grid", "Dotted", "Narrow lined", "Cornell notes", "Music staff",
            "Engineering", "Isometric", "Site inspection"};
    static final int PAPER_WHITE = 0, PAPER_CREAM = 1, PAPER_DARK = 2;
    static final String[] PAPER_NAMES = {"White paper", "Cream paper", "Dark paper"};
    private static final int MAGIC1 = 0x4E494231, MAGIC2 = 0x4E494232, MAGIC3 = 0x4E494233, MAGIC4 = 0x4E494234, MAGIC5 = 0x4E494235; // NIB3 +bookmarks, NIB4 +page style, NIB5 +tape paths
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

        Item(int type, float x, float y, float w, float h, String text, float size, int color) {
            this(type, x, y, w, h, text, size, color, null);
        }

        Item(int type, float x, float y, float w, float h, String text, float size, int color, float[] pts) {
            this.type = type; this.x = x; this.y = y; this.w = w; this.h = h;
            this.text = text; this.size = size; this.color = color; this.pts = pts;
        }

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

        Item moved(float px, float py, float s, float tx, float ty) {
            float[] np = null;
            if (pts != null) {
                np = new float[pts.length];
                for (int i = 0; i + 1 < pts.length; i += 2) { np[i] = (pts[i] - px) * s + px + tx; np[i + 1] = (pts[i + 1] - py) * s + py + ty; }
            }
            return new Item(type, (x - px) * s + px + tx, (y - py) * s + py + ty, w * s, h * s, text, size * s, color, np);
        }

        Item withText(String t, float nh) { return new Item(type, x, y, w, nh, t, size, color, pts); }

        Item withColor(int c) { return new Item(type, x, y, w, h, text, size, c, pts); }

        /** TAPE: text holds the pattern, plus "|open" while the tape is peeled back to show what's under it. */
        boolean revealed() { return type == TAPE && text.endsWith("|open"); }

        String pattern() { return type == TAPE ? (revealed() ? text.substring(0, text.length() - 5) : text) : ""; }

        Item withRevealed(boolean r) { return new Item(type, x, y, w, h, pattern() + (r ? "|open" : ""), size, color, pts); }

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

    static final class Page {
        int bg;
        float h = PH;
        int pdf = -1; // page index in source.pdf, or -1
        String mark;  // bookmark title ("" = untitled bookmark), null = not bookmarked
        // page style on top of the template: pattern spacing (×template default), line colour (0 = paper's own),
        // line strength (×, 1 = normal), margins (MARGIN_* bits)
        float spacing = 1f, strength = 1f;
        int lineColor, margins;

        void copyStyle(Page o) { spacing = o.spacing; strength = o.strength; lineColor = o.lineColor; margins = o.margins; }

        boolean sameStyle(Page o) {
            return spacing == o.spacing && strength == o.strength && lineColor == o.lineColor && margins == o.margins;
        }
        ArrayList<Stroke> strokes = new ArrayList<>();
        ArrayList<Item> items = new ArrayList<>();

        Page(int bg) { this.bg = bg; }

        Page copyShallow() {
            Page p = new Page(bg);
            p.h = h;
            p.pdf = pdf;
            p.mark = mark;
            p.copyStyle(this);
            p.strokes = new ArrayList<>(strokes);
            p.items = new ArrayList<>(items);
            return p;
        }
    }

    ArrayList<Page> pages = new ArrayList<>();
    String title = "Untitled", folder = "";
    boolean favorite;
    boolean canvas; // infinite canvas: one unbounded page you can pan in every direction
    boolean loadFailed; // ink.bin couldn't be read in full (a copy of it was kept)
    int paper = PAPER_WHITE;
    long created, modified;

    // ---- io ------------------------------------------------------------------------------

    static Notebook load(File dir) {
        Notebook nb = new Notebook();
        Properties m = meta(dir);
        nb.title = m.getProperty("title", "Untitled");
        nb.folder = m.getProperty("folder", "");
        nb.favorite = Boolean.parseBoolean(m.getProperty("favorite", "false"));
        nb.canvas = Boolean.parseBoolean(m.getProperty("canvas", "false"));
        nb.paper = Integer.parseInt(m.getProperty("paper", "0"));
        nb.created = Long.parseLong(m.getProperty("created", "0"));
        nb.modified = Long.parseLong(m.getProperty("modified", "0"));
        File f = new File(dir, "ink.bin");
        if (f.exists()) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 16))) {
                int magic = in.readInt();
                if (magic != MAGIC1 && magic != MAGIC2 && magic != MAGIC3 && magic != MAGIC4 && magic != MAGIC5) throw new IOException("bad magic");
                boolean v2 = magic != MAGIC1, v3 = magic == MAGIC3 || magic == MAGIC4 || magic == MAGIC5, v4 = magic == MAGIC4 || magic == MAGIC5, v5 = magic == MAGIC5;
                int pages = in.readInt();
                for (int i = 0; i < pages; i++) {
                    Page p = new Page(in.readInt());
                    if (v2) {
                        p.h = in.readFloat();
                        p.pdf = in.readInt();
                    }
                    int ns = in.readInt();
                    for (int s = 0; s < ns; s++) {
                        int tw = in.readInt();
                        Stroke st = new Stroke(tw & 0xffff, in.readInt(), in.readFloat());
                        st.shape = (tw >>> 16) & 0xff;
                        int n = in.readInt();
                        for (int k = 0; k < n; k++) st.add(in.readFloat(), in.readFloat(), in.readFloat());
                        p.strokes.add(st);
                    }
                    if (v2) {
                        int ni = in.readInt();
                        for (int k = 0; k < ni; k++) {
                            int type = in.readInt();
                            float x = in.readFloat(), y = in.readFloat(), w = in.readFloat(), h = in.readFloat();
                            String text = in.readUTF();
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
                            p.items.add(new Item(type, x, y, w, h, text, size, color, pts));
                        }
                    }
                    if (v3 && in.readBoolean()) p.mark = in.readUTF();
                    if (v4) {
                        p.spacing = in.readFloat();
                        p.lineColor = in.readInt();
                        p.strength = in.readFloat();
                        p.margins = in.readInt();
                    }
                    nb.pages.add(p);
                }
            } catch (IOException e) {
                // Keep the unreadable file: the next save would otherwise replace it with only the pages read so far.
                android.util.Log.e(Gl.TAG, "load failed", e);
                File keep = new File(dir, "ink.bin.unreadable-" + System.currentTimeMillis());
                try { Io.copy(new FileInputStream(f), new FileOutputStream(keep)); } catch (IOException ignored) { }
                nb.loadFailed = true;
            }
        }
        if (nb.pages.isEmpty()) nb.pages.add(new Page(BG_DOTS));
        return nb;
    }

    /** Copy safe to write on another thread (strokes/items are immutable). */
    Notebook snapshot() {
        Notebook n = new Notebook();
        n.title = title;
        n.folder = folder;
        n.favorite = favorite;
        n.canvas = canvas;
        n.paper = paper;
        n.created = created;
        n.modified = System.currentTimeMillis();
        for (Page p : pages) n.pages.add(p.copyShallow());
        return n;
    }

    void write(File dir) throws IOException {
        dir.mkdirs();
        File tmp = new File(dir, "ink.bin.tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16))) {
            out.writeInt(MAGIC5);
            out.writeInt(pages.size());
            for (Page p : pages) {
                out.writeInt(p.bg);
                out.writeFloat(p.h);
                out.writeInt(p.pdf);
                out.writeInt(p.strokes.size());
                for (Stroke s : p.strokes) {
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
                out.writeInt(p.items.size());
                for (Item it : p.items) {
                    out.writeInt(it.type);
                    out.writeFloat(it.x);
                    out.writeFloat(it.y);
                    out.writeFloat(it.w);
                    out.writeFloat(it.h);
                    out.writeUTF(it.text);
                    out.writeFloat(it.size);
                    out.writeInt(it.color);
                    out.writeInt(it.pts == null ? 0 : it.pts.length);
                    if (it.pts != null) for (float f : it.pts) out.writeFloat(f);
                }
                out.writeBoolean(p.mark != null);
                if (p.mark != null) out.writeUTF(p.mark);
                out.writeFloat(p.spacing);
                out.writeInt(p.lineColor);
                out.writeFloat(p.strength);
                out.writeInt(p.margins);
            }
        }
        if (!tmp.renameTo(new File(dir, "ink.bin"))) throw new IOException("rename failed");
        writeMetaFields(dir);
        Render.writeThumb(this, dir, new File(dir, "thumb.png"));
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

    static void setMeta(File dir, String key, String value) throws IOException {
        Properties m = meta(dir);
        m.setProperty(key, value);
        writeMeta(dir, m);
    }

    static final class Entry {
        File dir;
        String title, folder;
        boolean favorite, canvas;
        long modified, created;
        int pages;
    }

    static List<Entry> list(Context c) {
        ArrayList<Entry> out = new ArrayList<>();
        File[] dirs = root(c).listFiles(File::isDirectory);
        if (dirs == null) return out;
        for (File d : dirs) {
            if (!new File(d, "meta.properties").exists()) continue;
            Properties m = meta(d);
            Entry e = new Entry();
            e.dir = d;
            e.title = m.getProperty("title", "Untitled");
            e.folder = m.getProperty("folder", "");
            e.favorite = Boolean.parseBoolean(m.getProperty("favorite", "false"));
            e.modified = parseLong(m.getProperty("modified"), d.lastModified());
            e.created = parseLong(m.getProperty("created"), e.modified);
            e.pages = (int) parseLong(m.getProperty("pages"), 1);
            e.canvas = Boolean.parseBoolean(m.getProperty("canvas", "false"));
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

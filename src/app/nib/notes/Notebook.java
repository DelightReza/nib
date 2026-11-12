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
    private static final int MAGIC1 = 0x4E494231, MAGIC2 = 0x4E494232, MAGIC3 = 0x4E494233, MAGIC4 = 0x4E494234; // NIB3 +bookmarks, NIB4 +page style
    static final int MARGIN_LEFT = 1, MARGIN_HEADER = 2, MARGIN_BORDER = 4;

    /** Text box or picture placed on a page (page-local units). */
    static final class Item {
        static final int TEXT = 0, IMAGE = 1;
        final int type;
        final float x, y, w, h;
        final String text;   // TEXT: the text; IMAGE: file name under images/
        final float size;    // TEXT: font size in units
        final int color;     // TEXT colour

        Item(int type, float x, float y, float w, float h, String text, float size, int color) {
            this.type = type; this.x = x; this.y = y; this.w = w; this.h = h;
            this.text = text; this.size = size; this.color = color;
        }

        Item moved(float px, float py, float s, float tx, float ty) {
            return new Item(type, (x - px) * s + px + tx, (y - py) * s + py + ty, w * s, h * s, text, size * s, color);
        }

        Item withText(String t, float nh) { return new Item(type, x, y, w, nh, t, size, color); }

        Item withColor(int c) { return new Item(type, x, y, w, h, text, size, c); }

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
                if (magic != MAGIC1 && magic != MAGIC2 && magic != MAGIC3 && magic != MAGIC4) throw new IOException("bad magic");
                boolean v2 = magic != MAGIC1, v3 = magic == MAGIC3 || magic == MAGIC4, v4 = magic == MAGIC4;
                int pages = in.readInt();
                for (int i = 0; i < pages; i++) {
                    Page p = new Page(in.readInt());
                    if (v2) {
                        p.h = in.readFloat();
                        p.pdf = in.readInt();
                    }
                    int ns = in.readInt();
                    for (int s = 0; s < ns; s++) {
                        Stroke st = new Stroke(in.readInt(), in.readInt(), in.readFloat());
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
                            p.items.add(new Item(type, x, y, w, h, text, size, color));
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
                android.util.Log.e(Gl.TAG, "load failed", e);
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
            out.writeInt(MAGIC4);
            out.writeInt(pages.size());
            for (Page p : pages) {
                out.writeInt(p.bg);
                out.writeFloat(p.h);
                out.writeInt(p.pdf);
                out.writeInt(p.strokes.size());
                for (Stroke s : p.strokes) {
                    out.writeInt(s.tool);
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
        m.setProperty("title", title);
        m.setProperty("folder", folder);
        m.setProperty("favorite", Boolean.toString(favorite));
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

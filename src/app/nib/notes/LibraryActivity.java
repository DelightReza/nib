package app.nib.notes;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.SubMenu;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LibraryActivity extends Activity {
    private static final int REQ_PDF = 21, REQ_TREE = 22, REQ_RESTORE = 23;
    private static final String FAV = "★";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private Ui.Masonry grid;
    private LinearLayout topRow, selRow;
    private boolean selecting;
    private final java.util.LinkedHashSet<String> picked = new java.util.LinkedHashSet<>();
    private final java.util.HashMap<String, View> checks = new java.util.HashMap<>();
    private TextView pickedLabel;
    private View moreB;
    private TextView sortLabel;
    private LinearLayout viewControls;
    private LinearLayout libLinks;
    private TextView subtitle, pageTitle, empty;
    private LinearLayout crumbs;
    private Ui.Grid folderGrid;
    private View sidePanel, sideRule, navButton, searchBox, newCompact;
    private LinearLayout headerRow, searchRow;
    private static final String RECENT = "⏱", TRASH = "🗑", UNFILED = "∅";
    /** Notebook card width (dp) for Small / Medium / Large. */
    private static final int[] CARD_SIZES = {84, 110, 170}; // portrait (≈395dp of cards at 400 dpi): 4, 3, 2 a row
    private LinearLayout chips;
    private String filter = "", query = "";
    private int sort;
    private List<Notebook.Entry> all = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        if (!isTaskRoot() && in != null && Intent.ACTION_MAIN.equals(in.getAction()) && in.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            finish(); // launched from the icon while Nib is open: just go back to where you were
            return;
        }
        prefs = getSharedPreferences("nib", MODE_PRIVATE);
        if (b == null) Theme.restore(this);
        sort = prefs.getInt("sort", 0);
        filter = prefs.getString("filter", "");

        Theme.applyTo(this);
        LinearLayout rootRow = new LinearLayout(this);
        rootRow.setBackgroundColor(Ui.color(this, R.color.bg));

        // ---- sidebar: lists, then folders; one row style ----
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(Ui.dp(this, 14), Ui.dp(this, 30), Ui.dp(this, 14), Ui.dp(this, 16));
        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_nib);
        mark.setImageTintList(ColorStateList.valueOf(Ui.color(this, R.color.accent)));
        brand.addView(mark, new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));
        TextView bt = Ui.text(this, "Nib", 21, Ui.color(this, R.color.on_surface), Ui.font(700));
        bt.setLetterSpacing(-0.01f);
        bt.setPadding(Ui.dp(this, 9), 0, 0, 0);
        brand.addView(bt);
        brand.setPadding(Ui.dp(this, 10), 0, 0, 0);
        LinearLayout.LayoutParams brl = new LinearLayout.LayoutParams(-2, -2);
        brl.bottomMargin = Ui.dp(this, 22);
        side.addView(brand, brl);
        libLinks = new LinearLayout(this);
        libLinks.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lll = new LinearLayout.LayoutParams(-1, -2);
        lll.bottomMargin = Ui.dp(this, 18);
        side.addView(libLinks, lll);
        TextView cap = Ui.text(this, "Folders", 13, Ui.color(this, R.color.on_surface_variant), Ui.bold());
        cap.setPadding(Ui.dp(this, 10), 0, 0, 0);
        LinearLayout.LayoutParams capl = new LinearLayout.LayoutParams(-2, -2);
        capl.bottomMargin = Ui.dp(this, 4);
        side.addView(cap, capl);
        ScrollView navScroll = new ScrollView(this);
        navScroll.setVerticalScrollBarEnabled(false);
        chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.VERTICAL);
        navScroll.addView(chips);
        side.addView(navScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout foot = new LinearLayout(this);
        foot.setPadding(Ui.dp(this, 6), 0, 0, 0);
        foot.addView(roundButton(R.drawable.ic_settings, "Settings & backup", this::showMore));
        View themeB = roundButton(R.drawable.ic_palette, "Theme", v -> ThemeSheet.show(this, this::recreate));
        LinearLayout.LayoutParams tbl = new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44));
        tbl.leftMargin = Ui.dp(this, 10);
        foot.addView(themeB, tbl);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2);
        fl.topMargin = Ui.dp(this, 12);
        side.addView(foot, fl);
        rootRow.addView(side, new LinearLayout.LayoutParams(Ui.dp(this, 264), -1));
        View rule = new View(this);
        rule.setBackgroundColor(Ui.color(this, R.color.outline));
        rootRow.addView(rule, new LinearLayout.LayoutParams(Ui.hairline(this), -1));
        sidePanel = side;
        sideRule = rule;

        // ---- content ----
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 30);
        col.setPadding(pad, Ui.dp(this, 30), pad, Ui.dp(this, 48));
        sv.addView(col);
        contentCol = col;
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Ui.IconButton nav = new Ui.IconButton(this, R.drawable.ic_sidebar, "Folders and lists");
        nav.setOnClickListener(this::showNav);
        top.addView(nav, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        nav.setVisibility(View.GONE);
        navButton = nav;
        EditText search = new EditText(this);
        search.setHint("Search, even handwriting");
        search.setSingleLine(true);
        search.setTextSize(14.5f);
        search.setTextColor(Ui.color(this, R.color.on_surface));
        search.setHintTextColor(Ui.color(this, R.color.on_surface_variant));
        search.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0);
        search.setCompoundDrawableTintList(ColorStateList.valueOf(Ui.color(this, R.color.on_surface_variant)));
        search.setCompoundDrawablePadding(Ui.dp(this, 8));
        search.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        GradientDrawable sbg = Ui.round(Ui.color(this, R.color.surface), Ui.dp(this, 22));
        sbg.setStroke(Ui.hairline(this), Ui.color(this, R.color.outline));
        search.setBackground(sbg);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void afterTextChanged(Editable e) { query = e.toString().trim().toLowerCase(); fillGrid(); }
        });
        top.addView(search, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f)); // takes the free space; New and Select always fit
        searchBox = search;
        TextView newB = Ui.text(this, "New", 15, Ui.color(this, R.color.on_accent), Ui.bold());
        newB.setGravity(Gravity.CENTER_VERTICAL);
        newB.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_nib, 0, 0, 0);
        newB.setCompoundDrawableTintList(ColorStateList.valueOf(Ui.color(this, R.color.on_accent)));
        newB.setCompoundDrawablePadding(Ui.dp(this, 7));
        newB.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 18), 0);
        newB.setSingleLine(true);
        newB.setBackground(Ui.ripple(this, Ui.round(Ui.color(this, R.color.accent), Ui.dp(this, 22)), Ui.dp(this, 22)));
        newB.setOnClickListener(this::showNew);
        newB.setContentDescription("New notebook");
        newButton = newB;
        LinearLayout.LayoutParams nbl = new LinearLayout.LayoutParams(-2, Ui.dp(this, 44));
        nbl.leftMargin = Ui.dp(this, 10);
        TextView selB = Ui.text(this, "Select", 15, Ui.color(this, R.color.accent), Ui.bold());
        selB.setGravity(Gravity.CENTER);
        selB.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        selB.setBackground(Ui.ripple(this, null, Ui.dp(this, 22)));
        selB.setOnClickListener(v -> startSelecting(null));
        LinearLayout.LayoutParams sbl2 = new LinearLayout.LayoutParams(-2, Ui.dp(this, 44));
        sbl2.leftMargin = Ui.dp(this, 4);
        top.addView(selB, sbl2);
        top.addView(newB, nbl);
        col.addView(top);
        topRow = top;
        selRow = new LinearLayout(this);
        selRow.setGravity(Gravity.CENTER_VERTICAL);
        selRow.setVisibility(View.GONE);
        col.addView(selRow, new LinearLayout.LayoutParams(-1, Ui.dp(this, 44)));
        crumbs = new LinearLayout(this);
        crumbs.setGravity(Gravity.CENTER_VERTICAL);
        crumbs.setVisibility(View.GONE);
        LinearLayout.LayoutParams cbl = new LinearLayout.LayoutParams(-2, Ui.dp(this, 24));
        cbl.topMargin = Ui.dp(this, 18);
        col.addView(crumbs, cbl);
        pageTitle = Ui.text(this, "All notes", 38, Ui.color(this, R.color.on_surface), Theme.titleFace(this));
        pageTitle.setFontVariationSettings("'wght' 650, 'opsz' 32"); // Inter Display cut for big titles
        pageTitle.setLetterSpacing(-0.02f);
        pageTitle.setSingleLine(true);
        pageTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        // title row: the title, then view controls on the right (sort, card size)
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(pageTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        viewControls = new LinearLayout(this);
        viewControls.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(viewControls);
        LinearLayout.LayoutParams ptl = new LinearLayout.LayoutParams(-1, -2);
        ptl.topMargin = Ui.dp(this, 14);
        col.addView(titleRow, ptl);
        LinearLayout meta = new LinearLayout(this);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        subtitle = Ui.text(this, "", 14.5f, Ui.color(this, R.color.on_surface_variant), null);
        meta.addView(subtitle);
        sortLabel = Ui.text(this, "", 14.5f, Ui.color(this, R.color.on_surface_variant), null);
        sortLabel.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));
        sortLabel.setBackground(Ui.ripple(this, null, Ui.dp(this, 8)));
        meta.addView(sortLabel);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2);
        ml.topMargin = Ui.dp(this, 4);
        col.addView(meta, ml);
        folderGrid = new Ui.Grid(this, 190, 12);
        LinearLayout.LayoutParams fgl = new LinearLayout.LayoutParams(-1, -2);
        fgl.topMargin = Ui.dp(this, 24);
        col.addView(folderGrid, fgl);
        grid = new Ui.Masonry(this, CARD_SIZES[cardSize()], cardSize() == 0 ? 14 : 20);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, -2);
        gl.topMargin = Ui.dp(this, 26);
        col.addView(grid, gl);
        empty = Ui.text(this, "", 14.5f, Ui.color(this, R.color.on_surface_variant), null);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, Ui.dp(this, 80), 0, 0);
        col.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        rootRow.addView(sv, new LinearLayout.LayoutParams(0, -1, 1f));
        setContentView(rootRow);
        // narrow windows (split screen): the index folds into a button; search gets shorter
        rootRow.addOnLayoutChangeListener((v, l, t, r, b2, ol, ot, or, ob) -> {
            if (r - l == or - ol) return;
            boolean narrow = r - l < Ui.dp(this, 600);
            boolean phone = r - l < Ui.dp(this, 480);
            ui.post(() -> {
                sidePanel.setVisibility(narrow ? View.GONE : View.VISIBLE);
                sideRule.setVisibility(narrow ? View.GONE : View.VISIBLE);
                navButton.setVisibility(narrow ? View.VISIBLE : View.GONE);
                setCompact(phone);
            });
        });

        handleIncoming(getIntent());

        // Reopen where you left off: the app was left from a notebook (not by going back to this screen), so starting it
        // again goes straight back there (the editor restores its tabs, split and each notebook's page and zoom).
        // `am start ... --ez reopen true` does the same on demand (testing).
        String last = prefs.getString("tabCur", null);
        if (last == null || !new File(last).isDirectory()) last = prefs.getString("lastDir", null);
        boolean launcher = Intent.ACTION_MAIN.equals(getIntent().getAction());
        boolean resume = launcher && prefs.getBoolean("resumeEditor", false) && prefs.getBoolean("reopenLast", true);
        // only on a fresh launch: a theme change recreates this screen with the same intent, and must not open a notebook
        if (b == null && (resume || getIntent().getBooleanExtra("reopen", false)) && last != null && new File(last).isDirectory()) {
            getIntent().removeExtra("reopen");
            open(new File(last));
        }
    }

    /** Phone-width layout: tighter margins, a smaller title, New as a round + button, dates under the titles. */
    private void setCompact(boolean on) {
        if (on == compact && compactSet) return;
        compactSet = true;
        compact = on;
        int pad = Ui.dp(this, on ? 16 : 30);
        contentCol.setPadding(pad, Ui.dp(this, on ? 16 : 30), pad, Ui.dp(this, 48));
        ((EditText) searchBox).setHint(on ? "Search" : "Search, even handwriting");
        pageTitle.setTextSize(on ? 30 : 38);
        if (on) {
            newButton.setText("");
            newButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_plus, 0, 0, 0);
            newButton.setCompoundDrawablePadding(0);
            newButton.setGravity(Gravity.CENTER);
            newButton.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
            newButton.setLayoutParams(sized(newButton, Ui.dp(this, 44)));
        } else {
            newButton.setText("New");
            newButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_nib, 0, 0, 0);
            newButton.setCompoundDrawablePadding(Ui.dp(this, 7));
            newButton.setGravity(Gravity.CENTER_VERTICAL);
            newButton.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 18), 0);
            newButton.setLayoutParams(sized(newButton, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        newButton.setTooltipText(on ? "New" : null);
        updatePicked();
        fillGrid(); // cards put the date under the title
    }

    private static LinearLayout.LayoutParams sized(View v, int w) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((LinearLayout.LayoutParams) v.getLayoutParams());
        lp.width = w;
        return lp;
    }

    private boolean compact, compactSet;
    private LinearLayout contentCol;
    private TextView newButton;

    /** New: a sheet to choose name, notebook or canvas, page style, paper and cover first (NewNotebookSheet). */
    private void showNew(View anchor) {
        String title = new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(new Date());
        // the page style chosen with "Use for new notebooks", else dotted; Graphite theme defaults to dark paper
        Notebook.Page proto = new Notebook.Page(Notebook.BG_DOTS);
        int paper = Ui.dark(this) ? Notebook.PAPER_DARK : Notebook.PAPER_WHITE;
        try {
            String def = prefs.getString("defStyle", null);
            if (def != null) {
                org.json.JSONObject o = new org.json.JSONObject(def);
                proto = EditorActivity.lookOf(o);
                paper = o.optInt("p", paper);
            }
        } catch (org.json.JSONException ignored) { }
        org.json.JSONArray mine;
        try { mine = new org.json.JSONArray(prefs.getString("myTpl", "[]")); } catch (org.json.JSONException e) { mine = new org.json.JSONArray(); }
        NewNotebookSheet.show(this, title, proto, paper, mine,
                () -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf"), REQ_PDF),
                o -> prefs.edit().putString("defStyle", o.toString()).apply(),
                this::createNotebook);
    }

    private void createNotebook(String title, boolean canvas, Notebook.Page proto, int paper, String cover) {
        try {
            String folder = isFolderFilter() ? filter : "";
            File dir = Notebook.create(this, title, proto, folder, canvas, paper);
            if (cover != null) {
                Notebook.setCover(dir, cover);
                prefs.edit().putString("view:" + dir.getName(), "0,0,1,0,0").apply(); // a new notebook opens at page 1, ready to write
            }
            open(dir);
        } catch (Exception ex) {
            Toast.makeText(this, "Could not create notebook", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onNewIntent(Intent in) {
        super.onNewIntent(in);
        handleIncoming(in);
    }

    /** A PDF opened or shared from another app: import it as a notebook and open it. */
    private void handleIncoming(Intent in) {
        if (in == null) return;
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(in.getAction())) uri = in.getData();
        else if (Intent.ACTION_SEND.equals(in.getAction())) uri = in.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        if (uri == null) return;
        in.setAction(null); // don't import again on rotation / recreate
        importPdf(uri);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        ThemeSheet.reopenIfPending(this, this::recreate);
        bg.execute(() -> Notebook.purgeTrash(this)); // anything in the trash for over 30 days goes for good
        Handwriting.indexAll(this, () -> { if (!query.isEmpty()) fillGrid(); });
        if (Backup.due(this)) {
            bg.execute(() -> {
                try {
                    Backup.run(this);
                    Log.i(Gl.TAG, "auto backup done");
                } catch (Exception e) {
                    Log.w(Gl.TAG, "auto backup failed", e);
                }
            });
        }
    }

    // ---- listing -----------------------------------------------------------------------------

    // Folders are paths: "School/Physics" is the folder Physics inside School. A notebook's meta 'folder' holds
    // its folder's path; empty folders live in prefs 'folders'. Every ancestor of a path counts as a folder too.

    /** Every folder, parents before their children, siblings alphabetical. */
    private ArrayList<String> folders() {
        TreeSet<String> set = new TreeSet<>(LibraryActivity::comparePaths);
        ArrayList<String> raw = new ArrayList<>(prefs.getStringSet("folders", new HashSet<>()));
        for (Notebook.Entry e : all) if (!e.folder.isEmpty()) raw.add(e.folder);
        for (String f : raw) {
            String[] parts = f.split("/");
            StringBuilder b = new StringBuilder();
            for (String part : parts) {
                if (part.trim().isEmpty()) continue;
                if (b.length() > 0) b.append('/');
                b.append(part.trim());
                set.add(b.toString());
            }
        }
        return new ArrayList<>(set);
    }

    /** Orders paths depth-first: a folder, then everything inside it, then its next sibling. */
    private static int comparePaths(String a, String b) {
        String[] x = a.split("/"), y = b.split("/");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            int c = x[i].compareToIgnoreCase(y[i]);
            if (c == 0) c = x[i].compareTo(y[i]);
            if (c != 0) return c;
        }
        return Integer.compare(x.length, y.length);
    }

    static String leaf(String path) { return path.substring(path.lastIndexOf('/') + 1); }

    static String parentOf(String path) { int i = path.lastIndexOf('/'); return i < 0 ? "" : path.substring(0, i); }

    static int depth(String path) { int d = 0; for (char ch : path.toCharArray()) if (ch == '/') d++; return d; }

    static boolean within(String path, String folder) { return path.equals(folder) || path.startsWith(folder + "/"); }

    /** "School/Physics" → "School › Physics" */
    static String pretty(String path) { return path.replace("/", " › "); }

    private boolean isFolderFilter() { return !filter.isEmpty() && !filter.equals(FAV) && !filter.equals(RECENT) && !filter.equals(TRASH) && !filter.equals(UNFILED); }

    private ArrayList<String> childrenOf(ArrayList<String> fl, String parent) {
        ArrayList<String> out = new ArrayList<>();
        for (String f : fl) if (parentOf(f).equals(parent)) out.add(f);
        return out;
    }

    private HashSet<String> openFolders() { return new HashSet<>(prefs.getStringSet("openFolders", new HashSet<>())); }

    private void refresh() {
        all = Notebook.list(this);
        ArrayList<String> folders = folders();
        if (isFolderFilter() && !folders.contains(filter)) filter = "";
        int favs = 0, recent = 0;
        long week = System.currentTimeMillis() - 7L * 24 * 3600_000;
        for (Notebook.Entry e : all) { if (e.favorite) favs++; if (e.modified > week) recent++; }
        int trashed = Notebook.listTrash(this).size(), unfiled = 0;
        for (Notebook.Entry e : all) if (e.folder.isEmpty()) unfiled++;
        libLinks.removeAllViews();
        libLinks.addView(navRow(R.drawable.ic_book, "All notes", all.size(), 0, filter.isEmpty(), false, false, v -> setFilter(""), null, null));
        libLinks.addView(navRow(R.drawable.ic_star, "Favourites", favs, 0, filter.equals(FAV), false, false, v -> setFilter(FAV), null, null));
        libLinks.addView(navRow(R.drawable.ic_inbox, "Unfiled", unfiled, 0, filter.equals(UNFILED), false, false, v -> setFilter(UNFILED), null, null));
        libLinks.addView(navRow(R.drawable.ic_clock, "Recent", recent, 0, filter.equals(RECENT), false, false, v -> setFilter(RECENT), null, null));
        libLinks.addView(navRow(R.drawable.ic_trash, "Trash", trashed, 0, filter.equals(TRASH), false, false, v -> setFilter(TRASH), null, null));
        // folders: subfolders show under an open folder (and under the one you're in)
        chips.removeAllViews();
        HashSet<String> open = openFolders();
        if (isFolderFilter()) for (String a = parentOf(filter); !a.isEmpty(); a = parentOf(a)) open.add(a);
        for (String f : folders) {
            boolean visible = true;
            for (String a = parentOf(f); !a.isEmpty(); a = parentOf(a)) if (!open.contains(a)) { visible = false; break; }
            if (!visible) continue;
            int n = 0;
            for (Notebook.Entry e : all) if (within(e.folder, f)) n++;
            boolean kids = !childrenOf(folders, f).isEmpty();
            chips.addView(navRow(R.drawable.ic_folder, leaf(f), n, depth(f), filter.equals(f), kids, open.contains(f),
                    v -> setFilter(f), v -> { folderMenu(v, f); return true; }, kids ? v -> {
                        HashSet<String> o = openFolders();
                        if (!o.remove(f)) o.add(f);
                        prefs.edit().putStringSet("openFolders", o).apply();
                        refresh();
                    } : null));
        }
        View nf = navRow(R.drawable.ic_plus, "New folder", -1, 0, false, false, false, v -> newFolder(null), null, null);
        nf.setAlpha(0.8f);
        chips.addView(nf);
        fillGrid();
    }

    private void setFilter(String f) {
        if (selecting) stopSelecting();
        filter = f;
        prefs.edit().putString("filter", f).apply();
        refresh();
    }

    /**
     * One sidebar row: [chevron] icon, name, count. Indented by folder depth. The selected row sits on a soft accent
     * background with its name in the accent; the rest are quiet. Same row for lists and folders, so the column reads as one.
     */
    private View navRow(int icon, String label, int count, int depth, boolean sel, boolean kids, boolean open,
                        View.OnClickListener click, View.OnLongClickListener longClick, View.OnClickListener toggle) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 6 + 16 * depth), 0, Ui.dp(this, 12), 0);
        row.setBackground(Ui.ripple(this, sel ? Ui.round(Ui.color(this, R.color.accent_container), Ui.dp(this, 10)) : null, Ui.dp(this, 10)));
        // chevron slot (folders with subfolders), kept for every folder so names line up
        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chevron);
        chev.setImageTintList(ColorStateList.valueOf(Ui.color(this, R.color.on_surface_variant)));
        chev.setScaleType(ImageView.ScaleType.FIT_CENTER);
        chev.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        chev.setRotation(open ? 90 : 0);
        chev.setVisibility(kids ? View.VISIBLE : View.INVISIBLE);
        if (toggle != null) {
            chev.setOnClickListener(toggle);
            chev.setContentDescription(open ? "Hide subfolders" : "Show subfolders");
        }
        boolean folderRow = icon == R.drawable.ic_folder;
        if (folderRow) row.addView(chev, new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));
        int fg = Ui.color(this, sel ? R.color.accent : R.color.on_surface);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setImageTintList(ColorStateList.valueOf(sel ? fg : Ui.color(this, R.color.icon)));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 18));
        il.leftMargin = Ui.dp(this, folderRow ? 2 : 8);
        row.addView(iv, il);
        TextView t = Ui.text(this, label, 15, fg, sel ? Ui.bold() : Ui.medium());
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        if (count >= 0) {
            TextView c = Ui.text(this, String.valueOf(count), 13, Ui.color(this, sel ? R.color.accent : R.color.on_surface_variant), Ui.medium());
            c.setFontFeatureSettings("tnum");
            row.addView(c);
        }
        row.setOnClickListener(click);
        if (longClick != null) row.setOnLongClickListener(longClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 40));
        lp.bottomMargin = Ui.dp(this, 2);
        row.setLayoutParams(lp);
        return row;
    }

    /** Round hairline button (settings, theme) at the foot of the sidebar. */
    private View roundButton(int icon, String desc, View.OnClickListener l) {
        ImageView b = new ImageView(this);
        b.setImageResource(icon);
        b.setContentDescription(desc);
        b.setImageTintList(ColorStateList.valueOf(Ui.color(this, R.color.icon)));
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int p = Ui.dp(this, 11);
        b.setPadding(p, p, p, p);
        GradientDrawable g = Ui.round(Ui.color(this, R.color.surface), Ui.dp(this, 22));
        g.setStroke(Ui.hairline(this), Ui.color(this, R.color.outline));
        b.setBackground(Ui.ripple(this, g, Ui.dp(this, 22)));
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        return b;
    }

    /** The sidebar's lists as a menu (narrow windows). */
    private void showNav(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        android.view.Menu m = pm.getMenu();
        m.add(0, 1, 0, "All notes");
        m.add(0, 2, 1, "Recent");
        m.add(0, 3, 2, "Favourites");
        m.add(0, 8, 3, "Unfiled");
        m.add(0, 6, 3, "Trash");
        ArrayList<String> fl = folders();
        for (int i = 0; i < fl.size(); i++) m.add(1, 100 + i, 10 + i, "\u2003".repeat(depth(fl.get(i))) + "📁  " + leaf(fl.get(i)));
        m.add(2, 4, 100, "New folder…");
        m.add(2, 5, 101, "Settings & backup…");
        pm.setOnMenuItemClickListener(it -> {
            int id = it.getItemId();
            if (id == 1) setFilter("");
            else if (id == 2) setFilter(RECENT);
            else if (id == 3) setFilter(FAV);
            else if (id == 6) setFilter(TRASH);
            else if (id == 8) setFilter(UNFILED);
            else if (id == 4) newFolder(null);
            else if (id == 5) showMore(anchor);
            else if (id >= 100) setFilter(fl.get(id - 100));
            return true;
        });
        pm.show();
    }

    /** Sort order. */
    private void showSort(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        String[] names = {"Recently edited", "Name", "Date created"};
        for (int i = 0; i < 3; i++) pm.getMenu().add(1, 10 + i, i, names[i]).setCheckable(true).setChecked(sort == i);
        pm.getMenu().setGroupCheckable(1, true, true);
        pm.setOnMenuItemClickListener(it -> {
            int id = it.getItemId();
            sort = id - 10;
            prefs.edit().putInt("sort", sort).apply();
            fillGrid();
            return true;
        });
        pm.show();
    }

    private final java.util.HashMap<String, Handwriting.Hit> hits = new java.util.HashMap<>();

    private void fillGrid() {
        grid.removeAllViews();
        checks.clear();
        hits.clear();
        boolean trash = filter.equals(TRASH);
        ArrayList<Notebook.Entry> shown = new ArrayList<>();
        for (Notebook.Entry e : trash ? Notebook.listTrash(this) : all) {
            if (filter.equals(FAV) && !e.favorite) continue;
            if (filter.equals(RECENT) && e.modified < System.currentTimeMillis() - 7L * 24 * 3600_000) continue;
            if (filter.equals(UNFILED) && !e.folder.isEmpty()) continue; // not in any folder
            // in a folder: its own notebooks; while searching, everything inside it (subfolders too)
            if (isFolderFilter() && !(query.isEmpty() ? filter.equals(e.folder) : within(e.folder, filter))) continue;
            if (!query.isEmpty() && !e.title.toLowerCase().contains(query) && !e.folder.toLowerCase().contains(query)) {
                Handwriting.Hit hit = !trash && Handwriting.enabled(this) ? Handwriting.find(e.dir, query) : null;
                if (hit == null) continue;
                hits.put(e.dir.getPath(), hit); // found in the handwriting
            }
            shown.add(e);
        }
        if (!trash && sort == 1) shown.sort(Comparator.comparing((Notebook.Entry e) -> e.title.toLowerCase()));
        else if (!trash && sort == 2) shown.sort(Comparator.comparingLong((Notebook.Entry e) -> e.created).reversed());
        pageTitle.setText(filter.isEmpty() ? "All notes" : filter.equals(FAV) ? "Favourites" : filter.equals(RECENT) ? "Recent"
                : filter.equals(UNFILED) ? "Unfiled" : trash ? "Trash" : leaf(filter));
        ArrayList<String> subs = isFolderFilter() && query.isEmpty() ? childrenOf(folders(), filter) : new ArrayList<>();
        // meta line: what's here · when it changed · how it's sorted (tap to change)
        StringBuilder m = new StringBuilder();
        if (!subs.isEmpty()) m.append(subs.size()).append(subs.size() == 1 ? " folder · " : " folders · ");
        m.append(shown.size()).append(shown.size() == 1 ? " notebook" : " notebooks");
        if (trash) m.append(" · kept for 30 days");
        else if (filter.equals(RECENT)) m.append(" · last 7 days");
        subtitle.setText(m);
        renderViewControls(trash);
        if (trash) {
            sortLabel.setText(shown.isEmpty() ? "" : "  ·  Empty trash");
            sortLabel.setTextColor(Ui.color(this, R.color.accent));
            sortLabel.setOnClickListener(shown.isEmpty() ? null : v -> emptyTrash());
        } else {
            sortLabel.setText("");
            sortLabel.setOnClickListener(null);
        }
        renderCrumbs();
        folderGrid.removeAllViews();
        for (String f : subs) folderGrid.addView(folderCard(f));
        folderGrid.setVisibility(subs.isEmpty() ? View.GONE : View.VISIBLE);
        ((LinearLayout.LayoutParams) grid.getLayoutParams()).topMargin = Ui.dp(this, subs.isEmpty() ? 26 : 22);
        empty.setText(trash ? "Nothing in the trash. Deleted notebooks wait here for 30 days."
                : all.isEmpty() ? "No notebooks yet. Tap New to start writing."
                : !query.isEmpty() ? "Nothing matches “" + query + "”" : filter.equals(FAV) ? "Long-press a notebook to add it to favourites" : "Nothing here yet");
        empty.setVisibility(shown.isEmpty() && subs.isEmpty() ? View.VISIBLE : View.GONE);
        for (Notebook.Entry e : shown) grid.addView(card(e, trash));
    }

    private static String when(long t) { return when(t, false); }

    /** short: "5 min. ago" instead of "5 minutes ago" (narrow cards). */
    private static String when(long t, boolean shortForm) {
        long now = System.currentTimeMillis();
        return now - t < 60_000 ? "Just now" : DateUtils.getRelativeTimeSpanString(t, now, DateUtils.MINUTE_IN_MILLIS,
                shortForm ? DateUtils.FORMAT_ABBREV_RELATIVE : 0).toString();
    }

    private void emptyTrash() {
        new AlertDialog.Builder(this).setTitle("Empty the trash?")
                .setMessage("Notebooks in the trash will be deleted for good.")
                .setPositiveButton("Delete all", (d, w) -> { for (Notebook.Entry e : Notebook.listTrash(this)) Notebook.delete(e.dir); refresh(); })
                .setNegativeButton("Cancel", null).show();
    }

    /** "All notes › School ›" above a folder's title; each part opens that level. */
    private void renderCrumbs() {
        crumbs.removeAllViews();
        if (!isFolderFilter()) { crumbs.setVisibility(View.GONE); return; }
        crumbs.setVisibility(View.VISIBLE);
        ArrayList<String> levels = new ArrayList<>();
        levels.add("");
        for (String a = parentOf(filter); !a.isEmpty(); a = parentOf(a)) levels.add(1, a);
        for (String lv : levels) {
            TextView t = Ui.text(this, lv.isEmpty() ? "All notes" : leaf(lv), 13.5f, Ui.color(this, R.color.accent), Ui.medium());
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0);
            t.setBackground(Ui.ripple(this, null, Ui.dp(this, 6)));
            t.setOnClickListener(v -> setFilter(lv));
            crumbs.addView(t, new LinearLayout.LayoutParams(-2, -1));
            ImageView chev = new ImageView(this);
            chev.setImageResource(R.drawable.ic_chevron);
            chev.setImageTintList(ColorStateList.valueOf(Ui.color(this, R.color.on_surface_variant)));
            crumbs.addView(chev, new LinearLayout.LayoutParams(Ui.dp(this, 14), Ui.dp(this, 14)));
        }
    }

    /** A subfolder: name and what's inside, on a quiet card. Long-press for folder actions. */
    private View folderCard(String f) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 8), 0);
        GradientDrawable g = Ui.round(Ui.color(this, R.color.surface), Ui.dp(this, 16));
        g.setStroke(Ui.hairline(this), Ui.color(this, R.color.outline));
        row.setBackground(Ui.ripple(this, g, Ui.dp(this, 16)));
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        TextView t = Ui.text(this, leaf(f), 15.5f, Ui.color(this, R.color.on_surface), Ui.bold());
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        txt.addView(t);
        int nb = 0;
        for (Notebook.Entry e : all) if (within(e.folder, f)) nb++;
        int sub = childrenOf(folders(), f).size();
        TextView m = Ui.text(this, (sub > 0 ? sub + (sub == 1 ? " folder · " : " folders · ") : "") + nb + (nb == 1 ? " notebook" : " notebooks"),
                12.5f, Ui.color(this, R.color.on_surface_variant), null);
        m.setSingleLine(true);
        m.setPadding(0, Ui.dp(this, 4), 0, 0);
        txt.addView(m);
        row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
        Ui.IconButton more = new Ui.IconButton(this, R.drawable.ic_more, "Folder actions");
        more.setOnClickListener(v -> folderMenu(v, f));
        row.addView(more, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
        row.setOnClickListener(v -> setFilter(f));
        row.setOnLongClickListener(v -> { folderMenu(v, f); return true; });
        row.setLayoutParams(new android.view.ViewGroup.LayoutParams(-1, Ui.dp(this, 70)));
        row.setMinimumHeight(Ui.dp(this, 70));
        return row;
    }

    private int cardSize() { return Math.max(0, Math.min(2, prefs.getInt("cardSize", 1))); }

    /** Right of the title: a sort button and a three-way card size switch (hidden in the trash). */
    private void renderViewControls(boolean trash) {
        viewControls.removeAllViews();
        if (trash) return;
        Ui.IconButton sortB = new Ui.IconButton(this, R.drawable.ic_sort, "Sort");
        sortB.setOnClickListener(this::showSort);
        viewControls.addView(sortB, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        LinearLayout seg = new LinearLayout(this);
        seg.setPadding(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
        seg.setBackground(Ui.round(Ui.color(this, R.color.surface_variant), Ui.dp(this, 12)));
        int[] icons = {R.drawable.ic_size_s, R.drawable.ic_size_m, R.drawable.ic_size_l};
        String[] desc = {"Small notebooks", "Medium notebooks", "Large notebooks"};
        int cur = cardSize();
        for (int i = 0; i < 3; i++) {
            final int k = i;
            ImageView b = new ImageView(this);
            b.setImageResource(icons[i]);
            b.setContentDescription(desc[i]);
            b.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int pd = Ui.dp(this, 8);
            b.setPadding(pd, pd, pd, pd);
            boolean on = i == cur;
            b.setImageTintList(ColorStateList.valueOf(Ui.color(this, on ? R.color.on_surface : R.color.on_surface_variant)));
            if (on) { b.setBackground(Ui.round(Ui.color(this, R.color.surface), Ui.dp(this, 9))); b.setElevation(Ui.dp(this, 1)); }
            b.setOnClickListener(v -> {
                if (k == cardSize()) return;
                prefs.edit().putInt("cardSize", k).apply();
                grid.setMinCol(CARD_SIZES[k]);
                grid.setGap(k == 0 ? 14 : 20);
                fillGrid(); // titles size with the cards
            });
            seg.addView(b, new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 32)));
        }
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.leftMargin = Ui.dp(this, 6);
        viewControls.addView(seg, sl);
    }

    /** Spine colour of a notebook: steady per notebook, from the accent palette (covers will replace it in 1.4). */
    private int spineColor(Notebook.Entry e) {
        int i = Math.floorMod(e.dir.getName().hashCode(), Theme.ACC_LIGHT.length);
        return (Ui.dark(this) ? Theme.ACC_DARK : Theme.ACC_LIGHT)[i];
    }

    /** A notebook: its real first page with a coloured spine, then title, when, and what's in it. */
    private View card(Notebook.Entry e, boolean trashed) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        Ui.Aspect frame = new Ui.Aspect(this, Notebook.PH / Notebook.PW);
        frame.setBackground(Ui.round(Ui.color(this, R.color.surface), Ui.dp(this, 10)));
        frame.setClipToOutline(true);
        frame.setElevation(Ui.dp(this, 2.5f));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(img, new FrameLayout.LayoutParams(-1, -1));
        File thumb = new File(e.dir, "thumb.png");
        if (e.cover != null) { // a cover instead of the first page
            final String cv = e.cover, title = e.title;
            bg.execute(() -> {
                Bitmap bm = Covers.bitmap(this, cv, title, 420);
                ui.post(() -> img.setImageBitmap(bm));
            });
        } else if (thumb.exists()) {
            bg.execute(() -> {
                Bitmap bm = BitmapFactory.decodeFile(thumb.getPath());
                ui.post(() -> {
                    img.setImageBitmap(bm);
                    // canvases keep their own shape (that's what makes the flow uneven, like real pages)
                    if (bm != null && e.canvas && bm.getWidth() > 0) frame.setRatio(Math.max(0.55f, Math.min(1.6f, bm.getHeight() / (float) bm.getWidth())));
                });
            });
        }
        if (e.cover == null) { // a cover has its own spine
            View spine = new View(this);
            spine.setBackgroundColor(spineColor(e));
            frame.addView(spine, new FrameLayout.LayoutParams(Ui.dp(this, 6), -1, Gravity.START));
        }
        if (e.favorite && !trashed) {
            ImageView star = new ImageView(this);
            star.setImageResource(R.drawable.ic_star_filled);
            star.setImageTintList(ColorStateList.valueOf(0xfff2b705));
            star.setPadding(Ui.dp(this, 5), Ui.dp(this, 5), Ui.dp(this, 5), Ui.dp(this, 5));
            star.setBackground(Ui.round(0xf2ffffff, Ui.dp(this, 8)));
            FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26), Gravity.TOP | Gravity.END);
            slp.topMargin = slp.rightMargin = Ui.dp(this, 8);
            frame.addView(star, slp);
        }
        if (trashed) frame.setAlpha(0.7f);
        View ripple = new View(this);
        ripple.setBackground(Ui.ripple(this, null, Ui.dp(this, 10)));
        frame.addView(ripple, new FrameLayout.LayoutParams(-1, -1));
        Handwriting.Hit hit = hits.get(e.dir.getPath());
        String key = e.dir.getPath();
        if (selecting) {
            CheckDot dot = new CheckDot(this, picked.contains(key));
            FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28), Gravity.TOP | Gravity.START);
            dl.topMargin = Ui.dp(this, 10);
            dl.leftMargin = Ui.dp(this, 14);
            frame.addView(dot, frame.getChildCount() - 1, dl);
            checks.put(key, dot);
            View.OnClickListener tog = v -> togglePick(key);
            ripple.setOnClickListener(tog);
            ripple.setOnLongClickListener(v -> { togglePick(key); return true; });
        } else if (trashed) {
            ripple.setOnClickListener(v -> trashMenu(v, e));
            ripple.setOnLongClickListener(v -> { startSelecting(e); return true; });
        } else {
            ripple.setOnClickListener(v -> open(e.dir, hit != null ? hit.page : 0));
            ripple.setOnLongClickListener(v -> { startSelecting(e); return true; }); // hold to select
        }
        card.addView(frame);

        LinearLayout tr = new LinearLayout(this);
        tr.setGravity(Gravity.CENTER_VERTICAL);
        boolean small = cardSize() == 0;
        TextView t = Ui.text(this, e.title, small ? 13 : 15, Ui.color(this, R.color.on_surface), Ui.bold());
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tr.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView w = Ui.text(this, trashed ? daysLeft(e) : when(e.modified), small ? 11 : 12.5f, Ui.color(this, R.color.on_surface_variant), null);
        boolean dateBelow = compact && cardSize() == 1 && !trashed; // phone, two narrow columns: the date moves to the line below
        if ((small && !trashed) || dateBelow) w.setVisibility(View.GONE); // room for the title
        w.setPadding(Ui.dp(this, 8), 0, 0, 0);
        tr.addView(w);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = Ui.dp(this, 11);
        card.addView(tr, tl);
        String meta = e.canvas ? "Canvas" : e.pages + (e.pages == 1 ? " page" : " pages");
        if (!e.folder.isEmpty() && !filter.equals(e.folder)) meta = (isFolderFilter() ? e.folder.substring(Math.min(e.folder.length(), filter.length() + 1)) : leaf(e.folder)).replace("/", " › ") + " · " + meta;
        if (dateBelow) meta = meta + " · " + when(e.modified, true);
        if (hit != null) meta = "p. " + (hit.page + 1) + " · " + hit.snippet; // matched in the handwriting
        TextView mt = Ui.text(this, meta, small ? 11.5f : 12.5f, Ui.color(this, R.color.on_surface_variant), null);
        mt.setSingleLine(true);
        mt.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        mlp.topMargin = Ui.dp(this, 4);
        card.addView(mt, mlp);
        return card;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (selecting) { stopSelecting(); return; }
        super.onBackPressed();
    }

    // ---- selecting several notebooks ---------------------------------------------------------

    private void startSelecting(Notebook.Entry first) {
        moreB = null;
        selecting = true;
        picked.clear();
        if (first != null) picked.add(first.dir.getPath());
        buildSelRow();
        topRow.setVisibility(View.GONE);
        selRow.setVisibility(View.VISIBLE);
        fillGrid();
        updatePicked();
    }

    private void stopSelecting() {
        selecting = false;
        picked.clear();
        selRow.setVisibility(View.GONE);
        topRow.setVisibility(View.VISIBLE);
        fillGrid();
    }

    private void togglePick(String key) {
        if (!picked.remove(key)) picked.add(key);
        View d = checks.get(key);
        if (d instanceof CheckDot) ((CheckDot) d).set(picked.contains(key));
        updatePicked();
    }

    private void updatePicked() {
        if (pickedLabel != null) pickedLabel.setText(picked.isEmpty() ? (compact ? "" : "Select notebooks") : compact ? String.valueOf(picked.size()) : picked.size() + " selected");
        if (moreB != null) moreB.setVisibility(picked.size() == 1 && !filter.equals(TRASH) ? View.VISIBLE : View.GONE);
        for (int i = 0; i < selRow.getChildCount(); i++) {
            View v = selRow.getChildAt(i);
            if (v.getTag() != null) { v.setEnabled(!picked.isEmpty()); v.setAlpha(picked.isEmpty() ? 0.4f : 1f); }
        }
    }

    private List<Notebook.Entry> pickedEntries() {
        ArrayList<Notebook.Entry> out = new ArrayList<>();
        for (Notebook.Entry e : filter.equals(TRASH) ? Notebook.listTrash(this) : all) if (picked.contains(e.dir.getPath())) out.add(e);
        return out;
    }

    /** An icon action in the selection bar; its name shows on long-press (and to screen readers). */
    private View selButton(int icon, String label, View.OnClickListener l) {
        ImageView b = new ImageView(this);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(Ui.color(this, R.color.icon)));
        b.setContentDescription(label);
        b.setTooltipText(label);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int p = Ui.dp(this, 10);
        b.setPadding(p, p, p, p);
        b.setBackground(Ui.ripple(this, null, Ui.dp(this, 20)));
        b.setTag("act");
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
        return b;
    }

    private void buildSelRow() {
        selRow.removeAllViews();
        TextView done = Ui.text(this, "Done", 15, Ui.color(this, R.color.accent), Ui.bold());
        done.setGravity(Gravity.CENTER_VERTICAL);
        done.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 14), 0);
        done.setOnClickListener(v -> stopSelecting());
        selRow.addView(done, new LinearLayout.LayoutParams(-2, -1));
        pickedLabel = Ui.text(this, "", 15, Ui.color(this, R.color.on_surface), Ui.bold());
        pickedLabel.setGravity(Gravity.CENTER_VERTICAL);
        pickedLabel.setSingleLine(true);
        pickedLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        selRow.addView(pickedLabel, new LinearLayout.LayoutParams(0, -1, 1f));
        TextView allB = Ui.text(this, "All", 14.5f, Ui.color(this, R.color.accent), Ui.medium());
        allB.setGravity(Gravity.CENTER_VERTICAL);
        allB.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        allB.setOnClickListener(v -> { // select everything shown (or clear if all are)
            ArrayList<String> shown = new ArrayList<>(checks.keySet());
            boolean everyone = picked.containsAll(shown);
            for (String k : shown) { if (everyone) picked.remove(k); else picked.add(k); View d = checks.get(k); if (d instanceof CheckDot) ((CheckDot) d).set(!everyone); }
            updatePicked();
        });
        selRow.addView(allB, new LinearLayout.LayoutParams(-2, -1));
        if (filter.equals(TRASH)) {
            selRow.addView(selButton(R.drawable.ic_undo, "Restore", v -> {
                int n = 0;
                for (Notebook.Entry e : pickedEntries()) if (Notebook.restore(this, e.dir) != null) n++;
                Toast.makeText(this, "Restored " + n, Toast.LENGTH_SHORT).show();
                stopSelecting(); refresh();
            }));
            selRow.addView(selButton(R.drawable.ic_trash, "Delete", v -> {
                List<Notebook.Entry> es = pickedEntries();
                new AlertDialog.Builder(this).setTitle("Delete " + es.size() + (es.size() == 1 ? " notebook" : " notebooks") + " for good?")
                        .setMessage("This can't be undone.")
                        .setPositiveButton("Delete", (d, w) -> { for (Notebook.Entry e : es) Notebook.delete(e.dir); stopSelecting(); refresh(); })
                        .setNegativeButton("Cancel", null).show();
            }));
            return;
        }
        selRow.addView(selButton(R.drawable.ic_folder, "Move", this::movePicked));
        selRow.addView(selButton(R.drawable.ic_star, "Favourite", v -> {
            List<Notebook.Entry> es = pickedEntries();
            boolean allFav = true;
            for (Notebook.Entry e : es) allFav &= e.favorite;
            for (Notebook.Entry e : es) try { Notebook.setMeta(e.dir, "favorite", Boolean.toString(!allFav)); } catch (Exception ignored) { }
            Toast.makeText(this, allFav ? "Removed from favourites" : "Added to favourites", Toast.LENGTH_SHORT).show();
            stopSelecting(); refresh();
        }));
        selRow.addView(selButton(R.drawable.ic_share, "Export", v -> exportPicked()));
        moreB = selButton(R.drawable.ic_more, "More for this notebook", v -> {
            List<Notebook.Entry> es = pickedEntries();
            if (es.size() == 1) menu(v, es.get(0));
        });
        selRow.addView(moreB);
        selRow.addView(selButton(R.drawable.ic_trash, "Delete", v -> {
            int n = 0;
            for (Notebook.Entry e : pickedEntries()) if (Notebook.trash(this, e.dir)) n++;
            Toast.makeText(this, "Moved " + n + " to the trash · kept 30 days", Toast.LENGTH_SHORT).show();
            stopSelecting(); refresh();
        }));
    }

    private void movePicked(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        m.add(0, 1, 0, "No folder");
        ArrayList<String> fl = folders();
        for (int i = 0; i < fl.size(); i++) m.add(0, 100 + i, i + 1, "\u2003".repeat(depth(fl.get(i))) + leaf(fl.get(i)));
        pm.setOnMenuItemClickListener(it -> {
            String to = it.getItemId() == 1 ? "" : fl.get(it.getItemId() - 100);
            List<Notebook.Entry> es = pickedEntries();
            for (Notebook.Entry e : es) try { Notebook.setMeta(e.dir, "folder", to); } catch (Exception ignored) { }
            Toast.makeText(this, "Moved " + es.size() + " to " + (to.isEmpty() ? "no folder" : leaf(to)), Toast.LENGTH_SHORT).show();
            stopSelecting(); refresh();
            return true;
        });
        pm.show();
    }

    private void exportPicked() {
        List<Notebook.Entry> es = pickedEntries();
        Toast.makeText(this, "Exporting " + es.size() + "…", Toast.LENGTH_SHORT).show();
        bg.execute(() -> {
            ArrayList<File> files = new ArrayList<>();
            for (Notebook.Entry e : es) {
                try { files.add(Exporter.pdf(this, Notebook.load(e.dir), e.dir, -1)); } catch (Exception ex) { Log.e(Gl.TAG, "export", ex); }
            }
            ui.post(() -> {
                if (files.isEmpty()) { Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show(); return; }
                Exporter.shareMany(this, files, "application/pdf");
                stopSelecting();
            });
        });
    }

    /** Round tick in a card's corner while selecting. */
    private static final class CheckDot extends View {
        private boolean on;
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        CheckDot(android.content.Context c, boolean on) { super(c); this.on = on; }
        void set(boolean v) { on = v; invalidate(); }
        @Override protected void onDraw(android.graphics.Canvas cv) {
            float w = getWidth(), cx = w / 2, r = w / 2 - Ui.dp(getContext(), 1.5f);
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor(on ? Ui.color(getContext(), R.color.accent) : 0x99FFFFFF);
            cv.drawCircle(cx, cx, r, p);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dp(getContext(), 1.8f));
            p.setColor(on ? Ui.color(getContext(), R.color.accent) : 0xFF8A857D);
            cv.drawCircle(cx, cx, r, p);
            if (on) {
                p.setColor(Ui.color(getContext(), R.color.on_accent));
                p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
                android.graphics.Path t = new android.graphics.Path();
                t.moveTo(cx - r * 0.42f, cx + r * 0.02f);
                t.lineTo(cx - r * 0.1f, cx + r * 0.36f);
                t.lineTo(cx + r * 0.45f, cx - r * 0.3f);
                cv.drawPath(t, p);
            }
        }
    }

    private static String daysLeft(Notebook.Entry e) {
        long left = Notebook.TRASH_KEEP_MS - (System.currentTimeMillis() - e.trashedAt);
        int d = (int) Math.max(0, Math.ceil(left / 86400_000.0));
        return d <= 1 ? "last day" : d + " days left";
    }

    private void trashMenu(View anchor, Notebook.Entry e) {
        PopupMenu pm = new PopupMenu(this, anchor, Gravity.CENTER);
        pm.getMenu().add(0, 1, 0, "Restore");
        pm.getMenu().add(0, 2, 1, "Delete for good");
        pm.setOnMenuItemClickListener(it -> {
            if (it.getItemId() == 1) {
                if (Notebook.restore(this, e.dir) == null) Toast.makeText(this, "Could not restore", Toast.LENGTH_SHORT).show();
                else Toast.makeText(this, "Restored “" + e.title + "”", Toast.LENGTH_SHORT).show();
                refresh();
            } else {
                new AlertDialog.Builder(this).setTitle("Delete “" + e.title + "” for good?")
                        .setMessage("This can't be undone.")
                        .setPositiveButton("Delete", (d, w) -> { Notebook.delete(e.dir); refresh(); })
                        .setNegativeButton("Cancel", null).show();
            }
            return true;
        });
        pm.show();
    }

    // ---- notebook actions --------------------------------------------------------------------

    private void menu(View anchor, Notebook.Entry e) {
        PopupMenu pm = new PopupMenu(this, anchor, Gravity.CENTER);
        Menu m = pm.getMenu();
        m.add(0, 1, 0, "Open");
        m.add(0, 7, 0, "Open in split view");
        m.add(0, 2, 1, "Rename");
        m.add(0, 8, 1, "Cover…");
        m.add(0, 3, 2, e.favorite ? "Remove from favourites" : "Add to favourites");
        SubMenu mv = m.addSubMenu(0, 0, 3, "Move to folder");
        mv.add(1, 10, 0, "No folder").setCheckable(true).setChecked(e.folder.isEmpty());
        ArrayList<String> fl = folders();
        for (int i = 0; i < fl.size(); i++)
            mv.add(1, 100 + i, i + 1, "\u2003".repeat(depth(fl.get(i))) + leaf(fl.get(i))).setCheckable(true).setChecked(fl.get(i).equals(e.folder));
        mv.add(2, 11, fl.size() + 1, isFolderFilter() ? "New subfolder in " + leaf(filter) + "…" : "New folder…");
        m.add(0, 4, 4, "Duplicate");
        m.add(0, 5, 5, "Export as PDF");
        m.add(0, 6, 6, "Move to trash");
        pm.setOnMenuItemClickListener(it -> {
            int id = it.getItemId();
            try {
                if (selecting) stopSelecting();
                if (id == 1) open(e.dir);
                else if (id == 7) {
                    prefs.edit().putString("lastDir", e.dir.getPath()).apply();
                    startActivity(new Intent(this, EditorActivity.class).putExtra("dir", e.dir.getPath()).putExtra("split", true));
                }
                else if (id == 2) rename(e);
                else if (id == 8) Covers.showPicker(this, anchor, e.cover, e.title, cv -> {
                    try { Notebook.setCover(e.dir, cv); } catch (IOException ex) { Toast.makeText(this, "Couldn't change the cover", Toast.LENGTH_SHORT).show(); }
                    e.cover = cv;
                    refresh();
                });
                else if (id == 3) { Notebook.setMeta(e.dir, "favorite", Boolean.toString(!e.favorite)); refresh(); }
                else if (id == 10) { Notebook.setMeta(e.dir, "folder", ""); refresh(); }
                else if (id >= 100) { Notebook.setMeta(e.dir, "folder", fl.get(id - 100)); refresh(); }
                else if (id == 11) newFolder(isFolderFilter() ? filter : "", e);
                else if (id == 4) duplicate(e);
                else if (id == 5) exportPdf(e);
                else if (id == 6) confirmDelete(e);
                else return false;
            } catch (Exception ex) {
                Toast.makeText(this, "Something went wrong", Toast.LENGTH_SHORT).show();
            }
            return true;
        });
        pm.show();
    }

    private EditText field(String text, String hint) {
        EditText et = new EditText(this);
        et.setText(text);
        et.setHint(hint);
        et.setSelectAllOnFocus(true);
        et.setSingleLine(true);
        return et;
    }

    private View wrap(View v) {
        FrameLayout w = new FrameLayout(this);
        int p = Ui.dp(this, 22);
        w.setPadding(p, Ui.dp(this, 8), p, 0);
        w.addView(v);
        return w;
    }

    private void rename(Notebook.Entry e) {
        EditText et = field(e.title, "Title");
        new AlertDialog.Builder(this).setTitle("Rename").setView(wrap(et))
                .setPositiveButton("Save", (d, w) -> {
                    String s = et.getText().toString().trim();
                    if (s.isEmpty()) return;
                    try { Notebook.setMeta(e.dir, "title", s); } catch (Exception ignored) {}
                    refresh();
                })
                .setNegativeButton("Cancel", null).show();
        et.requestFocus();
    }

    private void newFolder(Notebook.Entry e) { newFolder("", e); }

    /** Creates a folder inside parent ("" = top level); if e is given, moves that notebook into it. */
    private void newFolder(String parent, Notebook.Entry e) {
        EditText et = field("", "Folder name");
        new AlertDialog.Builder(this).setTitle(parent.isEmpty() ? "New folder" : "New subfolder in " + leaf(parent)).setView(wrap(et))
                .setPositiveButton("Create", (d, w) -> {
                    String s = cleanName(et.getText().toString());
                    if (s.isEmpty()) return;
                    String path = parent.isEmpty() ? s : parent + "/" + s;
                    HashSet<String> set = new HashSet<>(prefs.getStringSet("folders", new HashSet<>()));
                    set.add(path);
                    HashSet<String> open = openFolders();
                    if (!parent.isEmpty()) open.add(parent);
                    prefs.edit().putStringSet("folders", set).putStringSet("openFolders", open).apply();
                    try { if (e != null) Notebook.setMeta(e.dir, "folder", path); } catch (Exception ignored) {}
                    if (e == null) filter = path;
                    refresh();
                })
                .setNegativeButton("Cancel", null).show();
        et.requestFocus();
    }

    /** Folder names can't contain the path separator or the list markers. */
    private static String cleanName(String s) { return s.replace("/", "-").replace(FAV, "").replace(RECENT, "").trim(); }

    private void folderMenu(View anchor, String f) {
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        m.add(0, 3, 0, "Add subfolder");
        m.add(0, 1, 1, "Rename folder");
        SubMenu mv = m.addSubMenu(0, 0, 2, "Move folder to");
        ArrayList<String> targets = new ArrayList<>();
        if (!parentOf(f).isEmpty()) { targets.add(""); mv.add(1, 100, 0, "Top level"); }
        for (String t : folders()) {
            if (within(t, f) || t.equals(parentOf(f))) continue; // not into itself, not where it already is
            mv.add(1, 100 + targets.size(), targets.size(), "\u2003".repeat(depth(t)) + leaf(t));
            targets.add(t);
        }
        if (targets.isEmpty()) mv.add(1, 99, 0, "No other folders").setEnabled(false);
        m.add(0, 2, 3, "Remove folder (keeps notebooks)");
        pm.setOnMenuItemClickListener(it -> {
            int id = it.getItemId();
            if (id == 3) newFolder(f, null);
            else if (id == 1) {
                EditText et = field(leaf(f), "Folder name");
                new AlertDialog.Builder(this).setTitle("Rename folder").setView(wrap(et))
                        .setPositiveButton("Save", (d, w) -> {
                            String s = cleanName(et.getText().toString());
                            if (s.isEmpty()) return;
                            String p = parentOf(f);
                            moveFolder(f, p.isEmpty() ? s : p + "/" + s);
                        }).setNegativeButton("Cancel", null).show();
            } else if (id >= 100) {
                String to = targets.get(id - 100);
                moveFolder(f, to.isEmpty() ? leaf(f) : to + "/" + leaf(f));
            } else if (id == 2) {
                int n = 0;
                for (Notebook.Entry e : all) if (within(e.folder, f)) n++;
                String where = parentOf(f).isEmpty() ? "No folder" : leaf(parentOf(f));
                new AlertDialog.Builder(this).setTitle("Remove “" + leaf(f) + "”?")
                        .setMessage(n == 0 ? "The folder is empty." : "Its " + n + (n == 1 ? " notebook moves" : " notebooks move")
                                + " to " + where + ", keeping any subfolders. Nothing is deleted.")
                        .setPositiveButton("Remove", (d, w) -> removeFolder(f))
                        .setNegativeButton("Cancel", null).show();
            } else return false;
            return true;
        });
        pm.show();
    }

    /** Renames / moves folder `from` (and everything inside it) to path `to`. */
    private void moveFolder(String from, String to) {
        if (to.equals(from)) return;
        if (folders().contains(to)) { Toast.makeText(this, "There's already a folder called " + leaf(to) + " there", Toast.LENGTH_SHORT).show(); return; }
        for (Notebook.Entry e : all) if (within(e.folder, from)) {
            try { Notebook.setMeta(e.dir, "folder", to + e.folder.substring(from.length())); } catch (Exception ignored) {}
        }
        prefs.edit().putStringSet("folders", repath(prefs.getStringSet("folders", new HashSet<>()), from, to))
                .putStringSet("openFolders", repath(openFolders(), from, to)).apply();
        if (isFolderFilter() && within(filter, from)) filter = to + filter.substring(from.length());
        prefs.edit().putString("filter", filter).apply();
        refresh();
    }

    private static HashSet<String> repath(java.util.Set<String> in, String from, String to) {
        HashSet<String> out = new HashSet<>();
        for (String s : in) out.add(within(s, from) ? to + s.substring(from.length()) : s);
        return out;
    }

    /** Removes folder f: its notebooks and subfolders move up one level. */
    private void removeFolder(String f) {
        String up = parentOf(f);
        for (Notebook.Entry e : all) if (within(e.folder, f)) {
            String rest = e.folder.substring(f.length()); // "" or "/Sub/..."
            String np = rest.isEmpty() ? up : (up.isEmpty() ? rest.substring(1) : up + rest);
            try { Notebook.setMeta(e.dir, "folder", np); } catch (Exception ignored) {}
        }
        HashSet<String> set = new HashSet<>();
        for (String s : prefs.getStringSet("folders", new HashSet<>())) {
            if (s.equals(f)) continue;
            if (within(s, f)) { String rest = s.substring(f.length() + 1); s = up.isEmpty() ? rest : up + "/" + rest; }
            set.add(s);
        }
        if (!up.isEmpty()) set.add(up); // the parent stays even if it's now empty
        prefs.edit().putStringSet("folders", set).apply();
        if (isFolderFilter() && within(filter, f)) filter = up;
        prefs.edit().putString("filter", filter).apply();
        refresh();
    }

    private void duplicate(Notebook.Entry e) {
        bg.execute(() -> {
            try {
                File d = Notebook.newDir(this);
                Notebook.copyDir(e.dir, d);
                Notebook.setMeta(d, "title", e.title + " copy");
                Notebook.setMeta(d, "created", Long.toString(System.currentTimeMillis()));
                Notebook.setMeta(d, "modified", Long.toString(System.currentTimeMillis()));
                ui.post(this::refresh);
            } catch (Exception ex) {
                ui.post(() -> Toast.makeText(this, "Couldn't duplicate", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void exportPdf(Notebook.Entry e) {
        Toast.makeText(this, "Exporting…", Toast.LENGTH_SHORT).show();
        bg.execute(() -> {
            try {
                File f = Exporter.pdf(this, Notebook.load(e.dir), e.dir, -1);
                ui.post(() -> Exporter.share(this, f, "application/pdf"));
            } catch (Exception ex) {
                Log.e(Gl.TAG, "export", ex);
                ui.post(() -> Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show());
            }
        });
    }

    /** Delete = move to the trash (30 days); real deletion happens from the trash. */
    private void confirmDelete(Notebook.Entry e) {
        if (Notebook.trash(this, e.dir)) Toast.makeText(this, "Moved to the trash · kept 30 days", Toast.LENGTH_SHORT).show();
        else Toast.makeText(this, "Could not delete", Toast.LENGTH_SHORT).show();
        refresh();
    }

    private void open(File dir) { open(dir, 0); }

    private void open(File dir, int page) {
        prefs.edit().putString("lastDir", dir.getPath()).apply();
        startActivity(new Intent(this, EditorActivity.class).putExtra("dir", dir.getPath()).putExtra("page", page));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    // ---- overflow: sort + backup -------------------------------------------------------------

    private void showMore(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        m.add(0, 30, 0, "Theme…");
        boolean has = Backup.tree(this) != null;
        m.add(0, 20, 1, has ? "Back up now" : "Set up backup…");
        if (has) m.add(0, 21, 2, "Change backup folder…");
        m.add(0, 22, 3, "Restore from backup…");
        long last = prefs.getLong(Backup.PREF_LAST, 0);
        if (last > 0) m.add(0, 23, 4, "Last backup " + DateUtils.getRelativeTimeSpanString(last)).setEnabled(false);
        pm.setOnMenuItemClickListener(it -> {
            int id = it.getItemId();
            if (id == 30) {
                ThemeSheet.show(this, this::recreate);
            } else if (id == 20 && has) {
                backupNow();
            } else if (id == 20 || id == 21) {
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE);
            } else if (id == 22) {
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/zip"), REQ_RESTORE);
            } else return false;
            return true;
        });
        pm.show();
    }

    private void backupNow() {
        Toast.makeText(this, "Backing up…", Toast.LENGTH_SHORT).show();
        bg.execute(() -> {
            try {
                String name = Backup.run(this);
                ui.post(() -> Toast.makeText(this, name == null ? "A backup is already running" : "Saved " + name, Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                Log.e(Gl.TAG, "backup", e);
                ui.post(() -> Toast.makeText(this, "Backup failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (req == REQ_PDF) importPdf(uri);
        else if (req == REQ_TREE) {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            prefs.edit().putString(Backup.PREF_TREE, uri.toString()).apply();
            backupNow();
        } else if (req == REQ_RESTORE) {
            bg.execute(() -> {
                try {
                    int n = Backup.restore(this, uri);
                    ui.post(() -> { Toast.makeText(this, "Restored " + n + " notebooks", Toast.LENGTH_LONG).show(); refresh(); });
                } catch (Exception e) {
                    ui.post(() -> Toast.makeText(this, "Restore failed", Toast.LENGTH_LONG).show());
                }
            });
        }
    }

    private void importPdf(Uri uri) {
        Toast.makeText(this, "Importing PDF…", Toast.LENGTH_SHORT).show();
        bg.execute(() -> {
            File dir = Notebook.newDir(this);
            try {
                String name = "Imported PDF";
                try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (c != null && c.moveToFirst() && c.getString(0) != null) name = c.getString(0).replaceAll("(?i)\\.pdf$", "");
                }
                dir.mkdirs();
                File pdf = new File(dir, "source.pdf");
                Io.copy(getContentResolver().openInputStream(uri), new FileOutputStream(pdf));
                Notebook nb = new Notebook();
                nb.title = name;
                nb.folder = filter.equals(FAV) || filter.equals(RECENT) ? "" : filter;
                nb.created = nb.modified = System.currentTimeMillis();
                try (Render.Pdf r = new Render.Pdf(pdf)) {
                    for (int i = 0; i < r.r.getPageCount(); i++) {
                        try (PdfRenderer.Page p = r.r.openPage(i)) {
                            Notebook.Page pg = new Notebook.Page(Notebook.BG_BLANK);
                            pg.pdf = i;
                            pg.h = Notebook.PW * p.getHeight() / (float) p.getWidth();
                            nb.pages.add(pg);
                        }
                    }
                }
                if (nb.pages.isEmpty()) throw new IllegalStateException("empty PDF");
                nb.write(dir);
                ui.post(() -> { refresh(); open(dir); });
            } catch (Exception e) {
                Log.e(Gl.TAG, "pdf import", e);
                Notebook.delete(dir);
                ui.post(() -> Toast.makeText(this, "Couldn't open that PDF", Toast.LENGTH_LONG).show());
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bg.shutdown();
    }
}

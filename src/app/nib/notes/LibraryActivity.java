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
    private Ui.Grid grid;
    private TextView subtitle, pageTitle, empty;
    private LinearLayout crumbs;
    private Ui.Grid folderGrid;
    private View sidePanel, sideRule, navButton, searchBox, newCompact;
    private LinearLayout headerRow, searchRow;
    private static final String RECENT = "⏱";
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

        LinearLayout rootRow = new LinearLayout(this);
        rootRow.setBackgroundColor(Ui.color(this, R.color.bg));

        // ---- sidebar ----
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setBackgroundColor(Ui.color(this, R.color.surface_variant));
        side.setPadding(Ui.dp(this, 12), Ui.dp(this, 26), Ui.dp(this, 12), Ui.dp(this, 16));
        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setPadding(Ui.dp(this, 8), 0, 0, Ui.dp(this, 20));
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_pen);
        mark.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        mark.setPadding(Ui.dp(this, 7), Ui.dp(this, 7), Ui.dp(this, 7), Ui.dp(this, 7));
        mark.setImageTintList(ColorStateList.valueOf(Ui.color(this, R.color.bg)));
        mark.setBackground(Ui.round(Ui.color(this, R.color.on_surface), Ui.dp(this, 9)));
        brand.addView(mark, new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32)));
        TextView bt = Ui.text(this, "Nib", 19, Ui.color(this, R.color.on_surface), Ui.bold());
        bt.setPadding(Ui.dp(this, 10), 0, 0, 0);
        brand.addView(bt);
        side.addView(brand);
        TextView add = Ui.button(this, "New", R.drawable.ic_plus, true);
        add.setGravity(Gravity.CENTER_VERTICAL | Gravity.START); // icon and label together, like the rows below
        add.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        add.setCompoundDrawablePadding(Ui.dp(this, 12));
        add.setOnClickListener(this::showNew);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, Ui.dp(this, 42));
        al.bottomMargin = Ui.dp(this, 14);
        side.addView(add, al);
        ScrollView navScroll = new ScrollView(this);
        navScroll.setVerticalScrollBarEnabled(false);
        chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.VERTICAL);
        navScroll.addView(chips);
        side.addView(navScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        View settingsItem = navItem(R.drawable.ic_settings, "Settings & backup", -1, false, this::showMore);
        side.addView(settingsItem);
        rootRow.addView(side, new LinearLayout.LayoutParams(Ui.dp(this, 236), -1));
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
        int pad = Ui.dp(this, 26);
        col.setPadding(pad, Ui.dp(this, 26), pad, Ui.dp(this, 40));
        sv.addView(col);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Ui.IconButton nav = new Ui.IconButton(this, R.drawable.ic_sidebar, "Folders and lists");
        nav.setOnClickListener(this::showNav);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40));
        nl.rightMargin = Ui.dp(this, 8);
        header.addView(nav, nl);
        nav.setVisibility(View.GONE);
        navButton = nav;
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        pageTitle = Ui.text(this, "All notes", 24, Ui.color(this, R.color.on_surface), Ui.bold());
        pageTitle.setSingleLine(true);
        pageTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        subtitle = Ui.text(this, "", 12.5f, Ui.color(this, R.color.on_surface_variant), null);
        LinearLayout.LayoutParams stl = new LinearLayout.LayoutParams(-2, -2);
        stl.topMargin = Ui.dp(this, 3);
        crumbs = new LinearLayout(this);
        crumbs.setGravity(Gravity.CENTER_VERTICAL);
        crumbs.setVisibility(View.GONE);
        LinearLayout.LayoutParams cbl = new LinearLayout.LayoutParams(-2, Ui.dp(this, 24));
        cbl.bottomMargin = Ui.dp(this, 2);
        titles.addView(crumbs, cbl);
        titles.addView(pageTitle);
        titles.addView(subtitle, stl);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        EditText search = new EditText(this);
        search.setHint("Search titles & handwriting");
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setTextColor(Ui.color(this, R.color.on_surface));
        search.setHintTextColor(Ui.color(this, R.color.on_surface_variant));
        search.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0);
        search.setCompoundDrawableTintList(ColorStateList.valueOf(Ui.color(this, R.color.on_surface_variant)));
        search.setCompoundDrawablePadding(Ui.dp(this, 8));
        search.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 12), 0);
        search.setBackground(Ui.round(Ui.color(this, R.color.surface_variant), Ui.dp(this, 10)));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void afterTextChanged(Editable e) { query = e.toString().trim().toLowerCase(); fillGrid(); }
        });
        header.addView(search, new LinearLayout.LayoutParams(Ui.dp(this, 190), Ui.dp(this, 40)));
        searchBox = search;
        headerRow = header;
        Ui.IconButton sortB = new Ui.IconButton(this, R.drawable.ic_sort, "Sort");
        sortB.setOnClickListener(this::showSort);
        LinearLayout.LayoutParams sbl = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40));
        sbl.leftMargin = Ui.dp(this, 6);
        header.addView(sortB, sbl);
        Ui.IconButton newB = new Ui.IconButton(this, R.drawable.ic_plus, "New");
        newB.setOnClickListener(this::showNew);
        header.addView(newB, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        newB.setVisibility(View.GONE);
        newCompact = newB;
        col.addView(header);
        searchRow = new LinearLayout(this);
        LinearLayout.LayoutParams srl = new LinearLayout.LayoutParams(-1, -2);
        srl.topMargin = Ui.dp(this, 14);
        col.addView(searchRow, srl);
        searchRow.setVisibility(View.GONE);
        folderGrid = new Ui.Grid(this, 190, 12);
        LinearLayout.LayoutParams fgl = new LinearLayout.LayoutParams(-1, -2);
        fgl.topMargin = Ui.dp(this, 24);
        col.addView(folderGrid, fgl);
        grid = new Ui.Grid(this, 118, 18);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, -2);
        gl.topMargin = Ui.dp(this, 26);
        col.addView(grid, gl);
        empty = Ui.text(this, "", 14, Ui.color(this, R.color.on_surface_variant), null);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, Ui.dp(this, 80), 0, 0);
        col.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        rootRow.addView(sv, new LinearLayout.LayoutParams(0, -1, 1f));
        setContentView(rootRow);
        // narrow windows (split screen): the sidebar folds into a button next to the title
        rootRow.addOnLayoutChangeListener((v, l, t, r, b2, ol, ot, or, ob) -> {
            if (r - l == or - ol) return;
            boolean narrow = r - l < Ui.dp(this, 600);
            ui.post(() -> {
                sidePanel.setVisibility(narrow ? View.GONE : View.VISIBLE);
                sideRule.setVisibility(narrow ? View.GONE : View.VISIBLE);
                navButton.setVisibility(narrow ? View.VISIBLE : View.GONE);
                newCompact.setVisibility(narrow ? View.VISIBLE : View.GONE);
                // narrow: search gets its own full-width row under the title
                ((android.view.ViewGroup) searchBox.getParent()).removeView(searchBox);
                if (narrow) searchRow.addView(searchBox, new LinearLayout.LayoutParams(-1, Ui.dp(this, 40)));
                else headerRow.addView(searchBox, 2, new LinearLayout.LayoutParams(Ui.dp(this, 190), Ui.dp(this, 40)));
                searchRow.setVisibility(narrow ? View.VISIBLE : View.GONE);
            });
        });

        handleIncoming(getIntent());

        // dev convenience: `am start ... --ez reopen true` jumps back into the last notebook
        String last = prefs.getString("lastDir", null);
        if (getIntent().getBooleanExtra("reopen", false) && last != null && new File(last).isDirectory()) open(new File(last));
    }

    private void showNew(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "Notebook");
        pm.getMenu().add(0, 3, 1, "Infinite canvas");
        pm.getMenu().add(0, 2, 2, "Import PDF…");
        pm.setOnMenuItemClickListener(it -> {
            if (it.getItemId() == 1) newNotebook(false);
            else if (it.getItemId() == 3) newNotebook(true);
            else startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf"), REQ_PDF);
            return true;
        });
        pm.show();
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

    private boolean isFolderFilter() { return !filter.isEmpty() && !filter.equals(FAV) && !filter.equals(RECENT); }

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
        chips.removeAllViews();
        chips.addView(navItem(R.drawable.ic_book, "All notes", all.size(), filter.isEmpty(), v -> setFilter("")));
        chips.addView(navItem(R.drawable.ic_clock, "Recent", recent, filter.equals(RECENT), v -> setFilter(RECENT)));
        chips.addView(navItem(R.drawable.ic_star, "Favourites", favs, filter.equals(FAV), v -> setFilter(FAV)));
        TextView sect = Ui.text(this, "Folders", 12, Ui.color(this, R.color.on_surface_variant), Ui.medium());
        sect.setPadding(Ui.dp(this, 12), Ui.dp(this, 20), 0, Ui.dp(this, 6));
        chips.addView(sect);
        HashSet<String> open = openFolders();
        if (isFolderFilter()) for (String a = parentOf(filter); !a.isEmpty(); a = parentOf(a)) open.add(a); // show where you are
        for (String f : folders) {
            boolean visible = true;
            for (String a = parentOf(f); !a.isEmpty(); a = parentOf(a)) if (!open.contains(a)) { visible = false; break; }
            if (!visible) continue;
            int n = 0;
            for (Notebook.Entry e : all) if (within(e.folder, f)) n++;
            boolean kids = !childrenOf(folders, f).isEmpty();
            View c = folderNavItem(f, n, kids, open.contains(f), filter.equals(f));
            chips.addView(c);
        }
        View nf = navItem(R.drawable.ic_plus, "New folder", -1, false, v -> newFolder(null));
        nf.setAlpha(0.75f);
        chips.addView(nf);
        fillGrid();
    }

    private void setFilter(String f) {
        filter = f;
        prefs.edit().putString("filter", f).apply();
        refresh();
    }

    /** Sidebar folder row: indented by depth, with a chevron that opens / closes its subfolders. */
    private View folderNavItem(String f, int count, boolean kids, boolean open, boolean sel) {
        LinearLayout row = (LinearLayout) navItem(R.drawable.ic_folder, leaf(f), count, sel, v -> setFilter(f));
        row.setOnLongClickListener(v -> { folderMenu(v, f); return true; });
        int d = depth(f);
        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chevron);
        chev.setImageTintList(ColorStateList.valueOf(Ui.color(this, R.color.on_surface_variant)));
        chev.setScaleType(ImageView.ScaleType.FIT_CENTER);
        chev.setPadding(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
        chev.setRotation(open ? 90 : 0);
        chev.setVisibility(kids ? View.VISIBLE : View.INVISIBLE);
        chev.setContentDescription(open ? "Hide subfolders" : "Show subfolders");
        chev.setBackground(Ui.ripple(this, null, Ui.dp(this, 10)));
        if (kids) chev.setOnClickListener(v -> {
            HashSet<String> o = openFolders();
            if (!o.remove(f)) o.add(f);
            prefs.edit().putStringSet("openFolders", o).apply();
            refresh();
        });
        row.addView(chev, 0, new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20)));
        row.setPadding(Ui.dp(this, 4 + 14 * d), 0, Ui.dp(this, 12), 0);
        return row;
    }

    /** Sidebar row: icon, label, count; the selected row is a raised surface. */
    private View navItem(int icon, String label, int count, boolean sel, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        GradientDrawable g = Ui.round(sel ? Ui.color(this, R.color.surface) : 0, Ui.dp(this, 9));
        if (sel) g.setStroke(Ui.hairline(this), Ui.color(this, R.color.outline));
        row.setBackground(Ui.ripple(this, g, Ui.dp(this, 9)));
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setImageTintList(ColorStateList.valueOf(Ui.color(this, sel ? R.color.on_surface : R.color.icon)));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        row.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 19), Ui.dp(this, 19)));
        TextView t = Ui.text(this, label, 14.5f, Ui.color(this, R.color.on_surface), sel ? Ui.bold() : null);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        if (count >= 0) row.addView(Ui.text(this, String.valueOf(count), 12.5f, Ui.color(this, R.color.on_surface_variant), null));
        row.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 40));
        lp.bottomMargin = Ui.dp(this, 2);
        row.setLayoutParams(lp);
        return row;
    }

    /** The sidebar's lists as a menu (narrow windows). */
    private void showNav(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        android.view.Menu m = pm.getMenu();
        m.add(0, 1, 0, "All notes");
        m.add(0, 2, 1, "Recent");
        m.add(0, 3, 2, "Favourites");
        ArrayList<String> fl = folders();
        for (int i = 0; i < fl.size(); i++) m.add(1, 100 + i, 10 + i, "\u2003".repeat(depth(fl.get(i))) + "📁  " + leaf(fl.get(i)));
        m.add(2, 4, 100, "New folder…");
        m.add(2, 5, 101, "Settings & backup…");
        pm.setOnMenuItemClickListener(it -> {
            int id = it.getItemId();
            if (id == 1) setFilter("");
            else if (id == 2) setFilter(RECENT);
            else if (id == 3) setFilter(FAV);
            else if (id == 4) newFolder(null);
            else if (id == 5) showMore(anchor);
            else if (id >= 100) setFilter(fl.get(id - 100));
            return true;
        });
        pm.show();
    }

    private void showSort(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        String[] names = {"Recently edited", "Name", "Date created"};
        for (int i = 0; i < 3; i++) pm.getMenu().add(1, 10 + i, i, names[i]).setCheckable(true).setChecked(sort == i);
        pm.getMenu().setGroupCheckable(1, true, true);
        pm.setOnMenuItemClickListener(it -> {
            sort = it.getItemId() - 10;
            prefs.edit().putInt("sort", sort).apply();
            fillGrid();
            return true;
        });
        pm.show();
    }

    private final java.util.HashMap<String, Handwriting.Hit> hits = new java.util.HashMap<>();

    private void fillGrid() {
        grid.removeAllViews();
        hits.clear();
        ArrayList<Notebook.Entry> shown = new ArrayList<>();
        for (Notebook.Entry e : all) {
            if (filter.equals(FAV) && !e.favorite) continue;
            if (filter.equals(RECENT) && e.modified < System.currentTimeMillis() - 7L * 24 * 3600_000) continue;
            // in a folder: its own notebooks; while searching, everything inside it (subfolders too)
            if (isFolderFilter() && !(query.isEmpty() ? filter.equals(e.folder) : within(e.folder, filter))) continue;
            if (!query.isEmpty() && !e.title.toLowerCase().contains(query) && !e.folder.toLowerCase().contains(query)) {
                Handwriting.Hit hit = Handwriting.enabled(this) ? Handwriting.find(e.dir, query) : null;
                if (hit == null) continue;
                hits.put(e.dir.getPath(), hit); // found in the handwriting
            }
            shown.add(e);
        }
        if (sort == 1) shown.sort(Comparator.comparing((Notebook.Entry e) -> e.title.toLowerCase()));
        else if (sort == 2) shown.sort(Comparator.comparingLong((Notebook.Entry e) -> e.created).reversed());
        pageTitle.setText(filter.isEmpty() ? "All notes" : filter.equals(FAV) ? "Favourites" : filter.equals(RECENT) ? "Recent" : leaf(filter));
        ArrayList<String> subs = isFolderFilter() && query.isEmpty() ? childrenOf(folders(), filter) : new ArrayList<>();
        subtitle.setText((subs.isEmpty() ? "" : subs.size() + (subs.size() == 1 ? " folder · " : " folders · "))
                + shown.size() + (shown.size() == 1 ? " notebook" : " notebooks") + (filter.equals(RECENT) ? " · last 7 days" : ""));
        renderCrumbs();
        folderGrid.removeAllViews();
        for (String f : subs) folderGrid.addView(folderCard(f));
        folderGrid.setVisibility(subs.isEmpty() ? View.GONE : View.VISIBLE);
        ((LinearLayout.LayoutParams) grid.getLayoutParams()).topMargin = Ui.dp(this, subs.isEmpty() ? 26 : 22);
        empty.setText(all.isEmpty() ? "No notebooks yet. Tap New to start writing."
                : !query.isEmpty() ? "Nothing matches “" + query + "”" : filter.equals(FAV) ? "Long-press a notebook to add it to favourites" : "Nothing here yet");
        empty.setVisibility(shown.isEmpty() && subs.isEmpty() ? View.VISIBLE : View.GONE);
        for (Notebook.Entry e : shown) grid.addView(card(e));
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
            TextView t = Ui.text(this, lv.isEmpty() ? "All notes" : leaf(lv), 13f, Ui.color(this, R.color.accent), Ui.medium());
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

    /** A subfolder tile: folder glyph, name, what's inside. Long-press for folder actions. */
    private View folderCard(String f) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 10), 0);
        GradientDrawable g = Ui.round(Ui.color(this, R.color.surface), Ui.dp(this, 10));
        g.setStroke(Ui.hairline(this), Ui.color(this, R.color.outline));
        row.setBackground(Ui.ripple(this, g, Ui.dp(this, 10)));
        ImageView iv = new ImageView(this);
        iv.setImageResource(R.drawable.ic_folder);
        iv.setImageTintList(ColorStateList.valueOf(Ui.color(this, R.color.accent)));
        row.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 4), 0);
        TextView t = Ui.text(this, leaf(f), 14.5f, Ui.color(this, R.color.on_surface), Ui.medium());
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        txt.addView(t);
        int nb = 0;
        for (Notebook.Entry e : all) if (within(e.folder, f)) nb++;
        int sub = childrenOf(folders(), f).size();
        TextView m = Ui.text(this, (sub > 0 ? sub + (sub == 1 ? " folder · " : " folders · ") : "") + nb + (nb == 1 ? " notebook" : " notebooks"),
                12f, Ui.color(this, R.color.on_surface_variant), null);
        m.setSingleLine(true);
        txt.addView(m);
        row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
        Ui.IconButton more = new Ui.IconButton(this, R.drawable.ic_more, "Folder actions");
        more.setOnClickListener(v -> folderMenu(v, f));
        row.addView(more, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
        row.setOnClickListener(v -> setFilter(f));
        row.setOnLongClickListener(v -> { folderMenu(v, f); return true; });
        row.setLayoutParams(new android.view.ViewGroup.LayoutParams(-1, Ui.dp(this, 64)));
        row.setMinimumHeight(Ui.dp(this, 64));
        return row;
    }

    private View card(Notebook.Entry e) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        Ui.Aspect frame = new Ui.Aspect(this, Notebook.PH / Notebook.PW);
        GradientDrawable g = Ui.round(0xffffffff, Ui.dp(this, 6));
        g.setStroke(Ui.hairline(this), Ui.color(this, R.color.outline));
        frame.setBackground(g);
        frame.setClipToOutline(true);
        frame.setElevation(Ui.dp(this, 1.5f));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(img, new FrameLayout.LayoutParams(-1, -1));
        File thumb = new File(e.dir, "thumb.png");
        if (thumb.exists()) {
            bg.execute(() -> {
                Bitmap bm = BitmapFactory.decodeFile(thumb.getPath());
                ui.post(() -> img.setImageBitmap(bm));
            });
        }
        if (e.favorite) {
            ImageView star = new ImageView(this);
            star.setImageResource(R.drawable.ic_star_filled);
            star.setImageTintList(ColorStateList.valueOf(0xfff2b705));
            star.setPadding(Ui.dp(this, 5), Ui.dp(this, 5), Ui.dp(this, 5), Ui.dp(this, 5));
            star.setBackground(Ui.round(0xf2ffffff, Ui.dp(this, 8)));
            FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26), Gravity.TOP | Gravity.END);
            slp.topMargin = slp.rightMargin = Ui.dp(this, 6);
            frame.addView(star, slp);
        }
        View ripple = new View(this);
        ripple.setBackground(Ui.ripple(this, null, Ui.dp(this, 6)));
        frame.addView(ripple, new FrameLayout.LayoutParams(-1, -1));
        Handwriting.Hit hit = hits.get(e.dir.getPath());
        ripple.setOnClickListener(v -> open(e.dir, hit != null ? hit.page : 0));
        ripple.setOnLongClickListener(v -> { menu(v, e); return true; });
        card.addView(frame);

        TextView t = Ui.text(this, e.title, 14, Ui.color(this, R.color.on_surface), Ui.medium());
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = Ui.dp(this, 10);
        card.addView(t, tl);
        long now = System.currentTimeMillis();
        String when = now - e.modified < 60_000 ? "Just now"
                : DateUtils.getRelativeTimeSpanString(e.modified, now, DateUtils.MINUTE_IN_MILLIS).toString();
        String meta = (e.canvas ? "Canvas · " : e.pages + (e.pages == 1 ? " page · " : " pages · ")) + when;
        if (!e.folder.isEmpty() && !filter.equals(e.folder)) meta = (isFolderFilter() ? e.folder.substring(Math.min(e.folder.length(), filter.length() + 1)) : leaf(e.folder)) .replace("/", " › ") + " · " + meta;
        if (hit != null) meta = "p. " + (hit.page + 1) + " · " + hit.snippet; // matched in the handwriting
        TextView mt = Ui.text(this, meta, 12, Ui.color(this, R.color.on_surface_variant), null);
        mt.setSingleLine(true);
        mt.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        mlp.topMargin = Ui.dp(this, 3);
        card.addView(mt, mlp);
        return card;
    }

    // ---- notebook actions --------------------------------------------------------------------

    private void menu(View anchor, Notebook.Entry e) {
        PopupMenu pm = new PopupMenu(this, anchor, Gravity.CENTER);
        Menu m = pm.getMenu();
        m.add(0, 1, 0, "Open");
        m.add(0, 7, 0, "Open in split view");
        m.add(0, 2, 1, "Rename");
        m.add(0, 3, 2, e.favorite ? "Remove from favourites" : "Add to favourites");
        SubMenu mv = m.addSubMenu(0, 0, 3, "Move to folder");
        mv.add(1, 10, 0, "No folder").setCheckable(true).setChecked(e.folder.isEmpty());
        ArrayList<String> fl = folders();
        for (int i = 0; i < fl.size(); i++)
            mv.add(1, 100 + i, i + 1, "\u2003".repeat(depth(fl.get(i))) + leaf(fl.get(i))).setCheckable(true).setChecked(fl.get(i).equals(e.folder));
        mv.add(2, 11, fl.size() + 1, isFolderFilter() ? "New subfolder in " + leaf(filter) + "…" : "New folder…");
        m.add(0, 4, 4, "Duplicate");
        m.add(0, 5, 5, "Export as PDF");
        m.add(0, 6, 6, "Delete");
        pm.setOnMenuItemClickListener(it -> {
            int id = it.getItemId();
            try {
                if (id == 1) open(e.dir);
                else if (id == 7) {
                    prefs.edit().putString("lastDir", e.dir.getPath()).apply();
                    startActivity(new Intent(this, EditorActivity.class).putExtra("dir", e.dir.getPath()).putExtra("split", true));
                }
                else if (id == 2) rename(e);
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

    private void confirmDelete(Notebook.Entry e) {
        new AlertDialog.Builder(this).setTitle("Delete “" + e.title + "”?")
                .setMessage("This notebook and all its pages will be removed.")
                .setPositiveButton("Delete", (d, w) -> { Notebook.delete(e.dir); refresh(); })
                .setNegativeButton("Cancel", null).show();
    }

    private void newNotebook(boolean canvas) {
        try {
            String title = new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(new Date());
            String folder = filter.equals(FAV) || filter.equals(RECENT) ? "" : filter;
            // the page style chosen with "Use for new notebooks", else dotted; Graphite theme defaults to dark paper
            Notebook.Page proto = new Notebook.Page(Notebook.BG_DOTS);
            int paper = Ui.dark(this) ? Notebook.PAPER_DARK : Notebook.PAPER_WHITE;
            String def = prefs.getString("defStyle", null);
            if (def != null) {
                org.json.JSONObject o = new org.json.JSONObject(def);
                proto = EditorActivity.lookOf(o);
                paper = o.optInt("p", paper);
            }
            File dir = Notebook.create(this, title, proto, folder, canvas, paper);
            open(dir);
        } catch (Exception ex) {
            Toast.makeText(this, "Could not create notebook", Toast.LENGTH_SHORT).show();
        }
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
        SubMenu s = m.addSubMenu(0, 0, 0, "Appearance");
        String[] names = {"System", "Light · Clean", "Dark · Graphite"};
        int th = Theme.get(this);
        for (int i = 0; i < 3; i++) s.add(1, 30 + i, i, names[i]).setCheckable(true).setChecked(th == i);
        s.setGroupCheckable(1, true, true);
        boolean has = Backup.tree(this) != null;
        m.add(0, 20, 1, has ? "Back up now" : "Set up backup…");
        if (has) m.add(0, 21, 2, "Change backup folder…");
        m.add(0, 22, 3, "Restore from backup…");
        long last = prefs.getLong(Backup.PREF_LAST, 0);
        if (last > 0) m.add(0, 23, 4, "Last backup " + DateUtils.getRelativeTimeSpanString(last)).setEnabled(false);
        pm.setOnMenuItemClickListener(it -> {
            int id = it.getItemId();
            if (id >= 30 && id < 33) {
                Theme.set(this, id - 30);
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

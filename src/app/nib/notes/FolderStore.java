package app.nib.notes;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Central folder model & persistence layer.
 * Folders remain hierarchical slash-separated paths ("School/Physics") for compatibility with
 * Notebook metadata and backups, while rich metadata (color, pinned state, created timestamp)
 * lives in SharedPreferences under {@link #PREF_META}.
 */
final class FolderStore {
    private FolderStore() {}

    static final String PREF_FOLDERS = "folders";
    static final String PREF_OPEN = "openFolders";
    static final String PREF_META = "folderMeta";

    /** Curated folder color palette (index 0 = dynamic theme accent). */
    static final int[] COLORS_LIGHT = {
            0,          // 0 = Theme accent
            0xFFC2603F, // Terracotta
            0xFF2D6FA3, // Ocean
            0xFF3B8463, // Sage
            0xFFC78822, // Amber
            0xFF7458B5, // Lavender
            0xFFC14C6C, // Rose
            0xFF23828A, // Teal
            0xFF8A6244, // Mocha
            0xFF5B6573  // Slate
    };

    static final int[] COLORS_DARK = {
            0,          // 0 = Theme accent
            0xFFE08666, // Terracotta
            0xFF67A7DB, // Ocean
            0xFF67B891, // Sage
            0xFFE5AF4E, // Amber
            0xFFA78EE0, // Lavender
            0xFFE37C98, // Rose
            0xFF55B8C0, // Teal
            0xFFB88F6E, // Mocha
            0xFF8C97A6  // Slate
    };

    static final String[] COLOR_NAMES = {
            "Theme accent", "Terracotta", "Ocean", "Sage", "Amber",
            "Lavender", "Rose", "Teal", "Mocha", "Slate"
    };

    static final class Meta {
        int colorIdx;
        boolean pinned;
        long created;

        Meta(int colorIdx, boolean pinned, long created) {
            this.colorIdx = clampColor(colorIdx);
            this.pinned = pinned;
            this.created = created;
        }
    }

    static final class Stats {
        int directNotebooks;
        int totalNotebooks;
        int directSubfolders;
        long lastModified;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("nib", Context.MODE_PRIVATE);
    }

    static int clampColor(int idx) {
        return idx >= 0 && idx < COLORS_LIGHT.length ? idx : 0;
    }

    static int resolveColor(Context c, int colorIdx) {
        int idx = clampColor(colorIdx);
        if (idx == 0) return Ui.color(c, R.color.accent);
        return (Ui.dark(c) ? COLORS_DARK : COLORS_LIGHT)[idx];
    }

    static int colorOf(Context c, String path) {
        return resolveColor(c, meta(c, path).colorIdx);
    }

    /** Soft tinted background badge for a folder icon. */
    static int tintContainer(Context c, int color) {
        int alpha = Ui.dark(c) ? 0x33 : 0x1E;
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    // ---- Path helpers ------------------------------------------------------------------------

    static String cleanName(String raw) {
        if (raw == null) return "";
        return raw.replace("/", "-")
                .replace("★", "")
                .replace("⏱", "")
                .replace("🗑", "")
                .replace("∅", "")
                .replace("∀", "")
                .trim();
    }

    static String leaf(String path) {
        if (path == null || path.isEmpty()) return "";
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    static String parentOf(String path) {
        if (path == null || path.isEmpty()) return "";
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }

    static int depth(String path) {
        if (path == null || path.isEmpty()) return 0;
        int d = 0;
        for (int i = 0; i < path.length(); i++) if (path.charAt(i) == '/') d++;
        return d;
    }

    static boolean within(String path, String folder) {
        if (path == null || folder == null || folder.isEmpty()) return false;
        return path.equals(folder) || path.startsWith(folder + "/");
    }

    static String pretty(String path) {
        return path == null ? "" : path.replace("/", " › ");
    }

    static int comparePaths(String a, String b) {
        String[] x = a.split("/"), y = b.split("/");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            int c = x[i].compareToIgnoreCase(y[i]);
            if (c == 0) c = x[i].compareTo(y[i]);
            if (c != 0) return c;
        }
        return Integer.compare(x.length, y.length);
    }

    // ---- Metadata storage --------------------------------------------------------------------

    private static HashMap<String, Meta> loadMetaMap(Context c) {
        HashMap<String, Meta> map = new HashMap<>();
        String raw = prefs(c).getString(PREF_META, "{}");
        try {
            JSONObject root = new JSONObject(raw);
            Iterator<String> it = root.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject o = root.optJSONObject(k);
                if (o != null) {
                    map.put(k, new Meta(o.optInt("c", 0), o.optBoolean("p", false), o.optLong("t", 0L)));
                }
            }
        } catch (Exception ignored) { }
        return map;
    }

    private static void saveMetaMap(Context c, HashMap<String, Meta> map) {
        JSONObject root = new JSONObject();
        for (HashMap.Entry<String, Meta> e : map.entrySet()) {
            try {
                Meta m = e.getValue();
                if (m.colorIdx == 0 && !m.pinned && m.created == 0L) continue;
                JSONObject o = new JSONObject();
                if (m.colorIdx != 0) o.put("c", m.colorIdx);
                if (m.pinned) o.put("p", true);
                if (m.created != 0L) o.put("t", m.created);
                root.put(e.getKey(), o);
            } catch (Exception ignored) { }
        }
        prefs(c).edit().putString(PREF_META, root.toString()).apply();
    }

    static Meta meta(Context c, String path) {
        if (path == null || path.isEmpty()) return new Meta(0, false, 0L);
        Meta m = loadMetaMap(c).get(path);
        return m != null ? m : new Meta(0, false, 0L);
    }

    static boolean isPinned(Context c, String path) {
        return meta(c, path).pinned;
    }

    static void setColor(Context c, String path, int colorIdx) {
        if (path == null || path.isEmpty()) return;
        HashMap<String, Meta> map = loadMetaMap(c);
        Meta m = map.get(path);
        if (m == null) {
            m = new Meta(colorIdx, false, System.currentTimeMillis());
            map.put(path, m);
        } else {
            m.colorIdx = clampColor(colorIdx);
        }
        saveMetaMap(c, map);
    }

    static boolean togglePin(Context c, String path) {
        if (path == null || path.isEmpty()) return false;
        HashMap<String, Meta> map = loadMetaMap(c);
        Meta m = map.get(path);
        if (m == null) {
            m = new Meta(0, true, System.currentTimeMillis());
            map.put(path, m);
        } else {
            m.pinned = !m.pinned;
        }
        saveMetaMap(c, map);
        return m.pinned;
    }

    static JSONObject exportMetaJson(Context c) {
        try {
            return new JSONObject(prefs(c).getString(PREF_META, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static void mergeMetaJson(Context c, JSONObject incoming) {
        if (incoming == null) return;
        HashMap<String, Meta> map = loadMetaMap(c);
        Iterator<String> it = incoming.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (map.containsKey(k)) continue;
            JSONObject o = incoming.optJSONObject(k);
            if (o != null) {
                map.put(k, new Meta(o.optInt("c", 0), o.optBoolean("p", false), o.optLong("t", 0L)));
            }
        }
        saveMetaMap(c, map);
    }

    // ---- Querying & hierarchy ----------------------------------------------------------------

    static HashSet<String> openFolders(Context c) {
        return new HashSet<>(prefs(c).getStringSet(PREF_OPEN, new HashSet<>()));
    }

    static void toggleExpanded(Context c, String path) {
        HashSet<String> open = openFolders(c);
        if (!open.remove(path)) open.add(path);
        prefs(c).edit().putStringSet(PREF_OPEN, open).apply();
    }

    /** Every folder path (parents before children; pinned siblings first, then alphabetical). */
    static ArrayList<String> allFolders(Context c, List<Notebook.Entry> all) {
        TreeSet<String> set = new TreeSet<>(FolderStore::comparePaths);
        ArrayList<String> raw = new ArrayList<>(prefs(c).getStringSet(PREF_FOLDERS, new HashSet<>()));
        if (all != null) {
            for (Notebook.Entry e : all) {
                if (e.folder != null && !e.folder.isEmpty()) raw.add(e.folder);
            }
        }
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
        HashMap<String, Meta> metaMap = loadMetaMap(c);
        ArrayList<String> ordered = new ArrayList<>();
        appendOrdered(set, "", metaMap, ordered);
        return ordered;
    }

    private static void appendOrdered(Set<String> allSet, String parent, HashMap<String, Meta> metaMap, List<String> out) {
        ArrayList<String> kids = new ArrayList<>();
        for (String f : allSet) {
            if (parentOf(f).equals(parent)) kids.add(f);
        }
        kids.sort((a, b) -> {
            Meta ma = metaMap.get(a), mb = metaMap.get(b);
            boolean pa = ma != null && ma.pinned, pb = mb != null && mb.pinned;
            if (pa != pb) return pa ? -1 : 1;
            int cmp = leaf(a).compareToIgnoreCase(leaf(b));
            return cmp != 0 ? cmp : a.compareTo(b);
        });
        for (String k : kids) {
            out.add(k);
            appendOrdered(allSet, k, metaMap, out);
        }
    }

    /** Direct child folders of parent ("" = top-level folders on Home). */
    static ArrayList<String> childrenOf(List<String> allFolders, String parent) {
        ArrayList<String> out = new ArrayList<>();
        for (String f : allFolders) {
            if (parentOf(f).equals(parent)) out.add(f);
        }
        return out;
    }

    /** All pinned folders across the library. */
    static ArrayList<String> pinnedFolders(Context c, List<String> allFolders) {
        HashMap<String, Meta> map = loadMetaMap(c);
        ArrayList<String> out = new ArrayList<>();
        for (String f : allFolders) {
            Meta m = map.get(f);
            if (m != null && m.pinned) out.add(f);
        }
        return out;
    }

    /** Folders whose name or path matches a search query (optionally scoped under a folder). */
    static ArrayList<String> matchingFolders(List<String> allFolders, String query, String scopeFolder) {
        ArrayList<String> out = new ArrayList<>();
        if (query == null || query.isEmpty()) return out;
        String q = query.toLowerCase(Locale.getDefault());
        for (String f : allFolders) {
            if (scopeFolder != null && !scopeFolder.isEmpty() && (!within(f, scopeFolder) || f.equals(scopeFolder))) {
                continue;
            }
            if (leaf(f).toLowerCase(Locale.getDefault()).contains(q) || f.toLowerCase(Locale.getDefault()).contains(q)) {
                out.add(f);
            }
        }
        return out;
    }

    static Stats stats(List<Notebook.Entry> all, List<String> allFolders, String path) {
        Stats s = new Stats();
        if (all != null) {
            for (Notebook.Entry e : all) {
                if (e.folder.equals(path)) {
                    s.directNotebooks++;
                    s.totalNotebooks++;
                    if (e.modified > s.lastModified) s.lastModified = e.modified;
                } else if (within(e.folder, path)) {
                    s.totalNotebooks++;
                    if (e.modified > s.lastModified) s.lastModified = e.modified;
                }
            }
        }
        if (allFolders != null) {
            for (String f : allFolders) {
                if (parentOf(f).equals(path)) s.directSubfolders++;
            }
        }
        return s;
    }

    static List<Notebook.Entry> notebooksIn(List<Notebook.Entry> all, String folderPath, boolean includeSubfolders) {
        ArrayList<Notebook.Entry> out = new ArrayList<>();
        if (all == null) return out;
        for (Notebook.Entry e : all) {
            if (includeSubfolders ? within(e.folder, folderPath) : e.folder.equals(folderPath)) {
                out.add(e);
            }
        }
        return out;
    }

    // ---- Mutations ---------------------------------------------------------------------------

    /** Creates a new folder (returns its full path, or null if name is empty). */
    static String create(Context c, String parent, String rawName, int colorIdx) {
        String name = cleanName(rawName);
        if (name.isEmpty()) return null;
        String path = (parent == null || parent.isEmpty()) ? name : parent + "/" + name;
        SharedPreferences p = prefs(c);
        HashSet<String> set = new HashSet<>(p.getStringSet(PREF_FOLDERS, new HashSet<>()));
        set.add(path);
        HashSet<String> open = openFolders(c);
        if (parent != null && !parent.isEmpty()) open.add(parent);
        p.edit().putStringSet(PREF_FOLDERS, set).putStringSet(PREF_OPEN, open).apply();

        HashMap<String, Meta> map = loadMetaMap(c);
        Meta existing = map.get(path);
        if (existing == null) {
            map.put(path, new Meta(colorIdx, false, System.currentTimeMillis()));
        } else if (colorIdx != 0) {
            existing.colorIdx = clampColor(colorIdx);
        }
        saveMetaMap(c, map);
        return path;
    }

    /** Renames or moves folder `from` (and everything inside it) to `to`. Returns false on collision/no-op. */
    static boolean moveOrRename(Context c, List<Notebook.Entry> all, String from, String to) {
        if (from == null || to == null || to.isEmpty() || to.equals(from)) return false;
        if (within(to, from)) return false;
        if (allFolders(c, all).contains(to)) return false;

        if (all != null) {
            for (Notebook.Entry e : all) {
                if (within(e.folder, from)) {
                    try {
                        Notebook.setMeta(e.dir, "folder", to + e.folder.substring(from.length()));
                    } catch (Exception ignored) { }
                }
            }
        }
        SharedPreferences p = prefs(c);
        p.edit()
                .putStringSet(PREF_FOLDERS, repathSet(p.getStringSet(PREF_FOLDERS, new HashSet<>()), from, to))
                .putStringSet(PREF_OPEN, repathSet(openFolders(c), from, to))
                .apply();

        HashMap<String, Meta> oldMeta = loadMetaMap(c);
        HashMap<String, Meta> newMeta = new HashMap<>();
        for (HashMap.Entry<String, Meta> e : oldMeta.entrySet()) {
            String k = e.getKey();
            String nk = within(k, from) ? to + k.substring(from.length()) : k;
            newMeta.put(nk, e.getValue());
        }
        saveMetaMap(c, newMeta);
        return true;
    }

    private static HashSet<String> repathSet(Set<String> in, String from, String to) {
        HashSet<String> out = new HashSet<>();
        for (String s : in) {
            out.add(within(s, from) ? to + s.substring(from.length()) : s);
        }
        return out;
    }

    /** Removes folder f while moving its notebooks and subfolders up one level. */
    static void removeKeepingNotebooks(Context c, List<Notebook.Entry> all, String f) {
        String up = parentOf(f);
        if (all != null) {
            for (Notebook.Entry e : all) {
                if (within(e.folder, f)) {
                    String rest = e.folder.substring(f.length());
                    String np = rest.isEmpty() ? up : (up.isEmpty() ? rest.substring(1) : up + rest);
                    try { Notebook.setMeta(e.dir, "folder", np); } catch (Exception ignored) { }
                }
            }
        }
        SharedPreferences p = prefs(c);
        HashSet<String> folders = new HashSet<>();
        for (String s : p.getStringSet(PREF_FOLDERS, new HashSet<>())) {
            if (s.equals(f)) continue;
            if (s.startsWith(f + "/")) {
                String rest = s.substring(f.length() + 1);
                folders.add(up.isEmpty() ? rest : up + "/" + rest);
            } else {
                folders.add(s);
            }
        }
        HashSet<String> open = new HashSet<>();
        for (String s : openFolders(c)) {
            if (s.equals(f)) continue;
            if (s.startsWith(f + "/")) {
                String rest = s.substring(f.length() + 1);
                open.add(up.isEmpty() ? rest : up + "/" + rest);
            } else {
                open.add(s);
            }
        }
        p.edit().putStringSet(PREF_FOLDERS, folders).putStringSet(PREF_OPEN, open).apply();

        HashMap<String, Meta> oldMeta = loadMetaMap(c);
        HashMap<String, Meta> newMeta = new HashMap<>();
        for (HashMap.Entry<String, Meta> e : oldMeta.entrySet()) {
            String k = e.getKey();
            if (k.equals(f)) continue;
            if (k.startsWith(f + "/")) {
                String rest = k.substring(f.length() + 1);
                newMeta.put(up.isEmpty() ? rest : up + "/" + rest, e.getValue());
            } else {
                newMeta.put(k, e.getValue());
            }
        }
        saveMetaMap(c, newMeta);
    }

    /** Moves all notebooks inside folder f (including subfolders) to Trash and deletes the folder. */
    static int trashWithNotebooks(Context c, List<Notebook.Entry> all, String f) {
        int trashed = 0;
        if (all != null) {
            for (Notebook.Entry e : all) {
                if (within(e.folder, f)) {
                    if (Notebook.trash(c, e.dir)) trashed++;
                }
            }
        }
        SharedPreferences p = prefs(c);
        HashSet<String> folders = new HashSet<>();
        for (String s : p.getStringSet(PREF_FOLDERS, new HashSet<>())) {
            if (!within(s, f)) folders.add(s);
        }
        HashSet<String> open = new HashSet<>();
        for (String s : openFolders(c)) {
            if (!within(s, f)) open.add(s);
        }
        p.edit().putStringSet(PREF_FOLDERS, folders).putStringSet(PREF_OPEN, open).apply();

        HashMap<String, Meta> oldMeta = loadMetaMap(c);
        HashMap<String, Meta> newMeta = new HashMap<>();
        for (HashMap.Entry<String, Meta> e : oldMeta.entrySet()) {
            if (!within(e.getKey(), f)) newMeta.put(e.getKey(), e.getValue());
        }
        saveMetaMap(c, newMeta);
        return trashed;
    }

    /** Moves a list of notebooks into destFolder ("" = Home / no folder). */
    static int moveNotebooks(List<Notebook.Entry> entries, String destFolder) {
        if (entries == null) return 0;
        String dest = destFolder == null ? "" : destFolder;
        int moved = 0;
        for (Notebook.Entry e : entries) {
            try {
                Notebook.setMeta(e.dir, "folder", dest);
                moved++;
            } catch (Exception ignored) { }
        }
        return moved;
    }
}

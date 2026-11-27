package app.nib.notes;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Zips every notebook into a user-chosen folder (Storage Access Framework tree), keeping the 7
 * newest backups. Restore unpacks a backup zip next to the existing notebooks.
 */
final class Backup {
    private Backup() {}

    static final String PREF_TREE = "backupTree", PREF_LAST = "backupLast";
    static final long AUTO_EVERY = 6 * 3600_000L;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("nib", Context.MODE_PRIVATE); }

    static Uri tree(Context c) {
        String s = prefs(c).getString(PREF_TREE, null);
        return s == null ? null : Uri.parse(s);
    }

    static boolean due(Context c) {
        return tree(c) != null && System.currentTimeMillis() - prefs(c).getLong(PREF_LAST, 0) > AUTO_EVERY;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean RUNNING = new java.util.concurrent.atomic.AtomicBoolean();
    /** Library settings that live outside the notebooks (empty folders, saved page templates), stored in the zip. */
    static final String LIBRARY = "nib-library.json";

    /** Returns the backup's file name (null if a backup is already running). */
    static String run(Context c) throws IOException {
        return run(c, null);
    }

    /** Returns the backup's file name (null if a backup is already running), reporting progress if cb != null. */
    static String run(Context c, ProgressPopup.Listener cb) throws IOException {
        if (!RUNNING.compareAndSet(false, true)) return null; // the notes screen reopened mid-backup: one is enough
        try {
            return runOnce(c, cb);
        } finally {
            RUNNING.set(false);
        }
    }

    private static String runOnce(Context c, ProgressPopup.Listener cb) throws IOException {
        Uri tree = tree(c);
        if (tree == null) throw new IOException("No backup folder chosen");
        ContentResolver cr = c.getContentResolver();
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        String name = "Nib backup " + new SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(new Date()) + ".zip";
        if (cb != null) cb.onProgress(0, 0, "Creating backup archive…");
        Uri doc = DocumentsContract.createDocument(cr, parent, "application/zip", name);
        if (doc == null) throw new IOException("Could not create " + name);
        try (OutputStream os = cr.openOutputStream(doc); ZipOutputStream zip = new ZipOutputStream(os)) {
            File root = Notebook.root(c);
            File[] dirs = root.listFiles(File::isDirectory);
            int totalSteps = (dirs != null ? dirs.length : 0) + 1;
            int step = 0;
            if (dirs != null) {
                for (File d : dirs) {
                    step++;
                    if (cb != null) {
                        cb.onProgress(step, totalSteps, "Backing up notebook " + step + " of " + dirs.length + "…");
                    }
                    addDir(zip, d, d.getName() + "/", null, null, 0);
                }
            }
            if (cb != null) cb.onProgress(totalSteps, totalSteps, "Saving fonts, stickers & settings…");
            File fonts = Fonts.dir(c); // imported fonts, so restored notebooks keep their look
            if (fonts.isDirectory()) addDir(zip, fonts, Fonts.BACKUP_DIR, null, null, 0);
            File stickers = Stickers.dir(c); // the sticker library
            if (stickers.isDirectory()) addDir(zip, stickers, Stickers.BACKUP_DIR, null, null, 0);
            zip.putNextEntry(new ZipEntry(LIBRARY));
            zip.write(libraryJson(c).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        prefs(c).edit().putLong(PREF_LAST, System.currentTimeMillis()).apply();
        prune(c, tree);
        return name;
    }

    private static int countFiles(File d) {
        File[] kids = d.listFiles();
        if (kids == null) return 0;
        int n = 0;
        for (File k : kids) {
            if (k.getName().endsWith(".tmp") || k.getName().equals("ink.bin.old")) continue;
            n += k.isDirectory() ? countFiles(k) : 1;
        }
        return n;
    }

    private static void addDir(ZipOutputStream zip, File d, String prefix, ProgressPopup.Listener cb, int[] done, int total) throws IOException {
        File[] kids = d.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.getName().endsWith(".tmp") || k.getName().equals("ink.bin.old")) continue; // .old: pre-2.3 copy, the pages hold it all
            if (k.isDirectory()) {
                addDir(zip, k, prefix + k.getName() + "/", cb, done, total);
                continue;
            }
            zip.putNextEntry(new ZipEntry(prefix + k.getName()));
            try (InputStream in = new FileInputStream(k)) {
                Io.pipe(in, zip);
            }
            zip.closeEntry();
            if (cb != null && done != null && total > 0) {
                done[0]++;
                cb.onProgress(done[0], total, "Packaging files (" + done[0] + " of " + total + ")…");
            }
        }
    }

    private static void prune(Context c, Uri tree) {
        try {
            ContentResolver cr = c.getContentResolver();
            Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            ArrayList<String[]> found = new ArrayList<>();
            try (Cursor cur = cr.query(kids, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                while (cur != null && cur.moveToNext()) {
                    String n = cur.getString(1);
                    if (n != null && n.startsWith("Nib backup ") && n.endsWith(".zip")) found.add(new String[]{n, cur.getString(0)});
                }
            }
            found.sort((a, b) -> a[0].compareTo(b[0]));
            Collections.reverse(found);
            for (int i = 7; i < found.size(); i++)
                DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, found.get(i)[1]));
        } catch (Exception ignored) {
        }
    }

    private static String libraryJson(Context c) {
        SharedPreferences p = prefs(c);
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("folders", new org.json.JSONArray(p.getStringSet("folders", new java.util.HashSet<>())));
            o.put("folderMeta", FolderStore.exportMetaJson(c));
            o.put("myTpl", new org.json.JSONArray(p.getString("myTpl", "[]")));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Adds a backup's empty folders, folder styles, and saved templates to what's here (nothing is replaced). */
    private static void mergeLibrary(Context c, String json) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            SharedPreferences p = prefs(c);
            java.util.HashSet<String> folders = new java.util.HashSet<>(p.getStringSet("folders", new java.util.HashSet<>()));
            org.json.JSONArray f = o.optJSONArray("folders");
            if (f != null) for (int i = 0; i < f.length(); i++) folders.add(f.getString(i));
            FolderStore.mergeMetaJson(c, o.optJSONObject("folderMeta"));
            org.json.JSONArray mine = new org.json.JSONArray(p.getString("myTpl", "[]")), add = o.optJSONArray("myTpl");
            java.util.HashSet<String> have = new java.util.HashSet<>();
            for (int i = 0; i < mine.length(); i++) have.add(mine.get(i).toString());
            if (add != null) for (int i = 0; i < add.length(); i++) if (have.add(add.get(i).toString())) mine.put(add.get(i));
            p.edit().putStringSet("folders", folders).putString("myTpl", mine.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static final class RestoreResult {
        final int count;
        final File singleDir;
        RestoreResult(int count, File singleDir) {
            this.count = count;
            this.singleDir = singleDir;
        }
    }

    /** Exports a single notebook directory as a shareable .nib archive in cache/exports. */
    static File exportNotebook(Context c, File nbDir, String title) throws IOException {
        return exportNotebook(c, nbDir, title, null);
    }

    static File exportNotebook(Context c, File nbDir, String title, ProgressPopup.Listener cb) throws IOException {
        File out = new File(Exporter.exportDir(c), Exporter.safeName(title) + ".nib");
        int total = cb != null ? Math.max(1, countFiles(nbDir)) : 0;
        int[] done = cb != null ? new int[]{0} : null;
        if (cb != null) cb.onProgress(0, total, "Packaging notebook…");
        try (OutputStream os = new FileOutputStream(out); ZipOutputStream zip = new ZipOutputStream(os)) {
            addDir(zip, nbDir, "", cb, done, total);
        }
        return out;
    }

    /** Unpacks a backup; notebooks that already exist are restored as copies. Returns count. */
    static int restore(Context c, Uri zipUri) throws IOException {
        return restoreAny(c, zipUri, null, null).count;
    }

    static RestoreResult restoreAny(Context c, Uri zipUri, String targetFolder) throws IOException {
        return restoreAny(c, zipUri, targetFolder, null);
    }

    /**
     * Unpacks either a single-notebook .nib archive (files at archive root or inside a single directory)
     * or a full library .zip backup. Returns how many notebooks were restored and the directory if exactly one.
     */
    static RestoreResult restoreAny(Context c, Uri zipUri, String targetFolder, ProgressPopup.Listener cb) throws IOException {
        File root = Notebook.root(c);
        String rootCanon = root.getCanonicalPath();
        java.util.HashMap<String, File> map = new java.util.HashMap<>();
        File rootNotebook = null;
        boolean sawLibrary = false;
        int extractedFiles = 0;

        if (cb != null) cb.onProgress(0, 0, "Opening archive…");
        try (InputStream is = c.getContentResolver().openInputStream(zipUri)) {
            if (is == null) throw new IOException("Could not open file");
            try (ZipInputStream zin = new ZipInputStream(is)) {
                ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    String n = e.getName();
                    if (n == null || n.isEmpty()) continue;
                    // normalize Windows separators if any
                    n = n.replace('\\', '/');
                    while (n.startsWith("/")) n = n.substring(1);
                    if (n.isEmpty() || n.contains("..")) continue;

                    if (n.equals(LIBRARY)) {
                        sawLibrary = true;
                        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                        Io.pipe(zin, b);
                        mergeLibrary(c, b.toString("UTF-8"));
                        continue;
                    }
                    if (e.isDirectory()) continue;
                    if (n.startsWith(Fonts.BACKUP_DIR)) {
                        Fonts.restoreFile(c, n.substring(Fonts.BACKUP_DIR.length()), zin);
                        continue;
                    }
                    if (n.startsWith(Stickers.BACKUP_DIR)) {
                        Stickers.restoreFile(c, n.substring(Stickers.BACKUP_DIR.length()), zin);
                        continue;
                    }

                    int slash = n.indexOf('/');
                    boolean flatNotebookEntry = slash < 0
                            || n.startsWith("pages/")
                            || n.startsWith("images/");

                    File f;
                    if (flatNotebookEntry) {
                        if (rootNotebook == null) rootNotebook = Notebook.newDir(c);
                        f = new File(rootNotebook, n);
                    } else {
                        String nb = n.substring(0, slash);
                        File target = map.get(nb);
                        if (target == null) {
                            target = new File(root, nb);
                            if (target.exists()) target = Notebook.newDir(c);
                            map.put(nb, target);
                            if (cb != null) {
                                cb.onProgress(0, 0, "Restoring notebook " + map.size() + "…");
                            }
                        }
                        f = new File(target, n.substring(slash + 1));
                    }
                    if (!f.getCanonicalPath().startsWith(rootCanon + File.separator)) continue;
                    File parent = f.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (OutputStream o = new FileOutputStream(f)) {
                        Io.pipe(zin, o);
                    }
                    extractedFiles++;
                    if (cb != null && map.isEmpty() && (extractedFiles == 1 || extractedFiles % 6 == 0)) {
                        cb.onProgress(0, 0, "Extracting notebook (" + extractedFiles + " files)…");
                    }
                }
            }
        }

        // Validate restored directories actually contain notebook data
        ArrayList<File> restored = new ArrayList<>();
        if (rootNotebook != null) {
            if (isNotebookDir(rootNotebook)) restored.add(rootNotebook);
            else Notebook.delete(rootNotebook);
        }
        for (File dir : map.values()) {
            if (isNotebookDir(dir)) restored.add(dir);
            else Notebook.delete(dir);
        }
        if (restored.isEmpty() && !sawLibrary) {
            throw new IOException("No Nib notebooks found in this file");
        }
        if (restored.size() == 1 && targetFolder != null && !targetFolder.isEmpty()) {
            File only = restored.get(0);
            try {
                // Only assign targetFolder when importing a single notebook whose folder was empty
                java.util.Properties meta = new java.util.Properties();
                File mf = new File(only, "meta.properties");
                if (mf.isFile()) {
                    try (FileInputStream fis = new FileInputStream(mf)) { meta.load(fis); }
                }
                if (meta.getProperty("folder", "").isEmpty()) {
                    Notebook.setMeta(only, "folder", targetFolder);
                }
            } catch (Exception ignored) { }
        }
        return new RestoreResult(restored.size(), restored.size() == 1 ? restored.get(0) : null);
    }

    private static boolean isNotebookDir(File dir) {
        return dir != null && dir.isDirectory()
                && (new File(dir, "ink.bin").isFile()
                || new File(dir, "meta.properties").isFile()
                || new File(dir, "source.pdf").isFile());
    }
}

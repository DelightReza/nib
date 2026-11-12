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

    /** Returns the backup's file name. */
    static String run(Context c) throws IOException {
        Uri tree = tree(c);
        if (tree == null) throw new IOException("No backup folder chosen");
        ContentResolver cr = c.getContentResolver();
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        String name = "Nib backup " + new SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(new Date()) + ".zip";
        Uri doc = DocumentsContract.createDocument(cr, parent, "application/zip", name);
        if (doc == null) throw new IOException("Could not create " + name);
        try (OutputStream os = cr.openOutputStream(doc); ZipOutputStream zip = new ZipOutputStream(os)) {
            File root = Notebook.root(c);
            File[] dirs = root.listFiles(File::isDirectory);
            if (dirs != null) for (File d : dirs) addDir(zip, d, d.getName() + "/");
        }
        prefs(c).edit().putLong(PREF_LAST, System.currentTimeMillis()).apply();
        prune(c, tree);
        return name;
    }

    private static void addDir(ZipOutputStream zip, File d, String prefix) throws IOException {
        File[] kids = d.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.getName().endsWith(".tmp")) continue;
            if (k.isDirectory()) {
                addDir(zip, k, prefix + k.getName() + "/");
                continue;
            }
            zip.putNextEntry(new ZipEntry(prefix + k.getName()));
            try (InputStream in = new FileInputStream(k)) {
                Io.pipe(in, zip);
            }
            zip.closeEntry();
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

    /** Unpacks a backup; notebooks that already exist are restored as copies. Returns count. */
    static int restore(Context c, Uri zipUri) throws IOException {
        File root = Notebook.root(c);
        java.util.HashMap<String, File> map = new java.util.HashMap<>();
        try (InputStream is = c.getContentResolver().openInputStream(zipUri); ZipInputStream zin = new ZipInputStream(is)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String n = e.getName();
                if (e.isDirectory() || n.contains("..")) continue;
                int slash = n.indexOf('/');
                if (slash <= 0) continue;
                String nb = n.substring(0, slash);
                File target = map.get(nb);
                if (target == null) {
                    target = new File(root, nb);
                    if (target.exists()) target = Notebook.newDir(c);
                    map.put(nb, target);
                }
                File f = new File(target, n.substring(slash + 1));
                if (!f.getCanonicalPath().startsWith(root.getCanonicalPath())) continue;
                f.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(f)) {
                    Io.pipe(zin, o);
                }
            }
        }
        return map.size();
    }
}

package app.nib.notes;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * Provider for app-private cache files:
 * - content://app.nib.notes.files/<name>       -> read-only shared exports (cache/exports/)
 * - content://app.nib.notes.files/scans/<name> -> read/write temp camera capture files (cache/scans/)
 */
public final class ExportProvider extends ContentProvider {
    static final String AUTHORITY = "app.nib.notes.files";

    @Override public boolean onCreate() { return true; }

    private boolean isScanUri(Uri uri) {
        List<String> segs = uri.getPathSegments();
        return segs != null && segs.size() == 2 && "scans".equals(segs.get(0));
    }

    private File file(Uri uri, boolean allowMissing) throws FileNotFoundException {
        List<String> segs = uri.getPathSegments();
        if (segs == null || segs.isEmpty() || segs.size() > 2) throw new FileNotFoundException();
        File base;
        String name;
        if (segs.size() == 2 && "scans".equals(segs.get(0))) {
            base = new File(getContext().getCacheDir(), "scans");
            base.mkdirs();
            name = segs.get(1);
        } else if (segs.size() == 1) {
            base = Exporter.exportDir(getContext());
            name = segs.get(0);
        } else {
            throw new FileNotFoundException();
        }
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\")
                || name.contains("..") || name.startsWith(".")) {
            throw new FileNotFoundException();
        }
        File f = new File(base, name);
        if (!allowMissing && !f.isFile()) throw new FileNotFoundException(name);
        return f;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String sel, String[] args, String sort) {
        try {
            File f = file(uri, false);
            MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            c.addRow(new Object[]{f.getName(), f.length()});
            return c;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public String getType(Uri uri) {
        String n = uri.getLastPathSegment();
        if (n == null) return null;
        String low = n.toLowerCase(java.util.Locale.US);
        if (low.endsWith(".pdf")) return "application/pdf";
        if (low.endsWith(".png")) return "image/png";
        if (low.endsWith(".jpg") || low.endsWith(".jpeg")) return "image/jpeg";
        if (low.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        boolean write = mode != null && (mode.contains("w") || mode.contains("t"));
        if (write && !isScanUri(uri)) {
            throw new SecurityException("Exports are read-only");
        }
        File f = file(uri, write);
        int flags = mode != null && !mode.isEmpty()
                ? ParcelFileDescriptor.parseMode(mode)
                : ParcelFileDescriptor.MODE_READ_ONLY;
        if (write) flags |= ParcelFileDescriptor.MODE_CREATE;
        return ParcelFileDescriptor.open(f, flags);
    }

    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}

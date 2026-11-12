package app.nib.notes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class Io {
    private Io() {}

    static long copy(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[1 << 16];
        long total = 0;
        try (InputStream i = in; OutputStream o = out) {
            int n;
            while ((n = i.read(b)) > 0) {
                o.write(b, 0, n);
                total += n;
            }
        }
        return total;
    }

    static byte[] read(java.io.File f) throws IOException {
        return java.nio.file.Files.readAllBytes(f.toPath());
    }

    /** Writes via a temp file + rename, so readers never see half a file. */
    static void write(java.io.File f, byte[] data) throws IOException {
        java.io.File tmp = new java.io.File(f.getPath() + ".tmp");
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(tmp)) { o.write(data); }
        if (!tmp.renameTo(f)) throw new IOException("rename failed: " + f);
    }

    /** Copy without closing either stream. */
    static void pipe(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[1 << 16];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
    }
}

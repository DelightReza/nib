package app.nib.notes;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Sticker library: images you import once and place on any page. Kept app-wide in files/stickers (like imported fonts;
 * included in backups as "_stickers/"). Placing one copies it into the notebook's images/, so notebooks stay
 * self-contained. Import takes images or a .zip sticker pack of PNG / WebP / JPEG files.
 */
final class Stickers {
    private Stickers() {}

    static final String BACKUP_DIR = "_stickers/";
    private static final int MAX_PX = 1024;

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "stickers");
        d.mkdirs();
        return d;
    }

    /** Newest first. */
    static List<File> list(Context c) {
        File[] fs = dir(c).listFiles(f -> f.isFile() && f.getName().endsWith(".png"));
        if (fs == null) return new ArrayList<>();
        Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return new ArrayList<>(Arrays.asList(fs));
    }

    /** Imports images and .zip packs; returns how many stickers were added. Background thread. */
    static int importUris(Context c, List<Uri> uris) {
        int n = 0;
        for (Uri u : uris) {
            String type = c.getContentResolver().getType(u);
            String name = u.getLastPathSegment() == null ? "" : u.getLastPathSegment().toLowerCase(Locale.US);
            try {
                if ((type != null && type.contains("zip")) || name.endsWith(".zip")) n += importZip(c, u);
                else if (save(c, ImageDecoder.createSource(c.getContentResolver(), u))) n++;
            } catch (Exception e) {
                Log.w(Gl.TAG, "sticker " + u, e);
            }
        }
        return n;
    }

    private static int importZip(Context c, Uri u) throws IOException {
        int n = 0;
        try (InputStream is = c.getContentResolver().openInputStream(u); ZipInputStream zin = new ZipInputStream(is)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String en = e.getName().toLowerCase(Locale.US);
                if (e.isDirectory() || en.contains("__macosx") || !(en.endsWith(".png") || en.endsWith(".webp") || en.endsWith(".jpg") || en.endsWith(".jpeg"))) continue;
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                Io.pipe(zin, b);
                try {
                    if (save(c, ImageDecoder.createSource(java.nio.ByteBuffer.wrap(b.toByteArray())))) n++;
                } catch (IOException ex) { Log.w(Gl.TAG, "sticker in zip " + en, ex); }
            }
        }
        return n;
    }

    /** Decodes (at most MAX_PX on the long side) and stores as PNG, keeping transparency. */
    private static boolean save(Context c, ImageDecoder.Source src) throws IOException {
        Bitmap b = ImageDecoder.decodeBitmap(src, (dec, info, s) -> {
            int w = info.getSize().getWidth(), h = info.getSize().getHeight(), m = Math.max(w, h);
            if (m > MAX_PX) dec.setTargetSize(Math.max(1, w * MAX_PX / m), Math.max(1, h * MAX_PX / m));
            dec.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
        File f = new File(dir(c), UUID.randomUUID() + ".png");
        try (FileOutputStream o = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
        } finally {
            b.recycle();
        }
        return true;
    }

    /** A small preview for the picker (null if unreadable). */
    static Bitmap thumb(File f, int px) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) return null;
        int s = 1;
        while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= px) s *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = s;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /** Copies a sticker into a notebook's images/; returns {file name, width, height}, or null. */
    static Object[] place(File sticker, File notebookDir) throws IOException {
        File images = new File(notebookDir, "images");
        images.mkdirs();
        String name = "st-" + UUID.randomUUID() + ".png";
        Io.copy(new java.io.FileInputStream(sticker), new FileOutputStream(new File(images, name)));
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(sticker.getPath(), o);
        if (o.outWidth <= 0) return null;
        return new Object[]{name, o.outWidth, o.outHeight};
    }

    /** Backup restore: one file from "_stickers/". */
    static void restoreFile(Context c, String name, InputStream in) throws IOException {
        if (name.isEmpty() || name.contains("/") || name.startsWith(".")) return;
        File f = new File(dir(c), name);
        if (f.exists()) return;
        try (FileOutputStream o = new FileOutputStream(f)) { Io.pipe(in, o); }
    }
}

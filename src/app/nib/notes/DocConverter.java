package app.nib.notes;

import android.content.Context;
import android.net.Uri;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Offline, zero-dependency converter from DOCX, PPTX, and plain text / Markdown files
 * into standard PDF documents (android.graphics.pdf.PdfDocument), so Nib can import
 * and annotate them identically to native PDFs while staying compatible with build-apk.sh.
 */
final class DocConverter {
    private DocConverter() {}

    /**
     * Converts the given document URI to a PDF written at outPdf.
     * Supports .docx, .pptx, .txt, .md, and direct .pdf pass-through.
     */
    static void toPdf(Context c, Uri uri, String displayName, String mime, File outPdf) throws IOException {
        toPdf(c, uri, displayName, mime, outPdf, null);
    }

    static void toPdf(Context c, Uri uri, String displayName, String mime, File outPdf, ProgressPopup.Listener cb) throws IOException {
        if (cb != null) cb.onProgress(0, 0, "Reading document…");
        byte[] raw = readAll(c, uri);
        if (raw.length == 0) throw new IOException("File is empty");

        String lower = displayName != null ? displayName.toLowerCase(Locale.US) : "";
        String lowerMime = mime != null ? mime.toLowerCase(Locale.US) : "";

        // Pass-through if user picked a PDF
        if (lower.endsWith(".pdf") || lowerMime.equals("application/pdf")
                || (raw.length >= 4 && raw[0] == '%' && raw[1] == 'P' && raw[2] == 'D' && raw[3] == 'F')) {
            if (cb != null) cb.onProgress(0, 0, "Preparing PDF…");
            try (FileOutputStream fos = new FileOutputStream(outPdf)) {
                fos.write(raw);
            }
            return;
        }

        boolean isZip = raw.length >= 4 && raw[0] == 'P' && raw[1] == 'K' && raw[2] == 3 && raw[3] == 4;
        if (isZip) {
            if (cb != null) cb.onProgress(0, 0, "Unpacking document…");
            Map<String, byte[]> entries = unzip(raw);
            OoxmlUtil.ZipEntries zip = new OoxmlUtil.ZipEntries(entries);
            if (zip.contains("word/document.xml") || lower.endsWith(".docx")
                    || lowerMime.contains("wordprocessingml")) {
                DocxRenderer.render(entries, outPdf, cb);
                return;
            }
            if (zip.contains("ppt/presentation.xml") || lower.endsWith(".pptx")
                    || lowerMime.contains("presentationml")) {
                PptxRenderer.render(entries, outPdf, cb);
                return;
            }
            throw new IOException("Unsupported archive format. Try DOCX, PPTX, TXT, or PDF.");
        }

        // Legacy binary Office (.doc / .ppt) OLE compound header D0 CF 11 E0
        if (raw.length >= 4 && (raw[0] & 0xFF) == 0xD0 && (raw[1] & 0xFF) == 0xCF
                && (raw[2] & 0xFF) == 0x11 && (raw[3] & 0xFF) == 0xE0) {
            throw new IOException("Legacy .doc/.ppt binary files aren't supported — save as .docx, .pptx, or PDF.");
        }

        // Plain text / Markdown fallback
        String text = new String(raw, StandardCharsets.UTF_8);
        boolean isMarkdown = lower.endsWith(".md") || lower.endsWith(".markdown");
        DocxRenderer.renderPlainText(text, isMarkdown, outPdf, cb);
    }

    private static byte[] readAll(Context c, Uri uri) throws IOException {
        try (InputStream is = c.getContentResolver().openInputStream(uri)) {
            if (is == null) throw new IOException("Could not open file");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Io.pipe(is, out);
            return out.toByteArray();
        }
    }

    private static Map<String, byte[]> unzip(byte[] data) throws IOException {
        HashMap<String, byte[]> map = new HashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (name == null || name.contains("..")) continue;
                name = name.replace('\\', '/');
                while (name.startsWith("/")) name = name.substring(1);
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                Io.pipe(zin, b);
                map.put(name, b.toByteArray());
            }
        }
        return map;
    }
}

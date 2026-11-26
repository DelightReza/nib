package app.nib.notes;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Offline, zero-dependency converter from DOCX, PPTX, and plain text / Markdown files
 * into standard PDF documents (android.graphics.pdf.PdfDocument), so Nib can import
 * and annotate them identically to native PDFs while staying compatible with build-apk.sh.
 */
final class DocConverter {
    private DocConverter() {}

    private static final int A4_W = 595;
    private static final int A4_H = 842;
    private static final int MARGIN_X = 48;
    private static final int MARGIN_Y = 52;
    private static final int CONTENT_W = A4_W - 2 * MARGIN_X;
    private static final int SLIDE_W = 960;
    private static final int SLIDE_H = 540;

    /**
     * Converts the given document URI to a PDF written at outPdf.
     * Supports .docx, .pptx, .txt, .md, and direct .pdf pass-through.
     */
    static void toPdf(Context c, Uri uri, String displayName, String mime, File outPdf) throws IOException {
        byte[] raw = readAll(c, uri);
        if (raw.length == 0) throw new IOException("File is empty");

        String lower = displayName != null ? displayName.toLowerCase(Locale.US) : "";
        String lowerMime = mime != null ? mime.toLowerCase(Locale.US) : "";

        // Pass-through if user picked a PDF
        if (lower.endsWith(".pdf") || lowerMime.equals("application/pdf")
                || (raw.length >= 4 && raw[0] == '%' && raw[1] == 'P' && raw[2] == 'D' && raw[3] == 'F')) {
            try (FileOutputStream fos = new FileOutputStream(outPdf)) {
                fos.write(raw);
            }
            return;
        }

        boolean isZip = raw.length >= 4 && raw[0] == 'P' && raw[1] == 'K' && raw[2] == 3 && raw[3] == 4;
        if (isZip) {
            Map<String, byte[]> entries = unzip(raw);
            if (entries.containsKey("word/document.xml") || lower.endsWith(".docx")
                    || lowerMime.contains("wordprocessingml")) {
                docxToPdf(entries, outPdf);
                return;
            }
            if (entries.containsKey("ppt/presentation.xml") || lower.endsWith(".pptx")
                    || lowerMime.contains("presentationml")) {
                pptxToPdf(entries, outPdf);
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
        textToPdf(text, isMarkdown, outPdf);
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

    // ---- DOCX -> PDF -------------------------------------------------------------------------

    private static final class DocBlock {
        static final int PARA = 0, IMAGE = 1, PAGE_BREAK = 2, TABLE = 3;
        final int kind;
        CharSequence text;
        float fontSize = 11.5f;
        float spaceBefore = 0f;
        float spaceAfter = 8f;
        int indent = 0;
        Layout.Alignment align = Layout.Alignment.ALIGN_NORMAL;
        byte[] imageBytes;
        List<List<String>> rows;

        DocBlock(int kind) { this.kind = kind; }
    }

    private static void docxToPdf(Map<String, byte[]> entries, File outPdf) throws IOException {
        byte[] docXml = entries.get("word/document.xml");
        if (docXml == null) throw new IOException("Invalid DOCX: missing word/document.xml");

        Map<String, String> rels = parseRels(entries.get("word/_rels/document.xml.rels"), "word/");
        List<DocBlock> blocks = parseDocxBlocks(docXml, rels, entries);
        if (blocks.isEmpty()) {
            DocBlock empty = new DocBlock(DocBlock.PARA);
            empty.text = "(Empty document)";
            blocks.add(empty);
        }
        renderFlowBlocksToPdf(blocks, outPdf);
    }

    private static List<DocBlock> parseDocxBlocks(byte[] xml, Map<String, String> rels, Map<String, byte[]> entries) {
        ArrayList<DocBlock> out = new ArrayList<>();
        try {
            XmlPullParser xp = Xml.newPullParser();
            xp.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            xp.setInput(new ByteArrayInputStream(xml), "UTF-8");

            boolean inTable = false;
            boolean inParagraph = false;
            boolean inRun = false;
            boolean inCell = false;

            SpannableStringBuilder paraText = new SpannableStringBuilder();
            StringBuilder cellText = new StringBuilder();
            List<List<String>> tableRows = null;
            List<String> currentRow = null;

            String pStyle = "";
            boolean isListItem = false;
            int listLevel = 0;
            Layout.Alignment pAlign = Layout.Alignment.ALIGN_NORMAL;

            boolean runBold = false;
            boolean runItalic = false;
            int runColor = 0;
            float runFontSize = 0f;
            boolean pendingPageBreak = false;

            int ev = xp.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String tag = localName(xp.getName());
                    if ("tbl".equals(tag) && !inTable) {
                        inTable = true;
                        tableRows = new ArrayList<>();
                    } else if ("tr".equals(tag) && inTable) {
                        currentRow = new ArrayList<>();
                    } else if ("tc".equals(tag) && inTable) {
                        inCell = true;
                        cellText.setLength(0);
                    } else if ("p".equals(tag)) {
                        inParagraph = true;
                        paraText.clear();
                        pStyle = "";
                        isListItem = false;
                        listLevel = 0;
                        pAlign = Layout.Alignment.ALIGN_NORMAL;
                        pendingPageBreak = false;
                    } else if ("pStyle".equals(tag) && inParagraph) {
                        String val = attr(xp, "val");
                        if (val != null) pStyle = val;
                    } else if ("numPr".equals(tag) && inParagraph) {
                        isListItem = true;
                    } else if ("ilvl".equals(tag) && inParagraph) {
                        String v = attr(xp, "val");
                        if (v != null) {
                            try { listLevel = Integer.parseInt(v); } catch (NumberFormatException ignored) { }
                        }
                    } else if ("jc".equals(tag) && inParagraph) {
                        String v = attr(xp, "val");
                        if ("center".equalsIgnoreCase(v)) pAlign = Layout.Alignment.ALIGN_CENTER;
                        else if ("right".equalsIgnoreCase(v) || "end".equalsIgnoreCase(v)) pAlign = Layout.Alignment.ALIGN_OPPOSITE;
                    } else if ("r".equals(tag) && inParagraph) {
                        inRun = true;
                        runBold = false;
                        runItalic = false;
                        runColor = 0;
                        runFontSize = 0f;
                    } else if ("b".equals(tag) && inRun) {
                        String v = attr(xp, "val");
                        runBold = v == null || (!"0".equals(v) && !"false".equalsIgnoreCase(v));
                    } else if ("i".equals(tag) && inRun) {
                        String v = attr(xp, "val");
                        runItalic = v == null || (!"0".equals(v) && !"false".equalsIgnoreCase(v));
                    } else if ("color".equals(tag) && inRun) {
                        String v = attr(xp, "val");
                        runColor = parseHexColor(v);
                    } else if ("sz".equals(tag) && inRun) {
                        String v = attr(xp, "val");
                        if (v != null) {
                            try { runFontSize = Float.parseFloat(v) / 2f; } catch (NumberFormatException ignored) { }
                        }
                    } else if ("t".equals(tag) && inParagraph) {
                        String txt = xp.nextText();
                        if (txt != null && !txt.isEmpty()) {
                            if (inCell) {
                                cellText.append(txt);
                            } else {
                                int start = paraText.length();
                                paraText.append(txt);
                                int end = paraText.length();
                                int style = (runBold ? Typeface.BOLD : 0) | (runItalic ? Typeface.ITALIC : 0);
                                if (style != 0) paraText.setSpan(new StyleSpan(style), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                                if (runColor != 0) paraText.setSpan(new ForegroundColorSpan(runColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                                if (runFontSize > 14f) {
                                    float rel = Math.min(2.0f, runFontSize / 11.5f);
                                    paraText.setSpan(new RelativeSizeSpan(rel), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                                }
                            }
                        }
                    } else if ("tab".equals(tag) && inParagraph) {
                        if (inCell) cellText.append("  ");
                        else paraText.append("    ");
                    } else if ("br".equals(tag) && inParagraph) {
                        String type = attr(xp, "type");
                        if ("page".equalsIgnoreCase(type)) {
                            pendingPageBreak = true;
                        } else if (inCell) {
                            cellText.append("\n");
                        } else {
                            paraText.append("\n");
                        }
                    } else if ("blip".equals(tag) && !inCell) {
                        String rId = attr(xp, "embed");
                        if (rId == null) rId = attr(xp, "link");
                        if (rId != null) {
                            String target = rels.get(rId);
                            byte[] img = target != null ? entries.get(target) : null;
                            if (img != null && img.length > 0) {
                                DocBlock ib = new DocBlock(DocBlock.IMAGE);
                                ib.imageBytes = img;
                                out.add(ib);
                            }
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    String tag = localName(xp.getName());
                    if ("r".equals(tag)) {
                        inRun = false;
                    } else if ("p".equals(tag) && inParagraph) {
                        inParagraph = false;
                        if (inCell) {
                            if (cellText.length() > 0 && cellText.charAt(cellText.length() - 1) != '\n') {
                                cellText.append('\n');
                            }
                        } else {
                            if (paraText.length() > 0) {
                                DocBlock b = new DocBlock(DocBlock.PARA);
                                b.align = pAlign;
                                applyDocxStyle(b, pStyle, paraText);
                                if (isListItem) {
                                    paraText.insert(0, "• ");
                                    b.indent = 16 + Math.min(4, listLevel) * 14;
                                    b.spaceAfter = 5f;
                                }
                                b.text = new SpannableStringBuilder(paraText);
                                out.add(b);
                            } else if (!pendingPageBreak) {
                                // preserve gentle vertical gap for blank spacer paragraphs
                                if (!out.isEmpty() && out.get(out.size() - 1).kind == DocBlock.PARA) {
                                    out.get(out.size() - 1).spaceAfter += 6f;
                                }
                            }
                            if (pendingPageBreak) {
                                out.add(new DocBlock(DocBlock.PAGE_BREAK));
                            }
                        }
                    } else if ("tc".equals(tag) && inCell) {
                        inCell = false;
                        if (currentRow != null) {
                            currentRow.add(cellText.toString().trim());
                        }
                    } else if ("tr".equals(tag) && inTable) {
                        if (currentRow != null && !currentRow.isEmpty() && tableRows != null) {
                            tableRows.add(currentRow);
                        }
                        currentRow = null;
                    } else if ("tbl".equals(tag) && inTable) {
                        inTable = false;
                        if (tableRows != null && !tableRows.isEmpty()) {
                            DocBlock tb = new DocBlock(DocBlock.TABLE);
                            tb.rows = tableRows;
                            out.add(tb);
                        }
                        tableRows = null;
                    }
                }
                ev = xp.next();
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static void applyDocxStyle(DocBlock b, String styleId, SpannableStringBuilder sb) {
        String s = styleId == null ? "" : styleId.toLowerCase(Locale.US);
        if (s.contains("title") && !s.contains("subtitle")) {
            b.fontSize = 22f;
            b.spaceBefore = 6f;
            b.spaceAfter = 12f;
            sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (s.contains("subtitle")) {
            b.fontSize = 14f;
            b.spaceAfter = 10f;
            sb.setSpan(new ForegroundColorSpan(0xFF555962), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (s.equals("heading1") || s.equals("1") || s.endsWith("heading1")) {
            b.fontSize = 18f;
            b.spaceBefore = 14f;
            b.spaceAfter = 8f;
            sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (s.equals("heading2") || s.equals("2") || s.endsWith("heading2")) {
            b.fontSize = 15f;
            b.spaceBefore = 12f;
            b.spaceAfter = 6f;
            sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (s.startsWith("heading") || s.equals("3")) {
            b.fontSize = 13f;
            b.spaceBefore = 10f;
            b.spaceAfter = 5f;
            sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    // ---- PPTX -> PDF -------------------------------------------------------------------------

    private static final class SlideElem {
        static final int TEXT = 0, IMAGE = 1;
        final int kind;
        float x, y, w, h; // normalized [0..1] in slide space, or -1 if unpositioned
        CharSequence text;
        float fontSize = 18f;
        boolean titleLike;
        byte[] imageBytes;

        SlideElem(int kind) { this.kind = kind; this.x = this.y = this.w = this.h = -1f; }
    }

    private static void pptxToPdf(Map<String, byte[]> entries, File outPdf) throws IOException {
        long[] slideEmu = readPptxSlideSize(entries.get("ppt/presentation.xml"));
        float emuW = slideEmu[0] > 0 ? slideEmu[0] : 12192000f; // default 16:9 widescreen
        float emuH = slideEmu[1] > 0 ? slideEmu[1] : 6858000f;

        int pageW = SLIDE_W;
        int pageH = Math.max(400, Math.min(960, Math.round(pageW * (emuH / emuW))));

        // Find slides in natural numeric order (slide1.xml, slide2.xml, ...)
        ArrayList<String> slidePaths = new ArrayList<>();
        Pattern sp = Pattern.compile("^ppt/slides/slide(\\d+)\\.xml$");
        for (String k : entries.keySet()) {
            if (sp.matcher(k).matches()) slidePaths.add(k);
        }
        Collections.sort(slidePaths, (a, b) -> {
            Matcher ma = sp.matcher(a);
            Matcher mb = sp.matcher(b);
            int ia = ma.matches() ? Integer.parseInt(ma.group(1)) : 0;
            int ib = mb.matches() ? Integer.parseInt(mb.group(1)) : 0;
            return Integer.compare(ia, ib);
        });
        if (slidePaths.isEmpty()) throw new IOException("No slides found in PPTX");

        PdfDocument doc = new PdfDocument();
        try {
            Paint bgPaint = new Paint();
            bgPaint.setColor(Color.WHITE);
            Paint numPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            numPaint.setColor(0xFF9AA0AA);
            numPaint.setTextSize(12f);
            numPaint.setTextAlign(Paint.Align.RIGHT);

            for (int i = 0; i < slidePaths.size(); i++) {
                String path = slidePaths.get(i);
                String fileName = path.substring(path.lastIndexOf('/') + 1);
                Map<String, String> rels = parseRels(entries.get("ppt/slides/_rels/" + fileName + ".rels"), "ppt/slides/");
                List<SlideElem> elems = parseSlideElements(entries.get(path), emuW, emuH, rels, entries);

                PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(pageW, pageH, i + 1).create();
                PdfDocument.Page pg = doc.startPage(info);
                Canvas cv = pg.getCanvas();
                cv.drawRect(0, 0, pageW, pageH, bgPaint);

                float fallbackY = 48f;
                for (SlideElem el : elems) {
                    if (el.kind == SlideElem.IMAGE && el.imageBytes != null) {
                        Bitmap bm = decodeScaled(el.imageBytes, 1400);
                        if (bm != null) {
                            try {
                                RectF dst;
                                if (el.x >= 0 && el.w > 0.01f && el.h > 0.01f) {
                                    dst = new RectF(el.x * pageW, el.y * pageH, (el.x + el.w) * pageW, (el.y + el.h) * pageH);
                                } else {
                                    float availW = pageW - 96f;
                                    float availH = Math.max(120f, pageH - fallbackY - 40f);
                                    float sc = Math.min(availW / bm.getWidth(), availH / bm.getHeight());
                                    float dw = bm.getWidth() * sc;
                                    float dh = bm.getHeight() * sc;
                                    dst = new RectF((pageW - dw) / 2f, fallbackY, (pageW + dw) / 2f, fallbackY + dh);
                                    fallbackY += dh + 16f;
                                }
                                Paint ip = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                                cv.drawBitmap(bm, null, dst, ip);
                            } finally {
                                bm.recycle();
                            }
                        }
                    } else if (el.kind == SlideElem.TEXT && el.text != null && el.text.length() > 0) {
                        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                        tp.setColor(0xFF1A1D24);
                        tp.setTextSize(el.fontSize);
                        tp.setTypeface(el.titleLike ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);

                        float x, y, w;
                        if (el.x >= 0 && el.w > 0.04f) {
                            x = Math.max(24f, el.x * pageW);
                            y = Math.max(20f, el.y * pageH);
                            w = Math.max(140f, Math.min(pageW - x - 24f, el.w * pageW));
                        } else {
                            x = 56f;
                            y = fallbackY;
                            w = pageW - 112f;
                        }
                        StaticLayout sl = StaticLayout.Builder.obtain(el.text, 0, el.text.length(), tp, Math.round(w))
                                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                                .setLineSpacing(4f, 1.15f)
                                .build();
                        cv.save();
                        cv.translate(x, y);
                        sl.draw(cv);
                        cv.restore();
                        if (el.x < 0) fallbackY += sl.getHeight() + 18f;
                    }
                }
                cv.drawText((i + 1) + " / " + slidePaths.size(), pageW - 22f, pageH - 16f, numPaint);
                doc.finishPage(pg);
            }
            try (FileOutputStream fos = new FileOutputStream(outPdf)) {
                doc.writeTo(fos);
            }
        } finally {
            doc.close();
        }
    }

    private static long[] readPptxSlideSize(byte[] xml) {
        long[] res = {0, 0};
        if (xml == null) return res;
        try {
            XmlPullParser xp = Xml.newPullParser();
            xp.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            xp.setInput(new ByteArrayInputStream(xml), "UTF-8");
            int ev = xp.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && "sldSz".equals(localName(xp.getName()))) {
                    String cx = attr(xp, "cx");
                    String cy = attr(xp, "cy");
                    if (cx != null) res[0] = Long.parseLong(cx);
                    if (cy != null) res[1] = Long.parseLong(cy);
                    return res;
                }
                ev = xp.next();
            }
        } catch (Exception ignored) { }
        return res;
    }

    private static List<SlideElem> parseSlideElements(byte[] xml, float emuW, float emuH,
                                                      Map<String, String> rels, Map<String, byte[]> entries) {
        ArrayList<SlideElem> out = new ArrayList<>();
        if (xml == null) return out;
        try {
            XmlPullParser xp = Xml.newPullParser();
            xp.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            xp.setInput(new ByteArrayInputStream(xml), "UTF-8");

            boolean inShape = false;
            boolean inPic = false;
            boolean inXfrm = false;
            boolean inPara = false;
            boolean titlePlaceholder = false;
            float sx = -1f, sy = -1f, sw = -1f, sh = -1f;
            float maxFontPt = 0f;
            SpannableStringBuilder shapeText = new SpannableStringBuilder();
            SpannableStringBuilder paraText = new SpannableStringBuilder();
            boolean runBold = false, runItalic = false;
            int runColor = 0;
            String picEmbed = null;

            int ev = xp.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String tag = localName(xp.getName());
                    if ("sp".equals(tag)) {
                        inShape = true;
                        inPic = false;
                        titlePlaceholder = false;
                        sx = sy = sw = sh = -1f;
                        maxFontPt = 0f;
                        shapeText.clear();
                    } else if ("pic".equals(tag)) {
                        inPic = true;
                        inShape = false;
                        sx = sy = sw = sh = -1f;
                        picEmbed = null;
                    } else if ("ph".equals(tag) && inShape) {
                        String t = attr(xp, "type");
                        if ("title".equalsIgnoreCase(t) || "ctrTitle".equalsIgnoreCase(t)) titlePlaceholder = true;
                    } else if ("xfrm".equals(tag) && (inShape || inPic)) {
                        inXfrm = true;
                    } else if ("off".equals(tag) && inXfrm) {
                        sx = parseFloat(attr(xp, "x"), -1f) / emuW;
                        sy = parseFloat(attr(xp, "y"), -1f) / emuH;
                    } else if ("ext".equals(tag) && inXfrm) {
                        sw = parseFloat(attr(xp, "cx"), -1f) / emuW;
                        sh = parseFloat(attr(xp, "cy"), -1f) / emuH;
                    } else if ("blip".equals(tag) && inPic) {
                        picEmbed = attr(xp, "embed");
                    } else if ("p".equals(tag) && inShape) {
                        inPara = true;
                        paraText.clear();
                    } else if ("rPr".equals(tag) && inPara) {
                        String b = attr(xp, "b");
                        String it = attr(xp, "i");
                        runBold = "1".equals(b) || "true".equalsIgnoreCase(b);
                        runItalic = "1".equals(it) || "true".equalsIgnoreCase(it);
                        runColor = 0;
                        String sz = attr(xp, "sz");
                        if (sz != null) {
                            float pt = parseFloat(sz, 0f) / 100f;
                            if (pt > maxFontPt) maxFontPt = pt;
                        }
                    } else if ("srgbClr".equals(tag) && inPara) {
                        int col = parseHexColor(attr(xp, "val"));
                        if (col != 0 && col != Color.WHITE) runColor = col;
                    } else if ("t".equals(tag) && inPara) {
                        String txt = xp.nextText();
                        if (txt != null && !txt.isEmpty()) {
                            int st = paraText.length();
                            paraText.append(txt);
                            int en = paraText.length();
                            int style = (runBold ? Typeface.BOLD : 0) | (runItalic ? Typeface.ITALIC : 0);
                            if (style != 0) paraText.setSpan(new StyleSpan(style), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                            if (runColor != 0) paraText.setSpan(new ForegroundColorSpan(runColor), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                    } else if ("br".equals(tag) && inPara) {
                        paraText.append('\n');
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    String tag = localName(xp.getName());
                    if ("xfrm".equals(tag)) {
                        inXfrm = false;
                    } else if ("p".equals(tag) && inPara) {
                        inPara = false;
                        if (paraText.length() > 0) {
                            if (shapeText.length() > 0) shapeText.append('\n');
                            shapeText.append(paraText);
                        }
                    } else if ("sp".equals(tag) && inShape) {
                        inShape = false;
                        if (shapeText.length() > 0) {
                            SlideElem el = new SlideElem(SlideElem.TEXT);
                            el.x = sx; el.y = sy; el.w = sw; el.h = sh;
                            el.titleLike = titlePlaceholder;
                            el.fontSize = maxFontPt > 0 ? Math.max(12f, Math.min(40f, maxFontPt)) : (titlePlaceholder ? 28f : 18f);
                            el.text = new SpannableStringBuilder(shapeText);
                            out.add(el);
                        }
                    } else if ("pic".equals(tag) && inPic) {
                        inPic = false;
                        if (picEmbed != null) {
                            String target = rels.get(picEmbed);
                            byte[] img = target != null ? entries.get(target) : null;
                            if (img != null && img.length > 0) {
                                SlideElem el = new SlideElem(SlideElem.IMAGE);
                                el.x = sx; el.y = sy; el.w = sw; el.h = sh;
                                el.imageBytes = img;
                                out.add(el);
                            }
                        }
                    }
                }
                ev = xp.next();
            }
        } catch (Exception ignored) { }
        return out;
    }

    // ---- Plain Text / Markdown -> PDF --------------------------------------------------------

    private static void textToPdf(String text, boolean markdown, File outPdf) throws IOException {
        ArrayList<DocBlock> blocks = new ArrayList<>();
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (String rawLine : lines) {
            String line = rawLine;
            if (line.trim().isEmpty()) {
                if (!blocks.isEmpty()) blocks.get(blocks.size() - 1).spaceAfter += 8f;
                continue;
            }
            DocBlock b = new DocBlock(DocBlock.PARA);
            if (markdown && line.startsWith("# ")) {
                b.fontSize = 20f;
                b.spaceBefore = 12f;
                b.spaceAfter = 8f;
                SpannableStringBuilder sb = new SpannableStringBuilder(line.substring(2).trim());
                sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                b.text = sb;
            } else if (markdown && line.startsWith("## ")) {
                b.fontSize = 16f;
                b.spaceBefore = 10f;
                b.spaceAfter = 6f;
                SpannableStringBuilder sb = new SpannableStringBuilder(line.substring(3).trim());
                sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                b.text = sb;
            } else if (markdown && line.startsWith("### ")) {
                b.fontSize = 13.5f;
                b.spaceBefore = 8f;
                b.spaceAfter = 5f;
                SpannableStringBuilder sb = new SpannableStringBuilder(line.substring(4).trim());
                sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                b.text = sb;
            } else if (markdown && (line.startsWith("- ") || line.startsWith("* "))) {
                b.indent = 16;
                b.spaceAfter = 5f;
                b.text = "• " + line.substring(2).trim();
            } else {
                b.text = line;
                b.spaceAfter = 6f;
            }
            blocks.add(b);
        }
        if (blocks.isEmpty()) {
            DocBlock b = new DocBlock(DocBlock.PARA);
            b.text = "(Empty document)";
            blocks.add(b);
        }
        renderFlowBlocksToPdf(blocks, outPdf);
    }

    // ---- Shared A4 Flow Renderer -------------------------------------------------------------

    private static void renderFlowBlocksToPdf(List<DocBlock> blocks, File outPdf) throws IOException {
        PdfDocument doc = new PdfDocument();
        try {
            int pageNum = 1;
            PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder(A4_W, A4_H, pageNum).create());
            Canvas cv = page.getCanvas();
            cv.drawColor(Color.WHITE);
            float y = MARGIN_Y;
            float maxY = A4_H - MARGIN_Y;

            for (DocBlock b : blocks) {
                if (b.kind == DocBlock.PAGE_BREAK) {
                    if (y > MARGIN_Y + 4f) {
                        doc.finishPage(page);
                        pageNum++;
                        page = doc.startPage(new PdfDocument.PageInfo.Builder(A4_W, A4_H, pageNum).create());
                        cv = page.getCanvas();
                        cv.drawColor(Color.WHITE);
                        y = MARGIN_Y;
                    }
                    continue;
                }

                if (b.kind == DocBlock.IMAGE && b.imageBytes != null) {
                    Bitmap bm = decodeScaled(b.imageBytes, 1400);
                    if (bm == null) continue;
                    try {
                        float maxW = CONTENT_W;
                        float maxH = (A4_H - 2 * MARGIN_Y) * 0.85f;
                        float sc = Math.min(1f, Math.min(maxW / bm.getWidth(), maxH / bm.getHeight()));
                        float dw = bm.getWidth() * sc;
                        float dh = bm.getHeight() * sc;
                        if (y + dh > maxY && y > MARGIN_Y + 4f) {
                            doc.finishPage(page);
                            pageNum++;
                            page = doc.startPage(new PdfDocument.PageInfo.Builder(A4_W, A4_H, pageNum).create());
                            cv = page.getCanvas();
                            cv.drawColor(Color.WHITE);
                            y = MARGIN_Y;
                        }
                        float left = MARGIN_X + (CONTENT_W - dw) / 2f;
                        Paint ip = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                        cv.drawBitmap(bm, null, new RectF(left, y, left + dw, y + dh), ip);
                        y += dh + 12f;
                    } finally {
                        bm.recycle();
                    }
                    continue;
                }

                if (b.kind == DocBlock.TABLE && b.rows != null && !b.rows.isEmpty()) {
                    int cols = 1;
                    for (List<String> r : b.rows) cols = Math.max(cols, r.size());
                    float colW = CONTENT_W / (float) cols;
                    TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                    tp.setColor(0xFF1A1D24);
                    tp.setTextSize(10.5f);
                    Paint border = new Paint();
                    border.setStyle(Paint.Style.STROKE);
                    border.setColor(0xFFD0D5DD);
                    border.setStrokeWidth(0.75f);
                    Paint headerBg = new Paint();
                    headerBg.setColor(0xFFF3F5F8);

                    for (int ri = 0; ri < b.rows.size(); ri++) {
                        List<String> r = b.rows.get(ri);
                        StaticLayout[] cells = new StaticLayout[cols];
                        float rowH = 22f;
                        for (int ci = 0; ci < cols; ci++) {
                            String txt = ci < r.size() ? r.get(ci) : "";
                            tp.setTypeface(ri == 0 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                            cells[ci] = StaticLayout.Builder.obtain(txt, 0, txt.length(), tp, Math.max(24, Math.round(colW - 10)))
                                    .setLineSpacing(2f, 1.1f).build();
                            rowH = Math.max(rowH, cells[ci].getHeight() + 10f);
                        }
                        if (y + rowH > maxY && y > MARGIN_Y + 4f) {
                            doc.finishPage(page);
                            pageNum++;
                            page = doc.startPage(new PdfDocument.PageInfo.Builder(A4_W, A4_H, pageNum).create());
                            cv = page.getCanvas();
                            cv.drawColor(Color.WHITE);
                            y = MARGIN_Y;
                        }
                        if (ri == 0) cv.drawRect(MARGIN_X, y, MARGIN_X + CONTENT_W, y + rowH, headerBg);
                        for (int ci = 0; ci < cols; ci++) {
                            float cx = MARGIN_X + ci * colW;
                            cv.drawRect(cx, y, cx + colW, y + rowH, border);
                            cv.save();
                            cv.translate(cx + 5f, y + 5f);
                            cells[ci].draw(cv);
                            cv.restore();
                        }
                        y += rowH;
                    }
                    y += 10f;
                    continue;
                }

                if (b.kind == DocBlock.PARA && b.text != null && b.text.length() > 0) {
                    if (y > MARGIN_Y + 2f) y += b.spaceBefore;
                    TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                    tp.setColor(0xFF1A1D24);
                    tp.setTextSize(b.fontSize);
                    int w = Math.max(80, CONTENT_W - b.indent);
                    StaticLayout full = StaticLayout.Builder.obtain(b.text, 0, b.text.length(), tp, w)
                            .setAlignment(b.align)
                            .setLineSpacing(3f, 1.22f)
                            .build();

                    // Paginate line-by-line so long paragraphs never clip off the bottom of a page
                    int line = 0;
                    int totalLines = full.getLineCount();
                    while (line < totalLines) {
                        int lineTop = full.getLineTop(line);
                        int lineBottom = full.getLineBottom(line);
                        float lh = lineBottom - lineTop;
                        if (y + lh > maxY && y > MARGIN_Y + 4f) {
                            doc.finishPage(page);
                            pageNum++;
                            page = doc.startPage(new PdfDocument.PageInfo.Builder(A4_W, A4_H, pageNum).create());
                            cv = page.getCanvas();
                            cv.drawColor(Color.WHITE);
                            y = MARGIN_Y;
                        }
                        // Find how many consecutive lines fit on this page
                        int endLine = line + 1;
                        while (endLine < totalLines && y + (full.getLineBottom(endLine) - lineTop) <= maxY) {
                            endLine++;
                        }
                        float sliceH = full.getLineBottom(endLine - 1) - lineTop;
                        cv.save();
                        cv.clipRect(MARGIN_X + b.indent, y, MARGIN_X + CONTENT_W, y + sliceH + 2f);
                        cv.translate(MARGIN_X + b.indent, y - lineTop);
                        full.draw(cv);
                        cv.restore();
                        y += sliceH;
                        line = endLine;
                    }
                    y += b.spaceAfter;
                }
            }

            doc.finishPage(page);
            try (FileOutputStream fos = new FileOutputStream(outPdf)) {
                doc.writeTo(fos);
            }
        } finally {
            doc.close();
        }
    }

    // ---- XML & helpers -----------------------------------------------------------------------

    private static Map<String, String> parseRels(byte[] xml, String baseDir) {
        HashMap<String, String> map = new HashMap<>();
        if (xml == null) return map;
        try {
            XmlPullParser xp = Xml.newPullParser();
            xp.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            xp.setInput(new ByteArrayInputStream(xml), "UTF-8");
            int ev = xp.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && "Relationship".equals(localName(xp.getName()))) {
                    String id = attr(xp, "Id");
                    String target = attr(xp, "Target");
                    if (id != null && target != null) {
                        map.put(id, resolveZipPath(baseDir, target));
                    }
                }
                ev = xp.next();
            }
        } catch (Exception ignored) { }
        return map;
    }

    private static String resolveZipPath(String baseDir, String target) {
        String p = target.replace('\\', '/');
        if (p.startsWith("/")) p = p.substring(1);
        else p = baseDir + p;
        String[] parts = p.split("/");
        ArrayList<String> norm = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (!norm.isEmpty()) norm.remove(norm.size() - 1);
            } else {
                norm.add(part);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < norm.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(norm.get(i));
        }
        return sb.toString();
    }

    private static String localName(String qName) {
        if (qName == null) return "";
        int i = qName.indexOf(':');
        return i >= 0 ? qName.substring(i + 1) : qName;
    }

    private static String attr(XmlPullParser xp, String local) {
        for (int i = 0; i < xp.getAttributeCount(); i++) {
            if (local.equals(localName(xp.getAttributeName(i)))) {
                return xp.getAttributeValue(i);
            }
        }
        return null;
    }

    private static float parseFloat(String s, float def) {
        if (s == null) return def;
        try { return Float.parseFloat(s); } catch (NumberFormatException e) { return def; }
    }

    private static int parseHexColor(String hex) {
        if (hex == null || hex.length() != 6 || "auto".equalsIgnoreCase(hex)) return 0;
        try {
            return 0xFF000000 | Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Bitmap decodeScaled(byte[] bytes, int maxDim) {
        try {
            BitmapFactory.Options b = new BitmapFactory.Options();
            b.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, b);
            if (b.outWidth <= 0 || b.outHeight <= 0) return null;
            int sample = 1;
            int longest = Math.max(b.outWidth, b.outHeight);
            while (longest / (sample * 2) >= maxDim) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        } catch (Throwable t) {
            return null;
        }
    }
}

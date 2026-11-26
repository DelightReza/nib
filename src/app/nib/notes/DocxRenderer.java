package app.nib.notes;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.UnderlineSpan;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * High-fidelity offline DOCX -> PDF renderer preserving exact page size, orientation (portrait/landscape),
 * margins, page background, headers/footers, styles.xml inheritance, theme colors, numbering.xml lists,
 * exact run font sizes & families, paragraph spacing/indents/shading/borders, EMU-sized images,
 * and rich formatted tables with real column widths and border styles.
 */
final class DocxRenderer {
    private DocxRenderer() {}

    private static final float TWIPS_PT = OoxmlUtil.TWIPS_PER_PT;
    private static final float EMU_PT = OoxmlUtil.EMU_PER_PT;

    // ---- Page Section Geometry ---------------------------------------------------------------

    static final class SectionSpec {
        float pageW = 595.3f;  // A4 default (11906 twips)
        float pageH = 841.9f;  // A4 default (16838 twips)
        float marginTop = 72f;
        float marginBottom = 72f;
        float marginLeft = 72f;
        float marginRight = 72f;
        float headerDist = 36f;
        float footerDist = 36f;
        int pageBgColor = Color.WHITE;
        String headerText;
        String footerText;

        float contentW() { return Math.max(120f, pageW - marginLeft - marginRight); }
        float contentH() { return Math.max(120f, pageH - marginTop - marginBottom); }

        SectionSpec copy() {
            SectionSpec s = new SectionSpec();
            s.pageW = pageW; s.pageH = pageH;
            s.marginTop = marginTop; s.marginBottom = marginBottom;
            s.marginLeft = marginLeft; s.marginRight = marginRight;
            s.headerDist = headerDist; s.footerDist = footerDist;
            s.pageBgColor = pageBgColor;
            s.headerText = headerText; s.footerText = footerText;
            return s;
        }
    }

    // ---- Resolved Word Style Model -----------------------------------------------------------

    private static final class RunStyle {
        float fontSizePt = 0f;
        Boolean bold;
        Boolean italic;
        Boolean underline;
        Boolean strike;
        Boolean allCaps;
        String vertAlign; // "superscript", "subscript"
        int color = 0;
        int bgColor = 0;
        String fontName;

        RunStyle copy() {
            RunStyle r = new RunStyle();
            r.fontSizePt = fontSizePt; r.bold = bold; r.italic = italic;
            r.underline = underline; r.strike = strike; r.allCaps = allCaps;
            r.vertAlign = vertAlign; r.color = color; r.bgColor = bgColor;
            r.fontName = fontName;
            return r;
        }

        void mergeFrom(RunStyle o) {
            if (o == null) return;
            if (o.fontSizePt > 0f) fontSizePt = o.fontSizePt;
            if (o.bold != null) bold = o.bold;
            if (o.italic != null) italic = o.italic;
            if (o.underline != null) underline = o.underline;
            if (o.strike != null) strike = o.strike;
            if (o.allCaps != null) allCaps = o.allCaps;
            if (o.vertAlign != null) vertAlign = o.vertAlign;
            if (o.color != 0) color = o.color;
            if (o.bgColor != 0) bgColor = o.bgColor;
            if (o.fontName != null) fontName = o.fontName;
        }
    }

    private static final class ParaStyle {
        Layout.Alignment align;
        boolean justify;
        float spaceBeforePt = -1f;
        float spaceAfterPt = -1f;
        float lineMult = 0f;
        float lineExactPt = 0f;
        float indLeftPt = Float.NaN;
        float indRightPt = Float.NaN;
        float firstLinePt = Float.NaN;
        int shdColor = 0;
        int borderBottomColor = 0;
        float borderBottomWidth = 0f;
        int borderLeftColor = 0;
        float borderLeftWidth = 0f;
        int borderTopColor = 0;
        float borderTopWidth = 0f;
        boolean pageBreakBefore;
        String numId;
        int ilvl = -1;
        RunStyle rStyle = new RunStyle();

        ParaStyle copy() {
            ParaStyle p = new ParaStyle();
            p.align = align; p.justify = justify;
            p.spaceBeforePt = spaceBeforePt; p.spaceAfterPt = spaceAfterPt;
            p.lineMult = lineMult; p.lineExactPt = lineExactPt;
            p.indLeftPt = indLeftPt; p.indRightPt = indRightPt; p.firstLinePt = firstLinePt;
            p.shdColor = shdColor;
            p.borderBottomColor = borderBottomColor; p.borderBottomWidth = borderBottomWidth;
            p.borderLeftColor = borderLeftColor; p.borderLeftWidth = borderLeftWidth;
            p.borderTopColor = borderTopColor; p.borderTopWidth = borderTopWidth;
            p.pageBreakBefore = pageBreakBefore;
            p.numId = numId; p.ilvl = ilvl;
            p.rStyle = rStyle.copy();
            return p;
        }

        void mergeFrom(ParaStyle o) {
            if (o == null) return;
            if (o.align != null) { align = o.align; justify = o.justify; }
            if (o.spaceBeforePt >= 0f) spaceBeforePt = o.spaceBeforePt;
            if (o.spaceAfterPt >= 0f) spaceAfterPt = o.spaceAfterPt;
            if (o.lineMult > 0f) { lineMult = o.lineMult; lineExactPt = 0f; }
            if (o.lineExactPt > 0f) { lineExactPt = o.lineExactPt; lineMult = 0f; }
            if (!Float.isNaN(o.indLeftPt)) indLeftPt = o.indLeftPt;
            if (!Float.isNaN(o.indRightPt)) indRightPt = o.indRightPt;
            if (!Float.isNaN(o.firstLinePt)) firstLinePt = o.firstLinePt;
            if (o.shdColor != 0) shdColor = o.shdColor;
            if (o.borderBottomColor != 0) { borderBottomColor = o.borderBottomColor; borderBottomWidth = o.borderBottomWidth; }
            if (o.borderLeftColor != 0) { borderLeftColor = o.borderLeftColor; borderLeftWidth = o.borderLeftWidth; }
            if (o.borderTopColor != 0) { borderTopColor = o.borderTopColor; borderTopWidth = o.borderTopWidth; }
            if (o.pageBreakBefore) pageBreakBefore = true;
            if (o.numId != null) numId = o.numId;
            if (o.ilvl >= 0) ilvl = o.ilvl;
            rStyle.mergeFrom(o.rStyle);
        }
    }

    private static final class StyleDef {
        String id;
        String basedOn;
        boolean isDefault;
        String type; // paragraph, character
        ParaStyle pStyle = new ParaStyle();
    }

    private static final class NumLevel {
        String numFmt = "bullet";
        String lvlText = "•";
        int start = 1;
        float indLeftPt = 24f;
        float hangingPt = 14f;
        String bulletFont;
    }

    // ---- Flow Blocks -------------------------------------------------------------------------

    static final class Block {
        static final int PARA = 0, IMAGE = 1, PAGE_BREAK = 2, TABLE = 3, SECTION_BREAK = 4;
        final int kind;
        CharSequence text;
        float baseFontPt = 11f;
        float spaceBefore = 0f;
        float spaceAfter = 6f;
        float lineMult = 1.15f;
        float indLeft = 0f;
        float indRight = 0f;
        Layout.Alignment align = Layout.Alignment.ALIGN_NORMAL;
        boolean justify;
        int shdColor = 0;
        int borderBottomColor = 0; float borderBottomW = 0f;
        int borderLeftColor = 0; float borderLeftW = 0f;
        int borderTopColor = 0; float borderTopW = 0f;
        boolean isSpacer;

        // Image
        byte[] imageBytes;
        float imgWpt, imgHpt;

        // Table
        TableModel table;

        // Section switch
        SectionSpec nextSection;

        Block(int kind) { this.kind = kind; }
    }

    private static final class TableModel {
        final List<Float> gridColPt = new ArrayList<>();
        final List<TableRow> rows = new ArrayList<>();
        boolean hasOuterBorder = true;
        boolean hasInnerBorder = true;
        int borderColor = 0xFFC5CBD3;
        float borderW = 0.6f;
        float defaultPadX = 5f, defaultPadY = 4f;
        float totalWidthPt = 0f;
    }

    private static final class TableRow {
        final List<TableCell> cells = new ArrayList<>();
        float minHeightPt = 18f;
        boolean isHeader;
    }

    private static final class TableCell {
        final List<Block> blocks = new ArrayList<>();
        int gridSpan = 1;
        float widthPt = 0f;
        int shdColor = 0;
        int vAlign = 0; // 0=top, 1=center, 2=bottom
        boolean noBorders;
        boolean vMergeContinue;
    }

    private static final class DocCtx {
        final OoxmlUtil.ZipEntries entries;
        final Map<String, OoxmlUtil.Rel> rels;
        final OoxmlUtil.Theme theme;
        final ParaStyle docDefault = new ParaStyle();
        final Map<String, StyleDef> styles = new HashMap<>();
        final Map<String, ParaStyle> resolvedParaStyles = new HashMap<>();
        final Map<String, RunStyle> resolvedCharStyles = new HashMap<>();
        String defaultParaStyleId;
        final Map<String, Map<Integer, NumLevel>> abstractNums = new HashMap<>();
        final Map<String, String> numToAbstract = new HashMap<>();
        final Map<String, int[]> numCounters = new HashMap<>();
        int pageBgColor = Color.WHITE;

        DocCtx(OoxmlUtil.ZipEntries entries, Map<String, OoxmlUtil.Rel> rels, OoxmlUtil.Theme theme) {
            this.entries = entries;
            this.rels = rels;
            this.theme = theme;
            docDefault.rStyle.fontSizePt = 11f;
            docDefault.rStyle.color = 0xFF1A1D24;
            docDefault.rStyle.fontName = theme != null ? theme.minorFont : "Calibri";
            docDefault.spaceBeforePt = 0f;
            docDefault.spaceAfterPt = 6f;
            docDefault.lineMult = 1.15f;
        }

        ParaStyle resolveParaStyle(String styleId) {
            String key = (styleId == null || styleId.isEmpty()) ? (defaultParaStyleId != null ? defaultParaStyleId : "") : styleId;
            if (resolvedParaStyles.containsKey(key)) return resolvedParaStyles.get(key);
            ParaStyle out = resolveParaStyleRec(key, new HashSet<>());
            resolvedParaStyles.put(key, out);
            return out;
        }

        private ParaStyle resolveParaStyleRec(String id, Set<String> seen) {
            ParaStyle base = docDefault.copy();
            if (id == null || id.isEmpty() || !seen.add(id)) return base;
            StyleDef def = styles.get(id);
            if (def == null) return base;
            if (def.basedOn != null && !def.basedOn.isEmpty()) {
                base = resolveParaStyleRec(def.basedOn, seen);
            }
            base.mergeFrom(def.pStyle);
            return base;
        }

        RunStyle resolveCharStyle(String styleId) {
            if (styleId == null || styleId.isEmpty()) return null;
            if (resolvedCharStyles.containsKey(styleId)) return resolvedCharStyles.get(styleId);
            RunStyle out = resolveCharStyleRec(styleId, new HashSet<>());
            resolvedCharStyles.put(styleId, out);
            return out;
        }

        private RunStyle resolveCharStyleRec(String id, Set<String> seen) {
            if (id == null || id.isEmpty() || !seen.add(id)) return new RunStyle();
            StyleDef def = styles.get(id);
            if (def == null) return new RunStyle();
            RunStyle base = (def.basedOn != null && !def.basedOn.isEmpty())
                    ? resolveCharStyleRec(def.basedOn, seen) : new RunStyle();
            base.mergeFrom(def.pStyle.rStyle);
            return base;
        }
    }

    // ---- Entry Points ------------------------------------------------------------------------

    static void render(Map<String, byte[]> rawEntries, File outPdf) throws IOException {
        OoxmlUtil.ZipEntries entries = new OoxmlUtil.ZipEntries(rawEntries);
        Document docXml = OoxmlUtil.parseXml(entries.get("word/document.xml"));
        if (docXml == null) throw new IOException("Invalid DOCX: missing or unreadable word/document.xml");

        Map<String, OoxmlUtil.Rel> rels = OoxmlUtil.parseRels(entries, "word/_rels/document.xml.rels", "word/");
        String themePath = OoxmlUtil.findRelTargetByTypeSuffix(rels, "/theme");
        if (themePath == null) themePath = "word/theme/theme1.xml";
        OoxmlUtil.Theme theme = OoxmlUtil.parseTheme(entries.get(themePath));

        DocCtx ctx = new DocCtx(entries, rels, theme);
        parseStyles(entries.get("word/styles.xml"), ctx);
        parseNumbering(entries.get("word/numbering.xml"), ctx);

        Element root = docXml.getDocumentElement();
        Element bgEl = OoxmlUtil.child(root, "background");
        if (bgEl != null) {
            int bgCol = resolveWordColor(bgEl, "color", ctx.theme);
            if (bgCol != 0) ctx.pageBgColor = bgCol;
        }

        Element body = OoxmlUtil.child(root, "body");
        if (body == null) throw new IOException("Invalid DOCX: missing document body");

        // Final body <w:sectPr> defines the default (or last) section's page format
        SectionSpec finalSec = parseSectPr(OoxmlUtil.child(body, "sectPr"), ctx);

        // Scan body children, associating any inline <w:pPr><w:sectPr> with the blocks BEFORE it
        List<SectionSlice> slices = new ArrayList<>();
        List<Block> curBlocks = new ArrayList<>();
        for (Element ch : OoxmlUtil.children(body, null)) {
            String tag = OoxmlUtil.localName(ch);
            if ("p".equals(tag)) {
                Element pPr = OoxmlUtil.child(ch, "pPr");
                Element inlineSect = pPr != null ? OoxmlUtil.child(pPr, "sectPr") : null;
                parseParagraphInto(ch, ctx, curBlocks);
                if (inlineSect != null) {
                    SectionSpec sec = parseSectPr(inlineSect, ctx);
                    slices.add(new SectionSlice(sec, curBlocks));
                    curBlocks = new ArrayList<>();
                }
            } else if ("tbl".equals(tag)) {
                TableModel tm = parseTable(ch, ctx);
                if (tm != null && !tm.rows.isEmpty()) {
                    Block b = new Block(Block.TABLE);
                    b.table = tm;
                    curBlocks.add(b);
                }
            } else if ("sdt".equals(tag)) {
                Element content = OoxmlUtil.child(ch, "sdtContent");
                if (content != null) parseContainerInto(content, ctx, curBlocks);
            }
        }
        if (!curBlocks.isEmpty() || slices.isEmpty()) {
            slices.add(new SectionSlice(finalSec, curBlocks));
        }

        // Flatten sections into a single block stream with SECTION_BREAK markers
        SectionSpec initialSec = slices.get(0).spec;
        ArrayList<Block> allBlocks = new ArrayList<>();
        for (int si = 0; si < slices.size(); si++) {
            SectionSlice sl = slices.get(si);
            if (si > 0) {
                Block sb = new Block(Block.SECTION_BREAK);
                sb.nextSection = sl.spec;
                allBlocks.add(sb);
            }
            allBlocks.addAll(sl.blocks);
        }
        if (allBlocks.isEmpty()) {
            Block empty = new Block(Block.PARA);
            empty.text = "(Empty document)";
            allBlocks.add(empty);
        }
        renderFlowToPdf(initialSec, allBlocks, outPdf);
    }

    static void renderPlainText(String text, boolean markdown, File outPdf) throws IOException {
        ArrayList<Block> blocks = new ArrayList<>();
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                if (!blocks.isEmpty()) blocks.get(blocks.size() - 1).spaceAfter += 8f;
                continue;
            }
            Block b = new Block(Block.PARA);
            b.lineMult = 1.22f;
            if (markdown && line.startsWith("# ")) {
                b.baseFontPt = 20f; b.spaceBefore = 12f; b.spaceAfter = 8f;
                b.text = styledText(line.substring(2).trim(), 20f, true);
            } else if (markdown && line.startsWith("## ")) {
                b.baseFontPt = 16f; b.spaceBefore = 10f; b.spaceAfter = 6f;
                b.text = styledText(line.substring(3).trim(), 16f, true);
            } else if (markdown && line.startsWith("### ")) {
                b.baseFontPt = 13.5f; b.spaceBefore = 8f; b.spaceAfter = 5f;
                b.text = styledText(line.substring(4).trim(), 13.5f, true);
            } else if (markdown && (line.startsWith("- ") || line.startsWith("* "))) {
                b.indLeft = 16f; b.spaceAfter = 5f;
                b.text = styledText("• " + line.substring(2).trim(), 11f, false);
            } else {
                b.spaceAfter = 6f;
                b.text = styledText(line, 11f, false);
            }
            blocks.add(b);
        }
        if (blocks.isEmpty()) {
            Block b = new Block(Block.PARA);
            b.text = styledText("(Empty document)", 11f, false);
            blocks.add(b);
        }
        SectionSpec a4 = new SectionSpec();
        a4.marginLeft = a4.marginRight = 50f;
        a4.marginTop = a4.marginBottom = 54f;
        renderFlowToPdf(a4, blocks, outPdf);
    }

    private static SpannableStringBuilder styledText(String s, float pt, boolean bold) {
        SpannableStringBuilder sb = new SpannableStringBuilder(s);
        sb.setSpan(new OoxmlUtil.ExactSizeSpan(pt), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new OoxmlUtil.FontSpan(OoxmlUtil.mapFontFamily(null, bold, false)), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(0xFF1A1D24), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private static final class SectionSlice {
        final SectionSpec spec;
        final List<Block> blocks;
        SectionSlice(SectionSpec spec, List<Block> blocks) { this.spec = spec; this.blocks = blocks; }
    }

    // ---- Section / Header / Footer Parsing ---------------------------------------------------

    private static SectionSpec parseSectPr(Element sectPr, DocCtx ctx) {
        SectionSpec s = new SectionSpec();
        s.pageBgColor = ctx.pageBgColor;
        if (sectPr == null) return s;

        Element pgSz = OoxmlUtil.child(sectPr, "pgSz");
        if (pgSz != null) {
            float w = OoxmlUtil.attrFloat(pgSz, "w", 11906f) / TWIPS_PT;
            float h = OoxmlUtil.attrFloat(pgSz, "h", 16838f) / TWIPS_PT;
            String orient = OoxmlUtil.attr(pgSz, "orient");
            if ("landscape".equalsIgnoreCase(orient) && w < h) {
                float tmp = w; w = h; h = tmp;
            }
            if (w >= 140f && h >= 140f) {
                s.pageW = w;
                s.pageH = h;
            }
        }
        Element pgMar = OoxmlUtil.child(sectPr, "pgMar");
        if (pgMar != null) {
            s.marginTop = clampMargin(OoxmlUtil.attrFloat(pgMar, "top", 1440f) / TWIPS_PT, s.pageH * 0.3f);
            s.marginBottom = clampMargin(OoxmlUtil.attrFloat(pgMar, "bottom", 1440f) / TWIPS_PT, s.pageH * 0.3f);
            s.marginLeft = clampMargin(OoxmlUtil.attrFloat(pgMar, "left", 1440f) / TWIPS_PT, s.pageW * 0.35f);
            s.marginRight = clampMargin(OoxmlUtil.attrFloat(pgMar, "right", 1440f) / TWIPS_PT, s.pageW * 0.35f);
            s.headerDist = Math.max(12f, OoxmlUtil.attrFloat(pgMar, "header", 720f) / TWIPS_PT);
            s.footerDist = Math.max(12f, OoxmlUtil.attrFloat(pgMar, "footer", 720f) / TWIPS_PT);
        }

        for (Element hr : OoxmlUtil.children(sectPr, "headerReference")) {
            String type = OoxmlUtil.attr(hr, "type");
            if (type == null || "default".equalsIgnoreCase(type)) {
                s.headerText = extractHeaderFooterText(OoxmlUtil.attr(hr, "id"), ctx);
                break;
            }
        }
        for (Element fr : OoxmlUtil.children(sectPr, "footerReference")) {
            String type = OoxmlUtil.attr(fr, "type");
            if (type == null || "default".equalsIgnoreCase(type)) {
                s.footerText = extractHeaderFooterText(OoxmlUtil.attr(fr, "id"), ctx);
                break;
            }
        }
        return s;
    }

    private static float clampMargin(float m, float max) {
        return Math.max(18f, Math.min(max, m));
    }

    private static String extractHeaderFooterText(String rId, DocCtx ctx) {
        if (rId == null) return null;
        OoxmlUtil.Rel rel = ctx.rels.get(rId);
        if (rel == null || rel.external) return null;
        Document d = OoxmlUtil.parseXml(ctx.entries.get(rel.target));
        if (d == null) return null;
        StringBuilder sb = new StringBuilder();
        collectPlainText(d.getDocumentElement(), sb);
        String out = sb.toString().replaceAll("\\s+", " ").trim();
        return out.isEmpty() ? null : out;
    }

    private static void collectPlainText(Element el, StringBuilder sb) {
        if (el == null) return;
        for (Node n = el.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element ch = (Element) n;
            String tag = OoxmlUtil.localName(ch);
            if ("Fallback".equals(tag) || "del".equals(tag) || "instrText".equals(tag)) continue;
            if ("t".equals(tag)) sb.append(ch.getTextContent());
            else if ("tab".equals(tag)) sb.append("   ");
            else if ("br".equals(tag)) sb.append(' ');
            else collectPlainText(ch, sb);
        }
    }

    // ---- styles.xml & numbering.xml Parsing --------------------------------------------------

    private static void parseStyles(byte[] xml, DocCtx ctx) {
        Document doc = OoxmlUtil.parseXml(xml);
        if (doc == null) return;
        Element root = doc.getDocumentElement();

        Element docDefaults = OoxmlUtil.child(root, "docDefaults");
        if (docDefaults != null) {
            Element rPrDef = OoxmlUtil.child(OoxmlUtil.child(docDefaults, "rPrDefault"), "rPr");
            if (rPrDef != null) ctx.docDefault.rStyle.mergeFrom(parseRunPr(rPrDef, ctx.theme));
            Element pPrDef = OoxmlUtil.child(OoxmlUtil.child(docDefaults, "pPrDefault"), "pPr");
            if (pPrDef != null) ctx.docDefault.mergeFrom(parseParaPr(pPrDef, ctx.theme));
        }

        for (Element st : OoxmlUtil.children(root, "style")) {
            String id = OoxmlUtil.attr(st, "styleId");
            if (id == null) continue;
            StyleDef def = new StyleDef();
            def.id = id;
            def.type = OoxmlUtil.attr(st, "type");
            def.isDefault = OoxmlUtil.attrBool(st, "default", false);
            Element basedOn = OoxmlUtil.child(st, "basedOn");
            if (basedOn != null) def.basedOn = OoxmlUtil.attr(basedOn, "val");
            Element pPr = OoxmlUtil.child(st, "pPr");
            if (pPr != null) def.pStyle.mergeFrom(parseParaPr(pPr, ctx.theme));
            Element rPr = OoxmlUtil.child(st, "rPr");
            if (rPr != null) def.pStyle.rStyle.mergeFrom(parseRunPr(rPr, ctx.theme));
            ctx.styles.put(id, def);
            if (def.isDefault && "paragraph".equals(def.type)) {
                ctx.defaultParaStyleId = id;
            }
        }
    }

    private static void parseNumbering(byte[] xml, DocCtx ctx) {
        Document doc = OoxmlUtil.parseXml(xml);
        if (doc == null) return;
        Element root = doc.getDocumentElement();
        for (Element an : OoxmlUtil.children(root, "abstractNum")) {
            String anId = OoxmlUtil.attr(an, "abstractNumId");
            if (anId == null) continue;
            HashMap<Integer, NumLevel> lvls = new HashMap<>();
            for (Element lvl : OoxmlUtil.children(an, "lvl")) {
                int ilvl = OoxmlUtil.attrInt(lvl, "ilvl", 0);
                NumLevel nl = new NumLevel();
                Element nf = OoxmlUtil.child(lvl, "numFmt");
                if (nf != null && OoxmlUtil.attr(nf, "val") != null) nl.numFmt = OoxmlUtil.attr(nf, "val");
                Element lt = OoxmlUtil.child(lvl, "lvlText");
                if (lt != null && OoxmlUtil.attr(lt, "val") != null) nl.lvlText = OoxmlUtil.attr(lt, "val");
                Element st = OoxmlUtil.child(lvl, "start");
                if (st != null) nl.start = OoxmlUtil.attrInt(st, "val", 1);
                Element ind = OoxmlUtil.child(OoxmlUtil.child(lvl, "pPr"), "ind");
                if (ind != null) {
                    float left = OoxmlUtil.attrFloat(ind, "left", 0f) / TWIPS_PT;
                    if (left > 0f) nl.indLeftPt = Math.min(180f, left);
                    float hang = OoxmlUtil.attrFloat(ind, "hanging", 0f) / TWIPS_PT;
                    if (hang > 0f) nl.hangingPt = Math.min(48f, hang);
                } else {
                    nl.indLeftPt = 18f + ilvl * 18f;
                }
                Element lvlRFonts = OoxmlUtil.child(OoxmlUtil.child(lvl, "rPr"), "rFonts");
                if (lvlRFonts != null) {
                    String bf = OoxmlUtil.attr(lvlRFonts, "ascii");
                    if (bf == null) bf = OoxmlUtil.attr(lvlRFonts, "hAnsi");
                    nl.bulletFont = bf;
                }
                lvls.put(ilvl, nl);
            }
            ctx.abstractNums.put(anId, lvls);
        }
        for (Element num : OoxmlUtil.children(root, "num")) {
            String numId = OoxmlUtil.attr(num, "numId");
            Element absRef = OoxmlUtil.child(num, "abstractNumId");
            String absId = absRef != null ? OoxmlUtil.attr(absRef, "val") : null;
            if (numId != null && absId != null) ctx.numToAbstract.put(numId, absId);
        }
    }

    private static ParaStyle parseParaPr(Element pPr, OoxmlUtil.Theme theme) {
        ParaStyle ps = new ParaStyle();
        if (pPr == null) return ps;
        Element jc = OoxmlUtil.child(pPr, "jc");
        if (jc != null) {
            String v = OoxmlUtil.attr(jc, "val");
            if ("center".equalsIgnoreCase(v)) ps.align = Layout.Alignment.ALIGN_CENTER;
            else if ("right".equalsIgnoreCase(v) || "end".equalsIgnoreCase(v)) ps.align = Layout.Alignment.ALIGN_OPPOSITE;
            else if ("both".equalsIgnoreCase(v) || "distribute".equalsIgnoreCase(v)) {
                ps.align = Layout.Alignment.ALIGN_NORMAL;
                ps.justify = true;
            } else if ("left".equalsIgnoreCase(v) || "start".equalsIgnoreCase(v)) {
                ps.align = Layout.Alignment.ALIGN_NORMAL;
            }
        }
        if (jc == null && OoxmlUtil.child(pPr, "bidi") != null && OoxmlUtil.isWordToggleOn(OoxmlUtil.child(pPr, "bidi"))) {
            ps.align = Layout.Alignment.ALIGN_OPPOSITE;
        }
        Element spc = OoxmlUtil.child(pPr, "spacing");
        if (spc != null) {
            if (OoxmlUtil.attr(spc, "before") != null) ps.spaceBeforePt = Math.max(0f, OoxmlUtil.attrFloat(spc, "before", 0f) / TWIPS_PT);
            if (OoxmlUtil.attr(spc, "after") != null) ps.spaceAfterPt = Math.max(0f, OoxmlUtil.attrFloat(spc, "after", 0f) / TWIPS_PT);
            if (OoxmlUtil.attr(spc, "line") != null) {
                float line = OoxmlUtil.attrFloat(spc, "line", 240f);
                String rule = OoxmlUtil.attr(spc, "lineRule");
                if ("exact".equalsIgnoreCase(rule) || "atLeast".equalsIgnoreCase(rule)) {
                    ps.lineExactPt = Math.max(8f, line / TWIPS_PT);
                } else {
                    float mult = line / 240f;
                    if (mult >= 0.8f && mult <= 3.5f) ps.lineMult = Math.max(0.92f, mult * 0.92f);
                }
            }
        }
        Element ind = OoxmlUtil.child(pPr, "ind");
        if (ind != null) {
            String l = OoxmlUtil.attr(ind, "left");
            if (l == null) l = OoxmlUtil.attr(ind, "start");
            if (l != null) ps.indLeftPt = Math.max(0f, parseFloatSafe(l, 0f) / TWIPS_PT);
            String r = OoxmlUtil.attr(ind, "right");
            if (r == null) r = OoxmlUtil.attr(ind, "end");
            if (r != null) ps.indRightPt = Math.max(0f, parseFloatSafe(r, 0f) / TWIPS_PT);
            if (OoxmlUtil.attr(ind, "firstLine") != null) {
                ps.firstLinePt = Math.max(0f, OoxmlUtil.attrFloat(ind, "firstLine", 0f) / TWIPS_PT);
            } else if (OoxmlUtil.attr(ind, "hanging") != null) {
                ps.firstLinePt = -Math.max(0f, OoxmlUtil.attrFloat(ind, "hanging", 0f) / TWIPS_PT);
            }
        }
        Element shd = OoxmlUtil.child(pPr, "shd");
        if (shd != null) {
            int col = resolveWordColor(shd, "fill", theme);
            if (col != 0 && col != Color.WHITE) ps.shdColor = col;
        }
        Element pBdr = OoxmlUtil.child(pPr, "pBdr");
        if (pBdr != null) {
            Element bot = OoxmlUtil.child(pBdr, "bottom");
            if (bot != null && isVisibleBorder(bot)) {
                ps.borderBottomColor = borderColor(bot, theme);
                ps.borderBottomWidth = borderWidthPt(bot);
            }
            Element top = OoxmlUtil.child(pBdr, "top");
            if (top != null && isVisibleBorder(top)) {
                ps.borderTopColor = borderColor(top, theme);
                ps.borderTopWidth = borderWidthPt(top);
            }
            Element left = OoxmlUtil.child(pBdr, "left");
            if (left != null && isVisibleBorder(left)) {
                ps.borderLeftColor = borderColor(left, theme);
                ps.borderLeftWidth = borderWidthPt(left);
            }
        }
        if (OoxmlUtil.child(pPr, "pageBreakBefore") != null && OoxmlUtil.isWordToggleOn(OoxmlUtil.child(pPr, "pageBreakBefore"))) {
            ps.pageBreakBefore = true;
        }
        Element numPr = OoxmlUtil.child(pPr, "numPr");
        if (numPr != null) {
            Element nid = OoxmlUtil.child(numPr, "numId");
            if (nid != null) ps.numId = OoxmlUtil.attr(nid, "val");
            Element il = OoxmlUtil.child(numPr, "ilvl");
            if (il != null) ps.ilvl = OoxmlUtil.attrInt(il, "val", 0);
        }
        Element rPr = OoxmlUtil.child(pPr, "rPr");
        if (rPr != null) ps.rStyle.mergeFrom(parseRunPr(rPr, theme));
        return ps;
    }

    private static RunStyle parseRunPr(Element rPr, OoxmlUtil.Theme theme) {
        RunStyle rs = new RunStyle();
        if (rPr == null) return rs;
        Element b = OoxmlUtil.child(rPr, "b");
        if (b == null) b = OoxmlUtil.child(rPr, "bCs");
        if (b != null) rs.bold = OoxmlUtil.isWordToggleOn(b);
        Element i = OoxmlUtil.child(rPr, "i");
        if (i == null) i = OoxmlUtil.child(rPr, "iCs");
        if (i != null) rs.italic = OoxmlUtil.isWordToggleOn(i);
        Element u = OoxmlUtil.child(rPr, "u");
        if (u != null) rs.underline = OoxmlUtil.isWordToggleOn(u);
        Element strike = OoxmlUtil.child(rPr, "strike");
        if (strike != null) rs.strike = OoxmlUtil.isWordToggleOn(strike);
        Element caps = OoxmlUtil.child(rPr, "caps");
        if (caps != null) rs.allCaps = OoxmlUtil.isWordToggleOn(caps);
        Element va = OoxmlUtil.child(rPr, "vertAlign");
        if (va != null) rs.vertAlign = OoxmlUtil.attr(va, "val");

        Element sz = OoxmlUtil.child(rPr, "sz");
        if (sz == null) sz = OoxmlUtil.child(rPr, "szCs");
        if (sz != null) {
            float hp = OoxmlUtil.attrFloat(sz, "val", 0f);
            if (hp > 0f) rs.fontSizePt = hp / 2f;
        }
        Element color = OoxmlUtil.child(rPr, "color");
        if (color != null) {
            int c = resolveWordColor(color, "val", theme);
            if (c != 0) rs.color = c;
        }
        Element hi = OoxmlUtil.child(rPr, "highlight");
        if (hi != null) {
            int hc = wordHighlightColor(OoxmlUtil.attr(hi, "val"));
            if (hc != 0) rs.bgColor = hc;
        }
        Element shd = OoxmlUtil.child(rPr, "shd");
        if (shd != null && rs.bgColor == 0) {
            int sc = resolveWordColor(shd, "fill", theme);
            if (sc != 0 && sc != Color.WHITE) rs.bgColor = sc;
        }
        Element rFonts = OoxmlUtil.child(rPr, "rFonts");
        if (rFonts != null) {
            String f = OoxmlUtil.attr(rFonts, "ascii");
            if (f == null) f = OoxmlUtil.attr(rFonts, "hAnsi");
            if (f == null) {
                String at = OoxmlUtil.attr(rFonts, "asciiTheme");
                if (at == null) at = OoxmlUtil.attr(rFonts, "hAnsiTheme");
                if (at != null && theme != null) {
                    f = at.startsWith("major") ? theme.majorFont : theme.minorFont;
                }
            }
            if (f != null && !f.isEmpty()) rs.fontName = f;
        }
        return rs;
    }

    private static int resolveWordColor(Element el, String valAttr, OoxmlUtil.Theme theme) {
        if (el == null) return 0;
        String val = OoxmlUtil.attr(el, valAttr);
        if (val != null && !"auto".equalsIgnoreCase(val) && !"none".equalsIgnoreCase(val) && !"clear".equalsIgnoreCase(val)) {
            int c = OoxmlUtil.parseHexColor(val);
            if (c != 0) return c;
        }
        String tc = OoxmlUtil.attr(el, "themeColor");
        if (tc == null) tc = OoxmlUtil.attr(el, "themeFill");
        if (tc != null) {
            String mapped = mapWordThemeColorName(tc);
            int base = OoxmlUtil.resolveSchemeColor(mapped, theme, OoxmlUtil.defaultColorMap(), 0);
            if (base != 0) {
                String tint = OoxmlUtil.attr(el, "themeTint");
                if (tint == null) tint = OoxmlUtil.attr(el, "themeFillTint");
                String shade = OoxmlUtil.attr(el, "themeShade");
                if (shade == null) shade = OoxmlUtil.attr(el, "themeFillShade");
                return OoxmlUtil.applyWordThemeTintShade(base, tint, shade);
            }
        }
        return 0;
    }

    private static String mapWordThemeColorName(String wName) {
        switch (wName) {
            case "dark1": case "text1": return "dk1";
            case "light1": case "background1": return "lt1";
            case "dark2": case "text2": return "dk2";
            case "light2": case "background2": return "lt2";
            case "hyperlink": return "hlink";
            case "followedHyperlink": return "folHlink";
            default: return wName;
        }
    }

    private static int wordHighlightColor(String name) {
        if (name == null || "none".equalsIgnoreCase(name)) return 0;
        switch (name.toLowerCase(Locale.US)) {
            case "yellow": return 0xFFFFFF00;
            case "green": return 0xFF00FF00;
            case "cyan": return 0xFF00FFFF;
            case "magenta": return 0xFFFF00FF;
            case "blue": return 0xFF66B2FF;
            case "red": return 0xFFFF6666;
            case "lightgray": return 0xFFD9D9D9;
            case "darkgray": return 0xFFA6A6A6;
            default: return 0;
        }
    }

    // ---- Body, Paragraph, Image, TextBox & Table Parsing -------------------------------------

    private static void parseContainerInto(Element container, DocCtx ctx, List<Block> out) {
        for (Element ch : OoxmlUtil.children(container, null)) {
            String tag = OoxmlUtil.localName(ch);
            if ("p".equals(tag)) parseParagraphInto(ch, ctx, out);
            else if ("tbl".equals(tag)) {
                TableModel tm = parseTable(ch, ctx);
                if (tm != null && !tm.rows.isEmpty()) {
                    Block b = new Block(Block.TABLE);
                    b.table = tm;
                    out.add(b);
                }
            } else if ("sdt".equals(tag)) {
                Element sc = OoxmlUtil.child(ch, "sdtContent");
                if (sc != null) parseContainerInto(sc, ctx, out);
            }
        }
    }

    private static void parseParagraphInto(Element pEl, DocCtx ctx, List<Block> out) {
        Element pPr = OoxmlUtil.child(pEl, "pPr");
        String pStyleId = null;
        if (pPr != null) {
            Element psEl = OoxmlUtil.child(pPr, "pStyle");
            if (psEl != null) pStyleId = OoxmlUtil.attr(psEl, "val");
        }
        ParaStyle ps = ctx.resolveParaStyle(pStyleId).copy();
        if (pPr != null) ps.mergeFrom(parseParaPr(pPr, ctx.theme));

        if (ps.pageBreakBefore) out.add(new Block(Block.PAGE_BREAK));

        SpannableStringBuilder sb = new SpannableStringBuilder();
        List<Block> inlineImages = new ArrayList<>();
        List<Block> textBoxBlocks = new ArrayList<>();
        boolean[] hasPageBreak = {false};
        float[] maxFontPt = {ps.rStyle.fontSizePt > 0 ? ps.rStyle.fontSizePt : 11f};

        collectParagraphContent(pEl, ctx, ps.rStyle, sb, inlineImages, textBoxBlocks, hasPageBreak, maxFontPt);

        // Emit paragraph if it has text or a visible horizontal border/shading
        boolean hasRule = ps.borderBottomColor != 0 || ps.borderTopColor != 0;
        if (sb.length() > 0 || hasRule) {
            Block b = new Block(Block.PARA);
            b.baseFontPt = maxFontPt[0];
            b.align = ps.align != null ? ps.align : Layout.Alignment.ALIGN_NORMAL;
            b.justify = ps.justify;
            b.spaceBefore = ps.spaceBeforePt >= 0f ? ps.spaceBeforePt : 0f;
            b.spaceAfter = ps.spaceAfterPt >= 0f ? ps.spaceAfterPt : 6f;
            if (ps.lineExactPt > 0f && b.baseFontPt > 0f) {
                b.lineMult = Math.max(0.95f, Math.min(2.8f, ps.lineExactPt / (b.baseFontPt * 1.15f)));
            } else {
                b.lineMult = ps.lineMult > 0f ? ps.lineMult : 1.15f;
            }
            b.indLeft = Float.isNaN(ps.indLeftPt) ? 0f : ps.indLeftPt;
            b.indRight = Float.isNaN(ps.indRightPt) ? 0f : ps.indRightPt;
            b.shdColor = ps.shdColor;
            b.borderBottomColor = ps.borderBottomColor; b.borderBottomW = ps.borderBottomWidth;
            b.borderLeftColor = ps.borderLeftColor; b.borderLeftW = ps.borderLeftWidth;
            b.borderTopColor = ps.borderTopColor; b.borderTopW = ps.borderTopWidth;

            // Numbered / bulleted list prefix
            if (sb.length() > 0 && ps.numId != null && !"0".equals(ps.numId)) {
                int ilvl = Math.max(0, Math.min(8, ps.ilvl >= 0 ? ps.ilvl : 0));
                String marker = nextListMarker(ctx, ps.numId, ilvl);
                if (marker != null && !marker.isEmpty()) {
                    sb.insert(0, marker);
                    float mSz = ps.rStyle.fontSizePt > 0f ? ps.rStyle.fontSizePt : 11f;
                    int mCol = ps.rStyle.color != 0 ? ps.rStyle.color : 0xFF1A1D24;
                    sb.setSpan(new OoxmlUtil.ExactSizeSpan(mSz), 0, marker.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new ForegroundColorSpan(mCol), 0, marker.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    if (b.indLeft <= 0f) {
                        NumLevel nl = findNumLevel(ctx, ps.numId, ilvl);
                        b.indLeft = nl != null ? nl.indLeftPt : (18f + ilvl * 16f);
                    }
                    b.spaceAfter = Math.min(b.spaceAfter, 5f);
                }
            }
            // Apply first-line or hanging indent
            if (sb.length() > 0 && !Float.isNaN(ps.firstLinePt) && Math.abs(ps.firstLinePt) > 1f) {
                if (ps.firstLinePt > 0f) {
                    int first = Math.round(Math.min(72f, ps.firstLinePt));
                    sb.setSpan(new android.text.style.LeadingMarginSpan.Standard(first, 0),
                            0, sb.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
                } else if (b.indLeft > 0f) {
                    int hang = Math.round(Math.min(b.indLeft, -ps.firstLinePt));
                    b.indLeft = Math.max(0f, b.indLeft - hang);
                    sb.setSpan(new android.text.style.LeadingMarginSpan.Standard(0, hang),
                            0, sb.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
                }
            }
            b.text = sb;
            out.add(b);
        } else if (inlineImages.isEmpty() && textBoxBlocks.isEmpty() && !hasPageBreak[0]) {
            // Preserve intentional blank paragraph spacing
            Block spacer = new Block(Block.PARA);
            spacer.isSpacer = true;
            spacer.baseFontPt = maxFontPt[0];
            spacer.spaceAfter = ps.spaceAfterPt >= 0f ? ps.spaceAfterPt : 4f;
            out.add(spacer);
        }

        for (Block ib : inlineImages) {
            ib.align = ps.align != null ? ps.align : Layout.Alignment.ALIGN_CENTER;
            out.add(ib);
        }
        out.addAll(textBoxBlocks);
        if (hasPageBreak[0]) out.add(new Block(Block.PAGE_BREAK));
    }

    private static void collectParagraphContent(Element parent, DocCtx ctx, RunStyle paraRunBase,
                                                SpannableStringBuilder sb, List<Block> images,
                                                List<Block> textBoxes, boolean[] hasPageBreak, float[] maxFontPt) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            String tag = OoxmlUtil.localName(el);
            if ("pPr".equals(tag) || "del".equals(tag) || "Fallback".equals(tag)
                    || "bookmarkStart".equals(tag) || "bookmarkEnd".equals(tag) || "proofErr".equals(tag)) {
                continue;
            }
            if ("r".equals(tag)) {
                collectRun(el, ctx, paraRunBase, sb, images, textBoxes, hasPageBreak, maxFontPt);
            } else if ("hyperlink".equals(tag) || "ins".equals(tag) || "smartTag".equals(tag) || "fldSimple".equals(tag)) {
                RunStyle linkBase = paraRunBase;
                if ("hyperlink".equals(tag)) {
                    linkBase = paraRunBase.copy();
                    linkBase.color = OoxmlUtil.resolveSchemeColor("hlink", ctx.theme, OoxmlUtil.defaultColorMap(), 0);
                    linkBase.underline = true;
                }
                collectParagraphContent(el, ctx, linkBase, sb, images, textBoxes, hasPageBreak, maxFontPt);
            } else if ("sdt".equals(tag)) {
                Element sc = OoxmlUtil.child(el, "sdtContent");
                if (sc != null) collectParagraphContent(sc, ctx, paraRunBase, sb, images, textBoxes, hasPageBreak, maxFontPt);
            } else if ("AlternateContent".equals(tag)) {
                Element choice = OoxmlUtil.child(el, "Choice");
                if (choice != null) collectParagraphContent(choice, ctx, paraRunBase, sb, images, textBoxes, hasPageBreak, maxFontPt);
            }
        }
    }

    private static void collectRun(Element rEl, DocCtx ctx, RunStyle paraRunBase,
                                   SpannableStringBuilder sb, List<Block> images,
                                   List<Block> textBoxes, boolean[] hasPageBreak, float[] maxFontPt) {
        RunStyle rs = paraRunBase.copy();
        Element rPr = OoxmlUtil.child(rEl, "rPr");
        if (rPr != null) {
            Element rStyleEl = OoxmlUtil.child(rPr, "rStyle");
            if (rStyleEl != null) {
                rs.mergeFrom(ctx.resolveCharStyle(OoxmlUtil.attr(rStyleEl, "val")));
            }
            rs.mergeFrom(parseRunPr(rPr, ctx.theme));
        }

        float sizePt = rs.fontSizePt > 0f ? rs.fontSizePt : 11f;
        boolean bold = Boolean.TRUE.equals(rs.bold);
        boolean italic = Boolean.TRUE.equals(rs.italic);
        boolean under = Boolean.TRUE.equals(rs.underline);
        boolean strike = Boolean.TRUE.equals(rs.strike);
        boolean allCaps = Boolean.TRUE.equals(rs.allCaps);
        int col = rs.color != 0 ? rs.color : 0xFF1A1D24;
        Typeface tf = OoxmlUtil.mapFontFamily(rs.fontName, bold, italic);

        for (Node n = rEl.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element ch = (Element) n;
            String tag = OoxmlUtil.localName(ch);
            if ("rPr".equals(tag) || "instrText".equals(tag) || "delText".equals(tag) || "Fallback".equals(tag)) continue;

            if ("t".equals(tag)) {
                String txt = ch.getTextContent();
                if (txt != null && !txt.isEmpty()) {
                    if (allCaps) txt = txt.toUpperCase(Locale.getDefault());
                    if (sizePt > maxFontPt[0]) maxFontPt[0] = sizePt;
                    int start = sb.length();
                    sb.append(txt);
                    int end = sb.length();
                    sb.setSpan(new OoxmlUtil.ExactSizeSpan(sizePt), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new OoxmlUtil.FontSpan(tf), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new ForegroundColorSpan(col), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    if (rs.bgColor != 0) sb.setSpan(new BackgroundColorSpan(rs.bgColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    if (under) sb.setSpan(new UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    if (strike) sb.setSpan(new StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    if ("superscript".equalsIgnoreCase(rs.vertAlign)) {
                        sb.setSpan(new OoxmlUtil.ScriptSpan(true), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    } else if ("subscript".equalsIgnoreCase(rs.vertAlign)) {
                        sb.setSpan(new OoxmlUtil.ScriptSpan(false), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
            } else if ("tab".equals(tag)) {
                sb.append("    ");
            } else if ("br".equals(tag)) {
                String type = OoxmlUtil.attr(ch, "type");
                if ("page".equalsIgnoreCase(type)) hasPageBreak[0] = true;
                else sb.append('\n');
            } else if ("drawing".equals(tag) || "pict".equals(tag) || "AlternateContent".equals(tag)) {
                extractDrawingOrPict(ch, ctx, images, textBoxes);
            }
        }
    }

    private static void extractDrawingOrPict(Element el, DocCtx ctx, List<Block> images, List<Block> textBoxes) {
        if ("AlternateContent".equals(OoxmlUtil.localName(el))) {
            Element choice = OoxmlUtil.child(el, "Choice");
            if (choice != null) extractDrawingOrPict(choice, ctx, images, textBoxes);
            return;
        }
        // Check for embedded text box (<w:txbxContent>)
        Element txbx = OoxmlUtil.findFirstDescendant(el, "txbxContent");
        if (txbx != null) {
            parseContainerInto(txbx, ctx, textBoxes);
            return;
        }
        // Check for DrawingML image (<a:blip> + <wp:extent>)
        Element blip = OoxmlUtil.findFirstDescendant(el, "blip");
        String rId = null;
        if (blip != null) {
            rId = OoxmlUtil.attr(blip, "embed");
            if (rId == null) rId = OoxmlUtil.attr(blip, "link");
        }
        if (rId == null) {
            Element imgData = OoxmlUtil.findFirstDescendant(el, "imagedata");
            if (imgData != null) rId = OoxmlUtil.attr(imgData, "id");
        }
        if (rId == null) return;
        OoxmlUtil.Rel rel = ctx.rels.get(rId);
        if (rel == null || rel.external) return;
        byte[] bytes = ctx.entries.get(rel.target);
        if (bytes == null || bytes.length == 0) return;

        Block ib = new Block(Block.IMAGE);
        ib.imageBytes = bytes;
        Element extent = OoxmlUtil.findFirstDescendant(el, "extent");
        if (extent != null) {
            float cx = OoxmlUtil.attrFloat(extent, "cx", 0f) / EMU_PT;
            float cy = OoxmlUtil.attrFloat(extent, "cy", 0f) / EMU_PT;
            if (cx > 4f && cy > 4f) {
                ib.imgWpt = cx;
                ib.imgHpt = cy;
            }
        }
        images.add(ib);
    }

    // ---- List Marker Resolution --------------------------------------------------------------

    private static NumLevel findNumLevel(DocCtx ctx, String numId, int ilvl) {
        String absId = ctx.numToAbstract.get(numId);
        if (absId == null) return null;
        Map<Integer, NumLevel> map = ctx.abstractNums.get(absId);
        return map != null ? map.get(ilvl) : null;
    }

    private static String nextListMarker(DocCtx ctx, String numId, int ilvl) {
        NumLevel nl = findNumLevel(ctx, numId, ilvl);
        if (nl == null) return "• ";
        if ("none".equalsIgnoreCase(nl.numFmt)) return "";
        if ("bullet".equalsIgnoreCase(nl.numFmt)) {
            if (nl.lvlText != null && !nl.lvlText.isEmpty()) {
                return OoxmlUtil.mapBulletChar(nl.lvlText, nl.bulletFont) + " ";
            }
            if (ilvl == 1) return "◦ ";
            if (ilvl >= 2) return "▪ ";
            return "• ";
        }
        int[] counters = ctx.numCounters.get(numId);
        if (counters == null) {
            counters = new int[9];
            ctx.numCounters.put(numId, counters);
        }
        if (counters[ilvl] == 0) counters[ilvl] = nl.start;
        else counters[ilvl]++;
        for (int k = ilvl + 1; k < counters.length; k++) counters[k] = 0;

        String formatted = formatNumber(counters[ilvl], nl.numFmt);
        String tpl = nl.lvlText != null && !nl.lvlText.isEmpty() ? nl.lvlText : ("%" + (ilvl + 1) + ".");
        String out = tpl.replace("%" + (ilvl + 1), formatted);
        for (int k = 0; k < ilvl; k++) {
            NumLevel parentNl = findNumLevel(ctx, numId, k);
            String pf = formatNumber(Math.max(1, counters[k]), parentNl != null ? parentNl.numFmt : "decimal");
            out = out.replace("%" + (k + 1), pf);
        }
        return out + " ";
    }

    private static String formatNumber(int n, String fmt) {
        if ("lowerLetter".equalsIgnoreCase(fmt)) return toAlpha(n, false);
        if ("upperLetter".equalsIgnoreCase(fmt)) return toAlpha(n, true);
        if ("lowerRoman".equalsIgnoreCase(fmt)) return toRoman(n).toLowerCase(Locale.US);
        if ("upperRoman".equalsIgnoreCase(fmt)) return toRoman(n);
        return String.valueOf(n);
    }

    private static String toAlpha(int n, boolean upper) {
        if (n <= 0) return "a";
        char base = upper ? 'A' : 'a';
        return String.valueOf((char) (base + ((n - 1) % 26)));
    }

    private static String toRoman(int n) {
        if (n <= 0 || n >= 4000) return String.valueOf(n);
        int[] vals = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] syms = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vals.length; i++) {
            while (n >= vals[i]) { sb.append(syms[i]); n -= vals[i]; }
        }
        return sb.toString();
    }

    // ---- Table Parsing -----------------------------------------------------------------------

    private static TableModel parseTable(Element tblEl, DocCtx ctx) {
        TableModel tm = new TableModel();
        Element tblPr = OoxmlUtil.child(tblEl, "tblPr");
        if (tblPr != null) {
            Element tblW = OoxmlUtil.child(tblPr, "tblW");
            if (tblW != null && "dxa".equalsIgnoreCase(OoxmlUtil.attr(tblW, "type"))) {
                tm.totalWidthPt = OoxmlUtil.attrFloat(tblW, "w", 0f) / TWIPS_PT;
            }
            Element tblBorders = OoxmlUtil.child(tblPr, "tblBorders");
            if (tblBorders != null) {
                Element top = OoxmlUtil.child(tblBorders, "top");
                Element left = OoxmlUtil.child(tblBorders, "left");
                Element insideH = OoxmlUtil.child(tblBorders, "insideH");
                Element insideV = OoxmlUtil.child(tblBorders, "insideV");
                if (top != null && !isVisibleBorder(top) && left != null && !isVisibleBorder(left)) {
                    tm.hasOuterBorder = false;
                }
                if (insideH != null && !isVisibleBorder(insideH) && insideV != null && !isVisibleBorder(insideV)) {
                    tm.hasInnerBorder = false;
                }
                Element sample = firstVisibleBorder(top, insideH, left, insideV);
                if (sample != null) {
                    tm.borderColor = borderColor(sample, ctx.theme);
                    tm.borderW = borderWidthPt(sample);
                }
            }
            Element tblCellMar = OoxmlUtil.child(tblPr, "tblCellMar");
            if (tblCellMar != null) {
                Element l = OoxmlUtil.child(tblCellMar, "left");
                Element t = OoxmlUtil.child(tblCellMar, "top");
                if (l != null) tm.defaultPadX = Math.max(2f, Math.min(16f, OoxmlUtil.attrFloat(l, "w", 108f) / TWIPS_PT));
                if (t != null) tm.defaultPadY = Math.max(2f, Math.min(12f, OoxmlUtil.attrFloat(t, "w", 60f) / TWIPS_PT));
            }
        }

        Element tblGrid = OoxmlUtil.child(tblEl, "tblGrid");
        if (tblGrid != null) {
            for (Element gc : OoxmlUtil.children(tblGrid, "gridCol")) {
                float w = OoxmlUtil.attrFloat(gc, "w", 0f) / TWIPS_PT;
                if (w > 0f) tm.gridColPt.add(w);
            }
        }

        for (Element trEl : OoxmlUtil.children(tblEl, "tr")) {
            TableRow row = new TableRow();
            Element trPr = OoxmlUtil.child(trEl, "trPr");
            if (trPr != null) {
                Element trH = OoxmlUtil.child(trPr, "trHeight");
                if (trH != null) row.minHeightPt = Math.max(16f, OoxmlUtil.attrFloat(trH, "val", 0f) / TWIPS_PT);
                if (OoxmlUtil.child(trPr, "tblHeader") != null) row.isHeader = true;
            }
            for (Element tcEl : OoxmlUtil.children(trEl, "tc")) {
                TableCell cell = new TableCell();
                Element tcPr = OoxmlUtil.child(tcEl, "tcPr");
                if (tcPr != null) {
                    Element gs = OoxmlUtil.child(tcPr, "gridSpan");
                    if (gs != null) cell.gridSpan = Math.max(1, OoxmlUtil.attrInt(gs, "val", 1));
                    Element tcW = OoxmlUtil.child(tcPr, "tcW");
                    if (tcW != null && "dxa".equalsIgnoreCase(OoxmlUtil.attr(tcW, "type"))) {
                        cell.widthPt = OoxmlUtil.attrFloat(tcW, "w", 0f) / TWIPS_PT;
                    }
                    Element shd = OoxmlUtil.child(tcPr, "shd");
                    if (shd != null) cell.shdColor = resolveWordColor(shd, "fill", ctx.theme);
                    Element va = OoxmlUtil.child(tcPr, "vAlign");
                    if (va != null) {
                        String v = OoxmlUtil.attr(va, "val");
                        if ("center".equalsIgnoreCase(v)) cell.vAlign = 1;
                        else if ("bottom".equalsIgnoreCase(v)) cell.vAlign = 2;
                    }
                    Element vm = OoxmlUtil.child(tcPr, "vMerge");
                    if (vm != null && !"restart".equalsIgnoreCase(OoxmlUtil.attr(vm, "val"))) {
                        cell.vMergeContinue = true;
                    }
                    Element tcBdr = OoxmlUtil.child(tcPr, "tcBorders");
                    if (tcBdr != null) {
                        Element t = OoxmlUtil.child(tcBdr, "top");
                        Element b = OoxmlUtil.child(tcBdr, "bottom");
                        Element l = OoxmlUtil.child(tcBdr, "left");
                        Element r = OoxmlUtil.child(tcBdr, "right");
                        if (t != null && !isVisibleBorder(t) && b != null && !isVisibleBorder(b)
                                && l != null && !isVisibleBorder(l) && r != null && !isVisibleBorder(r)) {
                            cell.noBorders = true;
                        }
                    }
                }
                parseContainerInto(tcEl, ctx, cell.blocks);
                // Trim redundant trailing blank spacers inside cells
                while (!cell.blocks.isEmpty() && cell.blocks.get(cell.blocks.size() - 1).isSpacer) {
                    cell.blocks.remove(cell.blocks.size() - 1);
                }
                row.cells.add(cell);
            }
            if (!row.cells.isEmpty()) tm.rows.add(row);
        }
        return tm;
    }

    private static boolean isVisibleBorder(Element b) {
        if (b == null) return false;
        String val = OoxmlUtil.attr(b, "val");
        return val != null && !"none".equalsIgnoreCase(val) && !"nil".equalsIgnoreCase(val);
    }

    private static Element firstVisibleBorder(Element... bs) {
        for (Element b : bs) if (isVisibleBorder(b)) return b;
        return null;
    }

    private static int borderColor(Element b, OoxmlUtil.Theme theme) {
        int c = resolveWordColor(b, "color", theme);
        return c != 0 ? c : 0xFFB8BFC9;
    }

    private static float borderWidthPt(Element b) {
        // Word border sz is in 1/8th of a point
        float sz = OoxmlUtil.attrFloat(b, "sz", 4f);
        return Math.max(0.5f, Math.min(4f, sz / 8f));
    }

    private static float parseFloatSafe(String s, float def) {
        try { return Float.parseFloat(s.trim()); } catch (Exception e) { return def; }
    }

    // ---- Section-Aware PDF Flow Renderer -----------------------------------------------------

    private static final class PageWriter {
        final PdfDocument doc = new PdfDocument();
        SectionSpec sec;
        PdfDocument.Page page;
        Canvas cv;
        int pageNum = 0;
        float y;

        PageWriter(SectionSpec initial) {
            this.sec = initial;
            startNewPage(initial);
        }

        void startNewPage(SectionSpec newSec) {
            if (page != null) doc.finishPage(page);
            sec = newSec;
            pageNum++;
            int pw = Math.max(140, Math.round(sec.pageW));
            int ph = Math.max(140, Math.round(sec.pageH));
            page = doc.startPage(new PdfDocument.PageInfo.Builder(pw, ph, pageNum).create());
            cv = page.getCanvas();
            cv.drawColor(sec.pageBgColor != 0 ? sec.pageBgColor : Color.WHITE);
            drawHeaderFooter();
            y = sec.marginTop;
        }

        float maxY() { return sec.pageH - sec.marginBottom; }
        float contentW() { return sec.contentW(); }

        private void drawHeaderFooter() {
            if (sec.headerText != null && !sec.headerText.isEmpty() && sec.marginTop >= 28f) {
                Paint hp = new Paint(Paint.ANTI_ALIAS_FLAG);
                hp.setColor(0xFF6B7280);
                hp.setTextSize(8.5f);
                String t = sec.headerText;
                cv.drawText(t, sec.marginLeft, Math.max(14f, Math.min(sec.marginTop - 10f, sec.headerDist + 8f)), hp);
            }
            if (sec.footerText != null && !sec.footerText.isEmpty() && sec.marginBottom >= 28f) {
                Paint fp = new Paint(Paint.ANTI_ALIAS_FLAG);
                fp.setColor(0xFF6B7280);
                fp.setTextSize(8.5f);
                String t = sec.footerText;
                cv.drawText(t, sec.marginLeft, Math.min(sec.pageH - 12f, sec.pageH - sec.footerDist), fp);
            }
        }

        void finish(File outPdf) throws IOException {
            try {
                if (page != null) doc.finishPage(page);
                try (FileOutputStream fos = new FileOutputStream(outPdf)) {
                    doc.writeTo(fos);
                }
            } finally {
                doc.close();
            }
        }
    }

    private static void renderFlowToPdf(SectionSpec initialSec, List<Block> blocks, File outPdf) throws IOException {
        PageWriter pw = new PageWriter(initialSec);
        try {
            for (Block b : blocks) {
                if (b.kind == Block.SECTION_BREAK && b.nextSection != null) {
                    pw.startNewPage(b.nextSection);
                    continue;
                }
                if (b.kind == Block.PAGE_BREAK) {
                    if (pw.y > pw.sec.marginTop + 4f) pw.startNewPage(pw.sec);
                    continue;
                }
                if (b.kind == Block.IMAGE && b.imageBytes != null) {
                    renderImageBlock(pw, b);
                    continue;
                }
                if (b.kind == Block.TABLE && b.table != null) {
                    renderTableBlock(pw, b.table);
                    continue;
                }
                if (b.kind == Block.PARA) {
                    renderParaBlock(pw, b);
                }
            }
            pw.finish(outPdf);
        } catch (IOException e) {
            pw.doc.close();
            throw e;
        }
    }

    private static void renderImageBlock(PageWriter pw, Block b) {
        Bitmap bm = OoxmlUtil.decodeScaled(b.imageBytes, 1600);
        if (bm == null) return;
        try {
            float maxW = pw.contentW();
            float maxH = pw.sec.contentH() * 0.92f;
            float targetW = b.imgWpt > 4f ? b.imgWpt : (bm.getWidth() * 0.75f);
            float targetH = b.imgHpt > 4f ? b.imgHpt : (bm.getHeight() * 0.75f);
            // Fit proportionally without distorting aspect ratio
            float sc = Math.min(1f, Math.min(maxW / Math.max(1f, targetW), maxH / Math.max(1f, targetH)));
            float dw = targetW * sc;
            float dh = targetH * sc;

            if (pw.y + dh > pw.maxY() && pw.y > pw.sec.marginTop + 4f) {
                pw.startNewPage(pw.sec);
            }
            float left = pw.sec.marginLeft;
            if (b.align == Layout.Alignment.ALIGN_CENTER) left = pw.sec.marginLeft + (maxW - dw) * 0.5f;
            else if (b.align == Layout.Alignment.ALIGN_OPPOSITE) left = pw.sec.marginLeft + (maxW - dw);

            Paint ip = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            pw.cv.drawBitmap(bm, null, new RectF(left, pw.y, left + dw, pw.y + dh), ip);
            pw.y += dh + 8f;
        } finally {
            bm.recycle();
        }
    }

    private static void renderParaBlock(PageWriter pw, Block b) {
        if (b.isSpacer) {
            if (pw.y > pw.sec.marginTop + 2f) {
                pw.y = Math.min(pw.maxY(), pw.y + b.baseFontPt * 0.75f + b.spaceAfter);
            }
            return;
        }
        if (pw.y > pw.sec.marginTop + 2f) pw.y += b.spaceBefore;

        float xLeft = pw.sec.marginLeft + Math.min(pw.contentW() * 0.5f, b.indLeft);
        float xRight = pw.sec.marginLeft + pw.contentW() - Math.min(pw.contentW() * 0.35f, b.indRight);
        float availW = Math.max(48f, xRight - xLeft);

        // Pure horizontal divider rule (empty paragraph with border)
        if (b.text == null || b.text.length() == 0) {
            if (b.borderBottomColor != 0 || b.borderTopColor != 0) {
                if (pw.y + 8f > pw.maxY()) pw.startNewPage(pw.sec);
                Paint rp = new Paint(Paint.ANTI_ALIAS_FLAG);
                rp.setColor(b.borderBottomColor != 0 ? b.borderBottomColor : b.borderTopColor);
                rp.setStrokeWidth(Math.max(0.75f, b.borderBottomW > 0 ? b.borderBottomW : b.borderTopW));
                pw.cv.drawLine(xLeft, pw.y + 3f, xRight, pw.y + 3f, rp);
                pw.y += 8f + b.spaceAfter;
            }
            return;
        }

        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(0xFF1A1D24);
        tp.setTextSize(b.baseFontPt);
        StaticLayout.Builder sb = StaticLayout.Builder.obtain(b.text, 0, b.text.length(), tp, Math.round(availW))
                .setAlignment(b.align)
                .setLineSpacing(0f, b.lineMult)
                .setIncludePad(false);
        if (b.justify) sb.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD);
        StaticLayout full = sb.build();

        float pad = b.shdColor != 0 ? 4f : 0f;
        int line = 0;
        int totalLines = full.getLineCount();
        while (line < totalLines) {
            int lineTop = full.getLineTop(line);
            float lh = full.getLineBottom(line) - lineTop;
            if (pw.y + lh + pad * 2f > pw.maxY() && pw.y > pw.sec.marginTop + 4f) {
                pw.startNewPage(pw.sec);
            }
            int endLine = line + 1;
            while (endLine < totalLines && pw.y + pad * 2f + (full.getLineBottom(endLine) - lineTop) <= pw.maxY()) {
                endLine++;
            }
            float sliceH = full.getLineBottom(endLine - 1) - lineTop;

            if (b.shdColor != 0) {
                Paint sp = new Paint();
                sp.setColor(b.shdColor);
                pw.cv.drawRect(xLeft - pad, pw.y, xRight + pad, pw.y + sliceH + pad * 2f, sp);
            }
            if (b.borderLeftColor != 0) {
                Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
                lp.setColor(b.borderLeftColor);
                lp.setStrokeWidth(Math.max(1f, b.borderLeftW));
                pw.cv.drawLine(xLeft - 4f, pw.y, xLeft - 4f, pw.y + sliceH + pad * 2f, lp);
            }
            if (line == 0 && b.borderTopColor != 0) {
                Paint tpB = new Paint(Paint.ANTI_ALIAS_FLAG);
                tpB.setColor(b.borderTopColor);
                tpB.setStrokeWidth(Math.max(0.75f, b.borderTopW));
                pw.cv.drawLine(xLeft, pw.y, xRight, pw.y, tpB);
            }

            pw.cv.save();
            pw.cv.clipRect(xLeft - 1f, pw.y + pad - 1f, xRight + 1f, pw.y + pad + sliceH + 2f);
            pw.cv.translate(xLeft, pw.y + pad - lineTop);
            full.draw(pw.cv);
            pw.cv.restore();

            pw.y += sliceH + pad * 2f;
            if (endLine == totalLines && b.borderBottomColor != 0) {
                Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
                bp.setColor(b.borderBottomColor);
                bp.setStrokeWidth(Math.max(0.75f, b.borderBottomW));
                pw.cv.drawLine(xLeft, pw.y + 2f, xRight, pw.y + 2f, bp);
                pw.y += 4f;
            }
            line = endLine;
        }
        pw.y += b.spaceAfter;
    }

    private static void renderTableBlock(PageWriter pw, TableModel tm) {
        int colCount = tm.gridColPt.size();
        if (colCount == 0) {
            for (TableRow r : tm.rows) {
                int spanSum = 0;
                for (TableCell c : r.cells) spanSum += c.gridSpan;
                colCount = Math.max(colCount, spanSum);
            }
        }
        if (colCount <= 0) return;

        float contentW = pw.contentW();
        float[] colW = new float[colCount];
        float sum = 0f;
        for (int c = 0; c < colCount; c++) {
            float w = c < tm.gridColPt.size() ? tm.gridColPt.get(c) : 0f;
            colW[c] = w > 0f ? w : (contentW / colCount);
            sum += colW[c];
        }
        float targetTableW = (tm.totalWidthPt > 20f && tm.totalWidthPt < contentW) ? tm.totalWidthPt : contentW;
        if (sum > 0f) {
            float sc = (sum > contentW || tm.gridColPt.isEmpty()) ? (targetTableW / sum) : Math.min(1f, contentW / sum);
            for (int c = 0; c < colCount; c++) colW[c] *= sc;
        }
        float[] colX = new float[colCount + 1];
        colX[0] = pw.sec.marginLeft;
        for (int c = 0; c < colCount; c++) colX[c + 1] = colX[c] + colW[c];

        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setColor(tm.borderColor);
        borderPaint.setStrokeWidth(tm.borderW);
        boolean drawBorders = tm.hasOuterBorder || tm.hasInnerBorder;

        for (int ri = 0; ri < tm.rows.size(); ri++) {
            TableRow row = tm.rows.get(ri);
            List<CellMeasure> measures = new ArrayList<>(row.cells.size());
            float rowH = row.minHeightPt;

            int colIdx = 0;
            for (TableCell cell : row.cells) {
                int startCol = Math.min(colIdx, colCount - 1);
                int endCol = Math.min(colCount, startCol + cell.gridSpan);
                float cw = Math.max(16f, colX[endCol] - colX[startCol]);
                CellMeasure cm = measureCell(cell, cw, tm.defaultPadX, tm.defaultPadY);
                cm.startCol = startCol;
                cm.endCol = endCol;
                measures.add(cm);
                rowH = Math.max(rowH, cm.height);
                colIdx = endCol;
            }

            if (pw.y + rowH > pw.maxY() && pw.y > pw.sec.marginTop + 4f) {
                pw.startNewPage(pw.sec);
            }

            for (CellMeasure cm : measures) {
                RectF rect = new RectF(colX[cm.startCol], pw.y, colX[cm.endCol], pw.y + rowH);
                if (cm.cell.shdColor != 0) {
                    Paint bg = new Paint();
                    bg.setColor(cm.cell.shdColor);
                    pw.cv.drawRect(rect, bg);
                }
                if (drawBorders && !cm.cell.noBorders) {
                    pw.cv.drawRect(rect, borderPaint);
                }
                drawMeasuredCell(pw.cv, rect, cm, tm.defaultPadX, tm.defaultPadY);
            }
            pw.y += rowH;
        }
        pw.y += 8f;
    }

    private static final class CellMeasure {
        final TableCell cell;
        int startCol, endCol;
        float height;
        float contentHeight;
        final List<Object> items = new ArrayList<>(); // StaticLayout or float[]{w,h} + Block
        final List<Block> itemBlocks = new ArrayList<>();
        CellMeasure(TableCell cell) { this.cell = cell; }
    }

    private static CellMeasure measureCell(TableCell cell, float cellW, float padX, float padY) {
        CellMeasure cm = new CellMeasure(cell);
        float innerW = Math.max(12f, cellW - padX * 2f);
        float h = 0f;
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < cell.blocks.size(); i++) {
            Block b = cell.blocks.get(i);
            if (b.kind == Block.PARA && b.text != null && b.text.length() > 0) {
                if (i > 0) h += Math.min(6f, b.spaceBefore);
                tp.setTextSize(b.baseFontPt);
                StaticLayout sl = StaticLayout.Builder.obtain(b.text, 0, b.text.length(), tp, Math.round(innerW))
                        .setAlignment(b.align)
                        .setLineSpacing(0f, b.lineMult)
                        .setIncludePad(false)
                        .build();
                cm.items.add(sl);
                cm.itemBlocks.add(b);
                h += sl.getHeight();
                if (i + 1 < cell.blocks.size()) h += Math.min(6f, b.spaceAfter);
            } else if (b.kind == Block.IMAGE && b.imageBytes != null) {
                Bitmap bm = OoxmlUtil.decodeScaled(b.imageBytes, 600);
                if (bm != null) {
                    float tw = b.imgWpt > 4f ? b.imgWpt : (bm.getWidth() * 0.5f);
                    float th = b.imgHpt > 4f ? b.imgHpt : (bm.getHeight() * 0.5f);
                    float sc = Math.min(1f, innerW / Math.max(1f, tw));
                    float dw = tw * sc, dh = th * sc;
                    bm.recycle();
                    cm.items.add(new float[]{dw, dh});
                    cm.itemBlocks.add(b);
                    h += dh + 4f;
                }
            }
        }
        cm.contentHeight = h;
        cm.height = Math.max(18f, h + padY * 2f);
        return cm;
    }

    private static void drawMeasuredCell(Canvas cv, RectF rect, CellMeasure cm, float padX, float padY) {
        float y = rect.top + padY;
        float availH = rect.height() - padY * 2f;
        if (cm.cell.vAlign == 1) y = rect.top + padY + Math.max(0f, (availH - cm.contentHeight) * 0.5f);
        else if (cm.cell.vAlign == 2) y = rect.bottom - padY - cm.contentHeight;

        cv.save();
        cv.clipRect(rect);
        for (int i = 0; i < cm.items.size(); i++) {
            Object item = cm.items.get(i);
            Block b = cm.itemBlocks.get(i);
            if (i > 0 && b.kind == Block.PARA) y += Math.min(6f, b.spaceBefore);
            if (item instanceof StaticLayout) {
                StaticLayout sl = (StaticLayout) item;
                cv.save();
                cv.translate(rect.left + padX, y);
                sl.draw(cv);
                cv.restore();
                y += sl.getHeight();
                if (i + 1 < cm.items.size()) y += Math.min(6f, b.spaceAfter);
            } else if (item instanceof float[] && b.imageBytes != null) {
                float[] wh = (float[]) item;
                Bitmap bm = OoxmlUtil.decodeScaled(b.imageBytes, 800);
                if (bm != null) {
                    try {
                        float left = rect.left + padX + Math.max(0f, (rect.width() - padX * 2f - wh[0]) * 0.5f);
                        Paint ip = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                        cv.drawBitmap(bm, null, new RectF(left, y, left + wh[0], y + wh[1]), ip);
                    } finally {
                        bm.recycle();
                    }
                }
                y += wh[1] + 4f;
            }
        }
        cv.restore();
    }
}

package app.nib.notes;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.UnderlineSpan;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-fidelity offline PPTX -> PDF renderer preserving exact slide aspect ratio & size,
 * slide/layout/master backgrounds (solid, gradient, image, theme bgRef), master/layout chrome,
 * theme & color-map resolution, placeholder inheritance, group transforms, shapes, tables,
 * cropped images, and accurate rich text typography.
 */
final class PptxRenderer {
    private PptxRenderer() {}

    private static final float EMU_PT = OoxmlUtil.EMU_PER_PT;

    // ---- Cached Master / Layout parts --------------------------------------------------------

    private static final class Part {
        final String path;
        final Element root;
        final Map<String, OoxmlUtil.Rel> rels;
        final Map<String, Element> phByKey = new HashMap<>();

        Part(String path, Element root, Map<String, OoxmlUtil.Rel> rels) {
            this.path = path;
            this.root = root;
            this.rels = rels;
            indexPlaceholders();
        }

        private void indexPlaceholders() {
            if (root == null) return;
            Element cSld = OoxmlUtil.child(root, "cSld");
            Element spTree = OoxmlUtil.child(cSld, "spTree");
            if (spTree == null) return;
            for (Element sp : OoxmlUtil.children(spTree, "sp")) {
                Element nvSpPr = OoxmlUtil.child(sp, "nvSpPr");
                Element nvPr = OoxmlUtil.child(nvSpPr, "nvPr");
                Element ph = OoxmlUtil.child(nvPr, "ph");
                if (ph == null) continue;
                String type = OoxmlUtil.attr(ph, "type");
                String idx = OoxmlUtil.attr(ph, "idx");
                if (idx != null) phByKey.put("idx:" + idx, sp);
                if (type != null) {
                    String norm = type.toLowerCase(Locale.US);
                    if (!phByKey.containsKey("type:" + norm)) phByKey.put("type:" + norm, sp);
                    if (idx != null) phByKey.put("ti:" + norm + ":" + idx, sp);
                } else {
                    if (!phByKey.containsKey("type:body")) phByKey.put("type:body", sp);
                }
            }
        }

        Element findPlaceholder(String type, String idx) {
            String norm = type != null ? type.toLowerCase(Locale.US) : null;
            if (norm != null && idx != null) {
                Element e = phByKey.get("ti:" + norm + ":" + idx);
                if (e != null) return e;
            }
            if (idx != null) {
                Element e = phByKey.get("idx:" + idx);
                if (e != null) return e;
            }
            if (norm != null) {
                Element e = phByKey.get("type:" + norm);
                if (e != null) return e;
                if ("ctrtitle".equals(norm)) return phByKey.get("type:title");
                if ("subtitle".equals(norm) || "obj".equals(norm)) return phByKey.get("type:body");
            }
            return phByKey.get("type:body");
        }
    }

    private static final class MasterInfo {
        final Part part;
        final OoxmlUtil.Theme theme;
        final Map<String, String> clrMap;
        final Element titleStyle;
        final Element bodyStyle;
        final Element otherStyle;

        MasterInfo(Part part, OoxmlUtil.Theme theme) {
            this.part = part;
            this.theme = theme;
            Element clrMapEl = part.root != null ? OoxmlUtil.child(part.root, "clrMap") : null;
            this.clrMap = OoxmlUtil.parseColorMap(clrMapEl, OoxmlUtil.defaultColorMap());
            Element txStyles = part.root != null ? OoxmlUtil.child(part.root, "txStyles") : null;
            this.titleStyle = txStyles != null ? OoxmlUtil.child(txStyles, "titleStyle") : null;
            this.bodyStyle = txStyles != null ? OoxmlUtil.child(txStyles, "bodyStyle") : null;
            this.otherStyle = txStyles != null ? OoxmlUtil.child(txStyles, "otherStyle") : null;
        }
    }

    /** Affine scale+translate mapping group child EMU coordinates into slide PDF-point coordinates. */
    private static final class CoordMap {
        final float offX, offY; // slide pt offset
        final float scaleX, scaleY; // slide pt per local EMU

        CoordMap(float offX, float offY, float scaleX, float scaleY) {
            this.offX = offX;
            this.offY = offY;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
        }

        static CoordMap root() {
            return new CoordMap(0f, 0f, 1f / EMU_PT, 1f / EMU_PT);
        }

        CoordMap childGroup(Element xfrm) {
            if (xfrm == null) return this;
            Element off = OoxmlUtil.child(xfrm, "off");
            Element ext = OoxmlUtil.child(xfrm, "ext");
            Element chOff = OoxmlUtil.child(xfrm, "chOff");
            Element chExt = OoxmlUtil.child(xfrm, "chExt");
            if (off == null || ext == null) return this;
            float gx = offX + OoxmlUtil.attrFloat(off, "x", 0f) * scaleX;
            float gy = offY + OoxmlUtil.attrFloat(off, "y", 0f) * scaleY;
            float gw = OoxmlUtil.attrFloat(ext, "cx", 0f) * scaleX;
            float gh = OoxmlUtil.attrFloat(ext, "cy", 0f) * scaleY;
            float cx = chOff != null ? OoxmlUtil.attrFloat(chOff, "x", 0f) : 0f;
            float cy = chOff != null ? OoxmlUtil.attrFloat(chOff, "y", 0f) : 0f;
            float cw = chExt != null ? OoxmlUtil.attrFloat(chExt, "cx", 0f) : 0f;
            float ch = chExt != null ? OoxmlUtil.attrFloat(chExt, "cy", 0f) : 0f;
            if (cw <= 0f || ch <= 0f) return new CoordMap(gx, gy, scaleX, scaleY);
            float nsx = gw / cw;
            float nsy = gh / ch;
            return new CoordMap(gx - cx * nsx, gy - cy * nsy, nsx, nsy);
        }
    }

    private static final class XfrmBox {
        float x, y, w, h; // in PDF points
        float rotDeg;
        boolean flipH, flipV;
        boolean valid;
    }

    private static final class SlideCtx {
        final OoxmlUtil.ZipEntries entries;
        final OoxmlUtil.Theme theme;
        final Map<String, String> clrMap;
        final MasterInfo master;
        final Part layout;
        final Element defaultTextStyle;
        final float pageW, pageH;
        final int slideNumber;
        float bgLuma = 1f;

        SlideCtx(OoxmlUtil.ZipEntries entries, OoxmlUtil.Theme theme, Map<String, String> clrMap,
                 MasterInfo master, Part layout, Element defaultTextStyle, float pageW, float pageH, int slideNumber) {
            this.entries = entries;
            this.theme = theme;
            this.clrMap = clrMap;
            this.master = master;
            this.layout = layout;
            this.defaultTextStyle = defaultTextStyle;
            this.pageW = pageW;
            this.pageH = pageH;
            this.slideNumber = slideNumber;
        }
    }

    // ---- Entry Point -------------------------------------------------------------------------

    static void render(Map<String, byte[]> rawEntries, File outPdf) throws IOException {
        OoxmlUtil.ZipEntries entries = new OoxmlUtil.ZipEntries(rawEntries);
        Document presDoc = OoxmlUtil.parseXml(entries.get("ppt/presentation.xml"));
        Element presRoot = presDoc != null ? presDoc.getDocumentElement() : null;
        Map<String, OoxmlUtil.Rel> presRels = OoxmlUtil.parseRels(entries, "ppt/_rels/presentation.xml.rels", "ppt/");

        // Exact slide dimensions in PDF points (1 pt = 12700 EMU)
        float emuW = 12192000f; // 16:9 widescreen default (960 x 540 pt)
        float emuH = 6858000f;
        Element sldSz = presRoot != null ? OoxmlUtil.child(presRoot, "sldSz") : null;
        if (sldSz != null) {
            long cx = OoxmlUtil.attrLong(sldSz, "cx", 0L);
            long cy = OoxmlUtil.attrLong(sldSz, "cy", 0L);
            if (cx > 0 && cy > 0) {
                emuW = cx;
                emuH = cy;
            }
        }
        int pageW = Math.max(180, Math.round(emuW / EMU_PT));
        int pageH = Math.max(180, Math.round(emuH / EMU_PT));

        Element defaultTextStyle = presRoot != null ? OoxmlUtil.child(presRoot, "defaultTextStyle") : null;

        // Slide paths in presentation order (<p:sldIdLst>), fallback to slideN.xml natural sort
        List<String> slidePaths = resolveSlidePaths(presRoot, presRels, rawEntries);
        if (slidePaths.isEmpty()) throw new IOException("No slides found in PPTX");

        // Cache themes, masters, and layouts across slides
        OoxmlUtil.Theme fallbackTheme = OoxmlUtil.parseTheme(entries.get("ppt/theme/theme1.xml"));
        Map<String, OoxmlUtil.Theme> themeCache = new HashMap<>();
        Map<String, MasterInfo> masterCache = new HashMap<>();
        Map<String, Part> layoutCache = new HashMap<>();

        PdfDocument doc = new PdfDocument();
        try {
            for (int i = 0; i < slidePaths.size(); i++) {
                String slidePath = slidePaths.get(i);
                Document sldDoc = OoxmlUtil.parseXml(entries.get(slidePath));
                Element sldRoot = sldDoc != null ? sldDoc.getDocumentElement() : null;
                Map<String, OoxmlUtil.Rel> sldRels = OoxmlUtil.parseRels(
                        entries, OoxmlUtil.relsPathFor(slidePath), OoxmlUtil.dirOf(slidePath));

                // Resolve SlideLayout -> SlideMaster -> Theme
                Part layout = null;
                String layoutPath = OoxmlUtil.findRelTargetByTypeSuffix(sldRels, "/slideLayout");
                if (layoutPath != null) {
                    layout = loadPart(entries, layoutPath, layoutCache);
                }
                MasterInfo master = null;
                if (layout != null) {
                    String masterPath = OoxmlUtil.findRelTargetByTypeSuffix(layout.rels, "/slideMaster");
                    if (masterPath != null) {
                        master = loadMaster(entries, masterPath, masterCache, themeCache, fallbackTheme);
                    }
                }
                if (master == null) {
                    String fallbackMaster = findFirstMatchingKey(rawEntries, "^ppt/slideMasters/slideMaster\\d+\\.xml$");
                    if (fallbackMaster != null) {
                        master = loadMaster(entries, fallbackMaster, masterCache, themeCache, fallbackTheme);
                    }
                }

                OoxmlUtil.Theme theme = master != null ? master.theme : fallbackTheme;
                Map<String, String> clrMap = master != null ? master.clrMap : OoxmlUtil.defaultColorMap();
                clrMap = applyClrMapOverride(layout != null ? layout.root : null, clrMap);
                clrMap = applyClrMapOverride(sldRoot, clrMap);

                SlideCtx ctx = new SlideCtx(entries, theme, clrMap, master, layout, defaultTextStyle, pageW, pageH, i + 1);

                PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(pageW, pageH, i + 1).create();
                PdfDocument.Page pg = doc.startPage(info);
                Canvas cv = pg.getCanvas();
                cv.clipRect(0f, 0f, pageW, pageH);

                // 1. Background (Slide -> Layout -> Master -> theme bg1)
                renderBackground(cv, ctx, sldRoot, sldRels);

                // 2. Non-placeholder Master shapes (unless disabled by layout or slide)
                boolean showMasterOnLayout = layout == null || layout.root == null
                        || OoxmlUtil.attrBool(layout.root, "showMasterSp", true);
                boolean showMasterOnSlide = sldRoot == null
                        || OoxmlUtil.attrBool(sldRoot, "showMasterSp", true);
                if (master != null && master.part.root != null && showMasterOnLayout && showMasterOnSlide) {
                    Element mTree = OoxmlUtil.child(OoxmlUtil.child(master.part.root, "cSld"), "spTree");
                    renderShapeTree(cv, ctx, mTree, master.part.rels, CoordMap.root(), true);
                }

                // 3. Non-placeholder Layout shapes (unless disabled by slide)
                if (layout != null && layout.root != null && showMasterOnSlide) {
                    Element lTree = OoxmlUtil.child(OoxmlUtil.child(layout.root, "cSld"), "spTree");
                    renderShapeTree(cv, ctx, lTree, layout.rels, CoordMap.root(), true);
                }

                // 4. Slide's own shapes, groups, images, tables
                if (sldRoot != null) {
                    Element sTree = OoxmlUtil.child(OoxmlUtil.child(sldRoot, "cSld"), "spTree");
                    renderShapeTree(cv, ctx, sTree, sldRels, CoordMap.root(), false);
                }

                doc.finishPage(pg);
            }
            try (FileOutputStream fos = new FileOutputStream(outPdf)) {
                doc.writeTo(fos);
            }
        } finally {
            doc.close();
        }
    }

    private static List<String> resolveSlidePaths(Element presRoot, Map<String, OoxmlUtil.Rel> presRels,
                                                  Map<String, byte[]> rawEntries) {
        ArrayList<String> out = new ArrayList<>();
        Element sldIdLst = presRoot != null ? OoxmlUtil.child(presRoot, "sldIdLst") : null;
        if (sldIdLst != null && presRels != null) {
            for (Element sid : OoxmlUtil.children(sldIdLst, "sldId")) {
                String rId = OoxmlUtil.attr(sid, "id");
                OoxmlUtil.Rel rel = rId != null ? presRels.get(rId) : null;
                if (rel != null && !rel.external && rel.target != null) {
                    out.add(rel.target);
                }
            }
        }
        if (!out.isEmpty()) return out;

        Pattern sp = Pattern.compile("^ppt/slides/slide(\\d+)\\.xml$", Pattern.CASE_INSENSITIVE);
        for (String k : rawEntries.keySet()) {
            if (sp.matcher(k).matches()) out.add(k);
        }
        Collections.sort(out, (a, b) -> {
            Matcher ma = sp.matcher(a);
            Matcher mb = sp.matcher(b);
            int ia = ma.matches() ? Integer.parseInt(ma.group(1)) : 0;
            int ib = mb.matches() ? Integer.parseInt(mb.group(1)) : 0;
            return Integer.compare(ia, ib);
        });
        return out;
    }

    private static String findFirstMatchingKey(Map<String, byte[]> entries, String regex) {
        Pattern p = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        ArrayList<String> list = new ArrayList<>();
        for (String k : entries.keySet()) if (p.matcher(k).matches()) list.add(k);
        Collections.sort(list);
        return list.isEmpty() ? null : list.get(0);
    }

    private static Part loadPart(OoxmlUtil.ZipEntries entries, String path, Map<String, Part> cache) {
        Part p = cache.get(path);
        if (p != null) return p;
        Document d = OoxmlUtil.parseXml(entries.get(path));
        Map<String, OoxmlUtil.Rel> rels = OoxmlUtil.parseRels(entries, OoxmlUtil.relsPathFor(path), OoxmlUtil.dirOf(path));
        p = new Part(path, d != null ? d.getDocumentElement() : null, rels);
        cache.put(path, p);
        return p;
    }

    private static MasterInfo loadMaster(OoxmlUtil.ZipEntries entries, String path,
                                         Map<String, MasterInfo> cache,
                                         Map<String, OoxmlUtil.Theme> themeCache,
                                         OoxmlUtil.Theme fallbackTheme) {
        MasterInfo m = cache.get(path);
        if (m != null) return m;
        Document d = OoxmlUtil.parseXml(entries.get(path));
        Map<String, OoxmlUtil.Rel> rels = OoxmlUtil.parseRels(entries, OoxmlUtil.relsPathFor(path), OoxmlUtil.dirOf(path));
        Part p = new Part(path, d != null ? d.getDocumentElement() : null, rels);
        String themePath = OoxmlUtil.findRelTargetByTypeSuffix(rels, "/theme");
        OoxmlUtil.Theme theme = fallbackTheme;
        if (themePath != null) {
            OoxmlUtil.Theme cached = themeCache.get(themePath);
            if (cached == null) {
                cached = OoxmlUtil.parseTheme(entries.get(themePath));
                themeCache.put(themePath, cached);
            }
            theme = cached;
        }
        m = new MasterInfo(p, theme);
        cache.put(path, m);
        return m;
    }

    private static Map<String, String> applyClrMapOverride(Element partRoot, Map<String, String> current) {
        if (partRoot == null) return current;
        Element ovr = OoxmlUtil.child(partRoot, "clrMapOvr");
        if (ovr == null) return current;
        Element map = OoxmlUtil.child(ovr, "overrideClrMapping");
        if (map != null) return OoxmlUtil.parseColorMap(map, current);
        return current;
    }

    // ---- Background Rendering ----------------------------------------------------------------

    private static void renderBackground(Canvas cv, SlideCtx ctx, Element sldRoot, Map<String, OoxmlUtil.Rel> sldRels) {
        RectF full = new RectF(0f, 0f, ctx.pageW, ctx.pageH);
        // Base fill with theme bg1 first so translucent gradients/overlays sit on the proper paper color
        int bg1 = OoxmlUtil.resolveSchemeColor("bg1", ctx.theme, ctx.clrMap, 0);
        if (bg1 == 0) bg1 = Color.WHITE;
        Paint base = new Paint();
        base.setColor(bg1);
        cv.drawRect(full, base);
        ctx.bgLuma = OoxmlUtil.luminance(bg1);

        OoxmlUtil.FillSpec bgFill = extractBgFill(sldRoot, sldRels, ctx);
        if (bgFill == null && ctx.layout != null) {
            bgFill = extractBgFill(ctx.layout.root, ctx.layout.rels, ctx);
        }
        if (bgFill == null && ctx.master != null) {
            bgFill = extractBgFill(ctx.master.part.root, ctx.master.part.rels, ctx);
        }
        if (bgFill != null && bgFill.kind != OoxmlUtil.FillSpec.NONE) {
            OoxmlUtil.drawFill(cv, full, null, bgFill, 2000);
            int rep = bgFill.representativeColor();
            if (rep != 0 && Color.alpha(rep) > 128) {
                ctx.bgLuma = OoxmlUtil.luminance(rep);
            }
        }
    }

    private static OoxmlUtil.FillSpec extractBgFill(Element partRoot, Map<String, OoxmlUtil.Rel> partRels, SlideCtx ctx) {
        if (partRoot == null) return null;
        Element cSld = OoxmlUtil.child(partRoot, "cSld");
        Element bg = OoxmlUtil.child(cSld, "bg");
        if (bg == null) return null;

        Element bgPr = OoxmlUtil.child(bg, "bgPr");
        if (bgPr != null) {
            return OoxmlUtil.parseFillFromContainer(bgPr, ctx.theme, ctx.clrMap, 0, partRels, ctx.entries);
        }
        Element bgRef = OoxmlUtil.child(bg, "bgRef");
        if (bgRef != null) {
            int idx = OoxmlUtil.attrInt(bgRef, "idx", 0);
            int phClr = OoxmlUtil.parseColorNode(bgRef, ctx.theme, ctx.clrMap, 0);
            Element styleFill = resolveThemeFillByIdx(ctx.theme, idx);
            if (styleFill != null) {
                return OoxmlUtil.parseFillElement(styleFill, ctx.theme, ctx.clrMap, phClr, partRels, ctx.entries);
            }
            if (phClr != 0) {
                OoxmlUtil.FillSpec f = new OoxmlUtil.FillSpec();
                f.kind = OoxmlUtil.FillSpec.SOLID;
                f.color = phClr;
                return f;
            }
        }
        return null;
    }

    private static Element resolveThemeFillByIdx(OoxmlUtil.Theme theme, int idx) {
        if (theme == null || idx <= 0) return null;
        if (idx >= 1001) {
            int i = idx - 1001;
            if (i < theme.bgFillStyles.size()) return theme.bgFillStyles.get(i);
            if (!theme.bgFillStyles.isEmpty()) return theme.bgFillStyles.get(0);
        } else {
            int i = idx - 1;
            if (i < theme.fillStyles.size()) return theme.fillStyles.get(i);
            if (!theme.fillStyles.isEmpty()) return theme.fillStyles.get(0);
        }
        return null;
    }

    // ---- Shape Tree Traversal ----------------------------------------------------------------

    private static void renderShapeTree(Canvas cv, SlideCtx ctx, Element container,
                                        Map<String, OoxmlUtil.Rel> rels, CoordMap cmap, boolean skipPlaceholders) {
        if (container == null) return;
        for (Element el : OoxmlUtil.children(container, null)) {
            String tag = OoxmlUtil.localName(el);
            if ("AlternateContent".equals(tag)) {
                Element choice = OoxmlUtil.child(el, "Choice");
                Element fallback = OoxmlUtil.child(el, "Fallback");
                if (choice != null && hasDrawableChild(choice)) {
                    renderShapeTree(cv, ctx, choice, rels, cmap, skipPlaceholders);
                } else if (fallback != null) {
                    renderShapeTree(cv, ctx, fallback, rels, cmap, skipPlaceholders);
                }
            } else if ("grpSp".equals(tag)) {
                Element grpSpPr = OoxmlUtil.child(el, "grpSpPr");
                Element xfrm = OoxmlUtil.child(grpSpPr, "xfrm");
                CoordMap childMap = cmap.childGroup(xfrm);
                float rot = xfrm != null ? OoxmlUtil.attrFloat(xfrm, "rot", 0f) / 60000f : 0f;
                XfrmBox gbox = parseXfrm(xfrm, cmap);
                boolean rotated = gbox.valid && Math.abs(rot) > 0.05f;
                if (rotated) {
                    cv.save();
                    cv.rotate(rot, gbox.x + gbox.w * 0.5f, gbox.y + gbox.h * 0.5f);
                }
                renderShapeTree(cv, ctx, el, rels, childMap, skipPlaceholders);
                if (rotated) cv.restore();
            } else if ("sp".equals(tag)) {
                if (skipPlaceholders && hasPlaceholder(el)) continue;
                renderShape(cv, ctx, el, rels, cmap, false);
            } else if ("cxnSp".equals(tag)) {
                renderShape(cv, ctx, el, rels, cmap, true);
            } else if ("pic".equals(tag)) {
                if (skipPlaceholders && hasPlaceholder(el)) continue;
                renderPicture(cv, ctx, el, rels, cmap);
            } else if ("graphicFrame".equals(tag)) {
                if (skipPlaceholders && hasPlaceholder(el)) continue;
                renderGraphicFrame(cv, ctx, el, rels, cmap);
            }
        }
    }

    private static boolean hasDrawableChild(Element container) {
        for (Element ch : OoxmlUtil.children(container, null)) {
            String t = OoxmlUtil.localName(ch);
            if ("sp".equals(t) || "pic".equals(t) || "grpSp".equals(t) || "cxnSp".equals(t) || "graphicFrame".equals(t)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPlaceholder(Element spOrPic) {
        Element nv = OoxmlUtil.child(spOrPic, "nvSpPr");
        if (nv == null) nv = OoxmlUtil.child(spOrPic, "nvPicPr");
        if (nv == null) nv = OoxmlUtil.child(spOrPic, "nvGraphicFramePr");
        Element nvPr = OoxmlUtil.child(nv, "nvPr");
        return OoxmlUtil.child(nvPr, "ph") != null;
    }

    private static XfrmBox parseXfrm(Element xfrm, CoordMap cmap) {
        XfrmBox box = new XfrmBox();
        if (xfrm == null) return box;
        Element off = OoxmlUtil.child(xfrm, "off");
        Element ext = OoxmlUtil.child(xfrm, "ext");
        if (off == null || ext == null) return box;
        box.x = cmap.offX + OoxmlUtil.attrFloat(off, "x", 0f) * cmap.scaleX;
        box.y = cmap.offY + OoxmlUtil.attrFloat(off, "y", 0f) * cmap.scaleY;
        box.w = OoxmlUtil.attrFloat(ext, "cx", 0f) * cmap.scaleX;
        box.h = OoxmlUtil.attrFloat(ext, "cy", 0f) * cmap.scaleY;
        box.rotDeg = OoxmlUtil.attrFloat(xfrm, "rot", 0f) / 60000f;
        box.flipH = OoxmlUtil.attrBool(xfrm, "flipH", false);
        box.flipV = OoxmlUtil.attrBool(xfrm, "flipV", false);
        box.valid = box.w > 0.2f || box.h > 0.2f;
        return box;
    }

    // ---- Shape & Connector Rendering ---------------------------------------------------------

    private static void renderShape(Canvas cv, SlideCtx ctx, Element sp,
                                    Map<String, OoxmlUtil.Rel> rels, CoordMap cmap, boolean isConnector) {
        Element nvSpPr = OoxmlUtil.child(sp, isConnector ? "nvCxnSpPr" : "nvSpPr");
        Element nvPr = OoxmlUtil.child(nvSpPr, "nvPr");
        Element ph = OoxmlUtil.child(nvPr, "ph");
        boolean hasPh = ph != null;
        String phType = ph != null ? OoxmlUtil.attr(ph, "type") : null;
        String phIdx = ph != null ? OoxmlUtil.attr(ph, "idx") : null;

        Element layoutPh = (hasPh && ctx.layout != null) ? ctx.layout.findPlaceholder(phType, phIdx) : null;
        String effectivePhType = phType;
        if (effectivePhType == null && layoutPh != null) {
            Element lPh = OoxmlUtil.child(OoxmlUtil.child(OoxmlUtil.child(layoutPh, "nvSpPr"), "nvPr"), "ph");
            if (lPh != null) effectivePhType = OoxmlUtil.attr(lPh, "type");
        }
        Element masterPh = (hasPh && ctx.master != null) ? ctx.master.part.findPlaceholder(effectivePhType, phIdx) : null;

        Element spPr = OoxmlUtil.child(sp, "spPr");
        Element lSpPr = layoutPh != null ? OoxmlUtil.child(layoutPh, "spPr") : null;
        Element mSpPr = masterPh != null ? OoxmlUtil.child(masterPh, "spPr") : null;

        // Geometry box: Slide -> Layout placeholder -> Master placeholder
        XfrmBox box = parseXfrm(OoxmlUtil.child(spPr, "xfrm"), cmap);
        if (!box.valid && lSpPr != null) box = parseXfrm(OoxmlUtil.child(lSpPr, "xfrm"), CoordMap.root());
        if (!box.valid && mSpPr != null) box = parseXfrm(OoxmlUtil.child(mSpPr, "xfrm"), CoordMap.root());
        if (!box.valid) return;

        // Preset geometry
        Element prstGeom = firstNonNull(OoxmlUtil.child(spPr, "prstGeom"),
                OoxmlUtil.child(lSpPr, "prstGeom"), OoxmlUtil.child(mSpPr, "prstGeom"));
        String prst = prstGeom != null ? OoxmlUtil.attr(prstGeom, "prst") : (isConnector ? "line" : "rect");

        // Resolve shape fill
        Element styleEl = firstNonNull(OoxmlUtil.child(sp, "style"),
                layoutPh != null ? OoxmlUtil.child(layoutPh, "style") : null,
                masterPh != null ? OoxmlUtil.child(masterPh, "style") : null);
        OoxmlUtil.FillSpec fill = OoxmlUtil.parseFillFromContainer(spPr, ctx.theme, ctx.clrMap, 0, rels, ctx.entries);
        if (fill == null && lSpPr != null) {
            fill = OoxmlUtil.parseFillFromContainer(lSpPr, ctx.theme, ctx.clrMap, 0,
                    ctx.layout != null ? ctx.layout.rels : rels, ctx.entries);
        }
        if (fill == null && mSpPr != null) {
            fill = OoxmlUtil.parseFillFromContainer(mSpPr, ctx.theme, ctx.clrMap, 0,
                    ctx.master != null ? ctx.master.part.rels : rels, ctx.entries);
        }
        if (fill == null && styleEl != null && !isConnector) {
            Element fillRef = OoxmlUtil.child(styleEl, "fillRef");
            if (fillRef != null) {
                int idx = OoxmlUtil.attrInt(fillRef, "idx", 0);
                if (idx > 0) {
                    int phClr = OoxmlUtil.parseColorNode(fillRef, ctx.theme, ctx.clrMap, 0);
                    Element tf = resolveThemeFillByIdx(ctx.theme, idx);
                    if (tf != null) fill = OoxmlUtil.parseFillElement(tf, ctx.theme, ctx.clrMap, phClr, rels, ctx.entries);
                    else if (phClr != 0) {
                        fill = new OoxmlUtil.FillSpec();
                        fill.kind = OoxmlUtil.FillSpec.SOLID;
                        fill.color = phClr;
                    }
                }
            }
        }

        // Resolve outline stroke (<a:ln> or <p:style><a:lnRef>)
        Element lnEl = firstNonNull(OoxmlUtil.child(spPr, "ln"), OoxmlUtil.child(lSpPr, "ln"), OoxmlUtil.child(mSpPr, "ln"));
        Paint strokePaint = buildStrokePaint(lnEl, styleEl, ctx, isConnector);

        RectF rect = new RectF(box.x, box.y, box.x + box.w, box.y + box.h);
        Element custGeom = firstNonNull(OoxmlUtil.child(spPr, "custGeom"),
                OoxmlUtil.child(lSpPr, "custGeom"), OoxmlUtil.child(mSpPr, "custGeom"));
        Path shapePath = custGeom != null
                ? buildCustomPath(custGeom, rect, box.flipH, box.flipV)
                : buildShapePath(prst, prstGeom, rect, box.flipH, box.flipV, isConnector);

        cv.save();
        if (Math.abs(box.rotDeg) > 0.05f) {
            cv.rotate(box.rotDeg, rect.centerX(), rect.centerY());
        }
        if (isConnector || "line".equals(prst) || "straightConnector1".equals(prst)) {
            if (strokePaint != null) {
                float x1 = box.flipH ? rect.right : rect.left;
                float y1 = box.flipV ? rect.bottom : rect.top;
                float x2 = box.flipH ? rect.left : rect.right;
                float y2 = box.flipV ? rect.top : rect.bottom;
                cv.drawLine(x1, y1, x2, y2, strokePaint);
            }
        } else {
            if (fill != null && fill.kind != OoxmlUtil.FillSpec.NONE) {
                OoxmlUtil.drawFill(cv, rect, shapePath, fill, 1400);
            }
            if (strokePaint != null) {
                cv.drawPath(shapePath, strokePaint);
            }
        }

        // Render text body if present
        Element txBody = OoxmlUtil.child(sp, "txBody");
        if (txBody != null && !isConnector) {
            float effectiveBgLuma = ctx.bgLuma;
            if (fill != null && fill.kind != OoxmlUtil.FillSpec.NONE) {
                int rep = fill.representativeColor();
                if (rep != 0 && Color.alpha(rep) > 128) effectiveBgLuma = OoxmlUtil.luminance(rep);
            }
            renderTextBody(cv, ctx, rect, txBody, effectivePhType, hasPh, layoutPh, masterPh, styleEl, effectiveBgLuma);
        }
        cv.restore();
    }

    private static Paint buildStrokePaint(Element lnEl, Element styleEl, SlideCtx ctx, boolean isConnector) {
        if (lnEl != null && OoxmlUtil.child(lnEl, "noFill") != null) return null;
        int col = 0;
        float widthPt = 0f;
        String dash = null;

        if (lnEl != null) {
            Element sf = OoxmlUtil.child(lnEl, "solidFill");
            if (sf != null) col = OoxmlUtil.parseColorNode(sf, ctx.theme, ctx.clrMap, 0);
            if (col == 0) {
                Element gf = OoxmlUtil.child(lnEl, "gradFill");
                Element gsLst = gf != null ? OoxmlUtil.child(gf, "gsLst") : null;
                Element firstGs = gsLst != null ? OoxmlUtil.child(gsLst, "gs") : null;
                if (firstGs != null) col = OoxmlUtil.parseColorNode(firstGs, ctx.theme, ctx.clrMap, 0);
            }
            float wEmu = OoxmlUtil.attrFloat(lnEl, "w", -1f);
            if (wEmu > 0f) widthPt = Math.max(0.5f, wEmu / EMU_PT);
            Element prstDash = OoxmlUtil.child(lnEl, "prstDash");
            if (prstDash != null) dash = OoxmlUtil.attr(prstDash, "val");
        }

        if (col == 0 && styleEl != null) {
            Element lnRef = OoxmlUtil.child(styleEl, "lnRef");
            if (lnRef != null) {
                int idx = OoxmlUtil.attrInt(lnRef, "idx", 0);
                if (idx > 0) {
                    col = OoxmlUtil.parseColorNode(lnRef, ctx.theme, ctx.clrMap, 0);
                    if (widthPt <= 0f && idx - 1 < ctx.theme.lineStyles.size()) {
                        float tw = OoxmlUtil.attrFloat(ctx.theme.lineStyles.get(idx - 1), "w", 12700f);
                        widthPt = Math.max(0.5f, tw / EMU_PT);
                    }
                }
            }
        }
        if (col == 0 && isConnector && lnEl != null) {
            col = OoxmlUtil.resolveSchemeColor("tx1", ctx.theme, ctx.clrMap, 0);
        }
        if (col == 0) return null;
        if (widthPt <= 0f) widthPt = 1f;

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(col);
        p.setStrokeWidth(widthPt);
        if (dash != null && !"solid".equals(dash)) {
            if (dash.contains("dot") && !dash.contains("dash")) {
                p.setPathEffect(new DashPathEffect(new float[]{widthPt * 1.5f, widthPt * 2.5f}, 0f));
            } else {
                p.setPathEffect(new DashPathEffect(new float[]{widthPt * 4f, widthPt * 3f}, 0f));
            }
        }
        return p;
    }

    private static Path buildShapePath(String prst, Element prstGeom, RectF r, boolean flipH, boolean flipV, boolean isConnector) {
        Path p = new Path();
        if (prst == null || "rect".equals(prst) || "flowChartProcess".equals(prst)) {
            p.addRect(r, Path.Direction.CW);
            return p;
        }
        float w = r.width(), h = r.height(), minSide = Math.min(w, h);
        if ("roundRect".equals(prst) || "round2SameRect".equals(prst) || "snip1Rect".equals(prst)) {
            float adj = 16667f;
            Element avLst = prstGeom != null ? OoxmlUtil.child(prstGeom, "avLst") : null;
            if (avLst != null) {
                for (Element gd : OoxmlUtil.children(avLst, "gd")) {
                    String fmla = OoxmlUtil.attr(gd, "fmla");
                    if (fmla != null && fmla.startsWith("val ")) {
                        try { adj = Float.parseFloat(fmla.substring(4).trim()); } catch (NumberFormatException ignored) {}
                        break;
                    }
                }
            }
            float rad = minSide * Math.max(0f, Math.min(0.5f, adj / 100000f));
            p.addRoundRect(r, rad, rad, Path.Direction.CW);
        } else if ("ellipse".equals(prst) || "oval".equals(prst)) {
            p.addOval(r, Path.Direction.CW);
        } else if ("triangle".equals(prst)) {
            p.moveTo(r.centerX(), r.top);
            p.lineTo(r.right, r.bottom);
            p.lineTo(r.left, r.bottom);
            p.close();
        } else if ("rtTriangle".equals(prst)) {
            p.moveTo(r.left, r.top);
            p.lineTo(r.right, r.bottom);
            p.lineTo(r.left, r.bottom);
            p.close();
        } else if ("diamond".equals(prst)) {
            p.moveTo(r.centerX(), r.top);
            p.lineTo(r.right, r.centerY());
            p.lineTo(r.centerX(), r.bottom);
            p.lineTo(r.left, r.centerY());
            p.close();
        } else if ("parallelogram".equals(prst)) {
            float off = Math.min(w * 0.25f, h * 0.35f);
            p.moveTo(r.left + off, r.top);
            p.lineTo(r.right, r.top);
            p.lineTo(r.right - off, r.bottom);
            p.lineTo(r.left, r.bottom);
            p.close();
        } else if ("chevron".equals(prst) || "homePlate".equals(prst)) {
            float tip = Math.min(w * 0.25f, h * 0.5f);
            p.moveTo(r.left, r.top);
            p.lineTo(r.right - tip, r.top);
            p.lineTo(r.right, r.centerY());
            p.lineTo(r.right - tip, r.bottom);
            p.lineTo(r.left, r.bottom);
            if ("chevron".equals(prst)) p.lineTo(r.left + tip, r.centerY());
            p.close();
        } else if ("hexagon".equals(prst)) {
            float off = w * 0.2f;
            p.moveTo(r.left + off, r.top);
            p.lineTo(r.right - off, r.top);
            p.lineTo(r.right, r.centerY());
            p.lineTo(r.right - off, r.bottom);
            p.lineTo(r.left + off, r.bottom);
            p.lineTo(r.left, r.centerY());
            p.close();
        } else if ("rightArrow".equals(prst)) {
            float head = Math.min(w * 0.4f, h * 0.6f);
            float bar = h * 0.25f;
            p.moveTo(r.left, r.top + bar);
            p.lineTo(r.right - head, r.top + bar);
            p.lineTo(r.right - head, r.top);
            p.lineTo(r.right, r.centerY());
            p.lineTo(r.right - head, r.bottom);
            p.lineTo(r.right - head, r.bottom - bar);
            p.lineTo(r.left, r.bottom - bar);
            p.close();
        } else if ("leftArrow".equals(prst)) {
            float head = Math.min(w * 0.4f, h * 0.6f);
            float bar = h * 0.25f;
            p.moveTo(r.right, r.top + bar);
            p.lineTo(r.left + head, r.top + bar);
            p.lineTo(r.left + head, r.top);
            p.lineTo(r.left, r.centerY());
            p.lineTo(r.left + head, r.bottom);
            p.lineTo(r.left + head, r.bottom - bar);
            p.lineTo(r.right, r.bottom - bar);
            p.close();
        } else {
            p.addRect(r, Path.Direction.CW);
        }
        return p;
    }

    private static Path buildCustomPath(Element custGeom, RectF r, boolean flipH, boolean flipV) {
        Path p = new Path();
        Element pathLst = OoxmlUtil.child(custGeom, "pathLst");
        if (pathLst == null) {
            p.addRect(r, Path.Direction.CW);
            return p;
        }
        boolean any = false;
        for (Element pathEl : OoxmlUtil.children(pathLst, "path")) {
            float pw = OoxmlUtil.attrFloat(pathEl, "w", 0f);
            float ph = OoxmlUtil.attrFloat(pathEl, "h", 0f);
            if (pw <= 0f || ph <= 0f) {
                // Find bounding extents of points if path w/h omitted
                float maxX = 1f, maxY = 1f;
                for (Element cmd : OoxmlUtil.children(pathEl, null)) {
                    for (Element pt : OoxmlUtil.children(cmd, "pt")) {
                        maxX = Math.max(maxX, OoxmlUtil.attrFloat(pt, "x", 0f));
                        maxY = Math.max(maxY, OoxmlUtil.attrFloat(pt, "y", 0f));
                    }
                }
                if (pw <= 0f) pw = maxX;
                if (ph <= 0f) ph = maxY;
            }
            for (Element cmd : OoxmlUtil.children(pathEl, null)) {
                String c = OoxmlUtil.localName(cmd);
                if ("moveTo".equals(c)) {
                    Element pt = OoxmlUtil.child(cmd, "pt");
                    if (pt != null) {
                        p.moveTo(mapCustomX(pt, pw, r, flipH), mapCustomY(pt, ph, r, flipV));
                        any = true;
                    }
                } else if ("lnTo".equals(c)) {
                    Element pt = OoxmlUtil.child(cmd, "pt");
                    if (pt != null) {
                        p.lineTo(mapCustomX(pt, pw, r, flipH), mapCustomY(pt, ph, r, flipV));
                        any = true;
                    }
                } else if ("cubicBezTo".equals(c)) {
                    List<Element> pts = OoxmlUtil.children(cmd, "pt");
                    if (pts.size() >= 3) {
                        p.cubicTo(
                                mapCustomX(pts.get(0), pw, r, flipH), mapCustomY(pts.get(0), ph, r, flipV),
                                mapCustomX(pts.get(1), pw, r, flipH), mapCustomY(pts.get(1), ph, r, flipV),
                                mapCustomX(pts.get(2), pw, r, flipH), mapCustomY(pts.get(2), ph, r, flipV));
                        any = true;
                    }
                } else if ("quadBezTo".equals(c)) {
                    List<Element> pts = OoxmlUtil.children(cmd, "pt");
                    if (pts.size() >= 2) {
                        p.quadTo(
                                mapCustomX(pts.get(0), pw, r, flipH), mapCustomY(pts.get(0), ph, r, flipV),
                                mapCustomX(pts.get(1), pw, r, flipH), mapCustomY(pts.get(1), ph, r, flipV));
                        any = true;
                    }
                } else if ("close".equals(c)) {
                    p.close();
                }
            }
        }
        if (!any) p.addRect(r, Path.Direction.CW);
        return p;
    }

    private static float mapCustomX(Element pt, float pw, RectF r, boolean flipH) {
        float fx = OoxmlUtil.attrFloat(pt, "x", 0f) / pw;
        return r.left + (flipH ? (1f - fx) : fx) * r.width();
    }

    private static float mapCustomY(Element pt, float ph, RectF r, boolean flipV) {
        float fy = OoxmlUtil.attrFloat(pt, "y", 0f) / ph;
        return r.top + (flipV ? (1f - fy) : fy) * r.height();
    }

    // ---- Picture Rendering -------------------------------------------------------------------

    private static void renderPicture(Canvas cv, SlideCtx ctx, Element pic,
                                      Map<String, OoxmlUtil.Rel> rels, CoordMap cmap) {
        Element spPr = OoxmlUtil.child(pic, "spPr");
        XfrmBox box = parseXfrm(OoxmlUtil.child(spPr, "xfrm"), cmap);
        if (!box.valid) return;

        Element blipFill = OoxmlUtil.child(pic, "blipFill");
        OoxmlUtil.FillSpec fill = OoxmlUtil.parseBlipFill(blipFill, rels, ctx.entries);
        if (fill == null) return;

        RectF rect = new RectF(box.x, box.y, box.x + box.w, box.y + box.h);
        Element prstGeom = OoxmlUtil.child(spPr, "prstGeom");
        String prst = prstGeom != null ? OoxmlUtil.attr(prstGeom, "prst") : "rect";
        Path clip = (prst != null && !"rect".equals(prst)) ? buildShapePath(prst, prstGeom, rect, false, false, false) : null;

        cv.save();
        if (Math.abs(box.rotDeg) > 0.05f) cv.rotate(box.rotDeg, rect.centerX(), rect.centerY());
        if (box.flipH || box.flipV) {
            cv.scale(box.flipH ? -1f : 1f, box.flipV ? -1f : 1f, rect.centerX(), rect.centerY());
        }
        OoxmlUtil.drawFill(cv, rect, clip, fill, 1800);
        Paint stroke = buildStrokePaint(OoxmlUtil.child(spPr, "ln"), OoxmlUtil.child(pic, "style"), ctx, false);
        if (stroke != null) {
            if (clip != null) cv.drawPath(clip, stroke);
            else cv.drawRect(rect, stroke);
        }
        cv.restore();
    }

    // ---- Table Rendering (<p:graphicFrame><a:tbl>) -------------------------------------------

    private static void renderGraphicFrame(Canvas cv, SlideCtx ctx, Element gf,
                                           Map<String, OoxmlUtil.Rel> rels, CoordMap cmap) {
        XfrmBox box = parseXfrm(OoxmlUtil.child(gf, "xfrm"), cmap);
        if (!box.valid) return;
        Element tbl = OoxmlUtil.findFirstDescendant(gf, "tbl");
        if (tbl == null) return;

        Element tblGrid = OoxmlUtil.child(tbl, "tblGrid");
        List<Element> gridCols = OoxmlUtil.children(tblGrid, "gridCol");
        List<Element> rows = OoxmlUtil.children(tbl, "tr");
        if (rows.isEmpty()) return;

        int colCount = gridCols.size();
        if (colCount == 0) {
            for (Element tr : rows) colCount = Math.max(colCount, OoxmlUtil.children(tr, "tc").size());
        }
        if (colCount <= 0) return;

        float[] colW = new float[colCount];
        float sumW = 0f;
        for (int c = 0; c < colCount; c++) {
            float w = c < gridCols.size() ? OoxmlUtil.attrFloat(gridCols.get(c), "w", 0f) / EMU_PT : 0f;
            colW[c] = w > 0f ? w : (box.w / colCount);
            sumW += colW[c];
        }
        if (sumW > 0f) {
            float scale = box.w / sumW;
            for (int c = 0; c < colCount; c++) colW[c] *= scale;
        }
        float[] colX = new float[colCount + 1];
        colX[0] = box.x;
        for (int c = 0; c < colCount; c++) colX[c + 1] = colX[c] + colW[c];

        Element tblPr = OoxmlUtil.child(tbl, "tblPr");
        boolean firstRowHeader = tblPr == null || OoxmlUtil.attrBool(tblPr, "firstRow", true);
        boolean bandRow = tblPr != null && OoxmlUtil.attrBool(tblPr, "bandRow", true);
        int accent1 = OoxmlUtil.resolveSchemeColor("accent1", ctx.theme, ctx.clrMap, 0);
        if (accent1 == 0) accent1 = 0xFF4472C4;

        float curY = box.y;
        Paint defaultBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
        defaultBorder.setStyle(Paint.Style.STROKE);
        defaultBorder.setColor(0xFFC8CDD6);
        defaultBorder.setStrokeWidth(0.75f);

        for (int ri = 0; ri < rows.size(); ri++) {
            Element tr = rows.get(ri);
            float rh = Math.max(18f, OoxmlUtil.attrFloat(tr, "h", 24f * EMU_PT) / EMU_PT);
            List<Element> tcs = OoxmlUtil.children(tr, "tc");
            for (int ci = 0; ci < tcs.size() && ci < colCount; ci++) {
                Element tc = tcs.get(ci);
                if (OoxmlUtil.attrBool(tc, "hMerge", false) || OoxmlUtil.attrBool(tc, "vMerge", false)) continue;
                int gridSpan = Math.max(1, OoxmlUtil.attrInt(tc, "gridSpan", 1));
                int endCol = Math.min(colCount, ci + gridSpan);
                int rowSpan = Math.max(1, OoxmlUtil.attrInt(tc, "rowSpan", 1));
                float cellH = rh;
                for (int rs = 1; rs < rowSpan && ri + rs < rows.size(); rs++) {
                    cellH += Math.max(18f, OoxmlUtil.attrFloat(rows.get(ri + rs), "h", 24f * EMU_PT) / EMU_PT);
                }

                RectF cellRect = new RectF(colX[ci], curY, colX[endCol], curY + cellH);
                Element tcPr = OoxmlUtil.child(tc, "tcPr");
                OoxmlUtil.FillSpec cellFill = OoxmlUtil.parseFillFromContainer(tcPr, ctx.theme, ctx.clrMap, 0, rels, ctx.entries);
                float cellBgLuma = ctx.bgLuma;
                if (cellFill != null && cellFill.kind != OoxmlUtil.FillSpec.NONE) {
                    OoxmlUtil.drawFill(cv, cellRect, null, cellFill, 800);
                    int rep = cellFill.representativeColor();
                    if (rep != 0 && Color.alpha(rep) > 128) cellBgLuma = OoxmlUtil.luminance(rep);
                } else if (cellFill == null) {
                    int fillCol = 0;
                    if (ri == 0 && firstRowHeader) fillCol = accent1;
                    else if (bandRow && ri % 2 == 1) fillCol = blendTint(accent1, 0.85f);
                    else fillCol = Color.WHITE;
                    Paint cp = new Paint();
                    cp.setColor(fillCol);
                    cv.drawRect(cellRect, cp);
                    cellBgLuma = OoxmlUtil.luminance(fillCol);
                }
                cv.drawRect(cellRect, defaultBorder);

                Element txBody = OoxmlUtil.child(tc, "txBody");
                if (txBody != null) {
                    RectF inset = new RectF(cellRect.left + 4f, cellRect.top + 3f, cellRect.right - 4f, cellRect.bottom - 3f);
                    if (inset.width() > 4f && inset.height() > 4f) {
                        renderTextBody(cv, ctx, inset, txBody, null, false, null, null, null, cellBgLuma);
                    }
                }
            }
            curY += rh;
        }
    }

    private static int blendTint(int color, float whiteRatio) {
        int r = Math.round(Color.red(color) * (1f - whiteRatio) + 255f * whiteRatio);
        int g = Math.round(Color.green(color) * (1f - whiteRatio) + 255f * whiteRatio);
        int b = Math.round(Color.blue(color) * (1f - whiteRatio) + 255f * whiteRatio);
        return Color.rgb(r, g, b);
    }

    // ---- Rich Text Body Rendering with Full Inheritance --------------------------------------

    private static final class LevelStyle {
        float fontSizePt = 0f;
        int color = 0;
        Boolean bold = null;
        Boolean italic = null;
        String fontName = null;
        Layout.Alignment align = null;
        boolean justify = false;
        float marLPt = 0f;
        float marRPt = 0f;
        float indentPt = 0f;
        float spcBefPt = -1f;
        float spcBefPct = -1f;
        float spcAftPt = -1f;
        float spcAftPct = -1f;
        float lineMult = 0f;
        String bulletChar = null;
        String bulletFont = null;
        int bulletColor = 0;
        float bulletSzPct = 0f;
        float bulletSzPt = 0f;
        boolean bulletAutoNum = false;
        String bulletAutoType = "arabicPeriod";
        int bulletStartAt = 1;
        boolean bulletNone = false;
    }

    private static void renderTextBody(Canvas cv, SlideCtx ctx, RectF boxRect, Element txBody,
                                       String phType, boolean hasPh, Element layoutPh, Element masterPh,
                                       Element styleEl, float bgLuma) {
        List<Element> paras = OoxmlUtil.children(txBody, "p");
        if (paras.isEmpty()) return;

        // Gather <a:bodyPr> from Master -> Layout -> Slide
        Element mBodyPr = masterPh != null ? OoxmlUtil.child(OoxmlUtil.child(masterPh, "txBody"), "bodyPr") : null;
        Element lBodyPr = layoutPh != null ? OoxmlUtil.child(OoxmlUtil.child(layoutPh, "txBody"), "bodyPr") : null;
        Element sBodyPr = OoxmlUtil.child(txBody, "bodyPr");

        float lIns = resolveBodyInset(sBodyPr, lBodyPr, mBodyPr, "lIns", 7.2f);
        float rIns = resolveBodyInset(sBodyPr, lBodyPr, mBodyPr, "rIns", 7.2f);
        float tIns = resolveBodyInset(sBodyPr, lBodyPr, mBodyPr, "tIns", 3.6f);
        float bIns = resolveBodyInset(sBodyPr, lBodyPr, mBodyPr, "bIns", 3.6f);
        String anchor = firstNonEmpty(OoxmlUtil.attr(sBodyPr, "anchor"),
                OoxmlUtil.attr(lBodyPr, "anchor"), OoxmlUtil.attr(mBodyPr, "anchor"));
        String wrapAttr = firstNonEmpty(OoxmlUtil.attr(sBodyPr, "wrap"),
                OoxmlUtil.attr(lBodyPr, "wrap"), OoxmlUtil.attr(mBodyPr, "wrap"));
        boolean noWrap = "none".equalsIgnoreCase(wrapAttr);
        boolean spAutoFit = (sBodyPr != null && OoxmlUtil.child(sBodyPr, "spAutoFit") != null);
        boolean explicitNoAutofit = (sBodyPr != null && OoxmlUtil.child(sBodyPr, "noAutofit") != null);

        float fontScale = 1f;
        float lnSpcReduction = 0f;
        if (!spAutoFit && !explicitNoAutofit) {
            Element normAutofit = firstNonNull(
                    OoxmlUtil.child(sBodyPr, "normAutofit"),
                    OoxmlUtil.child(lBodyPr, "normAutofit"),
                    OoxmlUtil.child(mBodyPr, "normAutofit"));
            if (normAutofit != null) {
                fontScale = OoxmlUtil.attrFloat(normAutofit, "fontScale", 100000f) / 100000f;
                lnSpcReduction = OoxmlUtil.attrFloat(normAutofit, "lnSpcReduction", 0f) / 100000f;
                fontScale = Math.max(0.45f, Math.min(1f, fontScale));
            }
        }

        // Shape style fontRef color & font
        int styleFontColor = 0;
        String styleFontName = null;
        if (styleEl != null) {
            Element fontRef = OoxmlUtil.child(styleEl, "fontRef");
            if (fontRef != null) {
                styleFontColor = OoxmlUtil.parseColorNode(fontRef, ctx.theme, ctx.clrMap, 0);
                String idx = OoxmlUtil.attr(fontRef, "idx");
                if ("major".equalsIgnoreCase(idx)) styleFontName = ctx.theme.majorFont;
                else if ("minor".equalsIgnoreCase(idx)) styleFontName = ctx.theme.minorFont;
            }
        }

        // Collect lstStyle hierarchy: defaultTextStyle -> master txStyle -> masterPh -> layoutPh -> shape
        List<Element> lstStyles = new ArrayList<>();
        if (ctx.defaultTextStyle != null) lstStyles.add(ctx.defaultTextStyle);
        if (ctx.master != null) {
            Element mTx = selectMasterTxStyle(ctx.master, phType, hasPh);
            if (mTx != null) lstStyles.add(mTx);
        }
        if (masterPh != null) {
            Element ls = OoxmlUtil.child(OoxmlUtil.child(masterPh, "txBody"), "lstStyle");
            if (ls != null) lstStyles.add(ls);
        }
        if (layoutPh != null) {
            Element ls = OoxmlUtil.child(OoxmlUtil.child(layoutPh, "txBody"), "lstStyle");
            if (ls != null) lstStyles.add(ls);
        }
        Element shapeLst = OoxmlUtil.child(txBody, "lstStyle");
        if (shapeLst != null) lstStyles.add(shapeLst);

        float rightEdge = Math.min(boxRect.right, ctx.pageW - 4f);
        float availW = Math.max(20f, rightEdge - boxRect.left - lIns - rIns);
        float availH = Math.max(12f, boxRect.height() - tIns - bIns);
        boolean isTitle = "title".equalsIgnoreCase(phType) || "ctrTitle".equalsIgnoreCase(phType);
        boolean isSubTitle = "subTitle".equalsIgnoreCase(phType);

        // Build per-paragraph layouts
        ArrayList<ParaDraw> rendered = new ArrayList<>();
        int[] autoNumCounter = new int[10];
        boolean hasAnyText = false;

        for (Element pEl : paras) {
            Element pPr = OoxmlUtil.child(pEl, "pPr");
            int lvl = Math.max(0, Math.min(8, OoxmlUtil.attrInt(pPr, "lvl", 0)));

            LevelStyle ls = resolveLevelStyle(lstStyles, lvl, pPr, ctx);
            float baseFontPt = ls.fontSizePt > 0f ? ls.fontSizePt : (isTitle ? 36f : (isSubTitle ? 22f : 18f));
            baseFontPt *= fontScale;

            int baseColor = ls.color;
            if (baseColor == 0) baseColor = styleFontColor;
            if (baseColor == 0) {
                int tx1 = OoxmlUtil.resolveSchemeColor("tx1", ctx.theme, ctx.clrMap, 0);
                if (tx1 != 0 && Math.abs(OoxmlUtil.luminance(tx1) - bgLuma) > 0.25f) baseColor = tx1;
                else baseColor = bgLuma < 0.45f ? Color.WHITE : 0xFF1A1D24;
            }
            boolean baseBold = ls.bold != null ? ls.bold : isTitle;
            boolean baseItalic = ls.italic != null && ls.italic;
            String baseFont = ls.fontName != null ? ls.fontName : (styleFontName != null ? styleFontName : (isTitle ? ctx.theme.majorFont : ctx.theme.minorFont));
            Layout.Alignment align = ls.align != null ? ls.align : ("ctrTitle".equalsIgnoreCase(phType) ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL);

            SpannableStringBuilder sb = new SpannableStringBuilder();
            float maxRunSize = baseFontPt;
            float firstRunSize = 0f;
            int firstRunColor = 0;

            // Collect runs (<a:r>, <a:br>, <a:fld>)
            for (Element ch : OoxmlUtil.children(pEl, null)) {
                String tag = OoxmlUtil.localName(ch);
                if ("r".equals(tag) || "fld".equals(tag)) {
                    Element rPr = OoxmlUtil.child(ch, "rPr");
                    Element tEl = OoxmlUtil.child(ch, "t");
                    String txt = tEl != null ? tEl.getTextContent() : null;
                    if ("fld".equals(tag) && "slidenum".equalsIgnoreCase(OoxmlUtil.attr(ch, "type"))) {
                        txt = String.valueOf(ctx.slideNumber);
                    }
                    if (txt == null || txt.isEmpty()) continue;
                    if (rPr != null && "all".equalsIgnoreCase(OoxmlUtil.attr(rPr, "cap"))) {
                        txt = txt.toUpperCase(Locale.getDefault());
                    }
                    float rSize = baseFontPt;
                    if (rPr != null && OoxmlUtil.attr(rPr, "sz") != null) {
                        float pt = OoxmlUtil.attrFloat(rPr, "sz", 0f) / 100f;
                        if (pt > 0f) rSize = pt * fontScale;
                    }
                    if (firstRunSize <= 0f) firstRunSize = rSize;
                    if (rSize > maxRunSize) maxRunSize = rSize;

                    boolean rBold = rPr != null && OoxmlUtil.attr(rPr, "b") != null ? OoxmlUtil.attrBool(rPr, "b", baseBold) : baseBold;
                    boolean rItalic = rPr != null && OoxmlUtil.attr(rPr, "i") != null ? OoxmlUtil.attrBool(rPr, "i", baseItalic) : baseItalic;
                    boolean rUnder = rPr != null && OoxmlUtil.attr(rPr, "u") != null && !"none".equalsIgnoreCase(OoxmlUtil.attr(rPr, "u"));
                    boolean rStrike = rPr != null && OoxmlUtil.attr(rPr, "strike") != null && !"noStrike".equalsIgnoreCase(OoxmlUtil.attr(rPr, "strike"));
                    int baseline = rPr != null ? OoxmlUtil.attrInt(rPr, "baseline", 0) : 0;

                    int rCol = 0;
                    if (rPr != null) {
                        Element sf = OoxmlUtil.child(rPr, "solidFill");
                        if (sf != null) rCol = OoxmlUtil.parseColorNode(sf, ctx.theme, ctx.clrMap, 0);
                    }
                    if (rCol == 0) rCol = baseColor;
                    if (firstRunColor == 0) firstRunColor = rCol;

                    String rFont = baseFont;
                    if (rPr != null) {
                        String f = extractFontName(rPr, ctx.theme);
                        if (f != null) rFont = f;
                    }

                    int start = sb.length();
                    sb.append(txt);
                    int end = sb.length();
                    sb.setSpan(new OoxmlUtil.ExactSizeSpan(rSize), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new OoxmlUtil.FontSpan(OoxmlUtil.mapFontFamily(rFont, rBold, rItalic)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new ForegroundColorSpan(rCol), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    if (rUnder) sb.setSpan(new UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    if (rStrike) sb.setSpan(new StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    if (baseline > 5000) sb.setSpan(new OoxmlUtil.ScriptSpan(true), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    else if (baseline < -5000) sb.setSpan(new OoxmlUtil.ScriptSpan(false), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    hasAnyText = true;
                } else if ("br".equals(tag)) {
                    sb.append('\n');
                }
            }

            // Empty paragraph height from endParaRPr if present
            if (sb.length() == 0) {
                Element endPr = OoxmlUtil.child(pEl, "endParaRPr");
                if (endPr != null && OoxmlUtil.attr(endPr, "sz") != null) {
                    float pt = OoxmlUtil.attrFloat(endPr, "sz", 0f) / 100f;
                    if (pt > 0f) maxRunSize = pt * fontScale;
                }
            }

            float refSize = firstRunSize > 0f ? firstRunSize : maxRunSize;
            float leftPad = Math.max(0f, ls.marLPt);
            float rightPad = Math.max(0f, ls.marRPt);

            // Resolve bullet glyph, color, size, and hanging position
            String bulletGlyph = null;
            float bulletSize = refSize;
            int bulletCol = ls.bulletColor != 0 ? ls.bulletColor : (firstRunColor != 0 ? firstRunColor : baseColor);
            float bulletX = 0f;

            if (sb.length() > 0 && !ls.bulletNone) {
                if (ls.bulletAutoNum) {
                    if (autoNumCounter[lvl] == 0) autoNumCounter[lvl] = ls.bulletStartAt;
                    else autoNumCounter[lvl]++;
                    for (int k = lvl + 1; k < autoNumCounter.length; k++) autoNumCounter[k] = 0;
                    bulletGlyph = formatAutoNum(autoNumCounter[lvl], ls.bulletAutoType);
                } else if (ls.bulletChar != null && !ls.bulletChar.isEmpty()) {
                    bulletGlyph = OoxmlUtil.mapBulletChar(ls.bulletChar, ls.bulletFont);
                }
                if (bulletGlyph != null) {
                    if (ls.bulletSzPt > 0f) bulletSize = ls.bulletSzPt * fontScale;
                    else if (ls.bulletSzPct > 0f) bulletSize = refSize * ls.bulletSzPct;

                    if (align == Layout.Alignment.ALIGN_NORMAL) {
                        Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
                        bp.setTextSize(bulletSize);
                        float bw = bp.measureText(bulletGlyph);
                        if (ls.indentPt < 0f) {
                            bulletX = Math.max(0f, leftPad + ls.indentPt);
                            if (leftPad < bulletX + bw + 4f) leftPad = bulletX + bw + 4f;
                        } else {
                            bulletX = leftPad;
                            leftPad = bulletX + bw + Math.max(4f, bulletSize * 0.35f);
                        }
                    } else {
                        // Centered / right-aligned: keep bullet inline with text
                        String inlinePrefix = bulletGlyph + " ";
                        sb.insert(0, inlinePrefix);
                        sb.setSpan(new OoxmlUtil.ExactSizeSpan(bulletSize), 0, inlinePrefix.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        sb.setSpan(new ForegroundColorSpan(bulletCol), 0, inlinePrefix.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        bulletGlyph = null;
                    }
                }
            } else if (sb.length() == 0) {
                autoNumCounter[lvl] = 0;
            }

            ParaDraw pd = new ParaDraw();
            pd.text = sb;
            pd.baseFontPt = maxRunSize;
            pd.align = align;
            pd.justify = ls.justify;
            pd.leftPad = Math.min(availW * 0.48f, leftPad);
            pd.rightPad = Math.min(availW * 0.3f, rightPad);
            pd.bulletGlyph = bulletGlyph;
            pd.bulletX = Math.min(pd.leftPad, bulletX);
            pd.bulletSize = bulletSize;
            pd.bulletColor = bulletCol;

            if (ls.spcBefPt >= 0f) pd.spcBef = ls.spcBefPt * fontScale;
            else if (ls.spcBefPct >= 0f) pd.spcBef = refSize * ls.spcBefPct;
            else pd.spcBef = 0f;

            if (ls.spcAftPt >= 0f) pd.spcAft = ls.spcAftPt * fontScale;
            else if (ls.spcAftPct >= 0f) pd.spcAft = refSize * ls.spcAftPct;
            else pd.spcAft = 0f;

            // Android StaticLayout's 1.0 line height already matches PowerPoint's 100% single spacing
            float lm = ls.lineMult > 0f ? ls.lineMult : 1.0f;
            pd.lineMult = Math.max(0.85f, lm - lnSpcReduction);
            rendered.add(pd);
        }

        if (!hasAnyText) return;

        // Build StaticLayouts; if wrap="none", allow extra horizontal room so single-line labels don't break awkwardly
        float layoutWidth = noWrap ? Math.max(availW, ctx.pageW - boxRect.left - lIns - 4f) : availW;
        float totalH = measureParagraphs(rendered, layoutWidth);

        // Gentle uniform scale-to-fit ONLY if text overflows a bounded box; re-measure at expanded
        // width so centered/right-aligned paragraphs stay aligned across the full box width.
        float fitScale = 1f;
        if (!spAutoFit && availH > 12f && totalH > availH) {
            fitScale = Math.max(0.62f, (float) Math.sqrt(availH / totalH));
            totalH = measureParagraphs(rendered, layoutWidth / fitScale);
            if (totalH * fitScale > availH) {
                fitScale = Math.max(0.52f, availH / totalH);
                totalH = measureParagraphs(rendered, layoutWidth / fitScale);
            }
        }

        float drawnH = totalH * fitScale;
        float startY = boxRect.top + tIns;
        if ("ctr".equalsIgnoreCase(anchor)) {
            startY = boxRect.top + tIns + Math.max(0f, (availH - drawnH) * 0.5f);
        } else if ("b".equalsIgnoreCase(anchor)) {
            startY = boxRect.bottom - bIns - drawnH;
        }

        cv.save();
        cv.translate(boxRect.left + lIns, startY);
        if (fitScale < 0.999f) cv.scale(fitScale, fitScale);

        Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
        float curY = 0f;
        for (int i = 0; i < rendered.size(); i++) {
            ParaDraw pd = rendered.get(i);
            if (i > 0) curY += pd.spcBef;
            if (pd.layout != null) {
                if (pd.bulletGlyph != null && pd.layout.getLineCount() > 0) {
                    bp.setColor(pd.bulletColor);
                    bp.setTextSize(pd.bulletSize);
                    bp.setTypeface(Typeface.SANS_SERIF);
                    float baselineY = curY + pd.layout.getLineBaseline(0);
                    cv.drawText(pd.bulletGlyph, pd.bulletX, baselineY, bp);
                }
                cv.save();
                cv.translate(pd.leftPad, curY);
                pd.layout.draw(cv);
                cv.restore();
                curY += pd.layout.getHeight();
            } else {
                curY += pd.baseFontPt * 0.8f;
            }
            if (i + 1 < rendered.size()) curY += pd.spcAft;
        }
        cv.restore();
    }

    private static final class ParaDraw {
        SpannableStringBuilder text;
        float baseFontPt;
        Layout.Alignment align;
        boolean justify;
        float leftPad;
        float rightPad;
        String bulletGlyph;
        float bulletX;
        float bulletSize;
        int bulletColor;
        float spcBef, spcAft;
        float lineMult;
        StaticLayout layout;
    }

    private static float measureParagraphs(List<ParaDraw> list, float availW) {
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        float total = 0f;
        for (int i = 0; i < list.size(); i++) {
            ParaDraw pd = list.get(i);
            if (i > 0) total += pd.spcBef;
            if (pd.text.length() > 0) {
                tp.setTextSize(pd.baseFontPt);
                int w = Math.max(16, Math.round(availW - pd.leftPad - pd.rightPad));
                StaticLayout.Builder b = StaticLayout.Builder.obtain(pd.text, 0, pd.text.length(), tp, w)
                        .setAlignment(pd.align)
                        .setLineSpacing(0f, pd.lineMult)
                        .setIncludePad(false);
                if (pd.justify) b.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD);
                pd.layout = b.build();
                total += pd.layout.getHeight();
            } else {
                pd.layout = null;
                total += pd.baseFontPt * 0.8f;
            }
            if (i + 1 < list.size()) total += pd.spcAft;
        }
        return total;
    }

    private static Element selectMasterTxStyle(MasterInfo master, String phType, boolean hasPh) {
        if (master == null) return null;
        if ("title".equalsIgnoreCase(phType) || "ctrTitle".equalsIgnoreCase(phType)) return master.titleStyle;
        if (hasPh) return master.bodyStyle != null ? master.bodyStyle : master.otherStyle;
        return master.otherStyle != null ? master.otherStyle : master.bodyStyle;
    }

    private static LevelStyle resolveLevelStyle(List<Element> lstStyles, int lvl, Element pPr, SlideCtx ctx) {
        LevelStyle ls = new LevelStyle();
        String lvlTag = "lvl" + (lvl + 1) + "pPr";
        // Apply default paragraph styles first (lowest priority), then level-specific styles across the cascade
        for (Element lst : lstStyles) {
            Element defPPr = OoxmlUtil.child(lst, "defPPr");
            if (defPPr != null) applyPPr(ls, defPPr, ctx);
        }
        for (Element lst : lstStyles) {
            Element lEl = OoxmlUtil.child(lst, lvlTag);
            if (lEl != null) applyPPr(ls, lEl, ctx);
        }
        if (pPr != null) applyPPr(ls, pPr, ctx);
        return ls;
    }

    private static void applyPPr(LevelStyle ls, Element pPr, SlideCtx ctx) {
        String algn = OoxmlUtil.attr(pPr, "algn");
        if ("ctr".equalsIgnoreCase(algn)) { ls.align = Layout.Alignment.ALIGN_CENTER; ls.justify = false; }
        else if ("r".equalsIgnoreCase(algn)) { ls.align = Layout.Alignment.ALIGN_OPPOSITE; ls.justify = false; }
        else if ("just".equalsIgnoreCase(algn) || "dist".equalsIgnoreCase(algn)) { ls.align = Layout.Alignment.ALIGN_NORMAL; ls.justify = true; }
        else if ("l".equalsIgnoreCase(algn)) { ls.align = Layout.Alignment.ALIGN_NORMAL; ls.justify = false; }

        if (OoxmlUtil.attr(pPr, "marL") != null) ls.marLPt = OoxmlUtil.attrFloat(pPr, "marL", 0f) / EMU_PT;
        if (OoxmlUtil.attr(pPr, "marR") != null) ls.marRPt = OoxmlUtil.attrFloat(pPr, "marR", 0f) / EMU_PT;
        if (OoxmlUtil.attr(pPr, "indent") != null) ls.indentPt = OoxmlUtil.attrFloat(pPr, "indent", 0f) / EMU_PT;

        Element spcBef = OoxmlUtil.child(pPr, "spcBef");
        if (spcBef != null) {
            Element pts = OoxmlUtil.child(spcBef, "spcPts");
            Element pct = OoxmlUtil.child(spcBef, "spcPct");
            if (pts != null) { ls.spcBefPt = Math.max(0f, OoxmlUtil.attrFloat(pts, "val", 0f) / 100f); ls.spcBefPct = -1f; }
            else if (pct != null) { ls.spcBefPct = Math.max(0f, OoxmlUtil.attrFloat(pct, "val", 0f) / 100000f); ls.spcBefPt = -1f; }
        }
        Element spcAft = OoxmlUtil.child(pPr, "spcAft");
        if (spcAft != null) {
            Element pts = OoxmlUtil.child(spcAft, "spcPts");
            Element pct = OoxmlUtil.child(spcAft, "spcPct");
            if (pts != null) { ls.spcAftPt = Math.max(0f, OoxmlUtil.attrFloat(pts, "val", 0f) / 100f); ls.spcAftPct = -1f; }
            else if (pct != null) { ls.spcAftPct = Math.max(0f, OoxmlUtil.attrFloat(pct, "val", 0f) / 100000f); ls.spcAftPt = -1f; }
        }
        Element lnSpc = OoxmlUtil.child(pPr, "lnSpc");
        if (lnSpc != null) {
            Element pct = OoxmlUtil.child(lnSpc, "spcPct");
            if (pct != null) {
                float m = OoxmlUtil.attrFloat(pct, "val", 100000f) / 100000f;
                if (m > 0.5f && m < 4f) ls.lineMult = m;
            }
        }

        Element buFont = OoxmlUtil.child(pPr, "buFont");
        if (buFont != null) {
            String tf = OoxmlUtil.attr(buFont, "typeface");
            if (tf != null && !tf.isEmpty()) ls.bulletFont = tf;
        }
        if (OoxmlUtil.child(pPr, "buFontTx") != null) ls.bulletFont = null;

        Element buClr = OoxmlUtil.child(pPr, "buClr");
        if (buClr != null) {
            int bc = OoxmlUtil.parseColorNode(buClr, ctx.theme, ctx.clrMap, 0);
            if (bc != 0) ls.bulletColor = bc;
        }
        if (OoxmlUtil.child(pPr, "buClrTx") != null) ls.bulletColor = 0;

        Element buSzPct = OoxmlUtil.child(pPr, "buSzPct");
        if (buSzPct != null) {
            float p = OoxmlUtil.attrFloat(buSzPct, "val", 100000f) / 100000f;
            if (p >= 0.25f && p <= 4f) { ls.bulletSzPct = p; ls.bulletSzPt = 0f; }
        }
        Element buSzPts = OoxmlUtil.child(pPr, "buSzPts");
        if (buSzPts != null) {
            float pt = OoxmlUtil.attrFloat(buSzPts, "val", 0f) / 100f;
            if (pt > 0f) { ls.bulletSzPt = pt; ls.bulletSzPct = 0f; }
        }
        if (OoxmlUtil.child(pPr, "buSzTx") != null) { ls.bulletSzPct = 0f; ls.bulletSzPt = 0f; }

        if (OoxmlUtil.child(pPr, "buNone") != null) {
            ls.bulletNone = true;
            ls.bulletChar = null;
            ls.bulletAutoNum = false;
        } else {
            Element buChar = OoxmlUtil.child(pPr, "buChar");
            if (buChar != null) {
                ls.bulletNone = false;
                ls.bulletAutoNum = false;
                ls.bulletChar = OoxmlUtil.attr(buChar, "char");
            }
            Element buAuto = OoxmlUtil.child(pPr, "buAutoNum");
            if (buAuto != null) {
                ls.bulletNone = false;
                ls.bulletAutoNum = true;
                String t = OoxmlUtil.attr(buAuto, "type");
                if (t != null && !t.isEmpty()) ls.bulletAutoType = t;
                ls.bulletStartAt = Math.max(1, OoxmlUtil.attrInt(buAuto, "startAt", 1));
            }
        }

        Element defRPr = OoxmlUtil.child(pPr, "defRPr");
        if (defRPr != null) {
            float sz = OoxmlUtil.attrFloat(defRPr, "sz", 0f) / 100f;
            if (sz > 0f) ls.fontSizePt = sz;
            if (OoxmlUtil.attr(defRPr, "b") != null) ls.bold = OoxmlUtil.attrBool(defRPr, "b", false);
            if (OoxmlUtil.attr(defRPr, "i") != null) ls.italic = OoxmlUtil.attrBool(defRPr, "i", false);
            Element sf = OoxmlUtil.child(defRPr, "solidFill");
            if (sf != null) {
                int c = OoxmlUtil.parseColorNode(sf, ctx.theme, ctx.clrMap, 0);
                if (c != 0) ls.color = c;
            }
            String fn = extractFontName(defRPr, ctx.theme);
            if (fn != null) ls.fontName = fn;
        }
    }

    private static String formatAutoNum(int n, String type) {
        if (type == null) return n + ".";
        String t = type.toLowerCase(Locale.US);
        String val;
        if (t.startsWith("alphalc")) val = String.valueOf((char) ('a' + ((Math.max(1, n) - 1) % 26)));
        else if (t.startsWith("alphauc")) val = String.valueOf((char) ('A' + ((Math.max(1, n) - 1) % 26)));
        else if (t.startsWith("romanlc")) val = toRoman(n).toLowerCase(Locale.US);
        else if (t.startsWith("romanuc")) val = toRoman(n);
        else val = String.valueOf(n);

        if (t.contains("parenboth")) return "(" + val + ")";
        if (t.contains("parenr")) return val + ")";
        if (t.contains("plain")) return val;
        return val + ".";
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

    private static String extractFontName(Element rPr, OoxmlUtil.Theme theme) {
        Element latin = OoxmlUtil.child(rPr, "latin");
        if (latin == null) latin = OoxmlUtil.child(rPr, "ea");
        if (latin == null) return null;
        String tf = OoxmlUtil.attr(latin, "typeface");
        if (tf == null || tf.isEmpty()) return null;
        if (tf.startsWith("+mj")) return theme != null ? theme.majorFont : "Calibri Light";
        if (tf.startsWith("+mn")) return theme != null ? theme.minorFont : "Calibri";
        return tf;
    }

    private static float resolveBodyInset(Element s, Element l, Element m, String attr, float defPt) {
        String v = firstNonEmpty(OoxmlUtil.attr(s, attr), OoxmlUtil.attr(l, attr), OoxmlUtil.attr(m, attr));
        if (v == null) return defPt;
        try { return Math.max(0f, Float.parseFloat(v) / EMU_PT); } catch (NumberFormatException e) { return defPt; }
    }

    private static Element firstNonNull(Element a, Element b, Element c) {
        if (a != null) return a;
        if (b != null) return b;
        return c;
    }

    private static String firstNonEmpty(String a, String b, String c) {
        if (a != null && !a.isEmpty()) return a;
        if (b != null && !b.isEmpty()) return b;
        if (c != null && !c.isEmpty()) return c;
        return null;
    }
}

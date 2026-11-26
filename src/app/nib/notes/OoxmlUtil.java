package app.nib.notes;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.style.MetricAffectingSpan;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Shared OOXML DOM, relationship, theme/color, gradient/image fill, and font utilities
 * used by {@link DocxRenderer} and {@link PptxRenderer}.
 */
final class OoxmlUtil {
    private OoxmlUtil() {}

    static final float EMU_PER_PT = 12700f;
    static final float TWIPS_PER_PT = 20f;

    // ---- ZIP entry lookup (case-insensitive fallback) ----------------------------------------

    static final class ZipEntries {
        final Map<String, byte[]> exact;
        private final Map<String, byte[]> lower;

        ZipEntries(Map<String, byte[]> entries) {
            this.exact = entries;
            this.lower = new HashMap<>(entries.size() * 2);
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                this.lower.put(e.getKey().toLowerCase(Locale.US), e.getValue());
            }
        }

        byte[] get(String path) {
            if (path == null) return null;
            byte[] b = exact.get(path);
            if (b != null) return b;
            return lower.get(path.toLowerCase(Locale.US));
        }

        boolean contains(String path) {
            return get(path) != null;
        }
    }

    // ---- DOM parsing & traversal helpers -----------------------------------------------------

    static Document parseXml(byte[] xml) {
        if (xml == null || xml.length == 0) return null;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) {}
            try { f.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Exception ignored) {}
            try { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Exception ignored) {}
            DocumentBuilder db = f.newDocumentBuilder();
            return db.parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            return null;
        }
    }

    static String localName(Node n) {
        if (n == null) return "";
        String ln = n.getLocalName();
        if (ln != null) return ln;
        String name = n.getNodeName();
        if (name == null) return "";
        int i = name.indexOf(':');
        return i >= 0 ? name.substring(i + 1) : name;
    }

    static Element child(Element parent, String local) {
        if (parent == null) return null;
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE && local.equals(localName(n))) {
                return (Element) n;
            }
        }
        return null;
    }

    static List<Element> children(Element parent, String local) {
        ArrayList<Element> out = new ArrayList<>();
        if (parent == null) return out;
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE && (local == null || local.equals(localName(n)))) {
                out.add((Element) n);
            }
        }
        return out;
    }

    static Element findFirstDescendant(Element root, String local) {
        if (root == null) return null;
        NodeList nl = root.getElementsByTagNameNS("*", local);
        if (nl != null && nl.getLength() > 0 && nl.item(0) instanceof Element) {
            return (Element) nl.item(0);
        }
        return null;
    }

    static String attr(Element el, String local) {
        if (el == null) return null;
        NamedNodeMap attrs = el.getAttributes();
        if (attrs == null) return null;
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            if (local.equals(localName(a))) return a.getNodeValue();
        }
        return null;
    }

    static float attrFloat(Element el, String local, float def) {
        String v = attr(el, local);
        if (v == null || v.isEmpty()) return def;
        try { return Float.parseFloat(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    static int attrInt(Element el, String local, int def) {
        String v = attr(el, local);
        if (v == null || v.isEmpty()) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    static long attrLong(Element el, String local, long def) {
        String v = attr(el, local);
        if (v == null || v.isEmpty()) return def;
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    static boolean attrBool(Element el, String local, boolean def) {
        String v = attr(el, local);
        if (v == null) return def;
        if ("0".equals(v) || "false".equalsIgnoreCase(v) || "off".equalsIgnoreCase(v)) return false;
        if ("1".equals(v) || "true".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v)) return true;
        return def;
    }

    static boolean isWordToggleOn(Element el) {
        if (el == null) return false;
        String v = attr(el, "val");
        return v == null || (!"0".equals(v) && !"false".equalsIgnoreCase(v) && !"none".equalsIgnoreCase(v));
    }

    // ---- Relationships (.rels) ---------------------------------------------------------------

    static final class Rel {
        final String id;
        final String type;
        final String target;
        final boolean external;

        Rel(String id, String type, String target, boolean external) {
            this.id = id;
            this.type = type;
            this.target = target;
            this.external = external;
        }
    }

    static Map<String, Rel> parseRels(ZipEntries entries, String relsPath, String baseDir) {
        HashMap<String, Rel> map = new HashMap<>();
        Document doc = parseXml(entries.get(relsPath));
        if (doc == null) return map;
        for (Element el : children(doc.getDocumentElement(), "Relationship")) {
            String id = attr(el, "Id");
            String type = attr(el, "Type");
            String target = attr(el, "Target");
            String mode = attr(el, "TargetMode");
            boolean ext = "External".equalsIgnoreCase(mode);
            if (id != null && target != null) {
                String resolved = ext ? target : resolveZipPath(baseDir, target);
                map.put(id, new Rel(id, type != null ? type : "", resolved, ext));
            }
        }
        return map;
    }

    static String relsPathFor(String partPath) {
        int slash = partPath.lastIndexOf('/');
        String dir = slash >= 0 ? partPath.substring(0, slash + 1) : "";
        String name = slash >= 0 ? partPath.substring(slash + 1) : partPath;
        return dir + "_rels/" + name + ".rels";
    }

    static String dirOf(String partPath) {
        int slash = partPath.lastIndexOf('/');
        return slash >= 0 ? partPath.substring(0, slash + 1) : "";
    }

    static String findRelTargetByTypeSuffix(Map<String, Rel> rels, String suffix) {
        if (rels == null) return null;
        for (Rel r : rels.values()) {
            if (!r.external && r.type.endsWith(suffix)) return r.target;
        }
        return null;
    }

    static String resolveZipPath(String baseDir, String target) {
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

    // ---- Theme & Color Resolution ------------------------------------------------------------

    static final class Theme {
        final Map<String, Integer> colors = new HashMap<>();
        final List<Element> fillStyles = new ArrayList<>();
        final List<Element> bgFillStyles = new ArrayList<>();
        final List<Element> lineStyles = new ArrayList<>();
        String majorFont = "Calibri Light";
        String minorFont = "Calibri";

        Theme() {
            // Sensible Office defaults if theme1.xml is absent
            colors.put("dk1", 0xFF000000);
            colors.put("lt1", 0xFFFFFFFF);
            colors.put("dk2", 0xFF44546A);
            colors.put("lt2", 0xFFE7E6E6);
            colors.put("accent1", 0xFF4472C4);
            colors.put("accent2", 0xFFED7D31);
            colors.put("accent3", 0xFFA5A5A5);
            colors.put("accent4", 0xFFFFC000);
            colors.put("accent5", 0xFF5B9BD5);
            colors.put("accent6", 0xFF70AD47);
            colors.put("hlink", 0xFF0563C1);
            colors.put("folHlink", 0xFF954F72);
        }
    }

    static Theme parseTheme(byte[] xml) {
        Theme t = new Theme();
        Document doc = parseXml(xml);
        if (doc == null) return t;
        Element root = doc.getDocumentElement();
        Element elements = child(root, "themeElements");
        if (elements == null) elements = findFirstDescendant(root, "themeElements");
        if (elements == null) return t;

        Element clrScheme = child(elements, "clrScheme");
        if (clrScheme != null) {
            for (Element slot : children(clrScheme, null)) {
                String slotName = localName(slot);
                int col = parseColorNode(slot, null, null, 0);
                if (col != 0) t.colors.put(slotName, col);
            }
        }

        Element fontScheme = child(elements, "fontScheme");
        if (fontScheme != null) {
            Element maj = child(fontScheme, "majorFont");
            Element majLatin = maj != null ? child(maj, "latin") : null;
            if (majLatin != null && attr(majLatin, "typeface") != null) t.majorFont = attr(majLatin, "typeface");
            Element min = child(fontScheme, "minorFont");
            Element minLatin = min != null ? child(min, "latin") : null;
            if (minLatin != null && attr(minLatin, "typeface") != null) t.minorFont = attr(minLatin, "typeface");
        }

        Element fmtScheme = child(elements, "fmtScheme");
        if (fmtScheme != null) {
            Element fl = child(fmtScheme, "fillStyleLst");
            if (fl != null) t.fillStyles.addAll(children(fl, null));
            Element bfl = child(fmtScheme, "bgFillStyleLst");
            if (bfl != null) t.bgFillStyles.addAll(children(bfl, null));
            Element ll = child(fmtScheme, "lnStyleLst");
            if (ll != null) t.lineStyles.addAll(children(ll, null));
        }
        return t;
    }

    static Map<String, String> defaultColorMap() {
        HashMap<String, String> m = new HashMap<>();
        m.put("bg1", "lt1");
        m.put("tx1", "dk1");
        m.put("bg2", "lt2");
        m.put("tx2", "dk2");
        m.put("accent1", "accent1");
        m.put("accent2", "accent2");
        m.put("accent3", "accent3");
        m.put("accent4", "accent4");
        m.put("accent5", "accent5");
        m.put("accent6", "accent6");
        m.put("hlink", "hlink");
        m.put("folHlink", "folHlink");
        return m;
    }

    static Map<String, String> parseColorMap(Element clrMapEl, Map<String, String> base) {
        HashMap<String, String> m = new HashMap<>(base != null ? base : defaultColorMap());
        if (clrMapEl == null) return m;
        NamedNodeMap attrs = clrMapEl.getAttributes();
        if (attrs != null) {
            for (int i = 0; i < attrs.getLength(); i++) {
                Node a = attrs.item(i);
                String k = localName(a);
                String v = a.getNodeValue();
                if (k != null && v != null && !v.isEmpty()) m.put(k, v);
            }
        }
        return m;
    }

    static int parseHexColor(String hex) {
        if (hex == null) return 0;
        String s = hex.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() != 6 || "auto".equalsIgnoreCase(s)) return 0;
        try {
            return 0xFF000000 | Integer.parseInt(s, 16);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Resolves an OOXML color element (or parent containing srgbClr/schemeClr/sysClr/prstClr)
     * with full OOXML color transforms (alpha, tint, shade, lumMod, lumOff, satMod, comp, inv).
     * Returns 0 if no color child is found.
     */
    static int parseColorNode(Element parentOrColor, Theme theme, Map<String, String> clrMap, int phClr) {
        if (parentOrColor == null) return 0;
        Element cEl = null;
        String ln = localName(parentOrColor);
        if ("srgbClr".equals(ln) || "schemeClr".equals(ln) || "sysClr".equals(ln) || "prstClr".equals(ln)) {
            cEl = parentOrColor;
        } else {
            for (Element ch : children(parentOrColor, null)) {
                String n = localName(ch);
                if ("srgbClr".equals(n) || "schemeClr".equals(n) || "sysClr".equals(n) || "prstClr".equals(n)) {
                    cEl = ch;
                    break;
                }
            }
        }
        if (cEl == null) return 0;

        int base = 0;
        String tag = localName(cEl);
        if ("srgbClr".equals(tag)) {
            base = parseHexColor(attr(cEl, "val"));
        } else if ("sysClr".equals(tag)) {
            base = parseHexColor(attr(cEl, "lastClr"));
            if (base == 0) {
                String val = attr(cEl, "val");
                if ("window".equalsIgnoreCase(val)) base = Color.WHITE;
                else if ("windowText".equalsIgnoreCase(val)) base = Color.BLACK;
                else base = Color.BLACK;
            }
        } else if ("prstClr".equals(tag)) {
            base = presetColor(attr(cEl, "val"));
        } else if ("schemeClr".equals(tag)) {
            String key = attr(cEl, "val");
            base = resolveSchemeColor(key, theme, clrMap, phClr);
        }
        if (base == 0) return 0;
        return applyColorTransforms(base, cEl);
    }

    static int resolveSchemeColor(String key, Theme theme, Map<String, String> clrMap, int phClr) {
        if (key == null) return 0;
        if ("phClr".equals(key)) return phClr != 0 ? phClr : 0xFF4472C4;
        String mapped = (clrMap != null && clrMap.containsKey(key)) ? clrMap.get(key) : key;
        if (theme != null) {
            Integer v = theme.colors.get(mapped);
            if (v != null) return v;
            v = theme.colors.get(key);
            if (v != null) return v;
        }
        // Fallback mapping if theme wasn't supplied
        switch (mapped) {
            case "lt1": case "bg1": return 0xFFFFFFFF;
            case "dk1": case "tx1": return 0xFF1A1D24;
            case "lt2": case "bg2": return 0xFFE7E6E6;
            case "dk2": case "tx2": return 0xFF44546A;
            case "accent1": return 0xFF4472C4;
            case "accent2": return 0xFFED7D31;
            case "accent3": return 0xFFA5A5A5;
            case "accent4": return 0xFFFFC000;
            case "accent5": return 0xFF5B9BD5;
            case "accent6": return 0xFF70AD47;
            case "hlink": return 0xFF0563C1;
            case "folHlink": return 0xFF954F72;
            default: return 0;
        }
    }

    static int applyColorTransforms(int argb, Element cEl) {
        int a = Color.alpha(argb);
        int r = Color.red(argb);
        int g = Color.green(argb);
        int b = Color.blue(argb);

        for (Element mod : children(cEl, null)) {
            String m = localName(mod);
            float val = attrFloat(mod, "val", 0f) / 100000f;
            if ("alpha".equals(m)) {
                a = clampByte(Math.round(255f * val));
            } else if ("alphaMod".equals(m)) {
                a = clampByte(Math.round(a * val));
            } else if ("alphaOff".equals(m)) {
                a = clampByte(Math.round(a + 255f * val));
            } else if ("tint".equals(m)) {
                r = clampByte(Math.round(r * val + 255f * (1f - val)));
                g = clampByte(Math.round(g * val + 255f * (1f - val)));
                b = clampByte(Math.round(b * val + 255f * (1f - val)));
            } else if ("shade".equals(m)) {
                r = clampByte(Math.round(r * val));
                g = clampByte(Math.round(g * val));
                b = clampByte(Math.round(b * val));
            } else if ("lumMod".equals(m) || "lumOff".equals(m) || "satMod".equals(m) || "satOff".equals(m)) {
                float[] hsl = rgbToHsl(r, g, b);
                if ("lumMod".equals(m)) hsl[2] = clamp01(hsl[2] * val);
                else if ("lumOff".equals(m)) hsl[2] = clamp01(hsl[2] + val);
                else if ("satMod".equals(m)) hsl[1] = clamp01(hsl[1] * val);
                else if ("satOff".equals(m)) hsl[1] = clamp01(hsl[1] + val);
                int rgb = hslToRgb(hsl[0], hsl[1], hsl[2]);
                r = Color.red(rgb);
                g = Color.green(rgb);
                b = Color.blue(rgb);
            } else if ("inv".equals(m)) {
                r = 255 - r;
                g = 255 - g;
                b = 255 - b;
            }
        }
        return Color.argb(a, r, g, b);
    }

    static int applyWordThemeTintShade(int argb, String tintHex, String shadeHex) {
        int r = Color.red(argb), g = Color.green(argb), b = Color.blue(argb);
        if (tintHex != null && !tintHex.isEmpty()) {
            try {
                float t = Integer.parseInt(tintHex, 16) / 255f;
                r = clampByte(Math.round(r * t + 255f * (1f - t)));
                g = clampByte(Math.round(g * t + 255f * (1f - t)));
                b = clampByte(Math.round(b * t + 255f * (1f - t)));
            } catch (NumberFormatException ignored) {}
        }
        if (shadeHex != null && !shadeHex.isEmpty()) {
            try {
                float s = Integer.parseInt(shadeHex, 16) / 255f;
                r = clampByte(Math.round(r * s));
                g = clampByte(Math.round(g * s));
                b = clampByte(Math.round(b * s));
            } catch (NumberFormatException ignored) {}
        }
        return Color.argb(Color.alpha(argb), r, g, b);
    }

    static float luminance(int color) {
        return (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f;
    }

    private static int clampByte(int v) { return Math.max(0, Math.min(255, v)); }
    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }

    private static float[] rgbToHsl(int r, int g, int b) {
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float h = 0f, s, l = (max + min) * 0.5f;
        if (max == min) {
            s = 0f;
        } else {
            float d = max - min;
            s = l > 0.5f ? d / (2f - max - min) : d / (max + min);
            if (max == rf) h = (gf - bf) / d + (gf < bf ? 6f : 0f);
            else if (max == gf) h = (bf - rf) / d + 2f;
            else h = (rf - gf) / d + 4f;
            h /= 6f;
        }
        return new float[]{h, s, l};
    }

    private static int hslToRgb(float h, float s, float l) {
        float r, g, b;
        if (s == 0f) {
            r = g = b = l;
        } else {
            float q = l < 0.5f ? l * (1f + s) : l + s - l * s;
            float p = 2f * l - q;
            r = hue2rgb(p, q, h + 1f / 3f);
            g = hue2rgb(p, q, h);
            b = hue2rgb(p, q, h - 1f / 3f);
        }
        return Color.rgb(clampByte(Math.round(r * 255f)), clampByte(Math.round(g * 255f)), clampByte(Math.round(b * 255f)));
    }

    private static float hue2rgb(float p, float q, float t) {
        if (t < 0f) t += 1f;
        if (t > 1f) t -= 1f;
        if (t < 1f / 6f) return p + (q - p) * 6f * t;
        if (t < 0.5f) return q;
        if (t < 2f / 3f) return p + (q - p) * (2f / 3f - t) * 6f;
        return p;
    }

    private static int presetColor(String name) {
        if (name == null) return 0;
        switch (name.toLowerCase(Locale.US)) {
            case "black": return 0xFF000000;
            case "white": return 0xFFFFFFFF;
            case "red": return 0xFFFF0000;
            case "green": return 0xFF008000;
            case "lime": return 0xFF00FF00;
            case "blue": return 0xFF0000FF;
            case "yellow": return 0xFFFFFF00;
            case "cyan": case "aqua": return 0xFF00FFFF;
            case "magenta": case "fuchsia": return 0xFFFF00FF;
            case "silver": return 0xFFC0C0C0;
            case "gray": case "grey": return 0xFF808080;
            case "dkgray": case "darkgray": case "darkgrey": return 0xFFA9A9A9;
            case "ltgray": case "lightgray": case "lightgrey": return 0xFFD3D3D3;
            case "maroon": return 0xFF800000;
            case "navy": return 0xFF000080;
            case "olive": return 0xFF808000;
            case "purple": return 0xFF800080;
            case "teal": return 0xFF008080;
            case "orange": return 0xFFFFA500;
            case "gold": return 0xFFFFD700;
            case "pink": return 0xFFFFC0CB;
            case "brown": return 0xFFA52A2A;
            default:
                try { return Color.parseColor(name); } catch (Exception e) { return 0; }
        }
    }

    // ---- Fill Parsing & Drawing (Solid, Linear/Radial Gradient, Blip Image) ------------------

    static final class GradStop implements Comparable<GradStop> {
        final float pos;
        final int color;
        GradStop(float pos, int color) { this.pos = pos; this.color = color; }
        @Override public int compareTo(GradStop o) { return Float.compare(this.pos, o.pos); }
    }

    static final class FillSpec {
        static final int NONE = 0, SOLID = 1, LINEAR_GRAD = 2, RADIAL_GRAD = 3, IMAGE = 4;
        int kind = NONE;
        int color;
        float angleDeg;
        float cx = 0.5f, cy = 0.5f;
        List<GradStop> stops;
        byte[] imageBytes;
        float srcL, srcT, srcR, srcB; // 0..1 crop fractions
        boolean tile;
        int alpha = 255;

        int representativeColor() {
            if (kind == SOLID) return color;
            if ((kind == LINEAR_GRAD || kind == RADIAL_GRAD) && stops != null && !stops.isEmpty()) {
                return stops.get(stops.size() / 2).color;
            }
            return 0;
        }
    }

    static FillSpec parseFillFromContainer(Element container, Theme theme, Map<String, String> clrMap,
                                           int phClr, Map<String, Rel> rels, ZipEntries entries) {
        if (container == null) return null;
        if (child(container, "noFill") != null) {
            FillSpec f = new FillSpec();
            f.kind = FillSpec.NONE;
            return f;
        }
        Element solid = child(container, "solidFill");
        if (solid != null) {
            int col = parseColorNode(solid, theme, clrMap, phClr);
            if (col != 0) {
                FillSpec f = new FillSpec();
                f.kind = FillSpec.SOLID;
                f.color = col;
                return f;
            }
        }
        Element grad = child(container, "gradFill");
        if (grad != null) {
            return parseGradFill(grad, theme, clrMap, phClr);
        }
        Element blipFill = child(container, "blipFill");
        if (blipFill != null) {
            return parseBlipFill(blipFill, rels, entries);
        }
        Element patt = child(container, "pattFill");
        if (patt != null) {
            int bg = parseColorNode(child(patt, "bgClr"), theme, clrMap, phClr);
            int fg = parseColorNode(child(patt, "fgClr"), theme, clrMap, phClr);
            int c = bg != 0 ? bg : fg;
            if (c != 0) {
                FillSpec f = new FillSpec();
                f.kind = FillSpec.SOLID;
                f.color = c;
                return f;
            }
        }
        return null;
    }

    static FillSpec parseFillElement(Element fillEl, Theme theme, Map<String, String> clrMap,
                                     int phClr, Map<String, Rel> rels, ZipEntries entries) {
        if (fillEl == null) return null;
        String ln = localName(fillEl);
        if ("noFill".equals(ln)) {
            FillSpec f = new FillSpec();
            f.kind = FillSpec.NONE;
            return f;
        }
        if ("solidFill".equals(ln)) {
            int col = parseColorNode(fillEl, theme, clrMap, phClr);
            if (col == 0) return null;
            FillSpec f = new FillSpec();
            f.kind = FillSpec.SOLID;
            f.color = col;
            return f;
        }
        if ("gradFill".equals(ln)) return parseGradFill(fillEl, theme, clrMap, phClr);
        if ("blipFill".equals(ln)) return parseBlipFill(fillEl, rels, entries);
        return null;
    }

    private static FillSpec parseGradFill(Element grad, Theme theme, Map<String, String> clrMap, int phClr) {
        Element gsLst = child(grad, "gsLst");
        if (gsLst == null) return null;
        ArrayList<GradStop> stops = new ArrayList<>();
        for (Element gs : children(gsLst, "gs")) {
            float pos = clamp01(attrFloat(gs, "pos", 0f) / 100000f);
            int col = parseColorNode(gs, theme, clrMap, phClr);
            if (col != 0) stops.add(new GradStop(pos, col));
        }
        if (stops.isEmpty()) return null;
        Collections.sort(stops);
        FillSpec f = new FillSpec();
        if (stops.size() == 1) {
            f.kind = FillSpec.SOLID;
            f.color = stops.get(0).color;
            return f;
        }
        f.stops = stops;
        Element path = child(grad, "path");
        if (path != null) {
            f.kind = FillSpec.RADIAL_GRAD;
            Element ftr = child(path, "fillToRect");
            if (ftr != null) {
                float l = attrFloat(ftr, "l", 50000f) / 100000f;
                float t = attrFloat(ftr, "t", 50000f) / 100000f;
                float r = attrFloat(ftr, "r", 50000f) / 100000f;
                float b = attrFloat(ftr, "b", 50000f) / 100000f;
                f.cx = clamp01((l + (1f - r)) * 0.5f);
                f.cy = clamp01((t + (1f - b)) * 0.5f);
            }
        } else {
            f.kind = FillSpec.LINEAR_GRAD;
            Element lin = child(grad, "lin");
            f.angleDeg = lin != null ? attrFloat(lin, "ang", 0f) / 60000f : 90f;
        }
        return f;
    }

    static FillSpec parseBlipFill(Element blipFill, Map<String, Rel> rels, ZipEntries entries) {
        if (blipFill == null || rels == null || entries == null) return null;
        Element blip = child(blipFill, "blip");
        if (blip == null) return null;
        String rId = attr(blip, "embed");
        if (rId == null) rId = attr(blip, "link");
        if (rId == null) return null;
        Rel rel = rels.get(rId);
        if (rel == null || rel.external) return null;
        byte[] bytes = entries.get(rel.target);
        if (bytes == null || bytes.length == 0) return null;

        FillSpec f = new FillSpec();
        f.kind = FillSpec.IMAGE;
        f.imageBytes = bytes;
        Element alphaMod = child(blip, "alphaModFix");
        if (alphaMod != null) {
            f.alpha = clampByte(Math.round(255f * attrFloat(alphaMod, "amt", 100000f) / 100000f));
        }
        Element sr = child(blipFill, "srcRect");
        if (sr != null) {
            f.srcL = attrFloat(sr, "l", 0f) / 100000f;
            f.srcT = attrFloat(sr, "t", 0f) / 100000f;
            f.srcR = attrFloat(sr, "r", 0f) / 100000f;
            f.srcB = attrFloat(sr, "b", 0f) / 100000f;
        }
        f.tile = child(blipFill, "tile") != null;
        return f;
    }

    static void drawFill(Canvas cv, RectF bounds, Path clipPath, FillSpec fill, int maxImgDim) {
        if (fill == null || fill.kind == FillSpec.NONE || bounds.width() <= 0f || bounds.height() <= 0f) return;
        cv.save();
        if (clipPath != null) cv.clipPath(clipPath);

        if (fill.kind == FillSpec.SOLID) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.FILL);
            p.setColor(fill.color);
            if (clipPath != null) cv.drawPath(clipPath, p);
            else cv.drawRect(bounds, p);
        } else if (fill.kind == FillSpec.LINEAR_GRAD && fill.stops != null && fill.stops.size() >= 2) {
            int n = fill.stops.size();
            int[] cols = new int[n];
            float[] pos = new float[n];
            for (int i = 0; i < n; i++) {
                cols[i] = fill.stops.get(i).color;
                pos[i] = fill.stops.get(i).pos;
            }
            double rad = Math.toRadians(fill.angleDeg);
            float dx = (float) Math.cos(rad);
            float dy = (float) Math.sin(rad);
            float cx = bounds.centerX(), cy = bounds.centerY();
            float half = (Math.abs(dx) * bounds.width() + Math.abs(dy) * bounds.height()) * 0.5f;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new LinearGradient(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half,
                    cols, pos, Shader.TileMode.CLAMP));
            if (clipPath != null) cv.drawPath(clipPath, p);
            else cv.drawRect(bounds, p);
        } else if (fill.kind == FillSpec.RADIAL_GRAD && fill.stops != null && fill.stops.size() >= 2) {
            int n = fill.stops.size();
            int[] cols = new int[n];
            float[] pos = new float[n];
            for (int i = 0; i < n; i++) {
                cols[i] = fill.stops.get(i).color;
                pos[i] = fill.stops.get(i).pos;
            }
            float gx = bounds.left + bounds.width() * fill.cx;
            float gy = bounds.top + bounds.height() * fill.cy;
            float radius = Math.max(1f, (float) Math.hypot(
                    Math.max(gx - bounds.left, bounds.right - gx),
                    Math.max(gy - bounds.top, bounds.bottom - gy)));
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new RadialGradient(gx, gy, radius, cols, pos, Shader.TileMode.CLAMP));
            if (clipPath != null) cv.drawPath(clipPath, p);
            else cv.drawRect(bounds, p);
        } else if (fill.kind == FillSpec.IMAGE && fill.imageBytes != null) {
            Bitmap bm = decodeScaled(fill.imageBytes, maxImgDim);
            if (bm != null) {
                try {
                    Paint ip = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                    ip.setAlpha(fill.alpha);
                    if (fill.tile) {
                        ip.setShader(new BitmapShader(bm, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
                        cv.drawRect(bounds, ip);
                    } else {
                        Rect src = computeCropRect(bm.getWidth(), bm.getHeight(), fill.srcL, fill.srcT, fill.srcR, fill.srcB);
                        cv.drawBitmap(bm, src, bounds, ip);
                    }
                } finally {
                    bm.recycle();
                }
            }
        }
        cv.restore();
    }

    static Rect computeCropRect(int bw, int bh, float l, float t, float r, float b) {
        int x0 = Math.max(0, Math.min(bw - 1, Math.round(bw * Math.max(0f, l))));
        int y0 = Math.max(0, Math.min(bh - 1, Math.round(bh * Math.max(0f, t))));
        int x1 = Math.max(x0 + 1, Math.min(bw, Math.round(bw * (1f - Math.max(0f, r)))));
        int y1 = Math.max(y0 + 1, Math.min(bh, Math.round(bh * (1f - Math.max(0f, b)))));
        return new Rect(x0, y0, x1, y1);
    }

    // ---- Bitmap decoding ---------------------------------------------------------------------

    static Bitmap decodeScaled(byte[] bytes, int maxDim) {
        if (bytes == null || bytes.length == 0) return null;
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

    // ---- Typography helpers ------------------------------------------------------------------

    static Typeface mapFontFamily(String fontName, boolean bold, boolean italic) {
        Typeface base = Typeface.SANS_SERIF;
        if (fontName != null && !fontName.isEmpty()) {
            String f = fontName.toLowerCase(Locale.US);
            if (f.contains("mono") || f.contains("courier") || f.contains("consola")
                    || f.contains("menlo") || f.contains("fira code") || f.contains("jetbrains")
                    || f.contains("cascadia") || f.contains("lucida console")) {
                base = Typeface.MONOSPACE;
            } else if (f.contains("serif") && !f.contains("sans")) {
                base = Typeface.SERIF;
            } else if (f.contains("times") || f.contains("georgia") || f.contains("cambria")
                    || f.contains("garamond") || f.contains("palatino") || f.contains("book")
                    || f.contains("baskerville") || f.contains("merriweather") || f.contains("playfair")
                    || f.contains("constantia") || f.contains("minion")) {
                base = Typeface.SERIF;
            }
        }
        int style = (bold ? Typeface.BOLD : 0) | (italic ? Typeface.ITALIC : 0);
        return Typeface.create(base, style);
    }

    /** Applies an exact float point size in PDF canvas units (1px = 1pt) without rounding or dip scaling. */
    static final class ExactSizeSpan extends MetricAffectingSpan {
        private final float sizePt;
        ExactSizeSpan(float sizePt) { this.sizePt = sizePt; }
        @Override public void updateDrawState(TextPaint tp) { tp.setTextSize(sizePt); }
        @Override public void updateMeasureState(TextPaint tp) { tp.setTextSize(sizePt); }
    }

    /** Applies a specific resolved Typeface (family + bold/italic) cleanly without conflicting with base Paint. */
    static final class FontSpan extends MetricAffectingSpan {
        private final Typeface tf;
        FontSpan(Typeface tf) { this.tf = tf; }
        @Override public void updateDrawState(TextPaint tp) { tp.setTypeface(tf); }
        @Override public void updateMeasureState(TextPaint tp) { tp.setTypeface(tf); }
    }

    /** Shifts baseline up (superscript) or down (subscript) with proportional scaling. */
    static final class ScriptSpan extends MetricAffectingSpan {
        private final boolean superScript;
        ScriptSpan(boolean superScript) { this.superScript = superScript; }
        private void apply(TextPaint tp) {
            float sz = tp.getTextSize();
            tp.setTextSize(sz * 0.65f);
            tp.baselineShift += Math.round(superScript ? -(sz * 0.35f) : (sz * 0.16f));
        }
        @Override public void updateDrawState(TextPaint tp) { apply(tp); }
        @Override public void updateMeasureState(TextPaint tp) { apply(tp); }
    }
}

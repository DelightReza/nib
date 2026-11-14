package app.nib.notes;

import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Desktop check for Beautify: synthetic messy lines in three scripts → before/after SVG. */
public class BeautifyTest {
    static final Random R = new Random(7);
    static final float PITCH = 40, FIRST = 120;

    static Stroke stroke(List<float[]> pts) {
        Stroke s = new Stroke(Stroke.BALL, 0xFF222222, 3.2f);
        for (float[] p : pts) s.add(p[0], p[1], 0.5f);
        return s;
    }

    /** A messy line: tilt, wave, uneven word size and spacing. Returns strokes in writing order. */
    static List<Stroke> latin(float y0, int words) {
        List<Stroke> out = new ArrayList<>();
        float x = 60, tilt = -0.07f;
        for (int w = 0; w < words; w++) {
            float sc = 0.78f + R.nextFloat() * 0.5f, xh = 13 * sc;
            int letters = 3 + R.nextInt(4);
            List<float[]> p = new ArrayList<>();
            float base = y0 + tilt * (x - 60) + 5 * (float) Math.sin(x / 90.0);
            for (int l = 0; l < letters; l++) {
                float hgt = R.nextFloat() < 0.25f ? 2.2f * xh : xh, wl = 10 * sc;
                for (int k = 0; k <= 12; k++) {
                    float u = k / 12f;
                    float jitter = (R.nextFloat() - 0.5f) * 1.6f;
                    p.add(new float[]{x + u * wl, base - hgt * (float) Math.sin(Math.PI * u) + jitter});
                }
                x += wl;
            }
            out.add(stroke(p));
            if (R.nextBoolean()) { // an i-dot
                List<float[]> d = new ArrayList<>();
                float dx = x - 18 * sc, dy = base - 1.9f * xh;
                d.add(new float[]{dx, dy}); d.add(new float[]{dx + 1.5f, dy + 1});
                out.add(stroke(d));
            }
            x += 14 + R.nextFloat() * 34;
        }
        return out;
    }

    /** Devanagari/Gurmukhi-like: per word a top bar (often in two pieces) and letters hanging from it. */
    static List<Stroke> headline(float y0, int words) {
        List<Stroke> out = new ArrayList<>();
        float x = 60, tilt = 0.06f;
        for (int w = 0; w < words; w++) {
            float sc = 0.8f + R.nextFloat() * 0.45f, xh = 16 * sc;
            int letters = 2 + R.nextInt(3);
            float ww = letters * 15 * sc;
            float top = y0 - xh + tilt * (x - 60) + 4 * (float) Math.sin(x / 70.0);
            for (int l = 0; l < letters; l++) { // letters first, like most writers
                List<float[]> p = new ArrayList<>();
                float lx = x + l * 15 * sc + 3;
                for (int k = 0; k <= 16; k++) {
                    double a = k / 16.0 * 2 * Math.PI;
                    p.add(new float[]{lx + 5 * sc + 5 * sc * (float) Math.sin(a), top + xh * 0.55f + xh * 0.42f * (float) -Math.cos(a) + (R.nextFloat() - .5f)});
                }
                p.add(new float[]{lx + 11 * sc, top + 1}); p.add(new float[]{lx + 11 * sc, top + xh}); // the vertical stem
                out.add(stroke(p));
            }
            // the top bar, maybe split in two with a gap, each a bit wavy
            boolean split = R.nextBoolean();
            float[][] spans = split ? new float[][]{{x, x + ww * 0.5f - 4}, {x + ww * 0.5f + 3, x + ww}} : new float[][]{{x, x + ww}};
            for (float[] sp : spans) {
                List<float[]> b = new ArrayList<>();
                float off = (R.nextFloat() - 0.5f) * 5;
                for (float bx = sp[0]; bx <= sp[1]; bx += 2) b.add(new float[]{bx, top + off + 1.5f * (float) Math.sin(bx / 6) + tilt * (bx - x)});
                out.add(stroke(b));
            }
            x += ww + 16 + R.nextFloat() * 30;
        }
        return out;
    }

    /** Arabic-like: words written right to left on a tilted baseline, teeth going up, dots above/below. */
    static List<Stroke> arabic(float y0, int words) {
        List<Stroke> out = new ArrayList<>();
        float x = 940, tilt = 0.05f;
        for (int w = 0; w < words; w++) {
            float sc = 0.8f + R.nextFloat() * 0.45f, ww = (40 + R.nextInt(50)) * sc;
            float base = y0 + tilt * (940 - x) + 4 * (float) Math.sin(x / 80.0);
            List<float[]> p = new ArrayList<>();
            for (float bx = x; bx >= x - ww; bx -= 2) { // right to left
                float u = (x - bx) / ww;
                float tooth = (float) Math.max(0, Math.sin(u * Math.PI * 5)) * 12 * sc;
                p.add(new float[]{bx, base - tooth + (R.nextFloat() - .5f)});
            }
            out.add(stroke(p));
            for (int d = 0; d < 1 + R.nextInt(2); d++) {
                float dx = x - ww * R.nextFloat(), dy = base + (R.nextBoolean() ? -22 : 9) * sc;
                List<float[]> dot = new ArrayList<>();
                dot.add(new float[]{dx, dy}); dot.add(new float[]{dx + 2, dy + 1});
                out.add(stroke(dot));
            }
            x -= ww + 14 + R.nextFloat() * 30;
        }
        return out;
    }

    static void svg(StringBuilder sb, List<Stroke> ss, float ox, float oy, String color) {
        for (Stroke s : ss) {
            sb.append("<polyline fill='none' stroke='").append(color).append("' stroke-width='2.2' stroke-linecap='round' stroke-linejoin='round' points='");
            for (int i = 0; i < s.n; i++) sb.append(s.x(i) + ox).append(',').append(s.y(i) + oy).append(' ');
            sb.append("'/>\n");
        }
    }

    public static void main(String[] a) throws Exception {
        String[] names = {"English", "Hindi / Punjabi (top line)", "Arabic (right to left)"};
        StringBuilder sb = new StringBuilder("<svg xmlns='http://www.w3.org/2000/svg' width='2100' height='1500' style='background:#fff;font-family:sans-serif'>\n");
        float y = 30;
        for (int sc = 0; sc < 3; sc++) {
            List<Stroke> in = new ArrayList<>();
            float base = FIRST + PITCH * 2;
            for (int line = 0; line < 3; line++, base += PITCH * 2) {
                in.addAll(sc == 0 ? latin(base, 6) : sc == 1 ? headline(base + 8, 6) : arabic(base, 6));
            }
            Beautify.Report rep = new Beautify.Report();
            List<Stroke> outS = Beautify.run(in, 4, new Beautify.Paper(FIRST, PITCH), null, rep);
            System.out.println(names[sc] + ": " + rep);
            sb.append("<text x='20' y='").append(y + 20).append("' font-size='22' font-weight='600'>").append(names[sc]).append("</text>");
            sb.append("<text x='1070' y='").append(y + 20).append("' font-size='18' fill='#888'>after (strength 4)</text>");
            for (int k = 0; k < 2; k++) { // ruled lines
                float ox = 20 + k * 1050;
                for (float ly = FIRST; ly < FIRST + PITCH * 8; ly += PITCH)
                    sb.append("<line x1='").append(ox).append("' x2='").append(ox + 1000).append("' y1='").append(y + ly - 80).append("' y2='").append(y + ly - 80).append("' stroke='#cfe0f5'/>");
            }
            svg(sb, in, 20, y - 80, "#333");
            svg(sb, outS, 1070, y - 80, "#1f4fbf");
            y += 470;
        }
        sb.append("</svg>");
        try (FileWriter f = new FileWriter(a[0])) { f.write(sb.toString()); }
    }
}

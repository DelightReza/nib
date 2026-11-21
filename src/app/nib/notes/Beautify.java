package app.nib.notes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * "Beautify handwriting": makes your own writing neater without changing what it looks like.
 * Geometry only (level 1), helped by recognition when a model is there (level 2):
 * <ul>
 *   <li>strokes are grouped into lines and words, drawings and shapes are left alone;</li>
 *   <li>each line is straightened and, on ruled paper, laid on its line;</li>
 *   <li>word sizes and the gaps between words are evened out;</li>
 *   <li>wobble is smoothed, corners kept;</li>
 *   <li>script rules: Latin sits on a baseline; Devanagari / Gurmukhi hang from a straight, joined top line
 *       (shirorekha); Arabic sits on its baseline (the densest band of ink), right to left, dots move with their letters.</li>
 * </ul>
 * Plain Java (no Android), so it can be tested on a desktop JVM. Same input, same output.
 */
final class Beautify {
    private Beautify() {}

    static final int LATIN = 0, HEADLINE = 1, ARABIC = 2;
    static final String[] SCRIPT_NAMES = {"Latin", "Devanagari/Gurmukhi", "Arabic"};

    /** Ruled lines on the page: y of the first line and the distance between lines (pitch 0 = no lines). */
    static final class Paper {
        final float first, pitch;
        Paper(float first, float pitch) { this.first = first; this.pitch = pitch; }
        static final Paper NONE = new Paper(0, 0);
    }

    /** Level 2: reads a line of ink in a script; null when there's no model for it (then geometry decides alone). */
    interface Reader { String read(List<Stroke> line, int script); }

    /** What happened, for the log and tests. */
    static final class Report {
        int lines, words, skipped;
        final int[] scripts = new int[3];
        @Override public String toString() {
            return lines + " lines, " + words + " words, " + skipped + " left alone · latin " + scripts[0] + " headline " + scripts[1] + " arabic " + scripts[2];
        }
    }

    /** One stroke being worked on: its points as editable arrays. */
    private static final class S {
        final Stroke src;
        final int index; // order written (selection order)
        float[] x, y, pr;
        int n;
        boolean mark;     // small mark: dot, accent, crossbar
        boolean bar;      // headline bar stroke (Devanagari / Gurmukhi)
        S(Stroke s, int index) {
            src = s; this.index = index; n = s.n;
            x = new float[n]; y = new float[n]; pr = new float[n];
            for (int i = 0; i < n; i++) { x[i] = s.x(i); y[i] = s.y(i); pr[i] = s.p[4 * i + 2]; }
        }
        float l, t, r, b;
        void bounds() {
            l = t = Float.MAX_VALUE; r = b = -Float.MAX_VALUE;
            for (int i = 0; i < n; i++) { l = Math.min(l, x[i]); r = Math.max(r, x[i]); t = Math.min(t, y[i]); b = Math.max(b, y[i]); }
        }
        float w() { return r - l; }
        float h() { return b - t; }
        float cx() { return (l + r) / 2; }
        float cy() { return (t + b) / 2; }
    }

    private static final class Word {
        final List<S> s = new ArrayList<>();
        float l, t, r, b, ref, body; // ref = baseline (or headline) y; body = letter height
        void bounds() {
            l = t = Float.MAX_VALUE; r = b = -Float.MAX_VALUE;
            for (S k : s) { k.bounds(); l = Math.min(l, k.l); r = Math.max(r, k.r); t = Math.min(t, k.t); b = Math.max(b, k.b); }
        }
        float cx() { return (l + r) / 2; }
    }

    /**
     * Returns the beautified strokes in the same order as {@code in} (one for one); strokes it leaves alone are returned
     * as the same object. {@code strength} 1..5.
     */
    static List<Stroke> run(List<Stroke> in, int strength, Paper paper, Reader reader, Report rep) {
        float t = Math.max(1, Math.min(5, strength)) / 5f;
        if (rep == null) rep = new Report();
        Stroke[] out = in.toArray(new Stroke[0]);
        List<S> cand = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            Stroke s = in.get(i);
            if (s.hl() || s.shape != 0 || s.n < 2) continue;
            S k = new S(s, i);
            k.bounds();
            cand.add(k);
        }
        if (cand.isEmpty()) return Arrays.asList(out);
        // typical letter height: dots and accents are often half the strokes, so look above the middle
        float[] hs = new float[cand.size()];
        for (int i = 0; i < hs.length; i++) hs[i] = Math.max(2, cand.get(i).h());
        Arrays.sort(hs);
        float hMed = pct(hs, 0.65f);
        // drawings and rules are left alone: very tall strokes, long thin lines
        List<S> text = new ArrayList<>();
        for (S k : cand) {
            boolean big = k.h() > 3.5f * hMed && k.w() > 1.5f * hMed;
            boolean rule = k.w() > 9 * hMed && k.h() < 0.5f * hMed;
            if (big || rule) rep.skipped++; else text.add(k);
        }
        for (List<S> line : lines(text, hMed)) {
            boolean done = line(line, t, paper, reader, rep);
            if (!done) rep.skipped += line.size();
        }
        for (S k : text) {
            if (k.x == null) continue;
            out[k.index] = rebuild(k);
        }
        return Arrays.asList(out);
    }

    // ---- one line ----------------------------------------------------------------------------

    private static boolean line(List<S> line, float t, Paper paper, Reader reader, Report rep) {
        for (S k : line) k.bounds();
        float[] lh = new float[line.size()];
        for (int i = 0; i < lh.length; i++) lh[i] = Math.max(2, line.get(i).h());
        Arrays.sort(lh);
        float hl = pct(lh, 0.65f);
        boolean anyMain = false;
        for (S k : line) { k.mark = k.h() < 0.38f * hl && k.w() < 1.2f * hl; anyMain |= !k.mark; }
        if (!anyMain) return false; // stray dots on their own: leave them
        int script = script(line, hl);
        String text = null;
        if (reader != null) {
            List<Stroke> ink = new ArrayList<>();
            List<S> chrono = new ArrayList<>(line);
            chrono.sort(Comparator.comparingInt(k -> k.index));
            for (S k : chrono) ink.add(k.src);
            text = reader.read(ink, script);
            if (text != null && text.trim().isEmpty()) return false; // a model is there and sees no writing: a drawing
        }
        int wanted = text == null ? -1 : text.trim().split("\\s+").length;
        List<Word> words = words(line, hl, wanted);
        // headline bars (shirorekha): flat strokes along the top of a word
        if (script == HEADLINE) for (Word w : words) {
            w.bounds();
            float band = w.t + 0.32f * (w.b - w.t);
            for (S k : w.s) k.bar = !k.mark && k.w() >= 1.6f * k.h() && k.h() < 0.4f * (w.b - w.t) && k.cy() <= band;
        }
        for (Word w : words) refs(w, script, hl);
        rep.lines++;
        rep.words += words.size();
        rep.scripts[script]++;

        // 1. straighten: fit the reference line through the words (or the strokes, for a short line) and rotate
        float[] fit = fit(words, line, script, hl);
        float slope = fit[0], icept = fit[1];
        float lx0 = Float.MAX_VALUE, lx1 = -Float.MAX_VALUE;
        for (Word w : words) { lx0 = Math.min(lx0, w.l); lx1 = Math.max(lx1, w.r); }
        float px = (lx0 + lx1) / 2, py = icept + slope * px;
        double ang = -Math.atan(slope) * t;
        float cs = (float) Math.cos(ang), sn = (float) Math.sin(ang);
        for (S k : line) for (int i = 0; i < k.n; i++) {
            float dx = k.x[i] - px, dy = k.y[i] - py;
            k.x[i] = px + dx * cs - dy * sn;
            k.y[i] = py + dx * sn + dy * cs;
        }
        // straighten what's left of the wave: each word moves most of the way onto the (now flatter) fitted line
        float slope2 = (float) Math.tan(Math.atan(slope) + ang); // tilt still left after rotating by t of it
        for (Word w : words) {
            refs(w, script, hl);
            float ideal = py + slope2 * (w.cx() - px);
            float dy = (ideal - w.ref) * 0.9f * t;
            if (Math.abs(dy) < 0.6f * hl) move(w, 0, dy);
        }

        // 2. lay it on the ruled line (Latin & Arabic: the baseline; Devanagari/Gurmukhi: the bottom of the letters)
        if (paper.pitch > 0) {
            float ref = 0, bodyBottom = 0;
            for (Word w : words) { refs(w, script, hl); ref += w.ref; bodyBottom += script == HEADLINE ? w.ref + w.body : w.ref; }
            ref /= words.size();
            bodyBottom /= words.size();
            float on = script == HEADLINE ? bodyBottom : ref;
            float target = paper.first + Math.round((on - paper.first) / paper.pitch) * paper.pitch;
            float dy = target - on;
            if (Math.abs(dy) < 0.5f * paper.pitch) for (S k : line) for (int i = 0; i < k.n; i++) k.y[i] += dy * t;
        }

        // 3. even word sizes (only with a few words to compare)
        if (words.size() >= 3) {
            for (Word w : words) refs(w, script, hl);
            float[] bodies = new float[words.size()];
            for (int i = 0; i < bodies.length; i++) bodies[i] = words.get(i).body;
            float hm = medianOf(bodies);
            for (Word w : words) {
                if (w.body <= 0) continue;
                float sc = (float) Math.pow(hm / w.body, 0.7 * t);
                sc = Math.max(0.82f, Math.min(1.22f, sc));
                if (Math.abs(sc - 1) < 0.01f) continue;
                float ax = w.cx(), ay = w.ref;
                for (S k : w.s) for (int i = 0; i < k.n; i++) { k.x[i] = ax + (k.x[i] - ax) * sc; k.y[i] = ay + (k.y[i] - ay) * sc; }
            }
        }

        // 4. even the gaps between words
        if (words.size() >= 3) {
            for (Word w : words) w.bounds();
            words.sort(Comparator.comparingDouble(w -> w.l));
            float[] gaps = new float[words.size() - 1];
            for (int i = 0; i < gaps.length; i++) gaps[i] = words.get(i + 1).l - words.get(i).r;
            float g = medianOf(gaps);
            for (int i = 0; i < gaps.length; i++) {
                float ng = Math.max(0.25f * hl, gaps[i] + (g - gaps[i]) * 0.6f * t);
                float d = ng - gaps[i];
                if (Math.abs(d) > 0.01f) for (int j = i + 1; j < words.size(); j++) move(words.get(j), d, 0); // later gaps keep their size
            }
        }

        // 5. Devanagari / Gurmukhi: straight top line, joined across each word
        if (script == HEADLINE) for (Word w : words) headline(w, t, hl);

        // 6. smooth the wobble, keep the corners
        for (S k : line) smooth(k, t);
        return true;
    }

    /** Lines of writing in a set of strokes (same grouping Beautify uses); for tests and tools. */
    static List<List<Stroke>> lineGroups(List<Stroke> strokes) {
        List<S> cand = new ArrayList<>();
        for (int i = 0; i < strokes.size(); i++) { Stroke s = strokes.get(i); if (s.hl() || s.n < 2) continue; S k = new S(s, i); k.bounds(); cand.add(k); }
        List<List<Stroke>> out = new ArrayList<>();
        if (cand.isEmpty()) return out;
        float[] hs = new float[cand.size()];
        for (int i = 0; i < hs.length; i++) hs[i] = Math.max(2, cand.get(i).h());
        Arrays.sort(hs);
        for (List<S> line : lines(cand, pct(hs, 0.65f))) {
            line.sort(Comparator.comparingInt(k -> k.index));
            List<Stroke> l = new ArrayList<>();
            for (S k : line) l.add(k.src);
            out.add(l);
        }
        return out;
    }

    /** Script of one line of strokes (for language auto-detection): LATIN, HEADLINE or ARABIC. */
    static int scriptOf(List<Stroke> strokes) {
        List<S> line = new ArrayList<>();
        for (int i = 0; i < strokes.size(); i++) {
            Stroke s = strokes.get(i);
            if (s.hl() || s.n < 2) continue;
            S k = new S(s, i);
            k.bounds();
            line.add(k);
        }
        if (line.isEmpty()) return LATIN;
        float[] lh = new float[line.size()];
        for (int i = 0; i < lh.length; i++) lh[i] = Math.max(2, line.get(i).h());
        Arrays.sort(lh);
        float hl = pct(lh, 0.65f);
        for (S k : line) k.mark = k.h() < 0.38f * hl && k.w() < 1.2f * hl;
        return script(line, hl);
    }

    /**
     * Which script a line looks like. Devanagari / Gurmukhi: most words carry a flat top line (shirorekha) across most
     * of their width, whether it was drawn as its own stroke or as part of the letters, so we measure how much of each
     * word's width has flat ink along one height near its top. Latin letter tops (arches of n, m, o) cover far less.
     * Arabic: the big strokes were written right to left. Otherwise Latin.
     */
    private static int script(List<S> line, float hl) {
        List<Word> words = words(line, hl, -1);
        int withBar = 0, counted = 0;
        for (Word w : words) {
            float cov = headlineCoverage(w);
            if (cov < 0) continue; // too small to judge (a dot, a comma)
            counted++;
            if (cov >= 0.6f) withBar++;
        }
        if (counted > 0 && withBar * 2 >= counted) return HEADLINE;
        // direction: in the order written, do the big strokes walk left or right?
        List<S> big = new ArrayList<>();
        for (S k : line) if (!k.mark && k.h() > 0.5f * hl) big.add(k);
        big.sort(Comparator.comparingInt(k -> k.index));
        float back = 0, fwd = 0;
        for (int i = 1; i < big.size(); i++) {
            float d = big.get(i).cx() - big.get(i - 1).cx();
            if (d < 0) back -= d; else fwd += d;
        }
        return big.size() >= 3 && back > 1.5f * fwd ? ARABIC : LATIN;
    }

    /** Share of a word's width covered by flat ink at one height in the top part of the word; -1 if too small to tell. */
    private static float headlineCoverage(Word w) {
        int total = 0;
        for (S k : w.s) if (!k.mark) total += k.n;
        if (total < 6) return -1;
        float[] ys = new float[total];
        int j = 0;
        float l = Float.MAX_VALUE, r = -Float.MAX_VALUE;
        for (S k : w.s) if (!k.mark) for (int i = 0; i < k.n; i++) { ys[j++] = k.y[i]; l = Math.min(l, k.x[i]); r = Math.max(r, k.x[i]); }
        Arrays.sort(ys);
        float top = pct(ys, 0.04f), bot = pct(ys, 0.96f), h = bot - top, width = r - l;
        if (h < 2 || width < h * 0.6f) return -1;
        // flat pieces: (x0, x1, y), from points two apart so jitter doesn't count
        List<float[]> flat = new ArrayList<>();
        for (S k : w.s) {
            if (k.mark) continue;
            for (int i = 0; i + 2 < k.n; i++) {
                float dx = Math.abs(k.x[i + 2] - k.x[i]), dy = Math.abs(k.y[i + 2] - k.y[i]);
                if (dx > 0.3f && dy <= 0.35f * dx) flat.add(new float[]{Math.min(k.x[i], k.x[i + 2]), Math.max(k.x[i], k.x[i + 2]), (k.y[i] + k.y[i + 2]) / 2});
            }
        }
        if (flat.isEmpty()) return 0;
        // the best height in the top 45% of the word, and how much width flat ink covers there
        float band = Math.max(1.5f, h * 0.12f), best = 0;
        for (float y = top - h * 0.08f; y <= top + h * 0.45f; y += band / 2) {
            List<float[]> iv = new ArrayList<>();
            for (float[] f : flat) if (Math.abs(f[2] - y) <= band) iv.add(f);
            if (iv.isEmpty()) continue;
            iv.sort(Comparator.comparingDouble(f -> f[0]));
            float cov = 0, cs = iv.get(0)[0], ce = iv.get(0)[1];
            for (float[] f : iv) {
                if (f[0] > ce + band) { cov += ce - cs; cs = f[0]; ce = f[1]; } // small gaps (between letters) still count as one line
                else ce = Math.max(ce, f[1]);
            }
            cov += ce - cs;
            best = Math.max(best, cov / width);
        }
        return Math.min(1, best);
    }

    /** Words: strokes sorted left to right, split at gaps; with a word count from recognition, at its largest gaps. */
    private static List<Word> words(List<S> line, float hl, int wanted) {
        List<S> ss = new ArrayList<>(line);
        for (S k : ss) k.bounds();
        ss.sort(Comparator.comparingDouble(k -> k.l));
        // gaps between the running right edge and the next stroke
        float[] gap = new float[ss.size()];
        float right = -Float.MAX_VALUE;
        for (int i = 0; i < ss.size(); i++) {
            S k = ss.get(i);
            gap[i] = i == 0 ? 0 : k.l - right;
            if (k.mark) gap[i] = Math.min(gap[i], 0); // marks never start a word
            right = Math.max(right, k.r);
        }
        float thr = 0.55f * hl;
        if (wanted > 1) {
            float[] g = new float[ss.size() - 1];
            for (int i = 1; i < ss.size(); i++) g[i - 1] = gap[i];
            Arrays.sort(g);
            int idx = g.length - (wanted - 1);
            if (idx >= 0 && idx < g.length && g[idx] > 0.2f * hl) thr = Math.max(0.2f * hl, g[idx] - 0.01f);
        }
        List<Word> out = new ArrayList<>();
        Word cur = null;
        for (int i = 0; i < ss.size(); i++) {
            if (cur == null || gap[i] >= thr) { cur = new Word(); out.add(cur); }
            cur.s.add(ss.get(i));
        }
        for (Word w : out) w.bounds();
        return out;
    }

    /** Reference y (baseline, or headline) and letter height of a word, ignoring small marks and tails. */
    private static void refs(Word w, int script, float hl) {
        w.bounds();
        int total = 0;
        for (S k : w.s) if (!k.mark) total += k.n;
        if (total == 0) for (S k : w.s) total += k.n;
        float[] ys = new float[total];
        int j = 0;
        boolean any = false;
        for (S k : w.s) if (!k.mark) { any = true; for (int i = 0; i < k.n; i++) ys[j++] = k.y[i]; }
        if (!any) for (S k : w.s) for (int i = 0; i < k.n; i++) ys[j++] = k.y[i];
        Arrays.sort(ys);
        if (script == HEADLINE) {
            float barY = 0; int bn = 0;
            for (S k : w.s) if (k.bar) for (int i = 0; i < k.n; i++) { barY += k.y[i]; bn++; }
            w.ref = bn > 0 ? barY / bn : pct(ys, 0.1f);
            w.body = Math.max(1, pct(ys, 0.75f) - w.ref);
        } else if (script == ARABIC) {
            w.ref = densest(ys, hl / 8f);
            w.body = Math.max(1, pct(ys, 0.85f) - pct(ys, 0.15f));
        } else {
            w.ref = pct(ys, 0.8f);
            w.body = Math.max(1, w.ref - pct(ys, 0.2f));
        }
    }

    /** Least-squares line through word references (weighted by width); short lines use their strokes. Outliers dropped once. */
    private static float[] fit(List<Word> words, List<S> line, int script, float hl) {
        List<float[]> pts = new ArrayList<>(); // x, y, weight
        if (words.size() >= 3) {
            for (Word w : words) pts.add(new float[]{w.cx(), w.ref, Math.max(1, w.r - w.l)});
        } else {
            for (S k : line) {
                if (k.mark || k.n < 3) continue;
                Word one = new Word();
                one.s.add(k);
                refs(one, script, hl);
                pts.add(new float[]{k.cx(), one.ref, Math.max(1, k.w())});
            }
        }
        if (pts.size() < 2) return new float[]{0, pts.isEmpty() ? 0 : pts.get(0)[1]};
        float[] f = lsq(pts);
        List<float[]> keep = new ArrayList<>();
        for (float[] p : pts) if (Math.abs(p[1] - (f[1] + f[0] * p[0])) < 0.35f * hl) keep.add(p);
        if (keep.size() >= 2 && keep.size() < pts.size()) f = lsq(keep);
        f[0] = Math.max(-0.35f, Math.min(0.35f, f[0]));
        return f;
    }

    private static float[] lsq(List<float[]> pts) {
        double sw = 0, sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (float[] p : pts) { sw += p[2]; sx += p[2] * p[0]; sy += p[2] * p[1]; sxx += p[2] * p[0] * p[0]; sxy += p[2] * p[0] * p[1]; }
        double den = sw * sxx - sx * sx;
        double b = Math.abs(den) < 1e-6 ? 0 : (sw * sxy - sx * sy) / den;
        double a = (sy - b * sx) / sw;
        return new float[]{(float) b, (float) a};
    }

    /** Pull the bars onto one straight top line and close small gaps between them. */
    private static void headline(Word w, float t, float hl) {
        refs(w, HEADLINE, hl);
        List<S> bars = new ArrayList<>();
        for (S k : w.s) if (k.bar) bars.add(k);
        if (bars.isEmpty()) return;
        float y0 = w.ref;
        for (S k : bars) for (int i = 0; i < k.n; i++) k.y[i] += (y0 - k.y[i]) * 0.85f * t;
        if (t < 0.4f || bars.size() < 2) return;
        bars.sort(Comparator.comparingDouble(k -> { k.bounds(); return k.l; }));
        for (int i = 0; i + 1 < bars.size(); i++) {
            S a = bars.get(i), b = bars.get(i + 1);
            a.bounds(); b.bounds();
            float gap = b.l - a.r;
            if (gap <= 0 || gap > 0.6f * hl) continue;
            // extend a's right end to meet b (from whichever end of a is further right)
            boolean endRight = a.x[a.n - 1] >= a.x[0];
            float sx = endRight ? a.x[a.n - 1] : a.x[0], sy = endRight ? a.y[a.n - 1] : a.y[0];
            float spr = endRight ? a.pr[a.n - 1] : a.pr[0];
            int add = Math.max(2, Math.round(gap / 3));
            float[] nx = new float[a.n + add], ny = new float[a.n + add], np = new float[a.n + add];
            if (endRight) {
                System.arraycopy(a.x, 0, nx, 0, a.n); System.arraycopy(a.y, 0, ny, 0, a.n); System.arraycopy(a.pr, 0, np, 0, a.n);
                for (int q = 1; q <= add; q++) { float u = q / (float) add; nx[a.n + q - 1] = sx + (b.l + 1 - sx) * u; ny[a.n + q - 1] = sy + (y0 - sy) * u; np[a.n + q - 1] = spr; }
            } else {
                for (int q = 0; q < add; q++) { float u = 1 - q / (float) add; nx[q] = sx + (b.l + 1 - sx) * u; ny[q] = sy + (y0 - sy) * u; np[q] = spr; }
                System.arraycopy(a.x, 0, nx, add, a.n); System.arraycopy(a.y, 0, ny, add, a.n); System.arraycopy(a.pr, 0, np, add, a.n);
            }
            a.x = nx; a.y = ny; a.pr = np; a.n += add;
        }
    }

    /** Gaussian smoothing over neighbouring points; sharp corners and the two ends stay put. */
    private static void smooth(S k, float t) {
        if (k.n < 5) return;
        int r = 1 + Math.round(2 * t);
        float sigma = r * 0.6f;
        float[] nx = k.x.clone(), ny = k.y.clone();
        for (int i = 1; i < k.n - 1; i++) {
            if (corner(k, i)) continue;
            float sx = 0, sy = 0, sw = 0;
            for (int j = Math.max(0, i - r); j <= Math.min(k.n - 1, i + r); j++) {
                float w = (float) Math.exp(-((j - i) * (j - i)) / (2 * sigma * sigma));
                sx += w * k.x[j]; sy += w * k.y[j]; sw += w;
            }
            nx[i] = k.x[i] + (sx / sw - k.x[i]) * t;
            ny[i] = k.y[i] + (sy / sw - k.y[i]) * t;
        }
        k.x = nx; k.y = ny;
    }

    private static boolean corner(S k, int i) {
        int a = Math.max(0, i - 3), b = Math.min(k.n - 1, i + 3);
        float ux = k.x[i] - k.x[a], uy = k.y[i] - k.y[a], vx = k.x[b] - k.x[i], vy = k.y[b] - k.y[i];
        float lu = (float) Math.hypot(ux, uy), lv = (float) Math.hypot(vx, vy);
        if (lu < 1e-3f || lv < 1e-3f) return false;
        float cos = (ux * vx + uy * vy) / (lu * lv);
        return cos < 0.6f; // turning more than ~53°
    }

    private static void move(Word w, float dx, float dy) {
        for (S k : w.s) for (int i = 0; i < k.n; i++) { k.x[i] += dx; k.y[i] += dy; }
        w.l += dx; w.r += dx; w.t += dy; w.b += dy; w.ref += dy;
    }

    private static Stroke rebuild(S k) {
        Stroke o = new Stroke(k.src.tool, k.src.color, k.src.width);
        o.shape = k.src.shape;
        o.layer = k.src.layer;
        for (int i = 0; i < k.n; i++) o.add(k.x[i], k.y[i], k.pr[i]);
        return o;
    }

    // ---- grouping ------------------------------------------------------------------------------

    /**
     * Lines of writing, in two passes: main strokes join when their heights overlap enough (or one is the top bar the
     * other hangs from); then every small mark (dot, accent, crossbar) joins the line of the nearest main stroke.
     */
    private static List<List<S>> lines(List<S> ss, float hMed) {
        int n = ss.size();
        boolean[] small = new boolean[n];
        for (int i = 0; i < n; i++) small[i] = ss.get(i).h() < hMed * 0.45f && ss.get(i).w() < hMed;
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) {
            if (small[i] || small[j]) continue;
            S a = ss.get(i), c = ss.get(j);
            float ov = Math.min(a.b, c.b) - Math.max(a.t, c.t);
            float hgap = Math.max(0, Math.max(a.l, c.l) - Math.min(a.r, c.r));
            boolean sameRow = ov > 0.4f * Math.min(Math.max(a.h(), hMed * 0.5f), Math.max(c.h(), hMed * 0.5f))
                    || Math.abs(a.cy() - c.cy()) < 0.5f * hMed
                    || (hgap < 2.5f * hMed && Math.abs(a.cy() - c.cy()) < 0.9f * hMed); // neighbours along a tilted line
            boolean bar = hangs(a, c, hMed) || hangs(c, a, hMed);
            if ((sameRow || bar) && hgap < hMed * 4) union(parent, i, j);
        }
        // pieces of one tilted line: a group continues another when the other's fitted line runs through it
        for (boolean merged = true; merged; ) {
            merged = false;
            java.util.Map<Integer, List<Integer>> gs = new java.util.LinkedHashMap<>();
            for (int i = 0; i < n; i++) if (!small[i]) gs.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
            List<float[]> info = new ArrayList<>(); // root, slope, intercept, l, r, cx, cy
            for (java.util.Map.Entry<Integer, List<Integer>> e : gs.entrySet()) {
                List<float[]> pts = new ArrayList<>();
                float l = Float.MAX_VALUE, r = -Float.MAX_VALUE, sy = 0;
                for (int i : e.getValue()) { S k = ss.get(i); pts.add(new float[]{k.cx(), k.cy(), Math.max(1, k.w())}); l = Math.min(l, k.l); r = Math.max(r, k.r); sy += k.cy(); }
                float[] f = pts.size() >= 2 ? lsq(pts) : new float[]{0, sy / pts.size()};
                f[0] = Math.max(-0.35f, Math.min(0.35f, f[0]));
                info.add(new float[]{e.getKey(), f[0], f[1], l, r, (l + r) / 2, sy / pts.size()});
            }
            outer:
            for (int i = 0; i < info.size(); i++) for (int j = 0; j < info.size(); j++) {
                if (i == j) continue;
                float[] A = info.get(i), B = info.get(j);
                float gap = Math.max(B[3] - A[4], A[3] - B[4]);
                if (gap < -0.5f * hMed || gap > 6 * hMed) continue; // side by side, not too far apart
                float edge = B[3] > A[4] ? B[3] : B[4]; // B's near end
                float pred = A[2] + A[1] * edge, bAt = B[2] + B[1] * edge;
                if (Math.abs(pred - bAt) < 0.6f * hMed) { union(parent, (int) A[0], (int) B[0]); merged = true; break outer; }
            }
        }
        for (int i = 0; i < n; i++) {
            if (!small[i]) continue;
            S m = ss.get(i);
            int best = -1;
            float bd = 2.2f * hMed;
            for (int j = 0; j < n; j++) {
                if (small[j]) continue;
                S c = ss.get(j);
                float dx = Math.max(0, Math.max(c.l - m.r, m.l - c.r)), dy = Math.max(0, Math.max(c.t - m.b, m.t - c.b));
                float d = (float) Math.hypot(dx, dy * 1.2f);
                if (d < bd) { bd = d; best = j; }
            }
            if (best >= 0) union(parent, i, best);
        }
        java.util.LinkedHashMap<Integer, List<S>> g = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) g.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(ss.get(i));
        return new ArrayList<>(g.values());
    }

    /** Is {@code bar} a flat stroke lying along the top of {@code c} (letters hanging from it)? */
    private static boolean hangs(S bar, S c, float hMed) {
        boolean flat = bar.h() < 0.45f * hMed && bar.w() >= 0.8f * hMed && bar.w() >= 2 * bar.h();
        float under = c.t - bar.b, overlapX = Math.min(bar.r, c.r) - Math.max(bar.l, c.l);
        return flat && under > -0.5f * hMed && under < 0.6f * hMed && overlapX > 0;
    }

    private static int find(int[] p, int i) { while (p[i] != i) { p[i] = p[p[i]]; i = p[i]; } return i; }

    private static void union(int[] p, int a, int b) { p[find(p, a)] = find(p, b); }

    // ---- small statistics --------------------------------------------------------------------

    interface F { float of(S k); }

    private static float median(List<S> ss, F f) {
        float[] v = new float[ss.size()];
        for (int i = 0; i < v.length; i++) v[i] = f.of(ss.get(i));
        return medianOf(v);
    }

    private static float medianOf(float[] v) {
        if (v.length == 0) return 0;
        float[] c = v.clone();
        Arrays.sort(c);
        return c[c.length / 2];
    }

    private static float pct(float[] sorted, float q) {
        if (sorted.length == 0) return 0;
        return sorted[Math.min(sorted.length - 1, Math.max(0, Math.round(q * (sorted.length - 1))))];
    }

    /** The y with the most ink nearby (Arabic letters join along their baseline). */
    private static float densest(float[] sorted, float bin) {
        if (sorted.length == 0) return 0;
        float lo = sorted[0];
        int nb = Math.max(1, (int) ((sorted[sorted.length - 1] - lo) / bin) + 1);
        int[] h = new int[nb];
        for (float y : sorted) h[Math.min(nb - 1, (int) ((y - lo) / bin))]++;
        int best = 0;
        for (int i = 1; i < nb; i++) if (h[i] + (i > 0 ? h[i - 1] : 0) / 2 > h[best] + (best > 0 ? h[best - 1] : 0) / 2) best = i;
        return lo + (best + 0.5f) * bin;
    }
}

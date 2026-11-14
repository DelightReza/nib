package app.nib.notes;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.RectF;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.vision.digitalink.DigitalInkRecognition;
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel;
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier;
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer;
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions;
import com.google.mlkit.vision.digitalink.Ink;
import com.google.mlkit.vision.digitalink.RecognitionResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Handwriting search: Google ML Kit digital-ink recognition, on the tablet. Models download once
 * (English, and Punjabi/Gurmukhi when offered), then everything runs offline. Each page's ink is split into
 * lines (the recognizer reads one line at a time); the text goes into dir/search.json, page by page, re-read
 * only for pages whose ink changed.
 */
final class Handwriting {
    private Handwriting() {}

    static final String TAG = "nib-hw";
    static final String[] LANGS = {"en-US", "pa", "hi", "ar"};   // tried in order; unsupported tags are skipped
    static final String[] LANG_NAMES = {"English", "Punjabi", "Hindi", "Arabic"};
    static final String[] LANG_NOTES = {"English handwriting", "Gurmukhi script", "Devanagari script", "Arabic script, right to left"};
    /** Which script each language is written in (for Beautify). */
    static final int[] LANG_SCRIPT = {Beautify.LATIN, Beautify.HEADLINE, Beautify.HEADLINE, Beautify.ARABIC};
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final List<DigitalInkRecognizer> recognizers = new ArrayList<>();
    private static final java.util.Map<Integer, DigitalInkRecognizer> byLang = new java.util.HashMap<>(); // LANGS index → recognizer
    private static volatile String status = "";
    private static final int VERSION = 2; // bump to re-read every notebook after improving recognition

    static boolean enabled(Context c) { return prefs(c).getBoolean("hwSearch", true); }

    static boolean langOn(Context c, int i) { return prefs(c).getBoolean("hwLang" + i, i == 0); }

    static String status() { return status; }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("nib", Context.MODE_PRIVATE); }

    /** Re-reads the changed pages of one notebook in the background (after you leave it). */
    static void indexLater(Context c, File dir) {
        if (!enabled(c)) return;
        Context app = c.getApplicationContext();
        EXEC.execute(() -> {
            if (ready(app)) index(dir);
        });
    }

    /** Catches up every notebook whose ink changed since it was last read (notes screen, on open). */
    static void indexAll(Context c, Runnable done) {
        if (!enabled(c)) return;
        Context app = c.getApplicationContext();
        EXEC.execute(() -> {
            if (!ready(app)) return;
            boolean any = false;
            for (Notebook.Entry e : Notebook.list(app)) {
                File ink = new File(e.dir, "ink.bin"), idx = new File(e.dir, "search.json");
                JSONObject cur = idx.exists() ? read(e.dir) : null;
                boolean stale = cur == null || cur.optInt("v") != VERSION || idx.lastModified() < ink.lastModified();
                if (ink.exists() && stale) any |= index(e.dir);
            }
            status = "Ready";
            if (any && done != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(done);
        });
    }

    /** Downloads (first time) and opens the recognizers for the chosen languages. Background thread only. */
    private static synchronized boolean ready(Context c) {
        if (!recognizers.isEmpty()) return true;
        RemoteModelManager mgr = RemoteModelManager.getInstance();
        for (int i = 0; i < LANGS.length; i++) {
            if (!langOn(c, i)) continue;
            try {
                DigitalInkRecognitionModelIdentifier id = DigitalInkRecognitionModelIdentifier.fromLanguageTag(LANGS[i]);
                if (id == null) { Log.w(TAG, "no model for " + LANGS[i]); continue; }
                DigitalInkRecognitionModel m = DigitalInkRecognitionModel.builder(id).build();
                if (!Tasks.await(mgr.isModelDownloaded(m), 30, TimeUnit.SECONDS)) {
                    status = "Downloading " + LANG_NAMES[i] + " model…";
                    Log.i(TAG, status);
                    Tasks.await(mgr.download(m, new DownloadConditions.Builder().build()), 10, TimeUnit.MINUTES);
                }
                DigitalInkRecognizer rec = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(m).build());
                recognizers.add(rec);
                byLang.put(i, rec);
                Log.i(TAG, "recognizer ready: " + LANGS[i]);
            } catch (Exception ex) {
                Log.w(TAG, "model " + LANGS[i] + " unavailable: " + ex);
                status = "Couldn't download the " + LANG_NAMES[i] + " model (needs internet once)";
            }
        }
        if (!recognizers.isEmpty()) status = "Ready";
        return !recognizers.isEmpty();
    }

    /** Recognizers changed (languages switched): rebuild them and re-read everything. */
    static void reset(Context c) {
        Context app = c.getApplicationContext();
        EXEC.execute(() -> {
            synchronized (Handwriting.class) {
                for (DigitalInkRecognizer r : recognizers) r.close();
                recognizers.clear();
                byLang.clear();
            }
            for (Notebook.Entry e : Notebook.list(app)) new File(e.dir, "search.json").delete();
        });
        indexAll(c, null);
    }

    // ---- indexing ------------------------------------------------------------------------------

    private static boolean index(File dir) {
        try {
            Notebook nb = Notebook.load(dir);
            JSONObject old = read(dir);
            JSONArray oldPages = old != null && old.optInt("v") == VERSION ? old.optJSONArray("pages") : null;
            JSONArray pages = new JSONArray();
            boolean changed = oldPages == null || oldPages.length() != nb.pages.size();
            for (int i = 0; i < nb.pages.size(); i++) {
                Notebook.Page p = nb.pages.get(i);
                String h = hash(p);
                JSONObject prev = oldPages != null && i < oldPages.length() ? oldPages.optJSONObject(i) : null;
                if (prev != null && h.equals(prev.optString("h"))) { pages.put(prev); continue; }
                changed = true;
                pages.put(new JSONObject().put("h", h).put("t", recognizePage(p)));
            }
            if (!changed) { touch(dir); return false; }
            File f = new File(dir, "search.json");
            Io.write(f, new JSONObject().put("v", VERSION).put("pages", pages).toString().getBytes(StandardCharsets.UTF_8));
            Log.i(TAG, "indexed " + dir.getName() + " (" + nb.pages.size() + " pages)");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "index " + dir.getName(), e);
            return false;
        }
    }

    private static void touch(File dir) { new File(dir, "search.json").setLastModified(System.currentTimeMillis()); }

    private static String hash(Notebook.Page p) {
        long h = p.strokes.size() * 31L + p.items.size();
        for (Stroke s : p.strokes) {
            h = h * 1_000_003L + s.n;
            if (s.n > 0) h = h * 31 + Float.floatToIntBits(s.x(0)) + 7L * Float.floatToIntBits(s.y(s.n - 1));
        }
        for (Notebook.Item it : p.items) h = h * 31 + (it.text == null ? 0 : it.text.hashCode());
        return Long.toHexString(h);
    }

    /**
     * Level 2 for Beautify: reads a line with the models of its script that are already on the tablet (never downloads
     * here). Returns null when no model for that script is ready, so geometry decides alone.
     */
    static Beautify.Reader reader(Context c) {
        if (!enabled(c)) return null;
        return (line, script) -> {
            String best = null;
            for (int i = 0; i < LANGS.length; i++) {
                DigitalInkRecognizer r;
                synchronized (Handwriting.class) { r = byLang.get(i); }
                if (r == null || LANG_SCRIPT[i] != script) continue;
                RectF box = null;
                for (Stroke s : line) { RectF b = bounds(s); if (box == null) box = new RectF(b); else box.union(b); }
                try {
                    RecognitionResult res = Tasks.await(r.recognize(ink(line), context(box, "")), 10, TimeUnit.SECONDS);
                    String t = res.getCandidates().isEmpty() ? "" : res.getCandidates().get(0).getText();
                    if (best == null || t.length() > best.length()) best = t;
                } catch (Exception ignored) { }
            }
            return best;
        };
    }

    // ---- convert handwriting to text ---------------------------------------------------------

    /** What Convert to text read: the text (one line per line of writing), other readings of a one-line selection,
     *  the typical line height (to size the text box), or an error to show. */
    /** Language index meaning "detect it". */
    static final int AUTO = -1;

    static final class Converted {
        String text = "", error;
        int lang = -1; // the language it was read in (the detected one with AUTO; the most used if lines differ)
        final List<String> alternatives = new ArrayList<>();
        float lineHeight;
    }

    /** The recognizer for one language, downloaded and opened on first use (needs internet once). Background only. */
    private static DigitalInkRecognizer recognizerFor(int i, java.util.function.Consumer<String> status) throws Exception {
        synchronized (Handwriting.class) {
            DigitalInkRecognizer r = byLang.get(i);
            if (r != null) return r;
        }
        DigitalInkRecognitionModelIdentifier id = DigitalInkRecognitionModelIdentifier.fromLanguageTag(LANGS[i]);
        if (id == null) throw new Exception("Google has no " + LANG_NAMES[i] + " model");
        DigitalInkRecognitionModel m = DigitalInkRecognitionModel.builder(id).build();
        RemoteModelManager mgr = RemoteModelManager.getInstance();
        if (!Tasks.await(mgr.isModelDownloaded(m), 30, TimeUnit.SECONDS)) {
            status.accept("Downloading the " + LANG_NAMES[i] + " model (once)…");
            Tasks.await(mgr.download(m, new DownloadConditions.Builder().build()), 10, TimeUnit.MINUTES);
        }
        DigitalInkRecognizer r = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(m).build());
        synchronized (Handwriting.class) { byLang.put(i, r); }
        return r;
    }

    /**
     * Reads handwriting as text in language {@code lang} (index into LANGS): line by line, top to bottom.
     * {@code status} gets progress (model download); {@code done} the result. Both on the main thread.
     */
    static void convert(List<Stroke> strokes, int lang, java.util.function.Consumer<String> status, java.util.function.Consumer<Converted> done) {
        convert(strokes, lang, "", status, done);
    }

    /** Ink for the recognizer: page units scaled up to screen-like pixels, in the order written, plausible timing. */
    private static Ink ink(List<Stroke> line) {
        Ink.Builder ib = Ink.builder();
        long tms = 0;
        for (Stroke s : line) {
            if (s.n == 0) continue;
            Ink.Stroke.Builder sb = Ink.Stroke.builder();
            for (int k = 0; k < s.n; k++) sb.addPoint(Ink.Point.create(s.x(k) * INK_SCALE, s.y(k) * INK_SCALE, tms += 4));
            ib.addStroke(sb.build());
            tms += 150;
        }
        return ib.build();
    }

    private static final float INK_SCALE = 2f;

    /** What the recognizer is told: the size of the writing area (it judges letter case and size by it) and the text before. */
    private static com.google.mlkit.vision.digitalink.RecognitionContext context(RectF box, String pre) {
        float w = box == null ? 1000 : Math.max(box.width() * 1.3f, box.height() * 4);
        float h = box == null ? 100 : box.height() * 1.3f;
        String tail = pre == null ? "" : pre.length() > 20 ? pre.substring(pre.length() - 20) : pre;
        return com.google.mlkit.vision.digitalink.RecognitionContext.builder()
                .setPreContext(tail)
                .setWritingArea(new com.google.mlkit.vision.digitalink.WritingArea(w * INK_SCALE, h * INK_SCALE))
                .build();
    }

    /** As above, with the text that comes just before (the box being continued), which helps the reading. */
    static void convert(List<Stroke> strokes, int lang, String preContext, java.util.function.Consumer<String> status,
                        java.util.function.Consumer<Converted> done) {
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        java.util.function.Consumer<String> st = m -> main.post(() -> status.accept(m));
        EXEC.execute(() -> {
            Converted out = new Converted();
            try {
                DigitalInkRecognizer fixed = lang == AUTO ? null : recognizerFor(lang, st);
                int[] used = new int[LANGS.length];
                List<List<Stroke>> lines = lines(strokes);
                float[] hs = new float[lines.size()];
                StringBuilder sb = new StringBuilder();
                for (int li = 0; li < lines.size(); li++) {
                    List<Stroke> line = lines.get(li);
                    RectF box = null;
                    for (Stroke s : line) {
                        if (s.n == 0) continue;
                        RectF b = bounds(s);
                        if (box == null) box = new RectF(b); else box.union(b);
                    }
                    hs[li] = box == null ? 0 : box.height();
                    String pre = sb.length() > 0 ? sb.toString().replace('\n', ' ') : preContext;
                    RecognitionResult res;
                    int li2 = lang;
                    if (fixed != null) res = Tasks.await(fixed.recognize(ink(line), context(box, pre)), 20, TimeUnit.SECONDS);
                    else { // auto: the line's script narrows the languages; their confidence scores pick one
                        int script = Beautify.scriptOf(line);
                        // the shape decides the script; scores only pick between languages of one script (Hindi or
                        // Punjabi). Scores from different scripts' models aren't comparable: English "won" on Indic ink.
                        int[] tryLangs = script == Beautify.HEADLINE ? new int[]{2, 1} : script == Beautify.ARABIC ? new int[]{3} : new int[]{0};
                        res = null;
                        float best = Float.MAX_VALUE;
                        for (int k : tryLangs) {
                            RecognitionResult rr = Tasks.await(recognizerFor(k, st).recognize(ink(line), context(box, pre)), 20, TimeUnit.SECONDS);
                            if (rr.getCandidates().isEmpty()) continue;
                            Float sc = rr.getCandidates().get(0).getScore();
                            float v = sc == null ? Float.MAX_VALUE / 2 : sc; // lower = more confident
                            if (res == null || v < best) { res = rr; best = v; li2 = k; }
                        }
                        if (res == null) continue;
                        Log.i(TAG, "auto language: script " + script + " → " + LANG_NAMES[li2] + " (score " + best + ")");
                    }
                    if (res.getCandidates().isEmpty()) continue;
                    if (li2 >= 0) used[li2]++;
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(res.getCandidates().get(0).getText());
                    if (lines.size() == 1)
                        for (int c = 1; c < Math.min(4, res.getCandidates().size()); c++) out.alternatives.add(res.getCandidates().get(c).getText());
                }
                out.text = sb.toString();
                int top = lang;
                for (int k = 0; k < used.length; k++) if (top < 0 || used[k] > used[top]) top = used[k] > 0 ? k : top;
                out.lang = top;
                java.util.Arrays.sort(hs);
                out.lineHeight = hs.length == 0 ? 0 : hs[hs.length / 2];
                if (out.text.isEmpty()) out.error = "Couldn't read any writing in the selection";
            } catch (Exception e) {
                Log.w(TAG, "convert failed", e);
                out.error = e.getMessage() != null && e.getMessage().startsWith("Google") ? e.getMessage()
                        : "Couldn't read it. " + (lang == AUTO ? "Auto needs each language's model" : "The " + LANG_NAMES[lang] + " model needs")
                        + " internet the first time.";
            }
            main.post(() -> done.accept(out));
        });
    }

    /** Language for converting, from settings: AUTO or an index into LANGS. */
    static int convertLang(Context c) {
        SharedPreferences p = prefs(c);
        if (p.getBoolean("convertAuto", false)) return AUTO;
        return Math.min(p.getInt("convertLang", 0), LANGS.length - 1);
    }

    /** Text of one page: typed text boxes, then each line of handwriting, top to bottom. */
    private static String recognizePage(Notebook.Page p) {
        StringBuilder sb = new StringBuilder();
        for (Notebook.Item it : p.items) if (it.type == Notebook.Item.TEXT && it.text != null) sb.append(it.text).append('\n');
        for (List<Stroke> line : lines(p.strokes)) {
            Ink.Builder ib = Ink.builder();
            long tms = 0;
            for (Stroke s : line) { // in the order you wrote them, with plausible pen timing
                if (s.hl() || s.n == 0) continue;
                Ink.Stroke.Builder sbld = Ink.Stroke.builder();
                for (int k = 0; k < s.n; k++) sbld.addPoint(Ink.Point.create(s.x(k), s.y(k), tms += 5));
                ib.addStroke(sbld.build());
                tms += 180;
            }
            Ink ink = ib.build();
            if (ink.getStrokes().isEmpty()) continue;
            String best = null;
            for (DigitalInkRecognizer r : recognizers) {
                try {
                    RecognitionResult res = Tasks.await(r.recognize(ink), 20, TimeUnit.SECONDS);
                    if (!res.getCandidates().isEmpty()) {
                        String t = res.getCandidates().get(0).getText();
                        if (best == null) best = t;
                        else best = best + " / " + t; // keep every language's reading: search matches either
                    }
                } catch (Exception ignored) { }
            }
            if (best != null) sb.append(best).append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * Groups strokes into lines of writing, in two passes. Main strokes share a line when their heights overlap enough,
     * when one is a top bar (shirorekha) the other hangs from, or when they're neighbours along a tilted line. Then every
     * small mark joins the line of the nearest main stroke: dots, accents, and the matras above or below Indic letters
     * (ि ी े ै ु ू ृ ं ँ ੁ ੂ ੰ ਂ…), which otherwise were read as separate lines and lost from their word.
     * Strokes keep the order they were written in (joined-up writing reads best that way).
     */
    private static List<List<Stroke>> lines(List<Stroke> strokes) {
        List<Stroke> ss = new ArrayList<>();
        for (Stroke s : strokes) if (!s.hl() && s.n > 0) ss.add(s);
        int n = ss.size();
        if (n == 0) return new ArrayList<>();
        RectF[] b = new RectF[n];
        float[] hs = new float[n];
        for (int i = 0; i < n; i++) { b[i] = bounds(ss.get(i)); hs[i] = Math.max(2, b[i].height()); }
        float[] sorted = hs.clone();
        java.util.Arrays.sort(sorted);
        float hMed = Math.max(8, sorted[Math.min(n - 1, Math.round(0.65f * (n - 1)))]); // typical letter height (marks are often half the strokes)
        boolean[] small = new boolean[n];
        for (int i = 0; i < n; i++) small[i] = b[i].height() < hMed * 0.55f && b[i].width() < hMed * 1.2f;
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) {
            if (small[i] || small[j]) continue;
            RectF a = b[i], c = b[j];
            float ov = Math.min(a.bottom, c.bottom) - Math.max(a.top, c.top);
            float hgap = Math.max(0, Math.max(a.left, c.left) - Math.min(a.right, c.right));
            boolean sameRow = ov > 0.4f * Math.min(Math.max(a.height(), hMed * 0.5f), Math.max(c.height(), hMed * 0.5f))
                    || Math.abs(a.centerY() - c.centerY()) < 0.5f * hMed
                    || (hgap < 2.5f * hMed && Math.abs(a.centerY() - c.centerY()) < 0.9f * hMed);
            boolean bar = hangs(a, c, hMed) || hangs(c, a, hMed);
            if ((sameRow || bar) && hgap < hMed * 4) union(parent, i, j);
        }
        boolean anyMain = false;
        for (boolean sm : small) anyMain |= !sm;
        for (int i = 0; i < n && anyMain; i++) {
            if (!small[i]) continue;
            RectF m = b[i];
            int best = -1;
            float bd = 2.2f * hMed;
            for (int j = 0; j < n; j++) {
                if (small[j]) continue;
                RectF c = b[j];
                float dx = Math.max(0, Math.max(c.left - m.right, m.left - c.right)), dy = Math.max(0, Math.max(c.top - m.bottom, m.top - c.bottom));
                float d = (float) Math.hypot(dx, dy * 1.2f);
                if (d < bd) { bd = d; best = j; }
            }
            if (best >= 0) union(parent, i, best);
        }
        java.util.LinkedHashMap<Integer, List<Stroke>> groups = new java.util.LinkedHashMap<>();
        java.util.HashMap<Integer, RectF> gb = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            int r = find(parent, i);
            groups.computeIfAbsent(r, k -> new ArrayList<>()).add(ss.get(i)); // chronological within a line
            RectF box = gb.get(r);
            if (box == null) gb.put(r, new RectF(b[i])); else box.union(b[i]);
        }
        List<Integer> keys = new ArrayList<>(groups.keySet());
        keys.sort(Comparator.comparingDouble((Integer k) -> gb.get(k).top).thenComparingDouble(k -> gb.get(k).left));
        List<List<Stroke>> out = new ArrayList<>();
        for (Integer k : keys) out.add(groups.get(k));
        return out;
    }

    /** Is {@code bar} a flat stroke lying along the top of {@code c} (letters hanging from a shirorekha)? */
    private static boolean hangs(RectF bar, RectF c, float hMed) {
        boolean flat = bar.height() < 0.45f * hMed && bar.width() >= 0.8f * hMed && bar.width() >= 2 * bar.height();
        float under = c.top - bar.bottom, overlapX = Math.min(bar.right, c.right) - Math.max(bar.left, c.left);
        return flat && under > -0.5f * hMed && under < 0.6f * hMed && overlapX > 0;
    }

    private static int find(int[] p, int i) { while (p[i] != i) { p[i] = p[p[i]]; i = p[i]; } return i; }

    private static void union(int[] p, int a, int b) { p[find(p, a)] = find(p, b); }

    private static RectF bounds(Stroke s) {
        RectF r = new RectF(s.x(0), s.y(0), s.x(0), s.y(0));
        for (int i = 1; i < s.n; i++) r.union(s.x(i), s.y(i));
        return r;
    }

    // ---- searching -----------------------------------------------------------------------------

    static JSONObject read(File dir) {
        File f = new File(dir, "search.json");
        if (!f.exists()) return null;
        try { return new JSONObject(new String(Io.read(f), StandardCharsets.UTF_8)); } catch (Exception e) { return null; }
    }

    /** First page whose handwriting contains q (lower-case), with a short snippet; null if none. */
    static Hit find(File dir, String q) {
        JSONObject o = read(dir);
        if (o == null) return null;
        JSONArray pages = o.optJSONArray("pages");
        if (pages == null) return null;
        for (int i = 0; i < pages.length(); i++) {
            String t = pages.optJSONObject(i) == null ? "" : pages.optJSONObject(i).optString("t");
            int at = t.toLowerCase(Locale.ROOT).indexOf(q);
            if (at < 0) continue;
            int s = Math.max(0, at - 6), e = Math.min(t.length(), at + q.length() + 36); // lead with the match
            int ws = t.lastIndexOf(' ', at); if (ws >= 0 && ws >= s) s = ws + 1;
            int nl = t.lastIndexOf('\n', at); if (nl >= 0 && nl >= s - 1) s = nl + 1;
            String snip = (s > 0 ? "…" : "") + t.substring(s, e).replace('\n', ' ').trim() + (e < t.length() ? "…" : "");
            return new Hit(i, snip);
        }
        return null;
    }

    static final class Hit {
        final int page;
        final String snippet;
        Hit(int page, String snippet) { this.page = page; this.snippet = snippet; }
    }
}

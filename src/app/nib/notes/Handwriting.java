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
    static final String[] LANGS = {"en-US", "pa"};   // tried in order; unsupported tags are skipped
    static final String[] LANG_NAMES = {"English", "Punjabi"};
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final List<DigitalInkRecognizer> recognizers = new ArrayList<>();
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
                recognizers.add(DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(m).build()));
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
     * Groups strokes into lines of writing. Two strokes share a line when their heights overlap enough, or when a
     * small mark (dot, accent, comma) sits within reach of a stroke; lines then split at wide horizontal gaps.
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
        float hMed = Math.max(8, sorted[n / 2]); // typical letter height on this page
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) {
            RectF a = b[i], c = b[j];
            float ov = Math.min(a.bottom, c.bottom) - Math.max(a.top, c.top);
            float hgap = Math.max(0, Math.max(a.left, c.left) - Math.min(a.right, c.right));
            boolean sameRow = ov > 0.4f * Math.min(Math.max(a.height(), hMed * 0.5f), Math.max(c.height(), hMed * 0.5f));
            boolean small = Math.min(a.height(), c.height()) < hMed * 0.45f && Math.min(a.width(), c.width()) < hMed;
            boolean near = Math.abs(a.centerY() - c.centerY()) < hMed * 0.9f;
            if ((sameRow || (small && near)) && hgap < hMed * 4) union(parent, i, j);
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

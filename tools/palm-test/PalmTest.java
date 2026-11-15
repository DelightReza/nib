package app.nib.notes;
import java.util.*;
/** Scripted touch scenarios for PalmFilter (density 3.5, like the test phone). */
public class PalmTest {
  static final float D = 3.5f, TIP = 12 * D, PALM = 90 * D;
  PalmFilter f = new PalmFilter(D);
  Map<Integer, float[]> down = new LinkedHashMap<>(); // id -> x, y, major
  List<String> out = new ArrayList<>();
  long t;
  String ev(int action, int id) {
    int n = down.size(); int[] ids = new int[n]; float[] xs = new float[n], ys = new float[n], ms = new float[n]; int i = 0;
    for (Map.Entry<Integer, float[]> e : down.entrySet()) { ids[i] = e.getKey(); xs[i] = e.getValue()[0]; ys[i] = e.getValue()[1]; ms[i] = e.getValue()[2]; i++; }
    int r = f.onEvent(action, id, n, ids, xs, ys, ms, t);
    String s = r == PalmFilter.PASS ? "PASS" : r == PalmFilter.IGNORE ? "IGNORE" : "WRITE_" + new String[]{"DOWN","UP","MOVE","CANCEL"}[f.writeAction] + "#" + f.writerId;
    out.add(s); return s;
  }
  void put(int id, float x, float y, float m) { down.put(id, new float[]{x, y, m}); }
  String touch(int id, float x, float y, float m) { boolean first = down.isEmpty(); put(id, x, y, m); return ev(first ? PalmFilter.DOWN : PalmFilter.POINTER_DOWN, id); }
  String move(int id, float x, float y) { float[] p = down.get(id); put(id, x, y, p[2]); t += 8; return ev(PalmFilter.MOVE, id); }
  String grow(int id, float m) { float[] p = down.get(id); put(id, p[0], p[1], m); t += 8; return ev(PalmFilter.MOVE, id); }
  String lift(int id) { String s = ev(down.size() == 1 ? PalmFilter.UP : PalmFilter.POINTER_UP, id); down.remove(id); return s; }
  String summary() { List<String> c = new ArrayList<>(); for (String s : out) if (c.isEmpty() || !c.get(c.size() - 1).equals(s)) c.add(s); return String.join(" ", c); }

  static int fails;
  static void check(String name, PalmTest p, String want) {
    String got = p.summary();
    boolean ok = got.equals(want);
    if (!ok) fails++;
    System.out.println((ok ? "PASS  " : "FAIL  ") + name + (ok ? "" : "\n      got:  " + got + "\n      want: " + want));
  }
  public static void main(String[] a) {
    PalmTest p;
    // 1 a plain stroke with the tip
    p = new PalmTest(); p.touch(0, 300, 500, TIP); for (int i = 0; i < 10; i++) p.move(0, 300 + i * 10, 500); p.lift(0);
    check("tip only: one stroke", p, "WRITE_DOWN#0 WRITE_MOVE#0 WRITE_UP#0");
    // 2 palm rests first (below-right), then the tip writes above-left of it; palm moves; tip lifts; palm lifts
    p = new PalmTest(); p.touch(0, 700, 1500, PALM); p.t += 300; p.move(0, 705, 1502);
    p.touch(1, 400, 1100, TIP); for (int i = 0; i < 8; i++) { p.move(1, 400 + i * 12, 1100); p.move(0, 706 + i, 1503); } p.lift(1); p.lift(0);
    check("palm first, then tip writes", p, "IGNORE WRITE_DOWN#1 WRITE_MOVE#1 WRITE_UP#1 IGNORE");
    // 3 tip writes, then the palm lands below-right mid-stroke: stroke carries on
    p = new PalmTest(); p.touch(0, 400, 1000, TIP); for (int i = 0; i < 30; i++) p.move(0, 400 + i * 5, 1000);
    p.touch(1, 650, 1300, PALM); for (int i = 0; i < 5; i++) p.move(0, 550 + i * 5, 1000); p.lift(0); p.lift(1);
    check("tip first, palm lands later", p, "WRITE_DOWN#0 WRITE_MOVE#0 IGNORE WRITE_MOVE#0 WRITE_UP#0 IGNORE");
    // 4 the palm's edge touches first (small), the rest of the palm lands next to it 40 ms later: cancel
    p = new PalmTest(); p.touch(0, 600, 1300, TIP); p.move(0, 602, 1301); p.t += 30; p.touch(1, 640, 1340, PALM); p.lift(0); p.lift(1);
    check("palm edge first: stroke cancelled", p, "WRITE_DOWN#0 WRITE_MOVE#0 WRITE_CANCEL#0 IGNORE");
    // 5 a contact that grows into a palm within the first moments: cancel
    p = new PalmTest(); p.touch(0, 600, 1300, TIP); p.move(0, 601, 1300); p.grow(0, 60 * D); p.grow(0, PALM); p.move(0, 610, 1310); p.lift(0);
    check("contact grows into palm: cancelled", p, "WRITE_DOWN#0 WRITE_MOVE#0 WRITE_CANCEL#0 IGNORE");
    // 6 two fingers together, the second up-left and far away: scroll as before
    p = new PalmTest(); p.touch(0, 600, 1300, TIP); p.t += 40; p.touch(1, 200, 800, TIP); p.move(0, 600, 1200); p.move(1, 200, 700); p.lift(1); p.lift(0);
    check("two-finger scroll still works", p, "WRITE_DOWN#0 PASS");
    // 7 left hand: palm below-left of the tip is ignored
    p = new PalmTest(); p.f.rightHanded = false; p.touch(0, 600, 1000, TIP); for (int i = 0; i < 30; i++) p.move(0, 600 - i * 4, 1000);
    p.touch(1, 380, 1250, TIP * 1.4f); p.move(0, 470, 1000); p.lift(0); p.lift(1);
    check("left hand: palm below-left ignored", p, "WRITE_DOWN#0 WRITE_MOVE#0 IGNORE WRITE_MOVE#0 WRITE_UP#0 IGNORE");
    // 8 palm resting, tip writes a word, lifts, writes the next word: two strokes
    p = new PalmTest(); p.touch(0, 750, 1500, PALM); p.t += 200;
    p.touch(1, 400, 1100, TIP); p.move(1, 420, 1100); p.lift(1); p.t += 150;
    p.touch(2, 450, 1105, TIP); p.move(2, 470, 1100); p.lift(2); p.lift(0);
    check("palm resting, two strokes", p, "IGNORE WRITE_DOWN#1 WRITE_MOVE#1 WRITE_UP#1 WRITE_DOWN#2 WRITE_MOVE#2 WRITE_UP#2 IGNORE");
    // 9 learns the tip size: after strokes with a 12 dp tip, a 45 dp contact counts as a palm
    p = new PalmTest(); for (int s = 0; s < 5; s++) { p.touch(0, 300, 500, TIP); p.move(0, 320, 500); p.lift(0); }
    p.out.clear(); p.touch(0, 300, 900, 45 * D); p.lift(0);
    check("learned tip size: 45 dp is a palm", p, "IGNORE");
    // 10 index then middle finger side by side (level, to the right): scroll, not a palm
    p = new PalmTest(); p.touch(0, 500, 1300, TIP); p.t += 30; p.touch(1, 580, 1290, TIP); p.move(0, 500, 1200); p.move(1, 580, 1190); p.lift(1); p.lift(0);
    check("two fingers side by side: scroll", p, "WRITE_DOWN#0 PASS");
    // 11 second finger lands 300 ms later, after the first drifted ~15 dp: still scroll
    p = new PalmTest(); p.touch(0, 500, 1300, TIP); for (int i = 0; i < 5; i++) p.move(0, 500 + i * 10, 1300); p.t += 260;
    p.touch(1, 250, 1000, TIP); p.move(0, 540, 1200); p.lift(1); p.lift(0);
    check("late second finger after drift: scroll", p, "WRITE_DOWN#0 WRITE_MOVE#0 PASS");
    // 12 a real stroke, then a small touch level with the tip: stray, the stroke goes on
    p = new PalmTest(); p.touch(0, 300, 1000, TIP); for (int i = 0; i < 30; i++) p.move(0, 300 + i * 10, 1000);
    p.touch(1, 700, 1010, TIP); p.move(0, 600, 1000); p.lift(0); p.lift(1);
    check("small touch after a real stroke: ignored", p, "WRITE_DOWN#0 WRITE_MOVE#0 IGNORE WRITE_MOVE#0 WRITE_UP#0 IGNORE");
    System.out.println(fails == 0 ? "all passed" : fails + " failed");
    System.exit(fails == 0 ? 0 : 1);
  }
}

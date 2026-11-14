package app.nib.notes;

import java.util.*;

/** Script detection on synthetic lines: Latin, Indic with a separate top bar, Indic with the bar drawn inside each letter, Arabic. */
public class ScriptTest {
    static List<Stroke> integrated(float y0, int words, Random R) {
        List<Stroke> out = new ArrayList<>();
        float x = 60;
        for (int w = 0; w < words; w++) {
            float sc = 0.8f + R.nextFloat() * 0.4f, xh = 16 * sc, top = y0 - xh + (R.nextFloat() - .5f) * 3;
            int letters = 2 + R.nextInt(3);
            for (int l = 0; l < letters; l++) { // each letter: its piece of the top line, then the body hanging from it
                Stroke s = new Stroke(Stroke.BALL, 0xFF222222, 3f);
                float lx = x + l * 15 * sc;
                for (float bx = lx; bx <= lx + 15 * sc; bx += 1.5f) s.add(bx, top + 0.8f * (float) Math.sin(bx / 5) + (R.nextFloat() - .5f) * .6f, .5f);
                for (int k = 0; k <= 18; k++) {
                    double a = k / 18.0 * 2 * Math.PI;
                    s.add(lx + 7 * sc + 5 * sc * (float) Math.sin(a), top + xh * 0.55f + xh * 0.42f * (float) -Math.cos(a), .5f);
                }
                out.add(s);
            }
            x += letters * 15 * sc + 18 + R.nextFloat() * 20;
        }
        return out;
    }

    public static void main(String[] a) {
        Random R = new Random(3);
        String[] names = {"latin", "indic (separate bar)", "indic (bar in letters)", "arabic"};
        for (int kind = 0; kind < 4; kind++) {
            int[] got = new int[3];
            for (int trial = 0; trial < 20; trial++) {
                BeautifyTest.R.setSeed(trial * 31 + kind);
                List<Stroke> line = kind == 0 ? BeautifyTest.latin(200, 4 + trial % 4) : kind == 1 ? BeautifyTest.headline(200, 3 + trial % 4)
                        : kind == 2 ? integrated(200, 3 + trial % 4, R) : BeautifyTest.arabic(200, 3 + trial % 4);
                got[Beautify.scriptOf(line)]++;
            }
            System.out.printf("%-24s → latin %2d  headline %2d  arabic %2d  (of 20)%n", names[kind], got[0], got[1], got[2]);
        }
    }
}

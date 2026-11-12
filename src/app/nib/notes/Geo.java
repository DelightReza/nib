package app.nib.notes;

final class Geo {
    private Geo() {}

    /** Even-odd point-in-polygon. */
    static boolean inside(float x, float y, float[] xs, float[] ys, int n) {
        boolean c = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            if (((ys[i] > y) != (ys[j] > y)) && (x < (xs[j] - xs[i]) * (y - ys[i]) / (ys[j] - ys[i]) + xs[i])) c = !c;
        }
        return c;
    }
}

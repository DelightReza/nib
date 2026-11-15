package app.nib.notes;

/**
 * Software palm rejection for writing with a finger or a passive (rubber / capacitive) stylus: Android reports both
 * as fingers, so the palm has to be told from the tip by contact size, position and timing. Plain Java (no Android),
 * so it can be tested on a desktop JVM; Engine turns its decisions into a single-pointer gesture for the ink code.
 *
 * Per touch event it answers one of:
 *  PASS   — hand the event on unchanged (no palm involved; two fingers that mean "scroll");
 *  IGNORE — drop it (a palm landing, moving or lifting);
 *  WRITE  — the writing contact {@link #writerId} did {@link #writeAction} (DOWN / MOVE / UP / CANCEL).
 *
 * Rules:
 *  - size: a contact wider than palmSize() is a palm (learned from the writer's own tip size; 40 dp until then);
 *  - zone: a contact landing below and on the writing-hand side of the tip (or touching a known palm) is a palm;
 *  - palm first: the gesture waits in palm state; the next small contact clear of the palm becomes the writer;
 *  - tip first: a later palm is ignored and the stroke goes on; a small second touch clear of the zone within
 *    TWO_FINGER_MS of the start (and before the tip has moved) still means two-finger scroll, as before;
 *  - retro-cancel: if the "tip" grows into a palm, or a big contact lands right next to it within LEAD_MS, it was the
 *    palm's leading edge: the stroke is cancelled before it's saved.
 */
final class PalmFilter {
    static final int PASS = 0, IGNORE = 1, WRITE = 2;
    // actions (the same values as MotionEvent's, so Engine can pass them straight through)
    static final int DOWN = 0, UP = 1, MOVE = 2, CANCEL = 3, POINTER_DOWN = 5, POINTER_UP = 6;

    static final long TWO_FINGER_MS = 120, LEAD_MS = 80, GROW_MS = 160;

    private final float dp;
    boolean rightHanded = true;

    /** Result of the last onEvent when it returned WRITE. */
    int writeAction, writerId = -1;

    private static final int IDLE = 0, WRITING = 1, PALM = 2, PASSING = 3;
    private int state = IDLE;
    private final int[] palmIds = new int[10];
    private final float[] palmX = new float[10], palmY = new float[10];
    private int palms;
    private long writeStart;
    private float startX, startY, refX, refY, writerMax;
    private boolean writerMoved;
    // the writer's tip size over recent strokes, to tell a palm from it
    private final float[] tips = new float[30];
    private int tipN;

    PalmFilter(float density) { dp = density; }

    float palmSize() {
        if (tipN < 3) return 40 * dp;
        float[] t = java.util.Arrays.copyOf(tips, Math.min(tipN, tips.length));
        java.util.Arrays.sort(t);
        return Math.max(28 * dp, 2.2f * t[t.length / 2]);
    }

    /**
     * One touch event. ids / xs / ys / majors describe every pointer now down (for UP / POINTER_UP the one lifting is
     * still included), actionId is the pointer that went down or up.
     */
    int onEvent(int action, int actionId, int n, int[] ids, float[] xs, float[] ys, float[] majors, long t) {
        switch (action) {
            case DOWN: {
                reset();
                int i = idx(ids, n, actionId);
                if (isPalm(majors[i])) { addPalm(actionId, xs[i], ys[i]); state = PALM; return IGNORE; }
                return startWriter(actionId, xs[i], ys[i], majors[i], t);
            }
            case POINTER_DOWN: {
                int i = idx(ids, n, actionId);
                if (state == PASSING) return PASS;
                if (state == WRITING) {
                    boolean big = isPalm(majors[i]);
                    boolean near = dist(xs[i], ys[i], refX, refY) < 60 * dp;
                    if (big && near && t - writeStart < LEAD_MS) { // the "tip" was the palm's leading edge
                        addPalm(writerId, refX, refY);
                        addPalm(actionId, xs[i], ys[i]);
                        state = PALM;
                        return write(CANCEL, writerId);
                    }
                    if (big || inZone(xs[i], ys[i], refX, refY) || nearPalm(xs[i], ys[i])) { addPalm(actionId, xs[i], ys[i]); return IGNORE; }
                    if (t - writeStart < TWO_FINGER_MS && !writerMoved) { state = PASSING; return PASS; } // two fingers: scroll
                    return IGNORE; // a stray touch while writing
                }
                if (state == PALM) {
                    if (!isPalm(majors[i]) && !nearPalm(xs[i], ys[i]) && !belowPalm(xs[i], ys[i]))
                        return startWriter(actionId, xs[i], ys[i], majors[i], t);
                    addPalm(actionId, xs[i], ys[i]);
                    return IGNORE;
                }
                return PASS;
            }
            case MOVE: {
                if (state == PASSING) return PASS;
                for (int k = 0; k < palms; k++) { int i = idx(ids, n, palmIds[k]); if (i >= 0) { palmX[k] = xs[i]; palmY[k] = ys[i]; } }
                if (state != WRITING) return IGNORE;
                int i = idx(ids, n, writerId);
                if (i < 0) return IGNORE;
                writerMax = Math.max(writerMax, majors[i]);
                if (isPalm(majors[i]) && t - writeStart < GROW_MS) { // grew into a palm: it never was the tip
                    addPalm(writerId, xs[i], ys[i]);
                    state = PALM;
                    return write(CANCEL, writerId);
                }
                if (dist(xs[i], ys[i], startX, startY) > 8 * dp) writerMoved = true;
                refX = xs[i];
                refY = ys[i];
                return write(MOVE, writerId);
            }
            case POINTER_UP: {
                if (state == PASSING) return PASS;
                if (state == WRITING && actionId == writerId) { learn(); state = palms > 0 ? PALM : IDLE; return write(UP, writerId); }
                removePalm(actionId);
                return IGNORE;
            }
            case UP: {
                int was = state;
                if (was == PASSING) { reset(); return PASS; }
                boolean writer = was == WRITING && actionId == writerId;
                if (writer) learn();
                reset();
                return writer ? write(UP, actionId) : IGNORE;
            }
            case CANCEL: {
                int was = state;
                reset();
                if (was == PASSING) return PASS;
                return was == WRITING ? write(CANCEL, writerId) : IGNORE;
            }
        }
        return state == PASSING ? PASS : IGNORE;
    }

    private int startWriter(int id, float x, float y, float major, long t) {
        state = WRITING;
        writerId = id;
        writeStart = t;
        startX = refX = x;
        startY = refY = y;
        writerMax = major;
        writerMoved = false;
        return write(DOWN, id);
    }

    private int write(int action, int id) { writeAction = action; writerId = id; return WRITE; }

    private void learn() {
        if (writerMax > 0 && !isPalm(writerMax)) tips[tipN++ % tips.length] = writerMax;
    }

    private void reset() { state = IDLE; palms = 0; writerId = -1; writerMoved = false; }

    private boolean isPalm(float major) { return major > palmSize(); }

    /** Below the tip and toward the writing hand: where the heel of the hand rests. */
    private boolean inZone(float x, float y, float tx, float ty) {
        if (y < ty - 30 * dp) return false;
        return rightHanded ? x > tx - 30 * dp : x < tx + 30 * dp;
    }

    private boolean nearPalm(float x, float y) {
        for (int k = 0; k < palms; k++) if (dist(x, y, palmX[k], palmY[k]) < 45 * dp) return true;
        return false;
    }

    /** Below a resting palm, on the side away from the pen: no tip can be there. */
    private boolean belowPalm(float x, float y) {
        for (int k = 0; k < palms; k++) {
            boolean side = rightHanded ? x > palmX[k] : x < palmX[k];
            if (side && y > palmY[k]) return true;
        }
        return false;
    }

    private void addPalm(int id, float x, float y) {
        for (int k = 0; k < palms; k++) if (palmIds[k] == id) { palmX[k] = x; palmY[k] = y; return; }
        if (palms < palmIds.length) { palmIds[palms] = id; palmX[palms] = x; palmY[palms] = y; palms++; }
    }

    private void removePalm(int id) {
        for (int k = 0; k < palms; k++)
            if (palmIds[k] == id) {
                palms--;
                palmIds[k] = palmIds[palms]; palmX[k] = palmX[palms]; palmY[k] = palmY[palms];
                return;
            }
    }

    private static int idx(int[] ids, int n, int id) {
        for (int i = 0; i < n; i++) if (ids[i] == id) return i;
        return -1;
    }

    private static float dist(float ax, float ay, float bx, float by) { return (float) Math.hypot(ax - bx, ay - by); }
}

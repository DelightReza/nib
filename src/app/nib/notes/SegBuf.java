package app.nib.notes;

import static android.opengl.GLES30.*;

import java.nio.IntBuffer;

/**
 * Growable instance buffer of ink segments, mirrored on the CPU, uploaded incrementally.
 * The CPU mirror is a plain int[] (floats as raw bits): on ART, writing through ByteBuffer float/int views cost
 * twice as much as the spline maths that makes the segments. It lives in the Java heap like a direct buffer would,
 * so these are kept small: one per page near the screen (Engine.PageInk) plus a few for live ink, selection and
 * overlays — never one for the whole notebook.
 */
final class SegBuf {
    static final int STRIDE = 28; // x0 y0 x1 y1 r0 r1 (floats) + rgba8
    private static final int FL = 7;
    /** Colour of a hidden segment (erased, or lifted into the selection): the vertex shader drops it. */
    static final int HIDDEN = 0;

    private int[] a;
    int count, vbo;
    private int uploaded, gpuCap, dirtyFrom = Integer.MAX_VALUE;
    private final int initial;

    SegBuf() { this(2048); }

    SegBuf(int initialSegs) { initial = Math.max(64, initialSegs); }

    private void alloc(int segs) {
        int[] na = new int[segs * FL];
        if (a != null) System.arraycopy(a, 0, na, 0, count * FL);
        a = na;
    }

    private int capacity() { return a == null ? 0 : a.length / FL; }

    private static int rgba(int argb) {
        return (argb & 0xff000000) | ((argb & 0xff) << 16) | (argb & 0xff00) | ((argb >> 16) & 0xff);
    }

    /** color is Android ARGB. */
    void put(float x0, float y0, float x1, float y1, float r0, float r1, int argb) {
        if (a == null) alloc(initial);
        else if (count + 1 > capacity()) alloc(Math.max(initial, count + (count >> 1) + 64)); // ×1.5: less waste
        int[] d = a;
        int o = count * FL;
        d[o] = Float.floatToRawIntBits(x0);
        d[o + 1] = Float.floatToRawIntBits(y0);
        d[o + 2] = Float.floatToRawIntBits(x1);
        d[o + 3] = Float.floatToRawIntBits(y1);
        d[o + 4] = Float.floatToRawIntBits(r0);
        d[o + 5] = Float.floatToRawIntBits(r1);
        d[o + 6] = rgba(argb);
        count++;
    }

    /** Recolours segments [from, to) in place (HIDDEN hides them); only that range is uploaded again. */
    void recolor(int from, int to, int argb) {
        if (a == null || from >= to) return;
        int c = argb == HIDDEN ? 0 : rgba(argb);
        for (int s = from; s < to; s++) a[s * FL + 6] = c;
        if (from < dirtyFrom) dirtyFrom = from;
    }

    void clear() { count = 0; uploaded = 0; dirtyFrom = Integer.MAX_VALUE; }

    /** CPU read-back: k = 0..5 → x0 y0 x1 y1 r0 r1. */
    float get(int seg, int k) { return Float.intBitsToFloat(a[seg * FL + k]); }

    void truncate(int c) {
        if (c < count) { count = c; uploaded = Math.min(uploaded, c); }
    }

    /** Approximate memory held (CPU mirror + GPU copy), bytes. */
    long bytes() { return (long) capacity() * STRIDE + (long) gpuCap * STRIDE; }

    void sync() {
        if (vbo == 0) {
            int[] v = new int[1];
            glGenBuffers(1, v, 0);
            vbo = v[0];
        }
        int from = Math.min(uploaded, dirtyFrom);
        dirtyFrom = Integer.MAX_VALUE;
        if (count <= from || a == null) { uploaded = count; return; }
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        if (count > gpuCap) {
            gpuCap = capacity();
            glBufferData(GL_ARRAY_BUFFER, gpuCap * STRIDE, IntBuffer.wrap(a, 0, gpuCap * FL), GL_DYNAMIC_DRAW);
        } else {
            glBufferSubData(GL_ARRAY_BUFFER, from * STRIDE, (count - from) * STRIDE, IntBuffer.wrap(a, from * FL, (count - from) * FL));
        }
        uploaded = count;
    }

    /**
     * New window surface: free the GPU copy (it's uploaded again on the next draw). The engine keeps one GL context
     * for its whole life, so the buffer must really be deleted; only forgetting its id leaked the GPU copy of all the
     * ink on every surface change (app switch, tab switch, rotation, split resize). Needs the context current.
     */
    void lost() {
        if (vbo != 0) glDeleteBuffers(1, new int[]{vbo}, 0);
        vbo = 0; gpuCap = 0; uploaded = 0;
    }

    /** Frees GPU and CPU memory for good (a page that scrolled far away). Needs the context current. */
    void free() {
        lost();
        a = null;
        count = 0; dirtyFrom = Integer.MAX_VALUE;
    }
}

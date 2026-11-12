package app.nib.notes;

import static android.opengl.GLES30.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/** Growable instance buffer of ink segments, mirrored on the CPU, uploaded incrementally. */
final class SegBuf {
    static final int STRIDE = 28; // x0 y0 x1 y1 r0 r1 (floats) + rgba8
    private static final int FL = 7;

    private ByteBuffer bb;
    private FloatBuffer fb;
    private IntBuffer ib;
    int count, vbo;
    private int uploaded, gpuCap;

    SegBuf() { alloc(2048); }

    private void alloc(int segs) {
        ByteBuffer nb = ByteBuffer.allocateDirect(segs * STRIDE).order(ByteOrder.nativeOrder());
        if (bb != null) {
            bb.position(0);
            bb.limit(count * STRIDE);
            nb.put(bb);
            nb.position(0);
        }
        bb = nb;
        fb = bb.asFloatBuffer();
        ib = bb.asIntBuffer();
    }

    /** color is Android ARGB. */
    void put(float x0, float y0, float x1, float y1, float r0, float r1, int argb) {
        if ((count + 1) * STRIDE > bb.capacity()) alloc(count * 2);
        int o = count * FL;
        fb.put(o, x0);
        fb.put(o + 1, y0);
        fb.put(o + 2, x1);
        fb.put(o + 3, y1);
        fb.put(o + 4, r0);
        fb.put(o + 5, r1);
        ib.put(o + 6, (argb & 0xff000000) | ((argb & 0xff) << 16) | (argb & 0xff00) | ((argb >> 16) & 0xff));
        count++;
    }

    void clear() { count = 0; uploaded = 0; }

    /** CPU read-back: k = 0..5 → x0 y0 x1 y1 r0 r1. */
    float get(int seg, int k) { return fb.get(seg * FL + k); }

    void truncate(int c) {
        if (c < count) { count = c; uploaded = Math.min(uploaded, c); }
    }

    void sync() {
        if (vbo == 0) {
            int[] v = new int[1];
            glGenBuffers(1, v, 0);
            vbo = v[0];
        }
        if (count <= uploaded) return;
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        if (count > gpuCap) {
            gpuCap = bb.capacity() / STRIDE;
            bb.position(0);
            glBufferData(GL_ARRAY_BUFFER, gpuCap * STRIDE, bb, GL_DYNAMIC_DRAW);
        } else {
            bb.position(uploaded * STRIDE);
            glBufferSubData(GL_ARRAY_BUFFER, uploaded * STRIDE, (count - uploaded) * STRIDE, bb);
        }
        bb.position(0);
        uploaded = count;
    }

    /** Forget GPU state (new GL context). */
    void lost() { vbo = 0; gpuCap = 0; uploaded = 0; }
}

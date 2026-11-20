package app.nib.notes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

/**
 * Keeps the display at its fastest refresh rate while the pen is in use.
 *
 * Android picks the refresh rate from the surfaces that are posting frames. Front-buffer ink posts none (it draws
 * straight into the buffer on screen), so while writing the system saw Nib as idle and ran the panel at its touch
 * boost rate (120 Hz on the Pad 6) or dropped to 60 Hz, whatever the ink surface asked for.
 *
 * This is a 1×1 px transparent surface on top of the window that asks for the fastest rate and posts a frame every
 * vsync while the pen is down or hovering, and for a moment after. It never draws anything visible, costs one tiny
 * frame per refresh, and stops by itself.
 */
final class RateKeeper extends SurfaceView implements SurfaceHolder.Callback, Choreographer.FrameCallback {
    private static final long LINGER_MS = 1000;
    private Surface surface;
    private float rate = 144f;
    private boolean running, odd;
    private long until;

    RateKeeper(Context c) {
        super(c);
        setZOrderOnTop(true);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        getHolder().setFixedSize(1, 1);
        getHolder().addCallback(this);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusable(false);
        setClickable(false);
    }

    /** The fastest rate the display offers (from the editor's mode choice). */
    void setRate(float hz) {
        if (hz <= 0) return;
        rate = hz;
        vote();
    }

    private void vote() {
        if (surface == null || !surface.isValid()) return;
        try {
            surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ALWAYS);
        } catch (Exception ignored) { // older or unusual systems: the window's preferred mode still applies
        }
    }

    /** The pen is touching or hovering: keep the rate up until a moment after the last call. */
    void poke() {
        until = SystemClock.uptimeMillis() + LINGER_MS;
        if (!running && surface != null) {
            running = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (surface == null || !surface.isValid() || SystemClock.uptimeMillis() > until) { running = false; return; }
        try {
            Canvas c = surface.lockHardwareCanvas();
            // one step of alpha on a single pixel: a real frame for the compositor, nothing anyone can see
            c.drawColor(odd ? 0x01000000 : 0x00000000, PorterDuff.Mode.SRC);
            surface.unlockCanvasAndPost(c);
            odd = !odd;
        } catch (Exception e) {
            running = false;
            return;
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override public void surfaceCreated(SurfaceHolder h) { surface = h.getSurface(); vote(); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { surface = h.getSurface(); vote(); }
    @Override public void surfaceDestroyed(SurfaceHolder h) { surface = null; running = false; Choreographer.getInstance().removeFrameCallback(this); }
}

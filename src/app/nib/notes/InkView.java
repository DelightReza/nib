package app.nib.notes;

import android.content.Context;
import android.graphics.PixelFormat;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/** The drawing surface. Does nothing but hand events to the engine as fast as possible. */
final class InkView extends SurfaceView implements SurfaceHolder.Callback {
    private final Engine engine;

    /** Pen (or eraser end) touched down / lifted: lets the editor fade its floating tools while writing. */
    interface PenListener { void pen(boolean down); }
    PenListener penListener;

    InkView(Context c, Engine e) {
        super(c);
        engine = e;
        getHolder().setFormat(PixelFormat.OPAQUE);
        getHolder().addCallback(this);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {}

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        engine.surfaceChanged(holder.getSurface(), w, h);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) { engine.surfaceDestroyed(); }

    @Override
    public boolean onHoverEvent(MotionEvent ev) {
        int tt = ev.getToolType(0);
        if (tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER) engine.hover();
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        long now = System.nanoTime();
        int t0 = ev.getToolType(0);
        if (t0 == MotionEvent.TOOL_TYPE_STYLUS || t0 == MotionEvent.TOOL_TYPE_ERASER) engine.hover();
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            int tt = ev.getToolType(0);
            // Stylus: deliver every sample the moment it arrives instead of batching per vsync.
            if (tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER || tt == MotionEvent.TOOL_TYPE_FINGER) {
                requestUnbufferedDispatch(ev);
            }
        }
        engine.touch(MotionEvent.obtain(ev), now);
        if (penListener != null && (t0 == MotionEvent.TOOL_TYPE_STYLUS || t0 == MotionEvent.TOOL_TYPE_ERASER)) {
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) penListener.pen(true);
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) penListener.pen(false);
        }
        return true;
    }
}

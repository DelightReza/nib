package app.nib.notes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * On-screen ruler (screen space, like a real ruler lying on the tablet). One finger drags it, two fingers move and
 * rotate it (snapping to 15° steps, angle shown while turning). Pen strokes started next to a long edge follow it
 * (Engine.rulerPick). Ticks read in the notebook's drawing scale, so lines measure in real units.
 */
final class Ruler extends View {
    interface Host {
        void rulerChanged();      // position/angle changed: tell the engine
        float pxPerUnit();        // current zoom (screen px per page unit)
        int scale();              // drawing scale denominator (1:N); 0 = none, measure the paper
    }

    static final float MM_PER_UNIT = 210f / Notebook.PW; // page width is A4

    final Host host;
    float cx, cy, angle, halfLen, halfThick;
    private String label;           // length of the line being ruled, or the angle while rotating
    private boolean rotating;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Runnable clearLabel = () -> { label = null; invalidate(); };

    // gesture
    private int ptrA = -1, ptrB = -1;
    private float lastX, lastY, lastAng, lastMidX, lastMidY;

    Ruler(Context c, Host host) {
        super(c);
        this.host = host;
        halfThick = Ui.dp(c, 36);
        setVisibility(GONE);
    }

    void place(int w, int h) {
        cx = w / 2f;
        cy = h * 0.45f;
        halfLen = Math.min(w, h) * 0.42f;
        angle = 0;
    }

    void showLength(float units, boolean done) {
        removeCallbacks(clearLabel);
        label = format(units * MM_PER_UNIT * Math.max(1, host.scale()), host.scale());
        invalidate();
        if (done) postDelayed(clearLabel, 2500);
    }

    /** Real length from millimetres (on paper when there is no scale). */
    static String format(float mm, int scale) {
        if (scale <= 0 || mm < 10) return mm < 10 ? String.format(Locale.US, "%.1f mm", mm) : String.format(Locale.US, "%.1f cm", mm / 10f);
        if (mm >= 1000) return String.format(Locale.US, "%.2f m", mm / 1000f);
        return String.format(Locale.US, "%.0f mm", mm);
    }

    private boolean inside(float x, float y) {
        float dx = x - cx, dy = y - cy, ux = (float) Math.cos(angle), uy = (float) Math.sin(angle);
        return Math.abs(dx * ux + dy * uy) <= halfLen && Math.abs(-dx * uy + dy * ux) <= halfThick;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            int tt = e.getToolType(0);
            if (tt != MotionEvent.TOOL_TYPE_FINGER || !inside(e.getX(), e.getY())) return false; // pen writes through
            ptrA = e.getPointerId(0);
            ptrB = -1;
            lastX = e.getX();
            lastY = e.getY();
            return true;
        }
        if (ptrA < 0) return false;
        switch (a) {
            case MotionEvent.ACTION_POINTER_DOWN:
                if (ptrB < 0) {
                    ptrB = e.getPointerId(e.getActionIndex());
                    syncTwo(e);
                    rotating = true;
                }
                break;
            case MotionEvent.ACTION_MOVE: {
                int ia = e.findPointerIndex(ptrA);
                if (ia < 0) break;
                if (ptrB >= 0 && e.findPointerIndex(ptrB) >= 0) {
                    int ib = e.findPointerIndex(ptrB);
                    float mx = (e.getX(ia) + e.getX(ib)) / 2, my = (e.getY(ia) + e.getY(ib)) / 2;
                    float ang = (float) Math.atan2(e.getY(ib) - e.getY(ia), e.getX(ib) - e.getX(ia));
                    cx += mx - lastMidX;
                    cy += my - lastMidY;
                    float d = ang - lastAng;
                    if (d > Math.PI) d -= 2 * Math.PI;
                    if (d < -Math.PI) d += 2 * Math.PI;
                    angle += d;
                    lastMidX = mx;
                    lastMidY = my;
                    lastAng = ang;
                    // stick to 15° steps when close; show the angle like a protractor
                    float deg = (float) Math.toDegrees(angle), snapped = Math.round(deg / 15f) * 15f;
                    float shown = Math.abs(deg - snapped) < 2.5f ? snapped : deg;
                    float norm = ((shown % 180) + 180) % 180;
                    removeCallbacks(clearLabel);
                    label = String.format(Locale.US, "%.0f°", norm);
                } else {
                    cx += e.getX(ia) - lastX;
                    cy += e.getY(ia) - lastY;
                    lastX = e.getX(ia);
                    lastY = e.getY(ia);
                }
                invalidate();
                host.rulerChanged();
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                int id = e.getPointerId(e.getActionIndex());
                if (id == ptrB || id == ptrA) {
                    if (id == ptrA) ptrA = ptrB;
                    ptrB = -1;
                    int ia = e.findPointerIndex(ptrA);
                    if (ia >= 0) { lastX = e.getX(ia); lastY = e.getY(ia); }
                    endRotate();
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                ptrA = ptrB = -1;
                endRotate();
                break;
        }
        return true;
    }

    private void syncTwo(MotionEvent e) {
        int ia = e.findPointerIndex(ptrA), ib = e.findPointerIndex(ptrB);
        if (ia < 0 || ib < 0) return;
        lastMidX = (e.getX(ia) + e.getX(ib)) / 2;
        lastMidY = (e.getY(ia) + e.getY(ib)) / 2;
        lastAng = (float) Math.atan2(e.getY(ib) - e.getY(ia), e.getX(ib) - e.getX(ia));
    }

    private void endRotate() {
        if (!rotating) return;
        rotating = false;
        float deg = (float) Math.toDegrees(angle), snapped = Math.round(deg / 15f) * 15f;
        if (Math.abs(deg - snapped) < 2.5f) angle = (float) Math.toRadians(snapped);
        invalidate();
        host.rulerChanged();
        postDelayed(clearLabel, 1200);
    }

    @Override protected void onDraw(Canvas c) {
        Context ctx = getContext();
        boolean dk = Ui.dark(ctx);
        float d = getResources().getDisplayMetrics().density;
        c.save();
        c.translate(cx, cy);
        c.rotate((float) Math.toDegrees(angle));
        RectF body = new RectF(-halfLen, -halfThick, halfLen, halfThick);
        p.setStyle(Paint.Style.FILL);
        p.setShadowLayer(8 * d, 0, 2 * d, 0x33000000);
        p.setColor(dk ? 0xe0222428 : 0xe6ffffff);
        c.drawRoundRect(body, 10 * d, 10 * d, p);
        p.clearShadowLayer();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1, 0.8f * d));
        p.setColor(dk ? 0xff3a3e46 : 0xffcfd3da);
        c.drawRoundRect(body, 10 * d, 10 * d, p);

        // ticks: pick a "nice" major step (in real mm) at least ~70 px apart
        int scale = host.scale();
        float realPerPaperMm = Math.max(1, scale);
        float pxPerRealMm = host.pxPerUnit() / MM_PER_UNIT / realPerPaperMm;
        float major = 1;
        float[] nice = {1, 2, 5};
        outer:
        for (float dec = 1; dec < 1e7f; dec *= 10) for (float n : nice) {
            if (n * dec * pxPerRealMm >= 70 * d) { major = n * dec; break outer; }
        }
        float minor = major / 10f;
        if (minor * pxPerRealMm < 6 * d) minor = major / 5f;
        if (minor * pxPerRealMm < 6 * d) minor = major / 2f;
        int ink = dk ? 0xffc9ced8 : 0xff3a4250;
        p.setColor(ink);
        p.setTextSize(11 * d);
        p.setTypeface(Typeface.DEFAULT);
        float len = 2 * halfLen;
        int n = (int) (len / (minor * pxPerRealMm)) + 1;
        for (int i = 0; i < n; i++) {
            float real = i * minor, x = -halfLen + real * pxPerRealMm;
            if (x > halfLen) break;
            boolean isMajor = Math.abs(real / major - Math.round(real / major)) < 1e-3;
            boolean isHalf = !isMajor && Math.abs(real / (major / 2) - Math.round(real / (major / 2))) < 1e-3;
            float t = (isMajor ? 14 : isHalf ? 10 : 6) * d;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(isMajor ? 1.3f * d : 0.9f * d);
            c.drawLine(x, -halfThick, x, -halfThick + t, p);
            c.drawLine(x, halfThick, x, halfThick - t, p);
            if (isMajor && x + 30 * d < halfLen) {
                p.setStyle(Paint.Style.FILL);
                c.drawText(tickLabel(real, major, scale), x + 3 * d, -halfThick + 27 * d, p);
            }
        }
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(10 * d);
        p.setColor(dk ? 0xff8f97a6 : 0xff7a8396);
        String unit = scale <= 0 ? "paper" : "1:" + scale;
        c.drawText(unit, halfLen - p.measureText(unit) - 8 * d, halfThick - 18 * d, p);

        if (label != null) {
            p.setTextSize(14 * d);
            p.setTypeface(Typeface.create(Typeface.DEFAULT, 600, false));
            float tw = p.measureText(label);
            RectF pill = new RectF(-tw / 2 - 10 * d, -11 * d, tw / 2 + 10 * d, 13 * d);
            p.setColor(dk ? 0xff8ab4f8 : 0xff2f6feb);
            c.drawRoundRect(pill, 8 * d, 8 * d, p);
            p.setColor(dk ? 0xff0b1b36 : 0xffffffff);
            c.drawText(label, -tw / 2, 6 * d, p);
        }
        c.restore();
    }

    private static String tickLabel(float realMm, float majorMm, int scale) {
        if (scale <= 0) return majorMm >= 10 ? String.format(Locale.US, "%.0f", realMm / 10f) : String.format(Locale.US, "%.1f", realMm / 10f); // cm on paper
        if (majorMm >= 1000) return String.format(Locale.US, "%.0f m", realMm / 1000f);
        if (majorMm >= 100) return String.format(Locale.US, "%.1f", realMm / 1000f);
        return String.format(Locale.US, "%.0f", realMm);
    }
}

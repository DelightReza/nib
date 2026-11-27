package app.nib.notes;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Lightweight, theme-aware progress popup for long-running import, export, scan, and backup tasks.
 * Thread-safe: update(), setTitle(), setIndeterminate(), and dismiss() may be called from any thread.
 */
final class ProgressPopup {
    interface Listener {
        void onProgress(int done, int total, String detail);
    }

    private final Activity activity;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Dialog dialog;
    private final TextView titleView;
    private final TextView pctView;
    private final TextView detailView;
    private final BarView barView;
    private volatile boolean dismissed;

    private ProgressPopup(Activity activity, String title, String detail) {
        this.activity = activity;
        this.dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        int padH = Ui.dp(activity, 22);
        int padV = Ui.dp(activity, 20);
        card.setPadding(padH, padV, padH, padV);
        GradientDrawable bg = Ui.round(Ui.color(activity, R.color.surface), Ui.dp(activity, 18));
        bg.setStroke(Ui.hairline(activity), Ui.color(activity, R.color.outline));
        card.setBackground(bg);
        card.setElevation(Ui.dp(activity, 12));

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        titleView = Ui.text(activity, title != null ? title : "Working…", 16f,
                Ui.color(activity, R.color.on_surface), Ui.bold());
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(titleView, titleLp);

        pctView = Ui.text(activity, "", 13f, Ui.color(activity, R.color.accent), Ui.medium());
        pctView.setSingleLine(true);
        pctView.setVisibility(View.GONE);
        LinearLayout.LayoutParams pctLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pctLp.setMarginStart(Ui.dp(activity, 12));
        header.addView(pctView, pctLp);

        card.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        barView = new BarView(activity);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(activity, 6));
        barLp.topMargin = Ui.dp(activity, 16);
        card.addView(barView, barLp);

        detailView = Ui.text(activity, detail != null ? detail : "", 13f,
                Ui.color(activity, R.color.on_surface_variant), Ui.font(400));
        detailView.setMaxLines(2);
        detailView.setEllipsize(TextUtils.TruncateAt.END);
        if (detail == null || detail.isEmpty()) detailView.setVisibility(View.GONE);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        detailLp.topMargin = Ui.dp(activity, 10);
        card.addView(detailView, detailLp);

        int maxW = Math.min(Ui.dp(activity, 340),
                Math.max(Ui.dp(activity, 240), activity.getResources().getDisplayMetrics().widthPixels - Ui.dp(activity, 48)));
        dialog.setContentView(card, new LinearLayout.LayoutParams(maxW, LinearLayout.LayoutParams.WRAP_CONTENT));

        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(maxW, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.35f);
        }
    }

    static ProgressPopup show(Activity activity, String title, String detail) {
        ProgressPopup popup = new ProgressPopup(activity, title, detail);
        popup.showNow();
        return popup;
    }

    private void showNow() {
        Runnable r = () -> {
            if (dismissed || activity.isFinishing() || activity.isDestroyed()) return;
            try {
                dialog.show();
            } catch (Exception ignored) { }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else ui.post(r);
    }

    Listener asListener() {
        return this::update;
    }

    void setTitle(String title) {
        if (dismissed) return;
        ui.post(() -> {
            if (dismissed) return;
            titleView.setText(title != null ? title : "");
        });
    }

    void setIndeterminate(String detail) {
        update(0, 0, detail);
    }

    void update(int done, int total, String detail) {
        if (dismissed) return;
        ui.post(() -> {
            if (dismissed) return;
            if (total > 0) {
                int clamped = Math.max(0, Math.min(done, total));
                float frac = clamped / (float) total;
                int pct = Math.round(frac * 100f);
                barView.setDeterminate(frac);
                pctView.setText(pct + "%");
                pctView.setVisibility(View.VISIBLE);
            } else {
                barView.setIndeterminate();
                pctView.setVisibility(View.GONE);
            }
            if (detail != null && !detail.isEmpty()) {
                detailView.setText(detail);
                detailView.setVisibility(View.VISIBLE);
            }
        });
    }

    void dismiss() {
        dismissed = true;
        Runnable r = () -> {
            barView.stop();
            try {
                if (dialog.isShowing()) dialog.dismiss();
            } catch (Exception ignored) { }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else ui.post(r);
    }

    /** Custom rounded pill progress bar supporting smooth determinate & indeterminate modes. */
    private static final class BarView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final int trackColor;
        private final int fillColor;
        private boolean indeterminate = true;
        private float targetFrac = 0f;
        private float shownFrac = 0f;
        private long startMs = SystemClock.uptimeMillis();
        private boolean stopped = false;

        BarView(Context c) {
            super(c);
            trackColor = Ui.dark(c) ? 0x2EFFFFFF : 0x1A000000;
            fillColor = Ui.color(c, R.color.accent);
        }

        void setDeterminate(float frac) {
            boolean wasIndet = indeterminate;
            indeterminate = false;
            targetFrac = Math.max(0f, Math.min(1f, frac));
            if (wasIndet) shownFrac = Math.min(targetFrac, 0.08f);
            postInvalidateOnAnimation();
        }

        void setIndeterminate() {
            if (!indeterminate) {
                indeterminate = true;
                startMs = SystemClock.uptimeMillis();
            }
            postInvalidateOnAnimation();
        }

        void stop() {
            stopped = true;
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0f || h <= 0f) return;
            float r = h * 0.5f;

            paint.setColor(trackColor);
            rect.set(0f, 0f, w, h);
            cv.drawRoundRect(rect, r, r, paint);

            paint.setColor(fillColor);
            if (indeterminate) {
                long elapsed = (SystemClock.uptimeMillis() - startMs) % 1350L;
                float t = elapsed / 1350f;
                // Smooth ease-in-out sweeping pill
                float head = easeOutCubic(Math.min(1f, t * 1.35f));
                float tail = easeInCubic(Math.max(0f, (t - 0.22f) / 0.78f));
                float left = tail * w;
                float right = Math.max(left + h, head * w);
                rect.set(Math.max(0f, left), 0f, Math.min(w, right), h);
                if (rect.right > rect.left) cv.drawRoundRect(rect, r, r, paint);
                if (!stopped && isAttachedToWindow()) postInvalidateOnAnimation();
            } else {
                float diff = targetFrac - shownFrac;
                if (Math.abs(diff) > 0.004f) {
                    shownFrac += diff * 0.28f;
                    if (!stopped && isAttachedToWindow()) postInvalidateOnAnimation();
                } else {
                    shownFrac = targetFrac;
                }
                float fillW = shownFrac <= 0f ? 0f : Math.max(h, w * shownFrac);
                if (fillW > 0f) {
                    rect.set(0f, 0f, Math.min(w, fillW), h);
                    cv.drawRoundRect(rect, r, r, paint);
                }
            }
        }

        private static float easeOutCubic(float t) {
            float u = 1f - t;
            return 1f - u * u * u;
        }

        private static float easeInCubic(float t) {
            return t * t * t;
        }
    }
}

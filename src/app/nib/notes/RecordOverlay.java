package app.nib.notes;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/**
 * Floating, draggable recording HUD capsule + Recording Settings dialog.
 *
 * Added to {@link EditorActivity}'s root {@link FrameLayout} above the GL {@link android.view.SurfaceView},
 * so it stays accessible while writing and is never captured by {@link CanvasRecorder}.
 */
final class RecordOverlay {

    interface Host {
        CanvasRecorder.Settings getSettings();
        void onSettingsChanged(CanvasRecorder.Settings s);
        int getCanvasWidth();
        int getCanvasHeight();
        void onStartRecording();
        void onPauseResume();
        void onStopRecording();
        void onToggleMicMute();
        void onCloseOverlay();
    }

    private static final int REC_RED = 0xFFDC2626;
    private static final int REC_AMBER = 0xFFD97706;

    private final Activity act;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());

    final LinearLayout container;
    private final LinearLayout readyGroup;
    private final LinearLayout activeGroup;
    private final LinearLayout savingGroup;

    private final TextView presetChip;
    private final View statusDot;
    private final TextView timerTv;
    private final TextView pausedBadge;
    private final ImageView micBtn;
    private final ImageView pauseBtn;

    private boolean isRecording;
    private boolean isPaused;
    private boolean isSaving;
    private boolean hasAudio;
    private boolean micMuted;
    private boolean positioned;
    private boolean pulseDim;

    private CanvasRecorder boundRecorder;
    private AlertDialog settingsDialog;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!isRecording) return;
            CanvasRecorder r = boundRecorder;
            if (r != null) {
                timerTv.setText(formatDuration(r.elapsedMs()));
            }
            if (!isPaused) {
                pulseDim = !pulseDim;
                statusDot.animate().alpha(pulseDim ? 0.35f : 1f).setDuration(420).start();
            } else {
                statusDot.animate().cancel();
                statusDot.setAlpha(1f);
            }
            ui.postDelayed(this, 250);
        }
    };

    RecordOverlay(Activity act, Host host) {
        this.act = act;
        this.host = host;

        container = new LinearLayout(act);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);
        container.setPadding(Ui.dp(act, 6), Ui.dp(act, 5), Ui.dp(act, 6), Ui.dp(act, 5));
        Ui.capsule(container, act, 26);
        container.setElevation(Ui.dp(act, 10));
        container.setVisibility(View.GONE);

        // Drag grip handle
        ImageView grip = new ImageView(act);
        grip.setImageResource(R.drawable.ic_grip);
        grip.setScaleType(ImageView.ScaleType.CENTER);
        grip.setImageTintList(ColorStateList.valueOf(Ui.color(act, R.color.on_surface_variant)));
        grip.setContentDescription("Drag recording bar");
        grip.setTooltipText("Drag to move");
        grip.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(act, 24), Ui.dp(act, 38)));
        container.addView(grip);

        attachDrag(container);
        attachDrag(grip);

        // ---- Ready controls (Start + Preset + Settings + Close) ----
        readyGroup = new LinearLayout(act);
        readyGroup.setOrientation(LinearLayout.HORIZONTAL);
        readyGroup.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout startBtn = new LinearLayout(act);
        startBtn.setOrientation(LinearLayout.HORIZONTAL);
        startBtn.setGravity(Gravity.CENTER_VERTICAL);
        startBtn.setPadding(Ui.dp(act, 12), 0, Ui.dp(act, 14), 0);
        startBtn.setBackground(Ui.ripple(act, Ui.round(REC_RED, Ui.dp(act, 18)), Ui.dp(act, 18)));
        startBtn.setContentDescription("Start recording");
        View recDot = new View(act);
        recDot.setBackground(Ui.round(Color.WHITE, Ui.dp(act, 5)));
        LinearLayout.LayoutParams rdp = new LinearLayout.LayoutParams(Ui.dp(act, 9), Ui.dp(act, 9));
        rdp.rightMargin = Ui.dp(act, 7);
        startBtn.addView(recDot, rdp);
        startBtn.addView(Ui.text(act, "Record", 13.5f, Color.WHITE, Ui.bold()));
        startBtn.setOnClickListener(v -> host.onStartRecording());
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(-2, Ui.dp(act, 36));
        sbp.rightMargin = Ui.dp(act, 6);
        readyGroup.addView(startBtn, sbp);

        presetChip = Ui.text(act, "", 12.5f, Ui.color(act, R.color.on_surface_variant), Ui.medium());
        presetChip.setGravity(Gravity.CENTER_VERTICAL);
        presetChip.setPadding(Ui.dp(act, 10), 0, Ui.dp(act, 10), 0);
        presetChip.setBackground(Ui.ripple(act,
                Ui.round(Ui.color(act, R.color.surface_variant), Ui.dp(act, 16)), Ui.dp(act, 16)));
        presetChip.setContentDescription("Recording preset · tap for settings");
        presetChip.setOnClickListener(v -> showSettings());
        LinearLayout.LayoutParams pcp = new LinearLayout.LayoutParams(-2, Ui.dp(act, 34));
        pcp.rightMargin = Ui.dp(act, 2);
        readyGroup.addView(presetChip, pcp);

        ImageView gearBtn = pillIcon(R.drawable.ic_settings, "Recording settings", Ui.color(act, R.color.icon), Color.TRANSPARENT);
        gearBtn.setOnClickListener(v -> showSettings());
        readyGroup.addView(gearBtn);

        ImageView closeBtn = pillIcon(R.drawable.ic_close, "Close recorder", Ui.color(act, R.color.icon), Color.TRANSPARENT);
        closeBtn.setOnClickListener(v -> host.onCloseOverlay());
        readyGroup.addView(closeBtn);

        container.addView(readyGroup);

        // ---- Active recording controls (Dot + Timer + Mic + Pause/Resume + Stop) ----
        activeGroup = new LinearLayout(act);
        activeGroup.setOrientation(LinearLayout.HORIZONTAL);
        activeGroup.setGravity(Gravity.CENTER_VERTICAL);
        activeGroup.setVisibility(View.GONE);

        statusDot = new View(act);
        statusDot.setBackground(Ui.round(REC_RED, Ui.dp(act, 5)));
        LinearLayout.LayoutParams sdp = new LinearLayout.LayoutParams(Ui.dp(act, 10), Ui.dp(act, 10));
        sdp.leftMargin = Ui.dp(act, 4);
        sdp.rightMargin = Ui.dp(act, 7);
        activeGroup.addView(statusDot, sdp);

        timerTv = Ui.text(act, "00:00", 14.5f, Ui.color(act, R.color.on_surface), Ui.bold());
        timerTv.setFontFeatureSettings("tnum");
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.rightMargin = Ui.dp(act, 6);
        activeGroup.addView(timerTv, tp);

        pausedBadge = Ui.text(act, "PAUSED", 10.5f, REC_AMBER, Ui.bold());
        pausedBadge.setPadding(Ui.dp(act, 6), Ui.dp(act, 2), Ui.dp(act, 6), Ui.dp(act, 2));
        pausedBadge.setBackground(Ui.round(0x26D97706, Ui.dp(act, 8)));
        pausedBadge.setVisibility(View.GONE);
        LinearLayout.LayoutParams pbp = new LinearLayout.LayoutParams(-2, -2);
        pbp.rightMargin = Ui.dp(act, 6);
        activeGroup.addView(pausedBadge, pbp);

        micBtn = pillIcon(R.drawable.ic_mic, "Mute microphone", Ui.color(act, R.color.icon), Color.TRANSPARENT);
        micBtn.setOnClickListener(v -> host.onToggleMicMute());
        activeGroup.addView(micBtn);

        pauseBtn = pillIcon(R.drawable.ic_pause, "Pause recording", Ui.color(act, R.color.on_surface),
                Ui.color(act, R.color.surface_variant));
        pauseBtn.setOnClickListener(v -> host.onPauseResume());
        LinearLayout.LayoutParams pzp = new LinearLayout.LayoutParams(Ui.dp(act, 38), Ui.dp(act, 38));
        pzp.leftMargin = Ui.dp(act, 4);
        pzp.rightMargin = Ui.dp(act, 6);
        activeGroup.addView(pauseBtn, pzp);

        ImageView stopBtn = pillIcon(R.drawable.ic_stop, "Stop recording", Color.WHITE, REC_RED);
        stopBtn.setOnClickListener(v -> host.onStopRecording());
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(Ui.dp(act, 38), Ui.dp(act, 38));
        stp.rightMargin = Ui.dp(act, 2);
        activeGroup.addView(stopBtn, stp);

        container.addView(activeGroup);

        // ---- Saving indicator ----
        savingGroup = new LinearLayout(act);
        savingGroup.setOrientation(LinearLayout.HORIZONTAL);
        savingGroup.setGravity(Gravity.CENTER_VERTICAL);
        savingGroup.setPadding(Ui.dp(act, 6), 0, Ui.dp(act, 10), 0);
        savingGroup.setVisibility(View.GONE);
        savingGroup.addView(Ui.text(act, "Finalizing video…", 13.5f, Ui.color(act, R.color.on_surface), Ui.medium()));
        container.addView(savingGroup);

        refreshPreset();
    }

    private ImageView pillIcon(int res, String desc, int tint, int bg) {
        ImageView iv = new ImageView(act);
        iv.setImageResource(res);
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setImageTintList(ColorStateList.valueOf(tint));
        iv.setContentDescription(desc);
        iv.setTooltipText(desc);
        iv.setBackground(Ui.ripple(act, bg == Color.TRANSPARENT ? null : Ui.round(bg, Ui.dp(act, 19)), Ui.dp(act, 19)));
        iv.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(act, 38), Ui.dp(act, 38)));
        return iv;
    }

    /** Places the floating capsule near top-center below the editor top bar on first show. */
    void show(FrameLayout parent, int topMarginPx) {
        if (container.getParent() == null) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            parent.addView(container, lp);
        }
        refreshPreset();
        container.setVisibility(View.VISIBLE);
        container.bringToFront();
        if (!positioned) {
            container.post(() -> {
                if (container.getParent() instanceof View) {
                    View p = (View) container.getParent();
                    float cx = Math.max(Ui.dp(act, 12), (p.getWidth() - container.getWidth()) / 2f);
                    float cy = Math.max(Ui.dp(act, 12), topMarginPx);
                    container.setX(cx);
                    container.setY(cy);
                    positioned = true;
                }
            });
        } else {
            clampIntoParent();
        }
    }

    void hide() {
        ui.removeCallbacks(tick);
        container.setVisibility(View.GONE);
    }

    boolean isVisible() { return container.getVisibility() == View.VISIBLE; }
    boolean isSaving() { return isSaving; }

    void refreshPreset() {
        CanvasRecorder.Settings s = host.getSettings();
        presetChip.setText(s.shortSummary());
    }

    void setReady() {
        boundRecorder = null;
        isRecording = false;
        isPaused = false;
        isSaving = false;
        ui.removeCallbacks(tick);
        readyGroup.setVisibility(View.VISIBLE);
        activeGroup.setVisibility(View.GONE);
        savingGroup.setVisibility(View.GONE);
        refreshPreset();
        clampLater();
    }

    void setRecording(CanvasRecorder recorder, boolean paused) {
        boundRecorder = recorder;
        isRecording = true;
        isPaused = paused;
        isSaving = false;
        hasAudio = recorder != null && recorder.hasAudio();
        micMuted = recorder != null && recorder.isMicMuted();

        readyGroup.setVisibility(View.GONE);
        activeGroup.setVisibility(View.VISIBLE);
        savingGroup.setVisibility(View.GONE);

        statusDot.setBackground(Ui.round(paused ? REC_AMBER : REC_RED, Ui.dp(act, 5)));
        pausedBadge.setVisibility(paused ? View.VISIBLE : View.GONE);
        pauseBtn.setImageResource(paused ? R.drawable.ic_play : R.drawable.ic_pause);
        pauseBtn.setContentDescription(paused ? "Resume recording" : "Pause recording");
        pauseBtn.setTooltipText(paused ? "Resume recording" : "Pause recording");
        int pauseBg = paused ? 0x26D97706 : Ui.color(act, R.color.surface_variant);
        int pauseFg = paused ? REC_AMBER : Ui.color(act, R.color.on_surface);
        pauseBtn.setImageTintList(ColorStateList.valueOf(pauseFg));
        pauseBtn.setBackground(Ui.ripple(act, Ui.round(pauseBg, Ui.dp(act, 19)), Ui.dp(act, 19)));

        updateMicIcon();
        if (recorder != null) timerTv.setText(formatDuration(recorder.elapsedMs()));
        ui.removeCallbacks(tick);
        ui.post(tick);
        clampLater();
    }

    void setMicMuted(boolean muted) {
        micMuted = muted;
        updateMicIcon();
    }

    private void updateMicIcon() {
        if (!hasAudio) {
            micBtn.setVisibility(View.GONE);
            return;
        }
        micBtn.setVisibility(View.VISIBLE);
        micBtn.setImageResource(micMuted ? R.drawable.ic_mic_off : R.drawable.ic_mic);
        micBtn.setContentDescription(micMuted ? "Unmute microphone" : "Mute microphone");
        micBtn.setTooltipText(micMuted ? "Unmute microphone" : "Mute microphone");
        int fg = micMuted ? REC_RED : Ui.color(act, R.color.icon);
        int bg = micMuted ? 0x1FDC2626 : Color.TRANSPARENT;
        micBtn.setImageTintList(ColorStateList.valueOf(fg));
        micBtn.setBackground(Ui.ripple(act, bg == Color.TRANSPARENT ? null : Ui.round(bg, Ui.dp(act, 19)), Ui.dp(act, 19)));
    }

    void setSaving() {
        isRecording = false;
        isPaused = false;
        isSaving = true;
        ui.removeCallbacks(tick);
        readyGroup.setVisibility(View.GONE);
        activeGroup.setVisibility(View.GONE);
        savingGroup.setVisibility(View.VISIBLE);
        clampLater();
    }

    void dismissDialogs() {
        ui.removeCallbacks(tick);
        if (settingsDialog != null) { settingsDialog.dismiss(); settingsDialog = null; }
    }

    void clampLater() {
        container.post(this::clampIntoParent);
    }

    private void clampIntoParent() {
        if (!(container.getParent() instanceof View)) return;
        View p = (View) container.getParent();
        int m = Ui.dp(act, 10);
        float maxX = Math.max(m, p.getWidth() - container.getWidth() - m);
        float maxY = Math.max(m, p.getHeight() - container.getHeight() - m);
        container.setX(Math.max(m, Math.min(maxX, container.getX())));
        container.setY(Math.max(m, Math.min(maxY, container.getY())));
    }

    private void attachDrag(View handle) {
        final int slop = ViewConfiguration.get(act).getScaledTouchSlop();
        final float[] down = new float[4]; // rawX, rawY, startX, startY
        final boolean[] dragging = new boolean[1];
        handle.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = ev.getRawX();
                    down[1] = ev.getRawY();
                    down[2] = container.getX();
                    down[3] = container.getY();
                    dragging[0] = false;
                    return v != container;
                case MotionEvent.ACTION_MOVE:
                    float dx = ev.getRawX() - down[0];
                    float dy = ev.getRawY() - down[1];
                    if (!dragging[0] && Math.hypot(dx, dy) > slop) dragging[0] = true;
                    if (dragging[0]) {
                        container.setX(down[2] + dx);
                        container.setY(down[3] + dy);
                        clampIntoParent();
                        return true;
                    }
                    return false;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging[0]) {
                        clampIntoParent();
                        dragging[0] = false;
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        });
    }

    // ---- Recording Settings Dialog -------------------------------------------------------------

    void showSettings() {
        if (isRecording || isSaving) return;
        if (settingsDialog != null) { settingsDialog.dismiss(); settingsDialog = null; }
        final CanvasRecorder.Settings s = host.getSettings();
        final int rawW = host.getCanvasWidth(), rawH = host.getCanvasHeight();
        final int cw = rawW > 0 ? rawW : 1080;
        final int ch = rawH > 0 ? rawH : 1920;

        ScrollView scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(act, 20);
        body.setPadding(p, Ui.dp(act, 18), p, Ui.dp(act, 14));
        scroll.addView(body, new ViewGroup.LayoutParams(-1, -2));

        body.addView(Ui.text(act, "Recording settings", 18, Ui.color(act, R.color.on_surface), Ui.bold()));

        final TextView specTv = Ui.text(act, s.detailSummary(cw, ch), 12.5f, Ui.color(act, R.color.accent), Ui.medium());
        specTv.setPadding(0, Ui.dp(act, 4), 0, Ui.dp(act, 2));
        body.addView(specTv);

        TextView noteTv = Ui.text(act, "Records only the notes canvas — toolbars, tabs, and this overlay are excluded.",
                12f, Ui.color(act, R.color.on_surface_variant), null);
        noteTv.setPadding(0, 0, 0, Ui.dp(act, 6));
        body.addView(noteTv);

        final Runnable notify = () -> {
            host.onSettingsChanged(s);
            specTv.setText(s.detailSummary(cw, ch));
            refreshPreset();
        };

        // Resolution
        addSectionLabel(body, "Resolution");
        body.addView(Ui.segmented(act, CanvasRecorder.RES_LABELS, s.res, idx -> {
            s.res = idx;
            notify.run();
        }));

        // Orientation / Aspect
        addSectionLabel(body, "Orientation");
        body.addView(Ui.segmented(act, CanvasRecorder.ORIENT_LABELS, s.orient, idx -> {
            s.orient = idx;
            notify.run();
        }));

        // Frame rate
        addSectionLabel(body, "Frame rate");
        body.addView(Ui.segmented(act, new String[]{"30 fps", "60 fps"}, s.fps == 60 ? 1 : 0, idx -> {
            s.fps = CanvasRecorder.FPS_OPTIONS[idx];
            notify.run();
        }));

        // Video bitrate
        addSectionLabel(body, "Video bitrate");
        String[] vbLabels = new String[CanvasRecorder.VIDEO_BITRATES_MBPS.length];
        int vbSel = 1;
        for (int i = 0; i < CanvasRecorder.VIDEO_BITRATES_MBPS.length; i++) {
            vbLabels[i] = CanvasRecorder.VIDEO_BITRATES_MBPS[i] + "M";
            if (CanvasRecorder.VIDEO_BITRATES_MBPS[i] == s.videoBitrateMbps) vbSel = i;
        }
        body.addView(Ui.segmented(act, vbLabels, vbSel, idx -> {
            s.videoBitrateMbps = CanvasRecorder.VIDEO_BITRATES_MBPS[idx];
            notify.run();
        }));

        // Framing
        addSectionLabel(body, "Framing");
        body.addView(Ui.segmented(act, new String[]{"Fit page", "Fill frame"}, s.fillFrame ? 1 : 0, idx -> {
            s.fillFrame = idx == 1;
            notify.run();
        }));

        // Divider before audio
        View div = new View(act);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, Ui.hairline(act));
        dlp.topMargin = Ui.dp(act, 14);
        dlp.bottomMargin = Ui.dp(act, 4);
        div.setBackgroundColor(Ui.color(act, R.color.outline));
        body.addView(div, dlp);

        // Audio bitrate group (shown when mic enabled)
        final LinearLayout audioBitrateBox = new LinearLayout(act);
        audioBitrateBox.setOrientation(LinearLayout.VERTICAL);
        addSectionLabel(audioBitrateBox, "Audio bitrate");
        String[] abLabels = new String[CanvasRecorder.AUDIO_BITRATES_KBPS.length];
        int abSel = 1;
        for (int i = 0; i < CanvasRecorder.AUDIO_BITRATES_KBPS.length; i++) {
            abLabels[i] = CanvasRecorder.AUDIO_BITRATES_KBPS[i] + "k";
            if (CanvasRecorder.AUDIO_BITRATES_KBPS[i] == s.audioBitrateKbps) abSel = i;
        }
        audioBitrateBox.addView(Ui.segmented(act, abLabels, abSel, idx -> {
            s.audioBitrateKbps = CanvasRecorder.AUDIO_BITRATES_KBPS[idx];
            notify.run();
        }));
        audioBitrateBox.setVisibility(s.recordAudio ? View.VISIBLE : View.GONE);

        body.addView(Ui.switchRow(act, "Record microphone audio", "Capture voice narration along with your notes",
                s.recordAudio, on -> {
                    s.recordAudio = on;
                    audioBitrateBox.setVisibility(on ? View.VISIBLE : View.GONE);
                    notify.run();
                }));
        body.addView(audioBitrateBox);

        // Done button
        TextView done = Ui.button(act, "Done", 0, true);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-1, Ui.dp(act, 44));
        btnLp.topMargin = Ui.dp(act, 18);
        done.setOnClickListener(v -> {
            if (settingsDialog != null) { settingsDialog.dismiss(); settingsDialog = null; }
        });
        body.addView(done, btnLp);

        AlertDialog d = new AlertDialog.Builder(act).setView(scroll).create();
        d.show();
        styleDialogWindow(d);
        settingsDialog = d;
    }

    private void addSectionLabel(LinearLayout parent, String label) {
        TextView tv = Ui.text(act, label, 12.5f, Ui.color(act, R.color.on_surface_variant), Ui.medium());
        tv.setPadding(0, Ui.dp(act, 12), 0, Ui.dp(act, 6));
        parent.addView(tv);
    }

    private void styleDialogWindow(AlertDialog d) {
        if (d.getWindow() == null) return;
        GradientDrawable bg = Ui.round(Ui.color(act, R.color.surface), Ui.dp(act, 18));
        bg.setStroke(Ui.hairline(act), Ui.color(act, R.color.outline));
        d.getWindow().setBackgroundDrawable(bg);
        int maxW = Math.min(Ui.dp(act, 400), act.getResources().getDisplayMetrics().widthPixels - Ui.dp(act, 32));
        d.getWindow().setLayout(maxW, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static String formatDuration(long ms) {
        long sec = Math.max(0L, ms / 1000L);
        long h = sec / 3600L;
        long m = (sec % 3600L) / 60L;
        long s = sec % 60L;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%02d:%02d", m, s);
    }
}

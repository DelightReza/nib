package app.nib.notes;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Hardware-accelerated canvas-only MP4 video + AAC microphone recorder.
 *
 * Video frames are pushed directly from OpenGL ES ({@link Gl}) onto a {@link MediaCodec} input
 * {@link Surface}, so toolbars, top bars, overlays, and Android system bars are never captured.
 * Supports pause/resume with a single shared monotonic clock and optional live mic mute.
 */
final class CanvasRecorder {

    static final int RES_720 = 0, RES_1080 = 1, RES_1440 = 2, RES_CANVAS = 3;
    static final int ORIENT_AUTO = 0, ORIENT_LANDSCAPE = 1, ORIENT_PORTRAIT = 2, ORIENT_SQUARE = 3;

    static final String[] RES_LABELS = {"720p", "1080p", "1440p", "Canvas"};
    static final String[] ORIENT_LABELS = {"Auto", "16:9", "9:16", "1:1"};
    static final int[] FPS_OPTIONS = {30, 60};
    static final int[] VIDEO_BITRATES_MBPS = {4, 8, 12, 16, 24};
    static final int[] AUDIO_BITRATES_KBPS = {96, 128, 192, 320};

    private static final int MAX_DIM = 2560;
    private static final int MIN_DIM = 256;
    private static final int MAX_PENDING_PACKETS = 320;
    private static final int MAX_PENDING_BYTES = 16 * 1024 * 1024;

    static final class Settings {
        int res = RES_1080;
        int orient = ORIENT_AUTO;
        int fps = 30;
        int videoBitrateMbps = 8;
        boolean fillFrame = false;
        boolean recordAudio = true;
        int audioBitrateKbps = 128;

        static Settings load(SharedPreferences p) {
            Settings s = new Settings();
            if (p == null) return s;
            s.res = clamp(p.getInt("recRes", RES_1080), RES_720, RES_CANVAS);
            s.orient = clamp(p.getInt("recOrient", ORIENT_AUTO), ORIENT_AUTO, ORIENT_SQUARE);
            s.fps = p.getInt("recFps", 30) == 60 ? 60 : 30;
            int vb = p.getInt("recVBitrate", 8);
            s.videoBitrateMbps = contains(VIDEO_BITRATES_MBPS, vb) ? vb : 8;
            s.fillFrame = p.getBoolean("recFill", false);
            s.recordAudio = p.getBoolean("recAudio", true);
            int ab = p.getInt("recABitrate", 128);
            s.audioBitrateKbps = contains(AUDIO_BITRATES_KBPS, ab) ? ab : 128;
            return s;
        }

        void save(SharedPreferences p) {
            if (p == null) return;
            p.edit()
                    .putInt("recRes", res)
                    .putInt("recOrient", orient)
                    .putInt("recFps", fps)
                    .putInt("recVBitrate", videoBitrateMbps)
                    .putBoolean("recFill", fillFrame)
                    .putBoolean("recAudio", recordAudio)
                    .putInt("recABitrate", audioBitrateKbps)
                    .apply();
        }

        /** Computes 16-aligned encoder dimensions for the given canvas size. */
        int[] computeDimensions(int canvasW, int canvasH) {
            int cw = Math.max(MIN_DIM, canvasW);
            int ch = Math.max(MIN_DIM, canvasH);
            int shortTarget = res == RES_720 ? 720 : res == RES_1440 ? 1440 : 1080;
            int w, h;
            if (orient == ORIENT_LANDSCAPE) {
                if (res == RES_CANVAS) {
                    int base = Math.min(MAX_DIM, Math.max(cw, Math.round(ch * 16f / 9f)));
                    w = base;
                    h = Math.round(base * 9f / 16f);
                } else {
                    h = shortTarget;
                    w = Math.round(shortTarget * 16f / 9f);
                }
            } else if (orient == ORIENT_PORTRAIT) {
                if (res == RES_CANVAS) {
                    int base = Math.min(MAX_DIM, Math.max(ch, Math.round(cw * 16f / 9f)));
                    h = base;
                    w = Math.round(base * 9f / 16f);
                } else {
                    w = shortTarget;
                    h = Math.round(shortTarget * 16f / 9f);
                }
            } else if (orient == ORIENT_SQUARE) {
                int side = res == RES_CANVAS ? Math.min(MAX_DIM, Math.max(cw, ch)) : shortTarget;
                w = side;
                h = side;
            } else {
                // ORIENT_AUTO: preserve the canvas aspect ratio
                if (res == RES_CANVAS) {
                    w = cw;
                    h = ch;
                } else {
                    float scale = shortTarget / (float) Math.min(cw, ch);
                    w = Math.round(cw * scale);
                    h = Math.round(ch * scale);
                }
            }
            if (Math.max(w, h) > MAX_DIM) {
                float s = MAX_DIM / (float) Math.max(w, h);
                w = Math.round(w * s);
                h = Math.round(h * s);
            }
            return new int[]{align16(w), align16(h)};
        }

        String shortSummary() {
            return RES_LABELS[res] + " · " + fps + "fps · " + (recordAudio ? "Mic" : "Silent");
        }

        String detailSummary(int canvasW, int canvasH) {
            int[] d = computeDimensions(canvasW, canvasH);
            return d[0] + "×" + d[1] + " · " + fps + " fps · " + videoBitrateMbps + " Mbps · "
                    + (recordAudio ? ("Mic " + audioBitrateKbps + " kbps") : "No audio");
        }

        private static int align16(int v) {
            int a = ((v + 8) & ~15);
            return Math.max(MIN_DIM, Math.min(MAX_DIM, a));
        }

        private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

        private static boolean contains(int[] a, int v) {
            for (int x : a) if (x == v) return true;
            return false;
        }
    }

    static final class Result {
        final Uri savedUri;
        final String displayName;
        final long durationMs;
        final int width;
        final int height;
        final boolean hasAudio;
        final String error;
        final String notice;

        Result(Uri savedUri, String displayName, long durationMs, int width, int height,
               boolean hasAudio, String error, String notice) {
            this.savedUri = savedUri;
            this.displayName = displayName;
            this.durationMs = durationMs;
            this.width = width;
            this.height = height;
            this.hasAudio = hasAudio;
            this.error = error;
            this.notice = notice;
        }
    }

    private static final class PendingPacket {
        final boolean isAudio;
        final byte[] data;
        final long ptsUs;
        final int flags;

        PendingPacket(boolean isAudio, byte[] data, long ptsUs, int flags) {
            this.isAudio = isAudio;
            this.data = data;
            this.ptsUs = ptsUs;
            this.flags = flags;
        }
    }

    private final Context appCtx;
    private final File outFile;
    private final boolean fillFrame;
    private int width, height, fps;
    private boolean audioEnabled;
    private int audioSampleRate = 48000;
    private String notice;

    private MediaCodec videoCodec;
    private Surface inputSurface;
    private MediaCodec audioCodec;
    private volatile AudioRecord audioRecord;
    private Thread audioThread;
    private Mp4Muxer muxer;

    private final Object clockLock = new Object();
    private final Object muxLock = new Object();
    private final Object videoDrainLock = new Object();

    private volatile boolean active;
    private volatile boolean paused;
    private volatile boolean stopping;
    private volatile boolean micMuted;

    private long startNs;
    private long pauseStartedNs;
    private long pausedTotalNs;
    private long lastVideoPtsNs = -1L;
    private volatile long lastFrameWallNs;

    private MediaFormat videoFormat;
    private MediaFormat audioFormat;
    private int videoTrack = -1;
    private int audioTrack = -1;
    private boolean muxerStarted;
    private boolean muxerClosed;
    private long lastMuxVideoPtsUs = -1L;
    private long lastMuxAudioPtsUs = -1L;
    private int videoFramesMuxed;
    private int audioFramesMuxed;
    private final ArrayList<PendingPacket> pendingVideo = new ArrayList<>();
    private final ArrayList<PendingPacket> pendingAudio = new ArrayList<>();
    private int pendingBytes;

    private final MediaCodec.BufferInfo videoBufInfo = new MediaCodec.BufferInfo();
    private final MediaCodec.BufferInfo audioBufInfo = new MediaCodec.BufferInfo();

    private CanvasRecorder(Context appCtx, File outFile, int width, int height, int fps, boolean fillFrame, boolean audioEnabled) {
        this.appCtx = appCtx;
        this.outFile = outFile;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.fillFrame = fillFrame;
        this.audioEnabled = audioEnabled;
    }

    /** Creates and starts a new recorder ready for {@link Engine#setRecorder(CanvasRecorder)}. */
    static CanvasRecorder start(Context ctx, String notebookTitle, int canvasW, int canvasH,
                                Settings settings, boolean allowAudio) throws IOException {
        int[] dims = settings.computeDimensions(canvasW, canvasH);
        String stamp = new SimpleDateFormat("yyyy-MM-dd HHmmss", Locale.US).format(new Date());
        String base = Exporter.safeName(notebookTitle == null ? "Notebook" : notebookTitle);
        File file = new File(Exporter.exportDir(ctx), base + " " + stamp + ".mp4");
        int n = 2;
        while (file.exists()) {
            file = new File(Exporter.exportDir(ctx), base + " " + stamp + " (" + n + ").mp4");
            n++;
        }

        CanvasRecorder rec = new CanvasRecorder(
                ctx.getApplicationContext(), file, dims[0], dims[1], settings.fps, settings.fillFrame,
                settings.recordAudio && allowAudio);
        try {
            rec.initEncoders(settings);
        } catch (Exception e) {
            rec.cleanupQuietly();
            file.delete();
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Could not start video recorder: " + e.getMessage(), e);
        }
        return rec;
    }

    private void initEncoders(Settings settings) throws IOException {
        int bitrate = settings.videoBitrateMbps * 1_000_000;
        try {
            configureVideo(width, height, fps, bitrate);
        } catch (Exception first) {
            Log.w(Gl.TAG, "Video encoder config " + width + "x" + height + "@" + fps + " failed, retrying safe fallback", first);
            releaseVideoOnly();
            int fbW = width, fbH = height;
            if (Math.max(fbW, fbH) > 1920) {
                float s = 1920f / Math.max(fbW, fbH);
                fbW = Settings.align16(Math.round(fbW * s));
                fbH = Settings.align16(Math.round(fbH * s));
            }
            int fbFps = 30;
            int fbBitrate = Math.min(bitrate, 8_000_000);
            configureVideo(fbW, fbH, fbFps, fbBitrate);
            width = fbW;
            height = fbH;
            fps = fbFps;
            notice = "Adjusted to " + width + "×" + height + " @ 30 fps for this device";
        }

        if (audioEnabled) {
            if (!initAudio(settings.audioBitrateKbps * 1000)) {
                audioEnabled = false;
                if (notice == null) notice = "Microphone unavailable · recorded video only";
            }
        }

        // MediaMuxer is created lazily in startMuxerLocked() once valid formats and samples exist.
        synchronized (clockLock) {
            startNs = System.nanoTime();
            pauseStartedNs = 0L;
            pausedTotalNs = 0L;
            lastVideoPtsNs = -1L;
        }
        active = true;
        paused = false;
        stopping = false;

        if (audioEnabled && audioRecord != null && audioCodec != null) {
            startAudioLoop();
        }
    }

    private void configureVideo(int w, int h, int targetFps, int bitrate) throws IOException {
        MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, targetFps);
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        videoCodec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = videoCodec.createInputSurface();
        videoCodec.start();
    }

    private void releaseVideoOnly() {
        if (inputSurface != null) {
            try { inputSurface.release(); } catch (Exception ignored) {}
            inputSurface = null;
        }
        if (videoCodec != null) {
            try { videoCodec.stop(); } catch (Exception ignored) {}
            try { videoCodec.release(); } catch (Exception ignored) {}
            videoCodec = null;
        }
    }

    private boolean initAudio(int bitrateBps) {
        int[] rates = {48000, 44100};
        int[] bitrates = bitrateBps > 192_000 ? new int[]{bitrateBps, 192_000} : new int[]{bitrateBps};
        for (int bps : bitrates) {
            for (int rate : rates) {
                int minBuf = AudioRecord.getMinBufferSize(
                        rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (minBuf <= 0) continue;
                int bufBytes = Math.max(minBuf * 2, 8192);
                AudioRecord ar = null;
                MediaCodec ac = null;
                try {
                    ar = new AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            rate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufBytes);
                    if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                        ar.release();
                        continue;
                    }
                    MediaFormat af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1);
                    af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                    af.setInteger(MediaFormat.KEY_BIT_RATE, bps);
                    af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.max(16384, bufBytes));
                    ac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                    ac.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    ac.start();
                    ar.startRecording();
                    if (ar.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                        throw new IllegalStateException("AudioRecord did not enter recording state");
                    }
                    audioSampleRate = rate;
                    audioRecord = ar;
                    audioCodec = ac;
                    return true;
                } catch (Exception e) {
                    Log.w(Gl.TAG, "Audio init failed at " + rate + " Hz / " + bps + " bps", e);
                    if (ar != null) {
                        try { ar.stop(); } catch (Exception ignored) {}
                        try { ar.release(); } catch (Exception ignored) {}
                    }
                    if (ac != null) {
                        try { ac.stop(); } catch (Exception ignored) {}
                        try { ac.release(); } catch (Exception ignored) {}
                    }
                }
            }
        }
        return false;
    }

    private void startAudioLoop() {
        audioThread = new Thread(() -> {
            byte[] pcm = new byte[4096];
            long samplesQueued = 0L;
            try {
                while (!stopping) {
                    int read = audioRecord.read(pcm, 0, pcm.length);
                    if (read < 0) {
                        Log.w(Gl.TAG, "AudioRecord read error: " + read);
                        break;
                    }
                    if (read == 0) continue;
                    if (paused || !active) {
                        drainAudio(false);
                        continue;
                    }
                    if (micMuted) Arrays.fill(pcm, 0, read, (byte) 0);
                    int off = 0;
                    while (off < read && !stopping) {
                        int inIdx = audioCodec.dequeueInputBuffer(10_000);
                        if (inIdx < 0) {
                            drainAudio(false);
                            continue;
                        }
                        ByteBuffer inBuf = audioCodec.getInputBuffer(inIdx);
                        if (inBuf == null) {
                            audioCodec.queueInputBuffer(inIdx, 0, 0, 0, 0);
                            break;
                        }
                        inBuf.clear();
                        int chunk = Math.min(read - off, inBuf.remaining());
                        inBuf.put(pcm, off, chunk);
                        long ptsUs = (samplesQueued * 1_000_000L) / audioSampleRate;
                        audioCodec.queueInputBuffer(inIdx, 0, chunk, ptsUs, 0);
                        samplesQueued += chunk / 2; // 16-bit mono = 2 bytes/sample
                        off += chunk;
                        drainAudio(false);
                    }
                }
                // Signal audio EOS
                long eosPtsUs = (samplesQueued * 1_000_000L) / audioSampleRate;
                long deadline = System.nanoTime() + 1_000_000_000L;
                while (System.nanoTime() < deadline) {
                    int inIdx = audioCodec.dequeueInputBuffer(10_000);
                    if (inIdx >= 0) {
                        audioCodec.queueInputBuffer(inIdx, 0, 0, eosPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        break;
                    }
                    drainAudio(false);
                }
                drainAudio(true);
            } catch (Exception e) {
                Log.e(Gl.TAG, "audio encode loop", e);
            } finally {
                AudioRecord ar = audioRecord;
                audioRecord = null;
                if (ar != null) {
                    try { ar.stop(); } catch (Exception ignored) {}
                    try { ar.release(); } catch (Exception ignored) {}
                }
                MediaCodec ac = audioCodec;
                audioCodec = null;
                if (ac != null) {
                    try { ac.stop(); } catch (Exception ignored) {}
                    try { ac.release(); } catch (Exception ignored) {}
                }
            }
        }, "nib-rec-audio");
        audioThread.start();
    }

    // ---- public state & controls ---------------------------------------------------------------

    Surface getInputSurface() { return inputSurface; }
    int getWidth() { return width; }
    int getHeight() { return height; }
    int getFps() { return fps; }
    boolean isFillFrame() { return fillFrame; }
    boolean hasAudio() { return audioEnabled; }
    boolean isActive() { return active && !stopping; }
    boolean isPaused() { return paused; }
    boolean isCapturing() { return active && !paused && !stopping; }
    boolean isMicMuted() { return micMuted; }
    String getNotice() { return notice; }

    void setMicMuted(boolean muted) { micMuted = muted; }

    void pause() {
        synchronized (clockLock) {
            if (!active || paused || stopping) return;
            paused = true;
            pauseStartedNs = System.nanoTime();
        }
    }

    void resume() {
        synchronized (clockLock) {
            if (!active || !paused || stopping) return;
            long now = System.nanoTime();
            if (pauseStartedNs > 0L) pausedTotalNs += Math.max(0L, now - pauseStartedNs);
            pauseStartedNs = 0L;
            paused = false;
        }
    }

    long elapsedMs() {
        synchronized (clockLock) {
            if (startNs == 0L) return 0L;
            long ref = paused && pauseStartedNs > 0L ? pauseStartedNs : System.nanoTime();
            return Math.max(0L, (ref - startNs - pausedTotalNs) / 1_000_000L);
        }
    }

    /** Minimum nanosecond interval between submitted video frames (slightly below target frame period). */
    long minFrameSpacingNs() {
        return (1_000_000_000L / Math.max(1, fps)) * 75L / 100L;
    }

    long lastFrameWallNs() { return lastFrameWallNs; }

    /** Computes the next strictly monotonic pause-adjusted video timestamp (ns) and marks frame submission. */
    long nextVideoPtsNs() {
        long now = System.nanoTime();
        lastFrameWallNs = now;
        synchronized (clockLock) {
            long pts = Math.max(0L, now - startNs - pausedTotalNs);
            if (lastVideoPtsNs >= 0L && pts <= lastVideoPtsNs) {
                pts = lastVideoPtsNs + 1_000_000L;
            }
            lastVideoPtsNs = pts;
            return pts;
        }
    }

    /** Drains available encoded video frames; safe to call from the GL thread after each swap. */
    void drainVideo(boolean eos) {
        synchronized (videoDrainLock) {
            if (videoCodec == null) return;
            long deadline = eos ? System.nanoTime() + 3_000_000_000L : 0L;
            while (true) {
                int idx;
                try {
                    idx = videoCodec.dequeueOutputBuffer(videoBufInfo, eos ? 10_000 : 0);
                } catch (IllegalStateException e) {
                    break;
                }
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!eos || System.nanoTime() >= deadline) break;
                } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    onTrackFormat(false, videoCodec.getOutputFormat());
                } else if (idx >= 0) {
                    boolean end = (videoBufInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    boolean cfg = (videoBufInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (!cfg && videoBufInfo.size > 0) {
                        ByteBuffer out = videoCodec.getOutputBuffer(idx);
                        if (out != null) writeSample(false, out, videoBufInfo);
                    }
                    try {
                        videoCodec.releaseOutputBuffer(idx, false);
                    } catch (IllegalStateException ignored) {}
                    if (end) break;
                }
            }
        }
    }

    private void drainAudio(boolean eos) {
        if (audioCodec == null) return;
        long deadline = eos ? System.nanoTime() + 2_000_000_000L : 0L;
        while (true) {
            int idx;
            try {
                idx = audioCodec.dequeueOutputBuffer(audioBufInfo, eos ? 10_000 : 0);
            } catch (IllegalStateException e) {
                break;
            }
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!eos || System.nanoTime() >= deadline) break;
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                onTrackFormat(true, audioCodec.getOutputFormat());
            } else if (idx >= 0) {
                boolean end = (audioBufInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                boolean cfg = (audioBufInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (!cfg && audioBufInfo.size > 0) {
                    ByteBuffer out = audioCodec.getOutputBuffer(idx);
                    if (out != null) writeSample(true, out, audioBufInfo);
                }
                try {
                    audioCodec.releaseOutputBuffer(idx, false);
                } catch (IllegalStateException ignored) {}
                if (end) break;
            }
        }
    }

    private void onTrackFormat(boolean isAudio, MediaFormat fmt) {
        synchronized (muxLock) {
            if (muxerStarted || muxerClosed) return;
            if (isAudio) {
                if (audioFormat == null) audioFormat = fmt;
            } else {
                if (videoFormat == null) videoFormat = fmt;
            }
            maybeStartMuxerLocked();
        }
    }

    private void maybeStartMuxerLocked() {
        if (muxerStarted || videoFormat == null) return;
        if (audioEnabled && audioFormat == null) return;
        // Wait until at least one video sample is available (and one audio sample if audio is enabled)
        // so MPEG4Writer never starts a track with 0 samples.
        boolean hasVid = !pendingVideo.isEmpty();
        boolean hasAud = !audioEnabled || !pendingAudio.isEmpty();
        if (hasVid && hasAud) startMuxerLocked(audioEnabled);
    }

    private static int sanitizeMuxFlags(boolean isAudio, int flags) {
        // Never pass BUFFER_FLAG_END_OF_STREAM or BUFFER_FLAG_CODEC_CONFIG to MediaMuxer.writeSampleData():
        // EOS inside writeSampleData() stops the MPEG4Writer track early, causing muxer.stop() to fail.
        return isAudio ? 0 : (flags & MediaCodec.BUFFER_FLAG_KEY_FRAME);
    }

    private void startMuxerLocked(boolean includeAudio) {
        if (muxerStarted || videoFormat == null) return;
        Mp4Muxer m = null;
        try {
            m = new Mp4Muxer(outFile);
            m.setVideoTrack(videoFormat, width, height, fps);
            videoTrack = 0;
            if (includeAudio && audioFormat != null && !pendingAudio.isEmpty()) {
                m.setAudioTrack(audioFormat);
                audioTrack = 1;
            } else {
                audioTrack = -1;
                audioEnabled = false;
            }
            muxer = m;
            muxerStarted = true;
            for (PendingPacket pk : pendingVideo) {
                long pts = clampPtsLocked(false, pk.ptsUs);
                muxer.writeVideoSampleBytes(pk.data, 0, pk.data.length, pts, sanitizeMuxFlags(false, pk.flags));
                videoFramesMuxed++;
            }
            if (audioTrack >= 0) {
                for (PendingPacket pk : pendingAudio) {
                    long pts = clampPtsLocked(true, pk.ptsUs);
                    muxer.writeAudioSampleBytes(pk.data, 0, pk.data.length, pts);
                    audioFramesMuxed++;
                }
            }
        } catch (Exception e) {
            Log.e(Gl.TAG, "startMuxer failed", e);
            videoTrack = -1;
            audioTrack = -1;
            muxer = null;
            muxerStarted = false;
            if (m != null) m.closeQuietly();
        } finally {
            pendingVideo.clear();
            pendingAudio.clear();
            pendingBytes = 0;
        }
    }

    private long clampPtsLocked(boolean isAudio, long ptsUs) {
        if (ptsUs < 0L) ptsUs = 0L;
        if (isAudio) {
            if (lastMuxAudioPtsUs >= 0L && ptsUs <= lastMuxAudioPtsUs) ptsUs = lastMuxAudioPtsUs + 1L;
            lastMuxAudioPtsUs = ptsUs;
        } else {
            if (lastMuxVideoPtsUs >= 0L && ptsUs <= lastMuxVideoPtsUs) ptsUs = lastMuxVideoPtsUs + 1L;
            lastMuxVideoPtsUs = ptsUs;
        }
        return ptsUs;
    }

    private void writeSample(boolean isAudio, ByteBuffer buf, MediaCodec.BufferInfo info) {
        synchronized (muxLock) {
            if (muxerClosed || info.size <= 0) return;
            buf.position(info.offset);
            buf.limit(info.offset + info.size);
            int cleanFlags = sanitizeMuxFlags(isAudio, info.flags);
            if (!muxerStarted) {
                ArrayList<PendingPacket> list = isAudio ? pendingAudio : pendingVideo;
                if (list.size() < MAX_PENDING_PACKETS && pendingBytes + info.size <= MAX_PENDING_BYTES) {
                    byte[] copy = new byte[info.size];
                    buf.get(copy);
                    list.add(new PendingPacket(isAudio, copy, info.presentationTimeUs, cleanFlags));
                    pendingBytes += info.size;
                }
                maybeStartMuxerLocked();
                return;
            }
            if (muxer == null) return;
            int track = isAudio ? audioTrack : videoTrack;
            if (track < 0) return;
            long pts = clampPtsLocked(isAudio, info.presentationTimeUs);
            try {
                if (isAudio) {
                    muxer.writeAudioSample(buf, info.offset, info.size, pts);
                    audioFramesMuxed++;
                } else {
                    muxer.writeVideoSample(buf, info.offset, info.size, pts, cleanFlags);
                    videoFramesMuxed++;
                }
            } catch (IOException e) {
                Log.e(Gl.TAG, "mux writeSample failed", e);
            }
        }
    }

    /**
     * Finalizes the MP4 asynchronously. Caller MUST detach the GL encoder surface from {@link Engine}
     * before invoking this method (e.g. via {@link Engine#detachRecorderSync()}).
     */
    void stopAsync(Consumer<Result> onDone) {
        final long durationMs = elapsedMs();
        stopping = true;
        active = false;
        Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String err = null;
            try {
                // 1. Signal video EOS and drain remaining encoded video packets
                synchronized (videoDrainLock) {
                    if (videoCodec != null) {
                        try { videoCodec.signalEndOfInputStream(); } catch (Exception e) { Log.w(Gl.TAG, "video EOS", e); }
                    }
                }
                drainVideo(true);

                // 2. Unblock AudioRecord.read() and wait for audio thread to finish EOS drain
                AudioRecord ar = audioRecord;
                if (ar != null) {
                    try { ar.stop(); } catch (Exception ignored) {}
                }
                Thread at = audioThread;
                if (at != null) {
                    try { at.join(2500); } catch (InterruptedException ignored) {}
                    audioThread = null;
                }

                // 3. Finalize MP4 container
                synchronized (muxLock) {
                    if (!muxerStarted && videoFormat != null && !pendingVideo.isEmpty()) {
                        startMuxerLocked(audioEnabled && audioFormat != null && !pendingAudio.isEmpty());
                    }
                    if (muxer != null) {
                        if (muxerStarted && videoFramesMuxed > 0) {
                            try {
                                muxer.finish();
                            } catch (Exception e) {
                                Log.e(Gl.TAG, "muxer finish", e);
                                err = "Could not finish video: " + e.getMessage();
                            }
                        } else {
                            muxer.closeQuietly();
                            err = "No video frames were captured";
                        }
                        muxer = null;
                        muxerStarted = false;
                    } else if (!muxerStarted) {
                        err = "No video frames were captured";
                    }
                    pendingVideo.clear();
                    pendingAudio.clear();
                    pendingBytes = 0;
                    muxerClosed = true;
                }
            } catch (Exception e) {
                Log.e(Gl.TAG, "recorder stop", e);
                err = "Could not finish video: " + e.getMessage();
            } finally {
                synchronized (videoDrainLock) {
                    releaseVideoOnly();
                }
            }

            if (err == null && (videoFramesMuxed == 0 || !outFile.isFile() || outFile.length() == 0L)) {
                err = "No video frames were captured";
            }
            Uri savedUri = null;
            if (err == null) {
                try {
                    savedUri = saveToGallery(appCtx, outFile, durationMs);
                } catch (Exception e) {
                    Log.e(Gl.TAG, "save video to gallery", e);
                    err = "Couldn't save video to gallery";
                }
            }
            outFile.delete();
            final Result res = new Result(
                    savedUri, outFile.getName(),
                    durationMs, width, height, audioEnabled && audioTrack >= 0,
                    err, notice);
            if (onDone != null) ui.post(() -> onDone.accept(res));
        }, "nib-rec-stop").start();
    }

    /** Publishes the finished MP4 into Movies/Nib via MediaStore (zero permissions on Android 10+). */
    private static Uri saveToGallery(Context ctx, File src, long durationMs) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Video.Media.DISPLAY_NAME, src.getName());
        cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        cv.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Nib");
        cv.put(MediaStore.Video.Media.DURATION, durationMs);
        cv.put(MediaStore.Video.Media.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new IOException("MediaStore insert returned null");
        try {
            try (FileInputStream in = new FileInputStream(src);
                 OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) throw new IOException("Could not open MediaStore output stream");
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                out.flush();
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Video.Media.IS_PENDING, 0);
            cr.update(uri, done, null, null);
            return uri;
        } catch (IOException | RuntimeException e) {
            try { cr.delete(uri, null, null); } catch (Exception ignored) {}
            throw e;
        }
    }

    /** Immediately aborts and discards the in-progress recording (e.g., Activity destroyed). */
    void abortSync() {
        stopping = true;
        active = false;
        AudioRecord ar = audioRecord;
        if (ar != null) {
            try { ar.stop(); } catch (Exception ignored) {}
        }
        Thread at = audioThread;
        if (at != null) {
            try { at.join(800); } catch (InterruptedException ignored) {}
            audioThread = null;
        }
        cleanupQuietly();
        outFile.delete();
    }

    private void cleanupQuietly() {
        synchronized (videoDrainLock) {
            releaseVideoOnly();
        }
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception ignored) {}
            try { audioRecord.release(); } catch (Exception ignored) {}
            audioRecord = null;
        }
        if (audioCodec != null) {
            try { audioCodec.stop(); } catch (Exception ignored) {}
            try { audioCodec.release(); } catch (Exception ignored) {}
            audioCodec = null;
        }
        synchronized (muxLock) {
            if (muxer != null) {
                muxer.closeQuietly();
                muxer = null;
                muxerStarted = false;
            }
            pendingVideo.clear();
            pendingAudio.clear();
            pendingBytes = 0;
            muxerClosed = true;
        }
    }
}

package app.nib.notes;

import android.media.MediaCodec;
import android.media.MediaFormat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Minimal, pure-Java ISO-BMFF (MP4) muxer for H.264 (AVC) video + optional AAC-LC audio.
 *
 * Writes standards-compliant {@code ftyp} + {@code mdat} + {@code moov} boxes directly, avoiding
 * Android's native {@code MediaMuxer}/{@code MPEG4Writer} teardown quirks and spurious
 * {@code E/MPEG4Writer: Stop() called but track is not started or stopped} log errors.
 */
final class Mp4Muxer {

    private static final int VIDEO_TIMESCALE = 90_000;
    private static final int MOVIE_TIMESCALE = 1_000;
    private static final int IO_BUF_SIZE = 64 * 1024;

    private static final class Track {
        final boolean isAudio;
        final int width, height, fps;
        final int sampleRate, channels, bitrate;
        final ArrayList<byte[]> sps = new ArrayList<>();
        final ArrayList<byte[]> pps = new ArrayList<>();
        final byte[] audioSpecificConfig;

        long[] offsets = new long[256];
        int[] sizes = new int[256];
        long[] ptsUs = new long[256];
        boolean[] sync = new boolean[256];
        int count;

        Track(MediaFormat fmt, boolean isAudio, int fallbackW, int fallbackH, int fallbackFps) {
            this.isAudio = isAudio;
            if (isAudio) {
                this.width = 0;
                this.height = 0;
                this.fps = 0;
                this.sampleRate = getInt(fmt, MediaFormat.KEY_SAMPLE_RATE, 48_000);
                this.channels = getInt(fmt, MediaFormat.KEY_CHANNEL_COUNT, 1);
                this.bitrate = getInt(fmt, MediaFormat.KEY_BIT_RATE, 192_000);
                byte[] csd = readCsd(fmt, "csd-0");
                this.audioSpecificConfig = (csd != null && csd.length >= 2)
                        ? csd
                        : buildAacSpecificConfig(this.sampleRate, this.channels);
            } else {
                this.width = getInt(fmt, MediaFormat.KEY_WIDTH, fallbackW);
                this.height = getInt(fmt, MediaFormat.KEY_HEIGHT, fallbackH);
                this.fps = Math.max(1, getInt(fmt, MediaFormat.KEY_FRAME_RATE, fallbackFps));
                this.sampleRate = 0;
                this.channels = 0;
                this.bitrate = getInt(fmt, MediaFormat.KEY_BIT_RATE, 8_000_000);
                this.audioSpecificConfig = null;
                extractSpsPps(readCsd(fmt, "csd-0"), sps, pps);
                extractSpsPps(readCsd(fmt, "csd-1"), sps, pps);
            }
        }

        void addSample(long offset, int size, long ptsMicros, boolean isSync) {
            if (count == offsets.length) {
                int next = offsets.length * 2;
                offsets = Arrays.copyOf(offsets, next);
                sizes = Arrays.copyOf(sizes, next);
                ptsUs = Arrays.copyOf(ptsUs, next);
                sync = Arrays.copyOf(sync, next);
            }
            offsets[count] = offset;
            sizes[count] = size;
            ptsUs[count] = ptsMicros;
            sync[count] = isSync;
            count++;
        }
    }

    private final RandomAccessFile raf;
    private final byte[] ioBuf = new byte[IO_BUF_SIZE];
    private int ioPos;
    private long filePos;
    private final long mdatBoxStart; // offset of 8-byte 'free' + 8-byte 'mdat' header
    private final long mdatDataStart;
    private Track videoTrack;
    private Track audioTrack;
    private boolean finished;
    private boolean closed;
    private boolean lastSampleHadIdr;
    private byte[] scratch = new byte[64 * 1024];

    Mp4Muxer(File outFile) throws IOException {
        this.raf = new RandomAccessFile(outFile, "rw");
        this.raf.setLength(0L);
        // 1. Write ftyp box (32 bytes): isom / iso2 / avc1 / mp41
        ByteArrayOutputStream ftyp = new ByteArrayOutputStream(32);
        writeU32(ftyp, 32);
        writeTag(ftyp, "ftyp");
        writeTag(ftyp, "isom");
        writeU32(ftyp, 0x00000200);
        writeTag(ftyp, "isom");
        writeTag(ftyp, "iso2");
        writeTag(ftyp, "avc1");
        writeTag(ftyp, "mp41");
        writeBuffered(ftyp.toByteArray(), 0, 32);

        // 2. Reserve 16 bytes for mdat header:
        //    For files < 4 GB: 8-byte 'free' placeholder + standard 8-byte 'mdat' header.
        //    For files >= 4 GB: overwritten at finish() with 16-byte 64-bit extended-size 'mdat'.
        this.mdatBoxStart = filePos;
        byte[] hdr = new byte[16];
        putU32(hdr, 0, 8);
        hdr[4] = 'f'; hdr[5] = 'r'; hdr[6] = 'e'; hdr[7] = 'e';
        putU32(hdr, 8, 8);
        hdr[12] = 'm'; hdr[13] = 'd'; hdr[14] = 'a'; hdr[15] = 't';
        writeBuffered(hdr, 0, 16);
        this.mdatDataStart = filePos;
    }

    void setVideoTrack(MediaFormat fmt, int fallbackW, int fallbackH, int fallbackFps) {
        videoTrack = new Track(fmt, false, fallbackW, fallbackH, fallbackFps);
    }

    void setAudioTrack(MediaFormat fmt) {
        audioTrack = new Track(fmt, true, 0, 0, 0);
    }

    void writeVideoSample(ByteBuffer buf, int offset, int size, long ptsUs, int flags) throws IOException {
        if (closed || finished || videoTrack == null || size <= 0) return;
        byte[] raw = readBytes(buf, offset, size);
        writeVideoSampleBytes(raw, 0, size, ptsUs, flags);
    }

    void writeVideoSampleBytes(byte[] raw, int off, int len, long ptsUs, int flags) throws IOException {
        if (closed || finished || videoTrack == null || len <= 0) return;
        long sampleOffset = filePos;
        lastSampleHadIdr = false;
        int written = writeAvccSample(raw, off, len, videoTrack);
        if (written <= 0) return;
        boolean key = ((flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) || lastSampleHadIdr;
        videoTrack.addSample(sampleOffset, written, ptsUs, key);
    }

    void writeAudioSample(ByteBuffer buf, int offset, int size, long ptsUs) throws IOException {
        if (closed || finished || audioTrack == null || size <= 0) return;
        byte[] raw = readBytes(buf, offset, size);
        writeAudioSampleBytes(raw, 0, size, ptsUs);
    }

    void writeAudioSampleBytes(byte[] raw, int off, int len, long ptsUs) throws IOException {
        if (closed || finished || audioTrack == null || len <= 0) return;
        // Defensively strip 7-byte ADTS header if an OEM encoder ever emits ADTS-wrapped AAC
        if (len > 7 && (raw[off] & 0xFF) == 0xFF && (raw[off + 1] & 0xF0) == 0xF0) {
            int hdrLen = (raw[off + 1] & 0x01) == 0 ? 9 : 7;
            if (len > hdrLen) {
                off += hdrLen;
                len -= hdrLen;
            }
        }
        long sampleOffset = filePos;
        writeBuffered(raw, off, len);
        audioTrack.addSample(sampleOffset, len, ptsUs, true);
    }

    /** Patches the mdat box size, appends the moov atom, flushes, and closes the file. */
    void finish() throws IOException {
        if (closed || finished) return;
        finished = true;
        flushBuffer();
        long mdatEnd = filePos;
        long mdatPayload = mdatEnd - mdatDataStart;
        if (videoTrack == null || videoTrack.count == 0 || mdatPayload <= 0) {
            closeQuietly();
            throw new IOException("No video samples written");
        }

        // Write moov box right after mdat
        byte[] moov = buildMoov();
        raf.seek(mdatEnd);
        raf.write(moov);

        // Patch mdat header size
        if (mdatPayload + 8L <= 0xFFFFFFFFL) {
            byte[] sz = new byte[4];
            putU32(sz, 0, mdatPayload + 8L);
            raf.seek(mdatBoxStart + 8L);
            raf.write(sz);
        } else {
            byte[] ext = new byte[16];
            putU32(ext, 0, 1);
            ext[4] = 'm'; ext[5] = 'd'; ext[6] = 'a'; ext[7] = 't';
            putU64(ext, 8, mdatPayload + 16L);
            raf.seek(mdatBoxStart);
            raf.write(ext);
        }
        raf.getFD().sync();
        closeQuietly();
    }

    void closeQuietly() {
        if (closed) return;
        closed = true;
        try { raf.close(); } catch (Exception ignored) {}
    }

    // ---- Annex-B -> AVCC conversion ------------------------------------------------------------

    /** Converts Annex-B H.264 NAL units into 4-byte big-endian length-prefixed AVCC NALUs. */
    private int writeAvccSample(byte[] data, int off, int len, Track vt) throws IOException {
        int end = off + len;
        int start = findStartCode(data, off, end);
        if (start < 0) {
            // Already raw NALU without start code
            return writeLengthPrefixedNalu(data, off, len, vt);
        }
        int total = 0;
        int pos = start;
        while (pos < end) {
            int scLen = startCodeLen(data, pos, end);
            int naluStart = pos + scLen;
            int nextSc = findStartCode(data, naluStart, end);
            int naluEnd = nextSc >= 0 ? nextSc : end;
            // Trim trailing zero padding bytes before next start code
            while (naluEnd > naluStart && data[naluEnd - 1] == 0) naluEnd--;
            int naluLen = naluEnd - naluStart;
            if (naluLen > 0) {
                total += writeLengthPrefixedNalu(data, naluStart, naluLen, vt);
            }
            if (nextSc < 0) break;
            pos = nextSc;
        }
        return total;
    }

    private int writeLengthPrefixedNalu(byte[] data, int off, int len, Track vt) throws IOException {
        int nalType = data[off] & 0x1F;
        if (nalType == 5) {
            lastSampleHadIdr = true;
        } else if (nalType == 7 && vt.sps.isEmpty()) {
            vt.sps.add(Arrays.copyOfRange(data, off, off + len));
        } else if (nalType == 8 && vt.pps.isEmpty()) {
            vt.pps.add(Arrays.copyOfRange(data, off, off + len));
        }
        byte[] lenHdr = new byte[4];
        putU32(lenHdr, 0, len);
        writeBuffered(lenHdr, 0, 4);
        writeBuffered(data, off, len);
        return 4 + len;
    }

    private static void extractSpsPps(byte[] csd, ArrayList<byte[]> sps, ArrayList<byte[]> pps) {
        if (csd == null || csd.length == 0) return;
        int end = csd.length;
        int pos = findStartCode(csd, 0, end);
        if (pos < 0) {
            int t = csd[0] & 0x1F;
            if (t == 7) sps.add(csd);
            else if (t == 8) pps.add(csd);
            return;
        }
        while (pos < end) {
            int scLen = startCodeLen(csd, pos, end);
            int naluStart = pos + scLen;
            int nextSc = findStartCode(csd, naluStart, end);
            int naluEnd = nextSc >= 0 ? nextSc : end;
            while (naluEnd > naluStart && csd[naluEnd - 1] == 0) naluEnd--;
            if (naluEnd > naluStart) {
                byte[] nalu = Arrays.copyOfRange(csd, naluStart, naluEnd);
                int t = nalu[0] & 0x1F;
                if (t == 7) sps.add(nalu);
                else if (t == 8) pps.add(nalu);
            }
            if (nextSc < 0) break;
            pos = nextSc;
        }
    }

    private static int findStartCode(byte[] data, int from, int end) {
        for (int i = from; i + 2 < end; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (data[i + 2] == 1) return i;
                if (i + 3 < end && data[i + 2] == 0 && data[i + 3] == 1) return i;
            }
        }
        return -1;
    }

    private static int startCodeLen(byte[] data, int pos, int end) {
        if (pos + 3 < end && data[pos] == 0 && data[pos + 1] == 0 && data[pos + 2] == 0 && data[pos + 3] == 1) {
            return 4;
        }
        return 3;
    }

    // ---- MOOV / ISO-BMFF box builders ----------------------------------------------------------

    private byte[] buildMoov() throws IOException {
        boolean hasAudio = audioTrack != null && audioTrack.count > 0;
        long videoDurTicks = computeTrackDurationTicks(videoTrack, VIDEO_TIMESCALE);
        long videoDurMs = Math.max(1L, (videoDurTicks * MOVIE_TIMESCALE) / VIDEO_TIMESCALE);
        long audioDurTicks = hasAudio ? (long) audioTrack.count * 1024L : 0L;
        long audioDurMs = hasAudio ? Math.max(1L, (audioDurTicks * MOVIE_TIMESCALE) / audioTrack.sampleRate) : 0L;
        long movieDurMs = Math.max(videoDurMs, audioDurMs);

        byte[] mvhd = buildMvhd(movieDurMs, hasAudio ? 3 : 2);
        byte[] vTrak = buildTrak(videoTrack, 1, VIDEO_TIMESCALE, videoDurTicks, videoDurMs);
        byte[] aTrak = hasAudio ? buildTrak(audioTrack, 2, audioTrack.sampleRate, audioDurTicks, audioDurMs) : null;

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(mvhd);
        body.write(vTrak);
        if (aTrak != null) body.write(aTrak);
        return wrapBox("moov", body.toByteArray());
    }

    private static long computeTrackDurationTicks(Track tr, int timescale) {
        if (tr.count <= 0) return 0L;
        long defaultDelta = Math.max(1L, timescale / Math.max(1, tr.fps));
        if (tr.count == 1) return defaultDelta;
        long total = 0L;
        long prevTick = 0L;
        long basePts = tr.ptsUs[0];
        for (int i = 1; i < tr.count; i++) {
            long tick = Math.max(prevTick + 1L, ((tr.ptsUs[i] - basePts) * timescale) / 1_000_000L);
            total += (tick - prevTick);
            prevTick = tick;
        }
        return total + defaultDelta;
    }

    private static byte[] buildMvhd(long durationMs, int nextTrackId) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(100);
        writeU32(b, 0); // creation_time
        writeU32(b, 0); // modification_time
        writeU32(b, MOVIE_TIMESCALE);
        writeU32(b, durationMs);
        writeU32(b, 0x00010000); // rate 1.0
        writeU16(b, 0x0100);     // volume 1.0
        writeZeros(b, 10);
        writeIdentityMatrix(b);
        writeZeros(b, 24);
        writeU32(b, nextTrackId);
        return wrapFullBox("mvhd", 0, 0, b.toByteArray());
    }

    private static byte[] buildTrak(Track tr, int trackId, int timescale, long trackDurTicks, long movieDurMs) throws IOException {
        byte[] tkhd = buildTkhd(tr, trackId, movieDurMs);
        byte[] mdia = buildMdia(tr, timescale, trackDurTicks);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(tkhd);
        body.write(mdia);
        return wrapBox("trak", body.toByteArray());
    }

    private static byte[] buildTkhd(Track tr, int trackId, long movieDurMs) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(84);
        writeU32(b, 0); // creation_time
        writeU32(b, 0); // modification_time
        writeU32(b, trackId);
        writeU32(b, 0); // reserved
        writeU32(b, movieDurMs);
        writeZeros(b, 8);
        writeU16(b, 0); // layer
        writeU16(b, 0); // alternate_group
        writeU16(b, tr.isAudio ? 0x0100 : 0); // volume
        writeU16(b, 0); // reserved
        writeIdentityMatrix(b);
        writeU32(b, tr.isAudio ? 0L : ((long) tr.width << 16));
        writeU32(b, tr.isAudio ? 0L : ((long) tr.height << 16));
        return wrapFullBox("tkhd", 0, 3, b.toByteArray());
    }

    private static byte[] buildMdia(Track tr, int timescale, long trackDurTicks) throws IOException {
        byte[] mdhd = buildMdhd(timescale, trackDurTicks);
        byte[] hdlr = buildHdlr(tr.isAudio ? "soun" : "vide", tr.isAudio ? "SoundHandler" : "VideoHandler");
        byte[] minf = buildMinf(tr, timescale);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(mdhd);
        body.write(hdlr);
        body.write(minf);
        return wrapBox("mdia", body.toByteArray());
    }

    private static byte[] buildMdhd(int timescale, long trackDurTicks) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(24);
        writeU32(b, 0);
        writeU32(b, 0);
        writeU32(b, timescale);
        writeU32(b, trackDurTicks);
        writeU16(b, 0x55C4); // "und"
        writeU16(b, 0);
        return wrapFullBox("mdhd", 0, 0, b.toByteArray());
    }

    private static byte[] buildHdlr(String type, String name) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(36);
        writeU32(b, 0);
        writeTag(b, type);
        writeZeros(b, 12);
        b.write(name.getBytes(StandardCharsets.UTF_8));
        b.write(0);
        return wrapFullBox("hdlr", 0, 0, b.toByteArray());
    }

    private static byte[] buildMinf(Track tr, int timescale) throws IOException {
        byte[] mhd = tr.isAudio
                ? wrapFullBox("smhd", 0, 0, new byte[4])
                : wrapFullBox("vmhd", 0, 1, new byte[8]);
        byte[] url = wrapFullBox("url ", 0, 1, new byte[0]);
        ByteArrayOutputStream drefBody = new ByteArrayOutputStream(16);
        writeU32(drefBody, 1);
        drefBody.write(url);
        byte[] dinf = wrapBox("dinf", wrapFullBox("dref", 0, 0, drefBody.toByteArray()));
        byte[] stbl = buildStbl(tr, timescale);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(mhd);
        body.write(dinf);
        body.write(stbl);
        return wrapBox("minf", body.toByteArray());
    }

    private static byte[] buildStbl(Track tr, int timescale) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(buildStsd(tr));
        body.write(buildStts(tr, timescale));
        if (!tr.isAudio) body.write(buildStss(tr));
        body.write(buildStsc());
        body.write(buildStsz(tr));
        body.write(buildStco(tr));
        return wrapBox("stbl", body.toByteArray());
    }

    private static byte[] buildStsd(Track tr) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeU32(b, 1);
        b.write(tr.isAudio ? buildMp4a(tr) : buildAvc1(tr));
        return wrapFullBox("stsd", 0, 0, b.toByteArray());
    }

    private static byte[] buildAvc1(Track tr) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(128);
        writeZeros(b, 6);
        writeU16(b, 1); // data_reference_index
        writeZeros(b, 16);
        writeU16(b, tr.width);
        writeU16(b, tr.height);
        writeU32(b, 0x00480000); // 72 dpi
        writeU32(b, 0x00480000);
        writeU32(b, 0);
        writeU16(b, 1); // frame_count
        writeZeros(b, 32); // compressorname
        writeU16(b, 0x0018); // depth 24
        writeU16(b, 0xFFFF); // pre_defined -1
        b.write(buildAvcC(tr));
        return wrapBox("avc1", b.toByteArray());
    }

    private static byte[] buildAvcC(Track tr) throws IOException {
        byte[] sps0 = tr.sps.isEmpty() ? new byte[]{0x67, 0x42, 0x00, 0x1E} : tr.sps.get(0);
        ByteArrayOutputStream b = new ByteArrayOutputStream(64);
        b.write(1); // configurationVersion
        b.write(sps0.length > 1 ? sps0[1] : 0x42);
        b.write(sps0.length > 2 ? sps0[2] : 0x00);
        b.write(sps0.length > 3 ? sps0[3] : 0x1E);
        b.write(0xFF); // 6 reserved 1-bits + lengthSizeMinusOne=3 (4-byte NALU lengths)
        int nSps = Math.min(31, tr.sps.size());
        b.write(0xE0 | nSps);
        for (int i = 0; i < nSps; i++) {
            byte[] s = tr.sps.get(i);
            writeU16(b, s.length);
            b.write(s);
        }
        int nPps = Math.min(255, tr.pps.size());
        b.write(nPps);
        for (int i = 0; i < nPps; i++) {
            byte[] p = tr.pps.get(i);
            writeU16(b, p.length);
            b.write(p);
        }
        int profile = (sps0.length > 1 ? (sps0[1] & 0xFF) : 0x42);
        if (profile == 100 || profile == 110 || profile == 122 || profile == 144) {
            b.write(0xFD); // 6 reserved 1-bits + chroma_format_idc=1 (4:2:0)
            b.write(0xF8); // 5 reserved 1-bits + bit_depth_luma_minus8=0 (8-bit)
            b.write(0xF8); // 5 reserved 1-bits + bit_depth_chroma_minus8=0 (8-bit)
            b.write(0x00); // numOfSequenceParameterSetExt=0
        }
        return wrapBox("avcC", b.toByteArray());
    }

    private static byte[] buildMp4a(Track tr) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(96);
        writeZeros(b, 6);
        writeU16(b, 1); // data_reference_index
        writeZeros(b, 8);
        writeU16(b, tr.channels);
        writeU16(b, 16); // sampleSize
        writeU16(b, 0);
        writeU16(b, 0);
        writeU32(b, ((long) (tr.sampleRate & 0xFFFF)) << 16);
        b.write(buildEsds(tr));
        return wrapBox("mp4a", b.toByteArray());
    }

    private static byte[] buildEsds(Track tr) throws IOException {
        byte[] asc = tr.audioSpecificConfig != null ? tr.audioSpecificConfig : new byte[]{0x11, (byte) 0x88};
        ByteArrayOutputStream decSpec = new ByteArrayOutputStream();
        decSpec.write(0x05); // DecSpecificInfoTag
        writeDescrLen(decSpec, asc.length);
        decSpec.write(asc);

        ByteArrayOutputStream decCfgBody = new ByteArrayOutputStream();
        decCfgBody.write(0x40); // MPEG-4 Audio
        decCfgBody.write(0x15); // AudioStream (5 << 2 | 1)
        decCfgBody.write(0); decCfgBody.write(0); decCfgBody.write(0); // bufferSizeDB
        writeU32(decCfgBody, tr.bitrate); // maxBitrate
        writeU32(decCfgBody, tr.bitrate); // avgBitrate
        decCfgBody.write(decSpec.toByteArray());

        ByteArrayOutputStream decCfg = new ByteArrayOutputStream();
        decCfg.write(0x04); // DecoderConfigDescrTag
        writeDescrLen(decCfg, decCfgBody.size());
        decCfg.write(decCfgBody.toByteArray());

        ByteArrayOutputStream esBody = new ByteArrayOutputStream();
        writeU16(esBody, 2); // ES_ID
        esBody.write(0);     // flags
        esBody.write(decCfg.toByteArray());
        esBody.write(0x06);  // SLConfigDescrTag
        esBody.write(0x01);
        esBody.write(0x02);  // predefined MP4

        ByteArrayOutputStream es = new ByteArrayOutputStream();
        es.write(0x03); // ES_DescrTag
        writeDescrLen(es, esBody.size());
        es.write(esBody.toByteArray());
        return wrapFullBox("esds", 0, 0, es.toByteArray());
    }

    private static byte[] buildStts(Track tr, int timescale) throws IOException {
        if (tr.isAudio) {
            ByteArrayOutputStream b = new ByteArrayOutputStream(16);
            writeU32(b, 1);
            writeU32(b, tr.count);
            writeU32(b, 1024); // AAC-LC constant 1024 samples per frame
            return wrapFullBox("stts", 0, 0, b.toByteArray());
        }
        // Video: VFR deltas in 90 kHz timescale, run-length encoded
        long defaultDelta = Math.max(1L, timescale / Math.max(1, tr.fps));
        long[] deltas = new long[tr.count];
        long basePts = tr.ptsUs[0];
        long prevTick = 0L;
        for (int i = 1; i < tr.count; i++) {
            long tick = Math.max(prevTick + 1L, ((tr.ptsUs[i] - basePts) * timescale) / 1_000_000L);
            deltas[i - 1] = tick - prevTick;
            prevTick = tick;
        }
        deltas[tr.count - 1] = defaultDelta;

        ArrayList<Long> counts = new ArrayList<>();
        ArrayList<Long> vals = new ArrayList<>();
        long curDelta = deltas[0];
        long run = 1;
        for (int i = 1; i < tr.count; i++) {
            if (deltas[i] == curDelta) {
                run++;
            } else {
                counts.add(run);
                vals.add(curDelta);
                curDelta = deltas[i];
                run = 1;
            }
        }
        counts.add(run);
        vals.add(curDelta);

        ByteArrayOutputStream b = new ByteArrayOutputStream(4 + counts.size() * 8);
        writeU32(b, counts.size());
        for (int i = 0; i < counts.size(); i++) {
            writeU32(b, counts.get(i));
            writeU32(b, vals.get(i));
        }
        return wrapFullBox("stts", 0, 0, b.toByteArray());
    }

    private static byte[] buildStss(Track tr) throws IOException {
        int syncCount = 0;
        for (int i = 0; i < tr.count; i++) if (tr.sync[i]) syncCount++;
        boolean forceFirst = syncCount == 0 && tr.count > 0;
        if (forceFirst) syncCount = 1;
        ByteArrayOutputStream b = new ByteArrayOutputStream(4 + syncCount * 4);
        writeU32(b, syncCount);
        if (forceFirst) {
            writeU32(b, 1);
        } else {
            for (int i = 0; i < tr.count; i++) {
                if (tr.sync[i]) writeU32(b, i + 1); // 1-based sample index
            }
        }
        return wrapFullBox("stss", 0, 0, b.toByteArray());
    }

    private static byte[] buildStsc() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(16);
        writeU32(b, 1); // entry_count
        writeU32(b, 1); // first_chunk
        writeU32(b, 1); // samples_per_chunk
        writeU32(b, 1); // sample_description_index
        return wrapFullBox("stsc", 0, 0, b.toByteArray());
    }

    private static byte[] buildStsz(Track tr) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(8 + tr.count * 4);
        writeU32(b, 0); // variable sample sizes
        writeU32(b, tr.count);
        for (int i = 0; i < tr.count; i++) writeU32(b, tr.sizes[i]);
        return wrapFullBox("stsz", 0, 0, b.toByteArray());
    }

    private static byte[] buildStco(Track tr) throws IOException {
        boolean use64 = false;
        for (int i = 0; i < tr.count; i++) {
            if (tr.offsets[i] > 0xFFFFFFFFL) { use64 = true; break; }
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream(4 + tr.count * (use64 ? 8 : 4));
        writeU32(b, tr.count);
        if (use64) {
            for (int i = 0; i < tr.count; i++) writeU64(b, tr.offsets[i]);
            return wrapFullBox("co64", 0, 0, b.toByteArray());
        } else {
            for (int i = 0; i < tr.count; i++) writeU32(b, tr.offsets[i]);
            return wrapFullBox("stco", 0, 0, b.toByteArray());
        }
    }

    // ---- Low-level I/O & helpers ---------------------------------------------------------------

    private byte[] readBytes(ByteBuffer buf, int offset, int size) {
        if (scratch.length < size) scratch = new byte[Math.max(size, scratch.length * 2)];
        buf.position(offset);
        buf.limit(offset + size);
        buf.get(scratch, 0, size);
        return scratch;
    }

    private void writeBuffered(byte[] data, int off, int len) throws IOException {
        if (len <= 0) return;
        if (ioPos + len > ioBuf.length) {
            flushBuffer();
            if (len >= ioBuf.length) {
                raf.write(data, off, len);
                filePos += len;
                return;
            }
        }
        System.arraycopy(data, off, ioBuf, ioPos, len);
        ioPos += len;
        filePos += len;
    }

    private void flushBuffer() throws IOException {
        if (ioPos > 0) {
            raf.write(ioBuf, 0, ioPos);
            ioPos = 0;
        }
    }

    private static byte[] wrapBox(String type, byte[] payload) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8 + payload.length);
        writeU32(out, 8L + payload.length);
        writeTag(out, type);
        out.write(payload);
        return out.toByteArray();
    }

    private static byte[] wrapFullBox(String type, int version, int flags, byte[] payload) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(12 + payload.length);
        writeU32(out, 12L + payload.length);
        writeTag(out, type);
        out.write(version & 0xFF);
        out.write((flags >>> 16) & 0xFF);
        out.write((flags >>> 8) & 0xFF);
        out.write(flags & 0xFF);
        out.write(payload);
        return out.toByteArray();
    }

    private static void writeDescrLen(ByteArrayOutputStream out, int len) {
        if (len < 128) {
            out.write(len);
        } else {
            out.write(((len >>> 21) & 0x7F) | 0x80);
            out.write(((len >>> 14) & 0x7F) | 0x80);
            out.write(((len >>> 7) & 0x7F) | 0x80);
            out.write(len & 0x7F);
        }
    }

    private static void writeIdentityMatrix(ByteArrayOutputStream b) {
        writeU32(b, 0x00010000); writeU32(b, 0); writeU32(b, 0);
        writeU32(b, 0); writeU32(b, 0x00010000); writeU32(b, 0);
        writeU32(b, 0); writeU32(b, 0); writeU32(b, 0x40000000);
    }

    private static void writeTag(ByteArrayOutputStream b, String fourcc) {
        for (int i = 0; i < 4; i++) b.write(fourcc.charAt(i));
    }

    private static void writeZeros(ByteArrayOutputStream b, int n) {
        for (int i = 0; i < n; i++) b.write(0);
    }

    private static void writeU16(ByteArrayOutputStream b, int v) {
        b.write((v >>> 8) & 0xFF);
        b.write(v & 0xFF);
    }

    private static void writeU32(ByteArrayOutputStream b, long v) {
        b.write((int) ((v >>> 24) & 0xFF));
        b.write((int) ((v >>> 16) & 0xFF));
        b.write((int) ((v >>> 8) & 0xFF));
        b.write((int) (v & 0xFF));
    }

    private static void writeU64(ByteArrayOutputStream b, long v) {
        writeU32(b, v >>> 32);
        writeU32(b, v & 0xFFFFFFFFL);
    }

    private static void putU32(byte[] a, int off, long v) {
        a[off] = (byte) ((v >>> 24) & 0xFF);
        a[off + 1] = (byte) ((v >>> 16) & 0xFF);
        a[off + 2] = (byte) ((v >>> 8) & 0xFF);
        a[off + 3] = (byte) (v & 0xFF);
    }

    private static void putU64(byte[] a, int off, long v) {
        putU32(a, off, v >>> 32);
        putU32(a, off + 4, v & 0xFFFFFFFFL);
    }

    private static int getInt(MediaFormat fmt, String key, int def) {
        return (fmt != null && fmt.containsKey(key)) ? fmt.getInteger(key) : def;
    }

    private static byte[] readCsd(MediaFormat fmt, String key) {
        if (fmt == null || !fmt.containsKey(key)) return null;
        ByteBuffer bb = fmt.getByteBuffer(key);
        if (bb == null) return null;
        ByteBuffer dup = bb.duplicate();
        dup.rewind();
        byte[] out = new byte[dup.remaining()];
        dup.get(out);
        return out;
    }

    private static byte[] buildAacSpecificConfig(int sampleRate, int channels) {
        int[] table = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};
        int idx = 3; // 48000 default
        for (int i = 0; i < table.length; i++) {
            if (table[i] == sampleRate) { idx = i; break; }
        }
        int audioObjectType = 2; // AAC-LC
        int val = (audioObjectType << 11) | (idx << 7) | ((channels & 0x0F) << 3);
        return new byte[]{(byte) ((val >>> 8) & 0xFF), (byte) (val & 0xFF)};
    }
}

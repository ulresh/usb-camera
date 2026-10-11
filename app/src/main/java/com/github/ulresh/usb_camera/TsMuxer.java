package com.github.ulresh.usb_camera;

import java.io.ByteArrayOutputStream;

/** Минимальный мультиплексор MPEG-TS: один видеопоток (H.264/H.265) и/или один аудиопоток (AAC ADTS). */
final class TsMuxer {
    interface Sink {
        /** key=true: чанк начинается с PAT/PMT и ключевого кадра (с него можно начинать воспроизведение). */
        void onChunk(byte[] data, boolean key);
    }

    static final int VIDEO_H264 = 0x1B;
    static final int VIDEO_HEVC = 0x24;
    private static final int STREAM_AAC = 0x0F;

    private static final int PID_PMT = 0x1000;
    private static final int PID_VIDEO = 0x100;
    private static final int PID_AUDIO = 0x101;
    /** Задержка PTS относительно PCR, мкс. */
    private static final long MUX_DELAY_US = 300_000;
    private static final long MASK33 = (1L << 33) - 1;

    private final Sink sink;
    private final int videoType;   // 0 - нет видео
    private final boolean hasAudio;
    private final int sfIndex;
    private final int pcrPid;
    private final int[] cc = new int[0x2000];
    private int audioCount;

    TsMuxer(int videoType, int audioRate, Sink sink) {
        this.videoType = videoType;
        this.hasAudio = audioRate > 0;
        this.sink = sink;
        this.sfIndex = sampleRateIndex(audioRate);
        this.pcrPid = videoType != 0 ? PID_VIDEO : PID_AUDIO;
    }

    private static int sampleRateIndex(int rate) {
        switch (rate) {
            case 48000: return 3;
            case 44100: return 4;
            case 32000: return 5;
            case 24000: return 6;
            case 22050: return 7;
            case 16000: return 8;
            case 8000: return 11;
            default: return 3;
        }
    }

    synchronized void writeVideo(byte[] es, long ptsUs, boolean key) {
        if (videoType == 0) return;
        ByteArrayOutputStream o = new ByteArrayOutputStream(es.length + es.length / 100 + 1024);
        if (key) writeTables(o);
        byte[] pes = pes(0xE0, es, 0, ptsUs);
        packetize(o, PID_VIDEO, pes, pcrPid == PID_VIDEO ? ptsUs : -1, key);
        sink.onChunk(o.toByteArray(), key);
    }

    synchronized void writeAudio(byte[] aac, long ptsUs) {
        if (!hasAudio) return;
        int frameLen = aac.length + 7;
        byte[] es = new byte[frameLen];
        es[0] = (byte) 0xFF;
        es[1] = (byte) 0xF1;
        es[2] = (byte) ((1 << 6) | (sfIndex << 2));            // AAC LC, 1 канал
        es[3] = (byte) ((1 << 6) | (frameLen >> 11));
        es[4] = (byte) (frameLen >> 3);
        es[5] = (byte) (((frameLen & 7) << 5) | 0x1F);
        es[6] = (byte) 0xFC;
        System.arraycopy(aac, 0, es, 7, aac.length);

        ByteArrayOutputStream o = new ByteArrayOutputStream(es.length + 512);
        boolean key = false;
        if (videoType == 0 && audioCount++ % 20 == 0) {
            writeTables(o);
            key = true;
        }
        byte[] pes = pes(0xC0, es, es.length, ptsUs);
        packetize(o, PID_AUDIO, pes, pcrPid == PID_AUDIO ? ptsUs : -1, false);
        sink.onChunk(o.toByteArray(), key);
    }

    private static byte[] pes(int streamId, byte[] es, int esLenField, long ptsUs) {
        long pts = ((ptsUs * 9 / 100) + MUX_DELAY_US * 9 / 100) & MASK33;
        byte[] p = new byte[14 + es.length];
        p[0] = 0;
        p[1] = 0;
        p[2] = 1;
        p[3] = (byte) streamId;
        int len = esLenField == 0 ? 0 : 3 + 5 + esLenField;
        p[4] = (byte) (len >> 8);
        p[5] = (byte) len;
        p[6] = (byte) 0x80;
        p[7] = (byte) 0x80;
        p[8] = 5;
        p[9] = (byte) (0x21 | (((pts >> 30) & 7) << 1));
        p[10] = (byte) (pts >> 22);
        p[11] = (byte) ((((pts >> 15) & 0x7F) << 1) | 1);
        p[12] = (byte) (pts >> 7);
        p[13] = (byte) (((pts & 0x7F) << 1) | 1);
        System.arraycopy(es, 0, p, 14, es.length);
        return p;
    }

    private void packetize(ByteArrayOutputStream o, int pid, byte[] data, long pcrUs, boolean random) {
        int off = 0;
        boolean first = true;
        byte[] pkt = new byte[188];
        while (off < data.length) {
            int remaining = data.length - off;
            boolean withPcr = first && pcrUs >= 0;
            int base = withPcr ? 8 : 0;            // размер adaptation field вместе с байтом длины
            int space = 184 - base;
            int payload = Math.min(remaining, space);
            int afTotal = base + (space - payload);  // добиваем стаффингом до 184
            java.util.Arrays.fill(pkt, (byte) 0xFF);
            pkt[0] = 0x47;
            pkt[1] = (byte) ((first ? 0x40 : 0) | (pid >> 8));
            pkt[2] = (byte) pid;
            pkt[3] = (byte) ((afTotal > 0 ? 0x30 : 0x10) | (cc[pid]++ & 0x0F));
            int pos = 4;
            if (afTotal > 0) {
                pkt[pos] = (byte) (afTotal - 1);
                if (afTotal > 1) {
                    int flags = 0;
                    if (withPcr) flags |= 0x10;
                    if (first && random && withPcr) flags |= 0x40;
                    pkt[pos + 1] = (byte) flags;
                    if (withPcr) {
                        long b = (pcrUs * 9 / 100) & MASK33;
                        pkt[pos + 2] = (byte) (b >> 25);
                        pkt[pos + 3] = (byte) (b >> 17);
                        pkt[pos + 4] = (byte) (b >> 9);
                        pkt[pos + 5] = (byte) (b >> 1);
                        pkt[pos + 6] = (byte) (((b & 1) << 7) | 0x7E);
                        pkt[pos + 7] = 0;
                    }
                }
                pos += afTotal;
            }
            System.arraycopy(data, off, pkt, pos, payload);
            off += payload;
            first = false;
            o.write(pkt, 0, 188);
        }
    }

    private void writeTables(ByteArrayOutputStream o) {
        // PAT: [0]=pointer_field, секция с [1]
        byte[] pat = new byte[17];
        pat[1] = 0x00;
        pat[2] = (byte) 0xB0;
        pat[3] = 13;
        pat[5] = 1;
        pat[6] = (byte) 0xC1;
        pat[10] = 1;
        pat[11] = (byte) (0xE0 | (PID_PMT >> 8));
        pat[12] = (byte) PID_PMT;
        putCrc(pat, 1, 13);
        psi(o, 0, pat);

        // PMT
        int streams = (videoType != 0 ? 1 : 0) + (hasAudio ? 1 : 0);
        int secLen = 9 + 5 * streams + 4;
        byte[] pmt = new byte[4 + secLen];
        pmt[1] = 0x02;
        pmt[2] = (byte) (0xB0 | (secLen >> 8));
        pmt[3] = (byte) secLen;
        pmt[5] = 1;
        pmt[6] = (byte) 0xC1;
        pmt[9] = (byte) (0xE0 | (pcrPid >> 8));
        pmt[10] = (byte) pcrPid;
        pmt[11] = (byte) 0xF0;
        int p = 13;
        if (videoType != 0) p = stream(pmt, p, videoType, PID_VIDEO);
        if (hasAudio) p = stream(pmt, p, STREAM_AAC, PID_AUDIO);
        putCrc(pmt, 1, p);
        psi(o, PID_PMT, pmt);
    }

    private static int stream(byte[] b, int p, int type, int pid) {
        b[p++] = (byte) type;
        b[p++] = (byte) (0xE0 | (pid >> 8));
        b[p++] = (byte) pid;
        b[p++] = (byte) 0xF0;
        b[p++] = 0;
        return p;
    }

    /** data начинается с pointer_field. */
    private void psi(ByteArrayOutputStream o, int pid, byte[] data) {
        byte[] pkt = new byte[188];
        java.util.Arrays.fill(pkt, (byte) 0xFF);
        pkt[0] = 0x47;
        pkt[1] = (byte) (0x40 | (pid >> 8));
        pkt[2] = (byte) pid;
        pkt[3] = (byte) (0x10 | (cc[pid]++ & 0x0F));
        System.arraycopy(data, 0, pkt, 4, data.length);
        o.write(pkt, 0, 188);
    }

    private static void putCrc(byte[] b, int start, int end) {
        int crc = 0xFFFFFFFF;
        for (int i = start; i < end; i++) {
            crc ^= (b[i] & 0xFF) << 24;
            for (int k = 0; k < 8; k++) {
                crc = (crc & 0x80000000) != 0 ? (crc << 1) ^ 0x04C11DB7 : crc << 1;
            }
        }
        b[end] = (byte) (crc >> 24);
        b[end + 1] = (byte) (crc >> 16);
        b[end + 2] = (byte) (crc >> 8);
        b[end + 3] = (byte) crc;
    }
}

package com.github.ulresh.usb_camera;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Захват звука с микрофона: измеритель уровня и (по желанию) кодирование в AAC. */
final class AudioCapture {
    interface Callback {
        void onFrame(byte[] aac, long ptsUs);

        void onLevel(int percent);

        void onError(String message);
    }

    private static final int FRAME_SAMPLES = 1024;

    private final int rate;
    private final boolean encode;
    private final Callback callback;
    private volatile boolean running;
    private volatile float gain = 1f;
    private Thread thread;

    AudioCapture(int rate, boolean encode, Callback callback) {
        this.rate = rate;
        this.encode = encode;
        this.callback = callback;
    }

    /** Программное усиление микрофона, дБ. */
    void setGainDb(int db) {
        gain = (float) Math.pow(10, db / 20.0);
    }

    void start() {
        running = true;
        thread = new Thread(this::run, "audio-capture");
        thread.start();
    }

    void stop() {
        running = false;
        try {
            if (thread != null) thread.join(2000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    @SuppressWarnings("MissingPermission")
    private void run() {
        AudioRecord rec = null;
        MediaCodec codec = null;
        try {
            int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IOException("частота " + rate + " Гц не поддерживается");
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME_SAMPLES * 8));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("микрофон недоступен");
            if (encode) {
                MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1);
                f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                f.setInteger(MediaFormat.KEY_BIT_RATE, 64_000);
                f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_SAMPLES * 2 * 4);
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                codec.start();
            }
            rec.startRecording();

            byte[] pcm = new byte[FRAME_SAMPLES * 2];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long samples = 0;
            while (running) {
                int n = 0;
                while (n < pcm.length && running) {
                    int r = rec.read(pcm, n, pcm.length - n);
                    if (r < 0) throw new IOException("ошибка чтения микрофона: " + r);
                    n += r;
                }
                if (n < pcm.length) break;
                applyGain(pcm);
                callback.onLevel(level(pcm));
                if (codec != null) {
                    int i = codec.dequeueInputBuffer(20_000);
                    if (i >= 0) {
                        ByteBuffer in = codec.getInputBuffer(i);
                        in.clear();
                        in.put(pcm, 0, n);
                        codec.queueInputBuffer(i, 0, n, samples * 1_000_000L / rate, 0);
                    }
                    samples += FRAME_SAMPLES;
                    drain(codec, info);
                }
            }
        } catch (IOException | RuntimeException e) {
            if (running) callback.onError("Микрофон: " + e.getMessage());
        } finally {
            if (rec != null) {
                try {
                    rec.stop();
                } catch (RuntimeException ignored) {
                }
                rec.release();
            }
            if (codec != null) {
                try {
                    codec.stop();
                } catch (RuntimeException ignored) {
                }
                codec.release();
            }
        }
    }

    private void drain(MediaCodec codec, MediaCodec.BufferInfo info) {
        int o;
        while ((o = codec.dequeueOutputBuffer(info, 0)) >= 0) {
            ByteBuffer b = codec.getOutputBuffer(o);
            if (b != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                byte[] d = new byte[info.size];
                b.position(info.offset);
                b.limit(info.offset + info.size);
                b.get(d);
                callback.onFrame(d, info.presentationTimeUs);
            }
            codec.releaseOutputBuffer(o, false);
        }
    }

    private void applyGain(byte[] pcm) {
        float g = gain;
        if (g == 1f) return;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int v = Math.round((short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8)) * g);
            v = Math.max(-32768, Math.min(32767, v));
            pcm[i] = (byte) v;
            pcm[i + 1] = (byte) (v >> 8);
        }
    }

    /** Пиковый уровень 0..100 по логарифмической шкале (от -60 дБ до 0 дБ). */
    private static int level(byte[] pcm) {
        int peak = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int v = Math.abs((short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8)));
            if (v > peak) peak = v;
        }
        if (peak == 0) return 0;
        double db = 20 * Math.log10(peak / 32768.0);
        return (int) Math.max(0, Math.min(100, (db + 60) / 60 * 100));
    }
}

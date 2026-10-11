package com.github.ulresh.usb_camera;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Аппаратный видеокодер (H.264/H.265) с входной Surface. */
final class VideoEncoder {
    interface Callback {
        void onFrame(byte[] data, long ptsUs, boolean key);

        void onError(String message);
    }

    private final MediaCodec codec;
    private final Surface surface;
    private final Callback callback;
    private volatile boolean running;
    private Thread thread;
    private byte[] config;

    VideoEncoder(String mime, int width, int height, int fps, int bitrate, Callback callback) throws IOException {
        this.callback = callback;
        MediaFormat f = MediaFormat.createVideoFormat(mime, width, height);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        codec = MediaCodec.createEncoderByType(mime);
        try {
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = codec.createInputSurface();
        } catch (RuntimeException e) {
            codec.release();
            throw e;
        }
    }

    Surface getSurface() {
        return surface;
    }

    void start() {
        codec.start();
        running = true;
        thread = new Thread(this::drain, "video-encoder");
        thread.start();
    }

    private void drain() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (running) {
                int i = codec.dequeueOutputBuffer(info, 20_000);
                if (i < 0) continue;
                ByteBuffer b = codec.getOutputBuffer(i);
                if (b != null && info.size > 0) {
                    byte[] d = new byte[info.size];
                    b.position(info.offset);
                    b.limit(info.offset + info.size);
                    b.get(d);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        config = d;
                    } else {
                        boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                        if (key && config != null) {
                            byte[] all = new byte[config.length + d.length];
                            System.arraycopy(config, 0, all, 0, config.length);
                            System.arraycopy(d, 0, all, config.length, d.length);
                            d = all;
                        }
                        callback.onFrame(d, info.presentationTimeUs, key);
                    }
                }
                codec.releaseOutputBuffer(i, false);
            }
        } catch (RuntimeException e) {
            if (running) callback.onError("Видеокодер: " + e.getMessage());
        }
    }

    void stop() {
        running = false;
        try {
            if (thread != null) thread.join(1000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        try {
            codec.stop();
        } catch (RuntimeException ignored) {
        }
        codec.release();
        surface.release();
    }
}

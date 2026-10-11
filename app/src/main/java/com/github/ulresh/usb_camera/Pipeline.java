package com.github.ulresh.usb_camera;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaFormat;
import android.os.Handler;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.util.ArrayList;
import java.util.List;

/** Одна запущенная конфигурация: камера, предпросмотр, кодеры, мультиплексор и HTTP-сервер. */
final class Pipeline {
    static final class Settings {
        String cameraId;
        Size size;
        Range<Integer> fps;
        String mime;          // MediaFormat.MIMETYPE_VIDEO_AVC / _HEVC
        int bitrate;
        boolean video;
        boolean audio;
        boolean meter;
        boolean audioPermitted;
        int audioRate;
        int gainDb;
        int port;
        SurfaceTexture texture;
    }

    interface Host {
        void onStatus(String text);

        void onLevel(int percent);
    }

    private final Context context;
    private final Handler cameraHandler;
    private final Settings s;
    private final Host host;

    private HttpStreamServer server;
    private TsMuxer muxer;
    private VideoEncoder venc;
    private AudioCapture audio;
    private CameraController camera;
    private volatile boolean stopped;

    private volatile int clients;
    private String errors = "";
    private boolean cameraReady;
    private long videoBase = -1;
    private long videoBaseNow;
    private long audioBaseNow = -1;

    Pipeline(Context context, Handler cameraHandler, Settings settings, Host host) {
        this.context = context;
        this.cameraHandler = cameraHandler;
        this.s = settings;
        this.host = host;
    }

    void start() {
        boolean streamVideo = s.video;
        boolean streamAudio = s.audio && s.audioPermitted;
        if (s.audio && !s.audioPermitted) addError("нет разрешения на микрофон");

        if (streamVideo || streamAudio) {
            try {
                server = new HttpStreamServer(s.port, n -> {
                    clients = n;
                    publish();
                });
                server.start();
                muxer = new TsMuxer(
                        streamVideo ? (MediaFormat.MIMETYPE_VIDEO_HEVC.equals(s.mime)
                                ? TsMuxer.VIDEO_HEVC : TsMuxer.VIDEO_H264) : 0,
                        streamAudio ? s.audioRate : 0,
                        (d, key) -> server.broadcast(d, key));
            } catch (Exception e) {
                addError("сервер на порту " + s.port + ": " + e.getMessage());
                if (server != null) server.stop();
                server = null;
                muxer = null;
                streamVideo = false;
                streamAudio = false;
            }
        }

        List<Surface> surfaces = new ArrayList<>();
        s.texture.setDefaultBufferSize(s.size.getWidth(), s.size.getHeight());
        surfaces.add(new Surface(s.texture));

        if (streamVideo) {
            try {
                venc = new VideoEncoder(s.mime, s.size.getWidth(), s.size.getHeight(),
                        s.fps.getUpper(), s.bitrate, new VideoEncoder.Callback() {
                    @Override
                    public void onFrame(byte[] data, long ptsUs, boolean key) {
                        long now = System.nanoTime() / 1000;
                        if (videoBase < 0) {
                            videoBase = ptsUs;
                            videoBaseNow = now;
                        }
                        muxer.writeVideo(data, videoBaseNow + ptsUs - videoBase, key);
                    }

                    @Override
                    public void onError(String message) {
                        addError(message);
                    }
                });
                surfaces.add(venc.getSurface());
                venc.start();
            } catch (Exception e) {
                addError("видеокодер: " + e.getMessage());
                venc = null;
            }
        }

        if ((streamAudio || s.meter) && s.audioPermitted) {
            final boolean enc = streamAudio;
            audio = new AudioCapture(s.audioRate, enc, new AudioCapture.Callback() {
                @Override
                public void onFrame(byte[] aac, long ptsUs) {
                    if (audioBaseNow < 0) audioBaseNow = System.nanoTime() / 1000;
                    muxer.writeAudio(aac, audioBaseNow + ptsUs);
                }

                @Override
                public void onLevel(int percent) {
                    host.onLevel(percent);
                }

                @Override
                public void onError(String message) {
                    addError(message);
                }
            });
            audio.setGainDb(s.gainDb);
            audio.start();
        }

        camera = new CameraController(context, cameraHandler, new CameraController.Listener() {
            @Override
            public void onCameraStarted() {
                cameraReady = true;
                publish();
            }

            @Override
            public void onCameraError(String message) {
                addError(message);
            }
        });
        camera.open(s.cameraId, surfaces, venc != null, s.fps);
        publish();
    }

    void setGainDb(int db) {
        if (audio != null) audio.setGainDb(db);
    }

    void stop() {
        stopped = true;
        if (camera != null) camera.close();
        if (venc != null) venc.stop();
        if (audio != null) audio.stop();
        if (server != null) server.stop();
        host.onLevel(0);
    }

    private synchronized void addError(String e) {
        if (stopped) return;
        errors += (errors.isEmpty() ? "" : "\n") + "Ошибка: " + e;
        publish();
    }

    private synchronized void publish() {
        if (stopped) return;
        StringBuilder b = new StringBuilder();
        b.append(cameraReady ? "Камера работает" : "Запуск камеры…");
        if (server != null) {
            b.append("\nТрансляция MPEG-TS (");
            if (venc != null) {
                b.append(MediaFormat.MIMETYPE_VIDEO_HEVC.equals(s.mime) ? "H.265" : "H.264");
            }
            if (muxer != null && s.audio && s.audioPermitted) b.append(venc != null ? " + AAC" : "AAC");
            b.append("), клиентов: ").append(clients);
            for (String a : HttpStreamServer.localAddresses()) {
                b.append("\nhttp://").append(a).append(':').append(s.port).append('/');
            }
        } else {
            b.append("\nТрансляция выключена");
        }
        if (!errors.isEmpty()) b.append('\n').append(errors);
        host.onStatus(b.toString());
    }
}

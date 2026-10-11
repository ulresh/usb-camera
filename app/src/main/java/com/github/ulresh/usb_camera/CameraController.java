package com.github.ulresh.usb_camera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Handler;
import android.util.Range;
import android.view.Surface;

import java.util.List;

/** Открывает камеру и запускает захват на заданные поверхности. */
final class CameraController {
    interface Listener {
        void onCameraStarted();

        void onCameraError(String message);
    }

    private final CameraManager manager;
    private final Handler handler;
    private final Listener listener;
    private volatile boolean closed;
    private CameraDevice device;
    private CameraCaptureSession session;

    CameraController(Context context, Handler handler, Listener listener) {
        this.manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        this.handler = handler;
        this.listener = listener;
    }

    @SuppressLint("MissingPermission")
    void open(String id, List<Surface> surfaces, boolean record, Range<Integer> fps) {
        try {
            manager.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice cd) {
                    synchronized (CameraController.this) {
                        if (closed) {
                            cd.close();
                            return;
                        }
                        device = cd;
                    }
                    startSession(cd, surfaces, record, fps);
                }

                @Override
                public void onDisconnected(CameraDevice cd) {
                    cd.close();
                    if (!closed) listener.onCameraError("Камера отключена");
                }

                @Override
                public void onError(CameraDevice cd, int error) {
                    cd.close();
                    if (!closed) listener.onCameraError("Ошибка камеры: " + error);
                }
            }, handler);
        } catch (CameraAccessException | RuntimeException e) {
            listener.onCameraError("Не удалось открыть камеру: " + e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private void startSession(CameraDevice cd, List<Surface> surfaces, boolean record, Range<Integer> fps) {
        try {
            CaptureRequest.Builder b = cd.createCaptureRequest(
                    record ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW);
            for (Surface s : surfaces) b.addTarget(s);
            if (fps != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
            cd.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    synchronized (CameraController.this) {
                        if (closed) {
                            s.close();
                            return;
                        }
                        session = s;
                    }
                    try {
                        s.setRepeatingRequest(b.build(), null, handler);
                        listener.onCameraStarted();
                    } catch (CameraAccessException | RuntimeException e) {
                        listener.onCameraError("Не удалось начать съёмку: " + e.getMessage());
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    if (!closed) listener.onCameraError("Камера не поддерживает выбранные параметры");
                }
            }, handler);
        } catch (CameraAccessException | RuntimeException e) {
            listener.onCameraError("Ошибка камеры: " + e.getMessage());
        }
    }

    synchronized void close() {
        closed = true;
        if (session != null) {
            session.close();
            session = null;
        }
        if (device != null) {
            device.close();
            device = null;
        }
    }
}

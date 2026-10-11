package com.github.ulresh.usb_camera;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.view.TextureView;
import android.view.WindowManager;

/** TextureView, сохраняющий пропорции видео и учитывающий поворот экрана. */
final class PreviewView extends TextureView {
    private int videoW = 16;
    private int videoH = 9;
    private int sensor;

    PreviewView(Context context) {
        super(context);
    }

    void setVideo(int w, int h, int sensorOrientation) {
        videoW = w;
        videoH = h;
        sensor = sensorOrientation;
        requestLayout();
        post(this::updateTransform);
    }

    @SuppressWarnings("deprecation")
    private int rotation() {
        return ((WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay().getRotation();
    }

    /** Размеры изображения в естественной ориентации экрана (после поворота, заданного сенсором). */
    private int natW() {
        return sensor % 180 == 0 ? videoW : videoH;
    }

    private int natH() {
        return sensor % 180 == 0 ? videoH : videoW;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int maxW = MeasureSpec.getSize(widthSpec);
        int maxH = MeasureSpec.getSize(heightSpec);
        boolean odd = rotation() % 2 == 1;
        int aw = odd ? natH() : natW();
        int ah = odd ? natW() : natH();
        int w;
        int h;
        if ((long) maxW * ah <= (long) maxH * aw) {
            w = maxW;
            h = (int) ((long) maxW * ah / aw);
        } else {
            h = maxH;
            w = (int) ((long) maxH * aw / ah);
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateTransform();
    }

    private void updateTransform() {
        int rot = rotation();
        float vw = getWidth();
        float vh = getHeight();
        if (vw == 0 || vh == 0) return;
        Matrix m = new Matrix();
        float cx = vw / 2f;
        float cy = vh / 2f;
        if (rot % 2 == 1) {
            RectF view = new RectF(0, 0, vw, vh);
            RectF buf = new RectF(0, 0, natW(), natH());
            buf.offset(cx - buf.centerX(), cy - buf.centerY());
            m.setRectToRect(view, buf, Matrix.ScaleToFit.FILL);
            float scale = Math.max(vh / natW(), vw / natH());
            m.postScale(scale, scale, cx, cy);
            m.postRotate(90 * (rot - 2), cx, cy);
        } else if (rot == 2) {
            m.postRotate(180, cx, cy);
        }
        setTransform(m);
    }
}

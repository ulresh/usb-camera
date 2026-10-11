package com.github.ulresh.usb_camera;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.InputType;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends Activity implements Pipeline.Host {
    private static final String[] BITRATE_LABELS = {"1 Мбит/с", "2 Мбит/с", "4 Мбит/с", "8 Мбит/с", "16 Мбит/с"};
    private static final int[] BITRATES = {1_000_000, 2_000_000, 4_000_000, 8_000_000, 16_000_000};
    private static final String[] RATE_LABELS = {"16000 Гц", "44100 Гц", "48000 Гц"};
    private static final int[] RATES = {16000, 44100, 48000};
    private static final int[] GAINS_DB = {-12, -6, 0, 6, 12, 18, 24, 30};
    private static final int REQ_PERMISSIONS = 1;

    private static final class Cam {
        String id;
        String label;
        int sensor;
        List<Size> sizes = new ArrayList<>();
        List<Range<Integer>> fps = new ArrayList<>();

        @Override
        public String toString() {
            return label;
        }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraManager cameraManager;
    private SharedPreferences prefs;

    private PreviewView preview;
    private Spinner spCamera, spCodec, spSize, spFps, spBitrate, spRate, spGain;
    private Switch swVideo, swAudio, swMeter;
    private EditText etPort;
    private TextView tvStatus;
    private ProgressBar levelBar;

    private final List<Cam> cams = new ArrayList<>();
    private boolean loading;
    private boolean resumed;
    private Pipeline pipeline;
    private long lastLevelPost;

    private final Runnable applyRunnable = this::apply;

    private final CameraManager.AvailabilityCallback availability = new CameraManager.AvailabilityCallback() {
        @Override
        public void onCameraAvailable(String id) {
            ui.post(MainActivity.this::onCameraListMaybeChanged);
        }

        @Override
        public void onCameraUnavailable(String id) {
            ui.post(MainActivity.this::onCameraListMaybeChanged);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
    }

    // ---------------------------------------------------------------- UI

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void buildUi() {
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });

        FrameLayout previewBox = new FrameLayout(this);
        previewBox.setBackgroundColor(0xFF000000);
        preview = new PreviewView(this);
        previewBox.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addView(previewBox, new LinearLayout.LayoutParams(
                landscape ? 0 : ViewGroup.LayoutParams.MATCH_PARENT,
                landscape ? ViewGroup.LayoutParams.MATCH_PARENT : 0, 1f));
        preview.setSurfaceTextureListener(new android.view.TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                scheduleApply();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                stopPipeline();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(8), dp(12), dp(8));

        spCamera = addSpinner(panel, "Камера");
        spCodec = addSpinner(panel, "Кодировка видео");
        spSize = addSpinner(panel, "Разрешение");
        spFps = addSpinner(panel, "Частота кадров");
        spBitrate = addSpinner(panel, "Битрейт видео");
        spRate = addSpinner(panel, "Частота дискретизации звука");
        spGain = addSpinner(panel, "Чувствительность микрофона (усиление)");
        String[] gainLabels = new String[GAINS_DB.length];
        for (int i = 0; i < GAINS_DB.length; i++) {
            gainLabels[i] = (GAINS_DB[i] > 0 ? "+" : "") + GAINS_DB[i] + " дБ";
        }
        spGain.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, gainLabels));
        spGain.setSelection(prefs.getInt("gain", 2));
        spBitrate.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, BITRATE_LABELS));
        spRate.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, RATE_LABELS));
        spBitrate.setSelection(prefs.getInt("bitrate", 2));
        spRate.setSelection(prefs.getInt("rate", 2));

        etPort = new EditText(this);
        etPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        etPort.setSingleLine();
        etPort.setText(String.valueOf(prefs.getInt("port", 8080)));
        etPort.setOnEditorActionListener((v, a, e) -> {
            scheduleApply();
            return false;
        });
        etPort.setOnFocusChangeListener((v, f) -> {
            if (!f) scheduleApply();
        });
        addLabel(panel, "Порт трансляции");
        panel.addView(etPort);

        swVideo = addSwitch(panel, "Трансляция видео в сеть", prefs.getBoolean("video", false));
        swAudio = addSwitch(panel, "Трансляция звука в сеть", prefs.getBoolean("audio", false));
        swMeter = addSwitch(panel, "Индикатор уровня микрофона", prefs.getBoolean("meter", true));

        levelBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        levelBar.setMax(100);
        panel.addView(levelBar);

        tvStatus = new TextView(this);
        tvStatus.setTextIsSelectable(true);
        tvStatus.setPadding(0, dp(8), 0, 0);
        panel.addView(tvStatus);

        AdapterView.OnItemSelectedListener l = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (loading) return;
                if (p == spGain) {
                    // усиление применяется на лету, без перезапуска камеры
                    prefs.edit().putInt("gain", pos).apply();
                    if (pipeline != null) pipeline.setGainDb(GAINS_DB[pos]);
                    return;
                }
                if (p == spCamera) {
                    loading = true;
                    fillCameraCaps();
                    loading = false;
                }
                scheduleApply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        };
        for (Spinner sp : new Spinner[]{spCamera, spCodec, spSize, spFps, spBitrate, spRate, spGain}) {
            sp.setOnItemSelectedListener(l);
        }
        for (Switch sw : new Switch[]{swVideo, swAudio, swMeter}) {
            sw.setOnCheckedChangeListener((b, c) -> scheduleApply());
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(panel);
        root.addView(scroll, new LinearLayout.LayoutParams(
                landscape ? 0 : ViewGroup.LayoutParams.MATCH_PARENT,
                landscape ? ViewGroup.LayoutParams.MATCH_PARENT : 0, 1f));
        setContentView(root);
    }

    private void addLabel(LinearLayout p, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setPadding(0, dp(6), 0, 0);
        p.addView(t);
    }

    private Spinner addSpinner(LinearLayout p, String label) {
        addLabel(p, label);
        Spinner s = new Spinner(this);
        p.addView(s);
        return s;
    }

    private Switch addSwitch(LinearLayout p, String text, boolean checked) {
        Switch s = new Switch(this);
        s.setText(text);
        s.setChecked(checked);
        s.setPadding(0, dp(6), 0, dp(6));
        p.addView(s);
        return s;
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        cameraThread = new HandlerThread("camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                    REQ_PERMISSIONS);
        }
        cameraManager.registerAvailabilityCallback(availability, ui);
        loadCameras();
    }

    @Override
    protected void onPause() {
        resumed = false;
        ui.removeCallbacks(applyRunnable);
        cameraManager.unregisterAvailabilityCallback(availability);
        stopPipeline();
        cameraThread.quitSafely();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        loadCameras();
        scheduleApply();
    }

    private boolean granted(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    // ---------------------------------------------------------------- cameras

    private void onCameraListMaybeChanged() {
        if (!resumed) return;
        try {
            if (cameraManager.getCameraIdList().length != cams.size()) {
                loadCameras();
                scheduleApply();
            }
        } catch (CameraAccessException ignored) {
        }
    }

    private void loadCameras() {
        List<Cam> list = new ArrayList<>();
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics cc = cameraManager.getCameraCharacteristics(id);
                StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) continue;
                Cam c = new Cam();
                c.id = id;
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                String f = facing == null ? "" : facing == CameraCharacteristics.LENS_FACING_BACK ? "задняя"
                        : facing == CameraCharacteristics.LENS_FACING_FRONT ? "фронтальная" : "USB/внешняя";
                c.label = "Камера " + id + (f.isEmpty() ? "" : " (" + f + ")");
                Integer so = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
                c.sensor = so == null ? 0 : so;
                for (Size sz : map.getOutputSizes(SurfaceTexture.class)) {
                    if (sz.getWidth() <= 4096 && sz.getHeight() <= 4096) c.sizes.add(sz);
                }
                c.sizes.sort((a, b) -> Integer.compare(b.getWidth() * b.getHeight(), a.getWidth() * a.getHeight()));
                Range<Integer>[] ranges = cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                if (ranges != null) {
                    c.fps.addAll(Arrays.asList(ranges));
                    c.fps.sort((a, b) -> a.getUpper() != b.getUpper()
                            ? Integer.compare(b.getUpper(), a.getUpper())
                            : Integer.compare(b.getLower(), a.getLower()));
                }
                if (c.fps.isEmpty()) c.fps.add(new Range<>(30, 30));
                if (!c.sizes.isEmpty()) list.add(c);
            }
        } catch (CameraAccessException e) {
            tvStatus.setText("Ошибка доступа к камерам: " + e.getMessage());
        }
        cams.clear();
        cams.addAll(list);

        String wanted = prefs.getString("camera", "");
        loading = true;
        spCamera.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, cams));
        for (int i = 0; i < cams.size(); i++) {
            if (cams.get(i).id.equals(wanted)) spCamera.setSelection(i);
        }
        fillCameraCaps();
        loading = false;
    }

    private static boolean hasEncoder(String mime) {
        for (android.media.MediaCodecInfo i : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!i.isEncoder()) continue;
            for (String t : i.getSupportedTypes()) if (t.equalsIgnoreCase(mime)) return true;
        }
        return false;
    }

    private static String fpsLabel(Range<Integer> r) {
        return r.getLower().equals(r.getUpper()) ? r.getUpper() + " к/с" : r.getLower() + "–" + r.getUpper() + " к/с";
    }

    private static String sizeLabel(Size s) {
        return s.getWidth() + "×" + s.getHeight();
    }

    private void fillCameraCaps() {
        Cam c = selectedCam();
        List<String> codecs = new ArrayList<>();
        codecs.add("H.264");
        if (hasEncoder(MediaFormat.MIMETYPE_VIDEO_HEVC)) codecs.add("H.265");
        setItems(spCodec, codecs, prefs.getString("codec", "H.264"));

        List<String> sizes = new ArrayList<>();
        List<String> fps = new ArrayList<>();
        if (c != null) {
            for (Size s : c.sizes) sizes.add(sizeLabel(s));
            for (Range<Integer> r : c.fps) fps.add(fpsLabel(r));
        }
        // по умолчанию — 1280×720, если есть
        String defSize = sizes.contains("1280×720") ? "1280×720" : sizes.isEmpty() ? "" : sizes.get(0);
        setItems(spSize, sizes, prefs.getString("size", defSize));
        setItems(spFps, fps, prefs.getString("fps", ""));
    }

    private void setItems(Spinner sp, List<String> items, String wanted) {
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items));
        int i = items.indexOf(wanted);
        if (i >= 0) sp.setSelection(i);
    }

    private Cam selectedCam() {
        int i = spCamera.getSelectedItemPosition();
        return i >= 0 && i < cams.size() ? cams.get(i) : null;
    }

    // ---------------------------------------------------------------- pipeline

    private void scheduleApply() {
        ui.removeCallbacks(applyRunnable);
        ui.postDelayed(applyRunnable, 300);
    }

    private void stopPipeline() {
        if (pipeline != null) {
            pipeline.stop();
            pipeline = null;
        }
    }

    private int parsePort() {
        try {
            int p = Integer.parseInt(etPort.getText().toString().trim());
            if (p >= 1024 && p <= 65535) return p;
        } catch (NumberFormatException ignored) {
        }
        return 8080;
    }

    private void apply() {
        if (!resumed) return;
        stopPipeline();
        Cam c = selectedCam();
        if (!granted(Manifest.permission.CAMERA)) {
            tvStatus.setText("Нет разрешения на камеру");
            return;
        }
        if (c == null || spSize.getSelectedItemPosition() < 0 || spFps.getSelectedItemPosition() < 0) {
            tvStatus.setText("Камеры не найдены");
            return;
        }
        if (!preview.isAvailable()) return; // придёт onSurfaceTextureAvailable

        Pipeline.Settings s = new Pipeline.Settings();
        s.cameraId = c.id;
        s.size = c.sizes.get(spSize.getSelectedItemPosition());
        s.fps = c.fps.get(spFps.getSelectedItemPosition());
        boolean h265 = "H.265".equals(spCodec.getSelectedItem());
        s.mime = h265 ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
        s.bitrate = BITRATES[spBitrate.getSelectedItemPosition()];
        s.video = swVideo.isChecked();
        s.audio = swAudio.isChecked();
        s.meter = swMeter.isChecked();
        s.audioPermitted = granted(Manifest.permission.RECORD_AUDIO);
        s.audioRate = RATES[spRate.getSelectedItemPosition()];
        s.gainDb = GAINS_DB[spGain.getSelectedItemPosition()];
        s.port = parsePort();
        s.texture = preview.getSurfaceTexture();

        prefs.edit()
                .putString("camera", c.id)
                .putString("codec", h265 ? "H.265" : "H.264")
                .putString("size", sizeLabel(s.size))
                .putString("fps", fpsLabel(s.fps))
                .putInt("bitrate", spBitrate.getSelectedItemPosition())
                .putInt("rate", spRate.getSelectedItemPosition())
                .putInt("port", s.port)
                .putBoolean("video", s.video)
                .putBoolean("audio", s.audio)
                .putBoolean("meter", s.meter)
                .apply();

        preview.setVideo(s.size.getWidth(), s.size.getHeight(), c.sensor);
        pipeline = new Pipeline(this, cameraHandler, s, this);
        pipeline.start();
    }

    @Override
    public void onStatus(String text) {
        ui.post(() -> tvStatus.setText(text));
    }

    @Override
    public void onLevel(int percent) {
        long now = System.nanoTime();
        if (percent != 0 && now - lastLevelPost < 60_000_000L) return;
        lastLevelPost = now;
        ui.post(() -> levelBar.setProgress(percent));
    }
}

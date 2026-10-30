package com.cleanmyphone.launcher;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.format.Formatter;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

@SuppressWarnings("deprecation")
public class TvCamActivity extends AppCompatActivity implements SurfaceHolder.Callback, Camera.PreviewCallback {

    private SurfaceView cameraSurface;
    private SurfaceHolder surfaceHolder;
    private Camera camera;
    private int currentCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
    private boolean isTorchOn = false;

    private TvCamServer server;
    private TextView tvServerUrl;
    private TextView tvStatus;
    private LinearLayout dimOverlay;
    private TextView tvDimUrl;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private ByteArrayOutputStream jpegBuffer = new ByteArrayOutputStream(65536);
    private Rect previewRect;
    private int previewFormat = ImageFormat.NV21;
    private int previewWidth = 640;
    private int previewHeight = 480;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tv_cam);

        initViews();
        startServer();
        acquireLocks();
    }

    private void initViews() {
        cameraSurface = findViewById(R.id.cameraSurface);
        surfaceHolder = cameraSurface.getHolder();
        surfaceHolder.addCallback(this);

        tvServerUrl = findViewById(R.id.tvServerUrl);
        tvStatus = findViewById(R.id.tvStatus);
        dimOverlay = findViewById(R.id.dimOverlay);
        tvDimUrl = findViewById(R.id.tvDimUrl);

        findViewById(R.id.btnFlipCam).setOnClickListener(v -> flipCamera());
        findViewById(R.id.btnTorch).setOnClickListener(v -> toggleTorch());
        findViewById(R.id.btnDim).setOnClickListener(v -> dimOverlay.setVisibility(View.VISIBLE));
        dimOverlay.setOnClickListener(v -> dimOverlay.setVisibility(View.GONE));
        findViewById(R.id.btnExit).setOnClickListener(v -> finish());

        String ip = getLocalIpAddress();
        String url = "http://" + ip + ":8080";
        tvServerUrl.setText("📡 TV Link: " + url);
        tvDimUrl.setText(url);
    }

    private void startServer() {
        server = new TvCamServer(8080);
        server.setListener(count -> new Handler(Looper.getMainLooper()).post(() -> {
            if (count > 0) {
                tvStatus.setText("🟢 TV Connected (" + count + ")");
                tvStatus.setTextColor(0xFF00E676);
            } else {
                tvStatus.setText("🔴 Waiting for TV...");
                tvStatus.setTextColor(0xFFFFD60A);
            }
        }));

        try {
            server.start();
        } catch (IOException e) {
            Toast.makeText(this, "Failed to start server on port 8080", Toast.LENGTH_SHORT).show();
        }
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && (wakeLock == null || !wakeLock.isHeld())) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CleanPhone:TvCam");
                wakeLock.acquire(4 * 3600 * 1000); // 4 hours max
            }
        } catch (Exception ignored) {}

        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && (wifiLock == null || !wifiLock.isHeld())) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CleanPhone:TvCamWifi");
                wifiLock.acquire();
            }
        } catch (Exception ignored) {}
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                wakeLock = null;
            }
        } catch (Exception ignored) {}

        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
                wifiLock = null;
            }
        } catch (Exception ignored) {}
    }

    private String getLocalIpAddress() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo wi = wm.getConnectionInfo();
                int ip = wi.getIpAddress();
                return Formatter.formatIpAddress(ip);
            }
        } catch (Exception ignored) {}
        return "192.168.0.7";
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        openCamera();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (camera != null) {
            try {
                camera.setPreviewDisplay(holder);
                camera.startPreview();
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        releaseCamera();
    }

    private void openCamera() {
        releaseCamera();
        try {
            camera = Camera.open(currentCameraId);
            Camera.Parameters params = camera.getParameters();

            List<Camera.Size> sizes = params.getSupportedPreviewSizes();
            if (sizes != null) {
                for (Camera.Size s : sizes) {
                    if (s.width == 640 && s.height == 480) {
                        previewWidth = 640;
                        previewHeight = 480;
                        break;
                    }
                }
            }
            params.setPreviewSize(previewWidth, previewHeight);
            params.setPreviewFormat(ImageFormat.NV21);

            List<String> focusModes = params.getSupportedFocusModes();
            if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            }

            camera.setParameters(params);
            camera.setDisplayOrientation(0); // Landscape orientation matches TV screen!

            previewFormat = ImageFormat.NV21;
            previewRect = new Rect(0, 0, previewWidth, previewHeight);

            int bufferSize = previewWidth * previewHeight * ImageFormat.getBitsPerPixel(previewFormat) / 8;
            camera.addCallbackBuffer(new byte[bufferSize]);
            camera.addCallbackBuffer(new byte[bufferSize]);
            camera.setPreviewCallbackWithBuffer(this);
            camera.setPreviewDisplay(surfaceHolder);
            camera.startPreview();
        } catch (Exception e) {
            Toast.makeText(this, "Could not open camera: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void releaseCamera() {
        if (camera != null) {
            camera.setPreviewCallbackWithBuffer(null);
            camera.stopPreview();
            camera.release();
            camera = null;
        }
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        if (server == null || data == null) return;

        try {
            jpegBuffer.reset();
            YuvImage yuv = new YuvImage(data, previewFormat, previewWidth, previewHeight, null);
            yuv.compressToJpeg(previewRect, 70, jpegBuffer); // 70% quality for optimal speed & low latency
            server.pushFrame(jpegBuffer.toByteArray());
        } catch (Exception ignored) {
        } finally {
            if (camera != null) {
                camera.addCallbackBuffer(data);
            }
        }
    }

    private void flipCamera() {
        int count = Camera.getNumberOfCameras();
        if (count > 1) {
            currentCameraId = (currentCameraId == Camera.CameraInfo.CAMERA_FACING_BACK)
                    ? Camera.CameraInfo.CAMERA_FACING_FRONT
                    : Camera.CameraInfo.CAMERA_FACING_BACK;
            openCamera();
        }
    }

    private void toggleTorch() {
        if (camera != null && currentCameraId == Camera.CameraInfo.CAMERA_FACING_BACK) {
            try {
                Camera.Parameters p = camera.getParameters();
                isTorchOn = !isTorchOn;
                p.setFlashMode(isTorchOn ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_OFF);
                camera.setParameters(p);
                TextView btnTorch = findViewById(R.id.btnTorch);
                btnTorch.setText(isTorchOn ? "🔦 ON" : "🔦 Light");
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseCamera();
        if (server != null) {
            server.stop();
        }
        releaseLocks();
    }
}

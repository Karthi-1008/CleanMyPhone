package com.cleanmyphone.launcher.game;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.text.format.Formatter;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.cleanmyphone.launcher.R;
import com.cleanmyphone.launcher.opencv.OpenCvMotionTracker;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Landscape Motion Runner Activity with Integrated Real-Time Wi-Fi TV Browser Streaming.
 *
 * Runs 60 FPS hardware game rendering on phone, streams live video to TV browser at
 * http://<ip>:8080 over local Wi-Fi, and executes ultra-low-latency body motion tracking
 * via OpenCV on the phone camera.
 */
@SuppressWarnings("deprecation")
public class MotionGameActivity extends AppCompatActivity implements TextureView.SurfaceTextureListener, Camera.PreviewCallback, OpenCvMotionTracker.MotionListener {
    private static final String TAG = "MotionGameActivity";

    private GameView gameView;
    private TextureView cameraPipTexture;
    private SoundManager soundManager;
    private TvGameServer tvGameServer;

    private Camera camera;
    private SurfaceTexture previewTexture;
    private int currentCameraId = Camera.CameraInfo.CAMERA_FACING_FRONT;
    private int previewWidth = 640;
    private int previewHeight = 480;

    private OpenCvMotionTracker motionTracker;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private final AtomicBoolean isProcessingFrame = new AtomicBoolean(false);

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        setContentView(R.layout.activity_motion_game);

        initViews();
        initAudio();
        initTvServer();
        initCameraThread();
        initOpenCvTracker();
        acquireLocks();
    }

    private void hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }
    }

    private void initViews() {
        gameView = findViewById(R.id.gameView);
        cameraPipTexture = findViewById(R.id.cameraPipTexture);
        cameraPipTexture.setSurfaceTextureListener(this);

        findViewById(R.id.btnExitGame).setOnClickListener(v -> finish());
        findViewById(R.id.btnFlipCam).setOnClickListener(v -> flipCamera());
        findViewById(R.id.btnResetTracking).setOnClickListener(v -> {
            if (motionTracker != null) {
                motionTracker.resetBaseline();
                Toast.makeText(this, "Stance Calibrated", Toast.LENGTH_SHORT).show();
            }
        });

        int numCameras = Camera.getNumberOfCameras();
        boolean hasFront = false;
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < numCameras; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                hasFront = true;
                currentCameraId = i;
                break;
            }
        }
        if (!hasFront && numCameras > 0) {
            currentCameraId = 0;
        }
    }

    private void initAudio() {
        soundManager = new SoundManager(this);
        gameView.setSoundManager(soundManager);
    }

    private void initTvServer() {
        String ip = getLocalIpAddress();
        int port = 8080;
        tvGameServer = new TvGameServer(port);

        tvGameServer.setStatusListener(count -> new Handler(Looper.getMainLooper()).post(() -> {
            if (gameView != null) {
                gameView.setTvClientCount(count);
            }
        }));

        tvGameServer.setInputListener(key -> runOnUiThread(() -> handleTvRemoteInput(key)));

        try {
            tvGameServer.start();
        } catch (IOException e) {
            Log.w(TAG, "Port 8080 busy, trying 8081: " + e.getMessage());
            port = 8081;
            tvGameServer = new TvGameServer(port);
            tvGameServer.setStatusListener(count -> new Handler(Looper.getMainLooper()).post(() -> {
                if (gameView != null) gameView.setTvClientCount(count);
            }));
            tvGameServer.setInputListener(key -> runOnUiThread(() -> handleTvRemoteInput(key)));
            try {
                tvGameServer.start();
            } catch (IOException ex) {
                Log.e(TAG, "Failed to start TvGameServer: " + ex.getMessage());
            }
        }

        String tvLink = "http://" + ip + ":" + port;
        gameView.setTvUrl(tvLink);
        gameView.setTvGameServer(tvGameServer);
        Log.i(TAG, "TV Game Stream ready at: " + tvLink);
    }

    private void handleTvRemoteInput(String key) {
        if (gameView == null || key == null) return;
        String k = key.toLowerCase();

        if (k.contains("left") || k.equals("a")) {
            gameView.setTargetLane(0);
        } else if (k.contains("right") || k.equals("d")) {
            gameView.setTargetLane(2);
        } else if (k.contains("up") || k.contains("space") || k.equals("w")) {
            gameView.jump();
        } else if (k.contains("down") || k.equals("s")) {
            gameView.slide(true);
        } else if (k.contains("enter") || k.contains("return")) {
            gameView.restartGame();
        }
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
        return "192.168.0.9";
    }

    private void initCameraThread() {
        cameraThread = new HandlerThread("CameraOpenCvThread", Thread.MAX_PRIORITY);
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void initOpenCvTracker() {
        motionTracker = new OpenCvMotionTracker(this);
        motionTracker.setMirror(isFrontCamera());
    }

    private boolean isFrontCamera() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        if (Camera.getNumberOfCameras() > currentCameraId) {
            Camera.getCameraInfo(currentCameraId, info);
            return info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT;
        }
        return true;
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && (wakeLock == null || !wakeLock.isHeld())) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CleanPhone:MotionGame");
                wakeLock.acquire(2 * 3600 * 1000);
            }
        } catch (Exception ignored) {}

        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && (wifiLock == null || !wifiLock.isHeld())) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CleanPhone:MotionGameWifi");
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

    // --- TextureView.SurfaceTextureListener Callbacks ---
    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        previewTexture = surface;
        openCamera();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        releaseCamera();
        previewTexture = null;
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {}

    private void openCamera() {
        if (previewTexture == null) return;
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
            camera.setDisplayOrientation(0); // Landscape

            int bufferSize = previewWidth * previewHeight * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
            camera.addCallbackBuffer(new byte[bufferSize]);
            camera.addCallbackBuffer(new byte[bufferSize]);
            camera.setPreviewCallbackWithBuffer(this);
            camera.setPreviewTexture(previewTexture);
            camera.startPreview();
            Log.d(TAG, "Camera preview started (" + previewWidth + "x" + previewHeight + ")");
        } catch (Exception e) {
            Log.e(TAG, "Failed to open camera: " + e.getMessage(), e);
            Toast.makeText(this, "Camera error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void releaseCamera() {
        if (camera != null) {
            try {
                camera.setPreviewCallbackWithBuffer(null);
                camera.stopPreview();
                camera.release();
            } catch (Exception ignored) {}
            camera = null;
        }
    }

    private void flipCamera() {
        int count = Camera.getNumberOfCameras();
        if (count > 1) {
            currentCameraId = (currentCameraId + 1) % count;
            if (motionTracker != null) {
                motionTracker.setMirror(isFrontCamera());
                motionTracker.resetBaseline();
            }
            openCamera();
        }
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera cam) {
        if (motionTracker == null || data == null || cam == null) return;

        if (isProcessingFrame.compareAndSet(false, true)) {
            cameraHandler.post(() -> {
                try {
                    motionTracker.processFrame(data, previewWidth, previewHeight);
                } catch (Throwable t) {
                    Log.e(TAG, "Error in processFrame: " + t.getMessage(), t);
                } finally {
                    isProcessingFrame.set(false);
                    if (camera != null) {
                        camera.addCallbackBuffer(data);
                    }
                }
            });
        } else {
            cam.addCallbackBuffer(data);
        }
    }

    // --- OpenCvMotionTracker.MotionListener Callbacks ---
    @Override
    public void onLaneChanged(int lane) {
        if (gameView != null) {
            gameView.setTargetLane(lane);
        }
    }

    @Override
    public void onJump() {
        if (gameView != null) {
            gameView.jump();
            gameView.setActionFeedback(true, false);
        }
    }

    @Override
    public void onSlide(boolean isSliding) {
        if (gameView != null) {
            gameView.slide(isSliding);
            gameView.setActionFeedback(false, isSliding);
        }
    }

    @Override
    public void onHandsUp() {
        if (gameView != null) {
            gameView.jump();
        }
    }

    @Override
    public void onTrackingUpdated(boolean isTracked, float normX, float normY, float width, float height, Bitmap pipBitmap) {
        if (gameView != null && motionTracker != null) {
            gameView.updatePipState(isTracked, normX, normY, width, height,
                    motionTracker.getCurrentLane(), motionTracker.getCurrentFps());
            if (pipBitmap != null) {
                gameView.updateCameraPipBitmap(pipBitmap);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (gameView != null) {
            gameView.startLoop();
        }
        if (soundManager != null) {
            soundManager.resumeMusic();
        }
        if (previewTexture != null && camera == null) {
            openCamera();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (gameView != null) {
            gameView.stopLoop();
        }
        if (soundManager != null) {
            soundManager.pauseMusic();
        }
        releaseCamera();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseCamera();
        releaseLocks();

        if (tvGameServer != null) {
            tvGameServer.stop();
            tvGameServer = null;
        }

        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
        }

        if (motionTracker != null) {
            motionTracker.release();
            motionTracker = null;
        }

        if (soundManager != null) {
            soundManager.release();
            soundManager = null;
        }

        if (gameView != null) {
            gameView.release();
            gameView = null;
        }
    }
}

package com.cleanmyphone.launcher.opencv;

import android.graphics.Bitmap;
import android.util.Log;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Moments;
import org.opencv.video.BackgroundSubtractorMOG2;
import org.opencv.video.Video;

import java.util.ArrayList;
import java.util.List;

/**
 * Ultra-low-latency OpenCV Motion & Player Pose Tracker.
 * Runs MOG2 background subtraction, morphological noise filtration,
 * and primary human contour extraction to trigger high-speed game actions.
 * Also generates downsampled camera silhouettes for TV & phone HUD display.
 */
public class OpenCvMotionTracker {
    private static final String TAG = "OpenCvMotionTracker";

    public interface MotionListener {
        void onLaneChanged(int lane); // 0=Left, 1=Center, 2=Right
        void onJump();
        void onSlide(boolean isSliding);
        void onHandsUp();
        void onTrackingUpdated(boolean isTracked, float normX, float normY, float width, float height, Bitmap pipBitmap);
    }

    private final MotionListener listener;
    private BackgroundSubtractorMOG2 mog2;
    private Mat yuvMat;
    private Mat smallGrayMat;
    private Mat fgMaskMat;
    private Mat cleanMaskMat;
    private Mat kernel;

    // PIP generation mats
    private Bitmap pipBitmap;
    private Mat pipRgbaMat;
    private Mat miniMat;
    private int pipSkipCounter = 0;

    private final int procWidth = 320;
    private final int procHeight = 240;

    private boolean isInitialized = false;
    private boolean mirror = true;

    // Tracking state
    private int currentLane = 1; // 0=Left, 1=Center, 2=Right
    private float smoothCentroidX = 0.5f;
    private float smoothCentroidY = 0.5f;
    private float baselineTop = -1;
    private float baselineBottom = -1;
    private float baselineHeight = -1;
    private float baselineCentroidY = -1;

    private boolean isJumping = false;
    private boolean isSliding = false;
    private boolean isHandsUp = false;
    private long lastJumpTime = 0;
    private long lastHandsTime = 0;

    // Performance metrics
    private int fpsCounter = 0;
    private int currentFps = 30;
    private long lastFpsCalcTime = 0;

    public OpenCvMotionTracker(MotionListener listener) {
        this.listener = listener;
        try {
            if (OpenCVLoader.initDebug()) {
                Log.d(TAG, "OpenCV native libraries loaded successfully");
                initOpenCv();
            } else {
                Log.e(TAG, "OpenCVLoader.initDebug() failed");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error initializing OpenCV: " + t.getMessage(), t);
        }
    }

    private void initOpenCv() {
        mog2 = Video.createBackgroundSubtractorMOG2(100, 22.0, true);
        kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(5, 5));
        yuvMat = new Mat();
        smallGrayMat = new Mat();
        fgMaskMat = new Mat();
        cleanMaskMat = new Mat();

        pipBitmap = Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888);
        pipRgbaMat = new Mat();
        miniMat = new Mat();

        isInitialized = true;
    }

    public void setMirror(boolean mirror) {
        this.mirror = mirror;
    }

    public boolean isMirror() {
        return mirror;
    }

    public int getCurrentLane() {
        return currentLane;
    }

    public int getCurrentFps() {
        return currentFps;
    }

    public synchronized void resetBaseline() {
        baselineTop = -1;
        baselineBottom = -1;
        baselineHeight = -1;
        baselineCentroidY = -1;
        currentLane = 1;
        smoothCentroidX = 0.5f;
        smoothCentroidY = 0.5f;
        isJumping = false;
        isSliding = false;
        isHandsUp = false;
        if (mog2 != null) {
            mog2.clear();
        }
        Log.d(TAG, "OpenCV tracker baseline reset");
    }

    public synchronized void processFrame(byte[] nv21Data, int width, int height) {
        if (!isInitialized || nv21Data == null || nv21Data.length < width * height) return;

        long now = System.currentTimeMillis();
        fpsCounter++;
        if (now - lastFpsCalcTime >= 1000) {
            currentFps = fpsCounter;
            fpsCounter = 0;
            lastFpsCalcTime = now;
        }

        try {
            if (yuvMat.rows() != height || yuvMat.cols() != width) {
                yuvMat.create(height, width, CvType.CV_8UC1);
            }
            yuvMat.put(0, 0, nv21Data);

            // Downscale to 320x240 for ultra-low latency (<4ms on ARM)
            Imgproc.resize(yuvMat, smallGrayMat, new Size(procWidth, procHeight), 0, 0, Imgproc.INTER_AREA);

            // Light Gaussian Blur
            Imgproc.GaussianBlur(smallGrayMat, smallGrayMat, new Size(5, 5), 0);

            // Background Subtraction
            double learningRate = (baselineHeight > 0) ? 0.003 : 0.02;
            mog2.apply(smallGrayMat, fgMaskMat, learningRate);

            // Shadow suppression
            Imgproc.threshold(fgMaskMat, cleanMaskMat, 200, 255, Imgproc.THRESH_BINARY);

            // Morphological opening & closing
            Imgproc.morphologyEx(cleanMaskMat, cleanMaskMat, Imgproc.MORPH_OPEN, kernel);
            Imgproc.morphologyEx(cleanMaskMat, cleanMaskMat, Imgproc.MORPH_CLOSE, kernel);

            // Contour extraction
            List<MatOfPoint> contours = new ArrayList<>();
            Mat hierarchy = new Mat();
            Imgproc.findContours(cleanMaskMat, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

            double maxArea = 0;
            MatOfPoint playerContour = null;
            double minBodyArea = (procWidth * procHeight) * 0.035;

            for (MatOfPoint c : contours) {
                double area = Imgproc.contourArea(c);
                if (area > maxArea && area >= minBodyArea) {
                    maxArea = area;
                    playerContour = c;
                }
            }

            // Generate downscaled PIP bitmap for HUD / TV every 2 frames
            pipSkipCounter++;
            if (pipSkipCounter % 2 == 0 && pipBitmap != null) {
                try {
                    Imgproc.resize(smallGrayMat, miniMat, new Size(120, 90));
                    Imgproc.cvtColor(miniMat, pipRgbaMat, Imgproc.COLOR_GRAY2RGBA);
                    Utils.matToBitmap(pipRgbaMat, pipBitmap);
                } catch (Throwable ignored) {}
            }

            if (playerContour != null) {
                Rect box = Imgproc.boundingRect(playerContour);
                float normW = (float) box.width / procWidth;
                float normH = (float) box.height / procHeight;

                if (normH >= 0.15f && normW >= 0.08f) {
                    Moments moments = Imgproc.moments(playerContour);
                    float cx = moments.m00 > 0 ? (float) (moments.m10 / moments.m00) : (box.x + box.width / 2.0f);
                    float cy = moments.m00 > 0 ? (float) (moments.m01 / moments.m00) : (box.y + box.height / 2.0f);

                    float normX = cx / procWidth;
                    if (mirror) {
                        normX = 1.0f - normX;
                    }
                    float normY = cy / procHeight;

                    smoothCentroidX = smoothCentroidX * 0.60f + normX * 0.40f;
                    smoothCentroidY = smoothCentroidY * 0.60f + normY * 0.40f;

                    float topY = (float) box.y / procHeight;
                    float botY = (float) (box.y + box.height) / procHeight;

                    if (baselineHeight < 0) {
                        baselineTop = topY;
                        baselineBottom = botY;
                        baselineHeight = normH;
                        baselineCentroidY = normY;
                    } else if (!isJumping && !isSliding && !isHandsUp) {
                        baselineTop = baselineTop * 0.96f + topY * 0.04f;
                        baselineBottom = baselineBottom * 0.96f + botY * 0.04f;
                        baselineHeight = baselineHeight * 0.96f + normH * 0.04f;
                        baselineCentroidY = baselineCentroidY * 0.96f + normY * 0.04f;
                    }

                    // 1. LANE STEERING
                    int newLane = currentLane;
                    if (currentLane == 1) {
                        if (smoothCentroidX < 0.38f) newLane = 0;
                        else if (smoothCentroidX > 0.62f) newLane = 2;
                    } else if (currentLane == 0) {
                        if (smoothCentroidX > 0.44f) newLane = 1;
                    } else {
                        if (smoothCentroidX < 0.56f) newLane = 1;
                    }

                    if (newLane != currentLane) {
                        currentLane = newLane;
                        if (listener != null) listener.onLaneChanged(currentLane);
                    }

                    // 2. JUMP DETECTION
                    float topRise = baselineTop - topY;
                    float botRise = baselineBottom - botY;
                    float cyRise = baselineCentroidY - normY;

                    if (!isJumping && topRise > 0.05f && (botRise > 0.035f || cyRise > 0.04f) && (now - lastJumpTime > 400)) {
                        isJumping = true;
                        lastJumpTime = now;
                        if (listener != null) listener.onJump();
                    } else if (isJumping && (botRise < 0.025f || now - lastJumpTime > 900)) {
                        isJumping = false;
                    }

                    // 3. SLIDE / SQUAT DETECTION
                    float heightRatio = normH / (baselineHeight > 0 ? baselineHeight : 0.5f);
                    if (!isSliding && !isJumping && heightRatio < 0.74f && Math.abs(botRise) < 0.08f) {
                        isSliding = true;
                        if (listener != null) listener.onSlide(true);
                    } else if (isSliding && (heightRatio > 0.86f || isJumping)) {
                        isSliding = false;
                        if (listener != null) listener.onSlide(false);
                    }

                    if (listener != null) {
                        listener.onTrackingUpdated(true, normX, normY, normW, normH, pipBitmap);
                    }
                    return;
                }
            }

            if (isJumping) isJumping = false;
            if (isSliding) {
                isSliding = false;
                if (listener != null) listener.onSlide(false);
            }
            if (listener != null) {
                listener.onTrackingUpdated(false, smoothCentroidX, smoothCentroidY, 0, 0, pipBitmap);
            }
        } catch (Throwable t) {
            Log.e(TAG, "OpenCV frame processing error: " + t.getMessage(), t);
        }
    }

    public void release() {
        isInitialized = false;
        if (yuvMat != null) { yuvMat.release(); yuvMat = null; }
        if (smallGrayMat != null) { smallGrayMat.release(); smallGrayMat = null; }
        if (fgMaskMat != null) { fgMaskMat.release(); fgMaskMat = null; }
        if (cleanMaskMat != null) { cleanMaskMat.release(); cleanMaskMat = null; }
        if (kernel != null) { kernel.release(); kernel = null; }
        if (pipRgbaMat != null) { pipRgbaMat.release(); pipRgbaMat = null; }
        if (miniMat != null) { miniMat.release(); miniMat = null; }
        if (pipBitmap != null) { pipBitmap.recycle(); pipBitmap = null; }
        if (mog2 != null) { mog2.clear(); mog2 = null; }
    }
}

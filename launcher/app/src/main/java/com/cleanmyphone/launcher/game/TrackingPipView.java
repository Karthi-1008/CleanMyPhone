package com.cleanmyphone.launcher.game;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * Picture-in-Picture camera overlay that visualizes the OpenCV motion tracker
 * detection box, centroid, lane divisions, and action triggers in real-time.
 */
public class TrackingPipView extends View {
    private boolean isTracked = false;
    private float normX = 0.5f;
    private float normY = 0.5f;
    private float normW = 0.0f;
    private float normH = 0.0f;
    private int currentLane = 1;
    private int fps = 30;

    private boolean isJumping = false;
    private boolean isSliding = false;

    private Paint boxPaint;
    private Paint centerPointPaint;
    private Paint guideLinePaint;
    private Paint textPaint;
    private Paint activeLanePaint;

    public TrackingPipView(Context context) {
        this(context, null);
    }

    public TrackingPipView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boxPaint.setColor(0xFF00E5FF);
        boxPaint.setStrokeWidth(2.5f);
        boxPaint.setStyle(Paint.Style.STROKE);

        centerPointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        centerPointPaint.setColor(0xFFFFD60A);
        centerPointPaint.setStyle(Paint.Style.FILL);

        guideLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        guideLinePaint.setColor(0x55FFFFFF);
        guideLinePaint.setStrokeWidth(1.5f);
        guideLinePaint.setStyle(Paint.Style.STROKE);

        activeLanePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        activeLanePaint.setColor(0x2200E676);
        activeLanePaint.setStyle(Paint.Style.FILL);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(12f);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    public void updateTracking(boolean isTracked, float normX, float normY, float normW, float normH, int currentLane, int fps) {
        this.isTracked = isTracked;
        this.normX = normX;
        this.normY = normY;
        this.normW = normW;
        this.normH = normH;
        this.currentLane = currentLane;
        this.fps = fps;
        postInvalidate();
    }

    public void setActionState(boolean jumping, boolean sliding) {
        this.isJumping = jumping;
        this.isSliding = sliding;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // 1. Draw Lane Divider Guides (Left: <0.38, Center: 0.38-0.62, Right: >0.62)
        float x1 = w * 0.38f;
        float x2 = w * 0.62f;
        canvas.drawLine(x1, 0, x1, h, guideLinePaint);
        canvas.drawLine(x2, 0, x2, h, guideLinePaint);

        // Highlight Active Lane Zone
        if (currentLane == 0) {
            canvas.drawRect(0, 0, x1, h, activeLanePaint);
        } else if (currentLane == 1) {
            canvas.drawRect(x1, 0, x2, h, activeLanePaint);
        } else {
            canvas.drawRect(x2, 0, w, h, activeLanePaint);
        }

        // 2. Draw Detected Body Bounding Box & Centroid
        if (isTracked && normW > 0 && normH > 0) {
            float boxLeft = (normX - normW * 0.5f) * w;
            float boxTop = (normY - normH * 0.5f) * h;
            float boxRight = (normX + normW * 0.5f) * w;
            float boxBottom = (normY + normH * 0.5f) * h;

            // Clamp
            boxLeft = Math.max(0, boxLeft);
            boxTop = Math.max(0, boxTop);
            boxRight = Math.min(w, boxRight);
            boxBottom = Math.min(h, boxBottom);

            canvas.drawRoundRect(new RectF(boxLeft, boxTop, boxRight, boxBottom), 6f, 6f, boxPaint);

            // Centroid Dot
            float cx = normX * w;
            float cy = normY * h;
            canvas.drawCircle(cx, cy, 4.5f, centerPointPaint);
        }

        // 3. Draw PIP HUD & Status
        String laneName = (currentLane == 0) ? "LEFT" : (currentLane == 1) ? "CENTER" : "RIGHT";
        textPaint.setColor(0xFF00E676);
        textPaint.setTextSize(11f);
        canvas.drawText(laneName, 8f, h - 8f, textPaint);

        textPaint.setColor(0xCCFFFFFF);
        textPaint.setTextSize(9f);
        canvas.drawText("CV: " + fps + " FPS", w - 55f, 14f, textPaint);

        if (isJumping) {
            textPaint.setColor(0xFFFFD60A);
            textPaint.setTextSize(11f);
            canvas.drawText("JUMP!", 8f, 16f, textPaint);
        } else if (isSliding) {
            textPaint.setColor(0xFFFF9500);
            textPaint.setTextSize(11f);
            canvas.drawText("SLIDE!", 8f, 16f, textPaint);
        }
    }
}

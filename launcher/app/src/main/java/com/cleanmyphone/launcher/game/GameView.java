package com.cleanmyphone.launcher.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.graphics.*;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * 60 FPS Hardware-Accelerated 3D Perspective Runner Game Engine
 * with Integrated Real-Time Wi-Fi TV Browser Streaming.
 *
 * Renders at full 60 FPS to the phone's SurfaceView, and simultaneously
 * compresses and broadcasts 30-60 FPS MJPEG stream to any TV web browser
 * at http://<ip>:8080 over local Wi-Fi with zero internet dependency!
 */
public class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable {
    private static final String TAG = "GameView";
    private static final String PREF_NAME = "MotionRunnerPrefs";
    private static final String KEY_HIGH_SCORE = "high_score";

    public static final int STATE_COUNTDOWN = 0;
    public static final int STATE_PLAYING = 1;
    public static final int STATE_GAME_OVER = 2;

    private Thread gameThread;
    private volatile boolean isRunning = false;
    private final SurfaceHolder holder;
    private final SharedPreferences prefs;
    private SoundManager soundManager;
    private TvGameServer tvGameServer;

    // TV streaming buffer
    private Bitmap bufferBitmap;
    private Canvas bufferCanvas;
    private static final int BUFFER_W = 640;
    private static final int BUFFER_H = 360;
    private final ByteArrayOutputStream jpegStream = new ByteArrayOutputStream(32768);
    private int streamFrameSkip = 0;
    private String tvUrl = "http://192.168.0.9:8080";
    private int tvClientCount = 0;

    // Camera PIP state (drawn on canvas so TV also sees camera feed & pose)
    private Bitmap cameraPipBitmap;
    private boolean isTracked = false;
    private float trackingNormX = 0.5f;
    private float trackingNormY = 0.5f;
    private float trackingNormW = 0.0f;
    private float trackingNormH = 0.0f;
    private int trackingLane = 1;
    private int trackingFps = 30;
    private boolean actionJumping = false;
    private boolean actionSliding = false;

    // Assets
    private Bitmap bmpBg;
    private Bitmap bmpRunnerRun1;
    private Bitmap bmpRunnerRun2;
    private Bitmap bmpRunnerJump;
    private Bitmap bmpRunnerSlide;
    private Bitmap bmpObstacleLow;
    private Bitmap bmpObstacleHigh;
    private Bitmap bmpCoin;
    private Bitmap bmpShield;
    private Bitmap bmpHeart;
    private Bitmap bmpLogo;

    // Paints
    private Paint roadPaint;
    private Paint roadBorderPaint;
    private Paint roadDividerPaint;
    private Paint shadowPaint;
    private Paint hudTextPaint;
    private Paint hudSubTextPaint;
    private Paint overlayPaint;
    private Paint particlePaint;
    private Paint badgePaint;
    private Paint badgeTextPaint;
    private Paint bannerPaint;
    private Paint floatingTextPaint;
    private Paint pipPaint;
    private Paint pipBoxPaint;
    private Paint pipCenterPaint;
    private Paint pipGuidePaint;
    private Paint pipActiveLanePaint;

    // Game State
    private int gameState = STATE_COUNTDOWN;
    private float countdownTimer = 2.0f;
    private int score = 0;
    private int highScore = 0;
    private int coinsCollected = 0;
    private int lives = 3;
    private float gameSpeed = 500.0f;
    private float roadDistance = 0.0f;

    // Player State
    private int targetLane = 1; // 0=Left, 1=Center, 2=Right
    private float runnerSmoothLane = 1.0f;
    private boolean isJumping = false;
    private float jumpOffsetY = 0.0f;
    private float jumpVelocity = 0.0f;
    private static final float GRAVITY = 1600.0f;
    private static final float JUMP_FORCE = -680.0f;

    private boolean isSliding = false;
    private float slideTimer = 0.0f;

    private float invulnerableTimer = 0.0f;
    private float hitFlashTimer = 0.0f;
    private float shieldTimer = 0.0f;

    private float runAnimTimer = 0.0f;

    // Entities & Spawner
    private final List<GameEntity> entities = new ArrayList<>();
    private final List<Particle> particles = new ArrayList<>();
    private final List<FloatingText> floatingTexts = new ArrayList<>();
    private float spawnTimer = 0.0f;
    private final Random random = new Random();

    // Auto-restart on Game Over
    private float gameOverTimer = 0.0f;

    // Touch controls
    private float touchStartX = 0.0f;
    private float touchStartY = 0.0f;
    private long touchStartTime = 0;

    private static class FloatingText {
        String text;
        float x, y;
        float vy;
        float alpha;
        int color;
        float life;

        FloatingText(String text, float x, float y, int color) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.vy = -60.0f;
            this.alpha = 1.0f;
            this.color = color;
            this.life = 1.2f;
        }

        boolean update(float dt) {
            y += vy * dt;
            life -= dt;
            alpha = Math.max(0, life / 1.2f);
            return life > 0;
        }
    }

    public GameView(Context context) {
        this(context, null);
    }

    public GameView(Context context, AttributeSet attrs) {
        super(context, attrs);
        holder = getHolder();
        holder.addCallback(this);
        setFocusable(true);
        setFocusableInTouchMode(true);

        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        highScore = prefs.getInt(KEY_HIGH_SCORE, 0);

        initPaints();
        loadAssets();
        initParticlePool();
        initBuffer();
    }

    private void initBuffer() {
        bufferBitmap = Bitmap.createBitmap(BUFFER_W, BUFFER_H, Bitmap.Config.RGB_565);
        bufferCanvas = new Canvas(bufferBitmap);
    }

    public void setSoundManager(SoundManager soundManager) {
        this.soundManager = soundManager;
    }

    public void setTvGameServer(TvGameServer server) {
        this.tvGameServer = server;
    }

    public void setTvUrl(String url) {
        this.tvUrl = url;
    }

    public void setTvClientCount(int count) {
        this.tvClientCount = count;
    }

    public synchronized void updatePipState(boolean isTracked, float normX, float normY, float normW, float normH, int lane, int fps) {
        this.isTracked = isTracked;
        this.trackingNormX = normX;
        this.trackingNormY = normY;
        this.trackingNormW = normW;
        this.trackingNormH = normH;
        this.trackingLane = lane;
        this.trackingFps = fps;
    }

    public synchronized void updateCameraPipBitmap(Bitmap bmp) {
        this.cameraPipBitmap = bmp;
    }

    public synchronized void setActionFeedback(boolean jumping, boolean sliding) {
        this.actionJumping = jumping;
        this.actionSliding = sliding;
    }

    private void initPaints() {
        roadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        roadPaint.setColor(0xFF141721);
        roadPaint.setStyle(Paint.Style.FILL);

        roadBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        roadBorderPaint.setColor(0xFF00E676);
        roadBorderPaint.setStrokeWidth(3.5f);
        roadBorderPaint.setStyle(Paint.Style.STROKE);

        roadDividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        roadDividerPaint.setColor(0x8800E5FF);
        roadDividerPaint.setStrokeWidth(2.5f);
        roadDividerPaint.setStyle(Paint.Style.STROKE);

        shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadowPaint.setColor(0x66000000);
        shadowPaint.setStyle(Paint.Style.FILL);

        hudTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        hudTextPaint.setColor(0xFFFFFFFF);
        hudTextPaint.setTextSize(18f);
        hudTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        hudSubTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        hudSubTextPaint.setColor(0xFF8E99B0);
        hudSubTextPaint.setTextSize(12f);
        hudSubTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        overlayPaint.setColor(0xCC080A10);
        overlayPaint.setStyle(Paint.Style.FILL);

        particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        badgePaint.setStyle(Paint.Style.FILL);

        badgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        badgeTextPaint.setColor(0xFFFFFFFF);
        badgeTextPaint.setTextSize(11f);
        badgeTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        badgeTextPaint.setTextAlign(Paint.Align.CENTER);

        bannerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bannerPaint.setColor(0xFFFFD60A);
        bannerPaint.setTextSize(36f);
        bannerPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        bannerPaint.setTextAlign(Paint.Align.CENTER);

        floatingTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        floatingTextPaint.setTextSize(16f);
        floatingTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        floatingTextPaint.setTextAlign(Paint.Align.CENTER);

        // PIP paints
        pipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pipPaint.setColor(0xFF141721);
        pipPaint.setStyle(Paint.Style.FILL);

        pipBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pipBoxPaint.setColor(0xFF00E5FF);
        pipBoxPaint.setStrokeWidth(2f);
        pipBoxPaint.setStyle(Paint.Style.STROKE);

        pipCenterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pipCenterPaint.setColor(0xFFFFD60A);
        pipCenterPaint.setStyle(Paint.Style.FILL);

        pipGuidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pipGuidePaint.setColor(0x44FFFFFF);
        pipGuidePaint.setStrokeWidth(1.2f);
        pipGuidePaint.setStyle(Paint.Style.STROKE);

        pipActiveLanePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pipActiveLanePaint.setColor(0x2200E676);
        pipActiveLanePaint.setStyle(Paint.Style.FILL);
    }

    private void loadAssets() {
        AssetManager am = getContext().getAssets();
        bmpBg = loadBitmap(am, "bg.png");
        bmpRunnerRun1 = loadBitmap(am, "runner_run1.png");
        bmpRunnerRun2 = loadBitmap(am, "runner_run2.png");
        bmpRunnerJump = loadBitmap(am, "runner_jump.png");
        bmpRunnerSlide = loadBitmap(am, "runner_slide.png");
        bmpObstacleLow = loadBitmap(am, "obstacle_low.png");
        bmpObstacleHigh = loadBitmap(am, "obstacle_high.png");
        bmpCoin = loadBitmap(am, "coin.png");
        bmpShield = loadBitmap(am, "shield.png");
        bmpHeart = loadBitmap(am, "heart.png");
        bmpLogo = loadBitmap(am, "logo.png");
    }

    private Bitmap loadBitmap(AssetManager am, String name) {
        try (InputStream is = am.open("game/" + name)) {
            return BitmapFactory.decodeStream(is);
        } catch (Throwable t) {
            Log.w(TAG, "Could not load bitmap asset: " + name + " (" + t.getMessage() + ")");
            return null;
        }
    }

    private void initParticlePool() {
        for (int i = 0; i < 60; i++) {
            particles.add(new Particle());
        }
    }

    private void emitBurst(float x, float y, int color, int count) {
        int spawned = 0;
        for (Particle p : particles) {
            if (!p.active) {
                float angle = (float) (Math.random() * Math.PI * 2);
                float speed = 80.0f + (float) Math.random() * 180.0f;
                float vx = (float) Math.cos(angle) * speed;
                float vy = (float) Math.sin(angle) * speed - 80.0f;
                float size = 3.5f + (float) Math.random() * 4.0f;
                float maxLife = 0.45f + (float) Math.random() * 0.35f;
                p.init(x, y, vx, vy, size, color, maxLife);
                spawned++;
                if (spawned >= count) break;
            }
        }
    }

    public synchronized void setTargetLane(int lane) {
        if (lane >= 0 && lane <= 2) {
            this.targetLane = lane;
        }
    }

    public synchronized void jump() {
        if (!isJumping && gameState == STATE_PLAYING) {
            isJumping = true;
            jumpVelocity = JUMP_FORCE;
            jumpOffsetY = 0.0f;
            if (soundManager != null) soundManager.playJump();
        }
    }

    public synchronized void slide(boolean sliding) {
        if (gameState == STATE_PLAYING) {
            if (sliding && !isSliding) {
                isSliding = true;
                slideTimer = 0.65f;
                if (soundManager != null) soundManager.playSlide();
            } else if (!sliding) {
                isSliding = false;
                slideTimer = 0.0f;
            }
        }
    }

    public void restartGame() {
        gameState = STATE_COUNTDOWN;
        countdownTimer = 2.0f;
        score = 0;
        coinsCollected = 0;
        lives = 3;
        gameSpeed = 500.0f;
        targetLane = 1;
        runnerSmoothLane = 1.0f;
        isJumping = false;
        jumpOffsetY = 0.0f;
        isSliding = false;
        slideTimer = 0.0f;
        invulnerableTimer = 0.0f;
        shieldTimer = 0.0f;
        entities.clear();
        floatingTexts.clear();
        if (soundManager != null) soundManager.startMusic();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        startLoop();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopLoop();
    }

    public synchronized void startLoop() {
        if (gameThread == null || !isRunning) {
            isRunning = true;
            gameThread = new Thread(this, "GameLoopThread");
            gameThread.start();
        }
        if (soundManager != null) {
            soundManager.startMusic();
        }
    }

    public synchronized void stopLoop() {
        isRunning = false;
        if (gameThread != null) {
            try {
                gameThread.join(500);
            } catch (InterruptedException ignored) {}
            gameThread = null;
        }
        if (soundManager != null) {
            soundManager.pauseMusic();
        }
    }

    @Override
    public void run() {
        long lastTime = SystemClock.elapsedRealtime();

        while (isRunning) {
            long now = SystemClock.elapsedRealtime();
            float dt = (now - lastTime) / 1000.0f;
            lastTime = now;

            if (dt > 0.05f) dt = 0.05f;

            update(dt);

            // 1. Render complete frame to bufferBitmap
            drawGame(bufferCanvas);

            // 2. Stream to TV browser if TV client is connected (30 FPS broadcast)
            if (tvGameServer != null && tvGameServer.hasActiveClients()) {
                streamFrameSkip++;
                if (streamFrameSkip % 2 == 0) { // ~30 FPS TV stream
                    try {
                        jpegStream.reset();
                        bufferBitmap.compress(Bitmap.CompressFormat.JPEG, 65, jpegStream);
                        tvGameServer.pushFrame(jpegStream.toByteArray());
                    } catch (Throwable t) {
                        Log.e(TAG, "Stream compress error: " + t.getMessage());
                    }
                }
            }

            // 3. Render bufferBitmap to phone SurfaceView Canvas
            Canvas canvas = null;
            try {
                canvas = holder.lockCanvas();
                if (canvas != null) {
                    synchronized (holder) {
                        Rect src = new Rect(0, 0, BUFFER_W, BUFFER_H);
                        Rect dst = new Rect(0, 0, canvas.getWidth(), canvas.getHeight());
                        canvas.drawBitmap(bufferBitmap, src, dst, null);
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "Phone render error: " + t.getMessage(), t);
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas);
                    } catch (Throwable ignored) {}
                }
            }

            // Frame limiter ~60 FPS (16.6ms)
            long elapsed = SystemClock.elapsedRealtime() - now;
            long sleepTime = 16 - elapsed;
            if (sleepTime > 0) {
                try {
                    Thread.sleep(sleepTime);
                } catch (InterruptedException ignored) {}
            }
        }
    }

    private void update(float dt) {
        if (gameState == STATE_COUNTDOWN) {
            countdownTimer -= dt;
            roadDistance += 200.0f * dt;
            runnerSmoothLane += (targetLane - runnerSmoothLane) * (dt * 12.0f);
            if (countdownTimer <= 0) {
                gameState = STATE_PLAYING;
            }
            return;
        }

        if (gameState == STATE_GAME_OVER) {
            gameOverTimer += dt;
            if (gameOverTimer >= 6.0f) {
                restartGame();
            }
            return;
        }

        // --- PLAYING STATE ---
        roadDistance += gameSpeed * dt;
        score += (int) (gameSpeed * dt * 0.1f);
        if (score > highScore) {
            highScore = score;
            prefs.edit().putInt(KEY_HIGH_SCORE, highScore).apply();
        }

        if (gameSpeed < 1000.0f) {
            gameSpeed += 4.0f * dt;
        }

        runnerSmoothLane += (targetLane - runnerSmoothLane) * (dt * 15.0f);
        runAnimTimer += dt;

        if (isJumping) {
            jumpOffsetY += jumpVelocity * dt;
            jumpVelocity += GRAVITY * dt;
            if (jumpOffsetY >= 0) {
                jumpOffsetY = 0;
                isJumping = false;
            }
        }

        if (isSliding) {
            slideTimer -= dt;
            if (slideTimer <= 0) {
                isSliding = false;
            }
        }

        if (invulnerableTimer > 0) invulnerableTimer -= dt;
        if (hitFlashTimer > 0) hitFlashTimer -= dt;
        if (shieldTimer > 0) shieldTimer -= dt;

        // Spawner
        spawnTimer -= dt;
        if (spawnTimer <= 0) {
            spawnEntityWave();
            spawnTimer = Math.max(1.1f, 2.0f - (gameSpeed - 500f) * 0.0015f);
        }

        // Entities & Collision
        float bottomY = BUFFER_H * 0.94f;
        float bottomRoadWidth = BUFFER_W * 0.86f;
        float centerX = BUFFER_W * 0.5f;

        Iterator<GameEntity> it = entities.iterator();
        while (it.hasNext()) {
            GameEntity entity = it.next();
            entity.update(dt, gameSpeed);

            if (!entity.active) {
                it.remove();
                continue;
            }

            if (!entity.collectedOrHit && entity.z >= -30f && entity.z <= 30f) {
                if (Math.abs(runnerSmoothLane - entity.lane) < 0.42f) {
                    entity.collectedOrHit = true;
                    float entityScreenX = centerX + (entity.lane - 1.0f) * (bottomRoadWidth * 0.31f);
                    float entityScreenY = bottomY - 20f;

                    if (entity.type == GameEntity.TYPE_COIN) {
                        coinsCollected++;
                        score += 10;
                        if (soundManager != null) soundManager.playCoin();
                        emitBurst(entityScreenX, entityScreenY, 0xFFFFD60A, 8);
                        floatingTexts.add(new FloatingText("+10", entityScreenX, entityScreenY, 0xFFFFD60A));
                        entity.active = false;
                    } else if (entity.type == GameEntity.TYPE_SHIELD) {
                        shieldTimer = 8.0f;
                        score += 25;
                        if (soundManager != null) soundManager.playPowerup();
                        emitBurst(entityScreenX, entityScreenY, 0xFF00E5FF, 12);
                        floatingTexts.add(new FloatingText("SHIELD!", entityScreenX, entityScreenY, 0xFF00E5FF));
                        entity.active = false;
                    } else if (entity.type == GameEntity.TYPE_OBSTACLE_LOW) {
                        if (shieldTimer > 0) {
                            score += 50;
                            if (soundManager != null) soundManager.playHit();
                            emitBurst(entityScreenX, entityScreenY, 0xFF00E5FF, 14);
                            floatingTexts.add(new FloatingText("+50 SMASH!", entityScreenX, entityScreenY, 0xFF00E5FF));
                            entity.active = false;
                        } else if (isJumping && jumpOffsetY < -20.0f) {
                            score += 20;
                            floatingTexts.add(new FloatingText("LEAP! +20", entityScreenX, entityScreenY, 0xFF00E676));
                        } else if (invulnerableTimer <= 0) {
                            onPlayerDamaged(entityScreenX, entityScreenY);
                        }
                    } else if (entity.type == GameEntity.TYPE_OBSTACLE_HIGH) {
                        if (shieldTimer > 0) {
                            score += 50;
                            if (soundManager != null) soundManager.playHit();
                            emitBurst(entityScreenX, entityScreenY, 0xFF00E5FF, 14);
                            floatingTexts.add(new FloatingText("+50 SMASH!", entityScreenX, entityScreenY, 0xFF00E5FF));
                            entity.active = false;
                        } else if (isSliding) {
                            score += 20;
                            floatingTexts.add(new FloatingText("SLIDE! +20", entityScreenX, entityScreenY, 0xFF00E676));
                        } else if (invulnerableTimer <= 0) {
                            onPlayerDamaged(entityScreenX, entityScreenY);
                        }
                    }
                }
            }
        }

        // Update Particles
        for (Particle p : particles) {
            p.update(dt);
        }

        // Update Floating Texts
        Iterator<FloatingText> ftIt = floatingTexts.iterator();
        while (ftIt.hasNext()) {
            if (!ftIt.next().update(dt)) {
                ftIt.remove();
            }
        }
    }

    private void onPlayerDamaged(float x, float y) {
        lives--;
        invulnerableTimer = 1.6f;
        hitFlashTimer = 0.35f;
        if (soundManager != null) soundManager.playHit();
        emitBurst(x, y, 0xFFFF3B30, 14);
        floatingTexts.add(new FloatingText("OUCH!", x, y, 0xFFFF3B30));

        if (lives <= 0) {
            gameState = STATE_GAME_OVER;
            gameOverTimer = 0.0f;
            if (soundManager != null) soundManager.playGameOver();
        }
    }

    private void spawnEntityWave() {
        int pattern = random.nextInt(6);
        switch (pattern) {
            case 0:
                entities.add(new GameEntity(GameEntity.TYPE_OBSTACLE_LOW, random.nextInt(3), 1200f));
                break;
            case 1:
                entities.add(new GameEntity(GameEntity.TYPE_OBSTACLE_HIGH, random.nextInt(3), 1200f));
                break;
            case 2:
                int coinLane = random.nextInt(3);
                entities.add(new GameEntity(GameEntity.TYPE_COIN, coinLane, 1200f));
                entities.add(new GameEntity(GameEntity.TYPE_COIN, coinLane, 1280f));
                entities.add(new GameEntity(GameEntity.TYPE_COIN, coinLane, 1360f));
                break;
            case 3:
                int openLane = random.nextInt(3);
                for (int l = 0; l < 3; l++) {
                    if (l != openLane) {
                        int type = random.nextBoolean() ? GameEntity.TYPE_OBSTACLE_LOW : GameEntity.TYPE_OBSTACLE_HIGH;
                        entities.add(new GameEntity(type, l, 1200f));
                    }
                }
                entities.add(new GameEntity(GameEntity.TYPE_COIN, openLane, 1220f));
                break;
            case 4:
                if (shieldTimer <= 0 && random.nextFloat() < 0.40f) {
                    entities.add(new GameEntity(GameEntity.TYPE_SHIELD, random.nextInt(3), 1200f));
                } else {
                    int l = random.nextInt(3);
                    entities.add(new GameEntity(GameEntity.TYPE_COIN, l, 1200f));
                    entities.add(new GameEntity(GameEntity.TYPE_COIN, l, 1270f));
                }
                break;
            case 5:
                int obsLane = random.nextInt(3);
                int cLane = (obsLane + 1 + random.nextInt(2)) % 3;
                entities.add(new GameEntity(GameEntity.TYPE_OBSTACLE_LOW, obsLane, 1200f));
                entities.add(new GameEntity(GameEntity.TYPE_COIN, cLane, 1200f));
                entities.add(new GameEntity(GameEntity.TYPE_COIN, cLane, 1270f));
                break;
        }
    }

    private void drawGame(Canvas canvas) {
        int w = BUFFER_W;
        int h = BUFFER_H;

        // 1. Background
        if (bmpBg != null) {
            Rect src = new Rect(0, 0, bmpBg.getWidth(), bmpBg.getHeight());
            Rect dst = new Rect(0, 0, w, h);
            canvas.drawBitmap(bmpBg, src, dst, null);
        } else {
            canvas.drawColor(0xFF090B10);
        }

        // 2. Perspective Road
        float horizonY = h * 0.38f;
        float bottomY = h * 0.96f;
        float horizonRoadW = w * 0.28f;
        float bottomRoadW = w * 0.86f;
        float centerX = w * 0.5f;

        Path roadPath = new Path();
        roadPath.moveTo(centerX - horizonRoadW * 0.5f, horizonY);
        roadPath.lineTo(centerX + horizonRoadW * 0.5f, horizonY);
        roadPath.lineTo(centerX + bottomRoadW * 0.5f, bottomY);
        roadPath.lineTo(centerX - bottomRoadW * 0.5f, bottomY);
        roadPath.close();
        canvas.drawPath(roadPath, roadPaint);

        canvas.drawLine(centerX - horizonRoadW * 0.5f, horizonY, centerX - bottomRoadW * 0.5f, bottomY, roadBorderPaint);
        canvas.drawLine(centerX + horizonRoadW * 0.5f, horizonY, centerX + bottomRoadW * 0.5f, bottomY, roadBorderPaint);

        // Dashes
        float div1HorizonX = centerX - horizonRoadW * 0.166f;
        float div1BottomX = centerX - bottomRoadW * 0.166f;
        float div2HorizonX = centerX + horizonRoadW * 0.166f;
        float div2BottomX = centerX + bottomRoadW * 0.166f;

        drawPerspectiveDashes(canvas, div1HorizonX, div1BottomX, horizonY, bottomY, roadDistance);
        drawPerspectiveDashes(canvas, div2HorizonX, div2BottomX, horizonY, bottomY, roadDistance);

        // 3. Draw Entities (farthest to closest)
        for (int i = entities.size() - 1; i >= 0; i--) {
            GameEntity entity = entities.get(i);
            if (!entity.active || entity.z < -100f) continue;
            drawEntity(canvas, entity, centerX, horizonY, bottomY, horizonRoadW, bottomRoadW);
        }

        // 4. Draw Runner
        drawRunner(canvas, centerX, horizonY, bottomY, bottomRoadW, h);

        // 5. Particles
        for (Particle p : particles) {
            p.draw(canvas, particlePaint);
        }

        // 6. Floating Texts
        for (FloatingText ft : floatingTexts) {
            floatingTextPaint.setColor(ft.color);
            floatingTextPaint.setAlpha((int) (ft.alpha * 255));
            canvas.drawText(ft.text, ft.x, ft.y, floatingTextPaint);
        }

        // 7. Hit Flash
        if (hitFlashTimer > 0) {
            int alpha = (int) (Math.min(1.0f, hitFlashTimer / 0.35f) * 110);
            Paint redPaint = new Paint();
            redPaint.setColor(0xFFFF0000);
            redPaint.setAlpha(alpha);
            canvas.drawRect(0, 0, w, h, redPaint);
        }

        // 8. HUD & Built-in Camera PIP (rendered on buffer so TV displays everything!)
        drawHud(canvas, w, h);
        drawPipCard(canvas, w, h);

        // 9. Countdown / Game Over Overlays
        if (gameState == STATE_COUNTDOWN) {
            drawCountdownOverlay(canvas, w, h);
        } else if (gameState == STATE_GAME_OVER) {
            drawGameOverOverlay(canvas, w, h);
        }
    }

    private void drawPerspectiveDashes(Canvas canvas, float topX, float bottomX, float topY, float bottomY, float dist) {
        float dashLen = 140.0f;
        float offset = (dist % dashLen) / dashLen;

        for (int i = 0; i < 9; i++) {
            float frac1 = (i + offset) / 9.0f;
            float frac2 = (i + offset + 0.45f) / 9.0f;
            if (frac1 < 0.05f || frac1 > 1.0f) continue;
            frac2 = Math.min(1.0f, frac2);

            float p1 = (float) Math.pow(frac1, 1.6);
            float p2 = (float) Math.pow(frac2, 1.6);

            float y1 = topY + (bottomY - topY) * p1;
            float x1 = topX + (bottomX - topX) * p1;
            float y2 = topY + (bottomY - topY) * p2;
            float x2 = topX + (bottomX - topX) * p2;

            canvas.drawLine(x1, y1, x2, y2, roadDividerPaint);
        }
    }

    private void drawEntity(Canvas canvas, GameEntity entity, float centerX, float horizonY, float bottomY, float horizonRoadW, float bottomRoadW) {
        float depthRatio = 1.0f - (entity.z / 1200.0f);
        if (depthRatio < 0.0f) return;
        depthRatio = Math.min(1.2f, depthRatio);

        float p = (float) Math.pow(depthRatio, 1.6);
        float currentRoadW = horizonRoadW + (bottomRoadW - horizonRoadW) * p;
        float entityX = centerX + (entity.lane - 1.0f) * (currentRoadW * 0.31f);
        float entityY = horizonY + (bottomY - horizonY) * p;
        float scale = 0.20f + 0.80f * p;

        switch (entity.type) {
            case GameEntity.TYPE_COIN:
                if (bmpCoin != null) {
                    float coinW = 32f * scale;
                    float coinH = 32f * scale;
                    float bob = (float) Math.sin(entity.animTimer * 6.0f) * 6.0f * scale;
                    float spinScale = (float) Math.abs(Math.cos(entity.animTimer * 5.0f));
                    float drawnW = Math.max(3.0f, coinW * spinScale);

                    RectF dst = new RectF(entityX - drawnW * 0.5f, entityY - coinH - 8f * scale + bob,
                            entityX + drawnW * 0.5f, entityY - 8f * scale + bob);
                    canvas.drawBitmap(bmpCoin, null, dst, null);
                }
                break;

            case GameEntity.TYPE_SHIELD:
                if (bmpShield != null) {
                    float shieldW = 40f * scale;
                    float shieldH = 40f * scale;
                    float bob = (float) Math.sin(entity.animTimer * 5.0f) * 8.0f * scale;
                    RectF dst = new RectF(entityX - shieldW * 0.5f, entityY - shieldH - 10f * scale + bob,
                            entityX + shieldW * 0.5f, entityY - 10f * scale + bob);
                    canvas.drawBitmap(bmpShield, null, dst, null);
                }
                break;

            case GameEntity.TYPE_OBSTACLE_LOW:
                if (bmpObstacleLow != null) {
                    float obsW = 75f * scale;
                    float obsH = 38f * scale;
                    RectF dst = new RectF(entityX - obsW * 0.5f, entityY - obsH,
                            entityX + obsW * 0.5f, entityY);
                    canvas.drawBitmap(bmpObstacleLow, null, dst, null);
                }
                break;

            case GameEntity.TYPE_OBSTACLE_HIGH:
                if (bmpObstacleHigh != null) {
                    float obsW = 82f * scale;
                    float obsH = 55f * scale;
                    float overheadGap = 26f * scale;
                    RectF dst = new RectF(entityX - obsW * 0.5f, entityY - obsH - overheadGap,
                            entityX + obsW * 0.5f, entityY - overheadGap);
                    canvas.drawBitmap(bmpObstacleHigh, null, dst, null);
                }
                break;
        }
    }

    private void drawRunner(Canvas canvas, float centerX, float horizonY, float bottomY, float bottomRoadW, int h) {
        float runnerX = centerX + (runnerSmoothLane - 1.0f) * (bottomRoadW * 0.31f);
        float baseY = bottomY - 6f;
        float runnerY = baseY + jumpOffsetY;

        Bitmap sprite = bmpRunnerRun1;
        float runnerW = 56.0f;
        float runnerH = 82.0f;

        if (isJumping) {
            sprite = (bmpRunnerJump != null) ? bmpRunnerJump : bmpRunnerRun1;
            runnerW = 60.0f;
            runnerH = 86.0f;
        } else if (isSliding) {
            sprite = (bmpRunnerSlide != null) ? bmpRunnerSlide : bmpRunnerRun1;
            runnerW = 75.0f;
            runnerH = 48.0f;
        } else {
            boolean frame2 = ((int) (runAnimTimer * 8.0f) % 2) == 1;
            sprite = (frame2 && bmpRunnerRun2 != null) ? bmpRunnerRun2 : bmpRunnerRun1;
        }

        // Shadow
        float shadowScale = isJumping ? Math.max(0.4f, 1.0f - (-jumpOffsetY / 250.0f)) : 1.0f;
        canvas.drawOval(new RectF(runnerX - 26f * shadowScale, baseY - 8f,
                runnerX + 26f * shadowScale, baseY + 4f), shadowPaint);

        // Flicker
        if (invulnerableTimer > 0 && ((int) (invulnerableTimer * 20.0f) % 2 == 1)) {
            return;
        }

        // Shield
        if (shieldTimer > 0) {
            Paint shieldAuraPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            shieldAuraPaint.setColor(0x8800E5FF);
            shieldAuraPaint.setStyle(Paint.Style.STROKE);
            shieldAuraPaint.setStrokeWidth(3.5f);
            float pulse = (float) Math.sin(SystemClock.elapsedRealtime() * 0.01f) * 4.0f;
            canvas.drawCircle(runnerX, runnerY - runnerH * 0.5f, runnerH * 0.65f + pulse, shieldAuraPaint);
        }

        if (sprite != null) {
            RectF dst = new RectF(runnerX - runnerW * 0.5f, runnerY - runnerH,
                    runnerX + runnerW * 0.5f, runnerY);
            canvas.drawBitmap(sprite, null, dst, null);
        }
    }

    private void drawHud(Canvas canvas, int w, int h) {
        // 1. Top Left: Lives (Hearts)
        float heartX = 14.0f;
        float heartY = 12.0f;
        for (int i = 0; i < 3; i++) {
            if (bmpHeart != null) {
                Paint heartPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                if (i >= lives) {
                    heartPaint.setAlpha(60);
                }
                RectF dst = new RectF(heartX, heartY, heartX + 20f, heartY + 20f);
                canvas.drawBitmap(bmpHeart, null, dst, heartPaint);
                heartX += 24.0f;
            }
        }

        // Coins under Hearts
        if (bmpCoin != null) {
            RectF coinDst = new RectF(14f, 38f, 34f, 58f);
            canvas.drawBitmap(bmpCoin, null, coinDst, null);
        }
        hudTextPaint.setTextAlign(Paint.Align.LEFT);
        hudTextPaint.setColor(0xFFFFD60A);
        hudTextPaint.setTextSize(14f);
        canvas.drawText("x " + coinsCollected, 38f, 53f, hudTextPaint);

        // 2. TV Link Badge (under coins)
        float badgeY = 66f;
        RectF tvBadge = new RectF(14f, badgeY, 190f, badgeY + 20f);
        badgePaint.setColor(tvClientCount > 0 ? 0xDD00E676 : 0xAA141721);
        canvas.drawRoundRect(tvBadge, 6f, 6f, badgePaint);

        badgeTextPaint.setTextAlign(Paint.Align.LEFT);
        badgeTextPaint.setColor(tvClientCount > 0 ? 0xFF000000 : 0xFF00E5FF);
        badgeTextPaint.setTextSize(10f);
        String badgeMsg = tvClientCount > 0 ? ("🟢 TV Connected (" + tvClientCount + ")") : ("📡 TV: " + tvUrl);
        canvas.drawText(badgeMsg, tvBadge.left + 8f, tvBadge.centerY() + 3.5f, badgeTextPaint);

        // 3. Top Center: Score & High Score
        String scoreStr = "SCORE: " + score;
        hudTextPaint.setTextAlign(Paint.Align.CENTER);
        hudTextPaint.setColor(0xFFFFFFFF);
        hudTextPaint.setTextSize(20f);
        canvas.drawText(scoreStr, w * 0.5f, 26f, hudTextPaint);

        String hiStr = "HIGH: " + highScore;
        hudSubTextPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(hiStr, w * 0.5f, 42f, hudSubTextPaint);

        // 4. Bottom Center: Active Lane Indicator
        drawLaneIndicator(canvas, w, h);

        // 5. Action Badges
        if (isJumping) {
            drawActionBadge(canvas, w * 0.5f - 65f, h - 65f, "⬆ JUMP!", 0xFFFFD60A);
        } else if (isSliding) {
            drawActionBadge(canvas, w * 0.5f - 65f, h - 65f, "⬇ SLIDE!", 0xFFFF9500);
        }
        if (shieldTimer > 0) {
            drawActionBadge(canvas, w * 0.5f + 10f, h - 65f, "🛡️ SHIELD (" + ((int) shieldTimer + 1) + "s)", 0xFF00E5FF);
        }
    }

    private void drawPipCard(Canvas canvas, int w, int h) {
        // PIP Card in Top-Right Corner (120x90 px)
        float pipW = 120f;
        float pipH = 90f;
        float pipX = w - pipW - 12f;
        float pipY = 12f;

        RectF cardRect = new RectF(pipX, pipY, pipX + pipW, pipY + pipH);
        canvas.drawRoundRect(cardRect, 8f, 8f, pipPaint);

        // Draw camera preview bitmap if available
        if (cameraPipBitmap != null) {
            canvas.drawBitmap(cameraPipBitmap, null, cardRect, null);
        }

        // Draw Lane divider guides on PIP
        float guide1X = pipX + pipW * 0.38f;
        float guide2X = pipX + pipW * 0.62f;
        canvas.drawLine(guide1X, pipY, guide1X, pipY + pipH, pipGuidePaint);
        canvas.drawLine(guide2X, pipY, guide2X, pipY + pipH, pipGuidePaint);

        // Active Lane highlight
        if (trackingLane == 0) {
            canvas.drawRect(pipX, pipY, guide1X, pipY + pipH, pipActiveLanePaint);
        } else if (trackingLane == 1) {
            canvas.drawRect(guide1X, pipY, guide2X, pipY + pipH, pipActiveLanePaint);
        } else {
            canvas.drawRect(guide2X, pipY, pipX + pipW, pipY + pipH, pipActiveLanePaint);
        }

        // Draw Player Bounding Box & Centroid
        if (isTracked && trackingNormW > 0 && trackingNormH > 0) {
            float boxLeft = pipX + (trackingNormX - trackingNormW * 0.5f) * pipW;
            float boxTop = pipY + (trackingNormY - trackingNormH * 0.5f) * pipH;
            float boxRight = pipX + (trackingNormX + trackingNormW * 0.5f) * pipW;
            float boxBottom = pipY + (trackingNormY + trackingNormH * 0.5f) * pipH;

            boxLeft = Math.max(pipX, boxLeft);
            boxTop = Math.max(pipY, boxTop);
            boxRight = Math.min(pipX + pipW, boxRight);
            boxBottom = Math.min(pipY + pipH, boxBottom);

            canvas.drawRoundRect(new RectF(boxLeft, boxTop, boxRight, boxBottom), 4f, 4f, pipBoxPaint);

            float cx = pipX + trackingNormX * pipW;
            float cy = pipY + trackingNormY * pipH;
            canvas.drawCircle(cx, cy, 3.5f, pipCenterPaint);
        }

        // PIP Border
        pipBoxPaint.setColor(0x8800E5FF);
        pipBoxPaint.setStrokeWidth(1.5f);
        canvas.drawRoundRect(cardRect, 8f, 8f, pipBoxPaint);

        // PIP Labels
        String laneText = (trackingLane == 0) ? "LEFT" : (trackingLane == 1) ? "CENTER" : "RIGHT";
        badgeTextPaint.setTextAlign(Paint.Align.LEFT);
        badgeTextPaint.setColor(0xFF00E676);
        badgeTextPaint.setTextSize(9f);
        canvas.drawText(laneText, pipX + 6f, pipY + pipH - 6f, badgeTextPaint);

        badgeTextPaint.setTextAlign(Paint.Align.RIGHT);
        badgeTextPaint.setColor(0xCCFFFFFF);
        badgeTextPaint.setTextSize(8f);
        canvas.drawText("CV: " + trackingFps + " FPS", pipX + pipW - 6f, pipY + 12f, badgeTextPaint);

        if (actionJumping) {
            badgeTextPaint.setTextAlign(Paint.Align.LEFT);
            badgeTextPaint.setColor(0xFFFFD60A);
            badgeTextPaint.setTextSize(9f);
            canvas.drawText("JUMP!", pipX + 6f, pipY + 12f, badgeTextPaint);
        } else if (actionSliding) {
            badgeTextPaint.setTextAlign(Paint.Align.LEFT);
            badgeTextPaint.setColor(0xFFFF9500);
            badgeTextPaint.setTextSize(9f);
            canvas.drawText("SLIDE!", pipX + 6f, pipY + 12f, badgeTextPaint);
        }
    }

    private void drawLaneIndicator(Canvas canvas, int w, int h) {
        float barY = h - 26f;
        float pillW = 60f;
        float pillH = 20f;
        float startX = w * 0.5f - (pillW * 1.5f + 8f);

        String[] labels = {"◀ LEFT", "● CENTER", "RIGHT ▶"};
        for (int l = 0; l < 3; l++) {
            float px = startX + l * (pillW + 8f);
            RectF pill = new RectF(px, barY, px + pillW, barY + pillH);

            if (l == targetLane) {
                badgePaint.setColor(0xFF00E676);
                badgeTextPaint.setColor(0xFF000000);
            } else {
                badgePaint.setColor(0x881E2333);
                badgeTextPaint.setColor(0xFF8E99B0);
            }

            canvas.drawRoundRect(pill, 6f, 6f, badgePaint);
            badgeTextPaint.setTextAlign(Paint.Align.CENTER);
            badgeTextPaint.setTextSize(9.5f);
            canvas.drawText(labels[l], pill.centerX(), pill.centerY() + 3.5f, badgeTextPaint);
        }
    }

    private void drawActionBadge(Canvas canvas, float x, float y, String text, int color) {
        RectF badge = new RectF(x, y, x + 76f, y + 20f);
        badgePaint.setColor(color);
        canvas.drawRoundRect(badge, 6f, 6f, badgePaint);
        badgeTextPaint.setColor(0xFF000000);
        badgeTextPaint.setTextSize(10f);
        badgeTextPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(text, badge.centerX(), badge.centerY() + 3.5f, badgeTextPaint);
    }

    private void drawCountdownOverlay(Canvas canvas, int w, int h) {
        canvas.drawRect(0, 0, w, h, overlayPaint);

        if (bmpLogo != null) {
            float logoW = 200f;
            float logoH = 75f;
            RectF dst = new RectF(w * 0.5f - logoW * 0.5f, h * 0.25f - logoH * 0.5f,
                    w * 0.5f + logoW * 0.5f, h * 0.25f + logoH * 0.5f);
            canvas.drawBitmap(bmpLogo, null, dst, null);
        }

        int count = ((int) countdownTimer) + 1;
        String text = (count > 0 && count <= 3) ? String.valueOf(count) : "GO!";
        bannerPaint.setColor(0xFFFFD60A);
        bannerPaint.setTextSize(54f);
        canvas.drawText(text, w * 0.5f, h * 0.55f, bannerPaint);

        hudSubTextPaint.setTextAlign(Paint.Align.CENTER);
        hudSubTextPaint.setColor(0xFFFFFFFF);
        hudSubTextPaint.setTextSize(14f);
        canvas.drawText("🏃 Steer Left/Right • Jump • Slide under obstacles", w * 0.5f, h * 0.68f, hudSubTextPaint);
        hudSubTextPaint.setColor(0xFF00E676);
        hudSubTextPaint.setTextSize(12f);
        canvas.drawText("📺 Open TV Browser at " + tvUrl + " to view on big screen!", w * 0.5f, h * 0.78f, hudSubTextPaint);
    }

    private void drawGameOverOverlay(Canvas canvas, int w, int h) {
        canvas.drawRect(0, 0, w, h, overlayPaint);

        bannerPaint.setColor(0xFFFF3B30);
        bannerPaint.setTextSize(40f);
        canvas.drawText("GAME OVER", w * 0.5f, h * 0.30f, bannerPaint);

        hudTextPaint.setTextAlign(Paint.Align.CENTER);
        hudTextPaint.setColor(0xFFFFFFFF);
        hudTextPaint.setTextSize(20f);
        canvas.drawText("Final Score: " + score, w * 0.5f, h * 0.44f, hudTextPaint);

        hudTextPaint.setColor(0xFFFFD60A);
        hudTextPaint.setTextSize(15f);
        canvas.drawText("Coins: " + coinsCollected + "  |  High Score: " + highScore, w * 0.5f, h * 0.54f, hudTextPaint);

        int restartIn = Math.max(1, (int) (6.0f - gameOverTimer));
        badgePaint.setColor(0xFF00E676);
        RectF retryBtn = new RectF(w * 0.5f - 110f, h * 0.65f, w * 0.5f + 110f, h * 0.65f + 36f);
        canvas.drawRoundRect(retryBtn, 10f, 10f, badgePaint);

        badgeTextPaint.setColor(0xFF000000);
        badgeTextPaint.setTextSize(14f);
        badgeTextPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("TAP TO RETRY (" + restartIn + "s)", retryBtn.centerX(), retryBtn.centerY() + 4.5f, badgeTextPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (gameState == STATE_GAME_OVER) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                restartGame();
                return true;
            }
        }

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                touchStartX = event.getX();
                touchStartY = event.getY();
                touchStartTime = SystemClock.elapsedRealtime();
                return true;

            case MotionEvent.ACTION_UP:
                float dx = event.getX() - touchStartX;
                float dy = event.getY() - touchStartY;
                long duration = SystemClock.elapsedRealtime() - touchStartTime;

                if (duration < 350) {
                    if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > 50f) {
                        if (dy < 0) {
                            jump();
                        } else {
                            slide(true);
                        }
                    } else if (Math.abs(dx) > 50f) {
                        if (dx < 0 && targetLane > 0) {
                            setTargetLane(targetLane - 1);
                        } else if (dx > 0 && targetLane < 2) {
                            setTargetLane(targetLane + 1);
                        }
                    } else {
                        if (event.getX() < getWidth() * 0.45f && targetLane > 0) {
                            setTargetLane(targetLane - 1);
                        } else if (event.getX() > getWidth() * 0.55f && targetLane < 2) {
                            setTargetLane(targetLane + 1);
                        }
                    }
                }
                return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (gameState == STATE_GAME_OVER) {
            restartGame();
            return true;
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (targetLane > 0) setTargetLane(targetLane - 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (targetLane < 2) setTargetLane(targetLane + 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_SPACE:
                jump();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                slide(true);
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    public void release() {
        stopLoop();
        if (bmpBg != null) { bmpBg.recycle(); bmpBg = null; }
        if (bmpRunnerRun1 != null) { bmpRunnerRun1.recycle(); bmpRunnerRun1 = null; }
        if (bmpRunnerRun2 != null) { bmpRunnerRun2.recycle(); bmpRunnerRun2 = null; }
        if (bmpRunnerJump != null) { bmpRunnerJump.recycle(); bmpRunnerJump = null; }
        if (bmpRunnerSlide != null) { bmpRunnerSlide.recycle(); bmpRunnerSlide = null; }
        if (bmpObstacleLow != null) { bmpObstacleLow.recycle(); bmpObstacleLow = null; }
        if (bmpObstacleHigh != null) { bmpObstacleHigh.recycle(); bmpObstacleHigh = null; }
        if (bmpCoin != null) { bmpCoin.recycle(); bmpCoin = null; }
        if (bmpShield != null) { bmpShield.recycle(); bmpShield = null; }
        if (bmpHeart != null) { bmpHeart.recycle(); bmpHeart = null; }
        if (bmpLogo != null) { bmpLogo.recycle(); bmpLogo = null; }
        if (bufferBitmap != null) { bufferBitmap.recycle(); bufferBitmap = null; }
    }
}

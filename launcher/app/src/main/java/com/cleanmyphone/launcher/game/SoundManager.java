package com.cleanmyphone.launcher.game;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.os.Build;
import android.util.Log;

/**
 * Ultra-low-latency Game Audio Engine.
 * Uses SoundPool for instant sub-millisecond SFX triggers
 * and MediaPlayer for continuous looping soundtrack.
 */
public class SoundManager {
    private static final String TAG = "SoundManager";

    private final Context context;
    private SoundPool soundPool;
    private MediaPlayer musicPlayer;

    private int soundCoin = 0;
    private int soundJump = 0;
    private int soundSlide = 0;
    private int soundHit = 0;
    private int soundPowerup = 0;
    private int soundGameOver = 0;

    private boolean isMuted = false;
    private boolean isMusicPlaying = false;

    public SoundManager(Context context) {
        this.context = context.getApplicationContext();
        initSoundPool();
        loadSounds();
        initMusic();
    }

    @SuppressWarnings("deprecation")
    private void initSoundPool() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();
                soundPool = new SoundPool.Builder()
                        .setMaxStreams(8)
                        .setAudioAttributes(attrs)
                        .build();
            } else {
                soundPool = new SoundPool(8, AudioManager.STREAM_MUSIC, 0);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error initializing SoundPool: " + t.getMessage(), t);
        }
    }

    private int loadSoundFromAsset(String filename) {
        if (soundPool == null) return 0;
        try {
            AssetFileDescriptor afd = context.getAssets().openFd("game/" + filename);
            int id = soundPool.load(afd, 1);
            afd.close();
            return id;
        } catch (Throwable t) {
            Log.w(TAG, "Could not load asset: " + filename + " (" + t.getMessage() + ")");
            return 0;
        }
    }

    private void loadSounds() {
        soundCoin = loadSoundFromAsset("coin.mp3");
        soundJump = loadSoundFromAsset("jump.mp3");
        soundSlide = loadSoundFromAsset("slide.mp3");
        soundHit = loadSoundFromAsset("hit.mp3");
        soundPowerup = loadSoundFromAsset("powerup.mp3");
        soundGameOver = loadSoundFromAsset("gameover.mp3");
    }

    private void initMusic() {
        try {
            AssetFileDescriptor afd = context.getAssets().openFd("game/music.mp3");
            musicPlayer = new MediaPlayer();
            musicPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            musicPlayer.setLooping(true);
            musicPlayer.setVolume(0.40f, 0.40f);
            musicPlayer.prepare();
        } catch (Throwable t) {
            Log.w(TAG, "Could not prepare background music: " + t.getMessage());
        }
    }

    public void playCoin() {
        playSound(soundCoin, 0.9f);
    }

    public void playJump() {
        playSound(soundJump, 1.0f);
    }

    public void playSlide() {
        playSound(soundSlide, 0.9f);
    }

    public void playHit() {
        playSound(soundHit, 1.0f);
    }

    public void playPowerup() {
        playSound(soundPowerup, 1.0f);
    }

    public void playGameOver() {
        playSound(soundGameOver, 1.0f);
    }

    private void playSound(int soundId, float volume) {
        if (isMuted || soundPool == null || soundId == 0) return;
        try {
            soundPool.play(soundId, volume, volume, 1, 0, 1.0f);
        } catch (Throwable ignored) {}
    }

    public void startMusic() {
        if (isMuted || musicPlayer == null) return;
        try {
            if (!musicPlayer.isPlaying()) {
                musicPlayer.start();
                isMusicPlaying = true;
            }
        } catch (Throwable ignored) {}
    }

    public void pauseMusic() {
        if (musicPlayer == null) return;
        try {
            if (musicPlayer.isPlaying()) {
                musicPlayer.pause();
                isMusicPlaying = false;
            }
        } catch (Throwable ignored) {}
    }

    public void resumeMusic() {
        if (!isMuted && musicPlayer != null && !musicPlayer.isPlaying()) {
            try {
                musicPlayer.start();
                isMusicPlaying = true;
            } catch (Throwable ignored) {}
        }
    }

    public void setMuted(boolean muted) {
        this.isMuted = muted;
        if (muted) {
            pauseMusic();
        } else {
            resumeMusic();
        }
    }

    public boolean isMuted() {
        return isMuted;
    }

    public void release() {
        try {
            if (musicPlayer != null) {
                musicPlayer.stop();
                musicPlayer.release();
                musicPlayer = null;
            }
        } catch (Throwable ignored) {}

        try {
            if (soundPool != null) {
                soundPool.release();
                soundPool = null;
            }
        } catch (Throwable ignored) {}
    }
}

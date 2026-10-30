package com.cleanmyphone.launcher.game;

import android.graphics.Canvas;
import android.graphics.Paint;

/**
 * Lightweight visual particle effect for coin collection and obstacle smashes.
 */
public class Particle {
    public float x, y;
    public float vx, vy;
    public float size;
    public int color;
    public float alpha;
    public float life;
    public float maxLife;
    public boolean active;

    public Particle() {
        this.active = false;
    }

    public void init(float x, float y, float vx, float vy, float size, int color, float maxLife) {
        this.x = x;
        this.y = y;
        this.vx = vx;
        this.vy = vy;
        this.size = size;
        this.color = color;
        this.alpha = 1.0f;
        this.life = 0;
        this.maxLife = maxLife;
        this.active = true;
    }

    public void update(float dt) {
        if (!active) return;
        life += dt;
        if (life >= maxLife) {
            active = false;
            return;
        }
        x += vx * dt;
        y += vy * dt;
        vy += 400.0f * dt; // gravity
        alpha = 1.0f - (life / maxLife);
    }

    public void draw(Canvas canvas, Paint paint) {
        if (!active) return;
        int a = (int) (Math.max(0, Math.min(1.0f, alpha)) * 255);
        paint.setColor(color);
        paint.setAlpha(a);
        canvas.drawCircle(x, y, size * alpha, paint);
    }
}

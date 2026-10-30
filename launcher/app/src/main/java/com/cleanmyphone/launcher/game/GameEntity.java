package com.cleanmyphone.launcher.game;

/**
 * An obstacle, coin, or powerup moving along one of the 3 perspective lanes.
 */
public class GameEntity {
    public static final int TYPE_COIN = 0;
    public static final int TYPE_OBSTACLE_LOW = 1;
    public static final int TYPE_OBSTACLE_HIGH = 2;
    public static final int TYPE_SHIELD = 3;

    public int type;
    public int lane; // 0=Left, 1=Center, 2=Right
    public float z; // Distance: 1200f (horizon) down to 0f (player) and -150f (passed)
    public boolean active;
    public float animTimer;
    public boolean collectedOrHit;

    public GameEntity(int type, int lane, float z) {
        this.type = type;
        this.lane = lane;
        this.z = z;
        this.active = true;
        this.animTimer = (float) (Math.random() * 100);
        this.collectedOrHit = false;
    }

    public void update(float dt, float speed) {
        z -= speed * dt;
        animTimer += dt;
        if (z < -150f) {
            active = false;
        }
    }
}

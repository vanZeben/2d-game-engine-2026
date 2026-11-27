package ge.entity;

import ge.Screen;
import ge.gfx.SpriteSheet;

/**
 * A moving thing in the {@link ge.World}: the local player now, other players (fed from network packets) and
 * monsters later. A mob has a position and knows how to draw itself; it does not know about input, windows or
 * the network. All of a world's mobs are ticked and rendered from the one thread that owns the world.
 */
public abstract class Mob {
    private int x, y;

    protected Mob(int x, int y) {
        this.x = x;
        this.y = y;
    }

    /** World pixel x of the sprite's top-left corner (may be negative). */
    public int x() {
        return x;
    }

    /** World pixel y of the sprite's top-left corner (may be negative). */
    public int y() {
        return y;
    }

    /** Moves the sprite by (dx, dy) pixels. Collision will hook in here in a later episode. */
    protected void move(int dx, int dy) {
        x += dx;
        y += dy;
    }

    /** Advances this mob one fixed step without local input (mobs driven from outside override it). Default: nothing. */
    public void tick() {}

    /**
     * Draws the sprite on top of whatever the screen holds (no clear): world pixel (x, y) lands on screen pixel
     * (x - xOffset, y - yOffset).
     */
    public abstract void render(Screen screen, SpriteSheet sheet, int xOffset, int yOffset);
}

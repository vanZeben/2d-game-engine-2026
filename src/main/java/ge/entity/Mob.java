package ge.entity;

import ge.Screen;
import ge.gfx.SpriteSheet;

/**
 * A moving thing in the {@link ge.World}: the local player now, other players (fed from network packets) and
 * monsters later. A mob has a position and knows how to draw itself; it does not know about input, windows or
 * the network. All of a world's mobs are ticked and rendered from the one thread that owns the world.
 */
public abstract class Mob {
    /** Facing of a mob; the sprite sheet draws every pose from these four. */
    public enum Dir { DOWN, UP, LEFT, RIGHT }

    /** Consecutive moving ticks per walk frame (the frame alternates 0, 1, 0, 1 ... every WALK_PERIOD ticks). */
    public static final int WALK_PERIOD = 8;

    private int x, y;
    private Dir dir = Dir.DOWN;
    private int walkFrame;
    /** Consecutive moving ticks, reset by an idle tick. */
    private int movingTicks;

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

    /** Facing; DOWN until the mob first moves, and it persists after the mob stops. */
    public Dir dir() {
        return dir;
    }

    /** Walk animation frame, 0 or 1; 0 while standing. */
    public int walkFrame() {
        return walkFrame;
    }

    /**
     * One movement tick: (dx, dy) = (0, 0) is an idle tick. Updates the facing and walk frame (see
     * {@link #updatePose}) and then the position ({@link #translate}); the two are independent so a later
     * collision check can refuse the position change while the mob still turns and animates.
     */
    protected void move(int dx, int dy) {
        updatePose(dx, dy);
        translate(dx, dy);
    }

    /**
     * Facing/animation bookkeeping for the intended move (dx, dy). Idle resets the moving-tick counter and the
     * frame and keeps the facing. Otherwise the counter counts consecutive moving ticks (a turn without an idle
     * tick does not reset it), frame = ((n-1) / WALK_PERIOD) % 2, and facing is LEFT/RIGHT if dx != 0
     * (horizontal wins on diagonals), else UP/DOWN.
     */
    protected final void updatePose(int dx, int dy) {
        if (dx == 0 && dy == 0) {
            movingTicks = 0;
            walkFrame = 0;
            return;
        }
        movingTicks++;
        walkFrame = ((movingTicks - 1) / WALK_PERIOD) % 2;
        if (dx != 0) dir = dx < 0 ? Dir.LEFT : Dir.RIGHT;
        else dir = dy < 0 ? Dir.UP : Dir.DOWN;
    }

    /** Changes the position by (dx, dy) pixels with no checks and no pose change. */
    protected final void translate(int dx, int dy) {
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

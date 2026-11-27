package ge.entity;

import ge.Screen;
import ge.gfx.SpriteSheet;
import ge.level.Level;
import ge.level.Tile;

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
    /** The level this mob collides with; null = it moves freely (a stand-alone mob, or one not yet in a world). */
    private Level level;

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

    /** Sets the level this mob collides against (null = no collision). {@link ge.World} calls this for its mobs. */
    public void attachLevel(Level level) {
        this.level = level;
    }

    /**
     * Collision box in sprite-local pixels, inclusive on both ends: the mob at (x, y) occupies world pixels
     * x+boxX0()..x+boxX1() by y+boxY0()..y+boxY1(). Default is the whole 16x16 sprite; subclasses override.
     */
    protected int boxX0() {
        return 0;
    }

    protected int boxX1() {
        return 15;
    }

    protected int boxY0() {
        return 0;
    }

    protected int boxY1() {
        return 15;
    }

    /**
     * One movement tick: (dx, dy) = (0, 0) is an idle tick. Updates the facing and walk frame (see
     * {@link #updatePose}) for the intended move, then moves the position as far as {@link #slide} allows. The
     * pose is bookkept first and independently, so a blocked mob still turns and animates.
     */
    protected void move(int dx, int dy) {
        updatePose(dx, dy);
        slide(dx, dy);
    }

    /**
     * Moves by (dx, dy) one axis at a time, a pixel at a time, stopping each axis at the first step whose box
     * would overlap a solid tile; the other axis is unaffected (so a diagonal against a wall slides). Without a
     * level this is {@link #translate}.
     */
    private void slide(int dx, int dy) {
        if (level == null) {
            translate(dx, dy);
            return;
        }
        for (int i = Math.abs(dx), sx = Integer.signum(dx); i > 0 && !hitsSolid(x + sx, y); i--) x += sx;
        for (int i = Math.abs(dy), sy = Integer.signum(dy); i > 0 && !hitsSolid(x, y + sy); i--) y += sy;
    }

    /**
     * Is the mob swimming: it has a level and the tile containing the centre of its collision box (world pixel
     * (x + (boxX0+boxX1)/2, y + (boxY0+boxY1)/2), cell = floorDiv by 8) is {@link Tile#WATER}? Depends only on
     * position and level.
     */
    public boolean isSwimming() {
        if (level == null) return false;
        int size = ge.Screen.TILE_SIZE;
        return level.getTile(Math.floorDiv(x + (boxX0() + boxX1()) / 2, size),
                Math.floorDiv(y + (boxY0() + boxY1()) / 2, size)) == Tile.WATER;
    }

    /** Does the box of a mob at (px, py) overlap a solid tile? Visits only the cells the box touches. */
    private boolean hitsSolid(int px, int py) {
        int size = ge.Screen.TILE_SIZE;
        int cx1 = Math.floorDiv(px + boxX1(), size), cy1 = Math.floorDiv(py + boxY1(), size);
        for (int cy = Math.floorDiv(py + boxY0(), size); cy <= cy1; cy++) {
            for (int cx = Math.floorDiv(px + boxX0(), size); cx <= cx1; cx++) {
                Tile t = level.getTile(cx, cy);
                if (t.isSolid()) return true;
            }
        }
        return false;
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

    /** Changes the position by (dx, dy) pixels with no collision check and no pose change. */
    protected final void translate(int dx, int dy) {
        x += dx;
        y += dy;
    }

    /**
     * Puts the mob at world pixel (x, y) with no collision check and no pose change. For mobs whose position is
     * authoritative elsewhere (remote players, positioned from network packets by their own client).
     */
    protected final void setPosition(int x, int y) {
        this.x = x;
        this.y = y;
    }

    /** Advances this mob one fixed step without local input (mobs driven from outside override it). Default: nothing. */
    public void tick() {}

    /**
     * Draws the sprite on top of whatever the screen holds (no clear): world pixel (x, y) lands on screen pixel
     * (x - xOffset, y - yOffset).
     */
    public abstract void render(Screen screen, SpriteSheet sheet, int xOffset, int yOffset);
}

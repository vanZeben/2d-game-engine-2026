package ge;

import ge.entity.Mob;
import ge.entity.Player;
import ge.gfx.SpriteSheet;
import ge.input.InputHandler;
import ge.level.Level;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A {@link Level} plus the mobs standing in it, and the camera that follows the player. Not thread-safe: one
 * thread owns a world and does all ticking, rendering and entity changes (network code hands its updates to that
 * thread rather than touching the world).
 */
public final class World {
    private final Level level;
    private final Player player;
    private final List<Mob> entities = new ArrayList<>();

    /** The player becomes the first entity. */
    public World(Level level, Player player) {
        if (level == null) throw new IllegalArgumentException("level is null");
        if (player == null) throw new IllegalArgumentException("player is null");
        this.level = level;
        this.player = player;
        entities.add(player);
    }

    public Level level() {
        return level;
    }

    public Player player() {
        return player;
    }

    /** Every mob in draw order (the player first, then later additions); a read-only view. */
    public List<Mob> entities() {
        return Collections.unmodifiableList(entities);
    }

    /** Adds a mob (drawn after those already here). */
    public void add(Mob mob) {
        if (mob == null) throw new IllegalArgumentException("mob is null");
        if (!entities.contains(mob)) entities.add(mob);
    }

    /** Removes a mob; the player cannot be removed. */
    public void remove(Mob mob) {
        if (mob != player) entities.remove(mob);
    }

    /** One fixed step: the player reads the input, every other mob ticks on its own. */
    public void tick(InputHandler in) {
        player.tick(in);
        for (Mob m : entities) {
            if (m != player) m.tick();
        }
    }

    /** World pixel shown at screen x 0: the player's sprite stays centred. Not clamped to the level. */
    public int cameraX() {
        return player.x() + Player.SIZE / 2 - Game.WIDTH / 2;
    }

    /** World pixel shown at screen y 0. */
    public int cameraY() {
        return player.y() + Player.SIZE / 2 - Game.HEIGHT / 2;
    }

    /** Draws the level at the camera, then the entities on top. Does not clear the screen. */
    public void render(Screen screen, SpriteSheet sheet) {
        int cx = cameraX(), cy = cameraY();
        level.render(screen, sheet, cx, cy);
        for (Mob m : entities) m.render(screen, sheet, cx, cy);
    }
}

package ge;

import ge.input.InputHandler;
import java.util.Random;

/** Game state and rules. Knows nothing about windows or files: it ticks and renders into a {@link Screen}. */
public class Game {
    public static final String NAME = "Java 2D Game Engine";
    public static final int WIDTH = 160;
    public static final int HEIGHT = 120;
    public static final int SCALE = 3;
    public static final int TICKS_PER_SECOND = 60;

    /** Seed used when none is given with {@code --seed}. */
    public static final long DEFAULT_SEED = 0L;

    private final Scene scene;
    private final InputHandler input = new InputHandler();
    private final Random random;
    private long tickCount;

    public Game() {
        this(null);
    }

    /** @param scene a fixed test scene to draw every frame, or null for the normal game */
    public Game(Scene scene) {
        this(scene, DEFAULT_SEED);
    }

    /** @param seed seeds the game's single {@link Random}, so runs with the same seed and input repeat exactly */
    public Game(Scene scene, long seed) {
        this.scene = scene;
        this.random = new Random(seed);
    }

    /** The one input source the game reads: the window's key listener and the script player both feed it. */
    public InputHandler input() {
        return input;
    }

    /** The game's only source of randomness (seeded). */
    public Random random() {
        return random;
    }

    /** Advances the simulation by one fixed step. */
    public void tick() {
        tickCount++;
    }

    /** Draws the current state into the screen buffer. */
    public void render(Screen screen) {
        if (scene != null) {
            scene.render(screen);
            return;
        }
        screen.clear(0x000000);
    }

    public long tickCount() {
        return tickCount;
    }
}

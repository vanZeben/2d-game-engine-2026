package ge;

/** Game state and rules. Knows nothing about windows or files: it ticks and renders into a {@link Screen}. */
public class Game {
    public static final String NAME = "Java 2D Game Engine";
    public static final int WIDTH = 160;
    public static final int HEIGHT = 120;
    public static final int SCALE = 3;
    public static final int TICKS_PER_SECOND = 60;

    private final Scene scene;
    private long tickCount;

    public Game() {
        this(null);
    }

    /** @param scene a fixed test scene to draw every frame, or null for the normal game */
    public Game(Scene scene) {
        this.scene = scene;
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

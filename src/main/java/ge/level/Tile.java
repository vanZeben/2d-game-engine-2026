package ge.level;

import ge.gfx.Palette;
import java.util.List;

/**
 * The tile registry: exactly four immutable tiles, ordered by id.
 *
 * <p>Each tile has a stable id (its index in {@link #all()}), a solidity flag for collision (episode 12), the
 * 0xRRGGBB colour that marks it in a level PNG (episode 13), its coordinates in the sprite sheet (tile units)
 * and the four {@link Palette} colours it is drawn with. Water's sheet coordinates are those of its first
 * animation frame; it cycles {@link #WATER_FRAMES} frames, frame f being sheet tile (f, 5) (see {@link #waterFrame}).
 */
public final class Tile {
    public static final Tile VOID = new Tile(0, false, 0x000000, 0, 0,
            Palette.index(0, 0, 0), Palette.index(0, 0, 0), Palette.index(0, 0, 0), Palette.index(0, 0, 0));
    public static final Tile STONE = new Tile(1, true, 0x555555, 1, 0,
            Palette.index(1, 1, 1), Palette.index(2, 2, 2), Palette.index(3, 3, 3), Palette.index(4, 4, 4));
    public static final Tile GRASS = new Tile(2, false, 0x00FF00, 2, 0,
            Palette.index(0, 1, 0), Palette.index(1, 3, 0), Palette.index(1, 4, 1), Palette.index(3, 5, 1));
    public static final Tile WATER = new Tile(3, false, 0x0000FF, 0, 5,
            Palette.index(0, 0, 2), Palette.index(0, 1, 4), Palette.index(1, 3, 5), Palette.index(3, 5, 5));

    /** Number of animation frames of {@link #WATER}: frame f is the sheet tile (f, 5). */
    public static final int WATER_FRAMES = 3;
    /** Ticks each water frame is shown. */
    public static final int WATER_FRAME_TICKS = 15;

    private static final List<Tile> ALL = List.of(VOID, STONE, GRASS, WATER);

    private final int id;
    private final boolean solid;
    private final int levelColour;
    private final int sheetX, sheetY;
    private final int[] colours;

    private Tile(int id, boolean solid, int levelColour, int sheetX, int sheetY, int... colours) {
        this.id = id;
        this.solid = solid;
        this.levelColour = levelColour;
        this.sheetX = sheetX;
        this.sheetY = sheetY;
        this.colours = colours;
    }

    public int id() {
        return id;
    }

    public boolean isSolid() {
        return solid;
    }

    /** The 0xRRGGBB colour of this tile in a level image. */
    public int levelColour() {
        return levelColour;
    }

    public int sheetX() {
        return sheetX;
    }

    public int sheetY() {
        return sheetY;
    }

    /** The 4 palette colours (index 0..215 or {@link Palette#TRANSPARENT}); a fresh copy each call. */
    public int[] colours() {
        return colours.clone();
    }

    /** The internal colour array, for drawing without a per-tile allocation. Callers must not modify it. */
    int[] paletteColours() {
        return colours;
    }

    /** The water frame (0..{@link #WATER_FRAMES}-1) shown at world tick {@code tick} (>= 0): 0 for ticks 0..14, 1 for 15..29, 2 for 30..44, then 0 again. */
    public static int waterFrame(long tick) {
        return (int) ((tick / WATER_FRAME_TICKS) % WATER_FRAMES);
    }

    /** @throws IllegalArgumentException unless 0 <= id <= 3 */
    public static Tile byId(int id) {
        if (id < 0 || id >= ALL.size()) throw new IllegalArgumentException("no tile with id " + id);
        return ALL.get(id);
    }

    /**
     * The tile whose level colour is {@code rgb} (alpha bits ignored). A colour no tile uses is {@link #VOID}
     * (episode 13: an unknown pixel colour in a level image becomes void).
     */
    public static Tile byLevelColour(int rgb) {
        int c = rgb & 0xFFFFFF;
        for (Tile t : ALL) if (t.levelColour == c) return t;
        return VOID;
    }

    /** The four tiles ordered by id (unmodifiable). */
    public static List<Tile> all() {
        return ALL;
    }
}

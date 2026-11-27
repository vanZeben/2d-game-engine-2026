package ge.entity;

import ge.Screen;
import ge.gfx.Palette;
import ge.gfx.SpriteSheet;
import ge.input.InputHandler;

/** The locally controlled player: a 16x16 sprite (2x2 tiles) that walks one pixel per tick per axis and collides with solid tiles by its feet box. */
public final class Player extends Mob {
    /** Sprite width and height in pixels. */
    public static final int SIZE = 16;
    /** Pixels moved per tick on each held axis. */
    public static final int SPEED = 1;
    /**
     * The collision box ("feet box") in sprite-local pixels, inclusive on both ends: 8 wide by 6 high at the
     * bottom of the sprite, so the head can overlap tiles behind it and the player fits a one-tile gap.
     */
    public static final int BOX_X0 = 4, BOX_X1 = 11, BOX_Y0 = 10, BOX_Y1 = 15;
    /** Sprite-sheet tile row of the top half of every pose; each pose is 2x2 tiles (top row here, bottom row +1). */
    private static final int SHEET_Y = 28;
    /** Sprite-sheet tile column of the left half of each pose: down, up, sideways frame 0, sideways frame 1. */
    private static final int COL_DOWN = 0, COL_UP = 2, COL_SIDE_0 = 4, COL_SIDE_1 = 6;
    /** Palette index (or transparent) for each sprite-sheet colour index 0..3. */
    public static final int[] COLOURS = {
        Palette.TRANSPARENT, Palette.index(1, 1, 3), Palette.index(5, 4, 3), Palette.index(5, 5, 5)};

    /** Sheet tile of the swimming ripple and its colours: only sheet colour-index 2 is drawn, in white. */
    private static final int RIPPLE_TX = 2, RIPPLE_TY = 5;
    private static final int[] RIPPLE_COLOURS = {
        Palette.TRANSPARENT, Palette.TRANSPARENT, Palette.index(5, 5, 5), Palette.TRANSPARENT};

    /** @param x world pixel x of the sprite's top-left corner @param y likewise for y */
    public Player(int x, int y) {
        super(x, y);
    }

    /** One fixed step: moves by SPEED on each axis the input holds; opposite keys cancel; solid tiles stop it (per axis). */
    public void tick(InputHandler in) {
        int dx = (in.right() ? 1 : 0) - (in.left() ? 1 : 0);
        int dy = (in.down() ? 1 : 0) - (in.up() ? 1 : 0);
        move(SPEED * dx, SPEED * dy);
    }

    @Override
    protected int boxX0() {
        return BOX_X0;
    }

    @Override
    protected int boxX1() {
        return BOX_X1;
    }

    @Override
    protected int boxY0() {
        return BOX_Y0;
    }

    @Override
    protected int boxY1() {
        return BOX_Y1;
    }

    @Override
    public void render(Screen screen, SpriteSheet sheet, int xOffset, int yOffset) {
        drawPose(screen, sheet, x() - xOffset, y() - yOffset, dir(), walkFrame(), isSwimming());
    }

    /**
     * Draws the 16x16 sprite for a facing and walk frame with its top-left at screen (sx, sy). Depends only on
     * (dir, walkFrame), so remote players can render from network state. Down and up have one sheet frame, so
     * walk frame 1 is that frame flipped left-right; sideways uses two sheet frames (drawn facing right) and
     * LEFT flips the whole sprite.
     */
    public static void drawPose(Screen screen, SpriteSheet sheet, int sx, int sy, Dir dir, int walkFrame) {
        drawPose(screen, sheet, sx, sy, dir, walkFrame, false);
    }

    /**
     * As the 6-argument pose; with {@code swimming} only the top half (rows 0..7) of the pose is drawn and the
     * lower half is left as it is except for a ripple: sheet tile (2, 5) at (sx, sy+8) and again mirrored at
     * (sx+8, sy+8), drawing only its colour-index-2 pixels in {@link #RIPPLE_COLOUR}.
     */
    public static void drawPose(Screen screen, SpriteSheet sheet, int sx, int sy, Dir dir, int walkFrame,
            boolean swimming) {
        int col;
        boolean mirror;
        switch (dir) {
            case DOWN -> { col = COL_DOWN; mirror = walkFrame == 1; }
            case UP -> { col = COL_UP; mirror = walkFrame == 1; }
            case RIGHT -> { col = walkFrame == 0 ? COL_SIDE_0 : COL_SIDE_1; mirror = false; }
            default -> { col = walkFrame == 0 ? COL_SIDE_0 : COL_SIDE_1; mirror = true; }
        }
        int half = Screen.TILE_SIZE;
        int flip = mirror ? Screen.MIRROR_X : Screen.MIRROR_NONE;
        // Mirroring the 2x2 sprite also swaps its left and right tiles.
        int leftX = mirror ? sx + half : sx, rightX = mirror ? sx : sx + half;
        screen.drawPaletteTile(sheet, col, SHEET_Y, leftX, sy, COLOURS, flip);
        screen.drawPaletteTile(sheet, col + 1, SHEET_Y, rightX, sy, COLOURS, flip);
        if (swimming) {
            screen.drawPaletteTile(sheet, RIPPLE_TX, RIPPLE_TY, sx, sy + half, RIPPLE_COLOURS);
            screen.drawPaletteTile(sheet, RIPPLE_TX, RIPPLE_TY, sx + half, sy + half, RIPPLE_COLOURS, Screen.MIRROR_X);
            return;
        }
        screen.drawPaletteTile(sheet, col, SHEET_Y + 1, leftX, sy + half, COLOURS, flip);
        screen.drawPaletteTile(sheet, col + 1, SHEET_Y + 1, rightX, sy + half, COLOURS, flip);
    }
}

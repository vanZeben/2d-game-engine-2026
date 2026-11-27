package ge.entity;

import ge.Screen;
import ge.gfx.Palette;
import ge.gfx.SpriteSheet;
import ge.input.InputHandler;

/** The locally controlled player: a 16x16 sprite (2x2 tiles) that walks one pixel per tick per axis. */
public final class Player extends Mob {
    /** Sprite width and height in pixels. */
    public static final int SIZE = 16;
    /** Pixels moved per tick on each held axis. */
    public static final int SPEED = 1;
    /** Sprite-sheet tile of the top-left quarter of the facing-down sprite; the sprite is 2x2 tiles from here. */
    private static final int SHEET_X = 0, SHEET_Y = 28;
    /** Palette index (or transparent) for each sprite-sheet colour index 0..3. */
    public static final int[] COLOURS = {
        Palette.TRANSPARENT, Palette.index(1, 1, 3), Palette.index(5, 4, 3), Palette.index(5, 5, 5)};

    /** @param x world pixel x of the sprite's top-left corner @param y likewise for y */
    public Player(int x, int y) {
        super(x, y);
    }

    /** One fixed step: moves by SPEED on each axis the input holds; opposite keys cancel; no collision yet. */
    public void tick(InputHandler in) {
        int dx = (in.right() ? 1 : 0) - (in.left() ? 1 : 0);
        int dy = (in.down() ? 1 : 0) - (in.up() ? 1 : 0);
        move(SPEED * dx, SPEED * dy);
    }

    @Override
    public void render(Screen screen, SpriteSheet sheet, int xOffset, int yOffset) {
        int sx = x() - xOffset, sy = y() - yOffset;
        int half = Screen.TILE_SIZE;
        screen.drawPaletteTile(sheet, SHEET_X, SHEET_Y, sx, sy, COLOURS);
        screen.drawPaletteTile(sheet, SHEET_X + 1, SHEET_Y, sx + half, sy, COLOURS);
        screen.drawPaletteTile(sheet, SHEET_X, SHEET_Y + 1, sx, sy + half, COLOURS);
        screen.drawPaletteTile(sheet, SHEET_X + 1, SHEET_Y + 1, sx + half, sy + half, COLOURS);
    }
}

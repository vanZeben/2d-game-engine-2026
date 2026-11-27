package ge;

import ge.gfx.SpriteSheet;

/**
 * A fixed test scene for headless runs, chosen with {@code --scene}. Every frame is the whole screen
 * cleared to black with one sprite-sheet tile drawn at a pixel position.
 *
 * <p>Syntax: {@code tile:TX,TY@PX,PY} (decimal ints; PX,PY may be negative). The tile is drawn with
 * the interim grey colours until episode 5 brings the palette.
 */
public final class Scene {
    /** Stand-in colours (index i -> 0x555555 * i) used until the 216-colour palette exists. */
    public static final int[] INTERIM_GREY = {0x000000, 0x555555, 0xAAAAAA, 0xFFFFFF};

    private final SpriteSheet sheet;
    private final int tileX, tileY, x, y;

    private Scene(SpriteSheet sheet, int tileX, int tileY, int x, int y) {
        this.sheet = sheet;
        this.tileX = tileX;
        this.tileY = tileY;
        this.x = x;
        this.y = y;
    }

    /** Parses a scene spec and loads the sprite sheet it needs. @throws IllegalArgumentException if malformed. */
    public static Scene parse(String spec) {
        String bad = "bad --scene '" + spec + "' (expected tile:TX,TY@PX,PY)";
        if (!spec.startsWith("tile:")) throw new IllegalArgumentException(bad);
        String[] halves = spec.substring("tile:".length()).split("@", -1);
        if (halves.length != 2) throw new IllegalArgumentException(bad);
        int[] tile = ints(halves[0], bad);
        int[] pos = ints(halves[1], bad);
        SpriteSheet sheet = SpriteSheet.load("/sprite_sheet.png");
        int tilesX = sheet.width() / Screen.TILE_SIZE, tilesY = sheet.height() / Screen.TILE_SIZE;
        if (tile[0] < 0 || tile[1] < 0 || tile[0] >= tilesX || tile[1] >= tilesY) {
            throw new IllegalArgumentException("bad --scene '" + spec + "': tile outside the "
                    + tilesX + "x" + tilesY + " grid");
        }
        return new Scene(sheet, tile[0], tile[1], pos[0], pos[1]);
    }

    private static int[] ints(String pair, String error) {
        String[] parts = pair.split(",", -1);
        if (parts.length != 2) throw new IllegalArgumentException(error);
        try {
            return new int[] {Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(error);
        }
    }

    public void render(Screen screen) {
        screen.clear(0x000000);
        screen.drawTile(sheet, tileX, tileY, x, y, INTERIM_GREY);
    }
}

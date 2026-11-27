package ge;

import ge.gfx.Palette;
import ge.gfx.SpriteSheet;

/**
 * A fixed test scene for headless runs, chosen with {@code --scene}. Every frame is the whole screen
 * cleared to black with one sprite-sheet tile drawn once ({@code tile:}) or tiled over the whole screen
 * ({@code fill:}).
 *
 * <p>Syntax:
 * <ul>
 *   <li>{@code tile:TX,TY@PX,PY[:colours=C0,C1,C2,C3]} - the tile at pixel (PX,PY) (may be negative)</li>
 *   <li>{@code fill:TX,TY[:colours=C0,C1,C2,C3]} - the tile at every (8*i, 8*j) covering the screen</li>
 * </ul>
 * Each Ci is three digits "RGB" (levels 0..5, a {@link Palette} entry) or "t" for transparent. Without
 * {@code colours=} the tile is drawn in the interim grey (as in episode 3), via {@code drawTile}.
 */
public final class Scene {
    /** Stand-in colours (index i -> 0x555555 * i) used when a scene gives no colours. */
    public static final int[] INTERIM_GREY = {0x000000, 0x555555, 0xAAAAAA, 0xFFFFFF};

    private static final String PATH = "/sprite_sheet.png";
    private static SpriteSheet sharedSheet;

    private final SpriteSheet sheet;
    private final boolean fill;
    private final int tileX, tileY, x, y;
    /** Palette indexes / TRANSPARENT, or null for the interim grey. */
    private final int[] colours;

    private Scene(SpriteSheet sheet, boolean fill, int tileX, int tileY, int x, int y, int[] colours) {
        this.sheet = sheet;
        this.fill = fill;
        this.tileX = tileX;
        this.tileY = tileY;
        this.x = x;
        this.y = y;
        this.colours = colours;
    }

    /** The sprite sheet is decoded once and shared by every scene in the process. */
    private static synchronized SpriteSheet sheet() {
        if (sharedSheet == null) sharedSheet = SpriteSheet.load(PATH);
        return sharedSheet;
    }

    /** Parses a scene spec and loads the sprite sheet it needs. @throws IllegalArgumentException if malformed. */
    public static Scene parse(String spec) {
        String bad = "bad --scene '" + spec + "' (expected tile:TX,TY@PX,PY[:colours=C0,C1,C2,C3] "
                + "or fill:TX,TY[:colours=C0,C1,C2,C3]; Ci = three digits 0..5 or t)";
        String[] parts = spec.split(":", -1);
        if (parts.length < 2 || parts.length > 3) throw new IllegalArgumentException(bad);
        boolean fill;
        switch (parts[0]) {
            case "tile" -> fill = false;
            case "fill" -> fill = true;
            default -> throw new IllegalArgumentException(bad);
        }
        int[] tile, pos;
        if (fill) {
            tile = ints(parts[1], bad);
            pos = new int[] {0, 0};
        } else {
            String[] halves = parts[1].split("@", -1);
            if (halves.length != 2) throw new IllegalArgumentException(bad);
            tile = ints(halves[0], bad);
            pos = ints(halves[1], bad);
        }
        int[] colours = parts.length == 3 ? colours(parts[2], bad) : null;
        SpriteSheet sheet = sheet();
        int tilesX = sheet.width() / Screen.TILE_SIZE, tilesY = sheet.height() / Screen.TILE_SIZE;
        if (tile[0] < 0 || tile[1] < 0 || tile[0] >= tilesX || tile[1] >= tilesY) {
            throw new IllegalArgumentException("bad --scene '" + spec + "': tile outside the "
                    + tilesX + "x" + tilesY + " grid");
        }
        return new Scene(sheet, fill, tile[0], tile[1], pos[0], pos[1], colours);
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

    /** Parses {@code colours=C0,C1,C2,C3} into palette indexes (or TRANSPARENT). */
    private static int[] colours(String part, String error) {
        String prefix = "colours=";
        if (!part.startsWith(prefix)) throw new IllegalArgumentException(error);
        String[] toks = part.substring(prefix.length()).split(",", -1);
        if (toks.length != SpriteSheet.COLOURS) throw new IllegalArgumentException(error);
        int[] out = new int[SpriteSheet.COLOURS];
        for (int i = 0; i < toks.length; i++) {
            String t = toks[i];
            if (t.equals("t")) {
                out[i] = Palette.TRANSPARENT;
            } else if (t.length() == 3 && level(t.charAt(0)) && level(t.charAt(1)) && level(t.charAt(2))) {
                out[i] = Palette.index(t.charAt(0) - '0', t.charAt(1) - '0', t.charAt(2) - '0');
            } else {
                throw new IllegalArgumentException(error);
            }
        }
        return out;
    }

    private static boolean level(char c) {
        return c >= '0' && c <= '5';
    }

    public void render(Screen screen) {
        screen.clear(0x000000);
        if (fill) {
            for (int py = 0; py < screen.height; py += Screen.TILE_SIZE) {
                for (int px = 0; px < screen.width; px += Screen.TILE_SIZE) draw(screen, px, py);
            }
        } else {
            draw(screen, x, y);
        }
    }

    private void draw(Screen screen, int px, int py) {
        if (colours == null) screen.drawTile(sheet, tileX, tileY, px, py, INTERIM_GREY);
        else screen.drawPaletteTile(sheet, tileX, tileY, px, py, colours);
    }
}

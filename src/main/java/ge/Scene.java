package ge;

import ge.gfx.Font;
import ge.gfx.Palette;
import ge.entity.Player;
import ge.gfx.SpriteSheet;
import ge.level.Level;
import ge.level.Tile;

/**
 * A fixed test scene for headless runs, chosen with {@code --scene}. Every frame is the whole screen
 * cleared to black with one sprite-sheet tile drawn once ({@code tile:}) or tiled over the whole screen
 * ({@code fill:}).
 *
 * <p>Syntax:
 * <ul>
 *   <li>{@code tile:TX,TY@PX,PY[:colours=C0,C1,C2,C3][:mirror=M]} - the tile at pixel (PX,PY) (may be negative)</li>
 *   <li>{@code fill:TX,TY[:colours=C0,C1,C2,C3][:mirror=M]} - the tile at every (8*i, 8*j) covering the screen</li>
 *   <li>{@code text:X,Y[:colours=C0,C1,C2,C3]:TEXT} - {@link Font} text with its top-left at pixel (X,Y)
 *       (may be negative). TEXT is the last field, verbatim (it may contain ':' and may be empty); the colours
 *       option is recognised only when the part after "X,Y:" starts with {@code colours=}. Without it the
 *       text is white ink on a transparent background.</li>
 *   <li>{@code level:gen:W,H,SEED@XOFF,YOFF} - {@link Level#generate(int, int, long)} (W,H >= 1 tiles, SEED a
 *       long) drawn with the view scrolled to pixel offset (XOFF,YOFF) (may be negative)</li>
 *   <li>{@code game:gen:W,H,SEED@SPAWNX,SPAWNY} - a playable {@link World}: the generated level with the player's
 *       sprite top-left at pixel (SPAWNX,SPAWNY) (may be negative)</li>
 *   <li>{@code game:grass:W,H@SPAWNX,SPAWNY} - the same with a W x H level of all grass</li>
 * </ul>
 * The optional parts may come in either order, each at most once. M is {@code x}, {@code y} or {@code xy}
 * (flip each 8x8 block in place on X, on Y, or both).
 * Each Ci is three digits "RGB" (levels 0..5, a {@link Palette} entry) or "t" for transparent. Without
 * {@code colours=} the tile is drawn in the interim grey (as in episode 3), via {@code drawTile}.
 */
public final class Scene {
    /** Stand-in colours (index i -> 0x555555 * i) used when a scene gives no colours. */
    public static final int[] INTERIM_GREY = {0x000000, 0x555555, 0xAAAAAA, 0xFFFFFF};

    private static final String TEXT_PREFIX = "text:";
    private static final String LEVEL_PREFIX = "level:";
    private static final String GAME_PREFIX = "game:";
    /** Default text colours: white ink (slot 3) over a transparent background. */
    private static final int[] TEXT_COLOURS = {Palette.TRANSPARENT, Palette.TRANSPARENT, Palette.TRANSPARENT,
        Palette.index(5, 5, 5)};
    private static final String PATH = "/sprite_sheet.png";
    private static SpriteSheet sharedSheet;

    private final SpriteSheet sheet;
    private final boolean fill;
    private final int tileX, tileY, x, y;
    /** Screen.MIRROR_* bit set. */
    private final int mirror;
    /** The text of a {@code text:} scene, or null for a tile scene. */
    private final String text;
    /** The level of a {@code level:} scene (drawn at offset x,y), or null. */
    private final Level level;
    /** Palette indexes / TRANSPARENT, or null for the interim grey. */
    private final int[] colours;
    /** The recipe of a {@code game:} scene, or null. */
    private final GameSpec game;

    /** What a {@code game:} scene builds: a level (all grass, or generated from seed) and a spawn point. */
    private record GameSpec(boolean grass, int width, int height, long seed, int spawnX, int spawnY) {}

    private Scene(SpriteSheet sheet, boolean fill, int tileX, int tileY, int x, int y, int[] colours, int mirror,
            String text, Level level) {
        this(sheet, fill, tileX, tileY, x, y, colours, mirror, text, level, null);
    }

    private Scene(SpriteSheet sheet, boolean fill, int tileX, int tileY, int x, int y, int[] colours, int mirror,
            String text, Level level, GameSpec game) {
        this.sheet = sheet;
        this.fill = fill;
        this.tileX = tileX;
        this.tileY = tileY;
        this.x = x;
        this.y = y;
        this.colours = colours;
        this.mirror = mirror;
        this.text = text;
        this.level = level;
        this.game = game;
    }

    /** The sprite sheet is decoded once and shared by every scene in the process. */
    static synchronized SpriteSheet sheet() {
        if (sharedSheet == null) sharedSheet = SpriteSheet.load(PATH);
        return sharedSheet;
    }

    /** Parses a scene spec and loads the sprite sheet it needs. @throws IllegalArgumentException if malformed. */
    public static Scene parse(String spec) {
        if (spec.startsWith(TEXT_PREFIX)) return parseText(spec);
        if (spec.startsWith(LEVEL_PREFIX)) return parseLevel(spec);
        if (spec.startsWith(GAME_PREFIX)) return parseGame(spec);
        String bad = "bad --scene '" + spec + "' (expected tile:TX,TY@PX,PY[:colours=C0,C1,C2,C3][:mirror=M] "
                + "or fill:TX,TY[:colours=C0,C1,C2,C3][:mirror=M]; Ci = three digits 0..5 or t; M = x, y or xy)";
        String[] parts = spec.split(":", -1);
        if (parts.length < 2 || parts.length > 4) throw new IllegalArgumentException(bad);
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
        int[] colours = null;
        int mirror = Screen.MIRROR_NONE;
        boolean haveColours = false, haveMirror = false;
        for (int i = 2; i < parts.length; i++) {
            if (parts[i].startsWith("colours=") && !haveColours) {
                colours = colours(parts[i], bad);
                haveColours = true;
            } else if (parts[i].startsWith("mirror=") && !haveMirror) {
                mirror = mirror(parts[i].substring("mirror=".length()), bad);
                haveMirror = true;
            } else {
                throw new IllegalArgumentException(bad);
            }
        }
        SpriteSheet sheet = sheet();
        int tilesX = sheet.width() / Screen.TILE_SIZE, tilesY = sheet.height() / Screen.TILE_SIZE;
        if (tile[0] < 0 || tile[1] < 0 || tile[0] >= tilesX || tile[1] >= tilesY) {
            throw new IllegalArgumentException("bad --scene '" + spec + "': tile outside the "
                    + tilesX + "x" + tilesY + " grid");
        }
        return new Scene(sheet, fill, tile[0], tile[1], pos[0], pos[1], colours, mirror, null, null);
    }

    /** {@code text:X,Y[:colours=...]:TEXT}. TEXT is everything after the delimiter, so it may contain ':'. */
    private static Scene parseText(String spec) {
        String bad = "bad --scene '" + spec + "' (expected text:X,Y[:colours=C0,C1,C2,C3]:TEXT; "
                + "Ci = three digits 0..5 or t)";
        String rest = spec.substring(TEXT_PREFIX.length());
        int colon = rest.indexOf(':');
        if (colon < 0) throw new IllegalArgumentException(bad); // no text field
        int[] pos = ints(rest.substring(0, colon), bad);
        String after = rest.substring(colon + 1);
        int[] colours = TEXT_COLOURS;
        if (after.startsWith("colours=")) {
            int end = after.indexOf(':');
            if (end < 0) throw new IllegalArgumentException(bad); // colours but no text field
            colours = colours(after.substring(0, end), bad);
            after = after.substring(end + 1);
        }
        return new Scene(sheet(), false, 0, 0, pos[0], pos[1], colours, Screen.MIRROR_NONE, after, null);
    }

    /** {@code level:gen:W,H,SEED@XOFF,YOFF}. */
    private static Scene parseLevel(String spec) {
        String bad = "bad --scene '" + spec + "' (expected level:gen:W,H,SEED@XOFF,YOFF; W,H >= 1 tiles, "
                + "SEED a long, XOFF,YOFF integers)";
        String gen = "level:gen:";
        if (!spec.startsWith(gen)) throw new IllegalArgumentException(bad);
        String[] halves = spec.substring(gen.length()).split("@", -1);
        if (halves.length != 2) throw new IllegalArgumentException(bad);
        String[] g = halves[0].split(",", -1);
        if (g.length != 3) throw new IllegalArgumentException(bad);
        int w, h;
        long seed;
        try {
            w = Integer.parseInt(g[0].trim());
            h = Integer.parseInt(g[1].trim());
            seed = Long.parseLong(g[2].trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(bad);
        }
        int[] off = ints(halves[1], bad);
        Level level;
        try {
            level = Level.generate(w, h, seed);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("bad --scene '" + spec + "': " + e.getMessage());
        }
        return new Scene(sheet(), false, 0, 0, off[0], off[1], null, Screen.MIRROR_NONE, null, level);
    }

    /** {@code game:gen:W,H,SEED@SPAWNX,SPAWNY} or {@code game:grass:W,H@SPAWNX,SPAWNY}. */
    private static Scene parseGame(String spec) {
        String bad = "bad --scene '" + spec + "' (expected game:gen:W,H,SEED@SPAWNX,SPAWNY or "
                + "game:grass:W,H@SPAWNX,SPAWNY; W,H >= 1 tiles, SEED a long, SPAWNX,SPAWNY integers)";
        String rest = spec.substring(GAME_PREFIX.length());
        int colon = rest.indexOf(':');
        if (colon < 0) throw new IllegalArgumentException(bad);
        boolean grass;
        switch (rest.substring(0, colon)) {
            case "gen" -> grass = false;
            case "grass" -> grass = true;
            default -> throw new IllegalArgumentException(bad);
        }
        String[] halves = rest.substring(colon + 1).split("@", -1);
        if (halves.length != 2) throw new IllegalArgumentException(bad);
        String[] g = halves[0].split(",", -1);
        if (g.length != (grass ? 2 : 3)) throw new IllegalArgumentException(bad);
        int w, h;
        long seed = 0;
        try {
            w = Integer.parseInt(g[0].trim());
            h = Integer.parseInt(g[1].trim());
            if (!grass) seed = Long.parseLong(g[2].trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(bad);
        }
        int[] spawn = ints(halves[1], bad);
        if (w < 1 || h < 1 || (long) w * h > Level.MAX_CELLS) {
            throw new IllegalArgumentException("bad --scene '" + spec + "': level size must be at least 1x1 and at most "
                    + Level.MAX_CELLS + " cells");
        }
        return new Scene(sheet(), false, 0, 0, 0, 0, null, Screen.MIRROR_NONE, null, null,
                new GameSpec(grass, w, h, seed, spawn[0], spawn[1]));
    }

    /** A fresh {@link World} (new level, new player at the spawn) for a {@code game:} scene; null for any other scene. */
    public World newWorld() {
        if (game == null) return null;
        Level l;
        if (game.grass()) {
            l = new Level(game.width(), game.height());
            for (int ty = 0; ty < game.height(); ty++) {
                for (int tx = 0; tx < game.width(); tx++) l.setTile(tx, ty, Tile.GRASS);
            }
        } else {
            l = Level.generate(game.width(), game.height(), game.seed());
        }
        return new World(l, new Player(game.spawnX(), game.spawnY()));
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

    private static int mirror(String word, String error) {
        return switch (word) {
            case "x" -> Screen.MIRROR_X;
            case "y" -> Screen.MIRROR_Y;
            case "xy" -> Screen.MIRROR_X | Screen.MIRROR_Y;
            default -> throw new IllegalArgumentException(error);
        };
    }

    private static boolean level(char c) {
        return c >= '0' && c <= '5';
    }

    public void render(Screen screen) {
        screen.clear(0x000000);
        if (game != null) {
            newWorld().render(screen, sheet); // a still of the spawn; Game renders its own live world instead
        } else if (level != null) {
            level.render(screen, sheet, x, y);
        } else if (text != null) {
            Font.render(screen, sheet, text, x, y, colours);
        } else if (fill) {
            for (int py = 0; py < screen.height; py += Screen.TILE_SIZE) {
                for (int px = 0; px < screen.width; px += Screen.TILE_SIZE) draw(screen, px, py);
            }
        } else {
            draw(screen, x, y);
        }
    }

    private void draw(Screen screen, int px, int py) {
        if (colours == null) screen.drawTile(sheet, tileX, tileY, px, py, INTERIM_GREY, mirror);
        else screen.drawPaletteTile(sheet, tileX, tileY, px, py, colours, mirror);
    }
}

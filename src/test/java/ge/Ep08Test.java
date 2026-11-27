package ge;

import static org.junit.jupiter.api.Assertions.*;

import ge.gfx.Palette;
import ge.gfx.SpriteSheet;
import ge.level.Level;
import ge.level.Tile;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/*
 * Episode 8 check: "Level/Tile Development".
 * SPEC: a tile registry (void, stone, grass, water) with IDs, solidity and level colour; a generated level
 * renders; the view scrolls with an offset. Asset map: void (0,0), stone (1,0) solid, grass (2,0), water first
 * frame (0,5); level colours black / 0x555555 / 0x00FF00 / 0x0000FF (used by episode 13's PNG loader).
 *
 * CONTRACT the builder must satisfy
 * ---------------------------------
 * 1. ge.level.Tile (final class, immutable). Exactly four instances, constants Tile.VOID, Tile.STONE, Tile.GRASS,
 *    Tile.WATER, with ids 0, 1, 2, 3 in that order.
 *      int id();  boolean isSolid();  int levelColour();     // 0xRRGGBB
 *      int sheetX(); int sheetY();                            // tile coordinates in the sprite sheet
 *      int[] colours();                                       // the 4 Palette colours (index 0..215 or Palette.TRANSPARENT)
 *                                                             // used to draw it; returns a COPY each call
 *      static Tile byId(int id);                              // 0..3 -> the tile
 *      static Tile byLevelColour(int rgb);                    // the four level colours -> the tile (other colours: not tested)
 *      static List<Tile> all();                               // the four tiles ordered by id
 *    Values: VOID id0 (0,0) colour 0x000000; STONE id1 (1,0) colour 0x555555 solid; GRASS id2 (2,0) colour 0x00FF00;
 *    WATER id3 (0,5) colour 0x0000FF. Only STONE is solid among stone/grass/water (void's solidity is not pinned).
 *    The 4 colour arrays are fixed art choices of the builder, but: every entry is TRANSPARENT or 0..215; the four
 *    tiles' arrays are pairwise different; GRASS has an opaque colour whose green level exceeds its red and blue
 *    levels; WATER has an opaque colour whose blue level exceeds its red and green levels.
 *
 * 2. ge.level.Level (final class): a width x height grid of Tile, row-major, cell (0,0) top-left, all VOID at creation.
 *      Level(int width, int height)            // IllegalArgumentException unless both >= 1
 *      int width(); int height();              // in tiles
 *      Tile getTile(int x, int y)              // out of bounds (any int, incl. negative / huge) -> Tile.VOID
 *      void setTile(int x, int y, Tile t)      // out of bounds: silently ignored; null t: IllegalArgumentException
 *      static Level generate(int width, int height, long seed)
 *                                              // same arguments -> identical level; IllegalArgumentException if a size < 1.
 *                                              // A 64x64 level contains at least one STONE, GRASS and WATER tile; a
 *                                              // different seed gives a different 64x64 level.
 *      void render(Screen screen, SpriteSheet sheet, int xOffset, int yOffset)
 *    render draws on top of whatever is in the screen (it does not clear it). Screen pixel (sx,sy) shows WORLD pixel
 *    (sx+xOffset, sy+yOffset); world pixel (wx,wy) belongs to cell (floorDiv(wx,8), floorDiv(wy,8)) at in-tile position
 *    (floorMod(wx,8), floorMod(wy,8)). Each visible cell, including cells outside the level (they are VOID), is drawn
 *    exactly as screen.drawPaletteTile(sheet, tile.sheetX(), tile.sheetY(), ..., tile.colours()) would draw it. So any
 *    pixel offset works (non-multiples of 8, negative, far beyond the edge); offsets near Integer.MIN/MAX must not throw
 *    or wrap (not pixel-checked beyond "no exception").
 *
 * 3. Headless scene (Scene.parse and --scene):   level:gen:W,H,SEED@XOFF,YOFF
 *    W,H >= 1 tiles, SEED a long, XOFF,YOFF ints (negative allowed). Each frame is cleared to black, then
 *    Level.generate(W,H,SEED).render(screen, sheet, XOFF, YOFF); every frame identical. Malformed or W/H < 1 ->
 *    IllegalArgumentException from Scene.parse / exit code 2 with a message on stderr from the jar.
 *    Existing tile:/fill:/text: scenes are unchanged. (A key-scrolled variant is not required this episode.)
 *
 * Expected pixels are computed here from res/sprite_sheet.png (index = blue >> 6), the SPEC coordinates, the
 * palette rule (index r*36+g*6+b, channel = level*51) and the contract above, never from engine output.
 */
public class Ep08Test {

    private static final Path JAR = Paths.get("build/game.jar");
    private static final int T = Palette.TRANSPARENT;
    private static final int W = Game.WIDTH, H = Game.HEIGHT;
    private static final int SENTINEL = 0x123456;

    private static BufferedImage sheetImage;
    private static SpriteSheet sheet;

    @BeforeAll
    static void loadSheet() throws Exception {
        try (InputStream in = Ep08Test.class.getResourceAsStream("/sprite_sheet.png")) {
            sheetImage = ImageIO.read(in);
        }
        sheet = SpriteSheet.load("/sprite_sheet.png");
    }

    // ---- independent model ------------------------------------------------------------------------

    private static int ruleRgb(int index) {
        int r = index / 36, g = (index / 6) % 6, b = index % 6;
        return (r * 51) << 16 | (g * 51) << 8 | (b * 51);
    }

    /** Per-pixel model of Level.render over base, using only the tile data (SPEC coords come from the tile accessors). */
    private static int[] model(Level level, int[] base, int xo, int yo) {
        int[] out = base.clone();
        for (int sy = 0; sy < H; sy++) {
            for (int sx = 0; sx < W; sx++) {
                long wx = (long) sx + xo, wy = (long) sy + yo;
                Tile t = level.getTile((int) Math.floorDiv(wx, 8L), (int) Math.floorDiv(wy, 8L));
                int px = t.sheetX() * 8 + (int) Math.floorMod(wx, 8L), py = t.sheetY() * 8 + (int) Math.floorMod(wy, 8L);
                int c = t.colours()[(sheetImage.getRGB(px, py) & 0xff) >> 6];
                if (c != T) out[sy * W + sx] = ruleRgb(c);
            }
        }
        return out;
    }

    private static int[] sentinel() {
        int[] b = new int[W * H];
        java.util.Arrays.fill(b, SENTINEL);
        return b;
    }

    private static int[] render(Level level, int xo, int yo) {
        Screen s = new Screen(W, H);
        s.clear(SENTINEL);
        level.render(s, sheet, xo, yo);
        return s.pixels.clone();
    }

    private static Level handBuilt() {
        Level l = new Level(5, 4);
        Tile[][] g = {
            {Tile.STONE, Tile.STONE, Tile.GRASS, Tile.WATER, Tile.VOID},
            {Tile.STONE, Tile.GRASS, Tile.GRASS, Tile.WATER, Tile.WATER},
            {Tile.GRASS, Tile.GRASS, Tile.WATER, Tile.STONE, Tile.STONE},
            {Tile.WATER, Tile.WATER, Tile.WATER, Tile.GRASS, Tile.STONE}};
        for (int y = 0; y < 4; y++) for (int x = 0; x < 5; x++) l.setTile(x, y, g[y][x]);
        return l;
    }

    // ---- registry ---------------------------------------------------------------------------------

    @Test
    void registryHasTheFourTilesWithSpecValues() {
        Tile[] ts = {Tile.VOID, Tile.STONE, Tile.GRASS, Tile.WATER};
        int[][] sheetXY = {{0, 0}, {1, 0}, {2, 0}, {0, 5}};
        int[] lc = {0x000000, 0x555555, 0x00FF00, 0x0000FF};
        for (int i = 0; i < 4; i++) {
            assertEquals(i, ts[i].id());
            assertEquals(sheetXY[i][0], ts[i].sheetX(), "sheetX " + i);
            assertEquals(sheetXY[i][1], ts[i].sheetY(), "sheetY " + i);
            assertEquals(lc[i], ts[i].levelColour() & 0xFFFFFF, "level colour " + i);
            assertSame(ts[i], Tile.byId(i));
            assertSame(ts[i], Tile.byLevelColour(lc[i]));
            assertSame(ts[i], Tile.all().get(i));
        }
        assertEquals(4, Tile.all().size());
        assertTrue(Tile.STONE.isSolid());
        assertFalse(Tile.GRASS.isSolid());
        assertFalse(Tile.WATER.isSolid());
    }

    @Test
    void tileColoursAreValidDistinctAndSensible() {
        List<Tile> all = Tile.all();
        for (Tile t : all) {
            int[] c = t.colours();
            assertEquals(4, c.length);
            for (int v : c) assertTrue(v == T || (v >= 0 && v < 216), "bad palette entry " + v);
            c[0] = 12345; // returned array must be a copy
            assertNotEquals(12345, t.colours()[0], "colours() must return a copy");
        }
        for (int i = 0; i < 4; i++)
            for (int j = i + 1; j < 4; j++)
                assertFalse(java.util.Arrays.equals(all.get(i).colours(), all.get(j).colours()), "tiles " + i + "," + j + " look identical");
        boolean green = false, blue = false;
        for (int v : Tile.GRASS.colours()) if (v != T && (v / 6) % 6 > v / 36 && (v / 6) % 6 > v % 6) green = true;
        for (int v : Tile.WATER.colours()) if (v != T && v % 6 > v / 36 && v % 6 > (v / 6) % 6) blue = true;
        assertTrue(green, "grass needs a green-dominant colour");
        assertTrue(blue, "water needs a blue-dominant colour");
    }

    // ---- level container --------------------------------------------------------------------------

    @Test
    void levelStoresTilesAndTreatsOutsideAsVoid() {
        Level l = new Level(7, 3);
        assertEquals(7, l.width());
        assertEquals(3, l.height());
        for (int y = 0; y < 3; y++) for (int x = 0; x < 7; x++) assertSame(Tile.VOID, l.getTile(x, y));
        l.setTile(6, 2, Tile.WATER);
        l.setTile(0, 0, Tile.STONE);
        l.setTile(3, 1, Tile.GRASS);
        assertSame(Tile.WATER, l.getTile(6, 2));
        assertSame(Tile.STONE, l.getTile(0, 0));
        assertSame(Tile.GRASS, l.getTile(3, 1));
        assertSame(Tile.VOID, l.getTile(1, 0), "neighbours untouched");
        int[][] outside = {{-1, 0}, {0, -1}, {7, 0}, {0, 3}, {7, 3}, {-1000, 1}, {1, 1000}, {Integer.MIN_VALUE, 0}, {0, Integer.MAX_VALUE}};
        for (int[] o : outside) {
            l.setTile(o[0], o[1], Tile.STONE); // ignored, no exception
            assertSame(Tile.VOID, l.getTile(o[0], o[1]), "outside " + o[0] + "," + o[1]);
        }
        assertSame(Tile.WATER, l.getTile(6, 2), "out-of-bounds sets must not wrap onto real cells");
        assertSame(Tile.GRASS, l.getTile(3, 1));
        assertThrows(IllegalArgumentException.class, () -> l.setTile(0, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new Level(0, 5));
        assertThrows(IllegalArgumentException.class, () -> new Level(5, 0));
        assertThrows(IllegalArgumentException.class, () -> new Level(-1, 5));
    }

    @Test
    void generatorIsDeterministicAndVaried() {
        Level a = Level.generate(64, 64, 12345L), b = Level.generate(64, 64, 12345L);
        assertEquals(64, a.width());
        assertEquals(64, a.height());
        boolean[] seen = new boolean[4];
        for (int y = 0; y < 64; y++)
            for (int x = 0; x < 64; x++) {
                assertSame(a.getTile(x, y), b.getTile(x, y), "same seed must give the same level");
                seen[a.getTile(x, y).id()] = true;
            }
        assertTrue(seen[1] && seen[2] && seen[3], "a 64x64 level needs stone, grass and water");
        for (long other : new long[] {12346L, 0L, -7L}) {
            Level c = Level.generate(64, 64, other);
            int diff = 0;
            for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) if (c.getTile(x, y) != a.getTile(x, y)) diff++;
            assertTrue(diff > 0, "seed " + other + " must differ from 12345");
        }
        Level odd = Level.generate(13, 5, 9L);
        assertEquals(13, odd.width());
        assertEquals(5, odd.height());
        assertThrows(IllegalArgumentException.class, () -> Level.generate(0, 4, 1L));
        assertThrows(IllegalArgumentException.class, () -> Level.generate(4, 0, 1L));
    }

    // ---- rendering --------------------------------------------------------------------------------

    @Test
    void handBuiltLevelAtOriginShowsTheRightTiles() {
        Level l = handBuilt();
        int[] got = render(l, 0, 0);
        assertArrayEquals(model(l, sentinel(), 0, 0), got);
        // independent spot check of the first stone tile: its pixels are the sheet's tile (1,0) in Tile.STONE.colours()
        int[] sc = Tile.STONE.colours();
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x++) {
                int c = sc[(sheetImage.getRGB(8 + x, y) & 0xff) >> 6];
                assertEquals(c == T ? SENTINEL : ruleRgb(c), got[y * W + x], "stone pixel " + x + "," + y);
            }
        // the level is 40x32 px; beyond it only void is drawn, identical to the void tile's own look
        int[] vc = Tile.VOID.colours();
        for (int y = 40; y < 48; y++)
            for (int x = 100; x < 108; x++) {
                int c = vc[(sheetImage.getRGB(x % 8, y % 8) & 0xff) >> 6];
                assertEquals(c == T ? SENTINEL : ruleRgb(c), got[y * W + x], "void beyond the edge " + x + "," + y);
            }
    }

    @Test
    void anyPixelOffsetMatchesTheModel() {
        Level hand = handBuilt();
        Level gen = Level.generate(40, 30, 777L);
        int[][] offs = {{0, 0}, {1, 0}, {0, 1}, {3, 5}, {7, 7}, {8, 8}, {13, 9}, {-1, -1}, {-5, -11}, {-8, 0}, {-30, 20}, {25, -17},
            {100, 60}, {161, 121}, {300, 200}, {-300, -200}, {-160, 50}, {5000, -5000}, {1000000, 1000000}, {-1000003, 7}};
        for (Level l : new Level[] {hand, gen})
            for (int[] o : offs) {
                assertArrayEquals(model(l, sentinel(), o[0], o[1]), render(l, o[0], o[1]), "offset " + o[0] + "," + o[1]);
            }
        // does not clear: pre-existing pixels survive wherever the tiles are transparent, and the model agrees (above).
        // Extreme offsets must not throw.
        for (int[] o : new int[][] {{Integer.MAX_VALUE, 0}, {0, Integer.MAX_VALUE}, {Integer.MIN_VALUE, Integer.MIN_VALUE}, {Integer.MAX_VALUE - 3, Integer.MIN_VALUE + 3}})
            assertDoesNotThrow(() -> render(gen, o[0], o[1]));
    }

    @Test
    void farOutsideTheLevelShowsOnlyVoid() {
        Level l = Level.generate(10, 10, 5L);
        Level empty = new Level(1, 1);
        assertArrayEquals(render(empty, 50, 50), render(l, 5000, -3000));
        assertArrayEquals(render(empty, 50, 50), render(l, -5000, 90));
        assertArrayEquals(render(new Level(3, 3), 0, 0), render(l, 80, 80), "world (80,80) is the first cell outside a 10x10 level");
    }

    @Test
    void scrollingByOnePixelShiftsTheImageByOnePixel() {
        Level l = Level.generate(40, 30, 31337L);
        for (int[] o : new int[][] {{0, 0}, {37, 11}, {-20, -9}, {7, 100}}) {
            int[] a = render(l, o[0], o[1]);
            int[] right = render(l, o[0] + 1, o[1]);
            int[] down = render(l, o[0], o[1] + 1);
            for (int y = 0; y < H; y++)
                for (int x = 0; x < W - 1; x++) assertEquals(a[y * W + x + 1], right[y * W + x], "x+1 at " + x + "," + y);
            for (int y = 0; y < H - 1; y++)
                for (int x = 0; x < W; x++) assertEquals(a[(y + 1) * W + x], down[y * W + x], "y+1 at " + x + "," + y);
        }
        // eight pixels = one whole tile column: a level made of one repeating tile row looks the same shifted by 8
        Level stripes = new Level(40, 1);
        for (int x = 0; x < 40; x++) stripes.setTile(x, 0, Tile.STONE);
        assertArrayEquals(render(stripes, 8, 0), render(stripes, 16, 0));
    }

    // ---- headless scene and jar dump --------------------------------------------------------------

    @Test
    void sceneParsesAndRendersInProcess() {
        String spec = "level:gen:30,20,42@5,-3";
        Screen s = new Screen(W, H);
        s.clear(0xABCDEF);
        Scene.parse(spec).render(s);
        assertArrayEquals(model(Level.generate(30, 20, 42L), blackBase(), 5, -3), s.pixels);
        for (String bad : new String[] {"level:gen:0,5,1@0,0", "level:gen:5,0,1@0,0", "level:gen:5,5,1", "level:gen:5,5@0,0",
                "level:gen:a,5,1@0,0", "level:gen:5,5,1@0", "level:gen:5,5,1@0,0,0", "level:gen:5,5,x@0,0", "level:gen:", "level:"})
            assertThrows(IllegalArgumentException.class, () -> Scene.parse(bad), bad);
    }

    private static int[] blackBase() {
        return new int[W * H];
    }

    @Test
    void jarDumpsGeneratedLevelWithOffset(@TempDir Path dir) throws Exception {
        Path d1 = dir.resolve("a");
        Run r = runJar("--headless", "--ticks", "2", "--dump-dir", d1.toString(), "--scene", "level:gen:30,20,42@5,-3");
        assertEquals(0, r.exit, r.out);
        int[] f1 = readFrame(d1.resolve("frame-00000.png")), f2 = readFrame(d1.resolve("frame-00001.png"));
        assertArrayEquals(model(Level.generate(30, 20, 42L), blackBase(), 5, -3), f1);
        assertArrayEquals(f1, f2, "every frame identical");
        // a different view of the same world differs, and an offset past the edge is all void
        Path d2 = dir.resolve("b");
        r = runJar("--headless", "--ticks", "1", "--dump-dir", d2.toString(), "--scene", "level:gen:30,20,42@-77,1000");
        assertEquals(0, r.exit, r.out);
        int[] g = readFrame(d2.resolve("frame-00000.png"));
        assertArrayEquals(model(Level.generate(30, 20, 42L), blackBase(), -77, 1000), g);
        assertFalse(java.util.Arrays.equals(f1, g));
    }

    @Test
    void jarRejectsBadLevelScene(@TempDir Path dir) throws Exception {
        Run r = runJar("--headless", "--ticks", "1", "--dump-dir", dir.toString(), "--scene", "level:gen:0,5,1@0,0");
        assertEquals(2, r.exit, r.out);
        assertFalse(r.out.isBlank(), "message expected");
    }

    private record Run(int exit, String out) {}

    private static Run runJar(String... args) throws Exception {
        assertTrue(Files.exists(JAR), "build/game.jar must exist");
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        List<String> cmd = new ArrayList<>(List.of(javaBin, "-Djava.awt.headless=true", "-jar", JAR.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail("headless run timed out");
        }
        return new Run(p.exitValue(), new String(out, StandardCharsets.UTF_8));
    }

    private static int[] readFrame(Path f) throws Exception {
        assertTrue(Files.exists(f), f + " missing");
        BufferedImage img = ImageIO.read(f.toFile());
        assertEquals(W, img.getWidth());
        assertEquals(H, img.getHeight());
        int[] px = new int[W * H];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) px[y * W + x] = img.getRGB(x, y) & 0xFFFFFF;
        return px;
    }
}

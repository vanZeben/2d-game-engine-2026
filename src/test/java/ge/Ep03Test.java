package ge;

import static org.junit.jupiter.api.Assertions.*;

import ge.gfx.SpriteSheet;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/*
 * Episode 3 check: "Displaying the Sprite Sheet".
 * SPEC: a tile can be drawn to the screen buffer at any pixel position; a frame dump matches the
 * expected tile.
 *
 * CONTRACT the builder must satisfy
 * ---------------------------------
 * 1. In class ge.Screen (existing), add:
 *      public void drawTile(SpriteSheet sheet, int tileX, int tileY, int x, int y, int[] colours)
 *    - sheet:   ge.gfx.SpriteSheet from episode 2.
 *    - tileX, tileY: tile coordinates in the 32x32 grid; the tile is the 8x8 block of sheet pixels
 *      starting at sheet pixel (tileX*8, tileY*8). Out-of-grid coordinates (<0 or >=32) throw
 *      IllegalArgumentException.
 *    - x, y:    screen pixel position of the tile's top-left corner. Any int, including negative
 *      and beyond the screen: pixels that fall outside the buffer are clipped (skipped); no
 *      exception is thrown, even when the tile is entirely off-screen.
 *    - colours: exactly 4 entries of 0xRRGGBB; a sheet pixel with colour index i is written to the
 *      screen as colours[i] (all 4 indexes are opaque in this episode; transparency is episode 5).
 *      Any other length throws IllegalArgumentException.
 *    - Only the (clipped) 8x8 area is written; every other screen pixel is left untouched.
 *    - The tile is drawn unmirrored, unscaled, 1 sheet pixel -> 1 screen pixel.
 *
 * 2. INTERIM COLOURS (palette is episode 5, not this episode). Until then the engine's stand-in
 *    mapping is grey:  index i -> 0x555555 * i, i.e. 0x000000, 0x555555, 0xAAAAAA, 0xFFFFFF.
 *    The headless scene below uses exactly this mapping. Episode 5 will replace the scene's
 *    colours (and its test will say how); the drawTile(sheet, tx, ty, x, y, int[4]) call above is
 *    the stable seam and need not change.
 *
 * 3. HEADLESS SCENE. Main accepts a new flag, usable with the existing launch contract:
 *      java -jar build/game.jar --headless --ticks N --dump-dir DIR --scene tile:TX,TY@PX,PY
 *    TX,TY,PX,PY are decimal ints (PX,PY may be negative, e.g. tile:1,0@-3,-5). Every rendered
 *    frame is: the whole 160x120 screen cleared to 0x000000, then that tile drawn from
 *    /sprite_sheet.png at (PX,PY) with the interim grey colours. The default (no --scene) frame is
 *    deliberately unspecified here. A malformed --scene value prints a message to stderr and exits 2
 *    (same as other bad arguments). The usual "ticks=N frames=M" line and exit code 0 stay.
 *
 * Expected pixels are computed here from res/sprite_sheet.png with ImageIO and the rule
 * index = blue >> 6 (episode 2), never from engine output.
 */
public class Ep03Test {

    private static final Path JAR = Paths.get("build/game.jar");
    private static final int[] GREY = {0x000000, 0x555555, 0xAAAAAA, 0xFFFFFF};
    private static final int[] BRIGHT = {0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00};
    private static final int SENTINEL = 0x123456;

    private static BufferedImage sheetImage;
    private static SpriteSheet sheet;

    @BeforeAll
    static void loadSheet() throws Exception {
        try (InputStream in = Ep03Test.class.getResourceAsStream("/sprite_sheet.png")) {
            sheetImage = ImageIO.read(in);
        }
        sheet = SpriteSheet.load("/sprite_sheet.png");
    }

    /** Independent expectation of the colour index at sheet pixel (sx, sy). */
    private static int idx(int sx, int sy) {
        return (sheetImage.getRGB(sx, sy) & 0xff) >> 6;
    }

    /** Expected buffer: base copy with the tile written at (px,py), clipped. */
    private static int[] expected(int[] base, int w, int h, int tx, int ty, int px, int py, int[] colours) {
        int[] out = base.clone();
        for (int dy = 0; dy < 8; dy++) {
            for (int dx = 0; dx < 8; dx++) {
                int sx = px + dx, sy = py + dy;
                if (sx < 0 || sy < 0 || sx >= w || sy >= h) continue;
                out[sy * w + sx] = colours[idx(tx * 8 + dx, ty * 8 + dy)];
            }
        }
        return out;
    }

    private static int distinct(int tx, int ty) {
        boolean[] seen = new boolean[4];
        for (int dy = 0; dy < 8; dy++) for (int dx = 0; dx < 8; dx++) seen[idx(tx * 8 + dx, ty * 8 + dy)] = true;
        int n = 0;
        for (boolean b : seen) if (b) n++;
        return n;
    }

    /** First tile (row-major over the grid) using at least 3 distinct colour indexes, so mistakes show. */
    private static int[] richTile() {
        for (int ty = 0; ty < 32; ty++)
            for (int tx = 0; tx < 32; tx++) if (distinct(tx, ty) >= 3) return new int[] {tx, ty};
        throw new AssertionError("no tile with 3+ colour indexes in the sprite sheet");
    }

    private static Screen sentinelScreen() {
        Screen s = new Screen(Game.WIDTH, Game.HEIGHT);
        s.clear(SENTINEL);
        return s;
    }

    private void checkDraw(int tx, int ty, int px, int py, int[] colours) {
        Screen s = sentinelScreen();
        int[] base = s.pixels.clone();
        s.drawTile(sheet, tx, ty, px, py, colours);
        int[] exp = expected(base, s.width, s.height, tx, ty, px, py, colours);
        assertArrayEquals(exp, s.pixels, "tile " + tx + "," + ty + " at " + px + "," + py);
    }

    // ---- unit checks on Screen.drawTile -------------------------------------------------------

    @Test
    void chosenTilesAreNotTrivial() {
        int[] t = richTile();
        assertTrue(distinct(t[0], t[1]) >= 3);
        assertTrue(distinct(1, 0) >= 1);
    }

    @Test
    void drawsTileAtOrigin() {
        int[] t = richTile();
        checkDraw(t[0], t[1], 0, 0, GREY);
        checkDraw(1, 0, 0, 0, GREY); // stone
        checkDraw(2, 0, 0, 0, GREY); // grass
    }

    @Test
    void writesColoursByIndex() {
        int[] t = richTile();
        checkDraw(t[0], t[1], 0, 0, BRIGHT);
        // spot check against the rule directly, not only the helper
        Screen s = sentinelScreen();
        s.drawTile(sheet, t[0], t[1], 20, 30, BRIGHT);
        for (int dy = 0; dy < 8; dy++)
            for (int dx = 0; dx < 8; dx++)
                assertEquals(BRIGHT[(sheetImage.getRGB(t[0] * 8 + dx, t[1] * 8 + dy) & 0xff) >> 6],
                        s.pixels[(30 + dy) * s.width + 20 + dx]);
    }

    @Test
    void drawsAtArbitraryNonMultipleOfEightPositions() {
        int[] t = richTile();
        int[][] spots = {{1, 1}, {3, 5}, {13, 7}, {37, 59}, {99, 83}, {151, 111}, {152, 112}, {61, 17}};
        for (int[] p : spots) checkDraw(t[0], t[1], p[0], p[1], GREY);
        checkDraw(2, 0, 77, 41, BRIGHT);
    }

    @Test
    void tileCoordinatesSelectTheRightBlock() {
        // different tiles at the same spot must give the sheet's different pixels
        checkDraw(1, 0, 40, 40, GREY);
        checkDraw(2, 0, 40, 40, GREY);
        checkDraw(0, 5, 40, 40, GREY);   // water frame
        checkDraw(0, 30, 40, 40, GREY);  // font row
        checkDraw(31, 31, 40, 40, GREY); // last tile of the grid
    }

    @Test
    void clipsOnEveryEdgeAndCorner() {
        int[] t = richTile();
        int w = Game.WIDTH, h = Game.HEIGHT;
        int[][] spots = {
            {-3, 50}, {-7, 50}, {-1, 50},        // left
            {w - 5, 50}, {w - 1, 50},            // right
            {60, -3}, {60, -7}, {60, -1},        // top
            {60, h - 5}, {60, h - 1},            // bottom
            {-4, -4}, {w - 3, -6}, {-5, h - 2}, {w - 6, h - 7}, // corners
        };
        for (int[] p : spots) assertDoesNotThrow(() -> checkDraw(t[0], t[1], p[0], p[1], GREY), p[0] + "," + p[1]);
    }

    @Test
    void entirelyOffScreenChangesNothingAndDoesNotThrow() {
        int[] t = richTile();
        int w = Game.WIDTH, h = Game.HEIGHT;
        int[][] spots = {{-8, 10}, {w, 10}, {10, -8}, {10, h}, {-100, -100}, {1000, 1000}, {w + 50, -50},
            {Integer.MIN_VALUE / 2, 0}, {0, Integer.MAX_VALUE / 2}};
        for (int[] p : spots) {
            Screen s = sentinelScreen();
            assertDoesNotThrow(() -> s.drawTile(sheet, t[0], t[1], p[0], p[1], GREY));
            int[] all = new int[s.pixels.length];
            Arrays.fill(all, SENTINEL);
            assertArrayEquals(all, s.pixels, "off-screen draw at " + p[0] + "," + p[1] + " must touch nothing");
        }
    }

    @Test
    void pixelsOutsideTileAreUntouchedEvenWhenBufferHasNoise() {
        int[] t = richTile();
        Screen s = new Screen(Game.WIDTH, Game.HEIGHT);
        for (int i = 0; i < s.pixels.length; i++) s.pixels[i] = (i * 2654435761L) % 0xFFFFFF > 0 ? (int) ((i * 2654435761L) % 0xFFFFFF) : 1;
        int[] base = s.pixels.clone();
        s.drawTile(sheet, t[0], t[1], 33, 21, GREY);
        assertArrayEquals(expected(base, s.width, s.height, t[0], t[1], 33, 21, GREY), s.pixels);
        int changedOutside = 0;
        for (int y = 0; y < s.height; y++)
            for (int x = 0; x < s.width; x++) {
                boolean inside = x >= 33 && x < 41 && y >= 21 && y < 29;
                if (!inside && s.pixels[y * s.width + x] != base[y * s.width + x]) changedOutside++;
            }
        assertEquals(0, changedOutside);
    }

    @Test
    void secondDrawOverwritesAndTwoTilesCoexist() {
        Screen s = sentinelScreen();
        int[] base = s.pixels.clone();
        s.drawTile(sheet, 1, 0, 10, 10, GREY);
        s.drawTile(sheet, 2, 0, 14, 12, GREY); // overlaps the first
        int[] exp = expected(base, s.width, s.height, 1, 0, 10, 10, GREY);
        exp = expected(exp, s.width, s.height, 2, 0, 14, 12, GREY);
        assertArrayEquals(exp, s.pixels);
    }

    @Test
    void rejectsBadArguments() {
        Screen s = sentinelScreen();
        assertThrows(IllegalArgumentException.class, () -> s.drawTile(sheet, 32, 0, 0, 0, GREY));
        assertThrows(IllegalArgumentException.class, () -> s.drawTile(sheet, 0, 32, 0, 0, GREY));
        assertThrows(IllegalArgumentException.class, () -> s.drawTile(sheet, -1, 0, 0, 0, GREY));
        assertThrows(IllegalArgumentException.class, () -> s.drawTile(sheet, 0, -1, 0, 0, GREY));
        assertThrows(IllegalArgumentException.class, () -> s.drawTile(sheet, 0, 0, 0, 0, new int[3]));
        assertThrows(IllegalArgumentException.class, () -> s.drawTile(sheet, 0, 0, 0, 0, new int[5]));
    }

    // ---- frame dump from the jar --------------------------------------------------------------

    private record Run(int exit, String out) {}

    private static Run runJar(String... args) throws Exception {
        assertTrue(Files.exists(JAR), "build/game.jar must exist");
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        var cmd = new java.util.ArrayList<String>(java.util.List.of(javaBin, "-Djava.awt.headless=true", "-jar", JAR.toString()));
        cmd.addAll(java.util.List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail("headless run timed out");
        }
        return new Run(p.exitValue(), new String(out, StandardCharsets.UTF_8));
    }

    private static void checkDump(Path dir, String lastFrame, int tx, int ty, int px, int py) throws Exception {
        int[] base = new int[Game.WIDTH * Game.HEIGHT]; // all 0x000000
        int[] exp = expected(base, Game.WIDTH, Game.HEIGHT, tx, ty, px, py, GREY);
        for (String name : new String[] {"frame-00000.png", lastFrame}) {
            Path f = dir.resolve(name);
            assertTrue(Files.exists(f), name + " missing");
            BufferedImage img = ImageIO.read(f.toFile());
            assertNotNull(img);
            assertEquals(Game.WIDTH, img.getWidth());
            assertEquals(Game.HEIGHT, img.getHeight());
            int[] got = img.getRGB(0, 0, Game.WIDTH, Game.HEIGHT, null, 0, Game.WIDTH);
            for (int i = 0; i < got.length; i++) got[i] &= 0xFFFFFF;
            assertArrayEquals(exp, got, name + " must be black with tile " + tx + "," + ty + " at " + px + "," + py);
        }
    }

    @Test
    void dumpShowsTileAtRequestedPosition(@TempDir Path tmp) throws Exception {
        int[] t = richTile();
        Path dir = tmp.resolve("a");
        Run r = runJar("--headless", "--ticks", "3", "--dump-dir", dir.toString(),
                "--scene", "tile:" + t[0] + "," + t[1] + "@37,59");
        assertEquals(0, r.exit, r.out);
        assertTrue(r.out.contains("ticks=3 frames=3"), r.out);
        checkDump(dir, "frame-00002.png", t[0], t[1], 37, 59);
        // the dump must actually contain something visible
        BufferedImage img = ImageIO.read(dir.resolve("frame-00000.png").toFile());
        int nonBlack = 0;
        for (int y = 0; y < img.getHeight(); y++)
            for (int x = 0; x < img.getWidth(); x++) if ((img.getRGB(x, y) & 0xFFFFFF) != 0) nonBlack++;
        assertTrue(nonBlack > 0, "scene dump is all black");
        assertTrue(nonBlack <= 64);
    }

    @Test
    void dumpAtOriginMatchesSpecificTiles(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("b");
        Run r = runJar("--headless", "--ticks", "2", "--dump-dir", dir.toString(), "--scene", "tile:2,0@0,0");
        assertEquals(0, r.exit, r.out);
        checkDump(dir, "frame-00001.png", 2, 0, 0, 0);
    }

    @Test
    void dumpClipsPartiallyOffScreenTile(@TempDir Path tmp) throws Exception {
        int[] t = richTile();
        Path dir = tmp.resolve("c");
        Run r = runJar("--headless", "--ticks", "2", "--dump-dir", dir.toString(),
                "--scene", "tile:" + t[0] + "," + t[1] + "@-3,-5");
        assertEquals(0, r.exit, r.out);
        checkDump(dir, "frame-00001.png", t[0], t[1], -3, -5);

        Path dir2 = tmp.resolve("d");
        r = runJar("--headless", "--ticks", "1", "--dump-dir", dir2.toString(),
                "--scene", "tile:" + t[0] + "," + t[1] + "@156,116");
        assertEquals(0, r.exit, r.out);
        checkDump(dir2, "frame-00000.png", t[0], t[1], 156, 116);
    }

    @Test
    void malformedSceneExitsTwo() throws Exception {
        for (String bad : new String[] {"tile:1,0", "tile:a,b@1,2", "blob:1,0@0,0", "tile:1,0@5", ""}) {
            Run r = runJar("--headless", "--ticks", "1", "--scene", bad);
            assertEquals(2, r.exit, "scene '" + bad + "' should be rejected; output:\n" + r.out);
        }
    }
}

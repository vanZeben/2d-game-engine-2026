package ge;

import static org.junit.jupiter.api.Assertions.*;

import ge.gfx.Palette;
import ge.gfx.SpriteSheet;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/*
 * Episode 5 check: "Colour & Rendering Optimisation".
 * SPEC: the 216-colour palette is built and tiles render with 4 colours each, including
 * transparent; a dump shows a recoloured tile. Rendering 600 frames headless stays under a set time.
 *
 * CONTRACT the builder must satisfy
 * ---------------------------------
 * 1. New class ge.gfx.Palette (final, static API only):
 *      public static final int SIZE = 216;          // 6 levels each of R, G, B
 *      public static final int TRANSPARENT = -1;    // a "colour" meaning "draw nothing"
 *      public static int rgb(int index)             // 0xRRGGBB of palette entry 0..215
 *      public static int index(int r, int g, int b) // levels 0..5 each -> palette index
 *    RULE: index = r*36 + g*6 + b (r,g,b are levels 0..5). Level -> channel value = level*51, i.e.
 *    0, 51, 102, 153, 204, 255. So rgb(index(r,g,b)) == (r*51)<<16 | (g*51)<<8 | b*51. All 216
 *    entries are therefore distinct, entry 0 is 0x000000 and entry 215 is 0xFFFFFF.
 *    rgb(i) throws IllegalArgumentException for i < 0 or i >= 216 (TRANSPARENT has no rgb).
 *    index(r,g,b) throws IllegalArgumentException if any level is < 0 or > 5.
 *
 * 2. A tile's 4 colours are an int[4] of PALETTE INDEXES (0..215) or Palette.TRANSPARENT (-1),
 *    one per sprite-sheet colour index 0..3 (slot i colours sheet pixels whose index is i).
 *    Any of the 4 slots may be TRANSPARENT.
 *    In ge.Screen (existing) add (the existing int[4]-of-0xRRGGBB drawTile stays as it is):
 *      public void drawPaletteTile(SpriteSheet sheet, int tileX, int tileY, int x, int y, int[] colours)
 *    - same geometry as drawTile: 8x8 block at sheet pixel (tileX*8, tileY*8), unmirrored, unscaled,
 *      (x,y) any int, clipped silently, entirely off-screen is a no-op.
 *    - a sheet pixel whose slot is a palette index is written to the screen as Palette.rgb(index);
 *      a sheet pixel whose slot is TRANSPARENT leaves the screen pixel exactly as it was.
 *    - IllegalArgumentException for: tile outside the 32x32 grid; colours null or not length 4;
 *      any entry < -1 or > 215.
 *
 * 3. HEADLESS SCENES. Main's --scene gains an optional colours suffix and a second scene kind:
 *      --scene tile:TX,TY@PX,PY[:colours=C0,C1,C2,C3]
 *      --scene fill:TX,TY[:colours=C0,C1,C2,C3]
 *    Each Ci is either exactly three digits "RGB", each 0..5 (the colour's levels, e.g. "500" =
 *    index(5,0,0) = 0xFF0000, "025" = 0x0033FF), or the single letter "t" for TRANSPARENT.
 *    Frames: cleared to 0x000000, then
 *      tile: the tile drawn once at (PX,PY) (as in episode 3);
 *      fill: the tile drawn at every (8*i, 8*j), i=0..19, j=0..14, covering the whole 160x120 screen.
 *    With colours= the tile is drawn with drawPaletteTile; WITHOUT colours= the scene behaves
 *    exactly as episode 3 specified (interim grey 0x000000,0x555555,0xAAAAAA,0xFFFFFF, via drawTile),
 *    for both kinds (fill without colours= therefore also uses the grey).
 *    Malformed values (wrong count of colours, a token that is not "t" or three digits 0..5, the
 *    key spelt other than "colours", empty list, extra ":" parts, fill with an "@") exit 2 with a
 *    message on stderr, like other bad arguments. The default (no --scene) frame stays unspecified.
 *    In-process the same specs go through Scene.parse(String) / Scene.render(Screen), which exist.
 *
 * PERFORMANCE
 * -----------
 * Test fillSceneRenders600FramesQuickly: Scene.parse("fill:...:colours=...") then 600 x
 * Scene.render(screen) on a 160x120 Screen (300 tiles / 19,200 pixels per frame, 11.5 M pixels in
 * total, no PNG encoding, no JVM start-up; 120 warm-up frames first) must finish under 1500 ms
 * (best of up to 3 attempts, so one slow scheduling hiccup on a busy shared box cannot fail it).
 * Justification (measured on this box with throwaway references): the straightforward fast path
 * (precomputed int[216] palette, int[] loops, one clip per tile) renders the 600 frames in ~30 ms; a
 * naive version that reads the PNG with BufferedImage.getRGB, allocates a java.awt.Color and calls
 * setRGB per pixel took ~670 ms. 1500 ms is ~50x the fast path (ample headroom for a loaded shared
 * 8-core box running concurrent builds, hence also the best-of-3) and ~2x the naive-but-working
 * version; it catches pathological code (re-reading/decoding the PNG per frame or tile, rebuilding
 * the palette per pixel, List<Integer>/boxed lookups, per-pixel exceptions).
 *
 * Expected pixels everywhere are computed here from res/sprite_sheet.png (index = blue >> 6, as
 * in episode 2) and from the palette rule above, never from engine output.
 */
public class Ep05Test {

    private static final Path JAR = Paths.get("build/game.jar");
    private static final int SENTINEL = 0x123456;
    private static final int T = Palette.TRANSPARENT;

    private static BufferedImage sheetImage;
    private static SpriteSheet sheet;

    @BeforeAll
    static void loadSheet() throws Exception {
        try (InputStream in = Ep05Test.class.getResourceAsStream("/sprite_sheet.png")) {
            sheetImage = ImageIO.read(in);
        }
        sheet = SpriteSheet.load("/sprite_sheet.png");
    }

    // ---- independent expectations ---------------------------------------------------------------

    private static int idx(int sx, int sy) {
        return (sheetImage.getRGB(sx, sy) & 0xff) >> 6;
    }

    /** Palette rule restated here: index r*36+g*6+b, channel = level*51. */
    private static int ruleRgb(int index) {
        int r = index / 36, g = (index / 6) % 6, b = index % 6;
        return (r * 51) << 16 | (g * 51) << 8 | (b * 51);
    }

    private static int[] expected(int[] base, int w, int h, int tx, int ty, int px, int py, int[] colours) {
        int[] out = base.clone();
        for (int dy = 0; dy < 8; dy++) {
            for (int dx = 0; dx < 8; dx++) {
                int sx = px + dx, sy = py + dy;
                if (sx < 0 || sy < 0 || sx >= w || sy >= h) continue;
                int c = colours[idx(tx * 8 + dx, ty * 8 + dy)];
                if (c == T) continue;
                out[sy * w + sx] = ruleRgb(c);
            }
        }
        return out;
    }

    private static boolean[] used(int tx, int ty) {
        boolean[] seen = new boolean[4];
        for (int dy = 0; dy < 8; dy++) for (int dx = 0; dx < 8; dx++) seen[idx(tx * 8 + dx, ty * 8 + dy)] = true;
        return seen;
    }

    private static int distinct(int tx, int ty) {
        int n = 0;
        for (boolean b : used(tx, ty)) if (b) n++;
        return n;
    }

    /** A tile containing sheet colour index {@code slot} and at least one other index, so a transparent slot is visible. */
    private static int[] tileUsing(int slot) {
        for (int ty = 0; ty < 32; ty++)
            for (int tx = 0; tx < 32; tx++) if (used(tx, ty)[slot] && distinct(tx, ty) >= 2) return new int[] {tx, ty};
        throw new AssertionError("no tile in the sheet mixes colour index " + slot + " with another");
    }

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

    private static Screen noisyScreen() {
        Screen s = new Screen(Game.WIDTH, Game.HEIGHT);
        for (int i = 0; i < s.pixels.length; i++) s.pixels[i] = (int) (((i + 1) * 2654435761L) & 0xFFFFFF);
        return s;
    }

    // ---- the palette -----------------------------------------------------------------------------

    @Test
    void paletteHasExactly216Entries() {
        assertEquals(216, Palette.SIZE);
        assertEquals(-1, Palette.TRANSPARENT);
        assertDoesNotThrow(() -> Palette.rgb(0));
        assertDoesNotThrow(() -> Palette.rgb(215));
        assertThrows(IllegalArgumentException.class, () -> Palette.rgb(216));
        assertThrows(IllegalArgumentException.class, () -> Palette.rgb(-1));
        assertThrows(IllegalArgumentException.class, () -> Palette.rgb(Palette.TRANSPARENT));
    }

    @Test
    void everyEntryFollowsTheSixLevelRule() {
        for (int i = 0; i < 216; i++) {
            assertEquals(ruleRgb(i), Palette.rgb(i), "entry " + i);
            assertEquals(0, Palette.rgb(i) & 0xFF000000, "entry " + i + " must be plain 0xRRGGBB");
        }
        assertEquals(0x000000, Palette.rgb(0));
        assertEquals(0x000033, Palette.rgb(1));
        assertEquals(0x003300, Palette.rgb(6));
        assertEquals(0x330000, Palette.rgb(36));
        assertEquals(0xFF0000, Palette.rgb(180));
        assertEquals(0x00FF00, Palette.rgb(30));
        assertEquals(0x0000FF, Palette.rgb(5));
        assertEquals(0xFFFFFF, Palette.rgb(215));
    }

    @Test
    void entriesAreUniqueAndUseOnlySixChannelLevels() {
        Set<Integer> seen = new HashSet<>();
        Set<Integer> levels = new HashSet<>();
        for (int i = 0; i < 216; i++) {
            assertTrue(seen.add(Palette.rgb(i)), "duplicate colour at entry " + i);
            levels.add((Palette.rgb(i) >> 16) & 0xFF);
            levels.add((Palette.rgb(i) >> 8) & 0xFF);
            levels.add(Palette.rgb(i) & 0xFF);
        }
        assertEquals(216, seen.size());
        assertEquals(Set.of(0, 51, 102, 153, 204, 255), levels);
    }

    @Test
    void indexLookupByLevelsIsTheInverse() {
        for (int r = 0; r < 6; r++)
            for (int g = 0; g < 6; g++)
                for (int b = 0; b < 6; b++) {
                    int i = Palette.index(r, g, b);
                    assertEquals(r * 36 + g * 6 + b, i);
                    assertEquals((r * 51) << 16 | (g * 51) << 8 | b * 51, Palette.rgb(i));
                }
        assertThrows(IllegalArgumentException.class, () -> Palette.index(6, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> Palette.index(0, 6, 0));
        assertThrows(IllegalArgumentException.class, () -> Palette.index(0, 0, 6));
        assertThrows(IllegalArgumentException.class, () -> Palette.index(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> Palette.index(0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> Palette.index(0, 0, -1));
    }

    // ---- drawPaletteTile -------------------------------------------------------------------------

    private void check(int tx, int ty, int px, int py, int[] colours) {
        Screen s = sentinelScreen();
        int[] base = s.pixels.clone();
        s.drawPaletteTile(sheet, tx, ty, px, py, colours);
        assertArrayEquals(expected(base, s.width, s.height, tx, ty, px, py, colours), s.pixels,
                "tile " + tx + "," + ty + " at " + px + "," + py + " colours " + java.util.Arrays.toString(colours));
    }

    @Test
    void fourOpaquePaletteColoursDrawByIndex() {
        int[] t = richTile();
        int[] colours = {Palette.index(5, 0, 0), Palette.index(0, 5, 0), Palette.index(0, 0, 5), Palette.index(5, 5, 0)};
        check(t[0], t[1], 20, 30, colours);
        check(1, 0, 0, 0, colours);
        check(2, 0, 77, 41, colours);
        // spot check against the rule directly
        Screen s = sentinelScreen();
        s.drawPaletteTile(sheet, t[0], t[1], 20, 30, colours);
        for (int dy = 0; dy < 8; dy++)
            for (int dx = 0; dx < 8; dx++)
                assertEquals(ruleRgb(colours[idx(t[0] * 8 + dx, t[1] * 8 + dy)]), s.pixels[(30 + dy) * s.width + 20 + dx]);
    }

    @Test
    void paletteIndexZeroIsOpaqueBlackNotTransparent() {
        int[] t = richTile();
        int[] colours = {0, 0, 0, 0};
        Screen s = sentinelScreen();
        s.drawPaletteTile(sheet, t[0], t[1], 10, 10, colours);
        for (int dy = 0; dy < 8; dy++)
            for (int dx = 0; dx < 8; dx++) assertEquals(0x000000, s.pixels[(10 + dy) * s.width + 10 + dx]);
        check(t[0], t[1], 10, 10, colours);
    }

    @Test
    void anyOneSlotCanBeTransparent() {
        for (int slot = 0; slot < 4; slot++) {
            int[] tile = tileUsing(slot);
            int[] colours = {Palette.index(5, 0, 0), Palette.index(0, 5, 0), Palette.index(0, 0, 5), Palette.index(5, 5, 5)};
            colours[slot] = T;
            Screen s = sentinelScreen();
            s.drawPaletteTile(sheet, tile[0], tile[1], 33, 21, colours);
            int kept = 0, drawn = 0;
            for (int dy = 0; dy < 8; dy++)
                for (int dx = 0; dx < 8; dx++) {
                    int got = s.pixels[(21 + dy) * s.width + 33 + dx];
                    if (idx(tile[0] * 8 + dx, tile[1] * 8 + dy) == slot) {
                        assertEquals(SENTINEL, got, "slot " + slot + " transparent: pixel must be untouched");
                        kept++;
                    } else {
                        assertNotEquals(SENTINEL, got, "slot " + slot + " transparent: other slots must still draw");
                        drawn++;
                    }
                }
            assertTrue(kept > 0 && drawn > 0, "test tile for slot " + slot + " must show both behaviours");
            check(tile[0], tile[1], 33, 21, colours);
        }
    }

    @Test
    void anyCombinationOfTransparentSlotsWorks() {
        int[] t = richTile();
        int[] pal = {Palette.index(5, 0, 5), Palette.index(1, 2, 3), Palette.index(4, 4, 0), Palette.index(0, 3, 3)};
        for (int mask = 0; mask < 16; mask++) {
            int[] colours = pal.clone();
            for (int slot = 0; slot < 4; slot++) if ((mask & (1 << slot)) != 0) colours[slot] = T;
            check(t[0], t[1], 57, 63, colours);
        }
        // all transparent: nothing at all changes
        Screen s = noisyScreen();
        int[] base = s.pixels.clone();
        s.drawPaletteTile(sheet, t[0], t[1], 10, 10, new int[] {T, T, T, T});
        assertArrayEquals(base, s.pixels);
    }

    @Test
    void transparentLeavesWhateverWasUnderneathIncludingEarlierTiles() {
        // draw an opaque tile, then a partly transparent one on top: the lower tile must show through
        int[] t = richTile();
        Screen s = sentinelScreen();
        int[] base = s.pixels.clone();
        int[] under = {Palette.index(0, 0, 5), Palette.index(0, 5, 0), Palette.index(5, 0, 0), Palette.index(3, 3, 3)};
        int[] over = {T, Palette.index(5, 5, 0), T, Palette.index(0, 0, 0)};
        s.drawPaletteTile(sheet, t[0], t[1], 40, 40, under);
        s.drawPaletteTile(sheet, t[0], t[1], 44, 42, over);
        int[] exp = expected(base, s.width, s.height, t[0], t[1], 40, 40, under);
        exp = expected(exp, s.width, s.height, t[0], t[1], 44, 42, over);
        assertArrayEquals(exp, s.pixels);
    }

    @Test
    void positionsAndClippingWorkWithTransparency() {
        int[] t = richTile();
        int[] colours = {Palette.index(5, 2, 0), T, Palette.index(0, 2, 5), Palette.index(5, 5, 5)};
        int w = Game.WIDTH, h = Game.HEIGHT;
        int[][] spots = {{0, 0}, {1, 1}, {13, 7}, {99, 83}, {152, 112}, {-3, 50}, {-7, 50}, {w - 5, 50}, {60, -3}, {60, h - 5},
            {-4, -4}, {w - 3, -6}, {-5, h - 2}, {w - 6, h - 7}};
        for (int[] p : spots) check(t[0], t[1], p[0], p[1], colours);
        int[][] off = {{-8, 10}, {w, 10}, {10, -8}, {10, h}, {1000, 1000}, {Integer.MIN_VALUE / 2, 0}, {0, Integer.MAX_VALUE / 2}};
        for (int[] p : off) {
            Screen s = noisyScreen();
            int[] base = s.pixels.clone();
            assertDoesNotThrow(() -> s.drawPaletteTile(sheet, t[0], t[1], p[0], p[1], colours));
            assertArrayEquals(base, s.pixels, "off-screen at " + p[0] + "," + p[1]);
        }
    }

    @Test
    void pixelsOutsideTheTileAreUntouched() {
        int[] t = richTile();
        Screen s = noisyScreen();
        int[] base = s.pixels.clone();
        s.drawPaletteTile(sheet, t[0], t[1], 33, 21, new int[] {Palette.index(5, 0, 0), T, Palette.index(0, 5, 0), T});
        for (int y = 0; y < s.height; y++)
            for (int x = 0; x < s.width; x++) {
                boolean inside = x >= 33 && x < 41 && y >= 21 && y < 29;
                if (!inside) assertEquals(base[y * s.width + x], s.pixels[y * s.width + x], x + "," + y);
            }
    }

    @Test
    void rejectsBadArguments() {
        Screen s = sentinelScreen();
        int[] ok = {0, 1, 2, 3};
        assertThrows(IllegalArgumentException.class, () -> s.drawPaletteTile(sheet, 32, 0, 0, 0, ok));
        assertThrows(IllegalArgumentException.class, () -> s.drawPaletteTile(sheet, 0, 32, 0, 0, ok));
        assertThrows(IllegalArgumentException.class, () -> s.drawPaletteTile(sheet, -1, 0, 0, 0, ok));
        assertThrows(IllegalArgumentException.class, () -> s.drawPaletteTile(sheet, 0, -1, 0, 0, ok));
        assertThrows(IllegalArgumentException.class, () -> s.drawPaletteTile(sheet, 0, 0, 0, 0, null));
        assertThrows(IllegalArgumentException.class, () -> s.drawPaletteTile(sheet, 0, 0, 0, 0, new int[3]));
        assertThrows(IllegalArgumentException.class, () -> s.drawPaletteTile(sheet, 0, 0, 0, 0, new int[5]));
        assertThrows(IllegalArgumentException.class, () -> s.drawPaletteTile(sheet, 0, 0, 0, 0, new int[] {0, 1, 2, 216}));
        assertThrows(IllegalArgumentException.class, () -> s.drawPaletteTile(sheet, 0, 0, 0, 0, new int[] {0, 1, 2, -2}));
        assertThrows(IllegalArgumentException.class, () -> s.drawPaletteTile(sheet, 0, 0, 0, 0, new int[] {Integer.MAX_VALUE, 1, 2, 3}));
    }

    @Test
    void episodeThreeDrawTileStillWorksWithOpaqueRgb() {
        int[] t = richTile();
        int[] grey = {0x000000, 0x555555, 0xAAAAAA, 0xFFFFFF};
        Screen s = sentinelScreen();
        s.drawTile(sheet, t[0], t[1], 5, 6, grey);
        for (int dy = 0; dy < 8; dy++)
            for (int dx = 0; dx < 8; dx++)
                assertEquals(grey[idx(t[0] * 8 + dx, t[1] * 8 + dy)], s.pixels[(6 + dy) * s.width + 5 + dx]);
    }

    // ---- frame dumps from the jar --------------------------------------------------------------

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
        assertNotNull(img);
        assertEquals(Game.WIDTH, img.getWidth());
        assertEquals(Game.HEIGHT, img.getHeight());
        int[] got = img.getRGB(0, 0, Game.WIDTH, Game.HEIGHT, null, 0, Game.WIDTH);
        for (int i = 0; i < got.length; i++) got[i] &= 0xFFFFFF;
        return got;
    }

    /** Test-side parse of "RGB"/"t" tokens into palette indexes (independent of the engine). */
    private static int[] tokens(String... toks) {
        int[] out = new int[4];
        for (int i = 0; i < 4; i++) {
            String t = toks[i];
            out[i] = t.equals("t") ? T : (t.charAt(0) - '0') * 36 + (t.charAt(1) - '0') * 6 + (t.charAt(2) - '0');
        }
        return out;
    }

    @Test
    void dumpShowsRecolouredTile(@TempDir Path tmp) throws Exception {
        int[] t = richTile();
        String[] toks = {"500", "050", "005", "550"};
        Path dir = tmp.resolve("a");
        Run r = runJar("--headless", "--ticks", "3", "--dump-dir", dir.toString(),
                "--scene", "tile:" + t[0] + "," + t[1] + "@37,59:colours=" + String.join(",", toks));
        assertEquals(0, r.exit, r.out);
        assertTrue(r.out.contains("ticks=3 frames=3"), r.out);
        int[] exp = expected(new int[Game.WIDTH * Game.HEIGHT], Game.WIDTH, Game.HEIGHT, t[0], t[1], 37, 59, tokens(toks));
        for (String f : new String[] {"frame-00000.png", "frame-00002.png"}) assertArrayEquals(exp, readFrame(dir.resolve(f)), f);
        // the dump really shows the palette colours: all of red, green, blue, yellow appear
        Set<Integer> colours = new HashSet<>();
        for (int px : readFrame(dir.resolve("frame-00000.png"))) colours.add(px);
        assertTrue(colours.contains(0xFF0000) && colours.contains(0x00FF00) && colours.contains(0x0000FF)
                && colours.contains(0xFFFF00), "dump colours: " + colours);
    }

    @Test
    void dumpWithEachSlotTransparentLeavesBlackBackgroundThere(@TempDir Path tmp) throws Exception {
        for (int slot = 0; slot < 4; slot++) {
            int[] tile = tileUsing(slot);
            String[] toks = {"505", "255", "540", "035"};
            toks[slot] = "t";
            Path dir = tmp.resolve("s" + slot);
            Run r = runJar("--headless", "--ticks", "1", "--dump-dir", dir.toString(),
                    "--scene", "tile:" + tile[0] + "," + tile[1] + "@-2,100:colours=" + String.join(",", toks));
            assertEquals(0, r.exit, r.out);
            int[] exp = expected(new int[Game.WIDTH * Game.HEIGHT], Game.WIDTH, Game.HEIGHT, tile[0], tile[1], -2, 100, tokens(toks));
            assertArrayEquals(exp, readFrame(dir.resolve("frame-00000.png")), "slot " + slot + " transparent");
        }
    }

    @Test
    void dumpWithAllLevelTokensMapsThroughThePalette(@TempDir Path tmp) throws Exception {
        int[] t = richTile();
        String[] toks = {"123", "340", "052", "415"};
        Path dir = tmp.resolve("p");
        Run r = runJar("--headless", "--ticks", "1", "--dump-dir", dir.toString(),
                "--scene", "tile:" + t[0] + "," + t[1] + "@8,8:colours=" + String.join(",", toks));
        assertEquals(0, r.exit, r.out);
        assertArrayEquals(expected(new int[Game.WIDTH * Game.HEIGHT], Game.WIDTH, Game.HEIGHT, t[0], t[1], 8, 8, tokens(toks)),
                readFrame(dir.resolve("frame-00000.png")));
    }

    @Test
    void fillSceneCoversTheScreenWithTheRecolouredTile(@TempDir Path tmp) throws Exception {
        int[] t = richTile();
        String[] toks = {"t", "301", "044", "555"};
        Path dir = tmp.resolve("f");
        Run r = runJar("--headless", "--ticks", "2", "--dump-dir", dir.toString(),
                "--scene", "fill:" + t[0] + "," + t[1] + ":colours=" + String.join(",", toks));
        assertEquals(0, r.exit, r.out);
        assertTrue(r.out.contains("ticks=2 frames=2"), r.out);
        int[] cols = tokens(toks);
        int[] exp = new int[Game.WIDTH * Game.HEIGHT];
        for (int j = 0; j < 15; j++) for (int i = 0; i < 20; i++) exp = expected(exp, Game.WIDTH, Game.HEIGHT, t[0], t[1], i * 8, j * 8, cols);
        for (String f : new String[] {"frame-00000.png", "frame-00001.png"}) assertArrayEquals(exp, readFrame(dir.resolve(f)), f);
    }

    @Test
    void sceneWithoutColoursKeepsEpisodeThreeGreyForBothKinds(@TempDir Path tmp) throws Exception {
        int[] t = richTile();
        int[] grey = {0x000000, 0x555555, 0xAAAAAA, 0xFFFFFF};
        Path dir = tmp.resolve("g");
        Run r = runJar("--headless", "--ticks", "1", "--dump-dir", dir.toString(), "--scene", "tile:" + t[0] + "," + t[1] + "@20,20");
        assertEquals(0, r.exit, r.out);
        assertArrayEquals(expectedGrey(new int[Game.WIDTH * Game.HEIGHT], t, 20, 20, grey), readFrame(dir.resolve("frame-00000.png")));

        Path dir2 = tmp.resolve("g2");
        r = runJar("--headless", "--ticks", "1", "--dump-dir", dir2.toString(), "--scene", "fill:" + t[0] + "," + t[1]);
        assertEquals(0, r.exit, r.out);
        int[] exp = new int[Game.WIDTH * Game.HEIGHT];
        for (int j = 0; j < 15; j++) for (int i = 0; i < 20; i++) exp = expectedGrey(exp, t, i * 8, j * 8, grey);
        assertArrayEquals(exp, readFrame(dir2.resolve("frame-00000.png")));
    }

    private static int[] expectedGrey(int[] base, int[] t, int px, int py, int[] grey) {
        int[] out = base.clone();
        for (int dy = 0; dy < 8; dy++)
            for (int dx = 0; dx < 8; dx++) {
                int sx = px + dx, sy = py + dy;
                if (sx < 0 || sy < 0 || sx >= Game.WIDTH || sy >= Game.HEIGHT) continue;
                out[sy * Game.WIDTH + sx] = grey[idx(t[0] * 8 + dx, t[1] * 8 + dy)];
            }
        return out;
    }

    @Test
    void malformedColourSpecsExitTwo() throws Exception {
        String[] bad = {
            "tile:1,0@0,0:colours=500,050,005",          // 3 colours
            "tile:1,0@0,0:colours=500,050,005,555,000",  // 5 colours
            "tile:1,0@0,0:colours=",                     // empty
            "tile:1,0@0,0:colours=500,050,005,556",      // level 6
            "tile:1,0@0,0:colours=500,050,005,55",       // two digits
            "tile:1,0@0,0:colours=500,050,005,x",        // not t
            "tile:1,0@0,0:colours=500,050,005,T",        // upper-case T is not "t"
            "tile:1,0@0,0:colour=500,050,005,555",       // key spelt wrong
            "tile:1,0@0,0:colours=500,050,005,555:x",    // extra part
            "fill:1,0@3,3:colours=500,050,005,555",      // fill takes no position
            "fill:1,0:colours=500,050,005",
            "fill:a,b:colours=500,050,005,555",
            "fill:99,0:colours=500,050,005,555",         // tile outside the grid
        };
        for (String s : bad) {
            Run r = runJar("--headless", "--ticks", "1", "--scene", s);
            assertEquals(2, r.exit, "scene '" + s + "' should be rejected; output:\n" + r.out);
        }
    }

    // ---- performance ---------------------------------------------------------------------------

    @Test
    void fillSceneRenders600FramesQuickly() {
        int[] t = richTile();
        Scene scene = Scene.parse("fill:" + t[0] + "," + t[1] + ":colours=t,301,044,555");
        Screen screen = new Screen(Game.WIDTH, Game.HEIGHT);
        for (int i = 0; i < 120; i++) scene.render(screen); // warm-up (JIT)
        long bestMs = Long.MAX_VALUE;
        long sink = 0;
        for (int attempt = 0; attempt < 3 && bestMs >= 1500; attempt++) {
            long start = System.nanoTime();
            for (int i = 0; i < 600; i++) {
                scene.render(screen);
                sink += screen.pixels[i % screen.pixels.length];
            }
            bestMs = Math.min(bestMs, (System.nanoTime() - start) / 1_000_000);
        }
        // the work was real: the last frame is the fill scene, drawn correctly at one spot
        int[] cols = tokens("t", "301", "044", "555");
        int[] exp = new int[Game.WIDTH * Game.HEIGHT];
        for (int j = 0; j < 15; j++) for (int i = 0; i < 20; i++) exp = expected(exp, Game.WIDTH, Game.HEIGHT, t[0], t[1], i * 8, j * 8, cols);
        assertArrayEquals(exp, screen.pixels, "benchmark must render the real fill scene (sink " + sink + ")");
        assertTrue(bestMs < 1500, "600 full-screen frames took " + bestMs + " ms (limit 1500 ms)");
    }
}

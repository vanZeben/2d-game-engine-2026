package ge;

import static org.junit.jupiter.api.Assertions.*;

import ge.gfx.Palette;
import ge.gfx.SpriteSheet;
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
 * Episode 6 check: "Mirroring the Sprites".
 * SPEC: a tile can be drawn mirrored on X, Y or both; dumps match.
 *
 * CONTRACT the builder must satisfy
 * ---------------------------------
 * 1. ge.Screen gains constants and two overloads (the existing 6-argument methods stay and behave as
 *    before, i.e. exactly "mirror = 0"):
 *      public static final int MIRROR_NONE = 0;
 *      public static final int MIRROR_X = 1;     // flip left-right
 *      public static final int MIRROR_Y = 2;     // flip top-bottom
 *      public void drawTile(SpriteSheet sheet, int tileX, int tileY, int x, int y, int[] colours, int mirror)
 *      public void drawPaletteTile(SpriteSheet sheet, int tileX, int tileY, int x, int y, int[] colours, int mirror)
 *    {@code mirror} is a bit set of MIRROR_X | MIRROR_Y (so 0, 1, 2 or 3 = both).
 *    Meaning: the tile is first laid out at (x,y) as an 8x8 block, then flipped in place. With MIRROR_X the
 *    screen pixel (x+dx, y+dy) shows the tile pixel (7-dx, dy); with MIRROR_Y it shows (dx, 7-dy); with both
 *    it shows (7-dx, 7-dy). The block still occupies screen x..x+7, y..y+7 (mirroring never moves it).
 *    Everything else is as for the unmirrored call: (x,y) any int, the block is clipped silently at every
 *    edge (what is visible is exactly the matching part of the flipped block, not a flipped part of the
 *    clipped block), entirely off-screen is a no-op, colours are int[4] 0xRRGGBB (drawTile; -1 = transparent
 *    as before) or palette indexes / Palette.TRANSPARENT (drawPaletteTile; transparent pixels leave the screen
 *    untouched). The argument checks of the 6-argument methods apply too (tile outside the grid, colours
 *    null or not 4 long, palette entry out of range -> IllegalArgumentException), plus:
 *    IllegalArgumentException if mirror has any bit other than MIRROR_X|MIRROR_Y set (mirror < 0 or > 3).
 *
 * 2. HEADLESS SCENES. --scene (and Scene.parse) gains one more optional part for both kinds:
 *      --scene tile:TX,TY@PX,PY[:colours=C0,C1,C2,C3][:mirror=M]
 *      --scene fill:TX,TY[:colours=C0,C1,C2,C3][:mirror=M]
 *    M is exactly one of the lower-case words  x  y  xy  (flip on X; flip on Y; both). The optional parts
 *    may come in either order (colours then mirror, or mirror then colours), each at most once.
 *    Without colours= the grey of episode 3 is used (via drawTile, now with the mirror argument); with
 *    colours= the tile goes through drawPaletteTile with the mirror argument. fill mirrors every tile of the
 *    grid the same way (each 8x8 block flipped in place). Without mirror= nothing changes.
 *    Malformed values exit 2 with a message on stderr: mirror= with an empty value, "z", "X", "XY", "yx",
 *    "none", "x,y"; mirror given twice; "mirror" spelt differently ("mirrored="); colours given twice.
 *
 * Expected pixels are computed here from res/sprite_sheet.png (index = blue >> 6, as in episode 2) and the
 * palette rule (index r*36+g*6+b, channel = level*51), never from engine output.
 */
public class Ep06Test {

    private static final Path JAR = Paths.get("build/game.jar");
    private static final int SENTINEL = 0x123456;
    private static final int T = Palette.TRANSPARENT;
    private static final int[] GREY = {0x000000, 0x555555, 0xAAAAAA, 0xFFFFFF};
    private static final int[] RGBX = {0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00};
    private static final int[] MODES = {0, Screen.MIRROR_X, Screen.MIRROR_Y, Screen.MIRROR_X | Screen.MIRROR_Y};

    private static BufferedImage sheetImage;
    private static SpriteSheet sheet;

    @BeforeAll
    static void loadSheet() throws Exception {
        try (InputStream in = Ep06Test.class.getResourceAsStream("/sprite_sheet.png")) {
            sheetImage = ImageIO.read(in);
        }
        sheet = SpriteSheet.load("/sprite_sheet.png");
    }

    // ---- independent expectations ---------------------------------------------------------------

    private static int idx(int sx, int sy) {
        return (sheetImage.getRGB(sx, sy) & 0xff) >> 6;
    }

    private static int ruleRgb(int index) {
        int r = index / 36, g = (index / 6) % 6, b = index % 6;
        return (r * 51) << 16 | (g * 51) << 8 | (b * 51);
    }

    /** The sheet colour index at tile pixel (u,v). */
    private static int tp(int tx, int ty, int u, int v) {
        return idx(tx * 8 + u, ty * 8 + v);
    }

    /**
     * Expected screen after drawing. {@code palette} selects palette indexes (transparent = -1) vs raw rgb
     * (-1 transparent too, as drawTile treats it).
     */
    private static int[] expected(int[] base, int tx, int ty, int px, int py, int[] colours, boolean palette, int mirror) {
        int w = Game.WIDTH, h = Game.HEIGHT;
        int[] out = base.clone();
        for (int dy = 0; dy < 8; dy++) {
            for (int dx = 0; dx < 8; dx++) {
                long sx = (long) px + dx, sy = (long) py + dy;
                if (sx < 0 || sy < 0 || sx >= w || sy >= h) continue;
                int u = (mirror & 1) != 0 ? 7 - dx : dx;
                int v = (mirror & 2) != 0 ? 7 - dy : dy;
                int c = colours[tp(tx, ty, u, v)];
                if (c == T) continue;
                out[(int) (sy * w + sx)] = palette ? ruleRgb(c) : c;
            }
        }
        return out;
    }

    private static boolean differs(int tx, int ty, int mirror) {
        for (int v = 0; v < 8; v++)
            for (int u = 0; u < 8; u++) {
                int uu = (mirror & 1) != 0 ? 7 - u : u, vv = (mirror & 2) != 0 ? 7 - v : v;
                if (tp(tx, ty, u, v) != tp(tx, ty, uu, vv)) return true;
            }
        return false;
    }

    /** Picks an asymmetric tile (all of X, Y and XY mirrors differ from it), preferring the given tile. */
    private static int[] asymmetric(int... prefer) {
        if (prefer.length == 2 && differs(prefer[0], prefer[1], 1) && differs(prefer[0], prefer[1], 2)
                && differs(prefer[0], prefer[1], 3)) return prefer;
        for (int ty = 0; ty < 32; ty++)
            for (int tx = 0; tx < 32; tx++)
                if (differs(tx, ty, 1) && differs(tx, ty, 2) && differs(tx, ty, 3)) return new int[] {tx, ty};
        throw new AssertionError("no asymmetric tile in the sheet");
    }

    /** Tile in the sheet that is symmetric on X but not on Y (and one the other way round), if any. */
    private static int[] onlyYAsymmetric() {
        for (int ty = 0; ty < 32; ty++)
            for (int tx = 0; tx < 32; tx++)
                if (!differs(tx, ty, 1) && differs(tx, ty, 2) && distinct(tx, ty) >= 2) return new int[] {tx, ty};
        return null;
    }

    private static int[] onlyXAsymmetric() {
        for (int ty = 0; ty < 32; ty++)
            for (int tx = 0; tx < 32; tx++)
                if (differs(tx, ty, 1) && !differs(tx, ty, 2) && distinct(tx, ty) >= 2) return new int[] {tx, ty};
        return null;
    }

    private static int distinct(int tx, int ty) {
        boolean[] seen = new boolean[4];
        for (int v = 0; v < 8; v++) for (int u = 0; u < 8; u++) seen[tp(tx, ty, u, v)] = true;
        int n = 0;
        for (boolean b : seen) if (b) n++;
        return n;
    }

    /** Font 'F'-ish: tile 0,30 is the glyph tile used since episode 3 ('A' there); needs all four indexes distinguishable. */
    private static int[] tile() {
        return asymmetric(1, 30);
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

    private static String modeName(int m) {
        return switch (m) { case 0 -> "none"; case 1 -> "x"; case 2 -> "y"; default -> "xy"; };
    }

    // ---- sanity of the premise -----------------------------------------------------------------

    @Test
    void theTestTileIsReallyAsymmetric() {
        int[] t = tile();
        assertTrue(differs(t[0], t[1], 1), "X mirror must change the tile");
        assertTrue(differs(t[0], t[1], 2), "Y mirror must change the tile");
        assertTrue(differs(t[0], t[1], 3), "XY mirror must change the tile");
        // the player tile rows 28-29 exist and the sheet offers asymmetric tiles at all
        assertEquals(Screen.MIRROR_X, 1);
        assertEquals(Screen.MIRROR_Y, 2);
        assertEquals(0, Screen.MIRROR_NONE);
    }

    // ---- grey path: drawTile --------------------------------------------------------------------

    @Test
    void drawTileMirrorsOnEachAxisAndBoth() {
        int[] t = tile();
        for (int m : MODES) {
            Screen s = sentinelScreen();
            s.drawTile(sheet, t[0], t[1], 41, 53, GREY, m);
            assertArrayEquals(expected(sentinelScreen().pixels, t[0], t[1], 41, 53, GREY, false, m), s.pixels,
                    "mirror " + modeName(m));
        }
    }

    @Test
    void mirrorXOnlyReversesColumnsAndKeepsRows() {
        int[] t = tile();
        Screen s = new Screen(Game.WIDTH, Game.HEIGHT);
        s.drawTile(sheet, t[0], t[1], 10, 20, RGBX, Screen.MIRROR_X);
        for (int v = 0; v < 8; v++)
            for (int u = 0; u < 8; u++)
                assertEquals(RGBX[tp(t[0], t[1], 7 - u, v)], s.pixels[(20 + v) * Game.WIDTH + 10 + u], "u=" + u + " v=" + v);
    }

    @Test
    void mirrorYOnlyReversesRowsAndKeepsColumns() {
        int[] t = tile();
        Screen s = new Screen(Game.WIDTH, Game.HEIGHT);
        s.drawTile(sheet, t[0], t[1], 10, 20, RGBX, Screen.MIRROR_Y);
        for (int v = 0; v < 8; v++)
            for (int u = 0; u < 8; u++)
                assertEquals(RGBX[tp(t[0], t[1], u, 7 - v)], s.pixels[(20 + v) * Game.WIDTH + 10 + u], "u=" + u + " v=" + v);
    }

    @Test
    void mirrorBothEqualsRotatingBy180() {
        int[] t = tile();
        Screen s = new Screen(Game.WIDTH, Game.HEIGHT);
        s.drawTile(sheet, t[0], t[1], 0, 0, RGBX, Screen.MIRROR_X | Screen.MIRROR_Y);
        for (int v = 0; v < 8; v++)
            for (int u = 0; u < 8; u++)
                assertEquals(RGBX[tp(t[0], t[1], 7 - u, 7 - v)], s.pixels[v * Game.WIDTH + u]);
    }

    @Test
    void mirrorTwiceIsTheIdentity() {
        int[] t = tile();
        Screen a = new Screen(Game.WIDTH, Game.HEIGHT), b = new Screen(Game.WIDTH, Game.HEIGHT);
        a.drawTile(sheet, t[0], t[1], 5, 5, GREY, 0);
        // draw the X-mirrored tile, then re-draw it mirrored again over itself on a second screen:
        // mirroring is an involution, so (flip of flip) lands on the original picture
        Screen once = new Screen(Game.WIDTH, Game.HEIGHT);
        once.drawTile(sheet, t[0], t[1], 5, 5, GREY, Screen.MIRROR_X);
        int[] flippedBack = new int[64];
        for (int v = 0; v < 8; v++) for (int u = 0; u < 8; u++) flippedBack[v * 8 + u] = once.pixels[(5 + v) * Game.WIDTH + 5 + 7 - u];
        for (int v = 0; v < 8; v++) for (int u = 0; u < 8; u++) assertEquals(a.pixels[(5 + v) * Game.WIDTH + 5 + u], flippedBack[v * 8 + u]);
        assertNotNull(b);
    }

    @Test
    void mirrorNoneIsExactlyTheExistingBehaviour() {
        int[] t = tile();
        int[][] spots = {{41, 53}, {-3, -5}, {157, 117}, {-8, 0}, {0, 112}, {200, 200}, {-100, 3}};
        for (int[] p : spots) {
            Screen old = noisyScreen(), now = noisyScreen(), paletteOld = noisyScreen(), paletteNow = noisyScreen();
            old.drawTile(sheet, t[0], t[1], p[0], p[1], GREY);
            now.drawTile(sheet, t[0], t[1], p[0], p[1], GREY, 0);
            assertArrayEquals(old.pixels, now.pixels, "drawTile at " + p[0] + "," + p[1]);
            int[] pal = {0, 215, 36, 7};
            paletteOld.drawPaletteTile(sheet, t[0], t[1], p[0], p[1], pal);
            paletteNow.drawPaletteTile(sheet, t[0], t[1], p[0], p[1], pal, Screen.MIRROR_NONE);
            assertArrayEquals(paletteOld.pixels, paletteNow.pixels, "drawPaletteTile at " + p[0] + "," + p[1]);
        }
    }

    @Test
    void sixArgumentMethodsStillWork() {
        int[] t = tile();
        Screen s = sentinelScreen();
        s.drawTile(sheet, t[0], t[1], 8, 8, GREY);
        assertArrayEquals(expected(sentinelScreen().pixels, t[0], t[1], 8, 8, GREY, false, 0), s.pixels);
    }

    @Test
    void symmetricAxisMirrorsLeaveTileUnchangedWhereTheSheetHasOne() {
        int[] ty = onlyYAsymmetric();
        if (ty != null) {
            Screen a = new Screen(Game.WIDTH, Game.HEIGHT), b = new Screen(Game.WIDTH, Game.HEIGHT);
            a.drawTile(sheet, ty[0], ty[1], 20, 20, RGBX, 0);
            b.drawTile(sheet, ty[0], ty[1], 20, 20, RGBX, Screen.MIRROR_X);
            assertArrayEquals(a.pixels, b.pixels, "X-symmetric tile must not change under MIRROR_X");
        }
        int[] tx = onlyXAsymmetric();
        if (tx != null) {
            Screen a = new Screen(Game.WIDTH, Game.HEIGHT), b = new Screen(Game.WIDTH, Game.HEIGHT);
            a.drawTile(sheet, tx[0], tx[1], 20, 20, RGBX, 0);
            b.drawTile(sheet, tx[0], tx[1], 20, 20, RGBX, Screen.MIRROR_Y);
            assertArrayEquals(a.pixels, b.pixels, "Y-symmetric tile must not change under MIRROR_Y");
        }
    }

    // ---- clipping with mirroring -----------------------------------------------------------------

    /** Positions that clip the 8x8 block on every edge, every corner, and fully off-screen. */
    private static int[][] clipSpots() {
        int W = Game.WIDTH, H = Game.HEIGHT;
        List<int[]> l = new ArrayList<>();
        int[] xs = {-8, -7, -4, -1, 0, 3, W - 8, W - 7, W - 4, W - 1, W, W + 5};
        int[] ys = {-8, -7, -4, -1, 0, 3, H - 8, H - 7, H - 4, H - 1, H, H + 5};
        for (int x : xs) for (int y : ys) l.add(new int[] {x, y});
        l.add(new int[] {Integer.MIN_VALUE, 5});
        l.add(new int[] {5, Integer.MIN_VALUE});
        l.add(new int[] {Integer.MAX_VALUE, 5});
        l.add(new int[] {5, Integer.MAX_VALUE});
        l.add(new int[] {Integer.MIN_VALUE, Integer.MIN_VALUE});
        l.add(new int[] {Integer.MAX_VALUE, Integer.MAX_VALUE});
        return l.toArray(new int[0][]);
    }

    @Test
    void drawTileMirroredIsClippedCorrectlyAtEveryEdge() {
        int[] t = tile();
        for (int m : MODES) {
            for (int[] p : clipSpots()) {
                Screen s = noisyScreen();
                s.drawTile(sheet, t[0], t[1], p[0], p[1], RGBX, m);
                assertArrayEquals(expected(noisyScreen().pixels, t[0], t[1], p[0], p[1], RGBX, false, m), s.pixels,
                        "mirror " + modeName(m) + " at " + p[0] + "," + p[1]);
            }
        }
    }

    @Test
    void drawPaletteTileMirroredIsClippedCorrectlyAtEveryEdge() {
        int[] t = tile();
        int[] pal = {Palette.index(5, 0, 0), Palette.index(0, 5, 0), Palette.index(0, 0, 5), Palette.index(5, 5, 0)};
        for (int m : MODES) {
            for (int[] p : clipSpots()) {
                Screen s = noisyScreen();
                s.drawPaletteTile(sheet, t[0], t[1], p[0], p[1], pal, m);
                assertArrayEquals(expected(noisyScreen().pixels, t[0], t[1], p[0], p[1], pal, true, m), s.pixels,
                        "mirror " + modeName(m) + " at " + p[0] + "," + p[1]);
            }
        }
    }

    @Test
    void mirroredTileOnLeftEdgeShowsTheRightColumnsOfTheFlippedBlock() {
        // x=-3: dest columns 0..4 are block columns 3..7. With MIRROR_X those show tile columns 4..0.
        int[] t = tile();
        Screen s = new Screen(Game.WIDTH, Game.HEIGHT);
        s.drawTile(sheet, t[0], t[1], -3, 10, RGBX, Screen.MIRROR_X);
        for (int v = 0; v < 8; v++)
            for (int sx = 0; sx < 5; sx++)
                assertEquals(RGBX[tp(t[0], t[1], 7 - (sx + 3), v)], s.pixels[(10 + v) * Game.WIDTH + sx], "sx=" + sx + " v=" + v);
        for (int v = 0; v < 8; v++) assertEquals(0, s.pixels[(10 + v) * Game.WIDTH + 5], "pixel right of the tile untouched");
    }

    @Test
    void mirroredTileOnBottomRightShowsTheTopLeftOfTheFlippedBlock() {
        int W = Game.WIDTH, H = Game.HEIGHT;
        int[] t = tile();
        Screen s = new Screen(W, H);
        s.drawTile(sheet, t[0], t[1], W - 3, H - 2, RGBX, 3);
        for (int dy = 0; dy < 2; dy++)
            for (int dx = 0; dx < 3; dx++)
                assertEquals(RGBX[tp(t[0], t[1], 7 - dx, 7 - dy)], s.pixels[(H - 2 + dy) * W + W - 3 + dx], "dx=" + dx + " dy=" + dy);
    }

    // ---- transparency with mirroring -------------------------------------------------------------

    @Test
    void transparentSlotsStayTransparentWhenMirrored() {
        int[] t = tile();
        for (int m : MODES) {
            for (int slot = 0; slot < 4; slot++) {
                int[] pal = {Palette.index(5, 0, 0), Palette.index(0, 5, 0), Palette.index(0, 0, 5), Palette.index(5, 5, 0)};
                pal[slot] = T;
                for (int[] p : new int[][] {{40, 40}, {-3, 112}, {157, -4}}) {
                    Screen s = noisyScreen();
                    s.drawPaletteTile(sheet, t[0], t[1], p[0], p[1], pal, m);
                    assertArrayEquals(expected(noisyScreen().pixels, t[0], t[1], p[0], p[1], pal, true, m), s.pixels,
                            "mirror " + modeName(m) + " transparent slot " + slot + " at " + p[0] + "," + p[1]);
                }
            }
        }
    }

    @Test
    void transparentMirroredTileLetsEarlierTilesShowThrough() {
        int[] t = tile();
        Screen s = sentinelScreen();
        int[] opaque = {Palette.index(1, 1, 1), Palette.index(2, 2, 2), Palette.index(3, 3, 3), Palette.index(4, 4, 4)};
        s.drawPaletteTile(sheet, t[0], t[1], 30, 30, opaque, 0);
        int[] base = s.pixels.clone();
        int[] over = {T, Palette.index(5, 0, 0), T, Palette.index(0, 0, 5)};
        s.drawPaletteTile(sheet, t[0], t[1], 30, 30, over, Screen.MIRROR_X);
        assertArrayEquals(expected(base, t[0], t[1], 30, 30, over, true, Screen.MIRROR_X), s.pixels);
    }

    @Test
    void allTransparentMirroredDrawsNothing() {
        int[] t = tile();
        Screen s = noisyScreen();
        s.drawPaletteTile(sheet, t[0], t[1], 20, 20, new int[] {T, T, T, T}, 3);
        assertArrayEquals(noisyScreen().pixels, s.pixels);
    }

    @Test
    void drawTileMinusOneIsTransparentAlsoWhenMirrored() {
        int[] t = tile();
        int[] c = {-1, 0x112233, -1, 0x445566};
        Screen s = noisyScreen();
        s.drawTile(sheet, t[0], t[1], 12, 12, c, Screen.MIRROR_Y);
        assertArrayEquals(expected(noisyScreen().pixels, t[0], t[1], 12, 12, c, false, Screen.MIRROR_Y), s.pixels);
    }

    // ---- argument checks -------------------------------------------------------------------------

    @Test
    void rejectsBadMirrorAndOtherBadArguments() {
        Screen s = new Screen(Game.WIDTH, Game.HEIGHT);
        int[] pal = {0, 1, 2, 3};
        for (int bad : new int[] {-1, 4, 5, 8, 255, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> s.drawTile(sheet, 0, 0, 0, 0, GREY, bad), "drawTile mirror " + bad);
            assertThrows(IllegalArgumentException.class, () -> s.drawPaletteTile(sheet, 0, 0, 0, 0, pal, bad), "drawPaletteTile mirror " + bad);
        }
        assertThrows(IllegalArgumentException.class, () -> s.drawTile(sheet, 32, 0, 0, 0, GREY, 1));
        assertThrows(IllegalArgumentException.class, () -> s.drawTile(sheet, 0, -1, 0, 0, GREY, 1));
        assertThrows(IllegalArgumentException.class, () -> s.drawTile(sheet, 0, 0, 0, 0, null, 1));
        assertThrows(IllegalArgumentException.class, () -> s.drawTile(sheet, 0, 0, 0, 0, new int[3], 2));
        assertThrows(IllegalArgumentException.class, () -> s.drawPaletteTile(sheet, 0, 32, 0, 0, pal, 1));
        assertThrows(IllegalArgumentException.class, () -> s.drawPaletteTile(sheet, 0, 0, 0, 0, null, 1));
        assertThrows(IllegalArgumentException.class, () -> s.drawPaletteTile(sheet, 0, 0, 0, 0, new int[5], 1));
        assertThrows(IllegalArgumentException.class, () -> s.drawPaletteTile(sheet, 0, 0, 0, 0, new int[] {0, 0, 0, 216}, 1));
        assertThrows(IllegalArgumentException.class, () -> s.drawPaletteTile(sheet, 0, 0, 0, 0, new int[] {0, 0, 0, -2}, 1));
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

    private static int[] tokens(String... toks) {
        int[] out = new int[4];
        for (int i = 0; i < 4; i++) {
            String t = toks[i];
            out[i] = t.equals("t") ? T : (t.charAt(0) - '0') * 36 + (t.charAt(1) - '0') * 6 + (t.charAt(2) - '0');
        }
        return out;
    }

    private static int modeOf(String word) {
        return switch (word) { case "x" -> 1; case "y" -> 2; case "xy" -> 3; default -> throw new AssertionError(word); };
    }

    @Test
    void dumpOfGreyTileMatchesForEveryMirrorMode(@TempDir Path tmp) throws Exception {
        int[] t = tile();
        for (String w : new String[] {"x", "y", "xy"}) {
            Path dir = tmp.resolve("g" + w);
            Run r = runJar("--headless", "--ticks", "3", "--dump-dir", dir.toString(),
                    "--scene", "tile:" + t[0] + "," + t[1] + "@37,59:mirror=" + w);
            assertEquals(0, r.exit, r.out);
            assertTrue(r.out.contains("ticks=3 frames=3"), r.out);
            int[] exp = expected(new int[Game.WIDTH * Game.HEIGHT], t[0], t[1], 37, 59, GREY, false, modeOf(w));
            for (String f : new String[] {"frame-00000.png", "frame-00002.png"}) assertArrayEquals(exp, readFrame(dir.resolve(f)), w + " " + f);
        }
    }

    @Test
    void dumpOfRecolouredTileMatchesForEveryMirrorModeAndEitherOptionOrder(@TempDir Path tmp) throws Exception {
        int[] t = tile();
        String[] toks = {"500", "050", "005", "550"};
        String cols = "colours=" + String.join(",", toks);
        int n = 0;
        for (String w : new String[] {"x", "y", "xy"}) {
            for (String opts : new String[] {cols + ":mirror=" + w, "mirror=" + w + ":" + cols}) {
                Path dir = tmp.resolve("p" + n++);
                Run r = runJar("--headless", "--ticks", "1", "--dump-dir", dir.toString(),
                        "--scene", "tile:" + t[0] + "," + t[1] + "@81,17:" + opts);
                assertEquals(0, r.exit, opts + "\n" + r.out);
                int[] exp = expected(new int[Game.WIDTH * Game.HEIGHT], t[0], t[1], 81, 17, tokens(toks), true, modeOf(w));
                assertArrayEquals(exp, readFrame(dir.resolve("frame-00000.png")), opts);
            }
        }
    }

    @Test
    void dumpOfMirroredTileWithTransparencyKeepsBlackBackground(@TempDir Path tmp) throws Exception {
        int[] t = tile();
        String[] toks = {"t", "500", "t", "005"};
        Path dir = tmp.resolve("t");
        Run r = runJar("--headless", "--ticks", "1", "--dump-dir", dir.toString(),
                "--scene", "tile:" + t[0] + "," + t[1] + "@60,60:colours=" + String.join(",", toks) + ":mirror=xy");
        assertEquals(0, r.exit, r.out);
        assertArrayEquals(expected(new int[Game.WIDTH * Game.HEIGHT], t[0], t[1], 60, 60, tokens(toks), true, 3),
                readFrame(dir.resolve("frame-00000.png")));
    }

    @Test
    void dumpOfMirroredTileClippedAtEdgesMatches(@TempDir Path tmp) throws Exception {
        int[] t = tile();
        int[][] spots = {{-3, -5}, {156, 116}, {-6, 100}, {158, 3}};
        String[] toks = {"500", "050", "005", "550"};
        int n = 0;
        for (int[] p : spots) {
            for (String w : new String[] {"x", "y", "xy"}) {
                Path dir = tmp.resolve("c" + n++);
                Run r = runJar("--headless", "--ticks", "1", "--dump-dir", dir.toString(),
                        "--scene", "tile:" + t[0] + "," + t[1] + "@" + p[0] + "," + p[1] + ":colours=" + String.join(",", toks) + ":mirror=" + w);
                assertEquals(0, r.exit, r.out);
                assertArrayEquals(expected(new int[Game.WIDTH * Game.HEIGHT], t[0], t[1], p[0], p[1], tokens(toks), true, modeOf(w)),
                        readFrame(dir.resolve("frame-00000.png")), w + " at " + p[0] + "," + p[1]);
            }
        }
    }

    @Test
    void mirroredDumpDiffersFromUnmirroredDumpForAsymmetricTile(@TempDir Path tmp) throws Exception {
        int[] t = tile();
        Path plain = tmp.resolve("plain");
        assertEquals(0, runJar("--headless", "--ticks", "1", "--dump-dir", plain.toString(),
                "--scene", "tile:" + t[0] + "," + t[1] + "@20,20").exit);
        int[] base = readFrame(plain.resolve("frame-00000.png"));
        assertArrayEquals(expected(new int[Game.WIDTH * Game.HEIGHT], t[0], t[1], 20, 20, GREY, false, 0), base);
        for (String w : new String[] {"x", "y", "xy"}) {
            Path dir = tmp.resolve(w);
            assertEquals(0, runJar("--headless", "--ticks", "1", "--dump-dir", dir.toString(),
                    "--scene", "tile:" + t[0] + "," + t[1] + "@20,20:mirror=" + w).exit);
            assertFalse(java.util.Arrays.equals(base, readFrame(dir.resolve("frame-00000.png"))), w + " must differ from unmirrored");
        }
    }

    @Test
    void fillSceneMirrorsEveryTileInPlace(@TempDir Path tmp) throws Exception {
        int[] t = tile();
        String[] toks = {"500", "050", "005", "550"};
        for (String w : new String[] {"x", "y", "xy"}) {
            for (boolean colours : new boolean[] {false, true}) {
                Path dir = tmp.resolve("f" + w + colours);
                Run r = runJar("--headless", "--ticks", "1", "--dump-dir", dir.toString(),
                        "--scene", "fill:" + t[0] + "," + t[1] + (colours ? ":colours=" + String.join(",", toks) : "") + ":mirror=" + w);
                assertEquals(0, r.exit, r.out);
                int[] exp = new int[Game.WIDTH * Game.HEIGHT];
                for (int j = 0; j < 15; j++)
                    for (int i = 0; i < 20; i++)
                        exp = expected(exp, t[0], t[1], i * 8, j * 8, colours ? tokens(toks) : GREY, colours, modeOf(w));
                assertArrayEquals(exp, readFrame(dir.resolve("frame-00000.png")), w + " colours=" + colours);
            }
        }
    }

    @Test
    void sceneParseAndRenderMatchInProcess() {
        int[] t = tile();
        Screen s = new Screen(Game.WIDTH, Game.HEIGHT);
        Scene.parse("tile:" + t[0] + "," + t[1] + "@-2,9:mirror=y").render(s);
        assertArrayEquals(expected(new int[Game.WIDTH * Game.HEIGHT], t[0], t[1], -2, 9, GREY, false, 2), s.pixels);
    }

    @Test
    void scenesWithoutMirrorAreUnchanged(@TempDir Path tmp) throws Exception {
        int[] t = tile();
        Path dir = tmp.resolve("u");
        Run r = runJar("--headless", "--ticks", "1", "--dump-dir", dir.toString(),
                "--scene", "tile:" + t[0] + "," + t[1] + "@33,44:colours=500,050,005,550");
        assertEquals(0, r.exit, r.out);
        assertArrayEquals(expected(new int[Game.WIDTH * Game.HEIGHT], t[0], t[1], 33, 44, tokens("500", "050", "005", "550"), true, 0),
                readFrame(dir.resolve("frame-00000.png")));
    }

    @Test
    void malformedMirrorSpecsExitTwo() throws Exception {
        String[] bad = {
            "tile:1,30@0,0:mirror=",                              // empty
            "tile:1,30@0,0:mirror=z",
            "tile:1,30@0,0:mirror=X",                             // upper case
            "tile:1,30@0,0:mirror=XY",
            "tile:1,30@0,0:mirror=yx",                            // only x, y, xy
            "tile:1,30@0,0:mirror=none",
            "tile:1,30@0,0:mirror=x,y",
            "tile:1,30@0,0:mirror=xx",
            "tile:1,30@0,0:mirror=x:mirror=y",                    // twice
            "tile:1,30@0,0:mirror=x:mirror=x",
            "tile:1,30@0,0:mirrored=x",                           // misspelt key
            "tile:1,30@0,0:mirror",                               // no value
            "tile:1,30@0,0:colours=500,050,005,555:colours=500,050,005,555",   // colours twice
            "tile:1,30@0,0:colours=500,050,005,555:mirror=x:mirror=y",
            "tile:1,30@0,0:colours=500,050,005,555:mirror=x:extra",
            "fill:1,30:mirror=q",
            "fill:1,30:mirror=",
            "fill:1,30@2,2:mirror=x",                             // fill takes no position
        };
        for (String s : bad) {
            Run r = runJar("--headless", "--ticks", "1", "--scene", s);
            assertEquals(2, r.exit, "scene '" + s + "' should be rejected; output:\n" + r.out);
        }
    }
}

package ge;

import static org.junit.jupiter.api.Assertions.*;

import ge.gfx.Font;
import ge.gfx.Palette;
import ge.gfx.SpriteSheet;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/*
 * Episode 10 check: "Scaling Things up!".
 * SPEC: sprites and text can be drawn at an integer scale; a x2 dump matches.
 *
 * CONTRACT (new overloads; every existing signature and behaviour is unchanged)
 * ------------------------------------------------------------------------------
 * 1. Screen:
 *      void drawTile(SpriteSheet, int tileX, int tileY, int x, int y, int[] colours, int mirror, int scale)
 *      void drawPaletteTile(SpriteSheet, int tileX, int tileY, int x, int y, int[] colours, int mirror, int scale)
 *    Each sheet pixel of the (mirrored) 8x8 tile becomes a scale x scale block, so the tile covers
 *    8*scale x 8*scale screen pixels with its top-left at (x, y). Screen pixel (x+dx, y+dy), 0<=dx,dy<8*scale, shows
 *    the tile pixel (dx/scale, dy/scale) after mirroring is applied to the 8x8 SOURCE exactly as at scale 1
 *    (MIRROR_X: source column 7-(dx/scale); MIRROR_Y likewise). Transparent colours leave the screen untouched,
 *    pixels outside the buffer are skipped (any x, y, even Integer.MIN/MAX, and any scale up to Integer.MAX_VALUE:
 *    clip in long arithmetic, never throw for position/size). scale < 1 -> IllegalArgumentException (checked even
 *    when the tile would be entirely off screen; the other argument checks are as for the shorter overloads).
 *    scale 1 draws exactly as the existing overload.
 * 2. Font:
 *      static void render(Screen, SpriteSheet, String text, int x, int y, int[] colours, int scale)
 *      static int width(String text, int scale)         // = width(text) * scale
 *    As Font.render without scale, but each glyph is drawn with drawPaletteTile(..., MIRROR_NONE, scale) and the
 *    cursor advances 8*scale per drawn/space character (unknown characters are still skipped). scale < 1 ->
 *    IllegalArgumentException, even for empty text. Extreme x must not throw or wrap.
 * 3. Scene (--scene / Scene.parse); a new option  scale=N  (N an integer >= 1; N < 1 or non-numeric, or the option
 *    given twice -> IllegalArgumentException / jar exit code 2):
 *      tile:TX,TY@PX,PY[:colours=..][:mirror=M][:scale=N]    one tile drawn at scale N at (PX,PY)
 *      fill:TX,TY[:colours=..][:mirror=M][:scale=N]          the scaled tile repeated every 8*N pixels in x and y
 *                                                            from (0,0), covering the screen (edges clipped)
 *      text:X,Y[:colours=C0,C1,C2,C3][:scale=N]:TEXT         options (in this order, each optional) come BEFORE the text,
 *                                                            like colours=; the text is everything after them
 *    Frame cleared to black first, as before. The 4th-option order for tile:/fill: is free (colours, mirror, scale in
 *    any order are not required; this test uses colours, mirror, scale).
 *
 * Expected pixels come from res/sprite_sheet.png (index = blue >> 6), the palette rule (index r*36+g*6+b, channel =
 * level*51) and the rules above (font: row 30 = A..Z, row 31 = 0..9), never from engine output.
 */
public class Ep10Test {
    private static final Path JAR = Paths.get("build/game.jar");
    private static final int T = Palette.TRANSPARENT;
    private static final int W = Game.WIDTH, H = Game.HEIGHT;
    private static final int SENT = 0x123456;
    private static BufferedImage img;
    private static SpriteSheet sheet;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = Ep10Test.class.getResourceAsStream("/sprite_sheet.png")) {
            img = ImageIO.read(in);
        }
        sheet = SpriteSheet.load("/sprite_sheet.png");
    }

    // ---- independent model ------------------------------------------------------------------------

    private static int ruleRgb(int index) {
        return ((index / 36) * 51) << 16 | (((index / 6) % 6) * 51) << 8 | ((index % 6) * 51);
    }

    private static int slot(int tx, int ty, int sx, int sy) {
        return (img.getRGB(tx * 8 + sx, ty * 8 + sy) & 0xff) >> 6;
    }

    /** Draws the scaled tile into base (rgb values or T); long arithmetic, per destination pixel. */
    private static void model(int[] base, int tx, int ty, long x, long y, int[] rgb, int mirror, long scale) {
        long x0 = Math.max(0, x), x1 = Math.min(W, x + 8 * scale);
        long y0 = Math.max(0, y), y1 = Math.min(H, y + 8 * scale);
        for (long sy = y0; sy < y1; sy++) {
            for (long sx = x0; sx < x1; sx++) {
                int u = (int) ((sx - x) / scale), v = (int) ((sy - y) / scale);
                if ((mirror & 1) != 0) u = 7 - u;
                if ((mirror & 2) != 0) v = 7 - v;
                int c = rgb[slot(tx, ty, u, v)];
                if (c != T) base[(int) (sy * W + sx)] = c;
            }
        }
    }

    private static int[] resolve(int[] palette) {
        int[] r = new int[4];
        for (int i = 0; i < 4; i++) r[i] = palette[i] == T ? T : ruleRgb(palette[i]);
        return r;
    }

    private static int[] sentinel() {
        int[] b = new int[W * H];
        Arrays.fill(b, SENT);
        return b;
    }

    private static Screen screen() {
        Screen s = new Screen(W, H);
        s.clear(SENT);
        return s;
    }

    private static final int[] PAL = {T, 36 * 5 + 6 * 1 + 0, 6 * 5 + 5, 36 * 3 + 6 * 4 + 5}; // one transparent slot
    private static final int[] PAL2 = {0, 215, 36 * 5, 5};

    private static int glyphTx(char c) {
        return c >= 'A' && c <= 'Z' ? c - 'A' : c - '0';
    }

    private static int glyphTy(char c) {
        return c >= 'A' && c <= 'Z' ? 30 : 31;
    }

    private static void modelText(int[] base, String text, long x, long y, int[] palette, int scale) {
        int[] rgb = resolve(palette);
        long cur = x;
        for (char c : text.toCharArray()) {
            if (c != ' ') model(base, glyphTx(c), glyphTy(c), cur, y, rgb, 0, scale);
            cur += 8L * scale;
        }
    }

    // ---- tile scaling -----------------------------------------------------------------------------

    @Test
    void scaleOneEqualsExistingOverloads() {
        for (int m = 0; m < 4; m++) {
            Screen a = screen(), b = screen();
            a.drawPaletteTile(sheet, 1, 0, 13, 7, PAL, m);
            b.drawPaletteTile(sheet, 1, 0, 13, 7, PAL, m, 1);
            assertArrayEquals(a.pixels, b.pixels, "palette mirror " + m);
            Screen c = screen(), d = screen();
            c.drawTile(sheet, 2, 0, -3, 5, new int[] {0x111111, 0x222222, 0x333333, 0x444444}, m);
            d.drawTile(sheet, 2, 0, -3, 5, new int[] {0x111111, 0x222222, 0x333333, 0x444444}, m, 1);
            assertArrayEquals(c.pixels, d.pixels, "rgb mirror " + m);
        }
    }

    @Test
    void scaledPaletteTilesMatchModel() {
        int[][] tiles = {{0, 5}, {1, 0}, {2, 0}, {0, 30}, {3, 30}};
        int[][] pos = {{10, 10}, {0, 0}, {-5, -11}, {-23, 3}, {150, 100}, {W - 1, H - 1}, {-1, -1}, {W, H}, {-200, 4}, {159, -300}};
        for (int scale = 1; scale <= 5; scale++) {
            for (int m = 0; m < 4; m++) {
                for (int[] t : tiles) {
                    for (int[] p : pos) {
                        Screen s = screen();
                        s.drawPaletteTile(sheet, t[0], t[1], p[0], p[1], PAL, m, scale);
                        int[] exp = sentinel();
                        model(exp, t[0], t[1], p[0], p[1], resolve(PAL), m, scale);
                        assertArrayEquals(exp, s.pixels, "tile " + Arrays.toString(t) + " at " + Arrays.toString(p)
                                + " scale " + scale + " mirror " + m);
                    }
                }
            }
        }
    }

    @Test
    void scaledRgbTileMatchesModelAndOpaqueBlocksAreSolid() {
        int[] rgb = {0x0A0B0C, 0x102030, 0xAA5500, 0xFFFFFF};
        for (int scale : new int[] {2, 3, 7}) {
            Screen s = screen();
            s.drawTile(sheet, 1, 0, 20, 30, rgb, Screen.MIRROR_X | Screen.MIRROR_Y, scale);
            int[] exp = sentinel();
            model(exp, 1, 0, 20, 30, rgb, 3, scale);
            assertArrayEquals(exp, s.pixels, "scale " + scale);
            // each sheet pixel is a solid scale x scale block
            for (int v = 0; v < 8; v++) for (int u = 0; u < 8; u++) {
                int first = s.pixels[(30 + v * scale) * W + 20 + u * scale];
                for (int j = 0; j < scale; j++) for (int i = 0; i < scale; i++)
                    assertEquals(first, s.pixels[(30 + v * scale + j) * W + 20 + u * scale + i]);
            }
        }
    }

    @Test
    void transparencyWithScaleLeavesBackground() {
        Screen s = screen();
        s.drawPaletteTile(sheet, 1, 0, 40, 40, new int[] {T, T, T, T}, 0, 4);
        assertArrayEquals(sentinel(), s.pixels);
        s.drawPaletteTile(sheet, 1, 0, 40, 40, new int[] {T, 215, T, T}, 0, 3);
        int[] exp = sentinel();
        model(exp, 1, 0, 40, 40, new int[] {T, 0xFFFFFF, T, T}, 0, 3);
        assertArrayEquals(exp, s.pixels);
        assertNotEquals(0xFFFFFF, s.pixels[0]);
    }

    @Test
    void extremePositionsAndScalesDoNotThrowOrWrap() {
        int[] xs = {Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -1_000_000, Integer.MAX_VALUE, Integer.MAX_VALUE - 3, 1_000_000};
        int[] scales = {1, 2, 1000, 1 << 28, Integer.MAX_VALUE};
        for (int scale : scales) {
            for (int x : xs) {
                for (int y : new int[] {Integer.MIN_VALUE, 0, 50, Integer.MAX_VALUE}) {
                    Screen s = screen();
                    s.drawPaletteTile(sheet, 1, 0, x, y, PAL2, 1, scale);
                    int[] exp = sentinel();
                    model(exp, 1, 0, x, y, resolve(PAL2), 1, scale);
                    assertArrayEquals(exp, s.pixels, "x=" + x + " y=" + y + " scale=" + scale);
                }
            }
        }
        // a huge block that covers the screen shows only the (mirrored) top-left source pixel
        Screen s = screen();
        s.drawPaletteTile(sheet, 1, 0, 0, 0, PAL2, 0, Integer.MAX_VALUE);
        int[] exp = sentinel();
        model(exp, 1, 0, 0, 0, resolve(PAL2), 0, Integer.MAX_VALUE);
        assertArrayEquals(exp, s.pixels);
        assertEquals(1, new java.util.HashSet<>(Arrays.stream(s.pixels).boxed().toList()).size());
    }

    @Test
    void badScaleRejectedEvenOffScreen() {
        for (int bad : new int[] {0, -1, -5, Integer.MIN_VALUE}) {
            Screen s = screen();
            assertThrows(IllegalArgumentException.class, () -> s.drawPaletteTile(sheet, 1, 0, 0, 0, PAL, 0, bad), "pal " + bad);
            assertThrows(IllegalArgumentException.class, () -> s.drawPaletteTile(sheet, 1, 0, 5000, 5000, PAL, 0, bad), "off " + bad);
            assertThrows(IllegalArgumentException.class, () -> s.drawTile(sheet, 1, 0, 0, 0, new int[] {1, 2, 3, 4}, 0, bad), "rgb " + bad);
            assertThrows(IllegalArgumentException.class, () -> Font.render(s, sheet, "A", 0, 0, PAL, bad), "font " + bad);
            assertThrows(IllegalArgumentException.class, () -> Font.render(s, sheet, "", 0, 0, PAL, bad), "empty " + bad);
            assertThrows(IllegalArgumentException.class, () -> Font.width("A", bad), "width " + bad);
            assertArrayEquals(sentinel(), s.pixels, "nothing drawn");
        }
        Screen s = screen();
        assertThrows(IllegalArgumentException.class, () -> s.drawPaletteTile(sheet, 1, 0, 0, 0, new int[] {1, 2, 3}, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> s.drawPaletteTile(sheet, 1, 0, 0, 0, PAL, 4, 2));
        assertThrows(IllegalArgumentException.class, () -> s.drawPaletteTile(sheet, 999, 0, 0, 0, PAL, 0, 2));
    }

    // ---- text scaling -----------------------------------------------------------------------------

    @Test
    void scaledTextMatchesModelAndAdvancesEightTimesScale() {
        assertEquals(40, Font.width("HELLO", 1));
        assertEquals(80, Font.width("HELLO", 2));
        assertEquals(Font.width("A B~1", 1) * 3, Font.width("A B~1", 3)); // unknown char skipped
        assertEquals(0, Font.width("", 4));
        String[] texts = {"HELLO", "Ab 12 z", "A~B", "", "   "};
        int[][] pos = {{0, 0}, {7, 9}, {-13, -5}, {-1000, 3}, {120, 110}, {159, 119}, {W, H}};
        for (int scale = 1; scale <= 4; scale++) {
            for (String t : texts) {
                for (int[] p : pos) {
                    Screen s = screen();
                    Font.render(s, sheet, t, p[0], p[1], PAL, scale);
                    int[] exp = sentinel();
                    modelText(exp, t.replace("~", "").replace("a", "A").replace("b", "B").replace("z", "Z"), p[0], p[1], PAL, scale);
                    assertArrayEquals(exp, s.pixels, "'" + t + "' at " + Arrays.toString(p) + " scale " + scale);
                }
            }
        }
        Screen a = screen(), b = screen();
        Font.render(a, sheet, "HELLO", 3, 4, PAL, 1);
        Font.render(b, sheet, "HELLO", 3, 4, PAL);
        assertArrayEquals(b.pixels, a.pixels);
    }

    @Test
    void scaledTextExtremeXDoesNotThrowOrWrap() {
        for (int scale : new int[] {1, 3, 1 << 20}) {
            for (int x : new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE - 9, -1_000_000_000}) {
                Screen s = screen();
                Font.render(s, sheet, "HELLO WORLD", x, 2, PAL2, scale);
                int[] exp = sentinel();
                modelText(exp, "HELLO WORLD", x, 2, PAL2, scale);
                assertArrayEquals(exp, s.pixels, "x=" + x + " scale " + scale);
            }
        }
    }

    // ---- scenes -----------------------------------------------------------------------------------

    private static String colourSpec(int[] pal) {
        StringBuilder sb = new StringBuilder("colours=");
        for (int i = 0; i < 4; i++) {
            if (i > 0) sb.append(',');
            sb.append(pal[i] == T ? "t" : "" + pal[i] / 36 + (pal[i] / 6) % 6 + pal[i] % 6);
        }
        return sb.toString();
    }

    private static int[] blackBase() {
        return new int[W * H];
    }

    private static int[] x2TileModel() {
        int[] exp = blackBase();
        model(exp, 1, 0, 30, 20, resolve(PAL), 1, 2);
        return exp;
    }

    private static int[] x2HelloModel() {
        int[] exp = blackBase();
        modelText(exp, "HELLO", 12, 40, PAL2, 2);
        return exp;
    }

    @Test
    void scenesParseAndRenderInProcess() {
        String cs = colourSpec(PAL);
        Screen s = screen();
        Scene.parse("tile:1,0@30,20:" + cs + ":mirror=x:scale=2").render(s);
        assertArrayEquals(x2TileModel(), s.pixels);

        s = screen();
        Scene.parse("fill:2,0:" + cs + ":mirror=y:scale=3").render(s);
        int[] exp = blackBase();
        for (int py = 0; py < H; py += 24) for (int px = 0; px < W; px += 24) model(exp, 2, 0, px, py, resolve(PAL), 2, 3);
        assertArrayEquals(exp, s.pixels);

        s = screen();
        Scene.parse("text:12,40:" + colourSpec(PAL2) + ":scale=2:HELLO").render(s);
        assertArrayEquals(x2HelloModel(), s.pixels);

        s = screen();
        Scene.parse("text:5,5:scale=3:A:B").render(s); // default colours, text keeps its ':'
        Screen ref = screen();
        Scene.parse("text:5,5:A:B").render(ref);
        assertNotEquals(Arrays.toString(ref.pixels), Arrays.toString(s.pixels));

        // scale=1 is the unscaled scene
        Screen a = screen(), b = screen();
        Scene.parse("tile:1,0@3,4:" + cs + ":scale=1").render(a);
        Scene.parse("tile:1,0@3,4:" + cs).render(b);
        assertArrayEquals(b.pixels, a.pixels);

        for (String bad : new String[] {"tile:1,0@3,4:" + cs + ":scale=0", "tile:1,0@3,4:" + cs + ":scale=-2",
                "tile:1,0@3,4:" + cs + ":scale=x", "tile:1,0@3,4:" + cs + ":scale=", "tile:1,0@3,4:scale=2:scale=3",
                "fill:1,0:scale=0", "text:1,1:scale=0:HI", "text:1,1:scale=abc:HI", "text:1,1:scale=2"})
            assertThrows(IllegalArgumentException.class, () -> Scene.parse(bad), bad);
    }

    @Test
    void jarDumpsX2TileAndX2Text(@TempDir Path dir) throws Exception {
        Path d1 = dir.resolve("tile");
        Run r = runJar("--headless", "--ticks", "2", "--dump-dir", d1.toString(), "--scene",
                "tile:1,0@30,20:" + colourSpec(PAL) + ":mirror=x:scale=2");
        assertEquals(0, r.exit, r.out);
        assertArrayEquals(x2TileModel(), readFrame(d1.resolve("frame-00000.png")));
        assertArrayEquals(x2TileModel(), readFrame(d1.resolve("frame-00001.png")));

        Path d2 = dir.resolve("text");
        r = runJar("--headless", "--ticks", "1", "--dump-dir", d2.toString(), "--scene",
                "text:12,40:" + colourSpec(PAL2) + ":scale=2:HELLO");
        assertEquals(0, r.exit, r.out);
        assertArrayEquals(x2HelloModel(), readFrame(d2.resolve("frame-00000.png")));

        r = runJar("--headless", "--ticks", "1", "--dump-dir", dir.resolve("bad").toString(), "--scene", "tile:1,0@3,4:scale=0");
        assertEquals(2, r.exit, r.out);
        assertFalse(r.out.isBlank());
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
        BufferedImage im = ImageIO.read(f.toFile());
        assertEquals(W, im.getWidth());
        assertEquals(H, im.getHeight());
        int[] px = new int[W * H];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) px[y * W + x] = im.getRGB(x, y) & 0xFFFFFF;
        return px;
    }
}

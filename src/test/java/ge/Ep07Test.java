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
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/*
 * Episode 7 check: "Rendering some Fonts".
 * SPEC: text renders from the font rows, case-insensitive; "HELLO 123!" dumps correctly; unknown
 * characters are skipped.
 *
 * GLYPH LAYOUT (inspected in res/sprite_sheet.png; also asserted below)
 * ---------------------------------------------------------------------
 *   row 30: 'A'..'Z' in columns 0..25.
 *   row 31: '0'..'9' in columns 0..9, then the 16 punctuation marks  . , : ; ' " ! ? $ % ( ) - = + /
 *           in columns 10..25 (in exactly that order).
 *   columns 26..31 of rows 30 and 31 are blank tiles (every pixel index 0): NOT glyphs.
 *   All 52 glyph tiles are pairwise different. A glyph is drawn with index 3 = ink and index 0 = its
 *   background (indexes 1 and 2 never occur in the font rows).
 *
 * CONTRACT the builder must satisfy
 * ---------------------------------
 * 1. New class ge.gfx.Font (final, static members only):
 *      public static final int ADVANCE = 8;     // pixels the cursor moves per character
 *      public static void render(Screen screen, SpriteSheet sheet, String text, int x, int y, int[] colours)
 *      public static int width(String text)     // 8 * the number of characters that are not skipped
 *                                               // (a space counts, a skipped character does not)
 *    render draws the text with its top-left at pixel (x,y), one 8x8 glyph tile per character, left to
 *    right, cursor starting at x and moving ADVANCE (8) px per character that is not skipped. Each glyph
 *    is drawn exactly as Screen.drawPaletteTile(sheet, tx, ty, cursor, y, colours) would draw it, so
 *    colours is a Palette-index int[4] (index i colours sheet index i; Palette.TRANSPARENT leaves the
 *    screen untouched for that slot; with the usual font tiles slot 0 is the background, slot 3 the ink).
 *    Mirroring is not part of this episode.
 *    - Case-insensitive, ASCII only: 'a'..'z' draw the same glyph as 'A'..'Z'. Nothing else is folded:
 *      U+0131 (dotless i) and U+017F (long s) and U+212A (Kelvin sign) are NOT letters here.
 *    - Space (' ') is a known character: it advances the cursor by 8 and draws NOTHING (not even the
 *      background colour, whatever colours[0] is).
 *    - Any other character is UNKNOWN and is skipped: it draws nothing AND does not advance the cursor,
 *      i.e. the text renders exactly as if the character were deleted from the string ("A@B" == "AB").
 *      (Reading of SPEC "unknown characters are skipped": skipped = left out, like a character that is
 *      not in the string. This includes '\n', '\t', '_', '[', '@', '#', accented letters and every
 *      char of a surrogate pair.) No line wrapping or line breaks: text runs off the right edge and is
 *      clipped like any tile.
 *    - Clipping: as drawPaletteTile; (x,y) any int; glyphs entirely or partly off any edge are clipped
 *      silently, later glyphs of a text starting off the left edge still show. Cursor arithmetic must not
 *      overflow int (x near Integer.MAX_VALUE must not wrap to the left of the screen).
 *    - Arguments are validated up front, whatever the text (also for "" and for text with only skipped
 *      characters): IllegalArgumentException if screen, sheet, text or colours is null, colours is not 4
 *      long, or an entry is below -1 or above 215.
 *    - Empty text draws nothing.
 *
 * 2. HEADLESS SCENE (Scene.parse and --scene):
 *      --scene text:X,Y[:colours=C0,C1,C2,C3]:TEXT
 *    X,Y are ints (negative allowed). The colours option (same syntax as the tile scenes: three digits
 *    0..5 or t) is optional. TEXT is the LAST field and is everything after the delimiter, verbatim, so
 *    it may itself contain ':' (e.g. "text:0,0:A:B" draws the three glyphs A : B) and may be empty
 *    ("text:0,0:" renders a plain black frame). The colours option is recognised only when the part after
 *    "X,Y:" starts with "colours=" (to draw text that itself starts with "colours=", give an explicit
 *    colours option first). Without colours= the default is {t,t,t,555}: white ink, transparent
 *    background, drawn over the scene's black clear. The frame is cleared to black each frame, so every
 *    frame of the run is identical.
 *    Malformed values exit 2 with a message on stderr: no text field ("text:0,0"), non-numeric or missing
 *    or extra coordinates, colours with the wrong count / a bad token / a bad level, a colours= option
 *    whose value is malformed (the part after X,Y: starts with "colours=" so it is parsed as the option).
 *    The existing tile: and fill: scenes are unchanged.
 *
 * Expected pixels are computed here from res/sprite_sheet.png (index = blue >> 6, as in episode 2), the
 * layout above and the palette rule (index r*36+g*6+b, channel = level*51), never from engine output.
 */
public class Ep07Test {

    private static final Path JAR = Paths.get("build/game.jar");
    private static final int T = Palette.TRANSPARENT;
    private static final String PUNCT = ".,:;'\"!?$%()-=+/";
    private static final int[] OPAQUE = {Palette.index(5, 0, 0), Palette.index(0, 5, 0), Palette.index(0, 0, 5), Palette.index(5, 5, 0)};
    private static final int[] DEFAULT = {T, T, T, Palette.index(5, 5, 5)};
    private static final String ALL = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789" + PUNCT;

    private static BufferedImage sheetImage;
    private static SpriteSheet sheet;

    @BeforeAll
    static void loadSheet() throws Exception {
        try (InputStream in = Ep07Test.class.getResourceAsStream("/sprite_sheet.png")) {
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

    /** Tile (col,row) of a character, or null if it is not a glyph (space and unknown included). Layout from the SPEC. */
    private static int[] glyph(int c) {
        if (c >= 'a' && c <= 'z') c = c - 'a' + 'A';
        if (c >= 'A' && c <= 'Z') return new int[] {c - 'A', 30};
        if (c >= '0' && c <= '9') return new int[] {c - '0', 31};
        int p = c < 128 ? PUNCT.indexOf(c) : -1;
        if (p >= 0) return new int[] {10 + p, 31};
        return null;
    }

    /** Characters (UTF-16 chars) that are not skipped: glyphs and space. */
    private static boolean known(char c) {
        return c == ' ' || glyph(c) != null;
    }

    private static String stripUnknown(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) if (known(c)) b.append(c);
        return b.toString();
    }

    /** Expected screen after rendering text at (x,y) with palette colours, over base. */
    private static int[] expected(int[] base, String text, int x, int y, int[] colours) {
        int w = Game.WIDTH, h = Game.HEIGHT;
        int[] out = base.clone();
        long cursor = x;
        for (char ch : text.toCharArray()) {
            if (!known(ch)) continue;
            int[] g = glyph(ch);
            if (g != null) {
                for (int dy = 0; dy < 8; dy++) {
                    for (int dx = 0; dx < 8; dx++) {
                        long sx = cursor + dx, sy = (long) y + dy;
                        if (sx < 0 || sy < 0 || sx >= w || sy >= h) continue;
                        int c = colours[idx(g[0] * 8 + dx, g[1] * 8 + dy)];
                        if (c == T) continue;
                        out[(int) (sy * w + sx)] = ruleRgb(c);
                    }
                }
            }
            cursor += 8;
        }
        return out;
    }

    private static Screen blank() {
        return new Screen(Game.WIDTH, Game.HEIGHT);
    }

    private static Screen noisy() {
        Screen s = new Screen(Game.WIDTH, Game.HEIGHT);
        for (int i = 0; i < s.pixels.length; i++) s.pixels[i] = (int) (((i + 1) * 2654435761L) & 0xFFFFFF);
        return s;
    }

    private static int[] render(String text, int x, int y, int[] colours) {
        Screen s = noisy();
        Font.render(s, sheet, text, x, y, colours);
        return s.pixels;
    }

    // ---- the premise: glyph layout of the sheet ------------------------------------------------

    @Test
    void fontRowsHaveTheAssumedLayout() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < ALL.length(); i++) {
            int[] g = glyph(ALL.charAt(i));
            assertNotNull(g, "glyph for " + ALL.charAt(i));
            int ink = 0, other = 0;
            StringBuilder sig = new StringBuilder();
            for (int v = 0; v < 8; v++)
                for (int u = 0; u < 8; u++) {
                    int k = idx(g[0] * 8 + u, g[1] * 8 + v);
                    if (k == 3) ink++;
                    else if (k != 0) other++;
                    sig.append(k);
                }
            assertTrue(ink > 0, "'" + ALL.charAt(i) + "' has ink");
            assertEquals(0, other, "'" + ALL.charAt(i) + "' uses only indexes 0 and 3");
            assertTrue(seen.add(sig.toString()), "'" + ALL.charAt(i) + "' differs from every other glyph");
        }
        assertEquals(52, seen.size());
        for (int row = 30; row < 32; row++)
            for (int col = 26; col < 32; col++)
                for (int v = 0; v < 8; v++)
                    for (int u = 0; u < 8; u++) assertEquals(0, idx(col * 8 + u, row * 8 + v), "blank tile " + col + "," + row);
    }

    // ---- every character maps to the right tile --------------------------------------------------

    @Test
    void everyGlyphCharacterDrawsItsTile() {
        for (int i = 0; i < ALL.length(); i++) {
            String t = String.valueOf(ALL.charAt(i));
            assertArrayEquals(expected(noisy().pixels, t, 21, 33, OPAQUE), render(t, 21, 33, OPAQUE), "char '" + t + "'");
        }
    }

    @Test
    void glyphsDrawBackgroundAndInkFromSlotsZeroAndThree() {
        Screen s = blank();
        Font.render(s, sheet, "A", 10, 10, OPAQUE);
        int bg = 0, ink = 0;
        for (int v = 0; v < 8; v++)
            for (int u = 0; u < 8; u++) {
                int rgb = s.pixels[(10 + v) * Game.WIDTH + 10 + u];
                if (idx(u, 30 * 8 + v) == 3) { assertEquals(ruleRgb(OPAQUE[3]), rgb); ink++; }
                else { assertEquals(ruleRgb(OPAQUE[0]), rgb); bg++; }
            }
        assertTrue(ink > 0 && bg > 0);
    }

    @Test
    void lowerCaseDrawsTheSameAsUpperCase() {
        for (char c = 'a'; c <= 'z'; c++) {
            assertArrayEquals(render(String.valueOf((char) (c - 32)), 30, 30, OPAQUE), render(String.valueOf(c), 30, 30, OPAQUE), "letter " + c);
        }
        assertArrayEquals(render("HELLO 123!", 8, 16, OPAQUE), render("hello 123!", 8, 16, OPAQUE));
        assertArrayEquals(render("HELLO 123!", 8, 16, OPAQUE), render("HeLlO 123!", 8, 16, OPAQUE));
    }

    @Test
    void helloOneTwoThreeBangRendersCorrectly() {
        int[] want = expected(noisy().pixels, "HELLO 123!", 8, 16, DEFAULT);
        assertArrayEquals(want, render("HELLO 123!", 8, 16, DEFAULT));
        assertArrayEquals(want, render("hello 123!", 8, 16, DEFAULT));
        // explicit anchors independent of the helper: H at x=8, E at 16, space block 48..55 untouched, '1' at 56, '!' at 80
        int[] base = noisy().pixels;
        int[] got = render("HELLO 123!", 8, 16, OPAQUE);
        for (int v = 0; v < 8; v++) {
            for (int u = 0; u < 8; u++) {
                assertEquals(ruleRgb(OPAQUE[idx(7 * 8 + u, 30 * 8 + v) == 3 ? 3 : 0]), got[(16 + v) * Game.WIDTH + 8 + u], "H");
                assertEquals(base[(16 + v) * Game.WIDTH + 48 + u], got[(16 + v) * Game.WIDTH + 48 + u], "space block untouched");
                assertEquals(ruleRgb(OPAQUE[idx(1 * 8 + u, 31 * 8 + v) == 3 ? 3 : 0]), got[(16 + v) * Game.WIDTH + 56 + u], "1");
            }
        }
        // nothing outside the 80 px wide, 8 px tall text box changed
        for (int py = 0; py < Game.HEIGHT; py++)
            for (int px = 0; px < Game.WIDTH; px++)
                if (py < 16 || py >= 24 || px < 8 || px >= 88)
                    assertEquals(base[py * Game.WIDTH + px], got[py * Game.WIDTH + px], "outside box at " + px + "," + py);
    }

    // ---- space and unknown characters ------------------------------------------------------------

    @Test
    void spaceAdvancesEightPixelsAndDrawsNothing() {
        assertArrayEquals(noisy().pixels, render(" ", 20, 20, OPAQUE));
        assertArrayEquals(noisy().pixels, render("     ", 20, 20, OPAQUE));
        assertArrayEquals(expected(noisy().pixels, "A B", 5, 40, OPAQUE), render("A B", 5, 40, OPAQUE));
        // "A B" is A at x and B at x+16: same as drawing them separately
        Screen s = noisy();
        Font.render(s, sheet, "A", 5, 40, OPAQUE);
        Font.render(s, sheet, "B", 21, 40, OPAQUE);
        assertArrayEquals(s.pixels, render("A B", 5, 40, OPAQUE));
        assertFalse(java.util.Arrays.equals(render("AB", 5, 40, OPAQUE), render("A B", 5, 40, OPAQUE)), "a space must move the next glyph");
    }

    @Test
    void unknownCharactersAreSkippedWithoutAdvancing() {
        String[] unknown = {"@", "#", "é", "É", "\n", "\t", "\r", "_", "[", "]", "{", "}", "|", "\\", "`", "~", "^", "&", "*", "<", ">",
                "\u0000", "\u007f", "ß", "ı", "ſ", "K", "中", "😀", " "};
        for (String u : unknown) {
            String a = "A" + u + "B", b = "AB";
            assertArrayEquals(render(b, 12, 12, OPAQUE), render(a, 12, 12, OPAQUE), "unknown char U+" + Integer.toHexString(u.codePointAt(0)) + " must leave no gap and no mark");
            assertArrayEquals(noisy().pixels, render(u, 12, 12, OPAQUE), "lone unknown draws nothing");
            assertArrayEquals(noisy().pixels, render(u + u + u, 12, 12, OPAQUE));
        }
        assertArrayEquals(render("HELLO 123!", 8, 16, OPAQUE), render("H@E#LéL\nO\t 1_2[3]!", 8, 16, OPAQUE));
        // an unknown character between two spaces: exactly the two spaces advance
        assertArrayEquals(render("A  B", 8, 16, OPAQUE), render("A @ B", 8, 16, OPAQUE));
    }

    @Test
    void caseFoldingIsAsciiOnly() {
        // U+0131 would upper-case to 'I', U+017F to 'S', U+212A lower-cases to 'k': none of them may draw a letter
        for (String s : new String[] {"ı", "ſ", "K"})
            assertArrayEquals(noisy().pixels, render(s, 30, 30, OPAQUE), "U+" + Integer.toHexString(s.charAt(0)));
    }

    @Test
    void widthCountsKnownCharactersOnly() {
        assertEquals(0, Font.width(""));
        assertEquals(8, Font.width("A"));
        assertEquals(80, Font.width("HELLO 123!"));
        assertEquals(80, Font.width("hello 123!"));
        assertEquals(16, Font.width(" ."));
        assertEquals(0, Font.width("@#\n\té"));
        assertEquals(16, Font.width("A@B\n"));
        assertEquals(8, Font.ADVANCE);
        // the rendered text spans exactly width() pixels
        String t = "Hi, there: $5.00"; // 16 chars, fits the 160 px screen
        Screen s = blank();
        Font.render(s, sheet, t, 4, 4, OPAQUE);
        int maxX = -1;
        for (int py = 0; py < Game.HEIGHT; py++) for (int px = 0; px < Game.WIDTH; px++) if (s.pixels[py * Game.WIDTH + px] != 0) maxX = Math.max(maxX, px);
        assertTrue(maxX < 4 + Font.width(t) && maxX >= 4 + Font.width(t) - 8, "last drawn column " + maxX);
    }

    // ---- punctuation and a long mixed string -----------------------------------------------------

    @Test
    void punctuationRowRendersInOrder() {
        assertArrayEquals(expected(noisy().pixels, PUNCT, 2, 50, OPAQUE), render(PUNCT, 2, 50, OPAQUE));
        String all = ALL.substring(0, 19);
        assertArrayEquals(expected(noisy().pixels, all, 0, 0, DEFAULT), render(all, 0, 0, DEFAULT));
        String rest = ALL.substring(19);
        assertArrayEquals(expected(noisy().pixels, rest, 0, 64, DEFAULT), render(rest, 0, 64, DEFAULT));
    }

    // ---- colours and transparency -----------------------------------------------------------------

    @Test
    void transparentSlotsLeaveTheScreenUntouched() {
        for (int slot = 0; slot < 4; slot++) {
            int[] pal = OPAQUE.clone();
            pal[slot] = T;
            assertArrayEquals(expected(noisy().pixels, "Tx-1?", 33, 71, pal), render("Tx-1?", 33, 71, pal), "slot " + slot);
        }
        assertArrayEquals(noisy().pixels, render("HELLO", 10, 10, new int[] {T, T, T, T}));
        // transparent background: text over an earlier drawing shows it through
        Screen s = blank();
        s.clear(0x123456);
        Font.render(s, sheet, "HELLO", 10, 10, new int[] {T, T, T, Palette.index(5, 5, 5)});
        for (int v = 0; v < 8; v++) assertEquals(0x123456, s.pixels[(10 + v) * Game.WIDTH + 10], "column 0 of H stays background");
    }

    // ---- clipping ---------------------------------------------------------------------------------

    @Test
    void textIsClippedAtEveryEdge() {
        int W = Game.WIDTH, H = Game.HEIGHT;
        String text = "HELLO 123!";
        int[] xs = {-100, -80, -79, -41, -20, -9, -8, -1, 0, 3, W - 80, W - 41, W - 9, W - 8, W - 1, W, W + 5, 1000};
        int[] ys = {-9, -8, -7, -1, 0, 3, H - 8, H - 7, H - 1, H, H + 5};
        for (int x : xs)
            for (int y : ys)
                assertArrayEquals(expected(noisy().pixels, text, x, y, OPAQUE), render(text, x, y, OPAQUE), "at " + x + "," + y);
    }

    @Test
    void laterGlyphsOfTextStartingOffTheLeftEdgeStillShow() {
        // x=-20: A and B (at -20, -12) are off-screen, C is partly visible (columns -4..3 -> 0..3), D fully
        int[] got = render("ABCD", -20, 10, OPAQUE);
        assertArrayEquals(expected(noisy().pixels, "ABCD", -20, 10, OPAQUE), got);
        assertFalse(java.util.Arrays.equals(noisy().pixels, got), "something must be visible");
    }

    @Test
    void longTextRunsOffTheRightEdgeWithoutWrapping() {
        String text = "THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG 0123456789";
        assertArrayEquals(expected(noisy().pixels, text, 100, 50, OPAQUE), render(text, 100, 50, OPAQUE));
        // the row below the text box is untouched
        int[] base = noisy().pixels, got = render(text, 100, 50, OPAQUE);
        for (int px = 0; px < Game.WIDTH; px++) {
            assertEquals(base[58 * Game.WIDTH + px], got[58 * Game.WIDTH + px], "row 58");
            assertEquals(base[49 * Game.WIDTH + px], got[49 * Game.WIDTH + px], "row 49");
        }
    }

    @Test
    void extremePositionsDoNotWrapAround() {
        int[] xs = {Integer.MAX_VALUE, Integer.MAX_VALUE - 3, Integer.MAX_VALUE - 7, Integer.MAX_VALUE - 15, Integer.MIN_VALUE, Integer.MIN_VALUE + 3};
        int[] ys = {5, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE - 3};
        for (int x : xs)
            for (int y : ys) {
                int[] got = render("AAAA BBBB", x, y, OPAQUE);
                assertArrayEquals(noisy().pixels, got, "off-screen at " + x + "," + y + " must draw nothing");
            }
        // the long cursor sum overflows an int: 8 * k added to x near MAX must not reappear on the left
        assertArrayEquals(noisy().pixels, render("A".repeat(40), Integer.MAX_VALUE - 7, 5, OPAQUE));
    }

    @Test
    void emptyAndSkippedOnlyTextDrawsNothing() {
        assertArrayEquals(noisy().pixels, render("", 10, 10, OPAQUE));
        assertArrayEquals(noisy().pixels, render("@#\n", 10, 10, OPAQUE));
    }

    // ---- argument validation ------------------------------------------------------------------------

    @Test
    void badArgumentsAreRejected() {
        Screen s = blank();
        assertThrows(IllegalArgumentException.class, () -> Font.render(null, sheet, "A", 0, 0, OPAQUE));
        assertThrows(IllegalArgumentException.class, () -> Font.render(s, null, "A", 0, 0, OPAQUE));
        assertThrows(IllegalArgumentException.class, () -> Font.render(s, sheet, null, 0, 0, OPAQUE));
        assertThrows(IllegalArgumentException.class, () -> Font.render(s, sheet, "A", 0, 0, null));
        assertThrows(IllegalArgumentException.class, () -> Font.render(s, sheet, "A", 0, 0, new int[] {0, 0, 0}));
        assertThrows(IllegalArgumentException.class, () -> Font.render(s, sheet, "A", 0, 0, new int[] {0, 0, 0, 0, 0}));
        assertThrows(IllegalArgumentException.class, () -> Font.render(s, sheet, "A", 0, 0, new int[] {0, 0, 0, 216}));
        assertThrows(IllegalArgumentException.class, () -> Font.render(s, sheet, "A", 0, 0, new int[] {-2, 0, 0, 0}));
        // validated even when there is nothing to draw
        assertThrows(IllegalArgumentException.class, () -> Font.render(s, sheet, "", 0, 0, new int[] {0, 0, 0, 216}));
        assertThrows(IllegalArgumentException.class, () -> Font.render(s, sheet, "@", 0, 0, null));
        assertThrows(IllegalArgumentException.class, () -> Font.render(s, sheet, "A", 500, 500, new int[] {0, 0, 0, 216}));
        assertThrows(IllegalArgumentException.class, () -> Font.width(null));
        for (int v : s.pixels) assertEquals(0, v, "a rejected call draws nothing");
    }

    @Test
    void drawingDoesNotChangeTheColoursArray() {
        int[] pal = OPAQUE.clone();
        render("HELLO 123!", 5, 5, pal);
        assertArrayEquals(OPAQUE, pal);
    }

    // ---- frame dumps from the jar --------------------------------------------------------------------

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
            out[i] = t.equals("t") ? T : Palette.index(t.charAt(0) - '0', t.charAt(1) - '0', t.charAt(2) - '0');
        }
        return out;
    }

    /** Runs 2 ticks of the scene, returns the first and last frame (they must be identical). */
    private static int[] dumpScene(Path tmp, String scene) throws Exception {
        Path dir = tmp.resolve("d" + Math.abs(scene.hashCode()) + "_" + System.nanoTime());
        Run r = runJar("--headless", "--ticks", "2", "--dump-dir", dir.toString(), "--scene", scene);
        assertEquals(0, r.exit, scene + "\n" + r.out);
        int[] first = readFrame(dir.resolve("frame-00000.png"));
        int[] last = readFrame(dir.resolve("frame-00001.png"));
        assertArrayEquals(first, last, "every frame of a text scene is identical");
        return first;
    }

    private static int[] black() {
        return new int[Game.WIDTH * Game.HEIGHT];
    }

    @Test
    void dumpOfHelloOneTwoThreeBangMatches(@TempDir Path tmp) throws Exception {
        int[] want = expected(black(), "HELLO 123!", 8, 16, DEFAULT);
        assertArrayEquals(want, dumpScene(tmp, "text:8,16:HELLO 123!"));
        assertArrayEquals(want, dumpScene(tmp, "text:8,16:hello 123!"));
        assertTrue(java.util.Arrays.stream(want).anyMatch(p -> p == 0xFFFFFF), "white ink is visible");
    }

    @Test
    void dumpWithColoursMatches(@TempDir Path tmp) throws Exception {
        assertArrayEquals(expected(black(), "Hello 123!", 20, 70, tokens("t", "000", "000", "531")),
                dumpScene(tmp, "text:20,70:colours=t,000,000,531:Hello 123!"));
        // opaque background colour fills glyph backgrounds but not spaces
        assertArrayEquals(expected(black(), "A B", 4, 4, tokens("005", "000", "000", "550")),
                dumpScene(tmp, "text:4,4:colours=005,000,000,550:A B"));
    }

    @Test
    void dumpWithColonsAndPunctuationInTheText(@TempDir Path tmp) throws Exception {
        assertArrayEquals(expected(black(), "A:B", 0, 0, DEFAULT), dumpScene(tmp, "text:0,0:A:B"));
        assertArrayEquals(expected(black(), ":::", 0, 0, DEFAULT), dumpScene(tmp, "text:0,0::::"));
        String p = PUNCT;
        assertArrayEquals(expected(black(), p, 2, 100, DEFAULT), dumpScene(tmp, "text:2,100:" + p));
        assertArrayEquals(expected(black(), "A:B", 0, 0, tokens("t", "t", "t", "500")),
                dumpScene(tmp, "text:0,0:colours=t,t,t,500:A:B"));
    }

    @Test
    void dumpClipsAtTheEdgesAndAcceptsNegativePositions(@TempDir Path tmp) throws Exception {
        assertArrayEquals(expected(black(), "HELLO 123!", -20, -3, DEFAULT), dumpScene(tmp, "text:-20,-3:HELLO 123!"));
        assertArrayEquals(expected(black(), "HELLO 123!", 130, 114, DEFAULT), dumpScene(tmp, "text:130,114:HELLO 123!"));
        assertArrayEquals(black(), dumpScene(tmp, "text:500,500:HELLO"));
    }

    @Test
    void dumpSkipsUnknownCharacters(@TempDir Path tmp) throws Exception {
        assertArrayEquals(expected(black(), "HELLO 123!", 8, 16, DEFAULT), dumpScene(tmp, "text:8,16:H@E#L_L[O 1|2~3\\!"));
        assertArrayEquals(expected(black(), "AB", 8, 16, DEFAULT), dumpScene(tmp, "text:8,16:A\tB"));
        assertArrayEquals(expected(black(), "AB", 8, 16, DEFAULT), dumpScene(tmp, "text:8,16:A\nB"));
    }

    @Test
    void dumpOfEmptyTextIsABlackFrame(@TempDir Path tmp) throws Exception {
        assertArrayEquals(black(), dumpScene(tmp, "text:0,0:"));
        assertArrayEquals(black(), dumpScene(tmp, "text:0,0:colours=t,t,t,555:"));
        assertArrayEquals(black(), dumpScene(tmp, "text:0,0:   "));
    }

    @Test
    void sceneParseAcceptsTheSyntax() {
        assertDoesNotThrow(() -> Scene.parse("text:1,2:HELLO"));
        assertDoesNotThrow(() -> Scene.parse("text:-1,-2:colours=t,000,111,555:HELLO: world"));
        Screen s = blank();
        Scene.parse("text:8,16:HELLO 123!").render(s);
        assertArrayEquals(expected(black(), "HELLO 123!", 8, 16, DEFAULT), s.pixels);
    }

    @Test
    void malformedTextScenesExitTwo() throws Exception {
        String[] bad = {
            "text:0,0",                           // no text field
            "text:",
            "text",
            "text:a,b:HI",
            "text:0:HI",
            "text:0,0,0:HI",
            "text:,:HI",
            "text:1.5,2:HI",
            "text:0,0:colours=t,t,t:HI",         // 3 colours
            "text:0,0:colours=t,t,t,t,t:HI",     // 5 colours
            "text:0,0:colours=bad,t,t,t:HI",
            "text:0,0:colours=600,t,t,t:HI",
            "text:0,0:colours=t,t,t,56:HI",
            "text:0,0:colours=:HI",
            "text:0,0:colours=t,t,t,555",        // colours given but no text field
            "texts:0,0:HI",
            "Text:0,0:HI",
        };
        for (String s : bad) {
            Run r = runJar("--headless", "--ticks", "1", "--scene", s);
            assertEquals(2, r.exit, "scene '" + s + "' should be rejected; output:\n" + r.out);
        }
        for (String s : bad) {
            assertThrows(IllegalArgumentException.class, () -> Scene.parse(s), s);
        }
    }

    @Test
    void existingScenesStillWork(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("old");
        Run r = runJar("--headless", "--ticks", "1", "--dump-dir", dir.toString(), "--scene", "tile:0,30@10,10:colours=t,t,t,555");
        assertEquals(0, r.exit, r.out);
        int[] got = readFrame(dir.resolve("frame-00000.png"));
        assertArrayEquals(expected(black(), "A", 10, 10, DEFAULT), got);
    }
}

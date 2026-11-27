package ge;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;

import ge.gfx.SpriteSheet;

/*
 * Episode 2 check: "Creating a Sprite Sheet".
 * SPEC: the sprite sheet loads into an array of colour indexes 0 to 3; a test checks known pixels.
 *
 * CONTRACT the builder must satisfy
 * ---------------------------------
 * Class ge.gfx.SpriteSheet (public final class), package ge.gfx:
 *   public static SpriteSheet load(String resourcePath)
 *        loads a classpath resource such as "/sprite_sheet.png" (via the class loader, so it works
 *        from build/classes and from the jar). Throws an unchecked exception (or IOException wrapped
 *        unchecked) if the resource is missing; never returns null.
 *   public int width()           image width in pixels  (256 for /sprite_sheet.png)
 *   public int height()          image height in pixels (256 for /sprite_sheet.png)
 *   public int[] pixels()        row-major colour indexes, length width()*height(), element
 *                                at (x,y) is pixels()[y*width()+x]; each value is 0..3.
 *   public int index(int x, int y)   same value as pixels()[y*width()+x]
 *
 * GREY -> INDEX RULE (the 2-bit quantisation the engine uses)
 * -----------------------------------------------------------
 * index = (blue channel of the pixel, 0..255) / 64, i.e. blue >> 6.
 * The sheet is drawn in greys (so any channel would do for them), but it also contains non-grey
 * filler pixels (0x7f007f and 0xff00ff, the unused area), where the BLUE channel is the defined one:
 * 0x7f007f -> 1, 0xff00ff -> 3. Observed distinct ARGB values in res/sprite_sheet.png (alpha always ff):
 *   0x000000 -> 0   0x191919 -> 0   0x515151 -> 1   0x555555 -> 1   0x7f007f -> 1
 *   0xaaaaaa -> 2   0xadadad -> 2   0xffffff -> 3   0xff00ff -> 3
 * (0 = darkest ... 3 = brightest; the quarter boundaries are 64, 128, 192.)
 * Expected values are computed here independently with ImageIO; the hard-coded pixels below were
 * verified by inspecting the PNG.
 */
public class Ep02Test {

    private static final String PATH = "/sprite_sheet.png";

    private static BufferedImage png;
    private static SpriteSheet sheet;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = Ep02Test.class.getResourceAsStream(PATH)) {
            assertNotNull(in, "test setup: " + PATH + " must be on the classpath");
            png = ImageIO.read(in);
        }
        sheet = SpriteSheet.load(PATH);
    }

    private static int expected(int x, int y) {
        return (png.getRGB(x, y) & 0xff) >> 6;
    }

    @Test
    void dimensionsAre256x256() {
        assertNotNull(sheet);
        assertEquals(256, png.getWidth());
        assertEquals(256, png.getHeight());
        assertEquals(256, sheet.width());
        assertEquals(256, sheet.height());
        assertEquals(256 * 256, sheet.pixels().length);
    }

    @Test
    void everyValueIsAColourIndex0To3() {
        for (int i = 0; i < sheet.pixels().length; i++) {
            int v = sheet.pixels()[i];
            assertTrue(v >= 0 && v <= 3, "pixel " + i + " has index " + v);
        }
    }

    @Test
    void allFourIndexesAreUsed() {
        boolean[] seen = new boolean[4];
        for (int v : sheet.pixels()) seen[v] = true;
        for (int i = 0; i < 4; i++) assertTrue(seen[i], "index " + i + " never appears");
    }

    @Test
    void fullArrayMatchesIndependentlyComputedExpectation() {
        int[] p = sheet.pixels();
        for (int y = 0; y < 256; y++)
            for (int x = 0; x < 256; x++) {
                assertEquals(expected(x, y), p[y * 256 + x], "pixels() at (" + x + "," + y + ")");
                assertEquals(expected(x, y), sheet.index(x, y), "index() at (" + x + "," + y + ")");
            }
    }

    @Test
    void knownPixelsFromInspection() {
        // tile (0,0), 8x8 at pixel (0,0): all black -> 0
        assertEquals(0, sheet.index(0, 0));
        assertEquals(0, sheet.index(7, 7));
        // stone tile (1,0): pixels x 8..15, y 0..7 are all grey 0x55 -> 1
        assertEquals(1, sheet.index(8, 0));
        assertEquals(1, sheet.index(12, 4));
        assertEquals(1, sheet.index(15, 7));
        // grass tile (2,0): base 0x55 -> 1 with 0xaa speckles -> 2 at (18,2), (22,4), (19,6)
        assertEquals(1, sheet.index(16, 0));
        assertEquals(2, sheet.index(18, 2));
        assertEquals(2, sheet.index(22, 4));
        assertEquals(2, sheet.index(19, 6));
        assertEquals(1, sheet.index(19, 2));
        // tile (3,0): top row and left column 0x7f -> 1, interior 0xff -> 3
        assertEquals(1, sheet.index(24, 0));
        assertEquals(1, sheet.index(24, 5));
        assertEquals(3, sheet.index(25, 1));
        assertEquals(3, sheet.index(31, 7));
        // font glyph row 30 (y = 240..247), first glyph tile (0,30): rows are
        //   y=240: 00000000   y=241: 0 0 3 3 3 3 3 0   y=244: 0 3 3 3 3 3 3 3
        assertEquals(0, sheet.index(0, 240));
        assertEquals(0, sheet.index(1, 241));
        assertEquals(3, sheet.index(2, 241));
        assertEquals(3, sheet.index(6, 241));
        assertEquals(0, sheet.index(7, 241));
        assertEquals(3, sheet.index(1, 242));
        assertEquals(0, sheet.index(3, 242));
        assertEquals(3, sheet.index(7, 244));
        // second glyph tile (1,30): y=244: 0 3 3 3 3 3 3 0
        assertEquals(3, sheet.index(9, 244));
        assertEquals(0, sheet.index(15, 244));
    }

    @Test
    void missingResourceFailsLoudly() {
        assertThrows(RuntimeException.class, () -> SpriteSheet.load("/no_such_sheet.png"));
    }
}

package ge;

import static org.junit.jupiter.api.Assertions.*;

import ge.entity.Player;
import ge.gfx.SpriteSheet;
import ge.level.Level;
import ge.level.Tile;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/*
 * Episode 13 check: "Loading in Levels"
 * SPEC: levels load from the PNGs by pixel colour; both given levels load and render; an unknown colour becomes void.
 *
 * CONTRACT the builder must satisfy (earlier contracts stay; the default run and earlier scenes are unchanged)
 * -------------------------------------------------------------------------------------------------------------
 * 1. ge.level.Level gets `public static Level load(String resource)`:
 *      - resource starting with '/'  -> a classpath resource (e.g. "/levels/small_test_level.png",
 *        from res/levels/, which build.sh copies into the jar);
 *      - anything else               -> a file path on disk (relative to the working directory, or absolute).
 *      - the level is as wide/high as the image; cell (x,y) is Tile.byLevelColour(pixel(x,y) & 0xFFFFFF)
 *        (the SPEC mapping: 0x000000 void, 0x555555 stone, 0x00FF00 grass, 0x0000FF water; alpha ignored;
 *        any other colour, exact match only, is Tile.VOID).
 *      - missing resource/file, or something that is not a decodable image -> IllegalArgumentException
 *        (never null, never another exception type).
 *    The given assets: small_test_level.png 10x10 (grass+stone), water_test_level.png 64x64 (grass+water).
 * 2. Scene forms (documented in the Scene javadoc):
 *      level:png:RES@XOFF,YOFF       like level:gen: but the level is Level.load(RES); drawn at view offset (XOFF,YOFF)
 *      game:png:RES@SPAWNX,SPAWNY    like game:gen:/game:grass: (World with a player at the spawn) on Level.load(RES);
 *                                    the ":stone=..." option still parses after the spawn, as before.
 *    RES is whatever Level.load takes (starting '/' = classpath). A missing RES makes Scene.parse throw
 *    IllegalArgumentException (a bad --scene must fail at parse time, not mid-run).
 *    Existing level:gen / game:gen / game:grass behaviour is unchanged.
 */
public class Ep13Test {

    private static final Path JAR = Paths.get("build/game.jar");
    private static final int W = Game.WIDTH, H = Game.HEIGHT;
    private static final String SMALL = "/levels/small_test_level.png", WATER = "/levels/water_test_level.png";
    private static SpriteSheet sheet;

    @BeforeAll
    static void load() {
        sheet = SpriteSheet.load("/sprite_sheet.png");
    }

    // ---- independent expectation: read the PNG ourselves and apply the SPEC mapping --------------------

    private static BufferedImage png(String res) throws Exception {
        try (var in = Ep13Test.class.getResourceAsStream(res)) {
            assertNotNull(in, res + " must be on the classpath");
            return ImageIO.read(in);
        }
    }

    private static Tile specTile(int rgb) {
        return switch (rgb & 0xFFFFFF) {
            case 0x555555 -> Tile.STONE;
            case 0x00FF00 -> Tile.GRASS;
            case 0x0000FF -> Tile.WATER;
            default -> Tile.VOID; // 0x000000 and every unknown colour
        };
    }

    private static Tile[][] expected(BufferedImage img) {
        Tile[][] t = new Tile[img.getHeight()][img.getWidth()];
        for (int y = 0; y < img.getHeight(); y++)
            for (int x = 0; x < img.getWidth(); x++) t[y][x] = specTile(img.getRGB(x, y));
        return t;
    }

    private static Level modelLevel(Tile[][] t) {
        Level l = new Level(t[0].length, t.length);
        for (int y = 0; y < t.length; y++) for (int x = 0; x < t[0].length; x++) l.setTile(x, y, t[y][x]);
        return l;
    }

    private static void assertLevelMatches(Tile[][] exp, Level got, String what) {
        assertEquals(exp[0].length, got.width(), what + " width");
        assertEquals(exp.length, got.height(), what + " height");
        for (int y = 0; y < exp.length; y++)
            for (int x = 0; x < exp[0].length; x++)
                assertSame(exp[y][x], got.getTile(x, y), what + " cell " + x + "," + y);
        assertSame(Tile.VOID, got.getTile(-1, 0), "outside is void");
        assertSame(Tile.VOID, got.getTile(exp[0].length, exp.length - 1), "outside is void");
    }

    private static int count(Tile[][] t, Tile k) {
        int n = 0;
        for (Tile[] row : t) for (Tile x : row) if (x == k) n++;
        return n;
    }

    /** A cell with grass in its whole 3x3 neighbourhood (a safe, non-water, non-stone place for the player). */
    private static int[] grassCell(Tile[][] t) {
        for (int y = 2; y < t.length - 2; y++)
            for (int x = 2; x < t[0].length - 2; x++) {
                boolean ok = true;
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) ok &= t[y + dy][x + dx] == Tile.GRASS;
                if (ok) return new int[] {x, y};
            }
        return fail("no grass cell with grass all around");
    }

    private static int[] render(Level l, int xo, int yo) {
        Screen s = new Screen(W, H);
        s.clear(0);
        l.render(s, sheet, xo, yo);
        return s.pixels.clone();
    }

    private static int[] renderScene(String spec) {
        Screen s = new Screen(W, H);
        Scene.parse(spec).render(s);
        return s.pixels.clone();
    }

    // ---- the given levels load -----------------------------------------------------------------------

    @Test
    void givenAssetsAreWhatTheTestAssumes() throws Exception {
        BufferedImage a = png(SMALL), b = png(WATER);
        assertEquals(10, a.getWidth());
        assertEquals(10, a.getHeight());
        assertEquals(64, b.getWidth());
        assertEquals(64, b.getHeight());
        assertTrue(count(expected(a), Tile.STONE) > 0 && count(expected(a), Tile.GRASS) > 0);
        assertTrue(count(expected(b), Tile.WATER) > 0 && count(expected(b), Tile.GRASS) > 0);
    }

    @Test
    void smallLevelLoadsCellForCell() throws Exception {
        assertLevelMatches(expected(png(SMALL)), Level.load(SMALL), "small");
    }

    @Test
    void waterLevelLoadsCellForCell() throws Exception {
        assertLevelMatches(expected(png(WATER)), Level.load(WATER), "water");
    }

    @Test
    void fileSystemPathLoadsLikeClasspath(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("copy.png");
        try (var in = Ep13Test.class.getResourceAsStream(WATER)) {
            Files.copy(in, f);
        }
        assertLevelMatches(expected(png(WATER)), Level.load(f.toString()), "file");
    }

    @Test
    void missingOrBrokenResourceIsIllegalArgument(@TempDir Path dir) throws Exception {
        assertThrows(IllegalArgumentException.class, () -> Level.load("/levels/no_such_level.png"));
        assertThrows(IllegalArgumentException.class, () -> Level.load(dir.resolve("nope.png").toString()));
        Path junk = dir.resolve("junk.png");
        Files.writeString(junk, "this is not a png");
        assertThrows(IllegalArgumentException.class, () -> Level.load(junk.toString()));
        assertThrows(IllegalArgumentException.class, () -> Scene.parse("level:png:/levels/no_such_level.png@0,0"));
        assertThrows(IllegalArgumentException.class, () -> Scene.parse("game:png:/levels/no_such_level.png@0,0"));
    }

    // ---- unknown colours become void ---------------------------------------------------------------

    @Test
    void unknownColoursAreVoid(@TempDir Path dir) throws Exception {
        int[][] px = { // 5 x 3, row-major RGB
            {0x000000, 0x555555, 0x00FF00, 0x0000FF, 0xFF0000},
            {0x123456, 0x555556, 0x00FE00, 0x0000FE, 0xFFFFFF},
            {0x00FF00, 0xAAAAAA, 0x0000FF, 0x010101, 0x555555}};
        BufferedImage img = new BufferedImage(5, 3, BufferedImage.TYPE_INT_ARGB);
        Tile[][] exp = new Tile[3][5];
        for (int y = 0; y < 3; y++)
            for (int x = 0; x < 5; x++) {
                img.setRGB(x, y, 0xFF000000 | px[y][x]);
                exp[y][x] = specTile(px[y][x]);
            }
        // sanity on the test's own expectations: known colours map, the rest are void
        assertSame(Tile.STONE, exp[0][1]);
        assertSame(Tile.WATER, exp[0][3]);
        assertSame(Tile.VOID, exp[0][4]);
        assertSame(Tile.VOID, exp[1][0]);
        assertSame(Tile.VOID, exp[1][1], "0x555556 is not stone");
        Path f = dir.resolve("odd.png");
        ImageIO.write(img, "png", f.toFile());
        Level l = Level.load(f.toString());
        assertLevelMatches(exp, l, "odd");
        assertSame(Tile.VOID, l.getTile(4, 0), "0xFF0000 is void");
        assertSame(Tile.VOID, l.getTile(0, 1), "0x123456 is void");
        // and the same through a scene (file path form): void cells render exactly like the model
        assertArrayEquals(render(modelLevel(exp), 0, 0), renderScene("level:png:" + f + "@0,0"));
    }

    // ---- both levels render -------------------------------------------------------------------------

    @Test
    void levelScenesRenderLikeTheModel() throws Exception {
        Tile[][] a = expected(png(SMALL)), b = expected(png(WATER));
        for (int[] off : new int[][] {{0, 0}, {-30, -20}, {13, 7}}) {
            assertArrayEquals(render(modelLevel(a), off[0], off[1]),
                    renderScene("level:png:" + SMALL + "@" + off[0] + "," + off[1]), "small @" + off[0] + "," + off[1]);
        }
        for (int[] off : new int[][] {{0, 0}, {200, 150}, {-17, 330}, {410, 3}}) {
            assertArrayEquals(render(modelLevel(b), off[0], off[1]),
                    renderScene("level:png:" + WATER + "@" + off[0] + "," + off[1]), "water @" + off[0] + "," + off[1]);
        }
        // the two levels do not look alike (the render is not a constant)
        assertFalse(java.util.Arrays.equals(renderScene("level:png:" + SMALL + "@0,0"),
                renderScene("level:png:" + WATER + "@0,0")));
    }

    @Test
    void gameScenesPutThePlayerInTheLoadedLevel() throws Exception {
        for (String res : new String[] {SMALL, WATER}) {
            Tile[][] t = expected(png(res));
            int[] c = grassCell(t);
            int sx = c[0] * 8 - 4, sy = c[1] * 8 - 8; // feet box inside the grass cell
            String spec = "game:png:" + res + "@" + sx + "," + sy;
            Scene s = Scene.parse(spec);
            World w = s.newWorld();
            assertNotNull(w, res);
            assertLevelMatches(t, w.level(), res + " in world");
            assertEquals(sx, w.player().x());
            assertEquals(sy, w.player().y());
            Screen model = new Screen(W, H);
            model.clear(0);
            new World(modelLevel(t), new Player(sx, sy)).render(model, sheet);
            assertArrayEquals(model.pixels, renderScene(spec), res + " game still");
        }
    }

    @Test
    void stoneOptionStillParsesOnAPngGame() throws Exception {
        Tile[][] t = expected(png(WATER));
        int[] c = grassCell(t);
        String spec = "game:png:" + WATER + "@" + (c[0] * 8 - 4) + "," + (c[1] * 8 - 8) + ":stone=" + c[0] + "," + (c[1] + 1);
        World w = Scene.parse(spec).newWorld();
        assertSame(Tile.STONE, w.level().getTile(c[0], c[1] + 1));
        assertSame(t[c[1]][c[0] + 1], w.level().getTile(c[0] + 1, c[1]), "other cells untouched");
        assertSame(Tile.GRASS, Scene.parse("game:grass:5,5@0,0:stone=1,1").newWorld().level().getTile(0, 0));
    }

    // ---- black-box: one jar dump per given level ---------------------------------------------------

    @Test
    void jarDumpsOfBothLevels(@TempDir Path dir) throws Exception {
        Tile[][] a = expected(png(SMALL)), b = expected(png(WATER));
        // small level, viewed with the player standing on it (a game: scene), a few ticks, no input
        int[] c = grassCell(a);
        int sx = c[0] * 8 - 4, sy = c[1] * 8 - 8;
        Path d1 = dir.resolve("small");
        Run r1 = runJar("--headless", "--ticks", "3", "--dump-dir", d1.toString(), "--scene",
                "game:png:" + SMALL + "@" + sx + "," + sy);
        assertEquals(0, r1.exit, r1.out);
        Screen model = new Screen(W, H);
        model.clear(0);
        new World(modelLevel(a), new Player(sx, sy)).render(model, sheet);
        assertArrayEquals(model.pixels, readFrame(d1.resolve("frame-00000.png")), "small level, first frame");

        // water level, plain level scene
        Path d2 = dir.resolve("water");
        Run r2 = runJar("--headless", "--ticks", "2", "--dump-dir", d2.toString(), "--scene",
                "level:png:" + WATER + "@120,90");
        assertEquals(0, r2.exit, r2.out);
        assertArrayEquals(render(modelLevel(b), 120, 90), readFrame(d2.resolve("frame-00000.png")), "water level frame");

        // a bad resource fails fast with a non-zero exit
        Run r3 = runJar("--headless", "--ticks", "1", "--scene", "level:png:/levels/no_such_level.png@0,0");
        assertNotEquals(0, r3.exit, r3.out);
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

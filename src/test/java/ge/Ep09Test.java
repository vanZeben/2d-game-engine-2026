package ge;

import static org.junit.jupiter.api.Assertions.*;

import ge.entity.Mob;
import ge.entity.Player;
import ge.gfx.Palette;
import ge.gfx.SpriteSheet;
import ge.input.InputHandler;
import ge.level.Level;
import ge.level.Tile;
import java.awt.event.KeyEvent;
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
 * Episode 9 check: "Making our first mob, the Player!"
 * SPEC: a player entity on screen moves with input; the camera follows it.
 *
 * CONTRACT the builder must satisfy
 * ---------------------------------
 * 1. ge.entity.Mob  (abstract class; reusable by later network players)
 *      int x(); int y();                       // world pixel of the sprite's TOP-LEFT corner (may be negative)
 * 2. ge.entity.Player extends Mob  (final or not)
 *      Player(int x, int y)                    // spawn: sprite top-left at world pixel (x,y)
 *      static final int SIZE = 16;             // sprite is 2x2 tiles
 *      static final int SPEED = 1;             // pixels per tick per axis
 *      static final int[] COLOURS;             // 4 entries: Palette index (0..215) or Palette.TRANSPARENT, indexed by
 *                                              //   sprite-sheet colour index. At least one entry is TRANSPARENT and at
 *                                              //   least one opaque; the sprite must contain pixels of both kinds.
 *      void tick(InputHandler in)              // one fixed step: dx = (right?1:0)-(left?1:0), dy = (down?1:0)-(up?1:0);
 *                                              //   x += SPEED*dx, y += SPEED*dy. Diagonal = both axes (no normalising).
 *                                              //   Opposite keys cancel. NO collision and NO clamping to the level yet.
 *      void render(Screen s, SpriteSheet sheet, int xOffset, int yOffset)
 *                                              // draws sprite tiles (0,28) (1,28) (0,29) (1,29) (facing down; no
 *                                              //   animation yet) at screen (x-xOffset, y-yOffset) as
 *                                              //   s.drawPaletteTile(..., COLOURS): top-left, top-right, bottom-left,
 *                                              //   bottom-right. Does not clear. These tiles are pinned ONLY for a player
 *                                              //   that has never moved; after any movement later episodes (facing, walk
 *                                              //   frames) change the tiles, so only the 16x16 box's presence is pinned.
 * 3. ge.World
 *      World(Level level, Player player)
 *      Level level(); Player player(); List<Mob> entities()   // entities() contains the player (and later others)
 *      void tick(InputHandler in)              // ticks the player
 *      int cameraX(); int cameraY()            // world pixel shown at screen (0,0). Camera keeps the player's sprite
 *                                              //   CENTRED: cameraX = player.x() + 8 - Game.WIDTH/2 ,
 *                                              //   cameraY = player.y() + 8 - Game.HEIGHT/2 (so the sprite's top-left is
 *                                              //   at screen (72,52)). NO clamping to level bounds (void shows past edges).
 *      void render(Screen s, SpriteSheet sheet) // level first (Level.render at the camera), then entities on top. No clear.
 * 4. Scene specs  game:gen:W,H,SEED@SPAWNX,SPAWNY   (W,H >= 1, SEED long, spawn = player's top-left, ints, may be negative)
 *                   game:grass:W,H@SPAWNX,SPAWNY      (W,H >= 1; a W x H level of all Tile.GRASS, no solid tiles)
 *      Scene.parse accepts both (IllegalArgumentException when malformed). New Scene method `World newWorld()` builds
 *      Level.generate(W,H,SEED) (or the all-grass level) + Player at spawn (null for non-game scenes). Game(scene, seed) uses it: Game.world()
 *      returns the World (null for other scenes). Game.tick() calls world.tick(input()) (after tickCount++);
 *      Game.render(screen) clears to black, then world.render.
 * 5. CLI  --trace-player (needs --headless and a game: scene, else exit 2 + message): after each tick's update one stdout
 *      line  `tick=T x=X y=Y camx=CX camy=CY`  (before the final `ticks=N frames=M` line). Later episodes only append fields.
 *      Works together with --script, --dump-dir. Frame frame-0000(T-1).png is the render of tick T.
 *
 * Expected values come from the spec above, the sheet in res/ and the pinned Level.render (Ep08); never builder output.
 */
public class Ep09Test {

    private static final Path JAR = Paths.get("build/game.jar");
    private static final int W = Game.WIDTH, H = Game.HEIGHT;
    private static SpriteSheet sheet;
    private static BufferedImage sheetImage;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = Ep09Test.class.getResourceAsStream("/sprite_sheet.png")) {
            sheetImage = ImageIO.read(in);
        }
        sheet = SpriteSheet.load("/sprite_sheet.png");
    }

    private static InputHandler keys(int... codes) {
        InputHandler in = new InputHandler();
        for (int c : codes) in.press(c);
        return in;
    }

    /** All-grass level: nothing solid, so later collision episodes cannot affect movement tests. */
    private static Level grass(int w, int h) {
        Level l = new Level(w, h);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) l.setTile(x, y, Tile.GRASS);
        return l;
    }

    private static World world(int sx, int sy) {
        return new World(grass(64, 64), new Player(sx, sy));
    }

    // ---- movement ---------------------------------------------------------------------------------

    @Test
    void constantsAndNoInputNoMovement() {
        assertEquals(16, Player.SIZE);
        assertEquals(1, Player.SPEED);
        World w = world(100, 80);
        for (int i = 0; i < 30; i++) w.tick(new InputHandler());
        assertEquals(100, w.player().x());
        assertEquals(80, w.player().y());
        assertEquals(100 + 8 - W / 2, w.cameraX());
        assertEquals(80 + 8 - H / 2, w.cameraY());
        assertSame(w.player(), w.entities().get(0));
        assertTrue(w.player() instanceof Mob);
    }

    @Test
    void eachDirectionAndDiagonalMoveOnePixelPerTickPerAxis() {
        int[][] cases = { // key, dx, dy
            {KeyEvent.VK_UP, 0, -1}, {KeyEvent.VK_DOWN, 0, 1}, {KeyEvent.VK_LEFT, -1, 0}, {KeyEvent.VK_RIGHT, 1, 0},
            {KeyEvent.VK_W, 0, -1}, {KeyEvent.VK_S, 0, 1}, {KeyEvent.VK_A, -1, 0}, {KeyEvent.VK_D, 1, 0}};
        for (int[] c : cases) {
            World w = world(100, 80);
            InputHandler in = keys(c[0]);
            for (int t = 1; t <= 7; t++) {
                w.tick(in);
                assertEquals(100 + 7 * 0 + c[1] * t, w.player().x(), "key " + c[0] + " tick " + t);
                assertEquals(80 + c[2] * t, w.player().y(), "key " + c[0] + " tick " + t);
            }
        }
        World w = world(100, 80);
        InputHandler in = keys(KeyEvent.VK_DOWN, KeyEvent.VK_LEFT);
        for (int i = 0; i < 5; i++) w.tick(in);
        assertEquals(95, w.player().x());
        assertEquals(85, w.player().y());
        in.release(KeyEvent.VK_LEFT);
        for (int i = 0; i < 3; i++) w.tick(in);
        assertEquals(95, w.player().x());
        assertEquals(88, w.player().y());
        in.releaseAll();
        w.tick(in);
        assertEquals(88, w.player().y());
        // opposite keys cancel
        World o = world(10, 10);
        o.tick(keys(KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_UP, KeyEvent.VK_DOWN));
        assertEquals(10, o.player().x());
        assertEquals(10, o.player().y());
    }

    @Test
    void cameraKeepsPlayerCentred() {
        World w = world(100, 100);
        assertEquals(100 + 8 - 80, w.cameraX());
        assertEquals(100 + 8 - 60, w.cameraY());
        InputHandler in = keys(KeyEvent.VK_RIGHT, KeyEvent.VK_DOWN);
        for (int t = 1; t <= 40; t++) {
            w.tick(in);
            assertEquals(100 + t, w.player().x());
            assertEquals(100 + t, w.player().y());
            assertEquals(100 + t + 8 - 80, w.cameraX());
            assertEquals(100 + t + 8 - 60, w.cameraY());
            assertEquals(72, w.player().x() - w.cameraX());
            assertEquals(52, w.player().y() - w.cameraY());
        }
    }

    // ---- rendering --------------------------------------------------------------------------------

    /** Independent model: black, Level.render at the camera, then the 16x16 sprite from the sheet through COLOURS. */
    private static int[] expectedFrame(Level level, int px, int py) {
        int camx = px + 8 - W / 2, camy = py + 8 - H / 2;
        Screen s = new Screen(W, H);
        s.clear(0x000000);
        level.render(s, sheet, camx, camy);
        int[] out = s.pixels.clone();
        for (int dy = 0; dy < 16; dy++) {
            for (int dx = 0; dx < 16; dx++) {
                int idx = (sheetImage.getRGB(dx, 28 * 8 + dy) & 0xff) >> 6;
                int c = Player.COLOURS[idx];
                int sx = px - camx + dx, sy = py - camy + dy;
                if (c != Palette.TRANSPARENT && sx >= 0 && sy >= 0 && sx < W && sy < H) out[sy * W + sx] = Palette.rgb(c);
            }
        }
        return out;
    }

    /**
     * For a player that has MOVED: everything outside the sprite box (screen 72..87 x 52..67) equals the
     * level-at-camera model on black, and the box differs from the level-only frame (sprite is drawn). The box's
     * tiles are not pinned (later episodes change facing/walk frames).
     */
    private static void assertMovedFrame(Level level, int px, int py, int[] actual, String msg) {
        int camx = px + 8 - W / 2, camy = py + 8 - H / 2;
        Screen lv = new Screen(W, H);
        lv.clear(0x000000);
        level.render(lv, sheet, camx, camy);
        assertEquals(W * H, actual.length, msg);
        boolean boxDiffers = false;
        for (int i = 0; i < W * H; i++) {
            int x = i % W, y = i / W;
            if (x >= 72 && x < 88 && y >= 52 && y < 68) {
                if (actual[i] != lv.pixels[i]) boxDiffers = true;
            } else {
                assertEquals(lv.pixels[i], actual[i], msg + " outside sprite box at " + x + "," + y);
            }
        }
        assertTrue(boxDiffers, msg + ": sprite box must differ from the level-only frame");
    }

    /** Guard: a never-moved exact-sprite spawn must not overlap WATER (a later episode draws swimming there). */
    private static void assertNoWaterUnder(Level level, int px, int py) {
        for (int ty = Math.floorDiv(py, 8); ty <= Math.floorDiv(py + 15, 8); ty++)
            for (int tx = Math.floorDiv(px, 8); tx <= Math.floorDiv(px + 15, 8); tx++)
                assertNotSame(Tile.WATER, level.getTile(tx, ty), "spawn " + px + "," + py + " overlaps water at tile " + tx + "," + ty);
    }

    @Test
    void spriteColoursHaveTransparentAndOpaqueSlotsAndTheSpriteUsesBoth() {
        assertEquals(4, Player.COLOURS.length);
        boolean[] used = new boolean[4];
        for (int dy = 0; dy < 16; dy++)
            for (int dx = 0; dx < 16; dx++) used[(sheetImage.getRGB(dx, 28 * 8 + dy) & 0xff) >> 6] = true;
        boolean transparentUsed = false, opaqueUsed = false;
        for (int i = 0; i < 4; i++) {
            int c = Player.COLOURS[i];
            assertTrue(c == Palette.TRANSPARENT || (c >= 0 && c < Palette.SIZE), "colour " + i);
            if (used[i]) {
                if (c == Palette.TRANSPARENT) transparentUsed = true;
                else opaqueUsed = true;
            }
        }
        assertTrue(transparentUsed, "sprite must have pixels in a transparent slot");
        assertTrue(opaqueUsed, "sprite must have pixels in an opaque slot");
    }

    @Test
    void worldRendersLevelThenPlayerAtCentre() {
        for (int[] spawn : new int[][] {{100, 80}, {200, 200}, {-30, 500}}) {
            Level level = Level.generate(64, 64, 7L);
            assertNoWaterUnder(level, spawn[0], spawn[1]);
            World w = new World(level, new Player(spawn[0], spawn[1]));
            Screen s = new Screen(W, H);
            s.clear(0x000000);
            w.render(s, sheet);
            assertArrayEquals(expectedFrame(level, spawn[0], spawn[1]), s.pixels, "spawn " + spawn[0] + "," + spawn[1]);
        }
        // the transparent slot lets the level show through: the frame differs from a level-only frame only under opaque pixels
        Level level = Level.generate(64, 64, 7L);
        assertNoWaterUnder(level, 100, 80);
        Screen lv = new Screen(W, H);
        lv.clear(0);
        level.render(lv, sheet, 100 + 8 - W / 2, 80 + 8 - H / 2);
        Screen full = new Screen(W, H);
        full.clear(0);
        new World(level, new Player(100, 80)).render(full, sheet);
        int same = 0, diff = 0;
        for (int y = 52; y < 68; y++)
            for (int x = 72; x < 88; x++) {
                if (lv.pixels[y * W + x] == full.pixels[y * W + x]) same++;
                else diff++;
            }
        assertTrue(same > 0 && diff > 0, "sprite box has both see-through and opaque pixels");
        for (int i = 0; i < W * H; i++) {
            int x = i % W, y = i / W;
            if (x < 72 || x >= 88 || y < 52 || y >= 68) assertEquals(lv.pixels[i], full.pixels[i], "outside sprite box");
        }
    }

    @Test
    void sceneAndGameWiring() {
        Scene sc = Scene.parse("game:gen:30,20,42@16,24");
        assertNotNull(sc.newWorld());
        assertEquals(30, sc.newWorld().level().width());
        assertNotSame(sc.newWorld(), sc.newWorld());
        Game g = new Game(sc, 0L);
        assertNotNull(g.world());
        assertEquals(16, g.world().player().x());
        assertEquals(24, g.world().player().y());
        assertNoWaterUnder(Level.generate(30, 20, 42L), 16, 24);
        assertNoWaterUnder(Level.generate(30, 20, 42L), -10, 5);
        Screen s = new Screen(W, H);
        s.clear(0xABCDEF);
        g.render(s);
        assertArrayEquals(expectedFrame(Level.generate(30, 20, 42L), 16, 24), s.pixels);
        // never-moved player, incl. a spawn whose view shows void past the level edge: exact tile model
        Game fresh = new Game(Scene.parse("game:gen:30,20,42@-10,5"), 0L);
        Screen fs = new Screen(W, H);
        fs.clear(0xABCDEF);
        fresh.render(fs);
        assertArrayEquals(expectedFrame(Level.generate(30, 20, 42L), -10, 5), fs.pixels);
        // game:grass
        Scene gs = Scene.parse("game:grass:40,30@50,60");
        World gw = gs.newWorld();
        assertNotNull(gw);
        assertNotSame(gw, gs.newWorld());
        assertEquals(40, gw.level().width());
        assertEquals(30, gw.level().height());
        for (int y = 0; y < 30; y++)
            for (int x = 0; x < 40; x++) assertSame(Tile.GRASS, gw.level().getTile(x, y), x + "," + y);
        assertEquals(50, gw.player().x());
        assertEquals(60, gw.player().y());
        Game gg = new Game(gs, 0L);
        assertNotNull(gg.world());
        gg.input().press(KeyEvent.VK_LEFT);
        for (int i = 0; i < 4; i++) gg.tick();
        assertEquals(46, gg.world().player().x());
        assertEquals(60, gg.world().player().y());
        Screen gsr = new Screen(W, H);
        gsr.clear(0xABCDEF);
        gg.render(gsr);
        assertMovedFrame(grass(40, 30), 46, 60, gsr.pixels, "game:grass after 4 ticks");
        assertNull(new Game(Scene.parse("level:gen:5,5,1@0,0"), 0L).world());
        assertNull(new Game().world());
        assertNull(Scene.parse("level:gen:5,5,1@0,0").newWorld());
        for (String bad : new String[] {"game:gen:0,5,1@0,0", "game:gen:5,5,1", "game:gen:5,5,1@0", "game:gen:a,5,1@0,0",
                "game:gen:5,5,1@0,0,0", "game:", "game:gen:", "game:grass:0,5@0,0", "game:grass:5,0@0,0",
                "game:grass:5,5,1@0,0", "game:grass:5,5", "game:grass:5,5@0", "game:grass:a,5@0,0", "game:grass:5,5@0,0,0",
                "game:grass:", "game:grass"})
            assertThrows(IllegalArgumentException.class, () -> Scene.parse(bad), bad);
    }

    // ---- jar: scripted walk ----------------------------------------------------------------------

    @Test
    void jarScriptedWalkTraceAndFrames(@TempDir Path dir) throws Exception {
        Path script = dir.resolve("walk.txt");
        Files.writeString(script, "1 press RIGHT\n11 release RIGHT\n21 press DOWN\n21 press A\n26 release DOWN\n26 release A\n");
        Path dump = dir.resolve("frames");
        Run r = runJar("--headless", "--ticks", "30", "--script", script.toString(), "--dump-dir", dump.toString(),
                "--scene", "game:grass:40,30@50,60", "--trace-player");
        assertEquals(0, r.exit, r.out);
        List<String> lines = new ArrayList<>();
        for (String l : r.out.split("\\R")) if (l.startsWith("tick=") && l.contains(" x=")) lines.add(l);
        assertEquals(30, lines.size(), r.out);
        for (int t = 1; t <= 30; t++) {
            int x = 50, y = 60;
            if (t >= 1) x += Math.min(t, 10);            // RIGHT held ticks 1..10
            if (t >= 21) { x -= Math.min(t, 25) - 20; y += Math.min(t, 25) - 20; } // DOWN+LEFT ticks 21..25
            String expect = "tick=" + t + " x=" + x + " y=" + y + " camx=" + (x - 72) + " camy=" + (y - 52);
            assertTrue(lines.get(t - 1).equals(expect) || lines.get(t - 1).startsWith(expect + " "), lines.get(t - 1) + " vs " + expect);
        }
        Level level = grass(40, 30);
        // tick 15 (x=60,y=60), tick 30 (x=55,y=65): the player has moved, so only the sprite box's presence is pinned
        assertMovedFrame(level, 60, 60, readFrame(dump.resolve("frame-00014.png")), "frame 14");
        assertMovedFrame(level, 55, 65, readFrame(dump.resolve("frame-00029.png")), "frame 29");
        assertTrue(r.out.contains("ticks=30 frames=30"), r.out);

        // --trace-player without a game scene is a usage error
        Run bad = runJar("--headless", "--ticks", "2", "--trace-player");
        assertEquals(2, bad.exit);
        Run bad2 = runJar("--headless", "--ticks", "2", "--scene", "level:gen:5,5,1@0,0", "--trace-player");
        assertEquals(2, bad2.exit);
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

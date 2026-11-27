package ge;

import static org.junit.jupiter.api.Assertions.*;

import ge.entity.Mob;
import ge.entity.Player;
import ge.gfx.Palette;
import ge.gfx.SpriteSheet;
import ge.input.InputHandler;
import ge.level.Level;
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
 *                                              //   bottom-right. Does not clear.
 * 3. ge.World
 *      World(Level level, Player player)
 *      Level level(); Player player(); List<Mob> entities()   // entities() contains the player (and later others)
 *      void tick(InputHandler in)              // ticks the player
 *      int cameraX(); int cameraY()            // world pixel shown at screen (0,0). Camera keeps the player's sprite
 *                                              //   CENTRED: cameraX = player.x() + 8 - Game.WIDTH/2 ,
 *                                              //   cameraY = player.y() + 8 - Game.HEIGHT/2 (so the sprite's top-left is
 *                                              //   at screen (72,52)). NO clamping to level bounds (void shows past edges).
 *      void render(Screen s, SpriteSheet sheet) // level first (Level.render at the camera), then entities on top. No clear.
 * 4. Scene spec  game:gen:W,H,SEED@SPAWNX,SPAWNY   (W,H >= 1, SEED long, spawn = player's top-left, ints, may be negative)
 *      Scene.parse accepts it (IllegalArgumentException when malformed). New Scene method `World newWorld()` builds
 *      Level.generate(W,H,SEED) + Player at spawn (null for non-game scenes). Game(scene, seed) uses it: Game.world()
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

    private static World world(int sx, int sy) {
        return new World(Level.generate(64, 64, 7L), new Player(sx, sy));
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
    void movementIsNotClampedToTheLevel() {
        World w = world(0, 0);
        InputHandler in = keys(KeyEvent.VK_LEFT, KeyEvent.VK_UP);
        for (int i = 0; i < 10; i++) w.tick(in);
        assertEquals(-10, w.player().x());
        assertEquals(-10, w.player().y());
    }

    @Test
    void cameraKeepsPlayerCentred() {
        World w = world(0, 0);
        assertEquals(-72, w.cameraX());
        assertEquals(-52, w.cameraY());
        InputHandler in = keys(KeyEvent.VK_RIGHT, KeyEvent.VK_DOWN);
        for (int t = 1; t <= 40; t++) {
            w.tick(in);
            assertEquals(t - 72, w.cameraX());
            assertEquals(t - 52, w.cameraY());
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
        for (int[] spawn : new int[][] {{100, 80}, {0, 0}, {-30, 500}}) {
            Level level = Level.generate(64, 64, 7L);
            World w = new World(level, new Player(spawn[0], spawn[1]));
            Screen s = new Screen(W, H);
            s.clear(0x000000);
            w.render(s, sheet);
            assertArrayEquals(expectedFrame(level, spawn[0], spawn[1]), s.pixels, "spawn " + spawn[0] + "," + spawn[1]);
        }
        // the transparent slot lets the level show through: the frame differs from a level-only frame only under opaque pixels
        Level level = Level.generate(64, 64, 7L);
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
        assertNotSame(sc.newWorld(), sc.newWorld());
        Game g = new Game(sc, 0L);
        assertNotNull(g.world());
        assertEquals(16, g.world().player().x());
        g.input().press(KeyEvent.VK_RIGHT);
        for (int i = 0; i < 5; i++) g.tick();
        assertEquals(21, g.world().player().x());
        assertEquals(24, g.world().player().y());
        Screen s = new Screen(W, H);
        s.clear(0xABCDEF);
        g.render(s);
        assertArrayEquals(expectedFrame(Level.generate(30, 20, 42L), 21, 24), s.pixels);
        assertNull(new Game(Scene.parse("level:gen:5,5,1@0,0"), 0L).world());
        assertNull(new Game().world());
        assertNull(Scene.parse("level:gen:5,5,1@0,0").newWorld());
        for (String bad : new String[] {"game:gen:0,5,1@0,0", "game:gen:5,5,1", "game:gen:5,5,1@0", "game:gen:a,5,1@0,0",
                "game:gen:5,5,1@0,0,0", "game:", "game:gen:"})
            assertThrows(IllegalArgumentException.class, () -> Scene.parse(bad), bad);
    }

    // ---- jar: scripted walk ----------------------------------------------------------------------

    @Test
    void jarScriptedWalkTraceAndFrames(@TempDir Path dir) throws Exception {
        Path script = dir.resolve("walk.txt");
        Files.writeString(script, "1 press RIGHT\n11 release RIGHT\n21 press DOWN\n21 press A\n26 release DOWN\n26 release A\n");
        Path dump = dir.resolve("frames");
        Run r = runJar("--headless", "--ticks", "30", "--script", script.toString(), "--dump-dir", dump.toString(),
                "--scene", "game:gen:40,30,5@50,60", "--trace-player");
        assertEquals(0, r.exit, r.out);
        List<String> lines = new ArrayList<>();
        for (String l : r.out.split("\\R")) if (l.startsWith("tick=") && l.contains(" x=")) lines.add(l);
        assertEquals(30, lines.size(), r.out);
        for (int t = 1; t <= 30; t++) {
            int x = 50, y = 60;
            if (t >= 1) x += Math.min(t, 10);            // RIGHT held ticks 1..10
            if (t >= 21) { x -= Math.min(t, 25) - 20; y += Math.min(t, 25) - 20; } // DOWN+LEFT ticks 21..25
            String expect = "tick=" + t + " x=" + x + " y=" + y + " camx=" + (x - 72) + " camy=" + (y - 52);
            assertEquals(expect, lines.get(t - 1));
        }
        Level level = Level.generate(40, 30, 5L);
        // tick 15 (x=60,y=60), tick 30 (x=55,y=65)
        assertArrayEquals(expectedFrame(level, 60, 60), readFrame(dump.resolve("frame-00014.png")));
        assertArrayEquals(expectedFrame(level, 55, 65), readFrame(dump.resolve("frame-00029.png")));
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

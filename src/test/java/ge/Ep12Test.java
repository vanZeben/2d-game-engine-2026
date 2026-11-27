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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/*
 * Episode 12 check: "The Collision Box"
 * SPEC: the player can't enter solid tiles; a scripted walk into stone stops at its edge.
 *
 * CONTRACT the builder must satisfy (earlier contracts stay; the default run and Ep09/Ep11 scenes are unchanged)
 * -------------------------------------------------------------------------------------------------------------
 * 1. Collision box, in sprite-local pixels, INCLUSIVE on both ends (the "feet box", 8 wide x 6 high), public
 *    constants on ge.entity.Player:
 *      BOX_X0 = 4, BOX_X1 = 11, BOX_Y0 = 10, BOX_Y1 = 15
 *    (the test reads the constants, it does not hard-code 4/11/10/15, but it requires 0 <= X0 <= X1 <= 15,
 *    0 <= Y0 <= Y1 <= 15 and a box between 2 and 8 pixels in each dimension so the player fits through a
 *    one-tile gap.) A mob at (x, y) occupies world pixels x+BOX_X0..x+BOX_X1, y+BOX_Y0..y+BOX_Y1. A tile cell
 *    (cx, cy) covers world pixels 8cx..8cx+7, 8cy..8cy+7 (floorDiv), as in Level.render.
 * 2. Rule: a move (dx, dy) is split per axis. The x part is applied only if the box at the new x overlaps no
 *    solid tile (Tile.isSolid(); only STONE is solid today), likewise the y part, each axis checked on its own,
 *    so on a diagonal against a wall the free axis still moves (slides). Speed stays 1 px/tick/axis, so a blocked
 *    mover ends with its box exactly touching the stone edge. Void and level-edge solidity are NOT pinned.
 *    (Tests avoid inside corners, so the order in which the axes are tried is not pinned either.)
 * 3. A Mob with no level still moves freely: ge.World hands its Level to the player (and to every mob added later)
 *    -- Mob gets a level it consults in move(); a Mob built stand-alone (Ep11's Dummy) has none and is never blocked.
 * 4. Facing and animation are NOT affected by blocking: a blocked mover still turns to its direction of travel and
 *    its walk frame keeps counting consecutive ticks of *intent* (Ep11's rule, with the intended (dx,dy)), it just
 *    does not change position. Release the keys (an idle tick) and the frame is 0 again.
 * 5. Scene option for game: scenes (grass levels are all grass except what is listed):
 *      game:grass:W,H@SX,SY:stone=TX,TY;TX,TY;...
 *    after the spawn, one option "stone=" with one or more semicolon-separated tile cells; those cells are
 *    Tile.STONE. Malformed (no cells, a cell without two integers, an unknown option name) -> IllegalArgumentException
 *    from Scene.parse. Stone cells outside the level are ignored. Without the option nothing changes.
 *    (Documented in the Scene javadoc.)
 * 6. --trace-player lines keep their format `tick=T x=X y=Y camx=CX camy=CY dir=D frame=F`; this test matches
 *    only that prefix so later episodes may append fields.
 */
public class Ep12Test {

    private static final Path JAR = Paths.get("build/game.jar");
    private static final int W = Game.WIDTH, H = Game.HEIGHT;
    private static final int X0 = Player.BOX_X0, X1 = Player.BOX_X1, Y0 = Player.BOX_Y0, Y1 = Player.BOX_Y1;
    private static final int U = KeyEvent.VK_UP, D = KeyEvent.VK_DOWN, L = KeyEvent.VK_LEFT, R = KeyEvent.VK_RIGHT;
    private static SpriteSheet sheet;
    private static BufferedImage sheetImage;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = Ep12Test.class.getResourceAsStream("/sprite_sheet.png")) {
            sheetImage = ImageIO.read(in);
        }
        sheet = SpriteSheet.load("/sprite_sheet.png");
    }

    private static Level grass(int w, int h, int[]... stones) {
        Level l = new Level(w, h);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) l.setTile(x, y, Tile.GRASS);
        for (int[] s : stones) l.setTile(s[0], s[1], Tile.STONE);
        return l;
    }

    private static int[][] rect(int cx0, int cy0, int cx1, int cy1) {
        List<int[]> out = new ArrayList<>();
        for (int y = cy0; y <= cy1; y++) for (int x = cx0; x <= cx1; x++) out.add(new int[] {x, y});
        return out.toArray(new int[0][]);
    }

    /** Independent check of the box rule: does a mob at (x, y) overlap a solid tile? */
    private static boolean blocked(Level l, int x, int y) {
        for (int py = y + Y0; py <= y + Y1; py += 1)
            for (int px = x + X0; px <= x + X1; px += 1)
                if (l.getTile(Math.floorDiv(px, 8), Math.floorDiv(py, 8)).isSolid()) return true;
        return false;
    }

    /** Presses the given keys for n ticks, returning positions [tick][x,y] after each tick. */
    private static int[][] hold(World w, int n, int... keys) {
        InputHandler in = new InputHandler();
        for (int k : keys) in.press(k);
        int[][] out = new int[n][];
        for (int t = 0; t < n; t++) {
            w.tick(in);
            out[t] = new int[] {w.player().x(), w.player().y()};
        }
        return out;
    }

    // ---- the box constants ----------------------------------------------------------------------

    @Test
    void boxIsInsideTheSpriteAndSmallEnough() {
        assertTrue(0 <= X0 && X0 <= X1 && X1 <= 15, "x range inside the 16x16 sprite");
        assertTrue(0 <= Y0 && Y0 <= Y1 && Y1 <= 15, "y range inside the 16x16 sprite");
        int bw = X1 - X0 + 1, bh = Y1 - Y0 + 1;
        assertTrue(bw >= 2 && bw <= 8, "box width " + bw);
        assertTrue(bh >= 2 && bh <= 8, "box height " + bh);
    }

    // ---- walking straight into one stone tile, four directions ---------------------------------

    private static final int SX = 20, SY = 20; // the lone stone tile: pixels 160..167 on both axes

    @Test
    void walkRightIntoStoneStopsAtItsEdge() {
        Level l = grass(40, 40, new int[] {SX, SY});
        World w = new World(l, new Player(100, SY * 8 - Y0)); // box top aligned with the stone's top row
        int[][] pos = hold(w, 150, R);
        int stopX = SX * 8 - 1 - X1; // box's right column is the pixel just left of the stone
        for (int t = 0; t < 150; t++) {
            assertEquals(Math.min(100 + t + 1, stopX), pos[t][0], "x after tick " + (t + 1));
            assertEquals(SY * 8 - Y0, pos[t][1], "y never changes");
        }
        assertFalse(blocked(l, w.player().x(), w.player().y()));
        assertTrue(blocked(l, w.player().x() + 1, w.player().y()), "exactly touching: one more pixel would overlap");
    }

    @Test
    void walkLeftIntoStoneStopsAtItsEdge() {
        Level l = grass(40, 40, new int[] {SX, SY});
        World w = new World(l, new Player(250, SY * 8 - Y0));
        int[][] pos = hold(w, 150, L);
        int stopX = (SX + 1) * 8 - X0; // box's left column is the first pixel right of the stone
        for (int t = 0; t < 150; t++) assertEquals(Math.max(250 - t - 1, stopX), pos[t][0], "x after tick " + (t + 1));
        assertFalse(blocked(l, w.player().x(), w.player().y()));
        assertTrue(blocked(l, w.player().x() - 1, w.player().y()));
    }

    @Test
    void walkDownIntoStoneStopsAtItsEdge() {
        Level l = grass(40, 40, new int[] {SX, SY});
        World w = new World(l, new Player(SX * 8 - X0, 100));
        int[][] pos = hold(w, 150, D);
        int stopY = SY * 8 - 1 - Y1;
        for (int t = 0; t < 150; t++) {
            assertEquals(Math.min(100 + t + 1, stopY), pos[t][1], "y after tick " + (t + 1));
            assertEquals(SX * 8 - X0, pos[t][0]);
        }
        assertTrue(blocked(l, w.player().x(), w.player().y() + 1));
        assertFalse(blocked(l, w.player().x(), w.player().y()));
    }

    @Test
    void walkUpIntoStoneStopsAtItsEdge() {
        Level l = grass(40, 40, new int[] {SX, SY});
        World w = new World(l, new Player(SX * 8 - X0, 250));
        int[][] pos = hold(w, 150, U);
        int stopY = (SY + 1) * 8 - Y0;
        for (int t = 0; t < 150; t++) assertEquals(Math.max(250 - t - 1, stopY), pos[t][1], "y after tick " + (t + 1));
        assertTrue(blocked(l, w.player().x(), w.player().y() - 1));
        assertFalse(blocked(l, w.player().x(), w.player().y()));
    }

    @Test
    void partialOverlapOfTheBoxBlocksToo() {
        // Only the box's last column/row reaches into the stone's rows: still blocked (any overlap counts).
        Level l = grass(40, 40, new int[] {SX, SY});
        World w = new World(l, new Player(100, SY * 8 + 7 - Y1)); // box bottom row on the stone's last pixel row
        hold(w, 120, R);
        assertEquals(SX * 8 - 1 - X1, w.player().x());
        // ... and a box one pixel clear of the stone's rows walks straight past.
        World w2 = new World(l, new Player(100, SY * 8 + 8 - Y0)); // box top row just below the stone
        hold(w2, 120, R);
        assertEquals(220, w2.player().x());
    }

    @Test
    void spriteOutsideTheBoxMayOverlapStone() {
        // The head (sprite rows above BOX_Y0) can walk "behind" the stone: player just below the stone, walking up
        // stops with the box (not the 16x16 sprite) touching it.
        Level l = grass(40, 40, new int[] {SX, SY});
        World w = new World(l, new Player(SX * 8 - X0, 300));
        hold(w, 200, U);
        assertEquals(SY * 8 + 8 - Y0, w.player().y());
        assertTrue(w.player().y() < SY * 8 + 8, "sprite top is above the stone's bottom edge: the sprite overlaps it");
    }

    // ---- holding longer: no creep, no jitter; facing and animation while blocked -----------------

    @Test
    void blockedMoverIsStillAndStillAnimates() {
        Level l = grass(40, 40, new int[] {SX, SY});
        World w = new World(l, new Player(100, SY * 8 - Y0));
        InputHandler in = new InputHandler();
        in.press(R);
        int stopX = SX * 8 - 1 - X1;
        for (int n = 1; n <= 200; n++) {
            w.tick(in);
            assertEquals(Math.min(100 + n, stopX), w.player().x(), "x at n=" + n);
            assertEquals(Mob.Dir.RIGHT, w.player().dir());
            assertEquals(((n - 1) / Mob.WALK_PERIOD) % 2, w.player().walkFrame(), "frame at n=" + n);
        }
        // releasing the key is an idle tick: standing frame, position unchanged
        in.releaseAll();
        w.tick(in);
        assertEquals(0, w.player().walkFrame());
        assertEquals(stopX, w.player().x());
    }

    // ---- sliding on diagonals ---------------------------------------------------------------------

    @Test
    void slideDownRightAlongAVerticalWall() {
        Level l = grass(40, 40, rect(20, 10, 20, 30)); // wall: x 160..167, y 80..247
        World w = new World(l, new Player(100, 100));
        int[][] pos = hold(w, 120, R, D);
        int stopX = 160 - 1 - X1;
        for (int t = 0; t < 120; t++) {
            assertEquals(Math.min(100 + t + 1, stopX), pos[t][0], "x after tick " + (t + 1));
            assertEquals(100 + t + 1, pos[t][1], "y keeps sliding after tick " + (t + 1));
        }
        assertEquals(Mob.Dir.RIGHT, w.player().dir(), "horizontal wins the facing on a diagonal, blocked or not");
    }

    @Test
    void slideUpLeftAlongAVerticalWall() {
        Level l = grass(40, 40, rect(20, 10, 20, 30));
        World w = new World(l, new Player(250, 200));
        int[][] pos = hold(w, 120, L, U);
        int stopX = 168 - X0;
        for (int t = 0; t < 120; t++) {
            assertEquals(Math.max(250 - t - 1, stopX), pos[t][0], "x after tick " + (t + 1));
            assertEquals(200 - t - 1, pos[t][1], "y keeps sliding after tick " + (t + 1));
        }
    }

    @Test
    void slideRightDownAlongAHorizontalWall() {
        Level l = grass(40, 40, rect(10, 20, 30, 20)); // wall: x 80..247, y 160..167
        World w = new World(l, new Player(100, 100));
        int[][] pos = hold(w, 100, R, D);
        int stopY = 160 - 1 - Y1;
        for (int t = 0; t < 100; t++) {
            assertEquals(100 + t + 1, pos[t][0], "x keeps sliding after tick " + (t + 1));
            assertEquals(Math.min(100 + t + 1, stopY), pos[t][1], "y after tick " + (t + 1));
        }
    }

    @Test
    void slideLeftUpAlongAHorizontalWall() {
        Level l = grass(40, 40, rect(10, 20, 30, 20));
        World w = new World(l, new Player(200, 250));
        int[][] pos = hold(w, 100, L, U);
        int stopY = 168 - Y0;
        for (int t = 0; t < 100; t++) {
            assertEquals(200 - t - 1, pos[t][0]);
            assertEquals(Math.max(250 - t - 1, stopY), pos[t][1], "y after tick " + (t + 1));
        }
    }

    // ---- walking away again ---------------------------------------------------------------------

    @Test
    void canWalkAwayFromTheWallInAllowedDirections() {
        Level l = grass(40, 40, rect(20, 10, 20, 30));
        World w = new World(l, new Player(100, 150));
        InputHandler in = new InputHandler();
        in.press(R);
        for (int i = 0; i < 100; i++) w.tick(in);
        int x = w.player().x(), y = w.player().y();
        assertEquals(160 - 1 - X1, x);
        in.releaseAll();
        in.press(D); // along the wall
        for (int i = 1; i <= 10; i++) {
            w.tick(in);
            assertEquals(y + i, w.player().y());
            assertEquals(x, w.player().x());
        }
        y += 10;
        in.releaseAll();
        in.press(L); // away from the wall, immediately and one pixel at a time
        for (int i = 1; i <= 10; i++) {
            w.tick(in);
            assertEquals(x - i, w.player().x());
            assertEquals(y, w.player().y());
            assertEquals(Mob.Dir.LEFT, w.player().dir());
        }
        in.releaseAll();
        in.press(R); // and back to the wall
        for (int i = 0; i < 30; i++) w.tick(in);
        assertEquals(x, w.player().x());
    }

    // ---- no level => no collision --------------------------------------------------------------

    private static final class Dummy extends Mob {
        Dummy() {
            super(0, 0);
        }

        void go(int dx, int dy) {
            move(dx, dy);
        }

        @Override
        public void render(Screen screen, SpriteSheet sheet, int xOffset, int yOffset) {}
    }

    @Test
    void mobWithoutALevelMovesFreely() {
        Dummy d = new Dummy();
        for (int i = 0; i < 300; i++) d.go(1, -1);
        assertEquals(300, d.x());
        assertEquals(-300, d.y());
    }

    @Test
    void stoneOnlyBlocksWhenSolid() {
        // grass, water are not solid: walking across them is unchanged
        Level l = grass(40, 40);
        l.setTile(20, 20, Tile.WATER);
        World w = new World(l, new Player(100, SY * 8 - Y0));
        hold(w, 150, R);
        assertEquals(250, w.player().x());
    }

    // ---- scene option ---------------------------------------------------------------------------

    @Test
    void sceneStoneOptionBuildsTheLevel() {
        World w = Scene.parse("game:grass:40,40@100,152:stone=20,20;21,20;5,6").newWorld();
        assertEquals(100, w.player().x());
        assertEquals(152, w.player().y());
        Level l = w.level();
        for (int[] c : new int[][] {{20, 20}, {21, 20}, {5, 6}}) assertSame(Tile.STONE, l.getTile(c[0], c[1]));
        for (int[] c : new int[][] {{19, 20}, {22, 20}, {20, 21}, {6, 5}, {0, 0}}) assertSame(Tile.GRASS, l.getTile(c[0], c[1]));
        // single cell, and cells outside the level are ignored
        World w2 = Scene.parse("game:grass:10,10@0,0:stone=3,3;99,99;-1,2").newWorld();
        assertSame(Tile.STONE, w2.level().getTile(3, 3));
        assertSame(Tile.GRASS, w2.level().getTile(4, 4));
        // without the option: all grass, as before
        assertSame(Tile.GRASS, Scene.parse("game:grass:10,10@0,0").newWorld().level().getTile(3, 3));
    }

    @Test
    void sceneStoneOptionIsCollidable() {
        World w = Scene.parse("game:grass:40,40@100," + (SY * 8 - Y0) + ":stone=20,20").newWorld();
        hold(w, 150, R);
        assertEquals(SX * 8 - 1 - X1, w.player().x());
    }

    @Test
    void malformedStoneOptionIsRejected() {
        for (String bad : new String[] {
            "game:grass:10,10@0,0:stone=", "game:grass:10,10@0,0:stone=1", "game:grass:10,10@0,0:stone=1,2;3",
            "game:grass:10,10@0,0:stone=a,b", "game:grass:10,10@0,0:rock=1,2", "game:grass:10,10@0,0:stone=1,2;"})
            assertThrows(IllegalArgumentException.class, () -> Scene.parse(bad), bad);
    }

    // ---- jar: scripted walk into stone, trace + dumped frames ----------------------------------

    private static final Pattern LINE = Pattern.compile(
        "tick=(\\d+) x=(-?\\d+) y=(-?\\d+) camx=(-?\\d+) camy=(-?\\d+) dir=(down|up|left|right) frame=([01])");

    private static int sheetIndex(int frame, int dx, int dy) { // facing RIGHT: columns 4-5 / 6-7, never mirrored
        int col = frame == 0 ? 4 : 6;
        return (sheetImage.getRGB(col * 8 + dx, 28 * 8 + dy) & 0xff) >> 6;
    }

    private static int[] expectedFrame(Level level, int px, int py, int frame) {
        int camx = px + 8 - W / 2, camy = py + 8 - H / 2;
        Screen s = new Screen(W, H);
        s.clear(0x000000);
        level.render(s, sheet, camx, camy);
        int[] out = s.pixels.clone();
        for (int dy = 0; dy < 16; dy++)
            for (int dx = 0; dx < 16; dx++) {
                int c = Player.COLOURS[sheetIndex(frame, dx, dy)];
                if (c != Palette.TRANSPARENT) out[(52 + dy) * W + 72 + dx] = Palette.rgb(c);
            }
        return out;
    }

    @Test
    void jarScriptedWalkIntoStoneStopsAtItsEdge(@TempDir Path dir) throws Exception {
        int startY = SY * 8 - Y0, startX = 130;
        String spec = "game:grass:40,40@" + startX + "," + startY + ":stone=20,20;21,20;20,21";
        Path sc = dir.resolve("walk.txt");
        Files.writeString(sc, "3 press RIGHT\n90 release RIGHT\n");
        Path dump = dir.resolve("frames");
        int ticks = 100;
        Run r = runJar("--headless", "--ticks", "" + ticks, "--script", sc.toString(), "--dump-dir", dump.toString(),
                "--scene", spec, "--trace-player");
        assertEquals(0, r.exit, r.out);
        List<String> lines = new ArrayList<>();
        for (String l : r.out.split("\\R")) if (l.startsWith("tick=") && l.contains(" x=")) lines.add(l);
        assertEquals(ticks, lines.size(), r.out);

        Level level = grass(40, 40, new int[] {20, 20}, new int[] {21, 20}, new int[] {20, 21});
        int stopX = SX * 8 - 1 - X1;
        int x = startX, n = 0;
        for (int t = 1; t <= ticks; t++) {
            boolean held = t >= 3 && t < 90;
            n = held ? n + 1 : 0;
            if (held && x < stopX) x++;
            Matcher mt = LINE.matcher(lines.get(t - 1));
            assertTrue(mt.lookingAt(), "trace line prefix: " + lines.get(t - 1)); // later episodes may append fields
            assertEquals(t, Integer.parseInt(mt.group(1)));
            assertEquals(x, Integer.parseInt(mt.group(2)), "x at tick " + t);
            assertEquals(startY, Integer.parseInt(mt.group(3)), "y at tick " + t);
            assertEquals(x - 72, Integer.parseInt(mt.group(4)));
            assertEquals(startY - 52, Integer.parseInt(mt.group(5)));
            String expDir = t < 3 ? "down" : "right";
            assertEquals(expDir, mt.group(6), "dir at tick " + t);
            int frame = held ? ((n - 1) / 8) % 2 : 0;
            assertEquals(frame, Integer.parseInt(mt.group(7)), "frame at tick " + t);
            if (t >= 3) // from tick 3 on the player faces right
                assertArrayEquals(expectedFrame(level, x, startY, frame),
                        readFrame(dump.resolve(String.format("frame-%05d.png", t - 1))), "dumped frame of tick " + t);
        }
        assertEquals(stopX, x);
        assertTrue(r.out.contains("ticks=" + ticks + " frames=" + ticks), r.out);
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

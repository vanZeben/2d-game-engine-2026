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
 * Episode 11 check: "The Walking Animation!"
 * SPEC: the player faces its direction of travel and alternates walk frames while moving; a scripted walk in each
 * direction dumps the expected frames.
 *
 * CONTRACT the builder must satisfy (Ep09's contract stays: a never-moved player renders tiles (0,28),(1,28),
 * (0,29),(1,29) unmirrored; --trace-player lines only gain fields at the end)
 * -------------------------------------------------------------------------------------------------------------
 * Sprite sheet (inspected): tile rows 28 (top half) and 29 (bottom half); each frame is 2x2 tiles.
 *   columns 0-1 facing down (one frame), 2-3 facing up (one frame), 4-5 and 6-7 facing sideways (two walk frames;
 *   the figure looks to the RIGHT, so the sheet frames are "facing right").
 * 1. ge.entity.Mob gains (all public):
 *      enum Dir { DOWN, UP, LEFT, RIGHT }              // nested: Mob.Dir
 *      Dir dir()            // facing; initially DOWN
 *      int walkFrame()      // 0 or 1; initially 0
 *      static final int WALK_PERIOD = 8;               // moving ticks per walk frame
 *    The bookkeeping happens inside Mob.move(dx, dy) (protected, called once per tick by Player.tick and, later, by
 *    remote players), so every Mob subclass gets it:
 *      - move(0,0) is an "idle tick": the consecutive-moving-ticks counter n resets to 0, walkFrame() becomes 0,
 *        dir() is unchanged (facing persists after stopping).
 *      - otherwise n++ (n counts consecutive moving ticks, 1 on the first; a change of direction without an idle
 *        tick does NOT reset it), walkFrame() = ((n-1) / WALK_PERIOD) % 2, and dir() becomes: LEFT if dx<0, RIGHT if
 *        dx>0 (horizontal wins on diagonals), else UP if dy<0, DOWN if dy>0. The position changes as before.
 *      Player.tick(in) calls move once per tick with the net (dx,dy) (opposite keys cancel => idle tick).
 * 2. Player.render picks the 2x2 frame from (dir, walkFrame) and draws it with drawPaletteTile(..., COLOURS[, mirror]):
 *      DOWN : sheet columns 0-1; mirrored in X when walkFrame()==1 (only one down frame exists, so the "second walk
 *             frame" is the same art flipped left-right), unmirrored when 0.
 *      UP   : columns 2-3; mirrored in X when walkFrame()==1, else unmirrored.
 *      RIGHT: columns 4-5 when walkFrame()==0, columns 6-7 when 1; never mirrored.   (standing sideways = frame 0)
 *      LEFT : as RIGHT but the whole 16x16 sprite mirrored in X.
 *    "Mirrored in X" is for the whole 2x2 sprite: pixel (dx,dy) of the 16x16 box shows sheet pixel (15-dx,dy) of the
 *    unmirrored frame (so the left and right tiles also swap places).
 * 3. --trace-player lines become  `tick=T x=X y=Y camx=CX camy=CY dir=down|up|left|right frame=0|1`
 *    (state after tick T's update; Ep09 matches the old prefix).
 *
 * Expected values: sheet pixels via ImageIO + Player.COLOURS over a Level.render model, and the rule above in
 * Model below; never builder output.
 */
public class Ep11Test {

    private static final Path JAR = Paths.get("build/game.jar");
    private static final int W = Game.WIDTH, H = Game.HEIGHT;
    private static SpriteSheet sheet;
    private static BufferedImage sheetImage;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = Ep11Test.class.getResourceAsStream("/sprite_sheet.png")) {
            sheetImage = ImageIO.read(in);
        }
        sheet = SpriteSheet.load("/sprite_sheet.png");
    }

    private static Level grass(int w, int h) {
        Level l = new Level(w, h);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) l.setTile(x, y, Tile.GRASS);
        return l;
    }

    /** The rule from the header, independent of the builder's code. */
    private static final class Model {
        int x, y, n, frame;
        Mob.Dir dir = Mob.Dir.DOWN;

        Model(int x, int y) {
            this.x = x;
            this.y = y;
        }

        void step(int dx, int dy) {
            if (dx == 0 && dy == 0) {
                n = 0;
                frame = 0;
                return;
            }
            n++;
            frame = ((n - 1) / 8) % 2;
            if (dx != 0) dir = dx < 0 ? Mob.Dir.LEFT : Mob.Dir.RIGHT;
            else dir = dy < 0 ? Mob.Dir.UP : Mob.Dir.DOWN;
            x += dx;
            y += dy;
        }
    }

    /** Sheet pixel colour index for the 16x16 frame of (dir, frame) at box pixel (dx, dy), per the header. */
    private static int sheetIndex(Mob.Dir dir, int frame, int dx, int dy) {
        int col;
        boolean mirror;
        switch (dir) {
            case DOWN -> { col = 0; mirror = frame == 1; }
            case UP -> { col = 2; mirror = frame == 1; }
            case RIGHT -> { col = frame == 0 ? 4 : 6; mirror = false; }
            default -> { col = frame == 0 ? 4 : 6; mirror = true; }
        }
        int sx = col * 8 + (mirror ? 15 - dx : dx);
        return (sheetImage.getRGB(sx, 28 * 8 + dy) & 0xff) >> 6;
    }

    /** Model frame: black, the level at the camera, then the sprite box (screen 72,52) for (dir, frame). */
    private static int[] expectedFrame(Level level, Model m) {
        int camx = m.x + 8 - W / 2, camy = m.y + 8 - H / 2;
        Screen s = new Screen(W, H);
        s.clear(0x000000);
        level.render(s, sheet, camx, camy);
        int[] out = s.pixels.clone();
        for (int dy = 0; dy < 16; dy++)
            for (int dx = 0; dx < 16; dx++) {
                int c = Player.COLOURS[sheetIndex(m.dir, m.frame, dx, dy)];
                if (c != Palette.TRANSPARENT) out[(52 + dy) * W + 72 + dx] = Palette.rgb(c);
            }
        return out;
    }

    private static int[] boxOf(int[] frame) {
        int[] b = new int[256];
        for (int y = 0; y < 16; y++) System.arraycopy(frame, (52 + y) * W + 72, b, y * 16, 16);
        return b;
    }

    // ---- sanity of the test's own model ----------------------------------------------------------

    @Test
    void modelFramesAreDistinguishable() {
        Level level = grass(64, 64);
        List<int[]> boxes = new ArrayList<>();
        for (Mob.Dir d : Mob.Dir.values())
            for (int f = 0; f < 2; f++) {
                Model m = new Model(100, 100);
                m.dir = d;
                m.frame = f;
                boxes.add(boxOf(expectedFrame(level, m)));
            }
        for (int i = 0; i < boxes.size(); i++)
            for (int j = i + 1; j < boxes.size(); j++)
                assertFalse(java.util.Arrays.equals(boxes.get(i), boxes.get(j)), "frames " + i + " and " + j + " must differ");
        // the standing down frame is exactly the Ep09 tiles
        Model fresh = new Model(100, 100);
        int[] box = boxOf(expectedFrame(level, fresh));
        for (int dy = 0; dy < 16; dy++)
            for (int dx = 0; dx < 16; dx++) {
                int idx = (sheetImage.getRGB(dx, 28 * 8 + dy) & 0xff) >> 6;
                int c = Player.COLOURS[idx];
                if (c != Palette.TRANSPARENT) assertEquals(Palette.rgb(c), box[dy * 16 + dx]);
            }
    }

    // ---- Mob-level bookkeeping (what remote players will reuse) ---------------------------------

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
    void mobMoveTracksFacingAndWalkFrame() {
        assertEquals(8, Mob.WALK_PERIOD);
        Dummy d = new Dummy();
        assertEquals(Mob.Dir.DOWN, d.dir());
        assertEquals(0, d.walkFrame());
        d.go(0, 0);
        assertEquals(Mob.Dir.DOWN, d.dir());
        assertEquals(0, d.walkFrame());
        for (int i = 1; i <= 8; i++) {
            d.go(1, 0);
            assertEquals(Mob.Dir.RIGHT, d.dir());
            assertEquals(0, d.walkFrame(), "n=" + i);
        }
        d.go(1, 0);
        assertEquals(1, d.walkFrame());
        assertEquals(9, d.x());
        d.go(0, 0); // stop: facing persists, standing frame
        assertEquals(Mob.Dir.RIGHT, d.dir());
        assertEquals(0, d.walkFrame());
        d.go(-1, 1); // diagonal: horizontal wins; counter restarted
        assertEquals(Mob.Dir.LEFT, d.dir());
        assertEquals(0, d.walkFrame());
        d.go(0, -1);
        assertEquals(Mob.Dir.UP, d.dir());
        d.go(0, 1);
        assertEquals(Mob.Dir.DOWN, d.dir());
        assertEquals(8, d.x()); // 9 after the rights, -1 for the diagonal
    }

    // ---- Player in a World: dir/frame and the rendered frame, every tick ------------------------

    private static final int U = KeyEvent.VK_UP, D = KeyEvent.VK_DOWN, L = KeyEvent.VK_LEFT, R = KeyEvent.VK_RIGHT;

    @Test
    void playerFacingFramesAndRenderEveryTick() {
        // {ticks, keys...}; keys empty = idle
        int[][] plan = {
            {5}, {20, R}, {3}, {10, U}, {1}, {17, D, L}, {4}, {9, KeyEvent.VK_W}, {9, KeyEvent.VK_S}, {2},
            {1, L, R}, {18, KeyEvent.VK_A}, {1}, {9, D, R}, {2, U, D}, {10, KeyEvent.VK_D}};
        Level level = grass(80, 80);
        World w = new World(level, new Player(300, 300));
        Model m = new Model(300, 300);
        InputHandler in = new InputHandler();
        Screen s = new Screen(W, H);
        int tick = 0;
        for (int[] seg : plan) {
            in.releaseAll();
            for (int i = 1; i < seg.length; i++) in.press(seg[i]);
            int dx = 0, dy = 0;
            for (int i = 1; i < seg.length; i++) {
                if (seg[i] == R || seg[i] == KeyEvent.VK_D) dx++;
                if (seg[i] == L || seg[i] == KeyEvent.VK_A) dx--;
                if (seg[i] == D || seg[i] == KeyEvent.VK_S) dy++;
                if (seg[i] == U || seg[i] == KeyEvent.VK_W) dy--;
            }
            for (int t = 0; t < seg[0]; t++) {
                tick++;
                w.tick(in);
                m.step(dx, dy);
                String msg = "tick " + tick;
                assertEquals(m.x, w.player().x(), msg);
                assertEquals(m.y, w.player().y(), msg);
                assertEquals(m.dir, w.player().dir(), msg);
                assertEquals(m.frame, w.player().walkFrame(), msg);
                s.clear(0x000000);
                w.render(s, sheet);
                assertArrayEquals(expectedFrame(level, m), s.pixels, msg + " frame");
            }
        }
        assertEquals(121, tick);
    }

    @Test
    void neverMovedPlayerStillShowsEp09Tiles() {
        Level level = grass(40, 40);
        World w = new World(level, new Player(100, 100));
        for (int i = 0; i < 12; i++) w.tick(new InputHandler()); // idle ticks do not change the pose
        assertEquals(Mob.Dir.DOWN, w.player().dir());
        assertEquals(0, w.player().walkFrame());
        Screen s = new Screen(W, H);
        s.clear(0);
        w.render(s, sheet);
        assertArrayEquals(expectedFrame(level, new Model(100, 100)), s.pixels);
    }

    // ---- jar: scripted walk in each direction ----------------------------------------------------

    private static final Pattern LINE = Pattern.compile(
        "tick=(\\d+) x=(-?\\d+) y=(-?\\d+) camx=(-?\\d+) camy=(-?\\d+) dir=(down|up|left|right) frame=([01])");

    @Test
    void jarScriptedWalksDumpExpectedFrames(@TempDir Path dir) throws Exception {
        // idle 1..4 | DOWN 5..24 | idle 25..28 | UP 29..48 | idle 49..52 | LEFT 53..72 | idle 73..76 |
        // RIGHT 77..96 | idle 97..100 | UP+RIGHT diagonal 101..110 | idle 111..114 | LEFT+RIGHT cancel 115..118
        String script = String.join("\n",
            "5 press DOWN", "25 release DOWN", "29 press UP", "49 release UP", "53 press LEFT", "73 release LEFT",
            "77 press D", "97 release D", "101 press UP", "101 press RIGHT", "111 release UP", "111 release RIGHT",
            "115 press LEFT", "115 press RIGHT", "119 release LEFT", "119 release RIGHT") + "\n";
        Path sc = dir.resolve("walk.txt");
        Files.writeString(sc, script);
        Path dump = dir.resolve("frames");
        int ticks = 120;
        Run r = runJar("--headless", "--ticks", "" + ticks, "--script", sc.toString(), "--dump-dir", dump.toString(),
                "--scene", "game:grass:60,60@200,200", "--trace-player");
        assertEquals(0, r.exit, r.out);
        List<String> lines = new ArrayList<>();
        for (String l : r.out.split("\\R")) if (l.startsWith("tick=") && l.contains(" x=")) lines.add(l);
        assertEquals(ticks, lines.size(), r.out);

        Level level = grass(60, 60);
        Model m = new Model(200, 200);
        for (int t = 1; t <= ticks; t++) {
            int dx = 0, dy = 0;
            if (t >= 5 && t < 25) dy = 1;
            if (t >= 29 && t < 49) dy = -1;
            if (t >= 53 && t < 73) dx = -1;
            if (t >= 77 && t < 97) dx = 1;
            if (t >= 101 && t < 111) { dx = 1; dy = -1; }
            m.step(dx, dy);
            Matcher mt = LINE.matcher(lines.get(t - 1));
            assertTrue(mt.matches(), "trace line format: " + lines.get(t - 1));
            assertEquals(t, Integer.parseInt(mt.group(1)));
            assertEquals(m.x, Integer.parseInt(mt.group(2)), "x at tick " + t);
            assertEquals(m.y, Integer.parseInt(mt.group(3)), "y at tick " + t);
            assertEquals(m.x - 72, Integer.parseInt(mt.group(4)));
            assertEquals(m.y - 52, Integer.parseInt(mt.group(5)));
            assertEquals(m.dir.name().toLowerCase(), mt.group(6), "dir at tick " + t);
            assertEquals(m.frame, Integer.parseInt(mt.group(7)), "frame at tick " + t);
            assertArrayEquals(expectedFrame(level, m), readFrame(dump.resolve(String.format("frame-%05d.png", t - 1))),
                    "dumped frame of tick " + t);
        }
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

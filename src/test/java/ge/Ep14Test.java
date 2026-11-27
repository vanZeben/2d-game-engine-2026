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
 * Episode 14 check: "Animated Tiles & Swimming Animation"
 * SPEC: water cycles its frames on a timer; a player on water draws as swimming (lower half hidden, a ripple);
 *       a scripted walk into water and out again switches state.
 *
 * CONTRACT the builder must satisfy (earlier contracts stay; the 4-argument Level.render and every call that
 * passes no time keep drawing water frame 0, so Ep08/Ep11/Ep12/Ep13 are unaffected)
 * -------------------------------------------------------------------------------------------------------------
 * 1. Water animation. Water's frame f in {0,1,2} is the sheet tile (f, 5) drawn with Tile.WATER's colours.
 *    Public constants on ge.level.Tile: WATER_FRAMES = 3, WATER_FRAME_TICKS = 15, and
 *      public static int waterFrame(long tick)  ==  (int) ((tick / 15) % 3)      (tick >= 0)
 *    i.e. frame 0 for ticks 0..14, frame 1 for 15..29, frame 2 for 30..44, frame 0 again from 45 (cycle 0,1,2,0).
 *    Only WATER animates; every other tile is unchanged.
 * 2. ge.level.Level gets   public void render(Screen, SpriteSheet, int xOffset, int yOffset, long tick)
 *    identical to the 4-argument render except water cells use frame waterFrame(tick). The 4-argument render is
 *    exactly render(..., 0L).
 * 3. ge.World gets   public long tickCount()   = number of World.tick(InputHandler) calls so far (0 for a new
 *    world); World.render(Screen, SpriteSheet) draws its level with render(..., tickCount()). Game renders after
 *    its tick, so the headless frame dumped after tick T (frame-(T-1).png) shows water frame waterFrame(T).
 * 4. Swimming. ge.entity.Mob gets   public boolean isSwimming()  : true iff the mob has a level and the tile
 *    containing world pixel (x + (BOX_X0+BOX_X1)/2, y + (BOX_Y0+BOX_Y1)/2)  (the feet-box centre; integer
 *    division, floorDiv by 8 for the cell) is Tile.WATER. Dependent only on position and level, so it flips the
 *    tick the player's position crosses the cell edge.
 * 5. Swimming pose. ge.entity.Player gets the overload
 *      public static void drawPose(Screen, SpriteSheet, int sx, int sy, Dir dir, int walkFrame, boolean swimming)
 *    (swimming=false == the existing 6-argument method, which stays). With swimming=true the sprite box
 *    (sx..sx+15, sy..sy+15) shows: rows 0..7 = exactly the normal pose's rows 0..7 (facing/walk frame/mirroring
 *    as before, no clear); rows 8..15 = NOT the pose (whatever was on screen stays) except a ripple: the sheet
 *    tile (2,5) drawn at (sx, sy+8) unmirrored and again at (sx+8, sy+8) with Screen.MIRROR_X, both with colours
 *    {TRANSPARENT, TRANSPARENT, Palette.index(5,5,5), TRANSPARENT} (only sheet colour-index-2 pixels are drawn).
 *    Player.render uses swimming = isSwimming().
 * 6. Scene: game: scenes accept the new option  :water=TX,TY;TX,TY;...  (same syntax as :stone=) making those
 *    cells Tile.WATER (outside the level ignored). :stone= and :water= may each appear at most once, in either
 *    order, after the spawn; malformed -> IllegalArgumentException from Scene.parse. If a cell is in both lists
 *    the result is not pinned. Document in the Scene javadoc.
 * 7. --trace-player lines become `tick=T x=X y=Y camx=CX camy=CY dir=D frame=F swim=S` (S = 0|1 from isSwimming()
 *    after that tick's update); earlier fields unchanged (append-only).
 */
public class Ep14Test {

    private static final Path JAR = Paths.get("build/game.jar");
    private static final int W = Game.WIDTH, H = Game.HEIGHT;
    private static SpriteSheet sheet;
    private static BufferedImage sheetImage;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = Ep14Test.class.getResourceAsStream("/sprite_sheet.png")) {
            sheetImage = ImageIO.read(in);
        }
        sheet = SpriteSheet.load("/sprite_sheet.png");
    }

    private static Level grass(int w, int h) {
        Level l = new Level(w, h);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) l.setTile(x, y, Tile.GRASS);
        return l;
    }

    private static Level fill(int w, int h, Tile t) {
        Level l = new Level(w, h);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) l.setTile(x, y, t);
        return l;
    }

    private static int[] render(Level l, int xo, int yo, long tick) {
        Screen s = new Screen(W, H);
        s.clear(0x000000);
        l.render(s, sheet, xo, yo, tick);
        return s.pixels.clone();
    }

    private static int expectedWaterFrame(long tick) {
        return (int) ((tick / 15) % 3);
    }

    // ---- water frames --------------------------------------------------------------------------

    @Test
    void waterFrameConstantsAndFunction() {
        assertEquals(3, Tile.WATER_FRAMES);
        assertEquals(15, Tile.WATER_FRAME_TICKS);
        for (long t = 0; t < 200; t++) assertEquals(expectedWaterFrame(t), Tile.waterFrame(t), "tick " + t);
        assertEquals(0, Tile.waterFrame(14));
        assertEquals(1, Tile.waterFrame(15));
        assertEquals(2, Tile.waterFrame(30));
        assertEquals(0, Tile.waterFrame(45));
    }

    @Test
    void waterFramesDifferInTheSheet() {
        // sanity of the test itself: the three sheet tiles (0,5) (1,5) (2,5) are three distinct images
        for (int a = 0; a < 3; a++)
            for (int b = a + 1; b < 3; b++) {
                boolean same = true;
                for (int y = 0; y < 8 && same; y++)
                    for (int x = 0; x < 8; x++)
                        if ((sheetImage.getRGB(a * 8 + x, 40 + y) & 0xff) != (sheetImage.getRGB(b * 8 + x, 40 + y) & 0xff)) {
                            same = false;
                            break;
                        }
                assertFalse(same, "water frames " + a + " and " + b);
            }
    }

    @Test
    void levelRenderDrawsTheCurrentWaterFrame() {
        Level water = fill(W / 8, H / 8, Tile.WATER); // covers the whole screen
        long[] ticks = {0, 1, 14, 15, 16, 29, 30, 44, 45, 59, 60, 74, 75, 1000, 1005};
        List<int[]> seen = new ArrayList<>();
        for (long t : ticks) {
            Screen exp = new Screen(W, H);
            exp.clear(0x000000);
            for (int cy = 0; cy < H / 8; cy++)
                for (int cx = 0; cx < W / 8; cx++)
                    exp.drawPaletteTile(sheet, expectedWaterFrame(t), 5, cx * 8, cy * 8, Tile.WATER.colours());
            assertArrayEquals(exp.pixels, render(water, 0, 0, t), "water at tick " + t);
            seen.add(exp.pixels);
        }
        assertFalse(java.util.Arrays.equals(seen.get(2), seen.get(3)), "frame 0 and 1 differ on screen");
        assertFalse(java.util.Arrays.equals(seen.get(3), seen.get(7)), "frame 1 and 2 differ on screen");
    }

    @Test
    void fourArgumentRenderIsFrameZeroAndOtherTilesDoNotAnimate() {
        Level l = Level.generate(40, 40, 7);
        Screen s = new Screen(W, H);
        s.clear(0x000000);
        l.render(s, sheet, 13, -5);
        assertArrayEquals(s.pixels, render(l, 13, -5, 0), "4-arg == tick 0");
        assertArrayEquals(s.pixels, render(l, 13, -5, 14), "still frame 0 at tick 14");
        assertFalse(java.util.Arrays.equals(s.pixels, render(l, 13, -5, 15)), "water changes at tick 15 (level has water)");
        Level dry = grass(30, 30);
        dry.setTile(3, 3, Tile.STONE);
        assertArrayEquals(render(dry, 0, 0, 0), render(dry, 0, 0, 20), "no water, nothing animates");
        assertArrayEquals(render(dry, 0, 0, 0), render(dry, 0, 0, 47));
    }

    // ---- World timer -----------------------------------------------------------------------------

    @Test
    void worldCountsTicksAndAnimatesWater() {
        Level l = grass(30, 30);
        for (int cy = 5; cy < 9; cy++) for (int cx = 4; cx < 9; cx++) l.setTile(cx, cy, Tile.WATER);
        World w = new World(l, new Player(120, 120));
        assertEquals(0, w.tickCount());
        InputHandler in = new InputHandler();
        for (int t = 0; t <= 100; t++) {
            if (t > 0) w.tick(in);
            assertEquals(t, w.tickCount());
            Screen s = new Screen(W, H);
            s.clear(0x000000);
            w.render(s, sheet);
            Screen e = new Screen(W, H);
            e.clear(0x000000);
            l.render(e, sheet, w.cameraX(), w.cameraY(), t);
            w.player().render(e, sheet, w.cameraX(), w.cameraY());
            assertArrayEquals(e.pixels, s.pixels, "world frame after " + t + " ticks");
        }
    }

    // ---- on water ---------------------------------------------------------------------------------

    private static final int CX = (Player.BOX_X0 + Player.BOX_X1) / 2, CY = (Player.BOX_Y0 + Player.BOX_Y1) / 2;

    private static boolean modelSwim(Level l, int x, int y) {
        return l.getTile(Math.floorDiv(x + CX, 8), Math.floorDiv(y + CY, 8)) == Tile.WATER;
    }

    @Test
    void isSwimmingUsesTheFeetBoxCentreCell() {
        Level l = grass(20, 20);
        for (int cy = 5; cy <= 8; cy++) for (int cx = 5; cx <= 8; cx++) l.setTile(cx, cy, Tile.WATER); // pixels 40..71
        Player lone = new Player(50, 50);
        assertFalse(lone.isSwimming(), "no level: never swimming");
        World w = new World(l, lone);
        for (int x = 20; x <= 90; x++) {
            for (int y : new int[] {20, 28, 29, 30, 31, 32, 50, 59, 60, 61, 62, 63, 64, 70, 90}) {
                Player p = new Player(x, y);
                new World(l, p);
                assertEquals(modelSwim(l, x, y), p.isSwimming(), "at " + x + "," + y);
            }
        }
        // explicit edges: centre pixel (x+CX, y+CY) crosses the water edge at pixel 40 / 72
        assertFalse(at(l, 40 - CX - 1, 50).isSwimming());
        assertTrue(at(l, 40 - CX, 50).isSwimming());
        assertTrue(at(l, 72 - CX - 1, 50).isSwimming());
        assertFalse(at(l, 72 - CX, 50).isSwimming());
        assertFalse(at(l, 50, 40 - CY - 1).isSwimming());
        assertTrue(at(l, 50, 40 - CY).isSwimming());
        assertFalse(at(l, 50, 72 - CY).isSwimming());
    }

    private static Player at(Level l, int x, int y) {
        Player p = new Player(x, y);
        new World(l, p);
        return p;
    }

    @Test
    void stoneAndGrassAndVoidAreNotWater() {
        Level l = grass(10, 10);
        l.setTile(2, 2, Tile.STONE);
        assertFalse(at(l, 16 - CX, 16 - CY).isSwimming());
        assertFalse(at(l, -100, -100).isSwimming());
        assertFalse(at(l, 30, 30).isSwimming());
    }

    // ---- the pose ---------------------------------------------------------------------------------

    private static int sheetIndex(Mob.Dir dir, int frame, int dx, int dy) {
        int col;
        boolean mirror;
        switch (dir) {
            case DOWN -> { col = 0; mirror = frame == 1; }
            case UP -> { col = 2; mirror = frame == 1; }
            case RIGHT -> { col = frame == 0 ? 4 : 6; mirror = false; }
            default -> { col = frame == 0 ? 4 : 6; mirror = true; }
        }
        return (sheetImage.getRGB(col * 8 + (mirror ? 15 - dx : dx), 28 * 8 + dy) & 0xff) >> 6;
    }

    private static final int RIPPLE = Palette.rgb(Palette.index(5, 5, 5));

    /** Applies the contract's sprite to a 160x120 background, sprite box top-left at screen (72, 52). */
    private static void applyModel(int[] px, Mob.Dir dir, int frame, boolean swimming) {
        for (int dy = 0; dy < (swimming ? 8 : 16); dy++)
            for (int dx = 0; dx < 16; dx++) {
                int c = Player.COLOURS[sheetIndex(dir, frame, dx, dy)];
                if (c != Palette.TRANSPARENT) px[(52 + dy) * W + 72 + dx] = Palette.rgb(c);
            }
        if (!swimming) return;
        for (int dy = 0; dy < 8; dy++)
            for (int dx = 0; dx < 16; dx++) {
                int tx = dx < 8 ? dx : 7 - (dx - 8); // right half is the mirrored tile
                if (((sheetImage.getRGB(2 * 8 + tx, 5 * 8 + dy) & 0xff) >> 6) == 2) px[(60 + dy) * W + 72 + dx] = RIPPLE;
            }
    }

    @Test
    void drawPoseSwimmingHidesLowerHalfAndAddsRipple() {
        for (Mob.Dir d : Mob.Dir.values())
            for (int f = 0; f < 2; f++) {
                for (boolean swim : new boolean[] {false, true}) {
                    Level bg = fill(20, 20, Tile.WATER);
                    Screen s = new Screen(W, H);
                    s.clear(0x000000);
                    bg.render(s, sheet, 0, 0, 20);
                    int[] exp = s.pixels.clone();
                    Player.drawPose(s, sheet, 72, 52, d, f, swim);
                    applyModel(exp, d, f, swim);
                    assertArrayEquals(exp, s.pixels, d + " frame " + f + " swimming=" + swim);
                }
                Screen a = new Screen(W, H), b = new Screen(W, H);
                a.clear(0x101010);
                b.clear(0x101010);
                Player.drawPose(a, sheet, 72, 52, d, f);
                Player.drawPose(b, sheet, 72, 52, d, f, false);
                assertArrayEquals(a.pixels, b.pixels, "6-arg == swimming=false");
            }
        // the swimming pose really differs from the dry one and has ripple pixels in the lower half
        Screen dry = new Screen(W, H), wet = new Screen(W, H);
        dry.clear(0);
        wet.clear(0);
        Player.drawPose(dry, sheet, 72, 52, Mob.Dir.DOWN, 0, false);
        Player.drawPose(wet, sheet, 72, 52, Mob.Dir.DOWN, 0, true);
        assertFalse(java.util.Arrays.equals(dry.pixels, wet.pixels));
        int ripple = 0;
        for (int y = 60; y < 68; y++) for (int x = 72; x < 88; x++) if (wet.pixels[y * W + x] == RIPPLE) ripple++;
        assertTrue(ripple > 0, "ripple pixels drawn");
    }

    @Test
    void playerRendersSwimmingOnlyOnWater() {
        Level l = grass(30, 30);
        for (int cy = 10; cy < 20; cy++) for (int cx = 10; cx < 20; cx++) l.setTile(cx, cy, Tile.WATER);
        for (int[] pos : new int[][] {{110, 110}, {110, 60}, {40, 40}, {79 - CX, 79 - CY}, {80 - CX, 80 - CY}}) {
            World w = new World(l, new Player(pos[0], pos[1]));
            long t = 33; // water frame 2
            for (int i = 0; i < t; i++) w.tick(new InputHandler());
            Screen s = new Screen(W, H);
            s.clear(0x000000);
            w.render(s, sheet);
            int[] exp = render(l, w.cameraX(), w.cameraY(), t);
            applyModel(exp, Mob.Dir.DOWN, 0, modelSwim(l, pos[0], pos[1]));
            assertArrayEquals(exp, s.pixels, "player at " + pos[0] + "," + pos[1]);
        }
    }

    // ---- scene option -------------------------------------------------------------------------------

    @Test
    void sceneWaterOption() {
        World w = Scene.parse("game:grass:10,10@8,8:water=2,3;4,5;99,99:stone=6,6").newWorld();
        assertSame(Tile.WATER, w.level().getTile(2, 3));
        assertSame(Tile.WATER, w.level().getTile(4, 5));
        assertSame(Tile.STONE, w.level().getTile(6, 6));
        assertSame(Tile.GRASS, w.level().getTile(3, 3));
        World v = Scene.parse("game:grass:10,10@8,8:stone=1,1:water=0,0").newWorld();
        assertSame(Tile.WATER, v.level().getTile(0, 0));
        assertSame(Tile.STONE, v.level().getTile(1, 1));
        assertSame(Tile.GRASS, Scene.parse("game:grass:10,10@8,8").newWorld().level().getTile(0, 0));
        assertSame(Tile.WATER, Scene.parse("game:gen:10,10,3@8,8:water=0,0").newWorld().level().getTile(0, 0));
        for (String bad : new String[] {"game:grass:10,10@8,8:water=", "game:grass:10,10@8,8:water=1", "game:grass:10,10@8,8:water=1,x",
                "game:grass:10,10@8,8:water=1,1:water=2,2", "game:grass:10,10@8,8:stone=1,1:stone=2,2",
                "game:grass:10,10@8,8:lava=1,1", "game:grass:10,10@8,8:water=1,1;"})
            assertThrows(IllegalArgumentException.class, () -> Scene.parse(bad), bad);
    }

    // ---- jar: animation dump ------------------------------------------------------------------------

    private static final Pattern LINE = Pattern.compile(
        "tick=(\\d+) x=(-?\\d+) y=(-?\\d+) camx=(-?\\d+) camy=(-?\\d+) dir=(down|up|left|right) frame=([01]) swim=([01])");

    private static String waterOpt(int cx0, int cy0, int cx1, int cy1) {
        StringBuilder sb = new StringBuilder("water=");
        for (int y = cy0; y <= cy1; y++) for (int x = cx0; x <= cx1; x++) sb.append(sb.length() > 6 ? ";" : "").append(x).append(',').append(y);
        return sb.toString();
    }

    @Test
    void jarDumpsAnimatedWater(@TempDir Path dir) throws Exception {
        Path dump = dir.resolve("frames");
        int ticks = 50;
        Run r = runJar("--headless", "--ticks", "" + ticks, "--dump-dir", dump.toString(),
                "--scene", "game:grass:20,20@80,80:" + waterOpt(4, 6, 9, 9), "--trace-player");
        assertEquals(0, r.exit, r.out);
        Level l = grass(20, 20);
        for (int cy = 6; cy <= 9; cy++) for (int cx = 4; cx <= 9; cx++) l.setTile(cx, cy, Tile.WATER);
        int[][] frames = new int[ticks][];
        for (int t = 1; t <= ticks; t++) {
            frames[t - 1] = readFrame(dump.resolve(String.format("frame-%05d.png", t - 1)));
            int[] exp = render(l, 80 + 8 - W / 2, 80 + 8 - H / 2, t);
            applyModel(exp, Mob.Dir.DOWN, 0, false);
            assertArrayEquals(exp, frames[t - 1], "dumped frame of tick " + t + " (water frame " + expectedWaterFrame(t) + ")");
        }
        assertArrayEquals(frames[0], frames[13], "water frame 0 through tick 14");
        assertFalse(java.util.Arrays.equals(frames[13], frames[14]), "frame changes at tick 15");
        assertFalse(java.util.Arrays.equals(frames[14], frames[29]), "and again at tick 30");
        assertArrayEquals(frames[0], frames[44], "back to frame 0 at tick 45");
        for (String line : r.out.split("\\R")) if (line.startsWith("tick=") && line.contains(" x=")) assertTrue(line.endsWith(" swim=0"), line);
    }

    // ---- jar: scripted walk into water and out ---------------------------------------------------------

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

    @Test
    void jarScriptedWalkIntoWaterAndOut(@TempDir Path dir) throws Exception {
        // RIGHT 5..44 from x=120 to x=160 (water cells 20..23 x 14..17 start at pixel 160; centre = x+7), idle 45..48,
        // LEFT 49..88 back to x=120, idle 89..92
        Path sc = dir.resolve("swim.txt");
        Files.writeString(sc, String.join("\n", "5 press RIGHT", "45 release RIGHT", "49 press LEFT", "89 release LEFT") + "\n");
        Path dump = dir.resolve("frames");
        int ticks = 92;
        Run r = runJar("--headless", "--ticks", "" + ticks, "--script", sc.toString(), "--dump-dir", dump.toString(),
                "--scene", "game:grass:30,30@120,112:" + waterOpt(20, 14, 23, 17), "--trace-player");
        assertEquals(0, r.exit, r.out);
        List<String> lines = new ArrayList<>();
        for (String l : r.out.split("\\R")) if (l.startsWith("tick=") && l.contains(" x=")) lines.add(l);
        assertEquals(ticks, lines.size(), r.out);
        Level level = grass(30, 30);
        for (int cy = 14; cy <= 17; cy++) for (int cx = 20; cx <= 23; cx++) level.setTile(cx, cy, Tile.WATER);
        Model m = new Model(120, 112);
        int swimTicks = 0, toggles = 0;
        boolean prev = false;
        for (int t = 1; t <= ticks; t++) {
            m.step(t >= 5 && t < 45 ? 1 : t >= 49 && t < 89 ? -1 : 0, 0);
            boolean swim = modelSwim(level, m.x, m.y);
            if (swim) swimTicks++;
            if (swim != prev) toggles++;
            prev = swim;
            Matcher mt = LINE.matcher(lines.get(t - 1));
            assertTrue(mt.matches(), "trace line format: " + lines.get(t - 1));
            assertEquals(m.x, Integer.parseInt(mt.group(2)), "x at tick " + t);
            assertEquals(m.y, Integer.parseInt(mt.group(3)), "y at tick " + t);
            assertEquals(m.dir.name().toLowerCase(), mt.group(6), "dir at tick " + t);
            assertEquals(m.frame, Integer.parseInt(mt.group(7)), "frame at tick " + t);
            assertEquals(swim ? 1 : 0, Integer.parseInt(mt.group(8)), "swim at tick " + t + " x=" + m.x);
            int[] exp = render(level, m.x + 8 - W / 2, m.y + 8 - H / 2, t);
            applyModel(exp, m.dir, m.frame, swim);
            assertArrayEquals(exp, readFrame(dump.resolve(String.format("frame-%05d.png", t - 1))), "dumped frame of tick " + t);
        }
        assertEquals(2, toggles, "walks in once and out once");
        assertTrue(swimTicks >= 10 && swimTicks < ticks, "swam for a while: " + swimTicks);
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

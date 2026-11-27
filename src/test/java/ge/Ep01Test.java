package ge;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import java.util.stream.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/*
 * Episode 1 check: "Setting up the JFrame".
 * SPEC: a 480x360 window titled with the game name opens; a fixed 60 tick/s loop runs;
 * a headless run for 600 ticks reports 600 +- 1 ticks and a frame count.
 *
 * CONTRACT the builder must satisfy
 * ---------------------------------
 * 1. Class ge.Game with public static final constants:
 *      String NAME            non-blank game name; the window title equals it
 *      int WIDTH = 160, HEIGHT = 120, SCALE = 3     (window = 480x360)
 *      int TICKS_PER_SECOND = 60
 * 2. Class ge.FixedStep, a no-display fixed-timestep accumulator (the seam of the game loop):
 *      public FixedStep()                       (uses Game.TICKS_PER_SECOND)
 *      public int advance(long elapsedNanos)    adds elapsed time, returns how many whole
 *                                               ticks are now due and consumes them; the
 *                                               remainder carries over to the next call.
 * 3. Entry point ge.Main, packaged as Main-Class of build/game.jar:
 *      java -jar build/game.jar                 opens the window (not tested; needs a display)
 *      java -jar build/game.jar --headless --ticks N [--dump-dir DIR]
 *        - no display needed (run with -Djava.awt.headless=true); runs exactly N ticks as fast
 *          as possible (no real-time sleeping required), rendering one frame per tick;
 *        - prints exactly one stdout line of the form:   ticks=N frames=M
 *          (decimal integers, single spaces; other output lines may exist but not match it);
 *        - exit code 0;
 *        - with --dump-dir DIR (created if missing) writes every rendered frame as PNG named
 *          frame-00000.png, frame-00001.png, ... ; each image is exactly 160x120 (the
 *          unscaled screen buffer), and the number of PNG files equals M.
 *      Frame count M must be >= 1 and <= N+1.
 */
public class Ep01Test {

    private static final Path JAR = Paths.get("build/game.jar");
    private static final Pattern LINE = Pattern.compile("^ticks=(\\d+) frames=(\\d+)$", Pattern.MULTILINE);

    private record Run(int exit, String out) {}

    private static Run runJar(String... args) throws Exception {
        assertTrue(Files.exists(JAR), "build/game.jar must exist");
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        var cmd = new java.util.ArrayList<String>(java.util.List.of(java, "-Djava.awt.headless=true", "-jar", JAR.toString()));
        cmd.addAll(java.util.List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail("headless run timed out");
        }
        return new Run(p.exitValue(), new String(out, StandardCharsets.UTF_8));
    }

    @Test
    void windowSizeAndTitleConstants() {
        assertEquals(160, Game.WIDTH);
        assertEquals(120, Game.HEIGHT);
        assertEquals(3, Game.SCALE);
        assertEquals(480, Game.WIDTH * Game.SCALE);
        assertEquals(360, Game.HEIGHT * Game.SCALE);
        assertNotNull(Game.NAME);
        assertFalse(Game.NAME.isBlank(), "game name must not be blank");
        assertEquals(60, Game.TICKS_PER_SECOND);
    }

    @Test
    void fixedStepYieldsSixtyTicksPerSimulatedSecond() {
        FixedStep s = new FixedStep();
        int total = 0;
        for (int i = 0; i < 1000; i++) total += s.advance(1_000_000L); // 1000 x 1 ms
        assertEquals(60, total, 1, "1 simulated second must give 60 ticks");
    }

    @Test
    void fixedStepCarriesRemainderAndNeverCatchesUpEarly() {
        FixedStep s = new FixedStep();
        long tick = 1_000_000_000L / 60;
        assertEquals(0, s.advance(tick / 2), "half a tick is not a tick");
        assertEquals(1, s.advance(tick / 2 + 1), "remainder carries over");
        assertEquals(3, s.advance(3 * tick + 1000), "three whole ticks at once");
        assertEquals(0, s.advance(0));
    }

    @Test
    void headlessRunReports600TicksAndFrameCount() throws Exception {
        Run r = runJar("--headless", "--ticks", "600");
        assertEquals(0, r.exit, "exit code; output:\n" + r.out);
        Matcher m = LINE.matcher(r.out);
        assertTrue(m.find(), "expected a line 'ticks=N frames=M' in:\n" + r.out);
        int ticks = Integer.parseInt(m.group(1));
        int frames = Integer.parseInt(m.group(2));
        assertEquals(600, ticks, 1);
        assertTrue(frames >= 1 && frames <= 601, "frames=" + frames);
    }

    @Test
    void headlessRunHonoursTickArgument() throws Exception {
        Run r = runJar("--headless", "--ticks", "37");
        assertEquals(0, r.exit, r.out);
        Matcher m = LINE.matcher(r.out);
        assertTrue(m.find(), r.out);
        assertEquals(37, Integer.parseInt(m.group(1)), 1);
    }

    @Test
    void dumpDirReceivesPngFramesOfScreenSize(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("frames");
        Run r = runJar("--headless", "--ticks", "10", "--dump-dir", dir.toString());
        assertEquals(0, r.exit, r.out);
        Matcher m = LINE.matcher(r.out);
        assertTrue(m.find(), r.out);
        int frames = Integer.parseInt(m.group(2));
        assertTrue(frames >= 1);
        Path first = dir.resolve("frame-00000.png");
        assertTrue(Files.exists(first), "frame-00000.png missing");
        try (Stream<Path> s = Files.list(dir)) {
            long pngs = s.filter(p -> p.getFileName().toString().matches("frame-\\d{5}\\.png")).count();
            assertEquals(frames, pngs, "PNG count must equal reported frames");
        }
        BufferedImage img = ImageIO.read(first.toFile());
        assertNotNull(img, "frame must be a readable PNG");
        assertEquals(160, img.getWidth());
        assertEquals(120, img.getHeight());
    }
}

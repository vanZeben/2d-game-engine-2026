package ge;

import static org.junit.jupiter.api.Assertions.*;

import ge.input.InputHandler;
import java.awt.Canvas;
import java.awt.event.KeyEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/*
 * Episode 4 check: "Basic Input Handling".
 * SPEC: arrow keys and WASD map to up/down/left/right; scripted input reports the right pressed state.
 *
 * CONTRACT the builder must satisfy (full script format: docs/script-format.md, written by the test-writer)
 * ----------------------------------------------------------------------------------------------------
 * 1. New class ge.input.InputHandler (src/main/java/ge/input/InputHandler.java), public, no-arg constructor,
 *    implements java.awt.event.KeyListener:
 *      public void press(int keyCode)      // keyCode = a KeyEvent.VK_* value
 *      public void release(int keyCode)
 *      public boolean up()  down()  left()  right()
 *    - UP or W held -> up(); DOWN or S -> down(); LEFT or A -> left(); RIGHT or D -> right().
 *    - Each key is tracked on its own: with both keys of a pair held, releasing one leaves the direction
 *      pressed; it clears only when both are released. Opposite directions are independent (both may be true).
 *    - press of a held key and release of an unheld key are no-ops; unrelated key codes (SPACE, Q, ...) change
 *      nothing and do not throw. Unknown/odd codes (0, -1, huge) also do not throw.
 *    - KeyListener: keyPressed(e)=press(e.getKeyCode()), keyReleased(e)=release(e.getKeyCode()), keyTyped does
 *      nothing. So the same object serves the window (addKeyListener) and the script runner.
 *    - A fresh handler has all four false.
 *
 * 2. CLI (extends the launch contract; existing flags unchanged):
 *      java -jar build/game.jar --headless --ticks N [--script FILE] --trace-input
 *    --script FILE: input script, see docs/script-format.md. Ticks are numbered 1..N. An event at tick T is
 *    applied immediately before tick T's update, so tick T's trace line already reflects it.
 *    --trace-input: prints to stdout, for each tick 1..N in order, after that tick's update, one line
 *        tick=T up=U down=D left=L right=R        (U,D,L,R are 1 or 0; single spaces; no other text on the line)
 *    before the final "ticks=N frames=M" line (which stays). Without --trace-input no "tick=" lines are printed.
 *    Without --script all lines show zeros.
 *    A missing/unreadable script file or a malformed script (checked entirely before running, even if the bad
 *    line lies beyond N) prints a message to stderr containing the script path and, for a bad line, "line <n>",
 *    prints no "ticks=" line, and exits 2.
 *    The default scene's rendering is not constrained by this episode.
 *
 * The expected states below come from an independent reference model inside this test plus hand-written spot
 * checks, never from engine output.
 */
public class Ep04Test {

    private static final Path JAR = Paths.get("build/game.jar");

    // ---- unit checks on InputHandler ----------------------------------------------------------

    private static boolean[] state(InputHandler h) {
        return new boolean[] {h.up(), h.down(), h.left(), h.right()};
    }

    private static void assertState(InputHandler h, boolean u, boolean d, boolean l, boolean r, String msg) {
        assertArrayEquals(new boolean[] {u, d, l, r}, state(h), msg);
    }

    @Test
    void freshHandlerHasNothingPressed() {
        assertState(new InputHandler(), false, false, false, false, "fresh");
    }

    @Test
    void arrowsAndWasdMapToDirections() {
        int[][] map = {
            {KeyEvent.VK_UP, 0}, {KeyEvent.VK_W, 0},
            {KeyEvent.VK_DOWN, 1}, {KeyEvent.VK_S, 1},
            {KeyEvent.VK_LEFT, 2}, {KeyEvent.VK_A, 2},
            {KeyEvent.VK_RIGHT, 3}, {KeyEvent.VK_D, 3},
        };
        for (int[] m : map) {
            InputHandler h = new InputHandler();
            h.press(m[0]);
            boolean[] exp = new boolean[4];
            exp[m[1]] = true;
            assertArrayEquals(exp, state(h), "after press " + KeyEvent.getKeyText(m[0]));
            h.release(m[0]);
            assertArrayEquals(new boolean[4], state(h), "after release " + KeyEvent.getKeyText(m[0]));
        }
    }

    @Test
    void bothKeysOfAPairHeldThenOneReleasedStaysPressed() {
        int[][] pairs = {
            {KeyEvent.VK_UP, KeyEvent.VK_W, 0}, {KeyEvent.VK_DOWN, KeyEvent.VK_S, 1},
            {KeyEvent.VK_LEFT, KeyEvent.VK_A, 2}, {KeyEvent.VK_RIGHT, KeyEvent.VK_D, 3},
        };
        for (int[] p : pairs) {
            for (int first = 0; first < 2; first++) {
                int a = p[first], b = p[1 - first];
                InputHandler h = new InputHandler();
                h.press(a);
                h.press(b);
                boolean[] on = new boolean[4];
                on[p[2]] = true;
                assertArrayEquals(on, state(h));
                h.release(a);
                assertArrayEquals(on, state(h), "still held via the other key");
                h.release(b);
                assertArrayEquals(new boolean[4], state(h), "both released");
            }
        }
    }

    @Test
    void duplicatePressAndStrayReleaseAreNoOps() {
        InputHandler h = new InputHandler();
        h.release(KeyEvent.VK_UP); // never pressed
        assertState(h, false, false, false, false, "stray release");
        h.press(KeyEvent.VK_UP);
        h.press(KeyEvent.VK_UP);
        h.release(KeyEvent.VK_UP);
        assertState(h, false, false, false, false, "a single release clears a doubly-pressed key");
    }

    @Test
    void oppositeAndSimultaneousDirectionsAreIndependent() {
        InputHandler h = new InputHandler();
        h.press(KeyEvent.VK_LEFT);
        h.press(KeyEvent.VK_D);
        h.press(KeyEvent.VK_UP);
        assertState(h, true, false, true, true, "three held");
        h.release(KeyEvent.VK_LEFT);
        assertState(h, true, false, false, true, "left released only");
    }

    @Test
    void unrelatedAndOddKeysDoNothing() {
        InputHandler h = new InputHandler();
        int[] others = {KeyEvent.VK_SPACE, KeyEvent.VK_Q, KeyEvent.VK_E, KeyEvent.VK_SHIFT, KeyEvent.VK_ENTER,
            KeyEvent.VK_ESCAPE, KeyEvent.VK_F1, KeyEvent.VK_1, KeyEvent.VK_UNDEFINED, 0, -1, Integer.MAX_VALUE};
        for (int k : others) {
            assertDoesNotThrow(() -> h.press(k));
            assertState(h, false, false, false, false, "press " + k);
        }
        h.press(KeyEvent.VK_W);
        for (int k : others) assertDoesNotThrow(() -> h.release(k));
        assertState(h, true, false, false, false, "releasing unrelated keys leaves W held");
    }

    @Test
    void worksAsARealKeyListener() {
        InputHandler h = new InputHandler();
        java.awt.event.KeyListener l = h; // must be a KeyListener
        Canvas src = new Canvas();
        l.keyPressed(key(src, KeyEvent.KEY_PRESSED, KeyEvent.VK_RIGHT));
        assertState(h, false, false, false, true, "keyPressed RIGHT");
        l.keyPressed(key(src, KeyEvent.KEY_PRESSED, KeyEvent.VK_S));
        assertState(h, false, true, false, true, "keyPressed S");
        l.keyTyped(key(src, KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, 'a')); // must not change anything
        assertState(h, false, true, false, true, "keyTyped ignored");
        l.keyReleased(key(src, KeyEvent.KEY_RELEASED, KeyEvent.VK_RIGHT));
        assertState(h, false, true, false, false, "keyReleased RIGHT");
        l.keyReleased(key(src, KeyEvent.KEY_RELEASED, KeyEvent.VK_S));
        assertState(h, false, false, false, false, "keyReleased S");
    }

    private static KeyEvent key(Canvas src, int id, int code) {
        return key(src, id, code, KeyEvent.CHAR_UNDEFINED);
    }

    private static KeyEvent key(Canvas src, int id, int code, char c) {
        return new KeyEvent(src, id, 0L, 0, code, c);
    }

    // ---- black-box checks through the jar -----------------------------------------------------

    private record Run(int exit, String out, String err) {}

    private static Run runJar(String... args) throws Exception {
        assertTrue(Files.exists(JAR), "build/game.jar must exist");
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        List<String> cmd = new ArrayList<>(List.of(javaBin, "-Djava.awt.headless=true", "-jar", JAR.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).start();
        // drain both streams concurrently to avoid pipe deadlock
        var errF = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8); }
            catch (java.io.IOException e) { return ""; }
        });
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail("run timed out");
        }
        return new Run(p.exitValue(), out, errF.get(10, TimeUnit.SECONDS));
    }

    private static Path write(Path dir, String name, String content) throws Exception {
        Path f = dir.resolve(name);
        Files.writeString(f, content);
        return f;
    }

    /** Parses "tick=T up=U down=D left=L right=R" lines into {T,U,D,L,R}; fails on any malformed tick line. */
    private static List<int[]> traceLines(String out) {
        List<int[]> rows = new ArrayList<>();
        for (String line : out.split("\\R")) {
            if (!line.startsWith("tick=")) continue;
            var m = java.util.regex.Pattern.compile("tick=(\\d+) up=([01]) down=([01]) left=([01]) right=([01])").matcher(line);
            assertTrue(m.matches(), "bad trace line: '" + line + "'");
            rows.add(new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5))});
        }
        return rows;
    }

    private static final String SCRIPT = String.join("\n",
        "# exercises all 8 keys, overlaps, same-tick events, releases, junk keys",
        "",
        "2 press UP",
        "4\tpress   W          # second key of the pair",
        "6 release UP",
        "8 release W",
        "10 press DOWN",
        "11 press S",
        "12 release S",
        "13 release DOWN",
        "14 press LEFT",
        "14 press RIGHT",
        "16 release LEFT",
        "17 press A",
        "18 release RIGHT",
        "19 press D",
        "20 release A",
        "20 release D",
        "22 press SPACE",
        "22 press SHIFT",
        "23 release SPACE",
        "24 press UP",
        "24 release UP",
        "26 release UP",
        "27 press UP",
        "27 press UP",
        "28 release UP",
        "29 press Q",
        "30 press W",
        "30 press DOWN",
        "   # indented comment line",
        "99 press LEFT", // beyond N: valid, never applied
        "");

    /** Independent reference model: set of held keys, events applied in file order before each tick. */
    private static List<int[]> reference(String script, int ticks) {
        List<String[]> events = new ArrayList<>();
        for (String raw : script.split("\\R")) {
            String line = raw.contains("#") ? raw.substring(0, raw.indexOf('#')) : raw;
            line = line.trim();
            if (line.isEmpty()) continue;
            events.add(line.split("\\s+"));
        }
        Set<String> held = new HashSet<>();
        List<int[]> rows = new ArrayList<>();
        int next = 0;
        for (int t = 1; t <= ticks; t++) {
            while (next < events.size() && Integer.parseInt(events.get(next)[0]) <= t) {
                String[] e = events.get(next++);
                if (e[1].equals("press")) held.add(e[2]); else held.remove(e[2]);
            }
            rows.add(new int[] {t,
                held.contains("UP") || held.contains("W") ? 1 : 0,
                held.contains("DOWN") || held.contains("S") ? 1 : 0,
                held.contains("LEFT") || held.contains("A") ? 1 : 0,
                held.contains("RIGHT") || held.contains("D") ? 1 : 0});
        }
        return rows;
    }

    private static String fmt(int[] r) {
        return "tick=" + r[0] + " up=" + r[1] + " down=" + r[2] + " left=" + r[3] + " right=" + r[4];
    }

    @Test
    void scriptedInputReportsThePressedStateEveryTick(@TempDir Path tmp) throws Exception {
        Path script = write(tmp, "in.txt", SCRIPT);
        Run r = runJar("--headless", "--ticks", "32", "--script", script.toString(), "--trace-input");
        assertEquals(0, r.exit, r.err);
        List<int[]> got = traceLines(r.out);
        List<int[]> exp = reference(SCRIPT, 32);
        assertEquals(32, got.size(), "one trace line per tick");
        for (int i = 0; i < exp.size(); i++) assertEquals(fmt(exp.get(i)), fmt(got.get(i)), "tick " + (i + 1));
        assertTrue(r.out.contains("ticks=32 frames=32"), r.out);
        // the "ticks=" summary line comes after the trace lines
        assertTrue(r.out.lastIndexOf("tick=32 ") < r.out.indexOf("ticks=32"), "summary after trace");
    }

    @Test
    void handWrittenSpotChecksOfTiming(@TempDir Path tmp) throws Exception {
        Path script = write(tmp, "in.txt", SCRIPT);
        Run r = runJar("--headless", "--ticks", "32", "--script", script.toString(), "--trace-input");
        assertEquals(0, r.exit, r.err);
        Map<Integer, String> by = new HashMap<>();
        for (int[] row : traceLines(r.out)) by.put(row[0], fmt(row));
        // a press at tick T is visible in tick T's line, and not in T-1
        assertEquals("tick=1 up=0 down=0 left=0 right=0", by.get(1));
        assertEquals("tick=2 up=1 down=0 left=0 right=0", by.get(2));
        assertEquals("tick=6 up=1 down=0 left=0 right=0", by.get(6)); // UP released, W still held
        assertEquals("tick=7 up=1 down=0 left=0 right=0", by.get(7));
        assertEquals("tick=8 up=0 down=0 left=0 right=0", by.get(8)); // a release at T is visible at T
        assertEquals("tick=12 up=0 down=1 left=0 right=0", by.get(12)); // S released, DOWN held
        assertEquals("tick=13 up=0 down=0 left=0 right=0", by.get(13));
        assertEquals("tick=14 up=0 down=0 left=1 right=1", by.get(14)); // two events one tick
        assertEquals("tick=17 up=0 down=0 left=1 right=1", by.get(17)); // A (LEFT released), RIGHT
        assertEquals("tick=20 up=0 down=0 left=0 right=0", by.get(20)); // A and D released together... (D held via 19) now gone
        assertEquals("tick=22 up=0 down=0 left=0 right=0", by.get(22)); // SPACE, SHIFT: nothing
        assertEquals("tick=24 up=0 down=0 left=0 right=0", by.get(24)); // press+release same tick, in order
        assertEquals("tick=27 up=1 down=0 left=0 right=0", by.get(27));
        assertEquals("tick=28 up=0 down=0 left=0 right=0", by.get(28)); // double press, single release
        assertEquals("tick=29 up=0 down=0 left=0 right=0", by.get(29));
        assertEquals("tick=30 up=1 down=1 left=0 right=0", by.get(30));
        assertEquals("tick=32 up=1 down=1 left=0 right=0", by.get(32)); // tick-99 event never applied
    }

    @Test
    void noScriptMeansAllZeroAndNoTraceMeansNoTraceLines(@TempDir Path tmp) throws Exception {
        Run r = runJar("--headless", "--ticks", "5", "--trace-input");
        assertEquals(0, r.exit, r.err);
        List<int[]> got = traceLines(r.out);
        assertEquals(5, got.size());
        for (int i = 0; i < 5; i++) assertEquals(fmt(new int[] {i + 1, 0, 0, 0, 0}), fmt(got.get(i)));

        Path script = write(tmp, "in.txt", "1 press UP\n");
        Run quiet = runJar("--headless", "--ticks", "5", "--script", script.toString());
        assertEquals(0, quiet.exit, quiet.err);
        assertTrue(traceLines(quiet.out).isEmpty(), "no tick= lines without --trace-input");
        assertTrue(quiet.out.contains("ticks=5 frames=5"), quiet.out);

        Run zero = runJar("--headless", "--ticks", "0", "--trace-input");
        assertEquals(0, zero.exit, zero.err);
        assertTrue(traceLines(zero.out).isEmpty());
    }

    @Test
    void scriptWithOnlyCommentsAndBlankLinesIsValid(@TempDir Path tmp) throws Exception {
        Path script = write(tmp, "in.txt", "\n# nothing\n\n   \t\n");
        Run r = runJar("--headless", "--ticks", "3", "--script", script.toString(), "--trace-input");
        assertEquals(0, r.exit, r.err);
        assertEquals(3, traceLines(r.out).size());
    }

    @Test
    void sameScriptTwiceGivesIdenticalTrace(@TempDir Path tmp) throws Exception {
        Path script = write(tmp, "in.txt", SCRIPT);
        Run a = runJar("--headless", "--ticks", "40", "--script", script.toString(), "--trace-input");
        Run b = runJar("--headless", "--ticks", "40", "--script", script.toString(), "--trace-input");
        assertEquals(0, a.exit);
        assertEquals(a.out, b.out, "scripted runs are deterministic");
    }

    @Test
    void missingScriptFileExits2(@TempDir Path tmp) throws Exception {
        Path missing = tmp.resolve("nope.txt");
        Run r = runJar("--headless", "--ticks", "3", "--script", missing.toString(), "--trace-input");
        assertEquals(2, r.exit, "missing script must exit 2; stderr=" + r.err);
        assertTrue(r.err.contains("nope.txt"), "message names the file: " + r.err);
        assertFalse(r.out.contains("ticks="), "must not run: " + r.out);
    }

    @Test
    void malformedScriptsExit2WithLineNumber(@TempDir Path tmp) throws Exception {
        String[][] bad = {
            {"1 press UP\n2 jump UP\n", "2", "unknown verb"},
            {"1 press NOTAKEY\n", "1", "unknown key"},
            {"# c\n\nabc press UP\n", "3", "non-numeric tick"},
            {"-1 press UP\n", "1", "negative tick"},
            {"0 press UP\n", "1", "tick 0 (ticks start at 1)"},
            {"1 press\n", "1", "missing key"},
            {"1\n", "1", "tick only"},
            {"press UP\n", "1", "missing tick"},
            {"1 press UP now\n", "1", "extra field"},
            {"1 PRESS UP\n", "1", "verbs are lower case"},
            {"5 press UP\n3 release UP\n", "2", "ticks going backwards"},
            {"1 press UP\n1.5 release UP\n", "2", "fractional tick"},
            {"1 press UP\n999 press BOGUS\n", "2", "bad line beyond N is still caught"},
        };
        for (String[] b : bad) {
            Path script = write(tmp, "bad.txt", b[0]);
            Run r = runJar("--headless", "--ticks", "3", "--script", script.toString(), "--trace-input");
            assertEquals(2, r.exit, b[2] + ": exit code; stderr=" + r.err);
            assertTrue(r.err.contains("bad.txt"), b[2] + ": message names the file: " + r.err);
            assertTrue(r.err.contains("line " + b[1]), b[2] + ": message has 'line " + b[1] + "': " + r.err);
            assertFalse(r.out.contains("ticks="), b[2] + ": must not run: " + r.out);
            assertTrue(traceLines(r.out).isEmpty(), b[2] + ": no trace output");
        }
    }

    @Test
    void scriptFlagWithoutValueExits2() throws Exception {
        Run r = runJar("--headless", "--ticks", "3", "--script");
        assertEquals(2, r.exit, r.err);
    }

    @Test
    void scriptedInputDoesNotBreakDumping(@TempDir Path tmp) throws Exception {
        Path script = write(tmp, "in.txt", SCRIPT);
        Path dump = tmp.resolve("frames");
        Run r = runJar("--headless", "--ticks", "10", "--script", script.toString(), "--dump-dir", dump.toString());
        assertEquals(0, r.exit, r.err);
        assertTrue(Files.exists(dump.resolve("frame-00000.png")));
        assertTrue(Files.exists(dump.resolve("frame-00009.png")));
    }
}

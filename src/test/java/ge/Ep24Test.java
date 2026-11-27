package ge;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;
import java.util.stream.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;

/*
 * Episode 24 check: "Converting to an Applet" - applets are gone, so ./build.sh yields ONE runnable jar that starts as
 * server or client from the command line; a fresh machine needs only Java.
 *
 * CONTRACT (all earlier flags unchanged; new behaviour is additive):
 *  build/game.jar: manifest has Main-Class and no Class-Path; contains ge/ classes, /sprite_sheet.png and both
 *      /levels/*.png; no test classes, no org/junit, nothing from lib/.
 *  --help (alone): prints a usage to STDOUT naming --server, --client, --connect, --name, --port, --headless; exit 0.
 *      (Today --help is "unknown argument", exit 2.) Unknown arguments keep failing as before.
 *  --version (alone): prints one line starting "game " to stdout, exit 0.
 *  Fresh machine: game.jar copied alone to an empty temp dir (cwd and HOME), scrubbed env, java by absolute path:
 *      --server --port 0 --headless --realtime --ticks 600  -> "server listening port=P"
 *      --client --connect 127.0.0.1:P --name fresh --headless --realtime --ticks 60 --scene game:png:/levels/water_test_level.png@X,Y
 *          -> a line containing "joined", exit 0
 *      --headless --ticks 5 --dump-dir out -> PNG(s) in out/. Afterwards the dir holds only game.jar and out/.
 *  README.md: a "Running" heading whose section has the exact `java -jar game.jar --server` and
 *      `java -jar game.jar --client --connect` command lines and says Java 21 is the only requirement.
 */
public class Ep24Test {
    private static final String JAVA = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
    private static final Path JAR = Paths.get("build/game.jar").toAbsolutePath();

    private record Run(int exit, String out, String err) {}

    private static Run run(Path cwd, Process p, long secs) throws Exception {
        StringBuffer o = new StringBuffer(), e = new StringBuffer();
        Thread a = drain(p.getInputStream(), o), b = drain(p.getErrorStream(), e);
        try {
            assertTrue(p.waitFor(secs, TimeUnit.SECONDS), "timed out; out=" + o + " err=" + e);
            a.join(2000); b.join(2000);
            return new Run(p.exitValue(), o.toString(), e.toString());
        } finally {
            p.destroyForcibly();
        }
    }

    private static Thread drain(InputStream in, StringBuffer sink) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) sink.append(l).append('\n');
            } catch (IOException ignored) { }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static ProcessBuilder pb(Path dir, Path jar, String... args) {
        List<String> cmd = new ArrayList<>(List.of(JAVA, "-Djava.awt.headless=true", "-jar", jar.toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
        pb.environment().clear();
        pb.environment().put("HOME", dir.toString());
        return pb;
    }

    @Test
    @Timeout(30)
    void jarIsSelfContained() throws Exception {
        assertTrue(Files.isRegularFile(JAR), "build/game.jar missing");
        try (JarFile jf = new JarFile(JAR.toFile())) {
            Attributes m = jf.getManifest().getMainAttributes();
            assertEquals("ge.Main", m.getValue("Main-Class"));
            assertNull(m.getValue("Class-Path"), "manifest must not point at outside files");
            Set<String> names = jf.stream().map(JarEntry::getName).collect(Collectors.toSet());
            for (String need : List.of("ge/Main.class", "sprite_sheet.png", "levels/small_test_level.png", "levels/water_test_level.png"))
                assertTrue(names.contains(need), "jar lacks " + need);
            for (String n : names) {
                assertFalse(n.startsWith("org/junit") || n.startsWith("org/opentest4j") || n.startsWith("org/apiguardian")
                        || n.startsWith("lib/") || n.startsWith("junit") || n.contains("META-INF/services/org.junit"), "junk in jar: " + n);
                assertFalse(n.matches(".*Ep\\d\\d.*Test.*\\.class"), "test class in jar: " + n);
            }
        }
    }

    @Test
    @Timeout(30)
    void helpAndVersionDescribeTheRoles() throws Exception {
        Path dir = Files.createTempDirectory("ep24-help");
        Run h = run(dir, pb(dir, JAR, "--help").start(), 20);
        assertEquals(0, h.exit, "--help exit; err=" + h.err);
        for (String f : List.of("--server", "--client", "--connect", "--name", "--port", "--headless"))
            assertTrue(h.out.contains(f), "--help stdout must name " + f + " but was: " + h.out);
        Run v = run(dir, pb(dir, JAR, "--version").start(), 20);
        assertEquals(0, v.exit, "--version exit; err=" + v.err);
        assertTrue(v.out.strip().matches("game \\S.*") && v.out.strip().lines().count() == 1, "--version line: " + v.out);
        Run bad = run(dir, pb(dir, JAR, "--bogus").start(), 20);
        assertNotEquals(0, bad.exit, "unknown arguments still fail");
    }

    /** Centre of a grass tile with grass all around it (tiles are 16 px), found from the PNG itself. */
    private static String grassSpawn() throws IOException {
        BufferedImage img = ImageIO.read(Ep24Test.class.getResource("/levels/water_test_level.png"));
        for (int ty = 1; ty < img.getHeight() - 1; ty++)
            for (int tx = 1; tx < img.getWidth() - 1; tx++) {
                boolean ok = true;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++)
                        ok &= (img.getRGB(tx + dx, ty + dy) & 0xFFFFFF) == 0x00FF00;
                if (ok) return (tx * 16 + 8) + "," + (ty * 16 + 8);
            }
        return fail("no 3x3 grass patch in water_test_level.png");
    }

    @Test
    @Timeout(90)
    void freshMachineRunsServerClientAndSinglePlayer() throws Exception {
        Path dir = Files.createTempDirectory("ep24-fresh").toRealPath();
        Path jar = dir.resolve("game.jar");
        Files.copy(JAR, jar);
        Process server = pb(dir, jar, "--server", "--port", "0", "--headless", "--realtime", "--ticks", "600").start();
        StringBuffer sOut = new StringBuffer(), sErr = new StringBuffer();
        drain(server.getInputStream(), sOut);
        drain(server.getErrorStream(), sErr);
        try {
            int port = -1;
            for (long end = System.currentTimeMillis() + 20000; port < 0 && System.currentTimeMillis() < end; Thread.sleep(50)) {
                var m = java.util.regex.Pattern.compile("server listening port=(\\d+)").matcher(sOut);
                if (m.find()) port = Integer.parseInt(m.group(1));
            }
            assertTrue(port > 0, "server never announced a port; out=" + sOut + " err=" + sErr);
            Run c = run(dir, pb(dir, jar, "--client", "--connect", "127.0.0.1:" + port, "--name", "fresh", "--headless",
                    "--realtime", "--ticks", "60", "--scene", "game:png:/levels/water_test_level.png@" + grassSpawn()).start(), 40);
            assertEquals(0, c.exit, "client exit; out=" + c.out + " err=" + c.err);
            assertTrue(c.out.contains("joined"), "client must report joining; out=" + c.out);
            assertFalse(c.err.contains("Exception"), c.err);
        } finally {
            server.destroyForcibly();
        }
        Run sp = run(dir, pb(dir, jar, "--headless", "--ticks", "5", "--dump-dir", "out").start(), 30);
        assertEquals(0, sp.exit, "single player exit; err=" + sp.err);
        try (Stream<Path> s = Files.list(dir.resolve("out"))) {
            assertTrue(s.anyMatch(p -> p.toString().endsWith(".png")), "no PNG frames dumped");
        }
        try (Stream<Path> s = Files.list(dir)) {
            assertEquals(new TreeSet<>(List.of("game.jar", "out")), s.map(p -> p.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new)),
                    "nothing but game.jar and out/ may exist in the run dir");
        }
    }

    @Test
    void readmeExplainsRunning() throws Exception {
        String md = Files.readString(Paths.get("README.md"));
        var m = java.util.regex.Pattern.compile("(?m)^#+\\s*Running\\s*$").matcher(md);
        assertTrue(m.find(), "README.md needs a 'Running' heading");
        String rest = md.substring(m.end());
        var next = java.util.regex.Pattern.compile("(?m)^#+\\s").matcher(rest);
        String section = next.find() ? rest.substring(0, next.start()) : rest;
        assertTrue(section.contains("java -jar game.jar --server"), "server command line missing");
        assertTrue(section.contains("java -jar game.jar --client --connect"), "client command line missing");
        assertTrue(section.matches("(?s).*Java 21.*") && section.toLowerCase().contains("only"), "must say Java 21 is the only requirement");
    }
}

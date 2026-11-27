package ge;

import static org.junit.jupiter.api.Assertions.*;

import ge.entity.Mob;
import ge.entity.Player;
import ge.net.ClientGame;
import ge.net.GamePacket;
import ge.net.Protocol;
import ge.net.RemotePlayer;
import ge.net.Server;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;

/*
 * Episode 23 check: "Solving Concurrency Issues and Moving with Direction" - remote players show the sender's facing
 * and walk animation; 4 clients moving at once for 60 s cause no exceptions and leave no ghosts.
 *
 * CONTRACT (extends ep15-22; their API and behaviour are unchanged):
 *  RemotePlayer: dir() and walkFrame() (Mob's public accessors) follow the newest applied Move's dir (Mob.Dir ordinal)
 *      and walkFrame, so a remote's pose is Player.drawPose(screen, sheet, sx, sy, dir(), walkFrame()) at its screen
 *      position. (How the pose is set, e.g. an applyMove overload, is the builder's choice.)
 *  Server (new, public, thread-safe):
 *      int errorCount()               number of uncaught exceptions (any Throwable) hit on the server's threads; the
 *                                     receive loop must catch, count and survive them. 0 in a healthy run.
 *      int[] lastPosition(String name) {x, y} of the player's newest accepted Move (the login position before any), or
 *                                     null if no such player is registered.
 *  Server timeout (docs/net.md): a client silent for more than 10 s (no valid packet of any kind) is removed exactly as
 *      if it had disconnected: dropped from playerNames()/clientCount(), the others get PlayerLeft, the name is free.
 *      Any valid packet (move, ping, repeated login) refreshes the timer. Removal happens within ~3 s after the 10 s.
 *  Threads: ClientGame/World are only touched by their owner thread (a ConcurrentModificationException anywhere is a
 *      failure); every process's stderr stays free of "Exception"; the test JVM's default uncaught handler is hooked.
 * Stress timeline: 3 jar clients (j1 runs its 3600 ticks, j2 gets SIGTERM at ~20 s, j3 gets SIGKILL at ~25 s) and one
 * in-process client (i1) random-walk on an all-grass level for 60 s; all scripts idle from tick 3300.
 */
public class Ep23Test {
    private static final List<Throwable> uncaught = new CopyOnWriteArrayList<>();
    private static Thread.UncaughtExceptionHandler oldHandler;

    @BeforeAll
    static void hook() {
        oldHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> uncaught.add(e));
    }

    @AfterAll
    static void unhook() {
        Thread.setDefaultUncaughtExceptionHandler(oldHandler);
    }

    private Server server;
    private final List<AutoCloseable> toClose = new ArrayList<>();

    @BeforeEach
    void up() throws IOException {
        uncaught.clear();
        server = Server.start(0);
    }

    @AfterEach
    void down() throws Exception {
        for (AutoCloseable c : toClose) c.close();
        server.close();
        assertEquals(List.of(), uncaught.stream().map(Throwable::toString).toList(), "uncaught exceptions in test JVM");
        assertEquals(0, server.errorCount(), "server hit uncaught exceptions");
    }

    private static final String GRASS = "game:grass:64,64@";
    private static final String SCENE_A = GRASS + "200,200";
    private static final String SCENE_B = GRASS + "260,240";

    private ClientGame login(String scene, String name) throws Exception {
        ClientGame g = ClientGame.connect(scene, "127.0.0.1", server.port(), name);
        toClose.add(g);
        assertTrue(g.client().awaitJoined(3000), name + " must be acked");
        return g;
    }

    private static RemotePlayer remote(ClientGame g, String name) {
        for (Mob m : g.world().entities()) if (m instanceof RemotePlayer rp && rp.name().equals(name)) return rp;
        return null;
    }

    private static Set<String> remoteNames(ClientGame g) {
        Set<String> s = new TreeSet<>();
        for (Mob m : g.world().entities()) if (m instanceof RemotePlayer rp) s.add(rp.name());
        return s;
    }

    private static boolean tickUntil(java.util.function.BooleanSupplier c, long ms, ClientGame... gs) throws InterruptedException {
        long end = System.nanoTime() + ms * 1_000_000L;
        while (!c.getAsBoolean() && System.nanoTime() < end) {
            for (ClientGame g : gs) g.tick();
            Thread.sleep(5);
        }
        return c.getAsBoolean();
    }

    private static int[] box(Screen s, int x0, int y0) {
        int[] out = new int[16 * 16];
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) out[y * 16 + x] = s.pixels[(y0 + y) * s.width + x0 + x];
        return out;
    }

    // ---------------------------------------------------------------- check 1: facing and animation

    @Test
    @Timeout(60)
    void remotePlayerShowsSendersFacingAndWalkAnimation() throws Exception {
        ClientGame a = login(SCENE_A, "alice"), b = login(SCENE_B, "bobby");
        assertTrue(tickUntil(() -> remote(b, "alice") != null, 5000, a, b));
        record St(int x, int y, Mob.Dir dir, int frame) {}
        List<St> hist = new ArrayList<>();
        hist.add(new St(a.world().player().x(), a.world().player().y(), Mob.Dir.DOWN, 0));
        int[][] walks = {{KeyEvent.VK_RIGHT, 3}, {KeyEvent.VK_DOWN, 0}, {KeyEvent.VK_LEFT, 2}, {KeyEvent.VK_UP, 1}};
        Mob.Dir[] dirs = {Mob.Dir.RIGHT, Mob.Dir.DOWN, Mob.Dir.LEFT, Mob.Dir.UP};
        Set<List<Integer>> boxes = new HashSet<>();
        for (int w = 0; w < 4; w++) {
            Set<Integer> framesSeen = new TreeSet<>();
            a.input().press(walks[w][0]);
            for (int t = 0; t < 20; t++) {
                a.tick();
                Player p = a.world().player();
                hist.add(new St(p.x(), p.y(), p.dir(), p.walkFrame()));
                for (int k = 0; k < 3; k++) {
                    b.tick();
                    Thread.sleep(2);
                }
                RemotePlayer r = remote(b, "alice");
                assertNotNull(r);
                St seen = new St(r.x(), r.y(), r.dir(), r.walkFrame());
                assertTrue(hist.subList(Math.max(0, hist.size() - 40), hist.size()).contains(seen),
                        dirs[w] + " tick " + t + ": remote pose " + seen + " is none of A's recent states " + hist.subList(Math.max(0, hist.size() - 4), hist.size()));
                if (seen.dir() == dirs[w]) framesSeen.add(seen.frame());
            }
            assertEquals(Set.of(0, 1), framesSeen, dirs[w] + ": B must have seen both walk frames while A walked 20 ticks");
            a.input().release(walks[w][0]);
            a.tick();
            Player p = a.world().player();
            assertEquals(dirs[w], p.dir());
            assertEquals(0, p.walkFrame(), "Ep11 rule: idle resets the frame");
            // standing still: B settles on A's exact position, last facing, standing frame
            assertTrue(tickUntil(() -> {
                RemotePlayer r = remote(b, "alice");
                return r != null && r.x() == p.x() && r.y() == p.y() && r.dir() == p.dir() && r.walkFrame() == 0;
            }, 3000, a, b), dirs[w] + ": remote never showed the standing pose of its last facing");
            // rendered: B's frame box for A equals drawPose at A's screen position (independent expected frame)
            int sx = p.x() - 260 + 72, sy = p.y() - 240 + 52; // B is centred at (72,52)
            Screen got = new Screen(Game.WIDTH, Game.HEIGHT);
            b.render(got);
            Screen exp = new Screen(Game.WIDTH, Game.HEIGHT);
            exp.clear(0);
            Scene.parse(SCENE_B).newWorld().render(exp, Scene.sheet());
            Player.drawPose(exp, Scene.sheet(), sx, sy, dirs[w], 0);
            int[] expBox = box(exp, sx, sy);
            assertArrayEquals(expBox, box(got, sx, sy), dirs[w] + ": rendered remote must be the standing pose facing " + dirs[w]);
            List<Integer> key = new ArrayList<>();
            for (int v : expBox) key.add(v);
            boxes.add(key);
            // and a walking frame 1 pose as pure rendering model: drawPose(dir, 1) differs from frame 0 for the walk frame check above
        }
        assertEquals(4, boxes.size(), "the four facings must look different");
    }

    // ---------------------------------------------------------------- check 2: timeout

    private final class Raw implements AutoCloseable {
        final DatagramSocket s;

        Raw() throws IOException {
            s = new DatagramSocket(0, InetAddress.getLoopbackAddress());
            toClose.add(this);
        }

        void send(byte[] d) throws IOException {
            s.send(new DatagramPacket(d, d.length, InetAddress.getLoopbackAddress(), server.port()));
        }

        byte[] recv(long ms) throws IOException {
            s.setSoTimeout((int) Math.max(1, ms));
            DatagramPacket p = new DatagramPacket(new byte[1024], 1024);
            try {
                s.receive(p);
            } catch (SocketTimeoutException e) {
                return null;
            }
            return Arrays.copyOf(p.getData(), p.getLength());
        }

        boolean login(String name) throws IOException {
            send(new GamePacket.Login(name, 100, 100).toBytes());
            for (int i = 0; i < 10; i++) {
                byte[] d = recv(2000);
                if (d == null) return false;
                if (Arrays.equals(d, Protocol.JOIN_ACK)) return true;
            }
            return false;
        }

        @Override
        public void close() {
            s.close();
        }
    }

    @Test
    @Timeout(90)
    void silentClientIsRemovedAfterTenSecondsButHeartbeatsAndPingsKeepPlayersAlive() throws Exception {
        ClientGame obs = login(SCENE_B, "bobby"), idle = login(SCENE_A, "hearty");
        Raw mute = new Raw(), pinger = new Raw();
        assertTrue(pinger.login("pinger"));
        assertTrue(mute.login("mute"));
        long t0 = System.nanoTime(); // mute's last packet
        assertTrue(tickUntil(() -> remote(obs, "mute") != null && remote(obs, "pinger") != null, 5000, obs, idle));
        long nextPing = t0 + 3_000_000_000L, goneAt = -1, spriteGoneAt = -1;
        while (System.nanoTime() - t0 < 13_500_000_000L && (goneAt < 0 || spriteGoneAt < 0)) {
            long now = System.nanoTime();
            if (now >= nextPing) {
                pinger.send(Protocol.PING);
                nextPing += 3_000_000_000L;
            }
            obs.tick();
            idle.tick();
            Thread.sleep(20);
            long el = System.nanoTime() - t0;
            Set<String> names = server.playerNames();
            if (el < 9_500_000_000L) assertTrue(names.contains("mute"), "silent player removed too early at " + el / 1_000_000 + " ms");
            if (goneAt < 0 && !names.contains("mute")) goneAt = el;
            if (spriteGoneAt < 0 && remote(obs, "mute") == null) spriteGoneAt = el;
            for (String n : List.of("bobby", "hearty", "pinger")) assertTrue(names.contains(n), n + " (alive) must not be removed: " + names + " at " + el / 1_000_000 + " ms");
        }
        assertTrue(goneAt > 10_000_000_000L && goneAt < 13_000_000_000L, "mute removed at " + goneAt / 1_000_000 + " ms, want (10 s, 13 s)");
        assertTrue(spriteGoneAt > 0, "other clients must get PlayerLeft and drop the sprite");
        assertNotNull(remote(obs, "hearty"));
        assertNotNull(remote(obs, "pinger"));
        assertEquals(3, server.clientCount());
        // the name is free again
        assertTrue(new Raw().login("mute"), "removal must free the name");
        assertTrue(tickUntil(() -> remote(obs, "mute") != null, 3000, obs, idle), "the new mute must appear");
    }

    // ---------------------------------------------------------------- check 3: stress

    private record Ev(int tick, int key, boolean press) {}

    private static final String[] KEY_NAMES = {"UP", "DOWN", "LEFT", "RIGHT"};
    private static final int[] KEY_CODES = {KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT};

    /** Random walk of axis moves, kept within 150 px of the spawn (1 px/tick); idle from tick 3300. */
    private static List<Ev> walk(long seed) {
        Random r = new Random(seed);
        List<Ev> evs = new ArrayList<>();
        int px = 0, py = 0, t = 1 + r.nextInt(30);
        while (t < 3200) {
            int k = r.nextInt(4), dur = 10 + r.nextInt(50);
            int dx = k == 2 ? -dur : k == 3 ? dur : 0, dy = k == 0 ? -dur : k == 1 ? dur : 0;
            if (Math.abs(px + dx) > 150 || Math.abs(py + dy) > 150) continue;
            px += dx;
            py += dy;
            evs.add(new Ev(t, k, true));
            evs.add(new Ev(t + dur, k, false));
            t += dur + r.nextInt(8); // gap 0 = turn without an idle tick
        }
        return evs;
    }

    private static final class Jar {
        final Process p;
        final StringBuilder out = new StringBuilder(), err = new StringBuilder();

        Jar(int port, String name, String scene, Path script) throws IOException {
            String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
            p = new ProcessBuilder(javaBin, "-Djava.awt.headless=true", "-jar", "build/game.jar", "--client", "--connect",
                    "127.0.0.1:" + port, "--name", name, "--headless", "--realtime", "--ticks", "3600", "--scene", scene,
                    "--script", script.toString()).start();
            drain(p.getInputStream(), out);
            drain(p.getErrorStream(), err);
        }

        private static void drain(java.io.InputStream in, StringBuilder sb) {
            Thread t = new Thread(() -> {
                try {
                    String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    synchronized (sb) {
                        sb.append(s);
                    }
                } catch (IOException ignored) {
                }
            });
            t.setDaemon(true);
            t.start();
        }
    }

    @Test
    @Timeout(240)
    void fourClientsMovingForAMinuteLeaveNoExceptionsAndNoGhosts() throws Exception {
        Path tmp = Files.createTempDirectory("ep23");
        String[] names = {"j1", "j2", "j3"};
        String[] scenes = {GRASS + "300,300", GRASS + "500,300", GRASS + "300,500"};
        List<Jar> jars = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                StringBuilder sb = new StringBuilder("# seeded random walk\n");
                for (Ev e : walk(2300 + i)) sb.append(e.tick()).append(e.press() ? " press " : " release ").append(KEY_NAMES[e.key()]).append('\n');
                Path script = tmp.resolve(names[i] + ".txt");
                Files.writeString(script, sb.toString());
                jars.add(new Jar(server.port(), names[i], scenes[i], script));
            }
            long wait = System.nanoTime() + 40_000_000_000L;
            while (server.playerNames().size() < 3 && System.nanoTime() < wait) Thread.sleep(20);
            assertEquals(new TreeSet<>(List.of(names)), new TreeSet<>(server.playerNames()), "jar clients must all join");
            ClientGame i1 = login(GRASS + "500,500", "i1");
            List<Ev> evs = walk(2399);
            int ei = 0;
            Process j1 = jars.get(0).p, j2 = jars.get(1).p, j3 = jars.get(2).p;
            long loop0 = System.nanoTime(), term = -1, kill = -1, termGone = -1, killGone = -1, killSprite = -1;
            boolean settledChecked = false;
            Set<String> maxExpected = new TreeSet<>(List.of("j1", "j2", "j3"));
            for (int tick = 1; tick <= 3600; tick++) {
                while (ei < evs.size() && evs.get(ei).tick() == tick) {
                    Ev e = evs.get(ei++);
                    if (e.press()) i1.input().press(KEY_CODES[e.key()]);
                    else i1.input().release(KEY_CODES[e.key()]);
                }
                i1.tick();
                long el = System.nanoTime() - loop0;
                Set<String> seen = remoteNames(i1), reg = server.playerNames();
                assertTrue(maxExpected.containsAll(seen), "i1 sees someone who never existed: " + seen);
                if (term < 0 && el > 20_000_000_000L) {
                    assertEquals(new TreeSet<>(List.of("j1", "j2", "j3")), seen, "all three visible before the kills");
                    j2.destroy(); // SIGTERM
                    term = el;
                }
                if (kill < 0 && el > 25_000_000_000L) {
                    j3.destroyForcibly(); // SIGKILL: no disconnect packet
                    kill = el;
                }
                if (term >= 0 && termGone < 0 && !reg.contains("j2")) termGone = el;
                if (kill >= 0 && killGone < 0 && !reg.contains("j3")) killGone = el;
                if (kill >= 0 && killSprite < 0 && !seen.contains("j3")) killSprite = el;
                if (kill >= 0 && el - kill < 5_000_000_000L) assertTrue(reg.contains("j3"), "a killed client must linger until the timeout, not vanish at once");
                if (term >= 0 && el - term > 13_000_000_000L) assertTrue(termGone >= 0, "SIGTERMed j2 still registered 13 s after");
                if (kill >= 0 && el - kill > 13_000_000_000L) assertTrue(killGone >= 0, "killed j3 is a ghost 13 s after its death: " + reg);
                if (kill >= 0 && el - kill > 14_000_000_000L) assertTrue(killSprite >= 0, "killed j3's sprite is still shown at " + el / 1_000_000);
                if (tick == 3400 && !settledChecked) {
                    settledChecked = true;
                    assertTrue(tickUntil(() -> {
                        int[] pos = server.lastPosition("j1");
                        RemotePlayer r = remote(i1, "j1");
                        return pos != null && r != null && r.x() == pos[0] && r.y() == pos[1];
                    }, 3000, i1), "i1's view of j1 must equal the server registry once moves settle; server=" + Arrays.toString(server.lastPosition("j1"))
                            + " seen=" + (remote(i1, "j1") == null ? null : remote(i1, "j1").x() + "," + remote(i1, "j1").y()));
                    assertEquals(new TreeSet<>(List.of("j1")), remoteNames(i1), "i1 must see exactly the live players");
                    assertEquals(new TreeSet<>(List.of("i1", "j1")), new TreeSet<>(server.playerNames()));
                    assertTrue(j1.isAlive());
                }
                long due = loop0 + tick * 16_666_667L;
                long sleep = (due - System.nanoTime()) / 1_000_000L;
                if (sleep > 0) Thread.sleep(sleep);
            }
            assertTrue(termGone >= 0 && killGone >= 0 && killSprite >= 0);
            assertTrue(killGone - kill > 10_000_000_000L, "a crashed client is only removed by the 10 s timeout");
            assertTrue(j1.waitFor(30, TimeUnit.SECONDS), "j1 must finish its 3600 ticks");
            assertEquals(0, j1.exitValue(), jars.get(0).out + "" + jars.get(0).err);
            assertTrue(j2.waitFor(10, TimeUnit.SECONDS), "SIGTERMed j2 must exit");
            assertTrue(j3.waitFor(10, TimeUnit.SECONDS));
            Thread.sleep(300);
            for (int i = 0; i < 3; i++)
                synchronized (jars.get(i).err) {
                    assertFalse(jars.get(i).err.toString().contains("Exception"), names[i] + " stderr: " + jars.get(i).err);
                    assertFalse(jars.get(i).out.toString().contains("Exception"), names[i] + " stdout: " + jars.get(i).out);
                }
            assertTrue(jars.get(0).out.toString().contains("client joined"), jars.get(0).out.toString());
            assertTrue(tickUntil(() -> remoteNames(i1).isEmpty() && server.playerNames().equals(Set.of("i1")), 4000, i1),
                    "after j1 left only i1 remains: " + server.playerNames() + " sees " + remoteNames(i1));
            i1.close();
            long end = System.nanoTime() + 5_000_000_000L;
            while (!server.playerNames().isEmpty() && System.nanoTime() < end) Thread.sleep(20);
            assertEquals(Set.of(), server.playerNames(), "after everyone disconnected the registry is empty");
            assertEquals(0, server.clientCount());
        } finally {
            for (Jar j : jars) j.p.destroyForcibly();
        }
    }
}

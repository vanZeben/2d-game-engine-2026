package ge;

import static org.junit.jupiter.api.Assertions.*;

import ge.entity.Mob;
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
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;

/*
 * Episode 21 check: "Making Players Move" - movement is sent over the network; client B sees client A move.
 *
 * CONTRACT (extends ep18-20; their API and behaviour are unchanged):
 *  GamePacket.Move(String name, int x, int y, int dir, int walkFrame, int seq)   id "02" (GamePacket.ID_MOVE),
 *      wire "02,NAME,X,Y,DIR,FRAME,SEQ" (canonical ints like the other packets). dir = Mob.Dir ordinal 0..3,
 *      walkFrame 0..1, seq >= 0; the constructor throws IllegalArgumentException for a bad name or any out-of-range
 *      field; parse() gives empty for a wrong field count, bad name, non-canonical or out-of-range field.
 *      The client sends it with its own name; relayed moves carry the sender's registered name and the sender's seq.
 *  Server: a valid Move from a registered address updates that player's registry entry (position, facing, frame, seq)
 *      and is relayed (same bytes semantics, registered name) to every OTHER registered address, never back to the
 *      sender. A Move whose seq is not higher than the stored seq for that player is dropped (not stored, not
 *      relayed). A fresh login (new player, also after disconnect) resets the stored seq. Moves from unregistered
 *      addresses and malformed moves are dropped. Players who log in later are announced (PlayerJoined) at the
 *      stored CURRENT position.
 *  ClientGame: new public InputHandler input() - the local player's input, used by tick() (default: nothing held).
 *      A Move for a known remote name is applied (RemotePlayer position = packet x,y; no collision) only if its seq is
 *      strictly higher than the last applied seq for that remote (newest-seq-wins; a PlayerJoined/new RemotePlayer
 *      starts a fresh seq, so seq 1 is accepted after leave + rejoin). Moves for unknown names or for the local
 *      name are ignored. Facing/walk-frame display of remote players is NOT checked (ep 23).
 *  ClientGame.tick()/drain() (either; jar client uses drain) sends the local player's full state as Move(seq strictly
 *      increasing per client, starting at >= 0) whenever it changed since the last send, and at least every 1 s
 *      (<= 1.5 s measured) while idle once the login is acked; nothing is sent before the ack.
 *  Jar --client --script FILE: runs the script on the local player and sends moves as above.
 * Not tested: duplicate names (ep22), timeouts / display of facing (ep23).
 */
public class Ep21Test {

    private Server server;
    private final List<AutoCloseable> toClose = new ArrayList<>();

    @BeforeEach
    void up() throws IOException {
        server = Server.start(0);
    }

    @AfterEach
    void down() throws Exception {
        for (AutoCloseable c : toClose) c.close();
        server.close();
    }

    private static final String SCENE_A = "game:grass:64,64@200,200";
    private static final String SCENE_B = "game:grass:64,64@216,208";
    private static final String SCENE_C = "game:grass:64,64@168,200";

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

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

    private static boolean at(ClientGame g, String name, int x, int y) {
        RemotePlayer r = remote(g, name);
        return r != null && r.x() == x && r.y() == y;
    }

    /** Ticks all games every 5 ms until the condition holds or ms pass. */
    private static boolean tickUntil(java.util.function.BooleanSupplier c, long ms, ClientGame... gs) throws InterruptedException {
        long end = System.nanoTime() + ms * 1_000_000L;
        while (!c.getAsBoolean() && System.nanoTime() < end) {
            for (ClientGame g : gs) g.tick();
            Thread.sleep(5);
        }
        return c.getAsBoolean();
    }

    private static boolean until(java.util.function.BooleanSupplier c, long ms) throws InterruptedException {
        long end = System.nanoTime() + ms * 1_000_000L;
        while (!c.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        return c.getAsBoolean();
    }

    private void send(DatagramSocket s, byte[] data) throws IOException {
        s.send(new DatagramPacket(data, data.length, InetAddress.getLoopbackAddress(), server.port()));
    }

    /** Next datagram within ms, or null. */
    private static byte[] recv(DatagramSocket s, long ms) throws IOException {
        s.setSoTimeout((int) Math.max(1, ms));
        DatagramPacket p = new DatagramPacket(new byte[1024], 1024);
        try {
            s.receive(p);
        } catch (SocketTimeoutException e) {
            return null;
        }
        return Arrays.copyOf(p.getData(), p.getLength());
    }

    private static int[] box(Screen s, int x0, int y0) {
        int[] out = new int[16 * 16];
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) out[y * 16 + x] = s.pixels[(y0 + y) * s.width + x0 + x];
        return out;
    }

    private static Screen fromPng(Path f) throws IOException {
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(f.toFile());
        Screen s = new Screen(img.getWidth(), img.getHeight());
        for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) s.pixels[y * s.width + x] = img.getRGB(x, y) & 0xFFFFFF;
        return s;
    }

    @Test
    void movePacketRoundTripsAndRejects() {
        GamePacket.Move m = new GamePacket.Move("walker", 260, -5, 3, 1, 42);
        assertEquals("02", m.id());
        assertEquals("02", GamePacket.ID_MOVE);
        assertEquals("02,walker,260,-5,3,1,42", new String(m.toBytes(), StandardCharsets.US_ASCII));
        assertEquals(m, GamePacket.parse(m.toBytes()).orElseThrow());
        assertEquals(new GamePacket.Move("a", 0, 0, 0, 0, 0), GamePacket.parse(b("02,a,0,0,0,0,0")).orElseThrow());
        for (String bad : new String[] {"02", "02,", "02,a,1,2,0,0", "02,a,1,2,0,0,1,9", "02,,1,2,0,0,1", "02,bad name,1,2,0,0,1",
                "02,a,1,2,4,0,1", "02,a,1,2,-1,0,1", "02,a,1,2,0,2,1", "02,a,1,2,0,-1,1", "02,a,1,2,0,0,-1", "02,a,01,2,0,0,1",
                "02,a,1,x,0,0,1", "02,a,1,2,0,0,99999999999", "02,a,1.5,2,0,0,1", "02,a,1,2,0,0,", "02,a,1,2,0,0,1\n"})
            assertTrue(GamePacket.parse(b(bad)).isEmpty(), bad);
        assertThrows(IllegalArgumentException.class, () -> new GamePacket.Move("a", 0, 0, 4, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new GamePacket.Move("a", 0, 0, 0, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> new GamePacket.Move("a", 0, 0, 0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new GamePacket.Move("no good", 0, 0, 0, 0, 0));
    }

    @Test
    @Timeout(60)
    void bSeesAMoveAndALateJoinerSeesWhereAIs() throws Exception {
        ClientGame a = login(SCENE_A, "alice");
        ClientGame bg = login(SCENE_B, "bobby");
        assertTrue(tickUntil(() -> remote(bg, "alice") != null && remote(a, "bobby") != null, 5000, a, bg));
        assertTrue(at(bg, "alice", 200, 200), "alice starts at her spawn on B");

        a.input().press(KeyEvent.VK_RIGHT);
        for (int i = 0; i < 40; i++) { // 40 ticks right (SPEED 1)
            a.tick();
            bg.tick();
            Thread.sleep(5);
        }
        a.input().release(KeyEvent.VK_RIGHT);
        a.input().press(KeyEvent.VK_DOWN);
        for (int i = 0; i < 25; i++) {
            a.tick();
            bg.tick();
            Thread.sleep(5);
        }
        a.input().release(KeyEvent.VK_DOWN);
        int ax = a.world().player().x(), ay = a.world().player().y();
        assertEquals(240, ax);
        assertEquals(225, ay);
        assertTrue(tickUntil(() -> at(bg, "alice", ax, ay), 3000, a, bg),
                "B must see alice at her exact position (" + ax + "," + ay + "), saw " + (remote(bg, "alice") == null ? "nobody" : remote(bg, "alice").x() + "," + remote(bg, "alice").y()));
        assertTrue(at(a, "bobby", 216, 208), "bobby never moved: still at spawn on A");

        // a late joiner learns the CURRENT position (alice is no longer ticked, so no heartbeat can repair it)
        ClientGame c = login(SCENE_C, "carol");
        assertTrue(tickUntil(() -> remote(c, "alice") != null, 3000, c), "carol never saw alice");
        assertTrue(until(() -> at(c, "alice", ax, ay), 1000) || tickUntil(() -> at(c, "alice", ax, ay), 3000, c),
                "late joiner must see alice at " + ax + "," + ay + ", saw " + remote(c, "alice").x() + "," + remote(c, "alice").y());
    }

    @Test
    @Timeout(60)
    void serverDropsStaleSeqRelaysToOthersOnlyAndResetsSeqOnRelogin() throws Exception {
        ClientGame a = login(SCENE_A, "alice");
        try (DatagramSocket raw = new DatagramSocket(); DatagramSocket stranger = new DatagramSocket()) {
            send(raw, new GamePacket.Login("rawz", 5, 5).toBytes());
            assertNotNull(recv(raw, 3000));
            assertTrue(tickUntil(() -> remote(a, "rawz") != null, 5000, a));

            send(raw, new GamePacket.Move("rawz", 50, 60, 1, 0, 10).toBytes());
            assertTrue(tickUntil(() -> at(a, "rawz", 50, 60), 3000, a));
            send(raw, new GamePacket.Move("rawz", 90, 90, 1, 0, 5).toBytes()); // older: dropped
            send(raw, new GamePacket.Move("rawz", 91, 91, 1, 0, 10).toBytes()); // duplicate seq: dropped
            send(raw, b("02,rawz,92,92,9,0,11")); // malformed
            send(stranger, new GamePacket.Move("rawz", 93, 93, 1, 0, 99).toBytes()); // unknown address
            send(stranger, new GamePacket.Move("ghost", 94, 94, 1, 0, 99).toBytes());
            tickUntil(() -> false, 600, a);
            assertTrue(at(a, "rawz", 50, 60), "stale/duplicate/malformed/stranger moves must change nothing");
            assertNull(remote(a, "ghost"));
            send(raw, new GamePacket.Move("rawz", 51, 61, 2, 1, 11).toBytes()); // newer wins
            assertTrue(tickUntil(() -> at(a, "rawz", 51, 61), 3000, a));

            // never echoed to the sender: only alice's moves (relayed) may reach raw, none named rawz
            alice(a, raw);

            send(raw, new GamePacket.Disconnect().toBytes());
            assertTrue(tickUntil(() -> remote(a, "rawz") == null, 5000, a));
            send(raw, new GamePacket.Login("rawz", 5, 5).toBytes());
            assertTrue(tickUntil(() -> remote(a, "rawz") != null, 5000, a));
            send(raw, new GamePacket.Move("rawz", 70, 71, 0, 0, 1).toBytes()); // seq 1 after relogin: accepted
            assertTrue(tickUntil(() -> at(a, "rawz", 70, 71), 3000, a), "fresh login resets seq");
        }
    }

    /** Alice moves; raw (a different registered player) must receive her move but never its own moves back. */
    private void alice(ClientGame a, DatagramSocket raw) throws Exception {
        boolean sawAlice = false;
        a.input().press(KeyEvent.VK_LEFT);
        for (int i = 0; i < 20; i++) {
            a.tick();
            Thread.sleep(5);
        }
        a.input().release(KeyEvent.VK_LEFT);
        long end = System.nanoTime() + 1_000_000_000L;
        while (System.nanoTime() < end) {
            byte[] d = recv(raw, 100);
            if (d == null) continue;
            var g = GamePacket.parse(d);
            if (g.isPresent() && g.get() instanceof GamePacket.Move m) {
                assertNotEquals("rawz", m.name(), "a move must never be echoed to its sender");
                if (m.name().equals("alice")) sawAlice = true;
            }
        }
        assertTrue(sawAlice, "raw never received alice's relayed move");
    }

    @Test
    @Timeout(60)
    void clientAppliesNewestSeqWinsAndSendsOnChangeAndAsHeartbeat() throws Exception {
        try (DatagramSocket fake = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            ClientGame g = ClientGame.connect(SCENE_A, "127.0.0.1", fake.getLocalPort(), "dave");
            toClose.add(g);
            SocketAddress me = null;
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (me == null && System.nanoTime() < deadline) {
                fake.setSoTimeout(200);
                DatagramPacket p = new DatagramPacket(new byte[1024], 1024);
                try {
                    fake.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                if (GamePacket.parse(Arrays.copyOf(p.getData(), p.getLength())).orElse(null) instanceof GamePacket.Login) {
                    me = p.getSocketAddress();
                    fake.send(new DatagramPacket(Protocol.JOIN_ACK, Protocol.JOIN_ACK.length, me));
                }
            }
            assertNotNull(me, "no login seen");
            assertTrue(g.client().awaitJoined(3000));
            long ackedAt = System.nanoTime();

            // --- receiving: newest seq wins
            byte[] joined = new GamePacket.PlayerJoined("ghost", 10, 10).toBytes();
            for (byte[] d : new byte[][] {joined, new GamePacket.Move("ghost", 30, 31, 0, 0, 7).toBytes(),
                    new GamePacket.Move("ghost", 99, 99, 0, 0, 3).toBytes(), new GamePacket.Move("ghost", 98, 98, 0, 0, 7).toBytes(),
                    new GamePacket.Move("dave", 1, 1, 0, 0, 100).toBytes(), new GamePacket.Move("nobody", 2, 2, 0, 0, 1).toBytes()})
                fake.send(new DatagramPacket(d, d.length, me));
            // while draining, collect what the client sends
            List<GamePacket.Move> sent = new ArrayList<>();
            List<Long> times = new ArrayList<>();
            long end = System.nanoTime() + 3_500_000_000L;
            while (System.nanoTime() < end) {
                g.tick();
                fake.setSoTimeout(5);
                DatagramPacket p = new DatagramPacket(new byte[1024], 1024);
                try {
                    fake.receive(p);
                    if (GamePacket.parse(Arrays.copyOf(p.getData(), p.getLength())).orElse(null) instanceof GamePacket.Move m) {
                        sent.add(m);
                        times.add(System.nanoTime());
                    }
                } catch (SocketTimeoutException ignored) {
                }
            }
            assertTrue(at(g, "ghost", 30, 31), "seq 7 applied, seq 3 and equal seq 7 ignored");
            assertNull(remote(g, "nobody"));
            assertNull(remote(g, "dave"), "own name is never a remote");
            assertEquals(200, g.world().player().x(), "a relayed move for the local name must not move the local player");

            // --- sending: idle heartbeat
            assertTrue(sent.size() >= 2, "idle client must keep sending its state (>= every 1 s), got " + sent.size() + " in 3.5 s");
            assertTrue(times.get(0) - ackedAt < 1_500_000_000L, "first state within 1.5 s of the ack");
            for (int i = 1; i < times.size(); i++)
                assertTrue(times.get(i) - times.get(i - 1) < 1_500_000_000L, "heartbeat gap too long at " + i);
            long last = times.get(times.size() - 1);
            assertTrue(System.nanoTime() - last < 1_500_000_000L, "heartbeat stopped");
            for (int i = 0; i < sent.size(); i++) {
                GamePacket.Move m = sent.get(i);
                assertEquals("dave", m.name());
                assertEquals(200, m.x());
                assertEquals(200, m.y());
                if (i > 0) assertTrue(m.seq() > sent.get(i - 1).seq(), "seq must strictly increase");
            }
            int lastSeq = sent.get(sent.size() - 1).seq();

            // --- sending: on change, promptly
            g.input().press(KeyEvent.VK_RIGHT);
            for (int i = 0; i < 12; i++) {
                g.tick();
                Thread.sleep(3);
            }
            g.input().release(KeyEvent.VK_RIGHT);
            g.tick();
            GamePacket.Move finalMove = null;
            long t0 = System.nanoTime();
            while (System.nanoTime() - t0 < 700_000_000L) {
                g.tick();
                byte[] d = recv(fake, 5);
                if (d != null && GamePacket.parse(d).orElse(null) instanceof GamePacket.Move m) {
                    assertTrue(m.seq() > lastSeq);
                    lastSeq = m.seq();
                    if (m.x() == 212) finalMove = m;
                    assertTrue(m.x() >= 200 && m.x() <= 212 && m.y() == 200);
                }
            }
            assertNotNull(finalMove, "the new position (212,200) must be sent within 0.7 s of changing");
            assertEquals(3, finalMove.dir(), "facing right is Mob.Dir.RIGHT (ordinal 3)");
        }
    }

    @Test
    @Timeout(120)
    void jarWalkerIsSeenMovingByInProcessAndJarObservers() throws Exception {
        Path tmp = Files.createTempDirectory("ep21");
        Path script = tmp.resolve("walk.txt");
        Files.writeString(script, "1 press RIGHT\n61 release RIGHT\n"); // exactly 60 ticks of walking
        Path dir = tmp.resolve("frames");
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        ClientGame bg = login(SCENE_B, "bobby");
        Process walker = new ProcessBuilder(javaBin, "-Djava.awt.headless=true", "-jar", "build/game.jar", "--client", "--connect",
                "127.0.0.1:" + server.port(), "--name", "walker", "--headless", "--realtime", "--ticks", "720", "--scene", SCENE_A,
                "--script", script.toString()).redirectErrorStream(true).start();
        Process obs = null;
        StringBuilder out = new StringBuilder();
        Thread d1 = drainTo(walker, out);
        try {
            assertTrue(tickUntil(() -> at(bg, "walker", 260, 200), 40000, bg), "in-process B never saw walker at (260,200); saw "
                    + (remote(bg, "walker") == null ? "nobody" : remote(bg, "walker").x() + "," + remote(bg, "walker").y()) + " " + out);
            // a jar observer joining afterwards, 60 ticks after the walk, dumps frames
            obs = new ProcessBuilder(javaBin, "-Djava.awt.headless=true", "-jar", "build/game.jar", "--client", "--connect",
                    "127.0.0.1:" + server.port(), "--name", "watcher", "--headless", "--realtime", "--ticks", "240", "--scene", SCENE_B,
                    "--dump-dir", dir.toString()).redirectErrorStream(true).start();
            StringBuilder out2 = new StringBuilder();
            Thread d2 = drainTo(obs, out2);
            assertTrue(obs.waitFor(90, TimeUnit.SECONDS), "observer hung: " + out2);
            d2.join(2000);
            assertEquals(0, obs.exitValue(), out2.toString());
            assertTrue(walker.isAlive(), "walker must outlive the observer for this check: " + out);
            List<Path> frames;
            try (var st = Files.list(dir)) {
                frames = st.filter(f -> f.getFileName().toString().matches("frame-\\d+\\.png")).sorted().toList();
            }
            assertFalse(frames.isEmpty());
            Screen last = fromPng(frames.get(frames.size() - 1));
            // watcher at (216,208): camera (144,156). Walker's final spot (260,200) -> screen (116,44); spawn (200,200) -> (56,44)
            Screen level = new Screen(Game.WIDTH, Game.HEIGHT);
            level.clear(0);
            Scene.parse(SCENE_B).newWorld().render(level, Scene.sheet()); // watcher himself centred, nobody else
            assertFalse(Arrays.equals(box(level, 116, 44), box(last, 116, 44)), "walker's sprite must be at his moved place (116,44)");
            assertArrayEquals(box(level, 56, 44), box(last, 56, 44), "walker must no longer be drawn at his spawn (56,44)");
            assertArrayEquals(box(level, 72, 52), box(last, 72, 52), "watcher's own sprite unchanged");
            walker.waitFor(60, TimeUnit.SECONDS);
            d1.join(2000);
            assertEquals(0, walker.exitValue(), out.toString());
        } finally {
            walker.destroyForcibly();
            if (obs != null) obs.destroyForcibly();
        }
    }

    private static Thread drainTo(Process p, StringBuilder out) {
        Thread t = new Thread(() -> {
            try {
                out.append(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }
}

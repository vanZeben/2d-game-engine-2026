package ge;

import static org.junit.jupiter.api.Assertions.*;

import ge.entity.Mob;
import ge.net.Client;
import ge.net.ClientGame;
import ge.net.GamePacket;
import ge.net.Protocol;
import ge.net.RemotePlayer;
import ge.net.Server;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;

/*
 * Episode 20 check: "The Actual Disconnect Packet" - closing a client tells the server, which removes the player and
 * tells everyone else.
 *
 * CONTRACT (extends ep18/ep19; their API and behaviour are unchanged):
 *  GamePacket.Disconnect   id "01", client -> server, no fields: wire is exactly "01". Record with no components
 *      (new GamePacket.Disconnect()); parse("01") equals it; "01,x" or any "01..." with extra fields is invalid (empty).
 *  GamePacket.PlayerLeft(String name)   id "04", server -> client, wire "04,NAME". Invalid name -> parse empty.
 *      Constant GamePacket.ID_DISCONNECT = "01", ID_PLAYER_LEFT = "04".
 *  Server: a valid Disconnect from a registered address removes that player from playerNames()/clientCount(), sends
 *      Protocol.JOIN_ACK (exactly) back to the sender, and sends PlayerLeft(name) to every OTHER registered address.
 *      A repeated Disconnect (address no longer registered) and a Disconnect from an address that never joined are
 *      each answered with Protocol.JOIN_ACK and change nothing (no PlayerLeft). A malformed disconnect ("01,x") is
 *      ignored: no ack, no change. (docs/net.md: acked only, idempotent.) A player may log in again with the same
 *      name after leaving and is announced as new (PlayerJoined) again.
 *  ClientGame.tick/drain: PlayerLeft(name) removes that RemotePlayer from the world (and forgets the name so a later
 *      PlayerJoined re-adds it); unknown names are ignored.
 *  Client.close(): if joined, first sends Disconnect to the server, resending (every <= 500 ms) until Protocol.JOIN_ACK
 *      arrives, at most 10 sends in total, and returns after at most ~5 s even if never acked; then closes the socket
 *      (idempotent). Not joined (never acked) -> no disconnect needed. Closing from the shutdown path must not hang.
 *  Jar --client: when its run ends (--ticks reached) the client closes (so sends the disconnect) before exit 0; it
 *      also installs a shutdown hook that closes the client on SIGTERM.
 * Not tested: timeouts/heartbeat (ep23), movement (ep21), duplicate names (ep22).
 */
public class Ep20Test {

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

    // A at (200,200): camera (128,148), B -> screen (88,60). C at (168,200): camera (96,148), B -> (120,60).
    private static final String SCENE_A = "game:grass:64,64@200,200";
    private static final String SCENE_B = "game:grass:64,64@216,208";
    private static final String SCENE_C = "game:grass:64,64@168,200";

    private static int[] box(Screen s, int x0, int y0) {
        int[] out = new int[16 * 16];
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) out[y * 16 + x] = s.pixels[(y0 + y) * s.width + x0 + x];
        return out;
    }

    private static int[] modelBox(String scene) {
        Screen model = new Screen(Game.WIDTH, Game.HEIGHT);
        model.clear(0);
        Scene.parse(scene).newWorld().render(model, Scene.sheet());
        return box(model, 72, 52);
    }

    private static Set<String> remoteNames(ClientGame g) {
        Set<String> r = new HashSet<>();
        for (Mob m : g.world().entities()) if (m instanceof RemotePlayer rp) assertTrue(r.add(rp.name()), "duplicate " + rp.name());
        return r;
    }

    private ClientGame login(String scene, String name) throws Exception {
        ClientGame g = ClientGame.connect(scene, "127.0.0.1", server.port(), name);
        toClose.add(g);
        assertTrue(g.client().awaitJoined(3000), name + " must be acked");
        return g;
    }

    private static void ticks(ClientGame g, int n) throws InterruptedException {
        for (int i = 0; i < n; i++) {
            g.tick();
            Thread.sleep(5);
        }
    }

    private static boolean tickUntil(ClientGame g, java.util.function.BooleanSupplier c, long ms) throws InterruptedException {
        long end = System.nanoTime() + ms * 1_000_000L;
        while (!c.getAsBoolean() && System.nanoTime() < end) {
            g.tick();
            Thread.sleep(5);
        }
        return c.getAsBoolean();
    }

    private static boolean until(java.util.function.BooleanSupplier c, long ms) throws InterruptedException {
        long end = System.nanoTime() + ms * 1_000_000L;
        while (!c.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        return c.getAsBoolean();
    }

    private static Screen shot(ClientGame g) {
        Screen s = new Screen(Game.WIDTH, Game.HEIGHT);
        g.render(s);
        return s;
    }

    /** Receives datagrams for up to ms; true if one equals exactly want. Other packets (joins etc.) are skipped. */
    private static boolean receives(DatagramSocket s, byte[] want, int ms) throws IOException {
        long end = System.nanoTime() + ms * 1_000_000L;
        byte[] buf = new byte[1024];
        while (true) {
            long left = (end - System.nanoTime()) / 1_000_000L;
            if (left <= 0) return false;
            s.setSoTimeout((int) left);
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                s.receive(p);
            } catch (SocketTimeoutException e) {
                return false;
            }
            if (Arrays.equals(Arrays.copyOf(p.getData(), p.getLength()), want)) return true;
        }
    }

    private void send(DatagramSocket s, byte[] data) throws IOException {
        s.send(new DatagramPacket(data, data.length, InetAddress.getLoopbackAddress(), server.port()));
    }

    @Test
    void packetsRoundTrip() {
        assertArrayEquals("01".getBytes(StandardCharsets.US_ASCII), new GamePacket.Disconnect().toBytes());
        assertEquals("01", new GamePacket.Disconnect().id());
        assertEquals(new GamePacket.Disconnect(), GamePacket.parse("01".getBytes(StandardCharsets.US_ASCII)).orElseThrow());
        for (String bad : new String[] {"01,", "01,x", "01,bobby", "010", "04", "04,", "04,bad name", "04,bobby,1"})
            assertTrue(GamePacket.parse(bad.getBytes(StandardCharsets.US_ASCII)).isEmpty(), bad);
        GamePacket left = new GamePacket.PlayerLeft("bobby");
        assertEquals("04,bobby", new String(left.toBytes(), StandardCharsets.US_ASCII));
        assertEquals("04", left.id());
        assertEquals(left, GamePacket.parse(left.toBytes()).orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> new GamePacket.PlayerLeft("no good"));
    }

    @Test
    @Timeout(60)
    void closingAClientRemovesItEverywhereWithoutGhost() throws Exception {
        ClientGame a = login(SCENE_A, "alice");
        ClientGame c = login(SCENE_C, "carol");
        int[] aEmpty = box(shot(a), 88, 60);
        int[] cEmpty = box(shot(c), 120, 60);
        ClientGame b = login(SCENE_B, "bobby");
        assertTrue(tickUntil(a, () -> remoteNames(a).contains("bobby"), 5000));
        assertTrue(tickUntil(c, () -> remoteNames(c).contains("bobby"), 5000));
        assertFalse(Arrays.equals(aEmpty, box(shot(a), 88, 60)), "bobby is drawn before he leaves");
        assertEquals(3, server.playerNames().size());

        b.close();
        toClose.remove(b);
        assertTrue(until(() -> !server.playerNames().contains("bobby"), 5000), "server still has bobby: " + server.playerNames());
        assertTrue(tickUntil(a, () -> !remoteNames(a).contains("bobby"), 5000), "A still shows bobby");
        assertTrue(tickUntil(c, () -> !remoteNames(c).contains("bobby"), 5000), "C still shows bobby");
        ticks(a, 100); // well past any periodic roster resend: no ghost comes back
        ticks(c, 100);
        assertFalse(remoteNames(a).contains("bobby"));
        assertEquals(Set.of("carol"), remoteNames(a));
        assertEquals(Set.of("alice"), remoteNames(c));
        assertArrayEquals(aEmpty, box(shot(a), 88, 60), "no ghost sprite where bobby stood on A's screen");
        assertArrayEquals(cEmpty, box(shot(c), 120, 60), "no ghost sprite where bobby stood on C's screen");
        assertEquals(Set.of("alice", "carol"), server.playerNames());

        // rejoin under the same name works and is shown again
        ClientGame b2 = login(SCENE_B, "bobby");
        assertTrue(tickUntil(a, () -> remoteNames(a).contains("bobby"), 5000), "rejoin not shown on A");
        assertTrue(tickUntil(c, () -> remoteNames(c).contains("bobby"), 5000), "rejoin not shown on C");
        assertArrayEquals(modelBox(SCENE_B), box(shot(a), 88, 60));
        assertArrayEquals(modelBox(SCENE_B), box(shot(c), 120, 60));
        assertTrue(server.playerNames().contains("bobby"));
        assertTrue(tickUntil(b2, () -> remoteNames(b2).equals(Set.of("alice", "carol")), 5000), "rejoined bobby sees both");
    }

    @Test
    @Timeout(60)
    void rawDisconnectsAreIdempotentAndOnlyAckedWhenWellFormed() throws Exception {
        ClientGame a = login(SCENE_A, "alice");
        try (DatagramSocket raw = new DatagramSocket(); DatagramSocket stranger = new DatagramSocket()) {
            send(raw, new GamePacket.Login("rawz", 5, 5).toBytes());
            assertTrue(receives(raw, Protocol.JOIN_ACK, 3000), "raw login acked");
            assertTrue(tickUntil(a, () -> remoteNames(a).contains("rawz"), 5000));

            send(raw, "01,x".getBytes(StandardCharsets.US_ASCII)); // malformed: ignored, no ack, no change
            send(raw, "01,".getBytes(StandardCharsets.US_ASCII));
            assertFalse(receives(raw, Protocol.JOIN_ACK, 500), "malformed disconnect must not be acked");
            assertTrue(server.playerNames().contains("rawz"));

            send(raw, new GamePacket.Disconnect().toBytes());
            assertTrue(receives(raw, Protocol.JOIN_ACK, 3000), "disconnect acked");
            assertTrue(until(() -> !server.playerNames().contains("rawz"), 3000));
            assertTrue(tickUntil(a, () -> !remoteNames(a).contains("rawz"), 5000));

            send(raw, new GamePacket.Disconnect().toBytes()); // duplicate after removal: acked again, nothing else
            assertTrue(receives(raw, Protocol.JOIN_ACK, 3000), "repeated disconnect acked again");
            send(stranger, new GamePacket.Disconnect().toBytes()); // never joined: acked only
            assertTrue(receives(stranger, Protocol.JOIN_ACK, 3000), "stranger's disconnect acked");
            assertFalse(receives(stranger, "04,rawz".getBytes(StandardCharsets.US_ASCII), 300));
            ticks(a, 60);
            assertEquals(Set.of("alice"), server.playerNames());
            assertEquals(Set.of(), remoteNames(a));
            assertEquals(1, server.clientCount());
        }
    }

    @Test
    @Timeout(60)
    void closeResendsUntilAckedAtMostTenTimesAndStopsWhenAcked() throws Exception {
        for (boolean ackDisconnect : new boolean[] {false, true}) {
            try (DatagramSocket fake = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
                Client c = Client.connect("127.0.0.1", fake.getLocalPort(), "dave", 1, 2);
                byte[] buf = new byte[1024];
                int disconnects = 0;
                boolean closed = false;
                long closeStart = 0, closeEnd = 0;
                Thread closer = null;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                fake.setSoTimeout(200);
                while (System.nanoTime() < deadline) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        fake.receive(p);
                    } catch (SocketTimeoutException e) {
                        if (closed && closer != null && !closer.isAlive() && disconnects > 0) {
                            try {
                                fake.setSoTimeout(1500); // quiet tail: nothing more may arrive after close returned
                                fake.receive(new DatagramPacket(buf, buf.length));
                                fail("packets kept coming after Client.close() returned");
                            } catch (SocketTimeoutException done) {
                                break;
                            }
                        }
                        continue;
                    }
                    byte[] d = Arrays.copyOf(p.getData(), p.getLength());
                    if (Arrays.equals(d, new GamePacket.Login("dave", 1, 2).toBytes())) {
                        byte[] ack = Protocol.JOIN_ACK;
                        fake.send(new DatagramPacket(ack, ack.length, p.getSocketAddress()));
                        if (!closed) {
                            closed = true;
                            Client cc = c;
                            assertTrue(cc.awaitJoined(3000));
                            closer = new Thread(cc::close);
                            closeStart = System.nanoTime();
                            closer.start();
                        }
                    } else if (Arrays.equals(d, new GamePacket.Disconnect().toBytes())) {
                        disconnects++;
                        if (ackDisconnect) fake.send(new DatagramPacket(Protocol.JOIN_ACK, Protocol.JOIN_ACK.length, p.getSocketAddress()));
                    }
                }
                assertNotNull(closer, "no login seen");
                closer.join(8000);
                assertFalse(closer.isAlive(), "Client.close() must return within a bounded time even if never acked");
                closeEnd = System.nanoTime();
                assertTrue(c.isClosed());
                if (ackDisconnect) {
                    assertTrue(disconnects >= 1 && disconnects <= 2, "acked disconnect: sent " + disconnects + " times");
                } else {
                    assertTrue(disconnects >= 2 && disconnects <= 10, "unacked: resend up to 10 tries, sent " + disconnects);
                }
                assertTrue(TimeUnit.NANOSECONDS.toSeconds(closeEnd - closeStart) < 15);
                c.close(); // idempotent
            }
        }
    }

    @Test
    @Timeout(120)
    void jarClientLeavingIsRemovedFromTheOtherPlayerAndTheServer() throws Exception {
        ClientGame a = login(SCENE_A, "alice");
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(javaBin, "-Djava.awt.headless=true", "-jar", "build/game.jar", "--client", "--connect",
                "127.0.0.1:" + server.port(), "--name", "jarbob", "--headless", "--realtime", "--ticks", "60", "--scene", SCENE_B)
                .redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        Thread drain = new Thread(() -> {
            try {
                out.append(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        });
        drain.start();
        try {
            assertTrue(tickUntil(a, () -> remoteNames(a).contains("jarbob"), 60000), "A never saw jarbob: " + out);
            assertTrue(server.playerNames().contains("jarbob") || !p.isAlive());
            assertTrue(p.waitFor(90, TimeUnit.SECONDS), "jar client hung: " + out);
            drain.join(2000);
            assertEquals(0, p.exitValue(), out.toString());
            assertTrue(tickUntil(a, () -> !remoteNames(a).contains("jarbob"), 10000), "A still shows jarbob after its exit");
            assertTrue(until(() -> !server.playerNames().contains("jarbob"), 5000), "server still has jarbob: " + server.playerNames());
            assertEquals(Set.of("alice"), server.playerNames());
        } finally {
            p.destroyForcibly();
        }
    }
}

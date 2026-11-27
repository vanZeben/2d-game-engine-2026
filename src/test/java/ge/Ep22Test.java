package ge;

import static org.junit.jupiter.api.Assertions.*;

import ge.net.ClientGame;
import ge.net.GamePacket;
import ge.net.Protocol;
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
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;

/*
 * Episode 22 check: "Cleaning up the Login Packet (part 2)" - a login whose name is already in use is rejected,
 * never duplicated. Also closes the ep-21 gaps (stale seq, relogin seq, no impersonation, silence before ack).
 *
 * CONTRACT (extends ep18-21; their API and behaviour are unchanged):
 *  GamePacket.LoginRejected(String reason)  id "05" (GamePacket.ID_LOGIN_REJECTED), server to client, wire
 *      "05,REASON". REASON is 1..20 chars of [a-z-]; the constructor throws IllegalArgumentException otherwise;
 *      parse() gives empty for "05", "05,", "05,a,b", "05,Upper", "05,has space". Constant
 *      GamePacket.REASON_NAME_TAKEN = "name-taken".
 *  Server: a Login whose name is held by a player at ANOTHER address is answered with LoginRejected(name-taken) and
 *      NOT with the ack; nothing is registered and nobody is told. A repeated Login from the SAME address is acked
 *      as before (also with a different position/name field value: still one player). Once the holder disconnects
 *      the name is free. Names are compared exactly (case-sensitive). Nameless joins (Protocol.JOIN) get unique
 *      guestN names that never collide with a chosen name, and are never rejected. A chosen name equal to a
 *      generated guest name held by another address is rejected like any other.
 *  Client: on LoginRejected from the server it stops resending the login. New: boolean isRejected(); awaitJoined
 *      returns false promptly (well before its timeout) and isJoined() stays false. ClientGame.tick()/drain() of a
 *      rejected client send nothing to the server.
 *  Jar --client with a rejected name: prints "client login rejected name=NAME" and exits with status 4 (the
 *      existing "join failed" status is unchanged); it never reports "client joined".
 *  Before the ack a client sends only Login packets (no Move, no ping).
 */
public class Ep22Test {

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

    private static final String SCENE = "game:grass:64,64@200,200";

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /** A raw UDP "client" aimed at the server (or at any port). */
    private final class Raw implements AutoCloseable {
        final DatagramSocket s;
        final int port;

        Raw(int port) throws IOException {
            this.port = port;
            s = new DatagramSocket(0, InetAddress.getLoopbackAddress());
            toClose.add(this);
        }

        Raw() throws IOException {
            this(server.port());
        }

        void send(byte[] d) throws IOException {
            s.send(new DatagramPacket(d, d.length, InetAddress.getLoopbackAddress(), port));
        }

        void send(GamePacket p) throws IOException {
            send(p.toBytes());
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

        /** Everything that arrives within ms. */
        List<byte[]> drain(long ms) throws IOException {
            List<byte[]> out = new ArrayList<>();
            long end = System.nanoTime() + ms * 1_000_000L;
            while (System.nanoTime() < end) {
                byte[] d = recv(Math.max(1, (end - System.nanoTime()) / 1_000_000L));
                if (d != null) out.add(d);
            }
            return out;
        }

        /** Sends the login and returns the server's first answer: "ACK", "REJECT" or "NONE"; the rest is returned via drain. */
        String login(byte[] loginBytes) throws IOException {
            send(loginBytes);
            byte[] d = recv(2000);
            // stale roster notices (PlayerJoined) from earlier logins may precede the answer: skip only those
            while (d != null && GamePacket.parse(d).orElse(null) instanceof GamePacket.PlayerJoined) d = recv(2000);
            return kind(d);
        }

        String login(String name) throws IOException {
            return login(new GamePacket.Login(name, 5, 5).toBytes());
        }

        @Override
        public void close() {
            s.close();
        }
    }

    private static String kind(byte[] d) {
        if (d == null) return "NONE";
        if (Arrays.equals(d, Protocol.JOIN_ACK)) return "ACK";
        if (GamePacket.parse(d).orElse(null) instanceof GamePacket.LoginRejected) return "REJECT";
        return "OTHER:" + new String(d, StandardCharsets.US_ASCII);
    }

    private static List<GamePacket> parsed(List<byte[]> ds) {
        List<GamePacket> out = new ArrayList<>();
        for (byte[] d : ds) GamePacket.parse(d).ifPresent(out::add);
        return out;
    }

    private static long count(List<GamePacket> ps, java.util.function.Predicate<GamePacket> f) {
        return ps.stream().filter(f).count();
    }

    private static boolean until(java.util.function.BooleanSupplier c, long ms) throws InterruptedException {
        long end = System.nanoTime() + ms * 1_000_000L;
        while (!c.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        return c.getAsBoolean();
    }

    @Test
    void loginRejectedPacketRoundTripsAndRejects() {
        GamePacket.LoginRejected r = new GamePacket.LoginRejected("name-taken");
        assertEquals("05", r.id());
        assertEquals("05", GamePacket.ID_LOGIN_REJECTED);
        assertEquals("name-taken", GamePacket.REASON_NAME_TAKEN);
        assertEquals("05,name-taken", new String(r.toBytes(), StandardCharsets.US_ASCII));
        assertEquals(r, GamePacket.parse(r.toBytes()).orElseThrow());
        assertEquals(new GamePacket.LoginRejected("x"), GamePacket.parse(b("05,x")).orElseThrow());
        for (String bad : new String[] {"05", "05,", "05,a,b", "05,Upper", "05,has space", "05,name-taken,", "05,a1", "05,aaaaaaaaaaaaaaaaaaaaa"})
            assertTrue(GamePacket.parse(b(bad)).isEmpty(), bad);
        for (String bad : new String[] {"", "A", "a b", "a,b", "aaaaaaaaaaaaaaaaaaaaa"})
            assertThrows(IllegalArgumentException.class, () -> new GamePacket.LoginRejected(bad), bad);
        assertThrows(IllegalArgumentException.class, () -> new GamePacket.LoginRejected(null));
    }

    @Test
    @Timeout(60)
    void secondSamIsRejectedNeverDuplicatedAndNameFreesOnDisconnect() throws Exception {
        Raw first = new Raw(), second = new Raw(), watcher = new Raw();
        assertEquals("ACK", watcher.login("watch"));
        assertEquals("ACK", first.login("sam"));
        watcher.drain(300); // sam's PlayerJoined
        assertEquals("REJECT", second.login("sam"));
        assertEquals("REJECT", second.login("sam"), "asking again is rejected again");
        assertEquals(List.of("sam", "watch").stream().sorted().toList(), server.playerNames().stream().sorted().toList());
        assertEquals(2, server.clientCount());
        assertEquals(0, parsed(second.drain(300)).size(), "the rejected address is told nothing else");
        assertEquals(0, parsed(watcher.drain(300)).size(), "others are not told about a rejected login");
        // the rejected address is not a player: its moves and pings are dropped like a stranger's
        second.send(new GamePacket.Move("sam", 9, 9, 0, 0, 1));
        second.send(Protocol.PING);
        assertNull(second.recv(400));
        assertEquals(0, count(parsed(watcher.drain(300)), p -> p instanceof GamePacket.Move));

        // the holder repeating its login is still acked (one player), also with another position
        assertEquals("ACK", first.login("sam"));
        assertEquals("ACK", first.login(new GamePacket.Login("sam", 77, 88).toBytes()));
        assertEquals(2, server.clientCount());

        // a newcomer's roster lists exactly one sam
        Raw newcomer = new Raw();
        assertEquals("ACK", newcomer.login("nina"));
        List<GamePacket> roster = parsed(newcomer.drain(500));
        assertEquals(1, count(roster, p -> p instanceof GamePacket.PlayerJoined j && j.name().equals("sam")), roster.toString());
        assertEquals(0, count(roster, p -> p instanceof GamePacket.PlayerJoined j && j.name().equals("nina")), "roster excludes the newcomer");

        // case differs: a different name, allowed
        assertEquals("ACK", new Raw().login("Sam"));

        // the name frees on disconnect and the old second can now log in; the holder's PlayerLeft precedes
        first.send(new GamePacket.Disconnect());
        byte[] bye = first.recv(2000);
        while (bye != null && GamePacket.parse(bye).orElse(null) instanceof GamePacket.PlayerJoined) bye = first.recv(2000); // roster resent by the repeat logins
        assertEquals("ACK", kind(bye));
        assertTrue(until(() -> !server.playerNames().contains("sam"), 3000));
        assertEquals("ACK", second.login("sam"));
        assertEquals(1, server.playerNames().stream().filter("sam"::equals).count());
    }

    @Test
    @Timeout(60)
    void guestNamesNeverRejectNamelessAndCollisionsFollowTheRule() throws Exception {
        // chosen names that look like generated ones are held first: nameless joins must still all be acked, uniquely
        for (String n : new String[] {"guest1", "guest2", "guest3"}) assertEquals("ACK", new Raw().login(n));
        for (int i = 0; i < 4; i++) assertEquals("ACK", new Raw().login(Protocol.JOIN), "nameless join " + i);
        assertEquals(7, server.clientCount());
        assertEquals(7, server.playerNames().size(), "all names distinct: " + server.playerNames());
        // a repeated nameless join from the same address is still acked and adds nobody
        Raw guest = new Raw();
        assertEquals("ACK", guest.login(Protocol.JOIN));
        assertEquals("ACK", guest.login(Protocol.JOIN));
        assertEquals(8, server.clientCount());
        String generated = server.playerNames().stream().filter(n -> !n.equals("guest1") && !n.equals("guest2") && !n.equals("guest3")).findFirst().orElseThrow();
        assertTrue(generated.startsWith("guest"), generated);
        // choosing a generated name that another address holds is rejected
        assertEquals("REJECT", new Raw().login(generated));
        assertEquals("REJECT", new Raw().login("guest2"));
        assertEquals(8, server.clientCount());
    }

    @Test
    @Timeout(60)
    void clientStopsResendingOnRejectAndExposesIt() throws Exception {
        Raw holder = new Raw();
        assertEquals("ACK", holder.login("sam"));
        ClientGame g = ClientGame.connect(SCENE, "127.0.0.1", server.port(), "sam");
        toClose.add(g);
        long t0 = System.nanoTime();
        assertFalse(g.client().awaitJoined(10000), "a rejected client is not joined");
        assertTrue(System.nanoTime() - t0 < 3_000_000_000L, "awaitJoined must return promptly on reject");
        assertTrue(g.client().isRejected());
        assertFalse(g.client().isJoined());
        for (int i = 0; i < 20; i++) {
            g.tick();
            Thread.sleep(20);
        }
        assertEquals(1, server.clientCount());
        assertEquals(List.of("sam"), List.copyOf(server.playerNames()));

        // exact count of logins a rejecting fake server sees: no resends after the reject
        try (DatagramSocket fake = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            ClientGame r = ClientGame.connect(SCENE, "127.0.0.1", fake.getLocalPort(), "sam");
            toClose.add(r);
            int logins = 0, others = 0;
            boolean rejected = false;
            long end = System.nanoTime() + 2_000_000_000L;
            while (System.nanoTime() < end) {
                r.tick();
                fake.setSoTimeout(20);
                DatagramPacket p = new DatagramPacket(new byte[1024], 1024);
                try {
                    fake.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                if (GamePacket.parse(Arrays.copyOf(p.getData(), p.getLength())).orElse(null) instanceof GamePacket.Login) {
                    logins++;
                    if (!rejected) {
                        byte[] rej = new GamePacket.LoginRejected("name-taken").toBytes();
                        fake.send(new DatagramPacket(rej, rej.length, p.getSocketAddress()));
                        rejected = true;
                    }
                } else others++;
            }
            assertTrue(rejected, "fake never saw a login");
            assertTrue(logins <= 2, "a rejected client must stop resending, saw " + logins + " logins");
            assertEquals(0, others, "a rejected client sends nothing but its login");
            assertTrue(r.client().isRejected());
        }
    }

    @Test
    @Timeout(60)
    void clientSendsNothingButLoginBeforeTheAck() throws Exception {
        try (DatagramSocket fake = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            ClientGame g = ClientGame.connect(SCENE, "127.0.0.1", fake.getLocalPort(), "dave");
            toClose.add(g);
            g.input().press(java.awt.event.KeyEvent.VK_RIGHT);
            int logins = 0;
            long end = System.nanoTime() + 1_500_000_000L;
            while (System.nanoTime() < end) {
                g.tick();
                fake.setSoTimeout(10);
                DatagramPacket p = new DatagramPacket(new byte[1024], 1024);
                try {
                    fake.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                GamePacket gp = GamePacket.parse(Arrays.copyOf(p.getData(), p.getLength())).orElse(null);
                assertInstanceOf(GamePacket.Login.class, gp, "before the ack only logins may be sent, got "
                        + new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII));
                logins++;
            }
            assertTrue(logins >= 2, "login must keep being resent while unanswered: " + logins);
        }
    }

    @Test
    @Timeout(60)
    void relaysAreCleanStaleSeqNeverRelayedRelogginRestartsSeqNoImpersonation() throws Exception {
        Raw alice = new Raw(), bob = new Raw(), obs = new Raw();
        assertEquals("ACK", obs.login("obs"));
        assertEquals("ACK", alice.login("alice"));
        assertEquals("ACK", bob.login("bobby"));
        obs.drain(300);
        alice.drain(100);

        alice.send(new GamePacket.Move("alice", 10, 11, 1, 0, 5));
        alice.send(new GamePacket.Move("alice", 99, 99, 1, 0, 5)); // duplicate seq
        alice.send(new GamePacket.Move("alice", 98, 98, 1, 0, 3)); // stale
        alice.send(new GamePacket.Move("alice", 12, 13, 2, 1, 6));
        alice.send(new GamePacket.Move("bobby", 20, 21, 3, 0, 7)); // claims bobby's name: relayed as alice
        alice.send(new GamePacket.Move("zed", 22, 23, 3, 0, 8)); // claims an unknown name: relayed as alice
        List<GamePacket> seen = parsed(obs.drain(800));
        List<GamePacket.Move> moves = seen.stream().filter(p -> p instanceof GamePacket.Move).map(p -> (GamePacket.Move) p).toList();
        assertEquals(4, moves.size(), "only strictly newer seqs are relayed: " + moves);
        assertEquals(List.of(5, 6, 7, 8), moves.stream().map(GamePacket.Move::seq).toList());
        for (GamePacket.Move m : moves) assertEquals("alice", m.name(), "relay must carry the registered name");
        assertEquals(10, moves.get(0).x());
        assertEquals(12, moves.get(1).x());
        assertEquals(20, moves.get(2).x());
        // bob is a recipient too, and nobody's moves come back to their sender
        assertEquals(4, count(parsed(bob.drain(300)), p -> p instanceof GamePacket.Move m && m.name().equals("alice")));
        assertEquals(0, count(parsed(alice.drain(300)), p -> p instanceof GamePacket.Move));

        // relogin restarts the seq: a low seq after disconnect + login is relayed again
        alice.send(new GamePacket.Disconnect());
        assertEquals("ACK", kind(alice.recv(2000)));
        assertTrue(until(() -> !server.playerNames().contains("alice"), 3000));
        obs.drain(300);
        assertEquals("ACK", alice.login("alice"));
        obs.drain(300);
        alice.send(new GamePacket.Move("alice", 30, 31, 0, 0, 1));
        List<GamePacket> after = parsed(obs.drain(800));
        assertEquals(1, count(after, p -> p instanceof GamePacket.Move m && m.name().equals("alice") && m.seq() == 1 && m.x() == 30), after.toString());
    }

    @Test
    @Timeout(120)
    void jarClientWithATakenNameExitsWithTheRejectCode() throws Exception {
        Raw holder = new Raw();
        assertEquals("ACK", holder.login("sam"));
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(javaBin, "-Djava.awt.headless=true", "-jar", "build/game.jar", "--client", "--connect",
                "127.0.0.1:" + server.port(), "--name", "sam", "--headless", "--realtime", "--ticks", "600", "--scene", SCENE)
                .redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        Thread t = new Thread(() -> {
            try {
                out.append(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        try {
            assertTrue(p.waitFor(60, TimeUnit.SECONDS), "rejected jar client must exit, not run its ticks: " + out);
            t.join(2000);
            assertEquals(4, p.exitValue(), out.toString());
            assertTrue(out.toString().lines().anyMatch(l -> l.trim().equals("client login rejected name=sam")), out.toString());
            assertFalse(out.toString().contains("client joined"), out.toString());
            assertEquals(List.of("sam"), List.copyOf(server.playerNames()));
            assertEquals(1, server.clientCount());
        } finally {
            p.destroyForcibly();
        }
    }
}

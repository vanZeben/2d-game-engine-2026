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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;

/*
 * Episode 18 check: "The Login Packet" - packets with IDs, a login packet with a username, the server tracks
 * connected players, a second client appears on the first client's screen.
 *
 * CONTRACT the builder must satisfy (extends ep15/ep17; their tests keep passing: a raw Protocol.JOIN still gets
 * exactly Protocol.JOIN_ACK, pings answered for any logged-in address, Client.connect(host, port) stays nameless):
 *
 *  ge.net.GamePacket (public interface, JDK only). Wire form: ASCII text, 2-char ID, then fields, all separated by
 *  ',' (comma): no trailing delimiter, no spaces. Never matches "ping", "pong" or "GE1 ...".
 *    byte[] toBytes();   String id();
 *    static Optional<GamePacket> parse(byte[] data)   empty for ANY invalid input, never throws (null, empty,
 *        oversized, non-ASCII, unknown ID, wrong field count, non-integer or out-of-int-range numbers, bad name).
 *    record GamePacket.Login(String name, int x, int y)          id "00", client -> server. Wire "00,NAME,X,Y".
 *        The position is the client's spawn (world pixel of the sprite's top-left corner); negatives allowed ("-5").
 *    record GamePacket.PlayerJoined(String name, int x, int y)   id "03", server -> client. Wire "03,NAME,X,Y".
 *    A valid NAME is 1..16 chars of [A-Za-z0-9_]. (IDs 01/02 are reserved for disconnect/move in later episodes.)
 *    Records' constructors may reject an invalid name with IllegalArgumentException; parse must return empty instead.
 *    parse(p.toBytes()) equals p (records give equals).
 *
 *  ge.net.Client:
 *    static Client connect(String host, int port, String name, int x, int y)   named login: repeats the login
 *        packet until acked (like the join). The ack is exactly Protocol.JOIN_ACK, so awaitJoined/ping work.
 *    static Client connect(String host, int port, String name)  same with position 0,0.
 *    static Client connect(String host, int port)  unchanged: nameless (bare Protocol.JOIN).
 *  ge.net.Server:
 *    Set<String> playerNames()  snapshot of the names of logged-in players (named ones as given; nameless ones under a
 *        server-assigned name that is non-empty, a valid NAME, and unique among players). clientCount() unchanged.
 *    A repeated login from the same address is acked again but is ONE player and is not re-announced.
 *    An invalid login (bad name, wrong fields) is dropped: no ack, no player, server keeps working.
 *    When a NEW named player logs in, the server sends GamePacket.PlayerJoined(name, x, y) (from the login) to every
 *    OTHER registered address. Whether a newcomer is told of earlier players is NOT part of this episode (ep 19).
 *    Duplicate-name rejection, disconnect/timeout and movement are not part of this episode.
 *  ge.net.RemotePlayer (public, extends ge.entity.Mob): String name(); x()/y() = position from the packet;
 *    facing DOWN, walk frame 0; renders with Player.drawPose at (x - xOffset, y - yOffset).
 *  ge.net.ClientGame (public, AutoCloseable): a client-side game.
 *    static ClientGame connect(String sceneSpec, String host, int port, String name) throws IOException
 *        sceneSpec is a game: scene (Scene.parse); logs in with the world's player spawn position.
 *    World world();  Client client();
 *    void tick()   game thread only: drains client.drain(...) parsing each packet with GamePacket.parse, adds a
 *        RemotePlayer to the world for each PlayerJoined (ignoring one whose name equals this client's own, and
 *        one for a name already present), then world.tick with no input.
 *    void render(Screen s)  s.clear(0) then world.render(s, sheet) (what Game.render does).
 *    void close()  closes the client.
 *  CLI: --name NAME is accepted with --client (headless prints the same "client joined server=HOST:PORT" line and
 *    logs in with that name; position 0,0). Invalid NAME: exit status 2.
 *  --realtime (with --headless; else exit 2): paces the tick loop at 60 ticks/s instead of running as fast as
 *    possible, so other clients can log in during a networked headless run. Changes no existing output.
 *  A jar --client with a game: scene (headless and windowed) drives its world from the network exactly like
 *    ClientGame.tick: each game-thread tick it drains the client inbox and adds a RemotePlayer per PlayerJoined; the
 *    frames it dumps (--dump-dir) show those remote players.
 * All sockets are closed in finally/AfterEach; every test has @Timeout.
 */
public class Ep18Test {

    private Server server;
    private final List<AutoCloseable> toClose = new ArrayList<>();
    private DatagramSocket raw;

    @BeforeEach
    void up() throws IOException {
        server = Server.start(0);
    }

    @AfterEach
    void down() throws Exception {
        for (AutoCloseable c : toClose) c.close();
        if (raw != null) raw.close();
        server.close();
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    // ---- packet format ----

    @Test
    @Timeout(20)
    void packetsRoundTripAndHaveTheDocumentedWireForm() {
        GamePacket login = new GamePacket.Login("bob_7", 216, -5);
        assertArrayEquals(b("00,bob_7,216,-5"), login.toBytes());
        assertEquals("00", login.id());
        assertEquals(Optional.of(login), GamePacket.parse(login.toBytes()));
        GamePacket joined = new GamePacket.PlayerJoined("Zed", 1, 2);
        assertArrayEquals(b("03,Zed,1,2"), joined.toBytes());
        assertEquals("03", joined.id());
        assertEquals(Optional.of(joined), GamePacket.parse(b("03,Zed,1,2")));
        GamePacket.Login p = (GamePacket.Login) GamePacket.parse(b("00,A234567890123456,0,0")).orElseThrow();
        assertEquals(16, p.name().length(), "16-char name is the longest valid one");
        assertNotEquals(Arrays.toString(login.toBytes()), Arrays.toString(joined.toBytes()));
    }

    @Test
    @Timeout(20)
    void invalidPacketsAreRejectedWithoutThrowing() {
        String[] bad = {"", "ping", "pong", "GE1 JOIN", "GE1 ACK", "00", "00,bob", "00,bob,1", "00,bob,1,2,3", "00,,1,2",
            "00,b ob,1,2", "00,bob,x,2", "00,bob,1,2.5", "00,bob,99999999999,2", "00,bob,1,", "00,bob,1,2,", "00,bo-b,1,2",
            "00,A2345678901234567,1,2", "99,bob,1,2", "0,bob,1,2", "000,bob,1,2", "00;bob;1;2", "00 bob 1 2", "03,bob,1",
            "01,bob,1,2", "00,béb,1,2"};
        for (String s : bad) {
            byte[] data = s.getBytes(StandardCharsets.UTF_8);
            assertEquals(Optional.empty(), assertDoesNotThrow(() -> GamePacket.parse(data), s), "must reject: '" + s + "'");
        }
        assertEquals(Optional.empty(), GamePacket.parse(null));
        assertEquals(Optional.empty(), GamePacket.parse(new byte[] {(byte) 0xFF, (byte) 0xFE, 0, 1}));
        assertEquals(Optional.empty(), GamePacket.parse(new byte[2000]));
        byte[] huge = new byte[2000];
        Arrays.fill(huge, (byte) 'a');
        huge[0] = '0';
        huge[1] = '0';
        assertEquals(Optional.empty(), GamePacket.parse(huge));
    }

    // ---- server registry ----

    private void sendRaw(byte[] data) throws IOException {
        if (raw == null) {
            raw = new DatagramSocket();
            raw.setSoTimeout(300);
        }
        raw.send(new DatagramPacket(data, data.length, InetAddress.getByName("127.0.0.1"), server.port()));
    }

    private byte[] recvRaw() throws IOException {
        byte[] buf = new byte[1024];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        try {
            raw.receive(dp);
        } catch (SocketTimeoutException e) {
            return null;
        }
        return Arrays.copyOf(dp.getData(), dp.getLength());
    }

    private static void until(java.util.function.BooleanSupplier c, long ms) throws InterruptedException {
        long end = System.nanoTime() + ms * 1_000_000L;
        while (!c.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
    }

    @Test
    @Timeout(30)
    void serverRegistersNamedAndNamelessLoginsWithUniqueNames() throws Exception {
        assertTrue(server.playerNames().isEmpty());
        Client alice = Client.connect("127.0.0.1", server.port(), "alice", 10, 20);
        toClose.add(alice);
        assertTrue(alice.awaitJoined(3000), "named login is acked with the join ack");
        Client n1 = Client.connect("127.0.0.1", server.port());
        Client n2 = Client.connect("127.0.0.1", server.port());
        toClose.add(n1);
        toClose.add(n2);
        assertTrue(n1.awaitJoined(3000) && n2.awaitJoined(3000));
        Client bob = Client.connect("127.0.0.1", server.port(), "bob");
        toClose.add(bob);
        assertTrue(bob.awaitJoined(3000));
        until(() -> server.playerNames().size() >= 4, 3000);
        Set<String> names = server.playerNames();
        assertEquals(4, names.size(), "two nameless clients get two different names: " + names);
        assertTrue(names.containsAll(List.of("alice", "bob")), names.toString());
        for (String n : names) assertTrue(GamePacket.parse(b("00," + n + ",0,0")).isPresent(), "assigned name must be a valid NAME: '" + n + "'");
        assertEquals(4, server.clientCount());
        assertTrue(alice.ping(2000), "logged-in client can ping");
    }

    @Test
    @Timeout(30)
    void repeatedLoginIsOnePlayerAndInvalidLoginIsDropped() throws Exception {
        sendRaw(b("00,not valid,1,2"));
        assertNull(recvRaw(), "invalid login gets no reply");
        sendRaw(b("00,carol,3,4,5"));
        assertNull(recvRaw());
        assertTrue(server.playerNames().isEmpty());
        sendRaw(b("00,carol,3,4"));
        assertArrayEquals(Protocol.JOIN_ACK, recvRaw());
        sendRaw(b("00,carol,3,4"));
        assertArrayEquals(Protocol.JOIN_ACK, recvRaw(), "a repeated login is acked again");
        assertEquals(Set.of("carol"), server.playerNames());
        sendRaw(b("ping"));
        assertArrayEquals(Protocol.PONG, recvRaw());
        // the raw nameless form still works, exactly as in ep15
        try (DatagramSocket other = new DatagramSocket()) {
            other.setSoTimeout(1000);
            other.send(new DatagramPacket(Protocol.JOIN, Protocol.JOIN.length, InetAddress.getByName("127.0.0.1"), server.port()));
            byte[] buf = new byte[64];
            DatagramPacket dp = new DatagramPacket(buf, buf.length);
            other.receive(dp);
            assertArrayEquals(Protocol.JOIN_ACK, Arrays.copyOf(buf, dp.getLength()));
        }
        assertEquals(2, server.playerNames().size());
        assertTrue(server.playerNames().contains("carol"));
    }

    @Test
    @Timeout(30)
    void existingClientIsToldWhenNewPlayerLogsIn() throws Exception {
        sendRaw(b("00,first,1,1"));
        assertArrayEquals(Protocol.JOIN_ACK, recvRaw());
        try (DatagramSocket second = new DatagramSocket()) {
            second.send(new DatagramPacket(b("00,second,40,50"), 15, InetAddress.getByName("127.0.0.1"), server.port()));
            byte[] got = recvRaw();
            assertNotNull(got, "first client must be notified");
            assertEquals(Optional.of(new GamePacket.PlayerJoined("second", 40, 50)), GamePacket.parse(got));
            assertNull(recvRaw(), "exactly one notice, and a retransmitted login is not announced again");
        }
    }

    // ---- a second client appears on the first client's screen ----

    private static final String SCENE_A = "game:grass:64,64@200,200";
    private static final String SCENE_B = "game:grass:64,64@216,208";

    private static int[] box(Screen s, int x0, int y0) {
        int[] out = new int[16 * 16];
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) out[y * 16 + x] = s.pixels[(y0 + y) * s.width + x0 + x];
        return out;
    }

    private static List<RemotePlayer> remotes(ClientGame g) {
        List<RemotePlayer> r = new ArrayList<>();
        for (Mob m : g.world().entities()) if (m instanceof RemotePlayer rp) r.add(rp);
        return r;
    }

    @Test
    @Timeout(40)
    void secondClientAppearsOnFirstClientsScreen() throws Exception {
        ClientGame a = ClientGame.connect(SCENE_A, "127.0.0.1", server.port(), "alice");
        toClose.add(a);
        assertTrue(a.client().awaitJoined(3000));
        for (int i = 0; i < 20; i++) a.tick();
        assertTrue(remotes(a).isEmpty(), "alone: nobody else in the world (and not itself)");
        Screen before = new Screen(Game.WIDTH, Game.HEIGHT);
        a.render(before);

        ClientGame b = ClientGame.connect(SCENE_B, "127.0.0.1", server.port(), "bobby");
        toClose.add(b);
        assertTrue(b.client().awaitJoined(3000));
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (remotes(a).isEmpty() && System.nanoTime() < end) {
            a.tick();
            Thread.sleep(5);
        }
        List<RemotePlayer> rs = remotes(a);
        assertEquals(1, rs.size(), "exactly one remote player");
        assertEquals("bobby", rs.get(0).name());
        assertEquals(216, rs.get(0).x());
        assertEquals(208, rs.get(0).y());
        assertEquals(Mob.Dir.DOWN, rs.get(0).dir());
        for (int i = 0; i < 30; i++) a.tick();
        assertEquals(1, remotes(a).size(), "still one after more ticks");
        assertEquals(2, server.playerNames().size());

        // A's camera is at (200+8-80, 200+8-60) = (128, 148): B is at screen (88, 60).
        Screen after = new Screen(Game.WIDTH, Game.HEIGHT);
        a.render(after);
        Screen model = new Screen(Game.WIDTH, Game.HEIGHT);
        World ref = Scene.parse(SCENE_B).newWorld(); // a never-moved player at B's position, centred on screen (72, 52)
        model.clear(0);
        ref.render(model, Scene.sheet());
        int[] want = box(model, 72, 52);
        int[] got = box(after, 88, 60);
        assertArrayEquals(want, got, "B's sprite must be drawn at PB minus A's camera");
        assertFalse(Arrays.equals(box(before, 88, 60), got), "the box changed once B arrived");
        // A itself is unchanged: its own sprite is still at the centre
        assertArrayEquals(box(before, 72, 52), box(after, 72, 52));
    }

    // ---- CLI ----

    @Test
    @Timeout(60)
    void jarClientAcceptsNameAndLogsInWithIt() throws Exception {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(java, "-Djava.awt.headless=true", "-jar", "build/game.jar", "--headless", "--ticks", "2",
                "--client", "--connect", "127.0.0.1:" + server.port(), "--name", "jarbird").redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        Thread drain = new Thread(() -> {
            try {
                out.append(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        });
        drain.start();
        try {
            assertTrue(p.waitFor(40, TimeUnit.SECONDS), "client hung: " + out);
            drain.join(2000);
            assertEquals(0, p.exitValue(), out.toString());
            assertTrue(out.toString().contains("client joined server=127.0.0.1:" + server.port()), out.toString());
            assertTrue(server.playerNames().contains("jarbird"), server.playerNames().toString());
        } finally {
            p.destroyForcibly();
        }
    }

    @Test
    @Timeout(90)
    void jarClientShowsASecondPlayerInItsDumpedFrames() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ep18-realtime");
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(javaBin, "-Djava.awt.headless=true", "-jar", "build/game.jar", "--client", "--connect",
                "127.0.0.1:" + server.port(), "--name", "alice", "--headless", "--realtime", "--ticks", "180", "--scene", SCENE_A,
                "--dump-dir", dir.toString()).redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        Thread drain = new Thread(() -> {
            try {
                out.append(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        });
        drain.start();
        try {
            until(() -> server.playerNames().contains("alice") || !p.isAlive(), 20000);
            assertTrue(server.playerNames().contains("alice"), "jar client never logged in: " + out);
            Client bob = Client.connect("127.0.0.1", server.port(), "bob", 216, 208);
            toClose.add(bob);
            assertTrue(bob.awaitJoined(3000));
            assertTrue(p.waitFor(60, TimeUnit.SECONDS), "client hung: " + out);
            drain.join(2000);
            assertEquals(0, p.exitValue(), out.toString());
            java.util.List<java.nio.file.Path> frames;
            try (var st = java.nio.file.Files.list(dir)) {
                frames = st.filter(f -> f.getFileName().toString().matches("frame-\\d+\\.png")).sorted().toList();
            }
            assertFalse(frames.isEmpty(), "no frames dumped: " + out);
            Screen first = fromPng(frames.get(0));
            Screen last = fromPng(frames.get(frames.size() - 1));
            Screen model = new Screen(Game.WIDTH, Game.HEIGHT);
            model.clear(0);
            Scene.parse(SCENE_B).newWorld().render(model, Scene.sheet()); // never-moved sprite, centred at (72, 52)
            int[] want = box(model, 72, 52);
            assertArrayEquals(want, box(last, 88, 60), "bob's sprite must be in the last frame at screen (88,60)");
            assertFalse(Arrays.equals(want, box(first, 88, 60)), "bob must not be in the first frame");
        } finally {
            p.destroyForcibly();
        }
    }

    private static Screen fromPng(java.nio.file.Path f) throws IOException {
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(f.toFile());
        Screen s = new Screen(img.getWidth(), img.getHeight());
        for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) s.pixels[y * s.width + x] = img.getRGB(x, y) & 0xFFFFFF;
        return s;
    }
}

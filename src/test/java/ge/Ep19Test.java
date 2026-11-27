package ge;

import static org.junit.jupiter.api.Assertions.*;

import ge.entity.Mob;
import ge.net.Client;
import ge.net.ClientGame;
import ge.net.RemotePlayer;
import ge.net.Server;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;

/*
 * Episode 19 check: "Cleaning up the Login Packet" - a newly joined client is told about the players already connected.
 *
 * CONTRACT (builds on ep18; no new API or CLI): API is exactly ep18's (Server, Client, ClientGame, GamePacket,
 * RemotePlayer). New behaviour: when a NAMED player logs in, the server also tells THAT newcomer about every other
 * already-logged-in NAMED player (as GamePacket.PlayerJoined(name, x, y) with the position from that player's login),
 * so a ClientGame / jar client that joins late ends up with one RemotePlayer per earlier player, drawn at
 * (position - own camera). The server may resend this state periodically (docs/net.md): repeats must never create a
 * duplicate RemotePlayer, and the client never shows itself. A nameless (GE1 JOIN) connection is not a player on
 * anyone's screen. Disconnect/timeout (ep 20), movement (ep 21), duplicate names (ep 22) are not tested here.
 * Jar check: server in-process; A and B (ClientGame) log in first; then the jar client C
 * (--client --connect H:P --name carol --headless --realtime --ticks 120 --scene SCENE_C --dump-dir D) must show A's
 * sprite (and B's) in its LAST dumped frame at (position - C's camera), with the never-moved-sprite model.
 */
public class Ep19Test {

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

    // Spawns (player top-left). C at (168,200): camera (96,148); A -> screen (104,52), B -> (120,60); C itself at (72,52).
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
        Scene.parse(scene).newWorld().render(model, Scene.sheet()); // never-moved sprite centred at (72, 52)
        return box(model, 72, 52);
    }

    private static List<RemotePlayer> remotes(ClientGame g) {
        List<RemotePlayer> r = new ArrayList<>();
        for (Mob m : g.world().entities()) if (m instanceof RemotePlayer rp) r.add(rp);
        return r;
    }

    private ClientGame login(String scene, String name) throws Exception {
        ClientGame g = ClientGame.connect(scene, "127.0.0.1", server.port(), name);
        toClose.add(g);
        assertTrue(g.client().awaitJoined(3000), name + " must be acked");
        return g;
    }

    private static void until(java.util.function.BooleanSupplier c, long ms) throws InterruptedException {
        long end = System.nanoTime() + ms * 1_000_000L;
        while (!c.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
    }

    @Test
    @Timeout(60)
    void newcomerSeesEveryEarlierPlayerOnceAtTheRightPlace() throws Exception {
        ClientGame a = login(SCENE_A, "alice");
        ClientGame b = login(SCENE_B, "bobby");
        Client nameless = Client.connect("127.0.0.1", server.port());
        toClose.add(nameless);
        assertTrue(nameless.awaitJoined(3000));
        ClientGame c = login(SCENE_C, "carol");
        Screen before = new Screen(Game.WIDTH, Game.HEIGHT);
        c.render(before);

        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (remotes(c).size() < 2 && System.nanoTime() < end) {
            c.tick();
            Thread.sleep(5);
        }
        // run well past any periodic resend; repeats must not duplicate, and a nameless connection is never a player
        for (int i = 0; i < 400; i++) {
            c.tick();
            Thread.sleep(5);
        }
        Map<String, RemotePlayer> byName = new HashMap<>();
        for (RemotePlayer rp : remotes(c)) assertNull(byName.put(rp.name(), rp), "duplicate RemotePlayer " + rp.name());
        assertEquals(Set.of("alice", "bobby"), byName.keySet(), "C sees exactly A and B (not itself, not the nameless client)");
        assertEquals(200, byName.get("alice").x());
        assertEquals(200, byName.get("alice").y());
        assertEquals(216, byName.get("bobby").x());
        assertEquals(208, byName.get("bobby").y());
        assertEquals(Mob.Dir.DOWN, byName.get("alice").dir());

        Screen after = new Screen(Game.WIDTH, Game.HEIGHT);
        c.render(after);
        assertArrayEquals(modelBox(SCENE_A), box(after, 104, 52), "A's sprite at PA minus C's camera");
        assertArrayEquals(modelBox(SCENE_B), box(after, 120, 60), "B's sprite at PB minus C's camera");
        assertFalse(Arrays.equals(box(before, 104, 52), box(after, 104, 52)), "the box changed once A was announced");
        assertArrayEquals(box(before, 72, 52), box(after, 72, 52), "C's own sprite is unchanged and drawn once");

        // earlier players still see only each other-or-newcomer, once each
        for (int i = 0; i < 60; i++) {
            a.tick();
            Thread.sleep(5);
        }
        Set<String> aSees = new java.util.HashSet<>();
        for (RemotePlayer rp : remotes(a)) assertTrue(aSees.add(rp.name()), "A has duplicate " + rp.name());
        assertTrue(aSees.contains("carol") && !aSees.contains("alice"), aSees.toString());
        assertEquals(4, server.playerNames().size(), "server holds A, B, C and the nameless client");
    }

    @Test
    @Timeout(120)
    void jarClientJoiningLateShowsTheEarlierPlayersInItsLastFrame() throws Exception {
        login(SCENE_A, "alice");
        login(SCENE_B, "bobby");
        Path dir = Files.createTempDirectory("ep19-late");
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(javaBin, "-Djava.awt.headless=true", "-jar", "build/game.jar", "--client", "--connect",
                "127.0.0.1:" + server.port(), "--name", "carol", "--headless", "--realtime", "--ticks", "120", "--scene", SCENE_C,
                "--dump-dir", dir.toString()).redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        Thread drain = new Thread(() -> {
            try {
                out.append(new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        });
        drain.start();
        try {
            until(() -> server.playerNames().contains("carol") || !p.isAlive(), 10000);
            boolean registeredWhileRunning = server.playerNames().contains("carol");
            assertTrue(p.waitFor(90, TimeUnit.SECONDS), "client hung: " + out);
            drain.join(2000);
            assertEquals(0, p.exitValue(), out.toString());
            assertTrue(registeredWhileRunning, "server never listed carol while the client ran: " + out);
            List<Path> frames;
            try (var st = Files.list(dir)) {
                frames = st.filter(f -> f.getFileName().toString().matches("frame-\\d+\\.png")).sorted().toList();
            }
            assertFalse(frames.isEmpty(), "no frames dumped: " + out);
            Screen last = fromPng(frames.get(frames.size() - 1));
            assertArrayEquals(modelBox(SCENE_A), box(last, 104, 52), "alice's sprite in the last frame at screen (104,52)");
            assertArrayEquals(modelBox(SCENE_B), box(last, 120, 60), "bobby's sprite in the last frame at screen (120,60)");
        } finally {
            p.destroyForcibly();
        }
    }

    private static Screen fromPng(Path f) throws IOException {
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(f.toFile());
        Screen s = new Screen(img.getWidth(), img.getHeight());
        for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) s.pixels[y * s.width + x] = img.getRGB(x, y) & 0xFFFFFF;
        return s;
    }
}

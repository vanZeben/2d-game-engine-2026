package ge;

import static org.junit.jupiter.api.Assertions.*;

import ge.net.Client;
import ge.net.Server;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;

/*
 * Episode 15 check: "Preparing for Multiplayer"
 * SPEC: The game can start as client or server (prompt or flag) and can host and join on localhost.
 *
 * CONTRACT the builder must satisfy (UDP via java.net.DatagramSocket, JDK only; package ge.net; localhost only).
 * Ep 16 owns docs/net.md, ep 17 ping/pong, ep 18 the login packet: ep 15 defines ONLY roles + a minimal join
 * handshake (request/ack, wire format free; the client must retransmit its request since UDP is lossy).
 * -----------------------------------------------------------------------------------------------------------
 * 1. ge.net.Server (implements AutoCloseable)
 *      public static Server start(int port) throws IOException   port 0 = pick a free port; bound to loopback/any
 *      public int port()          the actually bound port (> 0)
 *      public int clientCount()   distinct clients that have joined (a client retransmitting counts once)
 *      public void close()        stops receive thread(s), closes the socket; idempotent; returns within ~2 s
 * 2. ge.net.Client (implements AutoCloseable)
 *      public static Client connect(String host, int port) throws IOException   returns immediately; starts
 *                                 sending join requests in the background; never throws just because nobody listens
 *      public boolean isJoined()  true once the server's ack arrived
 *      public boolean awaitJoined(long timeoutMillis)  blocks up to the timeout, returns isJoined()
 *      public void close()        idempotent; stops threads, closes the socket
 * 3. CLI (Main). Roles are mutually exclusive; every earlier flag keeps its meaning.
 *      --server [--port P]            host; P defaults to 0 (free port; documented default may differ, tests pass --port)
 *      --client --connect HOST:PORT   join; --client without --connect, or --server with --client, exits 2
 *    Headless (--headless [--ticks N]):
 *      server: prints one flushed stdout line `server listening port=P` once bound (P = bound port), then runs
 *              until killed, unless --ticks is GIVEN, in which case it exits 0 after N ticks (--ticks 0 = at once)
 *              and closes its socket. (Main's default of 600 ticks does NOT apply to a server.)
 *      client: waits for the ack up to 3000 ms. On ack prints `client joined server=HOST:PORT` (HOST:PORT exactly as
 *              given), then runs --ticks N (default 600; ticks are as-fast-as-possible) and exits 0. If no ack within
 *              3000 ms it prints NO "joined" line, prints `client join failed server=HOST:PORT` to stdout and exits 3.
 *    Windowed with neither flag: a role prompt (single-player / host / join) is allowed but is not tested (no
 *    display). No --server/--client: behaviour is exactly as before (single-player).
 */
public class Ep15Test {

    private static final Path JAR = Paths.get("build/game.jar");
    private static final Pattern LISTEN = Pattern.compile("server listening port=(\\d+)");

    // ---- in-process ---------------------------------------------------------------------------

    @Test
    @Timeout(20)
    void clientJoinsInProcessServer() throws Exception {
        try (Server s = Server.start(0)) {
            assertTrue(s.port() > 0 && s.port() < 65536);
            assertEquals(0, s.clientCount());
            try (Client c = Client.connect("127.0.0.1", s.port())) {
                assertTrue(c.awaitJoined(5000), "client should be acked");
                assertTrue(c.isJoined());
                waitFor(() -> s.clientCount() == 1, 2000);
                assertEquals(1, s.clientCount());
                Thread.sleep(300); // retransmits/duplicates must not inflate the count
                assertEquals(1, s.clientCount());
            }
        }
    }

    @Test
    @Timeout(20)
    void twoClientsJoin() throws Exception {
        try (Server s = Server.start(0);
                Client a = Client.connect("127.0.0.1", s.port());
                Client b = Client.connect("localhost", s.port())) {
            assertTrue(a.awaitJoined(5000));
            assertTrue(b.awaitJoined(5000));
            waitFor(() -> s.clientCount() == 2, 2000);
            assertEquals(2, s.clientCount());
        }
    }

    @Test
    @Timeout(20)
    void clientToDeadPortDoesNotJoinAndClosesCleanly() throws Exception {
        int dead = freeUdpPort();
        long t0 = System.nanoTime();
        Client c = Client.connect("127.0.0.1", dead);
        assertFalse(c.awaitJoined(700));
        assertFalse(c.isJoined());
        c.close();
        c.close(); // idempotent
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 5000, "must not hang");
    }

    @Test
    @Timeout(20)
    void closeReleasesPortAndIsIdempotent() throws Exception {
        Server s = Server.start(0);
        int port = s.port();
        Client c = Client.connect("127.0.0.1", port);
        assertTrue(c.awaitJoined(5000));
        long t0 = System.nanoTime();
        s.close();
        s.close();
        c.close();
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 4000, "close must return promptly");
        try (DatagramSocket again = new DatagramSocket(port)) { // port is free again
            assertEquals(port, again.getLocalPort());
        }
        // a late client of a closed server just never joins
        try (Client late = Client.connect("127.0.0.1", port)) {
            assertFalse(late.awaitJoined(500));
        }
    }

    @Test
    @Timeout(20)
    void serverStartsOnExplicitPort() throws Exception {
        int p = freeUdpPort();
        try (Server s = Server.start(p)) {
            assertEquals(p, s.port());
        }
    }

    // ---- jar, real processes ------------------------------------------------------------------

    @Test
    @Timeout(60)
    void serverAndClientSubprocessesJoin() throws Exception {
        Process server = launch("--server", "--port", "0", "--headless");
        try {
            BufferedReader out = reader(server);
            int port = readPort(out, 10_000);
            Proc client = run(20, "--client", "--connect", "127.0.0.1:" + port, "--headless", "--ticks", "5");
            assertEquals(0, client.exit, client.out);
            assertTrue(client.out.contains("client joined server=127.0.0.1:" + port), client.out);
            assertTrue(server.isAlive(), "a server without --ticks keeps running");
        } finally {
            server.destroy();
            if (!server.waitFor(5, TimeUnit.SECONDS)) server.destroyForcibly();
        }
        assertFalse(server.isAlive());
    }

    @Test
    @Timeout(60)
    void clientToDeadPortSubprocessFailsWithoutJoining() throws Exception {
        int dead = freeUdpPort();
        long t0 = System.nanoTime();
        Proc p = run(30, "--client", "--connect", "127.0.0.1:" + dead, "--headless", "--ticks", "5");
        assertFalse(p.out.contains("client joined"), p.out);
        assertTrue(p.out.contains("client join failed server=127.0.0.1:" + dead), p.out);
        assertEquals(3, p.exit);
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - t0) < 25);
    }

    @Test
    @Timeout(60)
    void serverWithTicksExitsAfterListening() throws Exception {
        int p = freeUdpPort();
        Proc s = run(20, "--server", "--port", "" + p, "--headless", "--ticks", "0");
        assertEquals(0, s.exit, s.out);
        Matcher m = LISTEN.matcher(s.out);
        assertTrue(m.find(), s.out);
        assertEquals(p, Integer.parseInt(m.group(1)));
    }

    @Test
    @Timeout(60)
    void singlePlayerAndBadRolesUnchanged() throws Exception {
        Proc sp = run(20, "--headless", "--ticks", "3");
        assertEquals(0, sp.exit, sp.out);
        assertTrue(sp.out.contains("ticks=3 frames=3"), sp.out);
        assertFalse(sp.out.contains("listening") || sp.out.contains("joined"));
        assertEquals(2, run(20, "--client", "--headless", "--ticks", "1").exit, "--client needs --connect");
    }

    // ---- helpers ------------------------------------------------------------------------------

    private interface Cond { boolean ok(); }

    private static void waitFor(Cond c, long ms) throws InterruptedException {
        long end = System.nanoTime() + ms * 1_000_000L;
        while (!c.ok() && System.nanoTime() < end) Thread.sleep(20);
    }

    private static int freeUdpPort() throws IOException {
        try (DatagramSocket s = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            return s.getLocalPort();
        }
    }

    private static Process launch(String... args) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.awt.headless=true", "-jar", JAR.toString()));
        cmd.addAll(List.of(args));
        return new ProcessBuilder(cmd).redirectErrorStream(true).start();
    }

    private static BufferedReader reader(Process p) {
        return new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
    }

    private static int readPort(BufferedReader out, long timeoutMs) throws Exception {
        long end = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < end) {
            if (!out.ready()) {
                Thread.sleep(25);
                continue;
            }
            String line = out.readLine();
            if (line == null) break;
            Matcher m = LISTEN.matcher(line);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        fail("server never printed 'server listening port=P'");
        return -1;
    }

    private record Proc(int exit, String out) {}

    private static Proc run(int timeoutSec, String... args) throws Exception {
        Process p = launch(args);
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail("process hung: " + String.join(" ", args) + "\n" + out);
        }
        return new Proc(p.exitValue(), out);
    }
}

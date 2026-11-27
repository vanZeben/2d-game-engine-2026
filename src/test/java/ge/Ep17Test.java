package ge;

import static org.junit.jupiter.api.Assertions.*;

import ge.net.Client;
import ge.net.Protocol;
import ge.net.Server;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.*;

/*
 * Episode 17 check: "Basic UDP Client/Server Ping Pong"
 * SPEC: client sends "ping", server replies "pong", over UDP.
 *
 * CONTRACT the builder must satisfy (extends ep15 API; docs/net.md: packets from unknown addresses are dropped,
 * so a ping only counts from an address that has joined, i.e. completed the GE1 JOIN / JOIN_ACK handshake):
 *   ge.net.Client:  public boolean ping(long timeoutMillis)
 *       Sends ONE datagram whose payload is exactly the 4 ASCII bytes "ping" to the server, then blocks up to the
 *       timeout for a datagram whose payload is exactly the 4 ASCII bytes "pong" from the server.
 *       true = pong arrived; false = timeout (never throws on timeout, nor after close). May be called repeatedly,
 *       also from a thread other than the receive thread. Must not disturb join state or break later pings.
 *   ge.net.Server: on receiving exactly "ping" from a joined address, replies exactly "pong" to that address.
 *       From an address that never joined: no reply (dropped). Anything else (empty, "pin", "PING", "ping!",
 *       oversized datagrams) gets no pong and must not crash the server or break later pings.
 *   Constants for the wire format are NOT required; tests use the literal bytes.
 * Not tested here: CLI. All sockets are closed in finally/AfterEach; every test has @Timeout.
 */
public class Ep17Test {

    private Server server;
    private Client client;
    private DatagramSocket raw;

    @BeforeEach
    void up() throws IOException {
        server = Server.start(0);
    }

    @AfterEach
    void down() {
        if (client != null) client.close();
        if (raw != null) raw.close();
        server.close();
    }

    private Client join() throws IOException {
        client = Client.connect("127.0.0.1", server.port());
        assertTrue(client.awaitJoined(3000), "client should join");
        return client;
    }

    private void send(DatagramSocket s, byte[] b) throws IOException {
        s.send(new DatagramPacket(b, b.length, InetAddress.getByName("127.0.0.1"), server.port()));
    }

    /** @return payload received within timeoutMs, or null */
    private byte[] recv(DatagramSocket s, int timeoutMs) throws IOException {
        byte[] buf = new byte[2048];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        s.setSoTimeout(timeoutMs);
        try {
            s.receive(p);
        } catch (SocketTimeoutException e) {
            return null;
        }
        return Arrays.copyOf(buf, p.getLength());
    }

    /** A raw socket that has completed the handshake with the literal wire bytes. */
    private DatagramSocket rawJoined() throws IOException {
        raw = new DatagramSocket();
        byte[] ack = null;
        for (int i = 0; i < 10 && ack == null; i++) { // retransmit like a real client: UDP may lose
            send(raw, Protocol.JOIN);
            ack = recv(raw, 300);
        }
        assertArrayEquals(Protocol.JOIN_ACK, ack, "handshake ack");
        return raw;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    @Timeout(20)
    void joinedClientGetsPong() throws IOException {
        assertTrue(join().ping(2000), "pong expected within 2 s on localhost");
    }

    @Test
    @Timeout(30)
    void severalPingsInARowEachGetPong() throws IOException {
        join();
        for (int i = 0; i < 20; i++) assertTrue(client.ping(2000), "ping #" + i);
        assertTrue(client.isJoined(), "pinging must not disturb join state");
        assertEquals(1, server.clientCount());
    }

    @Test
    @Timeout(30)
    void twoClientsEachGetTheirOwnPong() throws IOException {
        join();
        try (Client other = Client.connect("127.0.0.1", server.port())) {
            assertTrue(other.awaitJoined(3000));
            assertTrue(client.ping(2000));
            assertTrue(other.ping(2000));
            assertTrue(client.ping(2000));
        }
    }

    @Test
    @Timeout(20)
    void wireFormatIsLiterallyPingAndPong() throws IOException {
        rawJoined();
        send(raw, ascii("ping"));
        byte[] reply = recv(raw, 2000);
        assertNotNull(reply, "server must answer a joined address's ping");
        assertArrayEquals(ascii("pong"), reply, "reply payload must be exactly the bytes 'pong'");
        send(raw, ascii("ping"));
        assertArrayEquals(ascii("pong"), recv(raw, 2000), "second ping, same socket");
        assertNull(recv(raw, 200), "exactly one pong per ping");
    }

    @Test
    @Timeout(20)
    void neverJoinedAddressGetsNoPong() throws IOException {
        raw = new DatagramSocket();
        send(raw, ascii("ping"));
        assertNull(recv(raw, 700), "unknown address: dropped, no pong (docs/net.md)");
        // and it did not become a client by pinging
        assertEquals(0, server.clientCount());
    }

    @Test
    @Timeout(30)
    void malformedAndOversizedDatagramsAreIgnoredAndDoNotBreakPings() throws IOException {
        rawJoined();
        byte[][] junk = {
            new byte[0], ascii("pin"), ascii("PING"), ascii("Ping"), ascii("ping!"), ascii(" ping"),
            ascii("pong"), ascii("ping\n"), new byte[] {0, 0, 0, 0}, new byte[5000], ascii("ping".repeat(500)),
        };
        for (byte[] j : junk) send(raw, j);
        assertNull(recv(raw, 500), "no reply to anything that is not exactly 'ping'");
        send(raw, ascii("ping"));
        assertArrayEquals(ascii("pong"), recv(raw, 2000), "server still answers after junk");
        // junk from a never-joined socket must not hurt a joined client either
        try (DatagramSocket stranger = new DatagramSocket()) {
            for (byte[] j : junk) send(stranger, j);
        }
        join();
        assertTrue(client.ping(2000));
    }

    @Test
    @Timeout(20)
    void pingAfterCloseOrWithNoServerReturnsFalseNotThrows() throws IOException {
        join();
        assertTrue(client.ping(2000));
        server.close();
        assertFalse(client.ping(300), "dead server: timeout -> false");
        client.close();
        assertFalse(client.ping(100), "closed client: false, no exception");
    }
}

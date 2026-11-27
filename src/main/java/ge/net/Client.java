package ge.net;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The joining side. {@link #connect} returns at once; a background thread sends join requests (retransmitting,
 * since UDP is lossy) until the server's ack arrives or the client is closed.
 */
public final class Client extends Endpoint {
    /** Pause between join requests while waiting for the ack. */
    static final long RETRANSMIT_MS = 100;

    private final InetSocketAddress server;
    private volatile boolean joined;
    /** Counted down on join or close: wakes the join loop and any awaitJoined. */
    private final CountDownLatch wake = new CountDownLatch(1);

    private Client(DatagramSocket socket, InetSocketAddress server) {
        super(socket);
        this.server = server;
    }

    /** @throws IOException if the host name cannot be resolved or no local socket can be opened; never because nobody listens */
    public static Client connect(String host, int port) throws IOException {
        InetSocketAddress addr = new InetSocketAddress(InetAddress.getByName(host), port);
        Client c = new Client(new DatagramSocket(), addr);
        c.startReceiving("client");
        c.startThread("client-join", c::joinLoop);
        return c;
    }

    public boolean isJoined() {
        return joined;
    }

    /** Blocks up to the timeout for the server's ack; returns {@link #isJoined()}. */
    public boolean awaitJoined(long timeoutMillis) {
        try {
            wake.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return isJoined();
    }

    private void joinLoop() {
        try {
            while (!isClosed() && !isJoined()) {
                send(server, Protocol.JOIN);
                wake.await(RETRANSMIT_MS, TimeUnit.MILLISECONDS);
            }
        } catch (IOException e) {
            // socket closed under us: we are shutting down
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    protected boolean handle(SocketAddress from, byte[] data) {
        if (!server.equals(from)) return true; // not from the server we joined: drop
        if (Protocol.isJoinAck(data)) {
            joined = true;
            wake.countDown();
            return true;
        }
        return false; // game packets go to the game thread
    }

    @Override
    protected void onClose() {
        wake.countDown(); // wake the join loop (isClosed() is already true) and any awaitJoined
    }
}

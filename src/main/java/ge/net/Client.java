package ge.net;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The joining side. {@link #connect} returns at once; a background thread sends the login (a named
 * {@link GamePacket.Login}, or the bare nameless {@link Protocol#JOIN}), retransmitting since UDP is lossy, until the
 * server's ack arrives or the client is closed; closing a joined client sends a disconnect first (see {@link #close()}). Packets that are not connection-level go to the inbox for the game thread.
 */
public final class Client extends Endpoint {
    /** Pause between join requests while waiting for the ack. */
    static final long RETRANSMIT_MS = 100;
    /** Pause between disconnect sends while waiting for the ack. */
    static final long DISCONNECT_RETRANSMIT_MS = 250;
    /** Most disconnects sent by one {@link #close()}; with the pause above, close returns within ~2.5 s. */
    static final int DISCONNECT_MAX_SENDS = 10;

    private final InetSocketAddress server;
    /** What the join loop repeats until acked: a login packet, or the bare nameless join. */
    private final byte[] loginBytes;
    private final String name;
    private volatile boolean joined;
    /** Counted down on join or close: wakes the join loop and any awaitJoined. */
    private final CountDownLatch wake = new CountDownLatch(1);

    /** Held for the whole of {@link #close()}: a second closer waits for the goodbye instead of cutting it short. */
    private final Object closeMutex = new Object();
    /** Set just before the disconnect goes out; from then on a join ack is the server's answer to it. */
    private volatile boolean leaving;
    private final CountDownLatch leaveAcked = new CountDownLatch(1);

    /** Guards {@link #pongs}; {@link #pongArrived} wakes a waiting {@link #ping}. */
    private final ReentrantLock pongLock = new ReentrantLock();
    private final Condition pongArrived = pongLock.newCondition();
    private long pongs;
    /** Serialises pings, so each waiter only ever sees the pong for its own probe. */
    private final Object pingMutex = new Object();

    private Client(DatagramSocket socket, InetSocketAddress server, String name, byte[] loginBytes) {
        super(socket);
        this.server = server;
        this.name = name;
        this.loginBytes = loginBytes;
    }

    /** @throws IOException if the host name cannot be resolved or no local socket can be opened; never because nobody listens */
    public static Client connect(String host, int port) throws IOException {
        return open(host, port, null, Protocol.JOIN);
    }

    /** Named login with spawn position (0, 0). @throws IllegalArgumentException if the name is not a valid {@link GamePacket.Login} name */
    public static Client connect(String host, int port, String name) throws IOException {
        return connect(host, port, name, 0, 0);
    }

    /**
     * Named login: repeats {@link GamePacket.Login}(name, x, y) until acked. (x, y) is the spawn, in world pixels.
     * @throws IllegalArgumentException if the name is not valid (checked before any socket is opened)
     */
    public static Client connect(String host, int port, String name, int x, int y) throws IOException {
        return open(host, port, name, new GamePacket.Login(name, x, y).toBytes());
    }

    private static Client open(String host, int port, String name, byte[] loginBytes) throws IOException {
        InetSocketAddress addr = new InetSocketAddress(InetAddress.getByName(host), port);
        Client c = new Client(new DatagramSocket(), addr, name, loginBytes);
        c.startReceiving("client");
        c.startThread("client-join", c::joinLoop);
        return c;
    }

    /** The name this client logged in with, or null for a nameless join. */
    public String name() {
        return name;
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

    /**
     * Sends one "ping" and blocks up to the timeout for the server's "pong". Returns false on timeout, if the
     * client is (or gets) closed, or if the server has not acked our join; never throws. Safe from any thread
     * except that concurrent pings run one after another.
     */
    public boolean ping(long timeoutMillis) {
        synchronized (pingMutex) {
            long remaining = TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
            pongLock.lock();
            try {
                if (isClosed()) return false;
                long seen = pongs; // anything counted before now is a stale answer to an earlier ping
                try {
                    send(server, Protocol.PING);
                } catch (IOException e) {
                    return false; // closed under us
                }
                while (pongs == seen && !isClosed()) {
                    if (remaining <= 0) return false;
                    remaining = pongArrived.awaitNanos(remaining);
                }
                return pongs != seen;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } finally {
                pongLock.unlock();
            }
        }
    }

    /** Sends a game packet to the server (not retransmitted, not acked). Safe from any thread; silently dropped once closed. */
    public void sendToServer(GamePacket packet) {
        try {
            send(server, packet.toBytes());
        } catch (IOException e) {
            // closed under us: nothing to send to
        }
    }

    private void onPong() {
        pongLock.lock();
        try {
            pongs++;
            pongArrived.signalAll();
        } finally {
            pongLock.unlock();
        }
    }

    private void joinLoop() {
        try {
            while (!isClosed() && !isJoined()) {
                send(server, loginBytes);
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
            if (leaving) leaveAcked.countDown();
            return true;
        }
        if (Protocol.isPong(data)) {
            onPong();
            return true;
        }
        return false; // game packets go to the game thread
    }

    /**
     * If joined, first tells the server: sends {@link GamePacket.Disconnect}, resending every
     * {@value #DISCONNECT_RETRANSMIT_MS} ms until the ack arrives, at most {@value #DISCONNECT_MAX_SENDS} sends in all
     * (so it returns within about 2.5 s even if the server never answers). A client that was never acked has nothing to
     * leave. Then closes the socket and threads. Idempotent and safe from any thread, including a shutdown hook.
     */
    @Override
    public void close() {
        synchronized (closeMutex) {
            if (!isClosed()) sayGoodbye();
            super.close();
        }
    }

    private void sayGoodbye() {
        if (!joined) return;
        leaving = true;
        byte[] bye = new GamePacket.Disconnect().toBytes();
        try {
            for (int sent = 0; sent < DISCONNECT_MAX_SENDS; sent++) {
                send(server, bye);
                if (leaveAcked.await(DISCONNECT_RETRANSMIT_MS, TimeUnit.MILLISECONDS)) return;
            }
        } catch (IOException e) {
            // socket unusable: nothing more to send
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // give up waiting, but still close
        }
    }

    @Override
    protected void onClose() {
        wake.countDown(); // wake the join loop (isClosed() is already true) and any awaitJoined
        pongLock.lock(); // wake a blocked ping, which sees isClosed() and gives up
        try {
            pongArrived.signalAll();
        } finally {
            pongLock.unlock();
        }
    }
}

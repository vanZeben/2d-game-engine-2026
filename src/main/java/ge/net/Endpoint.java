package ge.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * What a server and a client have in common: one UDP socket, one receive thread, and an inbox.
 *
 * <p>Threading rule: the receive thread only reads datagrams and either handles them itself (connection-level
 * messages, via {@link #handle}) or puts them in the inbox. Game state is only touched by the game thread, which
 * calls {@link #poll}/{@link #drain} once per tick. Nothing else crosses the thread boundary.
 *
 * <p>Close: {@link #close()} closes the socket (which unblocks the blocked receive), then joins the threads with a
 * timeout. It is idempotent and safe from any thread.
 */
abstract class Endpoint implements AutoCloseable {
    /** Inbox bound: when the game thread falls behind, the newest packets are dropped (UDP may drop anyway). */
    static final int INBOX_CAPACITY = 1024;
    private static final long JOIN_TIMEOUT_MS = 1000;

    protected final DatagramSocket socket;
    private final BlockingQueue<Packet> inbox = new LinkedBlockingQueue<>(INBOX_CAPACITY);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final List<Thread> threads = new ArrayList<>();

    Endpoint(DatagramSocket socket) {
        this.socket = socket;
    }

    /** Starts the receive thread. Call once, at the end of the subclass's construction. */
    protected final void startReceiving(String name) {
        startThread(name + "-recv", this::receiveLoop);
    }

    /** Starts a daemon thread that {@link #close()} will join. */
    protected final void startThread(String name, Runnable body) {
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        synchronized (threads) {
            threads.add(t);
        }
        t.start();
    }

    private void receiveLoop() {
        byte[] buf = new byte[Protocol.MAX_PACKET];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        while (!closed.get()) {
            try {
                dp.setLength(buf.length);
                socket.receive(dp);
            } catch (SocketException e) {
                return; // socket closed (or broken): nothing more will arrive
            } catch (IOException e) {
                continue; // a transient receive error must not kill the thread
            }
            byte[] data = java.util.Arrays.copyOfRange(buf, dp.getOffset(), dp.getOffset() + dp.getLength());
            try {
                if (!handle(dp.getSocketAddress(), data)) inbox.offer(new Packet(dp.getSocketAddress(), data));
            } catch (RuntimeException e) {
                // a bad packet is dropped, never fatal
            }
        }
    }

    /**
     * Called on the receive thread for each datagram. Return true if it was a connection-level message handled
     * here; false to hand it to the game thread through the inbox. Must not touch game state.
     */
    protected abstract boolean handle(SocketAddress from, byte[] data);

    /** Next packet for the game thread, or null if none is waiting. Game thread only. */
    public final Packet poll() {
        return inbox.poll();
    }

    /** Hands every waiting packet to {@code sink}, oldest first. Game thread only. */
    public final void drain(Consumer<Packet> sink) {
        Packet p;
        while ((p = inbox.poll()) != null) sink.accept(p);
    }

    /** Sends one datagram. Safe from any thread. Fails with an IOException once closed. */
    protected final void send(SocketAddress to, byte[] data) throws IOException {
        socket.send(new DatagramPacket(data, data.length, to));
    }

    public final boolean isClosed() {
        return closed.get();
    }

    @Override
    public final void close() {
        if (!closed.compareAndSet(false, true)) return;
        socket.close(); // unblocks receive()
        onClose();
        List<Thread> copy;
        synchronized (threads) {
            copy = new ArrayList<>(threads);
        }
        for (Thread t : copy) {
            if (t == Thread.currentThread()) continue;
            try {
                t.join(JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Hook for subclasses to wake their own threads (the socket is already closed). */
    protected void onClose() {}
}

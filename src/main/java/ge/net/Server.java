package ge.net;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The hosting side. Binds a UDP socket, answers join requests with an ack and pings with a pong, and remembers who has joined.
 * A client that retransmits its request (UDP loses datagrams) is acked again but counted once.
 */
public final class Server extends Endpoint {
    /** Upper bound on remembered clients; join requests beyond it are ignored. */
    public static final int MAX_CLIENTS = 256;

    private final Set<SocketAddress> clients = ConcurrentHashMap.newKeySet();

    private Server(DatagramSocket socket) {
        super(socket);
    }

    /** @param port the UDP port to bind, or 0 for any free port (see {@link #port()}) */
    public static Server start(int port) throws IOException {
        Server s = new Server(new DatagramSocket(port));
        s.startReceiving("server");
        return s;
    }

    /** The port actually bound. */
    public int port() {
        return socket.getLocalPort();
    }

    /** How many distinct clients have joined. */
    public int clientCount() {
        return clients.size();
    }

    @Override
    protected boolean handle(SocketAddress from, byte[] data) {
        if (Protocol.isJoin(data)) {
            onJoin(from);
            return true;
        }
        if (Protocol.isPing(data)) {
            onPing(from);
            return true;
        }
        return false;
    }

    private void onJoin(SocketAddress from) {
        if (clients.contains(from) || clients.size() < MAX_CLIENTS) {
            clients.add(from);
            reply(from, Protocol.JOIN_ACK); // acked every time: the first ack may have been lost
        }
    }

    /** Only joined addresses are answered; a ping from anyone else is dropped and does not make them a client. */
    private void onPing(SocketAddress from) {
        if (clients.contains(from)) reply(from, Protocol.PONG);
    }

    private void reply(SocketAddress to, byte[] data) {
        try {
            send(to, data);
        } catch (IOException e) {
            // closed while replying; the client will retry if it still cares
        }
    }
}

package ge.net;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The hosting side. Binds a UDP socket, answers join requests with an ack, and remembers who has joined.
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
        if (!Protocol.isJoin(data)) return false;
        if (clients.contains(from) || clients.size() < MAX_CLIENTS) {
            clients.add(from);
            try {
                send(from, Protocol.JOIN_ACK); // acked every time: the first ack may have been lost
            } catch (IOException e) {
                // closed while replying; the client will retransmit if it still cares
            }
        }
        return true;
    }
}

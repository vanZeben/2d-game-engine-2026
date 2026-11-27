package ge.net;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The hosting side. Binds a UDP socket and keeps a registry of logged-in players. A login is either the named
 * {@link GamePacket.Login} or the bare {@link Protocol#JOIN} (nameless: the server picks a unique name); both are
 * acked with {@link Protocol#JOIN_ACK}, every time, since the first ack may have been lost. A client that repeats its
 * login is acked again but counted once. Pings are answered for any logged-in address.
 *
 * <p>Threading: the registry is only changed by the receive thread (login is handled in {@link #handle}), but other
 * threads read it ({@link #playerNames()}, {@link #clientCount()}), so every access goes through one lock and
 * readers get copies. Sends happen outside the lock.
 */
public final class Server extends Endpoint {
    /** Upper bound on remembered clients; logins beyond it are ignored. */
    public static final int MAX_CLIENTS = 256;

    /** Prefix of the names the server gives to nameless clients. */
    private static final String GENERATED_PREFIX = "guest";

    /** A logged-in address. {@code named} is false for nameless clients (generated name): nobody is told about them. */
    private record Entry(String name, boolean named) {}

    private final Object lock = new Object();
    /** Logged-in players by address, in login order. Guarded by {@link #lock}. */
    private final Map<SocketAddress, Entry> players = new LinkedHashMap<>();
    /** Source of generated names; guarded by {@link #lock}. */
    private int nextGuest = 1;

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

    /** How many distinct clients are logged in. */
    public int clientCount() {
        synchronized (lock) {
            return players.size();
        }
    }

    /** A snapshot of the logged-in players' names: as given, or the generated name of a nameless client. */
    public Set<String> playerNames() {
        synchronized (lock) {
            Set<String> names = new LinkedHashSet<>();
            for (Entry e : players.values()) names.add(e.name());
            return names;
        }
    }

    @Override
    protected boolean handle(SocketAddress from, byte[] data) {
        if (Protocol.isJoin(data)) {
            onLogin(from, null, 0, 0);
            return true;
        }
        if (Protocol.isPing(data)) {
            onPing(from);
            return true;
        }
        if (GamePacket.parse(data).orElse(null) instanceof GamePacket.Login login) {
            onLogin(from, login.name(), login.x(), login.y());
            return true;
        }
        return false;
    }

    /**
     * Registers the address (once), acks, and for a new named player tells every other registered address.
     * {@code name} is null for the nameless form. A repeated login changes nothing and is not announced again.
     * Two players may still share a chosen name until a later episode rejects duplicates; generated names never
     * collide with a name already held.
     */
    private void onLogin(SocketAddress from, String name, int x, int y) {
        List<SocketAddress> others = null;
        synchronized (lock) {
            if (!players.containsKey(from)) {
                if (players.size() >= MAX_CLIENTS) return;
                if (name == null) {
                    players.put(from, new Entry(generateName(), false));
                } else {
                    others = new ArrayList<>(players.keySet());
                    players.put(from, new Entry(name, true));
                }
            }
        }
        reply(from, Protocol.JOIN_ACK);
        if (others != null) {
            byte[] notice = new GamePacket.PlayerJoined(name, x, y).toBytes();
            for (SocketAddress other : others) reply(other, notice);
        }
    }

    private String generateName() {
        Set<String> taken = new LinkedHashSet<>();
        for (Entry e : players.values()) taken.add(e.name());
        String candidate;
        do {
            candidate = GENERATED_PREFIX + nextGuest++;
        } while (taken.contains(candidate));
        return candidate;
    }

    /** Only logged-in addresses are answered; a ping from anyone else is dropped and does not make them a client. */
    private void onPing(SocketAddress from) {
        boolean known;
        synchronized (lock) {
            known = players.containsKey(from);
        }
        if (known) reply(from, Protocol.PONG);
    }

    private void reply(SocketAddress to, byte[] data) {
        try {
            send(to, data);
        } catch (IOException e) {
            // closed while replying; the client will retry if it still cares
        }
    }
}

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
 * login is acked again but counted once. Pings are answered for any logged-in address. A {@link GamePacket.Disconnect} removes the player (see {@link #onDisconnect}).
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

    /**
     * A logged-in address. {@code named} is false for nameless clients (generated name): nobody is told about them.
     * (x, y, dir, frame) is the player's latest known state: the spawn position from the login (0, 0 for a nameless
     * client, never sent), then whatever its newest accepted {@link GamePacket.Move} said; {@code seq} is that move's
     * sequence number, -1 until one arrives (a fresh login makes a fresh entry, so it resets).
     */
    private record Entry(String name, boolean named, int x, int y, int dir, int frame, int seq) {}

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
        GamePacket packet = GamePacket.parse(data).orElse(null);
        if (packet instanceof GamePacket.Login login) {
            onLogin(from, login.name(), login.x(), login.y());
            return true;
        }
        if (packet instanceof GamePacket.Disconnect) {
            onDisconnect(from);
            return true;
        }
        if (packet instanceof GamePacket.Move move) {
            onMove(from, move);
            return true;
        }
        return false;
    }

    /**
     * Removes the address's player and tells every other registered address with a {@link GamePacket.PlayerLeft}
     * (nameless clients were never announced, so their leaving is not either). The sender is acked every time, also
     * when it is not (or no longer) registered: a repeated disconnect means the first ack was lost, and changes nothing.
     */
    private void onDisconnect(SocketAddress from) {
        List<SocketAddress> others = null;
        byte[] notice = null;
        synchronized (lock) {
            Entry gone = players.remove(from);
            if (gone != null && gone.named()) {
                others = new ArrayList<>(players.keySet());
                notice = new GamePacket.PlayerLeft(gone.name()).toBytes();
            }
        }
        reply(from, Protocol.JOIN_ACK);
        if (others != null) {
            for (SocketAddress other : others) reply(other, notice);
        }
    }

    /**
     * Registers the address (once), acks, and for a new named player tells every other registered address about
     * them. A named player is also told about every other named player already logged in (one
     * {@link GamePacket.PlayerJoined} each, at that player's current position: the login position until it moves). {@code name} is null for the nameless
     * form, which is never announced to anyone and is told nothing. A repeated login registers nobody and announces
     * nothing to the others, but the roster is sent to the repeater again: its first copy may have been lost, and
     * the client ignores names it already shows. A login whose name is held (exactly, also a generated one) by
     * another address is answered with {@link GamePacket.LoginRejected} (name-taken) instead of the ack: nothing
     * is registered and nobody is told. The name is free again once its holder is gone. Generated names never
     * collide with a name already held.
     */
    private void onLogin(SocketAddress from, String name, int x, int y) {
        List<SocketAddress> others = null;
        List<byte[]> roster = null;
        boolean taken = false;
        synchronized (lock) {
            Entry self = players.get(from);
            if (self == null) {
                if (players.size() >= MAX_CLIENTS) return;
                if (name != null && isNameHeld(name)) {
                    taken = true; // nothing is registered and nobody is told
                } else if (name == null) {
                    players.put(from, new Entry(generateName(), false, 0, 0, 0, 0, -1));
                } else {
                    others = new ArrayList<>(players.keySet());
                    self = new Entry(name, true, x, y, 0, 0, -1);
                    players.put(from, self);
                }
            }
            if (self != null && self.named()) {
                roster = new ArrayList<>();
                for (Map.Entry<SocketAddress, Entry> e : players.entrySet()) {
                    Entry other = e.getValue();
                    if (!e.getKey().equals(from) && other.named()) {
                        roster.add(new GamePacket.PlayerJoined(other.name(), other.x(), other.y()).toBytes());
                    }
                }
            }
        }
        if (taken) {
            reply(from, new GamePacket.LoginRejected(GamePacket.REASON_NAME_TAKEN).toBytes());
            return;
        }
        reply(from, Protocol.JOIN_ACK);
        if (roster != null) {
            for (byte[] notice : roster) reply(from, notice);
        }
        if (others != null) {
            byte[] notice = new GamePacket.PlayerJoined(name, x, y).toBytes();
            for (SocketAddress other : others) reply(other, notice);
        }
    }

    /**
     * A move from a registered, named address with a seq higher than the one stored for that player replaces the
     * player's stored state and is relayed, under the registered name and with the sender's seq, to every other
     * registered address (never back to the sender). Anything else (unknown address, nameless client, stale or
     * repeated seq) is dropped without a word: moves are not acked.
     */
    private void onMove(SocketAddress from, GamePacket.Move move) {
        List<SocketAddress> others;
        byte[] relay;
        synchronized (lock) {
            Entry e = players.get(from);
            if (e == null || !e.named() || move.seq() <= e.seq()) return;
            players.put(from, new Entry(e.name(), true, move.x(), move.y(), move.dir(), move.walkFrame(), move.seq()));
            others = new ArrayList<>(players.keySet());
            others.remove(from);
            relay = new GamePacket.Move(e.name(), move.x(), move.y(), move.dir(), move.walkFrame(), move.seq()).toBytes();
        }
        for (SocketAddress other : others) reply(other, relay);
    }

    /** Whether some logged-in address holds exactly this name (case-sensitive). Caller holds {@link #lock}. */
    private boolean isNameHeld(String name) {
        for (Entry e : players.values()) {
            if (e.name().equals(name)) return true;
        }
        return false;
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

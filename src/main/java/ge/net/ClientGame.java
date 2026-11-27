package ge.net;

import ge.Scene;
import ge.Screen;
import ge.World;
import ge.entity.Player;
import ge.input.InputHandler;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * A client-side game: a {@link World} of the local player plus a {@link RemotePlayer} for each other player the
 * server announces. Remote players move as relayed {@link GamePacket.Move}s say; the local player's own state goes to
 * the server whenever it changes and at least once a second.
 *
 * <p>Threading: the {@link Client}'s receive thread only fills the inbox; {@link #tick} (the game thread) is the one
 * place that drains it and changes the world, so the world has a single owner and needs no locks. {@link #tick},
 * {@link #render} and {@link #world} belong to that thread; {@link #close} may be called from any.
 */
public final class ClientGame implements AutoCloseable {
    private final World world;
    private final Client client;
    private final String name;
    private final InputHandler input = new InputHandler();
    /** The state last sent to the server (game thread only); {@code lastSent} is null before the first send. */
    private GamePacket.Move lastSent;
    private long lastSentNanos;
    private int nextSeq;
    /** The remote players already in the world, by name (game thread only). */
    private final Map<String, RemotePlayer> remotes = new HashMap<>();

    private ClientGame(World world, Client client, String name) {
        this.world = world;
        this.client = client;
        this.name = name;
    }

    /**
     * Builds the world of a {@code game:} scene and logs in with its player spawn position; returns at once, the
     * login completes in the background ({@code client().awaitJoined}).
     * @throws IllegalArgumentException if the scene is not a game scene or the name is invalid
     */
    public static ClientGame connect(String sceneSpec, String host, int port, String name) throws IOException {
        World world = Scene.parse(sceneSpec).newWorld();
        if (world == null) throw new IllegalArgumentException("not a game: scene: " + sceneSpec);
        Client client = Client.connect(host, port, name, world.player().x(), world.player().y());
        return new ClientGame(world, client, name);
    }

    /**
     * Joins the world of an already running game (the jar's {@code --client}): the caller owns the world and its
     * tick loop and calls {@link #drain} on the game thread once per tick. The client's login is the caller's business.
     */
    public static ClientGame attach(World world, Client client) {
        return new ClientGame(world, client, client.name());
    }

    public World world() {
        return world;
    }

    public Client client() {
        return client;
    }

    /** The local player's input, read by {@link #tick}: nothing is held until a caller presses a key. */
    public InputHandler input() {
        return input;
    }

    /**
     * One step: apply every packet that arrived since the last tick, tick the world with {@link #input()}, then send
     * the local player's new state if it changed.
     */
    public void tick() {
        drain();
        world.tick(input);
        sendState();
    }

    /**
     * Applies every packet that arrived since the last call to the world, then sends the local player's state if it
     * changed or a second has passed (the jar client's game ticks its own world and only calls this). Game thread only.
     */
    public void drain() {
        client.drain(this::apply);
        sendState();
    }

    /** Longest silence between two state packets: a lost move is repaired within this, and an idle player stays alive. */
    static final long HEARTBEAT_NANOS = 1_000_000_000L;

    /**
     * Sends the local player's full state as a {@link GamePacket.Move} with the next seq, if the login has been acked
     * (nothing before that) and the state differs from the last one sent or the last send was a heartbeat ago.
     */
    private void sendState() {
        if (name == null || !client.isJoined()) return;
        Player p = world.player();
        long now = System.nanoTime();
        boolean changed = lastSent == null || lastSent.x() != p.x() || lastSent.y() != p.y()
                || lastSent.dir() != p.dir().ordinal() || lastSent.walkFrame() != p.walkFrame();
        if (!changed && now - lastSentNanos < HEARTBEAT_NANOS) return;
        lastSent = new GamePacket.Move(name, p.x(), p.y(), p.dir().ordinal(), p.walkFrame(), nextSeq++);
        lastSentNanos = now;
        client.sendToServer(lastSent);
    }

    private void apply(Packet p) {
        GamePacket.parse(p.data()).ifPresent(g -> {
            if (g instanceof GamePacket.PlayerJoined j && !j.name().equals(name)) {
                RemotePlayer known = remotes.get(j.name());
                if (known != null) {
                    known.reset(j.x(), j.y()); // announced again: a new session whose seq restarted (we missed its PlayerLeft)
                } else {
                    RemotePlayer r = new RemotePlayer(j.name(), j.x(), j.y());
                    remotes.put(j.name(), r);
                    world.add(r);
                }
            } else if (g instanceof GamePacket.PlayerLeft l) {
                RemotePlayer r = remotes.remove(l.name()); // forgotten, so a later PlayerJoined adds it again
                if (r != null) world.remove(r);
            } else if (g instanceof GamePacket.Move m) {
                RemotePlayer r = remotes.get(m.name()); // unknown names and our own name are not in the map
                if (r != null) r.applyMove(m.x(), m.y(), m.dir(), m.walkFrame(), m.seq());
            }
        });
    }

    /** Clears the screen and draws the world. */
    public void render(Screen screen) {
        screen.clear(0x000000);
        world.render(screen, Scene.sheet());
    }

    @Override
    public void close() {
        client.close();
    }
}

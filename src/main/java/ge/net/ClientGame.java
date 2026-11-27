package ge.net;

import ge.Scene;
import ge.Screen;
import ge.World;
import ge.input.InputHandler;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * A client-side game: a {@link World} of the local player plus a {@link RemotePlayer} for each other player the
 * server announces.
 *
 * <p>Threading: the {@link Client}'s receive thread only fills the inbox; {@link #tick} (the game thread) is the one
 * place that drains it and changes the world, so the world has a single owner and needs no locks. {@link #tick},
 * {@link #render} and {@link #world} belong to that thread; {@link #close} may be called from any.
 */
public final class ClientGame implements AutoCloseable {
    private final World world;
    private final Client client;
    private final String name;
    private final InputHandler noInput = new InputHandler();
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

    /** One step: apply every packet that arrived since the last tick, then tick the world with no input. */
    public void tick() {
        drain();
        world.tick(noInput);
    }

    /** Applies every packet that arrived since the last call to the world. Game thread only. */
    public void drain() {
        client.drain(this::apply);
    }

    private void apply(Packet p) {
        GamePacket.parse(p.data()).ifPresent(g -> {
            if (g instanceof GamePacket.PlayerJoined j && !j.name().equals(name) && !remotes.containsKey(j.name())) {
                RemotePlayer r = new RemotePlayer(j.name(), j.x(), j.y());
                remotes.put(j.name(), r);
                world.add(r);
            } else if (g instanceof GamePacket.PlayerLeft l) {
                RemotePlayer r = remotes.remove(l.name()); // forgotten, so a later PlayerJoined adds it again
                if (r != null) world.remove(r);
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

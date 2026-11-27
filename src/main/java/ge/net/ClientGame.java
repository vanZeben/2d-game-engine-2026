package ge.net;

import ge.Scene;
import ge.Screen;
import ge.World;
import ge.input.InputHandler;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

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
    /** Names of the remote players already in the world (game thread only). */
    private final Set<String> remoteNames = new HashSet<>();

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

    public World world() {
        return world;
    }

    public Client client() {
        return client;
    }

    /** One step: apply every packet that arrived since the last tick, then tick the world with no input. */
    public void tick() {
        client.drain(this::apply);
        world.tick(noInput);
    }

    private void apply(Packet p) {
        GamePacket.parse(p.data()).ifPresent(g -> {
            if (g instanceof GamePacket.PlayerJoined j && !j.name().equals(name) && remoteNames.add(j.name())) {
                world.add(new RemotePlayer(j.name(), j.x(), j.y()));
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

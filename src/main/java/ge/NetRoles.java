package ge;

import ge.net.Client;
import ge.net.ClientGame;
import ge.net.Server;
import java.io.IOException;
import java.io.PrintStream;

/** The server and client roles of the game, run from {@link Main}: what each prints and when it stops. */
final class NetRoles {
    /** How long a headless client waits for the server's ack before giving up. */
    static final long JOIN_TIMEOUT_MS = 3000;
    /** Exit status of a headless client that was never acked. */
    static final int EXIT_JOIN_FAILED = 3;

    private NetRoles() {}

    /** Parsed {@code HOST:PORT}, kept as the user wrote it for messages. */
    record Address(String text, String host, int port) {
        static Address parse(String text) {
            int colon = text.lastIndexOf(':');
            if (colon <= 0 || colon == text.length() - 1) {
                throw new IllegalArgumentException("--connect needs HOST:PORT, got: " + text);
            }
            int port;
            try {
                port = Integer.parseInt(text.substring(colon + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--connect needs HOST:PORT, got: " + text);
            }
            if (port < 1 || port > 65535) throw new IllegalArgumentException("port out of range: " + port);
            return new Address(text, text.substring(0, colon), port);
        }
    }

    /**
     * Headless server: binds, prints {@code server listening port=P}, then ticks the game (draining the network
     * inbox each tick) until killed, or for exactly {@code ticks} ticks as fast as possible when {@code ticks >= 0}.
     * @return the process exit status
     */
    static int serveHeadless(Game game, int port, long ticks, PrintStream out) {
        return serveHeadless(game, port, ticks, false, out);
    }

    /** As above; with {@code realtime} a bounded run is paced at 60 ticks/s too. */
    static int serveHeadless(Game game, int port, long ticks, boolean realtime, PrintStream out) {
        try (Server server = Server.start(port)) {
            out.println("server listening port=" + server.port());
            out.flush();
            long next = System.nanoTime();
            long step = 1_000_000_000L / Game.TICKS_PER_SECOND;
            for (long i = 0; ticks < 0 || i < ticks; i++) {
                server.drain(p -> { /* game packets arrive in later episodes */ });
                game.tick();
                if (ticks < 0 || realtime) { // real time when running until killed, or when asked
                    next += step;
                    long wait = next - System.nanoTime();
                    if (wait > 0) Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
                    else next = System.nanoTime();
                }
            }
            return 0;
        } catch (IOException e) {
            System.err.println("cannot start server: " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
    }

    /** Windowed server: starts listening in the background; closed when the JVM exits. */
    static void serveWindowed(int port, PrintStream out) throws IOException {
        Server server = Server.start(port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "server-close"));
        out.println("server listening port=" + server.port());
        out.flush();
    }

    /**
     * Headless client: connects (logging in as {@code name} at 0,0, or nameless if null) and waits for the ack.
     * @return the connected client (caller closes it, which sends the disconnect; a shutdown hook does too), or null after printing {@code client join failed ...}
     */
    static Client joinHeadless(Address to, String name, PrintStream out) throws IOException {
        return joinHeadless(to, name, null, out);
    }

    /**
     * As above, and when {@code game} plays a world: the login carries the player's spawn position, and on success
     * the game drains the network into its world at the start of every tick (remote players appear on screen).
     */
    static Client joinHeadless(Address to, String name, Game game, PrintStream out) throws IOException {
        Client c = open(to, name, game);
        if (c.awaitJoined(JOIN_TIMEOUT_MS)) {
            out.println("client joined server=" + to.text());
            out.flush();
            // SIGTERM (or any JVM exit) still tells the server we are leaving; close is idempotent and bounded (~2.5 s)
            Runtime.getRuntime().addShutdownHook(new Thread(c::close, "client-close"));
            return c;
        }
        c.close();
        out.println("client join failed server=" + to.text());
        out.flush();
        return null;
    }

    /** Windowed client: joins in the background while the window runs; closed when the JVM exits. */
    static void joinWindowed(Address to, String name, Game game) throws IOException {
        Client c = open(to, name, game);
        Runtime.getRuntime().addShutdownHook(new Thread(c::close, "client-close"));
    }

    /** Connects; with a world the named login is at the player's spawn and the game drains the inbox each tick. */
    private static Client open(Address to, String name, Game game) throws IOException {
        World world = game == null ? null : game.world();
        Client c;
        if (name == null) c = Client.connect(to.host(), to.port());
        else if (world == null) c = Client.connect(to.host(), to.port(), name);
        else c = Client.connect(to.host(), to.port(), name, world.player().x(), world.player().y());
        if (world != null) game.beforeTick(ClientGame.attach(world, c)::drain);
        return c;
    }
}

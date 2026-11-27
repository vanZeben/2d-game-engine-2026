package ge;

import ge.net.Client;
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
        try (Server server = Server.start(port)) {
            out.println("server listening port=" + server.port());
            out.flush();
            long next = System.nanoTime();
            long step = 1_000_000_000L / Game.TICKS_PER_SECOND;
            for (long i = 0; ticks < 0 || i < ticks; i++) {
                server.drain(p -> { /* game packets arrive in later episodes */ });
                game.tick();
                if (ticks < 0) { // real time only when running until killed
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
     * Headless client: connects and waits for the ack.
     * @return the connected client (caller closes it), or null after printing {@code client join failed ...}
     */
    static Client joinHeadless(Address to, PrintStream out) throws IOException {
        Client c = Client.connect(to.host(), to.port());
        if (c.awaitJoined(JOIN_TIMEOUT_MS)) {
            out.println("client joined server=" + to.text());
            out.flush();
            return c;
        }
        c.close();
        out.println("client join failed server=" + to.text());
        out.flush();
        return null;
    }

    /** Windowed client: joins in the background while the window runs; closed when the JVM exits. */
    static void joinWindowed(Address to) throws IOException {
        Client c = Client.connect(to.host(), to.port());
        Runtime.getRuntime().addShutdownHook(new Thread(c::close, "client-close"));
    }
}

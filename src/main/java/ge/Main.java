package ge;

import ge.input.InputScript;
import ge.net.Client;
import ge.net.GamePacket;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Entry point.
 *   java -jar game.jar                                     opens the window
 *   java -jar game.jar --headless --ticks N [--seed S] [--script FILE] [--dump-dir DIR]
 *                      [--scene SPEC] [--trace-input] [--trace-player [--trace-swim]]
 *   --script FILE    scripted key events (docs/script-format.md); a bad file exits 2 before running
 *   --trace-input    print one "tick=T up=U down=D left=L right=R" line per tick
 *   --trace-player   with a game: scene, print one "tick=T x=X y=Y camx=CX camy=CY dir=D frame=F" line per tick
 *   --trace-swim     with --trace-player (else exit 2): append " swim=S" to each line (S = 1 while the player stands in water, else 0)
 *   --server [--port P]            host over UDP (P defaults to 0 = any free port); headless it prints
 *                    "server listening port=P" and runs until killed, or for exactly --ticks N ticks if given
 *   --client --connect HOST:PORT [--name NAME]   join a server (NAME: 1..16 of A-Za-z0-9_, else exit 2; without it the
 *                    join is nameless and the server picks a name; a named client logs in at position 0,0); headless it prints "client joined server=HOST:PORT" (then plays
 *                    --ticks N) or, with no ack within 3 s, "client join failed server=HOST:PORT" and exits 3
 *                    (--server and --client are exclusive; --client needs --connect)
 *   --scene game:...  a game scene may end with :stone=TX,TY;... and/or :water=TX,TY;... (each at most once, either
 *                    order): those cells become stone (solid) or water (animated; the player swims in it)
 * Without --scene the window plays a generated world (game:gen).
 */
public class Main {
    /** What the window plays when no --scene is given: a 128x128-tile world, the player in the middle. */
    private static final String DEFAULT_WINDOW_SCENE = "game:gen:128,128," + Game.DEFAULT_SEED + "@512,512";

    public static void main(String[] args) {
        boolean headless = false;
        long ticks = 600;
        Path dumpDir = null;
        Scene scene = null;
        long seed = Game.DEFAULT_SEED;
        Path scriptFile = null;
        boolean traceInput = false;
        boolean tracePlayer = false;
        boolean traceSwim = false;
        boolean serverRole = false;
        boolean clientRole = false;
        boolean ticksGiven = false;
        Integer port = null;
        NetRoles.Address connect = null;
        String playerName = null;
        InputScript script = InputScript.empty();
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--headless" -> headless = true;
                    case "--ticks" -> {
                        ticks = Long.parseLong(value(args, ++i));
                        ticksGiven = true;
                    }
                    case "--dump-dir" -> dumpDir = Paths.get(value(args, ++i));
                    case "--scene" -> scene = Scene.parse(value(args, ++i));
                    case "--seed" -> seed = Long.parseLong(value(args, ++i));
                    case "--script" -> scriptFile = Paths.get(value(args, ++i));
                    case "--trace-input" -> traceInput = true;
                    case "--trace-player" -> tracePlayer = true;
                    case "--trace-swim" -> traceSwim = true;
                    case "--server" -> serverRole = true;
                    case "--client" -> clientRole = true;
                    case "--port" -> port = Integer.parseInt(value(args, ++i));
                    case "--connect" -> connect = NetRoles.Address.parse(value(args, ++i));
                    case "--name" -> playerName = value(args, ++i);
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
            if (traceSwim && !tracePlayer) throw new IllegalArgumentException("--trace-swim needs --trace-player");
            if (serverRole && clientRole) throw new IllegalArgumentException("--server and --client are exclusive");
            if (clientRole && connect == null) throw new IllegalArgumentException("--client needs --connect HOST:PORT");
            if (connect != null && !clientRole) throw new IllegalArgumentException("--connect needs --client");
            if (playerName != null && !clientRole) throw new IllegalArgumentException("--name needs --client");
            if (playerName != null && !GamePacket.isValidName(playerName)) {
                throw new IllegalArgumentException("--name must be 1 to " + GamePacket.MAX_NAME + " characters of A-Z a-z 0-9 _, got: " + playerName);
            }
            if (port != null && !serverRole) throw new IllegalArgumentException("--port needs --server");
            if (port != null && (port < 0 || port > 65535)) throw new IllegalArgumentException("port out of range: " + port);
            if (ticks < 0) throw new IllegalArgumentException("--ticks must be >= 0");
            if (!headless && (scriptFile != null || traceInput || tracePlayer)) {
                throw new IllegalArgumentException("--script, --trace-input and --trace-player need --headless");
            }
            // Whole script is checked before anything runs, even lines beyond --ticks.
            if (scriptFile != null) script = InputScript.load(scriptFile);
            if (!headless && scene == null) scene = Scene.parse(DEFAULT_WINDOW_SCENE);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("usage: [--headless --ticks N [--seed S] [--script FILE] [--dump-dir DIR] [--scene SPEC] [--trace-input] [--trace-player [--trace-swim]]] [--server [--port P] | --client --connect HOST:PORT [--name NAME]]");
            System.exit(2);
            return;
        }

        Game game = new Game(scene, seed);
        if (tracePlayer && game.world() == null) {
            System.err.println("--trace-player needs a game: scene (--scene game:gen:W,H,SEED@X,Y or game:grass:W,H@X,Y, optionally with :stone=/:water= cell lists)");
            System.exit(2);
            return;
        }
        if (headless) {
            System.setProperty("java.awt.headless", "true");
            if (serverRole) {
                // A server runs until killed unless --ticks is given (the default of 600 is for single-player).
                System.exit(NetRoles.serveHeadless(game, port == null ? 0 : port, ticksGiven ? ticks : -1, System.out));
                return;
            }
            Client client = null;
            try {
                if (clientRole) {
                    client = NetRoles.joinHeadless(connect, playerName, System.out);
                    if (client == null) {
                        System.exit(NetRoles.EXIT_JOIN_FAILED);
                        return;
                    }
                }
                HeadlessRunner.Result r = HeadlessRunner.run(game, ticks, dumpDir, script, traceInput ? System.out : null,
                        tracePlayer ? System.out : null, traceSwim);
                System.out.println("ticks=" + r.ticks() + " frames=" + r.frames());
            } catch (IOException e) {
                System.err.println("cannot join: " + e.getMessage());
                System.exit(1);
                return;
            } finally {
                if (client != null) client.close();
            }
        } else {
            if (GraphicsEnvironment.isHeadless()) {
                System.err.println("No display available: cannot open the window. "
                        + "Use --headless --ticks N [--dump-dir DIR] to run without one.");
                System.exit(1);
                return;
            }
            try {
                if (serverRole) NetRoles.serveWindowed(port == null ? 0 : port, System.out);
                if (clientRole) NetRoles.joinWindowed(connect, playerName);
            } catch (IOException e) {
                System.err.println("network error: " + e.getMessage());
                System.exit(1);
                return;
            }
            GameWindow.open(game);
        }
    }

    private static String value(String[] args, int i) {
        if (i >= args.length) throw new IllegalArgumentException("missing value for " + args[i - 1]);
        return args[i];
    }
}

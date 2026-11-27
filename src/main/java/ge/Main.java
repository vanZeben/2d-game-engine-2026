package ge;

import ge.input.InputScript;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Entry point.
 *   java -jar game.jar                                     opens the window
 *   java -jar game.jar --headless --ticks N [--seed S] [--script FILE] [--dump-dir DIR]
 *                      [--scene SPEC] [--trace-input] [--trace-player]
 *   --script FILE    scripted key events (docs/script-format.md); a bad file exits 2 before running
 *   --trace-input    print one "tick=T up=U down=D left=L right=R" line per tick
 *   --trace-player   with a game: scene, print one "tick=T x=X y=Y camx=CX camy=CY" line per tick
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
        InputScript script = InputScript.empty();
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--headless" -> headless = true;
                    case "--ticks" -> ticks = Long.parseLong(value(args, ++i));
                    case "--dump-dir" -> dumpDir = Paths.get(value(args, ++i));
                    case "--scene" -> scene = Scene.parse(value(args, ++i));
                    case "--seed" -> seed = Long.parseLong(value(args, ++i));
                    case "--script" -> scriptFile = Paths.get(value(args, ++i));
                    case "--trace-input" -> traceInput = true;
                    case "--trace-player" -> tracePlayer = true;
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
            if (ticks < 0) throw new IllegalArgumentException("--ticks must be >= 0");
            if (!headless && (scriptFile != null || traceInput || tracePlayer)) {
                throw new IllegalArgumentException("--script, --trace-input and --trace-player need --headless");
            }
            // Whole script is checked before anything runs, even lines beyond --ticks.
            if (scriptFile != null) script = InputScript.load(scriptFile);
            if (!headless && scene == null) scene = Scene.parse(DEFAULT_WINDOW_SCENE);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("usage: [--headless --ticks N [--seed S] [--script FILE] [--dump-dir DIR] [--scene SPEC] [--trace-input] [--trace-player]]");
            System.exit(2);
            return;
        }

        Game game = new Game(scene, seed);
        if (tracePlayer && game.world() == null) {
            System.err.println("--trace-player needs a game: scene (--scene game:gen:W,H,SEED@X,Y or game:grass:W,H@X,Y)");
            System.exit(2);
            return;
        }
        if (headless) {
            System.setProperty("java.awt.headless", "true");
            HeadlessRunner.Result r = HeadlessRunner.run(game, ticks, dumpDir, script, traceInput ? System.out : null,
                    tracePlayer ? System.out : null);
            System.out.println("ticks=" + r.ticks() + " frames=" + r.frames());
        } else {
            if (GraphicsEnvironment.isHeadless()) {
                System.err.println("No display available: cannot open the window. "
                        + "Use --headless --ticks N [--dump-dir DIR] to run without one.");
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

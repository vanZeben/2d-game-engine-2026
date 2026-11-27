package ge;

import ge.input.InputScript;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Entry point.
 *   java -jar game.jar                                     opens the window
 *   java -jar game.jar --headless --ticks N [--seed S] [--script FILE] [--dump-dir DIR]
 *                      [--scene tile:TX,TY@PX,PY] [--trace-input]
 *   --script FILE    scripted key events (docs/script-format.md); a bad file exits 2 before running
 *   --trace-input    print one "tick=T up=U down=D left=L right=R" line per tick
 */
public class Main {
    public static void main(String[] args) {
        boolean headless = false;
        long ticks = 600;
        Path dumpDir = null;
        Scene scene = null;
        long seed = Game.DEFAULT_SEED;
        Path scriptFile = null;
        boolean traceInput = false;
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
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
            if (ticks < 0) throw new IllegalArgumentException("--ticks must be >= 0");
            if (!headless && (scriptFile != null || traceInput)) {
                throw new IllegalArgumentException("--script and --trace-input need --headless");
            }
            // Whole script is checked before anything runs, even lines beyond --ticks.
            if (scriptFile != null) script = InputScript.load(scriptFile);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("usage: [--headless --ticks N [--seed S] [--script FILE] [--dump-dir DIR] [--scene tile:TX,TY@PX,PY] [--trace-input]]");
            System.exit(2);
            return;
        }

        Game game = new Game(scene, seed);
        if (headless) {
            System.setProperty("java.awt.headless", "true");
            HeadlessRunner.Result r = HeadlessRunner.run(game, ticks, dumpDir, script, traceInput ? System.out : null);
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

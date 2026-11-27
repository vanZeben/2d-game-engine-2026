package ge;

import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Entry point.
 *   java -jar game.jar                                     opens the window
 *   java -jar game.jar --headless --ticks N [--seed S] [--script FILE] [--dump-dir DIR]
 *                      [--scene tile:TX,TY@PX,PY]
 */
public class Main {
    public static void main(String[] args) {
        boolean headless = false;
        long ticks = 600;
        Path dumpDir = null;
        Scene scene = null;
        // --seed and --script are accepted now and used by later episodes.
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--headless" -> headless = true;
                    case "--ticks" -> ticks = Long.parseLong(value(args, ++i));
                    case "--dump-dir" -> dumpDir = Paths.get(value(args, ++i));
                    case "--scene" -> scene = Scene.parse(value(args, ++i));
                    case "--seed" -> Long.parseLong(value(args, ++i));
                    case "--script" -> value(args, ++i);
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
            if (ticks < 0) throw new IllegalArgumentException("--ticks must be >= 0");
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("usage: [--headless --ticks N [--seed S] [--script FILE] [--dump-dir DIR] [--scene tile:TX,TY@PX,PY]]");
            System.exit(2);
            return;
        }

        Game game = new Game(scene);
        if (headless) {
            System.setProperty("java.awt.headless", "true");
            HeadlessRunner.Result r = HeadlessRunner.run(game, ticks, dumpDir);
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

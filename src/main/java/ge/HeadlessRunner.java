package ge;

import ge.input.InputScript;
import java.io.PrintStream;
import java.nio.file.Path;

/** Runs the game with no display: exactly N ticks as fast as possible, one frame rendered per tick. */
public class HeadlessRunner {
    public record Result(long ticks, int frames) {}

    public static Result run(Game game, long ticks, Path dumpDir) {
        return run(game, ticks, dumpDir, InputScript.empty(), null);
    }

    /**
     * @param script scripted key events, applied through {@code game.input()} immediately before each tick's update
     * @param trace  if non-null, receives one {@code tick=T up=U down=D left=L right=R} line after each tick's update
     */
    public static Result run(Game game, long ticks, Path dumpDir, InputScript script, PrintStream trace) {
        Screen screen = new Screen(Game.WIDTH, Game.HEIGHT);
        FrameDumper dumper = dumpDir == null ? null : new FrameDumper(dumpDir);
        InputScript.Player player = script.player(game.input());
        int frames = 0;
        for (long i = 0; i < ticks; i++) {
            player.advanceTo(game.tickCount() + 1); // ticks are numbered from 1
            game.tick();
            if (trace != null) trace.println(traceLine(game));
            game.render(screen);
            frames++;
            if (dumper != null) dumper.dump(screen);
        }
        return new Result(game.tickCount(), frames);
    }

    static String traceLine(Game game) {
        var in = game.input();
        return "tick=" + game.tickCount() + " up=" + (in.up() ? 1 : 0) + " down=" + (in.down() ? 1 : 0)
                + " left=" + (in.left() ? 1 : 0) + " right=" + (in.right() ? 1 : 0);
    }
}

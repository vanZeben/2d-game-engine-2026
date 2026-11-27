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
        return run(game, ticks, dumpDir, script, trace, null);
    }

    /**
     * @param tracePlayer if non-null (needs a game world), receives one {@code tick=T x=X y=Y camx=CX camy=CY} line
     *                    after each tick's update, after the {@code trace} line of that tick
     */
    public static Result run(Game game, long ticks, Path dumpDir, InputScript script, PrintStream trace,
            PrintStream tracePlayer) {
        Screen screen = new Screen(Game.WIDTH, Game.HEIGHT);
        FrameDumper dumper = dumpDir == null ? null : new FrameDumper(dumpDir);
        InputScript.Player player = script.player(game.input());
        int frames = 0;
        for (long i = 0; i < ticks; i++) {
            player.advanceTo(game.tickCount() + 1); // ticks are numbered from 1
            game.tick();
            if (trace != null) trace.println(traceLine(game));
            if (tracePlayer != null) tracePlayer.println(playerLine(game));
            game.render(screen);
            frames++;
            if (dumper != null) dumper.dump(screen);
        }
        return new Result(game.tickCount(), frames);
    }

    static String playerLine(Game game) {
        World w = game.world();
        return "tick=" + game.tickCount() + " x=" + w.player().x() + " y=" + w.player().y()
                + " camx=" + w.cameraX() + " camy=" + w.cameraY();
    }

    static String traceLine(Game game) {
        var in = game.input();
        return "tick=" + game.tickCount() + " up=" + (in.up() ? 1 : 0) + " down=" + (in.down() ? 1 : 0)
                + " left=" + (in.left() ? 1 : 0) + " right=" + (in.right() ? 1 : 0);
    }
}

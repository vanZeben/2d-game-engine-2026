package ge;

import java.nio.file.Path;

/** Runs the game with no display: exactly N ticks as fast as possible, one frame rendered per tick. */
public class HeadlessRunner {
    public record Result(long ticks, int frames) {}

    public static Result run(Game game, long ticks, Path dumpDir) {
        Screen screen = new Screen(Game.WIDTH, Game.HEIGHT);
        FrameDumper dumper = dumpDir == null ? null : new FrameDumper(dumpDir);
        int frames = 0;
        for (long i = 0; i < ticks; i++) {
            game.tick();
            game.render(screen);
            frames++;
            if (dumper != null) dumper.dump(screen);
        }
        return new Result(game.tickCount(), frames);
    }
}

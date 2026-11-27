package ge;

/**
 * Fixed-timestep accumulator: the display-free seam of the game loop.
 * Time is accumulated exactly (nanoseconds x ticks-per-second against 1e9) so no drift builds up.
 */
public class FixedStep {
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final int ticksPerSecond;
    private long accumulated; // elapsed nanos * ticksPerSecond

    public FixedStep() {
        this(Game.TICKS_PER_SECOND);
    }

    public FixedStep(int ticksPerSecond) {
        if (ticksPerSecond <= 0) throw new IllegalArgumentException("ticksPerSecond must be positive");
        this.ticksPerSecond = ticksPerSecond;
    }

    /** Adds elapsed time; returns the whole ticks now due and consumes them. The remainder carries over. */
    public int advance(long elapsedNanos) {
        if (elapsedNanos > 0) accumulated += elapsedNanos * ticksPerSecond;
        long due = accumulated / NANOS_PER_SECOND;
        accumulated -= due * NANOS_PER_SECOND;
        return (int) due;
    }
}

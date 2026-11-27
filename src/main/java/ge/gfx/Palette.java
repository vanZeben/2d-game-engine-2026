package ge.gfx;

/**
 * The 216-colour palette: 6 levels each of red, green and blue.
 *
 * <p>Entry {@code r*36 + g*6 + b} (levels 0..5) is the colour whose channels are {@code level * 51}
 * (0, 51, 102, 153, 204, 255), so entry 0 is black and entry 215 is white. A tile colour is either a
 * palette index or {@link #TRANSPARENT}. The table is built once; lookups never allocate.
 */
public final class Palette {
    /** Number of palette entries. */
    public static final int SIZE = 216;
    /** A "colour" meaning draw nothing; it has no rgb value. */
    public static final int TRANSPARENT = -1;

    private static final int LEVELS = 6;
    private static final int STEP = 51;
    private static final int[] RGB = new int[SIZE];

    static {
        for (int r = 0; r < LEVELS; r++) {
            for (int g = 0; g < LEVELS; g++) {
                for (int b = 0; b < LEVELS; b++) {
                    RGB[r * 36 + g * 6 + b] = (r * STEP) << 16 | (g * STEP) << 8 | (b * STEP);
                }
            }
        }
    }

    private Palette() {}

    /** The 0xRRGGBB value of entry {@code index}. @throws IllegalArgumentException unless 0 <= index < 216 */
    public static int rgb(int index) {
        if (index < 0 || index >= SIZE) {
            throw new IllegalArgumentException("palette index " + index + " outside 0.." + (SIZE - 1));
        }
        return RGB[index];
    }

    /** The palette index of the colour with the given levels. @throws IllegalArgumentException if a level is outside 0..5 */
    public static int index(int r, int g, int b) {
        if (r < 0 || r >= LEVELS || g < 0 || g >= LEVELS || b < 0 || b >= LEVELS) {
            throw new IllegalArgumentException("colour levels must be 0..5, got " + r + "," + g + "," + b);
        }
        return r * 36 + g * 6 + b;
    }
}

package ge.level;

import ge.Screen;
import ge.gfx.SpriteSheet;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.imageio.ImageIO;

/**
 * A width x height grid of {@link Tile}, row-major with cell (0,0) at the top left. Everything outside the grid
 * reads as {@link Tile#VOID}, so callers (rendering, collision, the camera) never need their own bounds checks.
 */
public final class Level {
    /** Most cells a level may have (keeps {@code y * width + x} inside an int and memory sane). */
    public static final int MAX_CELLS = 1 << 24;

    private final int width, height;
    /** Tile ids, one byte per cell; all 0 = {@link Tile#VOID} at creation. */
    private final byte[] ids;

    /** @throws IllegalArgumentException unless both sizes are >= 1 and the cell count is at most {@link #MAX_CELLS} */
    public Level(int width, int height) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("level size must be at least 1x1, got " + width + "x" + height);
        }
        if ((long) width * height > MAX_CELLS) {
            throw new IllegalArgumentException("level " + width + "x" + height + " has more than " + MAX_CELLS + " cells");
        }
        this.width = width;
        this.height = height;
        this.ids = new byte[width * height];
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** An independent level with the same size and tiles. */
    public Level copy() {
        Level c = new Level(width, height);
        System.arraycopy(ids, 0, c.ids, 0, ids.length);
        return c;
    }

    /** The tile at cell (x, y); any cell outside the level (any int) is {@link Tile#VOID}. */
    public Tile getTile(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) return Tile.VOID;
        return Tile.byId(ids[y * width + x]);
    }

    /** Sets cell (x, y); a cell outside the level is silently ignored. @throws IllegalArgumentException if t is null */
    public void setTile(int x, int y, Tile t) {
        if (t == null) throw new IllegalArgumentException("tile is null");
        if (x < 0 || y < 0 || x >= width || y >= height) return;
        ids[y * width + x] = (byte) t.id();
    }

    /**
     * Draws the part of the world that the screen shows, on top of whatever the screen holds: screen pixel
     * (sx, sy) shows world pixel (sx + xOffset, sy + yOffset), and world pixel (wx, wy) is in cell
     * (floorDiv(wx, 8), floorDiv(wy, 8)). Only the cells overlapping the screen are visited, cells outside the
     * level draw as void, and any offset (negative, not a multiple of 8, near the int limits) is fine.
     */
    public void render(Screen screen, SpriteSheet sheet, int xOffset, int yOffset) {
        if (screen == null) throw new IllegalArgumentException("screen is null");
        if (sheet == null) throw new IllegalArgumentException("sheet is null");
        int size = Screen.TILE_SIZE;
        // Visible cell range in long: offset + screen size can pass Integer.MAX_VALUE. The cell numbers fit an
        // int (|offset| < 2^31 and size is 8), and a visible cell's screen position (cell*8 - offset) lies in
        // [-7, screen size), so the narrowing below never wraps.
        long firstX = Math.floorDiv((long) xOffset, (long) size);
        long lastX = Math.floorDiv((long) xOffset + screen.width - 1, (long) size);
        long firstY = Math.floorDiv((long) yOffset, (long) size);
        long lastY = Math.floorDiv((long) yOffset + screen.height - 1, (long) size);
        for (long cy = firstY; cy <= lastY; cy++) {
            int py = (int) (cy * size - yOffset);
            for (long cx = firstX; cx <= lastX; cx++) {
                Tile t = getTile((int) cx, (int) cy);
                screen.drawPaletteTile(sheet, t.sheetX(), t.sheetY(), (int) (cx * size - xOffset), py, t.paletteColours());
            }
        }
    }

    /**
     * Loads a level from a PNG: the level is as wide and high as the image, and cell (x, y) is
     * {@link Tile#byLevelColour(int)} of pixel (x, y) (alpha ignored, exact colour match, any unknown colour is
     * {@link Tile#VOID}).
     *
     * <p>A {@code resource} starting with '/' is a classpath resource such as {@code "/levels/small_test_level.png"};
     * if the classpath has no such resource the same text is tried as an absolute file path. Anything else is a
     * file path (relative to the working directory, or absolute).
     *
     * @throws IllegalArgumentException if nothing is found there, it is not a decodable image, or it is too big
     */
    public static Level load(String resource) {
        if (resource == null || resource.isEmpty()) throw new IllegalArgumentException("level resource is empty");
        BufferedImage image;
        try {
            image = readImage(resource);
        } catch (IOException | RuntimeException e) {
            if (e instanceof IllegalArgumentException iae) throw iae;
            throw new IllegalArgumentException("cannot read level image " + resource + ": " + e.getMessage(), e);
        }
        if (image == null) throw new IllegalArgumentException("not a readable image: " + resource);
        int w = image.getWidth(), h = image.getHeight();
        Level level = new Level(w, h); // rejects sizes over MAX_CELLS
        int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < argb.length; i++) level.ids[i] = (byte) Tile.byLevelColour(argb[i]).id();
        return level;
    }

    private static BufferedImage readImage(String resource) throws IOException {
        if (resource.startsWith("/")) {
            try (InputStream in = Level.class.getResourceAsStream(resource)) {
                if (in != null) return ImageIO.read(in);
            }
        }
        Path file = Paths.get(resource);
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("level not found: " + resource);
        try (InputStream in = Files.newInputStream(file)) {
            return ImageIO.read(in);
        }
    }

    /**
     * A reproducible terrain: the same (width, height, seed) always gives the same level. Two octaves of
     * smooth value noise are ranked, so about 30% of the cells are water, about 45% grass and about 25% stone
     * whatever the seed, with water in the lows and stone on the peaks.
     *
     * @throws IllegalArgumentException as {@link #Level(int, int)}
     */
    public static Level generate(int width, int height, long seed) {
        Level level = new Level(width, height);
        // Pass 1: histogram of the noise to find the water and stone cut-offs; pass 2: assign tiles.
        int[] histogram = new int[BINS];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) histogram[bin(noise(seed, x, y))]++;
        }
        long cells = (long) width * height;
        int waterTop = binReaching(histogram, cells * 30 / 100);
        int grassTop = binReaching(histogram, cells * 75 / 100);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int b = bin(noise(seed, x, y));
                Tile t = b <= waterTop ? Tile.WATER : b <= grassTop ? Tile.GRASS : Tile.STONE;
                level.ids[y * width + x] = (byte) t.id();
            }
        }
        return level;
    }

    private static final int BINS = 1024;
    /** Octaves: lattice spacing in cells and weight (weights sum to 1). */
    private static final int[] SPACING = {12, 5};
    private static final double[] WEIGHT = {0.7, 0.3};

    private static int bin(double v) {
        return Math.min(BINS - 1, (int) (v * BINS));
    }

    /** The lowest bin whose running total reaches {@code target} cells (the last bin if none does). */
    private static int binReaching(int[] histogram, long target) {
        long sum = 0;
        for (int i = 0; i < histogram.length; i++) {
            sum += histogram[i];
            if (sum >= target) return i;
        }
        return histogram.length - 1;
    }

    /** Smooth noise in [0, 1) at cell (x, y). */
    private static double noise(long seed, int x, int y) {
        double v = 0;
        for (int o = 0; o < SPACING.length; o++) {
            int s = SPACING[o];
            int gx = x / s, gy = y / s; // x, y >= 0 here
            double fx = smooth((x - gx * s) / (double) s), fy = smooth((y - gy * s) / (double) s);
            long os = seed + o * 0x632BE59BD9B4E019L;
            double top = lerp(lattice(os, gx, gy), lattice(os, gx + 1, gy), fx);
            double bottom = lerp(lattice(os, gx, gy + 1), lattice(os, gx + 1, gy + 1), fx);
            v += WEIGHT[o] * lerp(top, bottom, fy);
        }
        return Math.min(v, Math.nextDown(1.0));
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** A pseudo-random value in [0, 1) for lattice point (gx, gy): a SplitMix64 hash of seed and coordinates. */
    private static double lattice(long seed, int gx, int gy) {
        long z = seed + gx * 0x9E3779B97F4A7C15L + gy * 0xC2B2AE3D27D4EB4FL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53;
    }
}

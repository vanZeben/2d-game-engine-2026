package ge.gfx;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import javax.imageio.ImageIO;

/**
 * A sprite sheet held as one colour index (0..3) per pixel, row-major.
 *
 * <p>The sheet art is drawn in four grey levels. Each pixel is reduced to a 2-bit index by taking
 * its blue channel divided by 64 ({@code blue >> 6}), so 0 is the darkest level and 3 the brightest.
 * Later episodes map those indexes through a palette when tiles are drawn.
 */
public final class SpriteSheet {
    /** Number of colour indexes a sheet pixel can have (0..3). */
    public static final int COLOURS = 4;

    private final int width;
    private final int height;
    private final int[] pixels;

    private SpriteSheet(int width, int height, int[] pixels) {
        this.width = width;
        this.height = height;
        this.pixels = pixels;
    }

    /**
     * Loads a classpath resource such as {@code "/sprite_sheet.png"}.
     *
     * @throws IllegalArgumentException if the resource does not exist
     * @throws UncheckedIOException if it exists but cannot be read as an image
     */
    public static SpriteSheet load(String resourcePath) {
        BufferedImage image;
        try (InputStream in = SpriteSheet.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalArgumentException("Sprite sheet resource not found: " + resourcePath);
            }
            image = ImageIO.read(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read sprite sheet " + resourcePath, e);
        }
        if (image == null) {
            throw new IllegalArgumentException("Resource is not a readable image: " + resourcePath);
        }
        return fromImage(image);
    }

    private static SpriteSheet fromImage(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
        int[] indexes = new int[w * h];
        for (int i = 0; i < indexes.length; i++) {
            indexes[i] = (argb[i] & 0xff) >> 6;
        }
        return new SpriteSheet(w, h, indexes);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Row-major colour indexes, {@code width() * height()} long; the element for (x, y) is at {@code y * width() + x}. */
    public int[] pixels() {
        return pixels;
    }

    /** The colour index (0..3) at pixel (x, y). */
    public int index(int x, int y) {
        return pixels[y * width + x];
    }
}

package ge;

import java.awt.image.BufferedImage;
import java.util.Arrays;

/** The 160x120 off-screen pixel buffer (0xRRGGBB ints). Later episodes draw tiles and text into it. */
public class Screen {
    public final int width;
    public final int height;
    public final int[] pixels;

    public Screen(int width, int height) {
        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];
    }

    public void clear(int rgb) {
        Arrays.fill(pixels, rgb);
    }

    /** Copies the buffer into an image of the same size (no scaling). */
    public BufferedImage toImage() {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, width, height, pixels, 0, width);
        return img;
    }
}

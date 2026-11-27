package ge;

import ge.gfx.SpriteSheet;
import java.awt.image.BufferedImage;
import java.util.Arrays;

/** The 160x120 off-screen pixel buffer (0xRRGGBB ints). Later episodes draw tiles and text into it. */
public class Screen {
    /** Side of a sprite-sheet tile in pixels. */
    public static final int TILE_SIZE = 8;

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

    /**
     * Draws one 8x8 tile of the sprite sheet with its top-left corner at screen pixel (x, y).
     *
     * <p>The tile is the block of sheet pixels starting at (tileX*8, tileY*8). Each sheet pixel with
     * colour index i is written as {@code colours[i]} (0xRRGGBB). Pixels falling outside the buffer
     * are skipped, so any (x, y) is allowed, even one entirely off-screen. Unmirrored, unscaled.
     *
     * @throws IllegalArgumentException if the tile is outside the sheet's tile grid or
     *                                  {@code colours} does not have exactly 4 entries
     */
    public void drawTile(SpriteSheet sheet, int tileX, int tileY, int x, int y, int[] colours) {
        if (colours == null || colours.length != SpriteSheet.COLOURS) {
            throw new IllegalArgumentException("colours must have exactly " + SpriteSheet.COLOURS + " entries");
        }
        int tilesX = sheet.width() / TILE_SIZE;
        int tilesY = sheet.height() / TILE_SIZE;
        if (tileX < 0 || tileY < 0 || tileX >= tilesX || tileY >= tilesY) {
            throw new IllegalArgumentException("tile (" + tileX + "," + tileY + ") outside the "
                    + tilesX + "x" + tilesY + " tile grid");
        }
        // Clip the 8x8 rectangle to the buffer once, so the inner loops need no bounds checks.
        // (Done in long arithmetic: x/y may be anywhere in the int range.)
        int x0 = (int) Math.max(0L, -(long) x);
        int y0 = (int) Math.max(0L, -(long) y);
        int x1 = (int) Math.min(TILE_SIZE, (long) width - x);
        int y1 = (int) Math.min(TILE_SIZE, (long) height - y);
        if (x0 >= x1 || y0 >= y1) return;

        int[] sheetPixels = sheet.pixels();
        int sheetBase = tileY * TILE_SIZE * sheet.width() + tileX * TILE_SIZE;
        for (int ty = y0; ty < y1; ty++) {
            int src = sheetBase + ty * sheet.width();
            int dst = (y + ty) * width + x;
            for (int tx = x0; tx < x1; tx++) {
                pixels[dst + tx] = colours[sheetPixels[src + tx]];
            }
        }
    }

    /** Copies the buffer into an image of the same size (no scaling). */
    public BufferedImage toImage() {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, width, height, pixels, 0, width);
        return img;
    }
}

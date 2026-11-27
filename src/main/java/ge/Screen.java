package ge;

import ge.gfx.Palette;
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
    /** Scratch for the 4 resolved rgb values of the tile being drawn (Screen is single-threaded). */
    private final int[] tileColours = new int[SpriteSheet.COLOURS];

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
        checkTile(sheet, tileX, tileY);
        blit(sheet, tileX, tileY, x, y, colours);
    }

    /**
     * Like {@link #drawTile} but each of the 4 colours is a {@link Palette} index (0..215) or
     * {@link Palette#TRANSPARENT}: sheet pixels whose slot is transparent leave the screen untouched.
     *
     * @throws IllegalArgumentException if the tile is outside the sheet's tile grid, {@code colours}
     *                                  is null or not 4 long, or an entry is below -1 or above 215
     */
    public void drawPaletteTile(SpriteSheet sheet, int tileX, int tileY, int x, int y, int[] colours) {
        if (colours == null || colours.length != SpriteSheet.COLOURS) {
            throw new IllegalArgumentException("colours must have exactly " + SpriteSheet.COLOURS + " entries");
        }
        checkTile(sheet, tileX, tileY);
        // Resolve palette indexes to rgb once per tile (4 table reads) into a reusable scratch array,
        // so the pixel loop is a plain int lookup and nothing is allocated per call.
        for (int i = 0; i < SpriteSheet.COLOURS; i++) {
            int c = colours[i];
            tileColours[i] = c == Palette.TRANSPARENT ? Palette.TRANSPARENT : Palette.rgb(c);
        }
        blit(sheet, tileX, tileY, x, y, tileColours);
    }

    private static void checkTile(SpriteSheet sheet, int tileX, int tileY) {
        int tilesX = sheet.width() / TILE_SIZE;
        int tilesY = sheet.height() / TILE_SIZE;
        if (tileX < 0 || tileY < 0 || tileX >= tilesX || tileY >= tilesY) {
            throw new IllegalArgumentException("tile (" + tileX + "," + tileY + ") outside the "
                    + tilesX + "x" + tilesY + " tile grid");
        }
    }

    /**
     * The one tile loop. {@code rgb[i]} is the 0xRRGGBB for sheet colour index i, or
     * {@link Palette#TRANSPARENT} (-1, never a valid 0xRRGGBB) to skip those pixels.
     * Episode 6 (mirroring) and 10 (scaling) are meant to be overloads of this loop that change only
     * how the source column/row and destination step are derived.
     */
    private void blit(SpriteSheet sheet, int tileX, int tileY, int x, int y, int[] rgb) {
        // Clip the 8x8 rectangle to the buffer once, so the inner loops need no bounds checks.
        // (Done in long arithmetic: x/y may be anywhere in the int range.)
        int x0 = (int) Math.max(0L, -(long) x);
        int y0 = (int) Math.max(0L, -(long) y);
        int x1 = (int) Math.min(TILE_SIZE, (long) width - x);
        int y1 = (int) Math.min(TILE_SIZE, (long) height - y);
        if (x0 >= x1 || y0 >= y1) return;

        int[] sheetPixels = sheet.pixels();
        int sheetWidth = sheet.width();
        int c0 = rgb[0], c1 = rgb[1], c2 = rgb[2], c3 = rgb[3];
        int sheetBase = tileY * TILE_SIZE * sheetWidth + tileX * TILE_SIZE;
        for (int ty = y0; ty < y1; ty++) {
            int src = sheetBase + ty * sheetWidth;
            int dst = (y + ty) * width + x;
            for (int tx = x0; tx < x1; tx++) {
                int c = switch (sheetPixels[src + tx]) {
                    case 0 -> c0;
                    case 1 -> c1;
                    case 2 -> c2;
                    default -> c3;
                };
                if (c != Palette.TRANSPARENT) pixels[dst + tx] = c;
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

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

    /** No mirroring. */
    public static final int MIRROR_NONE = 0;
    /** Flip the tile left-right (bit set in a {@code mirror} argument). */
    public static final int MIRROR_X = 1;
    /** Flip the tile top-bottom (bit set in a {@code mirror} argument). */
    public static final int MIRROR_Y = 2;
    private static final int MIRROR_ALL = MIRROR_X | MIRROR_Y;

    /**
     * Draws one 8x8 tile of the sprite sheet with its top-left corner at screen pixel (x, y).
     *
     * <p>The tile is the block of sheet pixels starting at (tileX*8, tileY*8). Each sheet pixel with
     * colour index i is written as {@code colours[i]} (0xRRGGBB, or -1 for transparent). Pixels falling
     * outside the buffer are skipped, so any (x, y) is allowed, even one entirely off-screen. Unmirrored.
     *
     * @throws IllegalArgumentException if the tile is outside the sheet's tile grid or
     *                                  {@code colours} does not have exactly 4 entries
     */
    public void drawTile(SpriteSheet sheet, int tileX, int tileY, int x, int y, int[] colours) {
        drawTile(sheet, tileX, tileY, x, y, colours, MIRROR_NONE);
    }

    /**
     * Like {@link #drawTile(SpriteSheet, int, int, int, int, int[])} but the 8x8 block is flipped in place:
     * with {@link #MIRROR_X} screen pixel (x+dx, y+dy) shows tile pixel (7-dx, dy); with {@link #MIRROR_Y}
     * it shows (dx, 7-dy); with both, (7-dx, 7-dy). The block never moves, and clipping shows exactly the
     * matching part of the flipped block.
     *
     * @param mirror a bit set of {@link #MIRROR_X} | {@link #MIRROR_Y}
     * @throws IllegalArgumentException as the 6-argument method, or if {@code mirror} has any other bit set
     */
    public void drawTile(SpriteSheet sheet, int tileX, int tileY, int x, int y, int[] colours, int mirror) {
        drawTile(sheet, tileX, tileY, x, y, colours, mirror, 1);
    }

    /**
     * Like {@link #drawTile(SpriteSheet, int, int, int, int, int[], int)} but each sheet pixel becomes a
     * {@code scale} x {@code scale} block, so the tile covers {@code 8*scale} x {@code 8*scale} screen pixels with
     * its top-left at (x, y). Screen pixel (x+dx, y+dy) shows tile pixel (dx/scale, dy/scale); mirroring is
     * applied to the 8x8 source (source column 7-(dx/scale) for {@link #MIRROR_X}, likewise for Y). Scale 1 is
     * exactly the unscaled method. Only the visible part is visited, so any {@code scale} up to
     * {@link Integer#MAX_VALUE} at any (x, y) is cheap and never overflows.
     *
     * @throws IllegalArgumentException as the 7-argument method, or if {@code scale < 1} (even if the tile is off screen)
     */
    public void drawTile(SpriteSheet sheet, int tileX, int tileY, int x, int y, int[] colours, int mirror, int scale) {
        checkColours(colours);
        checkMirror(mirror);
        checkScale(scale);
        checkTile(sheet, tileX, tileY);
        blit(sheet, tileX, tileY, x, y, colours, mirror, scale);
    }

    /**
     * Like {@link #drawTile} but each of the 4 colours is a {@link Palette} index (0..215) or
     * {@link Palette#TRANSPARENT}: sheet pixels whose slot is transparent leave the screen untouched.
     * Unmirrored.
     *
     * @throws IllegalArgumentException if the tile is outside the sheet's tile grid, {@code colours}
     *                                  is null or not 4 long, or an entry is below -1 or above 215
     */
    public void drawPaletteTile(SpriteSheet sheet, int tileX, int tileY, int x, int y, int[] colours) {
        drawPaletteTile(sheet, tileX, tileY, x, y, colours, MIRROR_NONE);
    }

    /** {@link #drawPaletteTile(SpriteSheet, int, int, int, int, int[])} with the mirroring of {@link #drawTile(SpriteSheet, int, int, int, int, int[], int)}. */
    public void drawPaletteTile(SpriteSheet sheet, int tileX, int tileY, int x, int y, int[] colours, int mirror) {
        drawPaletteTile(sheet, tileX, tileY, x, y, colours, mirror, 1);
    }

    /**
     * {@link #drawPaletteTile(SpriteSheet, int, int, int, int, int[], int)} drawn at an integer {@code scale}; see
     * {@link #drawTile(SpriteSheet, int, int, int, int, int[], int, int)} for the geometry.
     *
     * @throws IllegalArgumentException as the 7-argument method, or if {@code scale < 1} (even if off screen)
     */
    public void drawPaletteTile(SpriteSheet sheet, int tileX, int tileY, int x, int y, int[] colours, int mirror,
            int scale) {
        drawPaletteTile(sheet, tileX, tileY, (long) x, (long) y, colours, mirror, scale);
    }

    /**
     * As the {@code int} position overload, with a {@code long} top-left so callers that advance a cursor in long
     * arithmetic (scaled text) can place a block starting beyond the int range whose far part is still visible.
     */
    public void drawPaletteTile(SpriteSheet sheet, int tileX, int tileY, long x, long y, int[] colours, int mirror,
            int scale) {
        checkColours(colours);
        checkMirror(mirror);
        checkScale(scale);
        checkTile(sheet, tileX, tileY);
        // Resolve palette indexes to rgb once per tile (4 table reads) into a reusable scratch array,
        // so the pixel loop is a plain int lookup and nothing is allocated per call.
        for (int i = 0; i < SpriteSheet.COLOURS; i++) {
            int c = colours[i];
            tileColours[i] = c == Palette.TRANSPARENT ? Palette.TRANSPARENT : Palette.rgb(c);
        }
        blit(sheet, tileX, tileY, x, y, tileColours, mirror, scale);
    }

    private static void checkColours(int[] colours) {
        if (colours == null || colours.length != SpriteSheet.COLOURS) {
            throw new IllegalArgumentException("colours must have exactly " + SpriteSheet.COLOURS + " entries");
        }
    }

    private static void checkScale(int scale) {
        if (scale < 1) throw new IllegalArgumentException("scale must be at least 1, got " + scale);
    }

    private static void checkMirror(int mirror) {
        if ((mirror & ~MIRROR_ALL) != 0) {
            throw new IllegalArgumentException("mirror must be a combination of MIRROR_X|MIRROR_Y (0..3), got " + mirror);
        }
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
     * The block covers {@code 8*scale} pixels each way from (x, y); it is clipped to the buffer in long arithmetic
     * and only visible destination pixels are visited. Along a row the visible part is cut into runs that share one
     * source pixel (one run per source column), so a run is at most {@code width} long and fits an int.
     * Mirroring only changes which source column/row a destination offset reads.
     */
    private void blit(SpriteSheet sheet, int tileX, int tileY, long x, long y, int[] rgb, int mirror, int scale) {
        long size = (long) TILE_SIZE * scale; // <= 8 * (2^31 - 1), fits a long
        long sx0 = Math.max(0L, x), sx1 = Math.min(width, x + size);
        long sy0 = Math.max(0L, y), sy1 = Math.min(height, y + size);
        if (sx0 >= sx1 || sy0 >= sy1) return;

        int[] sheetPixels = sheet.pixels();
        int sheetWidth = sheet.width();
        int c0 = rgb[0], c1 = rgb[1], c2 = rgb[2], c3 = rgb[3];
        int sheetBase = tileY * TILE_SIZE * sheetWidth + tileX * TILE_SIZE;
        boolean flipX = (mirror & MIRROR_X) != 0;
        boolean flipY = (mirror & MIRROR_Y) != 0;
        // Source column and offset inside its block at the first visible pixel (same for every row).
        long firstDx = sx0 - x;
        int firstU = (int) (firstDx / scale);
        long firstRem = firstDx % scale;
        for (int sy = (int) sy0; sy < sy1; sy++) {
            int v = (int) ((sy - y) / scale);
            int src = sheetBase + (flipY ? TILE_SIZE - 1 - v : v) * sheetWidth;
            int dst = sy * width;
            int u = firstU;
            long rem = firstRem;
            for (long px = sx0; px < sx1; u++) {
                int run = (int) Math.min(sx1 - px, scale - rem); // <= width
                int c = switch (sheetPixels[src + (flipX ? TILE_SIZE - 1 - u : u)]) {
                    case 0 -> c0;
                    case 1 -> c1;
                    case 2 -> c2;
                    default -> c3;
                };
                int from = dst + (int) px;
                if (c != Palette.TRANSPARENT) {
                    if (run == 1) pixels[from] = c;
                    else Arrays.fill(pixels, from, from + run, c);
                }
                px += run;
                rem = 0;
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

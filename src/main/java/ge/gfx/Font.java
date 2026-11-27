package ge.gfx;

import ge.Screen;

/**
 * Text drawn from the font rows of the sprite sheet: a thin layer over
 * {@link Screen#drawPaletteTile(SpriteSheet, int, int, int, int, int[])}.
 *
 * <p>Row 30 holds {@code A..Z} in columns 0..25; row 31 holds {@code 0..9} in columns 0..9, then the
 * punctuation {@code .,:;'"!?$%()-=+/} in columns 10..25. Lookup is case-insensitive for ASCII letters only.
 * A space advances the cursor and draws nothing. Any other character is unknown and is skipped: it draws
 * nothing and does not advance the cursor, exactly as if it were not in the string.
 */
public final class Font {
    /** Pixels the cursor moves per drawn (or space) character. */
    public static final int ADVANCE = Screen.TILE_SIZE;

    private static final int LETTER_ROW = 30;
    private static final int DIGIT_ROW = 31;
    private static final int PUNCT_COLUMN = 10;
    private static final String PUNCTUATION = ".,:;'\"!?$%()-=+/";

    /** Marker from {@link #column} for a character that is not in the font. */
    private static final int UNKNOWN = -1;
    /** Marker from {@link #row} / {@link #column} for the space (known, draws nothing). */
    private static final int SPACE = -2;

    private Font() {}

    /**
     * Draws {@code text} with its top-left at pixel (x, y), one 8x8 glyph per character, left to right.
     * Each glyph is drawn as {@code screen.drawPaletteTile(sheet, tx, ty, cursor, y, colours)} would;
     * glyphs off any edge are clipped.
     *
     * @throws IllegalArgumentException if screen, sheet, text or colours is null, colours is not 4 long,
     *                                  or an entry is below -1 or above 215 (checked even for empty text)
     */
    public static void render(Screen screen, SpriteSheet sheet, String text, int x, int y, int[] colours) {
        if (screen == null) throw new IllegalArgumentException("screen is null");
        if (sheet == null) throw new IllegalArgumentException("sheet is null");
        if (text == null) throw new IllegalArgumentException("text is null");
        if (colours == null || colours.length != SpriteSheet.COLOURS) {
            throw new IllegalArgumentException("colours must have exactly " + SpriteSheet.COLOURS + " entries");
        }
        for (int c : colours) {
            if (c < Palette.TRANSPARENT || c >= Palette.SIZE) {
                throw new IllegalArgumentException("colour " + c + " outside -1.." + (Palette.SIZE - 1));
            }
        }
        long cursor = x; // long: x near Integer.MAX_VALUE must not wrap
        for (int i = 0; i < text.length(); i++) {
            int col = column(text.charAt(i));
            if (col == UNKNOWN) continue;
            // Only glyphs that overlap the screen are handed on, so the int narrowing below is safe.
            if (col != SPACE && cursor > -ADVANCE && cursor < screen.width) {
                screen.drawPaletteTile(sheet, col, row(text.charAt(i)), (int) cursor, y, colours);
            }
            cursor += ADVANCE;
            if (cursor >= screen.width) return; // everything further right is off screen too
        }
    }

    /** Width in pixels of {@code text}: {@link #ADVANCE} per character that is not skipped (spaces count). */
    public static int width(String text) {
        if (text == null) throw new IllegalArgumentException("text is null");
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (column(text.charAt(i)) != UNKNOWN) n++;
        }
        return n * ADVANCE;
    }

    private static int column(char c) {
        if (c == ' ') return SPACE;
        if (c >= 'a' && c <= 'z') return c - 'a';
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= '0' && c <= '9') return c - '0';
        int p = c < 128 ? PUNCTUATION.indexOf(c) : -1;
        return p >= 0 ? PUNCT_COLUMN + p : UNKNOWN;
    }

    /** Sheet tile row of a known, non-space character. */
    private static int row(char c) {
        boolean letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
        return letter ? LETTER_ROW : DIGIT_ROW;
    }
}

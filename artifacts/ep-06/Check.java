import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/* Independent check: expected frame = black + tile (from sheet, flipped by this code) at (px,py). */
public class Check {
    static final int W = 160, H = 120;
    static final int T = -1;
    public static void main(String[] a) throws Exception {
        BufferedImage frame = ImageIO.read(new File(a[0]));
        int tx = Integer.parseInt(a[1]), ty = Integer.parseInt(a[2]);
        int px = Integer.parseInt(a[3]), py = Integer.parseInt(a[4]);
        int mode = Integer.parseInt(a[5]);          // bit0 = X, bit1 = Y
        String kind = a[6];                          // "grey" or "pal"
        int[] pal = new int[4];
        if (kind.equals("pal")) for (int i = 0; i < 4; i++) pal[i] = a[7 + i].equals("t") ? T : Integer.parseInt(a[7 + i]);
        BufferedImage sheet = ImageIO.read(new File("/tmp/ge2026/res/sprite_sheet.png"));
        int fw = frame.getWidth(), fh = frame.getHeight();
        int sc = fw / W;
        System.out.println("frame " + fw + "x" + fh + " scale=" + sc);
        int[] exp = new int[W * H];
        for (int dy = 0; dy < 8; dy++) for (int dx = 0; dx < 8; dx++) {
            int sx = px + dx, sy = py + dy;
            if (sx < 0 || sy < 0 || sx >= W || sy >= H) continue;
            int u = (mode & 1) != 0 ? 7 - dx : dx;
            int v = (mode & 2) != 0 ? 7 - dy : dy;
            int index = (sheet.getRGB(tx * 8 + u, ty * 8 + v) & 0xff) >> 6;
            int c;
            if (kind.equals("grey")) c = 0x555555 * index;
            else {
                int p = pal[index];
                if (p == T) continue;
                int r = p / 36, g = (p / 6) % 6, b = p % 6;
                c = (r * 51) << 16 | (g * 51) << 8 | (b * 51);
            }
            exp[sy * W + sx] = c;
        }
        int bad = 0, dark = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int got = frame.getRGB(x * sc + sc / 2, y * sc + sc / 2) & 0xffffff;
            // outside the tile block the background is black (0x000000)
            boolean inBlock = x >= px && x < px + 8 && y >= py && y < py + 8;
            int e = inBlock ? exp[y * W + x] : 0;
            if (got != e) { if (bad < 5) System.out.printf("mismatch at (%d,%d) got %06x expected %06x%n", x, y, got, e); bad++; }
            if (inBlock && exp[y * W + x] != 0) dark++;
        }
        System.out.println(bad == 0 ? "MATCH all " + (W * H) + " pixels (block nonzero px: " + dark + ")" : "MISMATCHES: " + bad);
    }
}

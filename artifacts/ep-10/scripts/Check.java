import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

// Independent check: expected frames built from sprite_sheet.png by a different method than the test
// (per-screen-pixel source lookup), compared with the dumped frames. Also saves an x3 nearest upscale.
public class Check {
    static BufferedImage sheet;
    static int W = 160, H = 120; // checked against the dump size below

    static int idx(int tx, int ty, int u, int v) {
        int p = sheet.getRGB(tx * 8 + u, ty * 8 + v) & 0xff;
        return p >> 6;
    }
    static int rgbOf(int i) { // palette rule: index = r*36+g*6+b, channel = level*51
        return ((i / 36) * 51) << 16 | (((i / 6) % 6) * 51) << 8 | ((i % 6) * 51);
    }
    // colours: array of 4 palette indexes or -1 for transparent
    static int[] expect(int w, int h, int tx, int ty, int ox, int oy, int s, int[] pal, int mirror) {
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int dx = x - ox, dy = y - oy;
            if (dx < 0 || dy < 0 || dx >= 8 * s || dy >= 8 * s) continue;
            int u = dx / s, v = dy / s;
            if ((mirror & 1) != 0) u = 7 - u;
            if ((mirror & 2) != 0) v = 7 - v;
            int c = pal[idx(tx, ty, u, v)];
            if (c >= 0) out[y * w + x] = rgbOf(c);
        }
        return out;
    }
    static int[] expectText(int w, int h, String text, int ox, int oy, int s, int[] pal) {
        int[] out = new int[w * h];
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ') continue;
            int tx = c >= 'A' && c <= 'Z' ? c - 'A' : c - '0', ty = c >= 'A' && c <= 'Z' ? 30 : 31;
            int[] g = expect(w, h, tx, ty, ox + 8 * s * i, oy, s, pal, 0);
            for (int k = 0; k < g.length; k++) if (g[k] != 0 || false) out[k] = g[k] != 0 ? g[k] : out[k];
        }
        return out;
    }
    static int[] read(String f) throws Exception {
        BufferedImage im = ImageIO.read(new File(f));
        int[] px = new int[im.getWidth() * im.getHeight()];
        for (int y = 0; y < im.getHeight(); y++) for (int x = 0; x < im.getWidth(); x++) px[y * im.getWidth() + x] = im.getRGB(x, y) & 0xFFFFFF;
        W = im.getWidth(); H = im.getHeight();
        return px;
    }
    static int check(String name, int[] got, int[] exp) {
        int bad = 0, first = -1, nonBlack = 0;
        for (int i = 0; i < got.length; i++) { if (got[i] != 0) nonBlack++; if (got[i] != exp[i]) { bad++; if (first < 0) first = i; } }
        System.out.printf("%-10s frame %dx%d nonblack=%d mismatches=%d%s%n", name, W, H, nonBlack, bad,
            bad > 0 ? String.format(" first at (%d,%d) got %06x exp %06x", first % W, first / W, got[first], exp[first]) : "");
        return bad;
    }
    static void blocks(String name, int[] px, int ox, int oy, int tiles, int s) {
        // each sheet pixel must be a solid s x s block
        int bad = 0;
        for (int v = 0; v < 8 * tiles; v++) for (int u = 0; u < 8; u++) {
            int y0 = oy + v * s, x0 = ox + u * s;
            int first = px[y0 * W + x0];
            for (int j = 0; j < s; j++) for (int i = 0; i < s; i++) if (px[(y0 + j) * W + x0 + i] != first) bad++;
        }
        System.out.println(name + " block-solidity violations=" + bad);
    }
    static void upscale(int[] px, int w, int h, int k, String out) throws Exception {
        BufferedImage im = new BufferedImage(w * k, h * k, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h * k; y++) for (int x = 0; x < w * k; x++) im.setRGB(x, y, px[(y / k) * w + x / k]);
        ImageIO.write(im, "png", new File(out));
    }

    public static void main(String[] a) throws Exception {
        String root = a[0], out = a[1];
        sheet = ImageIO.read(new File(root + "/res/sprite_sheet.png"));
        int[] gp = {6, 54, 61, 115};        // grass 010,130,141,351
        int[] tp = {-1, 186, 35, 137};      // t,510,055,345
        int total = 0;
        String d = "/tmp/ge2026-work/ep-10/";
        int[] g1 = read(d + "d-grass1/frame-00000.png");
        total += check("grass x1", g1, expect(160, 120, 2, 0, 0, 0, 1, gp, 0));
        int[] g2 = read(d + "d-grass2/frame-00000.png");
        total += check("grass x2", g2, expect(160, 120, 2, 0, 0, 0, 2, gp, 0));
        blocks("grass x2", g2, 0, 0, 1, 2);
        int[] g4 = read(d + "d-grass4/frame-00000.png");
        total += check("grass x4", g4, expect(160, 120, 2, 0, 0, 0, 4, gp, 0));
        blocks("grass x4", g4, 0, 0, 1, 4);
        int[] h2 = read(d + "d-hello2/frame-00000.png");
        total += check("HELLO x2", h2, expectText(160, 120, "HELLO", 12, 40, 2, tp));
        blocks("HELLO x2", h2, 12, 40, 0, 2);
        int[] h3 = read(d + "d-hello3/frame-00000.png");
        total += check("HELLO x3", h3, expectText(160, 120, "HELLO", 12, 40, 3, tp));
        int[] p3 = read(d + "d-player3/frame-00000.png");
        total += check("player x3", p3, expect(160, 120, 0, 28, 20, 20, 3, tp, 1));
        int[] c2 = read(d + "d-clip2/frame-00000.png");
        total += check("clip x2", c2, expect(160, 120, 2, 0, 156, 10, 2, gp, 0));
        System.out.println("TOTAL mismatches=" + total);
        upscale(h2, 160, 120, 1, out + "/hello-x2.png");
        upscale(h2, 160, 120, 3, out + "/hello-x2-up3.png");
        upscale(g2, 160, 120, 1, out + "/grass-x2.png");
    }
}

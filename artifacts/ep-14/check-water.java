import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import javax.imageio.ImageIO;

public class Check {
    static BufferedImage img(int t) throws Exception { return ImageIO.read(new File(String.format("frames/frame-%05d.png", t - 1))); }
    static int[] cam = new int[200 * 2], pos = new int[200 * 2];
    static boolean inSprite(int sx, int sy) { return sx >= 72 && sx <= 87 && sy >= 52 && sy <= 67; }
    // count water-region pixels (world px 160..191 x 112..143) that differ between ticks a and b, sprite box excluded
    static int diff(int a, int b) throws Exception {
        BufferedImage A = img(a), B = img(b);
        int n = 0;
        for (int wy = 112; wy < 144; wy++) for (int wx = 160; wx < 192; wx++) {
            int ax = wx - cam[2 * a], ay = wy - cam[2 * a + 1], bx = wx - cam[2 * b], by = wy - cam[2 * b + 1];
            if (inSprite(ax, ay) || inSprite(bx, by)) continue;
            if (ax < 0 || ay < 0 || ax >= A.getWidth() || ay >= A.getHeight() || bx < 0 || by < 0 || bx >= B.getWidth() || by >= B.getHeight()) continue;
            if (A.getRGB(ax, ay) != B.getRGB(bx, by)) n++;
        }
        return n;
    }
    public static void main(String[] a) throws Exception {
        Pattern p = Pattern.compile("tick=(\\d+) x=(-?\\d+) y=(-?\\d+) camx=(-?\\d+) camy=(-?\\d+) .* swim=(\\d)");
        for (String l : Files.readAllLines(Paths.get("run.out"))) {
            Matcher m = p.matcher(l);
            if (m.find()) { int t = Integer.parseInt(m.group(1)); cam[2*t] = Integer.parseInt(m.group(4)); cam[2*t+1] = Integer.parseInt(m.group(5)); }
        }
        BufferedImage f1 = img(1);
        System.out.println("frame size " + f1.getWidth() + "x" + f1.getHeight());
        int[][] pairs = {{1,2},{2,3},{14,15},{29,30},{30,31},{44,45},{1,15},{15,30}};
        for (int[] q : pairs) System.out.println("water diff ticks " + q[0] + " vs " + q[1] + ": " + diff(q[0], q[1]));
    }
}

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class Diff {
    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        String[] names = dir.list((d, n) -> n.matches("frame-\\d{5}\\.png"));
        java.util.Arrays.sort(names);
        BufferedImage ref = ImageIO.read(new File(dir, "frame-00000.png"));
        System.out.println("dims " + ref.getWidth() + "x" + ref.getHeight() + " frames " + names.length);
        int prevState = -1;
        for (String n : names) {
            BufferedImage im = ImageIO.read(new File(dir, n));
            int minX = 1 << 30, minY = 1 << 30, maxX = -1, maxY = -1, cnt = 0;
            int bx0 = 102, by0 = 67, bx1 = 117, by1 = 82; int boxCnt = 0;
            for (int y = 0; y < ref.getHeight(); y++) for (int x = 0; x < ref.getWidth(); x++) {
                if (im.getRGB(x, y) != ref.getRGB(x, y)) {
                    cnt++; minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                    if (x >= bx0 && x <= bx1 && y >= by0 && y <= by1) boxCnt++;
                }
            }
            System.out.printf("%s diffPx=%d bbox=%s box102_117x67_82=%d%n", n, cnt,
                cnt == 0 ? "-" : minX + ".." + maxX + "," + minY + ".." + maxY, boxCnt);
        }
    }
}

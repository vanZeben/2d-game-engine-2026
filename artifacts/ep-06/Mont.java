import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
public class Mont {
    public static void main(String[] a) throws Exception {
        // args: out.png then frame.png files; crop 8x8 block at (8,8) from each, place in a row with 2px gaps, x3 nearest
        int n = a.length - 1, cell = 8, gap = 2;
        BufferedImage out = new BufferedImage(n * (cell + gap) - gap, cell, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < n; i++) {
            BufferedImage f = ImageIO.read(new File(a[i + 1]));
            for (int y = 0; y < cell; y++) for (int x = 0; x < cell; x++)
                out.setRGB(i * (cell + gap) + x, y, f.getRGB(8 + x, 8 + y));
        }
        BufferedImage big = new BufferedImage(out.getWidth() * 3, out.getHeight() * 3, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < big.getHeight(); y++) for (int x = 0; x < big.getWidth(); x++)
            big.setRGB(x, y, out.getRGB(x / 3, y / 3));
        ImageIO.write(big, "png", new File(a[0]));
    }
}

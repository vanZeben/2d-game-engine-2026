import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class Sheet {
    static BufferedImage up3(BufferedImage src, int x, int y, int w, int h, int s) {
        BufferedImage out = new BufferedImage(w * s, h * s, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < h * s; j++) for (int i = 0; i < w * s; i++) out.setRGB(i, j, src.getRGB(x + i / s, y + j / s));
        return out;
    }
    public static void main(String[] a) throws Exception {
        String dir = a[0];
        // {label, tick}: one frame per pose, tick = first tick of that pose in the trace
        String[][] poses = {{"down f0", "1"}, {"down f1", "14"}, {"left f0", "31"}, {"left f1", "39"},
                            {"up f0", "56"}, {"up f1", "64"}, {"right f0", "81"}, {"right f1", "89"}};
        int s = 3, pad = 12, cell = 16 * s + pad;
        BufferedImage sheet = new BufferedImage(cell * 8 + 20, 16 * s + 70, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setColor(new Color(0x202020)); g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        for (int i = 0; i < poses.length; i++) {
            int t = Integer.parseInt(poses[i][1]);
            BufferedImage frame = ImageIO.read(new File(dir + String.format("/frame-%05d.png", t - 1)));
            BufferedImage crop = up3(frame, 72, 52, 16, 16, s);
            int x = 10 + i * cell;
            g.drawImage(crop, x, 30, null);
            g.setColor(Color.WHITE);
        }
        g.dispose();
        ImageIO.write(sheet, "png", new File(a[1]));
        BufferedImage full = ImageIO.read(new File(dir + "/frame-00038.png"));
        ImageIO.write(up3(full, 0, 0, full.getWidth(), full.getHeight(), 3), "png", new File(a[2]));
        System.out.println("sheet " + sheet.getWidth() + "x" + sheet.getHeight() + ", full x3 " + full.getWidth() * 3 + "x" + full.getHeight() * 3);
    }
}

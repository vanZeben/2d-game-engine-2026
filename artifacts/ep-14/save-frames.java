import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.io.File;
import javax.imageio.ImageIO;

public class Save {
    static BufferedImage img(int t) throws Exception { return ImageIO.read(new File(String.format("frames/frame-%05d.png", t - 1))); }
    static BufferedImage scale(BufferedImage src, int x0, int y0, int w, int h, int k) {
        BufferedImage out = new BufferedImage(w * k, h * k, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(src, 0, 0, w * k, h * k, x0, y0, x0 + w, y0 + h, null);
        g.dispose();
        return out;
    }
    public static void main(String[] a) throws Exception {
        String out = "/tmp/ge2026/artifacts/ep-14/";
        new File(out).mkdirs();
        BufferedImage dry = img(1), swim = img(40), f1 = img(15), f2 = img(30);
        ImageIO.write(dry, "png", new File(out + "dry-tick01.png"));
        ImageIO.write(swim, "png", new File(out + "swim-tick40.png"));
        ImageIO.write(f1, "png", new File(out + "water-frame1-tick15.png"));
        ImageIO.write(f2, "png", new File(out + "water-frame2-tick30.png"));
        ImageIO.write(scale(swim, 0, 0, 160, 120, 3), "png", new File(out + "swim-tick40-x3.png"));
        // sprite box 72..87 x 52..67, dry (left) vs swimming (right), x4, side by side with a gap
        BufferedImage cd = scale(dry, 72, 52, 16, 16, 4), cs = scale(swim, 72, 52, 16, 16, 4);
        BufferedImage pair = new BufferedImage(cd.getWidth() * 2 + 16, cd.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = pair.createGraphics();
        g.setColor(java.awt.Color.MAGENTA); g.fillRect(0, 0, pair.getWidth(), pair.getHeight());
        g.drawImage(cd, 0, 0, null); g.drawImage(cs, cd.getWidth() + 16, 0, null); g.dispose();
        ImageIO.write(pair, "png", new File(out + "sprite-box-dry-vs-swim-x4.png"));
    }
}

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
public class ViewFrame {
  public static void main(String[] a) throws Exception {
    BufferedImage img = ImageIO.read(new File(a[0]));
    System.out.println("size=" + img.getWidth() + "x" + img.getHeight());
    int s = Integer.parseInt(a[2]);
    BufferedImage out = new BufferedImage(img.getWidth()*s, img.getHeight()*s, BufferedImage.TYPE_INT_ARGB);
    for (int y=0;y<out.getHeight();y++) for(int x=0;x<out.getWidth();x++) out.setRGB(x,y,img.getRGB(x/s,y/s));
    ImageIO.write(out, "png", new File(a[1]));
  }
}

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
public class Diff {
  // bounding box of pixels that differ from the dominant grass colour
  public static void main(String[] a) throws Exception {
    for (String f : a) {
      BufferedImage img = ImageIO.read(new File(f));
      int bg = img.getRGB(0,0); int minx=999,miny=999,maxx=-1,maxy=-1,n=0;
      for (int y=0;y<img.getHeight();y++) for (int x=0;x<img.getWidth();x++) if (img.getRGB(x,y)!=bg){n++; minx=Math.min(minx,x);maxx=Math.max(maxx,x);miny=Math.min(miny,y);maxy=Math.max(maxy,y);}
      System.out.println(f+" nonbg="+n+" bbox=["+minx+","+miny+"]-["+maxx+","+maxy+"]");
    }
  }
}

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;
public class Analyze {
  static BufferedImage img(String p) throws Exception { return ImageIO.read(new File(p)); }
  static int[] px(BufferedImage b) { int[] a = new int[b.getWidth()*b.getHeight()]; b.getRGB(0,0,b.getWidth(),b.getHeight(),a,0,b.getWidth()); for (int i=0;i<a.length;i++) a[i]&=0xFFFFFF; return a; }
  public static void main(String[] a) throws Exception {
    String W = "/tmp/ge2026-work/ep-08/";
    BufferedImage f00 = img(W+"f00/frame-00000.png");
    int w = f00.getWidth(), h = f00.getHeight();
    System.out.println("frame size " + w + "x" + h + " type " + f00.getType());
    // 1. shift check: f35(x,y) == f00(x+3,y+5)
    BufferedImage f35 = img(W+"f35/frame-00000.png");
    int bad=0, n=0;
    for (int y=0;y<h-5;y++) for (int x=0;x<w-3;x++) { n++; if ((f35.getRGB(x,y)&0xFFFFFF)!=(f00.getRGB(x+3,y+5)&0xFFFFFF)) bad++; }
    System.out.println("shift(3,5) overlap px=" + n + " mismatches=" + bad);
    // frames identical within a run
    System.out.println("f00 frame1==frame0: " + Arrays.equals(px(f00), px(img(W+"f00/frame-00001.png"))));
    // 2. repeat / seed
    System.out.println("same seed twice identical: " + Arrays.equals(px(f00), px(img(W+"frep/frame-00000.png"))));
    BufferedImage s2 = img(W+"fseed2/frame-00000.png");
    int diff=0; int[] p0=px(f00), p2=px(s2); for (int i=0;i<p0.length;i++) if (p0[i]!=p2[i]) diff++;
    System.out.println("seed2 vs seed1 differing px=" + diff + " of " + p0.length);
    // 3. edge: offset 200,200 and fully outside
    int[] pe = px(img(W+"fedge/frame-00000.png"));
    Map<Integer,Integer> ce = new TreeMap<>(); for (int c: pe) ce.merge(c,1,Integer::sum);
    System.out.println("offset 200,200 colours: " + hex(ce));
    int[] pv = px(img(W+"fvoid/frame-00000.png"));
    Map<Integer,Integer> cv = new TreeMap<>(); for (int c: pv) cv.merge(c,1,Integer::sum);
    System.out.println("offset 1000,1000 colours: " + hex(cv));
    int[] pm = px(img(W+"fmin/frame-00000.png"));
    Map<Integer,Integer> cm = new TreeMap<>(); for (int c: pm) cm.merge(c,1,Integer::sum);
    System.out.println("offset -100,-100 colours: " + hex(cm));
    // 4. colour proportions in f00 (level 64x64 seed1 at 0,0 covers screen fully?)
    Map<Integer,Integer> c0 = new TreeMap<>(); for (int c: p0) c0.merge(c,1,Integer::sum);
    System.out.println("seed1 @0,0 colours (" + p0.length + " px): " + hex(c0));
    // 5. x3 upscale
    BufferedImage up = new BufferedImage(w*3,h*3,BufferedImage.TYPE_INT_RGB);
    for (int y=0;y<h*3;y++) for (int x=0;x<w*3;x++) up.setRGB(x,y,f00.getRGB(x/3,y/3));
    ImageIO.write(up,"png",new File(W+"level_seed1_0_0_x3.png"));
    ImageIO.write(f00,"png",new File(W+"level_seed1_0_0.png"));
    // print a coarse ASCII map: sample each 8px cell centre
    String[] names = {"?","void","stone","grass","water"};
    int cw=w/8, ch=h/8;
    for (int cy=0; cy<ch; cy++) { StringBuilder sb=new StringBuilder(); for (int cx=0; cx<cw; cx++) { int c=f00.getRGB(cx*8+4,cy*8+4)&0xFFFFFF; sb.append(c==0?' ':c==0x666666?'#':(c==0x339900||c==0x33CC33)?'g':(c==0x0033CC?'~':'?')); } System.out.println(sb); }
  }
  static String hex(Map<Integer,Integer> m) { StringBuilder sb=new StringBuilder(); for (var e: m.entrySet()) sb.append(String.format("%06X:%d ", e.getKey(), e.getValue())); return sb.toString(); }
}

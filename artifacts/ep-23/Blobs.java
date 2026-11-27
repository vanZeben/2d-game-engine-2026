import javax.imageio.ImageIO; import java.awt.image.BufferedImage; import java.io.*; import java.util.*;
// Per frame: non-grass pixels (grass = colour of frame 0 pixel 0), 8-connected blobs, obs centre box excluded.
public class Blobs {
  public static void main(String[] a) throws Exception {
    String dir=a[0]; int from=Integer.parseInt(a[1]), to=Integer.parseInt(a[2]);
    int W=160,H=120;
    BufferedImage f0=ImageIO.read(new File(String.format("%s/frame-00000.png",dir)));
    int g=f0.getRGB(0,0)&0xffffff;
    System.out.println("grass="+String.format("%06x",g));
    PrintWriter out=new PrintWriter(new FileWriter(a[3]));
    out.println("frame tick nblobs blobs(x,y wxh px) | colours");
    for (int f=from; f<=to; f++) {
      BufferedImage im=ImageIO.read(new File(String.format("%s/frame-%05d.png",dir,f)));
      boolean[][] on=new boolean[H][W]; Set<Integer> cols=new TreeSet<>();
      for (int y=0;y<H;y++) for (int x=0;x<W;x++) { int c=im.getRGB(x,y)&0xffffff; if (c!=g && c!=0x33cc33) { on[y][x]=true; cols.add(c);} }
      boolean[][] seen=new boolean[H][W]; List<String> bl=new ArrayList<>(); int n=0;
      for (int y=0;y<H;y++) for (int x=0;x<W;x++) if (on[y][x] && !seen[y][x]) {
        ArrayDeque<int[]> q=new ArrayDeque<>(); q.add(new int[]{x,y}); seen[y][x]=true;
        int x0=x,x1=x,y0=y,y1=y,px=0;
        while(!q.isEmpty()){ int[] p=q.poll(); px++; x0=Math.min(x0,p[0]);x1=Math.max(x1,p[0]);y0=Math.min(y0,p[1]);y1=Math.max(y1,p[1]);
          for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){int nx=p[0]+dx,ny=p[1]+dy; if(nx>=0&&ny>=0&&nx<W&&ny<H&&on[ny][nx]&&!seen[ny][nx]){seen[ny][nx]=true;q.add(new int[]{nx,ny});}}}
        if (x0>=71 && x1<=88 && y0>=51 && y1<=68) continue; // obs's own sprite (centre box)
        n++; bl.add(String.format("(%d,%d %dx%d %dpx)",x0,y0,x1-x0+1,y1-y0+1,px));
      }
      StringBuilder cs=new StringBuilder(); for(int c:cols) cs.append(String.format("%06x ",c));
      out.println(f+" "+(f+1)+" "+n+" "+String.join(" ",bl)+" | "+cs.toString().trim());
    }
    out.close();
  } }

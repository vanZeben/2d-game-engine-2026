import ge.entity.Player;
import ge.gfx.Palette;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import javax.imageio.ImageIO;

public class Pose {
    static BufferedImage sheet;
    static int idx(String dir, int frame, int dx, int dy) {
        int col; boolean mirror;
        switch (dir) {
            case "down": col = 0; mirror = frame == 1; break;
            case "up": col = 2; mirror = frame == 1; break;
            case "right": col = frame == 0 ? 4 : 6; mirror = false; break;
            default: col = frame == 0 ? 4 : 6; mirror = true; break;
        }
        int sx = col * 8 + (mirror ? 15 - dx : dx);
        return (sheet.getRGB(sx, 28 * 8 + dy) & 0xff) >> 6;
    }
    public static void main(String[] a) throws Exception {
        sheet = ImageIO.read(new File(a[2]));
        List<String> lines = new ArrayList<>();
        for (String l : Files.readAllLines(Paths.get(a[0]))) if (l.startsWith("tick=") && l.contains(" x=")) lines.add(l);
        Pattern p = Pattern.compile("dir=(\\w+) frame=(\\d)");
        Map<String, List<Integer>> poses = new TreeMap<>();
        Map<String, Integer> boxHashByPose = new TreeMap<>();
        int bad = 0, checked = 0;
        for (int t = 1; t <= lines.size(); t++) {
            Matcher m = p.matcher(lines.get(t - 1));
            if (!m.find()) throw new RuntimeException(lines.get(t - 1));
            String d = m.group(1); int f = Integer.parseInt(m.group(2));
            BufferedImage img = ImageIO.read(new File(a[1] + String.format("/frame-%05d.png", t - 1)));
            int opaque = 0, mism = 0;
            int[] box = new int[256];
            for (int dy = 0; dy < 16; dy++) for (int dx = 0; dx < 16; dx++) {
                int rgb = img.getRGB(72 + dx, 52 + dy) & 0xffffff;
                box[dy * 16 + dx] = rgb;
                int c = Player.COLOURS[idx(d, f, dx, dy)];
                if (c != Palette.TRANSPARENT) { opaque++; if ((Palette.rgb(c) & 0xffffff) != rgb) mism++; }
            }
            checked++;
            if (mism > 0) { bad++; System.out.println("tick " + t + " " + d + " f" + f + " mismatch " + mism + "/" + opaque); }
            String key = d + " f" + f + (d.equals("up") || d.equals("down") ? "" : "");
            poses.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
            boxHashByPose.putIfAbsent(key, Arrays.hashCode(box));
            if (t == 1) System.out.println("standing (tick1) box hash " + Arrays.hashCode(box));
        }
        System.out.println("ticks checked=" + checked + " ticks with mismatching opaque px=" + bad);
        for (var e : poses.entrySet()) System.out.println(e.getKey() + " : " + e.getValue().size() + " ticks, first " + e.getValue().get(0) + ", boxHash " + boxHashByPose.get(e.getKey()));
    }
}

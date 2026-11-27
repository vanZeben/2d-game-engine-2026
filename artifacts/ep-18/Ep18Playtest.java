import ge.Game;
import ge.Scene;
import ge.Screen;
import ge.World;
import ge.entity.Mob;
import ge.net.ClientGame;
import ge.net.RemotePlayer;
import ge.net.Server;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/** Ep18 playtest harness (playtester, not part of the repo). Usage: Ep18Playtest OUTDIR JAR */
public class Ep18Playtest {
    static int fails = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) fails++;
    }

    static List<RemotePlayer> remotes(ClientGame g) {
        List<RemotePlayer> r = new ArrayList<>();
        for (Mob m : g.world().entities()) if (m instanceof RemotePlayer rp) r.add(rp);
        return r;
    }

    static void save(Screen s, File f) throws IOException {
        ImageIO.write(s.toImage(), "png", f);
    }

    static void saveX3(Screen s, File f) throws IOException {
        BufferedImage src = s.toImage();
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w * 3, h * 3, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h * 3; y++)
            for (int x = 0; x < w * 3; x++) out.setRGB(x, y, src.getRGB(x / 3, y / 3));
        ImageIO.write(out, "png", f);
    }

    /** 16x16 box at (x0,y0) of a screen. */
    static int[] box(Screen s, int x0, int y0) {
        int[] out = new int[256];
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++) out[y * 16 + x] = s.pixels[(y0 + y) * s.width + x0 + x];
        return out;
    }

    /** Reference: a fresh world with the player at (x,y), rendered with its own camera (player centred). */
    static int[] refBox(int x, int y) {
        World w = Scene.parse("game:grass:64,64@" + x + "," + y).newWorld();
        Screen s = new Screen(Game.WIDTH, Game.HEIGHT);
        s.clear(0);
        w.render(s, Scene.sheet());
        return box(s, Game.WIDTH / 2 - 8, Game.HEIGHT / 2 - 8);
    }

    static int changedOutside(Screen a, Screen b, int[][] boxes) {
        int n = 0;
        for (int y = 0; y < a.height; y++)
            for (int x = 0; x < a.width; x++) {
                if (a.pixels[y * a.width + x] == b.pixels[y * a.width + x]) continue;
                boolean inside = false;
                for (int[] bx : boxes) if (x >= bx[0] && x < bx[0] + 16 && y >= bx[1] && y < bx[1] + 16) inside = true;
                if (!inside) n++;
            }
        return n;
    }

    static int changed(Screen a, Screen b, int x0, int y0) {
        int n = 0;
        for (int y = y0; y < y0 + 16; y++)
            for (int x = x0; x < x0 + 16; x++) if (a.pixels[y * a.width + x] != b.pixels[y * a.width + x]) n++;
        return n;
    }

    public static void main(String[] args) throws Exception {
        File out = new File(args[0]);
        String jar = args[1];
        out.mkdirs();
        Server server = Server.start(0);
        int port = server.port();
        System.out.println("server port=" + port);
        ClientGame a = null, b = null, c = null;
        try {
            a = ClientGame.connect("game:grass:64,64@200,200", "127.0.0.1", port, "alice");
            check(a.client().awaitJoined(3000), "alice acked (spawn 200,200)");
            for (int i = 0; i < 20; i++) a.tick();
            Screen alone = new Screen(Game.WIDTH, Game.HEIGHT);
            a.render(alone);
            save(alone, new File(out, "a-alone.png"));
            check(remotes(a).isEmpty(), "alice alone: no remote players");

            b = ClientGame.connect("game:grass:64,64@230,215", "127.0.0.1", port, "bob");
            check(b.client().awaitJoined(3000), "bob acked (spawn 230,215)");
            c = ClientGame.connect("game:grass:64,64@180,190", "127.0.0.1", port, "carol");
            check(c.client().awaitJoined(3000), "carol acked (spawn 180,190)");

            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (remotes(a).size() < 2 && System.nanoTime() < end) {
                a.tick();
                Thread.sleep(5);
            }
            for (int i = 0; i < 10; i++) { a.tick(); Thread.sleep(5); }
            List<RemotePlayer> rs = remotes(a);
            check(rs.size() == 2, "alice sees 2 remote players (got " + rs.size() + ")");

            System.out.println("server.playerNames=" + new java.util.TreeSet<>(server.playerNames()));
            System.out.println("server.clientCount=" + server.clientCount());
            check(server.playerNames().equals(java.util.Set.of("alice", "bob", "carol")), "server lists alice, bob, carol");

            World w = a.world();
            System.out.println("alice world player=(" + w.player().x() + "," + w.player().y() + ") camera=(" + w.cameraX() + "," + w.cameraY() + ")");
            int[][] boxes = new int[2][];
            int k = 0;
            for (RemotePlayer rp : rs) {
                int sx = rp.x() - w.cameraX(), sy = rp.y() - w.cameraY();
                System.out.println("remote name=" + rp.name() + " pos=(" + rp.x() + "," + rp.y() + ") dir=" + rp.dir()
                        + " walkFrame=" + rp.walkFrame() + " screen=(" + sx + "," + sy + ")");
                boxes[k++] = new int[] {sx, sy};
            }
            RemotePlayer bobR = rs.stream().filter(r -> r.name().equals("bob")).findFirst().orElse(null);
            RemotePlayer carolR = rs.stream().filter(r -> r.name().equals("carol")).findFirst().orElse(null);
            check(bobR != null && bobR.x() == 230 && bobR.y() == 215, "bob at 230,215");
            check(carolR != null && carolR.x() == 180 && carolR.y() == 190, "carol at 180,190");

            Screen two = new Screen(Game.WIDTH, Game.HEIGHT);
            a.render(two);
            save(two, new File(out, "a-two-remotes.png"));
            saveX3(two, new File(out, "a-two-remotes-x3.png"));

            // sprite pixels match a reference render of the same sprite at its world position
            int[] wantBob = refBox(230, 215), wantCarol = refBox(180, 190);
            int bsx = 230 - w.cameraX(), bsy = 215 - w.cameraY();
            int csx = 180 - w.cameraX(), csy = 190 - w.cameraY();
            System.out.println("expected bob sprite box at screen (" + bsx + "," + bsy + "), carol at (" + csx + "," + csy + ")");
            check(java.util.Arrays.equals(wantBob, box(two, bsx, bsy)), "bob's sprite is at (230,215) - camera");
            check(java.util.Arrays.equals(wantCarol, box(two, csx, csy)), "carol's sprite is at (180,190) - camera");
            check(!java.util.Arrays.equals(box(alone, bsx, bsy), box(two, bsx, bsy)), "bob's box changed from the alone frame");
            int diffB = changed(alone, two, bsx, bsy), diffC = changed(alone, two, csx, csy);
            int outside = changedOutside(alone, two, new int[][] {{bsx, bsy}, {csx, csy}});
            System.out.println("changed pixels: in bob box=" + diffB + " in carol box=" + diffC + " outside both=" + outside);
            check(outside == 0, "no pixels changed outside the two sprite boxes");
            check(java.util.Arrays.equals(box(alone, Game.WIDTH / 2 - 8, Game.HEIGHT / 2 - 8),
                    box(two, Game.WIDTH / 2 - 8, Game.HEIGHT / 2 - 8)), "alice's own sprite unchanged at centre");

            // jar client named dave, against this server
            String java = System.getProperty("java.home") + "/bin/java";
            ProcessBuilder pb = new ProcessBuilder(java, "-Djava.awt.headless=true", "-jar", jar, "--headless", "--ticks", "30",
                    "--client", "--connect", "127.0.0.1:" + port, "--name", "dave").redirectErrorStream(true);
            Process p = pb.start();
            String jarOut = new String(p.getInputStream().readAllBytes());
            boolean exited = p.waitFor(40, TimeUnit.SECONDS);
            System.out.println("jar client exit=" + (exited ? p.exitValue() : "TIMEOUT") + " output:\n" + jarOut.trim());
            check(exited && p.exitValue() == 0, "jar client exits 0");
            check(jarOut.contains("client joined server=127.0.0.1:" + port), "jar client reports joined");
            Thread.sleep(200);
            check(server.playerNames().contains("dave"), "server lists dave: " + new java.util.TreeSet<>(server.playerNames()));
            System.out.println("server.playerNames after dave=" + new java.util.TreeSet<>(server.playerNames()));
            end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (remotes(a).size() < 3 && System.nanoTime() < end) { a.tick(); Thread.sleep(5); }
            System.out.println("alice remote players after dave=" + remotes(a).size() + " " + remotes(a).stream().map(RemotePlayer::name).sorted().toList());
        } finally {
            for (ClientGame g : new ClientGame[] {a, b, c}) if (g != null) g.close();
            server.close();
        }
        System.out.println(fails == 0 ? "ALL CHECKS PASSED" : "FAILURES: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}

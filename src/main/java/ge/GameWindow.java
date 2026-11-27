package ge;

import java.awt.Canvas;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.image.BufferStrategy;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;

/** The real-time front end: a JFrame with a canvas, driven by a fixed 60 tick/s loop. */
public class GameWindow extends Canvas implements Runnable {
    private final Game game;
    private final Screen screen = new Screen(Game.WIDTH, Game.HEIGHT);
    private final BufferedImage image = new BufferedImage(Game.WIDTH, Game.HEIGHT, BufferedImage.TYPE_INT_RGB);
    private final int[] imagePixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
    private volatile boolean running;
    private Thread thread;

    public GameWindow(Game game) {
        this.game = game;
        Dimension size = new Dimension(Game.WIDTH * Game.SCALE, Game.HEIGHT * Game.SCALE);
        setPreferredSize(size);
        setMinimumSize(size);
        setMaximumSize(size);
    }

    /** Creates and shows the 480x360 window, then starts the loop. */
    public static void open(Game game) {
        GameWindow canvas = new GameWindow(game);
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame(Game.NAME);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setLayout(new BorderLayout());
            frame.add(canvas, BorderLayout.CENTER);
            frame.pack();
            frame.setResizable(false);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            canvas.start();
        });
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this, "game-loop");
        thread.start();
    }

    public synchronized void stop() {
        running = false;
    }

    @Override
    public void run() {
        FixedStep step = new FixedStep();
        long last = System.nanoTime();
        long fpsTimer = System.currentTimeMillis();
        int ticks = 0, frames = 0;
        while (running) {
            long now = System.nanoTime();
            int due = step.advance(now - last);
            last = now;
            for (int i = 0; i < due; i++) {
                game.tick();
                ticks++;
            }
            render();
            frames++;
            if (System.currentTimeMillis() - fpsTimer >= 1000) {
                fpsTimer += 1000;
                System.out.println("ticks=" + ticks + " frames=" + frames);
                ticks = 0;
                frames = 0;
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void render() {
        BufferStrategy bs = getBufferStrategy();
        if (bs == null) {
            createBufferStrategy(3);
            return;
        }
        game.render(screen);
        System.arraycopy(screen.pixels, 0, imagePixels, 0, imagePixels.length);
        Graphics g = bs.getDrawGraphics();
        g.drawImage(image, 0, 0, getWidth(), getHeight(), null);
        g.dispose();
        bs.show();
    }
}

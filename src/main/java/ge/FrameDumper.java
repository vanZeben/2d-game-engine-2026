package ge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Writes screen frames as frame-00000.png, frame-00001.png, ... (unscaled buffer size). */
public class FrameDumper {
    private final Path dir;
    private int next;

    public FrameDumper(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void dump(Screen screen) {
        Path file = dir.resolve(String.format("frame-%05d.png", next++));
        try {
            ImageIO.write(screen.toImage(), "png", file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public int frames() {
        return next;
    }
}

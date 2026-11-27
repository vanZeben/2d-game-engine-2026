package ge.input;

import java.awt.event.KeyEvent;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A parsed headless input script (format: docs/script-format.md). Parsing checks the whole file up front;
 * {@link #player(InputHandler)} then replays it onto an {@link InputHandler} tick by tick.
 */
public final class InputScript {
    /** One scripted key event. */
    public record Event(int tick, boolean press, int keyCode) {}

    private final List<Event> events;

    private InputScript(List<Event> events) {
        this.events = List.copyOf(events);
    }

    public static InputScript empty() {
        return new InputScript(List.of());
    }

    public List<Event> events() {
        return events;
    }

    /** @throws IllegalArgumentException with a message naming the file (and line) if it is unreadable or malformed */
    public static InputScript load(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            throw new IllegalArgumentException("cannot read script " + file + ": file not found");
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("cannot read script " + file + ": not valid UTF-8");
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("cannot read script " + file + ": " + e.getMessage());
        }
        return parse(lines, file.toString());
    }

    /** Parses script text; {@code name} is used in error messages. */
    public static InputScript parse(List<String> lines, String name) {
        List<Event> events = new ArrayList<>();
        int lastTick = 1;
        for (int i = 0; i < lines.size(); i++) {
            int lineNo = i + 1;
            String line = lines.get(i);
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            line = line.strip();
            if (line.isEmpty()) continue;
            String[] f = line.split("[ \t]+");
            if (f.length != 3) {
                throw bad(name, lineNo, "expected '<tick> press|release <KEY>', got " + f.length + " field(s)");
            }
            if (!f[0].matches("[0-9]{1,9}")) throw bad(name, lineNo, "bad tick '" + f[0] + "'");
            int tick = Integer.parseInt(f[0]);
            if (tick < 1) throw bad(name, lineNo, "tick must be >= 1, got " + tick);
            if (tick < lastTick) throw bad(name, lineNo, "tick " + tick + " goes backwards (previous " + lastTick + ")");
            boolean press;
            switch (f[1]) {
                case "press" -> press = true;
                case "release" -> press = false;
                default -> throw bad(name, lineNo, "unknown verb '" + f[1] + "' (use press or release)");
            }
            Integer code = keyCode(f[2]);
            if (code == null) throw bad(name, lineNo, "unknown key '" + f[2] + "'");
            lastTick = tick;
            events.add(new Event(tick, press, code));
        }
        return new InputScript(events);
    }

    private static IllegalArgumentException bad(String name, int line, String why) {
        return new IllegalArgumentException(name + ": line " + line + ": " + why);
    }

    /** Resolves a name like UP or F1 to its KeyEvent.VK_* value, or null if there is no such constant. */
    static Integer keyCode(String key) {
        if (!key.matches("[A-Z0-9_]+")) return null;
        try {
            Field f = KeyEvent.class.getField("VK_" + key);
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) return null;
            return f.getInt(null);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    public Player player(InputHandler target) {
        return new Player(events, target);
    }

    /** Feeds events to a handler: {@link #advanceTo(long)} applies everything due at or before a tick, in file order. */
    public static final class Player {
        private final List<Event> events;
        private final InputHandler target;
        private int next;

        private Player(List<Event> events, InputHandler target) {
            this.events = events;
            this.target = target;
        }

        public void advanceTo(long tick) {
            while (next < events.size() && events.get(next).tick() <= tick) {
                Event e = events.get(next++);
                if (e.press()) target.press(e.keyCode()); else target.release(e.keyCode());
            }
        }
    }
}

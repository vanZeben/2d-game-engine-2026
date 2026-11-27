package ge.net;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Game packets: ASCII text, a 2-character ID, then comma-separated fields, one datagram each
 * ({@code "00,NAME,X,Y"}). IDs are digits, so they can never be read as "ping", "pong" or a {@code "GE1 "}
 * connection message. IDs 01 and 02 are reserved for disconnect and move (later episodes).
 *
 * <p>{@link #parse} is the only way bytes become packets: anything malformed (null, empty, oversized, non-ASCII,
 * unknown ID, wrong field count, a non-canonical or out-of-range integer, a bad name) gives {@code Optional.empty()},
 * never an exception. Packets are immutable, so they can be handed between threads freely.
 */
public sealed interface GamePacket {
    /** Longest packet {@link #parse} looks at: ID, 16-char name and two 11-char ints fit with room to spare. */
    int MAX_LENGTH = 64;

    /** Longest valid player name. */
    int MAX_NAME = 16;

    String ID_LOGIN = "00";
    String ID_PLAYER_JOINED = "03";

    /** The two-character packet ID. */
    String id();

    /** The wire form (ASCII). */
    byte[] toBytes();

    /** Client to server: log in as {@code name}, spawning at world pixel (x, y) (the sprite's top-left corner). */
    record Login(String name, int x, int y) implements GamePacket {
        public Login {
            requireName(name);
        }

        @Override
        public String id() {
            return ID_LOGIN;
        }

        @Override
        public byte[] toBytes() {
            return encode(ID_LOGIN, name, x, y);
        }
    }

    /** Server to client: a player called {@code name} has joined, at world pixel (x, y). */
    record PlayerJoined(String name, int x, int y) implements GamePacket {
        public PlayerJoined {
            requireName(name);
        }

        @Override
        public String id() {
            return ID_PLAYER_JOINED;
        }

        @Override
        public byte[] toBytes() {
            return encode(ID_PLAYER_JOINED, name, x, y);
        }
    }

    /** A valid name is 1 to {@value #MAX_NAME} characters of {@code [A-Za-z0-9_]}. */
    static boolean isValidName(String name) {
        return name != null && Fields.NAME.matcher(name).matches();
    }

    private static void requireName(String name) {
        if (!isValidName(name)) throw new IllegalArgumentException("bad player name: " + name);
    }

    private static byte[] encode(String id, String name, int x, int y) {
        return (id + "," + name + "," + x + "," + y).getBytes(StandardCharsets.US_ASCII);
    }

    /** Parses one datagram's payload; empty for anything that is not exactly one valid packet. Never throws. */
    static Optional<GamePacket> parse(byte[] data) {
        if (data == null || data.length < 2 || data.length > MAX_LENGTH) return Optional.empty();
        for (byte b : data) {
            if (b < 0x20 || b > 0x7E) return Optional.empty(); // printable ASCII only
        }
        String[] f = new String(data, StandardCharsets.US_ASCII).split(",", -1); // -1 keeps empty trailing fields
        try {
            switch (f[0]) {
                case ID_LOGIN -> {
                    if (f.length != 4 || !isValidName(f[1])) return Optional.empty();
                    return Optional.of(new Login(f[1], Fields.integer(f[2]), Fields.integer(f[3])));
                }
                case ID_PLAYER_JOINED -> {
                    if (f.length != 4 || !isValidName(f[1])) return Optional.empty();
                    return Optional.of(new PlayerJoined(f[1], Fields.integer(f[2]), Fields.integer(f[3])));
                }
                default -> {
                    return Optional.empty();
                }
            }
        } catch (IllegalArgumentException e) {
            return Optional.empty(); // a bad number (NumberFormatException is one)
        }
    }

    /** Field syntax shared by the packets (an interface cannot hold private constants). */
    final class Fields {
        static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1," + MAX_NAME + "}");
        /** Canonical decimal: what {@code Integer.toString} writes, so parse(toBytes(p)) round-trips byte for byte. */
        private static final Pattern INTEGER = Pattern.compile("0|-?[1-9][0-9]{0,9}");

        private Fields() {}

        static int integer(String s) {
            if (!INTEGER.matcher(s).matches()) throw new IllegalArgumentException("bad integer: " + s);
            return Integer.parseInt(s); // out of int range throws NumberFormatException
        }
    }
}

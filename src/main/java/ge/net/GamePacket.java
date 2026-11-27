package ge.net;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Game packets: ASCII text, a 2-character ID, then comma-separated fields, one datagram each
 * ({@code "00,NAME,X,Y"}). IDs are digits, so they can never be read as "ping", "pong" or a {@code "GE1 "}
 * connection message. ID 02 is a move: a player's full state, stamped with a per-sender sequence number.
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
    String ID_DISCONNECT = "01";
    String ID_MOVE = "02";
    String ID_PLAYER_JOINED = "03";
    String ID_PLAYER_LEFT = "04";
    String ID_LOGIN_REJECTED = "05";

    /** The {@link LoginRejected} reason for a login whose name another address already holds. */
    String REASON_NAME_TAKEN = "name-taken";

    /** Longest valid rejection reason. */
    int MAX_REASON = 20;

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

    /** Client to server: this client is leaving. No fields: the wire form is exactly {@code "01"}. */
    record Disconnect() implements GamePacket {
        @Override
        public String id() {
            return ID_DISCONNECT;
        }

        @Override
        public byte[] toBytes() {
            return ID_DISCONNECT.getBytes(StandardCharsets.US_ASCII);
        }
    }

    /** Server to client: the player called {@code name} has left. */
    record PlayerLeft(String name) implements GamePacket {
        public PlayerLeft {
            requireName(name);
        }

        @Override
        public String id() {
            return ID_PLAYER_LEFT;
        }

        @Override
        public byte[] toBytes() {
            return (ID_PLAYER_LEFT + "," + name).getBytes(StandardCharsets.US_ASCII);
        }
    }

    /**
     * Server to client: the login was refused, for {@code reason} (1 to {@value #MAX_REASON} characters of
     * {@code [a-z-]}, e.g. {@link #REASON_NAME_TAKEN}). The client stops resending its login. Wire form
     * {@code "05,REASON"}.
     */
    record LoginRejected(String reason) implements GamePacket {
        public LoginRejected {
            if (reason == null || !Fields.REASON.matcher(reason).matches()) {
                throw new IllegalArgumentException("bad reject reason: " + reason);
            }
        }

        @Override
        public String id() {
            return ID_LOGIN_REJECTED;
        }

        @Override
        public byte[] toBytes() {
            return (ID_LOGIN_REJECTED + "," + reason).getBytes(StandardCharsets.US_ASCII);
        }
    }

    /**
     * A player's full state: position (world pixel of the sprite's top-left corner), facing ({@code dir} = the
     * ordinal of {@code Mob.Dir}, 0..3), walk frame (0 or 1) and a sequence number. Client to server, stamped by the
     * sender with an increasing {@code seq} (from 0); the server relays it to the other players under the sender's
     * registered name with the sender's seq, and receivers apply only a seq higher than the last one they applied
     * (UDP may reorder and duplicate). Wire form {@code "02,NAME,X,Y,DIR,FRAME,SEQ"}.
     */
    record Move(String name, int x, int y, int dir, int walkFrame, int seq) implements GamePacket {
        public Move {
            requireName(name);
            if (dir < 0 || dir > 3) throw new IllegalArgumentException("bad dir: " + dir);
            if (walkFrame < 0 || walkFrame > 1) throw new IllegalArgumentException("bad walk frame: " + walkFrame);
            if (seq < 0) throw new IllegalArgumentException("bad seq: " + seq);
        }

        @Override
        public String id() {
            return ID_MOVE;
        }

        @Override
        public byte[] toBytes() {
            return (ID_MOVE + "," + name + "," + x + "," + y + "," + dir + "," + walkFrame + "," + seq)
                    .getBytes(StandardCharsets.US_ASCII);
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
                case ID_MOVE -> {
                    if (f.length != 7 || !isValidName(f[1])) return Optional.empty();
                    return Optional.of(new Move(f[1], Fields.integer(f[2]), Fields.integer(f[3]), Fields.integer(f[4]),
                            Fields.integer(f[5]), Fields.integer(f[6])));
                }
                case ID_DISCONNECT -> {
                    return f.length == 1 ? Optional.of(new Disconnect()) : Optional.empty();
                }
                case ID_PLAYER_LEFT -> {
                    if (f.length != 2 || !isValidName(f[1])) return Optional.empty();
                    return Optional.of(new PlayerLeft(f[1]));
                }
                case ID_LOGIN_REJECTED -> {
                    if (f.length != 2 || !Fields.REASON.matcher(f[1]).matches()) return Optional.empty();
                    return Optional.of(new LoginRejected(f[1]));
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
        static final Pattern REASON = Pattern.compile("[a-z-]{1," + MAX_REASON + "}");
        /** Canonical decimal: what {@code Integer.toString} writes, so parse(toBytes(p)) round-trips byte for byte. */
        private static final Pattern INTEGER = Pattern.compile("0|-?[1-9][0-9]{0,9}");

        private Fields() {}

        static int integer(String s) {
            if (!INTEGER.matcher(s).matches()) throw new IllegalArgumentException("bad integer: " + s);
            return Integer.parseInt(s); // out of int range throws NumberFormatException
        }
    }
}

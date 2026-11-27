package ge.net;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Wire format shared by client and server. One datagram is one message.
 * Connection-level messages (this class) are text starting with {@link #MAGIC}; later episodes add game packets
 * with their own leading IDs, which never start with the magic, so the two kinds cannot be confused.
 * Anything that is not a well-formed message is dropped by the receiver, never an error.
 */
public final class Protocol {
    /** Prefix of every connection-level message; carries the protocol version. */
    public static final String MAGIC = "GE1 ";
    public static final byte[] JOIN = (MAGIC + "JOIN").getBytes(StandardCharsets.US_ASCII);
    public static final byte[] JOIN_ACK = (MAGIC + "ACK").getBytes(StandardCharsets.US_ASCII);
    /** Largest datagram either side reads; larger ones are truncated and then fail to parse. */
    public static final int MAX_PACKET = 1024;

    private Protocol() {}

    public static boolean isJoin(byte[] data) {
        return Arrays.equals(data, JOIN);
    }

    public static boolean isJoinAck(byte[] data) {
        return Arrays.equals(data, JOIN_ACK);
    }
}

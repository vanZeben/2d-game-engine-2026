import ge.net.Protocol;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class RawProbe {
    static String recv(DatagramSocket s, int ms) throws Exception {
        s.setSoTimeout(ms);
        byte[] buf = new byte[Protocol.MAX_PACKET];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        try {
            s.receive(p);
            return new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII)
                    + " (bytes=" + p.getLength() + ")";
        } catch (SocketTimeoutException e) {
            return null;
        }
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        InetSocketAddress server = new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
        try (DatagramSocket s = new DatagramSocket()) {
            // 1. ping without joining
            s.send(new DatagramPacket(Protocol.PING, Protocol.PING.length, server));
            String r1 = recv(s, 1000);
            System.out.println("unjoined ping reply=" + (r1 == null ? "NONE (timeout 1s)" : r1));

            // 2. join with the raw join bytes, expect ack
            s.send(new DatagramPacket(Protocol.JOIN, Protocol.JOIN.length, server));
            String r2 = recv(s, 1000);
            System.out.println("join reply=" + (r2 == null ? "NONE" : r2));

            // 3. ping after join, expect pong
            s.send(new DatagramPacket(Protocol.PING, Protocol.PING.length, server));
            String r3 = recv(s, 1000);
            System.out.println("joined ping reply=" + (r3 == null ? "NONE (timeout 1s)" : r3));
        }
    }
}

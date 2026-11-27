import java.net.*;
public class Garbage { public static void main(String[] a) throws Exception {
  try (DatagramSocket s = new DatagramSocket()) { int port = Integer.parseInt(a[0]); InetAddress ip = InetAddress.getLoopbackAddress();
    byte[][] bad = { {}, {(byte)0xff}, "ZZ garbage".getBytes(), new byte[1400] };
    for (byte[] b : bad) s.send(new DatagramPacket(b, b.length, ip, port));
    System.out.println("sent " + bad.length + " malformed datagrams to " + port); } } }

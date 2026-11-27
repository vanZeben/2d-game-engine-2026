import java.net.*;
public class UdpSink {
  public static void main(String[] a) throws Exception {
    try (DatagramSocket s = new DatagramSocket(Integer.parseInt(a[0]), InetAddress.getLoopbackAddress())) {
      s.setSoTimeout(4000);
      long start = System.nanoTime(); int n = 0; long first = -1, last = -1; double gapSum = 0; long prev = -1;
      byte[] buf = new byte[1500]; DatagramPacket p = new DatagramPacket(buf, buf.length);
      try { while (true) { s.receive(p); long t = System.nanoTime(); if (n == 0) first = t; else gapSum += (t - prev); prev = t; n++; last = t; if (n <= 3) System.out.println("pkt " + n + " len=" + p.getLength() + " hex=" + bytesHex(buf, p.getLength())); } }
      catch (SocketTimeoutException e) { }
      System.out.println("datagrams=" + n + " span_ms=" + (n > 1 ? (last - first) / 1e6 : 0) + " mean_gap_ms=" + (n > 1 ? gapSum / (n - 1) / 1e6 : 0));
    }
  }
  static String bytesHex(byte[] b, int len) { StringBuilder sb = new StringBuilder(); for (int i = 0; i < Math.min(len, 32); i++) sb.append(String.format("%02x", b[i])); return sb.toString(); }
}

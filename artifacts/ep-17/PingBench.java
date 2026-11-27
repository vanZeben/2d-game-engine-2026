import ge.net.Client;

public class PingBench {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        Client c = Client.connect("127.0.0.1", port);
        boolean joined = c.awaitJoined(3000);
        System.out.println("joined=" + joined);
        int ok = 0;
        long min = Long.MAX_VALUE, max = 0, sum = 0;
        for (int i = 0; i < 50; i++) {
            long t0 = System.nanoTime();
            boolean r = c.ping(1000);
            long us = (System.nanoTime() - t0) / 1000;
            if (r) {
                ok++;
                min = Math.min(min, us);
                max = Math.max(max, us);
                sum += us;
            } else {
                System.out.println("ping " + i + " failed");
            }
        }
        System.out.printf("ping success=%d/50 rtt_us min=%d avg=%d max=%d%n",
                ok, ok == 0 ? 0 : min, ok == 0 ? 0 : sum / ok, max);
        c.close();
    }
}

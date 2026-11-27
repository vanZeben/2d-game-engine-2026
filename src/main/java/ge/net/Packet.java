package ge.net;

import java.net.SocketAddress;

/** One datagram received from {@code from}. The payload is a private copy, safe to hand between threads. */
public record Packet(SocketAddress from, byte[] data) {}

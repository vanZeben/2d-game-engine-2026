# Network design: UDP

## Why UDP

The game sends small, frequent position updates where only the newest matters. TCP would retransmit and block stale positions, so one lost packet stalls everything behind it. UDP (`DatagramSocket`, one datagram per packet) lets the next update supersede a lost one. No TCP class is used.

## Delivery

UDP may lose packets. The game resends what must arrive until acknowledged: the client repeats its login (replacing the GE1 join) every 100 ms until the server acks. A quitting client resends disconnect until acked, stopping after 10 tries. Moves are not acked. Instead the client sends its full state (position, facing, walk frame, seq) at least every second, even idle, so any lost move, including the last, is repaired. The server's periodic full-state stream lists every player, so join and leave notices reach clients unacked.

## Ordering

UDP may reorder packets. Every sender stamps moves with an increasing sequence number; the server keeps the highest per player and drops older ones. The server forwards each seq; clients apply newest-seq-wins to relayed moves. A fresh login resets the stored seq.

## Duplicates

UDP may deliver a packet twice; retransmits add more. The server is idempotent: a repeated login is acked again but creates one player; a repeated disconnect is acked again and otherwise does nothing; a repeated or stale move is dropped by seq.

## Connection state

UDP has no connections, so the server builds its own. Login (carrying the name) creates a player; disconnect removes it. Any valid packet refreshes a timer; a client silent over 10 seconds is removed, so heartbeats keep idle players alive and a crashed client leaves no ghost. Except login, packets from unknown addresses are dropped. A login reusing a name held by another address is rejected. Malformed packets are ignored.

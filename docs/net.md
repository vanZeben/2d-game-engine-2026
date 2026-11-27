# Network design: UDP

## Why UDP

The game sends small, frequent position updates where only the newest one matters. TCP would retransmit and reorder-block stale positions, so one lost packet stalls everything behind it. UDP (`DatagramSocket`, one datagram per packet) lets a lost update be superseded by the next. No TCP socket class is used anywhere.

## Delivery

UDP may lose packets. The game resends what must arrive until it is acknowledged: the client repeats its join every 100 ms until the server acks (already done in `ge.net`), and login and disconnect packets follow the same rule. Movement is not resent: the client sends its full state on every change, so a lost move is repaired by the next one.

## Ordering

UDP may reorder packets. Every sender stamps move packets with an increasing sequence number; the server keeps the highest seen per player and drops anything older.

## Duplicates

UDP may deliver a packet twice, and our retransmits create more. The server handles every packet idempotently: a repeated join is acked again but creates one player (keyed by name), a repeated disconnect finds nobody and does nothing, and a repeated or stale move is dropped by its sequence number.

## Connection state

UDP has no connections, so the server builds its own. Login creates a player, disconnect removes it, and a client silent for 10 seconds is timed out and removed, so a crashed client never leaves a ghost. Any valid packet refreshes that timer. Packets from unknown addresses are dropped, and malformed packets are ignored, never fatal.

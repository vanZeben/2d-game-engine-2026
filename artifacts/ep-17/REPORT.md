# ep-17 playtest report (transcribed by the lead from the playtester's hand-back)

- Clean worktree build (c6f4a50): 212/212 tests passed.
- Jar server on port 0. PingBench (Client.connect, awaitJoined, then ping(1000) 50 times) joined and got 50/50 pongs; rtt min 167 / avg 268 / max 465 µs.
- RawProbe over a raw DatagramSocket: a ping before joining gets no reply. Protocol.JOIN gets `GE1 ACK`, and a ping after that gets `pong` (4 bytes).
- No leftover processes (checked via /proc).

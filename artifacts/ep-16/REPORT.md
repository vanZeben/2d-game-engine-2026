# ep-16 playtest report (transcribed by the lead from the playtester's hand-back)

- Clean worktree build: 205 tests, 0 failed.
- The server's socket is UDP (/proc/net/udp6, inode matches the server's fd). There is no TCP socket on the game port or owned by the server.
- A client joins and exits 0. A dead port gives "join failed" and exit 3 after 3.1 s.
- Join resend measured with a silent UDP sink: 30 datagrams in 2.9 s, mean gap 100.3 ms ("GE1 JOIN"), matching docs/net.md.
- Malformed datagrams (empty, 0xff, garbage, 1400 zero bytes) didn't crash the server; a later client joined.
- Claims for later episodes (sequence numbers, name keying, 10 s timeout) can't be observed yet.

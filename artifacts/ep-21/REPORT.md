# ep-21 playtest report (transcribed by the lead from the playtester's hand-back)

- Clean worktree build: 232 tests, 0 failed. Jar only: a server; observer bob (480 realtime ticks, spawn 200,200); and walker alice about 1 s later (`--script walk.txt`, right 60, down 40, left 30, spawn 180,180).
- Alice's trace moves 1 px/tick: (210,180) at tick 30, (240,180) at 60, (240,220) at 100, then (210,220) from tick 130.
- In bob's frames (camera 128,148), alice's box follows that exact path: f68 (182,180), f116 (236,180), f128 (240,188), f152 (240,212), f164 (236,220), f188 (212,220). It stays at (210,220) until about f299 and then disappears when she disconnects.
- No leftover processes.
- Files: walk.txt, a trace excerpt, 4 of bob's frames (plus x3), the frame scan, and logs.

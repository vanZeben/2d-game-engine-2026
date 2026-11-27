# ep-23 playtest report (transcribed by the lead; two playtester attempts both ran out of budget, see RUNLOG)

- Clean worktree build of b4fdb1d: 242/242 tests.
- Jar session: a server, an observer (obs, 3900 realtime ticks, spawn 256,256), and walkers w1..w3 (3600 ticks, scripts w1-w3.txt: 30-tick right/down/left/up loops, idle after tick 3000), all started together. w2 got SIGTERM at about 20 s; w3 got kill -9 at about 25 s.
- Exit codes: obs 0, w1 0, w2 143 (SIGTERM), w3 137 (SIGKILL). No "Exception" in any client stdout/stderr or the server's stderr.
- Blob analysis of obs's frames (Blobs.java, blobs-all.txt.gz) is unverified because blobs merge and split. Counts per 100 frames: 3 walkers early, 2 from about frame 1300 (roughly 100 frames after the SIGTERM), 1 from about frame 2000 (roughly 500 frames after the kill), 0 from about 3600 when w1 leaves. That matches no ghosts, but blob-to-walker identity wasn't confirmed.
- Not done: the per-facing frame picks. Facing and animation are checked pixel-exact by Ep23Test.
- Files: 5 obs frames, logs, scripts, the checker and its output.

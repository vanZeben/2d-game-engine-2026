# ep-20 playtest report (transcribed by the lead from the playtester's hand-back)

- Clean worktree build: 227 tests green. Jar only: a server, then alice (360 realtime ticks), bob (60 ticks, exits normally), and carol (killed with SIGTERM after about 1 s).
- Alice's frames, diffed against frame 0:
  - bob appears at f58 and is gone at f121, right after his run ends. Removal comes from the disconnect, not a timeout.
  - carol appears at f196 and is gone at f243, 47 frames later, which matches her 47 dumped frames. The SIGTERM shutdown hook disconnected her.
- Server stopped cleanly; no leftover processes.
- Files: alice before, with and after bob (plus x3), with and after carol, the diff log, and all process logs.

# ep-19 playtest report (transcribed by the lead from the playtester's hand-back)

- Clean worktree build (2fd08ff): 222/222 tests.
- Jar only: a server, then alice (300 ticks), bob about 1 s later (240), carol about 1 s after that (120). All `--realtime`, spawns 200,200 / 230,215 / 180,190.
- Changed 16x16 boxes between each client's first and last frame:
  - alice: bob and carol appear.
  - bob: carol appears; alice was already in his frame 0.
  - carol: nothing changes, because alice and bob are already in her frame 0.
- So newcomers see the earlier players straight away. Positions match cam = pos+8-(80,60).
- Files: carol-last.png (plus x3), carol-first-x3.png.

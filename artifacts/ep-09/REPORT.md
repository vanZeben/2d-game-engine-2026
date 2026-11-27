# ep-09 playtest report (transcribed by the lead from the playtester's hand-back)

- Clean worktree build (8fc5b0c): 134 tests passed.
- `--headless --ticks 100 --scene game:gen:64,64,7@200,200 --script walk.txt --trace-player`: right 30, down 30, up-left 20, then still. The trace moves 1 px/tick on the expected axes, e.g. tick 30 = (230,200), tick 60 = (230,230), tick 80 = (210,210). The camera is x+8-80 / y+8-60 on every tick, so the sprite stays at screen (72,52).
- Scrolling: for all 99 frame pairs, every pixel outside the sprite box equals the previous frame shifted by the camera delta (1,481,152 of 1,481,152).
- The sprite box always holds the same 150 opaque sprite pixels; the transparent background shows the level.
- Oddity: the player walks over stone (collision is ep 12).
- Files: first/mid/last frames, a x3 upscale, walk.txt, trace.txt, checks.txt.

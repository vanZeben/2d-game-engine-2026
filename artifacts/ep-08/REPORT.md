# ep-08 playtest report (transcribed by the lead from the playtester's hand-back)

- Clean worktree build of ep-08 (b0e5006): 127 tests, 0 failed.
- `level:gen:64,64,1@3,5` equals `@0,0` shifted by (3,5) on the overlap: 18055 px, 0 mismatches.
- Same seed twice: identical. Seed 2 vs seed 1: 15680/19200 px differ. Frames within one run are identical.
- Offset 1000,1000: all void. Offset -100,-100: void plus the level corner, rendered correctly.
- `level:gen:0,5,...` exits 2 with "level size must be at least 1x1".
- Seed 1 view: about 41% water, 38% grass, 21% stone (a lower-right stone mass). Colours are palette-quantised (for example water 0x0033CC).
- Files: frames, a x3 upscale, checks.txt, and Analyze.java.

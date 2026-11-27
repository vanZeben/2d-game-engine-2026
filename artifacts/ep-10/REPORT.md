# ep-10 playtest report (transcribed by the lead from the playtester's hand-back)

- Clean worktree build of ep-10: 144/144 tests.
- Own checker (per-screen-pixel source lookup from res/sprite_sheet.png) gave 0 mismatches on every frame:
  - grass x1 / x2 / x4 (8, 16 and 32 px squares; every sheet pixel is a solid block)
  - HELLO x2 at (12,40) and HELLO x3
  - player tile (0,28) mirrored x at x3
  - x2 tile clipped at the right edge (4 columns visible)
- `scale=0` exits 2 with usage and writes no frame.
- Files: frames, hello-x2-up3-for-humans.png, and scripts/ (checker and run logs).

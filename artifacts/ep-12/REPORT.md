# ep-12 playtest report (transcribed by the lead from the playtester's hand-back)

- Clean worktree build of ep-12: 168 tests green.
- A ring of 24 stone cells around tiles 10..16 (spec.txt). Script walk.txt goes right 60, down 60, left 80, up 80, then down-right 60, over 350 ticks.
- check.py: across all 350 ticks the feet box (x+4..x+11, y+10..y+15) never overlaps stone.
- The player stops exactly at each edge: right x=116 (box 127 | stone 128), down y=112, left x=84 (box 88 | stone 87), up y=78.
- On the diagonal both axes move until x is blocked, then y slides 2 ticks into the corner. The test's wall-slide cases cover longer slides.
- The walk frame keeps counting while pressed against a wall, as the contract says.
- Files: frames pressed against each wall, a x3 upscale, trace, script and checker.

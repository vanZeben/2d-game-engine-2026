# ep-14 playtest report (transcribed by the lead from the playtester's hand-back)

- Clean worktree build (dcaa497): 191 tests passed.
- `game:grass:30,30@120,112:water=<4x4 pool at 20..23 x 14..17>`: right from tick 5 to 45, then left from 49 to 89, with `--trace-player --trace-swim`. Exit 0, 90 frames.
- Swim: 0 for ticks 1-36, 1 for 37-55, 0 from 56 on. It flips at the water edge in both directions.
- Water animation (pool pixels, sprite box excluded): no change between ticks 1/2, 2/3 and 30/31. Changes between 14/15, 29/30 and 44/45, exactly at the 15-tick frame boundaries.
- Sprite box: dry shows the full character; swimming shows the upper body, with the lower 8 rows showing water and a ripple (sprite-box-dry-vs-swim-x4.png).
- The water PNG level runs (3 ticks, exit 0).

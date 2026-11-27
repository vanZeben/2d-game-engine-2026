# ep-13 playtest report (transcribed by the lead; the playtester ran out of budget after testing, so the lead copied its frames)

- Clean worktree build of ep-13 (59dbcbf): 178 tests green.
- Dumps of small_test_level at (0,0), (-30,-20), (13,7) and water_test_level at (0,0), (200,150), (-17,330). A tile-by-tile check against the PNG matched every visible cell: grass 339900, stone 666666, water 0033CC.
- Unknown colour: a copy of the small level with 4 red cells, loaded by file path. Those cells render as void (000000); the other 96 match.
- Water level walk (spawn 12,8, 200 ticks, right/down/left/up): no stops, since water isn't solid.
- Small level walking right from (12,24): stops at x=20 from tick 20 on (box right edge 31 | stone at 32).
- No x3 upscale was made (budget).

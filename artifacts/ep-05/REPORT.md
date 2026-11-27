# ep-05 playtest report (transcribed by the lead from the playtester's hand-back)

- Clean worktree build of ep-05 (bb8196b): exit 0, 62/62 tests, ~12 s.
- Recoloured grass `tile:2,0@0,0:colours=000,040,151,555`: 64 px, 0 mismatches against the palette rule (level*51).
- Font glyph with slot 0 transparent `tile:0,30@0,0:colours=t,500,050,005`: 64 px, 0 mismatches; the 32 slot-0 pixels were left as the cleared black.
- Stone fill `fill:1,0:colours=025,500,050,005`: all 19,200 px match.
- 600 ticks of the fill scene: 0.26 s wall without dumps, 1.08 s with 600 PNG dumps.
- Limits: transparency over a black clear can't be told apart from drawn black in a dump (the unit tests check it over noise); only tick 1 was pixel-checked.
- Files: recoloured grass, transparent glyph, stone fill, and a x3 nearest-neighbour upscale.

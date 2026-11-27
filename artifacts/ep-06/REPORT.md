# ep-06 playtest report (transcribed by the lead from the playtester's hand-back)

- Clean worktree build of ep-06 (13491bd): exit 0, 89/89 tests.
- Dumped glyph tile (1,30), which is asymmetric in X, Y and XY, at (8,8), grey and palette (`colours=t,025,320,555`), with mirror none/x/y/xy. Also dumped clips at (-3,-2) x and (152,112) xy, and the four player tiles (0..1, 28..29) mirrored x.
- An independent checker (Check.java: sheet blue>>6, own flip code, full-frame compare) MATCHED every frame, 19200/19200 px. Negative controls fail as they should (x vs none: 16 mismatches).
- Malformed mirror values (empty, z, X, XY, yx, none, duplicates, mirrored=) exit 2; `mirror=xy` exits 0.
- Files: x3 montages of none/x/y/xy (grey and palette), two raw frames, plus the checker and montage sources. The clip frames match but weren't saved as images.

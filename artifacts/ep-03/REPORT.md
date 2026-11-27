# ep-03 playtest report (transcribed by the lead from the playtester's hand-back; the playtester's own write of this file was refused by its sub-agent rules)

- 7 headless runs from the jar: 6 `--scene tile:...` runs exit 0; malformed `--scene` exits 2 with a usage error on stderr.
- Each frame's non-black bounding box matches the 8x8 sprite tile at PX,PY; clipping correct at (-3,-5) (5x3 visible) and (156,116) (4x4 visible).
- Grass uses two colours with a consistent level-to-colour map.
- Plain 60-tick run is all black.
- Oddity: level 0 and the background are both black, so level-0 transparency can't be confirmed from dumps (transparency arrives in ep 5).
- Files: 5 raw 160x120 frames plus a x4 upscale of the grass tile.

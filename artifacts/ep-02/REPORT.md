# ep-02 playtest report

Role: playtester. Branch ep-02 at 59fc6fe. Build run in a detached worktree (not /tmp/ge2026). Jar: /tmp/ge2026-work/ep-02/game.jar (21220 bytes).

## Build
- `./build.sh` in worktree: exit 0, 12 JUnit tests successful, 0 failed.

## (a) Launch contract
- Command: `java -Djava.awt.headless=true -jar game.jar --headless --ticks 120 --dump-dir /tmp/ge2026-work/ep-02/frames`
- stdout: `ticks=120 frames=120`, stderr empty, exit 0.
- PNGs written: 120 (frame-00000.png .. frame-00119.png), 160x120 each.
- Frames contain only colour 0x000000 (all black, 1 distinct colour). Matches the ep-01 note that frames are black pre-render. Not a sprite-sheet result; the frames do not show the sheet.

## (b) SpriteSheet probe
- Probe: /tmp/ge2026-work/ep-02/probe/src/SheetProbe.java (scratch, not committed), run against game.jar.
- `SpriteSheet.load("/sprite_sheet.png")`: size 256x256, pixels length 65536.
- Histogram of colour indexes:
  - index 0: 3427
  - index 1: 14738
  - index 2: 111
  - index 3: 47260
  - out of range (<0 or >3): 0
- `index(x,y)` agrees with `pixels()` at every pixel: true.
- Output is deterministic (two runs byte-identical PNG).
- Visualisation: sheet_indexes.png (index*85 grey, 1:1 at 256x256). Shows a light background with a dark grid, a font strip (A-Z, 0-9, punctuation) along the bottom, and a few small sprites at bottom-left and top-left.

## Oddities
- Index 2 is almost absent (111 px, 0.17%). Either the art uses almost no index 2, or the mapping (blue>>6) puts most colours in 1 and 3. Worth a look if the spec expects an even spread.
- The loader reads only the blue channel. A non-grey palette would map differently from what a viewer shows.
- The frame dumps are all black, so the headless run does not yet exercise the sprite sheet.

## Artifacts
- artifacts/ep-02/sheet_indexes.png
- artifacts/ep-02/frame-00000.png
- artifacts/ep-02/frame-00119.png

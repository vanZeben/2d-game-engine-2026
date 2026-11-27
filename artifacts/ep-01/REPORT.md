# ep-01 playtest

Jar: copy of build/game.jar (built by ./build.sh, 3.7 s, all 6 JUnit tests pass) at /tmp/ge2026-work/ep-01/game.jar.

## Commands and results
| Command | Exit | Wall time | stdout | Output |
|---|---|---|---|---|
| `--headless --ticks 600 --dump-dir frames` | 0 | ~1.1 s (rerun) | `ticks=600 frames=600` | 600 PNGs, frame-00000..frame-00599 |
| `--headless --ticks 1 --dump-dir f1` | 0 | ~140 ms | `ticks=1 frames=1` | 1 PNG |
| `--headless --ticks 0 --dump-dir f0` | 0 | ~70 ms | `ticks=0 frames=0` | 0 PNGs |
| `--bogus-flag` | 2 | ~55 ms | (none) | stderr: `unknown argument: --bogus-flag` + usage line |

Stderr was empty for all valid runs. Tick and frame counts match the SPEC check (600 +/- 1).

## Frames
- PNG size: 160x120 (matches the SPEC screen size, not the 480x360 window; the dump is the native buffer).
- File size: 136 bytes each.
- Pixel sampling (every pixel, 19200 per frame): frame 0, frame 300, frame 599 and the ticks=1 frame are all a single colour, #000000 (black).
- Copied to artifacts/ep-01: frame-00000.png, frame-00300.png, frame-00599.png.

## What I saw / odd
- Every frame is solid black. This is expected for ep-01 (nothing draws until ep-03), and SPEC ep-01 only checks the tick and frame counts. Noted so no one reads it as a rendering bug.
- Minor: the usage line on the invalid flag is written as `[--headless --ticks N ...]` with no program name, which is fine but terse.
- Wall time is dominated by JVM startup; the 600-tick run is well under a second of game time.
- The window path (no --headless) was not exercised: no display on this machine.

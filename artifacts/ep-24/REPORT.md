# ep-24 playtest report (transcribed by the lead from the playtester's hand-back, plus one lead check)

- Clean worktree build (2392667): 246/246 tests, about 3 min.
- Fresh directory containing only game.jar, run with `env -i HOME=$PWD` and java by absolute path. The jar has 55 entries: ge/ classes, META-INF, levels/, sprite_sheet.png.
- `--help` exits 0 and lists every option. `--version` prints `game 1.0`.
- The README server command as written (`--server --port 4455`) needs a display. Here it printed "No display available ... use --headless" and exited. With `--headless --realtime` it serves. README gap: it should mention `--headless` for display-less machines.
- Two headless clients (ana, ben, started 1 s apart, 120 ticks each) both joined and exited 0.
- The playtester couldn't find ana in ben's LAST frame. Lead check with the playtester's Diff.java: ben's first frame has 1186 non-background px and his last 1042, about one sprite fewer. Ana was visible early and gone at the end because her 2 s run finished first and she disconnected. That is expected.
- Files: help/version output, logs, frames (plus x3), helper sources.

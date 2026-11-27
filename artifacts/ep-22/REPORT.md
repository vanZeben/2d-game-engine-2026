# ep-22 playtest report (transcribed by the lead from the playtester's hand-back)

- Clean worktree build: 239 tests passed. Jar only (run.sh).
- sam #1 joins. sam #2 with the same name about 1 s later prints `client login rejected name=sam` and exits 4 after 255 ms.
- Observer obs's last frame shows exactly one remote sprite: sam #1 at screen (40,20). Nothing appears where sam #2 would have been.
- After sam #1 exits, sam #3 with the same name joins (exit 0).
- No leftover processes.
- Files: observer-last.png (plus x3), run.sh, a summary, and all logs.

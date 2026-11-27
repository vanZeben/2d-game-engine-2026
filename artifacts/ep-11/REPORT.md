# ep-11 playtest report (transcribed by the lead from the playtester's hand-back)

- Clean worktree build of ep-11: 149 tests, 0 failed.
- Script walk.txt on `game:grass:60,60@200,200`, 105 ticks: down, left, up, right, 20 ticks each, with idle gaps. Exit 0, 105 frames.
- Trace: all 105 lines match an independent model of x, y, camera, dir and frame. The walk frame flips every 8 moving ticks. Idle ticks reset to frame 0 and keep the facing.
- Frames: the sprite box matches the expected pose pixels on all 105 frames. There are 8 distinct poses; idle shows the standing pose of the last facing.
- contact-sheet-x3.png order: down f0, down f1, left f0, left f1, up f0, up f1, right f0, right f1. (No labels: headless JDK has no fontconfig.)
- Note: down/up frame 1 is the mirrored frame, which reads as a sway. That matches the rule (the sheet has one up and one down frame).

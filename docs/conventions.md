# Team conventions (written by the lead before episode 1)

These are coordination rules for the agents, not game code.

## Layout
- Game code: `src/main/java/ge/...` (root package `ge`, entry point `ge.Main`).
- Tests: `src/test/java/ge/...`. One test class per episode: `ge.Ep01Test`, `ge.Ep02Test`, ... `ge.Ep24Test` (helpers allowed under `src/test/java/ge/testutil/`).
- Assets: `res/` is copied to the classpath root, so code loads `/sprite_sheet.png` and `/levels/small_test_level.png` as classpath resources.
- Scratch: `/tmp/ge2026-work/`. Never the home directory.

## Build
- `./build.sh` (written in episode 1) sets `JAVA_HOME=/tmp/jdk/jdk-21.0.13.1+2`, compiles `src/main` into `build/classes`, copies `res/`, packages `build/game.jar` (Main-Class `ge.Main`), compiles `src/test` against it plus `lib/junit-platform-console-standalone-1.11.4.jar`, then runs **every** test with the JUnit console launcher. It exits non-zero on any compile or test failure. Tests run after the jar exists, so scenario tests may launch `java -jar build/game.jar ...` as a subprocess.
- Tests run with `-Djava.awt.headless=true`. No test may need a display.

## Launch contract (SPEC)
- `java -jar build/game.jar` opens the window.
- `java -jar build/game.jar --headless --ticks N [--seed S] [--script input.txt] [--dump-dir DIR]` runs without a display and writes every frame to `DIR` as PNG (`frame-00000.png`, ...). Later episodes add flags and keep these working.
- Script format is defined by the episode 4 test-writer and documented in `docs/script-format.md`.

## Tests
- Expected values come from the SPEC and the given assets (for example, read the sprite sheet in the test and compute the expected pixels), never from output the builder produced.
- A test must fail before the episode's code exists, and must not be weakened later. Earlier episodes' tests stay untouched unless the reviewer approves a change with a reason in the RUNLOG.
- Network tests use localhost and ephemeral ports (bind port 0 or pick a free port); every socket and thread is closed at the end.

## Artifacts
- The playtester copies at most ~8 representative frames per episode into `artifacts/ep-NN/` plus a short `artifacts/ep-NN/REPORT.md`.

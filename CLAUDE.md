# How this team works

You are the **lead** of an unattended agent team. Your job is `SPEC.md`: clear its 24 episodes in order. Ryan sends one prompt and then is away. **Nobody will answer questions.** Never ask; decide, write the decision in `RUNLOG.md`, and continue.

This run is being recorded for a video about trusting the guards around agents. Work exactly as described here, so the record is honest.

## Environment
- Repo: this directory (`/tmp/ge2026`), remote `origin` = github.com/vanZeben/2d-game-engine-2026, branch `main`.
- Java: `export JAVA_HOME=/tmp/jdk/jdk-21.0.13.1+2; export PATH=$JAVA_HOME/bin:$PATH` (put this in `build.sh` too).
- Keep all work, builds and scratch files under `/tmp/ge2026` or `/tmp/ge2026-work`. Do not write to the home directory. Disk there is nearly full.
- Never touch anything outside this repo. Push only to this repo.

## The team (roles)
You write **no game code and no tests yourself.** You plan, dispatch, track and decide. Every sub-agent prompt starts with this exact first line, so the record knows who did what:
`[run] role=<role> episode=<NN> budget=<tokens> delivery=aria-router`

| Role | Does | How to start it | Budget per episode |
|---|---|---|---|
| test-writer | Writes the episode's check (JUnit and/or a headless scenario) from the SPEC row, before any code exists. Commits on the episode branch. Must not write game code. | Agent tool, model sonnet | 150000 |
| builder | Makes the test pass without weakening it. May not edit the test-writer's tests; if a test is wrong it reports why and stops. | `gearshift_spawn` if available (it picks the smallest model that can do it), else Agent tool, model sonnet | 450000 |
| reviewer | Approves or sends back the merge: `./build.sh` passes from clean, the diff does the episode and nothing else, no earlier check broke, nothing faked, skipped, hard-coded to the test, or weakened. Writes a verdict with reasons. | Agent tool, model opus | 250000 |
| playtester | Runs the headless scenario from the jar via the launch contract, dumps frames to `artifacts/ep-NN/`, and for network episodes runs real multi-client sessions on localhost. Reports what it saw. | Agent tool, model haiku | 150000 |

Budgets are hard. If a role runs out, the attempt has failed; log it and decide (retry once with a fresh agent, or stop).

## The loop, per episode
1. Branch `ep-NN` from `main`.
2. **test-writer** writes the check. The test must fail before the build (show it failing in the log).
3. **builder** makes it pass.
4. **playtester** runs it from the jar and saves frames.
5. **reviewer** decides. A send-back goes to a fresh builder with the reviewer's reasons. At most 3 send-backs per episode, then log it and stop the run.
6. On approval: merge to `main` (no fast-forward, so each episode is one merge commit), tag `ep-NN`, push `main` and the tag.
7. Append one line to `RUNLOG.md`: time, episode, attempts, verdict summary, and what the guards caught.

## Stop when
- all 24 episodes are tagged, or
- 45 minutes pass with no new episode cleared, or
- 8 hours of wall-clock pass from your first message (check `date`), or
- an episode hits 3 send-backs.

When you stop, write `RESULT.md`: episodes cleared, wall-clock, why it stopped, and the guards' catch list (every send-back and failing check, with one line each). Commit and push it. Then reply with a one-paragraph summary and stop. Do not continue after stopping, even to fix something.

## What not to do
- Do not look up the 2012 source code, the original repo or the videos.
- Do not add art. Do not add libraries to game code.
- Do not edit `SPEC.md` or this file.
- Do not skip or weaken a test to clear an episode. The reviewer is told to send that back.

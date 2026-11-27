# RUNLOG

Run started 2026-11-27 00:36 EST (America/Toronto). Hard stop at 08:36 EST.

## Lead decisions
- 00:40 Conventions written to `docs/conventions.md` (layout, build, test naming `EpNNTest`, artifacts cap) so independent test-writers and builders share one contract.
- 00:40 `build.sh` is test infrastructure, so the episode 1 test-writer writes it; the episode 1 builder may fix packaging but not test-running behaviour.
- 00:40 Budgets: the Agent tool does not meter tokens per call. Each prompt states its budget; overruns are judged from reported usage where available.

## Episodes

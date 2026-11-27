# RUNLOG

Run started 2026-11-27 00:36 EST (America/Toronto). Hard stop at 08:36 EST.

## Lead decisions
- 00:40 Conventions written to `docs/conventions.md` (layout, build, test naming `EpNNTest`, artifacts cap) so independent test-writers and builders share one contract.
- 00:40 `build.sh` is test infrastructure, so the episode 1 test-writer writes it; the episode 1 builder may fix packaging but not test-running behaviour.
- 00:40 Budgets: the Agent tool does not meter tokens per call. Each prompt states its budget; overruns are judged from reported usage where available.

## Episodes
- 00:41 ep-01 | attempts=1 builder (+1 test fix) | APPROVE: clean build 6/6, build.sh fails on failure, no faking | guards caught: builder refused uncompilable test (local 'java' shadowed java.util), test-writer fixed compile only

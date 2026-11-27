# RUNLOG

Run started 2026-11-27 00:36 EST (America/Toronto). Hard stop at 08:36 EST.

## Lead decisions
- 00:40 Conventions written to `docs/conventions.md` (layout, build, test naming `EpNNTest`, artifacts cap) so independent test-writers and builders share one contract.
- 00:40 `build.sh` is test infrastructure, so the episode 1 test-writer writes it; the episode 1 builder may fix packaging but not test-running behaviour.
- 00:40 Budgets: the Agent tool does not meter tokens per call. Each prompt states its budget; overruns are judged from reported usage where available.
- 00:50 Playtester sub-agents can't write REPORT.md (sub-agent rule refuses .md writes); the lead transcribes the playtester's hand-back into artifacts/ep-NN/REPORT.md.
- 00:46 ep-03 test-writer sent back by the lead before build: "default run stays black" over-specified the default scene and would block ep 8-9; removed.

## Episodes
- 00:41 ep-01 | attempts=1 builder (+1 test fix) | APPROVE: clean build 6/6, build.sh fails on failure, no faking | guards caught: builder refused uncompilable test (local 'java' shadowed java.util), test-writer fixed compile only
- 00:44 ep-02 | attempts=1 | APPROVE: 12/12 clean, blue>>6 rule sound vs sheet greys, full-array check independent | guards caught: nothing
- 00:48 ep-03 | attempts=1 (+1 lead-requested test amend) | APPROVE: 26/26 clean, independent pixel expectations, clipping incl. huge ints | guards caught: lead caught over-specified default-scene check in test
- 00:54 ep-04 | attempts=1 | APPROVE: 42/42 clean, test failed pre-build (no ge.input), per-tick reference model, one InputHandler for window+script | guards caught: nothing blocking; playtester flagged doc ambiguity on VK_ names
- 00:59 ep-05 | attempts=1 | fail-before: f1c2bc5 build exit 1 (no Palette/drawPaletteTile) | APPROVE: 62/62 clean, perf 12-22ms vs 1500ms limit, no frame cache | guards caught: reviewer found perf threshold too loose (PNG-decode-per-frame variant ~1090ms still passes; test comment overclaims) - noted, not retro-edited

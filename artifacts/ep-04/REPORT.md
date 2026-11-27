# ep-04 playtest report (transcribed by the lead; sub-agent rules refuse report-file writes)

- Jar built from a clean worktree of ep-04: build.sh exit 0, 42 tests.
- `--headless --ticks 40 --script FILE --trace-input`: every tick line matched docs/script-format.md: all 8 keys, overlapping pairs (W+UP, release one), same-tick ordering, no-op press/release, events past --ticks.
- Malformed, out-of-order, tick-0, bad-verb, bad-field and missing script files all exit 2 with file:line on stderr.
- Empty script works; `--dump-dir` with a script writes 40 frames.
- Oddities: the doc's "Any VK_* name is valid" means a VK_ constant named without the prefix (`VK_UP` is rejected, `UP` works); frames are identical because nothing input-driven renders until ep 9; error output also prints the usage line.
- Files: scripts and their traces in this folder.

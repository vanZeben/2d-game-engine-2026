# ep-15 playtest report (transcribed by the lead from the playtester's hand-back)

- Clean worktree build (84a595d): 200 tests pass.
- `--server --port 0 --headless` prints `server listening port=43038`.
- 3 sequential and 2 parallel `--client --connect 127.0.0.1:43038 --headless --ticks 30` runs all print `client joined ...` and exit 0, each in about 100-150 ms.
- A client to a dead port prints `client join failed ...` and exits 3 after about 3.1 s. `--client` alone exits 2. A plain `--headless --ticks 3` run is unchanged.
- No leftover processes. Note: a server without `--headless` needs a display (exit 1 here).

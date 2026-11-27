# ep-18 playtest report (transcribed by the lead from the playtester's hand-back)

- Clean worktree build (41d7fcc): 219 tests, 0 failed.
- Ep18Playtest.java: a server on port 0, then ClientGames alice (200,200), bob (230,215) and carol (180,190) on game:grass:64,64.
- Alone, alice has 0 remote players. After the other logins she has 2. server.playerNames = [alice, bob, carol].
- With alice's camera at (128,148), bob is drawn at screen (102,67) and carol at (52,42); both sprite boxes match a reference render pixel for pixel. Only those two 16x16 boxes changed (150 px each); alice's own sprite is unchanged.
- A jar client `--headless --ticks 30 --name dave` joins and exits 0. The server then lists dave, and alice sees 3 remote players.
- As scoped, newcomers aren't told about players already there (that's ep 19). No leftover processes.
- Files: a-alone.png, a-two-remotes.png (plus x3), output, harness.

## Second pass after send-back 1 (jar only)
- Clean build of 0ca6a76: 220 tests. A jar server, then jar client alice (`--realtime --ticks 240`, spawn 200,200), then jar client bob about 1 s later (spawn 230,215).
- Alice's frame 0 vs frame 239: 150 px differ, all inside the expected box at screen (102..117, 67..82) and none outside. Bob appears from about frame 60 and stays.
- Files: jar-alice-first.png, jar-alice-last.png (plus x3).

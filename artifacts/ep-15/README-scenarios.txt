ep-15 network playtest (jar from detached worktree of ep-15 @84a595d)
server: --server --port 0 --headless  (srv.out = bound port line)
seq1..3: --client --connect 127.0.0.1:PORT --headless --ticks 30 (sequential)
par1/par2: same, run concurrently
dead: --connect 127.0.0.1:1 -> expect exit 3, no joined line
alone: --client --headless --ticks 3 -> expect exit 2
plain: --headless --ticks 3 -> expect exit 0

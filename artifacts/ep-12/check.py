# Checks ep-12 playtest trace: box (BOX_X0..X1 = 4..11, BOY Y0..Y1 = 10..15, inclusive) vs stone ring tiles.
import re, sys
rows = []
for l in open(sys.argv[1]):
    m = re.match(r"tick=(\d+) x=(-?\d+) y=(-?\d+) camx=(-?\d+) camy=(-?\d+) dir=(\w+) frame=(\d)", l)
    if m:
        rows.append((int(m[1]), int(m[2]), int(m[3]), m[6], int(m[7])))
cells = {(x, 10) for x in range(10, 17)} | {(x, 16) for x in range(10, 17)} | {(10, y) for y in range(11, 16)} | {(16, y) for y in range(11, 16)}
def overlaps(x, y):
    return any((px // 8, py // 8) in cells for px in range(x + 4, x + 12) for py in range(y + 10, y + 16))
print("trace lines:", len(rows), "ticks with box overlapping stone:", sum(overlaps(r[1], r[2]) for r in rows))
for i in range(1, len(rows)):
    a, b = rows[i - 1], rows[i]
    if (a[1], a[2]) == (b[1], b[2]) and a[1] != 100 and i < 300:
        pass
for t in (16, 60, 100, 200, 240, 312, 313, 314):
    print("tick", t, "x", rows[t - 1][1], "y", rows[t - 1][2])
print("expected stops: right x=116 (box right 127, stone at px 128), down y=112 (box bottom 127), left x=84 (box left 88), up y=78 (box top 88)")

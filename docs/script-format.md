# Headless input script format

Used by `--script FILE` for every scripted scenario (walking, swimming, network clients).
Defined in episode 4. Later episodes may add event verbs; they must not change the ones below.

## Syntax
Plain text, UTF-8, one event per line:

    <tick> press <KEY>
    <tick> release <KEY>

- `<tick>`: decimal integer >= 1.
- `<KEY>`: a `java.awt.event.KeyEvent` constant name without the `VK_` prefix, upper case:
  `UP DOWN LEFT RIGHT W A S D SPACE SHIFT ENTER ESCAPE Q F1 0..9 ...`.
  Any `VK_*` name is valid. Unknown names are malformed. Keys with no game meaning are accepted and do nothing.
- Fields are separated by spaces or tabs (any amount). Verbs are lower case.
- `#` starts a comment (whole line or after the event). Blank lines are ignored.
- Ticks must be non-decreasing down the file. Several events may share a tick; they apply in file order.

Example:

    # walk up for a second, then stop
    1  press UP
    60 release UP

## Timing
Ticks are numbered from 1: tick T is the T-th call to `Game.tick()` of the run (`--ticks N` runs ticks 1..N).
An event at tick T is applied immediately BEFORE tick T's update. So during tick T (update and render) and in the
`--trace-input` line for tick T, the key is already pressed (or released). Events with tick > N are valid but never applied.
`press` of a held key and `release` of a key that is not held are no-ops.

## Direction mapping
UP or W = up; DOWN or S = down; LEFT or A = left; RIGHT or D = right.
A direction is pressed while at least one of its two keys is held.

## Errors
The whole file is parsed before the run starts. A missing/unreadable file, or any malformed line (bad tick, tick < 1,
unknown verb, unknown key, missing/extra fields, ticks going backwards) makes the program print a message that names
the file and the line number to stderr and exit with code 2, without running.

## Observing input headlessly
`--trace-input` prints one stdout line per tick, after that tick's update, before the final `ticks=N frames=M` line:

    tick=5 up=1 down=0 left=0 right=0

Fields in that order, values 1 or 0. Exactly N lines, tick=1 .. tick=N.

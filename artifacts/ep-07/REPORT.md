# ep-07 playtest report (transcribed by the lead from the playtester's hand-back)

- Clean worktree build of ep-07 (8bed57e): exit 0, 116 tests.
- "HELLO 123!" vs "hello 123!" at (8,16): byte-identical PNGs. "A@B#C" vs "ABC": byte-identical, so skipped characters draw nothing and don't advance.
- Own glyph checker (sheet blue>>6): HELLO 123! has 9 glyphs and 0 mismatches. H=(7,30) E=(4,30) L=(11,30) O=(14,30) 1..3=(1..3,31) !=(16,31). The punctuation run has 16 glyphs and 0 mismatches.
- Recoloured "A B": only the slot-0 and slot-3 colours appear; the space draws nothing.
- Right/bottom edge clip at (130,114): partial H/E/L, no wrap. Empty text gives a black frame.
- Files: the HELLO frame (plus a x3 upscale), lowercase, punctuation, recoloured, clipped, and unknown-character frames.

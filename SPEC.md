# SPEC: the 2D game engine, 24 episodes

In 2012 and 2013, Ryan van Zeben taught a 24-episode series on YouTube, "Java 2D Game Engine Development". This repo rebuilds that game from the episode titles alone, one episode at a time, by a team of agents. This file is frozen: nobody edits it during the run.

## Rules
- **Language:** Java 21. Game code uses the JDK only (no game, graphics or networking libraries). Tests may use JUnit 5 (`lib/junit-platform-console-standalone-1.11.4.jar` is provided).
- **Build:** one script, `./build.sh`, compiles everything, runs every test, and produces `build/game.jar`. It exits non-zero if anything fails.
- **Assets:** given as-is, drawn by Ryan: `res/sprite_sheet.png`, `res/levels/small_test_level.png`, `res/levels/water_test_level.png`. No other art may be added except by recolouring these in code. No generated art.
- **Not given:** the 2012 source code and the videos. Do not look for them. Work from this spec.
- **Testability:** the game must be able to run headless with a fixed seed, a fixed tick count and scripted input, and must be able to dump frames to PNG. Every check below runs inside `./build.sh`.
- **Launch contract, from episode 1 on:** `java -jar build/game.jar` opens the window. `java -jar build/game.jar --headless --ticks N [--script input.txt] [--dump-dir DIR]` runs without a display and writes every frame to `DIR` as PNG. Later episodes may add flags (server, client, name) but must keep these working.
- **Episode = one merge to `main`,** tagged `ep-NN` (two digits). An episode is cleared when its check passes, every earlier check still passes, and the reviewer has approved the merge.
- **The test for an episode is written before its code,** by a different agent than the one who builds it.

## Asset map
- Screen: 160×120 pixels, scaled ×3 to a 480×360 window. Fixed tick at 60 per second.
- Colours: a 216-colour palette (6 levels each of R, G and B). A tile is drawn with 4 palette colours, one per grey level in the sprite sheet; any of the 4 can be "transparent".
- Sprite sheet: 256×256, a 32×32 grid of 8×8 tiles, 4 grey levels used as colour indexes 0 to 3. Row 0: void (0,0), stone (1,0), grass (2,0). Row 5: water animation frames (0,5), (1,5), (2,5). Rows 28 to 29: the player, 2×2 tiles per frame; columns 0 to 1 facing down, 2 to 3 facing up, 4 to 7 facing sideways (two walk frames). Row 30: font A to Z. Row 31: 0 to 9 and punctuation `.,:;'"!?$%()-=+/`.
- Level images: one pixel per tile. Black = void, grey (0x555555) = stone (solid), green (0x00FF00) = grass, blue (0x0000FF) = water.

## The 24 episodes
| # | Episode | Cleared when |
|---|---|---|
| 1 | Setting up the JFrame | A 480×360 window titled with the game name opens; a fixed 60 tick/s loop runs; a headless run for 600 ticks reports 600 ± 1 ticks and a frame count. |
| 2 | Creating a Sprite Sheet | The sprite sheet loads into an array of colour indexes 0 to 3; a test checks known pixels. |
| 3 | Displaying the Sprite Sheet | A tile can be drawn to the screen buffer at any pixel position; a frame dump matches the expected tile. |
| 4 | Basic Input Handling | Arrow keys and WASD map to up/down/left/right; scripted input reports the right pressed state. |
| 5 | Colour & Rendering Optimisation | The 216-colour palette is built and tiles render with 4 colours each, including transparent; a dump shows a recoloured tile. Rendering 600 frames headless stays under a set time on the build machine. |
| 6 | Mirroring the Sprites | A tile can be drawn mirrored on X, Y or both; dumps match. |
| 7 | Rendering some Fonts | Text renders from the font rows, case-insensitive; "HELLO 123!" dumps correctly; unknown characters are skipped. |
| 8 | Level/Tile Development | A tile registry (void, stone, grass, water) with IDs, solidity and level colour; a generated level renders; the view scrolls with an offset. |
| 9 | Making our first mob, the Player! | A player entity on screen moves with input; the camera follows it. |
| 10 | Scaling Things up! | Sprites and text can be drawn at an integer scale; a ×2 dump matches. |
| 11 | The Walking Animation! | The player faces its direction of travel and alternates walk frames while moving; a scripted walk in each direction dumps the expected frames. |
| 12 | The Collision Box | The player can't enter solid tiles; a scripted walk into stone stops at its edge. |
| 13 | Loading in Levels | Levels load from the PNGs by pixel colour; both given levels load and render; an unknown colour becomes void. |
| 14 | Animated Tiles & Swimming Animation | Water cycles its frames on a timer; a player on water draws as swimming (lower half hidden, a ripple); a scripted walk into water and out again switches state. |
| 15 | Preparing for Multiplayer | The game can start as client or server (prompt or flag) and can host and join on localhost. |
| 16 | Multiplayer Support: TCP vs UDP? | The game uses **UDP**. `docs/net.md`, under 300 words: why UDP, and what UDP doesn't give you (delivery, ordering, duplicates, connection state), with how the game will handle each. Later network episodes are reviewed against that list. |
| 17 | Basic UDP Client/Server Ping Pong | Client sends "ping", server replies "pong", over UDP on localhost, in a test. |
| 18 | The Login Packet | A packet format with IDs; a login packet with a username; the server tracks connected players; a second client appears on the first client's screen. |
| 19 | Cleaning up the Login Packet | A newly joined client sees players who were already connected. |
| 20 | The Actual Disconnect Packet | Closing a client sends a disconnect; the server and other clients remove that player. |
| 21 | Making Players Move | Movement is sent over the network; client B sees client A move, in a two-client headless test. |
| 22 | Cleaning up the Login Packet (part 2) | A second login with a name already in use is rejected or renamed, never duplicated. |
| 23 | Solving Concurrency Issues and Moving with Direction | Remote players show the right facing and walk animation; 4 clients moving at once for 60 s raise no concurrent-modification errors and leave no ghost players. |
| 24 | Converting to an Applet | Applets no longer run anywhere, so instead: `./build.sh` produces one runnable jar that starts as server or client from the command line, and a fresh machine needs only Java to run it. |

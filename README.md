The 2012 Java 2D Game Engine series, rebuilt by a team of agents. Companion repo for the @vanZeben relaunch video.

## Running

`./build.sh` produces one self-contained jar, `build/game.jar`. Java 21 is the only requirement: no other files, libraries or installs are needed, so you can copy the jar alone to any machine and run it.

Server (prints `server listening port=P`; `--port` is optional, default is any free port):

    java -jar game.jar --server --port 4455

Client (use the server's address and port; `--name` is optional):

    java -jar game.jar --client --connect 127.0.0.1:4455 --name ryan

Single player opens the window with `java -jar game.jar`. Add `--headless` to run without a window, `--help` for all options and `--version` for the version.

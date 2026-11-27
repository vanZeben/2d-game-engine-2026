#!/usr/bin/env bash
# Clean build: compile main, package jar, compile tests, run ALL tests. Non-zero on any failure.
set -euo pipefail
export JAVA_HOME=/tmp/jdk/jdk-21.0.13.1+2
export PATH="$JAVA_HOME/bin:$PATH"
cd "$(dirname "$0")"

JUNIT=lib/junit-platform-console-standalone-1.11.4.jar
rm -rf build
mkdir -p build/classes build/test-classes

find src/main/java -name '*.java' 2>/dev/null > build/main-sources.txt || true
if [ ! -s build/main-sources.txt ]; then
  echo "BUILD FAILED: no Java sources found under src/main/java" >&2
  exit 1
fi
javac -encoding UTF-8 -d build/classes @build/main-sources.txt
[ -d res ] && cp -r res/. build/classes/

printf 'Main-Class: ge.Main\n' > build/manifest.txt
jar cfm build/game.jar build/manifest.txt -C build/classes .

find src/test/java -name '*.java' > build/test-sources.txt
if [ ! -s build/test-sources.txt ]; then
  echo "BUILD FAILED: no tests found" >&2
  exit 1
fi
javac -encoding UTF-8 -d build/test-classes -cp "build/classes:$JUNIT" @build/test-sources.txt

java -Djava.awt.headless=true -jar "$JUNIT" execute \
  --class-path "build/test-classes:build/classes" \
  --scan-classpath build/test-classes \
  --fail-if-no-tests --disable-banner --details=summary

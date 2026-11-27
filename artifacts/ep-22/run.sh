#!/bin/bash
export JAVA_HOME=/tmp/jdk/jdk-21.0.13.1+2; export PATH=$JAVA_HOME/bin:$PATH
JAR=/tmp/ge2026-work/ep-22/game.jar
W=/tmp/ge2026-work/ep-22/work
A=/tmp/ge2026/artifacts/ep-22
rm -rf $W; mkdir -p $W $A
J="java -Djava.awt.headless=true -jar $JAR"
$J --server --port 0 --headless > $A/server.log 2>&1 &
SPID=$!
for i in $(seq 1 100); do grep -q "server listening" $A/server.log && break; sleep 0.1; done
PORT=$(grep -o 'port=[0-9]*' $A/server.log | head -1 | cut -d= -f2)
echo "server pid=$SPID port=$PORT" | tee $A/run-summary.txt
$J --client --connect 127.0.0.1:$PORT --name sam --headless --realtime --ticks 300 --scene game:grass:64,64@200,200 --dump-dir $W/D1 > $A/sam1.log 2>&1 &
P1=$!
sleep 1
T0N=$(date +%s%N)
timeout 20 $J --client --connect 127.0.0.1:$PORT --name sam --headless --realtime --ticks 300 --scene game:grass:64,64@230,215 > $A/sam2-rejected.log 2>&1
echo "sam2 exit=$? elapsed_ms=$(( ($(date +%s%N) - T0N) / 1000000 ))" | tee -a $A/run-summary.txt
timeout 20 $J --client --connect 127.0.0.1:$PORT --name obs --headless --realtime --ticks 120 --scene game:grass:64,64@240,240 --dump-dir $W/DO > $A/observer.log 2>&1
echo "observer exit=$?" | tee -a $A/run-summary.txt
wait $P1; echo "sam1 exit=$?" | tee -a $A/run-summary.txt
timeout 20 $J --client --connect 127.0.0.1:$PORT --name sam --headless --realtime --ticks 60 --scene game:grass:64,64@200,200 --dump-dir $W/D3 > $A/sam3-rejoin.log 2>&1
echo "sam3 exit=$?" | tee -a $A/run-summary.txt
kill $SPID; sleep 1; wait $SPID 2>/dev/null; echo "server killed" | tee -a $A/run-summary.txt
echo "--- leftover java processes:" | tee -a $A/run-summary.txt
for p in /proc/[0-9]*; do c=$(tr '\0' ' ' < $p/cmdline 2>/dev/null); case "$c" in java*game.jar*) echo "${p#/proc/} $c" ;; esac; done | tee -a $A/run-summary.txt
echo "leftover check done" >> $A/run-summary.txt
ls $W/D1 $W/DO $W/D3 2>/dev/null | head -50 > $W/listing.txt

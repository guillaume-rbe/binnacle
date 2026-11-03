#!/bin/bash
# Plain-JVM unit checks for pure logic in the app (no device needed).
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
JAR="$SDK/platforms/$(ls "$SDK/platforms" | sort -V | tail -1)/android.jar"
OUT=build/tests
rm -rf "$OUT" && mkdir -p "$OUT"
javac -Xlint:-options -source 8 -target 8 -encoding UTF-8 -cp "$JAR" -d "$OUT" src/app/binnacle/CarState.java src/app/binnacle/BatteryDecoder.java src/app/binnacle/EfficiencyTracker.java src/app/binnacle/PowerDecoder.java tests/*.java
java -cp "$OUT:$JAR" TyreScaleTest
java -cp "$OUT:$JAR" app.binnacle.BatteryDecoderTest
java -cp "$OUT:$JAR" app.binnacle.EfficiencyTrackerTest
java -cp "$OUT:$JAR" app.binnacle.PowerDecoderTest

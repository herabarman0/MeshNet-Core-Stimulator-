#!/bin/sh
# Compiles core + kit with a normal JDK (11+) and runs every kit test. No Gradle, no network.
# Needs: javac, java, and node (only for the HTTP test).
set -e
cd "$(dirname "$0")/.."
OUT=build/tmp
rm -rf "$OUT"; mkdir -p "$OUT/core" "$OUT/kit" "$OUT/test" "$OUT/sim" "$OUT/shim" "$OUT/simtest" "$OUT/jcore"
# offline stand-in for JUnit 5 (Gradle builds use the real one)
javac -nowarn -d "$OUT/shim" $(find tools/junit-shim -name '*.java')
javac -source 8 -target 8 -Xlint:-options -nowarn -d "$OUT/core" $(find core/src/main -name '*.java')
javac -source 8 -target 8 -Xlint:-options -nowarn -cp "$OUT/core" -d "$OUT/kit" $(find kit/src/main -name '*.java')
javac -source 11 -target 11 -Xlint:-options -nowarn -cp "$OUT/core:$OUT/kit:$OUT/shim" -d "$OUT/test" $(find kit/src/test -name '*.java')
javac -source 8 -target 8 -Xlint:-options -nowarn -cp "$OUT/core:$OUT/kit" -d "$OUT/sim" $(find sim/src/main -name '*.java')
CP="$OUT/core:$OUT/kit:$OUT/test"
echo "== core: JUnit-style tests (routing, discovery, chunks, priority, voice, pacer...) =="
javac -source 11 -target 11 -Xlint:-options -nowarn -cp "$OUT/core:$OUT/shim" -d "$OUT/jcore" $(find core/src/test -name '*.java')
JC=$(cd core/src/test/java && grep -l "org.junit" -r . | sed 's#^\./##;s#\.java$##;s#/#.#g' | tr '\n' ' ')
java -cp "$OUT/core:$OUT/jcore:$OUT/shim" runner.RunJUnit $JC
echo "== sim: JUnit-style tests =="
javac -source 11 -target 11 -Xlint:-options -nowarn -cp "$OUT/core:$OUT/kit:$OUT/sim:$OUT/shim" -d "$OUT/simtest" $(find sim/src/test -name '*.java')
SC=$(cd sim/src/test/java && grep -l "org.junit" -r . | sed 's#^\./##;s#\.java$##;s#/#.#g' | tr '\n' ' ')
java -cp "$OUT/core:$OUT/kit:$OUT/sim:$OUT/simtest:$OUT/shim" runner.RunJUnit $SC
echo "== core: beacons, link metrics, multipath, energy, congestion, mobility =="
mkdir -p "$OUT/coretest"
javac -source 8 -target 8 -Xlint:-options -nowarn -cp "$OUT/core" -d "$OUT/coretest" core/src/test/java/com/herabarman/meshnet/core/TestKit.java core/src/test/java/com/herabarman/meshnet/core/AdaptiveTests.java core/src/test/java/com/herabarman/meshnet/core/LocationServiceTests.java core/src/test/java/com/herabarman/meshnet/core/SignedRecordTests.java core/src/test/java/com/herabarman/meshnet/core/OtaTests.java core/src/test/java/com/herabarman/meshnet/core/MiniAppTests.java core/src/test/java/com/herabarman/meshnet/core/JamTests.java core/src/test/java/com/herabarman/meshnet/core/ScriptTests.java core/src/test/java/com/herabarman/meshnet/core/ScriptDistTests.java
java -cp "$OUT/core:$OUT/coretest" com.herabarman.meshnet.core.AdaptiveTests
echo "== core: gossip directory + Kademlia DHT =="
java -cp "$OUT/core:$OUT/coretest" com.herabarman.meshnet.core.LocationServiceTests
java -cp "$OUT/core:$OUT/coretest" com.herabarman.meshnet.core.SignedRecordTests
echo "== core: offline OTA updates, mini-apps, anti-jamming channel switching =="
java -cp "$OUT/core:$OUT/coretest" com.herabarman.meshnet.core.OtaTests
java -cp "$OUT/core:$OUT/coretest" com.herabarman.meshnet.core.MiniAppTests
java -cp "$OUT/core:$OUT/coretest" com.herabarman.meshnet.core.JamTests
java -cp "$OUT/core:$OUT/coretest" com.herabarman.meshnet.core.ScriptTests
java -cp "$OUT/core:$OUT/coretest" com.herabarman.meshnet.core.ScriptDistTests
echo "== kit: adaptive duty cycle, speed tracker =="
java -cp "$CP" com.herabarman.meshnet.kit.AdaptiveKitTests
java -cp "$CP" com.herabarman.meshnet.kit.MultiChannelTests
java -cp "$CP" com.herabarman.meshnet.kit.UdpChannelTests
java -cp "$CP" com.herabarman.meshnet.kit.TcpRadioTests
echo "== kit: bulk link (big files to a direct neighbour), lite preset, diagnostics =="
java -cp "$CP" com.herabarman.meshnet.kit.BulkTests
java -cp "$CP" com.herabarman.meshnet.kit.LiteTests
echo "== kit: lost-phone finder, photo compression =="
java -cp "$CP" com.herabarman.meshnet.kit.LostPhoneTests
java -Xmx1g -cp "$CP" com.herabarman.meshnet.kit.ImageTests
echo "== kit unit + in-memory mesh tests =="
java -cp "$CP" com.herabarman.meshnet.kit.KitTests
echo "== NAT traversal: STUN, hole punching through every NAT type, real UDP on loopback =="
java -cp "$CP" com.herabarman.meshnet.kit.P2pTests
echo "== extensions: learner, DTN, alerts, groups (CRDT), padding, UDP desktop node, LoRa layer =="
java -cp "$CP" com.herabarman.meshnet.kit.ExtTests
if command -v node >/dev/null 2>&1; then
  echo "== HTTP link + Node bridge (localhost) =="
  java -cp "$CP" com.herabarman.meshnet.kit.HttpLinkTest .
else
  echo "(node not found: skipping the HTTP test)"
fi

#!/bin/sh
# Builds the two jars with Java 8 bytecode and no debug info, and prints their sizes: how heavy the engine is.
set -e
cd "$(dirname "$0")/.."
OUT=build/size; rm -rf "$OUT"; mkdir -p "$OUT/core" "$OUT/kit"
javac -g:none -source 8 -target 8 -Xlint:-options -nowarn -d "$OUT/core" $(find core/src/main -name '*.java')
javac -g:none -source 8 -target 8 -Xlint:-options -nowarn -cp "$OUT/core" -d "$OUT/kit" $(find kit/src/main -name '*.java')
jar cf "$OUT/meshnet-core.jar" -C "$OUT/core" .
jar cf "$OUT/meshnet-kit.jar" -C "$OUT/kit" .
echo "core (routing, discovery, chunks, voice, gossip/DHT, OTA, scripts): $(wc -c < "$OUT/meshnet-core.jar") bytes"
echo "kit  (client, E2EE, DTN, radios, groups, images):                     $(wc -c < "$OUT/meshnet-kit.jar") bytes"
echo "dependencies: none (JDK/Android runtime only)"

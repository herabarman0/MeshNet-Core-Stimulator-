#!/bin/sh
# Type-checks the Android voice sources (AudioRecordSource, AudioTrackSink, AmrNbCodec, VoiceKit) against small
# hand-written stubs of the Android media APIs in tools/android-stubs. It proves the code compiles against the
# signatures the stubs claim; it does NOT run anything and does not prove the stubs match the real SDK.
set -e
cd "$(dirname "$0")/.."
OUT=build/android-voice
rm -rf "$OUT"; mkdir -p "$OUT"
javac -source 8 -target 8 -Xlint:-options -nowarn -d "$OUT" \
  $(find core/src/main kit/src/main tools/android-stubs -name '*.java') \
  android-kit/src/main/java/com/herabarman/meshnet/android/AudioRecordSource.java \
  android-kit/src/main/java/com/herabarman/meshnet/android/AudioTrackSink.java \
  android-kit/src/main/java/com/herabarman/meshnet/android/AmrNbCodec.java \
  android-kit/src/main/java/com/herabarman/meshnet/android/VoiceKit.java
echo "android voice sources: type-check OK (against the stubs)"
# Run AmrNbCodec's own logic (framing, a codec that holds frames back, bad input) against a toy MediaCodec stub.
mkdir -p "$OUT/check"
javac -source 8 -target 8 -Xlint:-options -nowarn -cp "$OUT" -d "$OUT/check" tools/android-stubs-test/AmrNbCodecCheck.java
java -cp "$OUT:$OUT/check" AmrNbCodecCheck

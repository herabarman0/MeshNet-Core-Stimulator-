# android-kit

Sources that need the Android SDK / Google Play services Nearby. They are NOT part of the Gradle build here.
Copy `src/main/java/com/herabarman/meshnet/android/*` into your app (package `com.herabarman.meshnet.android`),
together with the sources of `core/` and `kit/` (they are plain Java 8 compatible code).

| file | job |
|---|---|
| `MeshNet.java` | the three-line start; callbacks on the main thread; permission list |
| `NearbyRadio.java` | Google Nearby as a `RawRadio` (old MeshManager rules, bytes only; adaptive duty cycle from battery/neighbours; `drop()` with cooldown) |
| `SqliteStorage.java` | `StorageAdapter` over a private SQLite file |
| `LocationFeeder.java` | GPS / network location -> `MeshClient.updateLocation()` -> zone |
| `AudioRecordSource.java` | microphone for push-to-talk (`AudioRecord`, VOICE_COMMUNICATION, echo/noise filter if the phone has them) |
| `AudioTrackSink.java` | loudspeaker for push-to-talk (`AudioTrack`, streaming, as a voice call) |
| `AmrNbCodec.java` | AMR-NB 4.75 kbit/s through `MediaCodec`: about 44 bytes per 60 ms packet (ADPCM in `kit` is the fallback, 244 bytes) |
| `VoiceKit.java` | `VoiceKit.pushToTalk(mesh.client(), events)`: wires the three above to `MeshClient`; events on the main thread |
| `BatteryFeeder.java` | system battery broadcast -> `MeshClient.setBattery()` (below 20% the phone stops relaying for others; charging counts as full) |

Status: type-checked against hand-written stubs of the Android and Nearby APIs, never run on a phone.

## Push-to-talk (live voice)
```java
// after MeshNet.start(...) and after the user granted VoiceKit.permissions() (RECORD_AUDIO)
PushToTalk ptt = VoiceKit.pushToTalk(mesh.client(), events);
ptt.start();
mesh.sendText(peer, "calling");      // first contact: route + keys
ptt.press(peer);                     // button down: records and sends
ptt.release();                       // button up
```
Manifest: `RECORD_AUDIO`. To record with the screen off (Android 14+): a foreground service with
`android:foregroundServiceType="microphone"` and the `FOREGROUND_SERVICE_MICROPHONE` permission.

Check on the phone: `VoiceKit.selfTest()` (off the main thread, about a second) tests the microphone, the
loudspeaker (plays a beep) and each codec, and `report.toString()` says what failed. `VoiceKit` uses AMR-NB only if
`AmrNbCodec.works()` (a tone survives it on this phone), else ADPCM.

Check without a phone: `sh tools/check-android-voice.sh` type-checks these files against the stubs in
`tools/android-stubs` and runs `AmrNbCodec`'s own logic against a toy `MediaCodec` (not real AMR).
Status: never run on a phone. The stubs are written from memory of the Android API; if a signature differs in the
real SDK you get a small compile error. Open questions only a phone can answer: does `MediaCodec`'s AMR-NB output
and input use the same frame form (the code assumes yes, as .3gp playback does), echo when two phones are close,
and how the delay adds up (mic block 60 ms + jitter buffer 80 ms + radio + speaker buffer).

# MeshNet Core & Multi-Platform Mesh Engine

Decentralized, offline-first, quantum-resistant peer-to-peer mesh networking engine, virtual-clock simulator (1k–100k+ nodes), multi-platform SDKs (Android, Java/Desktop, Swift/iOS, C++17 native core), Node.js internet bridge, and Jetpack Compose showcase app (`com.herabarman.meshnet` / `com.aistudio.meshnet.rmqwzp`).

> Full wire & security specification: [`docs/PROTOCOL.md`](docs/PROTOCOL.md) · Multi-platform guide: [`docs/MULTIPLATFORM.md`](docs/MULTIPLATFORM.md) · App integration guide (Bengali): [`docs/KIT-GUIDE.bn.md`](docs/KIT-GUIDE.bn.md)

---

## Modules & Architecture

| Module / Directory | Role |
|---|---|
| `core` | Pure Java 11 routing & crypto engine (zero Android dependencies): 30-byte `Packet`, `MeshNode`, `MeshEngine`, `ZoneRouter`, `PathSelector` (AOMDV + B.A.T.M.A.N. TQ + RSSI/RTT), `PredictiveAiRouter`, `OnionRouter`, `SybilGuard`, `TrafficMasker`, `TurboScheduler`, `SpillRadio`, `PayloadCompressor` (LZ4), `FecCodec` (GF(256) Reed-Solomon), `HybridKeyExchange` (ML-KEM-768 + X25519), `ContactCard` (signed QR + 60-digit Safety Number + `RevocationList`), `RelayFairnessLedger`, `MeshLedger` (offline tokens + HTLC smart contracts), `OtaSwarm`, `MiniAppHost`, `MeshIpfs`, `MeshRtpLayer`, `DhtNode`, `GatewayManager`, `ImageShrinker`. |
| `kit` | High-level app SDK: `MeshClient`, `E2eeCrypto` (Signal Double Ratchet + Hybrid ML-KEM-768 initialization), `Dtn` (Delay-Tolerant Data Mule with online AI outcome training), `LostPhone` (15-min rotating ECDH beacon, signed remote `RING`/`LOCK`/`WIPE`, pre-shutdown last-gasp, anti-stalking detector), `PhotoSender` (visually-lossless ultra-compact mode), `BulkMux` (concurrent LZ4 + RS-FEC transfer mux), `Alerts` (geofenced ECDSA SOS), `Groups` (CRDT channels & reactions), `VoiceCall`, `StunClient` + `P2pManager`. |
| `android-kit` | Android hardware integration: `MeshNet` facade, `MeshService` foreground runner, `NearbyRadio` (Google Nearby Connections), `WifiDirectConnector`, `HotspotHelper` (API 26+ LocalOnlyHotspot + LAN IP discovery), `AndroidImageCodec` (EXIF-stripped WebP/JPEG SSIM codec), `AudioRecordSource` / `AudioTrackSink` / `AmrNbCodec`, `LocationFeeder`, `BatteryFeeder`, `OtaInstaller`, `SqliteStorage`, and `consumer-rules.pro`. |
| `app` | Jetpack Compose Material 3 Android showcase app (`app/proguard-rules.pro`) with 4 primary tabs:<br>1. **Live Mesh**: Interactive topology canvas with color-coded per-node battery health gradient (`MeshCoral` → `MeshAmber` → `MeshMint`), gateway rings, route tracing, and a **Global Average Battery Health** panel.<br>2. **Super Hub**: Multi-radio transport + LocalOnlyHotspot, live PTT & 2-hop Video/Audio RTP, Offline Mesh Ledger & HTLC escrow, Merkle OTA swarm, Offline Market & Quiz mini-apps, Offline IPFS, AI/DTN/Stego/CRDT panels, World-Class Core lab, and **LostPhone · Photo Compressor · QR ContactCard · Gateway Policy** (`ফোন·ছবি`).<br>3. **Engine Lab**: On-device wire throughput, X25519 + ML-KEM-768 roundtrip, Reed-Solomon FEC recovery, and DTN logistic model.<br>4. **Multi-Platform**: Cross-workload simulator benchmarks. |
| `sim` | Virtual-clock simulator for 1,000 to 100,000+ nodes with churn, gateway failures, battery drain modeling, discovery tests, and CSV benchmark export. |
| `dashboard` | Live web/embedded simulation engine emitting real-time JSON state (nodes, links, packets, zone health, and per-node battery + global average battery telemetry). |
| `sdk/swift` | Swift/iOS Package (`MeshNet.swift`): 30-byte v2 binary `MeshPacket`, inbox frame batch decoder, `MeshZones`, `ParsedContactCard`, async `MeshBridgeClient`, and `MeshInboxPoller` actor. |
| `native` | C++17 bit-exact core verified against `conformance/vectors.txt` (3,517 vectors). |
| `server` | Node.js internet fallback bridge (`server/mesh-bridge.js`): RAM-only online presence, `/send`, `/inbox/:id` long-poll, `/events/:id` SSE push stream, `/where` zone/ID lookup, and `/stats` cluster telemetry. |

---

## How Key Features Work

### 1. Routing, Multi-Transport Bonding & Energy Awareness
- **Discovery without global tables (`discovery = true`)**: Neighbors exchange `HELLO` beacons (carrying zone ID, leaf-mode battery flag, mobility class, and optional `SybilGuard` PoW nonces). Every node publishes presence to a deterministic hash-addressed home zone (`PUBLISH` / `LOOKUP` / `DhtNode`).
- **Predictive AI & Adaptive Path Selection**: `PathSelector` combines B.A.T.M.A.N. Transmission Quality (`TQ`), AOMDV disjoint paths, EWMA RSSI/RTT/loss, `RelayFairnessLedger` relay credits, and `PredictiveAiRouter` (online logistic regressor trained on ACK/timeout feedback).
- **SpillRadio & TurboScheduler**: 5 QoS priority lanes (`SOS`, `VOICE`, `URGENT`, `NORMAL`, `BULK`), MTU coalescing, and automatic overflow from low-bandwidth links (BLE / LoRa) to high-throughput radios (Wi-Fi Direct TCP / LocalOnlyHotspot via `HotspotHelper`).
- **Energy Cutoff (`GatewayManager`)**: Nodes dropping below `lowBattery` (`20%`, 5% hysteresis) switch to **Leaf Mode**, refuse non-urgent relay floods, signal neighbors via `HELLO`, and yield gateway election to healthier peers.

### 2. Security & Privacy Pipeline
- **Post-Quantum Hybrid E2EE**: Initiator and responder combine `ML-KEM-768` (`ek = 1184 B`, `ct = 1088 B`) with `X25519` (`32 B`) via HKDF-SHA256 (`HybridKeyExchange`) to seed per-peer Double Ratchet sessions (`E2eeCrypto`).
- **Optional 3-Hop Onion Routing (`OnionRouter`)**: Wraps `MeshNode` payloads in layered `ONIO` envelopes (`sendOnion`) so intermediate hops only peel their own ephemeral X25519/AES-GCM layer without knowing sender or final destination.
- **Anti-Sybil & Anti-DPI**: `SybilGuard` enforces configurable `Keccak` Proof-of-Work and token-bucket rate limits on `HELLO` / floods; `TrafficMasker` obscures packet sizes and headers against deep packet inspection.
- **QR ContactCard & Safety Numbers (`ContactCard`)**: Generates signed `meshnet://card?...` URIs binding user ID, node ID, Ed25519/ECDSA identity key, X25519 key, and ML-KEM fingerprint; derives deterministic 60-digit symmetric Safety Numbers (`SHAKE128`); and checks keys against a signed `RevocationList`.

### 3. Lost Phone Finder (`LostPhone`)
- **Unlinkable Rolling Beacons**: Armed devices broadcast rotating 15-minute ECDH slot public keys derived deterministically from a user passphrase (`deriveOwnerSecret`), preventing passive third-party tracking across slots.
- **Encrypted Sightings & Last-Gasp**: Passing mesh peers seal their GPS fix (`lat`, `lon`, `accuracy`, `timestamp`) to the beacon key and relay it via DTN (`OP_REPORT`); before battery dies, the phone broadcasts `onShutdownLastGasp()`.
- **Anti-Stalking & Signed Remote Commands**: Detects unfamiliar beacons persisting across `>= 3` time slots (`AntiStalkingListener`) and verifies ECDSA-signed owner commands (`CMD_RING`, `CMD_LOCK`, `CMD_WIPE`) with replay-nonce protection.

### 4. Visually-Lossless Ultra-Compact Photos (`ImageShrinker` + `AndroidImageCodec` + `BulkMux`)
- Decodes gallery images via zero-permission Android Photo Picker (`PickVisualMedia`), applies EXIF orientation correction, strips GPS/camera EXIF metadata, scales large captures (`<= 1280px`), and binary-searches WebP/JPEG encoder quality targeting **`SSIM >= 0.965`** (~5–15% of original size).
- `BulkMux` applies LZ4 compression (`PayloadCompressor`) and Cauchy `GF(256)` Reed-Solomon parity (`FecCodec`) so dropped packets recover without ARQ stalls.

---

## Build, Test & Run

```bash
# Run all unit & fuzz tests across core, sim, dashboard, kit, and app
gradle :core:test :sim:test :dashboard:test :kit:test :app:testDebugUnitTest

# Build Android debug APK
gradle :app:assembleDebug

# Run offline core+kit harness
sh tools/build-and-test.sh

# Run local Node.js mesh bridge server (default port 18080)
node server/test-server.js 18080

# Run live simulator dashboard in browser
gradle :dashboard:run
```

> **Real-Device Notes:** Live multi-phone radio discovery (Nearby, BLE, Wi-Fi Direct, LocalOnlyHotspot), real GPS sightings, audio PTT, and iOS Swift compilation require physical target hardware/Xcode; in headless CI and emulator environments all cryptographic, routing, codec, fuzz, and simulation paths are verified through JUnit 5, Gradle Android builds, and Node.js integration checks.

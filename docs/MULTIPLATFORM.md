# MeshNet Multi-Platform Architecture

MeshNet shares a single **30-byte binary wire format (v2)**, deterministic **Murmur3 `homeZone` hash**, **RFC 7748 X25519 + AES-256-GCM** encryption, and **3,517 language-neutral conformance vectors** (`conformance/vectors.txt`) across every platform.

## Platform Matrix

| Platform / Target | Module / Path | Capabilities |
|---|---|---|
| **Java 11+ / JVM / Desktop** | `core/`, `kit/`, `sim/`, `dashboard/` | Full `MeshNode`, `MeshEngine`, `X25519`, `FecCodec`, `BloomFilter`, `Ratchet` E2EE, `DtnBrain` ML, `SimNetwork` |
| **Android (API 24+)** | `app/`, `android-kit/` | Jetpack Compose Live Simulator + Engine Lab + Nearby/Wi-Fi Direct/SQLite/AMR-NB Voice |
| **C++17 (Embedded / ESP32 / Linux / macOS / Windows)** | `native/cpp/mesh_core.hpp`, `mesh_node.hpp` | Header-only zero-allocation `Packet`, `DedupCache`, `RouteCache`, `LocationDirectory`, `ZoneRouter`, `Pacer`, `StoreAndForward`, `GatewayManager`, `ChunkAssembler` |
| **C99 FFI ABI (iOS, Flutter, React Native, Go, Python, C#)** | `native/c/mesh_core_c.h`, `mesh_core_c.cpp` | Shared/static C library (`CMakeLists.txt`) exposing wire codec, caches, and pacer |
| **Rust (`no_std` + Wasm + C ABI)** | `native/rust/` | Memory-safe `Packet`, `home_zone`, caches, `ZoneRouter`, `StoreAndForward`, 3,517-vector conformance suite |
| **TypeScript / Browser / Node.js / React Native** | `sdk/ts/` | Zero-dependency ESM SDK + `MeshBridgeClient` + Node conformance test |
| **Swift (iOS 15+, macOS 13+, watchOS 8+)** | `sdk/swift/` | Swift Package (`MeshPacket`, `MeshZones`, `MeshBridgeClient`) |
| **Relay Bridge (Cloud / LAN)** | `server/mesh-bridge.js` | RAM-only zero-persistence binary long-poll relay |

## Conformance & Build Commands

- **Java / Android**: `gradle :core:compileJava :sim:compileJava :kit:compileJava :app:assembleDebug`
- **C++17 + C ABI**: `cmake -S native/cpp -B native/build && cmake --build native/build && ctest --test-dir native/build`
- **Rust**: `cargo test --manifest-path native/rust/Cargo.toml`
- **TypeScript / Node**: `node --test sdk/ts/test/conformance.test.mjs`
- **Swift**: `swift test --package-path sdk/swift`

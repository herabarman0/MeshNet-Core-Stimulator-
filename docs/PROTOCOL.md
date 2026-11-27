# MeshNet Wire & Security Protocol Specification (v2)

## 1. Binary Wire Header (30 Bytes, Big-Endian)

Every core frame transmitted over BLE, Wi-Fi Direct, LAN UDP/TCP, Nearby Connections, Ultrasonic, LoRa, or the Internet Bridge uses the fixed 30-byte `Packet` header:

| Offset | Size | Field | Description |
| --- | --- | --- | --- |
| `0` | `1` | `version` | Protocol version (`0x02`) |
| `1` | `1` | `attempt \| type` | High nibble: retry attempt (`0..15`), Low nibble: packet type (`1..10`) |
| `2` | `1` | `ttl` | Remaining hop budget (`0..255`) |
| `3` | `1` | `priority` | QoS lane (`0..255`: `<64` Bulk, `100` Normal, `180+` Urgent, `250+` SOS) |
| `4` | `8` | `msgId` | Signed 64-bit unique message identifier |
| `12` | `4` | `src` | Source 32-bit node identifier |
| `16` | `4` | `dst` | Destination node ID (`>= 0`) or Zone address (`0x80000000 \| zoneId`) |
| `20` | `4` | `srcZone` | Sender zone index (`-1` if unlocated) |
| `24` | `4` | `dstZone` | Target zone index (`-1` if unknown) |
| `28` | `2` | `payloadLen` | Unsigned 16-bit payload byte length (`0..65535`) |
| `30` | `N` | `payload` | Packet payload (`N = payloadLen`) |

### Packet Types (`type` = byte 1 low nibble)
- `1` `TYPE_DATA` — End-to-end encrypted data / onion envelope / mini-app frame
- `2` `TYPE_ACK` — End-to-end delivery receipt
- `3` `TYPE_HELLO` — Neighbor liveness, leaf battery state, mobility, PoW Sybil nonce
- `4` `TYPE_PUBLISH` — Local presence registration into home-zone directory
- `5` `TYPE_LOOKUP` — Node zone location query
- `6` `TYPE_LOOKUP_REPLY` — Zone lookup response
- `7` `TYPE_CHUNK` — Large blob / photo / OTA chunk with Reed-Solomon FEC parity
- `8` `TYPE_VOICE` — Real-time PTT audio & adaptive multi-hop RTP audio/video frame
- `9` `TYPE_GOSSIP` — ECDSA-signed zone membership / location summary digest
- `10` `TYPE_DHT` — Kademlia XOR DHT `STORE` / `FIND_VALUE` / `STORE_REPLY`

---

## 2. Routing, Discovery & Energy Policy

- **Zone Addressing**: If bit 31 of `dst` is set (`dst < 0`), `dstZone = dst & 0x7FFFFFFF` targets all peers in that zone (geofenced emergency broadcasts & DTN sweeps).
- **Adaptive Routing (`PathSelector` + `PredictiveAiRouter`)**: Blends B.A.T.M.A.N. Transmission Quality (`TQ`), AOMDV disjoint multi-path scores, EWMA RSSI/RTT/loss, `RelayFairnessLedger` credit weights, and an online logistic model trained on `ACK`/timeout outcomes.
- **Spillover & Multi-Radio (`SpillRadio` + `TurboScheduler`)**: 5 priority queues (`SOS`, `VOICE`, `URGENT`, `NORMAL`, `BULK`), MTU frame coalescing, and automatic spillover from low-power BLE/LoRa radios to high-bandwidth Wi-Fi Direct / LAN TCP links.
- **Energy Cutoff (`GatewayManager`)**: Nodes below `lowBattery` (`0.20`) enter **Leaf Mode**, declining non-urgent relay floods and yielding gateway elections until `lowBattery + batteryHysteresis`.

---

## 3. Cryptographic & Privacy Layers

- **Post-Quantum Hybrid Handshake (`HybridKeyExchange`)**: Combines NIST FIPS-203 `ML-KEM-768` (`ek = 1184 B`, `ct = 1088 B`) with `X25519` (`32 B`), mixed via HKDF-SHA256 to seed `DoubleRatchetSession` root key `RK0`.
- **3-Hop Onion Envelope (`OnionRouter`)**: Packets prefixed with `ONIO` carry layered ephemeral X25519 + AES-GCM headers; each hop peels one layer without seeing sender or final plaintext.
- **Signed ContactCards & Revocation (`ContactCard`)**: Offline identity verification via `meshnet://card?v=1&uid=...&nid=...&z=...&ed=...&x=...&kem=...&ts=...&sig=...` URIs, symmetric 60-digit SHAKE128 Safety Numbers, and signed offline `RevocationList`.
- **Sybil Protection & DPI Masking**: `SybilGuard` requires leading zero bits on `Keccak` PoW hashes for `HELLO`/floods plus per-peer token-bucket rate limiting; `TrafficMasker` applies fixed-bucket obfuscation.

---

## 4. Application Layer Protocols

- **Visually-Lossless Media & FEC (`ImageShrinker` + `BulkMux`)**: Client scales images (`<=1280px`), binary-searches encoder quality targeting `SSIM >= 0.965` (stripping EXIF GPS/device tags), LZ4-compresses, and stripes `GF(2^8)` Cauchy Reed-Solomon parity shards.
- **Lost Phone Finder (`LostPhone`)**:
  - `OP_BEACON (0x01)`: Unlinkable 15-minute rotating ECDH slot public keys.
  - `OP_REPORT (0x02)`: Finder seals `(lat, lon, accuracy, time, ownFlag)` to the beacon key and relays via DTN.
  - `OP_CMD (0x03)`: ECDSA-signed owner commands (`CMD_RING=1`, `CMD_LOCK=2`, `CMD_WIPE=3`) with replay nonce defense.
  - **Anti-Stalking**: Alerts user if an unfamiliar peer beacon persists across `>= 3` time slots.

---

## 5. Internet Bridge (`server/mesh-bridge.js`)

| Method & Path | Purpose |
| --- | --- |
| `POST /send?to=ID&from=ID` | Relay binary 30B+ packet to an online node (`200 ok`, `404 offline`, `413 too big`) |
| `GET /inbox/ID?wait=20&zone=Z` | Long-poll binary frames formatted as repeated `[u16 BE len][packet]` |
| `GET /events/ID?zone=Z` | SSE stream pushing base64 packet availability events for web/Swift clients |
| `POST /presence?node=ID&zone=Z` | Refresh node online presence and zone registration |
| `GET /where?ids=1,2,3` or `?zone=Z` | Query online status & zone mapping |
| `GET /stats` | JSON cluster health metrics (`onlineNodes`, `queuedPackets`, `zones`) |

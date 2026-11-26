# MeshNet Core

Pure-Java mesh engine + simulator. Package `com.herabarman.meshnet`.

## Modules
- `core`  – the engine (Java 11, no Android imports): Packet, DedupCache, MeshNode, GatewayManager,
            ZoneRouter, StoreAndForward, Crypto (AES-GCM, X25519), Transport / ServerBridge / Clock interfaces
- `sim`   – virtual-clock simulator, 1k to 100k+ nodes, churn, gateway failure, metrics, CSV export
- `dashboard` – live browser dashboard on top of the simulator (built-in JDK HTTP server + one HTML page, no dependencies)
- `android-adapter` – stub showing what BluetoothTransport has to implement
- `kit`   – the app engine on top of the core (pure Java): geohash zones, user-id mapping, end-to-end encryption with
            automatic key exchange, outbox with retries and receipts, `MeshClient` (text, bytes, `sendLarge`, battery, pacing)
- `android-kit` – Nearby radio, SQLite storage, location and battery feeders, the `MeshNet` facade (needs the Android SDK, not in the Gradle build)
- `server/mesh-bridge.js` – RAM-only relay for online users; `docs/KIT-GUIDE.bn.md` – start here for the app side (Bengali)
- `tools/build-and-test.sh` – builds core + kit and runs the kit tests (81 + 5); `gradle test` runs the JUnit suites of core, sim and dashboard

## Topology
1. Zone = P2P cluster (8-20 nodes, ring + random chords). Flooding only inside the zone (TTL + dedup).
2. **Full zone mesh (default up to 64 zones):** every zone has direct offline links to every other zone,
   `linksPerPair` (default 2) links per pair, each between different border nodes, so the load is spread
   and no single user going offline disconnects two zones. A dead border node is replaced by another
   member after the election delay. For N zones that is N(N-1)/2 zone pairs, so it only fits small and
   medium networks.
3. **Ring + shortcuts (automatic above 64 zones):** zones sit on a ring with links at +-1, +-2, +-4 ...,
   1-2 gateways per zone (elected by battery + signal + internet), greedy routing, about log2(zones) hops.
   This is what makes 1,000,000 nodes possible.
4. **Three delivery modes** (`DeliveryMode`, same engine, one switch):
   - `OFFLINE_ONLY`: the server is never used.
   - `OFFLINE_FIRST` (default): the mesh is the main path; the server helps when the mesh has no route
     right now and for retries after an ACK timeout.
   - `ONLINE_FIRST`: an online sender or border node goes straight through the server.
5. No route at all? Store-and-forward queue (priority, expiry, size cap), retried on a timer.

Physical note: a zone-to-zone link is a logical link between two phones. Two zones can only really link
offline if some of their users are within radio range (Bluetooth about 10-100 m, Wi-Fi Direct a bit more).
Zones far apart still reach each other, but through the zones in between.

Packet header is 30 bytes (it carries source and destination zone). AES-GCM nonce is derived from msgId+src, so encryption adds only the 16 byte tag.

## Discovery (no global knowledge)
With `discovery = true` a node needs no "node id -> zone" table. It learns everything itself:
- **HELLO:** neighbours greet each other when a link comes up and learn which zone the peer is in. That is how a node
  tells its zone peers from its border links, whatever links the radio happens to give it.
- **Zones in the header:** every packet carries source zone and destination zone. Relays never look anything up,
  and the receiver can ACK or reply without a lookup because the sender's zone is in the header it just got.
- **Location service:** every node has a *home zone* (a hash of its id, same answer on every node). It periodically
  PUBLISHes "I am in zone Z" there; the home zone keeps it on 3 nodes (`publishReplicas`). A node that wants to
  message someone whose zone it does not know sends a LOOKUP to that node's home zone, waits for the reply
  (retries with backoff, then reports `unknown-destination`), and caches the answer.
- In the simulator, discovery nodes are built with a `null` zone map, so any hidden use of global knowledge crashes
  the test instead of passing silently (`--discovery on|off|auto`; auto = on up to 5,000 nodes).

What you still provide: the zone a node belongs to (for example a map cell, or the user's choice) and the total
zone count. The engine does not invent zones. Moving to another zone: `MeshNode.setZone(...)`.

Measured at 1,000 nodes, 40 zones, same traffic (2 minutes, so start-up dominates the control share):

| | delivery | avg latency | p95 latency | tx per node | of which discovery |
|---|---|---|---|---|---|
| global zone map | 99.9% | 160 ms | 283 ms | 33 | 0 |
| discovery | 99.8% | 209 ms | 581 ms | 106 | 82,517 tx: hello 9.4k, publish 53k, lookup 15k, reply 4.5k |

Discovery costs latency on first contact (the lookup) and a start-up burst; publishing is about 50 transmissions per
node per publish (every 30 minutes by default), mostly one flood to find a border node.

## Congestion control, battery, large messages
**Congestion (`Pacer`).** All transmissions go through a token bucket (`txRatePerSec`, `txBurst`) with a priority queue:
ACKs and control first, then text, then bulk (priority below 100). Bulk cannot use the last 30% of the burst, so text always
has headroom. A full queue drops the lowest priority; packets that wait too long are dropped (the sender's ACK retry takes
over). While the queue backs up the flood jitter grows (AIMD, decays with time), bulk floods are shed, and relays flood
more gently (stay silent after 2 copies, forward to at most 2 neighbours). Off by default (`txRatePerSec = 0`).
Recommended on a phone: 30 packets per second, burst 20.

Measured (1,000 nodes, a burst of 4,000 messages in 8 s on top of normal traffic, radio able to send 20 packets/s per node,
half the burst bulk, half interactive text):

| | interactive delivered | bulk delivered | packets lost at the radio | p95 latency |
|---|---|---|---|---|
| no pacing | 87.0% | 83.3% | 240,534 | 9.4 s |
| pacing 15/s | 100% | 69.1% | 0 | 8.3 s |

With the radio at 10 packets/s (much heavier overload) interactive goes 63.8% to 85.3% but average latency reaches 14 s:
pacing protects what matters, it cannot create capacity. Without a burst the result is identical to no pacing
(100%, 164 ms), so it costs nothing while the radio keeps up. The radio model is a simplification: a per-node send limit,
no receiver-side collisions.

**Battery.** `setBattery(level)`: below `lowBattery` (20%, with 5% hysteresis) a node becomes a leaf. It still sends and
receives for itself but no longer relays others' floods, tells its neighbours by HELLO so they stop routing through it, and
is not elected gateway or border node (`GatewayManager.minBattery`). Measured with an invented drain per packet
(relative numbers only), 1,000 nodes, same traffic settings: nodes that ran flat 298 to 45 (moderate drain), 551 to 311
(heavy drain); delivery 93.7% to 96.1% and 58.6% to 73.2% (the two runs have slightly different senders, since flat nodes
cannot send).

**Large messages (`ChunkLayer`, `MeshEngine.sendLarge`).** Split into `chunkBytes` pieces (default 1 KB, fit it to your link),
at most `chunkWindow` (6) in flight. Each chunk is a normal engine message: routed, ACKed, retried alone. The receiver
reassembles out-of-order and repeated chunks, with caps on transfers and memory and a timeout. After a failure
`retryMissing(id)` sends only the chunks that were never confirmed. Overhead: 46 bytes of headers per chunk plus one 30 byte
ACK, about 7% at 1 KB chunks.

## Native core
`native/` holds a C++17 port of the packet format and caches, and `conformance/vectors.txt` holds 3,517 test vectors
from this Java engine that any port must reproduce bit for bit. See `native/README.md` (measurements included).

## Using it in an app (developer guide)
Simplest way, `MeshEngine` (thread-safe, handles timers and threads for you):

    MeshEngine engine = MeshEngine.builder(myNodeId)
        .zone(myZoneId).zoneCount(40)                         // zones are discovered, no zone map needed
        .transport(myBluetoothTransport)                      // you implement 3 methods
        .server(myWebSocketBridge)                            // optional internet helper
        .mode(DeliveryMode.OFFLINE_FIRST)
        .crypto(new AesGcmCrypto(key32))                      // optional
        .storage(new FileStorage(new File(filesDir, "mesh"))) // optional: survive restarts
        .onMessage((from, bytes) -> ...)
        .onDelivery(listener)                                 // onAcked / onFailed
        .onLarge(largeListener)                               // photos, voice: engine.sendLarge(dst, bytes, priority)
        .build();
    engine.start();                  // restores queued + unconfirmed messages from disk
    long id = engine.send(otherNodeId, bytes);
    engine.setBattery(0.15);         // below 20% the engine stops relaying for others
    engine.stop();

Callbacks run on the engine thread: post to your UI thread before touching views.

**Every part also works alone:**

| class | use it for |
|---|---|
| `Packet` | the 30 byte header binary wire format (encode / decode, type, attempt, TTL) |
| `DedupCache` | "have I seen this message id" with expiry and size cap |
| `RouteCache` | LRU next-hop table with expiry |
| `Pacer` | token-bucket pacing with priorities and congestion feedback, usable on any send path |
| `ChunkLayer` | split / reassemble large messages with a window and missing-chunk retry |
| `StoreAndForward` | priority queue for messages without a route; optionally disk-backed |
| `StorageAdapter` | implement over SQLite / Room / MMKV; `FileStorage` and `MemoryStorage` included |
| `GatewayManager` | score and elect gateways, re-elect without flapping |
| `ZoneRouter` | zone ring, shortcut links, greedy next hop |
| `LocationDirectory` | learned "node -> zone" table with expiry, plus the home-zone hash |
| `AesGcmCrypto`, `KeyExchange` | payload encryption with 16 byte overhead, X25519 |
| `MeshNode` | the whole engine without threading, for your own scheduler or for simulation |
| `Transport`, `ServerBridge`, `Clock` | the three things you plug in |
| `sim` module | test routing changes on 1,000 to 1,000,000 virtual nodes before shipping them |

Persistence: queued packets (`sf-*`) and messages sent but not yet confirmed (`out-*`) are written to the
StorageAdapter. After a crash or restart `start()` brings them back and re-sends the unconfirmed ones.
Not persisted yet: learned routes (soft state, relearned in one flood) and the "already delivered" list.

## Flood optimisation (inside a zone)
Relays wait a random 0..`floodJitterMs` before forwarding. While waiting they note which neighbours already
sent them the packet and skip those (sender suppression, loses nothing). If `counterThreshold` neighbours
already sent it, the relay stays silent (counter suppression). Gateways never suppress.

## Route learning + ACK + fallback
- The neighbour a packet first arrived from is the way back to its sender. Every node on a flood remembers
  that (`RouteCache`, LRU, expiring), so one flood teaches the whole zone how to reach the sender.
  Replies and follow-ups then go as unicast. Packets for another zone are unicast toward a gateway.
- The destination answers with a tiny ACK (header only, 30 bytes). No ACK after `ackTimeoutMs` (doubling each retry):
  the sender deletes its learned route and re-sends by flood (`attempt` 1, 2, ...). A retransmission
  always floods and has its own dedup key, so a stale route can never block it. The destination
  delivers each message once and re-ACKs repeats.
- A learned next hop that is no longer a live neighbour is skipped immediately (no timeout needed).
- Switches: `routeLearning`, `ackEnabled`, `maxRetries`, `ackTimeoutMs`; simulator `--classic true` = plain flooding.

Measured (10,000 nodes, 3 seeds, 20% online, 1% churn, 5% gateway failure). tx/msg includes ACK traffic:

| traffic | variant | delivery | tx/message | avg latency |
|---|---|---|---|---|
| 6-message conversations | classic flood | 99.93% | 52.4 | 216 ms |
| | suppression only | 99.93% | 47.5 | 250 ms |
| | **routes + ACK (default)** | **99.98%** | **22.1** | 241 ms |
| | routes, no ACK | 99.82% | 14.1 | 238 ms |
| one-shot (every message a new pair) | classic flood | 100.00% | 53.6 | 217 ms |
| | **routes + ACK (default)** | 99.95% | 43.8 | 254 ms |

Without ACK the routes are cheaper but fragile (delivery drops); ACK + fallback costs about 8 tx/message and fixes it.
ACK on top of plain flooding is a bad deal (96.9 tx/message): the ACK itself gets flooded back.
Reproduce: `java -cp ... com.herabarman.meshnet.sim.FloodBench 10000 3`.

## Dashboard
    gradle :dashboard:run                      # then open http://127.0.0.1:8080 in the phone/PC browser
    java -cp core/build/classes/java/main:sim/build/classes/java/main:dashboard/build/classes/java/main:dashboard/build/resources/main \
         com.herabarman.meshnet.dashboard.DashboardMain --port 8080

Shows zones (dashed circles), nodes (green = online, grey = offline, red ring = down), gateways (white ring, ring mode only),
zone-to-zone offline links (amber), and the learned route between the selected nodes A and B (blue).
Tap a node for A, another for B. Buttons: local / remote / A to B message (packets fly as dots, ACKs in blue,
server path in purple), kill A, revive A, kill the gateways of A's zone, pause. Settings: nodes, zone size,
online %, gateways, flood suppression / route learning / ACK on or off (these rebuild the network), and live
sliders for packet loss, churn, background traffic, speed (0.1x to 20x).
It binds to 127.0.0.1 only (`--host 0.0.0.0` to open it to your network, but the page can kill nodes).
Keep nodes under about 600 so the picture stays readable.

Measured (1,000 nodes in 40 zones, same traffic, 20% online, churn and gateway failures on):

| backbone | server | delivery | avg hops | avg latency | tx/message |
|---|---|---|---|---|---|
| ring + shortcuts | first | 99.9% | 4.93 | 213 ms | 34.6 |
| ring + shortcuts | helps only | 99.9% | 5.40 | 229 ms | 28.4 |
| full zone mesh | first | 99.9% | 4.09 | 160 ms | 30.8 |
| **full zone mesh** | **helps only (default)** | 99.9% | 4.27 | 164 ms | 24.2 |

100,000 nodes (ring, 6,666 zones): 100% delivered, 6.3 hops, 330 ms.

## Run
    gradle test
    gradle :sim:run --args="--nodes 1000,10000,100000 --messages 2000 --csv results.csv"

Options: --backbone auto|full|chord --links 2 --mode offline-first --zoneSize --gateways --online --churn --gwFail --messages --local --loss --seed --encrypt --csv

## Porting to the app
Implement `Transport` over Bluetooth/Wi-Fi Direct (peerId = stable int per device), a `Clock` over Handler,
and a `ServerBridge` over your WebSocket. Then `new MeshNode(...)`. The routing code does not change.

## Known simplifications (simulator)
- The gateway directory (who is gateway of zone X) is read from global state. The real app needs gossip/DHT for that.
- Any two gateways can link. In reality backbone links need internet or long-range radio; Bluetooth cannot span zones 3 km apart.
- Phase 2: dashboard (live topology + sliders).

---
## Added: kit (app engine on top of the unchanged core)
`core/` is byte-for-byte what it was. New folders: `kit/` (pure Java: geohash zones, user-id mapping, E2EE with
automatic key exchange, outbox/retries/receipts, `MeshClient`), `android-kit/` (Nearby radio, SQLite, location,
`MeshNet` facade), `server/mesh-bridge.js` (RAM-only relay for online users), `tools/build-and-test.sh`.
Also: DTN data-mule with an on-device learner, signed geofenced alerts, CRDT groups and channels, UDP desktop node, LoRa protocol layer. Start with `docs/KIT-GUIDE.bn.md`.

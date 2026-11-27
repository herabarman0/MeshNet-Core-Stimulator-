import Foundation

public enum MeshConstants {
    public static let version: UInt8 = 2
    public static let headerSize: Int = 30
    public static let maxPayload: Int = 0xFFFF
    public static let unknownZone: Int32 = -1
}

public enum MeshPacketType: UInt8 {
    case data = 1
    case ack = 2
    case hello = 3
    case publish = 4
    case lookup = 5
    case lookupReply = 6
    case chunk = 7
    case voice = 8
    case gossip = 9
    case dht = 10
}

public struct MeshPacket: Equatable {
    public var type: UInt8
    public var attempt: UInt8
    public var ttl: UInt8
    public var priority: UInt8
    public var msgId: Int64
    public var src: Int32
    public var dst: Int32
    public var srcZone: Int32
    public var dstZone: Int32
    public var payload: Data

    public init(type: UInt8, attempt: UInt8 = 0, ttl: UInt8 = 32, priority: UInt8 = 100,
                msgId: Int64, src: Int32, dst: Int32, srcZone: Int32, dstZone: Int32, payload: Data = Data()) {
        self.type = type
        self.attempt = attempt
        self.ttl = ttl
        self.priority = priority
        self.msgId = msgId
        self.src = src
        self.dst = dst
        self.srcZone = srcZone
        self.dstZone = dstZone
        self.payload = payload
    }

    public func encode() -> Data {
        let plen = min(payload.count, MeshConstants.maxPayload)
        var data = Data(capacity: MeshConstants.headerSize + plen)
        data.append(MeshConstants.version)
        data.append(((attempt & 0x0F) << 4) | (type & 0x0F))
        data.append(ttl)
        data.append(priority)
        appendBE(msgId, to: &data)
        appendBE(src, to: &data)
        appendBE(dst, to: &data)
        appendBE(srcZone, to: &data)
        appendBE(dstZone, to: &data)
        appendBE(UInt16(plen), to: &data)
        data.append(payload.prefix(plen))
        return data
    }

    public static func decode(_ data: Data) -> MeshPacket? {
        guard data.count >= MeshConstants.headerSize, data[data.startIndex] == MeshConstants.version else {
            return nil
        }
        let base = data.startIndex
        let plen = Int(readU16(data, base + 28))
        guard data.count == MeshConstants.headerSize + plen else { return nil }
        return MeshPacket(
            type: data[base + 1] & 0x0F,
            attempt: data[base + 1] >> 4,
            ttl: data[base + 2],
            priority: data[base + 3],
            msgId: Int64(bitPattern: readU64(data, base + 4)),
            src: Int32(bitPattern: readU32(data, base + 12)),
            dst: Int32(bitPattern: readU32(data, base + 16)),
            srcZone: Int32(bitPattern: readU32(data, base + 20)),
            dstZone: Int32(bitPattern: readU32(data, base + 24)),
            payload: data.subdata(in: (base + MeshConstants.headerSize)..<(base + data.count))
        )
    }

    /// Splits a framed `[u16 BE len][packet]...` buffer returned by `/inbox/<id>` into decoded packets.
    public static func decodeInboxBatch(_ batch: Data) -> [MeshPacket] {
        var packets: [MeshPacket] = []
        var offset = batch.startIndex
        while offset + 2 <= batch.endIndex {
            let len = Int(readU16(batch, offset))
            offset += 2
            guard offset + len <= batch.endIndex else { break }
            let slice = batch.subdata(in: offset..<(offset + len))
            if let pkt = decode(slice) {
                packets.append(pkt)
            }
            offset += len
        }
        return packets
    }

    private func appendBE<T: FixedWidthInteger>(_ val: T, to data: inout Data) {
        var be = val.bigEndian
        withUnsafeBytes(of: &be) { data.append(contentsOf: $0) }
    }

    private static func readU16(_ d: Data, _ o: Int) -> UInt16 {
        (UInt16(d[o]) << 8) | UInt16(d[o + 1])
    }

    private static func readU32(_ d: Data, _ o: Int) -> UInt32 {
        (UInt32(d[o]) << 24) | (UInt32(d[o + 1]) << 16) | (UInt32(d[o + 2]) << 8) | UInt32(d[o + 3])
    }

    private static func readU64(_ d: Data, _ o: Int) -> UInt64 {
        (UInt64(readU32(d, o)) << 32) | UInt64(readU32(d, o + 4))
    }
}

public enum MeshZones {
    public static func zoneAddress(_ zone: Int32) -> Int32 {
        Int32(bitPattern: 0x8000_0000 | UInt32(bitPattern: zone))
    }

    public static func isZoneAddress(_ dst: Int32) -> Bool {
        dst < 0
    }

    public static func homeZone(nodeId: Int32, zoneCount: Int32) -> Int32 {
        var h = UInt32(bitPattern: nodeId)
        h ^= h >> 16
        h = h &* 0x85EB_CA6B
        h ^= h >> 13
        h = h &* 0xC2B2_AE35
        h ^= h >> 16
        let zc = zoneCount < 1 ? UInt32(1) : UInt32(bitPattern: zoneCount)
        return Int32(bitPattern: h % zc)
    }

    public static func ringDistance(_ a: Int32, _ b: Int32, zoneCount: Int32) -> Int32 {
        let d = abs(a - b)
        return min(d, zoneCount - d)
    }
}

public struct ParsedContactCard: Equatable {
    public let version: Int
    public let userId: String
    public let nodeId: Int32
    public let zone: Int32
    public let identityPubBase64Url: String
    public let x25519PubBase64Url: String
    public let signatureBase64Url: String

    public static func parse(uri: String) -> ParsedContactCard? {
        guard uri.hasPrefix("meshnet://card?"),
              let comps = URLComponents(string: uri),
              let items = comps.queryItems else {
            return nil
        }
        var dict: [String: String] = [:]
        for item in items {
            if let v = item.value { dict[item.name] = v }
        }
        guard let vStr = dict["v"], let ver = Int(vStr), ver == 1,
              let uidB64 = dict["uid"], let uidData = base64UrlDecode(uidB64),
              let uid = String(data: uidData, encoding: .utf8), !uid.isEmpty,
              let nidStr = dict["nid"], let nid = Int32(nidStr),
              let zStr = dict["z"], let zone = Int32(zStr),
              let ed = dict["ed"], let x = dict["x"], let sig = dict["sig"] else {
            return nil
        }
        return ParsedContactCard(
            version: ver, userId: uid, nodeId: nid, zone: zone,
            identityPubBase64Url: ed, x25519PubBase64Url: x, signatureBase64Url: sig
        )
    }

    private static func base64UrlDecode(_ str: String) -> Data? {
        var s = str.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while s.count % 4 != 0 { s.append("=") }
        return Data(base64Encoded: s)
    }
}

public struct MeshNodePresence: Equatable {
    public let nodeId: Int32
    public let online: Bool
    public let zone: Int32
}

public final class MeshBridgeClient {
    private let baseURL: URL
    private let token: String?
    private let session: URLSession

    public init(baseURL: URL, token: String? = nil, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.token = token
        self.session = session
    }

    public func sendPacket(to: Int32, from: Int32, bytes: Data) async throws -> Bool {
        let url = baseURL.appendingPathComponent("send")
        var comps = URLComponents(url: url, resolvingAgainstBaseURL: false)!
        comps.queryItems = [
            URLQueryItem(name: "to", value: String(to)),
            URLQueryItem(name: "from", value: String(from))
        ]
        var req = URLRequest(url: comps.url!)
        req.httpMethod = "POST"
        req.httpBody = bytes
        req.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        if let token { req.setValue(token, forHTTPHeaderField: "X-Mesh-Token") }
        let (_, resp) = try await session.data(for: req)
        return (resp as? HTTPURLResponse)?.statusCode == 200
    }

    public func announcePresence(node: Int32, zone: Int32) async throws -> Bool {
        let url = baseURL.appendingPathComponent("presence")
        var comps = URLComponents(url: url, resolvingAgainstBaseURL: false)!
        comps.queryItems = [
            URLQueryItem(name: "node", value: String(node)),
            URLQueryItem(name: "zone", value: String(zone))
        ]
        var req = URLRequest(url: comps.url!)
        req.httpMethod = "POST"
        if let token { req.setValue(token, forHTTPHeaderField: "X-Mesh-Token") }
        let (_, resp) = try await session.data(for: req)
        return (resp as? HTTPURLResponse)?.statusCode == 200
    }

    public func pollInbox(node: Int32, zone: Int32, waitSeconds: Int = 20) async throws -> [MeshPacket] {
        let url = baseURL.appendingPathComponent("inbox").appendingPathComponent(String(node))
        var comps = URLComponents(url: url, resolvingAgainstBaseURL: false)!
        comps.queryItems = [
            URLQueryItem(name: "wait", value: String(max(0, min(25, waitSeconds)))),
            URLQueryItem(name: "zone", value: String(zone))
        ]
        var req = URLRequest(url: comps.url!)
        req.httpMethod = "GET"
        if let token { req.setValue(token, forHTTPHeaderField: "X-Mesh-Token") }
        let (data, resp) = try await session.data(for: req)
        guard (resp as? HTTPURLResponse)?.statusCode == 200 else { return [] }
        return MeshPacket.decodeInboxBatch(data)
    }

    public func queryWhere(ids: [Int32]) async throws -> [MeshNodePresence] {
        let url = baseURL.appendingPathComponent("where")
        var comps = URLComponents(url: url, resolvingAgainstBaseURL: false)!
        comps.queryItems = [URLQueryItem(name: "ids", value: ids.map(String.init).joined(separator: ","))]
        var req = URLRequest(url: comps.url!)
        req.httpMethod = "GET"
        if let token { req.setValue(token, forHTTPHeaderField: "X-Mesh-Token") }
        let (data, resp) = try await session.data(for: req)
        guard (resp as? HTTPURLResponse)?.statusCode == 200,
              let text = String(data: data, encoding: .utf8) else { return [] }
        return text.split(separator: "\n").compactMap { line -> MeshNodePresence? in
            let parts = line.split(separator: " ")
            guard parts.count == 3,
                  let nid = Int32(parts[0]),
                  let on = Int(parts[1]),
                  let z = Int32(parts[2]) else { return nil }
            return MeshNodePresence(nodeId: nid, online: on == 1, zone: z)
        }
    }
}

public actor MeshInboxPoller {
    private let bridge: MeshBridgeClient
    private let nodeId: Int32
    private var zone: Int32
    private var task: Task<Void, Never>?

    public init(bridge: MeshBridgeClient, nodeId: Int32, zone: Int32) {
        self.bridge = bridge
        self.nodeId = nodeId
        self.zone = zone
    }

    public func updateZone(_ newZone: Int32) {
        self.zone = newZone
    }

    public func start(onPackets: @escaping @Sendable ([MeshPacket]) -> Void) {
        guard task == nil else { return }
        task = Task { [weak self] in
            while let self, !Task.isCancelled {
                let z = await self.zone
                if let batch = try? await self.bridge.pollInbox(node: self.nodeId, zone: z, waitSeconds: 20),
                   !batch.isEmpty {
                    onPackets(batch)
                } else {
                    try? await Task.sleep(nanoseconds: 1_000_000_000)
                }
            }
        }
    }

    public func stop() {
        task?.cancel()
        task = nil
    }
}

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
}

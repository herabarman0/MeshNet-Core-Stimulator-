import XCTest
@testable import MeshNet

final class MeshNetTests: XCTestCase {
    func testPacketRoundTripAndHomeZone() {
        let p = MeshPacket(
            type: MeshPacketType.data.rawValue,
            attempt: 2,
            ttl: 32,
            priority: 180,
            msgId: -7,
            src: 101,
            dst: 202,
            srcZone: 3,
            dstZone: 9,
            payload: Data("hello swift".utf8)
        )
        let raw = p.encode()
        XCTAssertEqual(raw.count, MeshConstants.headerSize + 11)
        let decoded = MeshPacket.decode(raw)
        XCTAssertEqual(decoded, p)
        XCTAssertEqual(MeshZones.homeZone(nodeId: 12345, zoneCount: 40), MeshZones.homeZone(nodeId: 12345, zoneCount: 40))
        XCTAssertTrue(MeshZones.isZoneAddress(MeshZones.zoneAddress(7)))
    }
}

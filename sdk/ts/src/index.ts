export const MESH_VERSION = 2;
export const HEADER_SIZE = 30;
export const MAX_PAYLOAD = 0xffff;
export const UNKNOWN_ZONE = -1;

export const PacketType = {
  DATA: 1,
  ACK: 2,
  HELLO: 3,
  PUBLISH: 4,
  LOOKUP: 5,
  LOOKUP_REPLY: 6,
  CHUNK: 7,
  VOICE: 8,
  GOSSIP: 9,
  DHT: 10,
} as const;

export interface MeshPacket {
  type: number;
  attempt: number;
  ttl: number;
  priority: number;
  msgId: bigint;
  src: number;
  dst: number;
  srcZone: number;
  dstZone: number;
  payload: Uint8Array;
}

export function zoneAddress(zone: number): number {
  return (0x80000000 | (zone | 0)) >> 0;
}

export function isZoneAddress(dst: number): boolean {
  return (dst | 0) < 0;
}

/** Murmur3 finalizer home-zone hash matching LocationDirectory.homeZone bit-for-bit. */
export function homeZone(nodeId: number, zoneCount: number): number {
  let h = nodeId >>> 0;
  h ^= h >>> 16;
  h = Math.imul(h, 0x85ebca6b) >>> 0;
  h ^= h >>> 13;
  h = Math.imul(h, 0xc2b2ae35) >>> 0;
  h ^= h >>> 16;
  const zc = zoneCount < 1 ? 1 : zoneCount >>> 0;
  return (h % zc) | 0;
}

export function encodePacket(p: MeshPacket): Uint8Array {
  const plen = Math.min(p.payload.length, MAX_PAYLOAD);
  const out = new Uint8Array(HEADER_SIZE + plen);
  const view = new DataView(out.buffer);
  out[0] = MESH_VERSION;
  out[1] = ((p.attempt & 0x0f) << 4) | (p.type & 0x0f);
  out[2] = p.ttl & 0xff;
  out[3] = p.priority & 0xff;
  view.setBigInt64(4, p.msgId, false);
  view.setInt32(12, p.src, false);
  view.setInt32(16, p.dst, false);
  view.setInt32(20, p.srcZone, false);
  view.setInt32(24, p.dstZone, false);
  view.setUint16(28, plen, false);
  out.set(p.payload.subarray(0, plen), HEADER_SIZE);
  return out;
}

export function decodePacket(data: Uint8Array): MeshPacket | null {
  if (data.length < HEADER_SIZE || data[0] !== MESH_VERSION) return null;
  const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
  const plen = view.getUint16(28, false);
  if (data.length !== HEADER_SIZE + plen) return null;
  return {
    type: data[1] & 0x0f,
    attempt: data[1] >>> 4,
    ttl: data[2],
    priority: data[3],
    msgId: view.getBigInt64(4, false),
    src: view.getInt32(12, false),
    dst: view.getInt32(16, false),
    srcZone: view.getInt32(20, false),
    dstZone: view.getInt32(24, false),
    payload: data.slice(HEADER_SIZE),
  };
}

export class DedupCache {
  private entries = new Map<bigint, number>();
  constructor(private readonly maxEntries: number, private readonly ttlMs: number) {}

  firstSeen(id: bigint, nowMs: number): boolean {
    const exp = this.entries.get(id);
    if (exp !== undefined) {
      if (exp > nowMs) return false;
      this.entries.delete(id);
    }
    this.entries.set(id, nowMs + this.ttlMs);
    while (this.entries.size > this.maxEntries) {
      const oldest = this.entries.keys().next().value;
      if (oldest === undefined) break;
      this.entries.delete(oldest);
    }
    return true;
  }
}

export class RouteCache {
  private entries = new Map<number, { nextHop: number; expires: number }>();
  constructor(private readonly maxEntries: number, private readonly ttlMs: number) {}

  put(key: number, nextHop: number, nowMs: number): void {
    this.entries.delete(key);
    this.entries.set(key, { nextHop, expires: nowMs + this.ttlMs });
    while (this.entries.size > this.maxEntries) {
      const oldest = this.entries.keys().next().value;
      if (oldest === undefined) break;
      this.entries.delete(oldest);
    }
  }

  get(key: number, nowMs: number): number {
    const e = this.entries.get(key);
    if (!e) return -1;
    if (e.expires <= nowMs) {
      this.entries.delete(key);
      return -1;
    }
    this.entries.delete(key);
    this.entries.set(key, e);
    return e.nextHop;
  }
}

export function ringDistance(a: number, b: number, zoneCount: number): number {
  const d = Math.abs(a - b);
  return Math.min(d, zoneCount - d);
}

/** HTTP bridge client compatible with server/mesh-bridge.js in Browser, Node.js, and React Native. */
export class MeshBridgeClient {
  constructor(private readonly baseUrl: string, private readonly token?: string) {}

  private headers(extra?: Record<string, string>): Record<string, string> {
    const h: Record<string, string> = { ...(extra ?? {}) };
    if (this.token) h["X-Mesh-Token"] = this.token;
    return h;
  }

  async sendPacket(to: number, from: number, packetBytes: Uint8Array): Promise<boolean> {
    const r = await fetch(`${this.baseUrl}/send?to=${to}&from=${from}`, {
      method: "POST",
      headers: this.headers({ "Content-Type": "application/octet-stream" }),
      body: packetBytes,
    });
    return r.ok;
  }

  async pollInbox(nodeId: number, zone: number, waitSec = 20): Promise<Uint8Array[]> {
    const r = await fetch(`${this.baseUrl}/inbox/${nodeId}?wait=${waitSec}&zone=${zone}`, {
      headers: this.headers(),
    });
    if (!r.ok) return [];
    const buf = new Uint8Array(await r.arrayBuffer());
    const view = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
    const out: Uint8Array[] = [];
    let off = 0;
    while (off + 2 <= buf.length) {
      const len = view.getUint16(off, false);
      off += 2;
      if (off + len > buf.length) break;
      out.push(buf.slice(off, off + len));
      off += len;
    }
    return out;
  }
}

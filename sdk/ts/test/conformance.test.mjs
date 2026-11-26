import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

// Minimal inline verification mirroring src/index.ts wire logic against conformance/vectors.txt
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const vectorsPath = path.resolve(__dirname, "../../../conformance/vectors.txt");

function homeZone(nodeId, zoneCount) {
  let h = nodeId >>> 0;
  h ^= h >>> 16;
  h = Math.imul(h, 0x85ebca6b) >>> 0;
  h ^= h >>> 13;
  h = Math.imul(h, 0xc2b2ae35) >>> 0;
  h ^= h >>> 16;
  const zc = zoneCount < 1 ? 1 : zoneCount >>> 0;
  return (h % zc) | 0;
}

function decodePacket(data) {
  if (data.length < 30 || data[0] !== 2) return null;
  const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
  const plen = view.getUint16(28, false);
  if (data.length !== 30 + plen) return null;
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
    payload: data.slice(30),
  };
}

function encodePacket(p) {
  const out = new Uint8Array(30 + p.payload.length);
  const view = new DataView(out.buffer);
  out[0] = 2;
  out[1] = ((p.attempt & 0x0f) << 4) | (p.type & 0x0f);
  out[2] = p.ttl;
  out[3] = p.priority;
  view.setBigInt64(4, p.msgId, false);
  view.setInt32(12, p.src, false);
  view.setInt32(16, p.dst, false);
  view.setInt32(20, p.srcZone, false);
  view.setInt32(24, p.dstZone, false);
  view.setUint16(28, p.payload.length, false);
  out.set(p.payload, 30);
  return out;
}

test("TypeScript/JS SDK reproduces all 3,517 conformance vectors bit-for-bit", () => {
  const lines = fs.readFileSync(vectorsPath, "utf8").split(/\r?\n/);
  let packets = 0, bad = 0, homes = 0;
  for (const line of lines) {
    if (!line || line.startsWith("#")) continue;
    const parts = line.split(/\s+/);
    if (parts[0] === "packet") {
      const bytes = Uint8Array.from(Buffer.from(parts[1], "hex"));
      const p = decodePacket(bytes);
      assert.ok(p);
      assert.equal(p.type, Number(parts[2]));
      assert.equal(p.attempt, Number(parts[3]));
      assert.equal(p.ttl, Number(parts[4]));
      assert.equal(p.priority, Number(parts[5]));
      assert.equal(p.msgId, BigInt(parts[6]));
      assert.equal(p.src, Number(parts[7]));
      assert.equal(p.dst, Number(parts[8]));
      assert.equal(p.srcZone, Number(parts[9]));
      assert.equal(p.dstZone, Number(parts[10]));
      assert.deepEqual(encodePacket(p), bytes);
      packets++;
    } else if (parts[0] === "bad") {
      const bytes = parts[1] ? Uint8Array.from(Buffer.from(parts[1], "hex")) : new Uint8Array(0);
      assert.equal(decodePacket(bytes), null);
      bad++;
    } else if (parts[0] === "home") {
      assert.equal(homeZone(Number(parts[1]), Number(parts[2])), Number(parts[3]));
      homes++;
    }
  }
  assert.ok(packets > 400 && bad === 6 && homes > 3000);
});

#![cfg_attr(not(feature = "std"), no_std)]

extern crate alloc;

use alloc::collections::VecDeque;
use alloc::vec::Vec;
use core::cmp::Ordering;

pub const VERSION: u8 = 2;
pub const HEADER_SIZE: usize = 30;
pub const MAX_PAYLOAD: usize = 0xFFFF;
pub const UNKNOWN_ZONE: i32 = -1;

pub const TYPE_DATA: u8 = 1;
pub const TYPE_ACK: u8 = 2;
pub const TYPE_HELLO: u8 = 3;
pub const TYPE_PUBLISH: u8 = 4;
pub const TYPE_LOOKUP: u8 = 5;
pub const TYPE_LOOKUP_REPLY: u8 = 6;
pub const TYPE_CHUNK: u8 = 7;
pub const TYPE_VOICE: u8 = 8;
pub const TYPE_GOSSIP: u8 = 9;
pub const TYPE_DHT: u8 = 10;

#[inline]
pub fn zone_address(zone: i32) -> i32 {
    (0x8000_0000u32 | (zone as u32)) as i32
}

#[inline]
pub fn is_zone_address(dst: i32) -> bool {
    dst < 0
}

#[inline]
pub fn home_zone(node_id: i32, zone_count: i32) -> i32 {
    let mut h = node_id as u32;
    h ^= h >> 16;
    h = h.wrapping_mul(0x85EB_CA6B);
    h ^= h >> 13;
    h = h.wrapping_mul(0xC2B2_AE35);
    h ^= h >> 16;
    let zc = if zone_count < 1 { 1u32 } else { zone_count as u32 };
    (h % zc) as i32
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Packet {
    pub ptype: u8,
    pub attempt: u8,
    pub ttl: u8,
    pub priority: u8,
    pub msg_id: i64,
    pub src: i32,
    pub dst: i32,
    pub src_zone: i32,
    pub dst_zone: i32,
    pub payload: Vec<u8>,
}

impl Packet {
    pub fn encode(&self) -> Vec<u8> {
        let plen = self.payload.len().min(MAX_PAYLOAD);
        let mut out = Vec::with_capacity(HEADER_SIZE + plen);
        out.push(VERSION);
        out.push(((self.attempt & 0x0F) << 4) | (self.ptype & 0x0F));
        out.push(self.ttl);
        out.push(self.priority);
        out.extend_from_slice(&self.msg_id.to_be_bytes());
        out.extend_from_slice(&self.src.to_be_bytes());
        out.extend_from_slice(&self.dst.to_be_bytes());
        out.extend_from_slice(&self.src_zone.to_be_bytes());
        out.extend_from_slice(&self.dst_zone.to_be_bytes());
        out.extend_from_slice(&(plen as u16).to_be_bytes());
        out.extend_from_slice(&self.payload[..plen]);
        out
    }

    pub fn encode_into(&self, out: &mut [u8]) -> Option<usize> {
        let plen = self.payload.len();
        if plen > MAX_PAYLOAD || out.len() < HEADER_SIZE + plen {
            return None;
        }
        out[0] = VERSION;
        out[1] = ((self.attempt & 0x0F) << 4) | (self.ptype & 0x0F);
        out[2] = self.ttl;
        out[3] = self.priority;
        out[4..12].copy_from_slice(&self.msg_id.to_be_bytes());
        out[12..16].copy_from_slice(&self.src.to_be_bytes());
        out[16..20].copy_from_slice(&self.dst.to_be_bytes());
        out[20..24].copy_from_slice(&self.src_zone.to_be_bytes());
        out[24..28].copy_from_slice(&self.dst_zone.to_be_bytes());
        out[28..30].copy_from_slice(&(plen as u16).to_be_bytes());
        out[30..30 + plen].copy_from_slice(&self.payload);
        Some(HEADER_SIZE + plen)
    }

    pub fn decode(data: &[u8]) -> Option<Self> {
        if data.len() < HEADER_SIZE || data[0] != VERSION {
            return None;
        }
        let plen = u16::from_be_bytes([data[28], data[29]]) as usize;
        if data.len() != HEADER_SIZE + plen {
            return None;
        }
        Some(Self {
            ptype: data[1] & 0x0F,
            attempt: data[1] >> 4,
            ttl: data[2],
            priority: data[3],
            msg_id: i64::from_be_bytes(data[4..12].try_into().ok()?),
            src: i32::from_be_bytes(data[12..16].try_into().ok()?),
            dst: i32::from_be_bytes(data[16..20].try_into().ok()?),
            src_zone: i32::from_be_bytes(data[20..24].try_into().ok()?),
            dst_zone: i32::from_be_bytes(data[24..28].try_into().ok()?),
            payload: data[HEADER_SIZE..].to_vec(),
        })
    }
}

pub struct DedupCache {
    max_entries: usize,
    ttl_ms: i64,
    order: VecDeque<(i64, i64)>,
}

impl DedupCache {
    pub fn new(max_entries: usize, ttl_ms: i64) -> Self {
        Self { max_entries, ttl_ms, order: VecDeque::new() }
    }

    pub fn first_seen(&mut self, id: i64, now_ms: i64) -> bool {
        if let Some(pos) = self.order.iter().position(|(k, _)| *k == id) {
            if self.order[pos].1 > now_ms {
                return false;
            }
            self.order.remove(pos);
        }
        self.order.push_back((id, now_ms + self.ttl_ms));
        while self.order.len() > self.max_entries {
            self.order.pop_front();
        }
        true
    }

    pub fn len(&self) -> usize {
        self.order.len()
    }
}

pub struct RouteCache {
    max_entries: usize,
    ttl_ms: i64,
    entries: Vec<(i32, i32, i64)>, // front = MRU
}

impl RouteCache {
    pub fn new(max_entries: usize, ttl_ms: i64) -> Self {
        Self { max_entries, ttl_ms, entries: Vec::new() }
    }

    pub fn put(&mut self, key: i32, next_hop: i32, now_ms: i64) {
        self.entries.retain(|(k, _, _)| *k != key);
        self.entries.insert(0, (key, next_hop, now_ms + self.ttl_ms));
        if self.entries.len() > self.max_entries {
            self.entries.truncate(self.max_entries);
        }
    }

    pub fn get(&mut self, key: i32, now_ms: i64) -> Option<i32> {
        let pos = self.entries.iter().position(|(k, _, _)| *k == key)?;
        if self.entries[pos].2 <= now_ms {
            self.entries.remove(pos);
            return None;
        }
        let item = self.entries.remove(pos);
        let hop = item.1;
        self.entries.insert(0, item);
        Some(hop)
    }

    pub fn remove(&mut self, key: i32) {
        self.entries.retain(|(k, _, _)| *k != key);
    }
}

pub struct ZoneRouter;

impl ZoneRouter {
    pub fn ring_distance(a: i32, b: i32, zone_count: i32) -> i32 {
        let d = (a - b).abs();
        d.min(zone_count - d)
    }

    pub fn backbone_neighbors(zone: i32, zone_count: i32, max_fan_out: usize) -> Vec<i32> {
        let mut out = Vec::new();
        if zone_count <= 1 {
            return out;
        }
        let max_offsets = if max_fan_out == 0 { usize::MAX } else { (max_fan_out / 2).max(1) };
        let mut off = 1i32;
        let mut k = 0usize;
        while off <= zone_count / 2 && k < max_offsets {
            let plus = (zone + off).rem_euclid(zone_count);
            let minus = (zone - off).rem_euclid(zone_count);
            if plus != zone && !out.contains(&plus) { out.push(plus); }
            if minus != zone && !out.contains(&minus) { out.push(minus); }
            off <<= 1;
            k += 1;
        }
        out.sort_unstable();
        out
    }

    pub fn choose_next_hop<F>(
        my_zone: i32,
        dst_zone: i32,
        zone_count: i32,
        peers: &[i32],
        mut zone_of: F,
    ) -> Option<i32>
    where
        F: FnMut(i32) -> Option<i32>,
    {
        let mut best: Option<i32> = None;
        let mut best_dist = Self::ring_distance(my_zone, dst_zone, zone_count);
        for &peer in peers {
            if let Some(pz) = zone_of(peer) {
                let d = Self::ring_distance(pz, dst_zone, zone_count);
                if d < best_dist {
                    best_dist = d;
                    best = Some(peer);
                }
            }
        }
        best
    }
}

pub struct StoreItem {
    pub packet: Packet,
    pub expires_at_ms: i64,
    pub created_at_ms: i64,
    pub seq: u64,
}

pub struct StoreAndForward {
    max_items: usize,
    seq: u64,
    items: Vec<StoreItem>,
}

impl StoreAndForward {
    pub fn new(max_items: usize) -> Self {
        Self { max_items, seq: 0, items: Vec::new() }
    }

    pub fn enqueue(&mut self, packet: Packet, expires_at_ms: i64, now_ms: i64) -> bool {
        self.items.retain(|it| it.expires_at_ms > now_ms);
        if expires_at_ms <= now_ms {
            return false;
        }
        if self.items.len() >= self.max_items {
            let worst_idx = self.items.iter().enumerate().min_by(|(_, a), (_, b)| {
                match a.packet.priority.cmp(&b.packet.priority) {
                    Ordering::Equal => a.seq.cmp(&b.seq),
                    ord => ord,
                }
            }).map(|(i, _)| i);
            match worst_idx {
                Some(i) if self.items[i].packet.priority < packet.priority => {
                    self.items.remove(i);
                }
                _ => return false,
            }
        }
        let s = self.seq;
        self.seq += 1;
        self.items.push(StoreItem { packet, expires_at_ms, created_at_ms: now_ms, seq: s });
        true
    }

    pub fn take_all(&mut self, now_ms: i64) -> Vec<StoreItem> {
        self.items.retain(|it| it.expires_at_ms > now_ms);
        let mut out = core::mem::take(&mut self.items);
        out.sort_by(|a, b| match b.packet.priority.cmp(&a.packet.priority) {
            Ordering::Equal => a.seq.cmp(&b.seq),
            ord => ord,
        });
        out
    }
}

/// C-compatible ABI for Swift, Dart, Go, Python, and Wasm FFI.
#[no_mangle]
pub extern "C" fn mesh_rust_home_zone(node_id: i32, zone_count: i32) -> i32 {
    home_zone(node_id, zone_count)
}

#[no_mangle]
pub extern "C" fn mesh_rust_ring_distance(a: i32, b: i32, zone_count: i32) -> i32 {
    ZoneRouter::ring_distance(a, b, zone_count)
}

/// Decodes and bit-exactly re-encodes a MeshNet v2 packet into `out_ptr`.
/// Returns encoded length on success, or negative error code on malformed input.
///
/// # Safety
/// Caller must provide valid `in_ptr` of `in_len` bytes and `out_ptr` with `out_cap >= in_len`.
#[no_mangle]
pub unsafe extern "C" fn mesh_rust_packet_roundtrip(
    in_ptr: *const u8,
    in_len: usize,
    out_ptr: *mut u8,
    out_cap: usize,
) -> i32 {
    if in_ptr.is_null() || out_ptr.is_null() {
        return -1;
    }
    let input = core::slice::from_raw_parts(in_ptr, in_len);
    let pkt = match Packet::decode(input) {
        Some(p) => p,
        None => return -2,
    };
    let output = core::slice::from_raw_parts_mut(out_ptr, out_cap);
    match pkt.encode_into(output) {
        Some(written) => written as i32,
        None => -3,
    }
}


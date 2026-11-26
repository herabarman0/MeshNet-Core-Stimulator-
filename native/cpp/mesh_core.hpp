// MeshNet native core, phase 1: the data-plane building blocks, C++17, header only, no dependencies.
// Behaviour and wire format are defined by the Java engine; conformance/vectors.txt checks every bit of it.
#pragma once
#include <cstddef>
#include <cstdint>
#include <list>
#include <unordered_map>
#include <vector>

namespace mesh {

constexpr int kVersion = 2;
constexpr size_t kHeaderSize = 30;
constexpr size_t kMaxPayload = 0xFFFF;
constexpr int32_t kUnknownZone = -1;
enum PacketType : uint8_t { kData = 1, kAck = 2, kHello = 3, kPublish = 4, kLookup = 5, kLookupReply = 6, kChunk = 7, kVoice = 8, kGossip = 9, kDht = 10 };

/** A node id with the top bit set is a zone address: the packet is for every node of that zone. */
inline int32_t zone_address(int32_t zone) { return static_cast<int32_t>(0x80000000u | static_cast<uint32_t>(zone)); }
inline bool is_zone_address(int32_t dst) { return dst < 0; }

struct Packet {
    uint8_t type = 0;      // 0..15
    uint8_t attempt = 0;   // 0..15
    uint8_t ttl = 0;
    uint8_t priority = 0;
    int64_t msg_id = 0;
    int32_t src = 0, dst = 0, src_zone = 0, dst_zone = 0;
    std::vector<uint8_t> payload;

    /** Header (30 bytes) + payload, big endian. payload must be at most 65535 bytes. */
    std::vector<uint8_t> encode() const {
        std::vector<uint8_t> b;
        b.reserve(kHeaderSize + payload.size());
        b.push_back(static_cast<uint8_t>(kVersion));
        b.push_back(static_cast<uint8_t>(((attempt & 0x0F) << 4) | (type & 0x0F)));
        b.push_back(ttl);
        b.push_back(priority);
        put64(b, static_cast<uint64_t>(msg_id));
        put32(b, static_cast<uint32_t>(src));
        put32(b, static_cast<uint32_t>(dst));
        put32(b, static_cast<uint32_t>(src_zone));
        put32(b, static_cast<uint32_t>(dst_zone));
        const size_t n = payload.size() > kMaxPayload ? kMaxPayload : payload.size();
        b.push_back(static_cast<uint8_t>(n >> 8));
        b.push_back(static_cast<uint8_t>(n));
        b.insert(b.end(), payload.begin(), payload.begin() + static_cast<std::ptrdiff_t>(n));
        return b;
    }

    /**
     * Allocation-free encode into a caller buffer (stack, ring buffer, DMA area ...).
     * @return bytes written, or 0 if cap is too small or the payload is over 65535 bytes
     */
    size_t encode_into(uint8_t* out, size_t cap, const uint8_t* payload_data, size_t payload_len) const {
        if (payload_len > kMaxPayload || cap < kHeaderSize + payload_len) return 0;
        out[0] = static_cast<uint8_t>(kVersion);
        out[1] = static_cast<uint8_t>(((attempt & 0x0F) << 4) | (type & 0x0F));
        out[2] = ttl;
        out[3] = priority;
        wr64(out + 4, static_cast<uint64_t>(msg_id));
        wr32(out + 12, static_cast<uint32_t>(src));
        wr32(out + 16, static_cast<uint32_t>(dst));
        wr32(out + 20, static_cast<uint32_t>(src_zone));
        wr32(out + 24, static_cast<uint32_t>(dst_zone));
        out[28] = static_cast<uint8_t>(payload_len >> 8);
        out[29] = static_cast<uint8_t>(payload_len);
        for (size_t i = 0; i < payload_len; i++) out[kHeaderSize + i] = payload_data[i];
        return kHeaderSize + payload_len;
    }

    /**
     * Allocation-free decode: fills the header fields and points *payload_out into the input buffer
     * (valid as long as the buffer lives). The payload vector of this Packet is left untouched.
     */
    static bool decode_view(const uint8_t* data, size_t len, Packet& out, const uint8_t** payload_out, size_t* payload_len_out) {
        if (data == nullptr || len < kHeaderSize || data[0] != kVersion) return false;
        const size_t plen = (static_cast<size_t>(data[28]) << 8) | data[29];
        if (len != kHeaderSize + plen) return false;
        out.type = data[1] & 0x0F;
        out.attempt = data[1] >> 4;
        out.ttl = data[2];
        out.priority = data[3];
        out.msg_id = static_cast<int64_t>(get64(data + 4));
        out.src = static_cast<int32_t>(get32(data + 12));
        out.dst = static_cast<int32_t>(get32(data + 16));
        out.src_zone = static_cast<int32_t>(get32(data + 20));
        out.dst_zone = static_cast<int32_t>(get32(data + 24));
        *payload_out = data + kHeaderSize;
        *payload_len_out = plen;
        return true;
    }

    /** @return false if the bytes are not a valid packet (never reads outside [data, data+len)) */
    static bool decode(const uint8_t* data, size_t len, Packet& out) {
        if (data == nullptr || len < kHeaderSize) return false;
        if (data[0] != kVersion) return false;
        const size_t plen = (static_cast<size_t>(data[28]) << 8) | data[29];
        if (len != kHeaderSize + plen) return false;
        out.type = data[1] & 0x0F;
        out.attempt = data[1] >> 4;
        out.ttl = data[2];
        out.priority = data[3];
        out.msg_id = static_cast<int64_t>(get64(data + 4));
        out.src = static_cast<int32_t>(get32(data + 12));
        out.dst = static_cast<int32_t>(get32(data + 16));
        out.src_zone = static_cast<int32_t>(get32(data + 20));
        out.dst_zone = static_cast<int32_t>(get32(data + 24));
        out.payload.assign(data + kHeaderSize, data + len);
        return true;
    }

private:
    static void wr32(uint8_t* o, uint32_t v) { for (int i = 0; i < 4; i++) o[i] = static_cast<uint8_t>(v >> (24 - 8 * i)); }
    static void wr64(uint8_t* o, uint64_t v) { for (int i = 0; i < 8; i++) o[i] = static_cast<uint8_t>(v >> (56 - 8 * i)); }
    static void put32(std::vector<uint8_t>& b, uint32_t v) {
        for (int s = 24; s >= 0; s -= 8) b.push_back(static_cast<uint8_t>(v >> s));
    }
    static void put64(std::vector<uint8_t>& b, uint64_t v) {
        for (int s = 56; s >= 0; s -= 8) b.push_back(static_cast<uint8_t>(v >> s));
    }
    static uint32_t get32(const uint8_t* p) {
        return (uint32_t{p[0]} << 24) | (uint32_t{p[1]} << 16) | (uint32_t{p[2]} << 8) | uint32_t{p[3]};
    }
    static uint64_t get64(const uint8_t* p) { return (uint64_t{get32(p)} << 32) | get32(p + 4); }
};

/** The zone that stores where this node is. Same answer on every node and in every language. */
inline int32_t home_zone(int32_t node_id, int32_t zone_count) {
    uint32_t h = static_cast<uint32_t>(node_id);
    h ^= h >> 16;
    h *= 0x85EBCA6Bu;
    h ^= h >> 13;
    h *= 0xC2B2AE35u;
    h ^= h >> 16;
    const uint32_t zc = zone_count < 1 ? 1u : static_cast<uint32_t>(zone_count);  // fits: zone_count <= INT32_MAX
    return static_cast<int32_t>(h % zc);                                           // 32-bit divide is enough and faster
}

/** "Have I seen this message id?" with expiry and a size cap (oldest inserted entry goes first). */
class DedupCache {
public:
    DedupCache(size_t max_entries, int64_t ttl_ms) : max_(max_entries), ttl_(ttl_ms) {}

    /** @return true if the id was not seen (or its entry expired); it is now recorded. */
    bool first_seen(int64_t id, int64_t now_ms) {
        auto it = index_.find(id);
        if (it != index_.end()) {
            if (it->second->second > now_ms) return false;
            order_.erase(it->second);
            index_.erase(it);
        }
        order_.emplace_back(id, now_ms + ttl_);
        index_[id] = std::prev(order_.end());
        while (order_.size() > max_) {
            index_.erase(order_.front().first);
            order_.pop_front();
        }
        return true;
    }
    size_t size() const { return order_.size(); }

private:
    size_t max_;
    int64_t ttl_;
    std::list<std::pair<int64_t, int64_t>> order_;
    std::unordered_map<int64_t, std::list<std::pair<int64_t, int64_t>>::iterator> index_;
};

/** Small LRU map key -> value with per-entry expiry; the base of RouteCache and LocationDirectory. */
template <typename V>
class ExpiringLru {
public:
    explicit ExpiringLru(size_t max_entries) : max_(max_entries) {}

    void put(int32_t key, V value, int64_t expires_at_ms) {
        auto it = index_.find(key);
        if (it != index_.end()) order_.erase(it->second);
        order_.push_front({key, value, expires_at_ms});
        index_[key] = order_.begin();
        while (order_.size() > max_) {
            index_.erase(order_.back().key);
            order_.pop_back();
        }
    }
    /** Touches the entry (most recently used). @return nullptr if absent or expired. */
    const V* get(int32_t key, int64_t now_ms) {
        auto it = index_.find(key);
        if (it == index_.end()) return nullptr;
        if (it->second->expires <= now_ms) {
            order_.erase(it->second);
            index_.erase(it);
            return nullptr;
        }
        order_.splice(order_.begin(), order_, it->second);
        return &it->second->value;
    }
    /** Like get() but does not change the recency order. */
    const V* peek(int32_t key) const {
        auto it = index_.find(key);
        return it == index_.end() ? nullptr : &it->second->value;
    }
    int64_t peek_expiry(int32_t key) const {
        auto it = index_.find(key);
        return it == index_.end() ? 0 : it->second->expires;
    }
    void remove(int32_t key) {
        auto it = index_.find(key);
        if (it == index_.end()) return;
        order_.erase(it->second);
        index_.erase(it);
    }
    size_t size() const { return order_.size(); }

private:
    struct Entry { int32_t key; V value; int64_t expires; };
    size_t max_;
    std::list<Entry> order_;  // front = most recently used
    std::unordered_map<int32_t, typename std::list<Entry>::iterator> index_;
};

/** Destination key -> next-hop peer. */
class RouteCache {
public:
    RouteCache(size_t max_entries, int64_t ttl_ms) : lru_(max_entries), ttl_(ttl_ms) {}
    void put(int32_t key, int32_t next_hop, int64_t now_ms) { lru_.put(key, next_hop, now_ms + ttl_); }
    /** @return next hop, or -1 if unknown or expired */
    int32_t get(int32_t key, int64_t now_ms) {
        const int32_t* v = lru_.get(key, now_ms);
        return v ? *v : -1;
    }
    void remove(int32_t key) { lru_.remove(key); }
    size_t size() const { return lru_.size(); }

private:
    ExpiringLru<int32_t> lru_;
    int64_t ttl_;
};

/** nodeId -> zone, learned from neighbours, packet headers and the location service. */
class LocationDirectory {
public:
    explicit LocationDirectory(size_t max_entries) : lru_(max_entries) {}

    /** A shorter ttl never shortens an entry that is already known to be valid for longer (same zone). */
    void learn(int32_t node_id, int32_t zone, int64_t now_ms, int64_t ttl_ms) {
        int64_t expires = now_ms + ttl_ms;
        const int32_t* old = lru_.get(node_id, INT64_MIN);  // touch like the Java map.get(); INT64_MIN: never "expired"
        if (old != nullptr && *old == zone && lru_.peek_expiry(node_id) > expires) expires = lru_.peek_expiry(node_id);
        lru_.put(node_id, zone, expires);
    }
    int32_t zone_of(int32_t node_id, int64_t now_ms) {
        const int32_t* v = lru_.get(node_id, now_ms);
        return v ? *v : kUnknownZone;
    }
    void forget(int32_t node_id) { lru_.remove(node_id); }
    size_t size() const { return lru_.size(); }

private:
    ExpiringLru<int32_t> lru_;
};

}  // namespace mesh

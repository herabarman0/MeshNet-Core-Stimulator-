// MeshNet native core, phase 2: routing, pacing, store-and-forward, gateway election, and chunk reassembly.
// Header-only C++17, zero external dependencies. Mirrors core/*.java semantics.
#pragma once
#include "mesh_core.hpp"
#include <algorithm>
#include <cmath>
#include <deque>
#include <set>
#include <vector>

namespace mesh {

/** Ring + chord-finger zone routing (matches ZoneRouter.java). */
struct ZoneRouter {
    static int32_t ring_distance(int32_t a, int32_t b, int32_t zone_count) {
        int32_t d = std::abs(a - b);
        return std::min(d, zone_count - d);
    }

    static int32_t mod(int32_t x, int32_t n) {
        int32_t r = x % n;
        return r < 0 ? r + n : r;
    }

    static std::set<int32_t> backbone_neighbors(int32_t zone, int32_t zone_count, int32_t max_fan_out = 0) {
        std::set<int32_t> out;
        if (zone_count <= 1) return out;
        int32_t max_offsets = max_fan_out <= 0 ? zone_count : std::max(1, max_fan_out / 2);
        int32_t k = 0;
        for (int32_t off = 1; off <= zone_count / 2 && k < max_offsets; off <<= 1, k++) {
            out.insert(mod(zone + off, zone_count));
            out.insert(mod(zone - off, zone_count));
        }
        out.erase(zone);
        return out;
    }

    template <typename ZoneLookup, typename UsablePred>
    static int32_t choose_next_hop(int32_t my_zone, int32_t dst_zone, int32_t zone_count,
                                   const std::vector<int32_t>& peers, ZoneLookup&& zone_of, UsablePred&& usable) {
        int32_t best = -1;
        int32_t best_dist = ring_distance(my_zone, dst_zone, zone_count);
        for (int32_t peer : peers) {
            if (!usable(peer)) continue;
            int32_t pz = zone_of(peer);
            if (pz < 0) continue;
            int32_t d = ring_distance(pz, dst_zone, zone_count);
            if (d < best_dist) {
                best_dist = d;
                best = peer;
            }
        }
        return best;
    }
};

/** Priority token-bucket pacer with bulk reserve and AIMD jitter factor (matches Pacer.java). */
class Pacer {
public:
    Pacer(double rate_per_sec, int burst, int queue_max, int bulk_prio_below = 100, double bulk_reserve = 0.3)
        : rate_(rate_per_sec), burst_(std::max(1, burst)), queue_max_(std::max(1, queue_max)),
          bulk_below_(bulk_prio_below), reserve_(std::clamp(bulk_reserve, 0.0, 0.9)),
          tokens_(static_cast<double>(burst_)) {}

    bool enabled() const { return rate_ > 0.0; }

    /** Refills tokens and returns true if packet may transmit immediately; otherwise queues it. */
    bool offer(const Packet& p, int64_t now_ms) {
        if (!enabled()) return true;
        refill(now_ms);
        bool bulk = p.priority < bulk_below_;
        double floor_tokens = bulk ? (burst_ * reserve_) : 0.0;
        if (queue_.empty() && tokens_ >= 1.0 + floor_tokens) {
            tokens_ -= 1.0;
            return true;
        }
        if (queue_.size() >= queue_max_) {
            auto worst = std::min_element(queue_.begin(), queue_.end(), [](const Queued& a, const Queued& b) {
                return a.packet.priority != b.packet.priority ? a.packet.priority < b.packet.priority : a.seq < b.seq;
            });
            if (worst == queue_.end() || worst->packet.priority >= p.priority) {
                dropped_++;
                return false;
            }
            queue_.erase(worst);
            dropped_++;
        }
        queue_.push_back({p, now_ms, ++seq_});
        jitter_factor_ = std::min(4.0, jitter_factor_ + 0.15);
        return false;
    }

    /** Drains ready packets respecting priority order and bulk reserve. */
    std::vector<Packet> drain(int64_t now_ms, int64_t max_wait_ms = 20000) {
        std::vector<Packet> ready;
        if (!enabled()) return ready;
        refill(now_ms);
        // Drop expired
        queue_.erase(std::remove_if(queue_.begin(), queue_.end(), [&](const Queued& q) {
            bool exp = (now_ms - q.enqueued_ms) > max_wait_ms;
            if (exp) dropped_++;
            return exp;
        }), queue_.end());

        std::sort(queue_.begin(), queue_.end(), [](const Queued& a, const Queued& b) {
            return a.packet.priority != b.packet.priority ? a.packet.priority > b.packet.priority : a.seq < b.seq;
        });

        while (!queue_.empty()) {
            const Queued& top = queue_.front();
            double floor_tokens = (top.packet.priority < bulk_below_) ? (burst_ * reserve_) : 0.0;
            if (tokens_ < 1.0 + floor_tokens) break;
            tokens_ -= 1.0;
            ready.push_back(top.packet);
            queue_.erase(queue_.begin());
        }
        if (queue_.empty()) jitter_factor_ = std::max(1.0, jitter_factor_ * 0.9);
        return ready;
    }

    bool congested() const { return !queue_.empty() || jitter_factor_ > 1.2; }
    double jitter_factor() const { return jitter_factor_; }
    size_t queued() const { return queue_.size(); }
    uint64_t dropped() const { return dropped_; }

private:
    struct Queued { Packet packet; int64_t enqueued_ms; uint64_t seq; };
    void refill(int64_t now_ms) {
        if (last_ms_ < 0) { last_ms_ = now_ms; return; }
        if (now_ms > last_ms_) {
            tokens_ = std::min(static_cast<double>(burst_), tokens_ + ((now_ms - last_ms_) / 1000.0) * rate_);
            last_ms_ = now_ms;
        }
    }

    double rate_;
    int burst_;
    size_t queue_max_;
    int bulk_below_;
    double reserve_;
    double tokens_;
    int64_t last_ms_ = -1;
    double jitter_factor_ = 1.0;
    uint64_t seq_ = 0;
    uint64_t dropped_ = 0;
    std::vector<Queued> queue_;
};

/** Priority-ordered, expiring, size-capped StoreAndForward queue (matches StoreAndForward.java). */
class StoreAndForward {
public:
    struct Item {
        Packet packet;
        int64_t expires_at_ms;
        int64_t created_at_ms;
        uint64_t seq;
    };

    explicit StoreAndForward(size_t max_items, int64_t aging_step_ms = 0)
        : max_items_(max_items), aging_step_ms_(aging_step_ms) {}

    bool enqueue(const Packet& p, int64_t expires_at_ms, int64_t now_ms) {
        purge(now_ms);
        if (expires_at_ms <= now_ms) return false;
        if (items_.size() >= max_items_) {
            auto worst = std::min_element(items_.begin(), items_.end(), [](const Item& a, const Item& b) {
                return a.packet.priority != b.packet.priority ? a.packet.priority < b.packet.priority : a.seq < b.seq;
            });
            if (worst == items_.end() || worst->packet.priority >= p.priority) return false;
            items_.erase(worst);
            evicted_++;
        }
        items_.push_back({p, expires_at_ms, now_ms, seq_++});
        return true;
    }

    std::vector<Item> take_all(int64_t now_ms) {
        purge(now_ms);
        std::vector<Item> out = std::move(items_);
        items_.clear();
        std::sort(out.begin(), out.end(), [&](const Item& a, const Item& b) {
            int pa = effective_priority(a, now_ms);
            int pb = effective_priority(b, now_ms);
            return pa != pb ? pa > pb : a.seq < b.seq;
        });
        return out;
    }

    void requeue(const Item& item) { items_.push_back(item); }
    size_t size() const { return items_.size(); }
    uint64_t expired_count() const { return expired_; }
    uint64_t evicted_count() const { return evicted_; }

private:
    int effective_priority(const Item& it, int64_t now_ms) const {
        if (aging_step_ms_ <= 0 || it.packet.priority >= 200) return it.packet.priority;
        int64_t age = std::max<int64_t>(0, now_ms - it.created_at_ms);
        int boost = static_cast<int>(std::min<int64_t>(32, age / aging_step_ms_));
        return std::min(199, it.packet.priority + boost);
    }

    void purge(int64_t now_ms) {
        items_.erase(std::remove_if(items_.begin(), items_.end(), [&](const Item& it) {
            bool exp = it.expires_at_ms <= now_ms;
            if (exp) expired_++;
            return exp;
        }), items_.end());
    }

    size_t max_items_;
    int64_t aging_step_ms_;
    uint64_t seq_ = 0;
    uint64_t expired_ = 0;
    uint64_t evicted_ = 0;
    std::vector<Item> items_;
};

/** Weighted gateway election with low-battery hysteresis (matches GatewayManager.java). */
struct GatewayCandidate {
    int32_t id;
    double battery;
    double signal;
    bool online;
    bool charging;
    bool alive;
    int mobility = 0;
};

class GatewayManager {
public:
    GatewayManager(double wb = 0.4, double ws = 0.3, double wi = 0.3, double min_bat = 0.2, double w_stab = 0.0)
        : wb_(wb), ws_(ws), wi_(wi), min_bat_(min_bat), w_stab_(w_stab) {}

    double score(const GatewayCandidate& c) const {
        double bat = c.charging ? 1.0 : c.battery;
        return wb_ * bat + ws_ * c.signal + wi_ * (c.online ? 1.0 : 0.0) - w_stab_ * (c.mobility / 3.0);
    }

    std::vector<int32_t> reelect(const std::vector<GatewayCandidate>& members,
                                 const std::vector<int32_t>& current, size_t count) const {
        std::vector<GatewayCandidate> pool;
        for (const auto& m : members) if (m.alive && m.battery >= min_bat_) pool.push_back(m);
        if (pool.empty()) for (const auto& m : members) if (m.alive) pool.push_back(m);
        std::sort(pool.begin(), pool.end(), [&](const GatewayCandidate& a, const GatewayCandidate& b) {
            double sa = score(a), sb = score(b);
            return sa != sb ? sa > sb : a.id < b.id;
        });

        std::vector<int32_t> out;
        for (int32_t cid : current) {
            if (out.size() >= count) break;
            for (const auto& m : members) {
                if (m.id == cid && m.alive && m.battery >= min_bat_) { out.push_back(cid); break; }
            }
        }
        for (const auto& m : pool) {
            if (out.size() >= count) break;
            if (std::find(out.begin(), out.end(), m.id) == out.end()) out.push_back(m.id);
        }
        return out;
    }

private:
    double wb_, ws_, wi_, min_bat_, w_stab_;
};

/** Out-of-order chunk reassembler for large payloads. */
class ChunkAssembler {
public:
    explicit ChunkAssembler(uint16_t total_chunks) : total_(total_chunks), chunks_(total_chunks), present_(total_chunks, false) {}

    bool put(uint16_t index, const uint8_t* data, size_t len) {
        if (index >= total_) return false;
        if (!present_[index]) {
            chunks_[index].assign(data, data + len);
            present_[index] = true;
            done_++;
        }
        return true;
    }

    bool complete() const { return done_ == total_; }
    uint16_t done() const { return done_; }
    uint16_t total() const { return total_; }

    std::vector<uint16_t> missing() const {
        std::vector<uint16_t> m;
        for (uint16_t i = 0; i < total_; i++) if (!present_[i]) m.push_back(i);
        return m;
    }

    std::vector<uint8_t> assemble() const {
        std::vector<uint8_t> out;
        for (uint16_t i = 0; i < total_; i++) {
            out.insert(out.end(), chunks_[i].begin(), chunks_[i].end());
        }
        return out;
    }

private:
    uint16_t total_;
    uint16_t done_ = 0;
    std::vector<std::vector<uint8_t>> chunks_;
    std::vector<bool> present_;
};

}  // namespace mesh

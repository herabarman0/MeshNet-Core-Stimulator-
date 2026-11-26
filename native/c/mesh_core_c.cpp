#include "mesh_core_c.h"
#include "../cpp/mesh_core.hpp"
#include "../cpp/mesh_node.hpp"

struct mesh_dedup { mesh::DedupCache inner; explicit mesh_dedup(size_t m, int64_t t) : inner(m, t) {} };
struct mesh_route_cache { mesh::RouteCache inner; explicit mesh_route_cache(size_t m, int64_t t) : inner(m, t) {} };
struct mesh_location_dir { mesh::LocationDirectory inner; explicit mesh_location_dir(size_t m) : inner(m) {} };
struct mesh_pacer { mesh::Pacer inner; mesh_pacer(double r, int b, int q) : inner(r, b, q) {} };

extern "C" {

size_t mesh_packet_encode(const mesh_packet_header_t* hdr, const uint8_t* payload, uint8_t* out_buf, size_t out_cap) {
    if (!hdr || !out_buf) return 0;
    mesh::Packet p;
    p.type = hdr->type;
    p.attempt = hdr->attempt;
    p.ttl = hdr->ttl;
    p.priority = hdr->priority;
    p.msg_id = hdr->msg_id;
    p.src = hdr->src;
    p.dst = hdr->dst;
    p.src_zone = hdr->src_zone;
    p.dst_zone = hdr->dst_zone;
    return p.encode_into(out_buf, out_cap, payload, hdr->payload_len);
}

int mesh_packet_decode(const uint8_t* data, size_t len, mesh_packet_header_t* out_hdr, const uint8_t** out_payload) {
    if (!out_hdr) return 0;
    mesh::Packet p;
    const uint8_t* pl = nullptr;
    size_t plen = 0;
    if (!mesh::Packet::decode_view(data, len, p, &pl, &plen)) return 0;
    out_hdr->type = p.type;
    out_hdr->attempt = p.attempt;
    out_hdr->ttl = p.ttl;
    out_hdr->priority = p.priority;
    out_hdr->msg_id = p.msg_id;
    out_hdr->src = p.src;
    out_hdr->dst = p.dst;
    out_hdr->src_zone = p.src_zone;
    out_hdr->dst_zone = p.dst_zone;
    out_hdr->payload_len = static_cast<uint16_t>(plen);
    if (out_payload) *out_payload = pl;
    return 1;
}

int32_t mesh_home_zone(int32_t node_id, int32_t zone_count) { return mesh::home_zone(node_id, zone_count); }
int32_t mesh_zone_address(int32_t zone) { return mesh::zone_address(zone); }
int mesh_is_zone_address(int32_t dst) { return mesh::is_zone_address(dst) ? 1 : 0; }
int32_t mesh_ring_distance(int32_t a, int32_t b, int32_t zone_count) { return mesh::ZoneRouter::ring_distance(a, b, zone_count); }

mesh_dedup_t* mesh_dedup_create(size_t max_entries, int64_t ttl_ms) { return new mesh_dedup(max_entries, ttl_ms); }
void mesh_dedup_destroy(mesh_dedup_t* d) { delete d; }
int mesh_dedup_first_seen(mesh_dedup_t* d, int64_t msg_id, int64_t now_ms) { return d && d->inner.first_seen(msg_id, now_ms) ? 1 : 0; }

mesh_route_cache_t* mesh_route_create(size_t max_entries, int64_t ttl_ms) { return new mesh_route_cache(max_entries, ttl_ms); }
void mesh_route_destroy(mesh_route_cache_t* rc) { delete rc; }
void mesh_route_put(mesh_route_cache_t* rc, int32_t key, int32_t next_hop, int64_t now_ms) { if (rc) rc->inner.put(key, next_hop, now_ms); }
int32_t mesh_route_get(mesh_route_cache_t* rc, int32_t key, int64_t now_ms) { return rc ? rc->inner.get(key, now_ms) : -1; }

mesh_location_dir_t* mesh_loc_create(size_t max_entries) { return new mesh_location_dir(max_entries); }
void mesh_loc_destroy(mesh_location_dir_t* dir) { delete dir; }
void mesh_loc_learn(mesh_location_dir_t* dir, int32_t node_id, int32_t zone, int64_t now_ms, int64_t ttl_ms) { if (dir) dir->inner.learn(node_id, zone, now_ms, ttl_ms); }
int32_t mesh_loc_zone_of(mesh_location_dir_t* dir, int32_t node_id, int64_t now_ms) { return dir ? dir->inner.zone_of(node_id, now_ms) : mesh::kUnknownZone; }

mesh_pacer_t* mesh_pacer_create(double rate_per_sec, int burst, int queue_max) { return new mesh_pacer(rate_per_sec, burst, queue_max); }
void mesh_pacer_destroy(mesh_pacer_t* p) { delete p; }
int mesh_pacer_offer(mesh_pacer_t* p, const mesh_packet_header_t* hdr, const uint8_t* payload, int64_t now_ms) {
    if (!p || !hdr) return 0;
    mesh::Packet pk;
    pk.type = hdr->type;
    pk.attempt = hdr->attempt;
    pk.ttl = hdr->ttl;
    pk.priority = hdr->priority;
    pk.msg_id = hdr->msg_id;
    pk.src = hdr->src;
    pk.dst = hdr->dst;
    pk.src_zone = hdr->src_zone;
    pk.dst_zone = hdr->dst_zone;
    if (payload && hdr->payload_len > 0) pk.payload.assign(payload, payload + hdr->payload_len);
    return p->inner.offer(pk, now_ms) ? 1 : 0;
}
int mesh_pacer_congested(const mesh_pacer_t* p) { return p && p->inner.congested() ? 1 : 0; }

}  // extern "C"

/*
 * MeshNet C99 Foreign Function Interface (FFI) ABI.
 * Allows Swift (iOS/macOS), Dart (Flutter), C# (.NET/Unity), Go (cgo), Python (ctypes),
 * and Node.js N-API to share the exact same native MeshNet wire codec, caches, and router.
 */
#ifndef MESH_CORE_C_H
#define MESH_CORE_C_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define MESH_HEADER_SIZE 30
#define MESH_VERSION 2

typedef struct {
    uint8_t type;
    uint8_t attempt;
    uint8_t ttl;
    uint8_t priority;
    int64_t msg_id;
    int32_t src;
    int32_t dst;
    int32_t src_zone;
    int32_t dst_zone;
    uint16_t payload_len;
} mesh_packet_header_t;

typedef struct mesh_dedup mesh_dedup_t;
typedef struct mesh_route_cache mesh_route_cache_t;
typedef struct mesh_location_dir mesh_location_dir_t;
typedef struct mesh_pacer mesh_pacer_t;

/* Wire codec (allocation-free) */
size_t mesh_packet_encode(const mesh_packet_header_t* hdr, const uint8_t* payload, uint8_t* out_buf, size_t out_cap);
int mesh_packet_decode(const uint8_t* data, size_t len, mesh_packet_header_t* out_hdr, const uint8_t** out_payload);

/* Zone addressing & Murmur3 home-zone hash */
int32_t mesh_home_zone(int32_t node_id, int32_t zone_count);
int32_t mesh_zone_address(int32_t zone);
int mesh_is_zone_address(int32_t dst);
int32_t mesh_ring_distance(int32_t a, int32_t b, int32_t zone_count);

/* Deduplication cache */
mesh_dedup_t* mesh_dedup_create(size_t max_entries, int64_t ttl_ms);
void mesh_dedup_destroy(mesh_dedup_t* d);
int mesh_dedup_first_seen(mesh_dedup_t* d, int64_t msg_id, int64_t now_ms);

/* Route cache */
mesh_route_cache_t* mesh_route_create(size_t max_entries, int64_t ttl_ms);
void mesh_route_destroy(mesh_route_cache_t* rc);
void mesh_route_put(mesh_route_cache_t* rc, int32_t key, int32_t next_hop, int64_t now_ms);
int32_t mesh_route_get(mesh_route_cache_t* rc, int32_t key, int64_t now_ms);

/* Location directory */
mesh_location_dir_t* mesh_loc_create(size_t max_entries);
void mesh_loc_destroy(mesh_location_dir_t* dir);
void mesh_loc_learn(mesh_location_dir_t* dir, int32_t node_id, int32_t zone, int64_t now_ms, int64_t ttl_ms);
int32_t mesh_loc_zone_of(mesh_location_dir_t* dir, int32_t node_id, int64_t now_ms);

/* Pacer */
mesh_pacer_t* mesh_pacer_create(double rate_per_sec, int burst, int queue_max);
void mesh_pacer_destroy(mesh_pacer_t* p);
int mesh_pacer_offer(mesh_pacer_t* p, const mesh_packet_header_t* hdr, const uint8_t* payload, int64_t now_ms);
int mesh_pacer_congested(const mesh_pacer_t* p);

#ifdef __cplusplus
}
#endif

#endif /* MESH_CORE_C_H */

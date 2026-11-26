// Same work as Bench.java: encode + decode of a packet with a 60 byte payload, plus a home_zone lookup.
#include <chrono>
#include <cstdio>
#include "mesh_core.hpp"

int main() {
    const long iterations = 20'000'000;
    mesh::Packet p;
    p.type = mesh::kData; p.ttl = 32; p.priority = 1; p.msg_id = 0x1122334455667788LL;
    p.src = 1234; p.dst = 98765; p.src_zone = 12; p.dst_zone = 345;
    p.payload.assign(60, 0xAB);
    uint64_t sink = 0;
    auto t0 = std::chrono::steady_clock::now();
    for (long i = 0; i < iterations; i++) {
        p.msg_id = i;
        std::vector<uint8_t> bytes = p.encode();
        mesh::Packet q;
        mesh::Packet::decode(bytes.data(), bytes.size(), q);
        sink += static_cast<uint64_t>(q.msg_id) + q.payload.size() + static_cast<uint64_t>(mesh::home_zone(q.src + static_cast<int32_t>(i), 40000));
    }
    double secs = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    std::printf("C++ : %.1f million encode+decode+lookup per second (%.0f ns each)  [sink %llu]\n",
                iterations / secs / 1e6, secs / iterations * 1e9, static_cast<unsigned long long>(sink));

    // Same work without any heap allocation: encode into a stack buffer, decode as a view.
    uint8_t buf[mesh::kHeaderSize + 60];
    sink = 0;
    t0 = std::chrono::steady_clock::now();
    for (long i = 0; i < iterations; i++) {
        p.msg_id = i;
        size_t n = p.encode_into(buf, sizeof buf, p.payload.data(), p.payload.size());
        mesh::Packet q;
        const uint8_t* pl;
        size_t pl_len;
        mesh::Packet::decode_view(buf, n, q, &pl, &pl_len);
        sink += static_cast<uint64_t>(q.msg_id) + pl_len + static_cast<uint64_t>(mesh::home_zone(q.src + static_cast<int32_t>(i), 40000));
    }
    secs = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    std::printf("C++ (no heap allocation): %.1f million encode+decode+lookup per second (%.0f ns each)  [sink %llu]\n",
                iterations / secs / 1e6, secs / iterations * 1e9, static_cast<unsigned long long>(sink));
    return 0;
}

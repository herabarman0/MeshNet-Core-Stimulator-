// Conformance + unit tests for mesh_core.hpp. Usage: test_core ../../conformance/vectors.txt
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <string>
#include "mesh_core.hpp"

static int g_checks = 0, g_failures = 0;
#define CHECK(cond)                                                              \
    do {                                                                         \
        ++g_checks;                                                              \
        if (!(cond)) { ++g_failures; std::printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond); } \
    } while (0)

static std::vector<uint8_t> from_hex(const std::string& h) {
    std::vector<uint8_t> out;
    for (size_t i = 0; i + 1 < h.size(); i += 2) out.push_back(static_cast<uint8_t>(std::stoi(h.substr(i, 2), nullptr, 16)));
    return out;
}

static void conformance(const char* path) {
    std::ifstream in(path);
    if (!in) { std::printf("cannot open %s\n", path); std::exit(2); }
    std::string line;
    int packets = 0, bad = 0, homes = 0;
    while (std::getline(in, line)) {
        if (line.empty() || line[0] == '#') continue;
        std::istringstream s(line);
        std::string kind;
        s >> kind;
        if (kind == "packet") {
            std::string hex;
            int type, attempt, ttl, prio;
            long long msg;
            int src, dst, sz, dz;
            s >> hex >> type >> attempt >> ttl >> prio >> msg >> src >> dst >> sz >> dz;
            std::vector<uint8_t> bytes = from_hex(hex);
            mesh::Packet p;
            CHECK(mesh::Packet::decode(bytes.data(), bytes.size(), p));
            CHECK(p.type == type && p.attempt == attempt && p.ttl == ttl && p.priority == prio);
            CHECK(p.msg_id == msg && p.src == src && p.dst == dst && p.src_zone == sz && p.dst_zone == dz);
            CHECK(p.encode() == bytes);  // bit-exact round trip
            // allocation-free paths must agree with the vector paths
            std::vector<uint8_t> buf(bytes.size());
            CHECK(p.encode_into(buf.data(), buf.size(), p.payload.data(), p.payload.size()) == bytes.size());
            CHECK(buf == bytes);
            CHECK(p.encode_into(buf.data(), buf.size() - 1, p.payload.data(), p.payload.size()) == 0);  // too small: refuses
            mesh::Packet v;
            const uint8_t* pl = nullptr;
            size_t pl_len = 0;
            CHECK(mesh::Packet::decode_view(bytes.data(), bytes.size(), v, &pl, &pl_len));
            CHECK(v.msg_id == msg && v.src == src && v.dst == dst && v.type == type && v.dst_zone == dz);
            CHECK(pl_len == p.payload.size() && (pl_len == 0 || pl == bytes.data() + mesh::kHeaderSize));
            ++packets;
        } else if (kind == "bad") {
            std::string hex;
            s >> hex;
            std::vector<uint8_t> bytes = from_hex(hex);
            mesh::Packet p;
            CHECK(!mesh::Packet::decode(bytes.empty() ? nullptr : bytes.data(), bytes.size(), p));
            ++bad;
        } else if (kind == "home") {
            long long id, count, expected;
            s >> id >> count >> expected;
            CHECK(mesh::home_zone(static_cast<int32_t>(id), static_cast<int32_t>(count)) == expected);
            ++homes;
        }
    }
    std::printf("conformance: %d packet vectors, %d bad vectors, %d home-zone vectors\n", packets, bad, homes);
    CHECK(packets > 400 && bad == 6 && homes > 3000);
}

static void unit_tests() {
    using namespace mesh;
    // zone addresses
    CHECK(is_zone_address(zone_address(1234)));
    CHECK((zone_address(1234) & 0x7FFFFFFF) == 1234);
    CHECK(!is_zone_address(0) && !is_zone_address(99999));

    // DedupCache: same cases as the Java DedupCacheTest
    {
        DedupCache c(100, 1000);
        CHECK(c.first_seen(1, 0));
        CHECK(!c.first_seen(1, 10));
        CHECK(c.first_seen(2, 10));
        DedupCache e(100, 1000);
        CHECK(e.first_seen(1, 0));
        CHECK(!e.first_seen(1, 999));
        CHECK(e.first_seen(1, 1001));
        DedupCache cap(3, 100000);
        for (int i = 0; i < 10; i++) cap.first_seen(i, 0);
        CHECK(cap.size() == 3);
        CHECK(cap.first_seen(0, 1));   // oldest was evicted
        CHECK(!cap.first_seen(9, 1));  // newest is still known
    }
    // RouteCache: same cases as the Java RouteCacheTest
    {
        RouteCache c(10, 1000);
        CHECK(c.get(5, 0) == -1);
        c.put(5, 42, 0);
        CHECK(c.get(5, 10) == 42);
        c.put(5, 43, 20);
        CHECK(c.get(5, 30) == 43);
        RouteCache x(10, 1000);
        x.put(5, 42, 0);
        CHECK(x.get(5, 999) == 42);
        CHECK(x.get(5, 1000) == -1);
        CHECK(x.size() == 0);
        RouteCache lru(2, 100000);
        lru.put(1, 11, 0);
        lru.put(2, 22, 0);
        lru.get(1, 1);
        lru.put(3, 33, 2);
        CHECK(lru.get(1, 3) == 11);
        CHECK(lru.get(2, 3) == -1);
        CHECK(lru.get(3, 3) == 33);
        RouteCache r(10, 1000);
        r.put(5, 42, 0);
        r.remove(5);
        CHECK(r.get(5, 1) == -1);
    }
    // LocationDirectory: same cases as the Java LocationDirectoryTest
    {
        LocationDirectory d(10);
        CHECK(d.zone_of(5, 0) == kUnknownZone);
        d.learn(5, 7, 0, 1000);
        CHECK(d.zone_of(5, 999) == 7);
        CHECK(d.zone_of(5, 1000) == kUnknownZone);
        LocationDirectory m(10);
        m.learn(5, 7, 0, 1000);
        m.learn(5, 9, 10, 1000);
        CHECK(m.zone_of(5, 20) == 9);
        LocationDirectory keep(10);
        keep.learn(5, 7, 0, 100000);
        keep.learn(5, 7, 10, 1000);
        CHECK(keep.zone_of(5, 50000) == 7);
        LocationDirectory lru(2);
        lru.learn(1, 1, 0, 1000);
        lru.learn(2, 2, 0, 1000);
        lru.zone_of(1, 1);
        lru.learn(3, 3, 2, 1000);
        CHECK(lru.zone_of(1, 3) == 1);
        CHECK(lru.zone_of(2, 3) == kUnknownZone);
        CHECK(lru.zone_of(3, 3) == 3);
    }
    // home zones spread evenly
    {
        int count[40] = {0};
        for (int id = 0; id < 40000; id++) {
            int32_t h = home_zone(id, 40);
            CHECK(h >= 0 && h < 40);
            count[h]++;
        }
        for (int c : count) CHECK(c > 800 && c < 1200);
        CHECK(home_zone(12345, 1) == 0);
    }
    // robustness: decode never reads out of bounds, whatever the bytes are
    {
        uint32_t x = 12345;
        for (int i = 0; i < 20000; i++) {
            x = x * 1664525u + 1013904223u;
            std::vector<uint8_t> junk(x % 80);
            for (auto& b : junk) { x = x * 1664525u + 1013904223u; b = static_cast<uint8_t>(x >> 24); }
            if (junk.size() > 0 && (x & 1)) junk[0] = kVersion;
            Packet p;
            Packet::decode(junk.empty() ? nullptr : junk.data(), junk.size(), p);  // result irrelevant, sanitizers watch
        }
        CHECK(true);
    }
}

int main(int argc, char** argv) {
    unit_tests();
    conformance(argc > 1 ? argv[1] : "../../conformance/vectors.txt");
    std::printf("%d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}

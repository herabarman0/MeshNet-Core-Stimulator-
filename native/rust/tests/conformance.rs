use meshnet_core::{home_zone, DedupCache, Packet, RouteCache, StoreAndForward, ZoneRouter, HEADER_SIZE};
use std::fs;

fn from_hex(h: &str) -> Vec<u8> {
    (0..h.len())
        .step_by(2)
        .filter_map(|i| u8::from_str_radix(&h[i..i + 2], 16).ok())
        .collect()
}

#[test]
fn conformance_vectors_match_bit_for_bit() {
    let text = fs::read_to_string("../../conformance/vectors.txt").expect("vectors.txt");
    let mut packets = 0;
    let mut bad = 0;
    let mut homes = 0;
    for line in text.lines() {
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let parts: Vec<&str> = line.split_whitespace().collect();
        match parts[0] {
            "packet" => {
                let bytes = from_hex(parts[1]);
                let p = Packet::decode(&bytes).expect("valid vector");
                assert_eq!(p.ptype, parts[2].parse::<u8>().unwrap());
                assert_eq!(p.attempt, parts[3].parse::<u8>().unwrap());
                assert_eq!(p.ttl, parts[4].parse::<u8>().unwrap());
                assert_eq!(p.priority, parts[5].parse::<u8>().unwrap());
                assert_eq!(p.msg_id, parts[6].parse::<i64>().unwrap());
                assert_eq!(p.src, parts[7].parse::<i32>().unwrap());
                assert_eq!(p.dst, parts[8].parse::<i32>().unwrap());
                assert_eq!(p.src_zone, parts[9].parse::<i32>().unwrap());
                assert_eq!(p.dst_zone, parts[10].parse::<i32>().unwrap());
                assert_eq!(p.encode(), bytes);
                let mut buf = vec![0u8; bytes.len()];
                assert_eq!(p.encode_into(&mut buf), Some(HEADER_SIZE + p.payload.len()));
                assert_eq!(buf, bytes);
                packets += 1;
            }
            "bad" => {
                let bytes = if parts.len() > 1 { from_hex(parts[1]) } else { vec![] };
                assert!(Packet::decode(&bytes).is_none());
                bad += 1;
            }
            "home" => {
                let id: i32 = parts[1].parse().unwrap();
                let zc: i32 = parts[2].parse().unwrap();
                let exp: i32 = parts[3].parse().unwrap();
                assert_eq!(home_zone(id, zc), exp);
                homes += 1;
            }
            _ => {}
        }
    }
    assert!(packets > 400 && bad == 6 && homes > 3000);
}

#[test]
fn caches_and_router_unit_checks() {
    let mut d = DedupCache::new(3, 1000);
    assert!(d.first_seen(1, 0));
    assert!(!d.first_seen(1, 500));
    assert!(d.first_seen(1, 1000));

    let mut r = RouteCache::new(2, 1000);
    r.put(5, 42, 0);
    assert_eq!(r.get(5, 100), Some(42));
    assert_eq!(r.get(5, 1000), None);

    assert_eq!(ZoneRouter::ring_distance(1, 39, 40), 2);
    let mut sf = StoreAndForward::new(2);
    let p1 = Packet { ptype: 1, attempt: 0, ttl: 10, priority: 1, msg_id: 1, src: 1, dst: 2, src_zone: 0, dst_zone: 0, payload: vec![] };
    let p2 = Packet { priority: 9, msg_id: 2, ..p1.clone() };
    assert!(sf.enqueue(p1, 1000, 0));
    assert!(sf.enqueue(p2, 1000, 0));
    let taken = sf.take_all(10);
    assert_eq!(taken[0].packet.msg_id, 2);
}

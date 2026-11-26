package com.herabarman.meshnet.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EngineUpgradeTest {
    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    @Test
    void rfc7748X25519TestVector1() {
        byte[] scalar = hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4");
        byte[] u = hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c");
        byte[] expected = hex("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552");
        assertArrayEquals(expected, X25519.scalarMult(scalar, u));
    }

    @Test
    void rfc7748KeyAgreementAndAesGcmRoundTrip() {
        byte[][] alice = KeyExchange.generateRawKeyPair();
        byte[][] bob = KeyExchange.generateRawKeyPair();
        byte[] ka = KeyExchange.deriveRawKey(alice[0], bob[1]);
        byte[] kb = KeyExchange.deriveRawKey(bob[0], alice[1]);
        assertArrayEquals(ka, kb);
        AesGcmCrypto crypto = new AesGcmCrypto(ka);
        byte[] msg = "MeshNet universal X25519".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(msg, crypto.decrypt(crypto.encrypt(msg, 10, 20, 99L), 10, 20, 99L));
    }

    @Test
    void bloomFilterWireRoundTripAndMerge() {
        BloomFilter a = BloomFilter.forCapacity(64);
        BloomFilter b = BloomFilter.forCapacity(64);
        for (long i = 1; i <= 30; i++) a.add(i * 1009L);
        for (long i = 31; i <= 60; i++) b.add(i * 1009L);
        a.merge(b);
        BloomFilter restored = BloomFilter.fromBytes(a.toBytes());
        for (long i = 1; i <= 60; i++) {
            assertTrue(restored.mightContain(i * 1009L));
        }
    }

    @Test
    void fecRecoversAnyLostShardsUpToParityCount() {
        byte[] payload = "MeshNet Reed-Solomon GF(256) forward error correction test payload!".getBytes(StandardCharsets.UTF_8);
        FecCodec.EncodedBlock block = FecCodec.encode(payload, 4, 3);
        byte[][] partial = block.shards.clone();
        // Drop 3 shards: two data shards and one parity shard
        partial[0] = null;
        partial[2] = null;
        partial[5] = null;
        byte[] recovered = FecCodec.decode(partial, 4, 3);
        assertArrayEquals(payload, recovered);

        // Dropping 4 shards (more than parityCount=3) cannot decode
        partial[1] = null;
        assertNull(FecCodec.decode(partial, 4, 3));
    }
}

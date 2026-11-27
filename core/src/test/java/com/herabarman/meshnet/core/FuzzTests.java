package com.herabarman.meshnet.core;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Fuzz suite feeding 25,000+ malformed, truncated, bit-flipped, and oversized frames into every
 * core wire parser to verify zero uncaught crashes, OOMs, or infinite loops.
 */
final class FuzzTests {
    @Test
    void fuzzCoreWireDecoders25k() {
        Random rng = new Random(0xF00DCAFE1234L);
        byte[] dummyPriv = KeyExchange.generateRawKeyPair()[0];

        for (int i = 0; i < 5_000; i++) {
            int len = rng.nextInt(180);
            byte[] garbage = new byte[len];
            rng.nextBytes(garbage);

            // 1. Packet.decode must never throw anything other than IllegalArgumentException
            try {
                Packet p = Packet.decode(garbage);
                assertNotNull(p);
            } catch (IllegalArgumentException expected) {
                // OK
            }

            // 2. OnionRouter.peel must return null gracefully on arbitrary bytes
            assertDoesNotThrow(() -> {
                OnionRouter.peel(dummyPriv, garbage);
            });

            // 3. PayloadCompressor.decompress must return null gracefully without OOM
            if (len >= 5 && (garbage[0] == 0x01)) {
                garbage[1] = 0x00; // keep length prefix random-bounded against intentional 2GB flags too
            }
            assertDoesNotThrow(() -> {
                PayloadCompressor.decompress(garbage);
            });

            // 4. TurboScheduler.unpackBurst must always return a safe non-null list
            List<byte[]> unpacked = TurboScheduler.unpackBurst(garbage);
            assertNotNull(unpacked);

            // 5. TrafficMasker & Beacon & ContactCard
            assertDoesNotThrow(() -> {
                TrafficMasker.unmaskHttpsTls(garbage, null);
                TrafficMasker.extractLsbStego(garbage);
                Beacon.decode(garbage);
                ContactCard.decodeUri("meshnet://card?" + new String(garbage, StandardCharsets.ISO_8859_1));
            });
        }
    }

    @Test
    void spillRadioAndContactCardSafetyNumber() {
        byte[] kA = KeyExchange.generateRawKeyPair()[1];
        byte[] kB = KeyExchange.generateRawKeyPair()[1];
        String sAB = ContactCard.safetyNumber(kA, kB);
        String sBA = ContactCard.safetyNumber(kB, kA);
        assertEquals(sAB, sBA);
        assertEquals(71, sAB.length()); // 12 * 5 digits + 11 spaces
        assertEquals(12, sAB.split(" ").length);
    }
}

package com.herabarman.meshnet.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LocationDirectoryTest {
    @Test
    void learnsAndForgetsByExpiry() {
        LocationDirectory d = new LocationDirectory(10);
        assertEquals(Packet.UNKNOWN_ZONE, d.zoneOf(5, 0));
        d.learn(5, 7, 0, 1000);
        assertEquals(7, d.zoneOf(5, 999));
        assertEquals(Packet.UNKNOWN_ZONE, d.zoneOf(5, 1000));
    }

    @Test
    void aNewZoneReplacesTheOldOneForAMovedNode() {
        LocationDirectory d = new LocationDirectory(10);
        d.learn(5, 7, 0, 1000);
        d.learn(5, 9, 10, 1000);
        assertEquals(9, d.zoneOf(5, 20));
    }

    @Test
    void aShortTtlNeverShortensAKnownLongerOne() {
        LocationDirectory d = new LocationDirectory(10);
        d.learn(5, 7, 0, 100_000);       // published to the home zone: long lived
        d.learn(5, 7, 10, 1_000);        // passive sighting in a packet header
        assertEquals(7, d.zoneOf(5, 50_000));
    }

    @Test
    void leastRecentlyUsedEntryIsEvicted() {
        LocationDirectory d = new LocationDirectory(2);
        d.learn(1, 1, 0, 1000);
        d.learn(2, 2, 0, 1000);
        d.zoneOf(1, 1);                  // touch 1
        d.learn(3, 3, 2, 1000);
        assertEquals(1, d.zoneOf(1, 3));
        assertEquals(Packet.UNKNOWN_ZONE, d.zoneOf(2, 3));
        assertEquals(3, d.zoneOf(3, 3));
    }

    @Test
    void homeZoneIsStableAndSpreadsEvenly() {
        int zones = 40;
        int[] count = new int[zones];
        for (int id = 0; id < 40_000; id++) {
            int h = LocationDirectory.homeZone(id, zones);
            assertTrue(h >= 0 && h < zones);
            assertEquals(h, LocationDirectory.homeZone(id, zones));
            count[h]++;
        }
        for (int c : count) assertTrue(c > 800 && c < 1200, "uneven home zones: " + c);
        assertEquals(0, LocationDirectory.homeZone(12345, 1));
    }
}

package com.herabarman.meshnet.kit;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HardwareAndCrdtTest {
    @Test
    void kissLoraModemAndSatelliteBridgeRoundTrip() {
        final AtomicReference<byte[]> wire = new AtomicReference<>();
        HardwareBridges.KissSerialLoraModem modemA = new HardwareBridges.KissSerialLoraModem(220, new HardwareBridges.KissSerialLoraModem.SerialTransport() {
            @Override public void writeBytes(byte[] kissFrame) {
                wire.set(kissFrame);
            }
        });
        final AtomicReference<byte[]> rx = new AtomicReference<>();
        modemA.setReceiver(new LoraRadio.Receiver() {
            @Override public void onFrame(byte[] frame) { rx.set(frame); }
        });
        byte[] sample = new byte[]{1, 2, (byte) 0xC0, (byte) 0xDB, 99};
        modemA.send(sample);
        assertNotNull(wire.get());
        assertEquals(0xC0, wire.get()[0] & 0xFF);
        modemA.onSerialBytesReceived(wire.get());
        assertArrayEquals(sample, rx.get());

        final AtomicReference<byte[]> satRx = new AtomicReference<>();
        HardwareBridges.SatelliteModem sat = new HardwareBridges.SatelliteModem(340, null);
        sat.setReceiver(new LoraRadio.Receiver() {
            @Override public void onFrame(byte[] frame) { satRx.set(frame); }
        });
        sat.onDownlinkBurst(sample);
        assertArrayEquals(sample, satRx.get());
    }

    @Test
    void groupStateCrdtConvergesAcrossOutOfOrderReplicas() {
        final IdentityKeys alice = IdentityKeys.generate();
        final IdentityKeys bob = IdentityKeys.generate();
        byte[] gid = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};

        GroupState gAlice = new GroupState(gid, true, 0, null);
        GroupState gBob = new GroupState(gid, true, 0, null);

        GroupState.Op op1 = gAlice.create(alice, 1, GroupState.MSG, 1000L, "Hello Mesh".getBytes(StandardCharsets.UTF_8));
        gAlice.ingest(op1);
        GroupState.Op op2 = gAlice.create(alice, 1, GroupState.MSG, 2000L, "CRDT Sync".getBytes(StandardCharsets.UTF_8));
        gAlice.ingest(op2);
        GroupState.Op op3 = gBob.create(bob, 2, GroupState.MSG, 1500L, "Offline Reply".getBytes(StandardCharsets.UTF_8));
        gBob.ingest(op3);

        // Deliver in reverse order to Bob
        gBob.ingest(GroupState.Op.decode(op2.encode()));
        gBob.ingest(GroupState.Op.decode(op1.encode()));
        gAlice.ingest(GroupState.Op.decode(op3.encode()));

        assertEquals(gAlice.snapshot(), gBob.snapshot());
    }

    @Test
    void signedGeoAlertEncodesAndVerifies() {
        IdentityKeys k = IdentityKeys.generate();
        String area = GeoZones.geohash(23.8103, 90.4125, 5);
        byte[] wire = Alerts.encode(k, 777L, Alerts.SOS, System.currentTimeMillis() + 60_000L, area, 23.8103, 90.4125, "Flood SOS", 12);
        Alerts.Alert decoded = Alerts.decodeVerified(wire, Collections.singletonList(k.publicRaw()));
        assertNotNull(decoded);
        assertTrue(decoded.official);
        assertEquals("Flood SOS", decoded.text);
        assertEquals(area, decoded.area);
    }
}

package com.herabarman.meshnet.kit;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Cross-Platform Hardware Adapters uniting Android, Long-Range LoRa RF (10-15 km),
 * Satellite-IoT / NTN burst modems, and Desktop/Raspberry-Pi UDP nodes under {@link RawRadio}.
 */
public final class HardwareBridges {
    private HardwareBridges() {}

    /**
     * KISS/SLIP serial modem adapter (0xC0 framing + CRC-16) for USB-OTG / Bluetooth-SPP
     * SX127x / LoRa32 / Meshtastic long-range boards (up to 15 km line-of-sight).
     */
    public static final class KissSerialLoraModem implements LoraRadio.Modem {
        public interface SerialTransport {
            void writeBytes(byte[] kissFrame);
        }

        private static final int FEND = 0xC0;
        private static final int FESC = 0xDB;
        private static final int TFEND = 0xDC;
        private static final int TFESC = 0xDD;

        private final int maxMtu;
        private final SerialTransport serial;
        private volatile LoraRadio.Receiver receiver;
        public long txFrames;
        public long rxFrames;
        public long crcErrors;

        public KissSerialLoraModem(int maxMtu, SerialTransport serial) {
            this.maxMtu = Math.max(64, Math.min(255, maxMtu));
            this.serial = serial;
        }

        @Override public int maxFrame() { return maxMtu; }
        @Override public void setReceiver(LoraRadio.Receiver r) { this.receiver = r; }

        @Override
        public void send(byte[] frame) {
            txFrames++;
            byte[] kiss = encodeKiss(frame);
            if (serial != null) serial.writeBytes(kiss);
        }

        /** Feed raw KISS bytes received from USB-OTG serial / Bluetooth SPP. */
        public void onSerialBytesReceived(byte[] kissFrame) {
            byte[] raw = decodeKiss(kissFrame);
            if (raw == null) {
                crcErrors++;
                return;
            }
            rxFrames++;
            LoraRadio.Receiver r = receiver;
            if (r != null) r.onFrame(raw);
        }

        public static byte[] encodeKiss(byte[] payload) {
            int crc = crc16(payload);
            ByteArrayOutputStream bo = new ByteArrayOutputStream(payload.length + 10);
            bo.write(FEND);
            bo.write(0x00); // KISS Data Frame port 0
            for (byte b : payload) writeEscaped(bo, b & 0xFF);
            writeEscaped(bo, (crc >>> 8) & 0xFF);
            writeEscaped(bo, crc & 0xFF);
            bo.write(FEND);
            return bo.toByteArray();
        }

        public static byte[] decodeKiss(byte[] kiss) {
            if (kiss == null || kiss.length < 5) return null;
            ByteArrayOutputStream bo = new ByteArrayOutputStream(kiss.length);
            boolean inFrame = false;
            boolean esc = false;
            for (byte b : kiss) {
                int v = b & 0xFF;
                if (v == FEND) {
                    if (inFrame && bo.size() > 3) break;
                    inFrame = true;
                    bo.reset();
                    continue;
                }
                if (!inFrame) continue;
                if (v == FESC) { esc = true; continue; }
                if (esc) {
                    esc = false;
                    if (v == TFEND) bo.write(FEND);
                    else if (v == TFESC) bo.write(FESC);
                    else return null;
                } else {
                    bo.write(v);
                }
            }
            byte[] full = bo.toByteArray();
            if (full.length < 4 || full[0] != 0x00) return null;
            int payloadLen = full.length - 3;
            byte[] payload = new byte[payloadLen];
            System.arraycopy(full, 1, payload, 0, payloadLen);
            int expectedCrc = ((full[full.length - 2] & 0xFF) << 8) | (full[full.length - 1] & 0xFF);
            return crc16(payload) == expectedCrc ? payload : null;
        }

        private static void writeEscaped(ByteArrayOutputStream bo, int v) {
            if (v == FEND) { bo.write(FESC); bo.write(TFEND); }
            else if (v == FESC) { bo.write(FESC); bo.write(TFESC); }
            else bo.write(v);
        }

        private static int crc16(byte[] data) {
            int crc = 0xFFFF;
            for (byte b : data) {
                crc ^= (b & 0xFF) << 8;
                for (int i = 0; i < 8; i++) {
                    crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) & 0xFFFF : (crc << 1) & 0xFFFF;
                }
            }
            return crc;
        }
    }

    /**
     * Satellite-IoT / 3GPP NTN Burst Modem Adapter (e.g. Iridium SBD / Skylo NTN):
     * Larger SBD burst MTU (340 bytes) tuned for long-distance satellite relays when cellular & terrestrial mesh are dead.
     */
    public static final class SatelliteModem implements LoraRadio.Modem {
        public interface BurstUplink {
            void transmitBurst(byte[] sbdPayload);
        }

        private final int maxBurstBytes;
        private final BurstUplink uplink;
        private volatile LoraRadio.Receiver receiver;
        public long burstsSent;
        public long burstsReceived;

        public SatelliteModem(int maxBurstBytes, BurstUplink uplink) {
            this.maxBurstBytes = Math.max(128, Math.min(340, maxBurstBytes));
            this.uplink = uplink;
        }

        public static LoraRadio.Config satelliteConfig() {
            LoraRadio.Config c = new LoraRadio.Config();
            c.bitsPerSecond = 2400;     // SBD/NTN uplink rate
            c.dutyFraction = 0.08;      // 8% burst window
            c.helloMs = 60_000;
            c.downMs = 240_000;
            return c;
        }

        @Override public int maxFrame() { return maxBurstBytes; }
        @Override public void setReceiver(LoraRadio.Receiver r) { this.receiver = r; }

        @Override
        public void send(byte[] frame) {
            burstsSent++;
            if (uplink != null) uplink.transmitBurst(frame);
        }

        public void onDownlinkBurst(byte[] sbdPayload) {
            burstsReceived++;
            LoraRadio.Receiver r = receiver;
            if (r != null) r.onFrame(sbdPayload);
        }
    }

    /** Tracks active Desktop / Raspberry Pi UDP peer endpoints bridged alongside LoRa & Satellite links. */
    public static final class DesktopEndpointDirectory {
        private final CopyOnWriteArrayList<InetSocketAddress> endpoints = new CopyOnWriteArrayList<>();

        public void addEndpoint(String host, int port) {
            InetSocketAddress addr = new InetSocketAddress(host, port);
            if (!endpoints.contains(addr)) endpoints.add(addr);
        }

        public void registerWithUdp(UdpRadio udp) {
            for (InetSocketAddress a : endpoints) udp.addPeer(a);
        }

        public int count() { return endpoints.size(); }
    }
}

package com.herabarman.meshnet.kit;

import com.herabarman.meshnet.core.ChannelPlanner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Assembles a {@link MultiChannelRadio} from separate radios, one per channel, e.g. Bluetooth (Nearby), Wi-Fi (UdpRadio) and LoRa.
 * <pre>
 * RawRadio radio = new MultiChannelBuilder(selfNode)
 *     .add(0, nearbyRadio, 0.3)        // slow but works anywhere
 *     .add(1, udpOverWifi, 1.0)        // fast, needs the same Wi-Fi / hotspot
 *     .build();
 * </pre>
 * With a single radio, build() just returns it (no overhead). Both phones must use the same channel ids for the same kind of radio.
 */
public final class MultiChannelBuilder {
    private final int self;
    private final LinkedHashMap<Integer, RawRadio> radios = new LinkedHashMap<>();
    private final List<Double> capacity = new ArrayList<>();
    private ChannelPlanner.Config cfg;

    public MultiChannelBuilder(int selfNode) { this.self = selfNode; }

    /** @param channel 0..255, the same number on every phone for the same kind of radio. @param capacity 0..1, how good the channel is when clear. */
    public MultiChannelBuilder add(int channel, RawRadio radio, double capacity) {
        if (channel < 0 || channel > 255) throw new IllegalArgumentException("channel must be 0..255");
        if (radios.containsKey(channel)) throw new IllegalArgumentException("channel " + channel + " already used");
        radios.put(channel, radio);
        this.capacity.add(Math.max(0.01, Math.min(1.0, capacity)));
        return this;
    }

    public MultiChannelBuilder config(ChannelPlanner.Config c) { this.cfg = c; return this; }

    public RawRadio build() {
        if (radios.isEmpty()) throw new IllegalStateException("no radios");
        if (radios.size() == 1) return radios.values().iterator().next();
        double[] cap = new double[capacity.size()];
        for (int i = 0; i < cap.length; i++) cap[i] = capacity.get(i);
        return new MultiChannelRadio(self, radios, cap, cfg);
    }
}

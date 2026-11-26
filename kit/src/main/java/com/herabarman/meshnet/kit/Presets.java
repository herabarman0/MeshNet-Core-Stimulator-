package com.herabarman.meshnet.kit;

import com.herabarman.meshnet.core.MeshConfig;

/** Ready-made core settings. Use with {@code MeshClient.builder(...).tune(Presets.mobileAdaptive())}. */
public final class Presets {
    private Presets() {}

    /**
     * Phones that move and run on batteries: beacons every 15 s, several paths chosen by link quality, battery
     * awareness, congestion shedding, movement awareness. Costs one small HELLO per neighbour per 15 s.
     */
    public static MeshClient.Tuner mobileAdaptive() {
        return new MeshClient.Tuner() {
            @Override public void tune(MeshConfig cfg) {
                cfg.beaconMs = 15_000;
                cfg.adaptiveRouting = true;
                cfg.energyAware = true;
                cfg.congestionControl = true;
                cfg.mobilityAware = true;
            }
        };
    }

    /**
     * Small devices (old phones, Raspberry Pi Zero, microcontroller gateways, wearables): fewer cached packets, routes, directory
     * entries and reassembly buffers, so the engine's memory stays in the low megabytes. Behaviour is the same, only the caps are lower.
     * Roughly: dedup 256, stored packets 48, routes 24, directory 512, 4 big transfers at once (2 MB in flight).
     */
    public static MeshClient.Tuner lite() {
        return new MeshClient.Tuner() {
            @Override public void tune(MeshConfig cfg) {
                cfg.dedupMaxEntries = 256;
                cfg.storeMaxPackets = 48;
                cfg.routeCacheMax = 24;
                cfg.directoryMaxEntries = 512;
                cfg.awaitingMax = 16;
                cfg.txQueueMax = 48;
                cfg.maxAssemblies = 4;
                cfg.maxReassemblyBytes = 2L * 1024 * 1024;
                cfg.chunkWindow = 4;
            }
        };
    }

    /** Several tuners in a row (later ones win): {@code Presets.both(Presets.mobileAdaptive(), Presets.lite())}. */
    public static MeshClient.Tuner both(final MeshClient.Tuner a, final MeshClient.Tuner b) {
        return new MeshClient.Tuner() {
            @Override public void tune(MeshConfig cfg) { a.tune(cfg); b.tune(cfg); }
        };
    }
}

package com.herabarman.meshnet.core;

/** What gateway election needs to know about a node. */
public final class NodeInfo {
    public final int id;
    public int zone;
    /** 0..1 */
    public double battery;
    /** 0..1 link quality */
    public double signal;
    /** Has internet access. */
    public boolean online;
    public boolean alive = true;
    /** Plugged in: battery does not matter for routing or election. */
    public boolean charging;
    /** 0 still, 1 walking, 2 bike / slow vehicle, 3 fast (see Beacon.mobilityClass). A mover makes a poor gateway. */
    public int mobility;

    public NodeInfo(int id, int zone, double battery, double signal, boolean online) {
        this.id = id;
        this.zone = zone;
        this.battery = battery;
        this.signal = signal;
        this.online = online;
    }
}

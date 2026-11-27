package com.herabarman.meshnet.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Multi-Radio Overflow Spillover Transport ({@link Transport}).
 *
 * Sends traffic across a primary low-power radio (e.g. BLE) while tracking the primary's sliding
 * rate / congestion budget. When traffic spikes beyond {@code primaryMaxPacketsPerSec} or the primary
 * peer link goes down while the secondary radio is reachable, frames seamlessly spill over to the
 * high-bandwidth secondary transport (e.g. Wi-Fi Direct, Wi-Fi Aware, or Hotspot UDP) without blocking.
 */
public final class SpillRadio implements Transport {
    private final Transport primary;
    private final Transport secondary;
    private final Clock clock;
    private final int primaryMaxPacketsPerSec;
    private Listener listener;

    private long windowStartMs;
    private int windowCount;
    private final Map<Integer, Boolean> primaryUp = new HashMap<>();
    private final Map<Integer, Boolean> secondaryUp = new HashMap<>();

    public long primarySent;
    public long spilledToSecondary;

    public SpillRadio(Transport primary, Transport secondary, Clock clock, int primaryMaxPacketsPerSec) {
        if (primary == null || secondary == null || clock == null) {
            throw new IllegalArgumentException("transports and clock required");
        }
        this.primary = primary;
        this.secondary = secondary;
        this.clock = clock;
        this.primaryMaxPacketsPerSec = Math.max(1, primaryMaxPacketsPerSec);

        primary.setListener(new Listener() {
            @Override public void onReceive(int fromPeer, byte[] data) {
                if (listener != null) listener.onReceive(fromPeer, data);
            }
            @Override public void onPeerUp(int peerId) {
                boolean wasUp = isPeerReachable(peerId);
                primaryUp.put(peerId, true);
                if (!wasUp && listener != null) listener.onPeerUp(peerId);
            }
            @Override public void onPeerDown(int peerId) {
                primaryUp.put(peerId, false);
                if (!isPeerReachable(peerId) && listener != null) listener.onPeerDown(peerId);
            }
            @Override public void onLinkQuality(int peerId, int rssiDbm) {
                if (listener != null) listener.onLinkQuality(peerId, rssiDbm);
            }
        });

        secondary.setListener(new Listener() {
            @Override public void onReceive(int fromPeer, byte[] data) {
                if (listener != null) listener.onReceive(fromPeer, data);
            }
            @Override public void onPeerUp(int peerId) {
                boolean wasUp = isPeerReachable(peerId);
                secondaryUp.put(peerId, true);
                if (!wasUp && listener != null) listener.onPeerUp(peerId);
            }
            @Override public void onPeerDown(int peerId) {
                secondaryUp.put(peerId, false);
                if (!isPeerReachable(peerId) && listener != null) listener.onPeerDown(peerId);
            }
            @Override public void onLinkQuality(int peerId, int rssiDbm) {
                if (listener != null) listener.onLinkQuality(peerId, rssiDbm);
            }
        });
    }

    @Override
    public synchronized void send(int peerId, byte[] data) {
        long now = clock.nowMs();
        if (now - windowStartMs >= 1000L || now < windowStartMs) {
            windowStartMs = now;
            windowCount = 0;
        }
        boolean priReachable = Boolean.TRUE.equals(primaryUp.get(peerId));
        boolean secReachable = Boolean.TRUE.equals(secondaryUp.get(peerId));

        // Spill to secondary if primary is saturated or only secondary knows this peer
        boolean saturated = windowCount >= primaryMaxPacketsPerSec;
        if ((!priReachable && secReachable) || (saturated && secReachable)) {
            spilledToSecondary++;
            secondary.send(peerId, data);
            return;
        }
        windowCount++;
        primarySent++;
        primary.send(peerId, data);
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isPeerReachable(int peerId) {
        return Boolean.TRUE.equals(primaryUp.get(peerId)) || Boolean.TRUE.equals(secondaryUp.get(peerId));
    }
}

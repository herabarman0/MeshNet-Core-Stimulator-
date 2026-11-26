package com.herabarman.meshnet.kit;

/**
 * A fast side-channel for big payloads to a DIRECT neighbour: Google Nearby FILE payloads (they upgrade themselves to Wi-Fi /
 * hotspot / Wi-Fi Direct), a TCP socket over a Wi-Fi Direct group, a hotspot or a LAN ({@link TcpBulk}), and so on.
 * The mesh radio keeps doing what it does (small packets, routing, multi-hop); a BulkLink only carries the one-hop case,
 * where chunking 4 KB packets through a Bluetooth-class radio is far slower than a real stream.
 * <p>
 * MeshClient encrypts the payload end to end BEFORE handing it over, so a BulkLink never sees plaintext and needs no crypto.
 * If a send fails (the link went away, a timeout) MeshClient falls back to the normal chunked path, so a BulkLink is an
 * optimisation, never a requirement. Implementations must be thread-safe: send() may be called from the engine thread, the
 * callbacks may come from any thread.
 */
public interface BulkLink {
    /** True if a fast direct path to this neighbour exists right now. */
    boolean canSend(int node);

    /** Send bytes to a direct neighbour. cb.onDone(true) when it was handed over completely, false on any failure. Must not block. */
    void send(int node, byte[] data, Done cb);

    void setReceiver(Receiver r);

    default void start() { }
    default void stop() { }

    interface Done { void onDone(boolean ok); }

    interface Receiver { void onReceived(int fromNode, byte[] data); }
}

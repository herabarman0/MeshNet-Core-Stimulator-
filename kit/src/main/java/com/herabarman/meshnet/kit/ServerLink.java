package com.herabarman.meshnet.kit;

/**
 * Optional internet helper. Everything here must be cheap and non-blocking except refresh().
 * The server only relays packets to nodes that are online right now and keeps no message history:
 * what cannot be delivered stays queued on the sender (Outbox) and in the mesh.
 */
public interface ServerLink {
    interface Inbound { void onPacket(byte[] corePacket); }

    void start(int selfNode, Inbound inbound);
    void stop();

    /** Internet works right now (cached). */
    boolean available();

    /** The server saw this node recently (cached by refresh() and by the poll loop). */
    boolean isOnline(int node);

    /** Last zone the node published, or -1. */
    int zoneOf(int node);

    /** Hand a core packet to the server for this node. Must not block the engine thread. */
    void send(int node, byte[] corePacket);

    /** Tell the server my zone (it is also sent with every poll). */
    void publishZone(int zone);

    /** Blocking (a second or two at most): update presence and zone of these nodes. Call off the engine thread. */
    void refresh(int... nodes);
}

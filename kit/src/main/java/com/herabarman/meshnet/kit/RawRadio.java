package com.herabarman.meshnet.kit;

/**
 * The only thing a radio (Google Nearby, Wi-Fi Direct, Bluetooth, a test double) has to do:
 * move bytes to a directly connected neighbour. peerNode is the neighbour's node id.
 * Everything else (zones, framing, routing, ACKs, encryption) happens above.
 */
public interface RawRadio {
    void sendRaw(int peerNode, byte[] data);

    void setHandler(Handler handler);

    /** Disconnect this neighbour (misbehaving or a bad link). The radio may reconnect later with its own back-off. */
    default void drop(int peerNode) {}

    default void start() {}
    default void stop() {}

    interface Handler {
        void onLinkUp(int peerNode);
        void onLinkDown(int peerNode);
        void onRaw(int peerNode, byte[] data);
    }
}

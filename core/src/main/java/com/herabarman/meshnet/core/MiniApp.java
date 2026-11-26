package com.herabarman.meshnet.core;

/**
 * A mini-app that runs on top of the mesh: an offline game, a quiz, a local marketplace... Write one class, register it
 * with a {@link MiniAppHost}, and the host does the rest: finds neighbours running the same app, delivers messages,
 * keeps the app inside its limits (message size, send rate, storage) and survives its crashes.
 * All callbacks run on the engine thread, one at a time: never block in them.
 */
public interface MiniApp {
    /** Unique reverse-domain id, up to 48 characters, e.g. "com.example.quiz". */
    String id();

    int version();

    void onStart(MiniAppContext ctx);

    /** A reliable, end-to-end encrypted message from another node's copy of this app (sent with sendTo). */
    void onMessage(int fromNode, byte[] data);

    /** A message that came by sendNearby / floodNearby (not encrypted, may repeat across hops, may be lost). */
    void onNearby(int fromNode, byte[] data);

    /** A direct neighbour that runs this app appeared or went away. */
    void onPeer(int node, boolean up);

    void onStop();
}

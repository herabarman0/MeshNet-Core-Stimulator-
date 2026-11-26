package com.herabarman.meshnet.core;

import java.util.Set;

/** Everything a mini-app may do. It gets nothing else: no sockets, no other app's data, no access to the mesh internals. */
public interface MiniAppContext {
    int selfId();

    long now();

    /** Reliable and end-to-end encrypted, to any node by id (routed, retried, queued while offline). Max {@link MiniAppHost#MAX_DIRECT} bytes. @return false if over a limit. */
    boolean sendTo(int node, byte[] data);

    /** One hop: to every direct neighbour that runs this app. Max {@link MiniAppHost#MAX_NEARBY} bytes. @return false if over a limit. */
    boolean sendNearby(byte[] data);

    /** Like sendNearby but relayed on by the neighbours' copies of the app, up to hops hops (1..8). Duplicates are removed by the host. */
    boolean floodNearby(byte[] data, int hops);

    /** Direct neighbours that run this app right now (found automatically, no setup). */
    Set<Integer> peersRunning();

    void schedule(long delayMs, Runnable task);

    /** The app's own private storage (other apps cannot see it; limited in size). @return false if over the quota. */
    boolean save(String key, byte[] value);

    byte[] load(String key);

    void log(String message);
}

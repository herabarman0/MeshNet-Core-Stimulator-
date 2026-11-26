package com.herabarman.meshnet.kit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Several BulkLinks behind one: a send goes to the first link (in the order given: put the fastest first) that has a path to the
 * neighbour, and if that one fails the next is tried. Receiving works on all of them.
 */
public final class CompositeBulk implements BulkLink {
    private final List<BulkLink> links;

    public CompositeBulk(BulkLink... ls) {
        List<BulkLink> l = new ArrayList<>();
        for (BulkLink b : ls) if (b != null) l.add(b);
        this.links = Collections.unmodifiableList(l);
    }

    @Override public boolean canSend(int node) {
        for (BulkLink b : links) if (b.canSend(node)) return true;
        return false;
    }

    @Override public void send(final int node, final byte[] data, final Done cb) { trySend(0, node, data, cb); }

    private void trySend(final int from, final int node, final byte[] data, final Done cb) {
        for (int i = from; i < links.size(); i++) {
            final BulkLink b = links.get(i);
            if (!b.canSend(node)) continue;
            final int next = i + 1;
            b.send(node, data, new Done() {
                @Override public void onDone(boolean ok) {
                    if (ok) cb.onDone(true); else trySend(next, node, data, cb);
                }
            });
            return;
        }
        cb.onDone(false);
    }

    @Override public void setReceiver(Receiver r) { for (BulkLink b : links) b.setReceiver(r); }
    @Override public void start() { for (BulkLink b : links) b.start(); }
    @Override public void stop() { for (BulkLink b : links) b.stop(); }
}

package com.herabarman.meshnet.kit;

import com.herabarman.meshnet.core.ServerBridge;

/** The core's ServerBridge over a ServerLink. */
public final class LinkBridge implements ServerBridge {
    private final ServerLink link;

    public LinkBridge(ServerLink link) { this.link = link; }

    @Override public boolean available() { return link.available(); }
    @Override public boolean isReachable(int nodeId) { return link.isOnline(nodeId); }
    @Override public void send(int dstNode, byte[] data) { link.send(dstNode, data); }
}

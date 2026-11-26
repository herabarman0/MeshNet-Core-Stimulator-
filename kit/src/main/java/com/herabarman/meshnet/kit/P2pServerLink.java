package com.herabarman.meshnet.kit;

import com.herabarman.meshnet.core.SystemClock;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A ServerLink that tries to take the load off the server: it forwards everything to the server link you give it
 * (the relay), but when two nodes are both online it first makes a direct UDP path between them (STUN + hole
 * punching, see {@link P2pManager}) and sends over that path whenever it is open. The relay stays as the fallback:
 * whatever cannot go direct, goes through the server exactly as before, so turning this on cannot break delivery.
 * The server is used for the small signals that introduce the two nodes; they travel as ordinary relayed packets
 * that start with a marker byte (0xFE) which no core packet starts with, so the server needs no change.
 *
 * <pre>
 * ServerLink link = new P2pServerLink(new HttpServerLink(url, token), P2pServerLink.defaultStunServers());
 * MeshClient.builder(userId)....server(link)...
 * </pre>
 * If the UDP socket cannot be opened or STUN is unreachable, it simply behaves like the relay.
 */
public final class P2pServerLink implements ServerLink {
    private final ServerLink relay;
    private final List<String> stunHosts;
    private final int udpPort;
    private final boolean loopback;
    private volatile P2pManager p2p;
    private volatile SystemClock clock;
    private final AtomicLong viaP2p = new AtomicLong();
    private final AtomicLong viaRelay = new AtomicLong();

    /** "host:port" strings, resolved in the background when the link starts. Two different providers let the NAT type be measured. */
    public static List<String> defaultStunServers() {
        return Arrays.asList("stun.l.google.com:19302", "stun.cloudflare.com:3478");
    }

    public P2pServerLink(ServerLink relay, List<String> stunServers) {
        this(relay, stunServers, 0);
    }

    /** @param udpPort the local UDP port to use (0 = any free port) */
    public P2pServerLink(ServerLink relay, List<String> stunServers, int udpPort) {
        this(relay, stunServers, udpPort, false);
    }

    /** @param loopback also offer 127.0.0.1 to peers (two nodes on the same machine: tests) */
    public P2pServerLink(ServerLink relay, List<String> stunServers, int udpPort, boolean loopback) {
        this.relay = relay;
        this.stunHosts = stunServers;
        this.udpPort = udpPort;
        this.loopback = loopback;
    }

    @Override
    public void start(final int selfNode, final Inbound inbound) {
        relay.start(selfNode, new Inbound() {
            @Override public void onPacket(byte[] p) {
                if (p.length > 0 && p[0] == P2pManager.SIGNAL_MAGIC) {
                    P2pManager m = p2p;
                    if (m != null) m.onSignal(p);
                    return;
                }
                inbound.onPacket(p);
            }
        });
        Thread t = new Thread(new Runnable() {
            @Override public void run() { startP2p(selfNode, inbound); }
        }, "mesh-p2p-start");
        t.setDaemon(true);
        t.start();
    }

    /** Resolves the STUN names (can take a while, so not on the caller's thread), opens the socket, starts the manager. */
    private void startP2p(final int selfNode, final Inbound inbound) {
        try {
            List<InetSocketAddress> servers = new ArrayList<>();
            for (String h : stunHosts) {
                int i = h.lastIndexOf(':');
                try {
                    InetAddress a = InetAddress.getByName(h.substring(0, i));
                    if (a.getAddress().length == 4) servers.add(new InetSocketAddress(a, Integer.parseInt(h.substring(i + 1))));
                } catch (java.io.IOException | RuntimeException ignored) {
                    // this STUN server cannot be resolved right now; the others may be fine
                }
            }
            JavaUdpPort port = new JavaUdpPort(udpPort, loopback);
            SystemClock c = new SystemClock();
            P2pManager m = new P2pManager(selfNode, port, c, new Random(), servers, new P2pManager.Signal() {
                @Override public void send(int toNode, byte[] payload) { relay.send(toNode, payload); }
            }, new P2pManager.Sink() {
                @Override public void onPacket(byte[] p) { inbound.onPacket(p); }
            });
            clock = c;
            m.start();
            p2p = m;
        } catch (java.io.IOException | RuntimeException e) {
            p2p = null;                                         // no direct paths: relay only
        }
    }

    @Override
    public void stop() {
        P2pManager m = p2p;
        p2p = null;
        if (m != null) m.stop();
        SystemClock c = clock;
        if (c != null) c.shutdown();
        relay.stop();
    }

    @Override public boolean available() { return relay.available(); }
    @Override public boolean isOnline(int node) { return relay.isOnline(node); }
    @Override public int zoneOf(int node) { return relay.zoneOf(node); }
    @Override public void publishZone(int zone) { relay.publishZone(zone); }
    @Override public void refresh(int... nodes) { relay.refresh(nodes); }

    @Override
    public void send(int node, byte[] packet) {
        final P2pManager m = p2p;
        if (m != null) {
            if (m.send(node, packet)) {
                viaP2p.incrementAndGet();
                return;
            }
            if (relay.isOnline(node)) {                         // both online: try to make a direct path for the next packets
                final int n = node;
                clock.post(new Runnable() { @Override public void run() { m.ensure(n); } });
            }
        }
        viaRelay.incrementAndGet();
        relay.send(node, packet);
    }

    /** Packets sent over a direct path. */
    public long sentDirect() { return viaP2p.get(); }

    /** Packets sent through the server. */
    public long sentViaServer() { return viaRelay.get(); }

    /** The manager, for state and statistics (null until started, or if there is no UDP). */
    public P2pManager manager() { return p2p; }
}

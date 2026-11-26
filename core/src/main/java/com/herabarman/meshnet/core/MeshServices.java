package com.herabarman.meshnet.core;

import java.util.List;
import java.util.Random;

/**
 * The decentralized location service in one object: gossip for "who is near me", a DHT for "where is that far-away
 * node", both riding on one MeshNode, no server and no global table.
 *
 *   MeshServices svc = MeshServices.install(node, clock, rng);   // discovery-mode node
 *   svc.start();                                                // publishes me, starts gossip and DHT upkeep
 *   svc.resolve(otherId, (id, zone) -> ...);                    // local knowledge, then gossip, then the DHT
 *   node.setZone(newZone); svc.announce();                      // after moving
 *
 * Everything learned is also handed to the node ({@link MeshNode#hintLocation}), so messages that were waiting for a
 * lookup go out at once and the node's own LOOKUP/PUBLISH service is spared.
 */
public final class MeshServices {
    public final GossipDirectory gossip;
    public final DhtNode dht;
    private final MeshNode node;
    private final Clock clock;
    private final Random rng;
    private boolean running;
    private final long maintainMs;

    private MeshServices(MeshNode node, Clock clock, Random rng, GossipDirectory gossip, DhtNode dht, long maintainMs) {
        this.node = node;
        this.clock = clock;
        this.rng = rng;
        this.gossip = gossip;
        this.dht = dht;
        this.maintainMs = maintainMs;
    }

    public static MeshServices install(MeshNode node, Clock clock, Random rng) {
        return install(node, clock, rng, new GossipDirectory.Config(), new DhtNode.Config());
    }

    /** Same, with signed location records (gossip and DHT): nobody can announce a location for a node but that node. */
    public static MeshServices install(MeshNode node, Clock clock, Random rng, RecordAuth auth) {
        return install(node, clock, rng, new GossipDirectory.Config(), new DhtNode.Config(), auth);
    }

    public static MeshServices install(MeshNode node, Clock clock, Random rng,
                                       GossipDirectory.Config gc, DhtNode.Config dc) {
        return install(node, clock, rng, gc, dc, null);
    }

    public static MeshServices install(final MeshNode node, Clock clock, Random rng,
                                       GossipDirectory.Config gc, DhtNode.Config dc, RecordAuth auth) {
        GossipDirectory.Link link = new GossipDirectory.Link() {
            @Override public void send(int peer, byte[] message) { node.sendToPeer(peer, Packet.TYPE_GOSSIP, message, 100); }
            @Override public List<Integer> peers() { return node.peers(); }
        };
        final GossipDirectory gossip = new GossipDirectory(node.id(), gc, clock, rng, link);
        final DhtNode dht = new DhtNode(node.id(), node.zone(), dc, clock, rng, new DhtNode.Net() {
            @Override public void send(int nodeId, int zone, byte[] message) { node.sendControl(Packet.TYPE_DHT, nodeId, zone, message, 120); }
        });
        if (auth != null) {
            RecordGuard guard = new RecordGuard(auth);
            gossip.setGuard(guard);
            dht.setGuard(guard);
        }
        final MeshServices svc = new MeshServices(node, clock, rng, gossip, dht, gc.gossipMs * 6);
        gossip.setListener(new GossipDirectory.Listener() {
            @Override
            public void onRecord(GossipDirectory.Record r) {
                if (r.ns == GossipDirectory.NS_LOCATION) {
                    node.hintLocation(r.subject, r.value, 600_000);
                    dht.addContact(r.subject, r.value);
                }
            }
        });
        node.setControlHandler(new MeshNode.ControlHandler() {
            @Override
            public void onControl(MeshNode n, Packet p, int fromPeer) {
                if (p.type == Packet.TYPE_GOSSIP) {
                    if (p.srcZone >= 0) dht.addContact(p.src, p.srcZone);
                    gossip.onMessage(p.src, p.payload);
                } else if (p.type == Packet.TYPE_DHT) {
                    dht.onMessage(p.src, p.payload);
                }
            }
        });
        return svc;
    }

    /** Publish myself and keep the services running. */
    public void start() {
        if (running) return;
        running = true;
        gossip.start();
        dht.start();
        announce();
        schedule();
    }

    public void stop() {
        running = false;
        gossip.stop();
        dht.stop();
    }

    /** My zone or gateway role changed (or I just started): say so. */
    public void announce() {
        refreshContacts();
        dht.setZone(node.zone());
        gossip.publishLocation(node.zone());
        if (node.isGateway()) gossip.publishGateway(node.zone(), 200);
        else gossip.withdraw(GossipDirectory.NS_GATEWAY, node.zone());
        dht.publishLocation();
    }

    private void refreshContacts() {
        for (int peer : node.peers()) {
            int z = node.peerZoneOf(peer);
            if (z >= 0) dht.addContact(peer, z);
        }
    }

    private void schedule() {
        clock.schedule(maintainMs, new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                refreshContacts();
                if (node.isGateway()) gossip.publishGateway(node.zone(), 200);
                schedule();
            }
        });
    }

    /** Join the DHT through a node I know (a neighbour, a seed from gossip). */
    public void bootstrap(int seedNode, int seedZone) {
        dht.bootstrap(seedNode, seedZone);
    }

    /**
     * Find a node's zone: what the node already knows, then gossip, then a DHT lookup. The callback runs once
     * (possibly at once); zone is -1 if nobody knows.
     */
    public void resolve(final int nodeId, final DhtNode.LocationCallback cb) {
        int z = node.knownZoneOf(nodeId);
        if (z < 0) z = gossip.zoneOf(nodeId);
        if (z >= 0) {
            cb.onResult(nodeId, z);
            return;
        }
        dht.lookupLocation(nodeId, new DhtNode.LocationCallback() {
            @Override
            public void onResult(int id, int zone) {
                if (zone >= 0) {
                    node.hintLocation(id, zone, 600_000);
                    dht.addContact(id, zone);
                }
                cb.onResult(id, zone);
            }
        });
    }
}

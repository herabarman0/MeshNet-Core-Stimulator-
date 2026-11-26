package com.herabarman.meshnet.kit;

import com.herabarman.meshnet.core.MemoryStorage;
import java.io.File;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/** Runs server/test-server.js (needs `node`) and checks HttpServerLink + two MeshClients over real HTTP. */
public final class HttpLinkTest {
    public static void main(String[] args) throws Exception {
        int port = 18080;
        Process node = new ProcessBuilder("node", "server/test-server.js", Integer.toString(port))
                .directory(new File(args.length > 0 ? args[0] : ".")).redirectErrorStream(true).start();
        try {
            Thread.sleep(1000);
            String base = "http://127.0.0.1:" + port + "/api/mesh";
            // 1. raw link
            HttpServerLink a = new HttpServerLink(base, null), b = new HttpServerLink(base, null);
            final List<byte[]> got = new CopyOnWriteArrayList<>();
            a.start(1, p -> { });
            b.start(2, got::add);
            KitTests.waitFor(() -> a.available() && b.available(), 3000);
            a.refresh(2, 3);
            KitTests.check("http: presence shows B online, C offline", a.isOnline(2) && !a.isOnline(3));
            a.publishZone(777);
            Thread.sleep(200);
            b.publishZone(555);
            Thread.sleep(200);
            a.refresh(2);
            KitTests.check("http: zone published by B is visible to A", a.zoneOf(2) == 555);
            byte[] pk = new byte[300];
            Arrays.fill(pk, (byte) 9);
            a.send(2, pk);
            KitTests.check("http: packet relayed to online node, byte-exact",
                    KitTests.waitFor(() -> got.size() == 1 && Arrays.equals(got.get(0), pk), 4000));
            a.send(3, pk);
            Thread.sleep(500);
            a.refresh(3);
            KitTests.check("http: nothing is kept for an offline node", !a.isOnline(3));
            a.stop();
            b.stop();

            // 2. two clients, no radio at all, only the internet path
            KitTests.Peer.ROUTING = MeshClient.Routing.FLAT;
            MemNet net = new MemNet();
            MeshClient.Listener la = new KitTests.Probe(), lb = new KitTests.Probe();
            MeshClient ca = MeshClient.builder("71").radio(net.radio(71)).storage(new MemoryStorage()).listener(la)
                    .server(new HttpServerLink(base, null)).retry(400, 200).build();
            MeshClient cb = MeshClient.builder("72").radio(net.radio(72)).storage(new MemoryStorage()).listener(lb)
                    .server(new HttpServerLink(base, null)).retry(400, 200).build();
            ca.start();
            cb.start();
            Thread.sleep(1200);
            long id = ca.sendText("72", "over the internet only");
            KitTests.check("http: MeshClient A -> B through the server (keys, text, receipt)",
                    KitTests.waitFor(() -> ((KitTests.Probe) lb).texts.containsKey(id) && ((KitTests.Probe) la).delivered.contains(id), 15000));
            ca.stop();
            cb.stop();

            // 3. the same two clients with direct paths: the server only introduces them, then the packets go phone to phone
            MemNet net2 = new MemNet();
            KitTests.Probe pa = new KitTests.Probe(), pb = new KitTests.Probe();
            P2pServerLink la2 = new P2pServerLink(new HttpServerLink(base, null), java.util.Collections.<String>emptyList(), 0, true);
            P2pServerLink lb2 = new P2pServerLink(new HttpServerLink(base, null), java.util.Collections.<String>emptyList(), 0, true);
            MeshClient c1 = MeshClient.builder("81").radio(net2.radio(81)).storage(new MemoryStorage()).listener(pa).server(la2).retry(400, 200).build();
            MeshClient c2 = MeshClient.builder("82").radio(net2.radio(82)).storage(new MemoryStorage()).listener(pb).server(lb2).retry(400, 200).build();
            c1.start();
            c2.start();
            Thread.sleep(1200);
            long first = c1.sendText("82", "first message: keys, and the server introduces the two phones");
            KitTests.check("p2p: the first message gets through (server relay)",
                    KitTests.waitFor(() -> pb.texts.containsKey(first) && pa.delivered.contains(first), 15000));
            KitTests.check("p2p: the two phones make a direct path",
                    KitTests.waitFor(() -> la2.manager() != null && la2.manager().isOpen(82) && lb2.manager() != null && lb2.manager().isOpen(81), 12000));
            long relayBefore = la2.sentViaServer() + lb2.sentViaServer();
            long directBefore = la2.sentDirect() + lb2.sentDirect();
            long second = c1.sendText("82", "second message: straight from phone to phone");
            KitTests.check("p2p: the next message arrives and is confirmed",
                    KitTests.waitFor(() -> pb.texts.containsKey(second) && pa.delivered.contains(second), 10000));
            KitTests.check("p2p: it did not touch the server (" + (la2.sentViaServer() + lb2.sentViaServer() - relayBefore) + " relayed, "
                    + (la2.sentDirect() + lb2.sentDirect() - directBefore) + " direct)",
                    la2.sentViaServer() + lb2.sentViaServer() == relayBefore && la2.sentDirect() + lb2.sentDirect() > directBefore);
            byte[] photo = new byte[40_000];
            new java.util.Random(6).nextBytes(photo);
            long big = c1.sendLarge("82", photo);
            KitTests.check("p2p: a 40 KB file goes over the direct path too, byte for byte",
                    KitTests.waitFor(() -> pb.blobs.containsKey(big) && java.util.Arrays.equals(pb.blobs.get(big), photo) && pa.delivered.contains(big), 20000));
            KitTests.check("p2p: and still not through the server", la2.sentViaServer() + lb2.sentViaServer() == relayBefore);
            c1.stop();
            c2.stop();
        } finally {
            node.destroy();
        }
        System.out.println("\n" + KitTests.pass + " passed, " + KitTests.fail + " failed");
        System.exit(KitTests.fail == 0 ? 0 : 1);
    }
}

package com.herabarman.meshnet.kit;

import static com.herabarman.meshnet.kit.KitTests.check;
import static com.herabarman.meshnet.kit.KitTests.waitFor;

import com.herabarman.meshnet.core.MemoryStorage;
import java.util.Arrays;

/** The small-device preset and the diagnostics report. */
public final class LiteTests {
    static MeshClient lite(MemNet net, KitTests.Probe p, int id) {
        MeshClient c = MeshClient.builder(Integer.toString(id)).radio(net.radio(id)).storage(new MemoryStorage()).listener(p)
                .retry(400, 200).zoneHold(100)
                .tune(Presets.both(Presets.lite(), cfg -> { cfg.ackTimeoutMs = 700; cfg.retryMs = 300; }))
                .build();
        c.updateLocation(KitTests.LAT0, KitTests.LON0);
        c.start();
        return c;
    }

    public static void main(String[] args) {
        MemNet net = new MemNet();
        KitTests.Probe pa = new KitTests.Probe(), pb = new KitTests.Probe();
        MeshClient a = lite(net, pa, 1), b = lite(net, pb, 2);
        net.link(1, 2);
        check("lite: neighbours up", waitFor(() -> a.peerCount() == 1 && b.peerCount() == 1, 3000));
        long t = a.sendText("2", "hello from a small device");
        check("lite: text delivered", waitFor(() -> pa.delivered.contains(t), 10000));
        byte[] data = KitTests.randomBytes(1_000_000, 4);
        long id = a.sendLarge("2", data);
        check("lite: a 1 MB file still arrives byte-exact through the mesh", waitFor(() -> pb.blobs.containsKey(id) && Arrays.equals(pb.blobs.get(id), data), 90000));
        String d = a.diagnostics();
        System.out.println(d.replaceAll("(?m)^", "      "));
        check("diagnostics: lists the node, the neighbour and the counters", d.contains("node 1") && d.contains("neighbours 1") && d.contains("delivered") && d.contains("bulk: off"));
        check("diagnostics: contains no message content", !d.contains("hello from a small device"));
        a.stop(); b.stop();
        KitTests.finish();
    }
}

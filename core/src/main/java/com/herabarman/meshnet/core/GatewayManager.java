package com.herabarman.meshnet.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Picks gateway nodes for a zone. score = battery + signal + internet availability. */
public final class GatewayManager {
    private final double wBattery;
    private final double wSignal;
    private final double wInternet;
    private final double minBattery;
    private final double wStability;

    public GatewayManager() {
        this(0.4, 0.3, 0.3);
    }

    public GatewayManager(double wBattery, double wSignal, double wInternet) {
        this(wBattery, wSignal, wInternet, 0.0);
    }

    /** @param minBattery nodes below this level are not elected while any better candidate exists */
    public GatewayManager(double wBattery, double wSignal, double wInternet, double minBattery) {
        this.wBattery = wBattery;
        this.wSignal = wSignal;
        this.wInternet = wInternet;
        this.minBattery = minBattery;
        this.wStability = 0.0;
    }

    /**
     * wStability > 0 makes moving nodes lose points (a gateway that walks away takes its links with it).
     * minBattery as in the 4-argument constructor.
     */
    public GatewayManager(double wBattery, double wSignal, double wInternet, double minBattery, double wStability) {
        this.wBattery = wBattery;
        this.wSignal = wSignal;
        this.wInternet = wInternet;
        this.minBattery = minBattery;
        this.wStability = wStability;
    }

    public double score(NodeInfo n) {
        double battery = n.charging ? 1.0 : n.battery;
        return wBattery * battery + wSignal * n.signal + wInternet * (n.online ? 1.0 : 0.0)
                - wStability * (n.mobility / 3.0);
    }

    private List<NodeInfo> aliveSorted(Collection<NodeInfo> members) {
        List<NodeInfo> alive = new ArrayList<>();
        for (NodeInfo n : members) if (n.alive && n.battery >= minBattery) alive.add(n);
        if (alive.isEmpty()) {                       // everybody is low: better a weak gateway than none
            for (NodeInfo n : members) if (n.alive) alive.add(n);
        }
        alive.sort(new Comparator<NodeInfo>() {
            @Override
            public int compare(NodeInfo a, NodeInfo b) {
                int c = Double.compare(score(b), score(a));
                return c != 0 ? c : Integer.compare(a.id, b.id);
            }
        });
        return alive;
    }

    /** Fresh election: the best `count` alive members. */
    public List<Integer> elect(Collection<NodeInfo> members, int count) {
        List<Integer> out = new ArrayList<>();
        for (NodeInfo n : aliveSorted(members)) {
            if (out.size() >= count) break;
            out.add(n.id);
        }
        return out;
    }

    /**
     * Re-election after a failure: healthy current gateways keep their role (no flapping),
     * vacancies are filled with the best remaining members.
     */
    public List<Integer> reelect(Collection<NodeInfo> members, List<Integer> current, int count) {
        Map<Integer, NodeInfo> alive = new HashMap<>();
        for (NodeInfo n : members) if (n.alive) alive.put(n.id, n);
        List<Integer> out = new ArrayList<>();
        for (int id : current) {
            if (out.size() >= count) break;
            NodeInfo n = alive.get(id);
            if (n != null && n.battery >= minBattery) out.add(id);   // a gateway that ran low is relieved of the job
        }
        if (out.size() < count) {
            for (NodeInfo n : aliveSorted(members)) {
                if (out.size() >= count) break;
                if (!out.contains(n.id)) out.add(n.id);
            }
        }
        return out;
    }
}

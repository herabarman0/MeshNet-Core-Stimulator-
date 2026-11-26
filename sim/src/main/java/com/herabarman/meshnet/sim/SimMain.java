package com.herabarman.meshnet.sim;

import com.herabarman.meshnet.core.DeliveryMode;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Usage: java -jar sim.jar [--nodes 1000,10000,100000] [--zoneSize 15] [--gateways 2]
 *   [--online 0.2] [--churn 0.01] [--gwFail 0.05] [--messages 1000] [--local 0.3]
 *   [--loss 0.0] [--seed 1] [--encrypt true] [--csv results.csv]
 *   [--floodJitter 40] [--counter 3] [--fanout 0] [--classic true]  (flood optimisation; classic = old flooding)
 *   [--backbone auto|full|chord] [--links 2] [--mode offline-only|offline-first|online-first]  (zone-to-zone mesh; default: mesh first, server only helps)
 *   [--discovery on|off|auto]  (nodes learn zones themselves; auto = on up to 5,000 nodes)
 *   [--capacity 30] [--pacing 20] [--burst 3000 --burst-ms 10000 --bulk 0.5]  (radio capacity, engine pacing, traffic burst)
 *   [--energy-tx 0.002 --energy-rx 0.0005 --battery-aware true]  (battery drain model, relay-free low-battery nodes)
 *   [--routes true] [--ack true] [--retries 2] [--ackTimeout 3000] [--conv 6]  (route learning, ACK, messages per conversation)
 *   [--adaptive true] [--energy true] [--congestion true] [--mobility true] [--beacon 10000]  (multipath + link metrics, battery awareness, load shedding, movement awareness)
 *   [--badLinks 0.2] [--badLinkLoss 0.5] [--txDrain 0.0005] [--rxDrain 0.0001] [--move 0.02] [--moveEvery 10000] [--capacity 40]
 * A comma list for --nodes runs one simulation per size.
 */
public final class SimMain {
    public static void main(String[] args) throws IOException {
        SimConfig base = new SimConfig();
        base.discovery = "auto";
        List<Integer> sizes = new ArrayList<>();
        String csv = null;
        for (int i = 0; i + 1 < args.length; i += 2) {
            String k = args[i];
            String v = args[i + 1];
            switch (k) {
                case "--nodes": for (String s : v.split(",")) sizes.add(Integer.parseInt(s.trim())); break;
                case "--zoneSize": base.zoneSize = Integer.parseInt(v); break;
                case "--gateways": base.gatewayCount = Integer.parseInt(v); break;
                case "--online": base.onlineRatio = Double.parseDouble(v); break;
                case "--churn": base.churn = Double.parseDouble(v); break;
                case "--gwFail": base.gatewayFail = Double.parseDouble(v); break;
                case "--messages": base.messages = Integer.parseInt(v); break;
                case "--local": base.localRatio = Double.parseDouble(v); break;
                case "--loss": base.lossRate = Double.parseDouble(v); break;
                case "--seed": base.seed = Long.parseLong(v); break;
                case "--encrypt": base.encrypt = Boolean.parseBoolean(v); break;
                case "--floodJitter": base.floodJitterMs = Integer.parseInt(v); break;
                case "--counter": base.counterThreshold = Integer.parseInt(v); break;
                case "--fanout": base.floodFanout = Integer.parseInt(v); break;
                case "--routes": base.routeLearning = Boolean.parseBoolean(v); break;
                case "--ack": base.ackEnabled = Boolean.parseBoolean(v); break;
                case "--retries": base.maxRetries = Integer.parseInt(v); break;
                case "--ackTimeout": base.ackTimeoutMs = Long.parseLong(v); break;
                case "--backbone": base.backbone = v; break;
                case "--links": base.linksPerPair = Integer.parseInt(v); break;
                case "--mode": base.mode = DeliveryMode.valueOf(v.toUpperCase().replace('-', '_')); break;
                case "--discovery": base.discovery = v; break;
                case "--capacity": base.radioCapacityPerSec = Double.parseDouble(v); break;
                case "--pacing": base.txRatePerSec = Double.parseDouble(v); break;
                case "--energy-tx": base.energyPerTx = Double.parseDouble(v); break;
                case "--energy-rx": base.energyPerRx = Double.parseDouble(v); break;
                case "--battery-aware": base.batteryAware = Boolean.parseBoolean(v); break;
                case "--burst": base.burstMessages = Integer.parseInt(v); break;
                case "--burst-ms": base.burstWindowMs = Long.parseLong(v); break;
                case "--bulk": base.bulkRatio = Double.parseDouble(v); break;
                case "--conv": base.conversationLength = Integer.parseInt(v); break;
                case "--classic":
                    if (Boolean.parseBoolean(v)) {
                        base.floodJitterMs = 0; base.counterThreshold = 0; base.floodFanout = 0;
                        base.routeLearning = false; base.ackEnabled = false;
                    }
                    break;
                case "--adaptive": base.adaptive = Boolean.parseBoolean(v); break;
                case "--energy": base.energyAware = Boolean.parseBoolean(v); break;
                case "--congestion": base.congestion = Boolean.parseBoolean(v); break;
                case "--mobility": base.mobilityAware = Boolean.parseBoolean(v); break;
                case "--beacon": base.beaconMs = Long.parseLong(v); break;
                case "--badLinks": base.badLinkRatio = Double.parseDouble(v); break;
                case "--badLinkLoss": base.badLinkLoss = Double.parseDouble(v); break;
                case "--txDrain": base.txDrain = Double.parseDouble(v); break;
                case "--rxDrain": base.rxDrain = Double.parseDouble(v); break;
                case "--move": base.moveRatio = Double.parseDouble(v); break;
                case "--moveEvery": base.moveIntervalMs = Long.parseLong(v); break;
                case "--load-capacity": base.loadCapacity = Double.parseDouble(v); break;
                case "--csv": csv = v; break;
                default: System.err.println("unknown option " + k); System.exit(2);
            }
        }
        if (sizes.isEmpty()) sizes.add(base.nodes);

        boolean writeHeader = csv != null && !new File(csv).exists();
        FileWriter out = csv == null ? null : new FileWriter(csv, true);
        if (out != null && writeHeader) out.write(Metrics.csvHeader() + "\n");
        for (int size : sizes) {
            SimConfig cfg = base.copy();
            cfg.nodes = size;
            long t0 = System.currentTimeMillis();
            Metrics m = Simulation.run(cfg);
            System.out.println(m.summary(cfg) + m.extraSummary(cfg) + String.format("%n  (wall time %.1fs)%n", (System.currentTimeMillis() - t0) / 1000.0));
            if (out != null) out.write(m.csvRow(cfg) + "\n");
        }
        if (out != null) out.close();
    }
}

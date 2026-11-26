package com.herabarman.meshnet.sim;

import static org.junit.jupiter.api.Assertions.*;

import com.herabarman.meshnet.core.MeshNode;
import com.herabarman.meshnet.core.Packet;
import com.herabarman.meshnet.core.Priority;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Priority levels on a saturated radio: who gets through first, measured in simulated time. */
class QosTest {
    /** Records when each DATA packet reaches its destination, then passes everything on. */
    private static final class Clocked implements MeshNode.Listener {
        final MeshNode.Listener down;
        final Map<Long, Long> at;
        final SimNetwork net;

        Clocked(MeshNode.Listener down, Map<Long, Long> at, SimNetwork net) { this.down = down; this.at = at; this.net = net; }

        @Override public void onDelivered(MeshNode n, Packet p, byte[] plain, int from) {
            if (p.type == Packet.TYPE_DATA) at.putIfAbsent(p.msgId, net.clock.nowMs());
            down.onDelivered(n, p, plain, from);
        }
        @Override public void onDuplicate(MeshNode n, Packet p) { down.onDuplicate(n, p); }
        @Override public void onDropped(MeshNode n, Packet p, String r) { down.onDropped(n, p, r); }
        @Override public void onFloodSuppressed(MeshNode n, Packet p) { down.onFloodSuppressed(n, p); }
        @Override public void onAcked(MeshNode n, long id) { down.onAcked(n, id); }
        @Override public void onRetry(MeshNode n, Packet p) { down.onRetry(n, p); }
        @Override public void onShed(MeshNode n, Packet p) { down.onShed(n, p); }
    }

    private static final class Run {
        final Map<Long, Long> at = new HashMap<>();
        final List<long[]> sent = new ArrayList<>();        // msgId, time, priority

        long median(int priority) {
            List<Long> l = new ArrayList<>();
            for (long[] s : sent) if (s[2] == priority && at.containsKey(s[0])) l.add(at.get(s[0]) - s[1]);
            if (l.isEmpty()) return Long.MAX_VALUE;
            Collections.sort(l);
            return l.get(l.size() / 2);
        }

        int lost(int priority) {
            int n = 0;
            for (long[] s : sent) if (s[2] == priority && !at.containsKey(s[0])) n++;
            return n;
        }
    }

    private static SimConfig cfg() {
        SimConfig c = new SimConfig();
        c.nodes = 12;
        c.zoneSize = 12;
        c.onlineRatio = 0;
        c.seed = 5;
        c.txRatePerSec = 12;                                // a budget of 12 packets a second per node
        c.txBurst = 8;
        return c;
    }

    /**
     * A 60 KB file is on its way; on top of that a text flood of about 30 messages a second, far over the 12 a
     * second the radio may send, with an alarm every 12th message. With levels the alarm is sent at once; with
     * everything at one level the alarm waits behind the flood like any other message.
     */
    private static Run floodWithAlarms(boolean levels) {
        SimNetwork net = new SimNetwork(cfg());
        net.enableChunking();
        Run r = new Run();
        int src = 1, dst = 7;
        net.node(dst).setListener(new Clocked(net.chunkLayer(dst), r.at, net));
        net.sendMessage(src, dst, new byte[]{1}, Priority.NORMAL);
        net.clock.runFor(3_000);                             // route learned
        net.sendLarge(src, dst, new byte[60_000], Priority.BULK);
        net.clock.runFor(1_500);
        for (int i = 0; i < 120; i++) {
            int level = i % 12 == 0 ? Priority.SOS : Priority.NORMAL;
            long id = net.sendMessage(src, dst, new byte[]{(byte) i}, levels ? level : Priority.NORMAL);
            r.sent.add(new long[]{id, net.clock.nowMs(), level});
            net.clock.runFor(33);
        }
        net.clock.runFor(90_000);
        return r;
    }

    @Test
    void anAlarmGetsThroughAtOnceWhileTheRadioIsSaturated() {
        Run with = floodWithAlarms(true);
        Run without = floodWithAlarms(false);
        assertEquals(0, with.lost(Priority.SOS), "no alarm is lost");
        assertTrue(with.median(Priority.SOS) < 150, "alarm median " + with.median(Priority.SOS) + " ms");
        assertTrue(without.median(Priority.SOS) > 2_000, "the same messages without levels wait: " + without.median(Priority.SOS) + " ms");
        assertTrue(with.median(Priority.SOS) * 10 < with.median(Priority.NORMAL), "the flood itself is queued: normal median " + with.median(Priority.NORMAL) + " ms");
    }

    @Test
    void chatTextIsNotStuckBehindAFileInTransfer() {
        SimNetwork net = new SimNetwork(cfg());
        net.enableChunking();
        Run r = new Run();
        int src = 1, dst = 7;
        net.node(dst).setListener(new Clocked(net.chunkLayer(dst), r.at, net));
        net.sendMessage(src, dst, new byte[]{1}, Priority.NORMAL);
        net.clock.runFor(3_000);
        net.sendLarge(src, dst, new byte[60_000], Priority.BULK);
        net.clock.runFor(1_500);
        for (int i = 0; i < 40; i++) {
            int level = i % 2 == 0 ? Priority.NORMAL : Priority.BULK;     // the same message, once as chat and once at file level
            long id = net.sendMessage(src, dst, new byte[]{(byte) i}, level);
            r.sent.add(new long[]{id, net.clock.nowMs(), level});
            net.clock.runFor(400);
        }
        net.clock.runFor(90_000);
        assertEquals(0, r.lost(Priority.NORMAL));
        assertTrue(r.median(Priority.NORMAL) < 150, "chat median " + r.median(Priority.NORMAL) + " ms");
        assertTrue(r.median(Priority.BULK) > 5 * r.median(Priority.NORMAL), "at file level the same message waits: " + r.median(Priority.BULK) + " ms");
    }

    /** Anyone can write SOS into a packet: relays pass only so many per minute from one sender at that level. */
    @Test
    void aNodeCannotKeepTheEmergencyLaneForItselfBySpammingSos() {
        SimConfig c = cfg();
        c.txRatePerSec = 0;
        SimNetwork net = new SimNetwork(c);
        final int[] sosAtDst = {0}, total = {0};
        final int dst = 7;
        net.node(dst).setListener(new MeshNode.Listener() {
            @Override public void onDelivered(MeshNode n, Packet p, byte[] plain, int from) {
                if (p.type != Packet.TYPE_DATA) return;
                total[0]++;
                if (p.priority == Priority.SOS) sosAtDst[0]++;
            }
            @Override public void onDuplicate(MeshNode n, Packet p) { }
            @Override public void onDropped(MeshNode n, Packet p, String r) { }
            @Override public void onFloodSuppressed(MeshNode n, Packet p) { }
            @Override public void onAcked(MeshNode n, long id) { }
            @Override public void onRetry(MeshNode n, Packet p) { }
            @Override public void onShed(MeshNode n, Packet p) { }
        });
        for (int i = 0; i < 100; i++) {
            net.sendMessage(1, dst, new byte[]{(byte) i}, Priority.SOS);
            net.clock.runFor(100);
        }
        net.clock.runFor(10_000);
        assertTrue(total[0] >= 95, "the messages are all still delivered: " + total[0]);
        assertTrue(sosAtDst[0] <= 30, "only " + 30 + " per minute keep the SOS level, saw " + sosAtDst[0]);
        assertTrue(sosAtDst[0] >= 20, "a real burst of alarms is not touched: " + sosAtDst[0]);
    }
}

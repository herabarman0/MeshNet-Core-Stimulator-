package com.herabarman.meshnet.kit;

import com.herabarman.meshnet.core.ChannelPlanner;
import com.herabarman.meshnet.core.Clock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Anti-jamming radio: bundles several RawRadios, one per channel or technology (BLE, Wi-Fi 2.4 GHz, 5 GHz, LoRa ...), and moves
 * every link to a free channel when its current one is jammed or noisy, then back when the channel recovers (see
 * {@link ChannelPlanner}). Plugs in wherever a RawRadio goes.
 *
 * Both ends must use it (it adds a 1-byte header and a small keep-alive, about 7 tiny frames per second per neighbour, that
 * measures each channel). Frame header: 0 data, 1 planner control, 2 ping, 3 pong.
 *
 * Apps with a single radio stack (Nearby Connections picks the medium by itself and gives no channel control) cannot use this
 * directly: it needs separate radios per channel, for example BLE + Wi-Fi Direct/Aware + UDP/LoRa.
 */
public final class MultiChannelRadio implements RawRadio {
    private static final int DATA = 0, CONTROL = 1, PING = 2, PONG = 3;
    private static final long PING_MS = 300, PING_TIMEOUT_MS = 700;

    private final int self;
    private final LinkedHashMap<Integer, RawRadio> radios;
    private final ChannelPlanner planner;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(new java.util.concurrent.ThreadFactory() {
        @Override public Thread newThread(Runnable r) { Thread t = new Thread(r, "mesh-multichannel"); t.setDaemon(true); return t; }
    });
    private final Object lock = new Object();
    private final Map<Integer, Set<Integer>> upOn = new HashMap<>();              // peer -> channels where the link is up
    private final Map<Long, long[]> pings = new HashMap<>();                       // key(peer, seq) -> {sentAt, channel}
    private static final long YOUNG_MS = 5000;
    private final Map<Integer, Long> firstUpAt = new HashMap<>();
    private final Map<Integer, Double> caps = new HashMap<>();
    private int seq;
    private Handler handler;
    private boolean running;
    private final int[] channels;

    /**
     * @param radios channel id (0..255) to the radio that works on that channel, in order of preference is not needed: capacity says it
     * @param capacity relative quality of each channel when clear (0..1), same order as radios' keys
     */
    public MultiChannelRadio(int selfNode, LinkedHashMap<Integer, RawRadio> radios, double[] capacity, ChannelPlanner.Config cfg) {
        this.self = selfNode;
        this.radios = radios;
        { int k = 0; for (int chn : radios.keySet()) caps.put(chn, capacity[k++]); }
        channels = new int[radios.size()];
        int i = 0;
        for (int ch : radios.keySet()) channels[i++] = ch;
        final Clock clock = new Clock() {
            @Override public long nowMs() { return System.currentTimeMillis(); }
            @Override public void schedule(long delayMs, final Runnable task) {
                try { exec.schedule(new Runnable() { @Override public void run() { synchronized (lock) { task.run(); } } }, delayMs, TimeUnit.MILLISECONDS); }
                catch (java.util.concurrent.RejectedExecutionException stopped) { }
            }
        };
        planner = new ChannelPlanner(selfNode, channels, capacity, cfg == null ? new ChannelPlanner.Config() : cfg, clock, new Random(), new ChannelPlanner.Link() {
            // never call out of the planner while holding the lock: two phones' radios would wait for each other's locks
            @Override public void sendOn(final int channel, final int peer, final byte[] control) {
                try { exec.execute(new Runnable() { @Override public void run() { raw(channel, peer, CONTROL, control); } }); }
                catch (java.util.concurrent.RejectedExecutionException stopped) { }
            }
        });
        for (final Map.Entry<Integer, RawRadio> e : radios.entrySet()) {
            final int ch = e.getKey();
            e.getValue().setHandler(new Handler() {
                @Override public void onLinkUp(int peer) { linkUp(ch, peer); }
                @Override public void onLinkDown(int peer) { linkDown(ch, peer); }
                @Override public void onRaw(int peer, byte[] data) { received(ch, peer, data); }
            });
        }
    }

    private double capacityOf(int ch) { Double d = caps.get(ch); return d == null ? 0 : d; }

    public ChannelPlanner planner() { return planner; }

    /** Pass on a signal-strength reading from a channel's radio, if it has one. */
    public void reportRssi(int peer, int channel, int dbm) { synchronized (lock) { planner.onRssi(peer, channel, dbm); } }

    /** Pass on the noise floor of a channel (Wi-Fi scan, BLE energy detect), if the radio can measure it. */
    public void reportNoise(int channel, double dbm) { synchronized (lock) { planner.onNoiseFloor(channel, dbm); } }

    /** The channel a peer is on right now, or -1. */
    public int channelOf(int peer) { synchronized (lock) { return planner.channelFor(peer); } }

    @Override public void setHandler(Handler h) { this.handler = h; }

    @Override public void start() {
        running = true;
        for (RawRadio r : radios.values()) r.start();
        synchronized (lock) { planner.start(); }
        exec.scheduleWithFixedDelay(new Runnable() { @Override public void run() { try { pingRound(); } catch (RuntimeException ignored) { } } }, PING_MS, PING_MS, TimeUnit.MILLISECONDS);
    }

    @Override public void stop() {
        running = false;
        synchronized (lock) { planner.stop(); }
        for (RawRadio r : radios.values()) r.stop();
        exec.shutdownNow();
    }

    @Override public void drop(int peer) { for (RawRadio r : radios.values()) r.drop(peer); }

    @Override public void sendRaw(int peer, byte[] data) {
        int ch;
        synchronized (lock) {
            ch = planner.channelFor(peer);
            Set<Integer> up = upOn.get(peer);
            if (up != null && !up.contains(ch) && !up.isEmpty()) ch = up.iterator().next();      // the planner's channel is not up (yet): any that is
        }
        if (ch >= 0) raw(ch, peer, DATA, data);
    }

    // ---- internals -----------------------------------------------------------------------------

    private void raw(int channel, int peer, int type, byte[] body) {
        RawRadio r = radios.get(channel);
        if (r == null) return;
        byte[] f = new byte[1 + body.length];
        f[0] = (byte) type;
        System.arraycopy(body, 0, f, 1, body.length);
        r.sendRaw(peer, f);
    }

    private void linkUp(int ch, int peer) {
        boolean first;
        synchronized (lock) {
            Set<Integer> up = upOn.get(peer);
            first = up == null || up.isEmpty();
            if (up == null) { up = new HashSet<>(); upOn.put(peer, up); }
            up.add(ch);
            int[] have = new int[up.size()];
            int i = 0;
            for (int c : up) have[i++] = c;
            long nowMs = System.currentTimeMillis();
            if (first) firstUpAt.put(peer, nowMs);
            int keep = first ? ch : planner.channelFor(peer) < 0 ? ch : planner.channelFor(peer);
            Long t0 = firstUpAt.get(peer);
            if (!first && t0 != null && nowMs - t0 < YOUNG_MS) {
                // radios of one link rarely come up at the same instant: while the link is young, start on the best channel that is up now
                double bestCap = -1;
                for (int c : up) { double cp = capacityOf(c); if (cp > bestCap) { bestCap = cp; keep = c; } }
            }
            planner.removePeer(peer);
            planner.addPeer(peer, have, keep);
        }
        if (first && handler != null) handler.onLinkUp(peer);
    }

    private void linkDown(int ch, int peer) {
        boolean gone = false;
        synchronized (lock) {
            Set<Integer> up = upOn.get(peer);
            if (up == null) return;
            up.remove(ch);
            if (up.isEmpty()) { upOn.remove(peer); planner.removePeer(peer); gone = true; }
            else {
                int[] have = new int[up.size()];
                int i = 0;
                for (int c : up) have[i++] = c;
                int cur = planner.channelFor(peer);
                planner.removePeer(peer);
                planner.addPeer(peer, have, cur == ch ? have[0] : cur);
            }
        }
        if (gone && handler != null) handler.onLinkDown(peer);
    }

    private void received(int ch, int peer, byte[] f) {
        if (f.length < 1) return;
        byte[] body = java.util.Arrays.copyOfRange(f, 1, f.length);
        switch (f[0] & 0xFF) {
            case DATA:
                synchronized (lock) { planner.onReceive(peer, ch); }
                if (handler != null) handler.onRaw(peer, body);
                break;
            case CONTROL:
                synchronized (lock) { planner.onControl(peer, ch, body); }
                break;
            case PING:
                raw(ch, peer, PONG, body);
                synchronized (lock) { planner.onReceive(peer, ch); }
                break;
            case PONG:
                if (body.length >= 4) {
                    int s = java.nio.ByteBuffer.wrap(body).getInt();
                    synchronized (lock) {
                        long[] p = pings.remove(key(peer, s));
                        if (p != null) planner.onTx(peer, (int) p[1], true);
                    }
                }
                break;
            default:
                break;
        }
    }

    private void pingRound() {
        if (!running) return;
        long now = System.currentTimeMillis();
        List<int[]> toSend = new ArrayList<>();
        synchronized (lock) {
            for (java.util.Iterator<Map.Entry<Long, long[]>> it = pings.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Long, long[]> e = it.next();
                if (now - e.getValue()[0] > PING_TIMEOUT_MS) {
                    planner.onTx((int) (e.getKey() >>> 32), (int) e.getValue()[1], false);
                    it.remove();
                }
            }
            for (int peer : new ArrayList<>(upOn.keySet())) {
                int ch = planner.channelFor(peer);
                if (ch < 0) continue;
                int s = ++seq;
                pings.put(key(peer, s), new long[]{now, ch});
                toSend.add(new int[]{peer, ch, s});
            }
        }
        for (int[] t : toSend) raw(t[1], t[0], PING, java.nio.ByteBuffer.allocate(4).putInt(t[2]).array());
    }

    private static long key(int peer, int seq) { return ((long) peer << 32) | (seq & 0xFFFFFFFFL); }
}

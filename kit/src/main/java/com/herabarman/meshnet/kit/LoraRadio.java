package com.herabarman.meshnet.kit;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Protocol layer for a LoRa modem (a serial / USB / Bluetooth-attached SX127x board, a Meshtastic-style device in
 * raw mode, a satellite-IoT modem): turns "small frames on a shared, very slow, duty-cycle-limited medium" into
 * the RawRadio the mesh expects. The modem itself is behind the Modem interface (a few lines per hardware).
 *
 *  - fragmentation: frames are cut to the modem's maximum (typically 200-250 bytes) and put together again
 *  - neighbours: a HELLO beacon every helloMs; silence for downMs ends the link (LoRa has no connections)
 *  - duty cycle: at most dutyFraction of the time on air within any window (EU 868 MHz: 1 % per hour), computed from
 *    the data rate; frames wait in a bounded queue (oldest dropped first) until the budget allows
 *  - addressing: frames carry source and destination node ids; other nodes' frames are ignored
 *
 * Status: tested against a simulated modem and medium (loss, delay, MTU) only. Nothing here has touched RF hardware.
 * Header 12 bytes: src(4) dst(4, 0 = everyone) type(1) msgId(1) index(1) total(1).
 */
public final class LoraRadio implements RawRadio {
    /** What the hardware driver must provide. */
    public interface Modem {
        /** Transmit one frame (blocks until it is on air or queued in the modem). */
        void send(byte[] frame);
        void setReceiver(Receiver r);
        /** Largest frame in bytes the modem can transmit (e.g. 200). */
        int maxFrame();
    }

    public interface Receiver { void onFrame(byte[] frame); }

    public static final class Config {
        public long helloMs = 30_000, downMs = 100_000;
        public double bitsPerSecond = 5470;          // SF7, 125 kHz
        public double dutyFraction = 0.01;           // 1 %
        public long windowMs = 3_600_000;            // per hour
        public int maxQueue = 64;
    }

    static final int HEADER = 12, HELLO = 1, DATA = 2;

    private static final class Peer { long lastSeen; boolean up; }
    private static final class Assembly { final byte[][] parts; int got; final long started = System.currentTimeMillis(); Assembly(int n) { parts = new byte[n][]; } }
    private static final class Out { final byte[] frame; Out(byte[] f) { frame = f; } }

    private final int self;
    private final Modem modem;
    private final Config cfg;
    private final LinkedBlockingQueue<Out> queue = new LinkedBlockingQueue<>();
    private final ArrayDeque<long[]> airLog = new ArrayDeque<>();     // {timeMs, airtimeMs}
    private final Map<Integer, Peer> peers = new HashMap<>();
    private final Map<Long, Assembly> assemblies = new HashMap<>();
    private volatile Handler handler;
    private volatile boolean running;
    private int msgId;
    private long dropped;

    public LoraRadio(int selfNode, Modem modem, Config cfg) {
        this.self = selfNode;
        this.modem = modem;
        this.cfg = cfg;
        modem.setReceiver(new Receiver() { @Override public void onFrame(byte[] f) { receive(f); } });
    }

    @Override public void setHandler(Handler h) { this.handler = h; }

    public synchronized long droppedFrames() { return dropped; }

    public int queuedFrames() { return queue.size(); }

    /** Time on air used inside the current window, in milliseconds. */
    public synchronized long airtimeUsedMs() {
        trimLog(System.currentTimeMillis());
        long sum = 0;
        for (long[] e : airLog) sum += e[1];
        return sum;
    }

    @Override
    public void start() {
        if (running) return;
        running = true;
        Thread tx = new Thread(new Runnable() { @Override public void run() { pacer(); } }, "lora-tx");
        tx.setDaemon(true);
        tx.start();
        Thread tk = new Thread(new Runnable() { @Override public void run() { ticker(); } }, "lora-tick");
        tk.setDaemon(true);
        tk.start();
    }

    @Override
    public void stop() {
        running = false;
        java.util.List<Integer> was = new java.util.ArrayList<>();
        synchronized (this) {
            for (Map.Entry<Integer, Peer> e : peers.entrySet()) if (e.getValue().up) was.add(e.getKey());
            peers.clear();
        }
        queue.clear();
        Handler h = handler;
        if (h != null) for (int n : was) h.onLinkDown(n);
    }

    @Override
    public void sendRaw(int peerNode, byte[] data) {
        int per = modem.maxFrame() - HEADER;
        int total = Math.max(1, (data.length + per - 1) / per);
        if (total > 255) return;
        int id;
        synchronized (this) {
            id = ++msgId & 0xFF;
            if (!peers.containsKey(peerNode)) return;
        }
        for (int i = 0; i < total; i++) {
            int off = i * per, len = Math.min(per, data.length - off);
            enqueue(ByteBuffer.allocate(HEADER + len).putInt(self).putInt(peerNode).put((byte) DATA).put((byte) id)
                    .put((byte) i).put((byte) total).put(data, off, len).array());
        }
    }

    private void enqueue(byte[] frame) {
        while (queue.size() >= cfg.maxQueue) {
            if (queue.poll() != null) synchronized (this) { dropped++; }          // oldest first: fresh data matters more
        }
        queue.offer(new Out(frame));
    }

    // ---- transmit pacing (duty cycle)

    private double airtimeMs(int bytes) { return bytes * 8.0 / cfg.bitsPerSecond * 1000.0; }

    private synchronized void trimLog(long now) {
        while (!airLog.isEmpty() && now - airLog.peekFirst()[0] > cfg.windowMs) airLog.pollFirst();
    }

    private void pacer() {
        while (running) {
            Out o;
            try { o = queue.poll(200, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { return; }
            if (o == null) continue;
            double need = airtimeMs(o.frame.length);
            while (running) {
                long wait = 0;
                synchronized (this) {
                    long now = System.currentTimeMillis();
                    trimLog(now);
                    double used = 0;
                    for (long[] e : airLog) used += e[1];
                    double budget = cfg.dutyFraction * cfg.windowMs;
                    if (used + need <= budget) {
                        airLog.addLast(new long[]{now, Math.round(Math.ceil(need))});
                        break;
                    }
                    long[] oldest = airLog.peekFirst();
                    wait = oldest == null ? 50 : Math.max(20, oldest[0] + cfg.windowMs - now);
                }
                try { Thread.sleep(Math.min(wait, 250)); } catch (InterruptedException e) { return; }
            }
            if (running) modem.send(o.frame);
        }
    }

    // ---- hellos and link state

    private void ticker() {
        while (running) {
            enqueue(ByteBuffer.allocate(HEADER).putInt(self).putInt(0).put((byte) HELLO).put((byte) 0).put((byte) 0).put((byte) 1).array());
            java.util.List<Integer> gone = new java.util.ArrayList<>();
            long now = System.currentTimeMillis();
            synchronized (this) {
                Iterator<Map.Entry<Integer, Peer>> it = peers.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<Integer, Peer> e = it.next();
                    if (now - e.getValue().lastSeen > cfg.downMs) { if (e.getValue().up) gone.add(e.getKey()); it.remove(); }
                }
                Iterator<Assembly> ai = assemblies.values().iterator();
                while (ai.hasNext()) if (now - ai.next().started > 4 * cfg.helloMs + 30_000) ai.remove();
            }
            Handler h = handler;
            if (h != null) for (int n : gone) h.onLinkDown(n);
            try { Thread.sleep(cfg.helloMs); } catch (InterruptedException e) { return; }
        }
    }

    private void receive(byte[] f) {
        if (!running || f.length < HEADER) return;
        ByteBuffer b = ByteBuffer.wrap(f);
        int src = b.getInt(), dst = b.getInt();
        int type = b.get() & 0xFF, id = b.get() & 0xFF, idx = b.get() & 0xFF, total = b.get() & 0xFF;
        if (src == self || src <= 0 || (dst != 0 && dst != self)) return;
        boolean newlyUp = false;
        synchronized (this) {
            Peer p = peers.get(src);
            if (p == null) { p = new Peer(); peers.put(src, p); }
            p.lastSeen = System.currentTimeMillis();
            if (!p.up) { p.up = true; newlyUp = true; }
        }
        Handler h = handler;
        if (h == null) return;
        if (newlyUp) {
            h.onLinkUp(src);
            enqueue(ByteBuffer.allocate(HEADER).putInt(self).putInt(src).put((byte) HELLO).put((byte) 0).put((byte) 0).put((byte) 1).array());
        }
        if (type != DATA || total == 0 || idx >= total) return;
        byte[] part = new byte[b.remaining()];
        b.get(part);
        if (total == 1) { h.onRaw(src, part); return; }
        long key = ((long) src << 8) | id;
        byte[] whole = null;
        synchronized (this) {
            Assembly a = assemblies.get(key);
            if (a == null || a.parts.length != total) { a = new Assembly(total); assemblies.put(key, a); }
            if (a.parts[idx] == null) { a.parts[idx] = part; a.got++; }
            if (a.got == total) {
                assemblies.remove(key);
                int sum = 0;
                for (byte[] x : a.parts) sum += x.length;
                whole = new byte[sum];
                int pos = 0;
                for (byte[] x : a.parts) { System.arraycopy(x, 0, whole, pos, x.length); pos += x.length; }
            }
        }
        if (whole != null) h.onRaw(src, whole);
    }
}

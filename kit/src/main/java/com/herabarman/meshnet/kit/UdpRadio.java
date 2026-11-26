package com.herabarman.meshnet.kit;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A radio over UDP, so a desktop, a server or a Raspberry Pi can be a mesh node (same LAN, or across the internet
 * if you give it the other side's host:port). Pure Java, no Android.
 *
 * Datagram: magic(2) type(1) node(4) [DATA: msgId(2) index(1) total(1) payload]. HELLO every helloMs to the seed
 * addresses (and to the LAN broadcast address if enabled); a node is a neighbour from its first HELLO or DATA
 * until downMs of silence. Frames larger than one datagram (1200 bytes) are cut into up to 255 pieces and put
 * together again; a lost piece loses that frame (the layers above retry). Node ids are not authenticated by the
 * radio, like every radio here; end-to-end encryption is what protects the messages.
 */
public final class UdpRadio implements RawRadio {
    static final int MAGIC = 0x4D4E;
    static final int HELLO = 1, DATA = 2;
    static final int FRAG = 1200;

    private static final class Peer { InetSocketAddress addr; long lastSeen; boolean up; }
    private static final class Assembly { final byte[][] parts; int got; final long started = System.currentTimeMillis(); Assembly(int n) { parts = new byte[n][]; } }

    private final int self;
    private final int port;
    private final boolean lanBroadcast;
    private final long helloMs, downMs;
    private final List<InetSocketAddress> seeds = new ArrayList<>();
    private final Map<Integer, Peer> peers = new HashMap<>();
    private final Map<Long, Assembly> assemblies = new HashMap<>();
    private volatile Handler handler;
    private volatile boolean running;
    private DatagramSocket socket;
    private int msgId;

    public UdpRadio(int selfNode, int port, boolean lanBroadcast, long helloMs, long downMs) {
        this.self = selfNode;
        this.port = port;
        this.lanBroadcast = lanBroadcast;
        this.helloMs = helloMs;
        this.downMs = downMs;
    }

    public UdpRadio(int selfNode, int port, boolean lanBroadcast) { this(selfNode, port, lanBroadcast, 2000, 8000); }

    /** A neighbour to say HELLO to (host and its UDP port). */
    public synchronized void addPeer(InetSocketAddress a) { seeds.add(a); }

    @Override public void setHandler(Handler h) { this.handler = h; }

    @Override
    public synchronized void start() {
        if (running) return;
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setBroadcast(lanBroadcast);
            socket.bind(new InetSocketAddress(port));
        } catch (SocketException e) {
            throw new IllegalStateException("cannot open UDP port " + port, e);
        }
        running = true;
        Thread rx = new Thread(new Runnable() { @Override public void run() { receiveLoop(); } }, "udp-radio-rx");
        rx.setDaemon(true);
        rx.start();
        Thread tk = new Thread(new Runnable() { @Override public void run() { tickLoop(); } }, "udp-radio-tick");
        tk.setDaemon(true);
        tk.start();
    }

    @Override
    public void stop() {
        List<Integer> was = new ArrayList<>();
        synchronized (this) {
            running = false;
            if (socket != null) socket.close();
            for (Map.Entry<Integer, Peer> e : peers.entrySet()) if (e.getValue().up) was.add(e.getKey());
            peers.clear();
        }
        Handler h = handler;
        if (h != null) for (int n : was) h.onLinkDown(n);
    }

    @Override
    public void sendRaw(int peerNode, byte[] data) {
        InetSocketAddress to;
        int id;
        synchronized (this) {
            Peer p = peers.get(peerNode);
            if (p == null || !p.up || socket == null) return;
            to = p.addr;
            id = ++msgId & 0xFFFF;
        }
        int total = Math.max(1, (data.length + FRAG - 1) / FRAG);
        if (total > 255) return;
        for (int i = 0; i < total; i++) {
            int off = i * FRAG, len = Math.min(FRAG, data.length - off);
            ByteBuffer b = ByteBuffer.allocate(7 + 4 + len).putShort((short) MAGIC).put((byte) DATA).putInt(self)
                    .putShort((short) id).put((byte) i).put((byte) total).put(data, off, len);
            send(b.array(), to);
        }
    }

    private void send(byte[] d, InetSocketAddress to) {
        try {
            DatagramSocket s = socket;
            if (s != null && !s.isClosed()) s.send(new DatagramPacket(d, d.length, to));
        } catch (IOException ignored) { }
    }

    private void receiveLoop() {
        byte[] buf = new byte[2048];
        while (running) {
            try {
                DatagramPacket pk = new DatagramPacket(buf, buf.length);
                socket.receive(pk);
                handle(pk);
            } catch (IOException e) {
                if (!running) return;
            } catch (RuntimeException ignored) { }
        }
    }

    private void handle(DatagramPacket pk) {
        int len = pk.getLength();
        if (len < 7) return;
        ByteBuffer b = ByteBuffer.wrap(pk.getData(), 0, len);
        if ((b.getShort() & 0xFFFF) != MAGIC) return;
        int type = b.get() & 0xFF;
        int node = b.getInt();
        if (node == self || node <= 0) return;
        InetSocketAddress from = new InetSocketAddress(pk.getAddress(), pk.getPort());
        boolean newlyUp = false;
        synchronized (this) {
            Peer p = peers.get(node);
            if (p == null) { p = new Peer(); peers.put(node, p); }
            p.addr = from;
            p.lastSeen = System.currentTimeMillis();
            if (!p.up) { p.up = true; newlyUp = true; }
        }
        Handler h = handler;
        if (newlyUp && h != null) {
            h.onLinkUp(node);
            if (type == HELLO) send(hello(), from);                  // answer a first hello at once so both sides come up quickly
        }
        if (type != DATA || len < 11 || h == null) return;
        int id = b.getShort() & 0xFFFF, idx = b.get() & 0xFF, total = b.get() & 0xFF;
        if (total == 0 || idx >= total) return;
        byte[] part = new byte[b.remaining()];
        b.get(part);
        if (total == 1) { h.onRaw(node, part); return; }
        long key = ((long) node << 16) | id;
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
        if (whole != null) h.onRaw(node, whole);
    }

    private byte[] hello() { return ByteBuffer.allocate(7).putShort((short) MAGIC).put((byte) HELLO).putInt(self).array(); }

    private void tickLoop() {
        while (running) {
            byte[] hi = hello();
            List<InetSocketAddress> targets;
            List<Integer> gone = new ArrayList<>();
            long now = System.currentTimeMillis();
            synchronized (this) {
                targets = new ArrayList<>(seeds);
                java.util.Iterator<Map.Entry<Integer, Peer>> it = peers.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<Integer, Peer> e = it.next();
                    if (now - e.getValue().lastSeen > downMs) { if (e.getValue().up) gone.add(e.getKey()); it.remove(); }
                }
                java.util.Iterator<Assembly> ai = assemblies.values().iterator();
                while (ai.hasNext()) if (now - ai.next().started > 5000) ai.remove();
            }
            for (InetSocketAddress a : targets) send(hi, a);
            if (lanBroadcast) {
                try { send(hi, new InetSocketAddress(InetAddress.getByName("255.255.255.255"), port)); } catch (IOException ignored) { }
            }
            Handler h = handler;
            if (h != null) for (int n : gone) h.onLinkDown(n);
            try { Thread.sleep(helloMs); } catch (InterruptedException e) { return; }
        }
    }
}

package com.herabarman.meshnet.kit;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A radio over TCP: for links that give you an IP connection but no datagrams, such as a Wi-Fi Direct group, a hotspot, USB tethering
 * or Ethernet. Each phone listens on a port and also connects out to the addresses it is told about ({@link #connect}); a short handshake
 * exchanges node ids; messages travel as length-prefixed frames; an empty frame every pingMs keeps the link measured, and a link with no
 * traffic for deadMs is closed and reported down. A lost connection to an address given to connect() is retried.
 * Two phones that connect to each other at the same time end up with one link (the newer connection replaces the older one).
 */
public final class TcpRadio implements RawRadio {
    static final int MAGIC = 0x4D4E5443;
    static final int MAX_FRAME = 256 * 1024;

    private static final class Conn {
        final Socket socket;
        final DataOutputStream out;
        final int node;
        final boolean outgoing;
        volatile long lastRx = System.currentTimeMillis();
        Conn(Socket s, DataOutputStream o, int n, boolean outgoing) { socket = s; out = o; node = n; this.outgoing = outgoing; }
    }

    private final int self;
    private final int port;
    private final long pingMs, deadMs, retryMs;
    private final Map<Integer, Conn> conns = new HashMap<>();
    private final List<InetSocketAddress> targets = new ArrayList<>();
    private final Map<InetSocketAddress, Long> lastTry = new HashMap<>();
    private final Map<InetSocketAddress, Boolean> connecting = new HashMap<>();
    private final Map<InetSocketAddress, Integer> targetNode = new HashMap<>();
    private volatile Handler handler;
    private volatile boolean running;
    private ServerSocket server;

    public TcpRadio(int selfNode, int port, long pingMs, long deadMs, long retryMs) { this.self = selfNode; this.port = port; this.pingMs = pingMs; this.deadMs = deadMs; this.retryMs = retryMs; }

    public TcpRadio(int selfNode, int port) { this(selfNode, port, 3000, 10_000, 2000); }

    /** The port actually listened on (useful with port 0). */
    public synchronized int port() { return server == null ? port : server.getLocalPort(); }

    /** Keep a connection to this address (for example the Wi-Fi Direct group owner). */
    public synchronized void connect(InetSocketAddress a) { if (!targets.contains(a)) targets.add(a); }

    public synchronized void forget(InetSocketAddress a) { targets.remove(a); }

    /** The IP address a connected neighbour is reachable at (null if there is no live TCP link to it): lets TcpBulk follow this radio's links. */
    public synchronized java.net.InetAddress addressOf(int node) {
        Conn c = conns.get(node);
        return c == null || c.socket.isClosed() ? null : c.socket.getInetAddress();
    }

    @Override public void setHandler(Handler h) { this.handler = h; }

    @Override public synchronized void start() {
        if (running) return;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(port));
        } catch (IOException e) { throw new IllegalStateException("cannot listen on TCP port " + port, e); }
        running = true;
        daemon("tcp-radio-accept", new Runnable() { @Override public void run() { acceptLoop(); } });
        daemon("tcp-radio-tick", new Runnable() { @Override public void run() { tickLoop(); } });
    }

    @Override public void stop() {
        List<Conn> all;
        synchronized (this) {
            running = false;
            try { if (server != null) server.close(); } catch (IOException ignored) { }
            all = new ArrayList<>(conns.values());
        }
        for (Conn c : all) close(c, true);
    }

    @Override public void drop(int peerNode) {
        Conn c;
        synchronized (this) { c = conns.get(peerNode); }
        if (c != null) close(c, true);
    }

    @Override public void sendRaw(int peerNode, byte[] data) {
        Conn c;
        synchronized (this) { c = conns.get(peerNode); }
        if (c == null || data.length > MAX_FRAME) return;
        write(c, data);
    }

    private void write(Conn c, byte[] data) {
        try {
            synchronized (c.out) { c.out.writeInt(data.length); c.out.write(data); c.out.flush(); }
        } catch (IOException e) { close(c, true); }
    }

    private static void daemon(String name, Runnable r) { Thread t = new Thread(r, name); t.setDaemon(true); t.start(); }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = server.accept();
                daemon("tcp-radio-in", new Runnable() { @Override public void run() { session(s, null); } });
            } catch (IOException e) { if (!running) return; }
        }
    }

    private void tickLoop() {
        long lastPing = 0;
        while (running) {
            try { Thread.sleep(250); } catch (InterruptedException e) { return; }
            long now = System.currentTimeMillis();
            List<InetSocketAddress> dial = new ArrayList<>();
            List<Conn> dead = new ArrayList<>(), all;
            synchronized (this) {
                all = new ArrayList<>(conns.values());
                for (InetSocketAddress a : targets) {
                    Long t = lastTry.get(a);
                    Integer known = targetNode.get(a);
                    if (connecting.containsKey(a) || (known != null && conns.containsKey(known)) || (t != null && now - t < retryMs)) continue;
                    lastTry.put(a, now);
                    connecting.put(a, true);
                    dial.add(a);
                }
            }
            for (Conn c : all) if (now - c.lastRx > deadMs) dead.add(c);
            for (Conn c : dead) close(c, true);
            if (now - lastPing >= pingMs) { lastPing = now; for (Conn c : all) write(c, new byte[0]); }
            for (final InetSocketAddress a : dial) {
                daemon("tcp-radio-out", new Runnable() { @Override public void run() {
                    Socket s = null;
                    try {
                        s = new Socket();
                        s.connect(a, 3000);
                    } catch (IOException e) { try { if (s != null) s.close(); } catch (IOException ignored) { } s = null; }
                    if (s == null) { synchronized (TcpRadio.this) { connecting.remove(a); } return; }
                    try { session(s, a); } finally { synchronized (TcpRadio.this) { connecting.remove(a); } }
                } });
            }
        }
    }

    /** Handshake, then read frames until the connection ends. */
    private void session(Socket s, InetSocketAddress target) {
        Conn c = null;
        try {
            s.setTcpNoDelay(true);
            s.setSoTimeout((int) Math.max(1000, deadMs));
            DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(s.getOutputStream()));
            DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(s.getInputStream()));
            out.writeInt(MAGIC); out.writeInt(self); out.flush();
            if (in.readInt() != MAGIC) { s.close(); return; }
            int node = in.readInt();
            if (node <= 0 || node == self) { s.close(); return; }
            c = new Conn(s, out, node, target != null);
            Conn old;
            synchronized (this) {
                if (!running) { s.close(); return; }
                if (target != null) targetNode.put(target, node);
                old = conns.get(node);
                if (old != null) {
                    // two connections between the same phones: keep the one opened by the lower node id; if both were opened by the
                    // same side (a reconnect), the newer one wins
                    int oldInit = old.outgoing ? self : node, newInit = c.outgoing ? self : node;
                    if (oldInit != newInit && oldInit < newInit) { s.close(); return; }
                }
                conns.put(node, c);
            }
            if (old != null) close(old, false);                    // the upper layer sees no gap
            else { Handler h = handler; if (h != null) h.onLinkUp(node); }
            while (running) {
                int len = in.readInt();
                if (len < 0 || len > MAX_FRAME) break;
                byte[] d = new byte[len];
                in.readFully(d);
                c.lastRx = System.currentTimeMillis();
                if (len == 0) continue;
                Handler h = handler;
                if (h != null) h.onRaw(node, d);
            }
        } catch (IOException ignored) {
        } finally {
            if (c != null) close(c, true); else try { s.close(); } catch (IOException ignored) { }
        }
    }

    private void close(Conn c, boolean report) {
        boolean wasCurrent;
        synchronized (this) {
            wasCurrent = conns.get(c.node) == c;
            if (wasCurrent && report) conns.remove(c.node);
        }
        try { c.socket.close(); } catch (IOException ignored) { }
        if (wasCurrent && report) { Handler h = handler; if (h != null) h.onLinkDown(c.node); }
    }
}

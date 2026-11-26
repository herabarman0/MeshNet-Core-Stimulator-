package com.herabarman.meshnet.kit;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * {@link BulkLink} over plain TCP: one short-lived connection per transfer, so it needs no state to survive link changes.
 * Works wherever two phones have an IP path: a Wi-Fi Direct group, a phone hotspot, a LAN, USB tethering, a desktop node.
 * The platform code that learns a neighbour's address (WifiDirectConnector, a hotspot, mDNS, a QR code...) calls
 * {@link #setPeer}; remove it with {@link #removePeer} when the path is gone.
 * Frame: magic(4) | senderNode(4) | length(4) | bytes. Hard limit {@link #MAX_BYTES} so a hostile peer cannot make us allocate without bound.
 */
public final class TcpBulk implements BulkLink {
    static final int MAGIC = 0x4D4E4255;
    public static final int MAX_BYTES = 24 * 1024 * 1024;

    private final int self;
    private final int port;
    private final int connectMs, ioMs;
    private final Map<Integer, InetSocketAddress> peers = new ConcurrentHashMap<>();
    private volatile TcpRadio via;
    private volatile int viaPort;
    private volatile Receiver receiver;
    private volatile boolean running;
    private ServerSocket server;
    private ExecutorService pool;

    public TcpBulk(int selfNode, int port, int connectTimeoutMs, int ioTimeoutMs) {
        this.self = selfNode; this.port = port; this.connectMs = connectTimeoutMs; this.ioMs = ioTimeoutMs;
    }

    public TcpBulk(int selfNode, int port) { this(selfNode, port, 3000, 20_000); }

    public synchronized int port() { return server == null ? port : server.getLocalPort(); }

    /**
     * Follow a TcpRadio's links instead of being told addresses: every neighbour that has a live TCP link on that radio can also be
     * reached for bulk at the same IP address, on this TcpBulk's port (viaPort = the port every phone runs TcpBulk on).
     */
    public TcpBulk follow(TcpRadio radio, int bulkPort) { this.via = radio; this.viaPort = bulkPort; return this; }

    private InetSocketAddress addr(int node) {
        InetSocketAddress a = peers.get(node);
        if (a != null) return a;
        TcpRadio r = via;
        java.net.InetAddress ip = r == null ? null : r.addressOf(node);
        return ip == null ? null : new InetSocketAddress(ip, viaPort);
    }

    public void setPeer(int node, InetSocketAddress addr) { peers.put(node, addr); }
    public void removePeer(int node) { peers.remove(node); }

    @Override public boolean canSend(int node) { return running && addr(node) != null; }
    @Override public void setReceiver(Receiver r) { this.receiver = r; }

    @Override public synchronized void start() {
        if (running) return;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(port));
        } catch (IOException e) { throw new IllegalStateException("TcpBulk cannot listen on " + port, e); }
        running = true;
        pool = Executors.newCachedThreadPool(new ThreadFactory() {
            @Override public Thread newThread(Runnable r) { Thread t = new Thread(r, "tcp-bulk"); t.setDaemon(true); return t; }
        });
        final ServerSocket ss = server;
        pool.execute(new Runnable() { @Override public void run() { accept(ss); } });
    }

    @Override public synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        server = null;
        if (pool != null) pool.shutdownNow();
    }

    private void accept(ServerSocket ss) {
        while (running) {
            try {
                final Socket s = ss.accept();
                pool.execute(new Runnable() { @Override public void run() { receive(s); } });
            } catch (IOException e) { if (!running) return; }
            catch (RuntimeException e) { return; }
        }
    }

    private void receive(Socket s) {
        try {
            s.setSoTimeout(ioMs);
            DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(s.getInputStream(), 64 * 1024));
            if (in.readInt() != MAGIC) return;
            int from = in.readInt();
            int len = in.readInt();
            if (len <= 0 || len > MAX_BYTES) return;
            byte[] data = new byte[len];
            in.readFully(data);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            out.writeByte(1);                                   // the sender waits for this: "I have all of it"
            out.flush();
            Receiver r = receiver;
            if (r != null) r.onReceived(from, data);
        } catch (IOException | RuntimeException ignored) {
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    @Override public void send(final int node, final byte[] data, final Done cb) {
        final InetSocketAddress a = addr(node);
        if (!running || a == null || data.length == 0 || data.length > MAX_BYTES) { cb.onDone(false); return; }
        try {
            pool.execute(new Runnable() {
                @Override public void run() {
                    boolean ok = false;
                    try (Socket s = new Socket()) {
                        s.connect(a, connectMs);
                        s.setSoTimeout(ioMs);
                        s.setTcpNoDelay(true);
                        DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(s.getOutputStream(), 64 * 1024));
                        out.writeInt(MAGIC); out.writeInt(self); out.writeInt(data.length);
                        out.write(data);
                        out.flush();
                        ok = s.getInputStream().read() == 1;
                    } catch (IOException | RuntimeException ignored) { }
                    cb.onDone(ok);
                }
            });
        } catch (RuntimeException rejected) { cb.onDone(false); }
    }
}

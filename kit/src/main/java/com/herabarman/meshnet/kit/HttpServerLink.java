package com.herabarman.meshnet.kit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * ServerLink over plain HTTP (HttpURLConnection, works on Android 5+, no library) against server/mesh-bridge.js.
 * One long-poll connection receives packets and doubles as the "I am online" signal; sends are binary POSTs.
 */
public final class HttpServerLink implements ServerLink {
    /** Supplies the X-Mesh-Token value (or null). A plain interface, so it works below Android 7 without desugaring. */
    public interface TokenSource { String token(); }

    private static final long ONLINE_MS = 45_000;

    private final String base;
    private final TokenSource token;
    private final Map<Integer, long[]> seen = new ConcurrentHashMap<>();   // node -> {lastSeenOnline, zone, onlineFlag}
    private final ExecutorService sender = Executors.newSingleThreadExecutor(daemon("mesh-http-send"));
    private volatile boolean running;
    private volatile long lastOk;
    private volatile int self = -1;
    private volatile int zone = -1;
    private volatile Thread poller;
    private Inbound inbound;

    /** @param baseUrl e.g. "https://meshnet-i35o.onrender.com/api/mesh"  @param token may be null */
    public HttpServerLink(String baseUrl, TokenSource token) {
        this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
    }

    private static ThreadFactory daemon(final String name) {
        return new ThreadFactory() {
            @Override public Thread newThread(Runnable r) { Thread t = new Thread(r, name); t.setDaemon(true); return t; }
        };
    }

    @Override
    public void start(int selfNode, Inbound in) {
        self = selfNode;
        inbound = in;
        running = true;
        Thread t = new Thread(new Runnable() { @Override public void run() { pollLoop(); } }, "mesh-http-poll");
        t.setDaemon(true);
        poller = t;
        t.start();
    }

    @Override
    public void stop() {
        running = false;
        Thread t = poller;
        if (t != null) t.interrupt();
        sender.shutdownNow();
    }

    @Override public boolean available() { return running && System.currentTimeMillis() - lastOk < ONLINE_MS; }

    @Override
    public boolean isOnline(int node) {
        long[] e = seen.get(node);
        return available() && e != null && e[2] == 1 && System.currentTimeMillis() - e[0] < ONLINE_MS;
    }

    @Override
    public int zoneOf(int node) {
        long[] e = seen.get(node);
        return e == null ? -1 : (int) e[1];
    }

    @Override
    public void send(final int node, final byte[] packet) {
        sender.execute(new Runnable() {
            @Override public void run() {
                try {
                    int code = request("POST", "/send?to=" + node + "&from=" + self, packet, 8_000, null);
                    if (code == 200) lastOk = System.currentTimeMillis();
                    else if (code == 404) seen.put(node, new long[]{System.currentTimeMillis(), zoneOf(node), 0});
                } catch (IOException ignored) { }
            }
        });
    }

    @Override
    public void publishZone(final int z) {
        zone = z;
        if (!running) return;
        sender.execute(new Runnable() {
            @Override public void run() {
                try { request("POST", "/presence?node=" + self + "&zone=" + z, new byte[0], 8_000, null); } catch (IOException ignored) { }
            }
        });
    }

    @Override
    public void refresh(int... nodes) {
        if (nodes.length == 0) return;
        StringBuilder ids = new StringBuilder();
        for (int n : nodes) { if (ids.length() > 0) ids.append(','); ids.append(n); }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int code = request("GET", "/where?ids=" + ids, null, 4_000, out);
            if (code != 200) return;
            lastOk = System.currentTimeMillis();
            for (String line : new String(out.toByteArray(), StandardCharsets.US_ASCII).split("\n")) {
                String[] p = line.trim().split(" ");
                if (p.length != 3) continue;
                int node = Integer.parseInt(p[0]);
                seen.put(node, new long[]{System.currentTimeMillis(), Integer.parseInt(p[2]), Integer.parseInt(p[1])});
            }
        } catch (IOException | NumberFormatException ignored) { }
    }

    private void pollLoop() {
        boolean first = true;   // the first poll returns at once, so available() is true within a moment of start()
        while (running) {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int code = request("GET", "/inbox/" + self + "?wait=" + (first ? 0 : 20) + "&zone=" + zone, null, 35_000, out);
                first = false;
                if (code == 200) {
                    lastOk = System.currentTimeMillis();
                    deliver(out.toByteArray());
                } else {
                    sleep(3_000);
                }
            } catch (IOException e) {
                sleep(3_000);
            }
        }
    }

    private void deliver(byte[] body) {
        int i = 0;
        Inbound in = inbound;
        while (i + 2 <= body.length && in != null) {
            int len = ((body[i] & 0xFF) << 8) | (body[i + 1] & 0xFF);
            i += 2;
            if (i + len > body.length) return;
            byte[] p = new byte[len];
            System.arraycopy(body, i, p, 0, len);
            i += len;
            in.onPacket(p);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private int request(String method, String path, byte[] body, int readTimeoutMs, ByteArrayOutputStream into) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(8_000);
            c.setReadTimeout(readTimeoutMs);
            String t = token == null ? null : token.token();
            if (t != null && !t.isEmpty()) c.setRequestProperty("X-Mesh-Token", t);
            if (body != null) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length);
                c.setRequestProperty("Content-Type", "application/octet-stream");
                OutputStream os = c.getOutputStream();
                os.write(body);
                os.close();
            }
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (is != null) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) if (into != null) into.write(buf, 0, n);
                is.close();
            }
            return code;
        } finally {
            c.disconnect();
        }
    }
}

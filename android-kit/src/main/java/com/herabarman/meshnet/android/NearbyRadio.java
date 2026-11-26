package com.herabarman.meshnet.android;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Looper;
import android.util.Log;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.herabarman.meshnet.kit.AdaptiveDuty;
import com.herabarman.meshnet.kit.DutyPolicy;
import com.herabarman.meshnet.kit.RawRadio;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Google Nearby Connections as the mesh's radio. A port of the app's MeshManager with the same radio rules
 * (P2P_CLUSTER, connection cap, tie-break so two phones do not request each other at the same time,
 * backoff retries, ping + dead-link detection, battery-saver scan cycle), but it moves bytes only:
 * no JSON, no app ids. The advertised name is just my node id in base 36, so the neighbour's id is known
 * before the connection is even made.
 *
 * Simplification compared with the old ConnectionManager: it no longer asks "is this newcomer already inside
 * my mesh?" when full (the old graph code is gone). At the cap it accepts up to HARD_MAX connections, and an
 * Admission hook lets the app decide better if it wants to.
 *
 * Needs the usual runtime permissions (Nearby, Bluetooth, Wi-Fi, location) granted by the app before start().
 */
public final class NearbyRadio implements RawRadio, com.herabarman.meshnet.kit.BulkLink {
    private static final String TAG = "NearbyRadio";
    private static final String SERVICE_ID = "com.herabarman.meshnet";
    private static final Strategy STRATEGY = Strategy.P2P_CLUSTER;
    private static final int MAX_BYTES = 32 * 1024;     // Nearby limit for a bytes payload

    private static final long RETRY_MS = 5000;
    private static final long WAIT_MS = 8000;
    private static final int MAX_CONNECTIONS = 6;
    private static final int HARD_MAX = 7;
    private static final long PING_MS = 10000;
    private static final long DEAD_MS = 35000;
    private static final long SCAN_ON_MS = 30000;
    private static final long SCAN_OFF_MS = 30000;
    private static final long DROP_COOLDOWN_MS = 60000;
    private static final String PREFS = "meshnet_prefs";
    private static final String KEY_BATT = "battery_saver";
    private static final byte[] PING = new byte[]{0};

    /** Optional: tell the radio that a newcomer is already reachable through the mesh (so a full radio can skip it). */
    public interface Admission {
        boolean alreadyReachable(int node);
    }

    private final Context ctx;
    // ---- bulk (Nearby FILE payloads): big payloads to a direct neighbour; Nearby moves them over Wi-Fi / hotspot when it can
    private static final long MAX_BULK = 24L * 1024 * 1024;
    private volatile com.herabarman.meshnet.kit.BulkLink.Receiver bulkRx;
    private final Map<Long, com.herabarman.meshnet.kit.BulkLink.Done> bulkOut = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Long, java.io.File> bulkFiles = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Long, Payload> bulkIn = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Long, Integer> bulkInNode = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService bulkIo = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final ConnectionsClient nearby;
    private final int selfNode;
    private final String myName;
    private final android.os.Handler handler = new android.os.Handler(Looper.getMainLooper());
    private volatile Admission admission;
    private volatile RawRadio.Handler rawHandler;

    private boolean running;
    private boolean scanning;
    /** Backs the scan interval off in an empty neighbourhood, resets on traffic, a new neighbour or movement. */
    private final AdaptiveDuty duty = new AdaptiveDuty();
    private volatile double speedMps;

    /** Current speed in m/s (from GPS): a moving phone meets new neighbours, so it rests less between scans. */
    public void setSpeed(double mps) { this.speedMps = mps; }

    private final Map<String, Integer> discovered = new HashMap<>();      // endpointId -> node
    private final Map<String, Integer> connected = new HashMap<>();
    private final Map<String, Integer> pending = new HashMap<>();
    private final Map<Integer, String> endpointOf = new HashMap<>();      // node -> endpointId (connected)
    private final Map<String, Integer> attempts = new HashMap<>();
    private final Map<String, Long> lastRx = new HashMap<>();

    public NearbyRadio(Context ctx, int selfNode) {
        this.ctx = ctx.getApplicationContext();
        this.selfNode = selfNode;
        this.myName = Integer.toString(selfNode, 36);
        this.nearby = Nearby.getConnectionsClient(this.ctx);
    }

    public void setAdmission(Admission a) { this.admission = a; }

    @Override
    public void setHandler(RawRadio.Handler h) { this.rawHandler = h; }

    // ---- RawRadio -----------------------------------------------------------------------------

    @Override
    public void start() {
        if (running) return;
        running = true;
        startAdvertising();
        startDiscovery();
        scanning = true;
        handler.removeCallbacks(pingTick);
        handler.removeCallbacks(dutyTick);
        handler.postDelayed(pingTick, PING_MS);
        handler.postDelayed(dutyTick, SCAN_ON_MS);
    }

    @Override
    public void stop() {
        running = false;
        scanning = false;
        handler.removeCallbacksAndMessages(null);
        try { nearby.stopAdvertising(); } catch (Exception ignored) { }
        try { nearby.stopDiscovery(); } catch (Exception ignored) { }
        try { nearby.stopAllEndpoints(); } catch (Exception ignored) { }
        discovered.clear();
        connected.clear();
        pending.clear();
        endpointOf.clear();
        attempts.clear();
        lastRx.clear();
    }

    @Override
    public void sendRaw(int peerNode, byte[] data) {
        if (data.length > MAX_BYTES) return;
        String ep = endpointOf.get(peerNode);
        if (ep == null) return;
        duty.onTraffic(System.currentTimeMillis());
        nearby.sendPayload(ep, Payload.fromBytes(data)).addOnFailureListener(new OnFailureListener() {
            @Override public void onFailure(Exception e) { Log.w(TAG, "send failed: " + e.getMessage()); }
        });
    }

    private final Map<Integer, Long> cooldown = new HashMap<>();      // node -> do not reconnect before

    /** Disconnect a neighbour that floods or has a bad link; no reconnect attempt for a minute. */
    @Override
    public void drop(final int peerNode) {
        handler.post(new Runnable() {
            @Override public void run() {
                cooldown.put(peerNode, System.currentTimeMillis() + DROP_COOLDOWN_MS);
                String ep = endpointOf.get(peerNode);
                if (ep == null) return;
                try { nearby.disconnectFromEndpoint(ep); } catch (Exception ignored) { }
                linkDown(ep);
            }
        });
    }

    // ---- advertising / discovery --------------------------------------------------------------

    private int statusCode(Exception e) {
        return (e instanceof ApiException) ? ((ApiException) e).getStatusCode() : -1;
    }

    private boolean batterySaver() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BATT, false);
    }

    private void startAdvertising() {
        AdvertisingOptions options = new AdvertisingOptions.Builder().setStrategy(STRATEGY).build();
        nearby.startAdvertising(myName, SERVICE_ID, lifecycle, options)
                .addOnFailureListener(new OnFailureListener() {
                    @Override public void onFailure(Exception e) {
                        if (statusCode(e) == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING) return;
                        Log.e(TAG, "advertising failed: " + e.getMessage());
                    }
                });
    }

    private void startDiscovery() {
        DiscoveryOptions options = new DiscoveryOptions.Builder().setStrategy(STRATEGY).build();
        nearby.startDiscovery(SERVICE_ID, discoveryCallback, options)
                .addOnFailureListener(new OnFailureListener() {
                    @Override public void onFailure(Exception e) {
                        if (statusCode(e) == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) return;
                        Log.e(TAG, "discovery failed: " + e.getMessage());
                    }
                });
    }

    private void pauseDiscovery() {
        try { nearby.stopDiscovery(); } catch (Exception ignored) { }
        scanning = false;
        Iterator<String> it = discovered.keySet().iterator();
        while (it.hasNext()) {
            String ep = it.next();
            if (!connected.containsKey(ep) && !pending.containsKey(ep)) it.remove();
        }
    }

    private void resumeDiscovery() {
        if (!running) return;
        startDiscovery();
        scanning = true;
    }

    private void wakeScan() {
        if (!running || scanning) return;
        handler.removeCallbacks(dutyTick);
        resumeDiscovery();
        handler.postDelayed(dutyTick, SCAN_ON_MS);
    }

    /** Battery level 0..1 and whether the phone is plugged in (sticky broadcast, no receiver needed). */
    private double[] battery() {
        try {
            Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return new double[]{1.0, 0};
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            return new double[]{level < 0 ? 1.0 : level / (double) scale, plugged != 0 ? 1 : 0};
        } catch (RuntimeException e) {
            return new double[]{1.0, 0};
        }
    }

    /**
     * Adaptive duty cycle: how long to scan and rest depends on battery, charging, how many neighbours I already
     * have and the user's battery-saver switch (see DutyPolicy). Re-evaluated at the end of every phase.
     */
    private final Runnable dutyTick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            double[] b = battery();
            if (scanning) duty.onRoundEnd(connected.size());          // a scan round just ended
            DutyPolicy.Duty d = duty.next(b[0], b[1] != 0, connected.size(), batterySaver(), speedMps, System.currentTimeMillis());
            if (d.offMs == 0) {
                if (!scanning) resumeDiscovery();
                handler.postDelayed(this, d.onMs);
            } else if (scanning) {
                pauseDiscovery();
                handler.postDelayed(this, d.offMs);
            } else {
                resumeDiscovery();
                handler.postDelayed(this, d.onMs);
            }
        }
    };

    private final Runnable pingTick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = System.currentTimeMillis();
            for (String ep : new HashMap<>(connected).keySet()) {
                Long t = lastRx.get(ep);
                if (t != null && now - t > DEAD_MS) {
                    Log.w(TAG, "no answer, dropping " + ep);
                    try { nearby.disconnectFromEndpoint(ep); } catch (Exception ignored) { }
                    linkDown(ep);
                } else {
                    nearby.sendPayload(ep, Payload.fromBytes(PING));
                }
            }
            handler.postDelayed(this, PING_MS);
        }
    };

    private static int parseNode(String name) {
        try {
            int n = Integer.parseInt(name, 36);
            return n > 0 ? n : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private final EndpointDiscoveryCallback discoveryCallback = new EndpointDiscoveryCallback() {
        @Override public void onEndpointFound(String endpointId, DiscoveredEndpointInfo info) {
            int node = parseNode(info.getEndpointName());
            if (node < 0 || node == selfNode) return;
            discovered.put(endpointId, node);
            attempts.remove(endpointId);
            scheduleConnect(endpointId, node);
        }

        @Override public void onEndpointLost(String endpointId) {
            discovered.remove(endpointId);
            pending.remove(endpointId);
        }
    };

    // ---- connections --------------------------------------------------------------------------

    private int load(String except) {
        int n = connected.size();
        for (String k : pending.keySet()) if (!k.equals(except) && !connected.containsKey(k)) n++;
        return n;
    }

    private boolean hasRoom(String endpointId, int node) {
        if (load(endpointId) < MAX_CONNECTIONS) return true;
        Admission a = admission;
        if (a != null && a.alreadyReachable(node)) return false;
        return load(endpointId) < HARD_MAX;
    }

    private void scheduleConnect(final String endpointId, final int node) {
        if (!running) return;
        Long until = cooldown.get(node);
        if (until != null && System.currentTimeMillis() < until) {
            handler.postDelayed(new Runnable() {
                @Override public void run() { if (discovered.containsKey(endpointId)) scheduleConnect(endpointId, node); }
            }, until - System.currentTimeMillis() + 500);
            return;
        }
        boolean iGoFirst = myName.compareTo(Integer.toString(node, 36)) > 0;
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (running && discovered.containsKey(endpointId)) requestConnection(endpointId, node);
            }
        }, iGoFirst ? 300 : WAIT_MS);
    }

    private void requestConnection(final String endpointId, final int node) {
        if (!running || connected.containsKey(endpointId) || pending.containsKey(endpointId)) return;
        if (!hasRoom(endpointId, node)) return;
        pending.put(endpointId, node);
        nearby.requestConnection(myName, endpointId, lifecycle).addOnFailureListener(new OnFailureListener() {
            @Override public void onFailure(Exception e) {
                pending.remove(endpointId);
                if (statusCode(e) == ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT) return;
                retryLater(endpointId);
            }
        });
    }

    private void retryLater(final String endpointId) {
        if (!running) return;
        int n = attempts.containsKey(endpointId) ? attempts.get(endpointId) : 0;
        attempts.put(endpointId, n + 1);
        final Integer node = discovered.get(endpointId);
        if (node == null) return;
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (running && discovered.containsKey(endpointId)) requestConnection(endpointId, node);
            }
        }, Math.min(RETRY_MS << Math.min(n, 4), 60000L));
    }

    private void linkDown(String endpointId) {
        Integer node = connected.remove(endpointId);
        pending.remove(endpointId);
        lastRx.remove(endpointId);
        if (node != null) {
            if (endpointId.equals(endpointOf.get(node))) {
                endpointOf.remove(node);
                RawRadio.Handler h = rawHandler;
                if (h != null) h.onLinkDown(node);
            }
            wakeScan();
        }
        Integer again = discovered.get(endpointId);
        if (again != null) {
            attempts.remove(endpointId);
            scheduleConnect(endpointId, again);
        }
        for (Map.Entry<String, Integer> e : new HashMap<>(discovered).entrySet()) {
            String ep = e.getKey();
            if (!ep.equals(endpointId) && !connected.containsKey(ep) && !pending.containsKey(ep)) scheduleConnect(ep, e.getValue());
        }
    }

    private final ConnectionLifecycleCallback lifecycle = new ConnectionLifecycleCallback() {
        @Override public void onConnectionInitiated(String endpointId, ConnectionInfo info) {
            int node = parseNode(info.getEndpointName());
            if (node < 0 || !hasRoom(endpointId, node)) {
                pending.remove(endpointId);
                try { nearby.rejectConnection(endpointId); } catch (Exception ignored) { }
                return;
            }
            pending.put(endpointId, node);
            nearby.acceptConnection(endpointId, payloadCallback);
        }

        @Override public void onConnectionResult(String endpointId, ConnectionResolution result) {
            if (result.getStatus().isSuccess()) {
                Integer node = pending.remove(endpointId);
                if (node == null) return;
                attempts.remove(endpointId);
                String old = endpointOf.put(node, endpointId);
                if (old != null && !old.equals(endpointId)) {           // same phone reconnected: drop the stale link
                    connected.remove(old);
                    lastRx.remove(old);
                    try { nearby.disconnectFromEndpoint(old); } catch (Exception ignored) { }
                }
                connected.put(endpointId, node);
                lastRx.put(endpointId, System.currentTimeMillis());
                duty.onPeerUp();
                RawRadio.Handler h = rawHandler;
                if (h != null) h.onLinkUp(node);
            } else {
                pending.remove(endpointId);
                retryLater(endpointId);
            }
        }

        @Override public void onDisconnected(String endpointId) { linkDown(endpointId); }
    };

    // ------------------------------------------------------------------ BulkLink

    @Override public boolean canSend(int node) { return running && endpointOf.containsKey(node); }
    @Override public void setReceiver(com.herabarman.meshnet.kit.BulkLink.Receiver r) { bulkRx = r; }

    @Override public void send(final int node, final byte[] data, final com.herabarman.meshnet.kit.BulkLink.Done cb) {
        final String ep = endpointOf.get(node);
        if (ep == null || data.length == 0 || data.length > MAX_BULK) { cb.onDone(false); return; }
        bulkIo.execute(new Runnable() {
            @Override public void run() {
                java.io.File f = null;
                try {
                    f = java.io.File.createTempFile("mesh-bulk-", ".bin", ctx.getCacheDir());
                    java.io.FileOutputStream o = new java.io.FileOutputStream(f);
                    try { o.write(data); } finally { o.close(); }
                    Payload p = Payload.fromFile(f);
                    bulkOut.put(p.getId(), cb);
                    bulkFiles.put(p.getId(), f);
                    final long id = p.getId();
                    duty.onTraffic(System.currentTimeMillis());
                    nearby.sendPayload(ep, p).addOnFailureListener(new OnFailureListener() {
                        @Override public void onFailure(Exception e) { finishBulkOut(id, false); }
                    });
                } catch (java.io.IOException | RuntimeException e) {
                    if (f != null) f.delete();
                    cb.onDone(false);
                }
            }
        });
    }

    private void finishBulkOut(long payloadId, boolean ok) {
        com.herabarman.meshnet.kit.BulkLink.Done d = bulkOut.remove(payloadId);
        java.io.File f = bulkFiles.remove(payloadId);
        if (f != null) f.delete();
        if (d != null) d.onDone(ok);
    }

    private void bulkReceived(final long payloadId) {
        final Payload p = bulkIn.remove(payloadId);
        final Integer node = bulkInNode.remove(payloadId);
        final com.herabarman.meshnet.kit.BulkLink.Receiver r = bulkRx;
        if (p == null || node == null || r == null) return;
        bulkIo.execute(new Runnable() {
            @Override public void run() {
                try {
                    Payload.File pf = p.asFile();
                    if (pf == null) return;
                    java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(pf.asParcelFileDescriptor());
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    try {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            bo.write(buf, 0, n);
                            if (bo.size() > MAX_BULK) return;
                        }
                    } finally { in.close(); }
                    java.io.File jf = pf.asJavaFile();
                    if (jf != null) jf.delete();
                    r.onReceived(node, bo.toByteArray());
                } catch (java.io.IOException | RuntimeException e) {
                    Log.w(TAG, "bulk receive failed: " + e.getMessage());
                }
            }
        });
    }

    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override public void onPayloadReceived(String endpointId, Payload payload) {
            lastRx.put(endpointId, System.currentTimeMillis());
            if (payload.getType() == Payload.Type.FILE) {
                Integer from = connected.get(endpointId);
                if (from != null && bulkRx != null) { bulkIn.put(payload.getId(), payload); bulkInNode.put(payload.getId(), from); }
                else nearby.cancelPayload(payload.getId());
                return;
            }
            if (payload.getType() != Payload.Type.BYTES) return;
            byte[] bytes = payload.asBytes();
            Integer node = connected.get(endpointId);
            RawRadio.Handler h = rawHandler;
            if (bytes == null || node == null || h == null) return;
            if (bytes.length == 1 && bytes[0] == 0) return;   // ping: only proves the link is alive
            duty.onTraffic(System.currentTimeMillis());
            h.onRaw(node, bytes);
        }

        @Override public void onPayloadTransferUpdate(String endpointId, PayloadTransferUpdate update) {
            long id = update.getPayloadId();
            int st = update.getStatus();
            if (bulkOut.containsKey(id)) {
                if (st == PayloadTransferUpdate.Status.SUCCESS) finishBulkOut(id, true);
                else if (st == PayloadTransferUpdate.Status.FAILURE || st == PayloadTransferUpdate.Status.CANCELED) finishBulkOut(id, false);
                return;
            }
            if (bulkIn.containsKey(id)) {
                if (update.getTotalBytes() > MAX_BULK) { nearby.cancelPayload(id); bulkIn.remove(id); bulkInNode.remove(id); return; }
                if (st == PayloadTransferUpdate.Status.SUCCESS) bulkReceived(id);
                else if (st == PayloadTransferUpdate.Status.FAILURE || st == PayloadTransferUpdate.Status.CANCELED) { bulkIn.remove(id); bulkInNode.remove(id); }
            }
        }
    };
}

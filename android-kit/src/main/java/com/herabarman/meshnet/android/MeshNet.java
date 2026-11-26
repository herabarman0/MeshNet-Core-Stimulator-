package com.herabarman.meshnet.android;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import com.herabarman.meshnet.kit.Alerts;
import com.herabarman.meshnet.kit.Groups;
import com.herabarman.meshnet.kit.HttpServerLink;
import com.herabarman.meshnet.kit.IdMap;
import com.herabarman.meshnet.kit.MeshClient;
import com.herabarman.meshnet.kit.P2pServerLink;
import com.herabarman.meshnet.kit.Presets;
import com.herabarman.meshnet.kit.ServerLink;
import java.util.ArrayList;
import java.util.List;

/**
 * The three-line start:
 *
 * <pre>
 * MeshNet mesh = MeshNet.start(context, "70121", listener, "https://meshnet-i35o.onrender.com/api/mesh", null);
 * mesh.sendText("70122", "hello");
 * // that is all: radio, zones, keys, routing, retries and the offline queue are inside
 * </pre>
 *
 * Callbacks arrive on the main thread. Put this call in your foreground service (MeshService) so it keeps
 * running with the screen off. Request the permissions from {@link #requiredPermissions()} first.
 */
public final class MeshNet {
    private final MeshClient client;
    private final NearbyRadio radio;
    private final LocationFeeder location;
    private final BatteryFeeder battery;
    private Alerts alerts;
    private com.herabarman.meshnet.kit.LostPhone lost;
    private com.herabarman.meshnet.core.MiniAppHost miniHost;
    private com.herabarman.meshnet.core.script.ScriptDistributor scripts;
    private com.herabarman.meshnet.core.OtaSwarm otaSwarm;
    private WifiDirectConnector wifiDirect;
    private LocationFeeder locFeeder;
    private Groups groups;

    private MeshNet(MeshClient client, NearbyRadio radio, LocationFeeder location, BatteryFeeder battery) {
        this.client = client;
        this.radio = radio;
        this.location = location;
        this.battery = battery;
    }

    /** Optional features. All off by default. */
    public static final class Options {
        /** Make direct UDP paths between online phones (STUN + hole punching); the server stays the fallback. */
        public boolean direct = false;
        /**
         * Send big payloads (16 KB and up) to a DIRECT neighbour over a fast side-channel: Nearby FILE payloads (Nearby upgrades itself to
         * Wi-Fi / hotspot) and, with Wi-Fi Direct on, a TCP stream. Multi-hop and failures still use the normal mesh chunks.
         * Off by default because it has not yet been run on real phones; turn it on in a test build first.
         */
        public boolean bulk = false;
        /** Carry messages for phones you pass and hand copies to others (data mule). */
        public boolean dtn;
        /** Pad every message to a size bucket so lengths leak nothing. */
        public boolean maskTraffic;
        /** Public keys of the civil-defence authorities whose alerts count as OFFICIAL (ship them with the app). */
        public List<byte[]> trustedAuthorities = new ArrayList<>();
        /** Set to receive emergency alerts. */
        public Alerts.Listener alertListener;
        /** Set to use offline groups and channels. */
        public Groups.Listener groupListener;
        /**
         * Mobile-network mode: neighbours exchange battery / load / movement beacons, messages choose among several paths
         * by link quality, weak unplugged phones are spared, congested relays shed bulk traffic, and movement is predicted.
         * Costs one small beacon per neighbour every 15 s. Not tried on real phones yet (see docs): leave off if in doubt.
         */
        public boolean adaptive;
        /**
         * Anti-jamming: use a second, separate radio (UDP over the shared Wi-Fi / hotspot) next to Nearby, and move each link to
         * whichever is not jammed. Both phones must turn it on; it only helps while they share a Wi-Fi network or hotspot.
         * Not compiled or tried on real phones (see docs).
         */
        public boolean wifiChannel;
        /**
         * Lost-phone finder: this phone answers beacons of armed phones with sealed GPS sightings and relays others' reports.
         * Turn it on in every install (more phones = more chance to find a lost one). Then call lostPhone().arm(secret) on the
         * phone that may get lost, and lostPhone().search(secret) on any phone after it is lost.
         */
        public boolean lostPhone;
        public com.herabarman.meshnet.kit.LostPhone.Listener lostListener;
        public int wifiChannelPort = 47474;
        /** Anti-jamming / bulk link: Wi-Fi Direct group as one more channel (see WifiDirectConnector for its real-world limits). */
        public boolean wifiDirectChannel;
        public int wifiDirectPort = 47475;
        /** Mini-app host (games, quizzes, markets, script apps). flooded frames are always signed; signedMiniApps = also REQUIRE signatures (unsigned or unverifiable floods are dropped). */
        public boolean miniApps, signedMiniApps;
        /** Publishers whose script mini-apps may be installed from neighbours over the mesh (raw public keys); empty = none. */
        public java.util.List<byte[]> scriptPublishers = new java.util.ArrayList<>();
        /** Offline app updates over the mesh: set appId, installedVersion and trustedPublishers; a half-finished download resumes after a restart. */
        public com.herabarman.meshnet.core.OtaSwarm.Config ota;
    }

    /**
     * @param userId    your app's user id, as a string ("70121", "070121", "MESHab12cd": anything)
     * @param serverUrl base URL of the mesh bridge (server/mesh-bridge.js), or null for radio-only
     * @param token     value for the bridge's X-Mesh-Token header, or null
     */
    public static MeshNet start(Context ctx, String userId, MeshClient.Listener listener, String serverUrl, HttpServerLink.TokenSource token) {
        return start(ctx, userId, listener, serverUrl, token, new Options());
    }

    /**
     * @param direct true: when two online phones talk, they first make a direct UDP path (STUN + hole punching,
     *               see P2pServerLink) so the server carries only the introduction; the server stays the fallback.
     *               Needs the INTERNET permission (the app has it already for the bridge).
     */
    public static MeshNet start(Context ctx, String userId, MeshClient.Listener listener, String serverUrl, HttpServerLink.TokenSource token, boolean direct) {
        Options o = new Options();
        o.direct = direct;
        return start(ctx, userId, listener, serverUrl, token, o);
    }

    public static MeshNet start(Context ctx, String userId, MeshClient.Listener listener, String serverUrl, HttpServerLink.TokenSource token, Options opt) {
        Context app = ctx.getApplicationContext();
        SqliteStorage storage = new SqliteStorage(app);
        int node = new IdMap(storage).nodeOf(userId);
        NearbyRadio radio = new NearbyRadio(app, node);
        AndroidChannels.Set chans = AndroidChannels.build(app, node, radio, opt.wifiChannel, opt.wifiChannelPort, opt.wifiDirectChannel, opt.wifiDirectPort, opt.bulk);
        com.herabarman.meshnet.kit.RawRadio clientRadio = chans.radio;
        MeshClient.Builder b = MeshClient.builder(userId)
                .radio(clientRadio)
                .storage(storage)
                .listener(new MainThread(listener))
                .dtn(opt.dtn)
                .maskTraffic(opt.maskTraffic);
        if (opt.adaptive) b.tune(Presets.mobileAdaptive());
        if (opt.bulk) b.bulk(new com.herabarman.meshnet.kit.CompositeBulk(chans.tcpBulk, radio));   // fastest first; Nearby FILE as the other path
        if (serverUrl != null) {
            ServerLink link = new HttpServerLink(serverUrl, token);
            if (opt.direct) link = new P2pServerLink(link, P2pServerLink.defaultStunServers());
            b.server(link);
        }
        MeshClient client = b.build();
        LocationFeeder loc = new LocationFeeder(app, client).withRadio(radio);
        BatteryFeeder bat = new BatteryFeeder(app, client);
        MeshNet net = new MeshNet(client, radio, loc, bat);
        final Handler ui = new Handler(Looper.getMainLooper());
        if (opt.alertListener != null) {                       // these must exist before start()
            final Alerts.Listener al = opt.alertListener;
            net.alerts = new Alerts(client, opt.trustedAuthorities, new Alerts.Listener() {
                @Override public void onAlert(final Alerts.Alert a) { ui.post(new Runnable() { @Override public void run() { al.onAlert(a); } }); }
            });
        }
        if (opt.groupListener != null) {
            final Groups.Listener gl = opt.groupListener;
            net.groups = new Groups(client, new Groups.Listener() {
                @Override public void onMessage(final String g, final String a, final long t, final String x, final String o) {
                    ui.post(new Runnable() { @Override public void run() { gl.onMessage(g, a, t, x, o); } });
                }
                @Override public void onReaction(final String g, final String o, final String e, final String r, final boolean on) {
                    ui.post(new Runnable() { @Override public void run() { gl.onReaction(g, o, e, r, on); } });
                }
                @Override public void onMembers(final String g, final java.util.Set<String> m) {
                    ui.post(new Runnable() { @Override public void run() { gl.onMembers(g, m); } });
                }
            });
        }
        if (opt.lostPhone) {
            final LocationFeeder lf = loc;
            net.lost = com.herabarman.meshnet.kit.MeshLost.install(client, null, new com.herabarman.meshnet.kit.LostPhone.Where() {
                @Override public com.herabarman.meshnet.kit.LostPhone.Fix fix() { return lf.lastFix(); }
            }, storage);
            if (opt.lostListener != null) net.lost.setListener(opt.lostListener);
        }
        if (opt.miniApps || !opt.scriptPublishers.isEmpty()) {
            net.miniHost = com.herabarman.meshnet.kit.MeshMiniApps.install(client, storage, null, opt.signedMiniApps);
            if (!opt.scriptPublishers.isEmpty())
                net.scripts = com.herabarman.meshnet.kit.MeshScripts.install(client, net.miniHost, opt.scriptPublishers, storage, null);
        }
        if (opt.ota != null) net.otaSwarm = com.herabarman.meshnet.kit.MeshOta.install(client, opt.ota, new com.herabarman.meshnet.kit.IdentityRecordAuth(client.identity()), storage);
        net.wifiDirect = chans.wifiDirect;
        net.locFeeder = loc;
        client.start();
        if (chans.wifiDirect != null) chans.wifiDirect.start();
        loc.start();
        bat.start();
        net.startPowerFeed(app);
        return net;
    }

    public MeshClient client() { return client; }
    /** Emergency alerts (null unless Options.alertListener was set). */
    public Alerts alerts() { return alerts; }
    /** Mini-app host (null unless Options.miniApps or scriptPublishers). Register apps and call start() on the mesh thread via client().post(). */
    public com.herabarman.meshnet.core.MiniAppHost miniApps() { return miniHost; }
    /** Script mini-app distributor (null unless Options.scriptPublishers is non-empty): publish() here to spread a signed script app. */
    public com.herabarman.meshnet.core.script.ScriptDistributor scripts() { return scripts; }
    /** Offline update swarm (null unless Options.ota was set). */
    public com.herabarman.meshnet.core.OtaSwarm ota() { return otaSwarm; }
    /** Lost-phone finder (null unless Options.lostPhone was set). Its methods run on the mesh thread; post via client().post(). */
    public com.herabarman.meshnet.kit.LostPhone lostPhone() { return lost; }
    /** Offline groups and channels (null unless Options.groupListener was set). */
    public Groups groups() { return groups; }

    public long sendText(String toUser, String text) { return client.sendText(toUser, text); }
    /** priority: MeshClient.PRIORITY_SOS / HIGH / NORMAL / BULK */
    public long sendText(String toUser, String text, int priority) { return client.sendText(toUser, text, priority); }
    public long sendBytes(String toUser, byte[] data) { return client.sendBytes(toUser, data); }

    /** priority is a level from com.herabarman.meshnet.core.Priority: SOS, URGENT, NORMAL (default), BULK, BACKGROUND (same numbers as MeshClient.PRIORITY_*). */
    public long sendBytes(String toUser, byte[] data, int priority) { return client.sendBytes(toUser, data, priority); }

    /** An emergency text: sent at once even on a saturated radio, flooded everywhere, retried faster. For alarms only. */
    public long sendSos(String toUser, String text) { return client.sendSos(toUser, text); }

    /** Tell the engine this user id exists (needed once per contact if the id is not a plain number). */
    public void knowUser(String userId) { client.knowUser(userId); }

    /** Photo, voice note or file up to 16 MB. See MeshClient.sendLarge: progress, delivery and retry of only the missing chunks. */
    public long sendLarge(String toUser, byte[] data) { return client.sendLarge(toUser, data); }

    /** BULK (default) for photos and files, BACKGROUND for sync that can wait hours. */
    public long sendLarge(String toUser, byte[] data, int priority) { return client.sendLarge(toUser, data, priority); }
    public boolean retryLarge(long localId) { return client.retryLarge(localId); }
    /** A photo, voice note or file up to 2 MB, sent in chunks; the receiver gets Listener.onBlob(). */
    public long sendBlob(String toUser, byte[] data) { return client.sendBlob(toUser, data); }
    /**
     * Compress a photo (JPEG/PNG/WebP bytes, e.g. from the gallery) so it still looks like the original but is far smaller, then send it.
     * Runs off the UI thread; callback.onQueued tells what was achieved. Camera metadata (GPS) is removed.
     * Default budget is about 300 KB; pass a Config to change quality target, size limit or byte budget.
     */
    public void sendPhoto(String toUser, byte[] photo, com.herabarman.meshnet.core.image.ImageShrinker.Config cfg, com.herabarman.meshnet.kit.PhotoSender.Callback cb) {
        com.herabarman.meshnet.kit.PhotoSender.send(client, toUser, photo, new AndroidImageCodec(), cfg, cb);
    }
    public void markSeen(String fromUser, long... localIds) { client.markSeen(fromUser, localIds); }

    /** Call again after the location permission was granted. */
    public void restartLocation() { location.start(); }

    private volatile boolean stopped;

    /** Tells the engine the battery level and charger state once a minute (neighbours use it for energy-aware routing). */
    private void startPowerFeed(final Context ctx) {
        final Handler h = new Handler(Looper.getMainLooper());
        h.post(new Runnable() {
            @Override public void run() {
                if (stopped) return;
                try {
                    Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                    if (i != null) {
                        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                        int plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                        if (level >= 0 && scale > 0) {
                            // the battery level itself is fed by BatteryFeeder; this loop only does the lost-phone last gasp
                            if (lost != null && plugged == 0 && level * 100 / scale <= 5) {      // last gasp: tell neighbours where this phone is
                                final com.herabarman.meshnet.kit.LostPhone lp = lost;
                                client.post(new Runnable() { @Override public void run() { lp.selfReportNow(); } });
                            }
                        }
                    }
                } catch (RuntimeException ignored) { }
                h.postDelayed(this, 60_000);
            }
        });
    }

    public void stop() {
        stopped = true;
        battery.stop();
        if (wifiDirect != null) wifiDirect.stop();
        location.stop();
        client.stop();
    }

    /** Permissions to request at runtime before start(), depending on the Android version. */
    public static String[] requiredPermissions() {
        List<String> p = new ArrayList<>();
        p.add("android.permission.ACCESS_FINE_LOCATION");
        if (Build.VERSION.SDK_INT >= 31) {
            p.add("android.permission.BLUETOOTH_SCAN");
            p.add("android.permission.BLUETOOTH_ADVERTISE");
            p.add("android.permission.BLUETOOTH_CONNECT");
        }
        if (Build.VERSION.SDK_INT >= 33) p.add("android.permission.NEARBY_WIFI_DEVICES");
        return p.toArray(new String[0]);
    }

    private static final class MainThread implements MeshClient.Listener {
        private final MeshClient.Listener to;
        private final Handler ui = new Handler(Looper.getMainLooper());

        MainThread(MeshClient.Listener to) { this.to = to; }

        @Override public void onText(final String f, final long id, final long ts, final String t) {
            ui.post(new Runnable() { @Override public void run() { to.onText(f, id, ts, t); } });
        }
        @Override public void onBlob(final String f, final long id, final byte[] d) {
            ui.post(new Runnable() { @Override public void run() { to.onBlob(f, id, d); } });
        }
        @Override public void onBlobProgress(final long id, final int done, final int total) {
            ui.post(new Runnable() { @Override public void run() { to.onBlobProgress(id, done, total); } });
        }
        @Override public void onBytes(final String f, final long id, final byte[] d) {
            ui.post(new Runnable() { @Override public void run() { to.onBytes(f, id, d); } });
        }
        @Override public void onDelivered(final long id) {
            ui.post(new Runnable() { @Override public void run() { to.onDelivered(id); } });
        }
        @Override public void onFailed(final long id, final String r) {
            ui.post(new Runnable() { @Override public void run() { to.onFailed(id, r); } });
        }
        @Override public void onSeen(final long id) {
            ui.post(new Runnable() { @Override public void run() { to.onSeen(id); } });
        }
        @Override public void onPeers(final int c) {
            ui.post(new Runnable() { @Override public void run() { to.onPeers(c); } });
        }
        @Override public void onZone(final int z, final String g) {
            ui.post(new Runnable() { @Override public void run() { to.onZone(z, g); } });
        }
        @Override public void onLargeProgress(final long id, final int done, final int total) {
            ui.post(new Runnable() { @Override public void run() { to.onLargeProgress(id, done, total); } });
        }
        @Override public void onLargeFailed(final long id, final String r, final int missing) {
            ui.post(new Runnable() { @Override public void run() { to.onLargeFailed(id, r, missing); } });
        }
        @Override public void onKeyChanged(final String u, final String a, final String b) {
            ui.post(new Runnable() { @Override public void run() { to.onKeyChanged(u, a, b); } });
        }
    }
}

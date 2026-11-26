package com.herabarman.meshnet.core.script;

import com.herabarman.meshnet.core.Clock;
import com.herabarman.meshnet.core.OtaManifest;
import com.herabarman.meshnet.core.OtaSwarm;
import com.herabarman.meshnet.core.RecordAuth;
import com.herabarman.meshnet.core.StorageAdapter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Spreads script mini-apps from phone to phone with no internet: one {@link OtaSwarm} per app id (Merkle-verified chunks, signed manifest,
 * resumable), and a {@link ScriptPackage.Verifier} that installs a script only if a trusted publisher signed it and it is newer than
 * what is installed. A new script app, or a new version, therefore reaches every phone in the mesh that trusts the publisher.
 *
 * Messages: [appId length][appId][swarm message]. A swarm for an unknown app id is created when a neighbour offers it
 * (at most maxApps of them, app ids up to 48 bytes), and gives up on anything that does not pass the publisher check.
 * Installed packages are saved to storage and restored by {@link #restore()}.
 */
public final class ScriptDistributor {
    public interface Link {
        void send(int peer, byte[] message);
        List<Integer> peers();
    }

    public interface Listener {
        /** A verified script is installed (new, or newer than before). The host should (re)start it. */
        void onInstalled(ScriptApp app, ScriptPackage pkg);
        /** A download is under way (0..total chunks). */
        void onProgress(String appId, int have, int total);
        void onRejected(String appId, String reason);
    }

    public static final class Config {
        public List<byte[]> trustedPublishers = new ArrayList<>();
        public int maxApps = 16, chunkSize = 512;
        public MeshScript.Limits scriptLimits;
        public OtaSwarm.Config swarm = new OtaSwarm.Config();        // timing knobs; appId/installedVersion/trust are set here
    }

    private final int self;
    private final Config cfg;
    private final Clock clock;
    private final Random rng;
    private final Link link;
    private final RecordAuth auth;
    private final StorageAdapter store;
    private final ScriptPackage.Verifier verifier;
    private Listener listener;
    private boolean running;
    private final Map<String, OtaSwarm> swarms = new HashMap<>();
    public int rejected;

    public ScriptDistributor(int self, Config cfg, Clock clock, Random rng, Link link, RecordAuth auth, StorageAdapter store) {
        this.self = self; this.cfg = cfg; this.clock = clock; this.rng = rng; this.link = link; this.auth = auth; this.store = store;
        this.verifier = new ScriptPackage.Verifier(auth, cfg.trustedPublishers);
    }

    public void setListener(Listener l) { this.listener = l; }

    public int installedVersion(String appId) { return verifier.installedVersion(appId); }

    public List<String> known() { return new ArrayList<>(swarms.keySet()); }

    /** Load saved packages (re-verified) and unfinished downloads. Call once before start(). @return the apps to register. */
    public List<ScriptApp> restore() {
        List<ScriptApp> out = new ArrayList<>();
        if (store == null) return out;
        for (String k : store.keys("script.pkg.")) {
            byte[] v = store.get(k);
            ScriptPackage p = v == null ? null : ScriptPackage.decode(v);
            ScriptPackage.Result[] r = new ScriptPackage.Result[1];
            OtaSwarm sw = p == null ? null : swarmFor(p.id, true);
            ScriptApp app = p == null ? null : verifier.install(p, cfg.scriptLimits, r);
            if (app == null) { store.remove(k); continue; }
            out.add(app);
            if (sw != null) {
                OtaManifest m = OtaManifest.decode(store.get("script.man." + p.id) == null ? new byte[0] : store.get("script.man." + p.id));
                if (m != null && m.appId.equals(p.id)) sw.seed(m, p.encode());     // keep serving what I have
            }
        }
        for (String k : store.keys("ota.m.")) {                                       // downloads interrupted by a restart
            String id = k.substring("ota.m.".length());
            OtaSwarm sw = swarmFor(id, true);
            if (sw != null) sw.resume();
        }
        return out;
    }

    public void start() {
        running = true;
        for (OtaSwarm s : swarms.values()) s.start();
    }

    public void stop() {
        running = false;
        for (OtaSwarm s : swarms.values()) s.stop();
    }

    /**
     * This phone holds a signed package (it is the publisher, or got the file otherwise): install it here and serve it to others.
     * @param manifest signed by the publisher (OtaManifest.create with the same publisher key) over pkg.encode().
     */
    public ScriptApp publish(ScriptPackage pkg, OtaManifest manifest) {
        OtaSwarm sw = swarmFor(pkg.id, true);                // before install: the swarm's "installed version" is what was there until now
        ScriptPackage.Result[] r = new ScriptPackage.Result[1];
        ScriptApp app = verifier.install(pkg, cfg.scriptLimits, r);
        if (app == null) { reject(pkg.id, r[0].toString()); return null; }
        persist(pkg, manifest);
        if (sw != null) sw.seed(manifest, pkg.encode());
        if (running && sw != null) sw.start();
        if (listener != null) listener.onInstalled(app, pkg);
        return app;
    }

    public void onPeerUp(int peer) { for (OtaSwarm s : swarms.values()) s.onPeerUp(peer); }

    public void onPeerDown(int peer) { for (OtaSwarm s : swarms.values()) s.onPeerDown(peer); }

    public void onMessage(int peer, byte[] m) {
        if (m == null || m.length < 3) return;
        int n = m[0] & 0xFF;
        if (n == 0 || n > 48 || m.length < 1 + n + 1) return;
        String appId = new String(m, 1, n, StandardCharsets.UTF_8);
        byte[] inner = Arrays.copyOfRange(m, 1 + n, m.length);
        OtaSwarm sw = swarms.get(appId);
        if (sw == null) {
            if (inner[0] != 1) return;                         // only an OFFER can introduce an app
            sw = swarmFor(appId, false);
            if (sw == null) return;
            if (running) sw.start();
        }
        sw.onMessage(peer, inner);
    }

    // ---- internals ------------------------------------------------------------------------------------------------

    private OtaSwarm swarmFor(final String appId, boolean force) {
        OtaSwarm sw = swarms.get(appId);
        if (sw != null) return sw;
        if (swarms.size() >= cfg.maxApps || appId.getBytes(StandardCharsets.UTF_8).length > 48) return null;
        OtaSwarm.Config c = new OtaSwarm.Config();
        c.appId = appId;
        c.installedVersion = verifier.installedVersion(appId);
        c.trustedPublishers = cfg.trustedPublishers;
        c.offerMs = cfg.swarm.offerMs; c.tickMs = cfg.swarm.tickMs; c.requestTimeoutMs = cfg.swarm.requestTimeoutMs;
        c.window = cfg.swarm.window; c.perPeer = cfg.swarm.perPeer; c.serveLimitPerSec = cfg.swarm.serveLimitPerSec;
        final byte[] idb = appId.getBytes(StandardCharsets.UTF_8);
        sw = new OtaSwarm(self, c, clock, new Random(rng.nextLong()), new OtaSwarm.Link() {
            @Override public void send(int peer, byte[] message) {
                byte[] f = new byte[1 + idb.length + message.length];
                f[0] = (byte) idb.length;
                System.arraycopy(idb, 0, f, 1, idb.length);
                System.arraycopy(message, 0, f, 1 + idb.length, message.length);
                link.send(peer, f);
            }
            @Override public List<Integer> peers() { return link.peers(); }
        }, auth);
        sw.setStorage(store);
        sw.setListener(new OtaSwarm.Listener() {
            @Override public void onAvailable(OtaManifest m) { }
            @Override public void onProgress(OtaManifest m, int have, int total) { if (listener != null) listener.onProgress(appId, have, total); }
            @Override public void onComplete(OtaManifest m, byte[] file) { completed(appId, m, file); }
        });
        swarms.put(appId, sw);
        return sw;
    }

    private void completed(String appId, OtaManifest m, byte[] file) {
        ScriptPackage p = ScriptPackage.decode(file);
        if (p == null || !p.id.equals(appId) || p.version != m.versionCode) { reject(appId, "package does not match its manifest"); return; }
        ScriptPackage.Result[] r = new ScriptPackage.Result[1];
        ScriptApp app = verifier.install(p, cfg.scriptLimits, r);
        if (app == null) { reject(appId, r[0].toString()); return; }
        persist(p, m);
        if (listener != null) listener.onInstalled(app, p);
    }

    private void persist(ScriptPackage p, OtaManifest m) {
        if (store == null) return;
        store.put("script.pkg." + p.id, p.encode());
        store.put("script.man." + p.id, m.encode());
    }

    private void reject(String appId, String why) {
        rejected++;
        if (listener != null) listener.onRejected(appId, why);
    }
}

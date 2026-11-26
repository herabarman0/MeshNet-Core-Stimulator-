package com.herabarman.meshnet.core.apps;

import com.herabarman.meshnet.core.MiniApp;
import com.herabarman.meshnet.core.MiniAppContext;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Example mini-app: a local marketplace. Sellers post listings that spread a few hops around them; buyers browse what has
 * reached them and message the seller directly (end-to-end encrypted). Listings expire, so a street market stays fresh.
 */
public final class MarketApp implements MiniApp {
    public static final String ID = "com.herasoft.mesh.market";
    private static final long LIFETIME_MS = 6L * 60 * 60 * 1000;

    public static final class Listing {
        public final long id;
        public final int seller;
        public final long priceCents;
        public final String title;
        public final long receivedAt;
        Listing(long id, int seller, long price, String title, long at) { this.id = id; this.seller = seller; this.priceCents = price; this.title = title; this.receivedAt = at; }
    }

    public interface Listener {
        void onListing(Listing l);
        void onInterest(int buyer, long listingId, String note);
    }

    private MiniAppContext ctx;
    private final Listener listener;
    private final Map<Long, Listing> listings = new LinkedHashMap<>();
    private final Map<Long, byte[]> mine = new LinkedHashMap<>();

    public MarketApp(Listener l) { this.listener = l; }

    @Override public String id() { return ID; }
    @Override public int version() { return 1; }
    @Override public void onStop() { }

    @Override public void onStart(MiniAppContext c) {
        ctx = c;
        reannounce();
    }

    /** Seller: post a listing (spreads up to 5 hops) and keep re-announcing it while it is mine. */
    public long post(String title, long priceCents) {
        long id = ctx.now() * 1000 + (ctx.selfId() & 0x3FF);
        byte[] t = title.getBytes(StandardCharsets.UTF_8);
        byte[] m = ByteBuffer.allocate(8 + 4 + 8 + 1 + Math.min(100, t.length)).putLong(id).putInt(ctx.selfId()).putLong(priceCents).put((byte) Math.min(100, t.length)).put(t, 0, Math.min(100, t.length)).array();
        mine.put(id, m);
        ctx.floodNearby(m, 5);
        listings.put(id, new Listing(id, ctx.selfId(), priceCents, title, ctx.now()));
        return id;
    }

    public void remove(long id) { mine.remove(id); listings.remove(id); }

    public List<Listing> browse() {
        long now = ctx.now();
        List<Listing> out = new ArrayList<>();
        for (java.util.Iterator<Listing> it = listings.values().iterator(); it.hasNext(); ) {
            Listing l = it.next();
            if (now - l.receivedAt > LIFETIME_MS) it.remove(); else out.add(l);
        }
        return out;
    }

    /** Buyer: tell the seller you are interested. */
    public boolean contact(long listingId, String note) {
        Listing l = listings.get(listingId);
        if (l == null) return false;
        byte[] n = note.getBytes(StandardCharsets.UTF_8);
        return ctx.sendTo(l.seller, ByteBuffer.allocate(8 + Math.min(200, n.length)).putLong(listingId).put(n, 0, Math.min(200, n.length)).array());
    }

    @Override public void onMessage(int from, byte[] d) {
        if (d.length < 8 || listener == null) return;
        ByteBuffer b = ByteBuffer.wrap(d);
        long id = b.getLong();
        if (!mine.containsKey(id)) return;                       // only listings I actually posted
        byte[] n = new byte[b.remaining()];
        b.get(n);
        listener.onInterest(from, id, new String(n, StandardCharsets.UTF_8));
    }

    @Override public void onNearby(int from, byte[] d) {
        try {
            ByteBuffer b = ByteBuffer.wrap(d);
            long id = b.getLong();
            int seller = b.getInt();
            long price = b.getLong();
            byte[] t = new byte[b.get() & 0xFF];
            b.get(t);
            if (listings.containsKey(id) || listings.size() >= 500) return;
            Listing l = new Listing(id, seller, price, new String(t, StandardCharsets.UTF_8), ctx.now());
            listings.put(id, l);
            if (listener != null) listener.onListing(l);
        } catch (RuntimeException malformed) { }
    }

    @Override public void onPeer(int node, boolean up) {
        if (up) for (byte[] m : mine.values()) ctx.sendNearby(m);          // a new neighbour: hand it my listings straight away
    }

    private void reannounce() {
        for (byte[] m : mine.values()) ctx.floodNearby(m, 5);
        ctx.schedule(10 * 60_000, new Runnable() { @Override public void run() { reannounce(); } });
    }
}

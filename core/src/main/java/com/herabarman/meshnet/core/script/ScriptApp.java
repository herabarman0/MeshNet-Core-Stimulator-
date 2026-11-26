package com.herabarman.meshnet.core.script;

import com.herabarman.meshnet.core.MiniApp;
import com.herabarman.meshnet.core.MiniAppContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A mini-app whose logic is a MeshScript. The script sees ONLY the "mesh" object below, never Java objects:
 * mesh.selfId() mesh.now() mesh.sendTo(node, text) mesh.sendNearby(text) mesh.flood(text, hops) mesh.peers()
 * mesh.save(key, text) mesh.load(key) mesh.log(text) mesh.after(ms, fn).
 * Script callbacks (all optional): onStart(), onMessage(from, text), onNearby(from, text), onPeer(node, up), onStop().
 * Exceptions (including limit violations) propagate to {@link com.herabarman.meshnet.core.MiniAppHost}, which counts them and
 * stops the app after too many.
 */
public final class ScriptApp implements MiniApp {
    private final String id;
    private final int version;
    private final String source;
    private final MeshScript.Limits limits;
    private MeshScript vm;
    private MiniAppContext ctx;
    public int limitHits;

    public ScriptApp(String id, int version, String source, MeshScript.Limits limits) {
        this.id = id; this.version = version; this.source = source; this.limits = limits == null ? new MeshScript.Limits() : limits;
        new MeshScript(source, this.limits);   // syntax check now, so a broken script fails at install time
    }

    @Override public String id() { return id; }
    @Override public int version() { return version; }

    private static String text(byte[] d) { return new String(d, StandardCharsets.UTF_8); }
    private static byte[] bytes(Object o) { return String.valueOf(o instanceof Double ? show((Double) o) : o).getBytes(StandardCharsets.UTF_8); }
    private static String show(double d) { return d == Math.floor(d) ? Long.toString((long) d) : Double.toString(d); }
    private static int node(Object o) {
        if (!(o instanceof Double)) throw new MeshScript.ScriptError("node id must be a number");
        return (int) (double) (Double) o;
    }

    @Override public void onStart(final MiniAppContext c) {
        ctx = c;
        vm = new MeshScript(source, limits);
        Map<Object, Object> mesh = new LinkedHashMap<>();
        mesh.put("selfId", new MeshScript.Native() { public Object call(Object[] a) { return (double) c.selfId(); } });
        mesh.put("now", new MeshScript.Native() { public Object call(Object[] a) { return (double) c.now(); } });
        mesh.put("sendTo", new MeshScript.Native() { public Object call(Object[] a) { return c.sendTo(node(a.length > 0 ? a[0] : null), bytes(a.length > 1 ? a[1] : "")); } });
        mesh.put("sendNearby", new MeshScript.Native() { public Object call(Object[] a) { return c.sendNearby(bytes(a.length > 0 ? a[0] : "")); } });
        mesh.put("flood", new MeshScript.Native() { public Object call(Object[] a) {
            int hops = a.length > 1 ? Math.max(1, Math.min(8, node(a[1]))) : 3;
            return c.floodNearby(bytes(a.length > 0 ? a[0] : ""), hops); } });
        mesh.put("peers", new MeshScript.Native() { public Object call(Object[] a) {
            List<Object> out = new ArrayList<>();
            Set<Integer> p = c.peersRunning();
            for (Integer i : p) { if (out.size() >= limits.maxCollection) break; out.add((double) i); }
            return out; } });
        mesh.put("save", new MeshScript.Native() { public Object call(Object[] a) { return c.save(String.valueOf(a.length > 0 ? a[0] : ""), bytes(a.length > 1 ? a[1] : "")); } });
        mesh.put("load", new MeshScript.Native() { public Object call(Object[] a) { byte[] v = c.load(String.valueOf(a.length > 0 ? a[0] : "")); return v == null ? null : text(v); } });
        mesh.put("log", new MeshScript.Native() { public Object call(Object[] a) { c.log(String.valueOf(a.length > 0 ? a[0] : "")); return null; } });
        mesh.put("after", new MeshScript.Native() { public Object call(final Object[] a) {
            if (a.length < 2) throw new MeshScript.ScriptError("after(ms, fn)");
            final Object fn = a[1];
            c.schedule((long) Math.max(0, Math.min(3_600_000, ((Double) a[0]))), new Runnable() { public void run() { call0(fn); } });
            return null; } });
        vm.define("mesh", mesh);
        run(new Runnable() { public void run() { vm.run(); } });
        if (vm.has("onStart")) call("onStart");
    }

    private void call0(Object fn) { try { vm.callValue(fn); } catch (MeshScript.LimitError e) { limitHits++; throw e; } }

    private void call(String fn, Object... args) {
        if (vm == null || !vm.has(fn)) return;
        try { vm.call(fn, args); } catch (MeshScript.LimitError e) { limitHits++; throw e; }
    }

    private void run(Runnable r) { try { r.run(); } catch (MeshScript.LimitError e) { limitHits++; throw e; } }

    @Override public void onMessage(int from, byte[] data) { call("onMessage", (double) from, text(data)); }
    @Override public void onNearby(int from, byte[] data) { call("onNearby", (double) from, text(data)); }
    @Override public void onPeer(int n, boolean up) { call("onPeer", (double) n, up); }
    @Override public void onStop() { try { call("onStop"); } catch (RuntimeException ignored) { } vm = null; }
}

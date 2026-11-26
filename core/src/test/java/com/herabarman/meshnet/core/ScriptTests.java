package com.herabarman.meshnet.core;

import static com.herabarman.meshnet.core.TestKit.check;
import static com.herabarman.meshnet.core.TestKit.info;

import com.herabarman.meshnet.core.script.MeshScript;
import com.herabarman.meshnet.core.script.ScriptApp;
import com.herabarman.meshnet.core.script.ScriptPackage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** MeshScript: language basics, hostile scripts, and script mini-apps on the host. */
public final class ScriptTests {
    public static void main(String[] args) {
        language();
        hostile();
        scriptApps();
        packages();
        TestKit.summary();
    }

    static Object eval(String src) {
        MeshScript s = new MeshScript("var r = null; function f() { " + src + " }", null);
        s.run();
        return s.call("f");
    }

    static boolean limit(String src, MeshScript.Limits l) {
        try {
            MeshScript s = new MeshScript(src, l);
            s.run();
            if (s.has("f")) s.call("f");
            return false;
        } catch (MeshScript.LimitError e) { return true; } catch (MeshScript.ScriptError e) { return false; }
    }

    static boolean fails(String src) {
        try { MeshScript s = new MeshScript(src, null); s.run(); if (s.has("f")) s.call("f"); return false; } catch (MeshScript.ScriptError e) { return true; }
    }

    static void language() {
        check("arithmetic and precedence", Double.valueOf(14).equals(eval("return 2 + 3 * 4;")));
        check("strings, lists, maps and json round trip", "{\"a\":[1,2,\"x\"]}".equals(eval("return toJson({a: [1, 2, \"x\"]});")));
        check("fromJson parses and rejects garbage as null", Double.valueOf(7).equals(eval("var m = fromJson(\"{\\\"k\\\": 7}\"); return m.k;")) && eval("return fromJson(\"{oops\");") == null);
        check("closures keep their variables", Double.valueOf(3).equals(eval("function mk() { var c = 0; return function() { c = c + 1; return c; }; } var g = mk(); g(); g(); return g();")));
        check("for, for-of, while, break, continue", Double.valueOf(1 + 3 + 4).equals(eval("var t = 0; for (var i = 0; i < 6; i = i + 1) { if (i == 2) continue; if (i == 5) break; t = t + i; } return t;")));
        check("recursion works within the depth limit", Double.valueOf(120).equals(eval("function fact(n) { if (n <= 1) return 1; return n * fact(n - 1); } return fact(5);")));
        check("for-of over a list sums", Double.valueOf(6).equals(eval("var t = 0; for (var x of [1, 2, 3]) { t = t + x; } return t;")));
        check("string helpers", "b-c".equals(eval("return join(split(\"b,c\", \",\"), \"-\");")) && Double.valueOf(3).equals(eval("return len(\"abc\");")));
        check("a syntax error is reported, not crashed on", fails("var = ;") && fails("function f( { }"));
        check("assigning to an undeclared name is an error", fails("function f() { zz = 1; } f();"));
        check("unknown names cannot reach Java", fails("function f() { return java.lang.Runtime; } f();") && fails("function f() { return System; } f();"));
        check("calling a method on a string does not reach Java methods", fails("function f() { return \"x\".getClass(); } f();"));
    }

    static void hostile() {
        MeshScript.Limits l = new MeshScript.Limits();
        long t0 = System.currentTimeMillis();
        check("an infinite loop is stopped by the step limit", limit("function f() { while (true) { } } ", l) || limit("while (true) { }", l));
        check("an infinite for loop is stopped", limit("for (;;) { }", l));
        check("a loop that calls functions is stopped", limit("function g() { return 1; } while (true) { g(); }", l));
        check("runaway recursion is stopped by the depth limit, not a Java StackOverflow", limit("function r() { return r(); } r();", l));
        check("a string that doubles itself is stopped", limit("var s = \"aaaaaaaa\"; while (true) { s = s + s; }", l));
        check("a list that keeps growing is stopped", limit("var a = []; while (true) { push(a, 1); }", l));
        check("a map that keeps growing is stopped", limit("var m = {}; var i = 0; while (true) { m[str(i)] = i; i = i + 1; }", l));
        MeshScript.Limits small = new MeshScript.Limits();
        small.maxCollection = 100000; small.maxMemoryUnits = 5000; small.maxSteps = 2_000_000;
        check("persistent data over the memory budget is caught after the run", limit("var big = []; for (var i = 0; i < 20000; i = i + 1) { push(big, i); }", small));
        check("a self-referencing structure does not hang the checker", !limit("var a = []; push(a, a);", l));
        check("deeply nested json is rejected", eval("var s = \"\"; for (var i = 0; i < 40; i = i + 1) { s = \"[\" + s + \"]\"; } return fromJson(s);") == null);
        String[] misuse = {"push(1, 2);", "keys(5);", "num({});", "substr(null, {}, []);", "len(null);", "join(3);", "split(null, null);", "toJson(len);", "min(\"a\", []);", "remove(1, 2);", "pop(null);"};
        int clean = 0;
        for (String m : misuse) { try { MeshScript x = new MeshScript(m, null); x.run(); clean++; } catch (MeshScript.ScriptError e) { clean++; } catch (RuntimeException e) { info("leaked " + e); } }
        check("misusing built-ins only ever raises ScriptError, never a raw Java exception", clean == misuse.length);
        long ms = System.currentTimeMillis() - t0;
        check("all hostile scripts finished quickly (" + ms + " ms)", ms < 5000);
        MeshScript s = new MeshScript("function ok() { return 1; } function bad() { while (true) { } }", null);
        s.run();
        try { s.call("bad"); } catch (MeshScript.LimitError e) { }
        check("after a limit error the VM is still usable and the budget is fresh", Double.valueOf(1).equals(s.call("ok")));
        check("an oversized source is refused", failsBig());
        info("hostile scripts: loops, recursion, string/list/map bombs all stopped by limits");
    }

    static boolean failsBig() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 70000; i++) b.append(' ');
        try { new MeshScript(b.toString(), null); return false; } catch (MeshScript.LimitError e) { return true; }
    }

    static final class Ctx implements MiniAppContext {
        final List<String> sent = new ArrayList<>(), log = new ArrayList<>();
        final Map<String, byte[]> store = new HashMap<>();
        final List<Runnable> later = new ArrayList<>();
        @Override public int selfId() { return 7; }
        @Override public long now() { return 1234; }
        @Override public boolean sendTo(int n, byte[] d) { sent.add("to" + n + ":" + new String(d, StandardCharsets.UTF_8)); return true; }
        @Override public boolean sendNearby(byte[] d) { sent.add("nb:" + new String(d, StandardCharsets.UTF_8)); return true; }
        @Override public boolean floodNearby(byte[] d, int h) { sent.add("fl" + h + ":" + new String(d, StandardCharsets.UTF_8)); return true; }
        @Override public Set<Integer> peersRunning() { return new HashSet<>(Arrays.asList(1, 2)); }
        @Override public void schedule(long ms, Runnable r) { later.add(r); }
        @Override public boolean save(String k, byte[] v) { store.put(k, v); return true; }
        @Override public byte[] load(String k) { return store.get(k); }
        @Override public void log(String m) { log.add(m); }
    }

    static final String COUNTER =
            "var count = 0;\n" +
            "function onStart() { var c = mesh.load(\"count\"); if (c != null) count = num(c); mesh.log(\"hi \" + mesh.selfId()); }\n" +
            "function onMessage(from, text) {\n" +
            "  var m = fromJson(text);\n" +
            "  if (m == null) return;\n" +
            "  count = count + m.n;\n" +
            "  mesh.save(\"count\", str(count));\n" +
            "  mesh.sendTo(from, toJson({total: count, peers: len(mesh.peers())}));\n" +
            "}\n" +
            "function onNearby(from, text) { mesh.flood(\"seen \" + text, 99); }\n" +
            "function onPeer(n, up) { if (up) mesh.sendNearby(\"welcome \" + n); }\n" +
            "function onStop() { mesh.log(\"bye\"); }\n";

    static void scriptApps() {
        Ctx c = new Ctx();
        ScriptApp app = new ScriptApp("com.example.counter", 1, COUNTER, null);
        app.onStart(c);
        check("onStart runs and can use mesh.selfId and log", c.log.contains("hi 7"));
        app.onMessage(3, "{\"n\": 5}".getBytes(StandardCharsets.UTF_8));
        app.onMessage(3, "{\"n\": 2}".getBytes(StandardCharsets.UTF_8));
        check("onMessage parses json, keeps state, replies to the sender", c.sent.contains("to3:{\"total\":7,\"peers\":2}"));
        check("data saved by the script is stored as text", "7".equals(new String(c.store.get("count"), StandardCharsets.UTF_8)));
        app.onMessage(3, "garbage".getBytes(StandardCharsets.UTF_8));
        check("garbage input is ignored by the script, no crash", c.sent.size() == 2);
        app.onNearby(2, "x".getBytes(StandardCharsets.UTF_8));
        check("flood hops are clamped to 8 by the bridge", c.sent.contains("fl8:seen x"));
        app.onPeer(9, true);
        check("onPeer reaches the script", c.sent.contains("nb:welcome 9"));
        app.onStop();
        check("onStop runs", c.log.contains("bye"));

        Ctx c2 = new Ctx();
        c2.store.put("count", "40".getBytes(StandardCharsets.UTF_8));
        ScriptApp again = new ScriptApp("com.example.counter", 1, COUNTER, null);
        again.onStart(c2);
        again.onMessage(1, "{\"n\": 2}".getBytes(StandardCharsets.UTF_8));
        check("state survives a restart through mesh.save/load", c2.sent.contains("to1:{\"total\":42,\"peers\":2}"));

        boolean threw = false;
        try { new ScriptApp("bad", 1, "function onStart( {", null); } catch (MeshScript.ScriptError e) { threw = true; }
        check("a script with a syntax error is refused at creation", threw);

        // hostile app on the real host: it is stopped after repeated limit violations, the host and other apps keep working
        MiniAppTests.World w = new MiniAppTests.World(2, null);
        ScriptApp evil = new ScriptApp("com.evil", 1, "function onMessage(f, t) { while (true) { } }\nfunction onNearby(f, t) { mesh.sendTo(f, \"x\"); }", null);
        final int[] good = {0};
        w.host[0].register(evil);
        w.host[0].register(new MiniApp() {
            @Override public String id() { return "good"; }
            @Override public int version() { return 1; }
            @Override public void onStart(MiniAppContext x) { }
            @Override public void onMessage(int f, byte[] d) { good[0]++; }
            @Override public void onNearby(int f, byte[] d) { }
            @Override public void onPeer(int n, boolean up) { }
            @Override public void onStop() { }
        });
        w.startAll();
        w.clock.run(1000);
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < 12; i++) w.host[0].onReliableFrame(1, frameFor(w.host[0], "com.evil"));
        long ms = System.currentTimeMillis() - t0;
        check("the host survives a script that loops forever (" + ms + " ms) and records crashes", w.host[0].crashes >= 1 && ms < 8000);
        check("the evil script app is stopped after too many errors, the other app still runs", !w.host[0].isRunning("com.evil") && w.host[0].isRunning("good"));
        check("limit hits were counted by the app", evil.limitHits >= 1);
    }

    /** Build a direct frame (type 3) for an app, the way the host encodes it. */
    static byte[] frameFor(MiniAppHost h, String appId) {
        byte[] a = appId.getBytes(StandardCharsets.UTF_8);
        byte[] data = "go".getBytes(StandardCharsets.UTF_8);
        return java.nio.ByteBuffer.allocate(2 + a.length + data.length).put((byte) 3).put((byte) a.length).put(a).put(data).array();
    }

    static void packages() {
        SignedRecordTests.EcAuth pub = new SignedRecordTests.EcAuth(), other = new SignedRecordTests.EcAuth(), verifier = pub;
        ScriptPackage.Verifier v = new ScriptPackage.Verifier(verifier, Arrays.asList(pub.publicKey()));
        ScriptPackage p1 = ScriptPackage.create("com.example.counter", 1, COUNTER, pub);
        ScriptPackage rt = ScriptPackage.decode(p1.encode());
        check("a package survives encode/decode", rt != null && rt.id.equals(p1.id) && rt.version == 1 && rt.source.equals(COUNTER));
        ScriptPackage.Result[] r = new ScriptPackage.Result[1];
        check("a package from a trusted publisher installs", v.install(rt, null, r) != null && r[0] == ScriptPackage.Result.OK);
        check("the same version again is refused (rollback protection)", v.install(rt, null, r) == null && r[0] == ScriptPackage.Result.OLD_VERSION);
        check("an older version is refused", v.install(ScriptPackage.create("com.example.counter", 0, COUNTER, pub), null, r) == null);
        check("a newer version installs", v.install(ScriptPackage.create("com.example.counter", 2, COUNTER, pub), null, r) != null && v.installedVersion("com.example.counter") == 2);
        check("a package from an unknown publisher is refused", v.install(ScriptPackage.create("x", 1, "var a = 1;", other), null, r) == null && r[0] == ScriptPackage.Result.UNTRUSTED);
        ScriptPackage forged = new ScriptPackage("com.example.counter", 3, "function onStart() { }", pub.publicKey(), p1.signature);
        check("changing the code under a valid signature is detected", v.install(forged, null, r) == null && r[0] == ScriptPackage.Result.BAD_SIGNATURE);
        ScriptPackage broken = ScriptPackage.create("broken", 1, "function (", pub);
        check("a correctly signed but unparsable script is refused", v.install(broken, null, r) == null && r[0] == ScriptPackage.Result.BAD_SCRIPT);
        check("truncated package bytes decode to null", ScriptPackage.decode(Arrays.copyOf(p1.encode(), 30)) == null);
    }
}

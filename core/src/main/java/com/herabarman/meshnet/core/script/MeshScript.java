package com.herabarman.meshnet.core.script;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MeshScript: a small, safe scripting language for mini-apps, written from scratch so a script can be run without trusting it.
 *
 * A script can do nothing except compute and call the functions the host puts in front of it: there is no file, network, reflection,
 * class loading or clock unless the host hands one over. Every run is bounded: steps (so a loop cannot hang the phone), call depth, string length,
 * collection size and total memory; going over any of them stops the script with a {@link LimitError}.
 *
 * <pre>
 * var scores = {};
 * function onMessage(from, data) {            // called by the host
 *   var m = fromJson(data);
 *   scores[str(from)] = (scores[str(from)] || 0) + m.points;
 *   mesh.sendTo(from, toJson({total: scores[str(from)]}));
 * }
 * </pre>
 * Types: number (double), string, boolean, null, list, map (string keys), function. Statements: var, function, if/else, while,
 * for(;;), for (var x of list), break, continue, return. Operators: + - * / % == != &lt; &lt;= &gt; &gt;= &amp;&amp; || ! and a[i], a.k, f(x).
 * Semicolons are required. Not thread-safe: one run at a time.
 */
public final class MeshScript {

    // ---- errors and limits -----------------------------------------------------------------------

    public static class ScriptError extends RuntimeException {
        public ScriptError(String m) { super(m); }
    }

    /** The script used more steps, memory, depth... than it is allowed. */
    public static final class LimitError extends ScriptError {
        public LimitError(String m) { super(m); }
    }

    public static final class Limits {
        public int maxSteps = 200_000, maxDepth = 48, maxString = 16_384, maxCollection = 4_096, maxMemoryUnits = 100_000, maxSource = 64 * 1024;
    }

    /** A function the host provides (or a built-in). */
    public interface Native { Object call(Object[] args); }

    // ---- values ----------------------------------------------------------------------------------

    private static final class Closure {
        final List<String> params;
        final Stmt body;
        final Env env;
        Closure(List<String> p, Stmt b, Env e) { params = p; body = b; env = e; }
    }

    private static final class Env {
        final Map<String, Object> vars = new HashMap<>();
        final Env parent;
        Env(Env p) { parent = p; }
        Env find(String n) { for (Env e = this; e != null; e = e.parent) if (e.vars.containsKey(n)) return e; return null; }
    }

    // ---- interpreter state ---------------------------------------------------------------------------

    private final Limits lim;
    private final Env globals = new Env(null);
    private int steps, depth;
    private Object returnValue;
    private int line;

    public MeshScript(String source, Limits limits) {
        this.lim = limits == null ? new Limits() : limits;
        if (source.length() > lim.maxSource) throw new LimitError("script too long");
        installBuiltins();
        this.program = new Parser(source).parseProgram();
    }

    private final List<Stmt> program;

    /** Make a host function visible to the script as a global (e.g. under a map named "mesh"). */
    public void define(String name, Object value) { globals.vars.put(name, value); }

    public Object get(String name) { return globals.vars.get(name); }

    public boolean has(String name) { return globals.vars.get(name) instanceof Closure; }

    /** Run the top level (defines the script's functions and globals). */
    public void run() {
        begin();
        try {
            for (Stmt s : program) { if (s.exec(globals) != 0) break; }
        } finally { endCheck(); }
    }

    /** Call a function the script defined, with a fresh step budget. */
    public Object call(String name, Object... args) {
        Object f = globals.vars.get(name);
        if (!(f instanceof Closure)) throw new ScriptError("no function " + name);
        return callValue(f, args);
    }

    public Object callValue(Object f, Object... args) {
        begin();
        try { return invoke(f, args); } finally { endCheck(); }
    }

    public int lastSteps() { return steps; }

    private void begin() { steps = 0; depth = 0; }

    /** After a run: the script's persistent data must still fit its memory budget. */
    private void endCheck() {
        int[] units = {0};
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        for (Map.Entry<String, Object> e : globals.vars.entrySet()) if (!(e.getValue() instanceof Native)) size(e.getValue(), units, seen);
        if (units[0] > lim.maxMemoryUnits) throw new LimitError("memory limit (" + units[0] + " units)");
    }

    private void size(Object v, int[] units, IdentityHashMap<Object, Boolean> seen) {
        units[0]++;
        if (units[0] > lim.maxMemoryUnits * 2) return;
        if (v instanceof String) units[0] += ((String) v).length() / 16;
        else if (v instanceof List || v instanceof Map) {
            if (seen.put(v, true) != null) return;
            if (v instanceof List) for (Object o : (List<?>) v) size(o, units, seen);
            else for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) { units[0]++; size(e.getValue(), units, seen); }
        } else if (v instanceof Closure) {
            if (seen.put(v, true) != null) return;
            for (Env e = ((Closure) v).env; e != null && e != globals; e = e.parent)
                if (seen.put(e, true) == null) for (Object o : e.vars.values()) size(o, units, seen);
        }
    }

    private void tick() {
        if (++steps > lim.maxSteps) throw new LimitError("step limit (" + lim.maxSteps + ") at line " + line);
    }

    private Object invoke(Object f, Object[] args) {
        if (f instanceof Native) {
            try { return ((Native) f).call(args); }
            catch (ScriptError e) { throw e; }
            catch (RuntimeException e) { throw err("bad arguments (" + e.getClass().getSimpleName() + ")"); }
        }
        if (!(f instanceof Closure)) throw err("not a function");
        Closure c = (Closure) f;
        if (++depth > lim.maxDepth) throw new LimitError("call depth limit at line " + line);
        try {
            Env e = new Env(c.env);
            for (int i = 0; i < c.params.size(); i++) e.vars.put(c.params.get(i), i < args.length ? args[i] : null);
            returnValue = null;
            int r = c.body.exec(e);
            Object v = r == RETURN ? returnValue : null;
            returnValue = null;
            return v;
        } finally { depth--; }
    }

    private ScriptError err(String m) { return new ScriptError(m + " (line " + line + ")"); }

    // ---- value helpers -----------------------------------------------------------------------------

    private static boolean truthy(Object v) {
        return v != null && !Boolean.FALSE.equals(v) && !(v instanceof Double && ((Double) v == 0 || ((Double) v).isNaN())) && !"".equals(v);
    }

    static String show(Object v) {
        if (v == null) return "null";
        if (v instanceof Double) {
            double d = (Double) v;
            return d == Math.floor(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d);
        }
        if (v instanceof List) { StringBuilder b = new StringBuilder("["); boolean f = true; for (Object o : (List<?>) v) { if (!f) b.append(", "); f = false; b.append(show(o)); if (b.length() > 2000) break; } return b.append("]").toString(); }
        if (v instanceof Map) return "{map}";
        if (v instanceof Closure || v instanceof Native) return "<function>";
        return String.valueOf(v);
    }

    private static boolean equal(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Double && b instanceof Double) return ((Double) a).doubleValue() == ((Double) b).doubleValue();
        if (a instanceof String || a instanceof Boolean) return a.equals(b);
        return a == b;
    }

    private double num(Object v) {
        if (v instanceof Double) return (Double) v;
        throw err("expected a number, got " + show(v));
    }

    private String checkStr(String s) {
        if (s.length() > lim.maxString) throw new LimitError("string longer than " + lim.maxString + " at line " + line);
        return s;
    }

    @SuppressWarnings("unchecked")
    private List<Object> newList(int expect) {
        if (expect > lim.maxCollection) throw new LimitError("list too large at line " + line);
        return new ArrayList<>();
    }

    // ---- built-ins -----------------------------------------------------------------------------------

    private void def(String name, Native n) { globals.vars.put(name, n); }

    @SuppressWarnings("unchecked")
    private void installBuiltins() {
        def("len", new Native() { public Object call(Object[] a) {
            Object v = arg(a, 0);
            if (v instanceof String) return (double) ((String) v).length();
            if (v instanceof List) return (double) ((List<?>) v).size();
            if (v instanceof Map) return (double) ((Map<?, ?>) v).size();
            throw err("len of " + show(v)); } });
        def("str", new Native() { public Object call(Object[] a) { return checkStr(show(arg(a, 0))); } });
        def("num", new Native() { public Object call(Object[] a) {
            Object v = arg(a, 0);
            if (v instanceof Double) return v;
            try { return Double.parseDouble(String.valueOf(v).trim()); } catch (NumberFormatException e) { return null; } } });
        def("floor", new Native() { public Object call(Object[] a) { return Math.floor(num(arg(a, 0))); } });
        def("abs", new Native() { public Object call(Object[] a) { return Math.abs(num(arg(a, 0))); } });
        def("sqrt", new Native() { public Object call(Object[] a) { return Math.sqrt(num(arg(a, 0))); } });
        def("min", new Native() { public Object call(Object[] a) { return Math.min(num(arg(a, 0)), num(arg(a, 1))); } });
        def("max", new Native() { public Object call(Object[] a) { return Math.max(num(arg(a, 0)), num(arg(a, 1))); } });
        def("push", new Native() { public Object call(Object[] a) {
            Object l = arg(a, 0);
            if (!(l instanceof List)) throw err("push needs a list");
            if (((List<Object>) l).size() >= lim.maxCollection) throw new LimitError("list too large at line " + line);
            ((List<Object>) l).add(arg(a, 1));
            return l; } });
        def("pop", new Native() { public Object call(Object[] a) {
            Object l = arg(a, 0);
            if (!(l instanceof List)) throw err("pop needs a list");
            List<Object> x = (List<Object>) l;
            return x.isEmpty() ? null : x.remove(x.size() - 1); } });
        def("keys", new Native() { public Object call(Object[] a) {
            Object m = arg(a, 0);
            if (!(m instanceof Map)) throw err("keys needs a map");
            List<Object> out = newList(0);
            for (Object k : ((Map<Object, Object>) m).keySet()) out.add(k);
            return out; } });
        def("remove", new Native() { public Object call(Object[] a) {
            Object m = arg(a, 0);
            if (!(m instanceof Map)) throw err("remove needs a map");
            return ((Map<Object, Object>) m).remove(arg(a, 1)); } });
        def("substr", new Native() { public Object call(Object[] a) {
            String s = String.valueOf(arg(a, 0));
            int from = (int) Math.max(0, Math.min(s.length(), num(arg(a, 1)))), to = a.length > 2 ? (int) Math.max(from, Math.min(s.length(), num(a[2]))) : s.length();
            return s.substring(from, to); } });
        def("indexOf", new Native() { public Object call(Object[] a) { return (double) String.valueOf(arg(a, 0)).indexOf(String.valueOf(arg(a, 1))); } });
        def("split", new Native() { public Object call(Object[] a) {
            String s = String.valueOf(arg(a, 0)), sep = String.valueOf(arg(a, 1));
            List<Object> out = newList(0);
            if (sep.isEmpty()) { for (int i = 0; i < s.length() && i < lim.maxCollection; i++) out.add(String.valueOf(s.charAt(i))); return out; }
            int from = 0;
            while (out.size() < lim.maxCollection) { int i = s.indexOf(sep, from); if (i < 0) { out.add(s.substring(from)); break; } out.add(s.substring(from, i)); from = i + sep.length(); }
            return out; } });
        def("join", new Native() { public Object call(Object[] a) {
            Object l = arg(a, 0);
            if (!(l instanceof List)) throw err("join needs a list");
            StringBuilder b = new StringBuilder();
            String sep = a.length > 1 ? String.valueOf(a[1]) : "";
            boolean first = true;
            for (Object o : (List<Object>) l) { if (!first) b.append(sep); first = false; b.append(show(o)); if (b.length() > lim.maxString) throw new LimitError("string too long"); }
            return b.toString(); } });
        def("toJson", new Native() { public Object call(Object[] a) { StringBuilder b = new StringBuilder(); json(arg(a, 0), b, 0); return checkStr(b.toString()); } });
        def("fromJson", new Native() { public Object call(Object[] a) {
            String s = String.valueOf(arg(a, 0));
            try { Json j = new Json(s); Object v = j.value(0); j.ws(); return j.i == s.length() ? v : null; } catch (ScriptError e) { if (e instanceof LimitError) throw e; return null; } } });
        def("typeof", new Native() { public Object call(Object[] a) {
            Object v = arg(a, 0);
            return v == null ? "null" : v instanceof Double ? "number" : v instanceof String ? "string" : v instanceof Boolean ? "boolean" : v instanceof List ? "list" : v instanceof Map ? "map" : "function"; } });
    }

    private static Object arg(Object[] a, int i) { return i < a.length ? a[i] : null; }

    @SuppressWarnings("unchecked")
    private void json(Object v, StringBuilder b, int d) {
        if (d > 16 || b.length() > lim.maxString) throw new LimitError("toJson: too deep or too long");
        if (v == null) b.append("null");
        else if (v instanceof Double || v instanceof Boolean) b.append(show(v));
        else if (v instanceof String) quote((String) v, b);
        else if (v instanceof List) { b.append('['); boolean f = true; for (Object o : (List<Object>) v) { if (!f) b.append(','); f = false; json(o, b, d + 1); } b.append(']'); }
        else if (v instanceof Map) { b.append('{'); boolean f = true; for (Map.Entry<Object, Object> e : ((Map<Object, Object>) v).entrySet()) { if (!f) b.append(','); f = false; quote(String.valueOf(e.getKey()), b); b.append(':'); json(e.getValue(), b, d + 1); } b.append('}'); }
        else b.append("null");
    }

    private static void quote(String s, StringBuilder b) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c == '\t') b.append("\\t");
            else if (c < 32) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        b.append('"');
    }

    private final class Json {
        final String s;
        int i;
        Json(String s) { this.s = s; }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Object value(int d) {
            if (d > 16) throw new ScriptError("json too deep");
            ws();
            if (i >= s.length()) throw new ScriptError("json: end");
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<Object, Object> m = new LinkedHashMap<>();
                ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws();
                    String k = string();
                    ws();
                    if (s.charAt(i++) != ':') throw new ScriptError("json: colon");
                    if (m.size() >= lim.maxCollection) throw new LimitError("json: too large");
                    m.put(k, value(d + 1));
                    ws();
                    char n = s.charAt(i++);
                    if (n == '}') return m;
                    if (n != ',') throw new ScriptError("json: comma");
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) {
                    if (l.size() >= lim.maxCollection) throw new LimitError("json: too large");
                    l.add(value(d + 1));
                    ws();
                    char n = s.charAt(i++);
                    if (n == ']') return l;
                    if (n != ',') throw new ScriptError("json: comma");
                }
            }
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            try { return Double.parseDouble(s.substring(st, i)); } catch (NumberFormatException e) { throw new ScriptError("json: number"); }
        }
        String string() {
            if (s.charAt(i++) != '"') throw new ScriptError("json: string");
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    if (e == 'n') b.append('\n'); else if (e == 't') b.append('\t');
                    else if (e == 'u') { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    else b.append(e);
                } else b.append(c);
                if (b.length() > lim.maxString) throw new LimitError("json: string too long");
            }
        }
    }

    // ---- AST -------------------------------------------------------------------------------------------

    private static final int NORMAL = 0, BREAK = 1, CONTINUE = 2, RETURN = 3;

    private abstract class Expr { final int ln; Expr(int ln) { this.ln = ln; } abstract Object eval(Env e); }
    private abstract class Stmt { final int ln; Stmt(int ln) { this.ln = ln; } abstract int exec(Env e); }

    private Object ev(Expr x, Env e) { line = x.ln; tick(); return x.eval(e); }

    private final class Lit extends Expr { final Object v; Lit(int l, Object v) { super(l); this.v = v; } Object eval(Env e) { return v; } }
    private final class Ident extends Expr {
        final String n;
        Ident(int l, String n) { super(l); this.n = n; }
        Object eval(Env e) { Env f = e.find(n); if (f == null) throw err("unknown name " + n); return f.vars.get(n); }
    }
    private final class ListLit extends Expr {
        final List<Expr> items;
        ListLit(int l, List<Expr> i) { super(l); items = i; }
        Object eval(Env e) { List<Object> out = newList(items.size()); for (Expr x : items) out.add(ev(x, e)); return out; }
    }
    private final class MapLit extends Expr {
        final List<String> keys; final List<Expr> vals;
        MapLit(int l, List<String> k, List<Expr> v) { super(l); keys = k; vals = v; }
        Object eval(Env e) { if (keys.size() > lim.maxCollection) throw new LimitError("map too large"); Map<Object, Object> m = new LinkedHashMap<>(); for (int i = 0; i < keys.size(); i++) m.put(keys.get(i), ev(vals.get(i), e)); return m; }
    }
    private final class FuncLit extends Expr {
        final List<String> params; final Stmt body;
        FuncLit(int l, List<String> p, Stmt b) { super(l); params = p; body = b; }
        Object eval(Env e) { return new Closure(params, body, e); }
    }
    private final class Unary extends Expr {
        final String op; final Expr x;
        Unary(int l, String op, Expr x) { super(l); this.op = op; this.x = x; }
        Object eval(Env e) { Object v = ev(x, e); return op.equals("!") ? (Object) !truthy(v) : (Object) (-num(v)); }
    }
    private final class Binary extends Expr {
        final String op; final Expr a, b;
        Binary(int l, String op, Expr a, Expr b) { super(l); this.op = op; this.a = a; this.b = b; }
        @SuppressWarnings("unchecked")
        Object eval(Env e) {
            if (op.equals("&&")) { Object l = ev(a, e); return truthy(l) ? ev(b, e) : l; }
            if (op.equals("||")) { Object l = ev(a, e); return truthy(l) ? l : ev(b, e); }
            Object l = ev(a, e), r = ev(b, e);
            switch (op) {
                case "+":
                    if (l instanceof Double && r instanceof Double) return (Double) l + (Double) r;
                    if (l instanceof String || r instanceof String) return checkStr(show(l) + show(r));
                    if (l instanceof List && r instanceof List) { List<Object> o = newList(((List<?>) l).size() + ((List<?>) r).size()); o.addAll((List<Object>) l); o.addAll((List<Object>) r); return o; }
                    throw err("cannot add " + show(l) + " and " + show(r));
                case "-": return num(l) - num(r);
                case "*": return num(l) * num(r);
                case "/": return num(l) / num(r);
                case "%": return num(l) % num(r);
                case "==": return equal(l, r);
                case "!=": return !equal(l, r);
                default:
                    if (l instanceof String && r instanceof String) {
                        int c = ((String) l).compareTo((String) r);
                        return op.equals("<") ? c < 0 : op.equals("<=") ? c <= 0 : op.equals(">") ? c > 0 : c >= 0;
                    }
                    double x = num(l), y = num(r);
                    return op.equals("<") ? x < y : op.equals("<=") ? x <= y : op.equals(">") ? x > y : x >= y;
            }
        }
    }
    private final class Index extends Expr {
        final Expr obj, idx; final String prop;
        Index(int l, Expr o, Expr i, String p) { super(l); obj = o; idx = i; prop = p; }
        @SuppressWarnings("unchecked")
        Object eval(Env e) {
            Object o = ev(obj, e);
            Object k = prop != null ? prop : ev(idx, e);
            if (o instanceof List) {
                if ("length".equals(k)) return (double) ((List<?>) o).size();
                if (!(k instanceof Double)) throw err("list index must be a number");
                int i = (int) (double) (Double) k;
                List<Object> l = (List<Object>) o;
                return i >= 0 && i < l.size() ? l.get(i) : null;
            }
            if (o instanceof Map) return ((Map<Object, Object>) o).get(k instanceof Double ? show(k) : k);
            if (o instanceof String) {
                if ("length".equals(k)) return (double) ((String) o).length();
                if (k instanceof Double) { int i = (int) (double) (Double) k; String s = (String) o; return i >= 0 && i < s.length() ? String.valueOf(s.charAt(i)) : null; }
            }
            if (o == null) throw err("cannot read " + (prop != null ? prop : "an index") + " of null");
            return null;
        }
    }
    private final class Call extends Expr {
        final Expr fn; final List<Expr> args;
        Call(int l, Expr f, List<Expr> a) { super(l); fn = f; args = a; }
        Object eval(Env e) {
            Object f = ev(fn, e);
            Object[] a = new Object[args.size()];
            for (int i = 0; i < a.length; i++) a[i] = ev(args.get(i), e);
            line = ln;
            return invoke(f, a);
        }
    }
    private final class Assign extends Expr {
        final Expr target, value;
        Assign(int l, Expr t, Expr v) { super(l); target = t; value = v; }
        @SuppressWarnings("unchecked")
        Object eval(Env e) {
            if (target instanceof Ident) {
                String n = ((Ident) target).n;
                Object v = ev(value, e);
                Env f = e.find(n);
                if (f == null) throw err("assignment to undeclared " + n + " (use var)");
                f.vars.put(n, v);
                return v;
            }
            Index t = (Index) target;
            Object o = ev(t.obj, e);
            Object k = t.prop != null ? t.prop : ev(t.idx, e);
            Object v = ev(value, e);
            if (o instanceof Map) {
                Map<Object, Object> m = (Map<Object, Object>) o;
                Object key = k instanceof Double ? show(k) : k;
                if (!(key instanceof String)) throw err("map keys are strings");
                if (!m.containsKey(key) && m.size() >= lim.maxCollection) throw new LimitError("map too large at line " + ln);
                m.put(key, v);
            } else if (o instanceof List && k instanceof Double) {
                List<Object> l = (List<Object>) o;
                int i = (int) (double) (Double) k;
                if (i < 0 || i > l.size() || i >= lim.maxCollection) throw err("list index out of range");
                if (i == l.size()) l.add(v); else l.set(i, v);
            } else throw err("cannot assign into " + show(o));
            return v;
        }
    }

    private final class VarDecl extends Stmt {
        final String n; final Expr init;
        VarDecl(int l, String n, Expr i) { super(l); this.n = n; init = i; }
        int exec(Env e) { line = ln; tick(); e.vars.put(n, init == null ? null : ev(init, e)); return NORMAL; }
    }
    private final class FuncDecl extends Stmt {
        final String n; final List<String> params; final Stmt body;
        FuncDecl(int l, String n, List<String> p, Stmt b) { super(l); this.n = n; params = p; body = b; }
        int exec(Env e) { tick(); e.vars.put(n, new Closure(params, body, e)); return NORMAL; }
    }
    private final class ExprStmt extends Stmt {
        final Expr x;
        ExprStmt(int l, Expr x) { super(l); this.x = x; }
        int exec(Env e) { ev(x, e); return NORMAL; }
    }
    private final class Block extends Stmt {
        final List<Stmt> body; final boolean scope;
        Block(int l, List<Stmt> b, boolean scope) { super(l); body = b; this.scope = scope; }
        int exec(Env e) { Env in = scope ? new Env(e) : e; for (Stmt s : body) { line = s.ln; tick(); int r = s.exec(in); if (r != NORMAL) return r; } return NORMAL; }
    }
    private final class If extends Stmt {
        final Expr c; final Stmt a, b;
        If(int l, Expr c, Stmt a, Stmt b) { super(l); this.c = c; this.a = a; this.b = b; }
        int exec(Env e) { if (truthy(ev(c, e))) return a.exec(e); return b == null ? NORMAL : b.exec(e); }
    }
    private final class While extends Stmt {
        final Expr c; final Stmt body;
        While(int l, Expr c, Stmt b) { super(l); this.c = c; body = b; }
        int exec(Env e) {
            while (truthy(ev(c, e))) { tick(); int r = body.exec(e); if (r == BREAK) break; if (r == RETURN) return r; }
            return NORMAL;
        }
    }
    private final class For extends Stmt {
        final Stmt init; final Expr cond, step; final Stmt body;
        For(int l, Stmt i, Expr c, Expr s, Stmt b) { super(l); init = i; cond = c; step = s; body = b; }
        int exec(Env e) {
            Env in = new Env(e);
            if (init != null) init.exec(in);
            while (cond == null || truthy(ev(cond, in))) {
                tick();
                int r = body.exec(in);
                if (r == BREAK) break;
                if (r == RETURN) return r;
                if (step != null) ev(step, in);
            }
            return NORMAL;
        }
    }
    private final class ForOf extends Stmt {
        final String n; final Expr list; final Stmt body;
        ForOf(int l, String n, Expr li, Stmt b) { super(l); this.n = n; list = li; body = b; }
        @SuppressWarnings("unchecked")
        int exec(Env e) {
            Object o = ev(list, e);
            List<Object> items;
            if (o instanceof List) items = new ArrayList<>((List<Object>) o);
            else if (o instanceof Map) items = new ArrayList<>(((Map<Object, Object>) o).keySet());
            else throw err("for-of needs a list or map");
            for (Object it : items) {
                tick();
                Env in = new Env(e);
                in.vars.put(n, it);
                int r = body.exec(in);
                if (r == BREAK) break;
                if (r == RETURN) return r;
            }
            return NORMAL;
        }
    }
    private final class Jump extends Stmt {
        final int kind; final Expr v;
        Jump(int l, int k, Expr v) { super(l); kind = k; this.v = v; }
        int exec(Env e) { if (kind == RETURN) returnValue = v == null ? null : ev(v, e); return kind; }
    }

    // ---- parser ------------------------------------------------------------------------------------------

    private static final class Tok {
        final char kind;          // n number, s string, i identifier, p punctuation, e end
        final String text; final double num; final int line;
        Tok(char k, String t, double n, int l) { kind = k; text = t; num = n; line = l; }
    }

    private final class Parser {
        final List<Tok> toks = new ArrayList<>();
        int p;

        Parser(String src) {
            int i = 0, ln = 1;
            while (i < src.length()) {
                char c = src.charAt(i);
                if (c == '\n') { ln++; i++; }
                else if (Character.isWhitespace(c)) i++;
                else if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') { while (i < src.length() && src.charAt(i) != '\n') i++; }
                else if (Character.isDigit(c)) {
                    int s = i;
                    while (i < src.length() && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '.')) i++;
                    try { toks.add(new Tok('n', null, Double.parseDouble(src.substring(s, i)), ln)); } catch (NumberFormatException e) { throw new ScriptError("bad number at line " + ln); }
                } else if (Character.isLetter(c) || c == '_') {
                    int s = i;
                    while (i < src.length() && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_')) i++;
                    toks.add(new Tok('i', src.substring(s, i), 0, ln));
                } else if (c == '"') {
                    StringBuilder b = new StringBuilder();
                    i++;
                    while (true) {
                        if (i >= src.length() || src.charAt(i) == '\n') throw new ScriptError("unterminated string at line " + ln);
                        char d = src.charAt(i++);
                        if (d == '"') break;
                        if (d == '\\' && i < src.length()) { char e = src.charAt(i++); b.append(e == 'n' ? '\n' : e == 't' ? '\t' : e); } else b.append(d);
                    }
                    toks.add(new Tok('s', b.toString(), 0, ln));
                } else {
                    String two = i + 1 < src.length() ? src.substring(i, i + 2) : "";
                    if (two.equals("==") || two.equals("!=") || two.equals("<=") || two.equals(">=") || two.equals("&&") || two.equals("||")) { toks.add(new Tok('p', two, 0, ln)); i += 2; }
                    else if ("+-*/%<>=!(){}[];,.:".indexOf(c) >= 0) { toks.add(new Tok('p', String.valueOf(c), 0, ln)); i++; }
                    else throw new ScriptError("unexpected '" + c + "' at line " + ln);
                }
            }
            toks.add(new Tok('e', "", 0, ln));
        }

        Tok peek() { return toks.get(p); }
        boolean is(String t) { Tok k = peek(); return (k.kind == 'p' || k.kind == 'i') && k.text.equals(t); }
        boolean eat(String t) { if (is(t)) { p++; return true; } return false; }
        void need(String t) { if (!eat(t)) throw new ScriptError("expected '" + t + "' at line " + peek().line + ", found '" + peek().text + "'"); }
        String ident() { Tok k = toks.get(p++); if (k.kind != 'i') throw new ScriptError("expected a name at line " + k.line); return k.text; }

        List<Stmt> parseProgram() { List<Stmt> out = new ArrayList<>(); while (peek().kind != 'e') out.add(stmt()); return out; }

        Stmt block() {
            int ln = peek().line;
            need("{");
            List<Stmt> b = new ArrayList<>();
            while (!is("}")) { if (peek().kind == 'e') throw new ScriptError("missing }"); b.add(stmt()); }
            need("}");
            return new Block(ln, b, true);
        }

        Stmt stmt() {
            int ln = peek().line;
            if (is("{")) return block();
            if (eat("var")) { String n = ident(); Expr i = eat("=") ? expr() : null; need(";"); return new VarDecl(ln, n, i); }
            if (is("function") && toks.get(p + 1).kind == 'i') { p++; String n = ident(); List<String> ps = params(); return new FuncDecl(ln, n, ps, block()); }
            if (eat("if")) { need("("); Expr c = expr(); need(")"); Stmt a = stmt(); Stmt b = eat("else") ? stmt() : null; return new If(ln, c, a, b); }
            if (eat("while")) { need("("); Expr c = expr(); need(")"); return new While(ln, c, stmt()); }
            if (eat("for")) {
                need("(");
                if (is("var") && toks.get(p + 2).text.equals("of")) { p++; String n = ident(); p++; Expr l = expr(); need(")"); return new ForOf(ln, n, l, stmt()); }
                Stmt init = null;
                if (eat("var")) { String n = ident(); Expr i = eat("=") ? expr() : null; init = new VarDecl(ln, n, i); } else if (!is(";")) init = new ExprStmt(ln, expr());
                need(";");
                Expr c = is(";") ? null : expr();
                need(";");
                Expr s = is(")") ? null : expr();
                need(")");
                return new For(ln, init, c, s, stmt());
            }
            if (eat("return")) { Expr v = is(";") ? null : expr(); need(";"); return new Jump(ln, RETURN, v); }
            if (eat("break")) { need(";"); return new Jump(ln, BREAK, null); }
            if (eat("continue")) { need(";"); return new Jump(ln, CONTINUE, null); }
            Expr x = expr();
            need(";");
            return new ExprStmt(ln, x);
        }

        List<String> params() { need("("); List<String> ps = new ArrayList<>(); if (!is(")")) do { ps.add(ident()); } while (eat(",")); need(")"); return ps; }

        Expr expr() {
            int ln = peek().line;
            Expr l = or();
            if (eat("=")) {
                if (!(l instanceof Ident || l instanceof Index)) throw new ScriptError("cannot assign to that at line " + ln);
                return new Assign(ln, l, expr());
            }
            return l;
        }

        Expr or() { Expr l = and(); while (is("||")) { int ln = peek().line; p++; l = new Binary(ln, "||", l, and()); } return l; }
        Expr and() { Expr l = eq(); while (is("&&")) { int ln = peek().line; p++; l = new Binary(ln, "&&", l, eq()); } return l; }
        Expr eq() { Expr l = rel(); while (is("==") || is("!=")) { int ln = peek().line; String o = toks.get(p++).text; l = new Binary(ln, o, l, rel()); } return l; }
        Expr rel() { Expr l = add(); while (is("<") || is("<=") || is(">") || is(">=")) { int ln = peek().line; String o = toks.get(p++).text; l = new Binary(ln, o, l, add()); } return l; }
        Expr add() { Expr l = mul(); while (is("+") || is("-")) { int ln = peek().line; String o = toks.get(p++).text; l = new Binary(ln, o, l, mul()); } return l; }
        Expr mul() { Expr l = unary(); while (is("*") || is("/") || is("%")) { int ln = peek().line; String o = toks.get(p++).text; l = new Binary(ln, o, l, unary()); } return l; }
        Expr unary() { int ln = peek().line; if (eat("!")) return new Unary(ln, "!", unary()); if (eat("-")) return new Unary(ln, "-", unary()); return postfix(); }

        Expr postfix() {
            Expr x = primary();
            while (true) {
                int ln = peek().line;
                if (eat("(")) { List<Expr> a = new ArrayList<>(); if (!is(")")) do { a.add(expr()); } while (eat(",")); need(")"); x = new Call(ln, x, a); }
                else if (eat("[")) { Expr i = expr(); need("]"); x = new Index(ln, x, i, null); }
                else if (eat(".")) x = new Index(ln, x, null, ident());
                else return x;
            }
        }

        Expr primary() {
            Tok k = toks.get(p++);
            int ln = k.line;
            if (k.kind == 'n') return new Lit(ln, k.num);
            if (k.kind == 's') return new Lit(ln, k.text);
            if (k.kind == 'i') {
                switch (k.text) {
                    case "true": return new Lit(ln, Boolean.TRUE);
                    case "false": return new Lit(ln, Boolean.FALSE);
                    case "null": return new Lit(ln, null);
                    case "function": { List<String> ps = params(); return new FuncLit(ln, ps, block()); }
                    default: return new Ident(ln, k.text);
                }
            }
            if (k.text.equals("(")) { Expr x = expr(); need(")"); return x; }
            if (k.text.equals("[")) { List<Expr> items = new ArrayList<>(); if (!is("]")) do { items.add(expr()); } while (eat(",")); need("]"); return new ListLit(ln, items); }
            if (k.text.equals("{")) {
                List<String> keys = new ArrayList<>(); List<Expr> vals = new ArrayList<>();
                if (!is("}")) do {
                    Tok kt = toks.get(p++);
                    if (kt.kind != 'i' && kt.kind != 's') throw new ScriptError("bad map key at line " + kt.line);
                    need(":");
                    keys.add(kt.text); vals.add(expr());
                } while (eat(","));
                need("}");
                return new MapLit(ln, keys, vals);
            }
            throw new ScriptError("unexpected '" + k.text + "' at line " + ln);
        }
    }
}

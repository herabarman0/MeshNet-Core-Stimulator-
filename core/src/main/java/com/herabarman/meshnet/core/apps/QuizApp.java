package com.herabarman.meshnet.core.apps;

import com.herabarman.meshnet.core.MiniApp;
import com.herabarman.meshnet.core.MiniAppContext;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Example mini-app: an offline quiz. One phone is the quizmaster and asks; everybody within a few hops sees the question and
 * answers; the quizmaster collects the answers and floods the scoreboard. About 150 lines, no networking code of its own.
 */
public final class QuizApp implements MiniApp {
    public static final String ID = "com.herasoft.mesh.quiz";
    private static final int Q = 1, A = 2, R = 3;

    public interface Listener {
        void onQuestion(int quizmaster, int round, String question, String[] choices);
        void onResults(int round, int correctIndex, Map<Integer, Integer> scores);
    }

    private MiniAppContext ctx;
    private final Listener listener;
    private final Map<Integer, Integer> scores = new LinkedHashMap<>();       // quizmaster side: node -> points
    private final Map<Integer, Integer> answers = new HashMap<>();
    private int round, correct = -1;

    public QuizApp(Listener l) { this.listener = l; }

    @Override public String id() { return ID; }
    @Override public int version() { return 1; }
    @Override public void onStart(MiniAppContext c) { ctx = c; }
    @Override public void onStop() { }
    @Override public void onPeer(int node, boolean up) { }

    /** Quizmaster: ask a question; results go out after durationMs. */
    public boolean ask(String question, String[] choices, int correctIndex, long durationMs) {
        round++;
        correct = correctIndex;
        answers.clear();
        ByteBuffer b = ByteBuffer.allocate(480).put((byte) Q).putInt(ctx.selfId()).putInt(round);
        put(b, question);
        b.put((byte) choices.length);
        for (String c : choices) put(b, c);
        byte[] out = new byte[b.position()];
        System.arraycopy(b.array(), 0, out, 0, out.length);
        if (!ctx.floodNearby(out, 6)) return false;
        deliverQuestion(ctx.selfId(), round, question, choices);
        final int thisRound = round;
        ctx.schedule(durationMs, new Runnable() { @Override public void run() { finish(thisRound); } });
        return true;
    }

    /** Player: answer the current question. */
    public boolean answer(int quizmaster, int round, int choice) {
        return ctx.sendTo(quizmaster, ByteBuffer.allocate(6).put((byte) A).putInt(round).put((byte) choice).array());
    }

    private void finish(int r) {
        if (r != round) return;
        for (Map.Entry<Integer, Integer> e : answers.entrySet()) if (e.getValue() == correct) { Integer s = scores.get(e.getKey()); scores.put(e.getKey(), s == null ? 1 : s + 1); }
        ByteBuffer b = ByteBuffer.allocate(6 + 1 + Math.min(scores.size(), 30) * 5).put((byte) R).putInt(r).put((byte) correct);
        b.put((byte) Math.min(scores.size(), 30));
        int n = 0;
        for (Map.Entry<Integer, Integer> e : scores.entrySet()) { if (n++ >= 30) break; b.putInt(e.getKey()).put((byte) Math.min(255, e.getValue())); }
        byte[] out = new byte[b.position()];
        System.arraycopy(b.array(), 0, out, 0, out.length);
        ctx.floodNearby(out, 6);
        if (listener != null) listener.onResults(r, correct, new LinkedHashMap<>(scores));
    }

    @Override public void onMessage(int from, byte[] d) {                      // an answer, at the quizmaster
        if (d.length == 6 && d[0] == A && ByteBuffer.wrap(d, 1, 4).getInt() == round) answers.put(from, d[5] & 0xFF);
    }

    @Override public void onNearby(int from, byte[] d) {
        ByteBuffer b = ByteBuffer.wrap(d);
        int type = b.get();
        if (type == Q) {
            int master = b.getInt(), r = b.getInt();
            String q = get(b);
            String[] ch = new String[b.get() & 0xFF];
            for (int i = 0; i < ch.length; i++) ch[i] = get(b);
            deliverQuestion(master, r, q, ch);
        } else if (type == R && listener != null) {
            int r = b.getInt(), c = b.get();
            int n = b.get() & 0xFF;
            Map<Integer, Integer> s = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) s.put(b.getInt(), b.get() & 0xFF);
            listener.onResults(r, c, s);
        }
    }

    private void deliverQuestion(int from, int r, String q, String[] ch) { if (listener != null) listener.onQuestion(from, r, q, ch); }

    private static void put(ByteBuffer b, String s) { byte[] x = s.getBytes(StandardCharsets.UTF_8); b.put((byte) Math.min(255, x.length)).put(x, 0, Math.min(255, x.length)); }

    private static String get(ByteBuffer b) { byte[] x = new byte[b.get() & 0xFF]; b.get(x); return new String(x, StandardCharsets.UTF_8); }
}

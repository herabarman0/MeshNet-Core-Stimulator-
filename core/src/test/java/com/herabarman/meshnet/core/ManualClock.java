package com.herabarman.meshnet.core;

import java.util.PriorityQueue;

/** A clock the test moves by hand. */
final class ManualClock implements Clock {
    private static final class Event implements Comparable<Event> {
        final long time, seq;
        final Runnable task;
        Event(long time, long seq, Runnable task) { this.time = time; this.seq = seq; this.task = task; }
        @Override public int compareTo(Event o) { int c = Long.compare(time, o.time); return c != 0 ? c : Long.compare(seq, o.seq); }
    }

    private final PriorityQueue<Event> queue = new PriorityQueue<>();
    private long now;
    private long seq;

    @Override public long nowMs() { return now; }
    @Override public void schedule(long delayMs, Runnable task) { queue.add(new Event(now + Math.max(0, delayMs), seq++, task)); }

    void advance(long ms) {
        long end = now + ms;
        while (!queue.isEmpty() && queue.peek().time <= end) {
            Event e = queue.poll();
            now = e.time;
            e.task.run();
        }
        now = end;
    }
}

package com.herabarman.meshnet.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Congestion control for the radio: a token bucket with a priority queue in front of it.
 *
 *  - Within the rate every packet goes out at once.
 *  - Over the rate packets wait; the highest priority leaves first (ACKs and control, then text, then bulk).
 *  - Bulk packets (priority below bulkBelow) may not eat into a reserve of the burst, so text always has headroom.
 *  - When the queue is full the lowest priority is dropped; packets that wait too long are dropped
 *    (the sender's ACK retry takes over).
 *  - jitterFactor() grows while the queue is backing up and decays when it is empty: the engine uses it to
 *    spread floods out (backpressure, AIMD).
 * rate <= 0 switches the Pacer off: everything passes through.
 */
public final class Pacer {
    public interface Sink {
        void transmit(int peer, byte[] data);
    }

    private static final class Item {
        final int peer;
        final byte[] data;
        final int priority;
        final long enqueuedAt;
        final long seq;
        final long maxWait;

        Item(int peer, byte[] data, int priority, long enqueuedAt, long seq, long maxWait) {
            this.peer = peer;
            this.data = data;
            this.priority = priority;
            this.enqueuedAt = enqueuedAt;
            this.seq = seq;
            this.maxWait = maxWait;
        }
    }

    private final Clock clock;
    private final Sink sink;
    private final double rate;
    private final double burst;
    private final int queueMax;
    private final int bulkBelow;
    private final double bulkReserve;
    private final long maxWaitMs;
    private final double maxFactor;
    private final PriorityQueue<Item> queue = new PriorityQueue<>(16, new java.util.Comparator<Item>() {
        @Override
        public int compare(Item a, Item b) {
            int c = Integer.compare(b.priority, a.priority);
            return c != 0 ? c : Long.compare(a.seq, b.seq);
        }
    });
    private double tokens;
    private long lastRefill;
    private boolean timerScheduled;
    private double factor = 1.0;
    private long factorStamp;
    private long seq;
    private int sosFrom = Priority.SOS;
    private int sosDebt = 0;
    private double bulkShare = 0;
    private int sinceBulk;
    private boolean pickedFloor;
    private long sosSent;
    private long bulkFloorSent;
    private long sentCount;
    private long queuedCount;
    private long droppedCount;
    private long expiredCount;

    public Pacer(Clock clock, Sink sink, double ratePerSec, int burst, int queueMax, int bulkBelow,
                 double bulkReserve, long maxWaitMs, double maxJitterFactor) {
        this.clock = clock;
        this.sink = sink;
        this.rate = ratePerSec;
        this.burst = Math.max(1, burst);
        this.queueMax = queueMax;
        this.bulkBelow = bulkBelow;
        this.bulkReserve = bulkReserve;
        this.maxWaitMs = maxWaitMs;
        this.maxFactor = Math.max(1.0, maxJitterFactor);
        this.tokens = this.burst;
        this.lastRefill = clock.nowMs();
        this.factorStamp = this.lastRefill;
    }

    /**
     * Emergency lane: packets with at least this priority go out at once even when the bucket is empty, spending up
     * to debt tokens beyond zero (the bucket then refills more slowly for everyone else, which is the price of an alarm).
     */
    public void setSosLane(int fromPriority, int debt) {
        this.sosFrom = fromPriority;
        this.sosDebt = Math.max(0, debt);
    }

    /** While packets wait, at least this share of the transmissions go to the oldest bulk packet (0 = strict priority). */
    public void setBulkMinShare(double share) {
        this.bulkShare = Math.max(0, Math.min(0.5, share));
    }

    public boolean enabled() {
        return rate > 0;
    }

    public void send(int peer, byte[] data, int priority) {
        send(peer, data, priority, maxWaitMs);
    }

    /**
     * Like send(), with its own patience: a queued packet older than maxWait is dropped instead of sent late.
     * Real-time voice uses a few hundred ms here (a late voice frame is worse than none).
     */
    public void send(int peer, byte[] data, int priority, long maxWait) {
        if (rate <= 0) {
            sink.transmit(peer, data);
            sentCount++;
            return;
        }
        refill();
        if (priority >= sosFrom && tokens > -sosDebt) {                       // emergency lane: never waits for the budget
            tokens -= 1;
            sink.transmit(peer, data);
            sentCount++;
            sosSent++;
            return;
        }
        if (canSpend(priority) && (queue.isEmpty() || queue.peek().priority < priority)) {   // outranks everything waiting: no queueing
            tokens -= 1;
            sink.transmit(peer, data);
            sentCount++;
            return;
        }
        if (queue.size() >= queueMax) {
            Item worst = null;
            for (Item it : queue) {
                if (worst == null || it.priority < worst.priority || (it.priority == worst.priority && it.seq < worst.seq)) worst = it;
            }
            if (worst != null && worst.priority < priority) {
                queue.remove(worst);
                droppedCount++;
            } else {
                droppedCount++;
                raiseFactor();
                return;
            }
        }
        queue.add(new Item(peer, data, priority, clock.nowMs(), seq++, maxWait));
        queuedCount++;
        raiseFactor();                                 // we are over budget: tell the engine to slow its floods
        scheduleDrain();
    }

    /** The extra jitter fades with time (half of it every 2 s), whether or not any timer is running. */
    private void decayFactor() {
        long now = clock.nowMs();
        if (now > factorStamp) {
            factor = 1.0 + (factor - 1.0) * Math.pow(0.5, (now - factorStamp) / 2000.0);
            factorStamp = now;
        }
    }

    private void raiseFactor() {
        decayFactor();
        factor = Math.min(maxFactor, factor * 1.15);
    }

    private boolean canSpend(int priority) {
        if (tokens < 1) return false;
        return priority >= bulkBelow || tokens - 1 >= burst * bulkReserve;
    }

    private void refill() {
        long now = clock.nowMs();
        if (now > lastRefill) {
            tokens = Math.min(burst, tokens + (now - lastRefill) * rate / 1000.0);
            lastRefill = now;
        }
    }

    private void scheduleDrain() {
        if (timerScheduled || queue.isEmpty()) return;
        timerScheduled = true;
        double needed = queue.peek().priority >= bulkBelow ? 1.0 : burst * bulkReserve + 1.0;
        long delay = Math.max(1, (long) Math.ceil((needed - tokens) * 1000.0 / rate));
        clock.schedule(delay, new Runnable() {
            @Override
            public void run() {
                timerScheduled = false;
                drain();
            }
        });
    }

    private void drain() {
        refill();
        long now = clock.nowMs();
        Iterator<Item> it = queue.iterator();
        List<Item> stale = new ArrayList<>();
        while (it.hasNext()) {
            Item item = it.next();
            if (now - item.enqueuedAt > item.maxWait) stale.add(item);
        }
        for (Item item : stale) {
            queue.remove(item);
            expiredCount++;
        }
        while (!queue.isEmpty()) {
            Item item = nextToSend();
            if (item == null || !(pickedFloor ? tokens >= 1 : canSpend(item.priority))) break;
            queue.remove(item);
            tokens -= 1;
            sink.transmit(item.peer, item.data);
            sentCount++;
            if (item.priority < bulkBelow) sinceBulk = 0; else sinceBulk++;
            if (pickedFloor) bulkFloorSent++;
        }
        if (!queue.isEmpty()) scheduleDrain();
    }

    /**
     * The packet to send next: the highest priority, except that after every few non-bulk packets the oldest bulk
     * packet goes first (it may use the reserved tokens), so a steady stream of text cannot hold files back for ever.
     */
    private Item nextToSend() {
        pickedFloor = false;
        Item top = queue.peek();
        if (top == null) return null;
        if (bulkShare > 0 && top.priority >= bulkBelow && sinceBulk >= Math.round(1.0 / bulkShare) - 1) {
            Item oldest = null;
            for (Item it : queue) {
                if (it.priority < bulkBelow && (oldest == null || it.seq < oldest.seq)) oldest = it;
            }
            if (oldest != null) {
                pickedFloor = true;
                return oldest;
            }
        }
        return top;
    }

    /** 1.0 when the radio is keeping up, up to maxJitterFactor while packets are backing up. */
    public double jitterFactor() {
        decayFactor();
        return factor;
    }

    /** True while the queue holds a real backlog: the engine sheds bulk floods then. */
    public boolean congested() {
        decayFactor();
        return rate > 0 && (queue.size() > Math.max(1, queueMax / 4) || factor > 1.5);
    }

    public int queued() { return queue.size(); }
    public long sent() { return sentCount; }
    public long wasQueued() { return queuedCount; }
    public long dropped() { return droppedCount; }
    public long expired() { return expiredCount; }
    public long sosSent() { return sosSent; }
    /** Transmissions that went to bulk ahead of its turn because of the guaranteed share. */
    public long bulkFloorSent() { return bulkFloorSent; }
}

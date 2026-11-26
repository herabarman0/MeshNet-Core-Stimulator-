package com.herabarman.meshnet.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PacerTest {
    private final ManualClock clock = new ManualClock();
    private final List<Integer> sent = new ArrayList<>();   // peer ids in the order they hit the air

    private Pacer pacer(double rate, int burst, int queueMax, long maxWaitMs) {
        return new Pacer(clock, new Pacer.Sink() {
            @Override public void transmit(int peer, byte[] data) { sent.add(peer); }
        }, rate, burst, queueMax, 100, 0.3, maxWaitMs, 4.0);
    }

    @Test
    void rateZeroMeansNoPacingAtAll() {
        Pacer p = pacer(0, 10, 10, 1000);
        assertFalse(p.enabled());
        for (int i = 0; i < 500; i++) p.send(i, new byte[1], 20);
        assertEquals(500, sent.size());
        assertEquals(0, p.queued());
    }

    @Test
    void aBurstGoesOutAtOnceTheRestIsPacedAtTheRate() {
        Pacer p = pacer(10, 10, 100, 60_000);
        for (int i = 0; i < 30; i++) p.send(i, new byte[1], 150);
        assertEquals(10, sent.size());        // the burst
        assertEquals(20, p.queued());
        clock.advance(1000);
        assertEquals(20, sent.size());        // 10 packets per second after that
        clock.advance(1000);
        assertEquals(30, sent.size());
        assertEquals(0, p.queued());
    }

    @Test
    void theHighestPriorityLeavesFirst() {
        Pacer p = pacer(10, 10, 100, 60_000);
        for (int i = 0; i < 10; i++) p.send(i, new byte[1], 150);    // use up the burst
        sent.clear();
        p.send(1001, new byte[1], 120);
        p.send(1002, new byte[1], 250);
        p.send(1003, new byte[1], 150);
        clock.advance(400);
        assertEquals(List.of(1002, 1003, 1001), sent);
    }

    @Test
    void bulkCannotEatTheReserveSoTextStillGoesStraightOut() {
        Pacer p = pacer(10, 10, 100, 60_000);
        for (int i = 0; i < 10; i++) p.send(i, new byte[1], 20);      // ten bulk packets
        assertEquals(7, sent.size());                                  // only down to the reserve (30% of 10)
        assertEquals(3, p.queued());
        sent.clear();
        p.send(999, new byte[1], 150);                                 // interactive: outranks the queue, goes at once
        assertEquals(List.of(999), sent);
    }

    @Test
    void aFullQueueDropsTheLowestPriorityAndRefusesWorseNewcomers() {
        Pacer p = pacer(1, 1, 3, 600_000);
        p.send(1, new byte[1], 150);                                   // uses the only token
        sent.clear();
        p.send(110, new byte[1], 110);
        p.send(120, new byte[1], 120);
        p.send(130, new byte[1], 130);                                 // queue is full now
        p.send(200, new byte[1], 200);                                 // evicts 110
        assertEquals(1, p.dropped());
        p.send(105, new byte[1], 105);                                 // worse than everything queued: refused
        assertEquals(2, p.dropped());
        clock.advance(10_000);
        assertEquals(List.of(200, 130, 120), sent);
    }

    @Test
    void packetsThatWaitTooLongAreDropped() {
        Pacer p = pacer(0.1, 1, 10, 5_000);                            // one token per 10 s, patience 5 s
        p.send(1, new byte[1], 150);
        p.send(2, new byte[1], 150);                                   // waits for a token that comes after its patience ran out
        clock.advance(30_000);
        assertEquals(List.of(1), sent);
        assertEquals(1, p.expired());
    }

    @Test
    void backlogRaisesTheJitterFactorAndItDecaysWhenTheQueueIsEmpty() {
        Pacer p = pacer(10, 10, 20, 60_000);
        assertEquals(1.0, p.jitterFactor(), 0.0001);
        assertFalse(p.congested());
        for (int i = 0; i < 60; i++) p.send(i, new byte[1], 150);
        assertTrue(p.congested());
        assertTrue(p.jitterFactor() > 2.0, "factor " + p.jitterFactor());
        assertTrue(p.jitterFactor() <= 4.0);
        clock.advance(20_000);
        assertEquals(0, p.queued());
        assertTrue(p.jitterFactor() < 1.3, "factor " + p.jitterFactor());
        assertFalse(p.congested());
    }
}

package com.herabarman.meshnet.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class PriorityTest {
    private final ManualClock clock = new ManualClock();
    private final List<Integer> sent = new ArrayList<>();   // peer ids in the order they hit the air

    private Pacer pacer(double rate, int burst, int queueMax) {
        return pacer(clock, rate, burst, queueMax);
    }

    private Pacer pacer(ManualClock clock, double rate, int burst, int queueMax) {
        return new Pacer(clock, new Pacer.Sink() {
            @Override public void transmit(int peer, byte[] data) { sent.add(peer); }
        }, rate, burst, queueMax, 100, 0.3, 600_000, 4.0);
    }

    private void emptyTheBucket(Pacer p) {
        for (int i = 0; i < 10; i++) p.send(i, new byte[1], Priority.NORMAL);
        sent.clear();
    }

    @Test
    void levelsAreOrderedAndNamed() {
        assertTrue(Priority.CONTROL > Priority.SOS && Priority.SOS > Priority.VOICE && Priority.VOICE > Priority.URGENT
                && Priority.URGENT > Priority.NORMAL && Priority.NORMAL > Priority.BULK && Priority.BULK > Priority.BACKGROUND);
        assertTrue(Priority.NORMAL >= 100 && Priority.BULK < 100, "bulk is below the bulk line, chat text above it");
        assertEquals("sos", Priority.name(Priority.SOS));
        assertEquals("normal", Priority.name(Priority.NORMAL));
        assertEquals("bulk", Priority.name(Priority.BULK));
        assertEquals("background", Priority.name(Priority.BACKGROUND));
        assertEquals("control", Priority.name(255));
    }

    @Test
    void everyLevelLeavesBeforeTheOnesBelowIt() {
        Pacer p = pacer(10, 10, 100);
        emptyTheBucket(p);
        int[] reverse = {Priority.BACKGROUND, Priority.BULK, Priority.NORMAL, Priority.URGENT, Priority.VOICE, Priority.SOS};
        for (int pr : reverse) p.send(pr, new byte[1], pr);
        clock.advance(10_000);
        assertEquals(Arrays.asList(Priority.SOS, Priority.VOICE, Priority.URGENT, Priority.NORMAL, Priority.BULK, Priority.BACKGROUND), sent);
    }

    @Test
    void anSosGoesOutAtOnceEvenWhenTheRadioBudgetIsSpent() {
        Pacer p = pacer(10, 10, 100);
        p.setSosLane(Priority.SOS, 5);
        emptyTheBucket(p);
        for (int i = 0; i < 5; i++) p.send(100 + i, new byte[1], Priority.NORMAL);   // waiting
        assertEquals(5, p.queued());
        p.send(999, new byte[1], Priority.SOS);
        assertEquals(Arrays.asList(999), sent, "the alarm did not wait for a free slot");
        assertEquals(1, p.sosSent());
        assertEquals(5, p.queued(), "ordinary packets still wait their turn");
        p.send(998, new byte[1], Priority.NORMAL);
        assertEquals(1, sent.size(), "a text sent now still waits: only the alarm has the lane");
    }

    @Test
    void theSosLaneIsLimitedByItsDebtThenTheAlarmLeavesFirstInTheQueue() {
        Pacer p = pacer(10, 10, 100);
        p.setSosLane(Priority.SOS, 3);
        emptyTheBucket(p);
        for (int i = 0; i < 4; i++) p.send(100 + i, new byte[1], Priority.NORMAL);
        for (int i = 0; i < 5; i++) p.send(900 + i, new byte[1], Priority.SOS);
        assertEquals(Arrays.asList(900, 901, 902), sent, "three alarms used the lane");
        clock.advance(1000);                                                          // debt repaid, then tokens flow
        assertEquals(903, (int) sent.get(3), "the other two alarms are first in line");
        assertEquals(904, (int) sent.get(4));
    }

    @Test
    void anSosIsNeverPushedOutOfAFullQueueByText() {
        Pacer p = pacer(10, 10, 4);
        p.setSosLane(Priority.SOS, 0);                                               // no lane: the alarm has to queue
        emptyTheBucket(p);
        p.send(900, new byte[1], Priority.SOS);
        for (int i = 0; i < 10; i++) p.send(100 + i, new byte[1], Priority.URGENT);   // more than the queue holds
        clock.advance(10_000);
        assertTrue(sent.contains(900), "the alarm is still sent");
        assertEquals(900, (int) sent.get(0), "and first");
    }

    @Test
    void strictPriorityLetsTextHoldBulkBackButTheGuaranteedShareDoesNot() {
        for (int mode = 0; mode < 2; mode++) {
            sent.clear();
            ManualClock clock = new ManualClock();                                  // a clock of its own: the first pacer must not keep running
            Pacer p = pacer(clock, 10, 10, 1000);
            p.setBulkMinShare(mode == 0 ? 0 : 0.1);
            emptyTheBucket(p);
            for (int i = 0; i < 30; i++) p.send(5000 + i, new byte[1], Priority.BULK);
            for (int i = 0; i < 300; i++) p.send(i, new byte[1], Priority.NORMAL);
            clock.advance(5_000);                                                    // about 50 transmissions
            int bulk = 0;
            for (int peer : sent) if (peer >= 5000) bulk++;
            assertTrue(sent.size() >= 45 && sent.size() <= 55, "sent " + sent.size());
            if (mode == 0) assertEquals(0, bulk, "strict priority: the files wait for ever behind a text flood");
            else assertTrue(bulk >= 3 && bulk <= 8, "guaranteed share: " + bulk + " of " + sent.size() + " went to bulk");
            if (mode == 1) assertTrue(p.bulkFloorSent() >= 3);
        }
    }

    @Test
    void theGuaranteedShareNeverStealsFromAnAlarm() {
        Pacer p = pacer(10, 10, 1000);
        p.setBulkMinShare(0.5);
        p.setSosLane(Priority.SOS, 5);
        emptyTheBucket(p);
        for (int i = 0; i < 20; i++) p.send(5000 + i, new byte[1], Priority.BULK);
        for (int i = 0; i < 20; i++) p.send(i, new byte[1], Priority.NORMAL);
        sent.clear();
        p.send(777, new byte[1], Priority.SOS);
        assertEquals(Arrays.asList(777), sent);
    }
}

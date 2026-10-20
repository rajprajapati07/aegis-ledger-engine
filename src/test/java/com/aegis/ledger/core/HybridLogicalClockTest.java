package com.aegis.ledger.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridLogicalClockTest {

    private final AtomicLong wall = new AtomicLong(1_700_000_000_000L);
    private final HybridLogicalClock clock = new HybridLogicalClock(wall::get, 500);

    @Test
    void strictlyIncreasesEvenWhenWallClockIsFrozen() {
        long a = clock.now();
        long b = clock.now();
        long c = clock.now();
        assertTrue(a < b && b < c);
        assertEquals(HybridLogicalClock.physical(a), HybridLogicalClock.physical(c));
        assertEquals(2, HybridLogicalClock.logical(c));
    }

    @Test
    void neverGoesBackwardsWhenWallClockDoes() {
        long before = clock.now();
        wall.addAndGet(-10_000); // NTP step backwards
        long after = clock.now();
        assertTrue(after > before);
    }

    @Test
    void picksUpWallClockAgainOnceItMovesPast() {
        clock.now();
        clock.now();
        wall.addAndGet(5);
        long t = clock.now();
        assertEquals(wall.get(), HybridLogicalClock.physical(t));
        assertEquals(0, HybridLogicalClock.logical(t));
    }

    @Test
    void updateOrdersAfterTheRemoteEvent() {
        long local = clock.now();
        // another node, 200 ms ahead of us (within the allowed offset)
        long remote = HybridLogicalClock.pack(wall.get() + 200, 7);
        long merged = clock.update(remote);
        assertTrue(merged > remote);
        assertTrue(merged > local);
        assertEquals(8, HybridLogicalClock.logical(merged));
        // and local events after that keep going up from the remote's time
        assertTrue(clock.now() > merged);
    }

    @Test
    void updateWithAnOlderRemoteStillMovesForward() {
        long local = clock.now();
        long merged = clock.update(HybridLogicalClock.pack(wall.get() - 1_000, 3));
        assertTrue(merged > local);
    }

    @Test
    void refusesARemoteClockFarInTheFuture() {
        // A node whose clock is a year ahead. Accepting this would drag every
        // timestamp in the cluster a year forward, permanently.
        long yearAhead = HybridLogicalClock.pack(wall.get() + 365L * 24 * 3600 * 1000, 0);
        var e = assertThrows(LedgerException.class, () -> clock.update(yearAhead));
        assertEquals(ErrorCode.CLOCK_SKEW, e.code());

        // and the clock itself wasn't poisoned by the attempt
        assertTrue(HybridLogicalClock.physical(clock.now()) <= wall.get());
    }

    @Test
    void logicalCounterOverflowBorrowsTheNextMillisecond() {
        long full = HybridLogicalClock.pack(wall.get(), HybridLogicalClock.LOGICAL_MASK);
        long next = clock.update(full);
        assertTrue(next > full);
        assertEquals(wall.get() + 1, HybridLogicalClock.physical(next));
    }

    @Test
    void zeroMeansNoPriorTimestamp() {
        long t = clock.update(0);
        assertEquals(wall.get(), HybridLogicalClock.physical(t));
    }
}

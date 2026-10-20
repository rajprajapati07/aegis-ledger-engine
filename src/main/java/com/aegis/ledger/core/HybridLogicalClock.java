package com.aegis.ledger.core;

import java.util.function.LongSupplier;

/**
 * Hybrid Logical Clock (Kulkarni et al.), packed into one long:
 * the top 48 bits are wall-clock milliseconds, the bottom 16 a logical counter.
 *
 * Packing it this way keeps timestamps comparable as plain longs and lets
 * them sit in a BIGINT column with a normal index.
 *
 * The max-offset guard matters more than it looks. An HLC adopts the largest
 * physical time it has ever seen, so one node with a clock set far ahead would
 * drag every other node's timestamps forward and they'd never come back. We'd
 * rather refuse the write and page someone.
 */
public final class HybridLogicalClock {

    static final int LOGICAL_BITS = 16;
    static final long LOGICAL_MASK = (1L << LOGICAL_BITS) - 1;

    private final LongSupplier wallClockMillis;
    private final long maxOffsetMillis;
    private long last;

    public HybridLogicalClock(LongSupplier wallClockMillis, long maxOffsetMillis) {
        this.wallClockMillis = wallClockMillis;
        this.maxOffsetMillis = maxOffsetMillis;
    }

    public static HybridLogicalClock system(long maxOffsetMillis) {
        return new HybridLogicalClock(System::currentTimeMillis, maxOffsetMillis);
    }

    /** Timestamp for a local event. Always strictly greater than the previous one. */
    public synchronized long now() {
        long pt = wallClockMillis.getAsLong();
        if (pt > physical(last)) {
            last = pack(pt, 0);
        } else {
            last = pack(physical(last), logical(last) + 1);
        }
        return last;
    }

    /**
     * Timestamp for an event that causally follows {@code remote}, e.g. a
     * write after reading a row another node stamped. The result is greater
     * than both {@code remote} and anything this clock has issued before.
     */
    public synchronized long update(long remote) {
        if (remote <= 0) {
            return now();
        }
        long pt = wallClockMillis.getAsLong();
        long remotePt = physical(remote);
        if (remotePt - pt > maxOffsetMillis) {
            throw new LedgerException(ErrorCode.CLOCK_SKEW,
                    "remote HLC is %d ms ahead of local wall clock (limit %d ms)"
                            .formatted(remotePt - pt, maxOffsetMillis));
        }

        long localPt = physical(last);
        long maxPt = Math.max(pt, Math.max(localPt, remotePt));
        long counter;
        if (maxPt == localPt && maxPt == remotePt) {
            counter = Math.max(logical(last), logical(remote)) + 1;
        } else if (maxPt == localPt) {
            counter = logical(last) + 1;
        } else if (maxPt == remotePt) {
            counter = logical(remote) + 1;
        } else {
            counter = 0;
        }
        last = pack(maxPt, counter);
        return last;
    }

    public static long physical(long hlc) {
        return hlc >>> LOGICAL_BITS;
    }

    public static long logical(long hlc) {
        return hlc & LOGICAL_MASK;
    }

    static long pack(long physicalMillis, long counter) {
        // 65k events in one millisecond on one node is unlikely, but if it
        // happens we borrow the next millisecond rather than wrap the counter.
        if (counter > LOGICAL_MASK) {
            return (physicalMillis + 1) << LOGICAL_BITS;
        }
        return (physicalMillis << LOGICAL_BITS) | counter;
    }
}

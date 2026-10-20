package com.aegis.ledger.store;

import com.aegis.ledger.core.AccountType;
import com.aegis.ledger.core.Direction;
import com.aegis.ledger.core.EntryRequest;
import com.aegis.ledger.core.ErrorCode;
import com.aegis.ledger.core.HybridLogicalClock;
import com.aegis.ledger.core.LedgerException;
import com.aegis.ledger.core.PostedTransaction;
import com.aegis.ledger.core.PostingRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.aegis.ledger.core.Direction.CREDIT;
import static com.aegis.ledger.core.Direction.DEBIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tests that matter most. Single-threaded tests can't catch a missing
 * lock; these throw real concurrent load at real Postgres and then check the
 * invariants that must hold no matter how the threads interleaved.
 */
class ConcurrencyIT {

    private ThreadBoundDataSource ds;
    private LedgerStore store;
    private String ledger;
    private String reserves;

    @BeforeEach
    void setUp() {
        ds = new ThreadBoundDataSource(TestDatabase.freshSchema());
        store = new LedgerStore(ds);
        ledger = store.createLedger("LOAD_TEST", "USD").ledgerId();
        reserves = store.createAccount(ledger, AccountType.ASSET, true, 1).accountId();
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = new PostingEngine(ds, HybridLogicalClock.system(500), Duration.ofDays(7), Duration.ofSeconds(5));
        }
    }

    @AfterEach
    void tearDown() {
        ds.close();
    }

    // Two app instances sharing one database, each with its own clock, the way
    // it runs in production. Per-account HLC order must still hold.
    private final PostingEngine[] nodes = new PostingEngine[2];

    private PostingEngine node(int i) {
        return nodes[i];
    }

    @Test
    void randomTransfersUnderLoadKeepEveryInvariant() throws Exception {
        int walletCount = 50;
        long startingBalance = 100_000; // 1,000.00 each
        List<String> wallets = new ArrayList<>();
        for (int i = 0; i < walletCount; i++) {
            String w = store.createAccount(ledger, AccountType.LIABILITY, false, 1).accountId();
            fund(node(0), w, startingBalance);
            wallets.add(w);
        }

        int threads = 32;
        int perThread = 300;
        AtomicLong committed = new AtomicLong();
        AtomicLong refused = new AtomicLong();

        runConcurrently(threads, t -> {
            PostingEngine engine = node(t % 2);
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            for (int i = 0; i < perThread; i++) {
                String from = wallets.get(rnd.nextInt(walletCount));
                String to = wallets.get(rnd.nextInt(walletCount));
                if (from.equals(to)) {
                    continue;
                }
                long amount = rnd.nextLong(100, 30_001); // 1.00 .. 300.00, enough to drain wallets often
                try {
                    engine.post(new PostingRequest(ledger, UUID.randomUUID().toString(), List.of(
                            new EntryRequest(from, DEBIT, amount), new EntryRequest(to, CREDIT, amount))));
                    committed.incrementAndGet();
                } catch (LedgerException e) {
                    assertEquals(ErrorCode.INSUFFICIENT_FUNDS, e.code(), "unexpected: " + e.getMessage());
                    refused.incrementAndGet();
                }
            }
        });

        // Some transfers must have been refused, otherwise this test isn't
        // actually exercising the overdraft path under contention.
        assertTrue(refused.get() > 0, "no overdraft attempts happened; widen the amount range");
        assertTrue(committed.get() > 1_000, "too few transfers committed: " + committed.get());

        // 1. No money created or destroyed.
        assertEquals(0, store.trialBalance(ledger));
        long total = 0;
        for (String w : wallets) {
            long b = store.balance(w);
            // 2. No protected wallet below zero.
            assertTrue(b >= 0, w + " went negative: " + b);
            total += b;
        }
        assertEquals(walletCount * startingBalance, total);

        // 3. Every commit wrote exactly two entries, nothing more, nothing less.
        assertEquals(2 * (walletCount + committed.get()), countEntries());

        // 4. Replaying each wallet's journal in HLC order never dips below zero
        //    and lands on the stored balance. If HLC order didn't match the order
        //    the locks were actually taken in, some replay would go negative.
        for (String w : wallets) {
            assertReplayInHlcOrderNeverGoesNegative(w);
        }
    }

    @Test
    void aStormOfRetriesWithOneKeyPostsExactlyOnce() throws Exception {
        String alice = store.createAccount(ledger, AccountType.LIABILITY, false, 1).accountId();
        String bob = store.createAccount(ledger, AccountType.LIABILITY, false, 1).accountId();
        fund(node(0), alice, 10_000);

        PostingRequest request = new PostingRequest(ledger, "client-retry-storm", List.of(
                new EntryRequest(alice, DEBIT, 2_500), new EntryRequest(bob, CREDIT, 2_500)));
        Set<String> txIds = ConcurrentHashMap.newKeySet();
        AtomicLong fresh = new AtomicLong();

        runConcurrently(16, t -> {
            PostedTransaction tx = node(t % 2).post(request);
            txIds.add(tx.transactionId());
            if (!tx.replayed()) {
                fresh.incrementAndGet();
            }
        });

        assertEquals(1, txIds.size(), "different transaction ids came back for one key: " + txIds);
        assertEquals(1, fresh.get(), "more than one call claimed to have posted");
        assertEquals(7_500, store.balance(alice));
        assertEquals(2_500, store.balance(bob));
    }

    @Test
    void aHotMerchantWithBucketsTakesConcurrentPaymentsAndStillPaysOutExactly() throws Exception {
        String merchant = store.createAccount(ledger, AccountType.LIABILITY, false, 16).accountId();
        int threads = 32;
        int perThread = 150;
        long price = 1_999;

        List<String> buyers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            String w = store.createAccount(ledger, AccountType.LIABILITY, false, 1).accountId();
            fund(node(0), w, perThread * price);
            buyers.add(w);
        }

        runConcurrently(threads, t -> {
            PostingEngine engine = node(t % 2);
            for (int i = 0; i < perThread; i++) {
                engine.post(new PostingRequest(ledger, UUID.randomUUID().toString(), List.of(
                        new EntryRequest(buyers.get(t), DEBIT, price),
                        new EntryRequest(merchant, CREDIT, price))));
            }
        });

        long expected = threads * perThread * price;
        assertEquals(expected, store.balance(merchant));
        assertTrue(bucketsUsed(merchant) > 1, "credits all landed in one bucket");
        for (String b : buyers) {
            assertEquals(0, store.balance(b));
        }

        // Payout drains every bucket. One cent more than the balance must fail.
        String bank = reserves;
        var tooMuch = assertThrows(LedgerException.class, () -> node(1).post(new PostingRequest(ledger, "payout-x",
                List.of(new EntryRequest(merchant, DEBIT, expected + 1), new EntryRequest(bank, CREDIT, expected + 1)))));
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, tooMuch.code());

        node(1).post(new PostingRequest(ledger, "payout-1",
                List.of(new EntryRequest(merchant, DEBIT, expected), new EntryRequest(bank, CREDIT, expected))));
        assertEquals(0, store.balance(merchant));
        assertEquals(0, minBucketBalance(merchant));
        assertEquals(0, store.trialBalance(ledger));
    }

    // --- helpers ----------------------------------------------------------

    @FunctionalInterface
    private interface Worker {
        void run(int threadIndex) throws Exception;
    }

    /** Starts all workers at the same instant so they actually collide. */
    private static void runConcurrently(int threads, Worker worker) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int idx = t;
            futures.add(pool.submit(() -> {
                go.await();
                worker.run(idx);
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : futures) {
            f.get(120, TimeUnit.SECONDS); // rethrows worker assertion failures
        }
        pool.shutdown();
    }

    private void fund(PostingEngine engine, String wallet, long minor) {
        engine.post(new PostingRequest(ledger, "fund-" + UUID.randomUUID(), List.of(
                new EntryRequest(reserves, DEBIT, minor), new EntryRequest(wallet, CREDIT, minor))));
    }

    private void assertReplayInHlcOrderNeverGoesNegative(String wallet) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT e.direction, e.amount, t.commit_hlc
                       FROM journal_entries e JOIN transactions t USING (transaction_id)
                      WHERE e.account_id = ?
                      ORDER BY t.commit_hlc, e.seq""")) {
            ps.setString(1, wallet);
            long running = 0;
            long prevHlc = -1;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long hlc = rs.getLong(3);
                    running += AccountType.LIABILITY.naturalDelta(Direction.valueOf(rs.getString(1)), rs.getLong(2));
                    assertTrue(running >= 0, wallet + " negative at hlc " + hlc + " in replay");
                    assertTrue(hlc >= prevHlc);
                    prevHlc = hlc;
                }
            }
            assertEquals(store.balance(wallet), running);
        }
    }

    private long countEntries() throws SQLException {
        try (Connection c = ds.getConnection(); var st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM journal_entries")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long bucketsUsed(String account) throws SQLException {
        return scalar("SELECT count(*) FROM account_buckets WHERE account_id = ? AND balance <> 0", account);
    }

    private long minBucketBalance(String account) throws SQLException {
        return scalar("SELECT min(balance) FROM account_buckets WHERE account_id = ?", account);
    }

    private long scalar(String sql, String arg) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}

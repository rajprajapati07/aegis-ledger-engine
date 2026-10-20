package com.aegis.ledger.store;

import com.aegis.ledger.core.AccountType;
import com.aegis.ledger.core.EntryRequest;
import com.aegis.ledger.core.HybridLogicalClock;
import com.aegis.ledger.core.PostingRequest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;

import static com.aegis.ledger.core.Direction.CREDIT;
import static com.aegis.ledger.core.Direction.DEBIT;

/**
 * Not a test. A quick way to see what the hot-account buckets buy you on a
 * single Postgres:
 *
 *   docker compose up -d postgres
 *   mvn -q test-compile exec:java -Dexec.classpathScope=test \
 *       -Dexec.mainClass=com.aegis.ledger.store.PostingThroughputBench
 *
 * Numbers depend entirely on the machine and on fsync latency (it stays on;
 * see docker-compose.yml). This is a reference implementation of one shard's
 * rules, not the 2M TPS system; the point is the relative difference between
 * scenarios, not the absolute number.
 */
public final class PostingThroughputBench {

    private static final int THREADS = Integer.getInteger("threads", 32);
    private static final int SECONDS = Integer.getInteger("seconds", 10);

    public static void main(String[] args) throws Exception {
        try (ThreadBoundDataSource ds = new ThreadBoundDataSource(TestDatabase.freshSchema())) {
            LedgerStore store = new LedgerStore(ds);
            PostingEngine engine = new PostingEngine(ds, HybridLogicalClock.system(500),
                    Duration.ofDays(7), Duration.ofSeconds(5));

            String ledger = store.createLedger("BENCH", "USD").ledgerId();
            String reserves = store.createAccount(ledger, AccountType.ASSET, true, 1).accountId();

            List<String> buyers = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                String w = store.createAccount(ledger, AccountType.LIABILITY, false, 1).accountId();
                engine.post(new PostingRequest(ledger, "fund-" + i, List.of(
                        new EntryRequest(reserves, DEBIT, 1_000_000_000L), new EntryRequest(w, CREDIT, 1_000_000_000L))));
                buyers.add(w);
            }
            List<String> others = new ArrayList<>();
            for (int i = 0; i < 1_000; i++) {
                others.add(store.createAccount(ledger, AccountType.LIABILITY, false, 1).accountId());
            }

            System.out.printf("threads=%d, %ds per scenario, synchronous_commit on%n%n", THREADS, SECONDS);

            run("spread: buyer -> random one of 1,000 wallets", engine, ledger, buyers,
                    t -> others.get(ThreadLocalRandom.current().nextInt(others.size())));

            String hot1 = store.createAccount(ledger, AccountType.LIABILITY, false, 1).accountId();
            run("hot merchant, 1 bucket", engine, ledger, buyers, t -> hot1);

            String hot16 = store.createAccount(ledger, AccountType.LIABILITY, false, 16).accountId();
            run("hot merchant, 16 buckets", engine, ledger, buyers, t -> hot16);

            String hot64 = store.createAccount(ledger, AccountType.LIABILITY, false, 64).accountId();
            run("hot merchant, 64 buckets", engine, ledger, buyers, t -> hot64);

            System.out.printf("%ntrial balance: %d (must be 0)%n", store.trialBalance(ledger));
        }
    }

    private interface Target {
        String pick(int thread);
    }

    private static void run(String name, PostingEngine engine, String ledger, List<String> buyers, Target target)
            throws InterruptedException {
        long[][] latencies = new long[THREADS][];
        int[] counts = new int[THREADS];
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        long deadline = System.nanoTime() + Duration.ofSeconds(SECONDS).toNanos();

        for (int t = 0; t < THREADS; t++) {
            int idx = t;
            Thread.ofPlatform().start(() -> {
                long[] lat = new long[1 << 20];
                int n = 0;
                try {
                    start.await();
                    while (System.nanoTime() < deadline && n < lat.length) {
                        long t0 = System.nanoTime();
                        engine.post(new PostingRequest(ledger, UUID.randomUUID().toString(), List.of(
                                new EntryRequest(buyers.get(idx), DEBIT, 100),
                                new EntryRequest(target.pick(idx), CREDIT, 100))));
                        lat[n++] = System.nanoTime() - t0;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latencies[idx] = lat;
                    counts[idx] = n;
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();

        int total = Arrays.stream(counts).sum();
        long[] all = new long[total];
        int pos = 0;
        for (int t = 0; t < THREADS; t++) {
            System.arraycopy(latencies[t], 0, all, pos, counts[t]);
            pos += counts[t];
        }
        Arrays.sort(all);
        System.out.printf("%-48s %8.0f TPS   p50 %6.2f ms   p99 %6.2f ms%n",
                name, total / (double) SECONDS, pct(all, 50), pct(all, 99));
    }

    private static double pct(long[] sorted, int p) {
        if (sorted.length == 0) {
            return 0;
        }
        return sorted[Math.min(sorted.length - 1, (int) (sorted.length * p / 100.0))] / 1e6;
    }
}

package com.aegis.ledger.store;

import com.aegis.ledger.core.AccountType;
import com.aegis.ledger.core.Amounts;
import com.aegis.ledger.core.EntryRequest;
import com.aegis.ledger.core.ErrorCode;
import com.aegis.ledger.core.HybridLogicalClock;
import com.aegis.ledger.core.Ids;
import com.aegis.ledger.core.LedgerException;
import com.aegis.ledger.core.PostedTransaction;
import com.aegis.ledger.core.PostingRequest;
import com.aegis.ledger.core.PostingValidator;
import com.aegis.ledger.core.RequestFingerprint;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Writes transactions. One call = one Postgres transaction that does, in order:
 *
 *   1. claim the idempotency key (or find it already used and replay)
 *   2. lock the balance rows this posting touches, in a fixed order
 *   3. check no protected account goes below zero
 *   4. write the transaction, its entries, and the new balances
 *   5. mark the key COMMITTED
 *
 * Everything commits together or not at all, which is the whole point:
 * there's no moment where money moved but the key isn't recorded.
 *
 * Isolation is READ COMMITTED with explicit row locks rather than
 * SERIALIZABLE. The only decision that depends on reading data is the
 * overdraft check, and it runs on rows we hold FOR UPDATE, so nobody can
 * change them underneath us. SSI would give the same answer with
 * unpredictable aborts under load, which is exactly when we can't afford them.
 */
public class PostingEngine {

    private static final int MAX_ATTEMPTS = 4;

    private final DataSource dataSource;
    private final HybridLogicalClock clock;
    private final Duration idempotencyTtl;
    private final Duration lockTimeout;

    public PostingEngine(DataSource dataSource, HybridLogicalClock clock,
                         Duration idempotencyTtl, Duration lockTimeout) {
        this.dataSource = dataSource;
        this.clock = clock;
        this.idempotencyTtl = idempotencyTtl;
        this.lockTimeout = lockTimeout;
    }

    public PostedTransaction post(PostingRequest request) {
        PostingValidator.validate(request);
        byte[] hash = RequestFingerprint.ofPosting(request);
        return execute(request.ledgerId(), request.idempotencyKey(), hash,
                c -> apply(c, request, null));
    }

    /**
     * Posts the mirror image of an existing transaction. The original stays in
     * the journal untouched. A transaction can be reversed at most once; the
     * unique constraint on reverses_transaction_id enforces that even if two
     * reversal requests with different keys race.
     */
    public PostedTransaction reverse(String transactionId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new LedgerException(ErrorCode.VALIDATION_FAILED, "idempotency key is required");
        }
        PostedTransaction original = readTransaction(transactionId)
                .orElseThrow(() -> new LedgerException(ErrorCode.TRANSACTION_NOT_FOUND,
                        "no transaction " + transactionId));

        List<EntryRequest> mirrored = original.entries().stream()
                .map(e -> new EntryRequest(e.accountId(), e.direction().opposite(), e.amountMinor()))
                .toList();
        PostingRequest reversal = new PostingRequest(original.ledgerId(), idempotencyKey, mirrored);
        PostingValidator.validate(reversal);

        byte[] hash = RequestFingerprint.ofReversal(transactionId);
        return execute(original.ledgerId(), idempotencyKey, hash,
                c -> apply(c, reversal, transactionId));
    }

    // ---------------------------------------------------------------------
    // transaction scaffolding: idempotency, retries, rejection caching
    // ---------------------------------------------------------------------

    @FunctionalInterface
    private interface Work {
        PostedTransaction run(Connection c) throws SQLException;
    }

    private PostedTransaction execute(String ledgerId, String key, byte[] hash, Work work) {
        SQLException lastTransient = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try (Connection c = dataSource.getConnection()) {
                c.setAutoCommit(false);
                try {
                    setLockTimeout(c);

                    Optional<KeyRecord> existing = claimKey(c, ledgerId, key, hash, "IN_FLIGHT", null);
                    if (existing.isPresent()) {
                        c.rollback();
                        return replay(c, existing.get(), hash);
                    }

                    PostedTransaction posted = work.run(c);
                    markCommitted(c, ledgerId, key, posted.transactionId());
                    c.commit();
                    return posted;

                } catch (LedgerException e) {
                    c.rollback();
                    if (!e.replayed() && e.code().cachedForIdempotency()) {
                        return recordRejection(c, ledgerId, key, hash, e);
                    }
                    throw e;
                } catch (SQLException e) {
                    c.rollback();
                    if (isAlreadyReversed(e)) {
                        return recordRejection(c, ledgerId, key, hash,
                                new LedgerException(ErrorCode.ALREADY_REVERSED, "transaction was already reversed"));
                    }
                    if (!isTransient(e)) {
                        throw e;
                    }
                    lastTransient = e;
                }
            } catch (SQLException e) {
                throw new StorageException("posting failed", e);
            }
            backoff(attempt);
        }
        throw new LedgerException(ErrorCode.CONTENTION,
                "gave up after " + MAX_ATTEMPTS + " attempts: " + lastTransient.getMessage());
    }

    private record KeyRecord(byte[] requestHash, String status, String transactionId,
                             String errorCode, String errorMessage) {
    }

    /**
     * Inserts the key, or reclaims it if the previous use has expired.
     * Returns empty if we now own the key, or the existing record if it's live.
     *
     * If another transaction is in the middle of using the same key, the
     * INSERT waits on the unique index until that transaction finishes. So a
     * burst of identical retries serialises right here, and every one after
     * the first sees a finished record. No separate lock needed.
     */
    private Optional<KeyRecord> claimKey(Connection c, String ledgerId, String key, byte[] hash,
                                         String status, LedgerException rejection) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO idempotency_keys
                       (ledger_id, idempotency_key, request_hash, status, error_code, error_message, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, now() + ? * interval '1 second')
                ON CONFLICT (ledger_id, idempotency_key) DO UPDATE
                   SET request_hash   = EXCLUDED.request_hash,
                       status         = EXCLUDED.status,
                       transaction_id = NULL,
                       error_code     = EXCLUDED.error_code,
                       error_message  = EXCLUDED.error_message,
                       created_at     = now(),
                       expires_at     = EXCLUDED.expires_at
                 WHERE idempotency_keys.expires_at <= now()
                RETURNING 1""")) {
            ps.setString(1, ledgerId);
            ps.setString(2, key);
            ps.setBytes(3, hash);
            ps.setString(4, status);
            ps.setString(5, rejection == null ? null : rejection.code().name());
            ps.setString(6, rejection == null ? null : rejection.getMessage());
            ps.setLong(7, idempotencyTtl.toSeconds());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.empty();
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT request_hash, status, transaction_id, error_code, error_message
                  FROM idempotency_keys WHERE ledger_id = ? AND idempotency_key = ?""")) {
            ps.setString(1, ledgerId);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    // Row vanished between the two statements: the other writer rolled back.
                    // Surface as contention so the caller's retry loop goes again.
                    throw new SQLException("idempotency row disappeared", "40001");
                }
                return Optional.of(new KeyRecord(rs.getBytes(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)));
            }
        }
    }

    private PostedTransaction replay(Connection c, KeyRecord rec, byte[] hash) throws SQLException {
        if (!Arrays.equals(rec.requestHash(), hash)) {
            throw new LedgerException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "this idempotency key was already used with a different request");
        }
        switch (rec.status()) {
            case "COMMITTED" -> {
                PostedTransaction tx = LedgerStore.findTransaction(c, rec.transactionId())
                        .orElseThrow(() -> new IllegalStateException(
                                "idempotency record points at missing transaction " + rec.transactionId()));
                c.rollback();
                return tx.asReplay();
            }
            case "REJECTED" -> throw new LedgerException(
                    ErrorCode.valueOf(rec.errorCode()), rec.errorMessage(), true);
            default -> throw new LedgerException(ErrorCode.CONTENTION,
                    "a request with this idempotency key is still in progress");
        }
    }

    /**
     * Stores a business rejection against the key, in its own small
     * transaction (the posting transaction has already rolled back).
     * If someone else got the key in between, their outcome wins and we
     * return that instead, so a key still only ever has one answer.
     */
    private PostedTransaction recordRejection(Connection c, String ledgerId, String key, byte[] hash,
                                              LedgerException rejection) throws SQLException {
        Optional<KeyRecord> existing = claimKey(c, ledgerId, key, hash, "REJECTED", rejection);
        if (existing.isPresent()) {
            c.rollback();
            return replay(c, existing.get(), hash);
        }
        c.commit();
        throw rejection;
    }

    private void markCommitted(Connection c, String ledgerId, String key, String transactionId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE idempotency_keys SET status = 'COMMITTED', transaction_id = ?
                 WHERE ledger_id = ? AND idempotency_key = ?""")) {
            ps.setString(1, transactionId);
            ps.setString(2, ledgerId);
            ps.setString(3, key);
            ps.executeUpdate();
        }
    }

    // ---------------------------------------------------------------------
    // the posting itself
    // ---------------------------------------------------------------------

    /** What we need to do to one account: its net change and which bucket rows to lock. */
    private record AccountPlan(Account account, long naturalDelta, boolean checked, int[] buckets) {
    }

    private record LockedBucket(String accountId, int bucket, long balance, long lastHlc) {
    }

    private PostedTransaction apply(Connection c, PostingRequest req, String reversesTransactionId)
            throws SQLException {

        if (reversesTransactionId != null) {
            // Checked up front so a second reversal reports ALREADY_REVERSED rather
            // than whatever the balance check happens to say. The unique constraint
            // on reverses_transaction_id still catches two reversals racing.
            refuseIfAlreadyReversed(c, reversesTransactionId);
        }
        Map<String, Account> accounts = loadAccounts(c, req);
        Map<String, AccountPlan> plans = planLocks(req, accounts);
        Map<String, List<LockedBucket>> locked = lockBuckets(c, plans);

        long maxSeenHlc = 0;
        for (AccountPlan plan : plans.values()) {
            List<LockedBucket> rows = locked.getOrDefault(plan.account().accountId(), List.of());
            for (LockedBucket b : rows) {
                maxSeenHlc = Math.max(maxSeenHlc, b.lastHlc());
            }
            if (plan.checked()) {
                long available = rows.stream().mapToLong(LockedBucket::balance).sum();
                if (available + plan.naturalDelta() < 0) {
                    String ccy = plan.account().currency();
                    throw new LedgerException(ErrorCode.INSUFFICIENT_FUNDS,
                            "account %s has %s available, needs %s".formatted(
                                    plan.account().accountId(),
                                    Amounts.format(available, ccy),
                                    Amounts.format(-plan.naturalDelta(), ccy)));
                }
            }
        }

        // Stamp after the locks are held. Every earlier writer to these rows has
        // committed by now and left its HLC in last_hlc, so update() guarantees
        // we're ordered after all of them, even if they ran on another node.
        long commitHlc = clock.update(maxSeenHlc);
        String txId = Ids.next("tx");

        writeBalances(c, plans, locked, commitHlc, txId);
        writeJournal(c, req, txId, commitHlc, reversesTransactionId);

        return new PostedTransaction(txId, req.ledgerId(), commitHlc, reversesTransactionId, req.entries(), false);
    }

    private void refuseIfAlreadyReversed(Connection c, String transactionId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT transaction_id FROM transactions WHERE reverses_transaction_id = ?")) {
            ps.setString(1, transactionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    throw new LedgerException(ErrorCode.ALREADY_REVERSED,
                            "transaction %s was already reversed by %s".formatted(transactionId, rs.getString(1)));
                }
            }
        }
    }

    private Map<String, Account> loadAccounts(Connection c, PostingRequest req) throws SQLException {
        String[] ids = req.entries().stream().map(EntryRequest::accountId).distinct().toArray(String[]::new);
        Map<String, Account> found = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT a.account_id, a.ledger_id, l.currency, a.type, a.allow_negative, a.bucket_count
                  FROM accounts a JOIN ledgers l USING (ledger_id)
                 WHERE a.account_id = ANY (?)""")) {
            Array arr = c.createArrayOf("text", ids);
            ps.setArray(1, arr);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    found.put(rs.getString(1), new Account(rs.getString(1), rs.getString(2), rs.getString(3),
                            AccountType.valueOf(rs.getString(4)), rs.getBoolean(5), rs.getInt(6)));
                }
            }
        }
        for (String id : ids) {
            Account a = found.get(id);
            if (a == null) {
                throw new LedgerException(ErrorCode.ACCOUNT_NOT_FOUND, "no account " + id);
            }
            if (!a.ledgerId().equals(req.ledgerId())) {
                throw new LedgerException(ErrorCode.LEDGER_MISMATCH,
                        "account %s belongs to ledger %s, not %s".formatted(id, a.ledgerId(), req.ledgerId()));
            }
        }
        return found;
    }

    /**
     * Nets the entries per account and decides what to lock.
     *
     * The rule that keeps hot accounts cheap: a leg that moves an account
     * toward its normal side (a credit to a wallet, say) can never break
     * allow_negative, so it only needs one bucket and we pick it at random.
     * Only a net decrease on a protected account needs every bucket locked,
     * because only then do we have to know the full balance.
     */
    private Map<String, AccountPlan> planLocks(PostingRequest req, Map<String, Account> accounts) {
        Map<String, Long> net = new TreeMap<>();
        for (EntryRequest e : req.entries()) {
            Account a = accounts.get(e.accountId());
            net.merge(e.accountId(), a.type().naturalDelta(e.direction(), e.amountMinor()), Math::addExact);
        }

        Map<String, AccountPlan> plans = new TreeMap<>();
        for (var entry : net.entrySet()) {
            long delta = entry.getValue();
            if (delta == 0) {
                continue; // same account debited and credited equally; nothing to change
            }
            Account a = accounts.get(entry.getKey());
            boolean checked = delta < 0 && !a.allowNegative();
            int[] buckets;
            if (checked) {
                buckets = new int[a.bucketCount()];
                for (int i = 0; i < buckets.length; i++) {
                    buckets[i] = i;
                }
            } else {
                buckets = new int[] {ThreadLocalRandom.current().nextInt(a.bucketCount())};
            }
            plans.put(a.accountId(), new AccountPlan(a, delta, checked, buckets));
        }
        return plans;
    }

    /**
     * One statement, rows locked in (account_id, bucket) order. Postgres
     * applies FOR UPDATE after the ORDER BY, so every posting acquires its
     * locks in the same global order and two postings can't deadlock on each
     * other's balance rows.
     */
    private Map<String, List<LockedBucket>> lockBuckets(Connection c, Map<String, AccountPlan> plans)
            throws SQLException {
        List<String> accountIds = new ArrayList<>();
        List<Integer> buckets = new ArrayList<>();
        for (AccountPlan p : plans.values()) {
            for (int b : p.buckets()) {
                accountIds.add(p.account().accountId());
                buckets.add(b);
            }
        }
        Map<String, List<LockedBucket>> out = new HashMap<>();
        if (accountIds.isEmpty()) {
            return out;
        }
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT b.account_id, b.bucket, b.balance, b.last_hlc
                  FROM account_buckets b
                  JOIN unnest(?::text[], ?::int[]) AS t(account_id, bucket)
                    ON b.account_id = t.account_id AND b.bucket = t.bucket
                 ORDER BY b.account_id, b.bucket
                   FOR UPDATE OF b""")) {
            ps.setArray(1, c.createArrayOf("text", accountIds.toArray()));
            ps.setArray(2, c.createArrayOf("int4", buckets.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LockedBucket lb = new LockedBucket(rs.getString(1), rs.getInt(2), rs.getLong(3), rs.getLong(4));
                    out.computeIfAbsent(lb.accountId(), k -> new ArrayList<>()).add(lb);
                }
            }
        }
        return out;
    }

    private void writeBalances(Connection c, Map<String, AccountPlan> plans,
                               Map<String, List<LockedBucket>> locked,
                               long commitHlc, String txId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE account_buckets
                   SET balance = balance + ?, last_hlc = ?, last_tx_id = ?
                 WHERE account_id = ? AND bucket = ?""")) {
            for (AccountPlan plan : plans.values()) {
                List<LockedBucket> rows = locked.get(plan.account().accountId());
                for (var change : spread(plan, rows).entrySet()) {
                    ps.setLong(1, change.getValue());
                    ps.setLong(2, commitHlc);
                    ps.setString(3, txId);
                    ps.setString(4, plan.account().accountId());
                    ps.setInt(5, change.getKey());
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
    }

    /**
     * Splits an account's net change across its locked buckets.
     * Increases (and unprotected decreases) go to the one bucket we picked.
     * A protected decrease drains the fullest buckets first, so no bucket
     * goes negative even though the account total was what we checked.
     */
    static Map<Integer, Long> spread(AccountPlan plan, List<LockedBucket> rows) {
        Map<Integer, Long> changes = new TreeMap<>();
        if (!plan.checked()) {
            changes.put(plan.buckets()[0], plan.naturalDelta());
            return changes;
        }
        long remaining = -plan.naturalDelta();
        List<LockedBucket> byBalance = new ArrayList<>(rows);
        byBalance.sort(Comparator.comparingLong(LockedBucket::balance).reversed());
        for (LockedBucket b : byBalance) {
            if (remaining == 0) {
                break;
            }
            long take = Math.min(remaining, Math.max(b.balance(), 0));
            if (take > 0) {
                changes.put(b.bucket(), -take);
                remaining -= take;
            }
        }
        if (remaining != 0) {
            // Can't happen after the availability check unless a bucket was already
            // negative, which allow_negative = false should have made impossible.
            throw new IllegalStateException("bucket spread left " + remaining + " for " + plan.account().accountId());
        }
        return changes;
    }

    private void writeJournal(Connection c, PostingRequest req, String txId, long commitHlc,
                              String reversesTransactionId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO transactions (transaction_id, ledger_id, commit_hlc, idempotency_key, reverses_transaction_id)
                VALUES (?, ?, ?, ?, ?)""")) {
            ps.setString(1, txId);
            ps.setString(2, req.ledgerId());
            ps.setLong(3, commitHlc);
            ps.setString(4, req.idempotencyKey());
            ps.setString(5, reversesTransactionId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO journal_entries (transaction_id, seq, account_id, direction, amount)
                VALUES (?, ?, ?, ?, ?)""")) {
            int seq = 0;
            for (EntryRequest e : req.entries()) {
                ps.setString(1, txId);
                ps.setInt(2, seq++);
                ps.setString(3, e.accountId());
                ps.setString(4, e.direction().name());
                ps.setLong(5, e.amountMinor());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ---------------------------------------------------------------------

    private Optional<PostedTransaction> readTransaction(String transactionId) {
        try (Connection c = dataSource.getConnection()) {
            return LedgerStore.findTransaction(c, transactionId);
        } catch (SQLException e) {
            throw new StorageException("could not read transaction", e);
        }
    }

    private void setLockTimeout(Connection c) throws SQLException {
        try (var st = c.createStatement()) {
            st.execute("SET LOCAL lock_timeout = '" + lockTimeout.toMillis() + "ms'");
        }
    }

    private static boolean isTransient(SQLException e) {
        String state = e.getSQLState();
        // 40001 serialization failure, 40P01 deadlock, 55P03 lock timeout
        return "40001".equals(state) || "40P01".equals(state) || "55P03".equals(state);
    }

    private static boolean isAlreadyReversed(SQLException e) {
        return "23505".equals(e.getSQLState())
                && e.getMessage() != null
                && e.getMessage().contains("reverses_transaction_id");
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, 5L << attempt));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new LedgerException(ErrorCode.CONTENTION, "interrupted while retrying");
        }
    }
}

package com.aegis.ledger.store;

import com.aegis.ledger.core.AccountType;
import com.aegis.ledger.core.EntryRequest;
import com.aegis.ledger.core.ErrorCode;
import com.aegis.ledger.core.HybridLogicalClock;
import com.aegis.ledger.core.LedgerException;
import com.aegis.ledger.core.PostedTransaction;
import com.aegis.ledger.core.PostingRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.aegis.ledger.core.Direction.CREDIT;
import static com.aegis.ledger.core.Direction.DEBIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostingEngineIT {

    private DataSource ds;
    private LedgerStore store;
    private PostingEngine engine;

    private String ledger;
    private String reserves; // ASSET, bank money backing the wallets
    private String alice;    // LIABILITY wallet, no overdraft
    private String bob;      // LIABILITY wallet, no overdraft

    @BeforeEach
    void setUp() {
        ds = TestDatabase.freshSchema();
        store = new LedgerStore(ds);
        engine = newEngine(Duration.ofDays(7));

        ledger = store.createLedger("US_WEST_FINCORE", "USD").ledgerId();
        reserves = store.createAccount(ledger, AccountType.ASSET, true, 1).accountId();
        alice = store.createAccount(ledger, AccountType.LIABILITY, false, 1).accountId();
        bob = store.createAccount(ledger, AccountType.LIABILITY, false, 1).accountId();
    }

    private PostingEngine newEngine(Duration ttl) {
        return new PostingEngine(ds, HybridLogicalClock.system(500), ttl, Duration.ofSeconds(2));
    }

    // --- basics -----------------------------------------------------------

    @Test
    void fundingAWalletMovesBothSidesAndTheLedgerStaysBalanced() {
        fund(alice, 50_000);

        assertEquals(50_000, store.balance(alice));
        assertEquals(50_000, store.balance(reserves));
        assertEquals(0, store.trialBalance(ledger));
    }

    @Test
    void transferBetweenWallets() {
        fund(alice, 50_000);
        PostedTransaction tx = transfer("t-1", alice, bob, 15_000);

        assertFalse(tx.replayed());
        assertEquals(35_000, store.balance(alice));
        assertEquals(15_000, store.balance(bob));
        assertEquals(0, store.trialBalance(ledger));

        PostedTransaction stored = store.findTransaction(tx.transactionId()).orElseThrow();
        assertEquals(tx.entries(), stored.entries());
        assertEquals(tx.commitHlc(), stored.commitHlc());
    }

    @Test
    void legsOnTheSameAccountAreNettedBeforeTheOverdraftCheck() {
        fund(alice, 3_000);
        // gross debit of 100.00 would fail on 30.00, but the net is only -20.00
        engine.post(new PostingRequest(ledger, "net-1", List.of(
                new EntryRequest(alice, DEBIT, 10_000),
                new EntryRequest(alice, CREDIT, 8_000),
                new EntryRequest(bob, CREDIT, 2_000))));

        assertEquals(1_000, store.balance(alice));
        assertEquals(2_000, store.balance(bob));
    }

    // --- overdraft --------------------------------------------------------

    @Test
    void overdraftIsRejectedAndNothingIsWritten() {
        fund(alice, 10_000);
        long journalBefore = count("journal_entries");

        var e = assertThrows(LedgerException.class, () -> transfer("t-over", alice, bob, 10_001));

        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, e.code());
        assertTrue(e.getMessage().contains("100.00 available"), e.getMessage());
        assertEquals(10_000, store.balance(alice));
        assertEquals(0, store.balance(bob));
        assertEquals(journalBefore, count("journal_entries"));
    }

    @Test
    void srsExampleIsRejectedOnAFreshWallet() {
        // SRS §4.3 debits a brand-new LIABILITY account (allow_negative = false,
        // balance 0.00) by 150.00 and shows COMMITTED. Debiting a liability
        // reduces it, so per SRS §3.2 this has to fail. See review finding F-4.
        var e = assertThrows(LedgerException.class, () -> engine.post(new PostingRequest(ledger, "id_uuid_99812",
                List.of(new EntryRequest(alice, DEBIT, 15_000), new EntryRequest(bob, CREDIT, 15_000)))));
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, e.code());
    }

    @Test
    void accountsWithAllowNegativeCanGoBelowZero() {
        String equity = store.createAccount(ledger, AccountType.EQUITY, true, 1).accountId();
        engine.post(new PostingRequest(ledger, "seed", List.of(
                new EntryRequest(equity, DEBIT, 1_000_000),
                new EntryRequest(alice, CREDIT, 1_000_000))));

        assertEquals(-1_000_000, store.balance(equity));
        assertEquals(1_000_000, store.balance(alice));
        assertEquals(0, store.trialBalance(ledger));
    }

    // --- idempotency ------------------------------------------------------

    @Test
    void retryWithTheSameKeyReturnsTheOriginalAndPostsOnce() {
        fund(alice, 50_000);
        PostedTransaction first = transfer("retry-1", alice, bob, 1_000);
        PostedTransaction second = transfer("retry-1", alice, bob, 1_000);

        assertTrue(second.replayed());
        assertEquals(first.transactionId(), second.transactionId());
        assertEquals(first.commitHlc(), second.commitHlc());
        assertEquals(49_000, store.balance(alice));
        assertEquals(1_000, store.balance(bob));
    }

    @Test
    void sameKeyWithADifferentPayloadIsRefused() {
        fund(alice, 50_000);
        transfer("reuse-1", alice, bob, 1_000);

        var e = assertThrows(LedgerException.class, () -> transfer("reuse-1", alice, bob, 2_000));
        assertEquals(ErrorCode.IDEMPOTENCY_KEY_REUSED, e.code());
        assertEquals(1_000, store.balance(bob));
    }

    @Test
    void aRejectionIsRememberedForItsKey() {
        var first = assertThrows(LedgerException.class, () -> transfer("broke-1", alice, bob, 500));
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, first.code());
        assertFalse(first.replayed());

        fund(alice, 10_000);

        // Same key, same request: same answer, even though it would succeed now.
        var again = assertThrows(LedgerException.class, () -> transfer("broke-1", alice, bob, 500));
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, again.code());
        assertTrue(again.replayed());

        // A new key is a new attempt.
        transfer("broke-2", alice, bob, 500);
        assertEquals(500, store.balance(bob));
    }

    @Test
    void validationErrorsDontBurnTheKey() {
        assertThrows(LedgerException.class, () -> engine.post(new PostingRequest(ledger, "fix-me",
                List.of(new EntryRequest(alice, DEBIT, 100), new EntryRequest(bob, CREDIT, 99)))));

        fund(alice, 1_000);
        // client fixes the typo and resends with the same key; that has to work
        transfer("fix-me", alice, bob, 100);
        assertEquals(100, store.balance(bob));
    }

    @Test
    void keysAreScopedPerLedger() {
        String other = store.createLedger("EU_FINCORE", "EUR").ledgerId();
        String eurReserves = store.createAccount(other, AccountType.ASSET, true, 1).accountId();
        String eurWallet = store.createAccount(other, AccountType.LIABILITY, false, 1).accountId();

        fund(alice, 1_000);
        PostedTransaction usd = transfer("shared-key", alice, bob, 100);
        PostedTransaction eur = engine.post(new PostingRequest(other, "shared-key", List.of(
                new EntryRequest(eurReserves, DEBIT, 100), new EntryRequest(eurWallet, CREDIT, 100))));

        assertFalse(eur.replayed());
        assertNotEquals(usd.transactionId(), eur.transactionId());
    }

    @Test
    void anExpiredKeyCanBeUsedAgain() {
        PostingEngine shortLived = newEngine(Duration.ZERO);
        fund(alice, 10_000);

        PostedTransaction first = shortLived.post(transferRequest("daily-sweep", alice, bob, 100));
        PostedTransaction second = shortLived.post(transferRequest("daily-sweep", alice, bob, 200));

        assertFalse(second.replayed());
        assertNotEquals(first.transactionId(), second.transactionId());
        assertEquals(300, store.balance(bob));
    }

    // --- ledger boundaries ------------------------------------------------

    @Test
    void anAccountFromAnotherLedgerIsRefused() {
        String other = store.createLedger("EU_FINCORE", "EUR").ledgerId();
        String eurWallet = store.createAccount(other, AccountType.LIABILITY, false, 1).accountId();
        fund(alice, 1_000);

        var e = assertThrows(LedgerException.class, () -> transfer("cross", alice, eurWallet, 100));
        assertEquals(ErrorCode.LEDGER_MISMATCH, e.code());
        assertEquals(1_000, store.balance(alice));
    }

    @Test
    void unknownAccountIsRefused() {
        var e = assertThrows(LedgerException.class, () -> transfer("ghost", alice, "acc_does_not_exist", 1));
        assertEquals(ErrorCode.ACCOUNT_NOT_FOUND, e.code());
    }

    // --- reversals --------------------------------------------------------

    @Test
    void reversalRestoresBalancesAndLeavesTheOriginalInPlace() {
        fund(alice, 10_000);
        PostedTransaction original = transfer("pay-1", alice, bob, 4_000);

        PostedTransaction reversal = engine.reverse(original.transactionId(), "undo-pay-1");

        assertEquals(original.transactionId(), reversal.reversesTransactionId());
        assertTrue(reversal.commitHlc() > original.commitHlc());
        assertEquals(10_000, store.balance(alice));
        assertEquals(0, store.balance(bob));
        assertEquals(0, store.trialBalance(ledger));
        assertTrue(store.findTransaction(original.transactionId()).isPresent());
    }

    @Test
    void aTransactionCanOnlyBeReversedOnce() {
        fund(alice, 10_000);
        PostedTransaction original = transfer("pay-2", alice, bob, 4_000);
        PostedTransaction reversal = engine.reverse(original.transactionId(), "undo-a");

        // same key: replay
        assertEquals(reversal.transactionId(), engine.reverse(original.transactionId(), "undo-a").transactionId());

        // different key: refused, balances untouched
        var e = assertThrows(LedgerException.class, () -> engine.reverse(original.transactionId(), "undo-b"));
        assertEquals(ErrorCode.ALREADY_REVERSED, e.code());
        assertEquals(10_000, store.balance(alice));
    }

    @Test
    void aReversalThatWouldOverdraftIsRejectedLikeAnyOtherPosting() {
        String carol = store.createAccount(ledger, AccountType.LIABILITY, false, 1).accountId();
        fund(alice, 10_000);
        PostedTransaction pay = transfer("pay-3", alice, bob, 10_000);
        transfer("bob-spends", bob, carol, 10_000); // bob has already spent it

        var e = assertThrows(LedgerException.class, () -> engine.reverse(pay.transactionId(), "undo-pay-3"));
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, e.code());
        assertEquals(0, store.balance(bob));
    }

    // --- immutability -----------------------------------------------------

    @Test
    void theJournalIsAppendOnly() throws SQLException {
        fund(alice, 10_000);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            assertThrows(SQLException.class, () -> st.execute("UPDATE journal_entries SET amount = amount * 2"));
            assertThrows(SQLException.class, () -> st.execute("DELETE FROM transactions"));
            assertThrows(SQLException.class, () -> st.execute("TRUNCATE journal_entries"));
        }
        assertEquals(2, count("journal_entries"));
    }

    // --- helpers ----------------------------------------------------------

    private void fund(String wallet, long minor) {
        engine.post(new PostingRequest(ledger, "fund-" + UUID.randomUUID(), List.of(
                new EntryRequest(reserves, DEBIT, minor),
                new EntryRequest(wallet, CREDIT, minor))));
    }

    private PostedTransaction transfer(String key, String from, String to, long minor) {
        return engine.post(transferRequest(key, from, to, minor));
    }

    private PostingRequest transferRequest(String key, String from, String to, long minor) {
        return new PostingRequest(ledger, key, List.of(
                new EntryRequest(from, DEBIT, minor),
                new EntryRequest(to, CREDIT, minor)));
    }

    private long count(String table) {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}

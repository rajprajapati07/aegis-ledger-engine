package com.aegis.ledger.store;

import com.aegis.ledger.core.AccountType;
import com.aegis.ledger.core.Amounts;
import com.aegis.ledger.core.Direction;
import com.aegis.ledger.core.EntryRequest;
import com.aegis.ledger.core.ErrorCode;
import com.aegis.ledger.core.Ids;
import com.aegis.ledger.core.LedgerException;
import com.aegis.ledger.core.PostedTransaction;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Ledgers, accounts and read queries. Everything that moves money is in
 * {@link PostingEngine}; this class never writes to the journal.
 *
 * Plain JDBC rather than JPA: a ledger is a handful of tables and I want the
 * exact SQL, especially the locking, to be visible in the code.
 */
public class LedgerStore {

    public static final int MAX_BUCKETS = 256;

    private final DataSource dataSource;

    public LedgerStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Ledger createLedger(String name, String currency) {
        if (name == null || name.isBlank()) {
            throw new LedgerException(ErrorCode.VALIDATION_FAILED, "name is required");
        }
        String code = currency == null ? null : currency.trim().toUpperCase();
        Amounts.exponent(code); // throws on unknown or unbookable currency

        String id = Ids.next("led");
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO ledgers (ledger_id, name, currency) VALUES (?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, name.trim());
            ps.setString(3, code);
            ps.executeUpdate();
            return new Ledger(id, name.trim(), code);
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new LedgerException(ErrorCode.DUPLICATE_NAME, "a ledger named '" + name + "' already exists");
            }
            throw new StorageException("could not create ledger", e);
        }
    }

    public Account createAccount(String ledgerId, AccountType type, boolean allowNegative, int bucketCount) {
        if (type == null) {
            throw new LedgerException(ErrorCode.VALIDATION_FAILED, "type is required");
        }
        if (bucketCount < 1 || bucketCount > MAX_BUCKETS) {
            throw new LedgerException(ErrorCode.VALIDATION_FAILED, "buckets must be between 1 and " + MAX_BUCKETS);
        }
        Ledger ledger = findLedger(ledgerId)
                .orElseThrow(() -> new LedgerException(ErrorCode.LEDGER_NOT_FOUND, "no ledger " + ledgerId));

        String id = Ids.next("acc");
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO accounts (account_id, ledger_id, type, allow_negative, bucket_count)
                        VALUES (?, ?, ?, ?, ?)""")) {
                    ps.setString(1, id);
                    ps.setString(2, ledgerId);
                    ps.setString(3, type.name());
                    ps.setBoolean(4, allowNegative);
                    ps.setInt(5, bucketCount);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO account_buckets (account_id, bucket)
                        SELECT ?, b FROM generate_series(0, ? - 1) AS b""")) {
                    ps.setString(1, id);
                    ps.setInt(2, bucketCount);
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
            return new Account(id, ledgerId, ledger.currency(), type, allowNegative, bucketCount);
        } catch (SQLException e) {
            throw new StorageException("could not create account", e);
        }
    }

    public Optional<Ledger> findLedger(String ledgerId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ledger_id, name, currency FROM ledgers WHERE ledger_id = ?")) {
            ps.setString(1, ledgerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Ledger(rs.getString(1), rs.getString(2), rs.getString(3)));
            }
        } catch (SQLException e) {
            throw new StorageException("could not read ledger", e);
        }
    }

    public Optional<Account> findAccount(String accountId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT a.account_id, a.ledger_id, l.currency, a.type, a.allow_negative, a.bucket_count
                       FROM accounts a JOIN ledgers l USING (ledger_id)
                      WHERE a.account_id = ?""")) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Account(rs.getString(1), rs.getString(2), rs.getString(3),
                        AccountType.valueOf(rs.getString(4)), rs.getBoolean(5), rs.getInt(6)));
            }
        } catch (SQLException e) {
            throw new StorageException("could not read account", e);
        }
    }

    /** Current balance in minor units, natural orientation. Sums buckets for hot accounts. */
    public long balance(String accountId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT count(*), coalesce(sum(balance), 0)
                       FROM account_buckets WHERE account_id = ?""")) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getLong(1) == 0) {
                    throw new LedgerException(ErrorCode.ACCOUNT_NOT_FOUND, "no account " + accountId);
                }
                return rs.getLong(2);
            }
        } catch (SQLException e) {
            throw new StorageException("could not read balance", e);
        }
    }

    /**
     * Sum of every account in the ledger, debit-positive. For a correct ledger
     * this is always exactly zero. The reconciler and the tests both lean on it.
     */
    public long trialBalance(String ledgerId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT coalesce(sum(CASE WHEN a.type IN ('ASSET', 'EXPENSE') THEN b.balance
                                              ELSE -b.balance END), 0)
                       FROM account_buckets b JOIN accounts a USING (account_id)
                      WHERE a.ledger_id = ?""")) {
            ps.setString(1, ledgerId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new StorageException("could not compute trial balance", e);
        }
    }

    public Optional<PostedTransaction> findTransaction(String transactionId) {
        try (Connection c = dataSource.getConnection()) {
            return findTransaction(c, transactionId);
        } catch (SQLException e) {
            throw new StorageException("could not read transaction", e);
        }
    }

    static Optional<PostedTransaction> findTransaction(Connection c, String transactionId) throws SQLException {
        String ledgerId;
        long hlc;
        String reverses;
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT ledger_id, commit_hlc, reverses_transaction_id
                  FROM transactions WHERE transaction_id = ?""")) {
            ps.setString(1, transactionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                ledgerId = rs.getString(1);
                hlc = rs.getLong(2);
                reverses = rs.getString(3);
            }
        }
        List<EntryRequest> entries = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT account_id, direction, amount FROM journal_entries
                 WHERE transaction_id = ? ORDER BY seq""")) {
            ps.setString(1, transactionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(new EntryRequest(rs.getString(1), Direction.valueOf(rs.getString(2)), rs.getLong(3)));
                }
            }
        }
        return Optional.of(new PostedTransaction(transactionId, ledgerId, hlc, reverses, List.copyOf(entries), false));
    }
}

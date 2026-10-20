-- Aegis ledger schema.
--
-- Amounts are BIGINT minor units (cents for USD). Balances live in
-- account_buckets in the account's natural orientation, so for a wallet
-- (liability, credit-normal) a positive number is money the user has.

CREATE TABLE ledgers (
    ledger_id   TEXT        PRIMARY KEY,
    name        TEXT        NOT NULL UNIQUE,
    currency    CHAR(3)     NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE accounts (
    account_id      TEXT        PRIMARY KEY,
    ledger_id       TEXT        NOT NULL REFERENCES ledgers (ledger_id),
    type            TEXT        NOT NULL CHECK (type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    allow_negative  BOOLEAN     NOT NULL,
    bucket_count    SMALLINT    NOT NULL DEFAULT 1 CHECK (bucket_count BETWEEN 1 AND 256),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX accounts_ledger_idx ON accounts (ledger_id);

-- One row for a normal account, N rows for a hot one. Credits to a hot
-- account pick a random bucket, so concurrent payments to the same merchant
-- don't queue on a single row lock. Balance = SUM(balance) over buckets.
-- last_hlc is the HLC of the last transaction that touched the bucket; the
-- engine feeds it back into the clock so per-account order holds across
-- app instances.
CREATE TABLE account_buckets (
    account_id  TEXT     NOT NULL REFERENCES accounts (account_id),
    bucket      SMALLINT NOT NULL,
    balance     BIGINT   NOT NULL DEFAULT 0,
    last_hlc    BIGINT   NOT NULL DEFAULT 0,
    last_tx_id  TEXT,
    PRIMARY KEY (account_id, bucket)
);

CREATE TABLE transactions (
    transaction_id          TEXT        PRIMARY KEY,
    ledger_id               TEXT        NOT NULL REFERENCES ledgers (ledger_id),
    commit_hlc              BIGINT      NOT NULL,
    idempotency_key         TEXT        NOT NULL,
    reverses_transaction_id TEXT        UNIQUE REFERENCES transactions (transaction_id),
    committed_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE journal_entries (
    transaction_id  TEXT     NOT NULL REFERENCES transactions (transaction_id),
    seq             SMALLINT NOT NULL,
    account_id      TEXT     NOT NULL REFERENCES accounts (account_id),
    direction       TEXT     NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount          BIGINT   NOT NULL CHECK (amount > 0),
    PRIMARY KEY (transaction_id, seq)
);

CREATE INDEX journal_entries_account_idx ON journal_entries (account_id, transaction_id);

-- Idempotency lives in the same database as the journal on purpose: the key
-- is claimed in the same transaction that writes the entries, so they commit
-- or roll back together. Rejections are stored too (status REJECTED) so a
-- retry with the same key gets the same answer.
CREATE TABLE idempotency_keys (
    ledger_id        TEXT        NOT NULL,
    idempotency_key  TEXT        NOT NULL,
    request_hash     BYTEA       NOT NULL,
    status           TEXT        NOT NULL CHECK (status IN ('IN_FLIGHT', 'COMMITTED', 'REJECTED')),
    transaction_id   TEXT,
    error_code       TEXT,
    error_message    TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (ledger_id, idempotency_key)
);

CREATE INDEX idempotency_keys_expiry_idx ON idempotency_keys (expires_at);

-- The journal is append-only. The app role shouldn't have UPDATE/DELETE
-- grants on these tables in the first place (see deploy/grants.sql), but
-- grants drift, so the trigger is the backstop.
CREATE FUNCTION reject_journal_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ledger journal is append-only: % on % is not allowed', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER transactions_append_only
    BEFORE UPDATE OR DELETE ON transactions
    FOR EACH ROW EXECUTE FUNCTION reject_journal_mutation();

CREATE TRIGGER journal_entries_append_only
    BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION reject_journal_mutation();

-- TRUNCATE skips row triggers, so it needs its own.
CREATE TRIGGER transactions_no_truncate
    BEFORE TRUNCATE ON transactions
    FOR EACH STATEMENT EXECUTE FUNCTION reject_journal_mutation();

CREATE TRIGGER journal_entries_no_truncate
    BEFORE TRUNCATE ON journal_entries
    FOR EACH STATEMENT EXECUTE FUNCTION reject_journal_mutation();

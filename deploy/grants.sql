-- Production grants. Migrations run as aegis_owner; the service connects as
-- aegis_app, which can append to the journal but never rewrite it.
-- The triggers in V1 are the second line of defence, not the first.

CREATE ROLE aegis_app LOGIN;

GRANT USAGE ON SCHEMA public TO aegis_app;

GRANT SELECT, INSERT ON transactions, journal_entries TO aegis_app;
GRANT SELECT, INSERT ON ledgers, accounts TO aegis_app;

-- Balances are a projection of the journal and do change.
GRANT SELECT, INSERT, UPDATE ON account_buckets TO aegis_app;

-- Expired keys are purged by a scheduled job running as the owner role.
GRANT SELECT, INSERT, UPDATE ON idempotency_keys TO aegis_app;

REVOKE UPDATE, DELETE, TRUNCATE ON transactions, journal_entries FROM aegis_app;

# Aegis — Core Ledger Engine

A double-entry ledger designed for 2M write TPS and 5M read QPS, with p99 writes under 25 ms and zero data loss. This repo has the full architecture, a critical review of the requirements it was designed against, and a working reference implementation of the part where correctness is decided: posting a transaction exactly once, without ever overdrawing an account, even when thousands of requests hit the same account at the same time.

Java 21 · Spring Boot 3 · PostgreSQL 16 · plain JDBC

---

## Quick start

```bash
git clone https://github.com/rajprajapati07/aegis-ledger-engine.git
cd aegis-ledger-engine
docker compose up -d          # Postgres + the service, migrations run on startup
./scripts/smoke.sh            # walks through the API (needs curl + jq)
```

Run the tests (unit tests need nothing; integration tests need the Postgres from compose):

```bash
docker compose up -d postgres
mvn verify
```

---

## The problem

Moving money between accounts is easy. Moving it correctly under load is not. A ledger has to hold these no matter what:

1. **Never create or destroy money.** Every transaction sums to zero, and so does every ledger.
2. **Never double-post.** A client that times out and retries gets the first result back, not a second transfer.
3. **Never overdraw a protected account,** including when ten thousand payments land on it in the same millisecond.
4. **Never lose an acknowledged transaction.**
5. **Never rewrite history.** Corrections are new reversing entries.

The SRS this was designed against also asks for the "viral merchant" case: millions of buyers paying one merchant at once. On a normal database that's millions of transactions queuing for one row lock.

---

## How the full system works

```
clients ─► Envoy ─► Ledger Gateway (stateless) ─► 64 shards, each a 3-replica Raft group
                    auth, zero-sum check,          single-threaded deterministic state machine
                    idempotency fast path,         batched log appends, no row locks
                    routing                        2PC between shards, Raft-backed coordinator
                                                          │
                                       committed log ─► Kafka ─► history store + S3 WORM archive
```

- **Within a shard there are no locks.** One thread applies a batched Raft log in order, so execution is serial, which is trivially serializable and never aborts under contention.
- **Only one kind of leg can fail.** A credit to a wallet can never break the overdraft rule; only legs moving an account away from its normal side can. So only those need a hold in a cross-shard commit, and credits to a hot merchant are just additions.
- **Idempotency is written in the same log entry as the posting.** There's no gap where money moved but the key wasn't recorded. Redis sits in front as a cache, never as the authority.
- **Ordering uses the Raft index inside a shard and a Hybrid Logical Clock across shards,** with a max-offset guard so one bad clock can't drag the cluster into the future.

The full design, including the latency budget, capacity math and the RPO = 0 trade-off across regions, is in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

---

## What's built here

A reference implementation of **one shard's rules** on PostgreSQL, behind the SRS API. It's the clearest way to show the correctness rules and test them under real concurrency. It is not the 64-shard system and doesn't claim 2M TPS.

| Guarantee | How | Proven by |
|---|---|---|
| Zero-sum, amounts in minor units, no rounding | `PostingValidator`, `Amounts` (ISO 4217 exponents, `150.001 USD` is an error) | `PostingValidatorTest`, `AmountsTest` |
| Exactly-once posting | Key claimed in the same DB transaction as the entries; request hash stored with it | `aStormOfRetriesWithOneKeyPostsExactlyOnce` — 16 threads, one key, two app nodes |
| Same key, different payload → 409 | SHA-256 of the canonical request | `sameKeyWithADifferentPayloadIsRefused` |
| A rejected key stays rejected | Business rejections stored against the key | `aRejectionIsRememberedForItsKey` |
| No overdraft under concurrency | Balance rows locked `FOR UPDATE` in a fixed global order, checked after locking | `randomTransfersUnderLoadKeepEveryInvariant` |
| Hot merchant doesn't serialise | Account split into N bucket rows; credits pick a random one; debits lock all | `aHotMerchantWithBuckets...` and the bench below |
| Per-account causal order across app nodes | HLC stamped after locks, fed the `last_hlc` of locked rows | Replay of every wallet's journal in HLC order never goes negative |
| Append-only journal | Trigger rejects UPDATE / DELETE / TRUNCATE; grants in `deploy/grants.sql` | `theJournalIsAppendOnly` |
| Reversals, once | Mirrored posting, unique `reverses_transaction_id` | `aTransactionCanOnlyBeReversedOnce` |
| Ledger always sums to zero | `GET /v1/ledgers/{id}/trial-balance` | asserted at the end of every concurrency test |

The concurrency test runs 32 threads across two engine instances (two clocks, one database), making ~9,600 random transfer attempts between 50 wallets, with amounts large enough that wallets regularly run dry and transfers get refused. Then it checks: trial balance is zero, no wallet is negative, entry count is exactly 2× commits, and every wallet's history replayed in HLC order never dips below zero at any point.

### What the buckets buy

`PostingThroughputBench`, 32 threads, `synchronous_commit` on, on a 2-vCPU sandbox (absolute numbers are small; the ratios are the point):

| Scenario | TPS | p50 | p99 |
|---|---|---|---|
| Payments spread across 1,000 wallets | 1,012 | 27.9 ms | 102 ms |
| Every payment to one merchant, 1 bucket | 402 | 46.5 ms | 465 ms |
| Every payment to one merchant, 16 buckets | 1,298 | 19.6 ms | 96 ms |
| Every payment to one merchant, 64 buckets | 1,271 | 22.3 ms | 80 ms |

With one bucket, the hot merchant is 2.5x slower than spread traffic and p99 is nearly half a second. With 16 buckets it's as fast as spread traffic. Beyond that, the bottleneck is the machine, not the lock.

Run it yourself:

```bash
mvn -q test-compile exec:java -Dexec.classpathScope=test \
    -Dexec.mainClass=com.aegis.ledger.store.PostingThroughputBench
```

---

## API

Matches the SRS, with the gaps from review finding F-15 filled in.

| Method | Path | Notes |
|---|---|---|
| `POST` | `/v1/ledgers` | `{ "name", "currency" }` → 201 |
| `GET` | `/v1/ledgers/{id}` | |
| `GET` | `/v1/ledgers/{id}/trial-balance` | `balanced` should always be `true` |
| `POST` | `/v1/accounts` | `{ "ledger_id", "type", "allow_negative", "buckets"? }` → 201. `allow_negative` has no default on purpose |
| `GET` | `/v1/accounts/{id}` | balance as a decimal string |
| `POST` | `/v1/transactions` | key in `Idempotency-Key` header or body. 201 new, 200 + `Idempotent-Replayed: true` on retry |
| `GET` | `/v1/transactions/{id}` | |
| `POST` | `/v1/transactions/{id}/reversals` | `Idempotency-Key` header required |

```bash
curl -X POST localhost:8080/v1/transactions \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: id_uuid_99812' \
  -d '{"ledger_id":"led_...","entries":[
        {"account_id":"acc_wallet","direction":"DEBIT","amount":"150.00"},
        {"account_id":"acc_merchant","direction":"CREDIT","amount":"150.00"}]}'
```

```json
{ "transaction_id": "tx_01JB...", "ledger_id": "led_...", "status": "COMMITTED",
  "commit_hlc": 117440512000000003, "entries": [ ... ] }
```

Errors are `{ "error": "<CODE>", "message": "..." }` with stable codes: `VALIDATION_FAILED`, `UNBALANCED`, `INSUFFICIENT_FUNDS`, `IDEMPOTENCY_KEY_REUSED`, `ACCOUNT_NOT_FOUND`, `LEDGER_MISMATCH`, `ALREADY_REVERSED`, `CONTENTION`.

One thing worth knowing: the SRS's own example transaction debits a brand-new liability account with `allow_negative: false`. That's an overdraft, so this implementation rejects it, and there's a test that says so. More on that in review finding F-4.

---

## Project layout

```
aegis-ledger-engine/
├── docs/
│   ├── ARCHITECTURE.md          full design: sharding, 2PC, hot accounts, HLC, capacity, HA/DR
│   ├── ARCHITECTURE_REVIEW.md   15 findings on the SRS, ranked by blast radius
│   └── DESIGN_GUIDE.md          HLD, LLD and DB design in plain language, with trade-offs
├── src/main/java/com/aegis/ledger/
│   ├── core/                    no framework, no database: validation, amounts, HLC, fingerprints
│   ├── store/                   PostingEngine (all money movement), LedgerStore (reads, setup)
│   ├── api/                     REST controllers, DTOs, error mapping
│   └── config/                  Spring wiring
├── src/main/resources/db/migration/V1__ledger_schema.sql
├── src/test/java/com/aegis/ledger/
│   ├── core/                    unit tests
│   └── store/                   *IT against real Postgres, PostingThroughputBench
├── deploy/grants.sql            production grants: the app role can't UPDATE or DELETE the journal
├── scripts/smoke.sh
├── docker-compose.yml
└── Dockerfile
```

The core and store packages don't import Spring. The engine takes a `DataSource` and that's it, so the integration tests run it directly without a Spring context.

---

## What isn't built, and why

- **The sharded Raft engine.** That's the production design, and a credible version is months of work, not a weekend. The Postgres implementation shows the rules that engine has to enforce, and tests them the same way the real thing would be tested. If I were building it, I'd spike TigerBeetle as the per-shard engine first (see `ARCHITECTURE.md` §13).
- **Cross-shard 2PC.** Only meaningful once there's more than one shard.
- **Redis idempotency fast path.** It's an optimisation in front of the durable record, and with one database there's nothing for it to save.
- **Authorisation holds (auth → capture).** Called out as a gap in the SRS (F-13). The schema has room for it.
- **Auth, tenancy, rate limiting.** Gateway concerns, covered in the design (§12), not in this slice.
- **Purging expired idempotency keys.** Expired keys are already reusable; a scheduled delete against the `expires_at` index is the remaining step.

---

## Docs

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — how the full system meets 2M TPS / p99 25 ms / RPO 0, and what it costs
- [`docs/ARCHITECTURE_REVIEW.md`](docs/ARCHITECTURE_REVIEW.md) — where the SRS contradicts itself, and what I'd change before building anything
- [`docs/DESIGN_GUIDE.md`](docs/DESIGN_GUIDE.md) — HLD, LLD and DB design in plain language: every component and decision, with examples and trade-offs

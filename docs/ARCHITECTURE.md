# Aegis — Core Ledger Engine Architecture

**Author:** Raj Prajapati
**Status:** Proposed design, v1
**Inputs:** Project Aegis SRS v1.0.0 (2M write TPS, 5M read QPS, p99 < 25 ms, 99.999%, RPO = 0)

---

## 0. How to read this

Section 1 is what I think the system actually has to guarantee, in plain words. Sections 2–9 are the design. Section 10 is the capacity math, 11 is HA/DR, 13 is what I looked at and didn't pick. Section 14 maps the design to the code in this repo.

There's a companion document, [`ARCHITECTURE_REVIEW.md`](ARCHITECTURE_REVIEW.md), where I go through the SRS itself and list the places where it contradicts itself or where taking it literally would hurt us. A few decisions below only make sense after reading that, and I point to the relevant finding where it matters.

---

## 1. What this system must never get wrong

The numbers in the SRS are big, but the numbers are not the hard part. The hard part is a short list of things a ledger must never do, even for one transaction out of a trillion:

1. **Never create or destroy money.** Every transaction sums to zero per currency. Every ledger, summed across all accounts, is zero at any consistent snapshot.
2. **Never double-post.** A client retrying after a timeout gets the original result, not a second transfer.
3. **Never let a protected account go negative**, including when ten thousand requests hit it in the same millisecond.
4. **Never lose an acknowledged transaction.** If we said `COMMITTED`, it survives any single failure we've designed for.
5. **Never rewrite history.** Journal rows are append-only. Corrections are new reversing transactions.

Everything else — latency, throughput, availability — is negotiable in the sense that we'd degrade it before we'd violate any of the five above. When I had to choose, I chose in that order.

---

## 2. Requirements as I read them

| SRS line | What I'm designing for | Notes |
|---|---|---|
| 2M write TPS sustained for 60 min | 2M TPS peak, with 2x headroom in the shard count | Daily average assumed at ~15% of peak (300k TPS). See review F-12. |
| 5M read QPS | Point balance reads from shard replicas; audit/history from a separate store | Two very different workloads; the SRS bundles them. Review F-10. |
| p95 < 15 ms, p99 < 25 ms | Budgeted per stage in §5.3 | Cross-shard transactions are the ones that threaten p99. |
| 99.999% | Measured as successful requests / valid requests, per 30-day window | Wall-clock "5.26 minutes a year" isn't measurable for a sharded system. Review F-9. |
| RPO = 0 | Zero loss for any single AZ failure. Regional RPO = 0 only with a paired region < 8 ms RTT | Review F-3. This is the biggest trade-off in the doc. |
| SERIALIZABLE isolation | Serial execution per shard, atomic commit across shards, per-account linearizability | Review F-2. Literally SERIALIZABLE everywhere at 2M TPS is the trap. |
| Idempotency, 7-day window | Durable, written atomically with the posting | Review F-1. Redis can be a fast path, never the authority. |

---

## 3. The shape of it

```
                    clients (payment rails, internal services)
                                   │  gRPC / REST, mTLS
                                   ▼
                        ┌─────────────────────┐
                        │  Envoy ingress (L7) │  TLS, per-tenant rate limits
                        └──────────┬──────────┘
                                   ▼
             ┌──────────────────────────────────────────┐
             │   Ledger Gateway (stateless, ~40 pods)   │
             │  authn/z · schema checks · zero-sum check│
             │  idempotency fast-path (Redis, advisory) │
             │  shard routing (account_id → shard)      │
             └───────┬───────────────┬──────────────┬───┘
                     │               │              │
          ┌──────────▼───┐  ┌────────▼─────┐  ┌─────▼────────┐
          │  Shard 00    │  │  Shard 01    │  │  Shard 63    │   64 shards
          │  Raft group  │  │  Raft group  │  │  Raft group  │   3 replicas each,
          │  L ─ F ─ F   │  │  L ─ F ─ F   │  │  L ─ F ─ F   │   one per AZ
          │ deterministic│◄─┼─2PC prepare/ │──┼─► commit     │
          │ state machine│  │  commit msgs │  │              │
          └──────┬───────┘  └──────┬───────┘  └──────┬───────┘
                 │ committed log (CDC, ordered per shard)│
                 └───────────────┬────────────────────────┘
                                 ▼
                     ┌──────────────────────┐
                     │ Kafka (journal topic)│  exactly-once, keyed by shard
                     └───┬─────────────┬────┘
                         ▼             ▼
            ┌────────────────────┐  ┌──────────────────────────┐
            │ History store       │  │ S3 archive (Object Lock, │
            │ (statements, audits)│  │ WORM, 10-year retention) │
            └────────────────────┘  └──────────────────────────┘
                         ▲
            continuous reconciler: Σ balances = 0 per ledger, per closed timestamp
```

The core idea in one paragraph: **a shard is a single-threaded, deterministic state machine whose input is a Raft log.** The leader batches incoming requests for up to 1 ms, appends the batch to the log, and once a quorum has it on disk, every replica applies the batch in the same order and gets the same result. There are no row locks because there's only one writer per shard. That gives us serializability inside a shard for free and turns the "viral merchant" problem (§7) into an arithmetic problem instead of a locking problem. Transactions spanning shards use a two-phase commit where both the coordinator and the participants are Raft groups, so a coordinator crash doesn't leave anyone blocked.

This is close to how TigerBeetle works internally, plus a sharding and 2PC layer on top. I say more about that in §13.

---

## 4. Data model

### 4.1 Core entities

```
Ledger        ledger_id, name, currency (ISO 4217), tenant_id
Account       account_id, ledger_id, type, allow_negative, bucket_count, status
Transaction   transaction_id, ledger_id, commit_hlc, shard_log_index,
              idempotency_key, reverses_transaction_id?
Entry         transaction_id, seq, account_id, direction, amount (int64 minor units)
Hold          hold_id, transaction_id, account_id, amount, expires_at   (cross-shard only)
Idempotency   (ledger_id, key) → request_hash, outcome, transaction_id, expires_at
```

A few deliberate choices:

- **One ledger, one currency.** Zero-sum is checked per transaction, and since every account in a ledger shares a currency, that's the same as per-currency zero-sum. FX is two linked transactions on two ledgers plus a spread posting to a revenue account. Mixing currencies inside one zero-sum check is meaningless (review F-6).
- **Amounts are `int64` minor units**, with the exponent coming from the currency (USD 2, JPY 0, BHD 3). No floating point anywhere. The API accepts and returns decimal strings because that's what the SRS shows, and the gateway converts at the edge. `int64` holds ±92 quadrillion dollars in cents, which is enough.
- **Account types carry their normal side.** Asset and expense accounts are debit-normal; liability, equity and revenue are credit-normal. Balances are stored *in the account's natural orientation*, so "available balance" is just the number, and `allow_negative = false` means "this number may not drop below zero".

### 4.2 The rule that makes the rest possible

For a given entry, either it moves the account *toward* its normal side (a credit to a wallet, a debit to bank reserves), or *away* from it.

> **Only legs that move an account away from its normal side can violate `allow_negative`.**

Legs moving toward the normal side can always be applied. They're commutative additions. This sounds like a bookkeeping footnote, but the whole write path leans on it: decreasing legs need validation and, across shards, a hold; increasing legs never need either. A payment from a user wallet to a merchant has exactly one leg that can fail (the wallet debit) and one that can't (the merchant credit).

### 4.3 Immutability

- Journal (transactions, entries) is append-only. In the shard it's just the Raft log, which is append-only by construction. In the history store and in the reference implementation it's enforced twice: the application role has no `UPDATE`/`DELETE` grant, and a trigger rejects both anyway.
- Balances are a *projection* of the journal, and projections are mutable. The SRS's "no UPDATE anywhere" can't be literally true for balances (review F-7). What matters is that balances can always be rebuilt from the journal, and the reconciler checks that they match.
- Each shard batch carries the SHA-256 of the previous batch. That gives a per-shard hash chain, so tampering with an archived segment is detectable, not just forbidden.

---

## 5. Write path

### 5.1 Single-shard transaction (all legs on one shard)

```
client → gateway
  1. authn, resolve tenant, check ledger_id belongs to tenant
  2. validate: ≥ 2 entries, amounts > 0, Σdebits = Σcredits
  3. idempotency fast path: Redis GET (ledger_id, key)
        hit with same request hash  → return cached result, done
        hit with different hash     → 409, done
        miss                        → continue (miss is not proof of novelty)
  4. route to shard leader

shard leader
  5. enqueue into the current batch (flush at 1 ms or 8,190 requests)
  6. append batch to Raft log, wait for quorum fsync (2 of 3 AZs)
  7. apply batch deterministically, request by request:
        a. idempotency index lookup  → if seen: emit cached outcome
        b. for each decreasing, protected leg: check balance − amount ≥ 0
        c. all checks pass → apply all legs, write idempotency record
           any check fails → apply nothing, write REJECTED idempotency record
  8. reply to gateway with outcome + commit HLC + log index
gateway → client
```

Step 7c is the important part: **the idempotency record is written by the same log entry as the posting**. They become durable together or not at all. There is no window where the money moved but the key wasn't recorded, which is the window that causes double-posts after a failover (review F-1).

Rejections are recorded too. If a wallet had insufficient funds on the first attempt and the client retries with the same key after topping up, they get the original rejection back. That's the only behaviour that makes "same key = same outcome" true; a new attempt needs a new key.

### 5.2 Cross-shard transaction

Most real payments are cross-shard: the wallet and the merchant hash to different shards. I expect 60–75% of traffic here, and I size for 75%.

```
                 coordinator = shard of the first protected decreasing leg
                 (deterministic, so retries land on the same coordinator)

gateway ──► coordinator shard C
              log #1: BEGIN(tx) + local holds for C's decreasing legs
              ├── PREPARE(tx, legs) ──► participant shard P (decreasing legs)
              │                           log: HOLD(tx, account, amount)
              │                           ◄── PREPARED  (or REJECTED)
              │
              log #2: COMMIT(tx)   (or ABORT if anyone rejected)
gateway ◄── COMMITTED, commit_hlc
              │
              └── async: COMMIT(tx) ──► P: convert hold → posted entry
                                    ──► Q (increasing legs only): apply credit
```

- Only shards with **protected decreasing legs** participate in PREPARE. Shards that only receive credits get the COMMIT message after the client has its answer, and applying it can't fail (§4.2). This keeps the synchronous part to two consensus rounds regardless of how many credit legs there are.
- The coordinator's state lives in its Raft log. If its leader dies between PREPARE and COMMIT, the new leader reads the log and finishes the protocol. Classic 2PC blocks on coordinator failure; this one blocks only as long as a Raft election (~1.5 s).
- A participant that voted PREPARED never aborts unilaterally. Holds have an age alarm (page at 5 s), not a silent timeout, because a hold silently expiring after the coordinator committed would create money.
- COMMIT delivery to participants is at-least-once and applied idempotently by `transaction_id`.

**Visibility.** Between the client ACK and the moment shard Q applies the credit, a reader on Q won't see the credit yet. That's typically under 3 ms. Two tools handle it:

- Every response carries `commit_hlc`. A read with `min_hlc=<that>` waits until the shard has applied everything up to that timestamp. That's read-your-writes for clients that need it.
- Audits and cross-account reports read at a **closed timestamp**: an HLC value below which every shard guarantees nothing new will be applied. Reads at a closed timestamp are a consistent snapshot of the whole ledger, and that's where the Σ = 0 check runs.

### 5.3 Latency budget

Intra-region, AZ-to-AZ RTT around 1 ms, NVMe fsync ~100–300 µs with group commit.

| Stage | Single-shard p95 | Cross-shard p95 |
|---|---|---|
| Ingress + TLS + gateway checks | 1.0 ms | 1.0 ms |
| Idempotency fast path (Redis) | 0.4 ms | 0.4 ms |
| Batch wait (adaptive, ≤ 1 ms) | 1.0 ms | 1.0 ms |
| Raft round 1 (quorum fsync) | 2.5 ms | 2.5 ms |
| PREPARE to participants (parallel, includes their Raft round) | — | 3.5 ms |
| Raft round 2 (COMMIT record) | — | 2.5 ms |
| Apply + reply | 0.6 ms | 0.6 ms |
| **Total** | **~5.5 ms** | **~11.5 ms** |

That fits the SRS's p95 15 ms with room. p99 is where cross-shard gets tight: one slow follower fsync or a GC pause in the gateway adds 5–10 ms. Three things protect it: the shard process runs with no allocation on the hot path (pre-allocated ring buffers, Java with ZGC or plain off-heap structures), the leader only needs the *fastest* follower to ack, and the gateway uses hedged requests on idempotent reads only (never on writes).

Note the SRS measures latency to "commit log ACK". I measure to the response leaving the gateway, because that's what the client feels (review F-11).

---

## 6. Sharding

- **64 shards** at launch, each a 3-replica Raft group with one replica per AZ. Shards are placed on ~48 storage nodes (NVMe instances, 16 vCPU, 128 GB RAM), four replicas per node, with leaders spread so no node leads more than two shards.
- **Placement:** `shard = hash(account_id) mod 1024 → virtual shard → physical shard`. 1,024 virtual shards map onto 64 physical ones, so we can split a hot physical shard by moving virtual shards without rehashing account IDs.
- **Affinity, opt-in:** a ledger's internal operational accounts (fee revenue, FX spread, clearing) are pinned to the same virtual shard. A "payment + fee" transaction then touches the wallet's shard and the ops shard, rather than three random shards.
- **Rebalancing** moves a virtual shard by snapshotting its state, streaming the log tail, then a brief write fence (< 100 ms) while ownership flips in the routing table. The routing table itself is a small Raft-replicated config service; gateways cache it and refresh on a `WRONG_SHARD` reply.

Per-shard load at 2M TPS with 75% cross-shard: roughly 3.4 log records per transaction on average, so ~7M records/s fleet-wide, ~110k records/s per shard. Single-threaded apply of a simple transfer is 1–2 µs, so a shard core is ~20% busy at peak. The 64-shard count is driven by fsync headroom and blast radius more than CPU — losing one shard leader affects 1.5% of accounts for ~1.5 s.

---

## 7. The viral merchant

The SRS describes the classic failure: millions of payments to one merchant, every one locking the merchant's balance row, the queue of waiters grows, timeouts cascade.

In this design that failure mostly doesn't exist, for two reasons stacked on top of each other:

1. **There are no locks.** The merchant's shard applies credits one after another in a single thread. A credit is an addition to a `long` in memory. A batch of 8,000 credits to the same merchant costs 8,000 additions and one log write.
2. **Credits to a merchant are increasing legs** (§4.2). They never need validation, never need a hold, never participate in PREPARE. The merchant's shard only sees them in the async COMMIT fan-out, already aggregated per batch: the coordinator side sends "merchant M +Σ for these 4,000 tx ids" as one message.

That handles a merchant up to roughly the throughput of one shard (~150k credit applications/s with headroom). Beyond that — the "everyone in the country is buying the same concert ticket" case — the merchant account is **split into buckets**: `bucket_count = N` sub-balances placed on N different virtual shards. Credits pick a bucket at random. The account's balance is the sum of buckets. Debits from a bucketed account (payouts, refunds) are rare and go through a coordinator that holds across all buckets — slow, but it's the uncommon direction.

The reference implementation in this repo does the bucket part on Postgres, which is where the row-lock problem genuinely exists, and the concurrency test pushes 32 threads at one merchant to show it (§14).

I didn't go with the SRS's suggestion of buffering writes in Redis ring buffers before committing to disk. If we ACK before the batch is durable, we've broken RPO = 0. If we ACK after, Redis is just an extra hop. Batching belongs inside the durable log, which is what the shard leader does (review F-5).

---

## 8. Idempotency

- **Scope:** `(ledger_id, idempotency_key)`. Keys are scoped per ledger so two tenants can't collide or probe each other's keys.
- **Binding:** the record stores a SHA-256 of the canonical request (ledger, ordered entries, amounts in minor units). Same key + different payload is a client bug, returned as `409 IDEMPOTENCY_KEY_REUSED`. Silently returning the old result there would hide the bug.
- **Authority:** the shard's idempotency index, written in the same log entry as the posting (§5.1). It's an LSM (RocksDB) per shard with per-SST bloom filters in memory, which is where the SRS's "Bloom" belongs: as a filter in front of a durable index, never on its own. A bloom filter can say "definitely not seen" but only "maybe seen", and "maybe" isn't good enough to decide whether to move money.
- **Fast path:** gateways write outcomes to Redis after the shard replies, and check Redis first on the way in. A Redis hit saves a round to the shard; a Redis miss falls through to the shard, which is still authoritative. Losing Redis entirely costs latency, never correctness.
- **Retention:** 7 days, then compacted out of the LSM by TTL. Sizing is in §10 — it's the largest state in the system, larger than balances by orders of magnitude.
- **In-flight duplicates:** two requests with the same key arriving 50 µs apart land in the same or adjacent batch on the same coordinator shard (the coordinator is deterministic from the request). Apply is serial, so the second one sees the first one's record. No distributed lock needed.

---

## 9. Ordering and clocks

The SRS is right that wall-clock `now()` can't order a distributed ledger. Here's what orders things instead:

- **Within a shard:** the Raft log index. Total order, no ambiguity, no clocks involved.
- **Across shards:** a Hybrid Logical Clock. Each HLC is 64 bits: 48 bits of physical milliseconds and 16 bits of logical counter. A shard stamps every batch with `hlc.now()`; every message between shards carries the sender's HLC and the receiver calls `hlc.update(remote)`, which guarantees the receiver's next timestamp is greater than anything it has causally seen. A cross-shard commit's timestamp is the max of the participants' prepare timestamps, so a transaction is always ordered after everything it depended on.
- **Clock sanity:** HLC is only as good as the bound on physical drift. Nodes run chrony against the cloud's time service (sub-millisecond on AWS), and `update()` refuses a remote timestamp more than 500 ms ahead of local physical time. Without that guard, one node with a clock set a year ahead would drag every HLC in the cluster forward with it, permanently (review F-8).
- **Closed timestamps:** each shard periodically publishes "nothing below HLC *t* will ever be applied here". The cluster-wide closed timestamp is the min across shards, typically 10–50 ms behind real time. Consistent snapshots and the reconciler read there.

Vector clocks, which the SRS also mentions, don't fit: their size grows with the number of nodes and they give a partial order, where a ledger wants a total one for statements and audits.

---

## 10. Capacity and storage

Assumptions: peak 2M TPS for one hour; daily average 300k TPS (15% of peak); 2.2 entries per transaction on average; 75% cross-shard.

| Item | Math | Result |
|---|---|---|
| Transactions per day | 300k × 86,400 | ~26 B |
| Entries per day | 26 B × 2.2 | ~57 B |
| Raft log write rate at peak | 2M × ~3.4 records × ~220 B | ~1.5 GB/s fleet, ~23 MB/s per shard |
| Journal in history store (row form, ~120 B/entry) | 57 B × 120 B | ~6.8 TB/day raw, ~1.5 TB/day compressed columnar |
| Journal 10-year archive (S3, compressed) | 1.5 TB × 3,650 | ~5.5 PB |
| Idempotency index (7 days, ~48 B/key) | 26 B × 7 × 48 B | ~8.7 TB fleet, ~136 GB per shard per replica |
| Idempotency bloom filters (10 bits/key) | 182 B keys × 10 bits | ~227 GB fleet, ~3.5 GB RAM per shard |
| Balances in memory | assume 500 M accounts × 64 B | ~32 GB fleet, ~0.5 GB per shard |

Two things stand out. First, balances are tiny; the expensive state is idempotency and the journal. Second, 26 B transactions a day is roughly 30–40x what the large card networks process. I've sized for it because the SRS asks, but this is the first number I'd confirm with the business, because the archive cost alone is significant (review F-12).

**Reads.** 5M point-balance QPS across 192 replicas is ~26k reads/s per replica, all from memory. Leaders serve linearizable reads under a leader lease; followers serve reads at their applied index with a staleness bound, which is what most balance-check callers actually want. History, statements and audits go to the history store and have their own SLO; they never touch shard leaders.

---

## 11. Availability and disaster recovery

### 11.1 Inside a region

- Each shard has one replica per AZ. Losing a node: affected shards elect a new leader in ~1.5 s (election timeout 500–1,000 ms, tuned for the AZ RTT). Losing a whole AZ: every shard still has 2 of 3, writes continue, RPO = 0.
- Deploys are rolling, one AZ at a time, with explicit leadership transfer before a node restarts, so a planned restart costs a leader handoff (~50 ms) instead of an election.
- The gateway is stateless; Envoy health-checks it and ejects outliers. Redis is a cache and can be lost.

### 11.2 Losing a region, and what RPO = 0 really costs

A synchronous quorum must include a second region for regional RPO = 0. Every commit then pays one inter-region RTT.

| Option | Regional RPO | Commit latency add | Verdict |
|---|---|---|---|
| A. 3 AZs in one region, async to DR region | seconds | 0 | Breaks the SRS for region loss |
| B. 5 replicas: 2 + 2 + 1 witness across 3 regions | 0 | 1 RTT to nearest other region | Works if the nearest region is < 8 ms away |
| C. 2 + 2 + 1 in a region pair with ≥ 20 ms RTT | 0 | ~20 ms | Breaks p99 < 25 ms on cross-shard transactions |

My recommendation is **B where geography allows it** (e.g. paired metro regions) and **A elsewhere, with the gap written down and signed off**, because the SRS's RPO and latency targets can't both hold across distant regions. This is the most important open question in the review (F-3).

Each ledger has a **home region**; all writes for that ledger go there. Cross-region active-active writes to the same account would need cross-region consensus on every transaction, and I'd rather not pretend otherwise.

### 11.3 The error budget

99.999% is about 26 seconds of total outage per 30 days. With 64 shards, one shard failing over for 1.5 s is 1.5 / 64 ≈ 0.02 s of equivalent full outage, so the budget survives hundreds of node failures a month. What eats it is correlated failure: a bad deploy, a bad config push, a routing-table bug. So: config and binaries roll out shard-by-shard with automatic rollback on error-rate or latency regression, and the routing table change process requires a dry-run diff.

---

## 12. Security, tenancy, and reconciliation

- **Tenancy:** a tenant owns ledgers. Every request carries a tenant identity (mTLS client cert or signed JWT), and the gateway rejects any `ledger_id` or `account_id` the tenant doesn't own before anything reaches a shard. Rate limits are per tenant at Envoy so one noisy tenant can't consume the batch capacity.
- **PII:** none in the ledger. Accounts reference external customer IDs; names and documents live elsewhere. This matters because the journal is immutable for ten years, and an immutable store can't honour an erasure request. Keeping PII out is the only clean answer (review F-7).
- **Tamper evidence:** per-shard hash chain (§4.3), archive segments in S3 with Object Lock in compliance mode.
- **Reconciliation, always on:** at every closed timestamp each shard emits per-ledger sums. A reconciler checks Σ = 0 per ledger, checks that the number of holds older than 5 s is zero, and periodically rebuilds a sample of account balances from the journal and compares. Any mismatch pages immediately. I'd rather find a bug in minutes from an invariant check than in a quarter-end audit.
- **Observability:** per-stage latency histograms (gateway, batch wait, Raft round, prepare, apply), batch fill ratio, cross-shard ratio, idempotency fast-path hit rate, hold age, closed-timestamp lag. Each response carries a trace ID that follows the transaction through the coordinator and participants.

---

## 13. Alternatives I looked at

**Sharded PostgreSQL / Aurora with SERIALIZABLE.** Familiar and well understood. But a single Postgres primary doing locked balance updates manages tens of thousands of TPS, not hundreds of thousands, so 2M TPS means well over a hundred primaries plus an application-level 2PC between them, plus the hot-row problem on every one. I'd pick this for 20k TPS. Not for 2M. It's what the reference implementation uses, because it's the clearest way to show the correctness rules.

**CockroachDB / YugabyteDB / Spanner.** They give distributed serializable transactions out of the box, and that's tempting. The cost is that every cross-range transaction does its own consensus rounds with no batching across transactions, and contended rows cause retries that get worse precisely when load is highest. Reaching 2M TPS would take a very large cluster. It's my fallback if the scale target turns out to be 10x lower.

**TigerBeetle.** The closest thing to this design that already exists: deterministic state machine, batched, debit/credit native, two-phase transfers, published throughput in the high hundreds of thousands to ~1M transfers/s per cluster. Its data model is intentionally narrow (fixed-size 128-bit fields, no arbitrary metadata) and a cluster is a single consensus group. If I were building this for real, I'd spike TigerBeetle as the engine *inside* each shard before writing our own state machine. The sharding, routing and 2PC layer in this design would still be needed on top.

**Kafka as the log, stream processors as the state machine.** Kafka with `acks=all` and `min.insync.replicas=2` is durable enough, and Kafka Streams has exactly-once processing. But the end-to-end p99 for produce → process → produce → consume is hard to keep under 25 ms, and state-store recovery after a failure takes minutes, not seconds. I use Kafka downstream for CDC, where those properties are fine.

---

## 14. What's in this repository

The repo contains a working reference implementation of **one shard's semantics** on PostgreSQL 16, with the same API as the SRS. It's the part where correctness is decided, built so it can be read and tested. It is not the 64-shard system and doesn't pretend to do 2M TPS.

| Design element | Where it lives in the code |
|---|---|
| Zero-sum, entry validation, amounts as minor units | `core/PostingValidator`, `core/Amounts` |
| Natural-side balances, "decreasing legs" rule (§4.2) | `core/AccountType`, `store/PostingEngine.planLocks` |
| Idempotency written atomically with the posting, rejections cached, payload binding (§5.1, §8) | `store/PostingEngine.claimKey` and the `idempotency_keys` table |
| No-overdraft under concurrency | `store/PostingEngine` locks rows in a fixed order, checks after locking |
| Hot account buckets (§7) | `account_buckets` table, `bucket_count` on account |
| HLC with skew guard; per-account causal order across app nodes (§9) | `core/HybridLogicalClock`, `last_hlc` on bucket rows |
| Append-only journal (§4.3) | `V1__ledger_schema.sql` trigger on `transactions` and `journal_entries` |
| Reversals as new transactions | `PostingEngine.reverse`, unique `reverses_transaction_id` |
| Trial balance (Σ = 0) | `LedgerStore.trialBalance`, `GET /v1/ledgers/{id}/trial-balance` |

The integration tests are the interesting part: concurrent transfers across 50 wallets with a trial-balance check at the end, 16 threads racing on one idempotency key, 32 threads hammering a bucketed merchant, and a replay of every no-overdraft account's journal in HLC order to prove the balance never dipped below zero at any point in history.

---

## 15. Open questions

1. Is 2M TPS a real sustained target or a stress ceiling? It drives the archive cost by an order of magnitude.
2. Which region pairs do we operate in? Decides between DR option A and B (§11.2).
3. Do we need authorisation holds as a first-class feature (card auth → capture)? The cross-shard hold mechanism already exists, so exposing it is cheap, but it changes the API.
4. Statement and audit query patterns — needed to pick the history store (ClickHouse vs partitioned Postgres vs Cassandra).
5. Who owns the routing table and shard split operations? That's a runbook and an on-call question as much as a design one.

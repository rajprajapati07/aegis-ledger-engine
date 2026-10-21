# Aegis Ledger — Design Guide (HLD, LLD, DB)

**Author:** Raj Prajapati

This is the plain-language companion to [`ARCHITECTURE.md`](ARCHITECTURE.md). It walks through every component and every major technology decision: what it is, why it's there, how it works, and what we gave up by choosing it.

One distinction runs through the whole guide:

- **HLD** (sections 2–3) is the full production design for 2M write TPS.
- **LLD and DB design** (sections 4–5) describe what is actually built in this repo: one shard's rules on PostgreSQL.

---

## Contents

1. [One payment, start to finish](#1-one-payment-start-to-finish)
2. [HLD: the components](#2-hld-the-components)
3. [Technology decisions and rejected alternatives](#3-technology-decisions-and-rejected-alternatives)
4. [LLD: the code](#4-lld-the-code)
5. [DB design](#5-db-design)
6. [Problem → solution map](#6-problem--solution-map)

---

## 1. One payment, start to finish

**Priya pays a merchant ₹500.**

```
Priya's app
   │  POST /v1/transactions   Idempotency-Key: pay-789
   │  DEBIT  priya_wallet   500.00
   │  CREDIT merchant       500.00
   ▼
[Envoy]          Is this a real client? Is it sending too fast?
   ▼
[Gateway]        Valid JSON? Debits = credits? Seen pay-789 before? Which shard?
   ▼
[Shard 12]       priya_wallet lives here. Groups this with ~8,000 other requests,
                 writes the batch to 3 data centres, waits until 2 have it on disk
   ▼
                 Checks Priya has ≥ ₹500 → yes → subtracts ₹500,
                 records "pay-789 = done" in the same write
   ▼
[Shard 40]       merchant lives here. Gets "+₹500" a few ms later (a credit can't fail)
   ▼
Priya's app      201 COMMITTED   (~5–12 ms after tapping Pay)
   ▼  (in the background)
[Kafka] → [History store] for statements, [S3 archive] for 10-year records,
[Reconciler] checks that all money still adds up to zero
```

Every box exists to solve a specific problem. The rest of this guide is about those problems.

---

## 2. HLD: the components

### 2.1 Envoy ingress proxy

**What:** The front door. Every request passes through it first.

**Why:** Three jobs that don't belong in ledger code:

- **TLS / mTLS:** encrypts traffic and verifies the client certificate.
- **Per-tenant rate limiting:** one tenant's buggy retry loop can't use up capacity meant for others.
- **Health checks:** unhealthy gateway pods stop receiving traffic.

**Example:** Tenant A suddenly sends 500k TPS because of a retry bug. Envoy caps it at its quota (say 200k) and returns `429` for the rest. Tenant B never notices.

**Trade-off:** One extra hop, roughly 0.3 ms. Worth it: doing TLS and rate limiting in Java would be slower and more error-prone.

**Alternatives:** AWS ALB, NGINX. Envoy wins on gRPC support and per-tenant rate limiting.

---

### 2.2 Ledger Gateway (stateless, ~40 pods)

**What:** A Java service between Envoy and the shards. It stores no data.

**Why:** It runs every check that doesn't need the database, so bad requests never reach the expensive part.

**How:**

1. **Authorise:** does this tenant own `ledger_id`?
2. **Validate:** at least 2 entries, all amounts > 0, total debits = total credits.
3. **Idempotency fast path:** ask Redis whether `pay-789` has been seen.
4. **Route:** work out which shard owns each account.

**Why stateless:** Any pod can serve any request. If one dies, Envoy routes around it. Scaling is adding pods.

**Example:** Debits ₹500, credits ₹499 → rejected here with `UNBALANCED` in under 1 ms. No shard sees it.

**Trade-off:** One more service to deploy. Without it, every shard would spend CPU rejecting malformed requests.

---

### 2.3 Redis: idempotency fast path (a cache, never the authority)

**What:** An in-memory cache of recently completed idempotency keys and their results.

**Problem it solves:** Mobile networks drop responses. The app times out and retries `pay-789`. The ledger must return the **first** result, not post a second ₹500.

**How:**

- Key found in Redis → return the cached result and skip the shard.
- Key not found → **continue to the shard**, which decides.

**The rule that matters:** a Redis miss does **not** prove the key is new. Redis can lose recent writes on failover. The authoritative record is in the shard, written in the **same** durable write as the money movement.

**What goes wrong otherwise** (review finding F-1):

```
1. Shard moves ₹500                         ✅ on disk
2. Save "pay-789 = done" in Redis            ❌ Redis fails over, write lost
3. Client retries pay-789
4. Redis: "never seen it" → if trusted: ₹500 moves AGAIN 💥
```

**Trade-off:** Idempotency lives in two places. Redis is fast but can be wrong; the shard is always right. Losing Redis entirely costs latency, never money.

---

### 2.4 Routing table: which shard owns which account

**What:** A small replicated mapping: `account → virtual shard → physical shard`.

```
virtual_shard  = hash(account_id) mod 1024
physical_shard = routing_table[virtual_shard]     // 1024 entries → 64 shards
```

**Why 1024 virtual shards over 64 physical ones:** When a shard runs hot, a few virtual shards move to a new machine. Account IDs never get rehashed; only the routing table changes.

**Example:** Before a festival sale, shard 12 is at 80% CPU. Virtual shards 200–215 move to a new shard 64. Those accounts see a write pause of under 100 ms; nothing else is affected.

**Trade-off:** Gateways cache the table and can be briefly out of date. A shard replies `WRONG_SHARD`, the gateway refreshes and retries.

**Opt-in affinity:** A ledger's internal accounts (fee revenue, FX spread) are pinned to one virtual shard, so a "payment + fee" transaction touches 2 shards instead of 3.

---

### 2.5 Shards: the core of the system

**What:** 64 independent groups. Each is a **3-node Raft group, one node per availability zone**, running a **single-threaded deterministic state machine**.

#### (a) Raft group of 3: durability and failover

- One node is the **leader** and accepts writes; two are **followers**.
- The leader sends each write to the followers. Once **2 of 3** have it on disk, it is committed.
- If the leader dies, the followers elect a new one in about 1.5 s.

**Why 3 and not 2:** With 2 nodes, neither can tell whether the other is dead or just unreachable. With 3, the side with a majority (2) wins. That's what keeps RPO = 0 through the loss of an AZ.

**Why one per AZ:** An AZ is a separate data centre with independent power and network. Losing one leaves 2 of 3 copies for every shard.

#### (b) Single-threaded: no locks

**The usual problem:** 1,000 threads all update the same merchant balance. A database gives each a row lock, and they queue: waits, timeouts, deadlocks.

**Here:** one thread per shard applies requests **in order**. Nothing else touches the data, so nothing needs a lock. An update is `balance += 500` on a `long` in memory, about 1–2 µs.

**Analogy:** 100 tellers sharing one cash drawer keep passing the key around. One teller who never stops, with a queue in front, has nothing to wait for.

#### (c) Deterministic state machine

All 3 replicas receive **the same batches in the same order**, run **the same code**, and end up with **identical balances**. Nobody has to ask another node what the result was.

#### (d) Batching: where the throughput comes from

One durable write to 2 data centres takes ~2.5 ms. One request at a time, that's ~400 TPS per shard.

The leader **collects requests for up to 1 ms (or 8,190 requests)** and writes them as **one** log entry.

```
Without batching:  8,000 requests × 1 disk write each   = slow
With batching:     8,000 requests × 1 disk write total  = fast
```

**Trade-off:** A request waits up to 1 ms for its batch. Under load, batches fill almost instantly; at low load, the cap bounds the wait.

**Why not buffer writes in Redis first** (as the SRS suggests): acknowledging before the batch is durable loses it on a crash (RPO > 0); acknowledging after makes Redis a pointless extra hop. Batching belongs **inside** the durable write path (review finding F-5).

---

### 2.6 Cross-shard transactions: 2PC only where needed

**Problem:** The payer's wallet is on shard 12, the merchant is on shard 40. Both changes must happen, or neither.

**The insight that keeps this cheap:**

> Only legs that **reduce** a protected account can fail. A credit can **never** fail; adding money can't overdraw anything.

```
Shard 12 (coordinator, holds the debit):
  ① write BEGIN + HOLD ₹500 on the payer's wallet
  ② if other shards also have debits: PREPARE them, wait for PREPARED
  ③ write COMMIT
  ④ reply 201 COMMITTED
  ⑤ after replying: send "+₹500" to shard 40
Shard 40:
  apply +₹500 (cannot fail)
```

**Why the coordinator is a Raft group:** Classic 2PC blocks forever if the coordinator dies mid-protocol. Here the coordinator's state is in its Raft log; a new leader picks it up within ~1.5 s and finishes.

**Visibility gap and how it's handled:** for a few ms after the client gets success, a reader on shard 40 might not see the ₹500 yet.

- Every response carries `commit_hlc`. A client can read "as of at least this timestamp", and the read waits until it's applied (read-your-writes).
- Audits read at a **closed timestamp**, a point every shard has fully caught up to, so totals always balance.

**Trade-off:** Two consensus rounds synchronously (~11.5 ms vs ~5.5 ms same-shard). Still within p95 15 ms; cross-shard is the case that decides p99.

**Holds never expire silently.** A hold older than 5 s pages someone. A hold quietly expiring after the coordinator committed would return the money *and* pay the merchant, creating money.

---

### 2.7 Hot accounts ("viral merchant") and buckets

**Problem:** A ticket sale. 100,000 people per second pay the **same** merchant. On a normal database, that's 100,000 transactions queuing on one row lock.

**Two layers:**

1. **In the sharded design it mostly disappears.** Credits are additions on a single thread, with no locks, and they skip PREPARE because they can't fail. One shard absorbs roughly 150k credit applications/s.
2. **Beyond one shard: buckets.** Split the merchant balance into N sub-balances:

   ```
   merchant balance = bucket0 + bucket1 + ... + bucket15
   each payment → credit a random bucket
   ```

**Trade-offs:**

- Reading the balance means summing N buckets (cheap).
- **Debits** from the merchant (payouts, refunds) must lock or check **all** buckets (slow), which is acceptable because payouts are rare.
- Bucket count is chosen at account creation, so it's an operational decision.

**Built and measured in this repo** on Postgres, where the lock problem is real (32 threads, `synchronous_commit` on, 2-vCPU sandbox):

| Scenario | TPS | p99 |
|---|---|---|
| One merchant, 1 bucket | 402 | 465 ms |
| One merchant, 16 buckets | 1,298 | 96 ms |

3x the throughput and 5x better tail latency from one design change.

---

### 2.8 Hybrid Logical Clock (HLC)

**Problem:** Server A's clock reads 10:00:00.005, server B's reads 10:00:00.003, but B's event actually happened **after** A's. Clocks drift by milliseconds, so sorting by `now()` gives the wrong order: wrong statements, broken audits.

**What an HLC is:** a 64-bit value:

```
[ 48 bits: wall-clock milliseconds ][ 16 bits: counter ]
```

- **Local event:** if the wall clock moved forward, use it with counter 0; otherwise keep the milliseconds and add 1 to the counter.
- **Message from another node:** jump to max(mine, theirs) and add 1.

Anything that causally follows something else always gets a bigger number, even with skewed clocks.

```
Shard 12 HLC = 1000.5, sends COMMIT to shard 40
Shard 40's clock is behind: 998.0
Shard 40 receives → update(1000.5) → 1000.6   ✅ ordered after shard 12
```

**Skew guard (review finding F-8):** a remote timestamp **more than 500 ms ahead** of the local wall clock is refused and alerted on. An HLC always adopts the largest time it has seen; one node with a clock set a year ahead would drag the whole cluster a year forward, permanently.

**Within a shard,** the Raft log index is the true order. HLC is only for ordering across shards.

**Why not vector clocks:** their size grows with node count and they give only a partial order. Statements need a total order.

---

### 2.9 Kafka: change data capture

**What:** Every committed batch from every shard goes to a Kafka topic, keyed by shard so each shard's order is preserved.

**Why:** Statements, audit queries, analytics and archival all need the journal, and none of them should read from shard leaders.

**Why Kafka isn't the primary log:** produce → process → produce → consume makes p99 < 25 ms very hard, and stream-processor recovery takes minutes. Downstream, a few seconds of delay is fine.

---

### 2.10 History store and S3 WORM archive

- **History store** (ClickHouse or partitioned Postgres): range queries like "all transactions on this account in March". Optimised for scans, not point lookups.
- **S3 Object Lock, compliance mode, 10-year retention:** files can't be changed or deleted, even by an admin.
- **Hash chain:** each batch carries the SHA-256 of the previous one. Editing an old record breaks the chain and is detectable.

**Trade-off:** ~5.5 PB over 10 years at the SRS volume. That's a real cost, and the reason review finding F-12 asks for daily volume.

---

### 2.11 Reconciler

**What:** A background job that checks, at each closed timestamp:

1. Σ balances per ledger = 0 (no money created or destroyed).
2. No hold older than 5 s.
3. A sample of balances rebuilt from the journal matches the stored balances.

**Why:** Bugs will happen. Better to find one from an alert in minutes than from an auditor at quarter-end.

---

### 2.12 High availability and disaster recovery

```
Region
 ├── AZ-a: shard replicas + gateways
 ├── AZ-b: shard replicas + gateways      each shard: one replica per AZ
 └── AZ-c: shard replicas + gateways      write succeeds after 2 of 3 AZs

Lose 1 server  → new leader in ~1.5 s, RPO 0
Lose 1 AZ      → 2 of 3 remain, writes continue, RPO 0
Lose a region  → RPO 0 only with a second region < ~8 ms away (5 replicas across both);
                 otherwise a small non-zero regional RPO, documented and signed off (F-3)
```

**Why each ledger has a home region:** writing the same account from two regions would need cross-region consensus on every payment, which breaks the latency target.

---

## 3. Technology decisions and rejected alternatives

| Decision | Chosen | Rejected | Why |
|---|---|---|---|
| Core engine | Sharded Raft + single-threaded state machine | Sharded Postgres with SERIALIZABLE | Locked balance updates give tens of thousands of TPS per primary; 2M needs 100+ primaries plus home-built 2PC |
| | | CockroachDB / YugabyteDB / Spanner | No batching across transactions; contended rows retry more as load rises. Fallback if the real target is 10x lower |
| | Spike TigerBeetle per shard first | Writing a state machine from scratch | TigerBeetle already does batched, deterministic debit/credit; sharding and 2PC still go on top |
| | | Kafka as the primary log | p99 too high, recovery too slow |
| Consistency | Serial per shard + 2PC + closed timestamps | "SERIALIZABLE everywhere" | We need the invariants (no overdraft, atomicity, zero-sum), not an isolation level. SSI aborts under contention, exactly when load peaks (F-2) |
| Money type | `int64` minor units | `double`, `BigDecimal` in storage | `0.1 + 0.2 ≠ 0.3` in floating point. int64 holds ±92 quadrillion dollars in cents; exact and fast |
| Currency | One currency per ledger | Mixed currencies per transaction | 100 USD vs 92 EUR isn't balanced or unbalanced, just incomparable. FX = two linked transactions + spread (F-6) |
| Idempotency | Same durable write as the posting | Redis / Bloom as the authority | Redis can lose keys; a Bloom filter only says "maybe". Both can double-post (F-1) |
| Ordering | Raft index in a shard, HLC across shards | `now()`, vector clocks | Clocks drift; vector clocks give a partial order |
| Durability | 3 AZs, quorum of 2 | Async replication | Async means data loss on failover (RPO > 0) |
| Language | Java 21, ZGC | Go, Rust | Team skills, sub-ms GC pauses, virtual threads in the gateway. Rust would be faster on the hot path, which is part of why a TigerBeetle spike is worth doing |
| Downstream | Kafka exactly-once | Polling the database | Ordered per shard, replayable, decouples readers from writers |

---

## 4. LLD: the code

### 4.1 Package layout

```
com.aegis.ledger
├── core/     pure Java — no Spring, no database
│   ├── AccountType         normal side of each account type
│   ├── Direction           DEBIT / CREDIT
│   ├── Amounts             "150.00" ⇄ 15000, ISO 4217 exponents
│   ├── PostingValidator    zero-sum, entry count, amount > 0, overflow
│   ├── HybridLogicalClock  48+16 bit HLC with skew guard
│   ├── RequestFingerprint  SHA-256 of the canonical request
│   ├── Ids                 time-ordered IDs (ULID layout)
│   ├── ErrorCode           stable codes, HTTP status, "cache this rejection?"
│   └── PostingRequest / EntryRequest / PostedTransaction
├── store/    JDBC — all database work
│   ├── PostingEngine       the only class that moves money
│   └── LedgerStore         ledgers/accounts setup, balances, trial balance
├── api/      Spring MVC controllers, DTOs, error mapping
└── config/   wires core + store into Spring
```

**Why `core` and `store` don't depend on Spring:** money logic is testable without a Spring context and could move into a real shard engine later. Integration tests build `new PostingEngine(dataSource, clock, ...)` directly.

**Why plain JDBC, not JPA:** in a ledger the SQL *is* the correctness, especially `FOR UPDATE` and its ordering. JPA hides SQL, picks its own flush timing, and makes locking hard to review. Six tables are easy to manage in JDBC.

---

### 4.2 Account types and the normal side

```java
public enum AccountType {
    ASSET(DEBIT), EXPENSE(DEBIT),                        // debit raises the balance
    LIABILITY(CREDIT), EQUITY(CREDIT), REVENUE(CREDIT);  // credit raises the balance

    public long naturalDelta(Direction d, long amount) {
        return d == normalSide ? amount : -amount;
    }
}
```

**Example:** a customer wallet is a LIABILITY (the platform owes the customer).

- CREDIT ₹500 → balance **+500**
- DEBIT ₹500 → balance **−500**

**Why store balances this way:** `allow_negative = false` then means one thing for every type: the stored number can't drop below 0.

**This is how review finding F-4 was found:** the SRS example debits a brand-new LIABILITY wallet at balance 0 and shows `COMMITTED`. Under these rules 0 − 150 = −150, an overdraft, so it must be rejected. `PostingEngineIT.srsExampleIsRejectedOnAFreshWallet` asserts this.

---

### 4.3 Amounts are integers, never rounded

```java
Amounts.toMinor("150.00",  "USD")  → 15000
Amounts.toMinor("150",     "JPY")  → 150     // yen has no minor unit
Amounts.toMinor("1.500",   "BHD")  → 1500    // dinar has 3 decimals
Amounts.toMinor("150.001", "USD")  → VALIDATION_FAILED   // never guess
Amounts.toMinor("1e2",     "USD")  → VALIDATION_FAILED   // no scientific notation
```

**Why reject instead of round:** either the client has a bug or means something we don't understand. Silent rounding moves someone's money by a fraction every time.

---

### 4.4 Validate before touching the database

`PostingValidator.validate(request)` checks:

- ledger ID and idempotency key present (key ≤ 128 chars)
- 2 to 64 entries
- every amount > 0 (a negative "debit" is really a credit and would sneak past the rules)
- totals via `Math.addExact`, so overflow throws instead of wrapping
- **Σ debits = Σ credits**, else `UNBALANCED`

**Why before claiming the idempotency key:** a client that sends a typo, fixes it, and resends **with the same key** must succeed. Validation errors don't burn the key (`validationErrorsDontBurnTheKey`).

---

### 4.5 PostingEngine: one payment, step by step

`engine.post(request)` runs **one Postgres transaction**:

```
① claimKey()       INSERT idempotency row as IN_FLIGHT
                    └─ key already live? → replay() and stop
② loadAccounts()    every account exists and belongs to this ledger
③ planLocks()       net legs per account; decide which bucket rows to lock
④ lockBuckets()     SELECT ... FOR UPDATE, ordered by (account_id, bucket)
⑤ overdraft check   protected account going down: available + delta ≥ 0 ?
⑥ clock.update(max last_hlc of locked rows)  → commit HLC
⑦ writeBalances()   UPDATE account_buckets
⑧ writeJournal()    INSERT transactions + journal_entries
⑨ markCommitted()   UPDATE idempotency row → COMMITTED
⑩ COMMIT            everything becomes visible together, or nothing does
```

#### Step ① claim the idempotency key

```sql
INSERT INTO idempotency_keys (..., status = 'IN_FLIGHT', expires_at = now() + 7 days)
ON CONFLICT (ledger_id, idempotency_key) DO UPDATE ...
   WHERE idempotency_keys.expires_at <= now()   -- only take over an EXPIRED key
RETURNING 1
```

- Row returned → we own the key, continue.
- Nothing returned → the key is live; read it and **replay**.

**Concurrent duplicates:** two copies of `pay-789` arrive 50 µs apart. The second `INSERT` **waits** on the unique index until the first transaction commits or rolls back, then sees the finished record and replays it. No distributed lock. `ConcurrencyIT.aStormOfRetriesWithOneKeyPostsExactlyOnce` fires 16 threads at one key across two engine instances and asserts exactly one posting.

**Replay rules:**

| Stored status | Same request hash? | Client gets |
|---|---|---|
| COMMITTED | yes | original transaction, `200` + `Idempotent-Replayed: true` |
| REJECTED | yes | the original error (e.g. `INSUFFICIENT_FUNDS`) |
| any | **no** | `409 IDEMPOTENCY_KEY_REUSED` |
| IN_FLIGHT | yes | `503 CONTENTION`, retry shortly |

**Why compare a request hash:** reusing `pay-789` for a different ₹600 payment is a client bug. Silently returning the old ₹500 result would hide it.

**Why cache rejections:** a customer with ₹100 tries ₹500 with `pay-789` and gets `INSUFFICIENT_FUNDS`. They top up and retry `pay-789`: same rejection. "Same key = same outcome" only holds if failures are remembered too; a new attempt needs a new key.
*Trade-off:* clients must know this rule. It's documented in the API and tested (`aRejectionIsRememberedForItsKey`).

#### Step ③ planLocks: what keeps hot accounts cheap

```java
net     = sum of naturalDelta per account     // debit + credit on one account cancel out
checked = (net < 0) && !allowNegative          // only a protected account going DOWN

if (checked)  lock ALL buckets                 // need the full balance for the check
else          lock ONE random bucket           // a credit can't fail; any bucket works
```

**Example:** customer pays a 16-bucket merchant.

- Wallet: net −50000, protected → `checked = true` → lock its one bucket.
- Merchant: net +50000 → `checked = false` → lock **one random bucket**, say #7.

1,000 concurrent payers spread across 16 rows instead of queuing on 1.

#### Step ④ lockBuckets: ordered locking prevents deadlocks

```sql
SELECT b.account_id, b.bucket, b.balance, b.last_hlc
  FROM account_buckets b
  JOIN unnest(?::text[], ?::int[]) AS t(account_id, bucket) ON ...
 ORDER BY b.account_id, b.bucket
   FOR UPDATE OF b
```

**A deadlock:**

```
Tx1: locks A → wants B
Tx2: locks B → wants A        both wait forever
```

**The fix:** every posting locks rows in the **same global order** (account ID, then bucket), so a cycle can't form. Doing it in one statement means Postgres enforces the order, not a Java loop. The engine still retries on `40P01` as a backstop.

#### Step ⑤ overdraft check, after locking

```java
if (available + plan.naturalDelta() < 0) → INSUFFICIENT_FUNDS
```

**Why after the lock:** check-then-lock lets two ₹500 withdrawals from ₹600 both pass and both apply, leaving −₹400. Checking **after** `FOR UPDATE` means nobody can change the row between the check and the write.

#### Step ⑥ HLC after locking

```java
long commitHlc = clock.update(maxSeenHlc);   // max last_hlc of the rows just locked
```

Two app instances run with separate clocks. The last writer to this wallet may have been the other instance with a slightly faster clock. Feeding its `last_hlc` into our clock guarantees a **greater** timestamp, so per-account history in HLC order matches the real order. `ConcurrencyIT` replays every wallet's journal sorted by HLC and asserts the balance never dips below zero at any point.

#### Step ⑦ spreading a debit across buckets

When a multi-bucket protected account is debited (a merchant payout), `spread()` drains the fullest buckets first so no single bucket goes negative:

```
buckets [#3: 900, #7: 500, #1: 100], payout 1,200
→ #3: −900, #7: −300, #1 untouched
```

#### Step ⑩ one COMMIT

The posting, the balances and the idempotency record become durable **together**: the HLD's "no gap" property, on Postgres.

#### Retries and timeouts

- `SET LOCAL lock_timeout = '2000ms'`: never wait indefinitely.
- Up to **4 attempts** with random backoff on `40001` (serialisation), `40P01` (deadlock), `55P03` (lock timeout).
- After 4 → `503 CONTENTION`; the client retries with the **same key**, which is safe.

**Why READ COMMITTED, not SERIALIZABLE:** the only data-dependent decision is the overdraft check, and it runs on rows held `FOR UPDATE`. SERIALIZABLE would add unpredictable aborts under load. *Trade-off:* discipline. Any new check must lock first, which is why all money movement lives in one class.

---

### 4.6 Reversals

```java
engine.reverse(transactionId, "undo-pay-789")
```

- Builds a **new** transaction with every leg flipped.
- Sets `reverses_transaction_id = original`.
- The original is **never touched**.

**At most once:** a pre-check returns `ALREADY_REVERSED`; the `UNIQUE` constraint on `reverses_transaction_id` catches two reversals racing.

**A reversal can fail like any posting:** if the recipient already spent the money, reversing would overdraw them, so it returns `INSUFFICIENT_FUNDS`. A correction must never create money.

---

### 4.7 IDs

`tx_01JB6Z3K8Q5N2W7XGQ4Y9R1T0M`

- Prefix (`tx_`, `acc_`, `led_`) makes IDs readable in logs.
- First 10 characters are a timestamp, last 16 are random (ULID layout).

**Why time-ordered:** new IDs land at the right edge of the B-tree, so inserts append instead of splitting pages all over the index the way random UUIDs do. For an ever-growing journal this matters.

---

### 4.8 API

| Endpoint | Notes |
|---|---|
| `POST /v1/ledgers` | `201` |
| `POST /v1/accounts` | `allow_negative` is **required**, no default; getting it wrong on a wallet is how users get overdrawn |
| `POST /v1/transactions` | key from `Idempotency-Key` header (preferred) or body. `201` new, `200` + `Idempotent-Replayed: true` on retry |
| `POST /v1/transactions/{id}/reversals` | header key required |
| `GET` account / transaction / trial balance | |

Errors are always `{ "error": "INSUFFICIENT_FUNDS", "message": "..." }`, with stable codes clients can switch on.

**Why `201` vs `200`:** the SRS returns `200` for both, so a client can't tell "my call did something" from "my call was a duplicate" (F-15).

**Config:** Hikari pool of 32 (more connections than cores just means more lock waiters; tune with the bench), Flyway migrations at startup, Jackson snake_case to match the SRS JSON.

---

## 5. DB design

### 5.1 Tables

```
ledgers 1 ──< accounts 1 ──< account_buckets            (balances: mutable)
   │              │
   │              └──< journal_entries >── transactions   (journal: append-only)
   │                                          │
   └───────────── idempotency_keys ───────────┘ (points to transaction_id)
```

### 5.2 Columns and why

#### `ledgers`

| Column | Why |
|---|---|
| `ledger_id` TEXT PK | `led_...` |
| `name` UNIQUE | e.g. `US_WEST_FINCORE` |
| `currency` CHAR(3) | **one currency per ledger** (F-6) |

#### `accounts`

| Column | Why |
|---|---|
| `account_id` PK | |
| `ledger_id` FK | an account belongs to exactly one ledger; checked on every posting (`LEDGER_MISMATCH`) |
| `type` CHECK in (ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE) | the database rejects typos |
| `allow_negative` | wallets false; reserves/equity true |
| `bucket_count` 1–256 | 1 normally, 16+ for hot merchants |

No names, phone numbers or tax IDs. Accounts reference external customer IDs. The journal is immutable for 10 years and can't honour an erasure request under GDPR or India's DPDP Act, so PII stays out (F-7).

#### `account_buckets`: balances, the only table updated in place

| Column | Why |
|---|---|
| `(account_id, bucket)` PK | one row per bucket |
| `balance` BIGINT | minor units, natural orientation |
| `last_hlc` BIGINT | HLC of the last transaction to touch the row (step ⑥) |
| `last_tx_id` | debugging: who last changed it |

**Why separate from `accounts`:** account details almost never change; balances change constantly. Keeping them apart keeps hot rows small, and splitting a hot account is just more rows here.

#### `transactions`: append-only

| Column | Why |
|---|---|
| `transaction_id` PK | time-ordered ID |
| `ledger_id` | |
| `commit_hlc` BIGINT | the global order for audits and replay |
| `idempotency_key` | traceability to the originating request |
| `reverses_transaction_id` **UNIQUE** FK | links a reversal to its original; at most one reversal each |
| `committed_at` | wall-clock time for humans, **never** used for ordering |

#### `journal_entries`: append-only

| Column | Why |
|---|---|
| `(transaction_id, seq)` PK | entry order within a transaction |
| `account_id` FK | |
| `direction` CHECK DEBIT/CREDIT | |
| `amount` BIGINT CHECK > 0 | the database itself refuses zero or negative amounts |
| index `(account_id, transaction_id)` | fast per-account statements |

#### `idempotency_keys`

| Column | Why |
|---|---|
| `(ledger_id, idempotency_key)` PK | **scoped per ledger**; tenants can't collide or probe each other's keys |
| `request_hash` BYTEA | SHA-256 to detect key reuse with a different payload |
| `status` IN_FLIGHT / COMMITTED / REJECTED | the state machine from step ① |
| `transaction_id` | COMMITTED → the result |
| `error_code`, `error_message` | REJECTED → the stored answer |
| `expires_at` + index | 7-day window; expired keys are reusable; purge job uses the index |

**Why in the same database as the journal:** so the key and the posting commit in **one** transaction. That single decision is what prevents double-posting.

### 5.3 Immutability, enforced twice

```sql
CREATE TRIGGER journal_entries_append_only
  BEFORE UPDATE OR DELETE ON journal_entries
  FOR EACH ROW EXECUTE FUNCTION reject_journal_mutation();

CREATE TRIGGER journal_entries_no_truncate          -- TRUNCATE skips row triggers
  BEFORE TRUNCATE ON journal_entries
  FOR EACH STATEMENT EXECUTE FUNCTION reject_journal_mutation();
```

plus [`deploy/grants.sql`](../deploy/grants.sql): the app role gets only `SELECT, INSERT` on journal tables.

**Why both:** grants drift ("give it ALL just for this hotfix"); the trigger is the backstop. The TRUNCATE trigger is easy to forget because row triggers don't fire on TRUNCATE.

**Trade-off:** fixing a real mistake requires a reversal, never an edit. For a ledger, that's the point.

### 5.4 Worked example: the rows during a ₹500 payment

**Before** (wallet holds ₹1,000; merchant has 16 buckets):

```
account_buckets
 acc_priya     | 0 | 100000 | last_hlc 1000.3
 acc_merchant  | 7 | 250000 | last_hlc 1000.9
```

**The transaction** (key `pay-789`):

```
idempotency_keys  INSERT (led_in, pay-789, sha256…, IN_FLIGHT)
account_buckets   lock acc_merchant/7 and acc_priya/0  (sorted order)
                  check: 100000 − 50000 = 50000 ≥ 0 ✅
                  commit_hlc = update(max(1000.3, 1000.9)) = 1001.0
account_buckets   acc_priya/0    → 50000,  last_hlc 1001.0
                  acc_merchant/7 → 300000, last_hlc 1001.0
transactions      INSERT tx_01JB… | led_in | 1001.0 | pay-789 | NULL
journal_entries   INSERT tx_01JB…, 0, acc_priya,    DEBIT,  50000
                  INSERT tx_01JB…, 1, acc_merchant, CREDIT, 50000
idempotency_keys  UPDATE → COMMITTED, transaction_id = tx_01JB…
COMMIT
```

**Trial balance** (Σ ASSET/EXPENSE − Σ LIABILITY/EQUITY/REVENUE) is still **0**.

---

## 6. Problem → solution map

| Problem | HLD (production) | LLD (this repo) | Proven by |
|---|---|---|---|
| Money created or lost | zero-sum at gateway, reconciler | `PostingValidator`, trial balance | every concurrency test ends at trial balance 0 |
| Double-posting on retry | idempotency in the same Raft entry | key claimed in the same DB transaction | 16-thread retry storm → 1 posting |
| Overdraft under concurrency | serial apply per shard | `FOR UPDATE` in sorted order, check after locking | 32 threads, 50 wallets, none negative |
| Deadlocks | none (single thread) | global lock order, retry as backstop | concurrency suite |
| Viral merchant | lock-free credits + buckets | random-bucket credits | bench: 3x TPS, 5x better p99 |
| Clock skew | HLC + 500 ms guard + closed timestamps | `HybridLogicalClock` | "clock a year ahead" unit test |
| Wrong history order | Raft index + HLC | `last_hlc` fed into the clock | HLC-order replay never negative |
| Rewriting history | Raft log + WORM + hash chain | triggers + grants | `theJournalIsAppendOnly` |
| Data loss | 2-of-3 AZ quorum before ACK | `synchronous_commit` on | — |
| Rounding errors | int64 minor units | `Amounts` rejects extra precision | `AmountsTest` |
| Noisy tenant | Envoy per-tenant limits | — | — |
| Losing Redis | cache only | not built (single DB) | — |

### In one paragraph

Each shard is a single-threaded deterministic state machine replicated with Raft across three AZs, so there are no locks, and an acknowledgement means durable in two data centres. Batching provides the throughput. Cross-shard payments use 2PC with Raft-backed coordinators, and only debit legs participate because credits can't fail, which is also why hot merchants are cheap, with buckets beyond one shard. Idempotency is written in the same log entry as the money movement, so there's no double-post window. Ordering uses the Raft index within a shard and an HLC with a skew guard across shards. This repo implements and load-tests one shard's rules on Postgres to prove the correctness pieces.

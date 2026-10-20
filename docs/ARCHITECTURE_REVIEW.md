# Aegis SRS — Architecture Review

**Reviewer:** Raj Prajapati
**Document reviewed:** Project Aegis SRS v1.0.0 (Ultra-Scale Core Financial Ledger Engine)
**Companion:** [`ARCHITECTURE.md`](ARCHITECTURE.md) — the design that answers these findings

---

## Why this review exists

The SRS is a good starting point. It names the right problems: double-entry, idempotency, the hot merchant, clock skew. But several of its requirements, read literally, either contradict each other or push the implementation toward choices that would quietly break the guarantees the document cares about most.

I've written each finding the same way: what the SRS says, why it's a problem, and what I recommend. They're ordered by blast radius — how much damage the issue does if we build exactly what's written.

## Summary

| # | Finding | Severity | Area |
|---|---|---|---|
| F-1 | Idempotency backed by Redis/Bloom can double-post after a failover | **Critical** | Correctness |
| F-2 | SERIALIZABLE everywhere + 2PC on every transaction can't reach 2M TPS | **Critical** | Feasibility |
| F-3 | RPO = 0, p99 < 25 ms and multi-datacenter can't all hold | **Critical** | HA / DR |
| F-4 | The SRS's own example transaction would be rejected | High | Domain rules |
| F-5 | Batching writes in Redis before disk breaks RPO = 0 | High | Correctness |
| F-6 | "Currency-agnostic" zero-sum isn't defined per currency | High | Domain rules |
| F-7 | "No UPDATE anywhere" conflicts with balances and with erasure law | Medium | Data model |
| F-8 | HLC needs a drift bound; vector clocks don't fit | Medium | Ordering |
| F-9 | 99.999% defined as wall-clock minutes isn't measurable | Medium | SLOs |
| F-10 | 5M read QPS lumps balance checks and audits together | Medium | Read path |
| F-11 | Latency is measured at the wrong boundary | Low | SLOs |
| F-12 | 2M TPS has no daily-volume context; storage cost is unbounded | Medium | Capacity |
| F-13 | Missing domain features: holds, account lifecycle, limits | Medium | Scope |
| F-14 | Missing operational requirements: tenancy, reconciliation, retention | Medium | Scope |
| F-15 | API contract gaps | Low | API |

---

## F-1 · Idempotency backed by Redis/Bloom can double-post after a failover

**Severity:** Critical

**What the SRS says.** §4.3 shows the flow `Ingress → Idempotency Layer (Redis Bloom/Cache check) → Core Ledger Processing → Persist State → Save Response`.

**Why it's a problem.** Three separate ways this goes wrong:

1. *Ordering.* The response is saved after state is persisted, as a separate step. If the process dies between "persist state" and "save response", the money moved and the key wasn't recorded. The client times out, retries with the same key, and the ledger posts it a second time. This is exactly the scenario idempotency exists to prevent.
2. *Durability.* Redis with default persistence loses the last second or so of writes on failover. Even with `appendfsync always`, a replica promoted after an async replication lag has missing keys. Any key lost this way can double-post on retry. That's a violation of RPO = 0 by another route.
3. *Bloom semantics.* A Bloom filter answers "definitely not present" or "possibly present". "Possibly present" can't be the basis for refusing a payment, and "not present in Redis" can't be the basis for accepting one, because Redis might have lost the key.

There's also no statement about what happens when the same key arrives with a *different* payload, or whether rejected transactions are cached.

**Recommendation.**
- The idempotency record is written atomically with the posting: same database transaction, or same Raft log entry. Not before, not after.
- Store a hash of the canonical request with the key. Same key + different payload returns `409`, never the old result.
- Cache rejections too (e.g. insufficient funds), so "same key → same outcome" holds.
- Redis may sit in front as an accelerator. A hit returns early; a miss falls through to the durable store. Losing Redis costs latency, nothing else.
- Scope keys per ledger (or tenant), not globally.

Implemented in this repo: `PostingEngine.claimKey` inserts the key in the same Postgres transaction as the entries, and `idempotency_keys` holds `request_hash` and the cached outcome.

---

## F-2 · SERIALIZABLE everywhere + 2PC on every transaction can't reach 2M TPS

**Severity:** Critical

**What the SRS says.** §4.1: multi-account transactions use 2PC or Raft consensus; isolation equivalent to SERIALIZABLE everywhere to prevent race conditions, dirty reads and write skew.

**Why it's a problem.** The intent is correct; the mechanism isn't. A database-level SERIALIZABLE isolation (Postgres SSI, or a distributed SQL engine) detects conflicts and aborts transactions. Under contention — which is exactly what the hot merchant creates — abort rates climb, retries add load, and throughput collapses when it's needed most. Classic 2PC on top of that adds a blocking coordinator and at least two network round trips per transaction with no batching across transactions. Neither scales to 2M TPS without an enormous cluster.

What the ledger actually needs is narrower than "SERIALIZABLE everywhere":
- no protected account goes below zero, even under concurrency (prevents write skew on balances),
- every transaction is all-or-nothing across accounts,
- a consistent snapshot exists where Σ balances = 0.

**Recommendation.** Specify those invariants instead of an isolation level, and let the design choose the cheapest mechanism that guarantees them. In the proposed design: a shard is a single-threaded state machine (serial execution is trivially serializable, no aborts), cross-shard transactions use 2PC with Raft-replicated coordinators (no blocking on coordinator loss), and consistent snapshots come from closed HLC timestamps. In the Postgres reference implementation the same invariants come from READ COMMITTED plus explicit row locks taken in a fixed order, which is a cheaper and more predictable way to get the same guarantee for this workload than SSI.

---

## F-3 · RPO = 0, p99 < 25 ms and multi-datacenter can't all hold

**Severity:** Critical

**What the SRS says.** §2: RPO = 0, p99 write < 25 ms, 99.999%. §5.2: multi-datacenter model.

**Why it's a problem.** RPO = 0 against the loss of a data centre means a commit isn't acknowledged until it's durable in at least two data centres. If "data centre" means an availability zone inside a region (~1 ms apart), this is fine. If it means a region, every commit pays an inter-region round trip. Between distant regions that's 20–80 ms, which alone exceeds the p99 target, before any of the commit's own work. Cross-shard transactions, which need two consensus rounds, pay it twice.

**Recommendation.** The SRS should state which failure RPO = 0 covers:

- **AZ loss:** RPO = 0, achievable with a 3-AZ quorum, no latency penalty worth mentioning.
- **Region loss:** RPO = 0 achievable only with a paired region under ~8 ms RTT (5-replica quorum spanning both). Otherwise, accept a small non-zero regional RPO, document it, and get it signed off by risk/compliance.

I've laid out the options with numbers in `ARCHITECTURE.md` §11.2. Whichever is chosen, the business should choose it knowingly; it shouldn't be discovered in an incident review.

---

## F-4 · The SRS's own example transaction would be rejected

**Severity:** High

**What the SRS says.** §4.3 creates `acc_4410` as a `LIABILITY` account with `allow_negative: false` and balance `0.00`. The transaction example then posts `DEBIT acc_4410 150.00`, `CREDIT acc_7721 150.00` and shows `200 COMMITTED`.

**Why it's a problem.** A liability is credit-normal. Debiting it *reduces* its balance. A fresh liability account at 0.00 debited 150.00 goes to −150.00, and with `allow_negative: false` §3.2 says this must fail. So the example contradicts the rules two pages earlier.

This isn't a nitpick. It shows that "debit" and "credit" haven't been pinned to concrete balance effects per account type in the spec. If two teams read that differently, one of them builds a ledger where user wallets can be overdrawn.

**Recommendation.** Add a table to the SRS:

| Account type | Normal side | DEBIT does | CREDIT does |
|---|---|---|---|
| ASSET | Debit | increases | decreases |
| EXPENSE | Debit | increases | decreases |
| LIABILITY | Credit | decreases | increases |
| EQUITY | Credit | decreases | increases |
| REVENUE | Credit | decreases | increases |

Define `allow_negative` as "the balance, measured on the account's normal side, may not drop below zero". Then fix the example: either fund `acc_4410` first, or show the response as `422 INSUFFICIENT_FUNDS`. The repo's tests include the exact SRS example and assert the rejection (`PostingEngineIT.srsExampleIsRejectedOnAFreshWallet`).

Also, the SRS lists "Expense/Revenue" and no equity type. A ledger needs equity (or an opening-balance account) to bootstrap balances; I've added `EQUITY`.

---

## F-5 · Batching writes in Redis before disk breaks RPO = 0

**Severity:** High

**What the SRS says.** §5.1 suggests a buffered pipeline, e.g. Redis pipeline ring buffers, to batch updates "before pushing commits to the primary disk database".

**Why it's a problem.** Either the client gets its `COMMITTED` before the batch reaches disk, in which case a crash loses acknowledged transactions (RPO > 0), or the client waits until the batch reaches disk, in which case Redis is only an extra hop and a second system to keep consistent. Neither is useful. A secondary risk is that the buffer applies balance checks against stale state, approving two withdrawals that together overdraw an account.

**Recommendation.** Batch *inside* the durable commit path: the shard leader groups requests into one log append, and the ACK goes out after the quorum fsync. That gets the throughput benefit of batching with no gap in durability. For accounts too hot for one shard, split the account into buckets (sub-balances) instead of buffering writes to it.

---

## F-6 · "Currency-agnostic" zero-sum isn't defined per currency

**Severity:** High

**What the SRS says.** §1.2: the engine is currency-agnostic. §3.1: for any transaction, Σ debits − Σ credits = 0. §3.2 mentions currency conversion spread accounts.

**Why it's a problem.** If a transaction can carry entries in USD and EUR, then "Σ debits = Σ credits" across both is meaningless: 100 USD debited and 92 EUR credited isn't balanced or unbalanced, it's not comparable. Without a per-currency rule, an FX bug could create money in one currency and the zero-sum check would pass.

**Recommendation.** One of:
- one currency per ledger (the SRS's ledger already has a `currency` field), and FX as two linked transactions on two ledgers plus spread postings; or
- multi-currency transactions where the zero-sum rule applies **per currency** within the transaction.

The first is simpler and is what the design and the reference implementation do. The engine rejects any entry whose account belongs to a different ledger. Also specify amount precision per currency (ISO 4217 minor units) and reject amounts with more decimal places than the currency allows.

---

## F-7 · "No UPDATE anywhere" conflicts with balances and with erasure law

**Severity:** Medium

**What the SRS says.** §3.1: no row in the transaction log or entry ledger may be modified or deleted.

**Why it's a problem.** For journal rows, correct, and it should be enforced in the database, not just by convention. But two things need clarifying:
- Balances must change. Either they're computed from the journal on every read (too slow at 5M QPS) or they're a materialised projection that is updated. The SRS should say the projection is mutable but must always be rebuildable from the journal.
- Data protection law (GDPR, India's DPDP Act) can require deletion of personal data. If the journal contains names, descriptions with personal details, or similar metadata, that requirement conflicts with immutability.

**Recommendation.** Scope immutability to the journal. Keep PII out of the journal entirely: store references to external customer IDs. Enforce append-only with both permissions (no UPDATE/DELETE grant for the app role) and a trigger, since grants drift over time. Add tamper evidence (hash chain per shard, WORM archive storage) so "immutable" is verifiable, not just intended.

---

## F-8 · HLC needs a drift bound; vector clocks don't fit

**Severity:** Medium

**What the SRS says.** §5.2: use vector clocks or HLCs with Raft/Paxos indexes, "completely overriding any reliance on standard machine now() timestamps".

**Why it's a problem.**
- An HLC *is* built on `now()`, plus a logical counter. It's resilient to small skew, not independent of physical time. If one node's clock jumps a year ahead, every node that hears from it adopts that timestamp, and the whole cluster's HLC is a year ahead from then on. Nothing ever brings it back.
- Vector clocks grow with the number of participants and only give a partial order. A ledger needs a total order for statements and audit trails.

**Recommendation.** HLC, with: (a) a maximum-offset guard that rejects remote timestamps more than a configured bound (e.g. 500 ms) ahead of local physical time, and alerts; (b) chrony/NTP against a reliable source, monitored; (c) Raft log index as the authoritative order within a shard, HLC only for cross-shard causality and snapshots. The reference `HybridLogicalClock` implements the guard, and its test covers the "clock a year ahead" case.

---

## F-9 · 99.999% defined as wall-clock minutes isn't measurable

**Severity:** Medium

**What the SRS says.** §2: availability 99.999%, maximum 5.26 minutes of downtime annually.

**Why it's a problem.** In a 64-shard system, "down" is rarely all-or-nothing. If one shard is electing a leader for 1.5 s, is the system down? If the answer is yes, a single node failure burns through the year's budget fast. If it's no, the metric hides partial outages. There's also no measurement window: annual budgets are useless for operational decisions.

**Recommendation.** Define availability as an SLI: successful responses / valid requests (excluding 4xx caused by the client), measured at the gateway, over a rolling 30-day window. 99.999% then means ~26 s of full-outage-equivalent per month. Add an error budget policy: when the budget is spent, feature deploys stop.

---

## F-10 · 5M read QPS lumps balance checks and audits together

**Severity:** Medium

**What the SRS says.** §2: 5M read QPS for "point-in-time balance checks and audits".

**Why it's a problem.** These are opposite workloads. A balance check is a single key, needs to be fast, and often needs to be fresh. An audit scans history, can tolerate being seconds behind, and would destroy write latency if it ran against the shard leaders. The SRS also doesn't say what consistency a balance read needs: latest committed (linearizable), or "a few milliseconds stale is fine".

**Recommendation.** Split into two SLOs:
- Point balance reads: served from shard replicas from memory. Default bounded-staleness; linearizable on request (or `min_hlc` for read-your-writes).
- History and audit queries: served from a separate history store fed by CDC. Own latency SLO, own capacity plan.

---

## F-11 · Latency is measured at the wrong boundary

**Severity:** Low

**What the SRS says.** §2: p95 < 15 ms "measured from ingress proxy to commit log ACK".

**Why it's a problem.** The client experiences the time until the response arrives, which includes everything after the commit ACK. The SRS also doesn't say at what load the target applies, and doesn't separate single-shard from cross-shard transactions, whose latency differs by roughly 2x.

**Recommendation.** Measure from ingress request to ingress response. State the targets at peak load (2M TPS). Report single-shard and cross-shard separately. The budget in `ARCHITECTURE.md` §5.3 shows ~5.5 ms and ~11.5 ms p95 respectively.

---

## F-12 · 2M TPS has no daily-volume context; storage cost is unbounded

**Severity:** Medium

**What the SRS says.** §2: 2M write TPS sustained for a 60-minute window.

**Why it's a problem.** Peak alone doesn't size storage. If 2M TPS were sustained all day, that's 173 B transactions per day. Even at my assumed 15% daily average, it's ~26 B transactions per day, roughly 30–40x what the largest card networks handle. At that volume:
- the 7-day idempotency window holds ~180 B keys (~9 TB),
- the journal grows ~1.5 TB per day compressed, ~5.5 PB over a 10-year retention period.

**Recommendation.** Add daily average volume, growth rate, and retention period to the SRS. Confirm whether 2M TPS is a real business target or a stress-test ceiling. The architecture works either way, but the storage and archive budget differs by an order of magnitude.

---

## F-13 · Missing domain features: holds, account lifecycle, limits

**Severity:** Medium

**What the SRS doesn't cover.**
- **Two-phase (pending) transfers.** Card payments authorise first and capture later, sometimes for a different amount. Without holds, upstream systems will implement them outside the ledger, and the ledger stops being the source of truth for available balance.
- **Account status.** No way to freeze or close an account. Compliance teams will need both.
- **Limits.** No maximum number of entries per transaction, no maximum amount, no rule about whether one account can appear on both sides of a transaction.
- **Metadata.** No field for external references (payment ID, rail reference), which every reconciliation process needs.

**Recommendation.** Add holds (`PENDING → POSTED | VOIDED`) to the functional scope; the cross-shard design already uses them internally. Add `status` to accounts. Specify limits (the reference implementation caps a transaction at 64 entries and nets legs per account). Add a bounded metadata map that explicitly excludes PII.

---

## F-14 · Missing operational requirements: tenancy, reconciliation, retention

**Severity:** Medium

**What the SRS doesn't cover.**
- How tenants are isolated, authenticated and rate-limited. §1.2 says "multi-tenant banking regions" and nothing more.
- Continuous reconciliation. The SRS states invariants but never says they should be checked in production.
- Data retention and archival.
- Observability requirements.

**Recommendation.** At minimum: tenant → ledger ownership enforced at the gateway; per-tenant rate limits; an always-on reconciler that checks Σ = 0 per ledger at consistent snapshots and pages on any deviation; a retention policy (I've assumed 10 years); per-stage latency metrics and distributed tracing.

---

## F-15 · API contract gaps

**Severity:** Low

- `idempotency_key` is in the body. Fine, but most payment APIs use an `Idempotency-Key` header, which lets proxies and gateways see it without parsing the body. I'd accept both, header wins.
- Amounts are strings with no stated precision rule (see F-6).
- No read endpoints (`GET` account, `GET` transaction), no reversal endpoint, despite §3.1 requiring reversals.
- No error model. Clients need stable machine-readable codes: `UNBALANCED`, `INSUFFICIENT_FUNDS`, `IDEMPOTENCY_KEY_REUSED`, `ACCOUNT_NOT_FOUND`, `LEDGER_MISMATCH`.
- `POST /v1/transactions` returns `200`, other creates return `201`. A new transaction should be `201`, and an idempotent replay `200` with an `Idempotent-Replayed: true` header, so clients can tell the difference.
- The response should include the commit timestamp (HLC) so clients can do read-your-writes.

The reference implementation follows these: header or body key, `201` vs `200` + replay header, stable error codes, `commit_hlc` in the response, `GET` endpoints and `POST /v1/transactions/{id}/reversals`.

---

## What I'd do next

The three critical findings (F-1, F-2, F-3) need a decision before any build starts. F-1 and F-2 have clear fixes that I've already designed around. F-3 is a business decision between two costs, and needs the region list and a risk owner. The rest can be resolved by editing the SRS.

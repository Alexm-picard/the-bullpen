# ADR-0013: Scale-ready by design, single-box by choice

- **Status**: Accepted. The architecture decided here is true today and CI-proven; it is not prod-runtime the box does not yet have (prod currently trails `main` by ~13 commits, and the Wave D deploy is a batch scheduled off-window). Box deployment plus the D-41 capacity confirmation are the operational follow-through: `docs/capacity.md`'s "box (prod)" column fills in when that deploy lands.
- **Date**: 2026-07-04
- **Deciders**: alex
- **Related**: `decisions.md` entry [174], D-39 (`ApiPairTwoInstanceIT` + `WorkerPairTwoInstanceIT`), D-40 (`docs/capacity.md`), D-41 (box capacity confirmation), ADR-0003 (ClickHouse + SQLite), ADR-0006 (dev/prod boundary), `plan.md` Phase 5/6, `design.md` §6 §8

## Context

The Bullpen runs on one self-hosted desktop (WSL2 Ubuntu 24.04 LTS) behind a Cloudflare Tunnel, per ADR-0006 and the locked hosting choice. This is deliberate: the project is a portfolio piece framed for ML/SD hiring, not a SaaS product, and a single operated box with an honest capacity story is a better resume signal than an over-provisioned cluster with no traffic.

The question this ADR settles is not "should we scale out" (we should not, now) but "is the single-box topology a dead end or a deliberate waypoint." A reviewer scoring the system for scalability could read "one box" as a 7: no HA, no failover, no horizontal path. That read is wrong for this codebase, and the reason it is wrong is concrete and testable, so it deserves to be recorded rather than argued fresh each time.

Two pieces of prior work make the topology decidable rather than aspirational. D-39 added two integration tests, `ApiPairTwoInstanceIT` and `WorkerPairTwoInstanceIT` (both green), that stand up two instances of each profile against shared state and assert they do not corrupt each other or double-execute. D-40 authored `docs/capacity.md`, which quantifies the serving-stack, inference-compute, ClickHouse-read, and registry-write ceilings and already forward-references this ADR for the exit paths of each single-box boundary. This ADR closes that loop: it is the architectural decision `capacity.md` points at.

The concurrency-safety work is not theoretical. The daily drift and registry jobs, the live poller, the retraining-trigger enqueue path, and the retraining-queue consumer were each made idempotent or lease-guarded so that a second instance is SAFE, not just tolerated. That safety is exercised in CI today even though only one instance runs in prod. The point of writing this down is to state that the scale-out path is short and already de-risked, and to name the exact trigger that would make us take each step.

## Decision

We adopt a single-box topology as a deliberate architecture, not a default, and we record the concurrency-safety mechanisms that make it scale-ready plus the exit trigger for each single-box boundary. The system is "scale-ready by design, single-box by choice": every place where we run one of something is a choice with a documented, cheap exit, and the multi-instance safety for those exits is already CI-proven.

### Idempotency inventory (the safety that makes N-instance an option)

Four mechanisms cover every scheduled or triggered writer, so running a second instance never produces a double-execution or a corrupt write:

| Mechanism | Jobs | Two-instance safety |
| --- | --- | --- |
| `job_locks`, at-most-once per ET fire-date (D-36, migration `V017__job_locks.sql`) | `PsiFeatureJob`, `PsiPredictionJob`, `CalibrationJob`, `WeeklySegmentJob`, `DriftAlertEvaluator`, `ReconciliationJob` (6) | one winner per (job, ET fire-date) |
| ReplacingMergeTree / date-scoped writes | `PlayersRefreshJob`, `PitcherFormRefreshJob`, `MatchupRefreshJob` (3) | duplicate writes collapse |
| Heartbeat lease (D-37, migration `V019__job_leases.sql`) | `LivePollingService` (1) | one holder; ~30s stale-takeover; deliberate API-politeness singleton |
| `UNIQUE(trigger_id)` idempotent enqueue | `ScheduledTrigger`, `DriftTrigger` (2) | date-scoped id, so the first enqueues and the other hits `DuplicateTriggerId` |

The consumer side carries the one place where two-instance operation surfaced and fixed a real bug. `RetrainingQueueRepository.claimNextQueued()` is the atomic claim: an `UPDATE retraining_queue SET status='running' ... WHERE id=? AND status='queued'` that returns a row only if it won the flip. It maps the immediate `SQLITE_BUSY` raised on the write-lock upgrade to a lost race (returns empty) rather than letting it surface, which `busy_timeout` alone would turn into a deadlock under two concurrent workers. The net effect is that two workers can never both win the same queued row and never both error on it. This was hardened in D-39b (PR #221). Cite the class and method, not line numbers; the D-39b change already shifted those lines.

### Three deliberate single-box boundaries, each with an exit trigger

1. **One ClickHouse.** Exit trigger: sustained read RPS approaching the `8 x (1000/query_ms)` Hikari-pool ceiling (config key `bullpen.clickhouse.pool.max-size`, default 8). The exit is to raise the pool first, then add a ClickHouse replica. This ceiling only binds read endpoints: the predict hot path is CH-free at request time because writes go through `AsyncPredictionLogger`, so only read endpoints touch ClickHouse synchronously.

2. **One SQLite registry.** Exit trigger is qualitative and regime-change shaped, on purpose: when registry writes shift from administrative (registration, promotion, routing, plus one lock or lease row per job per day, all sustained well under 1 req/s) to per-request, OR when a second writer HOST becomes a standing need. The single-writer THROUGHPUT ceiling (`capacity.md` §4) is analytically orders of magnitude above current and projected load, so throughput is NOT the binding constraint: the wall is one-file-one-writer concurrency, not writes per second. We deliberately do not attach a hard p99 number to this boundary; a number here would be false precision that never fires. The exit is to Postgres, where `SELECT ... FOR UPDATE SKIP LOCKED` replaces the atomic `UPDATE ... WHERE` claim pattern.

3. **Singleton worker.** Exit trigger: when failover/availability becomes a standing requirement, or when parallel retraining-queue consumption is needed. The `job_locks` plus the `job_leases` lease already make N workers SAFE, so the exit is "flip to N workers, no code change." Be honest about what that buys: AVAILABILITY (failover) and parallel retraining-queue consumption, NOT throughput for the poller and NOT throughput for the daily jobs. The poller stays a lease-singleton by design because the ~500ms `minApiGapMs` (config key `bullpen.ingest.live.api-min-gap-ms`, default 500) makes single-poller an MLB-Stats-API-politeness FEATURE, not a limitation. The daily jobs stay at-most-once by lock. For those two tiers a second worker is availability, not more work done per unit time.

### Properties (not boundaries) worth naming

- **Admin-write idempotency behind a load balancer.** Promotion and routing writes are safe under an IP-affinity LB via stage-transition guards plus last-write-wins upserts.
- **Bounded-staleness routing.** Routing converges across instances within the cache TTL (config key `bullpen.cache.routing-ttl-seconds`, `CacheConfig`, default 30), a deliberate bounded-staleness tradeoff. Proven green by `ApiPairTwoInstanceIT`: a routing write on instance A is visible on instance B within the TTL.
- **Rolling-restart recipe.** Graceful shutdown (`server.shutdown: graceful` in `application.yml`) plus the `WarmupReadiness` probe give a zero-new-code rolling restart. The api is stateless (proven by the pair IT), so the recipe is: start-new, health-gate on readiness, drain-old.

### Where the two-instance proof runs in CI

The two pair ITs prove at different CI tiers, and the "proven green" claim is precise about which. `ApiPairTwoInstanceIT` is CH-free and runs on EVERY CI pass. `WorkerPairTwoInstanceIT` is docker-gated (`-Dbullpen.it.docker=true`) and runs when the Docker ITs run, i.e. in CI. So the routing/staleness and stateless-api guarantees are checked on every pass; the worker idempotency guarantees are checked on the docker-gated tier.

## Consequences

**Easier.** Scale-out, if it is ever needed, is a short and de-risked path rather than a rewrite: the concurrency-safety is already merged and CI-exercised, so "add an instance" is a deploy-topology change, not a code project. The capacity story is honest and defensible under review: each single-box boundary has a named ceiling and a named exit, and the scalability score reflects design intent (9) rather than the literal absence of HA (7).

What separates this 9 from a 10 is deliberate and enumerated, not a gap: a 10 would add multi-host serving, ClickHouse replication, a Postgres registry, and a sharded poller (Alternative B), each out of scope by decision until its trigger fires. This mirrors `capacity.md`'s "What keeps this a 9, not a 10" section: single-host by choice, every boundary with a cheap documented exit, concurrency-safety CI-proven but unused today.

**Harder.** The safety machinery (locks, leases, unique-enqueue, the busy-race mapping) is carried and tested continuously even though prod runs one instance, so we pay a small ongoing maintenance and test-runtime cost for a capability that is unused today. That is the deliberate premium for keeping the exit cheap.

**New failure modes.** None introduced by this decision; it documents mechanisms already in place. The honest caveat is that "scale-ready" is proven at the IT level, not under production multi-host load, and the two are not the same thing. If we ever flip to N instances, the pair ITs are the floor, not a substitute for a real multi-host soak.

**Locked into.** The single-box topology stays the default until one of the three named exit triggers fires. Adding a second serving host, a ClickHouse replica, a Postgres registry, or a sharded poller is a re-decision via `/decide` referencing the relevant trigger, not a quiet expansion.

## Alternatives Considered

### Alternative A: Score and present the system as a plain single-box (a 7), no scale-out story

- Accept "one box, no HA" at face value and make no claim about scale-readiness.
- Rejected: it understates what is actually in the tree. The idempotency inventory, the lease takeover, the busy-race mapping, and the two green pair ITs are real, merged, and CI-exercised. Presenting the system as unable to scale would be dishonest in the opposite direction from over-claiming, and it would discard genuine, verifiable engineering signal.

### Alternative B: Actually build HA now (a literal 10): multi-host serving, ClickHouse replication, Postgres registry, sharded poller

- Stand up a second serving host, replicate ClickHouse, migrate the registry to Postgres, and shard the game poller across workers.
- Rejected: it is a large amount of work with zero load to justify it (the site serves portfolio-scale traffic), and it would trade the honest "one operated box" story for an over-provisioned cluster that no reviewer would read as prudent. The point of the exit triggers is that this work becomes correct exactly when load demands it and not before. This is explicitly out of scope BY DECISION, not by omission.

### Alternative C: Leave the topology undocumented and decide ad hoc if load ever arrives

- Do the safety work (already done) but never write down the boundaries or triggers.
- Rejected: the exit triggers are the whole value. Without a pre-declared, checkable trigger per boundary, a future scaling decision gets made under pressure with no baseline, and the "single-box by choice" framing degrades into "single-box because we never got around to more." `capacity.md` already forward-references this ADR for those exit paths; not writing it would leave that reference dangling.

## Revision History

(Leave empty until the first revision.)

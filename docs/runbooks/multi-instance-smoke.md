# Runbook - Multi-instance smoke (prove the scale-ready topology by hand)

**Owner:** alex · **Last reviewed:** 2026-07-07 · **Phase:** 5 (scalability)

Manually boots a second api and a second worker to confirm the "scale-ready by
design, single-box by choice" claim (ADR-0013) holds against real processes, not
just the CI two-instance ITs. Run it after a change that touches routing cache,
the SQLite registry, the drift/retrain jobs, or the live poller lease.

> The automated equivalents run every CI pass / under Docker:
> `config/ApiPairTwoInstanceIT` (CH-free, boots two api contexts) and
> `config/WorkerPairTwoInstanceIT` (docker-gated, two worker contexts + one
> ClickHouse). This runbook is the by-hand version for a prod-parity check.

## What must hold (the invariants)

| Surface        | Invariant                                                                             | Mechanism                                                 |
| -------------- | ------------------------------------------------------------------------------------- | --------------------------------------------------------- |
| Serving        | Both api instances return identical predictions under fixed routing                   | deterministic murmur3 bucketer over (gameId, modelName)   |
| Routing writes | An admin routing change on A is visible on B within the cache TTL, immediately on A   | local `@CacheEvict` + `bullpen.cache.routing-ttl-seconds` |
| Worker jobs    | Each of the 6 non-idempotent jobs runs at most once per ET fire-date across instances | `job_locks` INSERT-marker try-acquire (V017)              |
| Live poller    | Exactly one worker holds the poll lease; ~30s failover                                | `job_leases` heartbeat lease (V019)                       |
| Alerts         | One alert row per (key, ET day) even with two evaluators                              | `alert_history` unique index + INSERT-or-ignore (V018)    |
| Retrain claim  | Exactly one worker claims a queued trigger                                            | atomic `UPDATE ... WHERE status='queued'`, BUSY-tolerant  |

## Setup

Shared state, two of each profile on distinct ports:

```bash
# Shared SQLite registry file + a running ClickHouse (docker compose up -d).
export BULLPEN_SQLITE=/tmp/bullpen-smoke.db     # both instances point here
export CH=... ADMIN=...                          # CH + admin creds as usual

# api A + B (serving); worker A + B (jobs). Boot sequentially so Flyway migrates once.
cd backend
./gradlew bootRun --args='--spring.profiles.active=api --server.port=8080'   &
./gradlew bootRun --args='--spring.profiles.active=api --server.port=8081'   &
./gradlew bootRun --args='--spring.profiles.active=worker --server.port=8090 --bullpen.ingest.players.enabled=false' &
./gradlew bootRun --args='--spring.profiles.active=worker --server.port=8091 --bullpen.ingest.players.enabled=false' &
```

(`--bullpen.ingest.players.enabled=false` avoids the on-boot MLB Stats API
backfill; every other worker job is cron 2-4 AM ET, so drive them explicitly
below via their visible-for-tests entry points or wait for the window.)

## Checks

1. **Identical serving.** Fire the same predict request at `:8080` and `:8081`;
   assert byte-identical probabilities and zero non-200s. Loop it concurrently.
2. **Routing convergence.** `GET /v1/admin/routing/{model}` on B to prime its
   cache, flip the mode via `POST` on A, then: A reflects it immediately, B still
   shows the old mode immediately, and B converges within
   `routing-ttl-seconds`. (Bounded split-brain is deliberate - ADR-0013.)
3. **Single job winner.** Trigger a lock-guarded job (e.g. `PsiFeatureJob`) on
   both workers for the same ET date; assert exactly one `job_locks` row and one
   batch of `drift_metrics` written. The loser logs "already ran ... skipping".
4. **One lease holder.** With live ingest enabled, assert exactly one worker
   polls (`job_leases` has one holder); kill it and confirm the other takes over
   within ~30s (stale-heartbeat takeover).
5. **One alert row.** Force a drift alert on both evaluators; assert one
   `alert_history` row for the (key, day).
6. **One retrain claimer.** Enqueue one trigger; assert exactly one worker moves
   it `queued -> claimed` (the other gets zero rows, not an error).

Any double-write, double-page, or split prediction is a regression - do not ship.

## Teardown

```bash
kill %1 %2 %3 %4 ; rm -f /tmp/bullpen-smoke.db*
```

## What this does NOT prove (deliberately)

Per ADR-0013 this is single-box by choice: there is one ClickHouse, one SQLite
registry file, and the worker is a hardened singleton (locks + lease), not an
HA fleet. The smoke proves the api is safe to duplicate behind an IP-affinity LB
and the worker is safe to run redundantly, not that the system is multi-host HA.
The documented exits (Postgres registry swap, R2-restore for artifacts) are in
the ADR.

## Related

- [ADR-0013](../adr/0013-scale-ready-single-box.md) - the topology + the full
  @Scheduled idempotency inventory.
- `docs/capacity.md` - the load ceiling + N-instance projection.

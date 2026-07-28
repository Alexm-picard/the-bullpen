# Runbook - model_routing integrity failure at boot

**Owner:** alex · **Last reviewed:** 2026-07-27 · **Task:** #94 / PR #373

Applies when `bullpen-api` or `bullpen-worker` refuses to start with:

```
IllegalStateException: routing integrity: N model_routing row(s) reference a
non-CHAMPION version - refusing to start rather than serve outside the
promotion gates (task #94): [ChampionStageRow[modelName=..., ...]]
```

`RoutingChampionIntegrityCheck` found a `model_routing` row whose
`champion_version_id` does not reference a CHAMPION-stage version OF THE SAME
MODEL. The check fails the boot before the web server accepts traffic, on both
profiles, because the alternative is the A/B router serving a version that
never passed the rule-5/rule-6 gates.

## Why the normal tools will not work

- Every admin endpoint lives in the api profile - the process that just
  refused to boot. There is no HTTP path to repair from.
- `transitionStage` cannot repair it either: a stranded row's version is
  typically ARCHIVED, which is terminal (`Stage.allowedTargets()`), so any
  transition attempt throws `IllegalTransition`.
- Do NOT weaken or bypass the check. A bad routing row is an un-audited
  promotion in everything but name.

## Recovery A - delete the offending row (usual case)

This is the documented EMERGENCY EXCEPTION to ADR-0006's read-only-prod rule:
a `sqlite3` write on the box, snapshot-first, smallest possible statement.

1. Back up first (non-negotiable):

   ```bash
   sqlite3 /opt/bullpen/data/registry.sqlite \
     ".backup /opt/bullpen/data/registry.pre-repair.$(date +%Y%m%d-%H%M%S).sqlite"
   ```

2. Look at what is actually there before deleting (the boot log names the
   model; confirm the row matches it):

   ```sql
   SELECT r.model_name, r.champion_version_id, v.model_name AS version_model, v.stage
     FROM model_routing r
     LEFT JOIN model_versions v
       ON v.id = r.champion_version_id AND v.model_name = r.model_name;
   ```

3. Delete ONLY the named row (the V020 DELETE path is unblocked by design):

   ```bash
   sqlite3 /opt/bullpen/data/registry.sqlite \
     "DELETE FROM model_routing WHERE model_name = '<model_name_from_the_boot_log>';"
   ```

4. Restart the units. With no routing row, the router finds nothing for that
   model and the legacy fallback (or a 503) serves - the honest state until a
   champion is promoted back through the gates.

5. Afterwards, from the Mac: re-promote a legitimate champion via the normal
   promote flow, and record how the row went bad. If the cause was a code
   path (not a hand edit), that path is a bug - file it.

## Recovery B - restore registry.sqlite from the nightly snapshot

Use when the corruption is broader than one row (or step 2 above shows
anything you cannot explain). The 03:00 snapshot captures the registry at
`_sqlite/registry.sqlite` inside each snapshot dir
(`infra/backup/clickhouse-snapshot.sh`).

1. Stop both units.
2. `.backup` the current file as in Recovery A step 1 (preserve the evidence).
3. Copy the latest snapshot's `_sqlite/registry.sqlite` over
   `/opt/bullpen/data/registry.sqlite`.
4. Restart. Flyway will re-apply nothing (the snapshot is post-V020 once this
   PR has deployed at least one nightly cycle; if restoring an OLDER snapshot,
   Flyway applies the missing migrations at boot - that is fine).
5. Reconcile: any registration or promotion that happened after the snapshot
   was taken must be replayed from the Mac via the normal flows.

## Pre-flight (avoid this runbook entirely)

Before every deploy that includes V020 or later, run the read-only check on
the box (this is exactly `findChampionStageRows`' violation predicate):

```sql
SELECT r.model_name, r.champion_version_id, v.stage
  FROM model_routing r
  LEFT JOIN model_versions v
    ON v.id = r.champion_version_id AND v.model_name = r.model_name
 WHERE v.stage IS NOT 'champion';
```

Zero rows = safe to deploy.

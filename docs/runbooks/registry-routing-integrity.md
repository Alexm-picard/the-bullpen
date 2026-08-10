# Runbook - model_routing integrity failure at boot

**Owner:** alex · **Last reviewed:** 2026-07-28 · **Task:** #94 / PR #373, issue #374

Applies when `bullpen-api` or `bullpen-worker` refuses to start with:

```
IllegalStateException: routing integrity: N model_routing row(s) violate the
stage invariants (champion must be a same-model CHAMPION, an occupied
challenger slot a same-model SHADOW) - refusing to start rather than serve
outside the promotion gates (task #94 / issue #374): [RoutingStageRow[...]]
```

`RoutingIntegrityCheck` found a `model_routing` row violating one of the two
stage invariants: `champion_version_id` must reference a CHAMPION-stage
version OF THE SAME MODEL, and an occupied `challenger_version_id` must
reference a same-model SHADOW-stage version. The check fails the boot before
the web server accepts traffic, on both profiles, because the alternative is
the A/B router serving (or shadow-routing) a version that never passed the
rule-5/rule-6 gates. The printed `RoutingStageRow` shows both sides - the
violating one is whichever stage is not its legit value.

## Why the normal tools will not work

- Every admin endpoint lives in the api profile - the process that just
  refused to boot. There is no HTTP path to repair from.
- `transitionStage` cannot repair it either: a stranded row's version is
  typically ARCHIVED, which is terminal (`Stage.allowedTargets()`), so any
  transition attempt throws `IllegalTransition`.
- Do NOT weaken or bypass the check. A bad routing row is an un-audited
  promotion in everything but name.

## Which repair for which violation

- **Champion violation** -> Recovery A (DELETE the row; the slot cannot be
  emptied, champion_version_id is NOT NULL).
- **Challenger violation** -> Recovery A2 (CLEAR the slot; the row itself is
  healthy and deleting it would needlessly drop the serving champion to the
  legacy fallback).
- **BOTH in one row** -> Recovery A. A champion violation blocks the A2 update as well:
  V020's trigger is `BEFORE UPDATE` on the whole row, not `UPDATE OF champion_version_id`, so
  clearing the challenger on a row whose champion is corrupt aborts too.
- **Anything you cannot explain** -> Recovery B.

## Recovery A - delete the offending row (champion violation)

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

## Recovery A2 - clear a stale challenger slot (challenger violation)

Same emergency-exception rules as Recovery A: `.backup` first, smallest
possible statement, evidence preserved.

1. Back up as in Recovery A step 1.
2. Confirm the slot is the violation (challenger stage not 'shadow' or NULL
   join miss), using the pre-flight query below.
3. Clear ONLY the slot - the row stays, the champion keeps serving:

   ```bash
   sqlite3 /opt/bullpen/data/registry.sqlite \
     "UPDATE model_routing SET challenger_version_id = NULL,
        challenger_traffic_pct = 0, mode = 'shadow'
      WHERE model_name = '<model_name_from_the_boot_log>';"
   ```

4. Restart. Afterwards, from the Mac: set a legitimate challenger via the
   normal admin flow if one is wanted, and record how the slot went stale.

## Recovery B - restore registry.sqlite from the nightly snapshot

Use when the corruption is broader than one row (or step 2 above shows
anything you cannot explain). The 03:00 snapshot captures the registry at
`_sqlite/registry.sqlite` inside each snapshot dir
(`infra/backup/clickhouse-snapshot.sh`).

1. Stop both units.
2. `.backup` the current file as in Recovery A step 1 (preserve the evidence).
3. Copy the latest snapshot's `_sqlite/registry.sqlite` over
   `/opt/bullpen/data/registry.sqlite`.
4. Restart. Flyway will re-apply nothing (the snapshot is post-V021 once this
   PR has deployed at least one nightly cycle; if restoring an OLDER snapshot,
   Flyway applies the missing migrations at boot - that is fine).
5. Reconcile: any registration or promotion that happened after the snapshot
   was taken must be replayed from the Mac via the normal flows.

## Pre-flight (avoid this runbook entirely)

Before every deploy that includes V020 or later, run the read-only check on
the box (this is exactly `findRoutingStageRows`' violation predicate, both invariants):

```sql
SELECT r.model_name,
       r.champion_version_id,   v.stage AS champion_stage,
       r.challenger_version_id, c.stage AS challenger_stage
  FROM model_routing r
  LEFT JOIN model_versions v
    ON v.id = r.champion_version_id AND v.model_name = r.model_name
  LEFT JOIN model_versions c
    ON c.id = r.challenger_version_id AND c.model_name = r.model_name
 WHERE v.stage IS NOT 'champion'
    OR (r.challenger_version_id IS NOT NULL AND c.stage IS NOT 'shadow');
```

Zero rows = safe to deploy.

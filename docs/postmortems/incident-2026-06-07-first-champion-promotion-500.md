# Incident postmortem: first-champion promotion bricked `/all-parks` (2026-06-07)

- **Status**: resolved-pending-clean-recovery (interim unblock available; durable fixes `[149]`/`[150]`/`[151]`/`[152]` landed on main, recovery runbook ready, gated on deploy of `2ca7e2f`). NOTE: the INC-2 load gate caught a fourth, code-side layer at the recovery re-promote - the reader fed the input name `"input"` while the MLP names it `"features"` (`[152]`); fixed in the reader, NOT the snapshot.
- **Severity**: SEV-3 (public endpoint 500, zero live consumers)
- **Date**: 2026-06-07 (deploy + promotion ~01:38-02:10 UTC)
- **Authors**: developer (Mac) + box-operator + independent reviewer
- **Type**: model-promotion incident (not drift - but the Phase-6 writeup format applies)

## Summary

The first-ever production CHAMPION promotion of the batted-ball outcome model
(`battedball_outcome` MLP, v1) succeeded at the registry layer but the model could not
be loaded at serving time, so `POST /v1/predict/batted-ball/all-parks` returned HTTP 500
on every call. The registry state machine offers **no rollback** from CHAMPION (only a
terminal ARCHIVED), so the bad champion could not be demoted. The owner chose to leave v1
in place (blast radius minimal - the endpoint has no live consumer yet) pending a Mac-side
fix. Root cause was a three-layer incomplete/mismatched model snapshot, the deepest layer
being an unguarded Python<->Java calibrator-format contract drift.

## Impact

- `/v1/predict/batted-ball/all-parks` returned 500 for the duration. **No user impact**:
  the Park Explorer renders from fixtures, so the endpoint has no live consumer; the 500
  was error-tracking (Sentry) noise, not a user-facing outage.
- All other surfaces unaffected: `/games/today`, registry read/write, pitch predict,
  `/predict/batted-ball` (single-park toy) all healthy.
- The deploy itself (defect-sweep fixes + M5 + `[146]` serving + B5 parity) landed
  cleanly and is un-degraded.

## Timeline (UTC)

| Time   | Event                                                                                                                                                                                                                                                                                                                                          |
| ------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| ~01:38 | Pre-flight ClickHouse + registry snapshot `auto_20260607T013828Z` (183 parts) taken first.                                                                                                                                                                                                                                                     |
| ~01:39 | Deploy of `e0b4500` (tag `v2026.06.07-0139`); smoke `/actuator/health` UP in 5s; api+worker active; `/v1/ops/registry` 200.                                                                                                                                                                                                                    |
| ~01:5x | WS-B preconditions checked: `battedball_outcome` id=1, SHADOW, single version (bootstrap exemption valid); snapshot `feature_pipeline.json` byte-identical to the `53be50f9` contract (rule 7 holds). **Snapshot artifact completeness was mis-read** ("15 KB ONNX -> self-contained"; 15 KB was actually the tell that weights are external). |
| ~02:0x | Promotion criteria pulled; `eval_metrics` flagged `prefer_for_production: lgbm`. Held for an explicit owner call; owner confirmed promote the MLP per decision `[141]`'s calibration gate (MLP dominates on ECE: 0.000537 vs 0.0065).                                                                                                          |
| ~02:08 | C4 promote SHADOW->CHAMPION: HTTP 200, routing row created.                                                                                                                                                                                                                                                                                    |
| ~02:09 | C5 verify: **HTTP 500**. Diagnosed via `journalctl`: `model.onnx.data` (external-data weights sidecar) missing from the snapshot.                                                                                                                                                                                                              |
| ~02:1x | Attempted rollback (demote CHAMPION->SHADOW): **409** - not an allowed transition. Confirmed CHAMPION -> only ARCHIVED, and ARCHIVED is terminal. Rollback impossible.                                                                                                                                                                         |
| ~02:1x | Completed the copy-set on the box (copied the byte-verified `model.onnx.data` sidecar in) -> load advanced past ONNX; next failure: `calibrator.json` missing -> copied the on-box one -> next failure: **"park ATH has 0 calibrators, expected 5"** (the on-box calibrator is stale list-format; the deployed loader needs map-format).       |
| ~02:2x | STOP: snapshot cannot be completed from box artifacts (calibrator needs a Mac re-export, not a box hand-edit). Owner chose "leave v1, wait for the Mac calibrator". v1 left CHAMPION; `/all-parks` 500s; everything else healthy.                                                                                                              |
| later  | Mac: stdlib list->map re-key script shipped (incident unblock); `to_json` reconciled to map-canonical (decision `[149]`) + a Java-contract CI guard added.                                                                                                                                                                                     |

## Root cause (three layers, one snapshot)

The registered `battedball_outcome/v1` snapshot was both **incomplete** and carried a
**format-drifted calibrator**:

1. **Missing `model.onnx.data`** - the MLP ONNX uses external-data format (the 15 KB
   `model.onnx` is the graph; weights live in the sidecar). The snapshot was registered
   Jun 4, _before_ the copy-set fix landed, and that fix does not backfill existing
   snapshots.
2. **Missing `calibrator.json`** - same incomplete copy-set.
3. **Calibrator format drift (the deep cause)** - even once present, the calibrator was
   stale **list-format** (`parks` as a list of `{park_id, classes}`), while the deployed
   Java loader (`BattedBallCalibrators.load`, B3/`e0b4500`) reads **map-format**
   (`parks.get(park)`). The Python exporter (`mlp/calibration.py:to_json`, decision `[51]`)
   and the Java loader had silently diverged. Rule-7 schema hashing covers
   `feature_pipeline.json` only - the calibrator contract was unguarded.

## Machinery gaps exposed (the real findings)

| #     | Sev  | Gap                                                                                                                                                                                                                                                                                                                                                 |
| ----- | ---- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| INC-1 | High | **No champion rollback.** `Stage`: CHAMPION -> {ARCHIVED} only; ARCHIVED -> {} (terminal). Contradicts the registry's stated purpose (design.md S3.1: "rollback-able changes to models already serving"). A bad champion bricks the endpoint with no way back.                                                                                      |
| INC-2 | High | **No load-check gate at register/promote.** The registry verifies file existence + schema hash, never that the model _loads + predicts_. "Files exist + hash matches" != "it loads". A load+warmup gate would have rejected this at register time.                                                                                                  |
| INC-3 | High | **Incomplete copy-set for external-data ONNX + calibrators** (BUG-1c). Now durably fixed in `RegistryService.register` (copies `model.onnx.data` + `calibrator.json` when present), but it does **not backfill** the pre-fix Jun-4 snapshot.                                                                                                        |
| INC-4 | Med  | **Rule-7 hashing doesn't cover the calibrator.** The schema hash guards the feature pipeline only; the calibrator format drifted (list->map) unguarded. Closed for _format_ by the new `to_json` Java-contract CI test (decision `[149]`); a hash over the calibrator would close it for _content_.                                                 |
| INC-5 | Med  | **Bootstrap "promote-then-validate".** A first champion has no shadow path to validate against, so it is promoted then validated - which, combined with INC-1, makes a broken first champion unrecoverable. The correct control is INC-2's load gate, not human shadow-validation (the plan's WS-C control was inapplicable to the bootstrap case). |
| INC-6 | Med  | **Bootstrap-recovery rule-5 deadlock.** Registering a fix-version (v2) makes the model 2-versioned, so the bootstrap exemption is lost and promoting v2 needs an `experiment_results` row that cannot exist (the only baseline, v1, is unloadable). Clean recovery therefore depends on INC-1 (demote v1) + idempotent re-register of v1, not a v2. |

## What went well

- **Pre-flight snapshot taken first** (rule 8 discipline) before any deploy.
- **Disciplined holds at every fork** - the `prefer_for_production: lgbm` signal was
  surfaced to the owner rather than steamrolled; recovery paths were stopped for an
  explicit decision rather than improvised.
- **No cowboy fixes** - no raw prod DB writes, no hand-conversion of the calibrator on the
  box (correctly identified as authoring / silent-miscalibration risk).
- **Layered root-cause** through three failures with byte-verification of the sidecar's
  provenance.
- **Minimal blast radius** by construction - the endpoint has no live consumer.

## What went wrong / contributing factors

- The snapshot-completeness check was mis-read on both the WS-B audit (developer) and the
  box check (operator): "15 KB ONNX -> self-contained" hid the external-data sidecar. A
  code-level audit cleared "copy-set DONE" without inspecting the deployed _artifact_.
- The activation runbook leaned on a human shadow-validation step the bootstrap case
  cannot satisfy and the system does not enforce (INC-5).
- The promotion machinery had no load gate (INC-2) and no rollback (INC-1) - the two rails
  that make promotion safe - so a recoverable error became a stuck endpoint.

## Action items

| Item                                                                                                                                                                                 | Owner                    | Status                                                                                                                                                            |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Re-key the on-box v1 calibrator list->map (interim unblock)                                                                                                                          | box-operator             | script shipped (`convert_calibrator_list_to_map.py`); run pending                                                                                                 |
| `to_json` -> map-canonical + `from_json` back-compat + Java-contract CI test                                                                                                         | developer (Mac)          | **DONE** (decision `[149]`, commit `b831ac7`)                                                                                                                     |
| INC-3 copy-set fix (`model.onnx.data` + `calibrator.json`)                                                                                                                           | (prior)                  | DONE (predates incident; doesn't backfill)                                                                                                                        |
| INC-2 register/promote **load gate** (load + warmup predict; reject on failure)                                                                                                      | registry track           | **DONE** (decision `[151]`, commit `27298ac`; on main, undeployed)                                                                                                |
| INC-1 champion **rollback** path (CHAMPION -> SHADOW or non-terminal RETIRED)                                                                                                        | registry track           | **DONE** (decision `[150]`, commit `2878420`; on main, undeployed)                                                                                                |
| INC-4 hash the calibrator under rule 7 (content guard)                                                                                                                               | registry track           | proposed (deferred; not in the recovery deploy)                                                                                                                   |
| INC-7 reader resolves the ONNX input name from the session (MLP names it `"features"`, reader fed `"input"`; latent + original, caught by the INC-2 gate at the recovery re-promote) | developer (Mac)          | **DONE** (decision `[152]`, commit `2ca7e2f`; on main, undeployed)                                                                                                |
| Clean recovery: verify snapshot complete -> (INC-1) demote v1 -> (INC-2-gated, [152]-dependent) re-promote v1 -> C5                                                                  | developer + box-operator | **unblocked** (all three fixes on main); runbook [`clean-recovery-first-champion.md`](../runbooks/clean-recovery-first-champion.md); gated on deploy of `2ca7e2f` |

## Lessons

1. **"Files exist + hash matches" is not "it loads."** Registration/promotion must prove
   the model loads and predicts (INC-2). This single gate would have prevented the
   incident.
2. **A serving system without rollback is not production-ready** (INC-1) - the registry's
   own charter is rollback-able change.
3. **Every Python<->Java file contract needs a real round-trip test.** The B5 parity test
   loaded a hand-shaped fixture, not the real exporter output, so the calibrator drift
   slipped. Test the actual export -> the actual loader.
4. **Verify the deployed artifact, not just the code.** Both audits cleared the copy-set
   from code while the deployed snapshot was stale.
5. **Bootstrap promotion needs a system-enforced control, not a human one** - the
   first-champion case cannot shadow-validate, and (INC-6) its recovery is rule-5-deadlocked
   without rollback.

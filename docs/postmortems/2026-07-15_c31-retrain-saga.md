# Postmortem - the C-31 retrain saga (16 attempts to the first real-data automated retrain)

**Date range:** 2026-07-10 to 2026-07-15 · **Owner:** alex (box/TD) + Mac-side [DEV]
**Severity:** none user-facing (the champion served untouched throughout; no data was ever silently wrong)
**Outcome:** SUCCESS - BOX HAND-OFF #1 complete. `battedball_outcome` v3 registered as CANDIDATE
by the automated retraining control plane, end-to-end on real data, unattended.

This is the sister artifact to the induced-drift drill postmortem
([drill-2026-05-30](./drill-2026-05-30-induced-battedball-drift.md)): where that one proved the
DETECTOR has teeth, this one proves the RETRAIN control plane survives contact with production -
and documents what that contact cost.

## The winning run (attempt 16, the first fully-instrumented one)

Trigger `de1e313c-06cd-432a-82c7-d54a803ed97c` (MANUAL, ceremony). Claimed 00:00:42Z,
succeeded 01:37:30Z - **96.8 minutes end-to-end, zero interventions**:

```
claim -> 11 per-year ClickHouse loads            (partial_merge + 500MB spills +
         (seasons 2015-2025, ~1.2M BIPs)          per-chunk JEMALLOC PURGE)
      -> GPU training                            (served BattedBallMLP + carry head,
                                                  peak 49C across four full trainings)
      -> single-file ONNX export                 (195KB, no sidecar)
      -> per-park isotonic calibration           (val-2025, n=123,345)
      -> register                                (rule-7 schema hash PASSED)
      -> queue row marked complete
```

**The candidate:** `battedball_outcome` v3, registry id=9, stage=CANDIDATE. Rule 6 intact -
not promoted, not shadow-routed; that is a separate, owner-gated decision. Staged artifact set
is the exact served file contract: `model.onnx`, `metadata.json` (trigger_id round-tripped;
`feature_scaler` + 30-park `park_order` + `carry_target` present), `calibrator.json`,
`feature_pipeline.json`. Rule 13 clean: train 2015-2024, calibration val 2025, no 2026.

**The quality headline:** mean per-park ECE **0.00587 -> 0.00058** post-calibration (10x
improvement), max post-ECE 0.00103, **30/30 parks improved**.

## The ledger: 16 attempts, 15 diagnosed failures, 6 distinct root causes

Every failure was diagnosed to a mechanism, fixed in code with regression tests, and the fix
was exercised in the winning run. Nothing was worked around by hand in the final state.

| #   | Attempts | Root cause                                                                                                                                                                                                                       | Fix (PR)                                                                                                                                                          |
| --- | -------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | 1        | Full-range `pitches FINAL x bbip_retrodicted_labels FINAL` hash join OOMs under the 4 GiB cap - #238 had fixed one copy; SEVEN more sat uncovered across four loaders                                                            | #266: `partial_merge` on all seven                                                                                                                                |
| 2   | 3        | The full-range DISTINCT count survived even partial_merge; probes proved NO settings rescue any full-range form                                                                                                                  | #269: per-year count summed + external spills; tests mechanically forbid full-range queries                                                                       |
| 3   | 4-9      | (a) 1.5 GB spill thresholds too high on a retention-inflated server; (b) jemalloc retention RATCHETS across year-chunks - failures marched later year-by-year as caps rose                                                       | #271: 500 MB spills everywhere + deterministic `SYSTEM JEMALLOC PURGE` per chunk                                                                                  |
| 4   | 10       | Relative artifact paths resolved against the TRAINER's cwd; the api (cwd `/opt/bullpen`, different user) could not find them                                                                                                     | #272: `RetrainOutput` paths `.resolve()`d absolute                                                                                                                |
| 5   | 11, 12   | Path VISIBILITY: `/home/<user>` is 750 so the api could not traverse it, and the registry's 422 said "does not exist" for what was really EACCES; the `../contracts/...` default pointed into the trainer's world, not the api's | #272: ENOENT vs EACCES distinguished in the 422; cwd-independent contract default + `BULLPEN_FEATURE_PIPELINE_PATH`; staging dir `/opt/bullpen/retrain-artifacts` |
| 6   | 13-15    | OBSERVABILITY: `_run_clickhouse` swallowed clickhouse-client stderr, so three attempts were diagnosed by assumption ("what does exit-241 mean this time?")                                                                       | #272: stderr (500 chars) surfaced into the raised error and the queue row's `error_message`                                                                       |

Plus one control-plane finding along the way: the queue's `claimNextQueued` maps SQLITE_BUSY to
"queue empty" (correct for claim races, misleading when a concurrent enqueue holds the write
lock) - tracked as [#270](https://github.com/Alexm-picard/the-bullpen/issues/270), workaround is
re-running the claim.

## What went right

- **The control plane itself never failed.** Claim semantics, the rule-13 fence, failure
  capture (row -> `failed` with the error), the rule-7 hash gate, and human-gated promotion all
  behaved as designed on every attempt, including attempt 1's 8-second failure. The 15 failed
  queue rows stand as honest history.
- **The champion was never at risk.** Serving continued untouched throughout; a retrain failure
  is a queue row, not an incident.
- **The evidence discipline held.** Every box observation crossed to the repo as code, tests,
  or documented environment (ADR-0006: the box authors nothing in git).
- **Thermals were a non-issue:** peak 49C across four full GPU trainings.

## What we learned (the durable rules)

1. **Every query on the big joined pair is per-year + partial_merge + bounded spills, from
   birth.** The full-range form is settings-unrescuable - probes buried it; unit tests now
   mechanically reject any full-range query shape (`training/.../mlp/dataset.py` module
   docstring carries the complete 6-probe evidence map).
2. **A static memory cap cannot outrun a ratcheting baseline** - allocator retention across
   chunks needs a deterministic in-loader release, not a bigger cap or a babysitter process.
3. **Paths that cross a process boundary are absolute, always** - and a permission failure must
   never masquerade as absence (`Files.exists()` is false for both; distinguish ENOENT/EACCES).
4. **Every subprocess wrapper surfaces stderr from birth.** Three attempts were burned
   diagnosing an exit code by assumption. The observability gap was the single most expensive
   root cause because it multiplied the cost of every other one.
5. **The working environment is documentation** - the ceremony runbook now carries the exact
   env block (`BULLPEN_ADMIN_USER/PASSWORD`, `CH_ADMIN_PASSWORD`,
   `BULLPEN_RETRAIN_ARTIFACT_DIR`, `BULLPEN_FEATURE_PIPELINE_PATH`, optional
   `BULLPEN_LOADER_MERGE_QUIET`), each variable annotated with which attempt its absence burned.

## Open items

- **CH memory cap decision:** the container ran at a temporary owner-approved 6g for the
  winning run; choose 6g-stays vs restore-4g and record it (capacity.md + a decisions.md
  entry via /decide).
- **The unattended timer path:** the MANUAL ceremony is proven; `bullpen-retrain.timer` stays
  disabled until the unit carries the proven env block (this batch updates the unit template;
  the box re-arm is the owner's step).
- **v3's disposition:** stays CANDIDATE until/unless the owner wants it serving - which means
  pre-declared promotion criteria (rule 5) through the /promote flow. No urgency: v2 serves.

## References

- Decisions: [172] (the servable adapter that this hand-off gates on), [178] (this hand-off's
  record entry), [51] (per-park calibration), rules 5/6/7/13.
- PRs: #266, #269, #271, #272 (the fix chain), #208 (the adapter).
- Runbooks: [retrain-ceremony.md](../runbooks/retrain-ceremony.md) (now carrying the proven
  env block), [retraining-failure-recovery.md](../runbooks/retraining-failure-recovery.md)
  (exercised 15 times).

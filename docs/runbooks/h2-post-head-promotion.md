# H2 - POST-head promotion (full-box gate -> SHADOW -> LIVE)

> **Scope:** the operator hand-off that turns the `pitch_outcome_post` head from a registered
> SHADOW model into the first **pitch CHAMPION**, lighting up user-visible per-pitch predictions
> (which render `n/a` today by design - decision [154]/ADR-0011). This is the single highest-leverage
> piece of real engineering left: it closes the "visible predictions" half of the live-data story
> AND gives the project its first honestly promotion-cleared pitch champion.
>
> **Where:** the WSL2 desktop (ADR-0006). Authoring is on the Mac; the gate run + promotion happen
> on the box, on explicit owner approval (rule 6 - human-gated, never automated).
>
> **Why POST and not PRE:** the PRE head's only evidence row honestly FAILS its declared primary
> (Brier edge inside the 0.002 margin) - promoting it would be a threshold bypass ([154]/ADR-0011).
> The POST head (Tier-4 early-flight features) is the strong candidate: its sample-stage gate
> **PASSED** with a Brier margin of ~0.021 (~10x the bar) and ECE 0.0194 (under the 0.02 absolute
> bar). H2 re-confirms that on full box data, where ECE has headroom (full-CV ECE was ~0.0036).
>
> **References:** decisions [154] (champion-less-by-design) / [157] (poller enabled ingest-only),
> ADR-0011, the `promote-model` skill (the 6-check gate), `training/data/eval/promotion/`
> (the sample-stage evidence rows), `docs/handoffs/2026-06-12-post-shadow-registration.md`.

---

## Pre-flight (confirm before running anything)

1. **POST is registered SHADOW.** Confirm the `pitch_outcome_post` SHADOW row and note its
   `model_version_id` (the promote-model skill loads it). Its `feature_schema_hash` must match the
   production pipeline (rule 7 - enforced at registration; re-confirm here).
2. **The LR baseline is co-registered** (`pitch_outcome_lr_baseline`) - rule 9, and it is the gate's
   champion-of-record for the relative verdict.
3. **Off-window** (rule 3): not 16:00-23:59 ET Apr-Oct, or an explicit owner waiver for the night.
4. **Snapshot first** if any ClickHouse work is involved: `infra/backup/clickhouse-snapshot.sh`.

---

## Step 1 - Run the promotion gate on FULL BOX data

The sample-stage rows under `training/data/eval/promotion/pitch_outcome_post_experiment_results.json`
already read `status: passed`, but they carry `data_source: "sample"`. The LIVE promotion must run
on full box data (the full 2015-2025 ClickHouse / the registered snapshot's `training_data.parquet`),
not the dev sample - and must be **labelled** `full` so the evidence row is self-describing. Use the
`--data-source full` flag (added for exactly this; it is a LABEL ONLY - changes no metric, threshold,
or verdict, and writes a distinct `*_experiment_results_full.json` so it never clobbers the committed
sample row):

```bash
cd training
# Point --sample-root at the FULL-box data mirror for the model (NOT data/samples/dev),
# and label the artifact 'full' so it reads as the LIVE-gate evidence, not the sample row.
uv run python -m bullpen_training.eval.promotion.driver \
  --model pitch_outcome_post \
  --sample-root <FULL_BOX_DATA_ROOT> \
  --data-source full \
  --out-dir data/eval/promotion
# Expect: [pitch_outcome_post] data_source=full status=PASSED ... champion=0.13xx challenger=0.11xx
# Writes: data/eval/promotion/pitch_outcome_post_experiment_results_full.json
```

Do NOT hand-edit the artifact JSON on the box, and do NOT touch `criteria.py` thresholds (rule 5 -
pre-declared). The gate must show **status PASSED on full data**: Brier margin >= 0.002 (expect
~0.02), ECE < 0.02 absolute (expect well under, ~0.004 from full-CV), log-loss guardrail green,
`sample_size_observed` >= 2000 (full box clears this trivially).

If it does NOT pass on full data, STOP - do not promote. Capture the failing row and hand it back;
a sample PASS that fails on full data is exactly what this re-run exists to catch.

---

## Step 2 - Promote SHADOW -> LIVE via the promote-model skill

Invoke the `promote-model` skill (or `/promote pitch_outcome_post`). It enforces the six rule-5/6
checks and will BLOCK on any failure - do not bypass:

1. Pre-declared criteria exist on the row (primary=Brier, threshold 0.002, sample target, ECE +
   log-loss guardrails) - they do.
2. A **passing** `experiment_results` row exists - the full-box row from Step 1.
3. Shadow-traffic sanity: `prediction_log` shows POST shadow predictions in the expected volume.
4. Rollback plan documented (see below).
5. Human approval recorded - type the `model_version_id` back as the confirmation token.
6. Not in a live-game window (rule 3).

On confirmation the skill flips POST to LIVE, records the displaced row, appends
`docs/promotion_log.md`, and pings Discord.

**Rollback plan:** there is no prior pitch champion, so the displaced state is "no champion." To
roll back: set POST back to `SHADOW` (the A/B router stops routing it user-visible) - per-pitch
predictions revert to `n/a`, which is the current, safe, by-design state. No data loss.

---

## Step 3 - Verify visible predictions light up

```bash
# A pitch prediction now returns a champion result (was n/a):
curl -fsS https://thebullpen.net/v1/predict/pitch -X POST -H 'content-type: application/json' \
  -d '{"countBalls":1,"countStrikes":2,"outs":1,"inning":5,"baseState":0,"scoreDiff":0,"dow":3,"pitcherThrows":"R","batterStand":"L","parkId":"BOS","pitcherId":1,"batterId":2}'

# During a live game, /games/:id should now render per-pitch predictions instead of n/a.
# prediction_log should show champion-role rows for pitch_outcome_post:
docker exec bullpen-clickhouse clickhouse-client --password "$CH_PASSWORD" --query \
  "SELECT role, count() FROM prediction_log WHERE model_name='pitch_outcome_post'
     AND request_at > now() - INTERVAL 1 HOUR GROUP BY role"
```

Watch Grafana for 10 minutes (p99 latency, error rate). The POST head adds Tier-4 ONNX inference to
the serving path - confirm latency stays under the p99 budget.

---

## After promotion (close the loop)

- Update the docs that currently say "no pitch champion / predictions held by design": `CLAUDE.md`
  ("Current reality" poller bullet + the "three calibrated models" line) and `README.md` - the
  honest framing flips from "held by design" to "POST head serving live as the first pitch
  champion, promoted on full-box evidence."
- The PRE head stays SHADOW; its honest status is unchanged (may never clear its own margin - that
  is a real property of a pre-pitch-only feature set, not a bug, and is good interview material).
- Capture the promotion as the operating-evidence artifact it is.

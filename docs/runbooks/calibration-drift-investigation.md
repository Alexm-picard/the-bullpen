# Runbook — Investigating a calibration-drift PAGE

**Owner:** alex · **Last reviewed:** 2026-05-24 · **Phase:** 3c.7

Fires when a champion model's `CALIBRATION_ERROR` metric (computed daily
by `CalibrationJob`) sustains above the page threshold (default 0.10,
configurable via `bullpen.drift.alert.calibration-page-threshold`) for
3+ consecutive days. The Discord WARN message includes:

- `worst_value` — the highest ECE seen in the 3-day window
- `consecutive_days` — count of rows above threshold
- `runbook` — link back to this document

## What it means

The champion model's predicted probabilities have systematically diverged
from observed outcomes for 3+ days running. Either:

1. **Concept drift** — the underlying data-generating process has
   shifted (rule changes, new park, new pitcher cohort, weather, etc.).
2. **Population shift** — the inputs the model sees have changed in a
   way that breaks the calibration baseline.
3. **Pipeline corruption** — a recent deploy broke feature transformation,
   the model is being fed garbage, and the output is therefore
   miscalibrated.

## Triage steps

1. **Confirm the alert is real** — query `drift_metrics` directly to
   see the trend, not just the 3-day window:

   ```bash
   docker compose -f infra/docker-compose.yml exec clickhouse \
     clickhouse-client -q "SELECT toDate(computed_at), metric_value
     FROM drift_metrics
     WHERE model_name = '<name>' AND metric_type = 'calibration_error'
     AND feature_or_segment = 'all'
     ORDER BY computed_at DESC LIMIT 30"
   ```

   If only 3 days are above threshold and the rest are clean, this is
   probably a real recent shift. If the metric has been climbing for
   weeks, it's been ignored for a while.

2. **Check the segment breakdown** — was the regression concentrated
   in a single park / pitch type / handedness?

   ```bash
   docker compose -f infra/docker-compose.yml exec clickhouse \
     clickhouse-client -q "SELECT feature_or_segment, metric_value
     FROM drift_metrics
     WHERE model_name = '<name>' AND metric_type = 'segment_brier'
     AND feature_or_segment LIKE '%:7d'
     AND computed_at >= now() - INTERVAL 1 DAY
     ORDER BY metric_value DESC LIMIT 20"
   ```

   Most-recent weekly segment Brier values, descending. If one
   dimension dominates (e.g. all the worst segments are `park_id:*`),
   you have a localized issue.

3. **Check if a deploy correlated** — every deploy gets a SHA + a
   `docs/deploys/{date}.md` entry. Compare the alert's `first_seen`
   timestamp to the most recent deploy. If they're within an hour,
   the deploy is the suspect.

4. **Check the feature pipeline** — if features have changed shape, the
   model is making predictions over garbage. PSI_FEATURE alerts would
   fire too — check whether the PSI dashboard shows anything anomalous.

5. **Look at the predictions themselves** — pull a sample from
   `prediction_log` for the alert's window and eyeball the output
   distribution. Is the model now skewed toward one class?

## Resolution paths

| Diagnosis                               | Action                                                                                                                                                                           |
| --------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Pipeline corruption (recent deploy)     | Roll back via `./deploy.sh <prior-SHA>`. Open a postmortem under `docs/postmortems/`.                                                                                            |
| Concept drift, gradual                  | File a retraining request (Phase 3d's `retraining_queue` once it lands; for now, manually queue a retrain run).                                                                  |
| Concept drift, sudden                   | Trigger a manual retrain on the latest 30 days of data. Stage as challenger, run shadow A/B, promote via 3a.4 flow.                                                              |
| False positive (sample size, noisy day) | If the metric was only just above threshold and the day was abnormal (e.g. postponed games), accept the alert and watch for recurrence. The 24h dedup will silence it for a day. |

## After resolution

- **Add a postmortem** under `docs/postmortems/YYYY-MM-DD_calibration-drift-<name>.md`
  if the root cause was a deploy or a real data event.
- **Tune the threshold** if the alert was a false positive driven by a
  known-noisy condition. Change `bullpen.drift.alert.calibration-page-threshold`
  in the prod env file; restart the worker.

## Related

- Leaf 3c.4 — daily calibration batch
- Leaf 3c.5 — weekly per-segment Brier (for the segment-breakdown query)
- `DriftAlertEvaluator` source —
  `backend/src/main/java/net/thebullpen/baseball/drift/alerting/DriftAlertEvaluator.java`

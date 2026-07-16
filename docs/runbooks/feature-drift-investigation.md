# Runbook — Investigating a feature-drift NOTICE

**Owner:** alex · **Last reviewed:** 2026-05-24 · **Phase:** 3c.7

Fires when a champion model's `PSI_FEATURE` metric for a single feature
sustains above the notice threshold (default 0.25, configurable via
`bullpen.drift.alert.feature-psi-notice-threshold`) for 7+ consecutive
days. The Discord NOTICE message includes:

- `feature` — the input feature whose distribution shifted
- `worst_value` — peak PSI in the 7-day window
- `consecutive_days` — count above threshold (≥ 7)
- `runbook` — link back to this document

PSI rule-of-thumb (industry):

| PSI       | Interpretation                  |
| --------- | ------------------------------- |
| < 0.10    | No significant change           |
| 0.10–0.25 | Moderate drift — worth watching |
| > 0.25    | Significant drift — investigate |

A NOTICE is informational — not a wake-up page. If the calibration job
ALSO fires (PAGE on `CALIBRATION_ERROR`), feature drift is likely the
upstream cause; chase that thread first.

## What it means

The distribution of one input feature has shifted away from the model's
training-time baseline. The model wasn't trained on this distribution
so its predictions on the new shape may be poorly calibrated even if
the change is benign.

## Triage steps

1. **Confirm the shift is real** — query 30 days of PSI for the feature:

   ```bash
   docker compose -f infra/docker-compose.yml exec clickhouse \
     clickhouse-client -q "SELECT toDate(computed_at), metric_value, sample_size
     FROM drift_metrics
     WHERE model_name = '<name>' AND metric_type = 'psi_feature'
     AND feature_or_segment = '<feature>'
     ORDER BY computed_at DESC LIMIT 30"
   ```

   If PSI was below 0.1 a month ago and is now above 0.25, it's a real
   shift. If it's been hovering around 0.25 for a long time, the
   threshold may need adjustment for this feature.

2. **Compare the distributions** — peek at the histogram of the feature
   over the last 24h vs the training baseline. The reference
   distribution is in the model's `metadata.json` under
   `feature_distributions.<feature_name>`. The observed distribution
   can be reconstructed from `prediction_log.features` (JSON column).

3. **Identify the suspect cause** —

   | Feature type                                  | Likely shift causes                                            |
   | --------------------------------------------- | -------------------------------------------------------------- |
   | Park-related (e.g. `park_id`, `park_hr_rate`) | New season schedule mix; expansion / re-alignment              |
   | Pitcher-related (`pitcher_strike_rate_28d`)   | New pitcher cohort; rule changes (pitch clock, sticky stuff)   |
   | Batter-related                                | Lineup turnover; injury-replacement cohort                     |
   | Count/situational                             | Strike-zone enforcement changes; new rule that shifts approach |
   | Weather                                       | Seasonal — Aug humidity vs Apr cold; sustained climate trend   |

4. **Check if calibration also fires** — if `CALIBRATION_ERROR` is
   approaching the page threshold (0.10), this NOTICE is probably the
   leading indicator. See
   [calibration-drift-investigation](./calibration-drift-investigation.md).

5. **Look at the segment breakdown** — does the per-park / per-handedness
   Brier show this feature's impact concentrated somewhere?

   ```bash
   docker compose -f infra/docker-compose.yml exec clickhouse \
     clickhouse-client -q "SELECT feature_or_segment, metric_value
     FROM drift_metrics
     WHERE model_name = '<name>' AND metric_type = 'segment_brier'
     AND feature_or_segment LIKE '%:28d'
     ORDER BY computed_at DESC, metric_value DESC LIMIT 20"
   ```

## Resolution paths

| Diagnosis                                                                                   | Action                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| ------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Real seasonal / cohort drift, calibration still good                                        | Accept the NOTICE. Document in `docs/decisions.md` if it represents a new normal. Consider tuning the threshold for this feature.                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| Real drift, calibration also degrading                                                      | Escalate via the [calibration runbook](./calibration-drift-investigation.md). Queue a retrain.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| Reference distribution outdated (a real-but-OK 2024 shift never made it to `metadata.json`) | Re-run `scripts/backfill_training_distributions.py` against the champion bundle to rewrite `feature_distributions` (the single baseline-emission mechanism), then let the next PSI run reset the baseline.                                                                                                                                                                                                                                                                                                                                                                        |
| False positive (small observed sample)                                                      | Verify `sample_size` in the query above. The math (first-organic triage, 2026-07-16): with 10 reference bins the no-drift noise floor is E[PSI] ~= (B-1)/n, which alone crosses the 0.25 threshold below n ~= 36, and each observed-empty decile adds ~0.69 - n=27 legitimately read PSI 1.62 with zero drift. The alert side now ENFORCES `bullpen.drift.alert.feature-psi-min-sample` (default 300): sub-floor rows are still written (visible on /ops with their sample sizes) but cannot fire or block a NOTICE. See docs/postmortems/2026-07-16_first-organic-psi-triage.md. |

## After resolution

- **No postmortem required** for an accepted real-drift NOTICE.
- **If retrained**, the new champion's `metadata.json` will carry an
  updated reference distribution; PSI on the same feature should fall
  back below 0.1 within ~7 days.
- **Dedup window is 24h** — the NOTICE stays silenced for a day after
  fire. If you fix the underlying issue and want to verify, wait 24h+
  for the next eval cycle.

## No PSI rows at all (the `DriftBaselineMissing` alert)

Distinct from a high-PSI NOTICE: if the daily job finds a champion with **no
training-distribution baseline**, it cannot compute PSI for that model at all
and writes zero rows. This is the silent-starve that produced an empty
`drift_metrics` on 2026-07-04 (decision [175]). It is now loud:

- **Alert:** `DriftBaselineMissing` (Prometheus, `severity: warning`) fires when
  `bullpen_drift_baseline_missing_total{model,kind}` increases over a daily cycle.
- **Log:** `PsiFeatureJob` / `PsiPredictionJob` WARN naming the champion and the
  exact remediation command (SHADOW challengers log INFO only — a shadow may
  legitimately lack a baseline, so it never trips the alert).

**Fix — run the backfill CLI against the champion bundle on the box** (the single
baseline-emission mechanism; also step 6 of the promote-model ceremony):

```bash
# From training/ on the box. battedball derives from ClickHouse; pitch reads the
# on-box snapshot parquet. Rule-13 safe (2015-2025), rule-7 hash-safe (additive keys).
uv run python scripts/backfill_training_distributions.py \
  --model <name> --model-dir <champion bundle dir> [--training-parquet <bundle>/training_data.parquet]
```

Confirm a non-zero `drift_metrics` count after the next 2 AM ET run (or force a
`runOnce`). A newly promoted champion is the usual trigger — its `metadata.json`
does not carry the baseline until the backfill writes it.

## Related

- Leaf 3c.2 — daily PSI on features
- Leaf 3c.5 — weekly per-segment Brier
- `Psi.computeContinuous` source — `backend/src/main/java/net/thebullpen/baseball/drift/algorithms/Psi.java`

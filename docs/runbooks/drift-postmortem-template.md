# Drift postmortem template

> Copy this file to `docs/postmortems/YYYY-MM-DD-<slug>.md` when a drift
> alert fires. Fill in alongside the investigation; commit when the
> resolution is in place. Don't sanitize.
>
> For drill-fired synthetic events, prefix the filename with `drill-`
> and add a banner at the top: **"Drill — not real production drift."**

---

# Postmortem: <one-line title>

- **Date range**: YYYY-MM-DD to YYYY-MM-DD
- **Severity**: page | notice
- **Affected model**: `model_name` v\<N\>
- **Detected by**: detector job name + threshold that fired
- **Operator**: <name>
- **Summary**: 3 sentences max. What happened, what was wrong, what
  changed.

## Timeline (UTC)

| When             | What                            |
| ---------------- | ------------------------------- |
| YYYY-MM-DDTHH:MM | Drift detector fired            |
| YYYY-MM-DDTHH:MM | Discord NOTICE / PAGE delivered |
| YYYY-MM-DDTHH:MM | Investigation began             |
| YYYY-MM-DDTHH:MM | Root cause confirmed            |
| YYYY-MM-DDTHH:MM | Resolution applied              |
| YYYY-MM-DDTHH:MM | Postmortem written              |

## What happened

<Prose. Reader is a future-you or a fresh teammate; they don't have
the context you have right now. Spell out the chain of events.>

## Dashboards (snapshots)

Attach Grafana / Ops-dashboard PNGs showing:

- Calibration drift sparkline at the time of detection
- Feature-distribution shift heatmap
- Prediction-log volume + class distribution over the window
- Anything else that was load-bearing for the investigation

Store under `docs/postmortems/<this-postmortem-slug>/`.

## Drift metrics that fired

| Metric                  | Value | Threshold                 | Notes        |
| ----------------------- | ----- | ------------------------- | ------------ |
| `calibration_delta_ece` | 0.052 | 0.030 (1.5× training)     | sustained 7d |
| `psi_<feature>`         | 0.247 | 0.20 (yellow), 0.30 (red) |              |

## Hypotheses + tests

1. **Hypothesis A**: <text>
   - **Test**: <how you tested>
   - **Result**: confirmed | rejected | inconclusive
2. **Hypothesis B**: …
3. **Hypothesis C**: …

## Root cause (5-Whys)

1. Why did calibration drift? Because <reason>.
2. Why <that reason>? Because <reason>.
3. Why <that reason>? Because <reason>.
4. Why <that reason>? Because <reason>.
5. **Root cause**: <the underlying-thing answer>.

## Resolution

<What you did. PR links, config changes, retraining runs, threshold
adjustments.>

## What changed in the system

- [ ] Code change: <PR link>
- [ ] Test added (CI regression for this failure mode): <link>
- [ ] Alert tightened / loosened (specify which): <details>
- [ ] Decision logged: `[N]` in `docs/decisions.md`
- [ ] ADR raised (if the change is architecturally substantive):
      `docs/adr/NNNN-…md`
- [ ] Runbook updated (if the operator's response should change):
<link>

## Lessons

<Prose. The thing you'd tell the next operator. One per paragraph.>

## References

- Drift detector run id / Discord alert message link
- Related decisions log entries `[N]`
- Related model versions in the registry
- External: MLB rule changes, weather events, ABS rollout dates, etc.

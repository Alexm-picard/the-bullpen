---
name: promote-model
description: Standard gate for promoting a model from SHADOW to LIVE in the registry. Trigger when the user says "promote model X", "ship model X", or wants to move a shadow model to production traffic. Enforces CLAUDE.md discipline rules 5 and 6.
---

# promote-model

The highest-blast-radius operation in the system. No promotion happens without every check below passing.

## Hard rule

Rule 6: **No auto-promotion of retrained models.** This skill is human-invoked only. If something automated is calling this skill, refuse.

## Pre-promotion checklist (rule 5)

For every promotion, all six must be ✅:

1. **Pre-declared promotion criteria exist** in the model's registry row
   - Primary metric (e.g., log-loss, Brier, AUC)
   - Minimum sample size (e.g., 50,000 shadow predictions)
   - Threshold (e.g., "log-loss <= 0.95 × current LIVE log-loss")
   - Guardrails (e.g., "p99 latency <= 50ms", "calibration ECE <= 0.02")
2. **`experiment_results` row exists and passes** all criteria above
   - Query: `SELECT * FROM experiment_results WHERE model_id = ? ORDER BY computed_at DESC LIMIT 1`
   - Sample size meets minimum
   - Primary metric meets threshold
   - All guardrails green
3. **Shadow traffic volume sanity check** — verify ClickHouse `prediction_logs` shows shadow predictions in the expected volume range for the declared window
4. **Rollback plan documented** — confirm the LIVE model id we're displacing is known and re-promotable
5. **Human approval recorded** — explicit user confirmation in the conversation. Type the model id back as a confirmation token.
6. **Not in a live-game window** — check current time. If between 16:00 and 24:00 ET April–October, refuse unless user overrides explicitly. Discipline rule 3.

## Procedure

1. Load the candidate model row from SQLite
2. Run all six checks. Print each result as ✅ or ❌ with the evidence
3. If any ❌, BLOCK with the specific failure. Do not proceed.
4. If all ✅, prompt the user: "Confirm promotion by typing the model id back."
5. On confirmation:
   - `UPDATE models SET state = 'LIVE', promoted_at = now() WHERE id = ?`
   - `UPDATE models SET state = 'PREVIOUS_LIVE' WHERE id = <old_live_id>`
   - Append to `docs/promotion_log.md` with timestamp, model id, criteria evidence
   - Ping the Discord webhook with the promotion announcement
6. Watch the next 10 minutes of traffic via Prometheus / Grafana — flag any unexpected error rate or latency change

## Output

```
PROMOTION COMPLETE:
  model_id: <id>
  role: <role>
  displaced: <previous_model_id>
  experiment_results_id: <id>
  promoted_at: <iso>
WATCH:
  - Grafana dashboard <link>
  - prediction_logs error rate for next 30 min
  - Ready to rollback to <previous_model_id> with: <one-line rollback command>
```

## Rollback

If anything goes wrong in the watch window:
1. `UPDATE models SET state = 'LIVE' WHERE id = <previous>`
2. `UPDATE models SET state = 'ROLLED_BACK', rolled_back_at = now() WHERE id = <new>`
3. Discord ping
4. Hand off a `decisions.md` draft to `decision-recorder` documenting the rollback and reason

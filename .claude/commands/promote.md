---
description: Promote a model from SHADOW to LIVE through the full discipline gate
argument-hint: <model_id>
---

Invoke the `promote-model` skill for model id:

$ARGUMENTS

Run all six pre-promotion checks (pre-declared criteria, passing experiment_results, shadow volume, rollback plan, human approval, live-game-window check). BLOCK on any failure. Require me to type the model id back as a confirmation token before any state change. After promotion, watch traffic for 10 minutes via Grafana and Discord-ping the result.

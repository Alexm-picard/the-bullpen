# r/sabermetrics

## Title

Built a per-park batted-ball / pitch-outcome serving stack with rolling-origin CV + public eval artifacts

## Body

Long-time r/sabermetrics reader, first time posting a build —
thebullpen.net is a self-hosted prediction service I've been working on
for the past eight months. Three models:

- Pitch outcome (pre-pitch): LightGBM, 5-class multinomial, isotonic
  calibrated on a held-out fold.
- Pitch outcome (post-pitch): same shape with early-flight features
  (release speed, plate location, spin rate) — separate registered
  model, never one model with feature masking.
- Batted-ball per-park HR probability: shared-backbone MLP with 30
  per-park heads (Phase 2c.5 — toy spine serves today).

What might interest this sub:

- **Eval artifact directory per model version**: rolling-origin CV (4
  folds 2015–2025), Brier + ECE + log-loss as mean ± std across folds,
  per-fold table, reliability diagrams, calibration delta vs the
  training-fold baseline.
- **Logistic-regression baseline is always co-registered** alongside
  the neural model. You can see it on the Ops page — the gap is the
  signal the neural model is actually buying.
- **Per-park calibration** breaks out separately per park, not pooled.
  Coors' calibration curve looks materially different from Tropicana
  Field's; pooling hides that.
- **Drift detection on prediction logs**: PSI per feature on a 7-day
  window + calibration delta against the training-fold baseline.
  Sustained-window threshold queues a retrain; promotion stays
  human-gated.
- **Leakage tests gated in CI**: future contamination, shuffled
  target, calendar-date trace, ID consistency.

Live: https://thebullpen.net  
Ops dashboard (public read): https://thebullpen.net/ops  
About / methodology: https://thebullpen.net/about  
Repo + decisions log: https://github.com/Alexm-picard/the-bullpen

Happy to discuss the calibration setup, the per-park split, or why I
co-register the LR baseline rather than just reporting deltas.

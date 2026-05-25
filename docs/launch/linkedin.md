# LinkedIn — personal post

After eight months of evenings + weekends, I just shipped **The
Bullpen** — a self-hosted baseball-prediction service I built primarily
to learn and demonstrate the operational discipline around shipping ML
systems.

What's there:

- A from-scratch model registry, A/B router, drift detection, and
  retraining triggers — Java + Spring Boot 3, ONNX Runtime in-process,
  no MLflow.
- Three calibrated models served behind one wrapper: pitch outcome (pre
  and post), batted-ball per-park HR probability. Each registered with
  a rolling-origin CV eval artifact and a logistic-regression baseline.
- Public Ops dashboard with the live registry, drift sparklines, A/B
  routing, retrain queue, and per-model calibration summary.
- Editorial-data design system — Inter / JetBrains Mono / Source
  Serif 4 on a warm-paper substrate.

Site: https://thebullpen.net  
Methodology: https://thebullpen.net/about  
Code + decisions log: https://github.com/Alexm-picard/the-bullpen

If you build serving infrastructure for ML and want to compare notes,
DM me.

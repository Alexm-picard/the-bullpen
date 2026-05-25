# r/programming

## Title

The Bullpen — a from-scratch ML systems wrapper (registry, A/B router, drift, retrain) around three baseball models, in Java + Spring + ONNX

## Body

I built **thebullpen.net** primarily as an excuse to write the
surrounding ML systems infrastructure — the baseball models are the
"why," but the registry / router / drift / retraining wrapper is the
project.

What's interesting from a systems perspective:

- **Custom model registry** in SQLite (Flyway-managed). Versions,
  stages (CANDIDATE / SHADOW / CHAMPION), feature schema hash, eval
  metrics JSON, training data hash. Registration verifies the feature
  schema hash matches the in-tree `feature_pipeline.json` — schema
  mismatch is a hard fail (CLAUDE.md rule 7).
- **Spring Boot 3 + ONNX Runtime in-process** for serving. No Python
  sidecar, no RPC. Training is Python and emits ONNX; the Python ↔
  Java contract is purely file-based (ONNX + metadata JSON + pipeline
  JSON + Parquet training snapshot).
- **A/B router** is in-process Java, Caffeine-cached, decides per
  request which model serves user-facing and which shadow. Logs the
  decision; reads happen on every inference request.
- **Drift detection** worker computes PSI per feature on a 7-day
  window + calibration delta vs the training-fold baseline. Sustained
  threshold queues a retrain in a SQLite-backed queue. The atomic
  claim uses SELECT-then-UPDATE-WHERE-status='queued' on a
  single-writer DB; no SKIP LOCKED needed.
- **Retraining is automated, promotion is human-gated.** Drift fires
  a trigger → worker claims, retrains, registers as CANDIDATE →
  appears on the Ops dashboard for review. No model ever auto-routes
  in front of users.
- **Editorial-data design system** instead of yet-another-SaaS chrome:
  Inter / JetBrains Mono / Source Serif 4 on a warm-paper substrate.
  No hex codes in components (CLAUDE.md rule 2); CI gate enforces.

The decisions log explains the rejected alternatives —
MLflow, Python sidecar, microservices, WebSockets, Next.js/SSR — with
written rationale. Read the decisions before re-litigating any of them:
https://github.com/Alexm-picard/the-bullpen/blob/main/docs/decisions.md

Live site: https://thebullpen.net  
Ops dashboard: https://thebullpen.net/ops  
About: https://thebullpen.net/about  
Repo + design.md + plan.md: https://github.com/Alexm-picard/the-bullpen

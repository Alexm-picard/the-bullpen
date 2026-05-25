# Hacker News — Show HN

## Title

Show HN: The Bullpen — a self-hosted ML systems wrapper around three baseball models

## Body (first comment)

I spent eight months building a baseball-prediction service primarily as
an excuse to write the surrounding ML systems infrastructure: a custom
model registry, A/B router, drift detection, and retraining triggers.
Three models live behind the wrapper — pitch outcome (pre and post),
batted-ball per-park HR probability — each registered with a rolling-
origin CV eval artifact and a co-registered logistic-regression
baseline.

Serving is Spring Boot 3 with ONNX Runtime in-process (no Python
sidecar). Training is Python and emits ONNX + a `feature_pipeline.json`
that the JVM hashes at registration — schema mismatch fails fast.

The service runs from my desktop in WSL2 through a Cloudflare Tunnel.
Public Ops dashboard at https://thebullpen.net/ops shows the registry,
drift sparklines, A/B routing, retrain queue, and calibration summary.
About / methodology page covers the choices:
https://thebullpen.net/about

Honest about what's not there: prediction_log truth-join is wired
contractually but the per-pitch index needs to land before per-player
calibration views populate fully; the live MLB-Stats poller has the
state machine + endpoints but the poller class itself is the next
follow-up. Both are documented in the README's "Known limitations".

Decisions log, ADRs, and the phased build plan are public:
https://github.com/Alexm-picard/the-bullpen

Happy to answer questions about the wrapper, the eval methodology, or
why I deliberately didn't use MLflow.

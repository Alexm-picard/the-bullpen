# 60-second verbal pitch

Read aloud. Time yourself. Target: 50–60 seconds, no pauses to think.

---

## Pitch (≈ 55 seconds spoken)

> The Bullpen is a self-hosted baseball-prediction service I built over
> about eight months. The interesting thing isn't the predictions — it's
> the ML systems wrapper around them.
>
> I wrote, from scratch, a model registry, an A/B router, drift
> detection, and an automated retraining queue — in Java with Spring
> Boot, serving ONNX models in-process. No MLflow, no Python sidecar.
> Training is Python; the contract between them is a
> `feature_pipeline.json` whose hash gets verified at registration —
> schema mismatch fails fast.
>
> Three models live behind the wrapper: pitch outcome pre- and
> post-pitch as separate registered models, plus a batted-ball per-park
> home-run probability head. Every model gets registered alongside a
> logistic-regression baseline and a rolling-origin cross-validation
> eval artifact — so the lift is always visible.
>
> The site runs from my desktop in WSL2 through a Cloudflare Tunnel.
> Public Ops dashboard at thebullpen.net/ops shows the registry, drift
> sparklines, A/B routing, retrain queue, calibration summaries —
> everything an SRE would actually want to see.
>
> The point of operating it through an MLB season is to write a real
> drift postmortem when a model degrades. That's the centerpiece resume
> artifact: not "I trained a model," but "I shipped one, watched it
> drift, and wrote up why."

---

## Variants

### 30-second elevator

> Self-hosted baseball-prediction service — Java + Spring + ONNX in-
> process, no Python sidecar — wrapping three models with a from-
> scratch model registry, A/B router, drift detection, and retraining
> queue. Operating through an MLB season for the drift postmortem.
> Public Ops dashboard at thebullpen.net/ops. The wrapper is the
> project; the models are the excuse.

### 15-second hook

> Baseball-prediction service whose actual point is the from-scratch
> ML systems wrapper around it — registry, A/B, drift, retrain — and
> the drift postmortem at the end of the season.

---

## Q&A — anticipate

**Why not MLflow?**

> Decision in the repo's `decisions.md` log. MLflow optimizes for
> notebook → registered-model workflows in Python; I wanted to learn
> what owning the registry surface looks like in a JVM serving layer
> where the same registry row gates the A/B router and the drift
> evaluator. Writing it myself was the educational point. The wrapper
> is ~3000 lines of Java; the surface area I get back is tight.

**Why ONNX in-process instead of a Python sidecar?**

> Latency, deployment simplicity, and forcing the Python → Java
> contract to be file-based — which makes the feature-pipeline hash
> check meaningful. There's an ADR for this in `docs/adr/`.

**How do you know the models are actually good?**

> Rolling-origin CV, never random splits — temporal cut by date, not
> by game or pitch. Brier + ECE + log-loss reported mean ± std across
> 4 folds (2015–2025). A logistic-regression baseline is always
> co-registered so the gap is the signal.

**What's in the postmortem?**

> Pull up the template in `ops/runbooks/drift-postmortem-template.md`
> — timeline, dashboards, drift metrics that fired, hypotheses-tested,
> 5-Whys root cause, what changed in the system. Real SRE format, not
> blog format.

**What would you do differently if you started over?**

> Wire the prediction-log truth-join early — it's the bottleneck for
> the per-player calibration view and the agreement marker on the live
> game feed. I deferred it; with hindsight I'd have built it alongside
> the registry.

---

## Rehearsal log

| Date       | Run | Wall time | Notes                                         |
| ---------- | --- | --------- | --------------------------------------------- |
| YYYY-MM-DD | 1   | 0:00      | first read aloud — too fast / slow / awkward? |
| YYYY-MM-DD | 2   | 0:00      |                                               |
| YYYY-MM-DD | 3   | 0:00      |                                               |

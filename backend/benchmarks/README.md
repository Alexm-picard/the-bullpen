# Inference-path JMH baselines

`baseline.json` is the reference the nightly `benchmark` workflow compares each
run against (via `scripts/check_benchmarks.py`, 25% regression threshold).

Benchmarks (see `backend/src/jmh/java/.../InferenceBenchmark.java`):

| Benchmark               | What it measures                              |
| ----------------------- | --------------------------------------------- |
| `onnxBattedBallPredict` | one ONNX forward pass (toy batted-ball model) |
| `routerBucketDecision`  | Murmur3 game-id bucketing                     |
| `routerAbRouteDecision` | full A/B route decision (bucket + mode + pct) |

## Re-baselining

JMH absolute times are **hardware-specific**, so a baseline taken on dev hardware
(Apple M-series) is not directly comparable to a GitHub shared runner. The
committed seed is a dev-hardware run; for accurate night-over-night regression
detection, re-baseline from CI:

```
# locally:
cd backend && ./gradlew jmh
cp build/results/jmh/results.json benchmarks/baseline.json

# or trigger the `benchmark` workflow with workflow_dispatch (rebaseline=true)
# to capture the CI runner's numbers.
```

The nightly comparison is **informational (Discord-ping, non-blocking)** — JMH
timing flaps on shared runners, so it never fails the build; it flags a likely
regression for a human to confirm + re-baseline.

# ADR-0002: Use ONNX Runtime Java for in-process inference, not a Python sidecar

- **Status**: Accepted
- **Date**: 2026-05-19
- **Deciders**: alex
- **Related**: `decisions.md` entries [26] [27] [28] [30], `plan.md` Phase 1–2, `design.md` §5 §10

## Context

The serving stack is JVM (Spring Boot 3.x). The training stack is Python
(LightGBM / scikit-learn / a multi-output MLP via PyTorch). The boundary
between training and serving is the central architectural decision of this
project — get it wrong and the project either becomes a Python-on-the-serving-
path liability or a fragile RPC dance between two runtimes.

The locked latency target is p99 prediction under 50ms end-to-end (`design.md`
§3). The forward simulator may issue tens of thousands of pitch predictions per
Monte Carlo run; a per-call RPC hop is structurally incompatible with that
budget.

Three integration patterns were considered:

1. **In-process inference in the JVM** via ONNX Runtime Java bindings —
   models are exported to ONNX, the JVM loads them, predictions happen on the
   same thread that handled the HTTP request.
2. **Python sidecar serving** — a separate FastAPI/Litestar process holds the
   model in memory, JVM calls it via HTTP/gRPC per prediction.
3. **In-process Python via GraalVM polyglot or JEP** — embed CPython inside
   the JVM and call native Python code without IPC.

The project is also explicit (decision [82], `plan.md` Phase 5) about
operating through an MLB season to capture a real drift postmortem. That
operational story is much cleaner with one process to monitor than two.

## Decision

We use **ONNX Runtime Java** loaded in-process inside the `api` profile of the
Spring Boot JAR. The Python ↔ Java handoff is **file-based**: training in
Python emits an ONNX model + a JSON metadata sidecar + the canonical
`/contracts/feature_pipeline.json` (with schema hash) + a Parquet snapshot of
the training data window. The registry references those file paths; nothing
crosses a network boundary at prediction time.

We explicitly reject any live RPC on the serving path — no Python sidecar, no
Triton inference server, no out-of-process model store.

## Consequences

**Easier:**

- p99 inference latency is dominated by ONNX Runtime's CPU execution path,
  not network plumbing. Easy to stay under the 50ms budget.
- Forward simulator can issue large prediction batches without RPC fan-out.
- Operationally one process, one log stream, one health check per profile.
- The Python ↔ Java contract is a directory of files, not a wire protocol —
  diffable, hashable, easy to reason about.

**Harder:**

- Model authoring is constrained to architectures that ONNX export cleanly:
  LightGBM via `onnxmltools`, sklearn via `skl2onnx`, PyTorch via the native
  exporter. Custom Python code at prediction time is impossible.
- ONNX opset compatibility becomes a real concern — pinning the opset across
  trainer + runtime is one of the first decisions Phase 0 will lock
  (`SETUP-NEXT-STEPS.md` step 7).
- Feature engineering at _serving_ time must be expressible in Java, since
  there is no Python in the prediction path. The `feature_pipeline.json`
  contract captures this — anything the pipeline cannot express in JSON-
  serializable transforms can't be a feature.

**New failure modes:**

- ONNX-export mismatch between training and serving (e.g., a sklearn
  estimator with an opset the runtime doesn't support) — caught by the
  parity test in CI (Phase 1 exit criterion: ONNX export + Java loading
  - parity test passing).
- Native-library issues on the host (onnxruntime ships JNI bindings) —
  systemd unit covers restart; restore drill exercises recovery.

**Locked into:**

- All models, including the multi-output MLP for batted ball, must export
  to ONNX. PyTorch ONNX export of a 30-head MLP works; we have validated
  that this is feasible in design.

## Alternatives Considered

### Alternative A: Python sidecar (FastAPI or Triton)

- Java POSTs feature vectors to a Python process; Python returns predictions.
- Rejected: adds an RPC hop to every prediction (5–15ms in localhost-best-
  case, plus retry/timeout complexity), violates the single-process
  operational story, and most importantly creates a second runtime to keep
  current — defeating the whole point of using JVM for the serving stack.

### Alternative B: GraalVM polyglot / JEP embedded Python

- Native Python inside the JVM, no IPC.
- Rejected: fragile, poorly documented in 2026 for the LightGBM/PyTorch
  combination needed here, and ships a second runtime in the same address
  space. The "you can call Python from Java!" trick reads worse to a
  reviewer than the clean ONNX boundary.

### Alternative C: Recompile LightGBM and the MLP as Java code

- Hand-port the trained models to Java.
- Rejected: not reproducible across retrains, brittle, and the entire reason
  ONNX exists. We'd reinvent ONNX worse.

## Revision History

(none)

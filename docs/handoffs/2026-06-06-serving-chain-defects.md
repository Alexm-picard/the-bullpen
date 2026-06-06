# Handoff: serving-chain defects (BUG-1/2/3/4) → B-workstream

- **Date**: 2026-06-06
- **From**: defect-register sweep (A-track)
- **To**: B-workstream (registry / serving rebuild - owns these files; calibrator port `3cfc396` landed in the same domain)
- **Why a handoff, not a fix**: these all live in `inference/` + `registry/`, which the B-workstream is actively rebuilding. Editing them from a second track would collide. They are also runtime-gated (no deploy during live games; BUG-1/2 verification needs the BUG-9 deploy on the box first). Per the sweep plan's coordination clause, the A-track verified them read-only and is handing off the spec.

All four **confirmed present at HEAD** (`7783823`) by direct read. File:line below are current.

---

## BUG-4 (High) - ModelLoader cache get-then-put race leaks ORT sessions

- **Where**: `backend/.../inference/ModelLoader.java:84-92` (`loadBattedBall`).
- **Root cause**: `getIfPresent` → if null → `loadBattedBallFresh` → `put`. Two threads missing the cache for the same `versionId` both build a `LoadedBattedBallModel` (each opens an ORT session). One `put` wins; the loser's bundle is never put, so the Caffeine `removalListener` never fires for it → its ORT session is **never closed** (native-memory leak), plus a wasted double-load.
- **Fix**: collapse to one atomic load - `battedBallCache.get(versionId, this::loadBattedBallFresh)`. Caffeine guarantees the mapping function runs at most once per key under contention.
- **Verify**: a concurrency unit test - N threads call `loadBattedBall(sameId)` on a cold cache; assert `loadBattedBallFresh` (or the underlying `LoadedBattedBallModel.load`) is invoked exactly once (spy/counter) and only one session is ever opened.

## BUG-1 (Critical) - registered batted-ball models are never actually served (3 stacked causes)

- **1a - routing key is the toy name.** `api/PredictBattedBallController.java:90` routes under `ToyBattedBallInference.MODEL_NAME` (`_toy_batted_ball`). Real models register under `battedball_outcome`, so `InferenceRouter` never finds a routing row → always falls through to the legacy toy supplier. Fix: route under the real model name (`battedball_outcome`); keep the legacy supplier only as the no-routing-row fallback.
- **1b - hardcoded toy contract.** `inference/ModelLoader.java:50` defaults `batted-ball-contract-path` to `../contracts/feature_pipeline_toy.json` and passes that **same** `defaultBattedBallContractPath` to `LoadedBattedBallModel.load` for **every** version (`:124`). A real model loads with the toy feature pipeline → wrong feature vector / schema-hash. Fix: resolve each model's contract from its own snapshot (the registered `feature_pipeline*.json` beside `model.onnx`), not a process-wide default.
- **1c - snapshot copy-list omits files.** `registry/SnapshotStorage.placeArtifacts` (`:119-141`) faithfully copies whatever `sources` map it's given - the omission is in the **caller** (`RegistryService.register`) that builds that map. If it omits `model.onnx.data` (ONNX external-data sidecar for large models) and/or `calibrator.json` (`SnapshotStorage.CALIBRATOR_FILE`), the snapshot is incomplete → `ModelLoader` fails to load (ORT can't find external weights) or serves uncalibrated. Fix: include `model.onnx.data` (when present) and `calibrator.json` in the register `sources` map; assert their presence post-copy.
- **Verify**: registration → serve round-trip - register a real `battedball_outcome` snapshot, hit `/v1/predict/batted-ball`, assert the response's serving model name/version is the registered one (not `_toy_...`) and the schema hash matches the snapshot manifest.

## BUG-3 (Critical) - pitch path bypasses the registry/router entirely

- **Where**: `api/PredictPitchController.java:103-104` calls `PitchInferenceService.predictPre/predictPost` directly; the bean is `@ConditionalOnBean(PitchInferenceService.class)` and `PitchInferenceService` itself is artifact-direct (`@ConditionalOnExpression` on a fixed `@Value` artifacts dir, `:44-45`/`:73-88`). No `InferenceRouter`, no registry version, no A/B, no shadow logging for the pitch heads.
- **Fix**: route the pitch path through `InferenceRouter` + `ModelLoader` (registry-resolved versions) the way `PredictBattedBallController` does - so pre/post heads get champion/shadow routing and prediction logging. Architectural; this is the biggest of the four. Keep the artifact-direct bean only as an explicit dev/no-registry fallback if desired.
- **Verify**: `InferenceRouter`-routed pitch prediction test (champion serves, shadow logged); registry version drives the served head.

## BUG-2 (Critical) - `/v1/predict/pitch` 404s when pre-head artifacts are absent on the box

- **Where**: `PitchInferenceService` is `@ConditionalOnExpression` gated on `model.onnx` existing under `bullpen.inference.pitch.artifacts-dir`; `PredictPitchController` is `@ConditionalOnBean(PitchInferenceService.class)`. Absent artifact on the box → no bean → no controller → 404.
- **This is mostly an artifact-deploy action**, not a Mac code change: ensure `pitch_outcome_pre/v1/model.onnx` (+ `.onnx.data`, `calibrator.json`) is present on the box. The **code half** is BUG-1c (the snapshot copy-list, so registration places a complete bundle). Once BUG-3 routes through the registry, the bean condition can relax.
- **Verify (box, off-window, post-BUG-9-deploy)**: `/v1/predict/pitch?head=pre` → 200 with a calibrated 5-class distribution.

---

## Also in this domain (held by the A-track for the same reason)

- **DEF-L3 (Low)** - `InferenceRouter.safeJoin` (`:132-135`) catches `RuntimeException` broadly, swallowing programming errors (NPE/CCE) as if they were challenger/shadow failures. Narrow the catch (or rethrow non-inference exceptions) so a genuine bug isn't silently masked as a degraded-routing event.

## Deploy gating (applies to all of the above)

1. No deploy during live games (rule 3).
2. BUG-1/2 runtime verification needs the BUG-9 fix deployed to the box first (the registry path 500s until then).
3. Backend deploys via `git push` + `./deploy.sh` (off-window) only - no edits on the box (ADR-0006).

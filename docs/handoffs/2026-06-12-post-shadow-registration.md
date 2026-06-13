# BOX HAND-OFF: register pitch_outcome_post (SHADOW) - 2026-06-12

The POST head was trained OFF-BOX on the Mac (the v2-clean fold export, via the new
`production.py --folds-dir` path merged in PR #56 / `6ec50e7`). It clears the pre-declared
promotion criteria decisively. This is the box-side procedure to register the assembled
snapshot SHADOW, generate the evidence row, regenerate the Python<->Java parity fixture, and
confirm shadow routing.

**Do NOT promote.** SHADOW only - promotion stays human-gated (rule 6, separate `promote-model`
step).

## What you are receiving (moved to the box out-of-band)

The assembled, gate-passing snapshot dir (Mac path `training/artifacts/snapshots/pitch_outcome_post/v1`,
40 MB, 9 files). This is the ASSEMBLED snapshot (`write_snapshot` output), NOT the raw training
artifacts - register THIS dir. Raw training metadata lacks the head discriminator the serving
loaders need and the registry will 422 (the L2 lesson).

Integrity (verify after transfer):

| file                    | sha256 (first 16)  |
| ----------------------- | ------------------ |
| model.onnx              | `75ecb628d416d866` |
| calibrator.json         | `097fe0a9b6af2de3` |
| feature_pipeline.json   | `57ff51700b03ff9a` |
| metadata.json           | `8804a90006b1ec43` |
| pitcher_te.json         | `68ac2049e46ca4ab` |
| batter_te.json          | `393e8e79c945319d` |
| park_id_mapping.json    | `ad63ee6bd6bd2acf` |
| pitch_type_mapping.json | `f7278265bbdc1834` |
| training_data.parquet   | `c04785a0cae87d7a` |

- `model.onnx` full sha256: `75ecb628d416d866ccd1ca49400ee4e52169dd7477691f42c9c39bcc8ba64a3e`
- feature schema_hash (rule 7): `fb2e6604f716f6964c821df2594693ca1dbb7d4bd0ba950457dfd80d83645c24`

## The evidence (Mac, full 4-fold rolling CV)

POST is the honest champion. Off-box CV on the full v2-clean folds (in the snapshot's
`metadata.json` under `eval_metrics_summary` / `eval_metrics_per_fold`):

- **Brier 0.1025 +/- 0.0002** (folds: 0.1028 / 0.1024 / 0.1024 / 0.1026) vs LR baseline **0.1487**
  -> beats by 0.046, ~23x the pre-declared 0.002 margin.
- **ECE 0.0025** (< the 0.02 absolute bar), **log-loss 0.964** (inside the 0.01 regression guardrail).
- ONNX-vs-booster export parity: max|diff| 3.2e-07.

Pre-declared criteria (`eval/promotion/criteria.py`, `pitch_outcome_post`, DO NOT edit - rule 5):
primary BRIER beat-baseline-by-0.002, guardrails log-loss <= 0.01 / ECE <= 0.015, absolute
ECE < 0.02, sample target 2000. Verdict: WOULD_PASS by a wide margin.

## Steps on the box

1. **Verify transfer integrity.** `shasum -a 256` the snapshot files against the table above;
   confirm `feature_pipeline.json` schema_hash == `fb2e6604...`. Stop if anything differs.

2. **(Optional, durable) Stage to R2** using the box's write creds, matching the ADR-0007
   `snapshots/` layout: `rclone copy <snapshot-dir> bullpen-r2:bullpen-prod/snapshots/pitch_outcome_post/v1/`.
   The Mac token is read-only, which is why this is a box step.

3. **Generate the experiment_results evidence row** (rule 5; register-model step 4 needs the id):
   `cd training && uv run python -m bullpen_training.eval.promotion.driver --model pitch_outcome_post`
   This runs the paired baseline-vs-challenger verdict against the pre-declared criteria and
   writes the experiment_results-shaped artifact. The SAMPLE-stage row clears the SHADOW gate
   per the locked decision; the full-data verdict is the later LIVE-promotion step (criteria.py
   H2 note). The Mac full-fold CV above is the off-box confirmation.

4. **Register SHADOW** via the `register-model` skill:
   - artifact = the ASSEMBLED snapshot dir (step 1), NOT raw artifacts
   - role = `post_pitch_outcome`, state = `SHADOW`
   - schema_hash = `fb2e6604...` (HARD FAIL if mismatch, rule 7)
   - baseline = `pitch_outcome_lr_baseline` (already registered 2026-06-09 - rule 9 satisfied)
   - experiment_results id = from step 3
     The Mac register-gate dry-run already PASSES every rule-7/9 check (metadata+contract present,
     head identity pre != post, three schema-hash checks, calibrator loads + labels match, ONNX
     [N,41]->[N,5], baseline partner present).

5. **Regenerate the Python<->Java parity fixture** (it goes stale on every retrain; this is a
   box step because it needs ClickHouse for fresh 2025 input rows AND validates the Java side):
   `cd training && uv run python -m bullpen_training.pitch.parity_fixture_post`
   then run backend `PitchPostParityTest.java` - it must pass (|prob - expected| < 1e-6 at every
   stage). This re-pins the contract that Java serves the new model identically. Commit the
   regenerated `training/tests/fixtures/parity_pitch_post_001*.json` (from the Mac, per ADR-0006
   - or note them as box-validated and regenerate on the Mac if you prefer the no-edit-on-box path).

6. **Smoke + confirm shadow.** Restart/verify the `api` profile loads the ONNX session at startup;
   confirm the new model is in the A/B router shadow set (predictions logged to `prediction_log`,
   not user-visible). This also un-404s the pitch endpoint chain (it was conditional-bean gated on
   `pitch_outcome_post`/pre artifacts).

## Discipline

- **SHADOW only.** Do NOT promote here (rule 6). Promotion is the separate `promote-model` gate.
- **Rule 7**: refuse registration if schema_hash != `fb2e6604...`.
- **Rule 9**: pre and post are separate registry rows; the LR baseline must be co-registered (it is).
- **ADR-0006**: registration is an operational action against the registry, not a code edit - fine
  on the box. The `--folds-dir` code change is already merged (`6ec50e7`) and arrives via `git pull`;
  do not edit code on the box.

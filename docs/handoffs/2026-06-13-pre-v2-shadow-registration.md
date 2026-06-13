# BOX HAND-OFF: register pitch_outcome_pre v2 (SHADOW) - 2026-06-13

PR-D. The PRE head was retrained OFF-BOX on the Mac (the v2-clean fold export, via
`production.py --model lightgbm --version v2 --folds-dir`, the same path POST used) so the
pre-pitch head is also on the post-DP1 clean features. This is the box-side procedure to register
the assembled snapshot SHADOW.

**Do NOT promote.** SHADOW only - promotion stays human-gated (rule 6). And read the margin note
below before considering promotion at all.

## What you are receiving (moved to this box out-of-band)

The assembled, gate-passing snapshot dir (Mac path `training/artifacts/snapshots/pitch_outcome_pre/v2`),
shipped as `pitch_outcome_pre_v2_snapshot.tar.gz` (22 MB, tarred with `COPYFILE_DISABLE=1` so it
carries no macOS `._` AppleDouble files). Register the ASSEMBLED snapshot, never the raw training
artifacts (the 422 head-discriminator lesson).

Integrity:

- tarball sha256: `dabb295b6dc05dddbdbf828f915a00c8602668c87eab2211c2c99167f5a03c6b`
- model.onnx sha256: `67023e647219aed16c81d913632298eb0a0a859f53a499cc8187218be9821372`
- feature schema_hash (rule 7): `bb033950d12bd9ad35a6bea5347f77978ed5dfae3e8a9aad1336548b14e00cf1`

This is a NEW version row (pitch_outcome_pre v1 stays registered); a retrain is never an overwrite.

## Evidence (Mac, full 4-fold rolling CV)

Reproduces the box's clean-feature PRE run to the digit:

- **Brier 0.14782 +/- 0.00017** (folds 0.14787 / 0.14778 / 0.14801 / 0.14761)
- **ECE 0.00356 +/- 0.00253** (< the 0.02 absolute bar)
- log-loss 1.4566 +/- 0.0020; ONNX-vs-booster export parity max|diff| 3.4e-07.

### Margin note (read before promotion)

The pre-declared `pitch_outcome_pre` criterion is "beat the co-registered LR baseline's Brier by

> = 0.002". PRE's Brier 0.14782 sits only ~0.0009 below the LR baseline's ~0.1487 - **inside** the
> 0.002 margin. That is expected, not a regression: the pre-pitch head sees no early-flight signal,
> so its lift over a logistic baseline on the same Tier 1+2+3 features is inherently small. The
> decisive margin lives in the POST head (Tier 4), which clears the bar ~23x. So: register PRE v2
> SHADOW for the clean-feature artifact + calibration coverage, but the LIVE-promotion verdict
> against the LR baseline is the box's W5 `experiment_results` evidence step, and PRE may well NOT
> clear it - that is an honest outcome, not a bug. Run the driver on full box data before any
> promotion call.

## Steps on the box

1. Verify the tarball + extracted hashes against the table above; confirm schema_hash == `bb033950...`.
2. (Optional, durable) stage the snapshot to R2 with the box write creds (Mac token is read-only).
3. Generate the experiment_results row: `cd training && uv run python -m
bullpen_training.eval.promotion.driver --model pitch_outcome_pre` (see the margin note - expect
   it may not clear the 0.002 bar).
4. Register SHADOW via the register-model skill: assembled snapshot, role `pre_pitch_outcome`,
   state SHADOW, schema_hash `bb033950...`, baseline `pitch_outcome_lr_baseline` (registered;
   rule 9 satisfied). The Mac register-gate dry-run already PASSES every rule-7/9 check.
5. Regenerate the Python<->Java parity fixture for the new model (a box step - needs ClickHouse for
   fresh inputs + validates the Java side): `uv run python -m
bullpen_training.pitch.parity_fixture` (the PRE fixture; `parity_pitch_pre_001*.json`), then run
   the backend `PitchPreParityTest` -> must pass (< 1e-6). The existing committed fixture is for
   PRE v1 and will not match v2.
6. Smoke + confirm SHADOW routing (api loads the v2 ONNX; logged to prediction_log, not
   user-visible).

## Discipline

- SHADOW only (rule 6). Rule 7: refuse if schema_hash != `bb033950...`. Rule 9: pre/post are
  separate rows; LR baseline co-registered (it is). ADR-0006: registration is operational; the
  `--folds-dir` + ECE/replay code changes are already merged and arrive via `git pull`.

"""SERVABLE-FAMILY retrain adapter for ``battedball_outcome`` (M2 task A3).

Closes M2 ruling C2: a real drift trigger enqueued with the registry model name
``battedball_outcome`` now retrains the SERVED architecture - the single-graph
``battedball/mlp`` :class:`BattedBallMLP` (shared backbone + 30 per-park outcome heads +
the Phase-4 carry head) - not the per-park experiment family (which stays reachable only
under ``_experiment_battedball_mlp_per_park``).

Served-format fidelity (the whole point of this adapter): the produced artifact directory
carries exactly the file set the serving loader consumes. Verified against
``LoadedAllParksModel.load`` (backend/src/main/java/net/thebullpen/baseball/inference/
LoadedAllParksModel.java, lines 62-77), which resolves from the snapshot dir:

* ``model.onnx``      - ``SnapshotStorage.ARTIFACT_FILE`` (SnapshotStorage.java:65); the
  single-file two-output serving graph (``probabilities`` (N,30,5) per-park softmax +
  ``carry`` (N,30) standardised), produced by the SAME :func:`export_onnx` the champion
  ceremony used ([168] format; inlines the dynamo sidecar so one file is self-contained).
* ``metadata.json``   - ``SnapshotStorage.METADATA_FILE`` (SnapshotStorage.java:67);
  must carry ``feature_scaler`` (FeaturePipelineBattedBall.java:132 fails loud without
  it), ``park_order``, and ``carry_target`` - all written by the production
  :func:`write_metadata`.
* ``calibrator.json`` - ``SnapshotStorage.CALIBRATOR_FILE`` (SnapshotStorage.java:69);
  the schema_version-2 park-keyed map ``BattedBallCalibrators.load`` reads, produced by
  the production :func:`save_calibrator`.
* ``feature_pipeline.json`` is NOT staged here - registration copies it from this
  output's ``feature_pipeline_path`` (RegistryService.doInsert copy-list,
  RegistryService.java:229-242, which also picks up ``calibrator.json`` beside the
  artifact FILE via ``artifactSource.getParent()`` - hence ``artifact_path`` below is
  the ``model.onnx`` FILE, single-file family, unlike the per-park DIRECTORY adapter).

Calibrator approach (mirrors the production 2c.6 pipeline exactly): the champion ceremony
trains via ``mlp/train.py main()`` on the earlier seasons and then fits the 30x5 per-park
isotonics with ``scripts/fit_calibrators.py`` on a HELD-OUT val season (decision [51]
norm: fit on val, NOT train; the desktop overnight pipeline passes 2025). This adapter
reproduces that split in one call: the train slice is ``[season_from, val_season - 1]``
and ``val_season`` (default: the last season of the window, 2025) is used both for
val-loss monitoring and for the calibrator fit via the same
``predict_park_probs -> fit_per_park_calibrators`` path ``fit_calibrators.py`` runs. A
``calibration_metrics.json`` sidecar is emitted for parity with that script (the
decision-[141] gate evidence shape); the registry row's ``eval_metrics_json`` carries the
same summary as training DIAGNOSTICS, not promotion evidence (promotion stays human-gated,
rule 6).

Rule 13 / [170] fence: explicit 2015-2025 defaults; the ``mlp`` family's trainer-level
fence lives in ``mlp/train.py main()`` (not in :func:`train_model`), so this adapter
calls the same :func:`refuse_holdout` guard before any data loads - a
``trigger_metadata`` override cannot smuggle the 2026 holdout in on any of
``season_from`` / ``season_to`` / ``val_season``.

Carry: the loader's ``carry`` arrays pass straight into :class:`BBIPDataset`, so the
carry head trains on whatever ``carry_ft`` backfill exists (the v2 champion path); rows
still NULL stay NaN and are masked out of the loss, exactly as production training does.
"""

from __future__ import annotations

import hashlib
import json
import math
import os
from datetime import UTC, datetime
from pathlib import Path

import torch

from bullpen_training.battedball.mlp.architecture import predict_park_probs
from bullpen_training.battedball.mlp.calibration import (
    fit_per_park_calibrators,
    per_park_ece,
    save_calibrator,
    transform,
)
from bullpen_training.battedball.mlp.dataset import (
    OUTCOME_NAMES,
    BBIPDataset,
    FeatureScaler,
    load_arrays,
)
from bullpen_training.battedball.mlp.train import (
    DEFAULT_EPOCHS,
    export_onnx,
    train_model,
    write_metadata,
)
from bullpen_training.battedball.parks.loader import load_all_parks
from bullpen_training.eval.leakage_guards import refuse_holdout
from bullpen_training.retraining._dispatch import RetrainOutput

# Rule 13: 2026 is holdout-only. refuse_holdout re-fences below, so even a
# trigger_metadata override cannot smuggle the holdout season in.
DEFAULT_SEASON_FROM = 2015
DEFAULT_SEASON_TO = 2025


def _finite(value: float) -> float | None:
    """NaN/inf -> None so eval_metrics_json stays strict JSON (Java-parseable)."""
    return float(value) if math.isfinite(value) else None


def retrain_battedball_outcome(
    trigger_id: str, version: str, trigger_metadata: dict
) -> RetrainOutput:
    meta = trigger_metadata or {}
    season_from = int(meta.get("season_from", DEFAULT_SEASON_FROM))
    season_to = int(meta.get("season_to", DEFAULT_SEASON_TO))
    # Default val = the LAST season of the window (2025 on defaults): the train slice ends
    # at val_season - 1 and the calibrators fit on val_season, mirroring the production
    # train-then-fit_calibrators.py split (decision [51]: calibrators fit on val, not train).
    val_season = int(meta.get("val_season", season_to))
    # Fence FIRST (before the ordering check, before any data loads) so a 2026 override
    # fails as a rule-13 LeakageError on any of the three params.
    refuse_holdout(season_from=season_from, season_to=season_to, val_season=val_season)
    if not season_from < val_season <= season_to:
        raise ValueError(
            f"battedball_outcome retrain needs season_from < val_season <= season_to "
            f"(train slice [{season_from},{val_season - 1}], calibration on {val_season}); "
            f"got season_from={season_from}, season_to={season_to}, val_season={val_season}"
        )

    limit = int(meta["limit"]) if meta.get("limit") is not None else None
    n_epochs = int(meta.get("n_epochs", DEFAULT_EPOCHS))
    seed = int(meta.get("seed", 42))
    device = str(meta.get("device", "auto"))

    # The served family is one graph over ALL parks - park_order comes from the geometry
    # set, sorted, exactly as mlp/train.py main() builds it. No park subsetting.
    park_order = tuple(sorted(load_all_parks().keys()))

    train_feat, train_lab, train_carry = load_arrays(
        season_from=season_from,
        season_to=val_season - 1,
        park_order=park_order,
        limit=limit,
    )
    if train_feat.shape[0] == 0:
        raise RuntimeError(
            f"battedball_outcome retrain for trigger {trigger_id!r} loaded zero training "
            f"BIPs (seasons [{season_from},{val_season - 1}]) - refusing to register"
        )
    val_feat, val_lab, val_carry = load_arrays(
        season_from=val_season,
        season_to=val_season,
        park_order=park_order,
        limit=limit,
    )
    if val_feat.shape[0] < 2:
        raise RuntimeError(
            f"battedball_outcome retrain for trigger {trigger_id!r} loaded "
            f"{val_feat.shape[0]} val BIPs for season {val_season} - the per-park isotonic "
            "fit needs at least 2; refusing to register an uncalibrated candidate"
        )

    # Scaler fits on the TRAIN slice only and is reused for val - the production norm
    # (mlp/train.py main()); its params land in metadata.json:feature_scaler for Java.
    scaler = FeatureScaler.fit(train_feat)
    train_ds = BBIPDataset(train_feat, train_lab, carry=train_carry, scaler=scaler)
    val_ds = BBIPDataset(val_feat, val_lab, carry=val_carry, scaler=scaler)

    model, summary = train_model(
        train_ds,
        val_ds,
        n_epochs=n_epochs,
        seed=seed,
        device=device,
        n_parks=len(park_order),
    )

    out_base = Path(os.environ.get("BULLPEN_RETRAIN_ARTIFACT_DIR", "artifacts/retrain"))
    out_dir = out_base / "battedball_outcome" / version
    out_dir.mkdir(parents=True, exist_ok=True)

    # Same persistence order as mlp/train.py main(): checkpoint, single-file serving ONNX
    # ([168] ceremony format), then the metadata sidecar with feature_scaler + park_order
    # + carry_target (the three blocks FeaturePipelineBattedBall reads).
    torch.save(model.state_dict(), out_dir / "model.pt")
    onnx_path = out_dir / "model.onnx"
    export_onnx(model, onnx_path)
    metadata_path = out_dir / "metadata.json"
    write_metadata(
        metadata_path,
        park_order=list(park_order),
        train_summary=summary,
        scaler=scaler,
    )

    # Per-park isotonic calibrators on the held-out val slice - the same forward + fit
    # path scripts/fit_calibrators.py runs (predict_park_probs unpacks the carry-aware
    # 2-output forward; export_onnx left the model on cpu in eval mode).
    val_scaled = scaler.transform(val_feat)
    raw_probs = predict_park_probs(model, val_scaled)
    calibrators = fit_per_park_calibrators(
        raw_probs,
        val_lab,
        park_order=park_order,
        outcome_order=tuple(OUTCOME_NAMES),
    )
    save_calibrator(calibrators, out_dir / "calibrator.json")
    calibrated = transform(calibrators, raw_probs)
    ece_pre = per_park_ece(raw_probs, val_lab)
    ece_post = per_park_ece(calibrated, val_lab)
    calibration_summary = {
        "val_season": val_season,
        "n_val_rows": int(val_feat.shape[0]),
        "per_park_ece_pre_mean": _finite(float(ece_pre.mean())),
        "per_park_ece_post_mean": _finite(float(ece_post.mean())),
        "per_park_ece_post_max": _finite(float(ece_post.max())),
        "parks_improved": int((ece_post < ece_pre).sum()),
        "n_parks": len(park_order),
    }
    # Sidecar for parity with the production fit_calibrators.py output (the [141] gate
    # evidence shape). Not part of the serving copy-list; provenance only.
    (out_dir / "calibration_metrics.json").write_text(
        json.dumps({"schema_version": 1, **calibration_summary}, indent=2) + "\n"
    )

    trained_at = datetime.now(UTC).isoformat()

    # The _dispatch contract: trigger_id lands in the produced metadata.json so the model
    # row correlates back to its retrain trigger in one lookup. Also stamp the real
    # version + season split over write_metadata's static defaults.
    top_meta = json.loads(metadata_path.read_text())
    top_meta["trigger_id"] = trigger_id
    top_meta["model_version"] = version
    top_meta["training_seasons"] = [season_from, season_to]
    top_meta["train_split_seasons"] = [season_from, val_season - 1]
    top_meta["calibration_val_season"] = val_season
    top_meta["trained_at"] = trained_at
    metadata_path.write_text(json.dumps(top_meta, indent=2))

    # Deterministic provenance token over what was trained on. The full Parquet-snapshot
    # hash (decision [68]) belongs to the box-side registration ceremony; this hash pins
    # the (seasons, split, parks, row counts) identity of THIS run.
    provenance = {
        "seasons": [season_from, season_to],
        "train_split": [season_from, val_season - 1],
        "val_season": val_season,
        "park_order": list(park_order),
        "n_train": summary.n_train,
        "n_val": summary.n_val,
    }
    training_data_hash = hashlib.sha256(
        json.dumps(provenance, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()

    # Training diagnostics, NOT promotion evidence - promotion stays human-gated (rule 6)
    # behind its own declared gate ([166]/ADR-0012 for this family).
    eval_metrics = {
        "kind": "training_diagnostics",
        "n_train": summary.n_train,
        "n_val": summary.n_val,
        "n_epochs": summary.n_epochs,
        "final_train_loss": _finite(summary.final_train_loss),
        "final_val_loss": _finite(summary.final_val_loss),
        "final_carry_loss": _finite(summary.final_carry_loss),
        "calibration": calibration_summary,
    }

    feature_pipeline_path = os.environ.get(
        "BULLPEN_FEATURE_PIPELINE_PATH", "../contracts/feature_pipeline_battedball.json"
    )

    return RetrainOutput(
        # Single-file family: the registry's copy-list treats artifact_path as the
        # model.onnx FILE and stages calibrator.json from its parent dir.
        artifact_path=str(onnx_path),
        metadata_path=str(metadata_path),
        feature_pipeline_path=feature_pipeline_path,
        eval_metrics_json=json.dumps(eval_metrics, sort_keys=True),
        training_data_hash=training_data_hash,
        training_data_window=f"[{season_from},{season_to}]",
        trained_at_iso=trained_at,
    )

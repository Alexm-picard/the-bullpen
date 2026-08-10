"""EXPERIMENT retrain adapter for the batted-ball per-park family (M1 task 3).

Wired to the ``mlp_per_park`` trainer: ``train_all_parks`` runs the real loop (scaler fit
-> train -> ``export_per_park_onnx`` with ``onnx.checker`` -> per-park + top-level
metadata), and this adapter packs the result into the :class:`RetrainOutput` shape the
register endpoint consumes. The [170] holdout fence is live inside ``train_all_parks``;
this adapter passes explicit 2015-2025 seasons by default and any override in
``trigger_metadata`` still hits the fence.

STATUS after the M2 rulings (both original caveats adjudicated):

* C1: dispatch keys now equal registry model names exactly; this adapter is deliberately
  keyed as ``_experiment_battedball_mlp_per_park`` (non-registry by construction), so it is
  reachable only by a hand-built trigger - never by a real queue row.
* C2: the SERVED ``battedball_outcome`` family (single-graph ``battedball/mlp`` with the
  carry head + per-park calibrators) retrains via ``retraining/battedball_outcome.py``
  (M2-A3), not this module. This module's preserved value is the proven
  claim -> dispatch -> REAL training -> ONNX export -> register -> complete seam.

Artifact layout note: the per-park family's artifact is a DIRECTORY (one ``model.onnx``
per park + a top-level ``metadata.json``), so ``artifact_path`` is the run directory.
"""

from __future__ import annotations

import hashlib
import json
import os
from datetime import UTC, datetime
from pathlib import Path

from bullpen_training.battedball.mlp_per_park.train import DEFAULT_EPOCHS, train_all_parks
from bullpen_training.battedball.parks.loader import load_all_parks
from bullpen_training.retraining._dispatch import RetrainOutput

# Rule 13: 2026 is holdout-only. train_all_parks re-fences via refuse_holdout, so even a
# trigger_metadata override cannot smuggle the holdout season in.
DEFAULT_SEASON_FROM = 2015
DEFAULT_SEASON_TO = 2025


def retrain_batted_ball(trigger_id: str, version: str, trigger_metadata: dict) -> RetrainOutput:
    meta = trigger_metadata or {}
    season_from = int(meta.get("season_from", DEFAULT_SEASON_FROM))
    season_to = int(meta.get("season_to", DEFAULT_SEASON_TO))
    val_season = meta.get("val_season")
    park_ids = tuple(sorted(meta.get("park_ids") or load_all_parks().keys()))

    out_base = Path(os.environ.get("BULLPEN_RETRAIN_ARTIFACT_DIR", "artifacts/retrain"))
    out_dir = out_base / "batted_ball" / version

    summaries = train_all_parks(
        park_ids=park_ids,
        season_from=season_from,
        season_to=season_to,
        val_season=int(val_season) if val_season is not None else None,
        limit=int(meta["limit"]) if meta.get("limit") is not None else None,
        n_epochs=int(meta.get("n_epochs", DEFAULT_EPOCHS)),
        seed=int(meta.get("seed", 42)),
        device=str(meta.get("device", "auto")),
        out_dir=out_dir,
    )
    if not summaries:
        raise RuntimeError(
            f"batted_ball retrain for trigger {trigger_id!r} trained zero parks "
            f"(no data in seasons [{season_from},{season_to}]) - refusing to register"
        )

    trained_at = datetime.now(UTC).isoformat()

    # The _dispatch contract: trigger_id lands in the produced metadata.json so the model
    # row correlates back to its retrain trigger in one lookup.
    metadata_path = out_dir / "metadata.json"
    top_meta = json.loads(metadata_path.read_text())
    top_meta["trigger_id"] = trigger_id
    top_meta["model_version"] = version
    top_meta["training_seasons"] = [season_from, season_to]
    top_meta["trained_at"] = trained_at
    metadata_path.write_text(json.dumps(top_meta, indent=2))

    # Deterministic provenance token over what was trained on. The full Parquet-snapshot
    # hash (decision [68]) belongs to the box-side registration ceremony; this hash pins
    # the (seasons, parks, per-park row counts) identity of THIS run.
    provenance = {
        "seasons": [season_from, season_to],
        "park_ids": list(park_ids),
        "n_train_per_park": {s.park_id: s.n_train for s in summaries},
    }
    training_data_hash = hashlib.sha256(
        json.dumps(provenance, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()

    # Training diagnostics, NOT promotion evidence - promotion stays human-gated (rule 6)
    # behind its own declared gate.
    eval_metrics = {
        "kind": "training_diagnostics",
        "n_parks_trained": len(summaries),
        "mean_final_train_loss": sum(s.final_train_loss for s in summaries) / len(summaries),
        "per_park_final_train_loss": {s.park_id: s.final_train_loss for s in summaries},
    }

    feature_pipeline_path = os.environ.get(
        "BULLPEN_FEATURE_PIPELINE_PATH", "../contracts/feature_pipeline_battedball.json"
    )

    return RetrainOutput(
        artifact_path=str(out_dir),
        metadata_path=str(metadata_path),
        feature_pipeline_path=feature_pipeline_path,
        eval_metrics_json=json.dumps(eval_metrics, sort_keys=True),
        training_data_hash=training_data_hash,
        training_data_window=f"[{season_from},{season_to}]",
        trained_at_iso=trained_at,
    )

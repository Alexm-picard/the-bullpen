"""Convert the toy LightGBM booster (1.3) to ONNX for Java consumption (1.4).

Boundary discipline (Risk Register G4):
    Numeric features pass through ONNX unchanged. The categorical lookups
    (target-encoded park_id, boolean stand_is_left) are **not** baked into
    the ONNX graph — they live in `/contracts/feature_pipeline.json` so the
    Java `FeaturePipeline` can apply them identically. This keeps the ONNX
    surface trivial and the Python↔Java parity test exercises the lookup
    path explicitly.

Source of truth: `/contracts/feature_pipeline.json` (CLAUDE.md rule 7).
This export READS the canonical pipeline + validates the model matches it.
Drift between this script's view of the schema and `/contracts` is a
HARD FAIL — registration of the resulting model would refuse anyway.

Outputs alongside `model.lgb`:
    model.onnx           — ONNX-format booster
    park_hr_rate.json    — target-encoding lookup table (data, not spec)

Determinism: re-running on the same model.lgb + same training frame is
byte-stable for the ONNX bytes and the lookup JSON.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
from pathlib import Path
from typing import Any, cast

import click
import lightgbm as lgb
import onnx
import onnxmltools
import pandas as pd
from onnxmltools.convert.common.data_types import FloatTensorType  # type: ignore[import-untyped]

from bullpen_training.battedball.features_toy import FEATURES, TARGET
from bullpen_training.battedball.train_toy import DEFAULT_OUTPUT_DIR
from bullpen_training.ingest.clickhouse_client import ClickHouseSettings, make_client
from bullpen_training.logging_config import configure_logging, get_logger
from bullpen_training.registry_client import feature_hasher

log = get_logger(__name__)

REPO_ROOT = Path(__file__).resolve().parents[4]
CONTRACT_PATH = REPO_ROOT / "contracts" / "feature_pipeline_toy.json"


def load_canonical_pipeline() -> dict[str, Any]:
    """Read /contracts/feature_pipeline_toy.json — the phase-1 toy contract.

    Phase 2a.8 moved the production contract to ``/contracts/feature_pipeline.json``
    (now ``pitch_outcome_pre``). The toy keeps its own file so the toy
    deploy keeps working without churning the canonical contract.

    Verifies the declared schema_hash matches the hook's recompute algorithm
    (so any drift in the JSON itself is caught at export time, not at registry
    registration time).
    """
    spec = cast(dict[str, Any], json.loads(CONTRACT_PATH.read_text()))
    declared = spec["schema_hash"]
    recomputed = feature_hasher.compute(CONTRACT_PATH)
    if declared != recomputed:
        raise RuntimeError(
            f"/contracts/feature_pipeline_toy.json schema_hash is stale "
            f"(declared={declared} computed={recomputed}); "
            "re-run the recompute snippet in .githooks/pre-commit and re-stage."
        )
    return spec


def _validate_model_matches_contract(spec: dict[str, Any]) -> None:
    """Hard-fail if this code's view of the toy pipeline diverges from /contracts."""
    if spec["model_name"] != "_toy_batted_ball":
        raise RuntimeError(
            f"contract model_name is {spec['model_name']!r}; "
            "export_toy_onnx only knows how to handle '_toy_batted_ball'"
        )
    if tuple(spec["feature_order"]) != FEATURES:
        raise RuntimeError(
            f"contract feature_order {spec['feature_order']} != "
            f"code FEATURES {list(FEATURES)} — code + contract drifted"
        )


def _compute_park_hr_rate(client: Any, year: int) -> dict[str, float]:
    """Recompute the same target-encoding the training frame used."""
    raw = pd.DataFrame(
        client.execute(
            f"""
            SELECT park_id, toUInt8(events = 'home_run') AS is_hr
            FROM pitches FINAL
            WHERE description = 'in_play'
              AND toYear(game_date) = {year}
              AND launch_speed_mph IS NOT NULL
              AND launch_angle_deg IS NOT NULL
              AND release_speed_mph IS NOT NULL
            ORDER BY game_date, game_id, at_bat_index, pitch_number
            """
        ),
        columns=["park_id", TARGET],
    )
    rate = cast(pd.Series, raw.groupby("park_id")[TARGET].mean())
    return {str(k): float(v) for k, v in sorted(rate.items())}


def export(
    *,
    output_dir: Path | None = None,
    year: int = 2024,
    settings: ClickHouseSettings | None = None,
    park_hr_rate: dict[str, float] | None = None,
) -> dict[str, Any]:
    spec = load_canonical_pipeline()
    _validate_model_matches_contract(spec)
    opset = int(spec["onnx_opset"])

    outdir = output_dir or DEFAULT_OUTPUT_DIR
    model_path = outdir / "model.lgb"
    if not model_path.exists():
        raise FileNotFoundError(f"toy model not found at {model_path}; run 1.3 train_toy first")

    booster = lgb.Booster(model_file=str(model_path))
    initial_types = [("input", FloatTensorType([None, len(FEATURES)]))]
    log.info("converting LightGBM → ONNX", n_features=len(FEATURES), opset=opset)
    onnx_model = onnxmltools.convert.convert_lightgbm(
        booster,
        initial_types=initial_types,
        target_opset=opset,
        zipmap=False,
    )
    onnx.checker.check_model(onnx_model)
    onnx_path = outdir / "model.onnx"
    onnxmltools.utils.save_model(onnx_model, str(onnx_path))

    if park_hr_rate is None:
        log.info("computing park HR-rate lookup", year=year)
        park_hr_rate = _compute_park_hr_rate(make_client(settings), year)
    park_lookup_path = outdir / "park_hr_rate.json"
    park_lookup_path.write_text(json.dumps(park_hr_rate, indent=2) + "\n")

    onnx_sha = hashlib.sha256(onnx_path.read_bytes()).hexdigest()
    log.info(
        "export complete",
        onnx_path=str(onnx_path),
        onnx_sha256=onnx_sha,
        schema_hash=spec["schema_hash"],
        park_lookup_entries=len(park_hr_rate),
    )
    return {
        "onnx_path": str(onnx_path),
        "onnx_sha256": onnx_sha,
        "schema_hash": spec["schema_hash"],
        "park_lookup_path": str(park_lookup_path),
        "park_lookup_entries": len(park_hr_rate),
    }


@click.command()
@click.option("--year", type=int, default=2024, show_default=True)
@click.option(
    "--out-dir",
    type=click.Path(file_okay=False, dir_okay=True, path_type=Path),
    default=None,
)
@click.option(
    "--log-format",
    type=click.Choice(["console", "json"], case_sensitive=False),
    default="console",
)
def main(year: int, out_dir: Path | None, log_format: str) -> None:
    if log_format.lower() == "json":
        os.environ["LOG_FORMAT"] = "json"
    configure_logging(level=logging.INFO)
    export(year=year, output_dir=out_dir)


if __name__ == "__main__":
    main()

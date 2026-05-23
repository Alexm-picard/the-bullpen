"""Convert the toy LightGBM booster (1.3) to ONNX for Java consumption (1.4).

Boundary discipline (Risk Register G4):
    Numeric features pass through ONNX unchanged. The categorical lookups
    (target-encoded park_id, boolean stand_is_left) are **not** baked into
    the ONNX graph — they live in `feature_pipeline.json` so the Java
    `FeaturePipeline` can apply them identically. This keeps the ONNX
    surface trivial and the Python↔Java parity test exercises the lookup
    path explicitly.

Outputs alongside `model.lgb`:
    model.onnx              — ONNX-format booster
    feature_pipeline.json   — column order + preprocess spec + schema hash
    park_hr_rate.json       — target-encoding lookup table (keyed by park_id)

Determinism: re-running on the same model.lgb + same training frame is
byte-stable for both the ONNX bytes and the lookup JSON.
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

log = get_logger(__name__)

ONNX_OPSET = 15  # cap of onnxmltools' LightGBM converter as of v1.16


def _compute_park_hr_rate(client: Any, year: int) -> dict[str, float]:
    """Recompute the same target-encoding the training frame used.

    Recomputed (not extracted from model) because the encoding is a property
    of the data, not the model — and the Java side needs the dict, not the
    booster's internal split state.
    """
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
    # JSON-friendly ordering: sort by park_id for deterministic file bytes.
    return {str(k): float(v) for k, v in sorted(rate.items())}


def _sha256_of_canonical_json(obj: dict[str, Any]) -> str:
    canon = json.dumps(obj, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canon.encode("utf-8")).hexdigest()


def _build_feature_pipeline_doc(park_hr_rate_path: str) -> dict[str, Any]:
    """Build the feature_pipeline.json contract — the Java side reads this
    to know column order + preprocessing rules."""
    spec: dict[str, Any] = {
        "model_name": "_toy_batted_ball",
        "version": "v0",
        "phase": "1.4",
        "feature_order": list(FEATURES),
        "input_dtype": "float32",
        "preprocess": {
            "launch_speed_mph": {"type": "passthrough"},
            "launch_angle_deg": {"type": "passthrough"},
            "release_speed_mph": {"type": "passthrough"},
            "park_id_encoded": {
                "type": "target_encoding",
                "source_column": "park_id",
                "lookup_path": park_hr_rate_path,
                "missing_strategy": "global_mean",
            },
            "stand_is_left": {
                "type": "boolean_eq",
                "source_column": "stand",
                "match_value": "L",
                "true_value": 1.0,
                "false_value": 0.0,
            },
        },
        "output": {
            "kind": "binary_probability",
            "label": "is_hr",
            "onnx_output_index": 0,
            "extract": "class_one_probability",
        },
        "onnx_opset": ONNX_OPSET,
    }
    # schema_hash zeroed during canonicalization so the field is self-stable.
    spec["schema_hash"] = _sha256_of_canonical_json({**spec, "schema_hash": "0" * 64})
    return spec


def export(
    *,
    output_dir: Path | None = None,
    year: int = 2024,
    settings: ClickHouseSettings | None = None,
) -> dict[str, Any]:
    outdir = output_dir or DEFAULT_OUTPUT_DIR
    model_path = outdir / "model.lgb"
    if not model_path.exists():
        raise FileNotFoundError(f"toy model not found at {model_path}; run 1.3 train_toy first")

    booster = lgb.Booster(model_file=str(model_path))
    initial_types = [("input", FloatTensorType([None, len(FEATURES)]))]
    log.info("converting LightGBM → ONNX", n_features=len(FEATURES), opset=ONNX_OPSET)
    onnx_model = onnxmltools.convert.convert_lightgbm(
        booster,
        initial_types=initial_types,
        target_opset=ONNX_OPSET,
        zipmap=False,
    )
    onnx.checker.check_model(onnx_model)
    onnx_path = outdir / "model.onnx"
    onnxmltools.utils.save_model(onnx_model, str(onnx_path))

    log.info("computing park HR-rate lookup", year=year)
    park_lookup = _compute_park_hr_rate(make_client(settings), year)
    park_lookup_path = outdir / "park_hr_rate.json"
    park_lookup_path.write_text(json.dumps(park_lookup, indent=2) + "\n")

    pipeline_spec = _build_feature_pipeline_doc(park_lookup_path.name)
    pipeline_path = outdir / "feature_pipeline.json"
    pipeline_path.write_text(json.dumps(pipeline_spec, indent=2) + "\n")

    onnx_sha = hashlib.sha256(onnx_path.read_bytes()).hexdigest()
    log.info(
        "export complete",
        onnx_path=str(onnx_path),
        onnx_sha256=onnx_sha,
        pipeline_path=str(pipeline_path),
        park_lookup_entries=len(park_lookup),
    )
    return {
        "onnx_path": str(onnx_path),
        "onnx_sha256": onnx_sha,
        "feature_pipeline_path": str(pipeline_path),
        "park_lookup_path": str(park_lookup_path),
        "park_lookup_entries": len(park_lookup),
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

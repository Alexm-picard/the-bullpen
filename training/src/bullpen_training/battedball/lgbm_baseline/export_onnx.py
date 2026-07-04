"""Convert the LightGBM Option-A batted-ball baseline (2c.8) to ONNX for Java
serving / registration (decision [46]; co-registered comparator for the MLP per
decision [141]).

Mirrors ``battedball.export_toy_onnx`` (same boundary discipline: numeric features
pass through ONNX; encodings + the per-class isotonic calibrators stay in Java /
the contract, not baked into the graph). The only structural difference from the
toy is the input width: this baseline takes the **16** FEATURE_COLUMNS - the MLP's
15 features plus ``park_id`` as its integer category code (the LightGBM
training-time ``pd.Categorical`` order, ``metadata.park_categories``). The booster
was trained with ``categorical_feature=[park_id]``, so the ONNX must preserve the
categorical split nodes; a Python<->Java parity check on the desktop confirms the
conversion (onnxmltools categorical handling is the one thing to verify here).

Reads the canonical contract ``/contracts/feature_pipeline_lgbm_battedball.json``
and HARD-FAILS if its declared schema_hash is stale or its feature_order has
drifted from ``FEATURE_COLUMNS`` - the same drift the registry would refuse at
registration (rule 7), surfaced here at export time instead.

Outputs alongside ``model.txt`` in the artifact dir:
    model.onnx   - ONNX-format booster (16 float inputs -> 5-class softmax)

Run on the desktop (the model.txt lives there, ADR-0006):
    uv run python -m bullpen_training.battedball.lgbm_baseline.export_onnx \\
        --artifact-dir artifacts/batted_ball_lgbm_baseline/v1
"""

from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
from typing import Any, cast

import click
import onnx
import onnxmltools
from onnxmltools.convert.common.data_types import FloatTensorType  # type: ignore[import-untyped]

from bullpen_training.battedball.lgbm_baseline import load_baseline
from bullpen_training.battedball.lgbm_baseline.dataset import FEATURE_COLUMNS

REPO_ROOT = Path(__file__).resolve().parents[5]
CONTRACT_PATH = REPO_ROOT / "contracts" / "feature_pipeline_lgbm_battedball.json"


def _load_and_verify_contract(contract_path: Path) -> dict[str, Any]:
    """Read the LGBM contract, verify its schema_hash + that feature_order matches
    FEATURE_COLUMNS. Drift is a hard fail (the registry would refuse it anyway)."""
    spec = cast(dict[str, Any], json.loads(contract_path.read_text()))
    declared = spec["schema_hash"]
    canonical = copy.deepcopy(spec)
    canonical["schema_hash"] = ""
    recomputed = hashlib.sha256(
        json.dumps(canonical, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()
    if declared != recomputed:
        raise RuntimeError(
            f"{contract_path.name} schema_hash is stale (declared={declared} "
            f"computed={recomputed}); re-run the recompute snippet + re-stage."
        )
    if tuple(spec["feature_order"]) != FEATURE_COLUMNS:
        raise RuntimeError(
            f"contract feature_order {spec['feature_order']} != code FEATURE_COLUMNS "
            f"{list(FEATURE_COLUMNS)} - contract + code drifted."
        )
    return spec


def export(*, artifact_dir: Path, contract_path: Path = CONTRACT_PATH) -> dict[str, Any]:
    spec = _load_and_verify_contract(contract_path)

    model_path = artifact_dir / "model.txt"
    if not model_path.exists():
        raise FileNotFoundError(
            f"LGBM booster not found at {model_path}; run 2c.8 lgbm_baseline.train first"
        )

    bundle = load_baseline(artifact_dir)
    n_features = len(FEATURE_COLUMNS)
    initial_types = [("input", FloatTensorType([None, n_features]))]
    # Let onnxmltools pick the LightGBM opset - it caps below the contract's serving onnx_opset (an
    # MLP/onnxruntime figure) because the LightGBM path emits the ai.onnx.ml TreeEnsemble domain,
    # exactly as lgbm_per_park's exporter does. Forcing the contract opset made the converter raise
    # "target_opset N is higher than supported". The rule-7 hash still validates because this
    # exporter never rewrites the contract JSON (its declared onnx_opset stays 18 in-file and is not
    # re-checked against the emitted graph). ORT-Java loads the lower opset (per_park serves 9).
    onnx_model = onnxmltools.convert.convert_lightgbm(
        bundle.booster,
        initial_types=initial_types,
        zipmap=False,
    )
    onnx.checker.check_model(onnx_model)
    emitted_opset = next(
        (o.version for o in onnx_model.opset_import if o.domain in ("", "ai.onnx")), 0
    )
    onnx_path = artifact_dir / "model.onnx"
    onnxmltools.utils.save_model(onnx_model, str(onnx_path))

    onnx_sha = hashlib.sha256(onnx_path.read_bytes()).hexdigest()
    print(
        f"wrote {onnx_path} ({n_features} inputs, opset {emitted_opset}, sha {onnx_sha[:12]})\n"
        f"  schema_hash: {spec['schema_hash']}\n"
        "  NEXT: run the Python<->Java parity check before registering "
        "(verify the categorical park_id splits converted faithfully)."
    )
    return {
        "onnx_path": str(onnx_path),
        "onnx_sha256": onnx_sha,
        "schema_hash": spec["schema_hash"],
    }


@click.command()
@click.option(
    "--artifact-dir",
    type=click.Path(file_okay=False, dir_okay=True, path_type=Path),
    default=Path("artifacts/batted_ball_lgbm_baseline/v1"),
    show_default=True,
)
def main(artifact_dir: Path) -> None:
    export(artifact_dir=artifact_dir)


if __name__ == "__main__":
    main()

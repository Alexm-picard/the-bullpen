"""Export the pooled LR baseline to a tiled [None, n_parks, 5] ONNX (decision [142], purist path).

``skl2onnx(Pipeline(StandardScaler, LogReg))`` -> [None, 5], then Unsqueeze + Tile across the
park axis so the baseline serves through the shared batted-ball contract identically to the
MLP / LGBM (every park slice is the same park-agnostic distribution - which is the whole point:
it's the "ignore park" floor).

  * StandardScaler is baked into the graph (LR is not scale-invariant), so metadata carries the
    IDENTITY feature_scaler - the shared Java FeaturePipelineBattedBall applies it as a raw
    pass-through, same pattern as the LGBM export.
  * Calibration stays post-inference: the 5 pooled isotonic calibrators are replicated per park
    (every slice identical) so the per-park serving path applies them uniformly.
  * target_opset is pinned to 18 (the contract's onnx_opset; skl2onnx defaults to 22, ORT-Java
    1.20 loads 18 comfortably). rule 7 hashes features, not opset.

Run on the desktop (the pipeline.joblib lives there, ADR-0006):
    uv run python -m bullpen_training.battedball.lr_baseline.export_onnx \\
        --artifact-dir artifacts/lr_baseline_batted_ball/v1
"""

from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
from typing import Any, cast

import click
import numpy as np
import onnx
from onnx import TensorProto, helper
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType

from bullpen_training.battedball.features_shared import FEATURE_NAMES
from bullpen_training.battedball.lr_baseline.train import load_lr_baseline_bundle

REPO_ROOT = Path(__file__).resolve().parents[5]
CONTRACT_PATH = REPO_ROOT / "contracts" / "feature_pipeline_battedball.json"
N_FEATURES = len(FEATURE_NAMES)
COMBINED_OUTPUT = "park_outcome_probs"
TARGET_OPSET = 18
PARITY_ATOL = 1e-5


def _load_and_verify_contract(contract_path: Path) -> dict[str, Any]:
    spec = cast(dict[str, Any], json.loads(contract_path.read_text()))
    declared = spec["schema_hash"]
    canonical = copy.deepcopy(spec)
    canonical["schema_hash"] = ""
    recomputed = hashlib.sha256(
        json.dumps(canonical, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()
    if declared != recomputed:
        raise RuntimeError(
            f"{contract_path.name} schema_hash stale (declared={declared} computed={recomputed})"
        )
    if tuple(spec["feature_order"]) != FEATURE_NAMES:
        raise RuntimeError(
            f"contract feature_order {spec['feature_order']} != FEATURE_NAMES {list(FEATURE_NAMES)}"
        )
    return spec


def _float_output_name(model: onnx.ModelProto) -> str:
    for out in model.graph.output:
        if out.type.tensor_type.elem_type == TensorProto.FLOAT:
            return out.name
    raise RuntimeError("no FLOAT (probabilities) output on the converted pipeline")


def _tile_to_parks(pipeline_onnx: onnx.ModelProto, n_parks: int, n_class: int) -> onnx.ModelProto:
    """Unsqueeze the [None, n_class] probs to [None, 1, n_class] and Tile across the park axis."""
    prob = _float_output_name(pipeline_onnx)
    main_opset = next(
        (o.version for o in pipeline_onnx.opset_import if o.domain in ("", "ai.onnx")), 0
    )
    nodes = list(pipeline_onnx.graph.node)
    inits = list(pipeline_onnx.graph.initializer)
    if main_opset >= 13:
        inits.append(helper.make_tensor("lr_unsq_axis", TensorProto.INT64, [1], [1]))
        nodes.append(helper.make_node("Unsqueeze", [prob, "lr_unsq_axis"], ["lr_unsq"]))
    else:
        nodes.append(helper.make_node("Unsqueeze", [prob], ["lr_unsq"], axes=[1]))
    inits.append(helper.make_tensor("lr_tile_reps", TensorProto.INT64, [3], [1, n_parks, 1]))
    nodes.append(helper.make_node("Tile", ["lr_unsq", "lr_tile_reps"], [COMBINED_OUTPUT]))

    graph = helper.make_graph(
        nodes,
        "lr_baseline_tiled",
        [pipeline_onnx.graph.input[0]],
        [
            helper.make_tensor_value_info(
                COMBINED_OUTPUT, TensorProto.FLOAT, [None, n_parks, n_class]
            )
        ],
        inits,
    )
    combined = helper.make_model(graph, opset_imports=list(pipeline_onnx.opset_import))
    combined.ir_version = pipeline_onnx.ir_version
    onnx.checker.check_model(combined)
    return combined


def _parity_self_check(
    combined: onnx.ModelProto, pipeline: Any, n_parks: int, n_check: int = 32
) -> float:
    import onnxruntime as ort

    rng = np.random.default_rng(20260605)
    x = rng.normal(size=(n_check, N_FEATURES)).astype(np.float32)
    sess = ort.InferenceSession(combined.SerializeToString())
    got = np.asarray(sess.run([COMBINED_OUTPUT], {"input": x})[0], dtype=np.float64)
    per_row = np.asarray(pipeline.predict_proba(x.astype(np.float64)), dtype=np.float64)
    want = np.repeat(per_row[:, None, :], n_parks, axis=1)
    max_diff = float(np.max(np.abs(got - want)))
    if got.shape != want.shape:
        raise RuntimeError(f"combined shape {got.shape} != tiled predict_proba {want.shape}")
    if max_diff > PARITY_ATOL:
        raise RuntimeError(f"LR baseline parity max|diff|={max_diff:.2e} > {PARITY_ATOL}")
    return max_diff


def export(*, artifact_dir: Path, contract_path: Path = CONTRACT_PATH) -> dict[str, Any]:
    spec = _load_and_verify_contract(contract_path)
    bundle = load_lr_baseline_bundle(artifact_dir)
    park_order = list(bundle.park_order)
    n_parks = len(park_order)
    n_class = len(bundle.outcome_names)
    if tuple(bundle.feature_columns) != FEATURE_NAMES:
        raise RuntimeError(
            f"bundle feature_columns {bundle.feature_columns} != {list(FEATURE_NAMES)}"
        )

    pipeline_onnx = convert_sklearn(
        bundle.pipeline,
        initial_types=[("input", FloatTensorType([None, N_FEATURES]))],
        target_opset=TARGET_OPSET,
        options={id(bundle.pipeline): {"zipmap": False}},
    )
    combined = _tile_to_parks(pipeline_onnx, n_parks, n_class)
    max_diff = _parity_self_check(combined, bundle.pipeline, n_parks)

    onnx_path = artifact_dir / "model.onnx"
    onnx.save(combined, str(onnx_path))

    # Replicate the 5 pooled calibrators across every park (park-agnostic floor).
    src_classes = json.loads((artifact_dir / "calibrator.json").read_text())["classes"]
    calibrator = {
        "schema_version": 2,
        "model_name": "lr_baseline_batted_ball",
        "outcome_order": list(bundle.outcome_names),
        "park_order": park_order,
        "parks": {park: src_classes for park in park_order},
    }
    (artifact_dir / "calibrator.json").write_text(json.dumps(calibrator, indent=2))

    metadata = {
        "schema_version": 2,
        "model_name": "lr_baseline_batted_ball",
        "model_version": "v1",
        "framework": "sklearn",
        "feature_columns": list(FEATURE_NAMES),
        "outcome_names": list(bundle.outcome_names),
        "park_order": park_order,
        "schema_hash": spec["schema_hash"],
        # StandardScaler is baked into the ONNX, so the shared Java pipeline must pass features
        # through unchanged: identity scaler. See module docstring.
        "feature_scaler": {"means": [0.0] * N_FEATURES, "stds": [1.0] * N_FEATURES},
        "onnx": {
            "input": "input",
            "output": COMBINED_OUTPUT,
            "output_shape": ["None", n_parks, n_class],
            "opset": next(
                (o.version for o in combined.opset_import if o.domain in ("", "ai.onnx")), 0
            ),
        },
        "calibrator_path": "calibrator.json",
        "note": (
            "Decision [142] purist path: pooled park-agnostic LR floor (rule-9 baseline), tiled to "
            f"[{n_parks},{n_class}]. Every park slice is identical by construction."
        ),
    }
    (artifact_dir / "metadata.json").write_text(json.dumps(metadata, indent=2))

    onnx_sha = hashlib.sha256(onnx_path.read_bytes()).hexdigest()
    print(
        f"wrote {onnx_path} (tiled to [{n_parks},{n_class}], output {COMBINED_OUTPUT}, "
        f"{onnx_path.stat().st_size // 1024} KB, sha {onnx_sha[:12]})\n"
        f"  parity (tiled ONNX vs predict_proba): max|diff|={max_diff:.2e} (< {PARITY_ATOL})\n"
        f"  schema_hash: {spec['schema_hash']}\n"
        "  NEXT: rolling-CV evidence + register-model as lr_baseline_batted_ball (state=SHADOW)."
    )
    return {
        "onnx_path": str(onnx_path),
        "onnx_sha256": onnx_sha,
        "schema_hash": spec["schema_hash"],
        "park_order": park_order,
        "parity_max_diff": max_diff,
    }


@click.command()
@click.option(
    "--artifact-dir",
    type=click.Path(file_okay=False, dir_okay=True, path_type=Path),
    default=Path("artifacts/lr_baseline_batted_ball/v1"),
    show_default=True,
)
def main(artifact_dir: Path) -> None:
    export(artifact_dir=artifact_dir)


if __name__ == "__main__":
    main()

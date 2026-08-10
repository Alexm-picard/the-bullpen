"""Combine the 30 per-park batted-ball LightGBM boosters into ONE registry-ready
ONNX model + a merged calibrator + metadata (decision [142]).

The Java serving path (``FeaturePipelineBattedBall`` + ``LoadedBattedBallModel``) treats a
batted-ball model as a SINGLE ONNX whose park axis is an OUTPUT dimension:

    input  [None, 15]      - the 15 launch/game-state features, NO park_id
    output [None, 30, 5]   - every park's P(out, 1b, 2b, 3b, hr), park axis = sorted park order

So the 30 independent per-park boosters are fanned out from one shared input and their
[None, 5] softmax outputs are unsqueezed + concatenated on a new park axis. This makes the
LGBM a drop-in SHADOW comparator served byte-identically to the MLP (decision [142]) - not a
set of 30 files that nothing on the Java side knows how to load.

Three subtleties this gets right (each would silently break a naive export):

  * Park axis order = ``sorted(park_ids)`` - the SAME order both trainers use
    (``tuple(sorted(load_all_parks().keys()))``), so the LGBM park axis aligns with the MLP's
    and the contract's ``metadata.park_order``.
  * Opset = whatever ``onnxmltools.convert_lightgbm`` emits (``ai.onnx`` 9 / ``ai.onnx.ml`` 1);
    the converter caps below the MLP's 18, and ORT-Java 1.20 loads it fine. Rule 7 hashes the
    FEATURE pipeline, not the ONNX opset, so the shared contract hash still matches. The full
    ``opset_import`` (incl. the ``ai.onnx.ml`` TreeEnsemble domain) is copied into the combined
    model.
  * An IDENTITY ``feature_scaler`` (means=0, std=1) in metadata. LightGBM is scale-invariant and
    needs RAW features, but the shared Java ``FeaturePipelineBattedBall`` ALWAYS applies
    ``(raw-mean)/std`` from metadata; identity makes it a pass-through. Without it the trees see
    z-scored inputs (wrong) or Java throws on the missing scaler.

Calibration stays POST-inference: the 30 per-park isotonic calibrators are merged into one
``calibrator.json`` (park axis aligned to the ONNX), applied by the serving layer to each park's
[5] slice of the raw softmax - not baked into the graph (matches the MLP).

Outputs at the artifact-dir root (alongside the 30 ``<PARK>/`` booster subdirs):
    model.onnx        - combined [None,15] -> [None,30,5]
    calibrator.json   - 30 parks x 5 isotonic calibrators, park axis aligned
    metadata.json     - registry metadata (schema_hash, park_order, identity scaler, ...)

Run on the desktop (the boosters live there, ADR-0006):
    uv run python -m bullpen_training.battedball.lgbm_per_park.export_onnx \\
        --artifact-dir artifacts/battedball_lgbm_per_park_v1
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any, cast

import click
import lightgbm as lgb
import numpy as np
import onnx
import onnxmltools
from onnx import TensorProto, helper
from onnx.compose import add_prefix
from onnxmltools.convert.common.data_types import FloatTensorType  # type: ignore[import-untyped]

from bullpen_training.battedball.lgbm_per_park.dataset import FEATURE_COLUMNS
from bullpen_training.registry_client import feature_hasher

REPO_ROOT = Path(__file__).resolve().parents[5]
CONTRACT_PATH = REPO_ROOT / "contracts" / "feature_pipeline_battedball.json"
N_FEATURES = len(FEATURE_COLUMNS)
COMBINED_OUTPUT = "park_outcome_probs"
PARITY_ATOL = 1e-5


def _load_and_verify_contract(contract_path: Path) -> dict[str, Any]:
    """Read the batted-ball contract; verify its schema_hash + that feature_order matches
    FEATURE_COLUMNS. Drift is a hard fail (the registry would refuse it anyway, rule 7)."""
    spec = cast(dict[str, Any], json.loads(contract_path.read_text()))
    declared = spec["schema_hash"]
    recomputed = feature_hasher.compute(contract_path)
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


def _booster_to_onnx(booster: lgb.Booster) -> onnx.ModelProto:
    return onnxmltools.convert.convert_lightgbm(
        booster,
        initial_types=[("input", FloatTensorType([None, N_FEATURES]))],
        zipmap=False,
    )


def _float_output_name(model: onnx.ModelProto) -> str:
    """The softmax probabilities output (convert_lightgbm emits ['label', 'probabilities'])."""
    for out in model.graph.output:
        if out.type.tensor_type.elem_type == TensorProto.FLOAT:
            return out.name
    raise RuntimeError("no FLOAT (probabilities) output on the converted booster")


def _combine(sub_models: list[onnx.ModelProto], n_class: int) -> onnx.ModelProto:
    """Fan N single-input booster graphs out from one shared ``input`` and concat their
    [None, n_class] probability outputs on a new park axis -> [None, N, n_class]."""
    m0 = sub_models[0]
    prob_name = _float_output_name(m0)
    main_opset = next((o.version for o in m0.opset_import if o.domain in ("", "ai.onnx")), 0)
    use_axes_input = main_opset >= 13  # Unsqueeze moved axes to an input at opset 13

    nodes: list[Any] = []
    inits: list[Any] = []
    axis_init = "park_concat_axis"
    unsq_outputs: list[str] = []
    for i, m in enumerate(sub_models):
        pref = f"park{i}_"
        g = add_prefix(m, prefix=pref).graph
        shared_in = g.input[0].name  # pref + "input"
        for node in g.node:
            node.input[:] = ["input" if x == shared_in else x for x in node.input]
        nodes.extend(g.node)
        inits.extend(g.initializer)
        unsq = f"{pref}prob_unsq"
        if use_axes_input:
            nodes.append(helper.make_node("Unsqueeze", [pref + prob_name, axis_init], [unsq]))
        else:
            nodes.append(helper.make_node("Unsqueeze", [pref + prob_name], [unsq], axes=[1]))
        unsq_outputs.append(unsq)

    if use_axes_input:
        inits.append(helper.make_tensor(axis_init, TensorProto.INT64, [1], [1]))
    nodes.append(helper.make_node("Concat", unsq_outputs, [COMBINED_OUTPUT], axis=1))

    graph = helper.make_graph(
        nodes,
        "battedball_lgbm_per_park_combined",
        [helper.make_tensor_value_info("input", TensorProto.FLOAT, [None, N_FEATURES])],
        [
            helper.make_tensor_value_info(
                COMBINED_OUTPUT, TensorProto.FLOAT, [None, len(sub_models), n_class]
            )
        ],
        inits,
    )
    combined = helper.make_model(graph, opset_imports=list(m0.opset_import))
    combined.ir_version = m0.ir_version
    onnx.checker.check_model(combined)
    return combined


def _parity_self_check(
    combined: onnx.ModelProto, boosters: list[lgb.Booster], n_check: int = 32
) -> float:
    """Run the combined ONNX vs stacking the native boosters on random inputs. Raises on drift.
    This is the export-time guard; the registry parity fixture is the durable CI gate."""
    import onnxruntime as ort

    rng = np.random.default_rng(20260605)
    x = rng.normal(size=(n_check, N_FEATURES)).astype(np.float32)
    sess = ort.InferenceSession(combined.SerializeToString())
    # booster.predict + ort.run are broadly typed (ndarray | spmatrix | list); coerce to ndarray.
    got = np.asarray(sess.run([COMBINED_OUTPUT], {"input": x})[0], dtype=np.float64)
    per_park = [np.asarray(b.predict(x.astype(np.float64)), dtype=np.float64) for b in boosters]
    want = np.stack(per_park, axis=1)
    max_diff = float(np.max(np.abs(got - want)))
    if got.shape != want.shape:
        raise RuntimeError(f"combined shape {got.shape} != native stack {want.shape}")
    if max_diff > PARITY_ATOL:
        raise RuntimeError(f"combined vs native parity max|diff|={max_diff:.2e} > {PARITY_ATOL}")
    return max_diff


def _merge_calibrators(park_dirs: list[Path], park_ids: list[str]) -> dict[str, Any]:
    """Assemble the 30 per-park calibrator.json files into one, park axis aligned to the ONNX."""
    outcome_order: list[str] | None = None
    parks: dict[str, Any] = {}
    for park_id, pd in zip(park_ids, park_dirs, strict=True):
        payload = json.loads((pd / "calibrator.json").read_text())
        if outcome_order is None:
            outcome_order = payload["outcome_order"]
        elif payload["outcome_order"] != outcome_order:
            raise RuntimeError(
                f"{park_id} outcome_order {payload['outcome_order']} != {outcome_order}"
            )
        parks[park_id] = payload["classes"]
    return {
        "schema_version": 2,
        "model_name": "battedball_lgbm_per_park",
        "outcome_order": outcome_order,
        "park_order": park_ids,
        "parks": parks,
    }


def export(*, artifact_dir: Path, contract_path: Path = CONTRACT_PATH) -> dict[str, Any]:
    spec = _load_and_verify_contract(contract_path)

    # Sorted park subdirs == the order both trainers use (sorted(load_all_parks().keys())),
    # so the ONNX park axis aligns with the MLP's and the contract's metadata.park_order.
    park_dirs = sorted(
        (p for p in artifact_dir.iterdir() if p.is_dir() and (p / "model.txt").exists()),
        key=lambda p: p.name,
    )
    if not park_dirs:
        raise FileNotFoundError(f"no <PARK>/model.txt booster subdirs under {artifact_dir}")
    park_ids = [p.name for p in park_dirs]

    boosters = [lgb.Booster(model_file=str(pd / "model.txt")) for pd in park_dirs]
    first_meta = json.loads((park_dirs[0] / "metadata.json").read_text())
    outcome_names = list(first_meta["outcome_names"])
    n_class = len(outcome_names)
    if tuple(first_meta["feature_columns"]) != FEATURE_COLUMNS:
        raise RuntimeError(
            f"booster feature_columns {first_meta['feature_columns']} != FEATURE_COLUMNS "
            f"{list(FEATURE_COLUMNS)}; the per-park set was trained on different features."
        )

    sub_models = [_booster_to_onnx(b) for b in boosters]
    combined = _combine(sub_models, n_class)
    max_diff = _parity_self_check(combined, boosters)

    onnx_path = artifact_dir / "model.onnx"
    onnx.save(combined, str(onnx_path))

    calibrator = _merge_calibrators(park_dirs, park_ids)
    (artifact_dir / "calibrator.json").write_text(json.dumps(calibrator, indent=2))

    metadata = {
        "schema_version": 2,
        "model_name": "battedball_lgbm_per_park",
        "model_version": artifact_dir.name.split("_")[-1] if "_v" in artifact_dir.name else "v1",
        "framework": "lightgbm",
        "feature_columns": list(FEATURE_COLUMNS),
        "outcome_names": outcome_names,
        "park_order": park_ids,
        "schema_hash": spec["schema_hash"],
        # LightGBM is scale-invariant and needs RAW features, but the shared Java
        # FeaturePipelineBattedBall always applies (raw-mean)/std from this scaler. Identity
        # (means=0, std=1) makes it a pass-through. See the module docstring.
        "feature_scaler": {"means": [0.0] * N_FEATURES, "stds": [1.0] * N_FEATURES},
        "onnx": {
            "input": "input",
            "output": COMBINED_OUTPUT,
            "output_shape": ["None", len(park_ids), n_class],
            "opset": next(
                (o.version for o in combined.opset_import if o.domain in ("", "ai.onnx")), 0
            ),
        },
        "calibrator_path": "calibrator.json",
        "note": (
            "Decision [142]: routed SHADOW candidate alongside the MLP. Park axis is sorted "
            "park order; per-park isotonic calibrators applied post-inference."
        ),
    }
    (artifact_dir / "metadata.json").write_text(json.dumps(metadata, indent=2))

    onnx_sha = hashlib.sha256(onnx_path.read_bytes()).hexdigest()
    print(
        f"wrote {onnx_path} ({len(park_ids)} parks x {n_class} outcomes, "
        f"output {COMBINED_OUTPUT}{[None, len(park_ids), n_class]}, "
        f"{onnx_path.stat().st_size // (1024 * 1024)} MB, sha {onnx_sha[:12]})\n"
        f"  parity (combined vs native): max|diff|={max_diff:.2e} (< {PARITY_ATOL})\n"
        f"  schema_hash: {spec['schema_hash']}\n"
        f"  park_order[0:3]: {park_ids[:3]} ... [{len(park_ids)} total]\n"
        "  NEXT: registry parity fixture + register-model (state=SHADOW)."
    )
    return {
        "onnx_path": str(onnx_path),
        "onnx_sha256": onnx_sha,
        "schema_hash": spec["schema_hash"],
        "park_order": park_ids,
        "parity_max_diff": max_diff,
    }


@click.command()
@click.option(
    "--artifact-dir",
    type=click.Path(file_okay=False, dir_okay=True, path_type=Path),
    default=Path("artifacts/battedball_lgbm_per_park_v1"),
    show_default=True,
)
def main(artifact_dir: Path) -> None:
    export(artifact_dir=artifact_dir)


if __name__ == "__main__":
    main()

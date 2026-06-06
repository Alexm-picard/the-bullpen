"""Generate the Python<->Java parity fixture for the batted-ball per-park OUTCOME model (B5).

Mirrors :mod:`bullpen_training.pitch.parity_fixture` but for the ``[None,15] -> [None,30,5]``
all-parks model (decision [146]). Runs a deterministic set of synthetic batted-ball inputs through
the Python serving path - build + z-score the 15-feature vector, run the ONNX (per-park softmax,
baked in per decision [148]), apply the per-park isotonic calibration - and emits two JSON files:

    parity_battedball_allparks_001.json           - the 7 raw inputs per row
    parity_battedball_allparks_001_expected.json  - 15-feature vector + calibrated [30,5] per row

The Java :class:`BattedBallAllParksParityTest` loads the same input file, runs
``FeaturePipelineBattedBall`` + ``BattedBallOnnxModel`` + ``BattedBallCalibrators``, and asserts
``|java - expected| < tolerance`` per feature and per (park, outcome). Drift means the Java serving
has diverged from this Python reference.

Synthetic inputs (no ClickHouse dependency) so it runs anywhere the registered artifacts are
present. Re-run when the model is retrained or the export / contract changes. Run on the desktop
(the registered model lives there, ADR-0006), or locally after ``make sync-mirror``:

    uv run python -m bullpen_training.battedball.mlp.parity_fixture_allparks \\
        --artifact-dir training/artifacts/battedball_outcome/v1
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import click
import numpy as np
import onnxruntime as ort

from bullpen_training.battedball.mlp.calibration import load_calibrator, transform

REPO_ROOT = Path(__file__).resolve().parents[4]
FIXTURES_DIR = REPO_ROOT / "training" / "tests" / "fixtures"
CONTRACT = REPO_ROOT / "contracts" / "feature_pipeline_battedball.json"
INPUT_FILE = FIXTURES_DIR / "parity_battedball_allparks_001.json"
EXPECTED_FILE = FIXTURES_DIR / "parity_battedball_allparks_001_expected.json"

# float32 serving path vs the float64 reference + the renormalise: ~1e-6 in practice, 1e-5 guard.
TOLERANCE = 1e-5

# Deterministic, varied synthetic inputs: a spread of launch params, both stands, a few base/out
# states. Enough to exercise the continuous-scaling + one-hot + per-park calibration paths.
INPUT_ROWS: list[dict[str, Any]] = [
    {
        "launch_speed_mph": 102.3,
        "launch_angle_deg": 27.0,
        "spray_angle_deg": 5.0,
        "hit_distance_ft": 401.0,
        "stand": "R",
        "base_state": 0,
        "outs": 1,
    },
    {
        "launch_speed_mph": 88.1,
        "launch_angle_deg": 12.0,
        "spray_angle_deg": -20.0,
        "hit_distance_ft": 230.0,
        "stand": "L",
        "base_state": 3,
        "outs": 2,
    },
    {
        "launch_speed_mph": 95.6,
        "launch_angle_deg": 38.5,
        "spray_angle_deg": 18.0,
        "hit_distance_ft": 360.0,
        "stand": "R",
        "base_state": 7,
        "outs": 0,
    },
    {
        "launch_speed_mph": 70.0,
        "launch_angle_deg": -5.0,
        "spray_angle_deg": 2.0,
        "hit_distance_ft": 120.0,
        "stand": "L",
        "base_state": 1,
        "outs": 1,
    },
]


def _build_feature_vector(
    row: dict[str, Any],
    feature_order: list[str],
    means: list[float],
    stds: list[float],
) -> list[float]:
    """Build + z-score the 15-feature vector, matching ``FeaturePipelineBattedBall.computeRaw`` +
    the metadata scaler ``(x - mean) / std`` (one-hots carry identity mean/std)."""
    stand = row["stand"]
    base = int(row["base_state"])
    raw: dict[str, float] = {
        "launch_speed_mph": float(row["launch_speed_mph"]),
        "launch_angle_deg": float(row["launch_angle_deg"]),
        "spray_angle_deg": float(row["spray_angle_deg"]),
        "hit_distance_ft": float(row["hit_distance_ft"]),
        # stand one-hot: unknown / non-L -> R (matches the Java fallback).
        "stand_R": 0.0 if stand == "L" else 1.0,
        "stand_L": 1.0 if stand == "L" else 0.0,
        "outs": float(row["outs"]),
    }
    for i in range(8):
        raw[f"base_state_{i}"] = 1.0 if base == i else 0.0
    vec: list[float] = []
    for j, name in enumerate(feature_order):
        if name not in raw:
            raise KeyError(f"no build rule for contract feature {name!r}")
        vec.append((raw[name] - means[j]) / stds[j])
    return vec


@click.command()
@click.option(
    "--artifact-dir",
    required=True,
    type=click.Path(exists=True, file_okay=False, path_type=Path),
    help="Registered snapshot dir holding model.onnx, metadata.json, calibrator.json.",
)
def main(artifact_dir: Path) -> None:
    contract: dict[str, Any] = json.loads(CONTRACT.read_text())
    feature_order: list[str] = list(contract["feature_order"])
    schema_hash: str = str(contract["schema_hash"])

    metadata: dict[str, Any] = json.loads((artifact_dir / "metadata.json").read_text())
    scaler: dict[str, Any] = metadata["feature_scaler"]
    means: list[float] = [float(m) for m in scaler["means"]]
    stds: list[float] = [float(s) for s in scaler["stds"]]
    park_order: list[str] = [str(p) for p in metadata["park_order"]]
    outcome_order: list[str] = [
        str(o) for o in metadata.get("outcome_names", ["out", "1b", "2b", "3b", "hr"])
    ]

    calibrators = load_calibrator(artifact_dir / "calibrator.json")
    session = ort.InferenceSession(str(artifact_dir / "model.onnx"))
    input_name = session.get_inputs()[0].name

    expected_rows: list[dict[str, Any]] = []
    for row in INPUT_ROWS:
        vec = _build_feature_vector(row, feature_order, means, stds)
        arr = np.asarray([vec], dtype=np.float32)
        # onnx_output_index 0 = the [None, 30, 5] per-park softmax distribution (the contract).
        probs = np.asarray(session.run(None, {input_name: arr})[0], dtype=np.float64)
        calibrated = transform(calibrators, probs)[0]  # (n_parks, n_outcomes)
        expected_rows.append(
            {
                "feature_vector": [float(v) for v in vec],
                "calibrated": [[float(c) for c in park] for park in calibrated],
            }
        )

    FIXTURES_DIR.mkdir(parents=True, exist_ok=True)
    input_doc = {"schema_hash": schema_hash, "rows": INPUT_ROWS}
    expected_doc = {
        "schema_hash": schema_hash,
        "tolerance": TOLERANCE,
        "park_order": park_order,
        "outcome_order": outcome_order,
        "rows": expected_rows,
    }
    INPUT_FILE.write_text(json.dumps(input_doc, indent=2) + "\n")
    EXPECTED_FILE.write_text(json.dumps(expected_doc, indent=2) + "\n")
    click.echo(f"wrote parity fixtures for {len(INPUT_ROWS)} rows to {FIXTURES_DIR}")


if __name__ == "__main__":
    main()

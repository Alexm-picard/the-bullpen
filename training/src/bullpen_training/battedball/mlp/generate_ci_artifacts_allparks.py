"""Generate a DETERMINISTIC MINIATURE all-parks batted-ball artifact for CI parity.

The real ``battedball_outcome`` champion is trained on ClickHouse Statcast (GPU, box-only per
ADR-0006) and its artifacts are git-ignored (``training/artifacts/**``). That makes
:class:`BattedBallAllParksParityTest` self-skip in CI, so the strongest cross-language guarantee -
that Java serving reproduces the Python exporter bit-for-bit - never actually runs where it counts.

This builds a SMALL, seeded stand-in with the EXACT same input/output contract (``[None,15] ->
[None,30,5]`` per-park softmax) by reusing the real serving-path code (:func:`build_model`,
:func:`export_onnx`, :func:`write_metadata`, :class:`FeatureScaler`, and
:func:`fit_per_park_calibrators`).
The model is random-init (untrained) - a parity test asserts Java == Python for the SAME graph, so
accuracy is irrelevant; only determinism and the wire contract matter. Pair with
``parity_fixture_allparks`` to emit the fixtures the Java test consumes:

    uv run python -m bullpen_training.battedball.mlp.generate_ci_artifacts_allparks \\
        --out-dir training/artifacts/battedball_outcome/v1
    uv run python -m bullpen_training.battedball.mlp.parity_fixture_allparks \\
        --artifact-dir training/artifacts/battedball_outcome/v1

Determinism: torch + numpy seeded, so CI regenerates identical artifacts + fixtures each run.
"""

from __future__ import annotations

import json
from pathlib import Path

import click
import numpy as np

from bullpen_training.battedball.mlp.architecture import build_model, predict_park_probs
from bullpen_training.battedball.mlp.calibration import fit_per_park_calibrators, save_calibrator
from bullpen_training.battedball.mlp.dataset import FeatureScaler
from bullpen_training.battedball.mlp.train import export_onnx, write_metadata

# parents[5] = repo root (this module sits one level deeper than the pitch/ generators - see the
# same note in parity_fixture_allparks).
REPO_ROOT = Path(__file__).resolve().parents[5]
CONTRACT = REPO_ROOT / "contracts" / "feature_pipeline_battedball.json"

# The 30 head labels. Order is fixed + shared by metadata.park_order and calibrator.park_order so
# the Java calibrator dispatches by park_id consistently; these are the real MLB park codes so the
# stand-in looks like the champion it substitutes for.
PARK_ORDER: list[str] = [
    "ATL",
    "AZ",
    "BAL",
    "BOS",
    "CHC",
    "CHW",
    "CIN",
    "CLE",
    "COL",
    "DET",
    "HOU",
    "KC",
    "LAA",
    "LAD",
    "MIA",
    "MIL",
    "MIN",
    "NYM",
    "NYY",
    "OAK",
    "PHI",
    "PIT",
    "SD",
    "SEA",
    "SF",
    "STL",
    "TB",
    "TEX",
    "TOR",
    "WSH",
]
OUTCOME_ORDER: list[str] = ["out", "1b", "2b", "3b", "hr"]

N_SYNTHETIC = 128  # enough to fit a well-formed scaler + per-(park,outcome) isotonic


def _synthetic_raw_features(feature_order: list[str], rng: np.random.Generator) -> np.ndarray:
    """Build ``(N_SYNTHETIC, 15)`` RAW (unscaled) feature rows in contract column order.

    Continuous columns get plausible batted-ball distributions; the stand + base_state one-hots
    are valid (exactly one hot). Mirrors ``FeaturePipelineBattedBall.computeRaw`` column layout.
    """
    n = N_SYNTHETIC
    feats = np.zeros((n, len(feature_order)), dtype=np.float64)
    idx = {name: j for j, name in enumerate(feature_order)}
    feats[:, idx["launch_speed_mph"]] = rng.normal(89.0, 14.0, n).clip(0.0, 125.0)
    feats[:, idx["launch_angle_deg"]] = rng.normal(14.0, 22.0, n).clip(-80.0, 85.0)
    feats[:, idx["spray_angle_deg"]] = rng.normal(0.0, 25.0, n).clip(-45.0, 45.0)
    feats[:, idx["hit_distance_ft"]] = rng.normal(235.0, 125.0, n).clip(0.0, 470.0)
    feats[:, idx["outs"]] = rng.integers(0, 3, n).astype(np.float64)
    stand_r = rng.integers(0, 2, n)
    feats[:, idx["stand_R"]] = stand_r
    feats[:, idx["stand_L"]] = 1 - stand_r
    base = rng.integers(0, 8, n)
    for i in range(8):
        feats[:, idx[f"base_state_{i}"]] = (base == i).astype(np.float64)
    return feats


@click.command()
@click.option(
    "--out-dir",
    default="training/artifacts/battedball_outcome/v1",
    type=click.Path(file_okay=False, path_type=Path),
    help="Destination artifact dir (model.onnx + metadata.json + calibrator.json).",
)
@click.option("--seed", default=42, show_default=True, help="Torch/NumPy seed for determinism.")
def main(out_dir: Path, seed: int) -> None:
    rng = np.random.default_rng(seed)
    feature_order: list[str] = list(json.loads(CONTRACT.read_text())["feature_order"])
    if len(feature_order) != 15:
        raise ValueError(f"expected 15 contract features, got {len(feature_order)}")

    out_dir.mkdir(parents=True, exist_ok=True)

    # 1) deterministic miniature model with the real I/O contract (15 in -> 30 parks x 5 outcomes).
    model = build_model(
        n_features=15, n_parks=len(PARK_ORDER), n_outcomes=len(OUTCOME_ORDER), hidden=32, seed=seed
    ).eval()

    # 2) fit the scaler on synthetic rows (one-hots forced to identity mean/std by FeatureScaler).
    raw = _synthetic_raw_features(feature_order, rng)
    scaler = FeatureScaler.fit(raw)

    # 3) export the two-output serving graph (output[0] = per-park softmax, the parity target).
    export_onnx(model, out_dir / "model.onnx")

    # 4) metadata (feature_scaler + park_order + outcome_names) - the Java pipeline + calibrator
    # read this alongside the committed contract.
    write_metadata(
        out_dir / "metadata.json",
        park_order=PARK_ORDER,
        feature_names=feature_order,
        outcome_names=OUTCOME_ORDER,
        scaler=scaler,
    )

    # 5) per-(park, outcome) isotonic calibrator. Fit each cell against a mild deterministic
    # monotone perturbation of the model's own softmax so the calibrator is non-trivial (exercises
    # real interpolation, not identity) yet reproducible.
    scaled = scaler.transform(raw)
    probs = predict_park_probs(model, scaled).astype(np.float64)  # (N, 30, 5)
    labels = probs**1.15
    labels = labels / labels.sum(axis=-1, keepdims=True)
    calibrators = fit_per_park_calibrators(
        probs, labels, park_order=tuple(PARK_ORDER), outcome_order=tuple(OUTCOME_ORDER)
    )
    save_calibrator(calibrators, out_dir / "calibrator.json")

    click.echo(
        f"wrote miniature all-parks artifacts to {out_dir} "
        f"(parks={len(PARK_ORDER)} outcomes={len(OUTCOME_ORDER)} scaler_dim={len(feature_order)})"
    )


if __name__ == "__main__":
    main()

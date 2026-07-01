"""Generate DETERMINISTIC MINIATURE pitch-outcome artifacts for CI parity.

The real ``pitch_outcome_pre`` / ``pitch_outcome_post`` heads are trained on ClickHouse Statcast
(box-only per ADR-0006) and their artifacts are git-ignored (``training/artifacts/**``). That makes
:class:`PitchPreParityTest` / :class:`PitchPostParityTest` self-skip in CI, so the strongest
cross-language guarantee - that Java serving reproduces the Python exporter bit-for-bit - never
actually runs where it counts.

This builds a SMALL, seeded stand-in with the EXACT serving contract (31-feature -> 5-class for pre,
41-feature -> 5-class for post) by reusing the real serving-path exporter
(:func:`bullpen_training.pitch.export_pre_onnx.export` /
:func:`bullpen_training.pitch.export_post_onnx.export`, which run ``onnxmltools.convert_lightgbm``
with ``zipmap=False``). The booster is trained on seeded synthetic rows - a parity test asserts
Java == Python for the SAME graph, so accuracy is irrelevant; only determinism and the wire contract
matter. Pair with ``parity_fixture --synthetic`` / ``parity_fixture_post --synthetic`` to emit the
fixtures the Java tests consume:

    uv run python -m bullpen_training.pitch.generate_ci_artifacts \\
        --head pre --out-dir artifacts/pitch_outcome_pre/v2
    uv run python -m bullpen_training.pitch.parity_fixture --synthetic \\
        --model-dir artifacts/pitch_outcome_pre/v2

    uv run python -m bullpen_training.pitch.generate_ci_artifacts \\
        --head post --out-dir artifacts/pitch_outcome_post/v1
    uv run python -m bullpen_training.pitch.parity_fixture_post --synthetic \\
        --model-dir artifacts/pitch_outcome_post/v1

Determinism: NumPy + LightGBM seeded (``deterministic=True``, ``force_row_wise=True``, single
thread, bagging off), so CI regenerates identical artifacts + fixtures each run.

The lookup tables (park_id_mapping / pitch_type_mapping / pitcher_te / batter_te) are written for a
FIXED entity universe (``PITCHER_IDS`` / ``BATTER_IDS`` / ``PARK_CODES`` / ``PITCH_TYPES``); the
synthetic request rows in ``parity_fixture.SYNTHETIC_INPUT_ROWS`` deliberately reference a mix of
in-universe ids (exercising the lookup hit path) and out-of-universe ids (exercising the
prior / missing-value fallback). Both languages take the same fallback, so parity holds either way.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import click
import lightgbm as lgb
import numpy as np
import pandas as pd

from bullpen_training.features import LABEL_CLASSES
from bullpen_training.pitch import PITCH_FEATURE_COLUMNS, PITCH_FEATURE_COLUMNS_POST
from bullpen_training.pitch.export_post_onnx import export as export_post
from bullpen_training.pitch.export_pre_onnx import export as export_pre
from bullpen_training.pitch.isotonic import IsotonicCalibrator

# parents[4] = repo root: this module sits at training/src/bullpen_training/pitch/.
REPO_ROOT = Path(__file__).resolve().parents[4]

# Fixed entity universes the fixture rows reference. Keep in sync with the SYNTHETIC_INPUT_ROWS in
# parity_fixture.py / parity_fixture_post.py (documented coupling, same as the batted-ball
# PARK_ORDER shared between its generator + fixture modules).
PITCHER_IDS: tuple[int, ...] = tuple(100000 + i for i in range(20))
BATTER_IDS: tuple[int, ...] = tuple(200000 + i for i in range(20))
# Real MLB park codes so the stand-in looks like the champion it substitutes for.
PARK_CODES: tuple[str, ...] = (
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
)
# Real Statcast pitch-type codes (post head only).
PITCH_TYPES: tuple[str, ...] = (
    "CH",
    "CU",
    "FC",
    "FF",
    "FS",
    "KC",
    "SI",
    "SL",
    "ST",
    "SV",
)

# Integer-encoded feature columns get integer synthetic values spanning the fixture range so the
# booster grows splits near the values the fixtures actually probe; everything else is continuous.
_INT_COLUMNS: frozenset[str] = frozenset(
    {
        "count_balls",
        "count_strikes",
        "outs",
        "inning",
        "base_state",
        "score_diff",
        "dow",
        "pitcher_throws_int",
        "batter_stand_int",
        "park_id_int",
        "pitch_type_int",
    }
)

N_SYNTHETIC = 1024  # enough rows for a well-formed multiclass booster + isotonic fit


def _synthetic_training_frame(
    feature_order: tuple[str, ...], rng: np.random.Generator
) -> pd.DataFrame:
    """Build ``(N_SYNTHETIC, len(feature_order))`` synthetic feature rows in contract order.

    Values are plausible-but-arbitrary: the booster only needs a valid graph, not accuracy. Integer
    columns span the fixture range; continuous columns are standard-normal. Column names match the
    contract so ``booster.predict(df[feature_cols])`` aligns by name with the ONNX positional input.
    """
    data: dict[str, np.ndarray] = {}
    for name in feature_order:
        if name in _INT_COLUMNS:
            data[name] = rng.integers(-10, 40, N_SYNTHETIC).astype(np.float64)
        else:
            data[name] = rng.normal(0.0, 1.0, N_SYNTHETIC)
    return pd.DataFrame(data, columns=list(feature_order))


def _synthetic_labels(frame: pd.DataFrame, rng: np.random.Generator) -> np.ndarray:
    """Draw integer labels with mild feature dependence so the isotonic fit is non-trivial.

    A softmax over a seeded linear projection of the (standardised) features gives per-row class
    probabilities; sampling from them ties labels weakly to features so the per-class isotonic
    breakpoints exercise real interpolation rather than collapsing to a constant.
    """
    x = frame.to_numpy(dtype=np.float64)
    x = (x - x.mean(axis=0)) / (x.std(axis=0) + 1e-9)
    weights = rng.normal(0.0, 0.6, (x.shape[1], len(LABEL_CLASSES)))
    logits = x @ weights
    logits -= logits.max(axis=1, keepdims=True)
    probs = np.exp(logits)
    probs /= probs.sum(axis=1, keepdims=True)
    labels = np.fromiter(
        (rng.choice(len(LABEL_CLASSES), p=probs[i]) for i in range(x.shape[0])),
        dtype=np.int64,
        count=x.shape[0],
    )
    return labels


def _train_miniature_booster(frame: pd.DataFrame, labels: np.ndarray, *, seed: int) -> lgb.Booster:
    """Train a small, fully-deterministic 5-class multinomial booster on the synthetic frame."""
    params = {
        "objective": "multiclass",
        "num_class": len(LABEL_CLASSES),
        "metric": "multi_logloss",
        "learning_rate": 0.1,
        "num_leaves": 15,
        "min_data_in_leaf": 20,
        "feature_fraction": 1.0,
        "bagging_fraction": 1.0,
        "bagging_freq": 0,
        "seed": seed,
        "deterministic": True,
        "force_row_wise": True,
        "num_threads": 1,
        "verbosity": -1,
    }
    dtrain = lgb.Dataset(frame, label=labels, free_raw_data=False)
    return lgb.train(params, dtrain, num_boost_round=40)


def _write_te_lookup(
    path: Path, entity_col: str, ids: tuple[int, ...], rng: np.random.Generator
) -> None:
    """Write a pitcher_te.json / batter_te.json in the exact shape the Java + Python loaders read.

    Each id gets a seeded per-class target-encoding vector (normalised to sum to 1, so it reads like
    a real rate distribution); the prior is a separate seeded distribution used for unseen ids.
    """

    def _distribution() -> dict[str, float]:
        raw = rng.uniform(0.05, 1.0, len(LABEL_CLASSES))
        raw = raw / raw.sum()
        return {cls: float(v) for cls, v in zip(LABEL_CLASSES, raw, strict=True)}

    rows: list[dict[str, Any]] = []
    for entity_id in ids:
        dist = _distribution()
        rows.append(
            {entity_col: int(entity_id), **{f"te_{cls}": dist[cls] for cls in LABEL_CLASSES}}
        )
    payload = {"entity_col": entity_col, "prior": _distribution(), "rows": rows}
    path.write_text(json.dumps(payload, indent=2) + "\n")


def _write_park_id_mapping(path: Path) -> None:
    """park_id_mapping.json: alphabetical code -> index (matches the real loader + persist)."""
    mapping = {code: i for i, code in enumerate(sorted(PARK_CODES))}
    path.write_text(json.dumps({"park_id": mapping, "missing_value": -1}, indent=2) + "\n")


def _write_pitch_type_mapping(path: Path) -> None:
    """pitch_type_mapping.json: alphabetical code -> index (post head only)."""
    mapping = {pt: i for i, pt in enumerate(sorted(PITCH_TYPES))}
    path.write_text(json.dumps({"pitch_type": mapping, "missing_value": -1}, indent=2) + "\n")


@click.command()
@click.option(
    "--head",
    type=click.Choice(["pre", "post"], case_sensitive=False),
    required=True,
    help="Which pitch head to synthesize (pre = 31 features, post = 41 features).",
)
@click.option(
    "--out-dir",
    default=None,
    type=click.Path(file_okay=False, path_type=Path),
    help="Destination artifact dir. Defaults to artifacts/pitch_outcome_<head>/<v2|v1>.",
)
@click.option("--seed", default=42, show_default=True, help="NumPy/LightGBM seed for determinism.")
def main(head: str, out_dir: Path | None, seed: int) -> None:
    head = head.lower()
    if out_dir is None:
        default_version = "v2" if head == "pre" else "v1"
        out_dir = Path("artifacts") / f"pitch_outcome_{head}" / default_version
    out_dir.mkdir(parents=True, exist_ok=True)

    feature_order = PITCH_FEATURE_COLUMNS if head == "pre" else PITCH_FEATURE_COLUMNS_POST
    rng = np.random.default_rng(seed)

    # 1) deterministic miniature booster with the real I/O contract.
    frame = _synthetic_training_frame(feature_order, rng)
    labels = _synthetic_labels(frame, rng)
    booster = _train_miniature_booster(frame, labels, seed=seed)

    # 2) persist model.lgb + training_data.parquet so the real exporter's parity check runs as-is.
    booster.save_model(str(out_dir / "model.lgb"))
    snapshot = frame.copy()
    snapshot["label"] = labels
    snapshot.to_parquet(out_dir / "training_data.parquet", index=False)

    # 3) export the two-output serving graph via the REAL exporter (onnxmltools convert_lightgbm,
    #    zipmap=False, opset from the committed contract). Its raw ONNX-vs-booster parity check runs
    #    here as a bonus sanity gate.
    export_fn = export_pre if head == "pre" else export_post
    export_fn(model_dir=out_dir)

    # 4) per-class isotonic calibrator, fit on the booster's own probs vs the synthetic labels.
    raw_probs = np.asarray(booster.predict(frame), dtype=np.float64)
    calibrator = IsotonicCalibrator.fit(labels, raw_probs, class_labels=LABEL_CLASSES)
    calibrator.to_json(out_dir / "calibrator.json")

    # 5) Java-side lookups the feature pipeline needs at serving time.
    _write_park_id_mapping(out_dir / "park_id_mapping.json")
    _write_te_lookup(out_dir / "pitcher_te.json", "pitcher_id", PITCHER_IDS, rng)
    _write_te_lookup(out_dir / "batter_te.json", "batter_id", BATTER_IDS, rng)
    if head == "post":
        _write_pitch_type_mapping(out_dir / "pitch_type_mapping.json")

    click.echo(
        f"wrote miniature {head} artifacts to {out_dir} "
        f"(features={len(feature_order)} classes={len(LABEL_CLASSES)})"
    )


if __name__ == "__main__":
    main()

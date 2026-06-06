"""Train the pooled, park-agnostic logistic-regression baseline (rule 9 / decision [142]).

One ``Pipeline(StandardScaler, LogisticRegression)`` on the 15 shared features (no park_id),
plus 5 isotonic calibrators fit on a held-out season - the simplest honest floor the per-park
MLP / LGBM must beat.

**Memory-safe by construction.** The full (BIP x park) retrodicted fan-out is ~32M rows for
2015-2024; materializing it in one DataFrame (``lgbm_baseline.load_lgbm_dataset`` with no limit)
OOMs the WSL box. A baseline floor needs a representative sample, not all 32M rows, so this
trainer pulls a bounded ``per_park_limit`` rows from each park (via the chunked per-park loader)
and pools them - capping peak memory at one park's worth of rows at a time.

Runs on the desktop (ClickHouse lives there, ADR-0006):
    uv run python -m bullpen_training.battedball.lr_baseline.train \\
        --train-season-from 2015 --train-season-to 2024 --val-season 2025 \\
        --per-park-limit 50000 --out-dir artifacts/lr_baseline_batted_ball/v1
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from sklearn.isotonic import IsotonicRegression
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

from bullpen_training.battedball.features_shared import FEATURE_NAMES, OUTCOME_NAMES
from bullpen_training.battedball.lgbm_per_park.dataset import LABEL_COLUMN, load_park_lgbm_dataset
from bullpen_training.battedball.parks.loader import load_all_parks

DEFAULT_PER_PARK_LIMIT = 50_000


@dataclass
class LrBaselineBundle:
    pipeline: Pipeline
    calibrators: list[IsotonicRegression]
    feature_columns: tuple[str, ...]
    outcome_names: tuple[str, ...]
    park_order: tuple[str, ...]
    train_summary: dict[str, object]


def _load_pooled_sample(
    *, season_from: int, season_to: int, per_park_limit: int, container: str
) -> pd.DataFrame:
    """Bounded, per-park-balanced, pooled sample. Caps peak memory at one park's rows at a time
    (the per-park loader is chunked) instead of concatenating the full ~32M-row fan-out."""
    park_ids = sorted(load_all_parks().keys())
    frames: list[pd.DataFrame] = []
    for i, park in enumerate(park_ids, start=1):
        df_p = load_park_lgbm_dataset(
            park_id=park,
            season_from=season_from,
            season_to=season_to,
            limit=per_park_limit,
            container=container,
        )
        if not df_p.empty:
            # list-indexing returns a DataFrame at runtime; pd.DataFrame() pins it for the
            # type checker (pandas stubs widen df[[...]] to Series | DataFrame).
            frames.append(pd.DataFrame(df_p[[*FEATURE_NAMES, LABEL_COLUMN]]))
        print(
            f"  lr baseline sample: park {i}/{len(park_ids)} ({park}) "
            f"-> {sum(len(f) for f in frames)} rows so far...",
            flush=True,
        )
    if not frames:
        raise RuntimeError(f"no rows loaded for seasons {season_from}-{season_to}")
    return pd.concat(frames, ignore_index=True)


def train_lr_baseline(
    *,
    season_from: int,
    season_to: int,
    val_season: int,
    per_park_limit: int = DEFAULT_PER_PARK_LIMIT,
    max_iter: int = 1000,
    container: str = "bullpen-clickhouse",
) -> LrBaselineBundle:
    if season_to >= 2026 or val_season >= 2026:
        raise ValueError("rule 13: 2026 is holdout-only; train/val must be 2015-2025")

    train_df = _load_pooled_sample(
        season_from=season_from,
        season_to=season_to,
        per_park_limit=per_park_limit,
        container=container,
    )
    cal_df = _load_pooled_sample(
        season_from=val_season,
        season_to=val_season,
        per_park_limit=per_park_limit,
        container=container,
    )

    x_train = train_df[list(FEATURE_NAMES)].to_numpy(dtype=np.float64)
    y_train = train_df[LABEL_COLUMN].to_numpy(dtype=np.int64)
    pipeline = Pipeline(
        [("scale", StandardScaler()), ("lr", LogisticRegression(max_iter=max_iter))]
    ).fit(x_train, y_train)

    x_cal = cal_df[list(FEATURE_NAMES)].to_numpy(dtype=np.float64)
    y_cal = cal_df[LABEL_COLUMN].to_numpy(dtype=np.int64)
    raw_cal = np.asarray(pipeline.predict_proba(x_cal), dtype=np.float64)
    calibrators: list[IsotonicRegression] = []
    for c in range(len(OUTCOME_NAMES)):
        iso = IsotonicRegression(out_of_bounds="clip")
        iso.fit(raw_cal[:, c], (y_cal == c).astype(np.float64))
        calibrators.append(iso)

    return LrBaselineBundle(
        pipeline=pipeline,
        calibrators=calibrators,
        feature_columns=FEATURE_NAMES,
        outcome_names=OUTCOME_NAMES,
        park_order=tuple(sorted(load_all_parks().keys())),
        train_summary={
            "train_rows": len(train_df),
            "cal_rows": len(cal_df),
            "per_park_limit": per_park_limit,
            "train_seasons": f"{season_from}-{season_to}",
            "val_season": val_season,
        },
    )


def _calibrator_to_dict(iso: IsotonicRegression, outcome_name: str) -> dict:
    return {
        "outcome": outcome_name,
        "x_thresholds": iso.X_thresholds_.astype(float).tolist(),
        "y_thresholds": iso.y_thresholds_.astype(float).tolist(),
        "y_min": float(iso.y_min) if iso.y_min is not None else None,
        "y_max": float(iso.y_max) if iso.y_max is not None else None,
        "out_of_bounds": iso.out_of_bounds,
    }


def _calibrator_from_dict(d: dict) -> IsotonicRegression:
    iso = IsotonicRegression(
        out_of_bounds=d.get("out_of_bounds", "clip"), y_min=d.get("y_min"), y_max=d.get("y_max")
    )
    iso.X_thresholds_ = np.asarray(d["x_thresholds"], dtype=np.float64)
    iso.y_thresholds_ = np.asarray(d["y_thresholds"], dtype=np.float64)
    iso.X_min_ = float(iso.X_thresholds_.min()) if iso.X_thresholds_.size else 0.0
    iso.X_max_ = float(iso.X_thresholds_.max()) if iso.X_thresholds_.size else 1.0
    iso.increasing_ = True
    iso._build_f(iso.X_thresholds_, iso.y_thresholds_)
    return iso


def save_lr_baseline_bundle(bundle: LrBaselineBundle, out_dir: Path) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    joblib.dump(bundle.pipeline, out_dir / "pipeline.joblib")
    (out_dir / "calibrator.json").write_text(
        json.dumps(
            {
                "schema_version": 1,
                "model_name": "lr_baseline_batted_ball",
                "outcome_order": list(bundle.outcome_names),
                "classes": [
                    _calibrator_to_dict(iso, name)
                    for iso, name in zip(bundle.calibrators, bundle.outcome_names, strict=True)
                ],
            },
            indent=2,
        )
    )
    (out_dir / "metadata.json").write_text(
        json.dumps(
            {
                "schema_version": 1,
                "model_name": "lr_baseline_batted_ball",
                "model_version": "v1",
                "framework": "sklearn",
                "feature_columns": list(bundle.feature_columns),
                "outcome_names": list(bundle.outcome_names),
                "park_order": list(bundle.park_order),
                "train_summary": bundle.train_summary,
            },
            indent=2,
        )
    )


def load_lr_baseline_bundle(model_dir: Path) -> LrBaselineBundle:
    pipeline = joblib.load(model_dir / "pipeline.joblib")
    cal_payload = json.loads((model_dir / "calibrator.json").read_text())
    calibrators = [_calibrator_from_dict(c) for c in cal_payload["classes"]]
    md = json.loads((model_dir / "metadata.json").read_text())
    return LrBaselineBundle(
        pipeline=pipeline,
        calibrators=calibrators,
        feature_columns=tuple(md["feature_columns"]),
        outcome_names=tuple(md["outcome_names"]),
        park_order=tuple(md["park_order"]),
        train_summary=md.get("train_summary", {}),
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Train the pooled LR batted-ball baseline.")
    parser.add_argument("--train-season-from", type=int, default=2015)
    parser.add_argument("--train-season-to", type=int, default=2024)
    parser.add_argument("--val-season", type=int, default=2025)
    parser.add_argument(
        "--per-park-limit",
        type=int,
        default=DEFAULT_PER_PARK_LIMIT,
        help="Max rows sampled per park (caps memory; total ~= 30 x this).",
    )
    parser.add_argument(
        "--out-dir", type=Path, default=Path("artifacts/lr_baseline_batted_ball/v1")
    )
    args = parser.parse_args()

    bundle = train_lr_baseline(
        season_from=args.train_season_from,
        season_to=args.train_season_to,
        val_season=args.val_season,
        per_park_limit=args.per_park_limit,
    )
    save_lr_baseline_bundle(bundle, args.out_dir)
    print(
        f"wrote LR baseline to {args.out_dir} "
        f"(train {bundle.train_summary['train_rows']} rows, "
        f"cal {bundle.train_summary['cal_rows']} rows, "
        f"<= {args.per_park_limit}/park, {len(bundle.park_order)} parks)\n"
        "  NEXT: export to ONNX via lr_baseline.export_onnx, then register (rule-9 baseline)."
    )


if __name__ == "__main__":
    main()

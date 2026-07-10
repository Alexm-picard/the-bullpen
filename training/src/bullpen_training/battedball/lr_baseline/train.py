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
from bullpen_training.eval.calibration import fit_isotonic, isotonic_from_dict, isotonic_to_dict
from bullpen_training.eval.leakage_guards import refuse_holdout

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


def _ensure_all_classes(x: np.ndarray, y: np.ndarray) -> tuple[np.ndarray, np.ndarray, list[int]]:
    """Guarantee every outcome class is present in ``y`` so the LR emits a 5-wide predict_proba.

    sklearn LogisticRegression infers its class set from ``y``; the sampled argmax labels can
    miss an outcome (3b is essentially never the *dominant* outcome of a retrodicted
    distribution), which would make predict_proba fewer than 5 columns and break the [30, 5]
    contract. Inject one mean-feature anchor row per absent class (the LGBM gets the same effect
    from ``num_class=5``). One row per absent class against ~1M is negligible bias, and the absent
    class then calibrates to ~0. Returns ``(x, y, absent_indices)`` for the diagnostic.
    """
    present = {int(v) for v in np.unique(y)}
    absent = [c for c in range(len(OUTCOME_NAMES)) if c not in present]
    if absent:
        anchors = np.tile(x.mean(axis=0, keepdims=True), (len(absent), 1))
        x = np.vstack([x, anchors])
        y = np.concatenate([y, np.asarray(absent, dtype=y.dtype)])
    return x, y, absent


def train_lr_baseline(
    *,
    season_from: int,
    season_to: int,
    val_season: int,
    per_park_limit: int = DEFAULT_PER_PARK_LIMIT,
    max_iter: int = 1000,
    container: str = "bullpen-clickhouse",
) -> LrBaselineBundle:
    # Unified onto the shared [170] guard (#188): one HOLDOUT_YEAR source of truth, one
    # LeakageError shape, instead of this trainer's former inline 2026 literal + ValueError.
    refuse_holdout(season_from=season_from, season_to=season_to, val_season=val_season)

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
    x_train, y_train, absent = _ensure_all_classes(x_train, y_train)
    if absent:
        print(
            "  note: outcome classes absent from sampled labels: "
            f"{[OUTCOME_NAMES[c] for c in absent]} -> injected anchor rows so the 5-class LR "
            "emits all outcomes (absent class calibrates to ~0).",
            flush=True,
        )
    pipeline = Pipeline(
        [("scale", StandardScaler()), ("lr", LogisticRegression(max_iter=max_iter))]
    ).fit(x_train, y_train)

    x_cal = cal_df[list(FEATURE_NAMES)].to_numpy(dtype=np.float64)
    y_cal = cal_df[LABEL_COLUMN].to_numpy(dtype=np.int64)
    raw_cal = np.asarray(pipeline.predict_proba(x_cal), dtype=np.float64)
    calibrators: list[IsotonicRegression] = []
    for c in range(len(OUTCOME_NAMES)):
        # NB: sklearn-default y_min/y_max (no unit clamp) - the LR baseline's
        # historical fit; do not "align" it with the MLP/LGBM y_min=0/y_max=1.
        calibrators.append(fit_isotonic(raw_cal[:, c], (y_cal == c).astype(np.float64)))

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
            "absent_outcome_classes": [OUTCOME_NAMES[c] for c in absent],
        },
    )


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
                    isotonic_to_dict(iso, name)
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
    calibrators = [isotonic_from_dict(c) for c in cal_payload["classes"]]
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

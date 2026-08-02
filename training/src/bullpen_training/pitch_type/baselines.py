"""Native drift-baseline emission for pitch_type_pre (the E-1 part 2 pattern, both blocks).

The battedball trainer emits ``feature_distributions`` natively and leaves
``training_prediction_distribution`` to the backfill CLI because its prediction block needs
per-row park identity plus a served-inference chain the trainer does not hold. This trainer
HOLDS the served chain (``ModelBundle.predict_proba`` is LightGBM + the temperature calibrator,
exactly what serving applies after ONNX), so BOTH blocks are emitted natively and every produced
bundle is PSI-ready at registration. Without this, promoting pitch_type_pre fires
``DriftBaselineMissing`` on the first nightly PSI pass - drift is dark on a production model.

TRAIN SLICE ONLY, like the battedball precedent: the reference is the distribution the model was
fit on. The bundle's ``training_data.parquet`` snapshot is the TEST slice and must never feed
this - a reference built from the held-out year would make the PSI comparison "live vs 2025"
instead of "live vs training", quietly re-aiming what drift means.

The prediction pass is capped by EVENLY-SPACED row selection (``np.linspace`` over positional
indices), never by random sampling: determinism keeps re-runs reproducible, and this module
stays a million miles from anything resembling a random split (hard project rule). The cap only
bounds the served-inference cost; the feature block always reads the full frame.
"""

from __future__ import annotations

from collections.abc import Callable
from typing import TYPE_CHECKING, Any

import numpy as np

if TYPE_CHECKING:
    import pandas as pd

# Bounds the LightGBM+calibrator pass over the ~5M-row fold-4 train slice on the box. The block
# itself is a <= DEFAULT_MAX_SAMPLE quantile-representative sample per class, so anything in this
# order of magnitude is statistically indistinguishable; the cap is a cost knob, not a quality one.
PREDICTION_ROW_CAP = 250_000


def train_slice_baselines(
    train_df: pd.DataFrame,
    predict_proba: Callable[[pd.DataFrame], np.ndarray],
    *,
    train_window: str,
    prediction_row_cap: int = PREDICTION_ROW_CAP,
) -> dict[str, Any]:
    """Both baseline blocks from the TRAIN slice, as metadata extras.

    ``predict_proba`` must be the SERVED chain (calibrated probabilities), because the observed
    side of the PSI comparison logs served predictions - a raw-margin reference would make the
    calibrator itself read as drift.

    Returns the extras dict ``persist`` merges into metadata.json: the two blocks, plus
    ``train_split_seasons`` (the key the backfill CLI's overwrite warning reads) and a provenance
    stamp saying how the prediction rows were selected.
    """
    from bullpen_training.registry_client.distributions import (
        CHAMPIONS,
        build_feature_block,
        compute_prediction_distribution,
    )

    cfg = CHAMPIONS["pitch_type_pre"]
    # Fails loud on a missing source column - a renamed store column must not silently shrink
    # the watched surface (the empty-drift failure mode the CHAMPIONS key test exists for).
    feature_block = build_feature_block(train_df, cfg, max_sample=5000)

    n = len(train_df)
    if n == 0:
        raise ValueError("train slice is empty - refusing to emit an empty drift baseline")
    if n > prediction_row_cap:
        idx = np.linspace(0, n - 1, prediction_row_cap).astype(np.int64)
        pred_frame = train_df.iloc[idx]
    else:
        pred_frame = train_df
    proba = predict_proba(pred_frame)
    prediction_block = compute_prediction_distribution(proba, list(cfg.class_labels))

    return {
        "feature_distributions": feature_block,
        "training_prediction_distribution": prediction_block,
        "train_split_seasons": train_window,
        "baseline_provenance": {
            "source": "native_trainer_emission",
            "slice": "train",
            "prediction_rows": len(pred_frame),
            "prediction_row_cap": int(prediction_row_cap),
            "prediction_row_selection": "evenly-spaced positional (np.linspace), deterministic",
        },
    }

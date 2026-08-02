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

The prediction pass is capped by EVENLY-SPACED positional selection (``np.linspace``), never by
random sampling: determinism keeps re-runs reproducible, and this module stays a million miles
from anything resembling a random split (hard project rule). Because the loader ORDERs BY
game_date, the evenly-spaced stride is also TEMPORALLY UNIFORM across the whole 2015-2023
window - a head slice would bake one era's pitch mix into the reference and read the league's
own evolution as permanent drift. The cap only bounds the served-inference cost; the feature
block always reads the full frame.

The prediction reference is IN-SAMPLE (the model's predictions over rows it was fit on), so live
out-of-sample predictions are structurally flatter and prediction-PSI carries a small permanent
non-drift offset - consistent with the battedball precedent, and stamped in provenance so the
reader of the first PSI alert knows.
"""

from __future__ import annotations

from collections.abc import Callable
from typing import TYPE_CHECKING, Any

import numpy as np

from bullpen_training.registry_client.distributions import DEFAULT_MAX_SAMPLE

if TYPE_CHECKING:
    import pandas as pd

# Bounds the LightGBM+calibrator pass over the ~5M-row fold-4 train slice on the box. The block
# itself is a <= max_sample quantile-representative sample per class, so anything in this order
# of magnitude is statistically indistinguishable; the cap is a cost knob, not a quality one.
PREDICTION_ROW_CAP = 250_000


def cap_rows(frame: pd.DataFrame, cap: int) -> pd.DataFrame:
    """Evenly-spaced positional row cap - deterministic, temporally uniform on a date-ordered
    frame, and shared by the native path and the backfill CLI so both select identical rows."""
    n = len(frame)
    if n <= cap:
        return frame
    idx = np.linspace(0, n - 1, cap).astype(np.int64)
    return frame.iloc[idx]


def train_slice_baselines(
    train_df: pd.DataFrame,
    predict_proba: Callable[[pd.DataFrame], np.ndarray],
    *,
    train_window: str,
    prediction_row_cap: int = PREDICTION_ROW_CAP,
    max_sample: int = DEFAULT_MAX_SAMPLE,
) -> dict[str, Any]:
    """Both baseline blocks from the TRAIN slice, as metadata extras.

    ``predict_proba`` must be the SERVED chain (calibrated probabilities), because the observed
    side of the PSI comparison logs served predictions - a raw-margin reference would make the
    calibrator itself read as drift.

    Returns the extras dict ``persist`` merges into metadata.json: the two blocks, plus
    ``train_split_seasons`` (the key the backfill CLI's overwrite warning reads) and a provenance
    stamp saying how the rows and predictions were produced.
    """
    from bullpen_training.registry_client.distributions import (
        CHAMPIONS,
        emit_distribution_blocks,
    )

    if len(train_df) == 0:
        raise ValueError("train slice is empty - refusing to emit an empty drift baseline")

    cfg = CHAMPIONS["pitch_type_pre"]
    pred_frame = cap_rows(train_df, prediction_row_cap)
    proba = predict_proba(pred_frame)
    # The ONE shared entry point (its docstring's promise, now true): the feature block reads the
    # FULL frame and fails loud on a missing source column - a renamed store column must not
    # silently shrink the watched surface - and the prediction block reads the capped proba.
    feature_block, prediction_block = emit_distribution_blocks(
        train_df, cfg, proba, max_sample=max_sample
    )

    return {
        "feature_distributions": feature_block,
        "training_prediction_distribution": prediction_block,
        "train_split_seasons": train_window,
        "baseline_provenance": {
            "source": "native_trainer_emission",
            "slice": "train",
            "predictions": "in-sample",
            "prediction_rows": int(len(pred_frame)),
            "prediction_row_cap": int(prediction_row_cap),
            "prediction_row_selection": "evenly-spaced positional (np.linspace), deterministic",
            "max_sample": int(max_sample),
        },
    }

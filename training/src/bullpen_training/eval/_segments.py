"""Per-segment Brier / log-loss / accuracy slicing (Phase 2a.7).

`segment_metrics` returns one row per (segment_col, bucket) with the
fold's primary metrics computed on that slice. The Ops dashboard
(Phase 4e) reads these to show where the model under-performs.
"""

from __future__ import annotations

from collections.abc import Iterable
from typing import cast

import numpy as np
import pandas as pd

from bullpen_training.eval.metrics import multiclass_brier, multiclass_log_loss


def segment_metrics(
    test_df: pd.DataFrame,
    y_pred_proba: np.ndarray,
    *,
    segment_cols: Iterable[str],
    min_rows_per_bucket: int = 100,
) -> pd.DataFrame:
    """For each (segment_col, bucket) with ≥ min_rows pitches, compute
    Brier, log-loss, and accuracy. Empty / sparse buckets are skipped."""
    if "label" not in test_df.columns:
        raise ValueError("test_df must include integer 'label' column")
    if len(test_df) != y_pred_proba.shape[0]:
        raise ValueError(
            f"test_df has {len(test_df)} rows; y_pred_proba has {y_pred_proba.shape[0]}"
        )
    y_true = np.asarray(test_df["label"], dtype=np.int64)
    predicted_class = np.asarray(y_pred_proba.argmax(axis=1), dtype=np.int64)

    rows: list[dict[str, object]] = []
    for col in segment_cols:
        if col not in test_df.columns:
            continue
        series = cast(pd.Series, test_df[col])
        for bucket, mask_series in series.groupby(series).groups.items():
            idx = np.asarray(mask_series)
            if len(idx) < min_rows_per_bucket:
                continue
            y_t = y_true[idx]
            y_p = y_pred_proba[idx]
            accuracy = float((predicted_class[idx] == y_t).mean())
            rows.append(
                {
                    "segment_col": col,
                    "bucket": str(bucket),
                    "n": len(idx),
                    "brier": float(multiclass_brier(y_t, y_p)),
                    "log_loss": float(multiclass_log_loss(y_t, y_p)),
                    "accuracy": accuracy,
                }
            )
    out = pd.DataFrame(rows)
    return (
        out.sort_values(["segment_col", "bucket"]).reset_index(drop=True) if not out.empty else out
    )

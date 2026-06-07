"""Shared, memory-careful LightGBM Dataset builder for the pitch heads (CV-MEM-1).

Both `train_pre.model_factory` and `train_post.model_factory` used to build
`lgb.Dataset(df[feat_cols], label=df["label"])` for the train and val frames and
let `lgb.train` construct them lazily. That left BOTH column sub-copies
(`train_df[feat_cols]` + `val_df[feat_cols]`) alive simultaneously, on top of the
caller's full-fold train/val/test frames, until training began - the spike that
OOM-aborted CV fold 3 (and would be worse on the wider post head's 41 columns).

This helper bins EAGERLY (`construct()`) and drops the raw (`free_raw_data=True`,
already the LightGBM default), so each sub-copy is binned and released BEFORE the
next Dataset is built and before tree-building. Calling it for train then val
(val referencing train) means the two sub-copies never coexist.

It deliberately keeps the pandas -> Dataset path and does NOT pre-cast to float32:
LightGBM computes its bin boundaries from the values it is handed, so a float64 ->
float32 pre-cast can move a boundary and, under `deterministic=True`, change the
model. Eager `construct()` bins the SAME float64 values lazy construction would, so
this is memory-only and the resulting model is byte-identical to the prior path.
"""

from __future__ import annotations

from collections.abc import Sequence

import lightgbm as lgb
import pandas as pd


def build_lgb_dataset(
    df: pd.DataFrame,
    feat_cols: Sequence[str],
    *,
    reference: lgb.Dataset | None = None,
) -> lgb.Dataset:
    """Build a constructed LightGBM Dataset, freeing the raw sub-copy after binning.

    Args:
        df: the per-fold frame; must carry the feature columns + a ``label`` column.
        feat_cols: the feature columns to bin (the label is taken from ``df["label"]``).
        reference: the train Dataset whose bin mappers a val/test Dataset must reuse;
            it must already be constructed (pass the train Dataset returned by an
            earlier call). ``None`` for the train Dataset itself.

    Returns:
        A constructed ``lgb.Dataset`` (raw data already released).
    """
    ds = lgb.Dataset(
        df[list(feat_cols)],
        label=df["label"],
        reference=reference,
        free_raw_data=True,
    )
    # Bin now: with free_raw_data=True this releases the df[feat_cols] sub-copy
    # before the caller builds the next Dataset / starts tree-building.
    ds.construct()
    return ds

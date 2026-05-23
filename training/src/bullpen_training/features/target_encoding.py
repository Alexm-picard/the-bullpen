"""Leakage-safe Bayesian-smoothed target encoding (Phase 2a.1).

Per decision [39]: pitcher_id and batter_id are encoded as their
class-conditional outcome distributions, smoothed toward a global prior
so cold-start IDs get a non-degenerate value. The encoding is computed
from a **training window** that strictly precedes the rows being encoded
(no future contamination — caught by the leakage tests in 2a.3).

Encoding for entity e and class c:

    te_c(e) = (n_c(e) + k * prior_c) / (n(e) + k)

where:
    n(e)     = pitches by/against e in the training window
    n_c(e)   = pitches by/against e where label = c
    prior_c  = global rate of class c in the training window
    k        = smoothing weight (rows-equivalent of prior)

Unseen entities at apply time get the prior vector directly.
"""

from __future__ import annotations

import json
from collections.abc import Iterable
from pathlib import Path
from typing import Any, cast

import pandas as pd

from bullpen_training.features import LABEL_CLASSES

DEFAULT_SMOOTHING_K = 20.0


def _validate_inputs(
    df: pd.DataFrame, entity_col: str, label_col: str, label_classes: Iterable[str]
) -> None:
    missing = {entity_col, label_col} - set(df.columns)
    if missing:
        raise ValueError(f"missing columns: {missing}")
    unknown_labels = set(df[label_col].unique()) - set(label_classes)
    if unknown_labels:
        raise ValueError(
            f"unexpected labels in {label_col}: {unknown_labels}; "
            f"filter to LABEL_CLASSES before computing TE"
        )


def compute_prior(df: pd.DataFrame, label_col: str) -> dict[str, float]:
    """Marginal class probabilities — fallback for unseen entities."""
    counts = cast(pd.Series, df[label_col].value_counts(normalize=True))
    return {cls: float(cast(float, counts.get(cls, 0.0))) for cls in LABEL_CLASSES}


def compute_te(
    df: pd.DataFrame,
    *,
    entity_col: str,
    label_col: str = "label",
    smoothing_k: float = DEFAULT_SMOOTHING_K,
    label_classes: Iterable[str] = LABEL_CLASSES,
) -> pd.DataFrame:
    """Return a DataFrame keyed by entity_col with one TE column per class.

    Caller is responsible for the temporal cutoff (pass a DataFrame already
    filtered to rows the entity should "have seen" up to as_of_date).
    """
    classes = list(label_classes)
    _validate_inputs(df, entity_col, label_col, classes)
    prior = compute_prior(df, label_col)

    pivoted = cast(
        pd.DataFrame,
        df.groupby([entity_col, label_col]).size().unstack(fill_value=0),
    ).reindex(columns=classes, fill_value=0)
    total = pivoted.sum(axis=1)

    out = pd.DataFrame(index=pivoted.index)
    for cls in classes:
        out[f"te_{cls}"] = (
            (pivoted[cls] + smoothing_k * prior[cls]) / (total + smoothing_k)
        ).astype("float32")
    out = out.reset_index()
    out.attrs["prior"] = prior
    out.attrs["smoothing_k"] = float(smoothing_k)
    return out


def apply_te(
    features_df: pd.DataFrame,
    encoding_df: pd.DataFrame,
    *,
    entity_col: str,
    column_prefix: str,
    prior: dict[str, float] | None = None,
    label_classes: Iterable[str] = LABEL_CLASSES,
) -> pd.DataFrame:
    """Left-join encoding_df onto features_df keyed by entity_col.

    Unseen entities get `prior` (or, if not supplied, the prior stashed in
    encoding_df.attrs["prior"] by `compute_te`).
    """
    classes = list(label_classes)
    eff_prior = prior or cast(dict[str, float], encoding_df.attrs.get("prior"))
    if eff_prior is None:
        raise ValueError(
            "prior not provided and encoding_df has no .attrs['prior']; "
            "pass prior= explicitly or supply an encoding_df from compute_te"
        )
    rename_map = {f"te_{cls}": f"{column_prefix}_te_{cls}" for cls in classes}
    out = features_df.merge(encoding_df.rename(columns=rename_map), on=entity_col, how="left")
    for cls in classes:
        col = f"{column_prefix}_te_{cls}"
        out[col] = out[col].astype("float64").fillna(eff_prior[cls]).astype("float32")
    return out


def save_encoding(encoding_df: pd.DataFrame, path: Path, *, entity_col: str) -> dict[str, Any]:
    """Persist as deterministic JSON so re-runs hash-equal and the Java
    side (Phase 2a.8) can pin a particular encoding by file hash."""
    payload = {
        "entity_col": entity_col,
        "prior": encoding_df.attrs.get("prior"),
        "smoothing_k": encoding_df.attrs.get("smoothing_k"),
        "rows": [
            {
                entity_col: int(cast(Any, row[entity_col])),
                **{c: float(cast(Any, row[c])) for c in encoding_df.columns if c != entity_col},
            }
            for _, row in encoding_df.sort_values(entity_col).iterrows()
        ],
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, sort_keys=False) + "\n")
    return payload


def load_encoding(path: Path) -> tuple[pd.DataFrame, dict[str, float]]:
    """Inverse of save_encoding. Returns (encoding_df, prior)."""
    raw = json.loads(path.read_text())
    df = pd.DataFrame(raw["rows"])
    df.attrs["prior"] = raw["prior"]
    df.attrs["smoothing_k"] = raw.get("smoothing_k", DEFAULT_SMOOTHING_K)
    return df, raw["prior"]

"""Head-to-head metrics: MLP vs LightGBM (Phase 2c.9).

Per-park Brier + ECE + argmax-confusion. The MLP path produces a
(N, n_parks, 5) tensor (one distribution per park per BIP); the LGBM
path produces a (N, 5) tensor per (BIP, park) row — both shapes lower
into the same per-park metric grid here.

Pure-numpy, no torch / no lightgbm — keeps the comparison module
loadable from either CI pass without dragging the heavy frameworks in.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import asdict, dataclass, field
from typing import Final

import numpy as np

from bullpen_training.battedball.features_shared import OUTCOME_NAMES
from bullpen_training.battedball.mlp.calibration import expected_calibration_error

DEFAULT_N_BINS: Final[int] = 15


# --- dataclasses ----------------------------------------------------------


@dataclass(frozen=True)
class ParkMetrics:
    """Per-park metrics, one row per park x model."""

    park_id: str
    model: str  # 'mlp' or 'lgbm'
    n_samples: int
    brier: float  # mean multi-class Brier score (lower better)
    ece: float  # mean ECE across the 5 classes (lower better)
    accuracy: float  # argmax accuracy vs argmax label
    confusion: list[list[int]]  # 5x5 confusion matrix [true][pred]


@dataclass(frozen=True)
class ClassMetrics:
    """Per-outcome precision / recall / F1 / support, derived from a 5x5
    ``[true][pred]`` confusion matrix.

    precision = TP / (TP + FP) = diag / column-sum (over predicted == this class)
    recall    = TP / (TP + FN) = diag / row-sum    (over true == this class)
    f1        = harmonic mean of precision + recall
    support   = row-sum (how many rows truly belong to this class)
    All are zero-guarded: a class never predicted (column-sum 0) gets
    precision 0; a class never observed (row-sum 0) gets recall 0; F1 is 0
    whenever precision + recall is 0.
    """

    outcome: str
    precision: float
    recall: float
    f1: float
    support: int


@dataclass(frozen=True)
class AggregateMetrics:
    """Aggregate (mean across parks) metrics for one model."""

    model: str
    mean_brier: float
    mean_ece: float
    mean_accuracy: float


@dataclass(frozen=True)
class ComparisonReport:
    park_order: tuple[str, ...]
    outcome_order: tuple[str, ...]
    per_park: list[ParkMetrics] = field(default_factory=list)
    aggregate: dict[str, AggregateMetrics] = field(default_factory=dict)
    prefer_for_production: str = "mlp"  # tiebreak default: simpler model is LGBM
    rationale: list[str] = field(default_factory=list)


# --- metric kernels -------------------------------------------------------


def _multiclass_brier(pred_probs: np.ndarray, labels_onehot: np.ndarray) -> float:
    """Mean Brier across rows. pred_probs / labels_onehot: (N, n_classes)."""
    if pred_probs.shape != labels_onehot.shape:
        raise ValueError(f"shape mismatch: pred {pred_probs.shape} vs label {labels_onehot.shape}")
    diff = pred_probs - labels_onehot
    return float((diff * diff).sum(axis=-1).mean())


def _confusion(pred_argmax: np.ndarray, true_argmax: np.ndarray, n_classes: int) -> list[list[int]]:
    matrix = np.zeros((n_classes, n_classes), dtype=np.int64)
    for t, p in zip(true_argmax, pred_argmax, strict=True):
        matrix[int(t), int(p)] += 1
    return matrix.tolist()


def _onehot(labels: np.ndarray, n_classes: int) -> np.ndarray:
    """labels can be either int 0..n-1 indices or full distributions (N, n)."""
    if labels.ndim == 2:
        return labels.astype(np.float64)
    out = np.zeros((labels.shape[0], n_classes), dtype=np.float64)
    out[np.arange(labels.shape[0]), labels.astype(np.int64)] = 1.0
    return out


def class_precision_recall(
    confusion: list[list[int]],
    outcome_order: Sequence[str],
) -> list[ClassMetrics]:
    """Derive per-class precision / recall / F1 / support from a square
    ``[true][pred]`` confusion matrix.

    ``confusion[t][p]`` is the count of rows whose true class is ``t`` and whose
    predicted (argmax) class is ``p``. For class ``c``:

      - TP        = confusion[c][c]
      - row-sum   = sum_p confusion[c][p]  (all rows truly class c -> support + FN base)
      - col-sum   = sum_t confusion[t][c]  (all rows predicted class c -> TP + FP base)
      - precision = TP / col-sum   (0 if col-sum == 0; class never predicted)
      - recall    = TP / row-sum   (0 if row-sum == 0; class never observed)
      - f1        = 2*P*R / (P+R)  (0 if P+R == 0)
      - support   = row-sum

    One :class:`ClassMetrics` per entry in ``outcome_order`` (which must match
    the matrix dimension). Pure / zero-guarded - no division-by-zero, no NaN.
    """
    n = len(outcome_order)
    if len(confusion) != n or any(len(row) != n for row in confusion):
        raise ValueError(
            f"confusion must be {n}x{n} to match outcome_order; got "
            f"{len(confusion)} rows of widths {[len(r) for r in confusion]}"
        )
    matrix = np.asarray(confusion, dtype=np.float64)
    row_sums = matrix.sum(axis=1)  # true totals per class (support)
    col_sums = matrix.sum(axis=0)  # predicted totals per class
    out: list[ClassMetrics] = []
    for c, name in enumerate(outcome_order):
        tp = float(matrix[c, c])
        precision = float(tp / col_sums[c]) if col_sums[c] > 0 else 0.0
        recall = float(tp / row_sums[c]) if row_sums[c] > 0 else 0.0
        denom = precision + recall
        f1 = float(2.0 * precision * recall / denom) if denom > 0 else 0.0
        out.append(
            ClassMetrics(
                outcome=name,
                precision=precision,
                recall=recall,
                f1=f1,
                support=int(row_sums[c]),
            )
        )
    return out


# --- per-park computation -------------------------------------------------


def per_park_metrics(
    *,
    pred_probs: np.ndarray,
    label_distributions: np.ndarray,
    park_ids: Sequence[str],
    park_order: Sequence[str] | None = None,
    n_bins: int = DEFAULT_N_BINS,
    model: str,
) -> list[ParkMetrics]:
    """Compute per-park metrics from row-wise predictions + labels.

    Inputs are flat (one row per (BIP, park) pair):
      - pred_probs: (N, n_outcomes) — model's row prediction
      - label_distributions: (N, n_outcomes) — retrodicted distribution
        for the row (NOT the argmax label)
      - park_ids: (N,) — park each row belongs to

    Iterates parks in ``park_order`` (or sorted-unique if None) and
    returns one :class:`ParkMetrics` per park. Empty parks raise.
    """
    if pred_probs.shape != label_distributions.shape:
        raise ValueError(
            f"shape mismatch: pred {pred_probs.shape} vs label {label_distributions.shape}"
        )
    if len(park_ids) != pred_probs.shape[0]:
        raise ValueError(f"park_ids length {len(park_ids)} != n_rows {pred_probs.shape[0]}")
    n_outcomes = pred_probs.shape[1]
    park_array = np.asarray(park_ids)
    if park_order is None:
        park_order = sorted(set(park_array.tolist()))

    out: list[ParkMetrics] = []
    for pid in park_order:
        mask = park_array == pid
        n = int(mask.sum())
        if n == 0:
            raise ValueError(f"park '{pid}' has no rows")
        p = pred_probs[mask]
        y = label_distributions[mask]
        # Argmax of the label distribution is the supervised target for
        # accuracy + confusion; the full distribution is used for Brier
        # + ECE (per the 2c.6 convention).
        y_argmax = y.argmax(axis=-1)
        p_argmax = p.argmax(axis=-1)
        accuracy = float((p_argmax == y_argmax).mean())
        brier = _multiclass_brier(p, _onehot(y_argmax, n_outcomes))
        # ECE per class, averaged.
        ece_per_class = [
            expected_calibration_error(p[:, c], y[:, c], n_bins=n_bins) for c in range(n_outcomes)
        ]
        ece = float(np.mean(ece_per_class))
        out.append(
            ParkMetrics(
                park_id=pid,
                model=model,
                n_samples=n,
                brier=brier,
                ece=ece,
                accuracy=accuracy,
                confusion=_confusion(p_argmax, y_argmax, n_outcomes),
            )
        )
    return out


# --- comparison -----------------------------------------------------------


def compare_models(
    *,
    mlp_pred_probs: np.ndarray,
    lgbm_pred_probs: np.ndarray,
    label_distributions: np.ndarray,
    park_ids: Sequence[str],
    park_order: Sequence[str] | None = None,
    outcome_order: Sequence[str] = OUTCOME_NAMES,
    n_bins: int = DEFAULT_N_BINS,
) -> ComparisonReport:
    """Build the per-park + aggregate side-by-side comparison report.

    Both ``mlp_pred_probs`` and ``lgbm_pred_probs`` must be (N, n_outcomes)
    aligned with ``label_distributions`` and ``park_ids`` row-for-row.
    Callers responsible for flattening any (N, n_parks, n_outcomes)
    tensors before calling.
    """
    if mlp_pred_probs.shape != lgbm_pred_probs.shape:
        raise ValueError(
            f"MLP/LGBM shape mismatch: mlp {mlp_pred_probs.shape} vs lgbm {lgbm_pred_probs.shape}"
        )
    park_order_t = tuple(park_order) if park_order is not None else tuple(sorted(set(park_ids)))
    mlp_per_park = per_park_metrics(
        pred_probs=mlp_pred_probs,
        label_distributions=label_distributions,
        park_ids=park_ids,
        park_order=park_order_t,
        n_bins=n_bins,
        model="mlp",
    )
    lgbm_per_park = per_park_metrics(
        pred_probs=lgbm_pred_probs,
        label_distributions=label_distributions,
        park_ids=park_ids,
        park_order=park_order_t,
        n_bins=n_bins,
        model="lgbm",
    )
    per_park = list(mlp_per_park) + list(lgbm_per_park)

    aggregate = {
        "mlp": AggregateMetrics(
            model="mlp",
            mean_brier=float(np.mean([m.brier for m in mlp_per_park])),
            mean_ece=float(np.mean([m.ece for m in mlp_per_park])),
            mean_accuracy=float(np.mean([m.accuracy for m in mlp_per_park])),
        ),
        "lgbm": AggregateMetrics(
            model="lgbm",
            mean_brier=float(np.mean([m.brier for m in lgbm_per_park])),
            mean_ece=float(np.mean([m.ece for m in lgbm_per_park])),
            mean_accuracy=float(np.mean([m.accuracy for m in lgbm_per_park])),
        ),
    }
    winner, rationale = decide_winner(aggregate["mlp"], aggregate["lgbm"])
    return ComparisonReport(
        park_order=park_order_t,
        outcome_order=tuple(outcome_order),
        per_park=per_park,
        aggregate=aggregate,
        prefer_for_production=winner,
        rationale=rationale,
    )


def decide_winner(
    mlp: AggregateMetrics,
    lgbm: AggregateMetrics,
    *,
    brier_tolerance: float = 1e-4,
) -> tuple[str, list[str]]:
    """Pick the production champion by Brier first, ECE tiebreak.

    Returns (winner, rationale[]). Rationale is a list of plain-English
    one-liners suitable for the HTML report + the decisions.md entry
    that needs to be written if LGBM wins (reversing decision [45]).

    Tie-break ladder (from the leaf):
      1. Lower mean Brier wins.
      2. Within ``brier_tolerance`` -> lower mean ECE wins.
      3. Still tied -> prefer the simpler model (LGBM).
    """
    rationale: list[str] = []
    brier_gap = mlp.mean_brier - lgbm.mean_brier
    rationale.append(
        f"Brier (lower is better): mlp={mlp.mean_brier:.4f} vs lgbm={lgbm.mean_brier:.4f} "
        f"(gap {brier_gap:+.4f})"
    )
    if abs(brier_gap) > brier_tolerance:
        winner = "mlp" if brier_gap < 0 else "lgbm"
        rationale.append(f"Brier gap exceeds tolerance {brier_tolerance} — winner: {winner}.")
        return winner, rationale

    ece_gap = mlp.mean_ece - lgbm.mean_ece
    rationale.append(
        f"Brier within tolerance; ECE tiebreak: mlp={mlp.mean_ece:.4f} vs "
        f"lgbm={lgbm.mean_ece:.4f} (gap {ece_gap:+.4f})"
    )
    if ece_gap < 0:
        rationale.append("ECE favours MLP — winner: mlp.")
        return "mlp", rationale
    if ece_gap > 0:
        rationale.append("ECE favours LGBM — winner: lgbm.")
        return "lgbm", rationale
    rationale.append("Brier + ECE tied; preferring simpler model (LGBM).")
    return "lgbm", rationale


# --- serialisation helpers ----------------------------------------------


def report_to_dict(report: ComparisonReport) -> dict:
    """Render a ComparisonReport to a JSON-safe dict (the registry contract
    boundary; mirror the same structure on the Java review side)."""
    return {
        "schema_version": 1,
        "artifact_name": "batted_ball_comparison",
        "artifact_version": "v1",
        "park_order": list(report.park_order),
        "outcome_order": list(report.outcome_order),
        "per_park": [asdict(p) for p in report.per_park],
        "aggregate": {k: asdict(v) for k, v in report.aggregate.items()},
        "prefer_for_production": report.prefer_for_production,
        "rationale": list(report.rationale),
    }


def report_from_dict(d: dict) -> ComparisonReport:
    if d.get("schema_version") != 1:
        raise ValueError(f"unknown comparison schema_version: {d.get('schema_version')}")
    return ComparisonReport(
        park_order=tuple(d["park_order"]),
        outcome_order=tuple(d["outcome_order"]),
        per_park=[ParkMetrics(**p) for p in d["per_park"]],
        aggregate={k: AggregateMetrics(**v) for k, v in d["aggregate"].items()},
        prefer_for_production=d["prefer_for_production"],
        rationale=list(d.get("rationale", [])),
    )


__all__ = (
    "DEFAULT_N_BINS",
    "AggregateMetrics",
    "ClassMetrics",
    "ComparisonReport",
    "ParkMetrics",
    "class_precision_recall",
    "compare_models",
    "decide_winner",
    "per_park_metrics",
    "report_from_dict",
    "report_to_dict",
)

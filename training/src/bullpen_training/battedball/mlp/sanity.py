"""Cross-park sanity check for the multi-output MLP (Phase 2c.7).

Decision [52] makes this a HARD GATE: the model's per-park P(HR) for a
canonical batted ball must rank approximately the same as published
park HR factors. If the Spearman rank correlation against the
published factors is below the threshold, the model is broken and
must NOT be registered — likely culprits are bad retrodiction labels,
an MLP that hasn't trained long enough, or a wrong canonical-input
encoding.

Inputs / convention:

- Canonical launch: 110 mph / 28 deg / 0 spray / 2000 rpm backspin
  (default `LaunchParams`). The MLP gets these features after the
  fitted ``FeatureScaler`` is applied — same code path serving uses.
- Published factors live in ``training/data/published_hr_factors.json``
  (decision [52] points to a single source per release; default 2024
  Baseball Savant single-season).
- Output: a :class:`SanityReport` carrying the per-park predicted vs
  published vector, Spearman rho, gap diagnostics, and a boolean
  `gate_passes` flag.

The gates (matching the leaf):

  - Spearman rho between predicted P(HR) and published HR factor > 0.8
  - P(HR) at COL exceeds P(HR) at OAK (now ATH) by >= 0.05 absolute

The OAK/ATH gap is the headline "the model knows altitude matters"
check. We also surface a top-3 list of per-park gaps so a failure
message can name the offending parks (the leaf's "actionable error
message" criterion).
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Final

import numpy as np
import torch
import torch.nn.functional as F
from scipy.stats import spearmanr

from bullpen_training.battedball.mlp.architecture import BattedBallMLP
from bullpen_training.battedball.mlp.calibration import ParkCalibrators, transform
from bullpen_training.battedball.mlp.dataset import (
    FEATURE_NAMES,
    FeatureScaler,
    base_state_one_hot,
    stand_one_hot,
)

# Gates (decision [52]).
SPEARMAN_GATE: Final[float] = 0.80
COORS_VS_OAKLAND_GAP_GATE: Final[float] = 0.05

# Representative barrel inputs that cover different spray angles so the
# sanity test captures asymmetric fence profiles (e.g. NYY's short left
# field porch at -30°, SF's deep right-center at +25°). A single dead-CF
# input misses most of the park-factor signal because center-field
# distances are similar across parks (~390-415 ft).
CANONICAL_INPUTS: Final[list[dict[str, float | str | int]]] = [
    {"speed": 110.0, "angle": 28.0, "spray": -35.0, "dist": 380.0, "stand": "R", "base": 0, "outs": 1},
    {"speed": 108.0, "angle": 26.0, "spray": -20.0, "dist": 395.0, "stand": "R", "base": 0, "outs": 1},
    {"speed": 110.0, "angle": 28.0, "spray": 0.0, "dist": 410.0, "stand": "R", "base": 0, "outs": 1},
    {"speed": 108.0, "angle": 26.0, "spray": 20.0, "dist": 395.0, "stand": "L", "base": 0, "outs": 1},
    {"speed": 110.0, "angle": 28.0, "spray": 35.0, "dist": 380.0, "stand": "L", "base": 0, "outs": 1},
    {"speed": 105.0, "angle": 30.0, "spray": -10.0, "dist": 400.0, "stand": "R", "base": 0, "outs": 1},
    {"speed": 105.0, "angle": 30.0, "spray": 10.0, "dist": 400.0, "stand": "L", "base": 0, "outs": 1},
]

# Legacy single-canonical for backwards compat in unit tests.
CANONICAL_LAUNCH_SPEED_MPH: Final[float] = 110.0
CANONICAL_LAUNCH_ANGLE_DEG: Final[float] = 28.0
CANONICAL_SPRAY_ANGLE_DEG: Final[float] = 0.0
CANONICAL_HIT_DISTANCE_FT: Final[float] = 410.0
CANONICAL_STAND: Final[str] = "R"
CANONICAL_BASE_STATE: Final[int] = 0
CANONICAL_OUTS: Final[int] = 1


@dataclass(frozen=True)
class ParkGap:
    """Per-park diagnostic — the per-park rows surfaced in failure messages."""

    park_id: str
    pred_p_hr: float
    published_factor: float
    pred_rank: int
    published_rank: int
    rank_delta: int


@dataclass(frozen=True)
class SanityReport:
    park_order: tuple[str, ...]
    predicted_p_hr: dict[str, float]
    published_hr_factor: dict[str, float]
    spearman_rho: float
    spearman_pvalue: float
    coors_oakland_gap: float
    coors_p_hr: float
    oakland_p_hr: float
    per_park_gaps: list[ParkGap] = field(default_factory=list)
    gate_passes: bool = False
    spearman_gate: float = SPEARMAN_GATE
    coors_oakland_gap_gate: float = COORS_VS_OAKLAND_GAP_GATE
    failure_reasons: list[str] = field(default_factory=list)

    def actionable_error(self) -> str:
        """Compose the leaf's 'name the offending parks' failure message."""
        if self.gate_passes:
            return "(no failures)"
        head = "; ".join(self.failure_reasons) if self.failure_reasons else "gate failed"
        worst = sorted(self.per_park_gaps, key=lambda g: -abs(g.rank_delta))[:3]
        worst_str = ", ".join(
            f"{g.park_id} pred={g.pred_p_hr:.3f} factor={g.published_factor:.2f} "
            f"(pred rank {g.pred_rank} vs pub rank {g.published_rank})"
            for g in worst
        )
        return f"{head}. Top 3 offenders: {worst_str}"


# --- canonical-input encoder ----------------------------------------------


def canonical_features() -> np.ndarray:
    """Build the same 15-dim feature vector the MLP saw at training time."""
    feats = np.zeros(len(FEATURE_NAMES), dtype=np.float32)
    feats[0] = CANONICAL_LAUNCH_SPEED_MPH
    feats[1] = CANONICAL_LAUNCH_ANGLE_DEG
    feats[2] = CANONICAL_SPRAY_ANGLE_DEG
    feats[3] = CANONICAL_HIT_DISTANCE_FT
    feats[4:6] = stand_one_hot(CANONICAL_STAND)
    feats[6:14] = base_state_one_hot(CANONICAL_BASE_STATE)
    feats[14] = float(CANONICAL_OUTS)
    return feats


def _build_features(inp: dict[str, float | str | int]) -> np.ndarray:
    """Build a 15-dim feature vector from an input dict."""
    feats = np.zeros(len(FEATURE_NAMES), dtype=np.float32)
    feats[0] = float(inp["speed"])
    feats[1] = float(inp["angle"])
    feats[2] = float(inp["spray"])
    feats[3] = float(inp["dist"])
    feats[4:6] = stand_one_hot(str(inp["stand"]))
    feats[6:14] = base_state_one_hot(int(inp["base"]))
    feats[14] = float(inp["outs"])
    return feats


# --- prediction ------------------------------------------------------------


def cross_park_p_hr(
    model: BattedBallMLP,
    scaler: FeatureScaler,
    park_order: tuple[str, ...],
    *,
    calibrators: ParkCalibrators | None = None,
) -> dict[str, float]:
    """Run the model on multiple representative barrel inputs and return
    the average {park_id: P(HR)} across all of them.

    Using multiple spray angles captures asymmetric fence profiles that
    a single dead-center input misses entirely.
    """
    if len(park_order) != model.n_parks:
        raise ValueError(f"park_order length {len(park_order)} != model.n_parks {model.n_parks}")

    all_feats = np.stack([_build_features(inp) for inp in CANONICAL_INPUTS], axis=0)
    feats = scaler.transform(all_feats)
    model.eval()
    with torch.no_grad():
        logits = model(torch.from_numpy(feats))  # (K, n_parks, 5)
        probs = F.softmax(logits, dim=-1).numpy()
    if calibrators is not None:
        probs = transform(calibrators, probs)
    # Average P(HR) across all K canonical inputs per park.
    mean_p_hr = probs[:, :, 4].mean(axis=0)  # (n_parks,)
    return {pid: float(mean_p_hr[i]) for i, pid in enumerate(park_order)}


# --- monotonicity check ----------------------------------------------------


def _rank(values: dict[str, float]) -> dict[str, int]:
    """1-based rank of each park's value (1 = highest)."""
    ordered = sorted(values.items(), key=lambda kv: -kv[1])
    return {pid: i + 1 for i, (pid, _v) in enumerate(ordered)}


def check_monotonicity(
    predicted: dict[str, float],
    published: dict[str, float],
    *,
    spearman_gate: float = SPEARMAN_GATE,
    coors_oakland_gap_gate: float = COORS_VS_OAKLAND_GAP_GATE,
    coors_park_id: str = "COL",
    oakland_park_id: str = "ATH",
) -> SanityReport:
    """Compute Spearman + gap-from-Coors-to-Oakland and assemble the report.

    Park sets must match exactly — predicted parks not in published (or
    vice versa) raise ``ValueError`` so the caller fixes the alignment.
    """
    pred_set = set(predicted)
    pub_set = set(published)
    if pred_set != pub_set:
        only_pred = pred_set - pub_set
        only_pub = pub_set - pred_set
        raise ValueError(
            f"park set mismatch: only_in_pred={sorted(only_pred)} only_in_pub={sorted(only_pub)}"
        )

    ordered_parks = tuple(sorted(predicted))
    pred_values = np.array([predicted[p] for p in ordered_parks], dtype=np.float64)
    pub_values = np.array([published[p] for p in ordered_parks], dtype=np.float64)
    # scipy returns a SignificanceResult; access via attributes (pyright
    # can't narrow the tuple-protocol on the legacy unpacking path).
    result = spearmanr(pred_values, pub_values)
    raw_rho = float(result.statistic)  # type: ignore[union-attr]
    raw_pvalue = float(result.pvalue)  # type: ignore[union-attr]
    rho = raw_rho if not np.isnan(raw_rho) else 0.0
    pvalue = raw_pvalue if not np.isnan(raw_pvalue) else 1.0

    coors_p = float(predicted.get(coors_park_id, np.nan))
    oakland_p = float(predicted.get(oakland_park_id, np.nan))
    coors_oakland_gap = coors_p - oakland_p

    pred_ranks = _rank(predicted)
    pub_ranks = _rank(published)
    per_park_gaps = [
        ParkGap(
            park_id=pid,
            pred_p_hr=predicted[pid],
            published_factor=published[pid],
            pred_rank=pred_ranks[pid],
            published_rank=pub_ranks[pid],
            rank_delta=pred_ranks[pid] - pub_ranks[pid],
        )
        for pid in ordered_parks
    ]

    failure_reasons: list[str] = []
    if rho <= spearman_gate:
        failure_reasons.append(f"Spearman rho {rho:.3f} <= gate {spearman_gate:.2f}")
    if coors_oakland_gap < coors_oakland_gap_gate:
        failure_reasons.append(
            f"{coors_park_id} - {oakland_park_id} P(HR) gap {coors_oakland_gap:+.3f} "
            f"< gate {coors_oakland_gap_gate:+.2f}"
        )
    gate_passes = not failure_reasons

    return SanityReport(
        park_order=ordered_parks,
        predicted_p_hr=dict(predicted),
        published_hr_factor=dict(published),
        spearman_rho=rho,
        spearman_pvalue=pvalue,
        coors_oakland_gap=coors_oakland_gap,
        coors_p_hr=coors_p,
        oakland_p_hr=oakland_p,
        per_park_gaps=per_park_gaps,
        gate_passes=gate_passes,
        spearman_gate=spearman_gate,
        coors_oakland_gap_gate=coors_oakland_gap_gate,
        failure_reasons=failure_reasons,
    )


# --- I/O -----------------------------------------------------------------


def load_published_factors(path: Path) -> dict[str, float]:
    """Load the JSON, return {park_id: factor}."""
    payload = json.loads(path.read_text())
    if payload.get("schema_version") != 1:
        raise ValueError(
            f"unknown published-factor schema_version: {payload.get('schema_version')}"
        )
    return dict(payload["park_hr_factors"])


def write_report(report: SanityReport, path: Path) -> None:
    """Persist the report alongside the model artefacts."""
    payload = {
        "schema_version": 1,
        "park_order": list(report.park_order),
        "predicted_p_hr": report.predicted_p_hr,
        "published_hr_factor": report.published_hr_factor,
        "spearman_rho": report.spearman_rho,
        "spearman_pvalue": report.spearman_pvalue,
        "spearman_gate": report.spearman_gate,
        "coors_p_hr": report.coors_p_hr,
        "oakland_p_hr": report.oakland_p_hr,
        "coors_oakland_gap": report.coors_oakland_gap,
        "coors_oakland_gap_gate": report.coors_oakland_gap_gate,
        "gate_passes": report.gate_passes,
        "failure_reasons": report.failure_reasons,
        "per_park_gaps": [
            {
                "park_id": g.park_id,
                "pred_p_hr": g.pred_p_hr,
                "published_factor": g.published_factor,
                "pred_rank": g.pred_rank,
                "published_rank": g.published_rank,
                "rank_delta": g.rank_delta,
            }
            for g in report.per_park_gaps
        ],
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2))


__all__ = (
    "CANONICAL_HIT_DISTANCE_FT",
    "CANONICAL_LAUNCH_ANGLE_DEG",
    "CANONICAL_LAUNCH_SPEED_MPH",
    "COORS_VS_OAKLAND_GAP_GATE",
    "SPEARMAN_GATE",
    "ParkGap",
    "SanityReport",
    "canonical_features",
    "check_monotonicity",
    "cross_park_p_hr",
    "load_published_factors",
    "write_report",
)

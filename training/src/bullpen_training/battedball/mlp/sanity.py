"""Cross-park sanity check for the multi-output MLP (Phase 2c.7).

Decision [52] makes this a HARD GATE: the model's per-park P(HR) for a
canonical batted ball must rank approximately the same as a per-park
reference HR factor. If the Spearman rank correlation against the
reference is below the threshold, the model is broken and must NOT be
registered — likely culprits are bad retrodiction labels, an MLP that
hasn't trained long enough, or a wrong canonical-input encoding.

The reference target was re-aimed by decision [140] (amends [52]): from
the single-year published file (Savant 2024) to **observed_norm** — the
roster-controlled, multi-year park factor the model is actually built to
reproduce. The published single-season file is only ~0.64-correlated with
observed_norm, so it was a noisy yardstick; observed_norm carries a
split-half reliability of ~0.935 (the achievable ceiling). The threshold
moved 0.80 → 0.65 to sit below the capped lever-stack fidelity (~0.69,
decision [139]) with enough margin that n=30 Spearman noise doesn't flake
the gate, while still failing a genuinely broken model (raw physics
rho~0.29, under-trained MLP rho~0.49). 0.65 is an interim floor — to be
tightened as the model improves (the deferred backlog in [139]).

Inputs / convention:

- Canonical launch: 110 mph / 28 deg / 0 spray / 2000 rpm backspin
  (default `LaunchParams`). The MLP gets these features after the
  fitted ``FeatureScaler`` is applied — same code path serving uses.
- The observed_norm reference is a frozen anchor at
  ``training/data/observed_norm_factors.json``, emitted once on the
  desktop from ClickHouse via
  ``scripts/compare_park_factors.py --emit-anchor`` and committed
  (decision [140]). The legacy ``published_hr_factors.json`` stays as a
  secondary diagnostic, no longer the gate target.
- Output: a :class:`SanityReport` carrying the per-park predicted vs
  reference vector, Spearman rho, gap diagnostics, and a boolean
  `gate_passes` flag.

The gates (decision [52], re-aimed by [140]):

  - Spearman rho between predicted P(HR) and observed_norm factor > 0.65
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

# Gates (decision [52], re-aimed by [140]). The Spearman gate now scores against
# observed_norm (not the published single-year file); 0.65 is an interim floor
# below the ~0.69 capped fidelity ([139]) and above a genuinely broken model.
SPEARMAN_GATE: Final[float] = 0.65
COORS_VS_OAKLAND_GAP_GATE: Final[float] = 0.05

# Representative barrel grid for the cross-park probe.
#
# Sign convention (features_shared.hc_to_spray_deg): +spray = toward 3B/LF,
# -spray = toward 1B/RF. So a *pulled* barrel is (RHB, +spray) → LF, or
# (LHB, -spray) → RF; an *oppo* barrel is the mirror. The MLP conditions on
# `stand` AND `spray` separately, so pull and oppo are NOT interchangeable
# inputs — the head sees genuinely different feature vectors.
#
# Why the grid matters (decision [52] / 2c.7): ~80% of HR are PULLED, and the
# park features that discriminate HR factors live on the pull side — NYY's short
# RF porch is exploited by LHB pull (stand L, -spray), the Crawford Boxes by RHB
# pull, etc. The previous grid was entirely opposite-field/center (RHB only at
# spray <= 0, LHB only at spray >= +10), so it probed each park in the spray
# region the model barely sees and never visited the porches — a likely driver
# of the 0.49 Spearman (NYY ranked 28 vs published 3).
#
# This grid is pull-heavy for BOTH hands (4 pull / 1 center / 1 oppo per hand),
# weighted to ~84% pull / ~8% center / ~8% oppo to approximate the HR-conditional
# batted-ball distribution, over the HR-producing EV/LA region (EV ~104-110, LA
# ~25-30). `cross_park_p_hr` does a `weight`-weighted average. Pure gate-side —
# no retrodiction, no retrain — so it re-scores last night's model directly.
# Empirically reweighting from the real HR-conditional distribution is a possible
# refinement but would couple the gate to data at runtime; kept self-contained.
CANONICAL_INPUTS: Final[list[dict[str, float | str | int]]] = [
    # --- RHB pulled barrels -> LF (+spray) ---
    {
        "speed": 108.0,
        "angle": 27.0,
        "spray": 18.0,
        "dist": 405.0,
        "stand": "R",
        "base": 0,
        "outs": 1,
        "weight": 1.0,
    },
    {
        "speed": 110.0,
        "angle": 25.0,
        "spray": 28.0,
        "dist": 400.0,
        "stand": "R",
        "base": 0,
        "outs": 1,
        "weight": 1.0,
    },
    {
        "speed": 104.0,
        "angle": 29.0,
        "spray": 33.0,
        "dist": 395.0,
        "stand": "R",
        "base": 0,
        "outs": 1,
        "weight": 1.0,
    },
    {
        "speed": 106.0,
        "angle": 26.0,
        "spray": 24.0,
        "dist": 400.0,
        "stand": "R",
        "base": 0,
        "outs": 1,
        "weight": 1.0,
    },
    # --- RHB center ---
    {
        "speed": 110.0,
        "angle": 28.0,
        "spray": 0.0,
        "dist": 410.0,
        "stand": "R",
        "base": 0,
        "outs": 1,
        "weight": 0.5,
    },
    # --- RHB oppo -> RF (-spray) ---
    {
        "speed": 106.0,
        "angle": 30.0,
        "spray": -22.0,
        "dist": 400.0,
        "stand": "R",
        "base": 0,
        "outs": 1,
        "weight": 0.25,
    },
    # --- LHB pulled barrels -> RF (-spray); this is the NYY short-porch region ---
    {
        "speed": 108.0,
        "angle": 27.0,
        "spray": -18.0,
        "dist": 405.0,
        "stand": "L",
        "base": 0,
        "outs": 1,
        "weight": 1.0,
    },
    {
        "speed": 110.0,
        "angle": 25.0,
        "spray": -28.0,
        "dist": 400.0,
        "stand": "L",
        "base": 0,
        "outs": 1,
        "weight": 1.0,
    },
    {
        "speed": 104.0,
        "angle": 29.0,
        "spray": -33.0,
        "dist": 395.0,
        "stand": "L",
        "base": 0,
        "outs": 1,
        "weight": 1.0,
    },
    {
        "speed": 106.0,
        "angle": 26.0,
        "spray": -24.0,
        "dist": 400.0,
        "stand": "L",
        "base": 0,
        "outs": 1,
        "weight": 1.0,
    },
    # --- LHB center ---
    {
        "speed": 110.0,
        "angle": 28.0,
        "spray": 0.0,
        "dist": 410.0,
        "stand": "L",
        "base": 0,
        "outs": 1,
        "weight": 0.5,
    },
    # --- LHB oppo -> LF (+spray) ---
    {
        "speed": 106.0,
        "angle": 30.0,
        "spray": 22.0,
        "dist": 400.0,
        "stand": "L",
        "base": 0,
        "outs": 1,
        "weight": 0.25,
    },
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
    """Per-park diagnostic — the per-park rows surfaced in failure messages.

    ``reference_factor``/``reference_rank`` are the gate reference (observed_norm
    since decision [140]); the field was named ``published_*`` before the re-aim.
    """

    park_id: str
    pred_p_hr: float
    reference_factor: float
    pred_rank: int
    reference_rank: int
    rank_delta: int


@dataclass(frozen=True)
class SanityReport:
    park_order: tuple[str, ...]
    predicted_p_hr: dict[str, float]
    # The per-park reference the gate ranks against — observed_norm ([140]).
    reference_hr_factor: dict[str, float]
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
            f"{g.park_id} pred={g.pred_p_hr:.3f} factor={g.reference_factor:.2f} "
            f"(pred rank {g.pred_rank} vs ref rank {g.reference_rank})"
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
    """Run the model on the CANONICAL_INPUTS barrel grid and return the
    ``weight``-weighted average {park_id: P(HR)} across them.

    The grid is pull-heavy for both hands (see CANONICAL_INPUTS) so the
    average reflects the spray/handedness region where HRs actually occur and
    where park fence profiles (short porches, pull-side walls) discriminate —
    a single dead-center input, or an all-oppo grid, misses that signal.
    Per-input ``weight`` approximates the HR-conditional batted-ball density.
    """
    if len(park_order) != model.n_parks:
        raise ValueError(f"park_order length {len(park_order)} != model.n_parks {model.n_parks}")

    all_feats = np.stack([_build_features(inp) for inp in CANONICAL_INPUTS], axis=0)
    weights = np.array(
        [float(inp.get("weight", 1.0)) for inp in CANONICAL_INPUTS], dtype=np.float64
    )
    feats = scaler.transform(all_feats)
    model.eval()
    with torch.no_grad():
        logits = model(torch.from_numpy(feats))  # (K, n_parks, 5)
        probs = F.softmax(logits, dim=-1).numpy()
    if calibrators is not None:
        probs = transform(calibrators, probs)
    # Weighted average of P(HR) across the K canonical inputs, per park.
    mean_p_hr = np.average(probs[:, :, 4], axis=0, weights=weights)  # (n_parks,)
    return {pid: float(mean_p_hr[i]) for i, pid in enumerate(park_order)}


# --- monotonicity check ----------------------------------------------------


def _rank(values: dict[str, float]) -> dict[str, int]:
    """1-based rank of each park's value (1 = highest)."""
    ordered = sorted(values.items(), key=lambda kv: -kv[1])
    return {pid: i + 1 for i, (pid, _v) in enumerate(ordered)}


def check_monotonicity(
    predicted: dict[str, float],
    reference: dict[str, float],
    *,
    spearman_gate: float = SPEARMAN_GATE,
    coors_oakland_gap_gate: float = COORS_VS_OAKLAND_GAP_GATE,
    coors_park_id: str = "COL",
    oakland_park_id: str = "ATH",
) -> SanityReport:
    """Compute Spearman + gap-from-Coors-to-Oakland and assemble the report.

    ``reference`` is the per-park HR factor the gate ranks against —
    observed_norm since decision [140] (was the published file). Park sets
    must match exactly — predicted parks not in the reference (or vice
    versa) raise ``ValueError`` so the caller fixes the alignment.
    """
    pred_set = set(predicted)
    ref_set = set(reference)
    if pred_set != ref_set:
        only_pred = pred_set - ref_set
        only_ref = ref_set - pred_set
        raise ValueError(
            f"park set mismatch: only_in_pred={sorted(only_pred)} only_in_ref={sorted(only_ref)}"
        )

    ordered_parks = tuple(sorted(predicted))
    pred_values = np.array([predicted[p] for p in ordered_parks], dtype=np.float64)
    ref_values = np.array([reference[p] for p in ordered_parks], dtype=np.float64)
    # scipy returns a SignificanceResult; access via attributes (pyright
    # can't narrow the tuple-protocol on the legacy unpacking path).
    result = spearmanr(pred_values, ref_values)
    raw_rho = float(result.statistic)  # type: ignore[union-attr]
    raw_pvalue = float(result.pvalue)  # type: ignore[union-attr]
    rho = raw_rho if not np.isnan(raw_rho) else 0.0
    pvalue = raw_pvalue if not np.isnan(raw_pvalue) else 1.0

    coors_p = float(predicted.get(coors_park_id, np.nan))
    oakland_p = float(predicted.get(oakland_park_id, np.nan))
    coors_oakland_gap = coors_p - oakland_p

    pred_ranks = _rank(predicted)
    ref_ranks = _rank(reference)
    per_park_gaps = [
        ParkGap(
            park_id=pid,
            pred_p_hr=predicted[pid],
            reference_factor=reference[pid],
            pred_rank=pred_ranks[pid],
            reference_rank=ref_ranks[pid],
            rank_delta=pred_ranks[pid] - ref_ranks[pid],
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
        reference_hr_factor=dict(reference),
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
    """Load the legacy published-factor JSON, return {park_id: factor}.

    No longer the gate target since decision [140] — kept as a secondary
    diagnostic and for the synthetic unit tests that just need a 30-park vector.
    """
    payload = json.loads(path.read_text())
    if payload.get("schema_version") != 1:
        raise ValueError(
            f"unknown published-factor schema_version: {payload.get('schema_version')}"
        )
    return dict(payload["park_hr_factors"])


def load_observed_norm_factors(path: Path) -> dict[str, float]:
    """Load the frozen observed_norm anchor (the gate reference, decision [140]).

    Emitted once on the desktop from ClickHouse via
    ``scripts/compare_park_factors.py --emit-anchor`` and committed. Schema:
    ``{"schema_version": 1, "observed_norm_factors": {park_id: factor}, ...}``.
    """
    payload = json.loads(path.read_text())
    if payload.get("schema_version") != 1:
        raise ValueError(
            f"unknown observed_norm-anchor schema_version: {payload.get('schema_version')}"
        )
    return dict(payload["observed_norm_factors"])


def write_report(report: SanityReport, path: Path) -> None:
    """Persist the report alongside the model artefacts."""
    payload = {
        "schema_version": 2,
        "reference": "observed_norm",
        "park_order": list(report.park_order),
        "predicted_p_hr": report.predicted_p_hr,
        "reference_hr_factor": report.reference_hr_factor,
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
                "reference_factor": g.reference_factor,
                "pred_rank": g.pred_rank,
                "reference_rank": g.reference_rank,
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
    "load_observed_norm_factors",
    "load_published_factors",
    "write_report",
)

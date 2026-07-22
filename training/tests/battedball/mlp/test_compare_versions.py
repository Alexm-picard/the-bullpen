"""Mac-side unit tests for the inference-only v3-vs-v2 non-inferiority gate (compare_versions).

The ClickHouse load + the real ONNX scoring are box-only; here we build the Verdict directly from
synthetic paired predictions and exercise the PURE artifact assembly. The load-bearing assertion:
a PASSING artifact is SELF-CONSISTENT under the EXACT re-derivation OfflineGateImportService runs
(so the box's import-offline will accept it), and a carry failure or a primary regression flips the
status to failed. Mirrors test_carry_promotion_eval.py.
"""

from __future__ import annotations

import numpy as np

from bullpen_training.battedball.mlp.compare_versions import (
    CHALLENGER_NAME,
    CHAMPION_NAME,
    MODEL_NAME,
    build_v3_gate_artifact,
)
from bullpen_training.battedball.mlp.rolling_cv_eval import carry_gate
from bullpen_training.battedball.mlp.train import CARRY_MEAN_FT, CARRY_STD_FT
from bullpen_training.eval.promotion.criteria import (
    Verdict,
    criteria_for,
    evaluate_challenger_vs_baseline,
)

_N = 2500  # > the 2000 sample-size target
_TRUE = np.array([0.60, 0.12, 0.12, 0.08, 0.08])  # a non-degenerate conditional outcome dist
_PARKS = ("NYY", "BOS", "COL")


def _carry_raw(target_ft: float) -> float:
    """The standardised carry value that un-standardises to target_ft (ft = raw*STD + MEAN)."""
    return (target_ft - CARRY_MEAN_FT) / CARRY_STD_FT


def _verdict(*, regress: bool) -> Verdict:
    """v2 (champion) predicts the true conditional; v3 (challenger) is identical (non-inferior) or a
    near-uniform regression, paired on the same realized outcomes."""
    rng = np.random.default_rng(0)  # a synthetic TEST fixture, not a data split
    y = rng.choice(5, size=_N, p=_TRUE).astype(np.int64)
    v2 = np.tile(_TRUE, (_N, 1))
    v3 = np.tile(np.full(5, 0.2), (_N, 1)) if regress else v2.copy()
    return evaluate_challenger_vs_baseline(
        criteria=criteria_for(MODEL_NAME),
        y_true_int=y,
        baseline_proba=v2,
        challenger_proba=v3,
    )


def _passing_carry():
    return carry_gate({p: _carry_raw(300.0) for p in _PARKS})  # ft 300 in [50, 550] -> pass


def _artifact(*, regress: bool, carry):
    return build_v3_gate_artifact(
        verdict=_verdict(regress=regress),
        criteria=criteria_for(MODEL_NAME),
        carry=carry,
        champion_name=CHAMPION_NAME,
        challenger_name=CHALLENGER_NAME,
        val_season=2025,
        git_commit="deadbeef",
        generated_at="2026-07-22T00:00:00+00:00",
    )


def _reimport_pass(art: dict) -> bool:
    """Replicate OfflineGateImportService's ACCEPT-path re-derivation (the box's gate).

    Faithful to the accept decision; the importer's standalone sample-size numeric check is folded
    into ``verdict.sample_size_met`` here (equivalent given the artifact's guaranteed
    observed/target <-> sample_size_met consistency).
    """
    primary_met = art["challenger_metric"] + art["primary_threshold"] <= art["champion_metric"]
    breached = [
        k
        for k, mx in art["guardrails"].items()
        if (obs := art["guardrails_observed"].get(k)) is not None and obs > mx
    ]
    carry_ok = art["carry_gate"] is None or art["carry_gate"]["passed"]
    recomputed = primary_met and not breached and carry_ok
    declared = (
        art["status"] == "passed" and art["verdict"]["passed"] and art["verdict"]["sample_size_met"]
    )
    return recomputed and declared


def test_passing_v3_gate_is_import_self_consistent():
    art = _artifact(regress=False, carry=_passing_carry())
    assert art["status"] == "passed"
    assert art["model_name"] == "battedball_outcome"
    assert art["primary_metric"] == "brier" and art["primary_threshold"] == -0.002
    assert art["champion_model_name"] == CHAMPION_NAME
    assert art["challenger_model_name"] == CHALLENGER_NAME
    assert art["sample_size_observed"] >= art["sample_size_target"]
    assert art["carry_gate"]["hard_gate"] is True
    # The whole point: the box's import-offline re-derivation accepts this artifact.
    assert _reimport_pass(art)


def test_carry_failure_flips_status_to_failed():
    # Outcome non-inferior, but one park's carry is implausible (ft 9999) -> HARD fail.
    bad_carry = carry_gate(
        {"NYY": _carry_raw(9999.0), "BOS": _carry_raw(300.0), "COL": _carry_raw(300.0)}
    )
    art = _artifact(regress=False, carry=bad_carry)
    assert art["carry_gate"]["passed"] is False
    assert art["status"] == "failed"
    assert not _reimport_pass(art)


def test_primary_regression_flips_status_to_failed():
    art = _artifact(regress=True, carry=_passing_carry())
    assert art["status"] == "failed"  # v3 uniform Brier is far past v2 + the 0.002 margin
    assert art["verdict"]["passed"] is False
    assert not _reimport_pass(art)

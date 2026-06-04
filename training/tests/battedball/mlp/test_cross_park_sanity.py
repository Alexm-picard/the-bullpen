"""Cross-park sanity tests (Phase 2c.7, decision [52]).

Two layers:
  1. Pure-function tests on :func:`check_monotonicity` using synthetic
     prediction dicts so the gate logic itself is pinned regardless of
     whether the production model exists yet.
  2. Integration test that loads ``artifacts/battedball_mlp_v1/`` if
     present and runs the canonical input through it. Skipped on CI
     when the artifact directory hasn't been populated (i.e. before
     the 2c.5 production training run lands on the desktop).
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pytest

from bullpen_training.battedball.mlp.architecture import build_model
from bullpen_training.battedball.mlp.dataset import FEATURE_NAMES, FeatureScaler
from bullpen_training.battedball.mlp.sanity import (
    CANONICAL_INPUTS,
    COORS_VS_OAKLAND_GAP_GATE,
    SPEARMAN_GATE,
    ParkGap,
    canonical_features,
    check_monotonicity,
    cross_park_p_hr,
    load_observed_norm_factors,
    load_published_factors,
    write_report,
)

# --- CANONICAL_INPUTS probe grid (Patch B, decision [52]) -----------------


def _is_pull(inp: dict) -> bool:
    """Pulled barrel: RHB to LF (+spray) or LHB to RF (-spray)."""
    return (str(inp["stand"]) == "R" and float(inp["spray"]) > 0) or (
        str(inp["stand"]) == "L" and float(inp["spray"]) < 0
    )


def _is_oppo(inp: dict) -> bool:
    return (str(inp["stand"]) == "R" and float(inp["spray"]) < 0) or (
        str(inp["stand"]) == "L" and float(inp["spray"]) > 0
    )


def test_canonical_inputs_include_pulled_barrels_for_both_hands() -> None:
    """The pre-Patch-B grid was all oppo/center and never visited the pull side
    where HR-discriminating park features live (e.g. NYY's RF porch = LHB pull)."""
    rhb_pull = [i for i in CANONICAL_INPUTS if str(i["stand"]) == "R" and float(i["spray"]) > 0]
    lhb_pull = [i for i in CANONICAL_INPUTS if str(i["stand"]) == "L" and float(i["spray"]) < 0]
    assert rhb_pull, "no RHB pulled barrels (R, +spray -> LF)"
    assert lhb_pull, "no LHB pulled barrels (L, -spray -> RF) — the NYY short-porch region"


def test_canonical_inputs_also_cover_oppo_and_center() -> None:
    assert any(_is_oppo(i) for i in CANONICAL_INPUTS), "grid has no opposite-field inputs"
    assert any(float(i["spray"]) == 0.0 for i in CANONICAL_INPUTS), "grid has no center inputs"


def test_canonical_inputs_are_pull_weighted() -> None:
    """~80% of HR are pulled; the grid should weight pull accordingly."""
    pull_w = sum(float(i.get("weight", 1.0)) for i in CANONICAL_INPUTS if _is_pull(i))
    total_w = sum(float(i.get("weight", 1.0)) for i in CANONICAL_INPUTS)
    share = pull_w / total_w
    assert share >= 0.6, f"pull weight share {share:.2f} too low for an HR-conditional grid"


def test_canonical_inputs_sit_in_the_hr_zone() -> None:
    for i in CANONICAL_INPUTS:
        assert 100.0 <= float(i["speed"]) <= 115.0, f"EV out of HR zone: {i}"
        assert 22.0 <= float(i["angle"]) <= 34.0, f"LA out of HR zone: {i}"
        assert float(i.get("weight", 1.0)) > 0.0


def test_cross_park_p_hr_weighted_path_returns_valid_probs() -> None:
    """Exercises the weighted-average path on a small synthetic model so the
    plumbing is pinned without needing the production artifact."""
    import torch  # local import keeps the module importable without torch

    _ = torch
    park_order = ("COL", "NYY", "SF")
    model = build_model(n_parks=len(park_order))
    n = len(FEATURE_NAMES)
    scaler = FeatureScaler(
        means=np.zeros(n, dtype=np.float32),
        stds=np.ones(n, dtype=np.float32),
        is_continuous=np.ones(n, dtype=bool),
    )
    per_park = cross_park_p_hr(model, scaler, park_order)
    assert set(per_park) == set(park_order)
    for pid, p in per_park.items():
        assert 0.0 <= p <= 1.0, f"{pid}: P(HR)={p} outside [0,1]"


_HR_FACTORS_PATH = Path(__file__).resolve().parents[3] / "data" / "published_hr_factors.json"
_ANCHOR_PATH = Path(__file__).resolve().parents[3] / "data" / "observed_norm_factors.json"
_MODEL_DIR = Path(__file__).resolve().parents[3] / "artifacts" / "battedball_mlp_v1"


# --- canonical features ---------------------------------------------------


def test_canonical_features_match_FEATURE_NAMES_length() -> None:
    feats = canonical_features()
    assert feats.shape == (len(FEATURE_NAMES),)
    assert feats.dtype == np.float32


def test_canonical_features_have_expected_launch_values() -> None:
    feats = canonical_features()
    # 110 mph EV / 28 deg LA / 0 spray / 410 ft hit distance.
    assert feats[FEATURE_NAMES.index("launch_speed_mph")] == pytest.approx(110.0)
    assert feats[FEATURE_NAMES.index("launch_angle_deg")] == pytest.approx(28.0)
    assert feats[FEATURE_NAMES.index("spray_angle_deg")] == pytest.approx(0.0)
    assert feats[FEATURE_NAMES.index("hit_distance_ft")] == pytest.approx(410.0)


# --- published HR factors -------------------------------------------------


def test_published_factors_load_and_cover_30_parks() -> None:
    factors = load_published_factors(_HR_FACTORS_PATH)
    assert len(factors) == 30
    expected = {
        "AZ",
        "ATH",
        "ATL",
        "BAL",
        "BOS",
        "CHC",
        "CIN",
        "CLE",
        "COL",
        "CWS",
        "DET",
        "HOU",
        "KC",
        "LAA",
        "LAD",
        "MIA",
        "MIL",
        "MIN",
        "NYM",
        "NYY",
        "PHI",
        "PIT",
        "SD",
        "SEA",
        "SF",
        "STL",
        "TB",
        "TEX",
        "TOR",
        "WSH",
    }
    assert set(factors) == expected


def test_coors_is_top_published_factor() -> None:
    """Sanity on the data file itself — Coors should rank highest."""
    factors = load_published_factors(_HR_FACTORS_PATH)
    top = max(factors, key=lambda k: factors[k])
    assert top == "COL", f"expected COL on top of published HR factors, got {top}"


def test_load_published_rejects_unknown_schema(tmp_path: Path) -> None:
    bad = tmp_path / "bad.json"
    bad.write_text(json.dumps({"schema_version": 999, "park_hr_factors": {}}))
    with pytest.raises(ValueError, match="schema_version"):
        load_published_factors(bad)


# --- observed_norm anchor (the re-aimed gate target, decision [140]) ------


def test_spearman_gate_is_065_interim_floor() -> None:
    """Pin decision [140]: the gate is the interim 0.65 floor vs observed_norm
    (re-aimed from 0.80 vs the published file). Tighten as the model improves."""
    assert SPEARMAN_GATE == 0.65


def test_load_observed_norm_factors_round_trips(tmp_path: Path) -> None:
    anchor = tmp_path / "anchor.json"
    anchor.write_text(
        json.dumps(
            {
                "schema_version": 1,
                "reference": "observed_norm",
                "observed_norm_factors": {"COL": 1.31, "ATH": 0.78, "NYY": 1.12},
            }
        )
    )
    factors = load_observed_norm_factors(anchor)
    assert factors == {"COL": 1.31, "ATH": 0.78, "NYY": 1.12}


def test_load_observed_norm_rejects_unknown_schema(tmp_path: Path) -> None:
    bad = tmp_path / "bad.json"
    bad.write_text(json.dumps({"schema_version": 999, "observed_norm_factors": {}}))
    with pytest.raises(ValueError, match="schema_version"):
        load_observed_norm_factors(bad)


# --- check_monotonicity (synthetic) --------------------------------------


def _identity_predicted_from_published(published: dict[str, float]) -> dict[str, float]:
    """Build predicted P(HR) that perfectly mirrors the published rank."""
    # Map a 0.7..1.4 factor to a 0.04..0.20 P(HR) — same rank order.
    fmin, fmax = min(published.values()), max(published.values())
    pmin, pmax = 0.04, 0.20
    return {
        pid: pmin + (factor - fmin) / (fmax - fmin + 1e-9) * (pmax - pmin)
        for pid, factor in published.items()
    }


def test_check_monotonicity_passes_on_perfectly_ranked_predictions() -> None:
    published = load_published_factors(_HR_FACTORS_PATH)
    predicted = _identity_predicted_from_published(published)
    report = check_monotonicity(predicted, published)
    assert report.spearman_rho == pytest.approx(1.0, abs=1e-9)
    assert report.coors_oakland_gap >= COORS_VS_OAKLAND_GAP_GATE
    assert report.gate_passes is True
    assert report.failure_reasons == []


def test_check_monotonicity_fails_on_anti_correlated_predictions() -> None:
    published = load_published_factors(_HR_FACTORS_PATH)
    # Invert the predictions so rank order is reversed.
    flipped = {pid: 1.0 - v for pid, v in _identity_predicted_from_published(published).items()}
    report = check_monotonicity(flipped, published)
    assert report.spearman_rho == pytest.approx(-1.0, abs=1e-9)
    assert report.gate_passes is False
    assert any("Spearman" in r for r in report.failure_reasons)


def test_check_monotonicity_fails_when_coors_oakland_gap_too_small() -> None:
    published = load_published_factors(_HR_FACTORS_PATH)
    # Build a predicted that passes Spearman but flattens Coors->Oakland.
    predicted = _identity_predicted_from_published(published)
    predicted["COL"] = 0.10
    predicted["ATH"] = 0.09  # 0.01 gap, below the 0.05 gate
    report = check_monotonicity(predicted, published)
    assert report.coors_oakland_gap == pytest.approx(0.01, abs=1e-6)
    assert report.gate_passes is False
    assert any("gap" in r for r in report.failure_reasons)


def test_check_monotonicity_park_set_mismatch_raises() -> None:
    published = load_published_factors(_HR_FACTORS_PATH)
    short_pred = {
        k: v for k, v in _identity_predicted_from_published(published).items() if k != "COL"
    }
    with pytest.raises(ValueError, match="park set mismatch"):
        check_monotonicity(short_pred, published)


def test_actionable_error_names_offending_parks() -> None:
    published = load_published_factors(_HR_FACTORS_PATH)
    # All predicted = 0.1 -> Spearman is undefined / 0; should fail.
    flat = dict.fromkeys(published, 0.1)
    report = check_monotonicity(flat, published)
    err = report.actionable_error()
    assert err != "(no failures)"
    assert "rho" in err or "gap" in err


def test_sanity_report_round_trips_through_json(tmp_path: Path) -> None:
    published = load_published_factors(_HR_FACTORS_PATH)
    report = check_monotonicity(_identity_predicted_from_published(published), published)
    path = tmp_path / "report.json"
    write_report(report, path)
    data = json.loads(path.read_text())
    assert data["gate_passes"] is True
    assert data["schema_version"] == 2
    assert data["reference"] == "observed_norm"
    assert "per_park_gaps" in data
    assert len(data["per_park_gaps"]) == 30


# --- ParkGap dataclass --------------------------------------------------


def test_park_gap_rank_delta_computed() -> None:
    gap = ParkGap(
        park_id="X",
        pred_p_hr=0.10,
        reference_factor=1.2,
        pred_rank=3,
        reference_rank=10,
        rank_delta=-7,
    )
    assert gap.rank_delta == -7


# --- model integration (skipped if no artifact) -------------------------


def _have_production_artifacts() -> bool:
    return (_MODEL_DIR / "model.pt").exists() and (_MODEL_DIR / "metadata.json").exists()


@pytest.mark.skipif(not _have_production_artifacts(), reason="batted_ball/v1 not trained yet")
def test_cross_park_p_hr_against_smoke_model() -> None:
    """Run cross_park_p_hr against whatever model.pt is in the artifact
    dir. Only asserts the *shape* of the result — actual sanity gates
    apply to the full-data production model and live in
    ``test_cross_park_sanity_production_gate``."""
    import torch  # local import keeps this test skippable without torch ImportError

    metadata = json.loads((_MODEL_DIR / "metadata.json").read_text())
    park_order = tuple(metadata["park_order"])
    scaler_dict = metadata["feature_scaler"]
    scaler = FeatureScaler(
        means=np.array(scaler_dict["means"], dtype=np.float32),
        stds=np.array(scaler_dict["stds"], dtype=np.float32),
        is_continuous=np.array(scaler_dict["is_continuous"], dtype=bool),
    )
    model = build_model(n_parks=len(park_order))
    model.load_state_dict(torch.load(_MODEL_DIR / "model.pt", weights_only=True))

    per_park = cross_park_p_hr(model, scaler, park_order)
    assert set(per_park) == set(park_order)
    for pid, p in per_park.items():
        assert 0.0 <= p <= 1.0, f"{pid}: P(HR)={p} outside [0,1]"


@pytest.mark.skipif(not _have_production_artifacts(), reason="batted_ball/v1 not trained yet")
def test_smoke_model_cross_park_report_writes(tmp_path: Path) -> None:
    """End-to-end: produce + write a SanityReport against the smoke
    model. The actual ``gate_passes`` value depends on training quality
    — assertions on it are left to the production-gate test below."""
    import torch

    metadata = json.loads((_MODEL_DIR / "metadata.json").read_text())
    park_order = tuple(metadata["park_order"])
    scaler = FeatureScaler(
        means=np.array(metadata["feature_scaler"]["means"], dtype=np.float32),
        stds=np.array(metadata["feature_scaler"]["stds"], dtype=np.float32),
        is_continuous=np.array(metadata["feature_scaler"]["is_continuous"], dtype=bool),
    )
    model = build_model(n_parks=len(park_order))
    model.load_state_dict(torch.load(_MODEL_DIR / "model.pt", weights_only=True))

    per_park = cross_park_p_hr(model, scaler, park_order)
    published = load_published_factors(_HR_FACTORS_PATH)
    # Sanity report writer accepts any aligned park set.
    report = check_monotonicity(per_park, published)
    out_path = tmp_path / "cross_park_sanity_report.json"
    write_report(report, out_path)
    assert out_path.exists()
    payload = json.loads(out_path.read_text())
    assert "spearman_rho" in payload


@pytest.mark.production
@pytest.mark.skipif(not _have_production_artifacts(), reason="batted_ball/v1 not trained yet")
def test_production_model_passes_cross_park_sanity_gate() -> None:
    """Cross-park sanity check ([52], re-aimed by [140] to observed_norm at a 0.65
    floor) — now an ADVISORY DIAGNOSTIC, not a registration blocker (decision [141]).

    Batted-ball v1 ships as a calibrated per-park OUTCOME model and makes no
    cross-park park-factor claim, so this no longer gates registration — the
    overnight pipeline runs it via ``run_soft`` (2c.7b), so a sub-0.65 rho is
    reported in the stage log but does not halt the run. The blocking registration
    gate is outcome calibration (``test_outcome_calibration_gate.py``, 2c.7a). The
    assertion is kept so the rho + offending parks are surfaced, and so this turns
    green again on its own if cross-park fidelity is ever recovered (the [139]/[141]
    future-work backlog). Marked @pytest.mark.production; only meaningful against
    the full-backfill model.

    The anchor is REQUIRED whenever a model exists: if it's missing we fail loud
    rather than skip, so a missing/un-emitted anchor can never let the diagnostic
    silently pass-by-skip and report nothing.
    """
    import torch

    assert _ANCHOR_PATH.exists(), (
        f"observed_norm anchor missing at {_ANCHOR_PATH}. The gate must not be skipped "
        "when a trained model exists (decision [140]). Emit it on the desktop:\n"
        "  uv run python scripts/compare_park_factors.py "
        "--emit-anchor data/observed_norm_factors.json\n"
        "then commit it from the Mac (ADR-0006)."
    )

    metadata = json.loads((_MODEL_DIR / "metadata.json").read_text())
    park_order = tuple(metadata["park_order"])
    scaler = FeatureScaler(
        means=np.array(metadata["feature_scaler"]["means"], dtype=np.float32),
        stds=np.array(metadata["feature_scaler"]["stds"], dtype=np.float32),
        is_continuous=np.array(metadata["feature_scaler"]["is_continuous"], dtype=bool),
    )
    model = build_model(n_parks=len(park_order))
    model.load_state_dict(torch.load(_MODEL_DIR / "model.pt", weights_only=True))

    per_park = cross_park_p_hr(model, scaler, park_order)
    observed_norm = load_observed_norm_factors(_ANCHOR_PATH)
    report = check_monotonicity(per_park, observed_norm)
    assert report.gate_passes, report.actionable_error()
    assert report.spearman_rho > SPEARMAN_GATE
    assert report.coors_oakland_gap >= COORS_VS_OAKLAND_GAP_GATE

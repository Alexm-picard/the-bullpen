"""Unit tests for the 2c.9 MLP vs LGBM comparison.

Pure-Python synthetic fixtures — no torch / no lightgbm / no docker.
The smoke run against the real trained models lives in the script
``scripts/run_2c9_comparison.py`` (referenced from the leaf status log).
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pytest

from bullpen_training.battedball.eval.comparison import (
    AggregateMetrics,
    ComparisonReport,
    ParkMetrics,
    _multiclass_brier,
    _onehot,
    compare_models,
    decide_winner,
    per_park_metrics,
    report_from_dict,
    report_to_dict,
)
from bullpen_training.battedball.eval.report import render_html, save_report

_OUTCOMES = ("out", "1b", "2b", "3b", "hr")


def _synthetic(
    n_per_park: int = 100,
    parks: tuple[str, ...] = ("COL", "SF", "NYY"),
    seed: int = 0,
    mlp_noise: float = 0.05,
    lgbm_noise: float = 0.15,
) -> dict:
    """Two models predicting against the same retrodicted-label tensor.
    MLP gets the smaller noise so it should win on Brier in expectation."""
    rng = np.random.default_rng(seed)
    n_total = n_per_park * len(parks)
    labels = rng.dirichlet(np.ones(5), size=n_total).astype(np.float64)

    # Predictions = labels + Gaussian noise, renormalised.
    def _noisy(noise: float, seed_offset: int) -> np.ndarray:
        r = np.random.default_rng(seed + seed_offset)
        p = np.clip(labels + r.normal(0.0, noise, labels.shape), 1e-6, None)
        p /= p.sum(axis=-1, keepdims=True)
        return p

    park_ids: list[str] = []
    for pid in parks:
        park_ids.extend([pid] * n_per_park)
    return {
        "mlp": _noisy(mlp_noise, 1).astype(np.float64),
        "lgbm": _noisy(lgbm_noise, 2).astype(np.float64),
        "labels": labels,
        "park_ids": park_ids,
        "parks": parks,
    }


# --- metric kernels ---


def test_multiclass_brier_zero_on_perfect_predictions() -> None:
    labels = _onehot(np.array([0, 1, 2, 3, 4]), 5)
    pred = labels.copy()
    assert _multiclass_brier(pred, labels) == pytest.approx(0.0, abs=1e-12)


def test_multiclass_brier_two_on_certain_wrong_predictions() -> None:
    # Predict all-mass on class 0, true label class 1 -> per-row Brier = 2.
    labels = _onehot(np.full(10, 1), 5)
    pred = np.zeros_like(labels)
    pred[:, 0] = 1.0
    assert _multiclass_brier(pred, labels) == pytest.approx(2.0, abs=1e-9)


def test_multiclass_brier_shape_mismatch_raises() -> None:
    with pytest.raises(ValueError, match="shape mismatch"):
        _multiclass_brier(np.zeros((3, 5)), np.zeros((3, 4)))


# --- per_park_metrics ---


def test_per_park_metrics_one_row_per_park() -> None:
    fix = _synthetic(n_per_park=80)
    metrics = per_park_metrics(
        pred_probs=fix["mlp"],
        label_distributions=fix["labels"],
        park_ids=fix["park_ids"],
        park_order=fix["parks"],
        model="mlp",
    )
    assert [m.park_id for m in metrics] == list(fix["parks"])
    assert all(m.model == "mlp" for m in metrics)
    assert all(m.n_samples == 80 for m in metrics)


def test_per_park_metrics_brier_finite() -> None:
    fix = _synthetic(n_per_park=50)
    for m in per_park_metrics(
        pred_probs=fix["mlp"],
        label_distributions=fix["labels"],
        park_ids=fix["park_ids"],
        park_order=fix["parks"],
        model="mlp",
    ):
        assert 0.0 <= m.brier < 2.0
        assert 0.0 <= m.ece <= 1.0
        assert 0.0 <= m.accuracy <= 1.0
        # 5x5 confusion sums to n_samples.
        flat = sum(sum(r) for r in m.confusion)
        assert flat == m.n_samples


def test_per_park_metrics_rejects_unknown_park_order() -> None:
    fix = _synthetic(n_per_park=20, parks=("COL",))
    with pytest.raises(ValueError, match="no rows"):
        per_park_metrics(
            pred_probs=fix["mlp"],
            label_distributions=fix["labels"],
            park_ids=fix["park_ids"],
            park_order=("BOS",),  # not in the data
            model="mlp",
        )


def test_per_park_metrics_rejects_length_mismatch() -> None:
    fix = _synthetic(n_per_park=20)
    with pytest.raises(ValueError, match="park_ids length"):
        per_park_metrics(
            pred_probs=fix["mlp"],
            label_distributions=fix["labels"],
            park_ids=fix["park_ids"][:5],
            park_order=fix["parks"],
            model="mlp",
        )


# --- compare_models + decide_winner ---


def test_compare_models_returns_full_report() -> None:
    fix = _synthetic(n_per_park=200)
    report = compare_models(
        mlp_pred_probs=fix["mlp"],
        lgbm_pred_probs=fix["lgbm"],
        label_distributions=fix["labels"],
        park_ids=fix["park_ids"],
        park_order=fix["parks"],
        outcome_order=_OUTCOMES,
    )
    assert isinstance(report, ComparisonReport)
    assert report.park_order == fix["parks"]
    assert report.outcome_order == _OUTCOMES
    assert len(report.per_park) == 2 * len(fix["parks"])
    assert set(report.aggregate) == {"mlp", "lgbm"}


def test_lower_noise_model_wins_on_brier() -> None:
    """The MLP synthetic gets noise 0.05 and LGBM gets 0.15 — MLP
    should win the Brier comparison reliably."""
    fix = _synthetic(n_per_park=300, mlp_noise=0.05, lgbm_noise=0.15)
    report = compare_models(
        mlp_pred_probs=fix["mlp"],
        lgbm_pred_probs=fix["lgbm"],
        label_distributions=fix["labels"],
        park_ids=fix["park_ids"],
        park_order=fix["parks"],
    )
    assert report.aggregate["mlp"].mean_brier < report.aggregate["lgbm"].mean_brier
    assert report.prefer_for_production == "mlp"


def test_higher_noise_mlp_loses_to_lgbm() -> None:
    """Flip the noise: MLP synthetic gets 0.20, LGBM 0.05 — LGBM wins."""
    fix = _synthetic(n_per_park=300, mlp_noise=0.20, lgbm_noise=0.05)
    report = compare_models(
        mlp_pred_probs=fix["mlp"],
        lgbm_pred_probs=fix["lgbm"],
        label_distributions=fix["labels"],
        park_ids=fix["park_ids"],
        park_order=fix["parks"],
    )
    assert report.prefer_for_production == "lgbm"


def test_decide_winner_uses_brier_first() -> None:
    mlp = AggregateMetrics("mlp", mean_brier=0.10, mean_ece=0.30, mean_accuracy=0.5)
    lgbm = AggregateMetrics("lgbm", mean_brier=0.20, mean_ece=0.05, mean_accuracy=0.5)
    winner, rationale = decide_winner(mlp, lgbm)
    assert winner == "mlp"
    assert any("Brier" in r for r in rationale)


def test_decide_winner_falls_through_to_ece_on_tied_brier() -> None:
    mlp = AggregateMetrics("mlp", mean_brier=0.20, mean_ece=0.04, mean_accuracy=0.5)
    lgbm = AggregateMetrics("lgbm", mean_brier=0.20, mean_ece=0.10, mean_accuracy=0.5)
    winner, rationale = decide_winner(mlp, lgbm)
    assert winner == "mlp"
    assert any("ECE tiebreak" in r for r in rationale)


def test_decide_winner_full_tie_prefers_lgbm() -> None:
    mlp = AggregateMetrics("mlp", mean_brier=0.10, mean_ece=0.05, mean_accuracy=0.5)
    lgbm = AggregateMetrics("lgbm", mean_brier=0.10, mean_ece=0.05, mean_accuracy=0.5)
    winner, rationale = decide_winner(mlp, lgbm)
    assert winner == "lgbm"
    assert any("simpler" in r for r in rationale)


def test_compare_models_rejects_shape_mismatch() -> None:
    with pytest.raises(ValueError, match="shape mismatch"):
        compare_models(
            mlp_pred_probs=np.zeros((10, 5)),
            lgbm_pred_probs=np.zeros((10, 4)),
            label_distributions=np.zeros((10, 5)),
            park_ids=["COL"] * 10,
            park_order=("COL",),
        )


# --- serialisation + HTML ---


def test_report_to_dict_round_trip() -> None:
    fix = _synthetic(n_per_park=80)
    report = compare_models(
        mlp_pred_probs=fix["mlp"],
        lgbm_pred_probs=fix["lgbm"],
        label_distributions=fix["labels"],
        park_ids=fix["park_ids"],
        park_order=fix["parks"],
    )
    payload = report_to_dict(report)
    restored = report_from_dict(payload)
    assert restored.park_order == report.park_order
    assert restored.prefer_for_production == report.prefer_for_production
    assert len(restored.per_park) == len(report.per_park)


def test_report_from_dict_rejects_unknown_schema_version() -> None:
    with pytest.raises(ValueError, match="schema_version"):
        report_from_dict(
            {
                "schema_version": 999,
                "park_order": [],
                "outcome_order": [],
                "per_park": [],
                "aggregate": {},
                "prefer_for_production": "mlp",
            }
        )


def test_save_report_writes_json_and_html(tmp_path: Path) -> None:
    fix = _synthetic(n_per_park=80)
    report = compare_models(
        mlp_pred_probs=fix["mlp"],
        lgbm_pred_probs=fix["lgbm"],
        label_distributions=fix["labels"],
        park_ids=fix["park_ids"],
        park_order=fix["parks"],
    )
    j = tmp_path / "comparison.json"
    h = tmp_path / "comparison.html"
    save_report(report, j, h)
    assert j.exists()
    assert h.exists()
    payload = json.loads(j.read_text())
    assert payload["artifact_name"] == "batted_ball_comparison"
    html = h.read_text()
    assert "Production champion" in html
    assert "MLP" in html and "LGBM" in html


def test_render_html_includes_each_park() -> None:
    fix = _synthetic(n_per_park=50, parks=("COL", "SF"))
    report = compare_models(
        mlp_pred_probs=fix["mlp"],
        lgbm_pred_probs=fix["lgbm"],
        label_distributions=fix["labels"],
        park_ids=fix["park_ids"],
        park_order=fix["parks"],
    )
    html = render_html(report)
    for pid in fix["parks"]:
        assert pid in html


def test_park_metrics_dataclass_is_immutable_and_typed() -> None:
    pm = ParkMetrics(
        park_id="COL",
        model="mlp",
        n_samples=10,
        brier=0.1,
        ece=0.02,
        accuracy=0.5,
        confusion=[[1] * 5 for _ in range(5)],
    )
    assert pm.park_id == "COL"
    # frozen dataclass — can't mutate.
    with pytest.raises((AttributeError, TypeError)):
        pm.park_id = "BOS"  # type: ignore[misc]

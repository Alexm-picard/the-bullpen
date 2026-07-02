"""Unit tests for the OFFLINE pitch_outcome_post holdout accuracy job (M1 rider R2).

Pure-Python synthetic fixtures - no ClickHouse, no onnxruntime, no registered
bundle. The box-only :class:`PostHeadOnnxPredictor` and the ClickHouse frame
assembly (``load_holdout_frame``) are deliberately NOT exercised here (they
need a real session / a live container); these tests cover the pure scoring
surface (top-k + marginal math, the rule-13 inverse season fence, the report
JSON shape) and the ``_shared`` helpers with fake sessions - mirroring how the
battedball sibling's tests fake its predictor.
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd
import pytest
from bullpen_training.features import LABEL_CLASSES
from bullpen_training.pitch.eval._shared import (
    LABEL_TO_INT,
    labels_to_int,
    onnx_probabilities,
)
from bullpen_training.pitch.eval.backfill_accuracy import (
    ARTIFACT_NAME,
    DATA_SOURCE,
    EVAL_KIND_HOLDOUT,
    HOLDOUT_DISCLAIMER,
    N_CLASSES,
    HoldoutAccuracyReport,
    _refuse_non_holdout_cli,
    marginal_top_k_accuracy,
    report_from_dict,
    report_to_dict,
    save_report,
    score_holdout,
    top_k_accuracy,
)

_MODEL_KWARGS = {
    "model_name": "pitch_outcome_post",
    "model_version": "v1",
    "model_dir": "artifacts/pitch_outcome_post/v1",
}


def _onehot_probs(labels: list[int]) -> np.ndarray:
    """A perfect predictor's (N, 5) distribution: one-hot at the realized label."""
    return np.eye(N_CLASSES)[np.asarray(labels)]


# --- (a) top-k accuracy math ------------------------------------------------


def test_top_k_accuracy_hand_computed() -> None:
    labels = np.array([0, 1, 2, 4])
    probs = np.array(
        [
            [0.6, 0.3, 0.05, 0.03, 0.02],  # top1 hit (0), top2 {0,1}
            [0.5, 0.3, 0.1, 0.06, 0.04],  # top1 miss (pred 0), top2 {0,1} hit
            [0.4, 0.3, 0.2, 0.06, 0.04],  # top1 miss, top2 {0,1} miss for label 2
            [0.1, 0.1, 0.1, 0.1, 0.6],  # top1 hit (4)
        ]
    )
    assert top_k_accuracy(labels, probs, 1) == pytest.approx(2 / 4)
    assert top_k_accuracy(labels, probs, 2) == pytest.approx(3 / 4)
    # k covering every class is always 1.0.
    assert top_k_accuracy(labels, probs, N_CLASSES) == pytest.approx(1.0)


def test_top_k_accuracy_rejects_bad_k() -> None:
    labels = np.array([0])
    probs = _onehot_probs([0])
    with pytest.raises(ValueError, match="k must be"):
        top_k_accuracy(labels, probs, 0)


def test_perfect_predictor_scores_one() -> None:
    labels = [0, 1, 2, 3, 4, 0, 1]
    report = score_holdout(
        labels=np.asarray(labels),
        probs=_onehot_probs(labels),
        season=2026,
        **_MODEL_KWARGS,
    )
    assert report.headline["top1_accuracy"] == pytest.approx(1.0)
    assert report.headline["top2_accuracy"] == pytest.approx(1.0)
    for c in report.per_class:
        if c.support:
            assert c.top1_recall == pytest.approx(1.0)
            assert c.top2_recall == pytest.approx(1.0)


# --- (b) marginal baseline --------------------------------------------------


def test_marginal_top_k_accuracy_hand_computed() -> None:
    # counts: class 0 -> 3, class 1 -> 1, class 2 -> 1. Most frequent = 0.
    labels = np.array([0, 0, 0, 1, 2])
    assert marginal_top_k_accuracy(labels, 1) == pytest.approx(3 / 5)
    # top-2 = class 0 plus one of the count-1 classes -> 4/5 either way.
    assert marginal_top_k_accuracy(labels, 2) == pytest.approx(4 / 5)
    # k covering every class is always 1.0.
    assert marginal_top_k_accuracy(labels, N_CLASSES) == pytest.approx(1.0)


def test_marginal_baseline_lands_in_headline() -> None:
    labels = [0, 0, 0, 1, 2, 0]
    # A deliberately bad predictor (always class 4) - marginal must still reflect labels.
    probs = np.tile(np.array([0.05, 0.05, 0.05, 0.05, 0.8]), (len(labels), 1))
    report = score_holdout(
        labels=np.asarray(labels),
        probs=probs,
        season=2026,
        **_MODEL_KWARGS,
    )
    assert report.headline["top1_accuracy"] == pytest.approx(0.0)
    assert report.headline["marginal_top1_accuracy"] == pytest.approx(4 / 6)


# --- (c) rule-13 inverse season fence ---------------------------------------


def test_score_holdout_refuses_pre_holdout_season() -> None:
    labels = [0, 1, 2]
    for season in (2015, 2024, 2025):
        with pytest.raises(ValueError, match="rule 13"):
            score_holdout(
                labels=np.asarray(labels),
                probs=_onehot_probs(labels),
                season=season,
                **_MODEL_KWARGS,
            )


def test_score_holdout_allows_holdout_seasons() -> None:
    labels = [0, 1, 2]
    for season in (2026, 2027):
        report = score_holdout(
            labels=np.asarray(labels),
            probs=_onehot_probs(labels),
            season=season,
            **_MODEL_KWARGS,
        )
        assert report.season == season
        assert report.eval_kind == EVAL_KIND_HOLDOUT


def test_cli_fence_refuses_pre_holdout_and_exits() -> None:
    # The CLI's pre-data guard raises SystemExit before any bundle / data is touched.
    with pytest.raises(SystemExit, match="rule 13"):
        _refuse_non_holdout_cli(2025)
    with pytest.raises(SystemExit, match="rule 13"):
        _refuse_non_holdout_cli(2015)
    # Holdout seasons pass the fence.
    _refuse_non_holdout_cli(2026)
    _refuse_non_holdout_cli(2027)


# --- (d) report shape + round-trip ------------------------------------------


def _sample_report() -> HoldoutAccuracyReport:
    labels = [0, 0, 1, 2, 3, 4, 4, 0]
    return score_holdout(
        labels=np.asarray(labels),
        probs=_onehot_probs(labels),
        season=2026,
        **_MODEL_KWARGS,
    )


def test_report_to_dict_shape() -> None:
    payload = report_to_dict(_sample_report())
    assert payload["schema_version"] == 1
    assert payload["artifact_name"] == ARTIFACT_NAME == "pitch_post_backfill_accuracy"
    assert payload["model_name"] == "pitch_outcome_post"
    assert payload["model_version"] == "v1"
    assert payload["model_dir"] == "artifacts/pitch_outcome_post/v1"
    assert payload["season"] == 2026
    assert payload["class_order"] == list(LABEL_CLASSES)
    assert payload["n_pitches"] == 8
    assert set(payload["headline"]) == {
        "top1_accuracy",
        "top2_accuracy",
        "marginal_top1_accuracy",
        "marginal_top2_accuracy",
    }
    assert len(payload["per_class"]) == N_CLASSES
    for entry in payload["per_class"]:
        assert set(entry) == {
            "outcome",
            "support",
            "label_share",
            "top1_precision",
            "top1_recall",
            "top2_recall",
        }
    # Honesty metadata present and correct.
    assert payload["data_source"] == DATA_SOURCE == "historical_pitches_offline"
    assert payload["eval_kind"] == EVAL_KIND_HOLDOUT == "offline_holdout_unseen"
    assert payload["disclaimer"] == HOLDOUT_DISCLAIMER


def test_report_round_trip() -> None:
    report = _sample_report()
    restored = report_from_dict(report_to_dict(report))
    assert isinstance(restored, HoldoutAccuracyReport)
    assert restored == report


def test_report_from_dict_rejects_unknown_schema_version() -> None:
    with pytest.raises(ValueError, match="schema_version"):
        report_from_dict({"schema_version": 999})


def test_save_report_writes_json(tmp_path: Path) -> None:
    out = tmp_path / "sub" / "pitch_post_backfill_accuracy_v1.json"
    save_report(_sample_report(), out)
    payload = json.loads(out.read_text())
    assert payload["artifact_name"] == ARTIFACT_NAME
    assert payload["n_pitches"] == 8


# --- (e) per-class breakdown math -------------------------------------------


def test_per_class_breakdown_hand_computed() -> None:
    # labels: two class-0 rows, one class-4 row.
    labels = np.array([0, 0, 4])
    # Tie-free rows: np.argsort tie-breaking is stable-order-dependent, so equal
    # probabilities would make the expected top-2 membership ambiguous.
    probs = np.array(
        [
            [0.70, 0.15, 0.08, 0.04, 0.03],  # pred 0 (correct); top2 {0,1}
            [0.30, 0.50, 0.10, 0.06, 0.04],  # pred 1; top2 {0,1} still catches label 0
            [0.60, 0.20, 0.10, 0.06, 0.04],  # pred 0 (wrong; label 4, top2 {0,1} misses)
        ]
    )
    report = score_holdout(labels=labels, probs=probs, season=2026, **_MODEL_KWARGS)
    by_outcome = {c.outcome: c for c in report.per_class}

    ball = by_outcome["ball"]  # class 0
    assert ball.support == 2
    assert ball.label_share == pytest.approx(2 / 3)
    # class 0 predicted twice (rows 0 and 2), one true positive -> precision 1/2.
    assert ball.top1_precision == pytest.approx(1 / 2)
    assert ball.top1_recall == pytest.approx(1 / 2)
    assert ball.top2_recall == pytest.approx(1.0)

    in_play = by_outcome["in_play"]  # class 4
    assert in_play.support == 1
    assert in_play.top1_recall == pytest.approx(0.0)
    assert in_play.top2_recall == pytest.approx(0.0)

    unseen = by_outcome["called_strike"]  # class 1: never a true label
    assert unseen.support == 0
    assert unseen.top1_recall == 0.0
    assert unseen.top2_recall == 0.0


# --- (f) guards --------------------------------------------------------------


def test_score_holdout_rejects_bad_shapes_and_ranges() -> None:
    labels = np.array([0, 1])
    with pytest.raises(ValueError, match="probs must be"):
        score_holdout(labels=labels, probs=np.zeros((2, 4)), season=2026, **_MODEL_KWARGS)
    with pytest.raises(ValueError, match="labels must be"):
        score_holdout(
            labels=np.array([0]), probs=_onehot_probs([0, 1]), season=2026, **_MODEL_KWARGS
        )
    with pytest.raises(ValueError, match="no pitches"):
        score_holdout(
            labels=np.empty((0,), dtype=np.int64),
            probs=np.empty((0, N_CLASSES)),
            season=2026,
            **_MODEL_KWARGS,
        )
    with pytest.raises(ValueError, match="label values"):
        score_holdout(
            labels=np.array([0, 7]), probs=_onehot_probs([0, 1]), season=2026, **_MODEL_KWARGS
        )


# --- (g) _shared: label mapping ---------------------------------------------


def test_labels_to_int_maps_label_classes_in_order() -> None:
    assert {cls: i for i, cls in enumerate(LABEL_CLASSES)} == LABEL_TO_INT
    out = labels_to_int(pd.Series(list(LABEL_CLASSES)))
    assert out.dtype == np.int64
    assert list(out) == list(range(N_CLASSES))
    # Plain sequences work too.
    assert list(labels_to_int(["in_play", "ball"])) == [4, 0]


def test_labels_to_int_raises_on_unknown_label() -> None:
    with pytest.raises(ValueError, match="unknown outcome label"):
        labels_to_int(pd.Series(["ball", "hit_by_pitch"]))


# --- (h) _shared: batched ONNX inference ------------------------------------


class _FakeSession:
    """Stands in for ort.InferenceSession: records batch sizes, echoes a
    deterministic per-row probability (row-index-derived) so concatenation
    order is verifiable. Two outputs, mirroring convert_lightgbm zipmap=False
    ([label, probabilities])."""

    def __init__(self) -> None:
        self.batch_sizes: list[int] = []

    def run(self, output_names: object, feeds: dict[str, np.ndarray]) -> list[np.ndarray]:
        batch = feeds["input"]
        self.batch_sizes.append(batch.shape[0])
        labels = np.zeros((batch.shape[0],), dtype=np.int64)
        # Row-identifying probs: first column carries the row's feature value.
        probs = np.tile(batch[:, :1], (1, N_CLASSES)).astype(np.float32)
        return [labels, probs]


def test_onnx_probabilities_batches_and_concatenates() -> None:
    session = _FakeSession()
    features = np.arange(10, dtype=np.float32).reshape(10, 1)
    out = onnx_probabilities(session, features, batch_size=4)  # type: ignore[arg-type]
    assert session.batch_sizes == [4, 4, 2]
    assert out.shape == (10, N_CLASSES)
    assert out.dtype == np.float64
    # Row order preserved across batches.
    np.testing.assert_allclose(out[:, 0], np.arange(10, dtype=np.float64))


def test_onnx_probabilities_single_output_graph() -> None:
    class _SingleOutput:
        def run(self, output_names: object, feeds: dict[str, np.ndarray]) -> list[np.ndarray]:
            batch = feeds["input"]
            return [np.full((batch.shape[0], N_CLASSES), 0.2, dtype=np.float32)]

    out = onnx_probabilities(_SingleOutput(), np.zeros((3, 2), dtype=np.float32))  # type: ignore[arg-type]
    assert out.shape == (3, N_CLASSES)
    np.testing.assert_allclose(out, 0.2)


def test_onnx_probabilities_rejects_bad_input() -> None:
    session = _FakeSession()
    with pytest.raises(ValueError, match="2-D"):
        onnx_probabilities(session, np.zeros((3,), dtype=np.float32))  # type: ignore[arg-type]
    with pytest.raises(ValueError, match="no rows"):
        onnx_probabilities(session, np.zeros((0, 2), dtype=np.float32))  # type: ignore[arg-type]
    with pytest.raises(ValueError, match="batch_size"):
        onnx_probabilities(session, np.zeros((2, 2), dtype=np.float32), batch_size=0)  # type: ignore[arg-type]

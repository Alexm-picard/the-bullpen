"""Unit tests for the OFFLINE batted-ball backfill accuracy job (Phase 3 PR-alpha).

Pure-Python synthetic fixtures - no torch / no onnxruntime / no docker. The
box-only :class:`OnnxMlpPredictor` is deliberately NOT imported here (it needs a
real ONNX session + a registered bundle); these tests exercise the pure scoring
surface (:func:`score_backfill`, the report dict round-trip, rule 13) with a fake
:class:`Predictor`.

The load-bearing test is the honesty one: scoring is against the REAL realized
``label``, NEVER the ``retro_*`` distribution the MLP trained on (test (c)).
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

from bullpen_training.battedball.eval.backfill_accuracy import (
    DATA_SOURCE,
    EVAL_KIND_HOLDOUT,
    EVAL_KIND_IN_SAMPLE,
    BackfillAccuracyReport,
    report_from_dict,
    report_to_dict,
    save_report,
    score_backfill,
)
from bullpen_training.battedball.eval.comparison import class_precision_recall
from bullpen_training.battedball.features_shared import FEATURE_NAMES, OUTCOME_NAMES

_PARKS = ("BOS", "NYY", "LAD")
_DISCLAIMER = "test disclaimer: offline held-out, scored vs realized label, in-sample caveat noted."


# --- fakes / builders -----------------------------------------------------


class _OneHotPredictor:
    """Returns a perfect one-hot prediction for each row's realized label.

    Reads ``df["_truth"]`` (a test-only column the real predictor never sees) so
    we can assert a perfect predictor scores Brier 0 / accuracy 1.
    """

    def predict_proba(self, df: pd.DataFrame) -> np.ndarray:
        truth = df["_truth"].to_numpy(dtype=int)
        out = np.zeros((len(df), len(OUTCOME_NAMES)), dtype=np.float64)
        out[np.arange(len(df)), truth] = 1.0
        return out


class _RetroArgmaxPredictor:
    """Predicts a one-hot at the argmax of the row's ``retro_*`` distribution.

    Used by the honesty test: when the realized ``label`` disagrees with the retro
    argmax, a predictor that nails the retro distribution must still be SCORED
    against the realized label - so its reported accuracy reflects ``label``.
    """

    def predict_proba(self, df: pd.DataFrame) -> np.ndarray:
        retro = df[[f"retro_{i}" for i in range(len(OUTCOME_NAMES))]].to_numpy(dtype=np.float64)
        argmax = retro.argmax(axis=1)
        out = np.zeros_like(retro)
        out[np.arange(len(df)), argmax] = 1.0
        return out


def _frame(labels: list[int], parks: list[str], retro: np.ndarray | None = None) -> pd.DataFrame:
    """Build a feature frame carrying every production feature column + label + park.

    ``_truth`` mirrors ``label`` so a predictor can return a perfect prediction
    without the scorer reading it (the scorer reads ``label`` only).
    """
    n = len(labels)
    rng = np.random.default_rng(0)
    cols: dict[str, np.ndarray] = {}
    for name in FEATURE_NAMES:
        cols[name] = rng.normal(0.0, 1.0, n).astype("float32")
    df = pd.DataFrame(cols)
    df["label"] = np.asarray(labels, dtype=np.int64)
    df["_truth"] = np.asarray(labels, dtype=np.int64)
    df["park"] = parks
    if retro is None:
        # default retro = one-hot on the realized label (agrees with label)
        retro = np.eye(len(OUTCOME_NAMES))[np.asarray(labels)]
    for i in range(len(OUTCOME_NAMES)):
        df[f"retro_{i}"] = retro[:, i].astype("float32")
    return df


# --- (a) metric correctness: perfect predictor -----------------------------


def test_perfect_predictor_scores_brier_zero_accuracy_one() -> None:
    # Every park appears so per_park_metrics (which requires non-empty parks) holds.
    labels = [0, 1, 2, 3, 4, 0, 1, 2, 3, 4, 0, 4]
    parks = ["BOS", "NYY", "LAD", "BOS", "NYY", "LAD", "BOS", "NYY", "LAD", "BOS", "NYY", "LAD"]
    df = _frame(labels, parks)
    report = score_backfill(
        predictor=_OneHotPredictor(),
        df=df,
        park_order=_PARKS,
        model_name="battedball_outcome",
        model_version="v1",
        season_from=2015,
        season_to=2025,
        disclaimer=_DISCLAIMER,
    )
    assert report.aggregate["brier"] == pytest.approx(0.0, abs=1e-12)
    assert report.aggregate["log_loss"] == pytest.approx(0.0, abs=1e-9)
    assert report.aggregate["accuracy"] == pytest.approx(1.0, abs=1e-12)
    # A perfect predictor is perfectly calibrated -> ECE ~ 0.
    assert report.aggregate["ece"] == pytest.approx(0.0, abs=1e-9)
    # confusion is purely diagonal.
    confusion = np.asarray(report.confusion)
    assert int(confusion.sum()) == len(labels)
    assert int(np.trace(confusion)) == len(labels)
    # HR (class 4) precision + recall are perfect (3 HRs, all caught).
    assert report.hr_precision == pytest.approx(1.0)
    assert report.hr_recall == pytest.approx(1.0)


# --- (b) class_precision_recall on a known confusion -----------------------


def test_class_precision_recall_hand_computed_hr() -> None:
    # 5x5 [true][pred]. Construct a known HR (class 4) cell:
    #   true HR rows: confusion[4] = [1, 0, 0, 0, 3]  -> 4 true HRs, 3 predicted HR (TP=3)
    #   predicted HR col: confusion[*][4] = [1(out), 0, 0, 0, 3(hr)] -> 4 predicted HR (FP=1)
    # precision = 3 / 4 = 0.75 ; recall = 3 / 4 = 0.75 ; F1 = 0.75 ; support = 4.
    confusion = [
        [10, 0, 0, 0, 1],  # true out: one predicted as HR (the FP into class 4)
        [0, 5, 0, 0, 0],
        [0, 0, 3, 0, 0],
        [0, 0, 0, 2, 0],
        [1, 0, 0, 0, 3],  # true HR
    ]
    metrics = class_precision_recall(confusion, OUTCOME_NAMES)
    hr = next(c for c in metrics if c.outcome == "hr")
    assert hr.support == 4
    assert hr.precision == pytest.approx(0.75)
    assert hr.recall == pytest.approx(0.75)
    assert hr.f1 == pytest.approx(0.75)
    # "out" class: TP=10, col-sum = 10 + 1(from true HR) = 11, row-sum = 11.
    out = next(c for c in metrics if c.outcome == "out")
    assert out.support == 11
    assert out.precision == pytest.approx(10 / 11)
    assert out.recall == pytest.approx(10 / 11)


def test_class_precision_recall_zero_guarded_on_unpredicted_class() -> None:
    # class 3 never predicted (column all-zero) and never observed (row all-zero):
    confusion = [
        [4, 0, 0, 0, 0],
        [0, 4, 0, 0, 0],
        [0, 0, 4, 0, 0],
        [0, 0, 0, 0, 0],  # true class 3: never observed
        [0, 0, 0, 0, 4],
    ]
    metrics = class_precision_recall(confusion, OUTCOME_NAMES)
    c3 = metrics[3]
    assert c3.support == 0
    assert c3.precision == 0.0
    assert c3.recall == 0.0
    assert c3.f1 == 0.0


def test_class_precision_recall_rejects_non_square() -> None:
    with pytest.raises(ValueError, match="to match outcome_order"):
        class_precision_recall([[1, 2, 3]], OUTCOME_NAMES)


# --- (c) HONESTY: scored vs the REAL label, not retro ----------------------


def test_score_uses_real_label_not_retro_argmax() -> None:
    # Realized labels are all "out" (0); the retro distribution argmaxes to "hr" (4)
    # on every row. A predictor that nails retro (predicts HR) must be SCORED against
    # the realized label (out) -> accuracy 0, and the confusion must show true=out,
    # pred=hr, NOT a well-calibrated retro match.
    n = 9
    labels = [0] * n
    parks = (["BOS", "NYY", "LAD"] * 3)[:n]
    retro = np.zeros((n, len(OUTCOME_NAMES)))
    retro[:, 4] = 0.9  # peak on HR
    retro[:, 0] = 0.1
    df = _frame(labels, parks, retro=retro)

    report = score_backfill(
        predictor=_RetroArgmaxPredictor(),  # predicts HR (the retro argmax)
        df=df,
        park_order=_PARKS,
        model_name="battedball_outcome",
        model_version="v1",
        season_from=2015,
        season_to=2025,
        disclaimer=_DISCLAIMER,
    )
    # Scored vs realized label (all out), predictor said all HR -> accuracy 0.
    assert report.aggregate["accuracy"] == pytest.approx(0.0)
    confusion = np.asarray(report.confusion)
    # All mass at [true=out=0][pred=hr=4]; nothing on the diagonal.
    assert confusion[0, 4] == n
    assert int(np.trace(confusion)) == 0
    # HR recall is 0 (no true HRs) and HR precision is 0 (every HR prediction was wrong).
    assert report.hr_recall == 0.0
    assert report.hr_precision == 0.0
    # If this had (wrongly) scored vs retro, the retro-argmax predictor would look
    # PERFECT (accuracy 1). The assert above is the placebo guard.


def test_score_against_label_when_retro_disagrees_on_some_rows() -> None:
    # Mixed: half the rows have realized label == retro argmax, half disagree. The
    # reported accuracy must track the REALIZED label.
    labels = [0, 4, 0, 4, 0, 4]
    parks = ["BOS", "NYY", "LAD", "BOS", "NYY", "LAD"]
    # retro always peaks on HR (4); so it agrees only on the label==4 rows.
    retro = np.tile(np.array([0.1, 0.0, 0.0, 0.0, 0.9]), (6, 1))
    df = _frame(labels, parks, retro=retro)
    report = score_backfill(
        predictor=_RetroArgmaxPredictor(),
        df=df,
        park_order=_PARKS,
        model_name="m",
        model_version="v1",
        season_from=2015,
        season_to=2025,
        disclaimer=_DISCLAIMER,
    )
    # 3 of 6 realized labels are HR (match the retro-argmax predictor) -> accuracy 0.5.
    assert report.aggregate["accuracy"] == pytest.approx(0.5)


# --- (d) report dict round-trip + honesty metadata -------------------------


def test_report_to_from_dict_round_trip_and_metadata() -> None:
    labels = [0, 1, 2, 3, 4, 0]
    parks = ["BOS", "NYY", "LAD", "BOS", "NYY", "LAD"]
    df = _frame(labels, parks)
    report = score_backfill(
        predictor=_OneHotPredictor(),
        df=df,
        park_order=_PARKS,
        model_name="battedball_outcome",
        model_version="v1",
        season_from=2015,
        season_to=2025,
        disclaimer=_DISCLAIMER,
    )
    payload = report_to_dict(report)
    # Honesty metadata is present and correct.
    assert payload["data_source"] == DATA_SOURCE == "historical_pitches_offline"
    # 2015-2025 span -> the model trained on these years, so this is an IN-SAMPLE read.
    assert payload["eval_kind"] == EVAL_KIND_IN_SAMPLE == "offline_in_sample"
    assert payload["disclaimer"] == _DISCLAIMER
    assert payload["schema_version"] == 1
    assert payload["artifact_name"] == "battedball_backfill_accuracy"

    restored = report_from_dict(payload)
    assert isinstance(restored, BackfillAccuracyReport)
    assert restored.park_order == report.park_order
    assert restored.outcome_order == report.outcome_order
    assert restored.n_samples == report.n_samples
    assert restored.aggregate == pytest.approx(report.aggregate)
    assert restored.hr_precision == pytest.approx(report.hr_precision)
    assert restored.confusion == report.confusion
    assert [c.outcome for c in restored.per_class] == [c.outcome for c in report.per_class]
    assert restored.data_source == report.data_source
    assert restored.disclaimer == report.disclaimer


def test_report_from_dict_rejects_unknown_schema_version() -> None:
    with pytest.raises(ValueError, match="schema_version"):
        report_from_dict({"schema_version": 999})


def test_save_report_writes_json_and_html(tmp_path: Path) -> None:
    labels = [0, 1, 2, 3, 4, 0]
    parks = ["BOS", "NYY", "LAD", "BOS", "NYY", "LAD"]
    df = _frame(labels, parks)
    report = score_backfill(
        predictor=_OneHotPredictor(),
        df=df,
        park_order=_PARKS,
        model_name="battedball_outcome",
        model_version="v1",
        season_from=2015,
        season_to=2025,
        disclaimer=_DISCLAIMER,
    )
    j = tmp_path / "backfill.json"
    h = tmp_path / "backfill.html"
    save_report(report, j, h)
    assert j.exists() and h.exists()
    payload = json.loads(j.read_text())
    assert payload["artifact_name"] == "battedball_backfill_accuracy"
    html = h.read_text()
    assert "OFFLINE held-out eval" in html
    assert _DISCLAIMER in html


# --- (e) rule 13: refuse season >= 2026 ------------------------------------


def test_score_backfill_refuses_holdout_season() -> None:
    df = _frame([0, 1, 2], ["BOS", "NYY", "LAD"])
    with pytest.raises(ValueError, match="rule 13"):
        score_backfill(
            predictor=_OneHotPredictor(),
            df=df,
            park_order=_PARKS,
            model_name="m",
            model_version="v1",
            season_from=2015,
            season_to=2026,  # touches holdout
            disclaimer=_DISCLAIMER,
        )
    with pytest.raises(ValueError, match="rule 13"):
        score_backfill(
            predictor=_OneHotPredictor(),
            df=df,
            park_order=_PARKS,
            model_name="m",
            model_version="v1",
            season_from=2026,
            season_to=2027,
            disclaimer=_DISCLAIMER,
        )


def test_score_backfill_allows_2026_holdout_with_opt_in() -> None:
    # Rule-13 carve-out: 2026 IS scoreable for a post-training ACCURACY read when explicitly opted
    # in, and the artifact is honestly stamped as the unseen out-of-sample holdout read.
    df = _frame([0, 1, 2], ["BOS", "NYY", "LAD"])
    report = score_backfill(
        predictor=_OneHotPredictor(),
        df=df,
        park_order=_PARKS,
        model_name="battedball_outcome",
        model_version="v1",
        season_from=2026,
        season_to=2026,
        disclaimer=_DISCLAIMER,
        allow_holdout_eval=True,
    )
    assert report.eval_kind == EVAL_KIND_HOLDOUT == "offline_holdout_unseen"


def _load_cli_module():
    """Load the CLI script by file path (scripts/ is not an importable package)."""
    import importlib.util

    script = Path(__file__).resolve().parents[3] / "scripts" / "run_battedball_backfill_accuracy.py"
    spec = importlib.util.spec_from_file_location("_backfill_cli_under_test", script)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_cli_refuse_holdout_exits() -> None:
    # The CLI's pre-data guard raises SystemExit before any bundle / data is touched.
    cli = _load_cli_module()

    with pytest.raises(SystemExit, match="rule 13"):
        cli._refuse_holdout(2015, 2026)
    with pytest.raises(SystemExit, match="rule 13"):
        cli._refuse_holdout(2026, 2026)
    # A legal training-years span does not raise.
    cli._refuse_holdout(2015, 2025)
    # The explicit rule-13 carve-out opt-in permits the 2026 holdout for the accuracy read.
    cli._refuse_holdout(2026, 2026, allow_holdout_eval=True)


# --- guards ----------------------------------------------------------------


def test_score_backfill_rejects_missing_label_column() -> None:
    df = _frame([0, 1, 2], ["BOS", "NYY", "LAD"]).drop(columns=["label"])
    with pytest.raises(ValueError, match="label"):
        score_backfill(
            predictor=_OneHotPredictor(),
            df=df,
            park_order=_PARKS,
            model_name="m",
            model_version="v1",
            season_from=2015,
            season_to=2025,
            disclaimer=_DISCLAIMER,
        )


def test_score_backfill_rejects_bad_proba_shape() -> None:
    class _BadShape:
        def predict_proba(self, df: pd.DataFrame) -> np.ndarray:
            return np.zeros((len(df), 4))  # 4 != 5 outcomes

    df = _frame([0, 1, 2], ["BOS", "NYY", "LAD"])
    with pytest.raises(ValueError, match="predict_proba must return"):
        score_backfill(
            predictor=_BadShape(),
            df=df,
            park_order=_PARKS,
            model_name="m",
            model_version="v1",
            season_from=2015,
            season_to=2025,
            disclaimer=_DISCLAIMER,
        )

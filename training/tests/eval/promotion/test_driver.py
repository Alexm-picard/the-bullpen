"""End-to-end tests for the promotion-evidence driver (W5).

Proves the gate evaluates on sample data for all three models, that the
artifact is experiment_results-shaped, and that the rolling-origin discipline
holds (4 folds, 2026 absent, no random splits)."""

from __future__ import annotations

from pathlib import Path

import pytest

from bullpen_training.eval.promotion.driver import (
    experiment_results_artifact,
    run_evidence,
    write_artifact,
)
from bullpen_training.eval.promotion.sample_loader import generate_sample_dataset


@pytest.fixture(scope="module")
def sample_root(tmp_path_factory: pytest.TempPathFactory) -> Path:
    root = tmp_path_factory.mktemp("samples_dev")
    for m in ("pitch_outcome_pre", "pitch_outcome_post", "batted_ball_lr_baseline"):
        generate_sample_dataset(root, m, rows_per_year=800)
    return root


@pytest.mark.parametrize(
    "model_name",
    ["pitch_outcome_pre", "pitch_outcome_post", "batted_ball_lr_baseline"],
)
def test_evidence_run_produces_verdict_for_each_model(model_name: str, sample_root: Path) -> None:
    run = run_evidence(model_name, sample_root=sample_root, rows_per_year=800)
    # rolling-origin: 4 folds for both predictors.
    assert len(run.baseline_cv.per_fold) == 4
    assert len(run.challenger_cv.per_fold) == 4
    # the verdict scored some rows on the final test year.
    assert run.verdict.sample_size_observed > 0
    assert run.final_test_year == 2025


@pytest.mark.parametrize(
    "model_name",
    ["pitch_outcome_pre", "pitch_outcome_post", "batted_ball_lr_baseline"],
)
def test_artifact_is_experiment_results_shaped(model_name: str, sample_root: Path) -> None:
    run = run_evidence(model_name, sample_root=sample_root, rows_per_year=800)
    art = experiment_results_artifact(run)
    # V012 experiment_results column analogues are all present.
    for key in (
        "model_name",
        "champion_model_name",
        "challenger_model_name",
        "primary_metric",
        "primary_threshold",
        "guardrails",
        "sample_size_target",
        "sample_size_observed",
        "champion_metric",
        "challenger_metric",
        "guardrails_observed",
        "status",
    ):
        assert key in art, f"missing experiment_results field {key}"
    assert art["status"] in {"passed", "failed"}
    assert art["data_source"] == "sample"
    assert art["primary_metric"] in {"brier", "log-loss", "ece"}
    # pre-declared criteria are copied in (rule 5 - gate is self-describing).
    assert art["pre_declared_criteria"]["primary_threshold"] == run.criteria.primary_threshold


def test_artifact_records_rolling_origin_folds_without_2026(sample_root: Path) -> None:
    run = run_evidence("pitch_outcome_pre", sample_root=sample_root, rows_per_year=800)
    art = experiment_results_artifact(run)
    folds = art["rolling_origin_cv"]["folds"]
    assert len(folds) == 4
    touched_years = set()
    for f in folds:
        touched_years.update(
            {f["train_start_year"], f["train_end_year"], f["val_year"], f["test_year"]}
        )
    assert 2026 not in touched_years  # rule 13
    assert max(touched_years) == 2025


def test_challenger_beats_baseline_on_sample(sample_root: Path) -> None:
    """The generator embeds a non-linear signal, so the LightGBM challenger
    should beat the LR baseline's Brier (the gate then demonstrates a real
    challenger-vs-baseline pass on sample data)."""
    run = run_evidence("pitch_outcome_pre", sample_root=sample_root, rows_per_year=800)
    v = run.verdict
    assert v.challenger_metrics.brier < v.baseline_metrics.brier


def test_batted_ball_lr_beats_marginal_floor(sample_root: Path) -> None:
    """The batted-ball LR (challenger) must beat the degenerate marginal-class
    floor (baseline)."""
    run = run_evidence("batted_ball_lr_baseline", sample_root=sample_root, rows_per_year=800)
    v = run.verdict
    assert v.challenger_metrics.brier < v.baseline_metrics.brier


def test_artifact_records_absolute_ece_supplementary_check(sample_root: Path) -> None:
    """Every model declares an absolute Phase-2 ECE bar, so the artifact carries
    a supplementary check and the final status folds it in."""
    run = run_evidence("batted_ball_lr_baseline", sample_root=sample_root, rows_per_year=800)
    art = experiment_results_artifact(run)
    checks = art["supplementary_checks"]
    assert len(checks) == 1
    chk = checks[0]
    assert chk["name"] == "absolute_ece_phase2_bar"
    assert chk["max_allowed"] == run.criteria.absolute_ece_bar
    # final status reflects supplementary_ok too.
    assert art["verdict"]["supplementary_checks_passed"] == chk["passed"]


def test_write_artifact_round_trips(tmp_path: Path, sample_root: Path) -> None:
    run = run_evidence("pitch_outcome_pre", sample_root=sample_root, rows_per_year=800)
    path = write_artifact(run, tmp_path)
    assert path.is_file()
    import json

    loaded = json.loads(path.read_text())
    assert loaded["model_name"] == "pitch_outcome_pre"

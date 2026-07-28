"""Tests for the pitch_type offline promotion-gate emitter.

Lives under ``tests/scripts`` because that directory is DIRECTORY-GLOBBED by training.yml (unlike
``tests/eval``, whose files are enumerated one by one and would silently never run). The code
under test lives in ``src/`` so it is covered by pyright and coverage.

WHAT THESE PIN, and why the list is shaped this way: two reviewers independently found that the
first version of this file tested the guards but not their WIRING, and that its
derives-from-criteria test asserted values rather than derivation - a copy of the module with
criteria.py stripped out and replaced by literals passed every test. Both of those are now
mutation-verified below, along with the boundaries, the sample-size lane, and the filename
three-way contract.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

from bullpen_training.eval.promotion import pitch_type_gate as gate_mod
from bullpen_training.eval.promotion.criteria import (
    GuardrailSpec,
    PrimaryMetric,
    PromotionCriteria,
)
from bullpen_training.eval.promotion.pitch_type_gate import (
    Bundle,
    GateEvidenceError,
    assert_comparable,
    assert_importable,
    build_gate,
    read_bundle,
)

_LABELS = ["FF", "SI", "FC", "SL", "CU", "CH", "OFF"]


def _metrics(
    model_name: str, *, ece: float, log_loss: float, brier: float = 0.70, test_rows: int = 700_000
) -> dict[str, Any]:
    """A metrics.json carrying the VERBATIM key names cv_harness writes."""
    per_fold_metrics = {
        "multiclass_brier": brier,
        "multiclass_log_loss": log_loss,
        "expected_calibration_error": ece,
    }
    return {
        "model_name": model_name,
        "model_version": "v1",
        "class_labels": list(_LABELS),
        "n_folds": 4,
        "summary": {k: {"mean": v, "std": 0.0003} for k, v in per_fold_metrics.items()},
        "per_fold": [
            {
                "fold_id": i,
                "train_rows": 1_000_000 * i,
                "val_rows": 300_000,
                "test_rows": test_rows,
                "metrics": dict(per_fold_metrics),
            }
            for i in range(1, 5)
        ],
    }


def _bundle(model_name: str, **kw: Any) -> Bundle:
    return Bundle(
        metrics=_metrics(model_name, **kw),
        metadata={
            "git_commit": "deadbeef",
            "trained_at": "2026-07-28T05:00:00Z",
            "feature_pipeline_hash": "a2a5d474",
            "training_data_hash": "cafe1234",
        },
        path=Path(f"/box/{model_name}/v1"),
        metrics_sha256="0" * 64,
    )


def _pair(
    *,
    chal_ece: float = 0.0036,
    champ_ece: float = 0.0120,
    chal_ll: float = 1.60,
    champ_ll: float = 1.75,
    test_rows: int = 700_000,
) -> tuple[Bundle, Bundle]:
    return (
        _bundle("pitch_type_pre", ece=chal_ece, log_loss=chal_ll, test_rows=test_rows),
        _bundle("pitch_type_lr_baseline", ece=champ_ece, log_loss=champ_ll, test_rows=test_rows),
    )


def _write_bundle(root: Path, model_name: str, **kw: Any) -> Path:
    bundle = root / model_name / "v1"
    (bundle / "eval").mkdir(parents=True)
    (bundle / "eval" / "metrics.json").write_text(json.dumps(_metrics(model_name, **kw)))
    (bundle / "metadata.json").write_text(
        json.dumps(
            {
                "git_commit": "abc123",
                "feature_pipeline_hash": "a2a5d474",
                "training_data_hash": "cafe1234",
            }
        )
    )
    return bundle


# --- the derivation, which is HALF THE ANTI-BYPASS ---------------------------------------


def test_the_gate_moves_with_the_declared_criteria(monkeypatch: pytest.MonkeyPatch):
    """THE test the first version of this file only claimed to be.

    A reviewer stripped criteria.py out of a copy of the module - replacing every derived read
    with the same literals - and all tests still passed, because they asserted VALUES rather than
    DERIVATION. That matters far more than tidiness: `guardrails` is ARTIFACT-SUPPLIED and is
    exactly what the Java importer's guardrail loop iterates. Java has no access to criteria.py,
    so an artifact that hardcodes (or empties) that map silently becomes an anti-bypass hole.
    """
    fake = PromotionCriteria(
        model_name="pitch_type_pre",
        primary_metric=PrimaryMetric.BRIER,  # not ECE
        primary_threshold=-0.5,
        sample_size_target=9_999,
        guardrails=(GuardrailSpec(metric=PrimaryMetric.LOG_LOSS, max_delta=0.25),),
        absolute_ece_bar=0.99,
    )
    monkeypatch.setattr(gate_mod, "criteria_for", lambda _name: fake)

    gate = build_gate(*_pair())

    assert gate["primary_metric"] == "brier", "primary metric must follow the declaration"
    assert gate["sample_size_target"] == 9_999
    assert gate["guardrails"] == {"log-loss": 0.25}
    assert gate["declared_primary_threshold"] == -0.5
    assert gate["supplementary_checks"][0]["max_allowed"] == 0.99
    # ...and the metric it READ moved with it: the fixtures' brier is equal on both sides, so the
    # observed margin is 0.0 here, where the real ECE-based criteria give -0.0084.
    assert gate["verdict"]["primary_margin_observed"] == 0.0


def test_a_criteria_declaring_no_guardrails_is_refused(monkeypatch: pytest.MonkeyPatch):
    """An empty guardrail map imports with ZERO guardrail enforcement on the Java side."""
    fake = PromotionCriteria(
        model_name="pitch_type_pre",
        primary_metric=PrimaryMetric.ECE,
        primary_threshold=-0.02,
        sample_size_target=2_000,
        guardrails=(),
        absolute_ece_bar=0.02,
    )
    monkeypatch.setattr(gate_mod, "criteria_for", lambda _name: fake)
    with pytest.raises(GateEvidenceError, match="declares no guardrails"):
        build_gate(*_pair())


def test_real_criteria_give_pitch_type_exactly_one_guardrail():
    """A literal copy of the sibling emitter would have invented an undeclared brier guardrail."""
    gate = build_gate(*_pair())
    assert gate["guardrails"] == {"log-loss": 0.0}
    assert "brier" not in gate["guardrails"]
    assert gate["primary_metric"] == "ece"
    assert gate["declared_primary_threshold"] == -0.02
    assert gate["primary_threshold"] == 0.0


# --- the three lanes ----------------------------------------------------------------------


@pytest.mark.parametrize(
    ("chal_ece", "champ_ece", "expect"),
    [
        (0.0036, 0.0120, "passed"),  # clearly better
        (0.0120, 0.0120, "passed"),  # EXACT equality: <= must admit it
        (0.0121, 0.0120, "failed"),  # a hair worse
        (0.0190, 0.0120, "failed"),  # worse; would PASS at the vacuous -0.02
    ],
)
def test_primary_lane_boundaries(chal_ece: float, champ_ece: float, expect: str):
    assert build_gate(*_pair(chal_ece=chal_ece, champ_ece=champ_ece))["status"] == expect


@pytest.mark.parametrize(
    ("chal_ece", "expect"), [(0.0199, "passed"), (0.02, "failed"), (0.0201, "failed")]
)
def test_absolute_bar_is_strict_and_gates_status(chal_ece: float, expect: str):
    """[183]'s declared primary. Java has no field for it, so it must bite here or nowhere.

    champ_ece is deliberately WORSE so the relative lane passes: only the absolute bar can be
    what fails these.
    """
    gate = build_gate(*_pair(chal_ece=chal_ece, champ_ece=0.05))
    assert gate["challenger_metric"] <= gate["champion_metric"]
    assert gate["status"] == expect


@pytest.mark.parametrize(
    ("chal_ll", "champ_ll", "expect"),
    [(1.60, 1.75, "passed"), (1.75, 1.75, "passed"), (1.7500001, 1.75, "failed")],
)
def test_guardrail_lane_boundaries(chal_ll: float, champ_ll: float, expect: str):
    gate = build_gate(*_pair(chal_ll=chal_ll, champ_ll=champ_ll))
    assert gate["status"] == expect
    if expect == "failed":
        assert "log-loss" in gate["guardrails_violated"]


@pytest.mark.parametrize(
    ("test_rows", "met", "expect"),
    [(700_000, True, "passed"), (2_000, True, "passed"), (1_999, False, "failed")],
)
def test_sample_size_lane(test_rows: int, met: bool, expect: str):
    gate = build_gate(*_pair(test_rows=test_rows))
    assert gate["verdict"]["sample_size_met"] is met
    assert gate["status"] == expect


# --- comparability ------------------------------------------------------------------------


def test_mismatched_folds_are_refused():
    challenger, baseline = _pair()
    baseline.metrics["per_fold"][-1]["test_rows"] = 12_345
    with pytest.raises(GateEvidenceError, match="per-fold test row counts differ"):
        assert_comparable(challenger, baseline)


def test_mismatched_class_labels_are_refused():
    challenger, baseline = _pair()
    baseline.metrics["class_labels"] = ["FF", "SI"]
    with pytest.raises(GateEvidenceError, match="class_labels differ"):
        assert_comparable(challenger, baseline)


def test_mismatched_fold_count_is_refused():
    challenger, baseline = _pair()
    baseline.metrics["n_folds"] = 3
    with pytest.raises(GateEvidenceError, match="n_folds differ"):
        assert_comparable(challenger, baseline)


def test_empty_per_fold_is_refused_by_name_not_indexerror():
    challenger, baseline = _pair()
    challenger.metrics["per_fold"] = []
    with pytest.raises(GateEvidenceError, match="no per_fold entries"):
        assert_comparable(challenger, baseline)


@pytest.mark.parametrize("field", ["feature_pipeline_hash", "training_data_hash"])
def test_differing_data_or_contract_hashes_are_refused(field: str):
    """The realistic failure: two CLI runs hours apart against a MUTABLE feature store.

    Row counts can match while the feature sets differ, and [183]'s guardrail would then be
    comparing models trained on different features. Rule 7's idiom, on the evidence path.
    """
    challenger, baseline = _pair()
    baseline.metadata[field] = "different"
    with pytest.raises(GateEvidenceError, match=field):
        assert_comparable(challenger, baseline)


# --- the wiring, which the guards alone do not pin -----------------------------------------


def test_crossed_bundle_arguments_are_refused(tmp_path: Path):
    baseline_bundle = _write_bundle(tmp_path, "pitch_type_lr_baseline", ece=0.01, log_loss=1.7)
    with pytest.raises(GateEvidenceError, match="expected 'pitch_type_pre'"):
        read_bundle(baseline_bundle, "pitch_type_pre")
    ok = read_bundle(baseline_bundle, "pitch_type_lr_baseline")
    assert ok.metrics["model_name"] == "pitch_type_lr_baseline"
    assert len(ok.metrics_sha256) == 64


def test_emit_refuses_both_slots_pointed_at_the_same_bundle(tmp_path: Path):
    """MUTATION-PINNED (M28). Comparing a model against ITSELF yields margin 0.0, guardrail
    delta 0.0 and a fully self-consistent importable PASS - and the Java importer cannot detect
    it, since it verifies the champion VERSION's name, never that the numbers came from it.
    The guard is one argument in emit(); this pins that it is actually passed.
    """
    chal = _write_bundle(tmp_path, "pitch_type_pre", ece=0.0036, log_loss=1.60)
    out = tmp_path / "gate.json"
    with pytest.raises(GateEvidenceError, match="expected 'pitch_type_lr_baseline'"):
        gate_mod.emit(chal, chal, out)
    assert not out.exists()


@pytest.mark.parametrize(
    ("chal_ece", "chal_ll", "why"),
    [
        (0.0300, 1.60, "over the absolute bar"),
        (0.0190, 1.60, "worse than the baseline"),
        (0.0036, 1.90, "log-loss regression"),
    ],
)
def test_emit_never_writes_a_failed_gate(tmp_path: Path, chal_ece: float, chal_ll: float, why: str):
    """MUTATION-PINNED (M26). No partial artifact, and nothing to import."""
    chal = _write_bundle(tmp_path, "pitch_type_pre", ece=chal_ece, log_loss=chal_ll)
    base = _write_bundle(tmp_path, "pitch_type_lr_baseline", ece=0.0120, log_loss=1.75)
    out = tmp_path / "gate.json"
    with pytest.raises(GateEvidenceError, match="refusing to write a FAILED gate"):
        gate_mod.emit(chal, base, out)
    assert not out.exists(), why


def test_emit_enforces_comparability(tmp_path: Path):
    """MUTATION-PINNED (M27): deleting the assert_comparable call from emit() must red."""
    chal = _write_bundle(tmp_path, "pitch_type_pre", ece=0.0036, log_loss=1.60)
    base = _write_bundle(
        tmp_path, "pitch_type_lr_baseline", ece=0.0120, log_loss=1.75, test_rows=123
    )
    out = tmp_path / "gate.json"
    with pytest.raises(GateEvidenceError, match="per-fold test row counts differ"):
        gate_mod.emit(chal, base, out)
    assert not out.exists()


def test_emit_happy_path_writes_an_importable_artifact(tmp_path: Path):
    chal = _write_bundle(tmp_path, "pitch_type_pre", ece=0.0036, log_loss=1.60)
    base = _write_bundle(tmp_path, "pitch_type_lr_baseline", ece=0.0120, log_loss=1.75)
    out = tmp_path / "nested" / "gate.json"

    gate_mod.emit(chal, base, out)

    written = json.loads(out.read_text())
    assert written["status"] == "passed"
    assert written["verdict"]["passed"] is True
    assert written["verdict"]["sample_size_met"] is True
    assert written["model_name"] == "pitch_type_pre"
    assert written["champion_model_name"] == "pitch_type_lr_baseline"
    assert "carry_gate" not in written  # null == pass; emitting false would fail a non-carry model
    assert written["challenger_metric"] + written["primary_threshold"] <= written["champion_metric"]
    # dispersion the means discard, recorded per the statistician's note
    assert written["provenance"]["folds_won"]["ece"] == 4
    assert len(written["provenance"]["per_fold_deltas"]["ece"]) == 4
    assert (
        written["provenance"]["challenger_metrics_sha256"]
        != (written["provenance"]["baseline_metrics_sha256"])
    )


def test_a_missing_bundle_file_names_the_path(tmp_path: Path):
    with pytest.raises(GateEvidenceError, match=r"metrics\.json"):
        read_bundle(tmp_path / "nope" / "v1", "pitch_type_pre")


def test_missing_metric_key_names_the_keys_present():
    challenger, baseline = _pair()
    del challenger.metrics["summary"]["expected_calibration_error"]
    with pytest.raises(GateEvidenceError, match="expected_calibration_error"):
        build_gate(challenger, baseline)


# --- the filename three-way contract --------------------------------------------------------


def test_default_out_name_matches_the_gitignore_and_gradle_contracts():
    """Rename this and the artifact is silently never committed, never bundled, and
    import-offline 404s on the box with no local signal."""
    assert gate_mod.DEFAULT_OUT_NAME == "pitch_type_pre_promotion_gate.json"
    assert gate_mod.DEFAULT_OUT_NAME.endswith("_promotion_gate.json")
    # ...and must NOT match the glob that publishes to the public /accuracy scorecard.
    assert "_experiment_results_full" not in gate_mod.DEFAULT_OUT_NAME

    repo_root = Path(__file__).resolve().parents[3]
    gitignore = (repo_root / ".gitignore").read_text()
    assert f"!training/data/eval/promotion/{gate_mod.DEFAULT_OUT_NAME}" in gitignore, (
        "without the negation the artifact is silently untracked"
    )


def test_assert_importable_rejects_a_hand_edited_pass():
    """The declared side and the arithmetic must agree - the importer checks both."""
    gate = build_gate(*_pair())
    gate["guardrails_observed"]["log-loss"] = 0.5  # a regression the status still calls passed
    with pytest.raises(GateEvidenceError, match="guardrail log-loss regressed"):
        assert_importable(gate)

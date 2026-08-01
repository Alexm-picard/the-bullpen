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

import importlib.util
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
                # +i makes the folds DISTINGUISHABLE, so the artifact's "final fold only" claim
                # for sample_size_observed is testable: [-1] reads test_rows + 4, [0] would read
                # test_rows + 1. With identical rows per fold the two were indistinguishable and
                # the claim was pinned by nothing (reviewer AD-3).
                "test_rows": test_rows + i,
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


def test_every_primary_metric_has_a_bundle_mapping():
    """Reviewer AD-4: the two "no bundle mapping" raises are dead branches TODAY because this
    invariant holds. If PrimaryMetric gains a member without a DB_TO_BUNDLE entry, a declared
    guardrail on it would be silently unenforced on BOTH sides (a missing observed entry is
    skipped by the Java loop too). This reds on exactly the day those branches stop being dead.
    """
    assert set(gate_mod.DB_TO_BUNDLE) == {m.db_value for m in PrimaryMetric}


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
    gate = build_gate(*_pair(chal_ece=chal_ece, champ_ece=champ_ece))
    assert gate["status"] == expect
    # verdict.passed is one of the importer's THREE redundant declared-pass signals (status &&
    # verdict.passed && verdict.sampleSizeMet); a hardcoded True here survived every status-only
    # assertion (reviewer AD-1), silently removing one of the three.
    assert gate["verdict"]["passed"] is (expect == "passed")
    assert gate["verdict"]["outcome"] == (
        "would_pass" if expect == "passed" else "would_fail_primary"
    )


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
    assert gate["verdict"]["passed"] is (expect == "passed")
    # The absolute bar IS the declared [183] primary, so its failure is a primary failure.
    assert gate["verdict"]["outcome"] == (
        "would_pass" if expect == "passed" else "would_fail_primary"
    )


def test_absolute_bar_reads_ece_by_name_not_the_primary(monkeypatch: pytest.MonkeyPatch):
    """registry-guard AD1. The bar is an ECE bar BY NAME; under a re-aimed primary (a live
    pattern - ADR-0014 re-aimed pitch_outcome_pre's) conflating them would apply the bar to
    whatever the primary became while still labelling the check "ece".
    """
    fake = PromotionCriteria(
        model_name="pitch_type_pre",
        primary_metric=PrimaryMetric.BRIER,
        primary_threshold=0.0,
        sample_size_target=2_000,
        guardrails=(GuardrailSpec(metric=PrimaryMetric.LOG_LOSS, max_delta=0.0),),
        absolute_ece_bar=0.02,
    )
    monkeypatch.setattr(gate_mod, "criteria_for", lambda _name: fake)

    # ECE is comfortably under the bar; brier (the primary, 0.70) is far over it. Reading the
    # primary here would fail the bar; reading ECE passes it - and labels the ECE value.
    gate = build_gate(*_pair(chal_ece=0.0036))
    sup = gate["supplementary_checks"][0]
    assert sup["metric"] == "ece"
    assert sup["observed"] == 0.0036
    assert sup["passed"] is True
    assert gate["status"] == "passed"

    # ...and the converse: a good primary cannot smuggle a bad ECE past the bar.
    gate = build_gate(*_pair(chal_ece=0.05, champ_ece=0.06))
    assert gate["supplementary_checks"][0]["observed"] == 0.05
    assert gate["supplementary_checks"][0]["passed"] is False
    assert gate["status"] == "failed"


@pytest.mark.parametrize(
    ("chal_ll", "champ_ll", "expect"),
    [(1.60, 1.75, "passed"), (1.75, 1.75, "passed"), (1.7500001, 1.75, "failed")],
)
def test_guardrail_lane_boundaries(chal_ll: float, champ_ll: float, expect: str):
    gate = build_gate(*_pair(chal_ll=chal_ll, champ_ll=champ_ll))
    assert gate["status"] == expect
    assert gate["verdict"]["passed"] is (expect == "passed")
    if expect == "failed":
        assert "log-loss" in gate["guardrails_violated"]
        assert gate["verdict"]["outcome"] == "would_fail_guardrail"


def test_outcome_is_guardrail_first_when_both_lanes_fail():
    """Reviewer AD-2: criteria.evaluate_challenger_vs_baseline and the Java ExperimentService
    both resolve guardrail-first; the first version here was primary-first, so the three
    surfaces would disagree on the one field just aligned to their shared vocabulary."""
    gate = build_gate(*_pair(chal_ece=0.0190, chal_ll=1.90))
    assert gate["status"] == "failed"
    assert gate["verdict"]["outcome"] == "would_fail_guardrail"


@pytest.mark.parametrize(
    ("test_rows", "met", "expect"),
    # observed = test_rows + 4 (the FINAL fold; the fixture offsets folds by +i), so the exact
    # boundary against the 2_000 target sits at test_rows = 1_996.
    [(700_000, True, "passed"), (1_996, True, "passed"), (1_995, False, "failed")],
)
def test_sample_size_lane(test_rows: int, met: bool, expect: str):
    gate = build_gate(*_pair(test_rows=test_rows))
    assert gate["sample_size_observed"] == test_rows + 4
    assert gate["verdict"]["sample_size_met"] is met
    assert gate["status"] == expect
    # sample size is a SEPARATE signal from verdict.passed: the importer checks all three of
    # status, verdict.passed and verdict.sampleSizeMet, and a metrics-clean gate under target
    # must fail status while still reporting passed metrics.
    assert gate["verdict"]["passed"] is True


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


def test_build_gate_itself_refuses_empty_per_fold(monkeypatch: pytest.MonkeyPatch):
    """registry-guard AD2: the direct build_gate seam must raise the NAMED refusal too, not
    IndexError - emit() calls assert_comparable first, but build_gate is public."""
    challenger, baseline = _pair()
    challenger.metrics["per_fold"] = []
    baseline.metrics["per_fold"] = []
    with pytest.raises(GateEvidenceError, match="no per_fold entries"):
        build_gate(challenger, baseline)


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
    # the FINAL fold's rows (700_000 + 4), pinning the artifact's "final fold only" claim
    assert written["sample_size_observed"] == 700_004
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
    assert f"!training/{gate_mod.DEFAULT_OUT_RELPATH.as_posix()}" in gitignore, (
        "without the negation the artifact is silently untracked"
    )

    # The DIRECTORY half (registry-guard AD3): the shim writes training/<DEFAULT_OUT_RELPATH>,
    # and gradle's processResources bundles exactly that directory into offline-gate-evidence/.
    assert gate_mod.DEFAULT_OUT_RELPATH.name == gate_mod.DEFAULT_OUT_NAME
    gradle = (repo_root / "backend" / "build.gradle.kts").read_text()
    gradle_dir = f'from("../training/{gate_mod.DEFAULT_OUT_RELPATH.parent.as_posix()}")'
    assert gradle_dir in gradle, "the artifact would never be bundled for import-offline"


def test_assert_importable_rejects_a_hand_edited_pass():
    """The declared side and the arithmetic must agree - the importer checks both."""
    gate = build_gate(*_pair())
    gate["guardrails_observed"]["log-loss"] = 0.5  # a regression the status still calls passed
    with pytest.raises(GateEvidenceError, match="guardrail log-loss regressed"):
        assert_importable(gate)


# --- the shim ------------------------------------------------------------------------------


def _load_shim() -> Any:
    script = Path(__file__).resolve().parents[2] / "scripts" / "emit_pitch_type_promotion_gate.py"
    spec = importlib.util.spec_from_file_location("emit_pitch_type_promotion_gate", script)
    assert spec is not None and spec.loader is not None
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def test_shim_main_smoke(tmp_path: Path, capsys: pytest.CaptureFixture[str]):
    """Reviewer AD-5. The shim's print block reads nine keys out of the gate dict AFTER the
    artifact is written - a renamed key would give the operator a traceback and a non-zero exit
    with a perfectly valid artifact already on disk, which reads as "the emit failed" mid
    box hand-off. One smoke call exercises every key at once.
    """
    chal = _write_bundle(tmp_path, "pitch_type_pre", ece=0.0036, log_loss=1.60)
    base = _write_bundle(tmp_path, "pitch_type_lr_baseline", ece=0.0120, log_loss=1.75)
    out = tmp_path / "gate.json"

    rc = _load_shim().main(
        ["--challenger-bundle", str(chal), "--baseline-bundle", str(base), "--out", str(out)]
    )

    assert rc == 0
    assert out.exists()
    stdout = capsys.readouterr().out
    assert f"wrote {out}" in stdout
    assert "folds won on ece: 4/4" in stdout


def test_shim_main_fails_named_and_writes_nothing(tmp_path: Path):
    chal = _write_bundle(tmp_path, "pitch_type_pre", ece=0.0300, log_loss=1.60)
    base = _write_bundle(tmp_path, "pitch_type_lr_baseline", ece=0.0120, log_loss=1.75)
    out = tmp_path / "gate.json"
    with pytest.raises(SystemExit, match="refusing to write a FAILED gate"):
        _load_shim().main(
            ["--challenger-bundle", str(chal), "--baseline-bundle", str(base), "--out", str(out)]
        )
    assert not out.exists()

"""Unit tests for the pitch_type offline-gate emitter.

``build_gate`` is a pure function over two loaded bundles, so these need no ClickHouse, no
box artifacts, and no CV run. What they pin is the set of claims the Java importer's
anti-bypass will re-derive, plus the two claims it CANNOT re-derive and therefore must be
enforced here: the declared absolute ECE bar, and the fact that the imported threshold is 0.0
rather than the vacuous declared -0.02.

The emitter lives in ``training/scripts/`` (not an installed package), so it is loaded by path.
"""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path
from typing import Any

import pytest

_EMITTER = Path(__file__).resolve().parents[2] / "scripts" / "emit_pitch_type_promotion_gate.py"


def _load_module():
    spec = importlib.util.spec_from_file_location("emit_pitch_type_gate", _EMITTER)
    assert spec and spec.loader
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


emitter = _load_module()


def _metrics(model_name: str, *, ece: float, log_loss: float, brier: float) -> dict[str, Any]:
    """A metrics.json with the VERBATIM key names the trainer writes."""
    return {
        "model_name": model_name,
        "model_version": "v1",
        "class_labels": ["FF", "SI", "FC", "SL", "CU", "CH", "OFF"],
        "n_folds": 4,
        "summary": {
            "multiclass_brier": {"mean": brier, "std": 0.001},
            "multiclass_log_loss": {"mean": log_loss, "std": 0.002},
            "expected_calibration_error": {"mean": ece, "std": 0.0003},
        },
        "per_fold": [
            {
                "fold_id": i,
                "train_rows": 1_000_000 * i,
                "val_rows": 300_000,
                "test_rows": 700_000 + i,
                "metrics": {
                    "multiclass_brier": brier,
                    "multiclass_log_loss": log_loss,
                    "expected_calibration_error": ece,
                },
            }
            for i in range(1, 5)
        ],
    }


def _bundle(model_name: str, **kw: float) -> dict[str, Any]:
    return {
        "metrics": _metrics(model_name, **kw),
        "metadata": {"git_commit": "deadbeef", "trained_at": "2026-07-28T05:00:00Z"},
        "path": Path(f"/box/{model_name}/v1"),
    }


def _pair(*, chal_ece: float, champ_ece: float, chal_ll: float = 1.60, champ_ll: float = 1.75):
    challenger = _bundle("pitch_type_pre", ece=chal_ece, log_loss=chal_ll, brier=0.70)
    baseline = _bundle("pitch_type_lr_baseline", ece=champ_ece, log_loss=champ_ll, brier=0.76)
    return challenger, baseline


def test_gate_derives_every_threshold_from_criteria_not_literals():
    """A criteria change must move the artifact, or rule 5's declaration is decorative."""
    gate = emitter.build_gate(*_pair(chal_ece=0.0036, champ_ece=0.0120))
    assert gate["primary_metric"] == "ece"
    assert gate["sample_size_target"] == 2_000
    # pitch_type_pre declares exactly ONE guardrail - log-loss - unlike pitch_outcome_pre's two.
    assert gate["guardrails"] == {"log-loss": 0.0}
    assert "brier" not in gate["guardrails"]
    assert gate["supplementary_checks"][0]["max_allowed"] == 0.02


def test_imported_threshold_is_zero_not_the_vacuous_declared_minus_002():
    """THE anti-bypass claim. At -0.02 the relative lane cannot fail; at 0.0 it is real."""
    gate = emitter.build_gate(*_pair(chal_ece=0.0036, champ_ece=0.0120))
    assert gate["primary_threshold"] == 0.0
    # And the check it enables actually discriminates: a WORSE-calibrated challenger fails,
    # which at -0.02 it would not (0.019 - 0.02 <= 0.012 is true).
    worse = emitter.build_gate(*_pair(chal_ece=0.0190, champ_ece=0.0120))
    assert worse["status"] == "failed"
    assert worse["verdict"]["passed"] is False


def test_absolute_ece_bar_gates_status_because_java_cannot_check_it():
    """[183]'s declared primary. The importer has no field for it, so it must bite here."""
    # Relative lane passes (challenger better than baseline) but the absolute bar does not.
    gate = emitter.build_gate(*_pair(chal_ece=0.0300, champ_ece=0.0400))
    assert gate["challenger_metric"] <= gate["champion_metric"]  # relative lane WOULD pass
    assert gate["supplementary_checks"][0]["passed"] is False
    assert gate["status"] == "failed", "the declared [183] bar must gate `status`"


def test_log_loss_guardrail_regression_fails_the_gate():
    gate = emitter.build_gate(
        *_pair(chal_ece=0.0036, champ_ece=0.0120, chal_ll=1.80, champ_ll=1.75)
    )
    assert gate["guardrails_observed"]["log-loss"] == pytest.approx(0.05)
    assert gate["guardrails_violated"] == {"log-loss": pytest.approx(0.05)}
    assert gate["status"] == "failed"


def test_happy_path_is_import_shaped_and_self_consistent():
    """Everything OfflineGateEvidence requires, in the names it requires."""
    gate = emitter.build_gate(*_pair(chal_ece=0.0036, champ_ece=0.0120))
    assert gate["status"] == "passed"
    assert gate["verdict"]["passed"] is True
    assert gate["verdict"]["sample_size_met"] is True
    assert gate["model_name"] == "pitch_type_pre"
    # Must match RegistryBaselines, or the importer's first-champion branch rejects it.
    assert gate["champion_model_name"] == "pitch_type_lr_baseline"
    # sample_size_observed comes from fold 4's test_rows - metrics.json has no row total.
    assert gate["sample_size_observed"] == 700_004
    # carry_gate is absent by design: the Java record treats null as pass, and emitting
    # false would fail a model that has no carry head.
    assert "carry_gate" not in gate
    # The re-derivation the importer performs, done here first.
    assert gate["challenger_metric"] + gate["primary_threshold"] <= gate["champion_metric"]
    assert json.dumps(gate)  # serializable


def _write_bundle(root: Path, model_name: str) -> Path:
    """A minimal on-disk bundle, so _read_bundle is EXERCISED rather than simulated."""
    bundle = root / model_name / "v1"
    (bundle / "eval").mkdir(parents=True)
    (bundle / "eval" / "metrics.json").write_text(
        json.dumps(_metrics(model_name, ece=0.01, log_loss=1.7, brier=0.7))
    )
    (bundle / "metadata.json").write_text(json.dumps({"git_commit": "abc123"}))
    return bundle


def test_crossed_bundle_arguments_are_refused(tmp_path: Path):
    """A mis-pointed argument would otherwise compare a model against ITSELF and pass every
    downstream check - this guard's failure mode is silent, not loud.

    An earlier version of this test re-implemented the check inline instead of calling
    _read_bundle, which would have passed with the guard deleted. Call the real thing.
    """
    baseline_bundle = _write_bundle(tmp_path, "pitch_type_lr_baseline")
    # Hand the BASELINE bundle to the challenger slot - the realistic operator slip.
    with pytest.raises(SystemExit, match="expected 'pitch_type_pre'"):
        emitter._read_bundle(baseline_bundle, "pitch_type_pre")
    # ...and the correctly-pointed call is accepted, so the refusal is not unconditional.
    ok = emitter._read_bundle(baseline_bundle, "pitch_type_lr_baseline")
    assert ok["metrics"]["model_name"] == "pitch_type_lr_baseline"


def test_a_missing_bundle_file_names_the_path(tmp_path: Path):
    with pytest.raises(SystemExit, match=r"metrics\.json"):
        emitter._read_bundle(tmp_path / "nope" / "v1", "pitch_type_pre")


def test_mismatched_folds_are_refused_as_incomparable():
    """Two heads evaluated over different folds make the guardrail delta meaningless."""
    challenger, baseline = _pair(chal_ece=0.0036, champ_ece=0.0120)
    baseline["metrics"]["per_fold"][-1]["test_rows"] = 12345
    with pytest.raises(SystemExit, match="per-fold test row counts differ"):
        emitter._assert_comparable(challenger, baseline)


def test_missing_metric_key_names_the_keys_present():
    """The bundle/db key-name boundary is the likeliest failure; it must not be silent."""
    challenger, baseline = _pair(chal_ece=0.0036, champ_ece=0.0120)
    del challenger["metrics"]["summary"]["expected_calibration_error"]
    with pytest.raises(SystemExit, match="expected_calibration_error"):
        emitter.build_gate(challenger, baseline)

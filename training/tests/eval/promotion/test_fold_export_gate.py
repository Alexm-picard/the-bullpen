"""The promotion driver's fold-export data source (``--fold-root`` / ParquetFoldLoader path).

The pitch full-box gate reuses the production fold-export (schema-hash-pinned, leak-safe served
contract) instead of re-deriving a per-year mirror. These pin the wiring: ``_make_loader``'s
selection, and that the artifact records the fold-export loader + root in provenance. The real
fold-export round-trip (manifest hash check, fold spans) is ``ParquetFoldLoader``'s own contract,
covered in the pitch fold_store tests.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from bullpen_training.eval.cv_harness import CVResult
from bullpen_training.eval.promotion import driver
from bullpen_training.eval.promotion.criteria import (
    MetricSummary,
    Verdict,
    VerdictOutcome,
    criteria_for,
)
from bullpen_training.eval.promotion.sample_loader import (
    ParquetSampleLoader,
    feature_cols_for,
)
from bullpen_training.pitch import PITCH_FEATURE_COLUMNS, PITCH_FEATURE_COLUMNS_POST


def test_make_loader_uses_fold_loader_when_fold_root_set(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    seen: dict[str, Path] = {}

    def fake_fold_loader(folds_dir: Path) -> str:
        seen["folds_dir"] = folds_dir
        return "FOLD_LOADER"

    monkeypatch.setattr(driver, "ParquetFoldLoader", fake_fold_loader)
    out = driver._make_loader("pitch_outcome_post", Path("/unused"), Path("/folds"))
    assert out == "FOLD_LOADER"
    assert seen["folds_dir"] == Path("/folds")


def test_make_loader_falls_back_to_sample_loader_without_fold_root(tmp_path: Path) -> None:
    out = driver._make_loader("pitch_outcome_post", tmp_path, None)
    assert isinstance(out, ParquetSampleLoader)


def _pitch_run(fold_root: Path | None) -> object:
    crit = criteria_for("pitch_outcome_post")
    ch = MetricSummary(brier=0.111, log_loss=1.02, ece=0.019)
    bl = MetricSummary(brier=0.132, log_loss=1.25, ece=0.013)
    verdict = Verdict(
        outcome=VerdictOutcome.WOULD_PASS,
        sample_size_observed=123345,
        baseline_metrics=bl,
        challenger_metrics=ch,
        primary_metric=crit.primary_metric,
        primary_threshold=crit.primary_threshold,
        guardrail_deltas={},
        guardrails_violated={},
    )
    cv = CVResult(per_fold=(), summary={"multiclass_brier": (0.112, 0.001)})
    return driver.EvidenceRun(
        model_name="pitch_outcome_post",
        criteria=crit,
        baseline_cv=cv,
        challenger_cv=cv,
        verdict=verdict,
        baseline_name="b",
        challenger_name="pitch_outcome_post",
        final_fold_id=4,
        final_test_year=2025,
        sample_root=Path("/unused"),
        rows_per_year=1,
        fold_root=fold_root,
    )


def test_artifact_records_fold_export_provenance() -> None:
    run = _pitch_run(Path("/box/fold_export/v2-clean"))
    art = driver.experiment_results_artifact(run, "full")  # type: ignore[arg-type]
    prov = art["provenance"]
    assert prov["loader"] == "fold_export"
    # the data-root provenance points at the fold-export, not a per-year mirror
    assert prov["sample_root"] == "/box/fold_export/v2-clean"


def test_artifact_records_per_year_loader_without_fold_root() -> None:
    run = _pitch_run(None)
    art = driver.experiment_results_artifact(run, "full")  # type: ignore[arg-type]
    assert art["provenance"]["loader"] == "per_year_mirror"
    assert art["provenance"]["sample_root"] == "/unused"


# --- the feature set the gate actually certifies (the deeper #126 fix) ------------------------
#
# #126 wired the fold-export DATA but left the feature LIST as the sample mirror's reduced proxy,
# so the gate would have certified a ~17-feature model instead of the registered 41-feature POST
# head. These pin that the fold-export path uses the PRODUCTION columns, with a PRE-31 LR baseline
# per decision [37], while the sample path stays on the proxy.


def test_evidence_feature_cols_fold_export_post_uses_production_41_and_pre_31_baseline() -> None:
    challenger, baseline = driver._evidence_feature_cols(
        "pitch_outcome_post", Path("/box/fold_export/v2-clean")
    )
    # Challenger certified on the PRODUCTION 41-feature set the registered head serves.
    assert challenger == PITCH_FEATURE_COLUMNS_POST
    assert len(challenger) == 41
    # Rule-9 baseline is the PRE-31 LR ([37]): the co-registered cross-head sanity check.
    assert baseline == PITCH_FEATURE_COLUMNS
    assert len(baseline) == 31
    # ...and emphatically NOT the synthetic sample proxy (the bug this fix closes).
    assert challenger != feature_cols_for("pitch_outcome_post")


def test_evidence_feature_cols_fold_export_pre_uses_31_for_both() -> None:
    challenger, baseline = driver._evidence_feature_cols(
        "pitch_outcome_pre", Path("/box/fold_export/v2-clean")
    )
    assert challenger == PITCH_FEATURE_COLUMNS
    assert baseline == PITCH_FEATURE_COLUMNS


def test_evidence_feature_cols_sample_path_keeps_proxy_for_both() -> None:
    # Without a fold_root (the synthetic per-year mirror) both heads stay on the proxy - the mirror
    # carries only proxy columns, so the production names would KeyError there.
    challenger, baseline = driver._evidence_feature_cols("pitch_outcome_post", None)
    proxy = feature_cols_for("pitch_outcome_post")
    assert challenger == proxy
    assert baseline == proxy

"""Build the pitch_type_pre offline promotion-gate artifact from two co-trained bundles.

IN ``src/`` RATHER THAN ``scripts/``, DELIBERATELY. This module produces the terminal rule-5
evidence that ``OfflineGateImportService`` imports to promote pitch_type_pre to first champion,
and ``training/scripts/`` is outside BOTH remaining training gates: ``[tool.pyright] include =
["src", "tests"]`` and ``--cov=src/bullpen_training``. Leaving the logic there would make the
sole producer of promotion evidence permanently un-typechecked and unmeasured. The sibling gate
producers (``battedball/mlp/compare_versions.py``, ``carry_promotion_eval.py``) already live in
``src/``; only the two ``scripts/emit_*`` ones did not. The script of the same name is now a
thin argparse shim over this module.

WHY THIS READS BUNDLES, NOT A DRIVER SCORECARD. ``eval.promotion.driver`` does not support
pitch_type, and adding it would touch four independent per-model registries plus a loader that
does not exist, re-run both 4-fold CVs on the box (hours, plus an OOM risk on the unsubsampled LR
fold-4 CV fit), and - decisively - the driver's generic factories fit ISOTONIC calibration while
both shipped heads use TEMPERATURE precisely so [183]'s log-loss guardrail compares MODELS rather
than calibrators (``pitch_type/persist.py``). A driver-rebuilt comparison would answer a subtly
different question than the one [183] declares.

THE COMPARISON IS PAIRED AT THE FOLD LEVEL, which is the load-bearing sentence and stronger than
"two summaries compared": within each fold both heads score the IDENTICAL test rows (same loader,
same calendar year, same ``FOLDS``), so each per-fold difference is a paired difference and the
difference of the 4-fold means is the mean of four paired differences. Both bundles' metrics also
come from the same ``eval.metrics`` implementations - ECE from the same 10-bin floor binning the
Java ``MetricsComputer`` uses (the C1 single-implementation fix).

TWO HONEST LIMITS, recorded in the artifact rather than smoothed over:
  * The reported metrics are UNWEIGHTED MEANS OF PER-FOLD values, not pooled-row metrics. For ECE
    that is not merely a weighting difference: ECE is a binned aggregate, so a mean of fold ECEs
    is not the pooled-rows ECE at all.
  * ``expected_calibration_error`` is TOP-LABEL (argmax-confidence) ECE, not classwise. At top-1
    around 0.45 that is a weaker witness for "the whole y7 distribution is calibrated" than the
    phrase implies. This matches every other head and the Java implementation, so it is a
    project-wide choice rather than something to change here - but the artifact says what is
    measured.

WHY THE IMPORTED THRESHOLD IS 0.0 AND NOT THE DECLARED -0.02. [183] sets ``primary_threshold =
-absolute_ece_bar``, which makes the RELATIVE lane vacuous by construction: ``challenger - 0.02 <=
champion`` holds for any non-negative champion ECE once the challenger is under the bar. Importing
at -0.02 would hand the anti-bypass a check that cannot fail. Importing at 0.0 is STRICTLY
TIGHTENING - every artifact passing at 0.0 would also pass at -0.02 - so nothing the declaration
rejects is waved through. The declared value is recorded as ``declared_primary_threshold`` because
pitch_type_pre gets no driver scorecard, and without it the declared -0.02 would appear in no
committed artifact at all, leaving the imported row's 0.0 looking like an unexplained divergence.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Final, cast

from bullpen_training.eval.promotion.criteria import PromotionCriteria, criteria_for

MODEL: Final[str] = "pitch_type_pre"
# MUST equal RegistryBaselines' mapping: OfflineGateImportService's first-champion branch resolves
# baselineFor("pitch_type_pre") and requires BOTH that and the artifact's declared
# champion_model_name to match the registered champion version's model name.
BASELINE_MODEL: Final[str] = "pitch_type_lr_baseline"

# The filename is a THREE-WAY CONTRACT, pinned by test: the .gitignore negation that makes the
# artifact committable, backend/build.gradle.kts's processResources glob that bundles it into
# offline-gate-evidence/, and OfflineGateEvidenceRepository's filename lookup. Rename it and the
# artifact is silently never committed, never bundled, and import-offline 404s on the box with no
# local signal. The *_promotion_gate.json suffix also keeps it OUT of the
# *_experiment_results_full*.json glob that publishes to the public /accuracy scorecard.
DEFAULT_OUT_NAME: Final[str] = "pitch_type_pre_promotion_gate.json"

# The DIRECTORY is the other half of that contract and equally load-bearing: the .gitignore
# negation is a full repo-relative path, and backend/build.gradle.kts's processResources
# `from("../training/data/eval/promotion")` bundles this directory. Relative to training/; kept
# here (not in the un-typechecked shim) so it is pinned by the same contract test as the name.
DEFAULT_OUT_RELPATH: Final[Path] = Path("data/eval/promotion") / DEFAULT_OUT_NAME

# 0.0, not the declared -0.02 - see the module docstring. Used for BOTH the emitted field and the
# arithmetic, so the two cannot drift apart.
IMPORT_THRESHOLD: Final[float] = 0.0

# Bundle-side metric keys are the metric FUNCTIONS' __name__; the gate artifact, experiment_results
# and Java all use PrimaryMetric db_values. The names differ on both sides of the boundary (note
# the HYPHEN in log-loss), so the translation is explicit and total.
BUNDLE_TO_DB: Final[dict[str, str]] = {
    "expected_calibration_error": "ece",
    "multiclass_log_loss": "log-loss",
    "multiclass_brier": "brier",
}
DB_TO_BUNDLE: Final[dict[str, str]] = {v: k for k, v in BUNDLE_TO_DB.items()}


class GateEvidenceError(Exception):
    """The evidence does not support emitting a gate. Never caught inside this module."""


@dataclass(frozen=True)
class Bundle:
    """One persisted model bundle's two JSON surfaces, plus where it came from."""

    metrics: dict[str, Any]
    metadata: dict[str, Any]
    path: Path
    metrics_sha256: str


def _summary_metric(bundle: Bundle, bundle_key: str) -> float:
    """One CV-mean metric, failing with the file and the keys actually present."""
    if not isinstance(bundle.metrics.get("summary"), dict):
        raise GateEvidenceError(
            f"{bundle.path}: metrics.json has no 'summary' object"
            f" (top-level keys present: {sorted(bundle.metrics)})"
        )
    summary = cast(dict[str, Any], bundle.metrics["summary"])
    entry = summary.get(bundle_key)
    if not isinstance(entry, dict) or "mean" not in entry:
        raise GateEvidenceError(
            f"{bundle.path}: summary lacks {bundle_key!r}.mean"
            f" (summary keys present: {sorted(summary)})"
        )
    return float(cast(dict[str, Any], entry)["mean"])


def _per_fold_metric(bundle: Bundle, bundle_key: str) -> list[float]:
    out: list[float] = []
    for fold in bundle.metrics.get("per_fold", []):
        metrics = fold.get("metrics", {})
        if bundle_key not in metrics:
            raise GateEvidenceError(
                f"{bundle.path}: fold {fold.get('fold_id')} lacks metric {bundle_key!r}"
            )
        out.append(float(metrics[bundle_key]))
    return out


def read_bundle(bundle_dir: Path, expected_model: str) -> Bundle:
    """Load one bundle, asserting it is the model the caller said it is.

    The expected-model assertion is not defensive politeness: crossed --challenger/--baseline
    arguments would produce a gate comparing a model against ITSELF, which yields margin 0.0,
    guardrail delta 0.0, and a fully self-consistent importable PASS. The Java importer cannot
    detect it - it verifies that the champion VERSION belongs to the baseline model, never that
    the numbers came from it.
    """
    metrics_path = bundle_dir / "eval" / "metrics.json"
    metadata_path = bundle_dir / "metadata.json"
    for p in (metrics_path, metadata_path):
        if not p.is_file():
            raise GateEvidenceError(f"missing {p} - is {bundle_dir} a persisted pitch_type bundle?")
    raw = metrics_path.read_bytes()
    metrics = json.loads(raw)
    metadata = json.loads(metadata_path.read_text())

    actual = metrics.get("model_name")
    if actual != expected_model:
        raise GateEvidenceError(
            f"{metrics_path}: model_name is {actual!r}, expected {expected_model!r}"
            " - the bundle arguments are crossed or point at the wrong family"
        )
    return Bundle(
        metrics=metrics,
        metadata=metadata,
        path=bundle_dir,
        metrics_sha256=hashlib.sha256(raw).hexdigest(),
    )


def assert_comparable(challenger: Bundle, baseline: Bundle) -> None:
    """The two heads must have been evaluated over IDENTICAL data, or the deltas are noise.

    The driver path gets this for free by running both models itself. Reading two independently
    produced bundles - two separate CLI invocations, potentially hours or days apart, against a
    MUTABLE materialized feature store (V029) - it has to be asserted.

    Row counts alone are a proxy. The bundles carry two much stronger witnesses, so those are the
    real check: identical ``feature_pipeline_hash`` (rule 7's contract hash) and identical
    ``training_data_hash``. If the contract was bumped or the store re-materialized between the
    two runs, per-fold counts can match while the feature sets differ, and [183]'s log-loss
    guardrail would then be comparing models trained on different features.
    """
    cm, bm = challenger.metrics, baseline.metrics
    if cm.get("class_labels") != bm.get("class_labels"):
        raise GateEvidenceError(
            f"class_labels differ: challenger {cm.get('class_labels')}"
            f" vs baseline {bm.get('class_labels')} - not the same y7 vocabulary"
        )
    if cm.get("n_folds") != bm.get("n_folds"):
        raise GateEvidenceError(
            f"n_folds differ: challenger {cm.get('n_folds')} vs baseline {bm.get('n_folds')}"
        )
    c_rows = [(f.get("fold_id"), f.get("test_rows")) for f in cm.get("per_fold", [])]
    b_rows = [(f.get("fold_id"), f.get("test_rows")) for f in bm.get("per_fold", [])]
    if not c_rows:
        raise GateEvidenceError(
            f"{challenger.path}: metrics.json carries no per_fold entries"
            " - nothing to size the gate on (was this a --skip-cv run?)"
        )
    if c_rows != b_rows:
        raise GateEvidenceError(
            f"per-fold test row counts differ - challenger {c_rows} vs baseline {b_rows}."
            " The heads were not evaluated over the same folds, so the guardrail delta would"
            " compare different data."
        )
    for field in ("feature_pipeline_hash", "training_data_hash"):
        c_val, b_val = challenger.metadata.get(field), baseline.metadata.get(field)
        if c_val != b_val:
            raise GateEvidenceError(
                f"{field} differs between the bundles: challenger {c_val!r} vs baseline {b_val!r}."
                " The two heads were built against different data or a different feature contract,"
                " so the guardrail would compare models trained on different features (rule 7)."
            )


def build_gate(
    challenger: Bundle, baseline: Bundle, *, criteria: PromotionCriteria | None = None
) -> dict[str, Any]:
    """Pure formatter: two loaded bundles in, one import-shaped gate dict out.

    EVERY threshold and guardrail is DERIVED from the pre-declared criteria rather than typed
    here, and that is load-bearing rather than tidy: ``guardrails`` is ARTIFACT-SUPPLIED and is
    exactly what the Java importer's guardrail loop iterates. Java has no access to criteria.py
    and no table of declared guardrails, so an artifact emitting ``{}`` would import with ZERO
    guardrail enforcement. The derivation IS the other half of the anti-bypass.
    """
    c = criteria if criteria is not None else criteria_for(MODEL)
    if not c.guardrails_as_map():
        raise GateEvidenceError(
            f"{c.model_name} declares no guardrails; refusing to emit a gate whose guardrail map"
            " would be empty, because the importer enforces only what the artifact declares"
        )

    primary_key = DB_TO_BUNDLE.get(c.primary_metric.db_value)
    if primary_key is None:
        raise GateEvidenceError(
            f"declared primary metric {c.primary_metric.db_value!r} has no bundle mapping"
            f" (known: {sorted(BUNDLE_TO_DB.values())})"
        )
    chal_primary = _summary_metric(challenger, primary_key)
    champ_primary = _summary_metric(baseline, primary_key)

    guardrails = c.guardrails_as_map()
    guardrails_observed: dict[str, float] = {}
    per_fold_deltas: dict[str, list[float]] = {}
    for db_name in guardrails:
        bundle_key = DB_TO_BUNDLE.get(db_name)
        if bundle_key is None:
            raise GateEvidenceError(
                f"declared guardrail {db_name!r} has no bundle metric mapping"
                f" (known: {sorted(BUNDLE_TO_DB.values())})"
            )
        guardrails_observed[db_name] = _summary_metric(challenger, bundle_key) - _summary_metric(
            baseline, bundle_key
        )
        per_fold_deltas[db_name] = [
            a - b
            for a, b in zip(
                _per_fold_metric(challenger, bundle_key),
                _per_fold_metric(baseline, bundle_key),
                strict=True,
            )
        ]
    per_fold_deltas[c.primary_metric.db_value] = [
        a - b
        for a, b in zip(
            _per_fold_metric(challenger, primary_key),
            _per_fold_metric(baseline, primary_key),
            strict=True,
        )
    ]

    per_fold = cast(list[dict[str, Any]], challenger.metrics.get("per_fold") or [])
    if not per_fold:
        raise GateEvidenceError(
            f"{challenger.path}: metrics.json carries no per_fold entries"
            " - nothing to size the gate on (was this a --skip-cv run?)"
        )
    sample_observed = int(per_fold[-1]["test_rows"])

    # The absolute bar is an ECE bar BY NAME, so it reads the ECE metric explicitly - never the
    # primary. They coincide under [183] today, but re-aiming a declared primary is a live pattern
    # here (ADR-0014 did it to pitch_outcome_pre), and conflating them would silently apply the
    # bar to whatever the primary became while still labelling the check "ece".
    chal_ece = _summary_metric(challenger, DB_TO_BUNDLE["ece"])
    absolute_ok = c.absolute_ece_bar is not None and chal_ece < c.absolute_ece_bar

    # The bar gates on the CV MEAN, but a mean can pass while an individual fold exceeds the
    # declared bar - the real v1 numbers do exactly that (fold 1, test year 2022: 0.0225 vs the
    # 0.02 bar, mean 0.0161). TD directive on this artifact: the promotion decision must SEE
    # that, not just a passing mean. Carried in the check itself, not gating (the declaration
    # binds the mean; making folds gate would be a criteria change, so /decide).
    chal_ece_per_fold = _per_fold_metric(challenger, DB_TO_BUNDLE["ece"])
    folds_over_bar = {
        f"fold_{pf.get('fold_id', i + 1)}": v
        for i, (pf, v) in enumerate(zip(per_fold, chal_ece_per_fold, strict=True))
        if c.absolute_ece_bar is not None and v >= c.absolute_ece_bar
    }
    primary_met = chal_primary + IMPORT_THRESHOLD <= champ_primary
    guardrails_ok = all(obs <= guardrails[k] for k, obs in guardrails_observed.items())
    sample_met = sample_observed >= c.sample_size_target

    supplementary = [
        {
            "name": "absolute_ece_phase2_bar",
            "metric": "ece",
            "max_allowed": c.absolute_ece_bar,
            "observed": chal_ece,
            "passed": bool(absolute_ok),
            "per_fold_observed": chal_ece_per_fold,
            "folds_over_bar": folds_over_bar,
            "rationale": (
                "THE declared [183] primary: TOP-LABEL ECE of the y7 distribution must be below"
                " the bar. Recorded here because OfflineGateEvidence has no field for an absolute"
                " bar, and folded into `status` so it is not merely decorative. The bar binds the"
                " CV MEAN; per_fold_observed and folds_over_bar carry the dispersion so a fold"
                " individually exceeding the bar is visible to the promotion decision rather than"
                " smoothed into a passing mean. BASIS NOTE: the sibling driver-emitted artifacts"
                " (pitch_outcome_pre) apply this bar to the FINAL FOLD's ECE, not the mean - two"
                " conventions coexist, so `observed` means different things across sibling"
                " artifacts; the final-fold reading is recoverable as per_fold_observed[-1], and"
                " unifying the basis belongs to the open /decide on fold-level bounds."
            ),
        }
    ]
    supplementary_ok = all(bool(s["passed"]) for s in supplementary)
    passed = primary_met and guardrails_ok and supplementary_ok
    status = "passed" if (passed and sample_met) else "failed"
    # GUARDRAIL-FIRST, matching criteria.evaluate_challenger_vs_baseline and the Java
    # ExperimentService ("guardrail check takes precedence") - when the primary and a guardrail
    # both fail, all three surfaces must agree the answer is would_fail_guardrail. The absolute
    # bar folds into the primary branch because it IS the declared [183] primary.
    if not guardrails_ok:
        outcome = "would_fail_guardrail"
    elif primary_met and supplementary_ok:
        outcome = "would_pass"
    else:
        outcome = "would_fail_primary"

    return {
        "schema_version": 1,
        "artifact_name": "pitch_type_pre_promotion_gate",
        "data_source": "full",
        "data_source_note": (
            "FULL-data first-champion gate for pitch_type_pre, built from the two co-trained"
            " bundles' 4-fold CV summaries rather than a driver re-run (the driver's generic"
            " factories fit isotonic; both shipped heads use temperature so [183]'s log-loss"
            " guardrail compares models, not calibrators). Import-shaped (OfflineGateEvidence):"
            " a RELATIVE ECE non-inferiority-to-baseline check at threshold 0.0 that the"
            " anti-bypass can verify; the declared -0.02 lane is vacuous and is recorded as"
            " declared_primary_threshold rather than imported. The declared absolute ECE bar is"
            " applied in `status` because the Java re-derivation cannot check it. Metrics are"
            " UNWEIGHTED MEANS OF PER-FOLD values (for ECE that is not the pooled-rows ECE), and"
            " the ECE is TOP-LABEL, not classwise. No promotion here (rule 6)."
        ),
        "model_name": MODEL,
        "champion_model_name": BASELINE_MODEL,
        "challenger_model_name": MODEL,
        "primary_metric": c.primary_metric.db_value,
        "primary_threshold": IMPORT_THRESHOLD,
        "declared_primary_threshold": c.primary_threshold,
        "champion_metric": champ_primary,
        "challenger_metric": chal_primary,
        "sample_size_target": c.sample_size_target,
        "sample_size_observed": sample_observed,
        "guardrails": guardrails,
        "guardrails_observed": guardrails_observed,
        "guardrails_violated": {k: v for k, v in guardrails_observed.items() if v > guardrails[k]},
        "status": status,
        "verdict": {
            "outcome": outcome,
            "passed": passed,
            "sample_size_met": sample_met,
            "primary_margin_required": IMPORT_THRESHOLD,
            "primary_margin_observed": chal_primary - champ_primary,
        },
        "supplementary_checks": supplementary,
        "provenance": {
            "git_commit": challenger.metadata.get("git_commit"),
            "generated_at": datetime.now(UTC).isoformat(),
            "challenger_bundle": str(challenger.path),
            "challenger_metrics_sha256": challenger.metrics_sha256,
            "challenger_trained_at": challenger.metadata.get("trained_at"),
            "challenger_git_commit": challenger.metadata.get("git_commit"),
            "baseline_bundle": str(baseline.path),
            "baseline_metrics_sha256": baseline.metrics_sha256,
            "baseline_trained_at": baseline.metadata.get("trained_at"),
            "baseline_git_commit": baseline.metadata.get("git_commit"),
            "training_data_window": challenger.metadata.get("training_data_window"),
            "training_data_hash": challenger.metadata.get("training_data_hash"),
            "feature_pipeline_hash": challenger.metadata.get("feature_pipeline_hash"),
            "n_folds": challenger.metrics.get("n_folds"),
            "class_labels": challenger.metrics.get("class_labels"),
            "rule_13_holdout": "2026 excluded - trained and validated on 2015-2025 only",
            "evidence_shape": (
                "Unpaired at the row level, PAIRED AT THE FOLD LEVEL: within each fold both heads"
                " score identical test rows, so each per-fold delta is a paired difference."
                " Reported metrics are unweighted means of per-fold values;"
                " sample_size_observed is the FINAL fold's test rows only, while the metrics"
                " summarise all folds. per_fold_deltas and folds_won below carry the dispersion"
                " the means discard - a 4/4 sign-consistent win and a 2/4 split are different"
                " evidence for the same mean margin."
            ),
            "per_fold_deltas": per_fold_deltas,
            "per_fold_values": {
                "challenger": {
                    db: _per_fold_metric(challenger, DB_TO_BUNDLE[db]) for db in per_fold_deltas
                },
                "baseline": {
                    db: _per_fold_metric(baseline, DB_TO_BUNDLE[db]) for db in per_fold_deltas
                },
            },
            "folds_won": {
                k: sum(1 for d in deltas if d < 0) for k, deltas in per_fold_deltas.items()
            },
            "folds_total": challenger.metrics.get("n_folds"),
            "top_k_accuracy_note": (
                "Decision [183]: top-1 is around 0.45 and top-3 around 0.88, both SUPPLEMENTARY."
                " This model is promoted on calibration and never on accuracy. The numbers live"
                " in the bundle's metadata.json and are deliberately absent from criteria - and"
                " from supplementary_checks, because a permanently-passing entry in a list of"
                " checks is a check that cannot fire."
            ),
        },
    }


def assert_importable(gate: dict[str, Any]) -> None:
    """Refuse to emit anything the importer would reject, or that the evidence does not support.

    Explicit raises rather than ``assert``: bare asserts vanish under ``python -O``, and this is
    the only thing standing between a failing model and a committed promotion artifact.
    """
    if gate["status"] != "passed":
        raise GateEvidenceError(
            "refusing to write a FAILED gate:"
            f" primary {gate['challenger_metric']:.6f} vs {gate['champion_metric']:.6f},"
            f" guardrails {gate['guardrails_observed']},"
            f" absolute-bar passed={gate['supplementary_checks'][0]['passed']},"
            f" sample {gate['sample_size_observed']}/{gate['sample_size_target']}."
            " The evidence does not support promotion; there is nothing to import."
        )
    if gate["challenger_metric"] + gate["primary_threshold"] > gate["champion_metric"]:
        raise GateEvidenceError("primary not met")
    for k, obs in gate["guardrails_observed"].items():
        if obs > gate["guardrails"][k]:
            raise GateEvidenceError(f"guardrail {k} regressed: {obs}")
    if gate["sample_size_observed"] < gate["sample_size_target"]:
        raise GateEvidenceError("sample size below target")
    if not (gate["verdict"]["passed"] and gate["verdict"]["sample_size_met"]):
        raise GateEvidenceError("verdict does not declare a pass")


def emit(challenger_dir: Path, baseline_dir: Path, out: Path) -> dict[str, Any]:
    """Read, verify, build, verify again, write. The whole job."""
    challenger = read_bundle(challenger_dir, MODEL)
    baseline = read_bundle(baseline_dir, BASELINE_MODEL)
    assert_comparable(challenger, baseline)
    gate = build_gate(challenger, baseline)
    assert_importable(gate)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(gate, indent=2) + "\n")
    return gate

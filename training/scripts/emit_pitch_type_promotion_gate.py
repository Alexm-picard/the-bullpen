"""Emit the pitch_type_pre offline promotion-gate artifact from two trained bundles.

WHY THIS READS BUNDLES, NOT A DRIVER SCORECARD. Its sibling
``emit_pre_promotion_gate.py`` reads ``*_experiment_results_full.json``, the
``eval.promotion.driver`` product. That path is unavailable here and adding it would be the
wrong trade: ``pitch_type_pre`` is absent from the driver's four independent per-model
registries, and wiring it in would re-run both 4-fold CVs on the box (hours, plus an OOM risk
on the unsubsampled LR fold-4 fit) to recompute numbers the training run already produced.
Worse, the driver's generic factories fit ISOTONIC calibration, while both shipped pitch_type
heads calibrate with TEMPERATURE precisely so the [183] log-loss guardrail compares MODELS
rather than calibrators (``pitch_type/persist.py``). A driver-rebuilt comparison would be
partly a measurement of the calibrators - a subtly different claim than the one [183] declares.

So this reads what the box actually produced. That is sound rather than merely convenient:
``eval/metrics.json``'s ``expected_calibration_error`` is computed by the SAME
``eval.metrics.expected_calibration_error`` with the same 10-bin floor binning the gate
arithmetic and the Java ``MetricsComputer`` use (the C1 single-implementation fix), and both
heads are co-trained over identical folds with identical metrics, so their numbers are already
apples-to-apples. The LR's CV path is NOT subsampled (that applies only to its persisted
production fit), so the comparison is full-window on both sides.

WHY THE IMPORTED THRESHOLD IS 0.0 AND NOT THE DECLARED -0.02. Same reasoning as the sibling.
[183] declares ECE with ``primary_threshold = -absolute_ece_bar = -0.02``, which makes the
RELATIVE lane vacuous by construction: ``challenger - 0.02 <= champion`` holds for any
non-negative champion ECE once the challenger is under the bar. Importing at -0.02 would hand
``OfflineGateImportService``'s anti-bypass a check that cannot fail. At 0.0 the importer
verifies a REAL comparison - pitch_type_pre is at least as calibrated as its rule-9 baseline -
and the declared absolute bar is applied HERE, in ``status``, because the Java re-derivation
has no concept of it.

Deterministic: reads two bundles, writes one artifact. No CV re-run, no measurement. Run from
``training/``::

    python scripts/emit_pitch_type_promotion_gate.py \\
        --challenger-bundle /opt/bullpen/retrain-artifacts/pitch_type_pre/v1 \\
        --baseline-bundle   /opt/bullpen/retrain-artifacts/pitch_type_lr_baseline/v1

The written artifact is committed FROM THE MAC per ADR-0006's box-evidence carve-out.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from bullpen_training.eval.promotion.criteria import criteria_for

_MODEL = "pitch_type_pre"
# MUST equal RegistryBaselines' mapping: OfflineGateImportService's first-champion branch
# resolves baselineFor("pitch_type_pre") and requires BOTH that and the artifact's declared
# champion_model_name to match the registered champion version's model name.
_BASELINE_MODEL = "pitch_type_lr_baseline"

_DEFAULT_OUT = (
    Path(__file__).resolve().parents[1]
    / "data"
    / "eval"
    / "promotion"
    / "pitch_type_pre_promotion_gate.json"
)

# Bundle-side metric keys are the metric FUNCTIONS' __name__; the gate artifact, experiment_results
# and Java all use the PrimaryMetric db_values. The names differ on both sides of the boundary
# (note the HYPHEN in log-loss), so the translation is explicit and one-directional.
_BUNDLE_TO_DB = {
    "expected_calibration_error": "ece",
    "multiclass_log_loss": "log-loss",
    "multiclass_brier": "brier",
}


def _summary_metric(metrics: dict[str, Any], bundle_key: str, where: str) -> float:
    """One CV-mean metric, with the failure naming the file and the keys actually present."""
    summary = metrics.get("summary")
    if not isinstance(summary, dict):
        raise SystemExit(f"{where}: no 'summary' object (keys present: {sorted(metrics)})")
    entry = summary.get(bundle_key)
    if not isinstance(entry, dict) or "mean" not in entry:
        raise SystemExit(
            f"{where}: summary lacks {bundle_key!r}.mean (summary keys present: {sorted(summary)})"
        )
    return float(entry["mean"])


def _read_bundle(bundle: Path, expected_model: str) -> dict[str, Any]:
    """Load one bundle's metrics + metadata, asserting it is the model we were told it is."""
    metrics_path = bundle / "eval" / "metrics.json"
    metadata_path = bundle / "metadata.json"
    for p in (metrics_path, metadata_path):
        if not p.is_file():
            raise SystemExit(f"missing {p} - is {bundle} a persisted pitch_type bundle?")
    metrics = json.loads(metrics_path.read_text())
    metadata = json.loads(metadata_path.read_text())

    # A mis-pointed --challenger-bundle / --baseline-bundle would otherwise silently produce a
    # gate comparing a model against ITSELF, which passes every downstream check.
    actual = metrics.get("model_name")
    if actual != expected_model:
        raise SystemExit(
            f"{metrics_path}: model_name is {actual!r}, expected {expected_model!r}"
            " - the bundle arguments are crossed or point at the wrong family"
        )
    return {"metrics": metrics, "metadata": metadata, "path": bundle}


def _assert_comparable(challenger: dict[str, Any], baseline: dict[str, Any]) -> None:
    """The two heads must have been evaluated over IDENTICAL folds, or the deltas are noise.

    This is the check the driver path gets for free by running both models itself. Reading two
    independently-produced bundles, it has to be asserted: same class vocabulary, same fold
    count, and the same per-fold row counts. Two heads trained over different windows would
    still yield a plausible-looking gate whose log-loss guardrail compared different data.
    """
    cm, bm = challenger["metrics"], baseline["metrics"]
    if cm.get("class_labels") != bm.get("class_labels"):
        raise SystemExit(
            f"class_labels differ: challenger {cm.get('class_labels')} vs"
            f" baseline {bm.get('class_labels')} - not the same y7 vocabulary"
        )
    if cm.get("n_folds") != bm.get("n_folds"):
        raise SystemExit(f"n_folds differ: {cm.get('n_folds')} vs {bm.get('n_folds')}")
    c_rows = [(f.get("fold_id"), f.get("test_rows")) for f in cm.get("per_fold", [])]
    b_rows = [(f.get("fold_id"), f.get("test_rows")) for f in bm.get("per_fold", [])]
    if c_rows != b_rows:
        raise SystemExit(
            "per-fold test row counts differ between the two bundles"
            f" - challenger {c_rows} vs baseline {b_rows}."
            " The heads were not evaluated over the same folds, so the guardrail delta would"
            " compare different data."
        )
    if not c_rows:
        raise SystemExit("metrics.json carries no per_fold entries - nothing to size the gate on")


def build_gate(
    challenger: dict[str, Any], baseline: dict[str, Any], *, ece_note: str = ""
) -> dict[str, Any]:
    """Pure formatter: two loaded bundles in, one import-shaped gate dict out.

    Every threshold and guardrail is DERIVED from criteria.py rather than typed here, so a
    change to the pre-declared criteria cannot silently desync from the artifact that claims to
    satisfy them (rule 5's whole point).
    """
    c = criteria_for(_MODEL)
    cm, bm = challenger["metrics"], baseline["metrics"]

    chal_ece = _summary_metric(cm, "expected_calibration_error", str(challenger["path"]))
    champ_ece = _summary_metric(bm, "expected_calibration_error", str(baseline["path"]))

    # Guardrails come from the declared criteria (pitch_type_pre: log-loss only, max_delta 0.0),
    # not from a hardcoded map - unlike the sibling emitter, whose two guardrails are literals.
    db_to_bundle = {v: k for k, v in _BUNDLE_TO_DB.items()}
    guardrails = c.guardrails_as_map()
    guardrails_observed: dict[str, float] = {}
    for db_name in guardrails:
        bundle_key = db_to_bundle.get(db_name)
        if bundle_key is None:
            raise SystemExit(
                f"declared guardrail {db_name!r} has no bundle metric mapping"
                f" (known: {sorted(_BUNDLE_TO_DB.values())})"
            )
        chal_v = _summary_metric(cm, bundle_key, str(challenger["path"]))
        champ_v = _summary_metric(bm, bundle_key, str(baseline["path"]))
        guardrails_observed[db_name] = chal_v - champ_v

    sample_observed = int(cm["per_fold"][-1]["test_rows"])
    absolute_ok = c.absolute_ece_bar is not None and chal_ece < c.absolute_ece_bar

    primary_met = chal_ece + 0.0 <= champ_ece
    guardrails_ok = all(obs <= guardrails[k] for k, obs in guardrails_observed.items())
    sample_met = sample_observed >= c.sample_size_target
    # status folds in the ABSOLUTE bar, mirroring driver.experiment_results_artifact. The Java
    # re-derivation has no concept of absolute_ece_bar, so if it were only recorded in
    # supplementary_checks the declared [183] primary would gate nothing anywhere.
    status = (
        "passed" if (primary_met and guardrails_ok and sample_met and absolute_ok) else "failed"
    )

    return {
        "schema_version": 1,
        "artifact_name": "pitch_type_pre_promotion_gate",
        "data_source": "full",
        "data_source_note": (
            "FULL-data first-champion gate for pitch_type_pre, built from the two co-trained"
            " bundles' 4-fold CV summaries rather than a driver re-run (the driver's generic"
            " factories fit isotonic; both shipped heads use temperature so the [183] log-loss"
            " guardrail compares models, not calibrators). Import-shaped (OfflineGateEvidence):"
            " a RELATIVE ECE non-inferiority-to-baseline check at threshold 0.0 that the"
            " anti-bypass can verify. The declared absolute ECE < 0.02 bar ([183]) is applied"
            " here in `status` because the Java re-derivation cannot check it. No promotion"
            " here (rule 6)."
        ),
        "model_name": _MODEL,
        "champion_model_name": _BASELINE_MODEL,
        "challenger_model_name": _MODEL,
        "primary_metric": c.primary_metric.db_value,
        # 0.0, NOT the declared -0.02: see the module docstring. At -0.02 the relative lane is
        # vacuous and the importer's anti-bypass could not fail.
        "primary_threshold": 0.0,
        "champion_metric": champ_ece,
        "challenger_metric": chal_ece,
        "sample_size_target": c.sample_size_target,
        "sample_size_observed": sample_observed,
        "guardrails": guardrails,
        "guardrails_observed": guardrails_observed,
        "guardrails_violated": {k: v for k, v in guardrails_observed.items() if v > guardrails[k]},
        "status": status,
        "verdict": {
            "outcome": "would_pass" if status == "passed" else "would_fail",
            "passed": primary_met and guardrails_ok,
            "sample_size_met": sample_met,
            "primary_margin_required": 0.0,
            "primary_margin_observed": chal_ece - champ_ece,
        },
        "supplementary_checks": [
            {
                "name": "absolute_ece_phase2_bar",
                "metric": "ece",
                "max_allowed": c.absolute_ece_bar,
                "observed": chal_ece,
                "passed": bool(absolute_ok),
                "rationale": (
                    "THE declared [183] primary: the y7 distribution's ECE must be < 0.02."
                    " Recorded here because OfflineGateEvidence has no field for an absolute"
                    " bar, and folded into `status` above so it is not merely decorative."
                    + (f" {ece_note}" if ece_note else "")
                ),
            },
            {
                "name": "top_k_accuracy_is_not_a_gate",
                "metric": "top3_accuracy",
                "max_allowed": None,
                "observed": None,
                "passed": True,
                "rationale": (
                    "Decision [183]: top-1 is ~0.45 and top-3 ~0.88, both SUPPLEMENTARY. This"
                    " model is promoted on calibration and never on accuracy; the numbers live"
                    " in the bundle's metadata.json and are deliberately absent from criteria."
                ),
            },
        ],
        "provenance": {
            "git_commit": challenger["metadata"].get("git_commit"),
            "generated_at": challenger["metadata"].get("trained_at"),
            "challenger_bundle": str(challenger["path"]),
            "baseline_bundle": str(baseline["path"]),
            "training_data_window": challenger["metadata"].get("training_data_window"),
            "training_data_hash": challenger["metadata"].get("training_data_hash"),
            "feature_pipeline_hash": challenger["metadata"].get("feature_pipeline_hash"),
            "n_folds": cm.get("n_folds"),
            "class_labels": cm.get("class_labels"),
            "rule_13_holdout": "2026 excluded - trained and validated on 2015-2025 only",
            "evidence_source": (
                "co-trained bundle eval/metrics.json 4-fold CV summaries; no CV re-run"
            ),
        },
    }


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    ap.add_argument("--challenger-bundle", required=True, type=Path)
    ap.add_argument("--baseline-bundle", required=True, type=Path)
    ap.add_argument("--out", type=Path, default=_DEFAULT_OUT)
    args = ap.parse_args(argv)

    challenger = _read_bundle(args.challenger_bundle, _MODEL)
    baseline = _read_bundle(args.baseline_bundle, _BASELINE_MODEL)
    _assert_comparable(challenger, baseline)

    gate = build_gate(challenger, baseline)

    # Fail loud rather than emit a gate that cannot self-verify. These mirror the Java
    # re-derivation exactly, so an artifact that survives here cannot 422 on arithmetic.
    if gate["status"] != "passed":
        raise SystemExit(
            "refusing to write a FAILED gate:"
            f" primary {gate['challenger_metric']:.6f} vs {gate['champion_metric']:.6f},"
            f" guardrails {gate['guardrails_observed']},"
            f" absolute-bar passed={gate['supplementary_checks'][0]['passed']},"
            f" sample {gate['sample_size_observed']}/{gate['sample_size_target']}."
            " The evidence does not support promotion; nothing to import."
        )
    assert gate["challenger_metric"] + gate["primary_threshold"] <= gate["champion_metric"]
    for k, obs in gate["guardrails_observed"].items():
        assert obs <= gate["guardrails"][k], f"guardrail {k} regressed: {obs}"
    assert gate["sample_size_observed"] >= gate["sample_size_target"]
    assert gate["verdict"]["passed"] and gate["verdict"]["sample_size_met"]

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(gate, indent=2) + "\n")
    print(f"wrote {args.out}")
    print(
        f"  primary ece: pitch_type_pre {gate['challenger_metric']:.6f}"
        f" <= LR {gate['champion_metric']:.6f} (threshold 0.0) -> PASS"
    )
    print(
        f"  absolute [183] bar: {gate['challenger_metric']:.6f} <"
        f" {gate['supplementary_checks'][0]['max_allowed']} -> PASS"
    )
    for k, obs in gate["guardrails_observed"].items():
        print(f"  guardrail {k}: {obs:+.6f} <= {gate['guardrails'][k]} -> PASS")


if __name__ == "__main__":
    main()

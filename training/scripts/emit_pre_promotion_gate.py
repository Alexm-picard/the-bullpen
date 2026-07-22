"""Emit the pitch_outcome_pre offline promotion-gate artifact from the committed D evidence.

PRE is a first-champion, no-champion, negative-threshold case: the online experiment path can't
create its gate row (it rejects a negative threshold), and the declared primary (absolute ECE < 0.02
per ADR-0014 / [180]) is a supplementary bar the OfflineGateImportService re-derivation does not
check. So this emits an import-shaped gate that the anti-bypass CAN verify: a RELATIVE ECE
non-inferiority-to-the-baseline check at threshold 0.0 (PRE ECE <= the rule-9 LR baseline's ECE).

Why threshold 0.0 rather than the declared -0.02: under the -0.02 idiom the relative lane is
VACUOUSLY true (challenger - 0.02 <= champion holds for any non-negative ECEs), so importing at
-0.02 would wave PRE through on a vacuous check. At 0.0 the import verifies a REAL comparison - PRE
is at least as calibrated as the co-registered baseline - while the absolute ECE < 0.02 bar is
separately satisfied (0.000895) and already shipped in the /accuracy scorecard. See decision
[181]/[145] (first-champion baseline binding).

Deterministic: reads the committed scorecard, writes the gate. No CV re-run. Run from training/:
    uv run python -m scripts.emit_pre_promotion_gate     (or: python training/scripts/emit_pre_promotion_gate.py)
"""

from __future__ import annotations

import json
from pathlib import Path

_PROMOTION_DIR = Path(__file__).resolve().parents[1] / "data" / "eval" / "promotion"
_SCORECARD = _PROMOTION_DIR / "pitch_outcome_pre_experiment_results_full.json"
_OUT = _PROMOTION_DIR / "pitch_outcome_pre_promotion_gate.json"

_BASELINE_MODEL = "pitch_outcome_lr_baseline"  # rule-9 co-registered baseline for pitch_outcome_pre


def build_gate(scorecard: dict) -> dict:
    champ = scorecard["champion_full_metrics"]  # the LR baseline
    chal = scorecard["challenger_full_metrics"]  # PRE
    brier_delta = chal["brier"] - champ["brier"]
    logloss_delta = chal["log_loss"] - champ["log_loss"]
    return {
        "schema_version": 1,
        "artifact_name": "pitch_outcome_pre_promotion_gate",
        "data_source": "full",
        "data_source_note": (
            "FULL-data first-champion gate for pitch_outcome_pre. Import-shaped (OfflineGateEvidence):"
            " a RELATIVE ECE non-inferiority-to-baseline check (threshold 0.0, PRE ECE <= LR ECE) the"
            " anti-bypass can verify. The declared absolute ECE < 0.02 bar (ADR-0014) is separately"
            " satisfied (0.000895) and shipped in the /accuracy scorecard. No promotion here (rule 6)."
        ),
        "model_name": "pitch_outcome_pre",
        # MUST equal the rule-9 baseline model name - the importer's first-champion branch checks it.
        "champion_model_name": _BASELINE_MODEL,
        "challenger_model_name": "pitch_outcome_pre",
        "primary_metric": "ece",
        "primary_threshold": 0.0,
        "champion_metric": champ["ece"],  # LR baseline ECE
        "challenger_metric": chal["ece"],  # PRE ECE
        "sample_size_target": scorecard["sample_size_target"],
        "sample_size_observed": scorecard["sample_size_observed"],
        # criteria.py guardrails: PRE may not regress vs LR on Brier or log-loss at all (max_delta 0).
        "guardrails": {"brier": 0.0, "log-loss": 0.0},
        "guardrails_observed": {"brier": brier_delta, "log-loss": logloss_delta},
        "guardrails_violated": {},
        "status": "passed",
        "verdict": {
            "outcome": "would_pass",
            "passed": True,
            "sample_size_met": True,
            "primary_margin_required": 0.0,
            "primary_margin_observed": chal["ece"] - champ["ece"],
        },
        "supplementary_checks": [
            {
                "name": "absolute_ece_bar",
                "metric": "ece",
                "max_allowed": 0.02,
                "observed": chal["ece"],
                "passed": chal["ece"] < 0.02,
                "rationale": (
                    "The ADR-0014 declared primary (absolute ECE < 0.02). NON-GATING in this import"
                    " (the re-derivation gates on the relative check above); recorded because it is"
                    " the declared criterion and it passes at 0.000895."
                ),
            }
        ],
        "provenance": dict(scorecard["provenance"]),
    }


def main() -> None:
    scorecard = json.loads(_SCORECARD.read_text())
    gate = build_gate(scorecard)
    # Fail loud if the import invariants would not hold - never emit a gate that can't self-verify.
    assert (
        gate["challenger_metric"] + gate["primary_threshold"] <= gate["champion_metric"]
    ), "primary not met: PRE ECE is not <= LR ECE"
    for k, obs in gate["guardrails_observed"].items():
        assert (
            obs <= gate["guardrails"][k]
        ), f"guardrail {k} regressed: {obs} > {gate['guardrails'][k]}"
    assert gate["sample_size_observed"] >= gate["sample_size_target"]
    _OUT.write_text(json.dumps(gate, indent=2) + "\n")
    print(f"wrote {_OUT}")
    print(
        f"  primary ece: PRE {gate['challenger_metric']:.6f} <= LR {gate['champion_metric']:.6f}"
        f" (threshold {gate['primary_threshold']}) -> PASS"
    )


if __name__ == "__main__":
    main()

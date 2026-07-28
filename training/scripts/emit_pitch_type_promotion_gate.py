"""Emit the pitch_type_pre offline promotion-gate artifact from two trained bundles.

A THIN SHIM. All logic lives in ``bullpen_training.eval.promotion.pitch_type_gate`` so it is
covered by pyright and coverage, neither of which reaches ``training/scripts/``. Read that
module's docstring for why this reads bundles rather than a driver scorecard, why the imported
threshold is 0.0 rather than the declared -0.02, and what the evidence shape actually is.

Run from ``training/``::

    python scripts/emit_pitch_type_promotion_gate.py \\
        --challenger-bundle /opt/bullpen/retrain-artifacts/pitch_type_pre/v1 \\
        --baseline-bundle   /opt/bullpen/retrain-artifacts/pitch_type_lr_baseline/v1

The written artifact is committed FROM THE MAC per ADR-0006's box-evidence carve-out.
"""

from __future__ import annotations

import argparse
from pathlib import Path

from bullpen_training.eval.promotion.pitch_type_gate import (
    DEFAULT_OUT_NAME,
    GateEvidenceError,
    emit,
)

_DEFAULT_OUT = (
    Path(__file__).resolve().parents[1] / "data" / "eval" / "promotion" / DEFAULT_OUT_NAME
)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Emit the pitch_type_pre offline promotion gate.")
    ap.add_argument("--challenger-bundle", required=True, type=Path)
    ap.add_argument("--baseline-bundle", required=True, type=Path)
    ap.add_argument("--out", type=Path, default=_DEFAULT_OUT)
    args = ap.parse_args(argv)

    try:
        gate = emit(args.challenger_bundle, args.baseline_bundle, args.out)
    except GateEvidenceError as e:
        # The message is the diagnosis; a stack trace would bury it.
        raise SystemExit(str(e)) from e

    print(f"wrote {args.out}")
    print(
        f"  primary {gate['primary_metric']}: pitch_type_pre {gate['challenger_metric']:.6f}"
        f" <= LR {gate['champion_metric']:.6f}"
        f" (imported threshold {gate['primary_threshold']},"
        f" declared {gate['declared_primary_threshold']}) -> PASS"
    )
    sup = gate["supplementary_checks"][0]
    print(f"  absolute [183] bar: {sup['observed']:.6f} < {sup['max_allowed']} -> PASS")
    for k, obs in gate["guardrails_observed"].items():
        print(f"  guardrail {k}: {obs:+.6f} <= {gate['guardrails'][k]} -> PASS")
    won = gate["provenance"]["folds_won"]
    total = gate["provenance"]["folds_total"]
    # The dispersion the means discard: a 4/4 sign-consistent win and a 2/4 split are very
    # different evidence for the same mean margin, and only one of them reads as a coin flip.
    for metric, k in won.items():
        print(f"  folds won on {metric}: {k}/{total}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

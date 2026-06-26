"""Carry-promotion evidence: the v2-vs-v1 NON-INFERIORITY + carry-sanity gate (rule 5, [166]).

The carry champion (``battedball_outcome`` v2 = v1's served ``BattedBallMLP`` + an ADDITIVE per-park
carry head) cannot honestly clear a beats-the-LR-baseline gate, and neither can the current champion
v1: on REALIZED outcomes this model is a calibrated per-park PHYSICS ESTIMATE that loses to the LR
baseline (v2 Brier ~0.117 vs ~0.086), the documented [141]/[163] reality gap; v1 serves only via the
first-champion bootstrap. So promoting v2 over v1 is NOT a "beat a baseline" question - the registry
gate (``assertPromotionCriteriaMet``) binds a passing ``experiment_results`` row to the CURRENT
champion (v1), so the honest question is: does adding carry REGRESS the served outcome?

This module answers exactly that with a 4-fold rolling-origin NON-INFERIORITY ABLATION: per fold it
trains the same served ``BattedBallMLP`` recipe WITHOUT the carry head (``carry_weight=0`` = v1's
method, the baseline) and WITH it (``carry_weight=1`` = v2, the challenger) on identical 30-park
folds, scores BOTH on the home-park realized outcome (paired), and evaluates the non-inferiority
criteria (``criteria_for("battedball_outcome")``: v2 Brier may not be > 0.002 worse than v1, plus
log-loss/ECE non-regression guardrails). The carry head's per-park physical plausibility is a
SEPARATE HARD gate (``carry_gate``); the artifact's ``status`` is ``passed`` only if BOTH the
outcome non-inferiority verdict AND the carry gate pass.

It reuses the faithful eval's orchestration (:func:`run_faithful_cv`, dependency-injected
``FoldRunner``) and the driver's box-ingestible ``experiment_results`` artifact shape, so the box's
register/promote ceremony reads this 1:1. NO promotion is performed here (rule 6).

The realized-Brier-vs-LR gap is carried into the artifact as a documented, NON-GATING fact (this is
not the gate). The 4-fold rolling-origin generalization + carry sanity of the served challenger
lives in ``battedball_outcome_faithful_experiment_results_full.json`` (PR-6 / the faithful eval).

BOX-ONLY end-to-end (ClickHouse + GPU): the pure verdict + carry hard-gate + artifact assembly are
dependency-injected so they unit-test on the Mac; ``main`` wires the real per-fold ablation run.

Rule 13: never evaluates 2026 - the folds (cv_harness.FOLDS) span 2015-2025 only.
"""

from __future__ import annotations

import argparse
import json
import logging
from pathlib import Path
from typing import Any

from bullpen_training.battedball.mlp.rolling_cv_eval import (
    CarryGateResult,
    FoldPrediction,
    FoldRunner,
    _box_carry_gate,
    run_faithful_cv,
    select_home_park_proba,
)
from bullpen_training.eval.cv_harness import FOLDS, FoldSpec
from bullpen_training.eval.promotion.criteria import criteria_for
from bullpen_training.eval.promotion.driver import EvidenceRun, experiment_results_artifact

log = logging.getLogger(__name__)

# The served champion (battedball_outcome). The ablation's "baseline" is the SAME recipe WITHOUT the
# carry head (v1's method); the "challenger" is WITH it (v2). The artifact's model_name stays
# "battedball_outcome" so the box maps champion_version_id=v1, challenger_version_id=v2 directly.
PROMOTION_CRITERIA_NAME = "battedball_outcome"
BASELINE_NAME = "battedball_outcome v1 (no-carry recipe = current champion's method)"
CHALLENGER_NAME = "battedball_outcome v2 (carry candidate)"


def run_carry_promotion_cv(
    fold_runner: FoldRunner,
    *,
    carry_gate_result: CarryGateResult,
) -> tuple[EvidenceRun, CarryGateResult]:
    """Run the 4-fold rolling-origin NON-INFERIORITY ablation (carry recipe vs no-carry recipe).

    Thin wrapper over :func:`run_faithful_cv` that swaps in the non-inferiority criteria + the v1/v2
    recipe labels. The injected ``fold_runner`` must return, per fold, ``challenger_proba`` = the
    carry-recipe home-park distribution and ``baseline_proba`` = the no-carry-recipe one (paired on
    the same test rows). ``carry_gate_result`` is the carry head's per-park sanity. No promotion.
    """
    return run_faithful_cv(
        fold_runner,
        carry_gate_result=carry_gate_result,
        criteria=criteria_for(PROMOTION_CRITERIA_NAME),
        model_name=PROMOTION_CRITERIA_NAME,
        baseline_name=BASELINE_NAME,
        challenger_name=CHALLENGER_NAME,
    )


def build_carry_promotion_artifact(
    run: EvidenceRun, carry: CarryGateResult, *, data_source: str = "full"
) -> dict[str, Any]:
    """The box-ingestible ``experiment_results`` artifact for the carry promotion, with the carry
    sanity gate applied as a HARD gate on ``status``.

    ``experiment_results_artifact`` computes the OUTCOME status (non-inferiority verdict + sample
    size). Here the carry gate is ANDed in: ``status`` is ``passed`` iff the outcome is non-inferior
    AND the carry head is physically plausible. A mis-standardised / broken carry head therefore
    blocks promotion even when the outcome head is fine - closing the output[0]-only blind spot of
    the served-path load gate (``ModelLoadValidator``). The realized-vs-LR reality gap is recorded
    as a documented, non-gating fact.
    """
    art = experiment_results_artifact(run, data_source=data_source)
    outcome_passed = art["status"] == "passed"
    final_passed = outcome_passed and carry.passed
    art["status"] = "passed" if final_passed else "failed"
    art["carry_gate"] = {
        "passed": carry.passed,
        "hard_gate": True,
        "per_park_ft": carry.per_park_ft,
        "reasons": list(carry.reasons),
    }
    art["carry_promotion"] = {
        "design": (
            "4-fold rolling-origin NON-INFERIORITY ablation: the served BattedBallMLP recipe "
            "trained WITHOUT the carry head (carry_weight=0 = v1's method, baseline) vs WITH it "
            "(carry_weight=1 = v2, challenger) on identical 30-park folds, both scored on the "
            "home-park realized outcome. Isolates whether adding the carry objective regresses the "
            "served outcome head."
        ),
        "gate": (
            "outcome NON-INFERIORITY (v2 Brier within 0.002 of v1 + log-loss/ECE non-regression) "
            "AND carry sanity (per-park carry in plausible feet) - the carry gate is HARD."
        ),
        "outcome_noninferiority_passed": outcome_passed,
        "carry_gate_passed": carry.passed,
        "realized_vs_lr_gap": (
            "NOT a beats-the-LR-baseline gate: on realized outcomes this model loses to the LR "
            "baseline (the documented [141]/[163] reality gap - a calibrated per-park physics "
            "ESTIMATE), and v1 itself serves on the first-champion bootstrap. That gap is carried "
            "as a documented, non-gating fact; see "
            "battedball_outcome_faithful_experiment_results_full.json for the 4-fold "
            "rolling-origin generalization + carry sanity of the served challenger vs the LR "
            "baseline. See [166]."
        ),
    }
    return art


# --- box wiring (ClickHouse + GPU; not exercised by the Mac unit tests) -----


def _box_ablation_fold_runner(park_order: tuple[str, ...], *, epochs: int) -> FoldRunner:
    """The real ablation fold runner: per fold, trains the served BattedBallMLP recipe WITHOUT carry
    (carry_weight=0 = baseline / v1's method) and WITH carry (carry_weight=1 = challenger / v2) on
    the SAME 30-park CH labels with the SAME scaler, and scores BOTH on the home-park realized
    outcome (paired). ClickHouse + GPU; box-only. The two trainings are the only added cost vs the
    faithful eval - the price of an apples-to-apples carry ablation."""
    import torch

    from bullpen_training.battedball.features_shared import FEATURE_NAMES
    from bullpen_training.battedball.mlp.dataset import (
        BBIPDataset,
        FeatureScaler,
        _run_clickhouse,
        load_arrays,
    )
    from bullpen_training.battedball.mlp.train import train_model
    from bullpen_training.eval.promotion.export_batted_ball_full import (
        build_year_query,
        rows_to_frame,
    )

    def _home_park(year: int) -> tuple[Any, list[str], Any]:
        df = rows_to_frame(_run_clickhouse(build_year_query(year)))
        import numpy as np

        feats = df[list(FEATURE_NAMES)].to_numpy(dtype=np.float32)
        parks = [str(p) for p in df["park"].tolist()]
        labels = np.asarray(df["label"], dtype=np.int64)
        return feats, parks, labels

    def _score(model: Any, scaler: Any, test_feat: Any, test_parks: list[str]) -> Any:
        import numpy as np

        with torch.no_grad():
            logits, _carry = model(torch.from_numpy(scaler.transform(test_feat)))
            proba30 = torch.softmax(logits, dim=-1).numpy()
        return select_home_park_proba(proba30, test_parks, park_order).astype(np.float64)

    def runner(fold: FoldSpec) -> FoldPrediction:
        feat30, lab30, carry30 = load_arrays(
            season_from=fold.train_start_year,
            season_to=fold.train_end_year,
            park_order=park_order,
        )
        scaler = FeatureScaler.fit(feat30)
        train_ds = BBIPDataset(feat30, lab30, carry=carry30, scaler=scaler)

        # baseline = no-carry recipe (v1's method); challenger = carry recipe (v2). Same data, same
        # scaler, same epochs - the ONLY difference is the carry objective, so any Brier delta is
        # the carry head's effect on the shared backbone, not a confound.
        baseline_model, _b = train_model(
            train_ds, n_epochs=epochs, carry_weight=0.0, n_parks=len(park_order)
        )
        challenger_model, _c = train_model(
            train_ds, n_epochs=epochs, carry_weight=1.0, n_parks=len(park_order)
        )
        baseline_model.cpu().eval()
        challenger_model.cpu().eval()

        test_feat, test_parks, y_true = _home_park(fold.test_year)
        return FoldPrediction(
            fold_id=fold.fold_id,
            test_year=fold.test_year,
            test_rows=int(y_true.shape[0]),
            y_true_int=y_true,
            challenger_proba=_score(challenger_model, scaler, test_feat, test_parks),
            baseline_proba=_score(baseline_model, scaler, test_feat, test_parks),
            retro=None,  # the gate is realized-outcome non-inferiority; no retro-ECE bar here
        )

    return runner


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    parser = argparse.ArgumentParser(
        description="Carry-promotion NON-INFERIORITY evidence (v2 carry recipe vs v1 no-carry "
        "recipe) for the served battedball_outcome champion. Decision [166]."
    )
    parser.add_argument(
        "--out",
        type=Path,
        # NOTE: deliberately NOT named '*_experiment_results_full*.json' - that glob is bundled into
        # the JAR for the public /accuracy scorecard (build.gradle.kts processResources), and this
        # raw-softmax non-inferiority row (model_name=battedball_outcome) would collide with the
        # served champion's calibrated scorecard row. This is promotion-GATE evidence, not a public
        # accuracy number; the box's register/promote ceremony reads it by this explicit name.
        default=Path("artifacts/battedball_outcome_carry_promotion_gate.json"),
        help="Where to write the experiment_results-shaped carry-promotion gate evidence artifact.",
    )
    parser.add_argument(
        "--epochs",
        type=int,
        default=50,
        help="Epochs per model; match the served retrain (50). Two models trained per fold.",
    )
    args = parser.parse_args()

    from bullpen_training.battedball.parks.loader import load_all_parks

    park_order = tuple(sorted(load_all_parks().keys()))
    log.info(
        "carry-promotion non-inferiority CV over %d folds, %d parks (2 trainings/fold)",
        len(FOLDS),
        len(park_order),
    )

    runner = _box_ablation_fold_runner(park_order, epochs=args.epochs)
    # Reuse the faithful eval's carry probe (carry recipe = carry_weight=1.0) so the two evals never
    # drift on what "carry sanity" means (python-training-reviewer DRY note).
    carry = _box_carry_gate(park_order, epochs=args.epochs, carry_weight=1.0)
    run, carry = run_carry_promotion_cv(runner, carry_gate_result=carry)
    artifact = build_carry_promotion_artifact(run, carry, data_source="full")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(artifact, indent=2) + "\n")
    log.info(
        "wrote %s (status=%s, outcome_noninferiority=%s, carry_gate_passed=%s)",
        args.out,
        artifact.get("status"),
        artifact["carry_promotion"]["outcome_noninferiority_passed"],
        carry.passed,
    )
    if not carry.passed:
        log.warning("CARRY GATE FAILED (HARD): %s", "; ".join(carry.reasons))


if __name__ == "__main__":
    main()


__all__ = (
    "build_carry_promotion_artifact",
    "run_carry_promotion_cv",
)

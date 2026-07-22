"""Inference-only v3-vs-v2 NON-INFERIORITY gate for the served ``battedball_outcome`` champion.

The carry champion (v2) is CHAMPION; a retrained v3 CANDIDATE has been registered (Box Hand-off #1,
decision [178]). The honest promotion question for v3-over-v2 is the SAME one [166] asked for
v2-over-v1: does the retrain REGRESS the served outcome? This module answers it with a single
held-out-season non-inferiority comparison of the two ALREADY-TRAINED, FIXED ONNX bundles - NOT a
rolling-origin retrain CV (two fixed models cannot be retrained per fold; that is what
``carry_promotion_eval`` does for the carry ABLATION, which trains fresh recipes).

It reuses the LOCKED gate machinery unchanged, so the box's register/promote ceremony reads the
output 1:1:
  - ``criteria_for("battedball_outcome")`` - the [166] NON-INFERIORITY criteria: v3 multiclass Brier
    may be at most 0.002 WORSE than v2 on the home-park realized outcome (negative threshold), with
    log-loss (<= 0.01) + ECE (<= 0.015) non-regression guardrails.
  - Scored on each BIP's REALIZED home-park outcome via ``select_home_park_proba`` over the served
    ONNX graph's RAW per-park softmax (output[0]). RAW, not calibrated: the _BATTED_BALL_CARRY
    criteria are declared on raw softmax, and the served per-park isotonic calibration is applied
    equally to BOTH versions downstream, so it cancels in a v3-vs-v2 non-inferiority delta. This is
    apples-to-apples with how v2's own carry gate was scored (``carry_promotion_eval._score``).
  - The carry head's per-park physical plausibility (v3) is a SEPARATE HARD gate (``carry_gate``),
    ANDed into the artifact ``status`` exactly as the carry-promotion gate does.

The emitted ``battedball_outcome_v3_promotion_gate.json`` is the SAME OfflineGateEvidence shape the
``import-offline`` admin path re-derives its pass from (champion=v2, challenger=v3); it deliberately
does NOT match the ``*_experiment_results_full*.json`` /accuracy glob, so it bundles into
``classpath:offline-gate-evidence/`` (build.gradle.kts) without colliding with the public scorecard.
NO promotion is performed here (rule 6) - evidence only.

Rule 13: the eval season must be <= 2025. 2026 is holdout-only and using it for a promotion
DECISION would be model selection on the holdout - refused here AND by ``build_year_query``
(``allow_holdout_eval=False``). The operator must also pass a season HELD-OUT from BOTH bundles'
training (the fold-4 test year 2025 is the natural default: standard training ends 2023, validates
on 2024).

BOX-ONLY end-to-end (ClickHouse + a real ONNX session): the pure artifact assembly is
dependency-injected so it unit-tests on the Mac with a synthetic verdict + carry gate; ``main``
wires the real ClickHouse loader + ONNX scoring for the box run. Do NOT run until the box has a few
days of proven post-#327 uptime.
"""

from __future__ import annotations

import argparse
import json
import logging
import subprocess
import sys
from pathlib import Path
from typing import TYPE_CHECKING, Any

import numpy as np

from bullpen_training.battedball.mlp.rolling_cv_eval import CarryGateResult, carry_gate
from bullpen_training.eval.promotion.criteria import (
    PromotionCriteria,
    Verdict,
    criteria_for,
    evaluate_challenger_vs_baseline,
)

if TYPE_CHECKING:
    import pandas as pd

log = logging.getLogger(__name__)

MODEL_NAME = "battedball_outcome"
CHAMPION_NAME = "battedball_outcome v2 (current champion)"
CHALLENGER_NAME = "battedball_outcome v3 (retrain candidate)"
ARTIFACT_NAME = "battedball_outcome_v3_promotion_gate"
DEFAULT_VAL_SEASON = 2025  # fold-4 test year; held out from standard training (train ends 2023).


# --- pure artifact assembly (Mac-unit-tested; no ClickHouse / ONNX) ---


def build_v3_gate_artifact(
    *,
    verdict: Verdict,
    criteria: PromotionCriteria,
    carry: CarryGateResult,
    champion_name: str,
    challenger_name: str,
    val_season: int,
    git_commit: str,
    generated_at: str,
    data_source: str = "full",
) -> dict[str, Any]:
    """The OfflineGateEvidence-shaped v3-vs-v2 gate artifact.

    Field names + semantics mirror what ``OfflineGateImportService`` RE-DERIVES the pass from:
    ``challenger_metric + primary_threshold <= champion_metric`` (a negative threshold is the
    non-inferiority margin), every ``guardrails_observed`` delta within its ``guardrails`` max, the
    ``carry_gate.passed`` HARD gate, AND a self-consistent declared verdict (``status == "passed"``
    with ``verdict.passed`` and ``verdict.sample_size_met``). ``status`` is ``passed`` iff the
    outcome is non-inferior, the sample target is met, AND the carry gate passes.
    """
    if data_source not in ("sample", "full"):
        raise ValueError(f"data_source must be 'sample' or 'full', got {data_source!r}")
    champ_primary = verdict.baseline_metrics.value_for(criteria.primary_metric)
    chal_primary = verdict.challenger_metrics.value_for(criteria.primary_metric)
    sample_met = verdict.sample_size_observed >= criteria.sample_size_target
    outcome_passed = verdict.passed and sample_met
    status = "passed" if (outcome_passed and carry.passed) else "failed"
    return {
        "schema_version": 1,
        "artifact_name": ARTIFACT_NAME,
        "data_source": data_source,
        "data_source_note": (
            "FULL-box evidence for the LIVE promotion of v3 over v2 (operator hand-off). The row "
            "the promote-model skill reads via import-offline. No promotion is performed (rule 6); "
            "promotion stays human-gated."
        ),
        "model_name": criteria.model_name,
        # champion == the CURRENT champion v2 (import-offline binds championVersionId to it); the
        # challenger is the v3 candidate.
        "champion_model_name": champion_name,
        "challenger_model_name": challenger_name,
        "primary_metric": criteria.primary_metric.db_value,
        "primary_threshold": criteria.primary_threshold,
        "sample_size_target": criteria.sample_size_target,
        "sample_size_observed": verdict.sample_size_observed,
        "champion_metric": champ_primary,
        "challenger_metric": chal_primary,
        "guardrails": criteria.guardrails_as_map(),
        "guardrails_observed": verdict.guardrail_deltas,
        "guardrails_violated": verdict.guardrails_violated,
        "status": status,
        "verdict": {
            "outcome": verdict.outcome.value,
            "passed": verdict.passed,
            "sample_size_met": sample_met,
            "primary_margin_required": criteria.primary_threshold,
            # champion - challenger: positive means v3 is BETTER (lower Brier); margin met when
            # this is >= -threshold, i.e. challenger + threshold <= champion.
            "primary_margin_observed": champ_primary - chal_primary,
        },
        "carry_gate": {
            "passed": carry.passed,
            "hard_gate": True,
            "per_park_ft": carry.per_park_ft,
            "reasons": list(carry.reasons),
        },
        "champion_full_metrics": {
            "brier": verdict.baseline_metrics.brier,
            "log_loss": verdict.baseline_metrics.log_loss,
            "ece": verdict.baseline_metrics.ece,
        },
        "challenger_full_metrics": {
            "brier": verdict.challenger_metrics.brier,
            "log_loss": verdict.challenger_metrics.log_loss,
            "ece": verdict.challenger_metrics.ece,
        },
        "pre_declared_criteria": {
            "primary_metric": criteria.primary_metric.db_value,
            "primary_threshold": criteria.primary_threshold,
            "sample_size_target": criteria.sample_size_target,
            "guardrails": [
                {"metric": g.metric.db_value, "max_delta": g.max_delta, "rationale": g.rationale}
                for g in criteria.guardrails
            ],
            "absolute_ece_bar": criteria.absolute_ece_bar,
            "rationale": criteria.rationale,
        },
        "evaluation": {
            "design": (
                "single held-out-season NON-INFERIORITY of two FIXED registered ONNX bundles (v2 "
                "champion vs v3 candidate), scored on each BIP's home-park REALIZED outcome, RAW "
                "per-park softmax. NOT a rolling-origin retrain CV: the two models are fixed. The "
                "carry head's per-park plausibility is a SEPARATE HARD gate. Matches how v2's own "
                "carry gate was scored (raw softmax, home-park realized). See [166]/[178]."
            ),
            "val_season": val_season,
            "scored_on": "home_park_realized_outcome_raw_softmax",
            "calibration": (
                "raw_softmax_uncalibrated: the served per-park isotonic is applied equally to BOTH "
                "versions downstream, so it cancels in the v3-vs-v2 delta; the locked "
                "_BATTED_BALL_CARRY criteria are declared on raw softmax."
            ),
        },
        "provenance": {
            "git_commit": git_commit,
            "generated_at": generated_at,
            "val_season": val_season,
            "loader": (
                "clickhouse home-park realized (build_year_query + rows_to_frame); raw ONNX "
                "output[0], no calibrator"
            ),
        },
    }


# --- box wiring (ClickHouse + a real ONNX session; NOT exercised by the Mac unit tests) ---


class _BundleScorer:
    """RAW (uncalibrated) home-park scorer for a registered ``battedball_outcome`` ONNX bundle.

    Mirrors ``OnnxMlpPredictor``'s feature scaling (the metadata ``FeatureScaler``) + the served
    graph, but reads the RAW per-park softmax ``output[0]`` WITHOUT per-park isotonic calibration
    - the non-inferiority criteria are declared on raw softmax and the served calibration cancels in
    a v3-vs-v2 delta. Also reads the carry head ``output[1]`` for the per-park carry sanity gate.
    BOX-ONLY (needs a real ``onnxruntime`` session + a registered bundle).
    """

    def __init__(self, model_dir: Path) -> None:
        import onnxruntime as ort

        self.model_dir = Path(model_dir)
        meta = json.loads((self.model_dir / "metadata.json").read_text())
        self.feature_order: list[str] = list(meta["feature_names"])
        self.park_order: tuple[str, ...] = tuple(str(p) for p in meta["park_order"])
        scaler = meta["feature_scaler"]
        self._means = np.asarray(scaler["means"], dtype=np.float64)
        self._stds = np.asarray(scaler["stds"], dtype=np.float64)
        self._session = ort.InferenceSession(str(self.model_dir / "model.onnx"))
        self._input_name = self._session.get_inputs()[0].name

    def _scaled(self, feat: np.ndarray) -> np.ndarray:
        """Z-score with the metadata scaler (one-hot columns carry identity mean/std)."""
        return ((feat.astype(np.float64) - self._means) / self._stds).astype(np.float32)

    def raw_home_park_proba(self, df: pd.DataFrame, parks: list[str]) -> np.ndarray:
        """(N, 5) raw home-park softmax: select each BIP's own home-park column from output[0]."""
        from bullpen_training.battedball.mlp.rolling_cv_eval import select_home_park_proba

        missing = [c for c in self.feature_order if c not in df.columns]
        if missing:
            raise ValueError(
                f"eval frame is missing model features {missing}; the "
                f"{len(self.feature_order)}-feature contract is not covered by the loaded columns"
            )
        feat = df[self.feature_order].to_numpy(dtype=np.float64)
        outputs = self._session.run(None, {self._input_name: self._scaled(feat)})
        per_park = np.asarray(outputs[0], dtype=np.float64)  # (N, n_parks, 5) raw softmax
        return select_home_park_proba(per_park, parks, self.park_order)

    def per_park_carry(self) -> dict[str, float]:
        """Standardised per-park carry for a canonical barrel (output[1]); feeds ``carry_gate``."""
        from bullpen_training.battedball.mlp.sanity import canonical_features

        canonical = self._scaled(canonical_features()[None, :])
        outputs = self._session.run(None, {self._input_name: canonical})
        carry = np.asarray(outputs[1], dtype=np.float64)  # (1, n_parks) standardised
        return {self.park_order[i]: float(carry[0, i]) for i in range(len(self.park_order))}


def _load_home_park_eval(
    val_season: int, container: str
) -> tuple[pd.DataFrame, np.ndarray, list[str]]:
    """CH-load the val season's home-park BIPs: (frame, realized labels 0..4, home parks). Box-only.

    ``build_year_query`` defaults to ``allow_holdout_eval=False`` so 2026 is refused here too - a
    second rule-13 fence behind ``main``'s guard.
    """
    from bullpen_training.battedball.mlp.dataset import _run_clickhouse
    from bullpen_training.eval.promotion.export_batted_ball_full import (
        build_year_query,
        rows_to_frame,
    )

    df = rows_to_frame(_run_clickhouse(build_year_query(val_season), container=container))
    y_true = df["label"].to_numpy(dtype=np.int64)
    parks = [str(p) for p in df["park"].tolist()]
    return df, y_true, parks


def _git_commit() -> str:
    try:
        return subprocess.run(
            ["git", "rev-parse", "HEAD"], capture_output=True, text=True, check=True
        ).stdout.strip()
    except (subprocess.SubprocessError, OSError):
        return "unknown"


def _now_iso() -> str:
    from datetime import UTC, datetime

    return datetime.now(UTC).isoformat(timespec="seconds")


def main(argv: list[str] | None = None) -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    parser = argparse.ArgumentParser(
        description="v3-vs-v2 non-inferiority gate for battedball_outcome ([166]/[178])."
    )
    parser.add_argument(
        "--v2-dir", required=True, type=Path, help="current champion (v2) bundle dir"
    )
    parser.add_argument(
        "--v3-dir", required=True, type=Path, help="retrain candidate (v3) bundle dir"
    )
    parser.add_argument(
        "--val-season",
        type=int,
        default=DEFAULT_VAL_SEASON,
        help="held-out eval season <= 2025 (rule-13 refuses 2026); held out from BOTH bundles.",
    )
    parser.add_argument(
        "--container", default="bullpen-clickhouse", help="the ClickHouse container"
    )
    parser.add_argument(
        "--out",
        type=Path,
        # Box-local + gitignored (like the carry gate); relayed + committed from the Mac to
        # training/data/eval/promotion/ (gitignore negation), where the build bundles it for
        # import-offline. NOT named '*_experiment_results_full*.json' (that glob is the public
        # /accuracy scorecard).
        default=Path("artifacts/battedball_outcome_v3_promotion_gate.json"),
        help="where to write the experiment_results-shaped v3 gate evidence artifact",
    )
    args = parser.parse_args(argv)

    if args.val_season >= 2026:
        raise SystemExit(
            f"rule-13: the promotion-gate eval season must be <= 2025 (2026 is holdout-only, "
            f"never a selection/validation slice); got {args.val_season}"
        )
    if args.val_season != DEFAULT_VAL_SEASON:
        # The module cannot read a bundle's train range - only feature_names/park_order/scaler. A
        # season IN-sample for either bundle makes the non-inferiority gate less conservative
        # (an in-sample year advantages both models, so it does not inflate v3 specifically, but it
        # is not a clean held-out comparison). Nudge the operator to confirm.
        log.warning(
            "--val-season %d is not the default held-out fold-4 test year %d; confirm it is held "
            "OUT of BOTH the v2 and v3 training slices (the module cannot verify a bundle's train "
            "range).",
            args.val_season,
            DEFAULT_VAL_SEASON,
        )

    df, y_true, parks = _load_home_park_eval(args.val_season, args.container)
    if y_true.shape[0] == 0:
        raise SystemExit(f"no BIP rows for season {args.val_season}")
    log.info("loaded %d home-park BIPs for season %d", y_true.shape[0], args.val_season)

    v2 = _BundleScorer(args.v2_dir)
    v3 = _BundleScorer(args.v3_dir)
    v2_proba = v2.raw_home_park_proba(df, parks)
    v3_proba = v3.raw_home_park_proba(df, parks)

    criteria = criteria_for(MODEL_NAME)
    verdict = evaluate_challenger_vs_baseline(
        criteria=criteria,
        y_true_int=y_true,
        baseline_proba=v2_proba,  # v2 = champion / baseline
        challenger_proba=v3_proba,  # v3 = candidate / challenger
    )
    carry = carry_gate(v3.per_park_carry())

    artifact = build_v3_gate_artifact(
        verdict=verdict,
        criteria=criteria,
        carry=carry,
        champion_name=CHAMPION_NAME,
        challenger_name=CHALLENGER_NAME,
        val_season=args.val_season,
        git_commit=_git_commit(),
        generated_at=_now_iso(),
        data_source="full",
    )

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(artifact, indent=2) + "\n")
    log.info(
        "wrote %s (status=%s; primary brier v3 %.5f vs v2 %.5f, threshold %s; carry_gate=%s)",
        args.out,
        artifact["status"],
        artifact["challenger_metric"],
        artifact["champion_metric"],
        artifact["primary_threshold"],
        carry.passed,
    )
    if artifact["status"] != "passed":
        log.warning(
            "V3 GATE NOT PASSED: outcome=%s carry_gate=%s carry_reasons=%s",
            verdict.outcome.value,
            carry.passed,
            "; ".join(carry.reasons),
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())


__all__ = ("build_v3_gate_artifact",)

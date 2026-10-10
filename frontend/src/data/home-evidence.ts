/**
 * The home page's evidence figures ([195] front page, approved SPEC-home §6).
 *
 * These are OFFLINE gate numbers from committed promotion evidence - not live accuracy - and the
 * basis line under each figure says so. They are constants rather than a fetch because neither
 * figure is served live in the shape the page needs: the /v1/ops/accuracy scorecard only bundles
 * `*_experiment_results_full*.json` files, so the pitch-type gate (a `_promotion_gate.json`) is not
 * in it at all. `home-evidence.test.ts` re-reads both source JSON files and fails if a constant
 * drifts from its evidence, so a retrain that changes the numbers cannot leave the page stale.
 *
 * [183]: the pitch-type figure is CALIBRATION (top-label ECE), never top-1 accuracy.
 */

export type EvidenceFigure = {
  /** Display value, already rounded the way the page prints it. */
  value: string;
  /** What the number is, in plain words. */
  label: string;
  /** Where it comes from - sample, method, and the baseline it beat. */
  basis: string;
  /** The committed evidence file, relative to the repo root. */
  source: string;
};

// SHELF: 2027-03 training/data/eval/promotion/pitch_type_pre_promotion_gate.json challenger_metric (top-label ECE, unweighted 4-fold CV mean) and champion_metric (LR baseline); re-verify after any pitch_type_pre retrain
export const PITCH_TYPE_ECE: EvidenceFigure = {
  value: "0.016",
  label: "Pitch-type calibration error",
  basis: "Top-label ECE, 4-fold CV mean. LR baseline 0.044.",
  source: "training/data/eval/promotion/pitch_type_pre_promotion_gate.json",
};

// SHELF: 2027-03 training/data/eval/promotion/pitch_outcome_post_experiment_results_full.json challenger_metric (Brier) and champion_metric (LR baseline), 710k held-out rows; re-verify after any pitch_outcome_post retrain
export const POST_PITCH_BRIER: EvidenceFigure = {
  value: "0.104",
  label: "Post-pitch Brier score",
  basis: "710k held-out pitches. LR baseline 0.149.",
  source:
    "training/data/eval/promotion/pitch_outcome_post_experiment_results_full.json",
};

// SHELF: 2027-03 the four champion families in CLAUDE.md (pitch_outcome_pre, pitch_outcome_post, pitch_type_pre, battedball_outcome); shown only when the registry is unreachable
export const CHAMPION_COUNT_FALLBACK = 4;

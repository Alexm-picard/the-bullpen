/**
 * The Model Guide's evidence (SPEC-guide-about §6.1, decision [195]).
 *
 * Every figure on /models/guide lives here. They are OFFLINE gate and holdout numbers from committed
 * evidence files, never live accuracy, and each card prints its source path under the figure.
 * `guide-evidence.test.ts` re-reads the JSON each constant names and fails when a number drifts, so a
 * retrain that moves a figure turns a test red instead of leaving a stale claim on the page.
 *
 * One number, one place: a card's MARGIN carries its headline figure(s); its CHECKS table carries
 * every other gate row. The page test asserts no margin figure is printed twice.
 *
 * [183]: nothing about pitch_type_pre is a top-1 rate. [202]: none of this is fixture data.
 */
import type { EvidenceFigure } from "./home-evidence";
import { PITCH_TYPE_ECE, POST_PITCH_BRIER } from "./home-evidence";

export type GuideAnchor =
  | "next-pitch"
  | "pitch-type"
  | "post-pitch"
  | "batted-ball";

export type CheckVerdict = "Passed" | "Over" | "LR wins" | "Not gated";

export type GateCheck = {
  check: string;
  result: string;
  bar: string;
  verdict: CheckVerdict;
};

const PROMO = "training/data/eval/promotion";

// SHELF: 2027-03 training/data/eval/promotion/pitch_outcome_pre_promotion_gate.json challenger_metric (ECE), champion_metric (LR baseline), sample_size_observed; re-verify after any pitch_outcome_pre retrain or promotion
export const PRE_ECE: EvidenceFigure = {
  value: "0.0009",
  label: "Calibration error (ECE)",
  basis: "LR baseline 0.0016. 710,600 rolling-origin CV rows, 2015 to 2025.",
  source: `${PROMO}/pitch_outcome_pre_promotion_gate.json`,
};

// SHELF: 2027-03 training/data/eval/pitch_post_backfill_accuracy_v1.json headline.top1_accuracy, n_pitches, headline.marginal_top1_accuracy (2026 holdout, rule 13); re-verify after any pitch_outcome_post retrain
export const POST_HOLDOUT_TOP1: EvidenceFigure = {
  value: "59.1%",
  label: "Top-1 on unseen 2026 pitches",
  basis:
    "237,396 pitches never used in training. Guessing the most common outcome gets 36.6%.",
  source: "training/data/eval/pitch_post_backfill_accuracy_v1.json",
};

// SHELF: 2027-03 docs/decisions.md [141]/[163]: the batted-ball champion's physics retrodiction correlates ~0.30 with realized park outcomes (no JSON field carries it; same claim as data/parks-claims.ts); re-verify after any battedball_outcome retrain or promotion
export const BATTED_PHYSICS_VS_REALITY: EvidenceFigure = {
  value: "0.30",
  label: "How well the physics tracks real outcomes",
  basis:
    "Correlation of its physics retrodiction with realized park outcomes. It is a physics estimate, not a record of what happened.",
  source: "docs/decisions.md [141], [163]",
};

/** The margin of each model card: its headline figure(s), in print order. */
export const GUIDE_MARGIN: Record<GuideAnchor, readonly EvidenceFigure[]> = {
  "next-pitch": [PRE_ECE],
  "pitch-type": [PITCH_TYPE_ECE],
  "post-pitch": [POST_PITCH_BRIER, POST_HOLDOUT_TOP1],
  "batted-ball": [BATTED_PHYSICS_VS_REALITY],
};

/**
 * The rest of each gate. Sources, field by field, are asserted in guide-evidence.test.ts.
 */
// SHELF: 2027-03 the four promotion-gate JSONs under training/data/eval/promotion/ (guardrails_observed, supplementary_checks, sample_size_observed, challenger_full_metrics, carry_gate.per_park_ft) and docs/decisions.md [166] for the ~0.086 LR realized Brier; re-verify after any retrain or promotion of the four champions
export const GUIDE_CHECKS: Record<GuideAnchor, readonly GateCheck[]> = {
  "next-pitch": [
    {
      check: "Brier vs LR baseline",
      result: "-0.0009",
      bar: "beat LR",
      verdict: "Passed",
    },
    {
      check: "Log-loss vs LR baseline",
      result: "-0.011",
      bar: "beat LR",
      verdict: "Passed",
    },
  ],
  "pitch-type": [
    {
      check: "Log-loss vs LR baseline",
      result: "-0.143",
      bar: "beat LR",
      verdict: "Passed",
    },
    {
      check: "Earliest of the four folds",
      result: "0.022",
      bar: "under 0.02",
      verdict: "Over",
    },
    {
      check: "Rows scored",
      result: "709,855",
      bar: "at least 2,000",
      verdict: "Passed",
    },
  ],
  "post-pitch": [
    {
      check: "Calibration error (ECE)",
      result: "0.0013",
      bar: "within +0.015 of LR (0.0016)",
      verdict: "Passed",
    },
    {
      check: "Log-loss vs LR baseline",
      result: "-0.487",
      bar: "within +0.01",
      verdict: "Passed",
    },
    {
      check: "Top-2, unseen 2026",
      result: "80.8%",
      bar: "reported, not gated",
      verdict: "Not gated",
    },
  ],
  "batted-ball": [
    {
      check: "Brier, v2 with carry vs v1 without",
      result: "0.1166 vs 0.1165",
      bar: "within 0.002",
      verdict: "Passed",
    },
    {
      check: "Carry, every park",
      result: "412 to 440 ft",
      bar: "plausible feet, hard gate",
      verdict: "Passed",
    },
    {
      check: "LR baseline, Brier on the same outcomes",
      result: "~0.086",
      bar: "none declared",
      verdict: "LR wins",
    },
  ],
};

/**
 * How many times a figure appears as a WHOLE number in `text`: not part of a longer number and not
 * a signed delta (-0.0009 is a Brier delta, a different quantity from an ECE of 0.0009).
 */
export function countFigure(text: string, value: string): number {
  const escaped = value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return (
    text.match(new RegExp(`(?<![\\d.\\-])${escaped}(?![\\d])`, "g")) ?? []
  ).length;
}

/** Where each card's checks come from, for the test and for the card's source line. */
export const GUIDE_CHECK_SOURCES: Record<GuideAnchor, readonly string[]> = {
  "next-pitch": [`${PROMO}/pitch_outcome_pre_promotion_gate.json`],
  "pitch-type": [`${PROMO}/pitch_type_pre_promotion_gate.json`],
  "post-pitch": [
    `${PROMO}/pitch_outcome_post_experiment_results_full.json`,
    "training/data/eval/pitch_post_backfill_accuracy_v1.json",
  ],
  "batted-ball": [
    `${PROMO}/battedball_outcome_carry_promotion_gate.json`,
    "docs/decisions.md [166]",
  ],
};

/**
 * /accuracy's static claims ([190] SHELF discipline). Every sentence on the page that states a
 * model fact the payload does not carry lives here with its source and an expiry, so
 * check-shelf-markers.sh can see it (it scans src/data/, not src/pages/).
 */

/**
 * The 2026 holdout ([177], PR 210): a season kept out of training and validation, scored once
 * after the model was frozen. OFFLINE - it renders in Part two only, never beside a live figure
 * (decision [199] moved it out of the live retrospective).
 */
// SHELF: 2027-01 docs/decisions.md [177] + PR 210 holdout evidence (pitch_outcome_post v1: 59.1% top-1, 80.8% top-2 on the 2026 holdout); re-verify after any pitch_outcome_post retrain
export const HOLDOUT_2026 = {
  model: "pitch_outcome_post v1",
  top1: "59.1%",
  top2: "80.8%",
  provenance: "verified 2026 holdout, PR 210, decision [177]",
} as const;

/** Outcome-class counts per head: the anchor a bare top-1 % lacks. No endpoint field carries them. */
// SHELF: 2027-03 contracts/feature_pipeline*.json class vocabularies (y5 outcome heads, y7 pitch type); re-verify if a head's class set changes
export const CLASS_COUNT: Record<string, number> = {
  pitch_outcome_pre: 5,
  pitch_outcome_post: 5,
  pitch_type_pre: 7,
};

/** Below this many graded calls no pitch-type top-1 % renders ([183]). */
export const PITCH_TYPE_RENDER_FLOOR = 500;

/** Below this many graded calls in the calibration window no chart draws (ten bins x 30). */
export const CHART_FLOOR = 300;

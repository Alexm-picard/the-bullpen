/**
 * The game page's static claims ([190] SHELF discipline): every sentence on /games/:id that states a
 * fact about a model's quality lives here, dated and sourced, so check-shelf-markers.sh can expire
 * it. The page renders these verbatim as inline captions next to the data they qualify.
 */

// SHELF: 2027-03 docs/decisions.md [180]/[182] + docs/adr/0014: pitch_outcome_pre v2 passed its absolute-calibration gate (ECE < 0.02; experiment_results evidence row id=5); re-verify after any pitch_outcome_pre retrain
export const OUTCOME_CAPTION =
  "Calibrated pre-pitch estimate. It passes calibration (ECE < 0.02); it is not an accuracy claim.";

// SHELF: 2027-03 training/data/eval/promotion/pitch_type_pre_promotion_gate.json (top-label ECE 0.016 < 0.02) + decision [183] honest-framing constraint; re-verify after any pitch_type_pre retrain
export const PITCH_TYPE_CAPTION = "Calibrated prior, not a call.";
export const PITCH_TYPE_PROVENANCE_CLAIM = "calibrated (ECE < 0.02)";

// SHELF: 2027-03 docs/decisions.md [163]: the batted-ball champion is a per-park calibrated PHYSICS ESTIMATE with a documented reality gap, and AllParksResponse carries no per-park uncertainty; re-verify if a carry-uncertainty head ships
export const PARKS_CAPTION =
  "A physics estimate of this ball's carry in each park, calibrated per park. Not a replay, and it carries a known reality gap. The model reports no per-park uncertainty, so none is shown.";

export const LINE_SCORE_CAPTION =
  "Runs only, worked out from the score on each logged pitch. Hits and errors are not in the feed this page reads.";

export const MODEL_GAVE_NOTE =
  "The small figure after each pitch is the probability pitch_outcome_pre gave, before the pitch, to what happened. Low figures are normal for a calibrated model; they are not misses.";

export const INFERRED_NOTE =
  "The feed names an event only for balls in play. Strikeouts and walks are read from the final count and labelled so.";

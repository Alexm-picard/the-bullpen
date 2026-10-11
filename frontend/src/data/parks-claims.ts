/**
 * /parks' static claims ([190] SHELF discipline). Every sentence on the page that states a fact
 * about the batted-ball champion's quality lives here, dated and sourced, so
 * check-shelf-markers.sh can expire it (it scans src/data/, not src/pages/). The page renders
 * these verbatim; the caveat sits under the number it qualifies ([163]).
 */

// SHELF: 2027-03 docs/decisions.md [141]/[163]: the served batted-ball champion is a per-park calibrated PHYSICS ESTIMATE; its physics retrodiction correlates ~0.30 with realized park outcomes and the co-registered linear baseline wins aggregate realized Brier (0.107 vs 0.084; carry v2 ~0.117 vs ~0.086, [166]); re-verify after any battedball_outcome retrain or promotion
export const PHYSICS_CAVEAT_LEAD =
  "A physics estimate, not a record of what happened.";
export const PHYSICS_CAVEAT_BODY =
  "Its retrodiction correlates about 0.30 with real outcomes, and a plain linear baseline still beats it on aggregate Brier. Compare parks with each other; do not read these as home-run rates.";

// SHELF: 2027-03 frontend/src/api/parks.ts AllParksResponse: the endpoint returns point estimates only (no per-park interval); re-verify if a carry-uncertainty head ships
export const NO_UNCERTAINTY_NOTE =
  "The model reports no per-park uncertainty, so none is shown.";

/** The field drawing is generic: no wall geometry is drawn, so it makes no park-shape claim. */
export const FIELD_NOTE =
  "The field is generic: it shows the ball's direction and the model's carry, not any park's walls.";

/** The input ring is the client's rough distance (estimate-landing.ts), sent as hitDistanceFt. */
export const INPUT_RING_NOTE =
  "The hollow ring is the rough distance this page sends the model, worked out from speed and angle.";

/** The Showcase fence (D4 + [202]): fixtures may illustrate scouting, never model performance. */
export const SHOWCASE_FACTORS_NOTE =
  "Illustrative values in the shape a park-factors endpoint would return. GET /v1/parks/factors is not built yet, and no model produced these numbers.";

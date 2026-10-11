/**
 * The Model Guide's copy (SPEC-guide-about §5, decision [195]; the canonical methodology page of
 * [191]/ADR-0017 §4). Figures are NOT here: every number lives in guide-evidence.ts with its source.
 *
 * Anchors are a contract: the game, accuracy and parks pages link to them. `#post-pitch` is new; the
 * other four predate this page's editorial migration and must keep working.
 */
import type { GuideAnchor } from "./guide-evidence";
import { PHYSICS_CAVEAT_LEAD } from "./parks-claims";

export type SeenOn = { label: string; to: string };

export type GuideModelCard = {
  anchor: GuideAnchor;
  n: number;
  title: string;
  /** One line for the index. */
  summary: string;
  /** The declared test, in words, for the index. */
  test: string;
  registry: string;
  stage: string;
  seenOn: readonly SeenOn[];
  givesYou: string;
  sees: string;
  calibratedBy: string;
  heldTo: string;
  /** The honest limit, printed under "Read it this way." */
  readIt: string;
};

// SHELF: 2027-03 the live registry via GET /v1/ops/registry/all (champion versions and promotion dates) and docs/decisions.md [166]/[177]/[182]/[183]; re-verify on every /promote
export const GUIDE_MODELS: readonly GuideModelCard[] = [
  {
    anchor: "next-pitch",
    n: 1,
    title: "The next pitch",
    summary: "Five outcomes for the pitch about to be thrown.",
    test: "Held to calibration",
    registry: "pitch_outcome_pre v2",
    stage: "Champion since Jul 22",
    seenOn: [
      { label: "Games, the next pitch", to: "/games" },
      { label: "Accuracy", to: "/accuracy" },
    ],
    givesYou:
      "Five outcomes for the pitch about to be thrown: ball, called strike, swinging strike, foul, or in play. The game page shows the five as a distribution, never as a single call.",
    sees: "Only what exists before the windup: the count, outs, inning, runners, score, both players' handedness, the park, how this pitcher and this batter have split their pitches across the five outcomes (target encodings), and their last 28 days of form. All of it is cut off at the moment of the pitch, so nothing from later in the game leaks in.",
    calibratedBy:
      "Isotonic, one curve per outcome, fitted on the validation fold; the five calibrated probabilities are renormalized to sum to one. The calibrator ships in the same registry snapshot as the model weights.",
    heldTo:
      "Its declared test is calibration: expected calibration error under 0.02 ([180], ADR-0014), and it must beat the logistic-regression baseline on Brier and log-loss.",
    readIt:
      "It beats the baseline on Brier by less than a thousandth. Its value is that a 30% means 30%, not that it calls pitches the baseline misses.",
  },
  {
    anchor: "pitch-type",
    n: 2,
    title: "Pitch type",
    summary: "Seven pitch types, as a calibrated prior.",
    test: "Held to calibration",
    registry: "pitch_type_pre v1",
    stage: "Champion since Aug 2",
    seenOn: [
      { label: "Games, by type", to: "/games" },
      { label: "Accuracy", to: "/accuracy" },
    ],
    givesYou:
      "A probability for each of seven pitch types before the pitch: four-seam (FF), sinker (SI), cutter (FC), slider (SL, sweepers and slurves folded in), curveball (CU, knuckle-curves folded in), changeup (CH), and everything else (OFF).",
    sees: "The pitcher's arsenal mix, overall and by count; the count, outs, inning and runners; the last two pitches and how deep into the outing he is; times through the order; the batter's side.",
    calibratedBy:
      "Temperature scaling: one positive number divides the model's logits before the softmax, so its ranking never changes and only its confidence does.",
    heldTo:
      "Calibration again: top-label ECE under 0.02 as the mean of four rolling-origin folds, and it must beat its LR baseline on log-loss ([183]).",
    readIt:
      "This is a prior, not a call. It was judged on whether its probabilities are honest, never on how often its top pick is right, so the page shows all seven and never leads with one.",
  },
  {
    anchor: "post-pitch",
    n: 3,
    title: "The pitch just thrown",
    summary: "The same five outcomes, read after the release.",
    test: "Held to Brier score",
    registry: "pitch_outcome_post v1",
    stage: "Champion since Jun 20",
    seenOn: [
      { label: "Accuracy, the post-pitch retrospective", to: "/accuracy" },
    ],
    givesYou:
      "The same five outcomes, but for a pitch that has already left the hand. Because it reads the pitch in flight, it can only ever be shown against a pitch that is finished ([177]).",
    sees: "Everything the next-pitch model sees, plus release speed, plate location and spin.",
    calibratedBy:
      "Isotonic per outcome, renormalized, as with the next-pitch model.",
    heldTo:
      "A Brier score at least 0.002 better than the LR baseline, with log-loss and calibration guardrails.",
    readIt:
      "A read of the pitch just thrown, never a forecast of the next one.",
  },
  {
    anchor: "batted-ball",
    n: 4,
    title: "The batted ball",
    summary: "Outcome and carry for one ball in all 30 parks.",
    test: "Held to non-inferiority",
    registry: "battedball_outcome v2",
    stage: "Champion, a physics estimate",
    seenOn: [
      { label: "Parks", to: "/parks" },
      { label: "Games, a ball in play", to: "/games" },
    ],
    givesYou:
      "For one batted ball, the chance of an out, single, double, triple or home run in each of the 30 parks, and how far it carries in each.",
    sees: "Exit velocity, launch angle, spray angle, distance, the batter's side, the runners and the outs. A small network with one shared trunk and 30 park heads; a second output gives the carry in feet ([166]).",
    calibratedBy:
      "Isotonic per park and per outcome: 30 parks by 5 outcomes, 150 curves.",
    heldTo:
      "v2 was promoted on non-inferiority: adding the carry head could not make the outcome model worse ([166], ADR-0012). It was trained on physics, what each ball would have done in each park, so it is a calibrated estimate of the physics, not a record of what happened ([163]).",
    readIt: `${PHYSICS_CAVEAT_LEAD} Compare parks with each other. Do not read its numbers as home-run rates.`,
  },
];

export const HOW_SCORED = {
  anchor: "how-scored",
  n: 5,
  title: "How accuracy is scored",
  summary: "Live and held-out evidence, and why they never mix.",
  test: "Live and held out, kept apart",
  lead: "Every prediction the site shows is logged with the model version, its inputs and its probabilities. When the pitch or the play resolves, a truth-join pairs the prediction with what happened. The Accuracy page keeps two kinds of evidence apart.",
  live: "Live: the last 7 days of top-1 accuracy and a 30-day reliability chart, from served champion predictions only.",
  heldOut:
    "Held out: rolling-origin cross-validation, four folds over 2015 to 2025, each trained only on earlier seasons. The 2026 season is never trained or tuned on; it is kept to test against.",
  after:
    "They answer different questions. A model can test well on held-out seasons and slip live when the game shifts under it; that is what the drift checks on the Ops page watch for.",
} as const;

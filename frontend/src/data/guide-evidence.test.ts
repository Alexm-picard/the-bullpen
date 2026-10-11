/**
 * The Model Guide's figures against the committed evidence they quote. The JSON is the oracle (an
 * independent artifact the training side writes); the constants are the subject. Formatting rules
 * mirror the page's: ECE to 4 places when it is under 0.01, Brier/log-loss deltas as printed.
 */
import { describe, expect, it } from "vitest";

import {
  BATTED_PHYSICS_VS_REALITY,
  countFigure,
  GUIDE_CHECKS,
  GUIDE_MARGIN,
  POST_HOLDOUT_TOP1,
  PRE_ECE,
  type GateCheck,
  type GuideAnchor,
} from "./guide-evidence";

type Json = Record<string, unknown>;
const PROMOTION = import.meta.glob<Json>(
  "../../../training/data/eval/promotion/*.json",
  { eager: true, import: "default" },
);
const HOLDOUT = import.meta.glob<Json>(
  "../../../training/data/eval/pitch_post_backfill_accuracy_v1.json",
  { eager: true, import: "default" },
);
function read(rel: string): Json {
  const hit = { ...PROMOTION, ...HOLDOUT }[`../../../${rel}`];
  if (!hit) throw new Error(`evidence not found: ${rel}`);
  return hit;
}
const num = (o: Json, ...path: (string | number)[]): number => {
  let at: unknown = o;
  for (const k of path) at = (at as Record<string | number, unknown>)[k];
  if (typeof at !== "number")
    throw new Error(`not a number at ${path.join(".")}`);
  return at;
};
const row = (anchor: GuideAnchor, check: string): GateCheck => {
  const hit = GUIDE_CHECKS[anchor].find((c) => c.check === check);
  if (!hit) throw new Error(`no check "${check}" on ${anchor}`);
  return hit;
};
const pct = (x: number) => `${(x * 100).toFixed(1)}%`;
const count = (x: number) => x.toLocaleString("en-US");

const PRE =
  "training/data/eval/promotion/pitch_outcome_pre_promotion_gate.json";
const TYPE = "training/data/eval/promotion/pitch_type_pre_promotion_gate.json";
const POST =
  "training/data/eval/promotion/pitch_outcome_post_experiment_results_full.json";
const CARRY =
  "training/data/eval/promotion/battedball_outcome_carry_promotion_gate.json";

describe("guide evidence: margin figures", () => {
  it("next pitch: ECE, its LR baseline and the row count match the promotion gate", () => {
    const g = read(PRE);
    expect(PRE_ECE.source).toBe(PRE);
    expect(g.primary_metric).toBe("ece");
    expect(PRE_ECE.value).toBe(num(g, "challenger_metric").toFixed(4));
    expect(PRE_ECE.basis).toContain(
      `LR baseline ${num(g, "champion_metric").toFixed(4)}`,
    );
    expect(PRE_ECE.basis).toContain(count(num(g, "sample_size_observed")));
  });

  it("post-pitch holdout: top-1, sample and the marginal baseline match the 2026 backfill", () => {
    const h = read(POST_HOLDOUT_TOP1.source);
    expect(h.season).toBe(2026);
    expect(POST_HOLDOUT_TOP1.value).toBe(
      pct(num(h, "headline", "top1_accuracy")),
    );
    expect(POST_HOLDOUT_TOP1.basis).toContain(count(num(h, "n_pitches")));
    expect(POST_HOLDOUT_TOP1.basis).toContain(
      pct(num(h, "headline", "marginal_top1_accuracy")),
    );
  });

  it("[183]: no pitch-type figure or check is a top-1 or accuracy claim", () => {
    const words = [
      ...GUIDE_MARGIN["pitch-type"].flatMap((f) => [f.label, f.basis]),
      ...GUIDE_CHECKS["pitch-type"].map((c) => c.check),
    ].join(" ");
    expect(words.toLowerCase()).not.toMatch(/top-1|accuracy/);
  });

  it("[163]: the batted-ball margin says it is a physics estimate", () => {
    expect(BATTED_PHYSICS_VS_REALITY.basis).toContain("physics estimate");
  });
});

describe("guide evidence: checks rows", () => {
  it("next pitch: Brier and log-loss guardrails match", () => {
    const g = read(PRE);
    expect(row("next-pitch", "Brier vs LR baseline").result).toBe(
      num(g, "guardrails_observed", "brier").toFixed(4),
    );
    expect(row("next-pitch", "Log-loss vs LR baseline").result).toBe(
      num(g, "guardrails_observed", "log-loss").toFixed(3),
    );
  });

  it("pitch type: log-loss, the earliest fold over the bar, and rows match", () => {
    const g = read(TYPE);
    expect(row("pitch-type", "Log-loss vs LR baseline").result).toBe(
      num(g, "guardrails_observed", "log-loss").toFixed(3),
    );
    const firstFold = num(g, "supplementary_checks", 0, "per_fold_observed", 0);
    const fold = row("pitch-type", "Earliest of the four folds");
    expect(fold.result).toBe(firstFold.toFixed(3));
    expect(firstFold).toBeGreaterThan(
      num(g, "supplementary_checks", 0, "max_allowed"),
    );
    expect(fold.verdict).toBe("Over");
    expect(row("pitch-type", "Rows scored").result).toBe(
      count(num(g, "sample_size_observed")),
    );
  });

  it("post-pitch: ECE vs LR, log-loss and holdout top-2 match", () => {
    const g = read(POST);
    const ece = row("post-pitch", "Calibration error (ECE)");
    expect(ece.result).toBe(
      num(g, "challenger_full_metrics", "ece").toFixed(4),
    );
    expect(ece.bar).toContain(
      num(g, "champion_full_metrics", "ece").toFixed(4),
    );
    expect(row("post-pitch", "Log-loss vs LR baseline").result).toBe(
      num(g, "guardrails_observed", "log-loss").toFixed(3),
    );
    const h = read(POST_HOLDOUT_TOP1.source);
    expect(row("post-pitch", "Top-2, unseen 2026").result).toBe(
      pct(num(h, "headline", "top2_accuracy")),
    );
  });

  it("batted ball: the v2 vs v1 non-inferiority Brier and the carry range match the carry gate", () => {
    const g = read(CARRY);
    expect(
      row("batted-ball", "Brier, v2 with carry vs v1 without").result,
    ).toBe(
      `${num(g, "challenger_metric").toFixed(4)} vs ${num(g, "champion_metric").toFixed(4)}`,
    );
    const feet = Object.values(
      (g.carry_gate as { per_park_ft: Record<string, number> }).per_park_ft,
    );
    expect(feet).toHaveLength(30);
    expect(row("batted-ball", "Carry, every park").result).toBe(
      `${Math.floor(Math.min(...feet))} to ${Math.round(Math.max(...feet))} ft`,
    );
  });
});

describe("guide evidence: one number, one place", () => {
  it("countFigure matches whole figures only", () => {
    expect(countFigure("ECE 0.0009 and Brier -0.0009", "0.0009")).toBe(1);
    expect(countFigure("0.0016 is not 0.016", "0.016")).toBe(1);
    expect(countFigure("59.1% then 59.15", "59.1%")).toBe(1);
  });

  it("no margin figure value is also a checks result on the same card", () => {
    for (const anchor of Object.keys(GUIDE_MARGIN) as GuideAnchor[]) {
      const results = GUIDE_CHECKS[anchor].map((c) => c.result);
      for (const f of GUIDE_MARGIN[anchor]) {
        for (const r of results) expect(countFigure(r, f.value)).toBe(0);
      }
    }
  });
});

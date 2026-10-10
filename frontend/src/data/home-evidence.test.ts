/**
 * The front page's evidence figures against the committed evidence they quote. The JSON is the
 * oracle here (an independent artifact the training side writes), the constants are the subject:
 * a retrain that moves either number turns this red instead of leaving a stale claim on /.
 */
import { describe, expect, it } from "vitest";

import { POST_PITCH_BRIER, PITCH_TYPE_ECE } from "./home-evidence";

type Gate = {
  challenger_metric: number;
  champion_metric: number;
  primary_metric: string;
  sample_size_observed: number;
};
// Vite resolves these at test time; the paths are the `source` fields the constants declare.
const EVIDENCE = import.meta.glob<Gate>(
  "../../../training/data/eval/promotion/*.json",
  { eager: true, import: "default" },
);
const read = (rel: string): Gate => {
  const hit = EVIDENCE[`../../../${rel}`];
  if (!hit) throw new Error(`evidence not found: ${rel}`);
  return hit;
};

describe("home evidence figures", () => {
  it("pitch-type ECE and its LR baseline match the promotion gate", () => {
    const g = read(PITCH_TYPE_ECE.source);
    expect(g.primary_metric).toBe("ece");
    expect(PITCH_TYPE_ECE.value).toBe(g.challenger_metric.toFixed(3));
    expect(PITCH_TYPE_ECE.basis).toContain(
      `LR baseline ${g.champion_metric.toFixed(3)}`,
    );
  });

  it("post-pitch Brier, its LR baseline and the sample size match the experiment results", () => {
    const g = read(POST_PITCH_BRIER.source);
    expect(g.primary_metric).toBe("brier");
    expect(POST_PITCH_BRIER.value).toBe(g.challenger_metric.toFixed(3));
    expect(POST_PITCH_BRIER.basis).toContain(
      `LR baseline ${g.champion_metric.toFixed(3)}`,
    );
    expect(POST_PITCH_BRIER.basis).toContain(
      `${Math.floor(g.sample_size_observed / 1000)}k`,
    );
  });

  it("[183]: the pitch-type figure is calibration, never top-1 accuracy", () => {
    expect(PITCH_TYPE_ECE.label.toLowerCase()).toContain("calibration");
    expect(`${PITCH_TYPE_ECE.label} ${PITCH_TYPE_ECE.basis}`).not.toMatch(
      /accura|top-1/i,
    );
  });
});

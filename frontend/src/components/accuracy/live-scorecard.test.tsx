/**
 * Unit tests for <LiveScorecard> - the four card states the work order names:
 *
 *   live ........... % + n + window render (and only for live models)
 *   no-truth ....... battedball shows the OFFLINE ECE with its label + the
 *                    [163] calibrated-physics line, never a live %
 *   accumulating ... pitch_type with no truth-joinable volume: promoted date,
 *                    reason, no %
 *   below-floor .... pitch_type live at n < 500: NO % renders; n shown; floor
 *                    stated (a % over 2 predictions is noise wearing a number)
 *
 * Plus the honesty caption: at n >= floor the pitch_type % renders ONLY with
 * the [183] supplementary caption attached.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { ModelRollingAccuracy } from "../../api/rolling-accuracy";
import { theme } from "../../design/theme";

import { LiveScorecard, PITCH_TYPE_RENDER_FLOOR } from "./live-scorecard";

function render(
  models: ModelRollingAccuracy[],
  offlineEce: number | null = 0.031,
): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>
      <LiveScorecard
        models={models}
        windowDays={7}
        battedBallOfflineEce={offlineEce}
      />
    </MantineProvider>,
  );
}

const live = (
  name: string,
  top1: number,
  n: number,
  buckets: { date: string; n: number; top1: number }[] = [],
): ModelRollingAccuracy => ({
  modelName: name,
  status: "live",
  reason: null,
  top1,
  n,
  buckets,
  note: null,
});

const noTruth = (name: string, reason: string): ModelRollingAccuracy => ({
  modelName: name,
  status: "no_live_truth",
  reason,
  top1: null,
  n: null,
  buckets: null,
  note: null,
});

const BATTED = noTruth(
  "battedball_outcome",
  "no join keys - calibrated physics estimate (decision [163])",
);

describe("LiveScorecard", () => {
  it("renders the live % with its n and window for the pitch heads", () => {
    const html = render([
      live("pitch_outcome_pre", 0.412, 42977, [
        { date: "2026-08-01", n: 4000, top1: 0.41 },
        { date: "2026-08-02", n: 3900, top1: 0.42 },
      ]),
      live("pitch_outcome_post", 0.5561, 16084),
      BATTED,
      noTruth("pitch_type_pre", "promoted 2026-08-02; accumulating"),
    ]);
    expect(html).toContain("41.2%");
    expect(html).toContain("55.6%");
    // every % carries its n and window - a percentage without a denominator is decoration
    expect(html).toContain("n = 42,977");
    expect(html).toContain("n = 16,084");
    expect(html).toContain("rolling 7d");
    // two daily buckets -> the sparkline polyline renders for pre
    expect(html).toContain("<polyline");
  });

  it("batted-ball shows the offline figure with its label and the [163] line, never a live %", () => {
    const html = render([BATTED]);
    expect(html).toContain("ECE 0.031 (offline)");
    expect(html).toContain("calibrated physics estimate ([163])");
    expect(html).not.toMatch(/\d+\.\d%/);
  });

  it("pitch_type with no truth-joinable volume renders the accumulating state, no %", () => {
    const html = render([
      noTruth(
        "pitch_type_pre",
        "promoted 2026-08-02; live predictions are accumulating",
      ),
    ]);
    expect(html).toContain("accumulating - promoted 2026-08-02");
    expect(html).not.toMatch(/\d+\.\d%/);
  });

  it("pitch_type BELOW the floor renders n but refuses the %", () => {
    const html = render([live("pitch_type_pre", 1.0, 2)]);
    // a 100%-over-2-predictions would be the exact "noise wearing a number" the order bans
    expect(html).not.toContain("100.0%");
    expect(html).not.toMatch(/\d+\.\d%/);
    expect(html).toContain("accumulating - promoted 2026-08-02");
    expect(html).toContain("n = 2");
    expect(html).toContain(String(PITCH_TYPE_RENDER_FLOOR));
  });

  it("pitch_type AT the floor renders the % only with the [183] supplementary caption", () => {
    const html = render([
      live("pitch_type_pre", 0.4443, PITCH_TYPE_RENDER_FLOOR),
    ]);
    expect(html).toContain("44.4%");
    expect(html).toContain(
      "calibrated prior; top-1 is supplementary, never the claim ([183])",
    );
  });

  it("the sparkline is decorative only - hidden from the a11y tree", () => {
    const html = render([
      live("pitch_outcome_pre", 0.41, 1000, [
        { date: "2026-08-01", n: 500, top1: 0.4 },
        { date: "2026-08-02", n: 500, top1: 0.42 },
      ]),
    ]);
    expect(html).toContain('aria-hidden="true"');
  });
});

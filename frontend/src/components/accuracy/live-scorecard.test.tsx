/**
 * Unit tests for <LiveScorecard> - the card states the work order names, plus
 * the review-round gaps:
 *
 *   live ........... % + class count + n + window render, live-only
 *   no-truth ....... the BACKEND's reason renders verbatim (the FE owns no
 *                    facts); battedball echoes the offline ECE with a
 *                    calibration gloss and never a live %
 *   accumulating ... pitch_type live below the floor: no %, floor stated
 *   degraded ....... a "live" entry with null numbers NEVER renders 0.0%
 *                    (the fabricated-zero MUST-FIX); a family missing from
 *                    the payload renders a stated absence, never nothing
 *
 * The [183] caption comes from the endpoint's note field (single source);
 * the FE fallback exists only for a payload without one, and both paths are
 * pinned. Mutation-verified: floor bypass, caption drop, and the
 * always-live-card mutation each red.
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
  top1: number | null,
  n: number | null,
  buckets: { date: string; n: number; top1: number }[] = [],
  note: string | null = null,
): ModelRollingAccuracy => ({
  modelName: name,
  status: "live",
  reason: null,
  top1,
  n,
  buckets,
  note,
});

const noTruth = (
  name: string,
  reason: string | null,
  note: string | null = null,
): ModelRollingAccuracy => ({
  modelName: name,
  status: "no_live_truth",
  reason,
  top1: null,
  n: null,
  buckets: null,
  note,
});

const BATTED = noTruth(
  "battedball_outcome",
  "no live pitch keys - a calibrated physics estimate (decision [163]) -" +
    " structurally unavailable, not merely pending",
);

describe("LiveScorecard", () => {
  it("renders the live % with its class count, n and window for the pitch heads", () => {
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
    // every % carries its anchor (top-1 of HOW MANY), its n, and its window
    expect(html).toContain("of 5 classes");
    expect(html).toContain("n = 42,977");
    expect(html).toContain("n = 16,084");
    expect(html).toContain("rolling 7d");
    // two daily buckets -> the sparkline polyline renders for pre
    expect(html).toContain("<polyline");
  });

  it("a no_live_truth pitch head renders the backend's reason and no %", () => {
    // The mutation this pins: pre/post rendering LivePctCard unconditionally
    // (ignoring status) previously stayed green because no test passed a
    // no_live_truth pitch head.
    const html = render([
      noTruth(
        "pitch_outcome_pre",
        "no truth-joined champion predictions in the window",
      ),
    ]);
    expect(html).toContain("no live truth");
    expect(html).toContain(
      "no truth-joined champion predictions in the window",
    );
    expect(html).not.toMatch(/\d+\.\d%/);
  });

  it("a live entry with null numbers NEVER renders a fabricated 0.0%", () => {
    // top1: null is the payload's encoding of "no number exists"; ?? 0 was
    // the exact bug - a malformed body rendered as realized 0.0% accuracy.
    const html = render([live("pitch_outcome_pre", null, null)]);
    expect(html).not.toContain("0.0%");
    expect(html).not.toMatch(/\d+\.\d%/);
    expect(html).toContain("without its numbers");
  });

  it("batted-ball echoes the offline figure with a calibration gloss and the backend's [163] reason", () => {
    const html = render([BATTED]);
    expect(html).toContain("offline ECE 0.031 (calibration error)");
    expect(html).toContain("structurally unavailable, not merely pending");
    expect(html).not.toMatch(/\d+\.\d%/);
  });

  it("batted-ball with no offline figure still states its no-truth state", () => {
    const html = render([BATTED], null);
    expect(html).toContain("no live truth");
    expect(html).toContain("[163]");
    expect(html).not.toContain("offline ECE");
  });

  it("pitch_type with no truth-joinable volume renders the backend's reason, never an FE-invented headline", () => {
    // An infra outage must not be captioned as accumulation: the backend's
    // reason IS the headline fact, whatever it says.
    const html = render([
      noTruth(
        "pitch_type_pre",
        "analytical store not configured in this environment",
      ),
    ]);
    expect(html).toContain("no live truth yet");
    expect(html).toContain("analytical store not configured");
    expect(html).not.toContain("accumulating");
    expect(html).not.toMatch(/\d+\.\d%/);
  });

  it("pitch_type BELOW the floor renders n but refuses the %", () => {
    const html = render([live("pitch_type_pre", 1.0, 2)]);
    // a 100%-over-2-predictions would be the exact "noise wearing a number"
    expect(html).not.toContain("100.0%");
    expect(html).not.toMatch(/\d+\.\d%/);
    expect(html).toContain("accumulating");
    expect(html).toContain("n = 2");
    expect(html).toContain(String(PITCH_TYPE_RENDER_FLOOR));
  });

  it("pitch_type AT the floor renders the % with the endpoint's own [183] note as the caption", () => {
    const html = render([
      live(
        "pitch_type_pre",
        0.4443,
        PITCH_TYPE_RENDER_FLOOR,
        [],
        "calibrated prior ([183]): top-1 is supplementary, never the claim;" +
          " the site renders no % below n = 500",
      ),
    ]);
    expect(html).toContain("44.4%");
    expect(html).toContain("of 7 classes");
    expect(html).toContain(
      "calibrated prior ([183]): top-1 is supplementary, never the claim",
    );
  });

  it("pitch_type at the floor WITHOUT a note falls back to the FE caption", () => {
    const html = render([
      live("pitch_type_pre", 0.4443, PITCH_TYPE_RENDER_FLOOR),
    ]);
    expect(html).toContain("44.4%");
    expect(html).toContain(
      "calibrated prior; top-1 is supplementary, never the claim ([183])",
    );
  });

  it("a family missing from the payload renders a stated absence, never nothing", () => {
    const html = render([BATTED]);
    expect(html).toContain("pitch_outcome_pre");
    expect(html).toContain("pitch_outcome_post");
    expect(html).toContain("pitch_type_pre");
    expect(html).toContain("not reported");
    expect(html).toContain("omitted this family");
  });

  it("a family the endpoint ADDS beyond the contracted four still renders", () => {
    // The mirror of the omission case: extras render through the generic
    // shape rather than silently vanishing.
    const html = render([
      BATTED,
      noTruth("some_future_model", "registered but not yet contracted here"),
    ]);
    expect(html).toContain("some_future_model");
    expect(html).toContain("registered but not yet contracted here");
  });

  it("a no_live_truth entry with a null reason states that the reason is missing", () => {
    const html = render([noTruth("pitch_outcome_post", null)]);
    expect(html).toContain("reported no reason");
  });

  it("the sparkline itself is aria-hidden - the svg, not some other element", () => {
    const html = render([
      live("pitch_outcome_pre", 0.41, 1000, [
        { date: "2026-08-01", n: 500, top1: 0.4 },
        { date: "2026-08-02", n: 500, top1: 0.42 },
      ]),
    ]);
    expect(html).toMatch(/<svg[^>]*aria-hidden="true"/);
  });
});

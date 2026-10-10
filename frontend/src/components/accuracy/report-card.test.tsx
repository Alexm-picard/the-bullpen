/**
 * ReportCard / NoLiveTruthStrip: the per-model states the retired LiveScorecard carried, plus the
 * two-window contract of decision [199] (chart window and top-1 window each labelled, never
 * confused, calibration independent of status).
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";

import type {
  Calibration,
  ModelRollingAccuracy,
} from "../../api/rolling-accuracy";
import { theme } from "../../design/theme";

import { NoLiveTruthStrip, ReportCard } from "./report-card";

const GENERATED = "2026-10-09T23:30:00Z";

const CAL: Calibration = {
  definition: "top_label",
  binWidth: 0.1,
  minReadableN: 30,
  windowDays: 30,
  windowKind: "calendar",
  truthFrom: "2026-09-09",
  truthThrough: "2026-10-08",
  gameDays: 30,
  n: 9330,
  bins: [
    {
      lower: 0.3,
      upper: 0.4,
      n: 5000,
      hits: 1750,
      meanConfidence: 0.35,
      observed: 0.35,
    },
    {
      lower: 0.6,
      upper: 0.7,
      n: 4330,
      hits: 2598,
      meanConfidence: 0.65,
      observed: 0.6,
    },
  ],
};

function model(over: Partial<ModelRollingAccuracy>): ModelRollingAccuracy {
  return {
    modelName: "pitch_outcome_pre",
    status: "live",
    reason: null,
    top1: 0.418,
    n: 18406,
    buckets: [
      { date: "2026-10-07", n: 2600, top1: 0.401 },
      { date: "2026-10-08", n: 2700, top1: 0.43 },
    ],
    note: null,
    calibration: CAL,
    ...over,
  };
}

function render(node: React.ReactNode): string {
  return renderToStaticMarkup(
    <MemoryRouter>
      <MantineProvider theme={theme}>{node}</MantineProvider>
    </MemoryRouter>,
  );
}

const card = (
  m: ModelRollingAccuracy | undefined,
  modelName = m?.modelName ?? "pitch_outcome_pre",
  variant: "lede" | "pair" = "pair",
) =>
  render(
    <ReportCard
      modelName={modelName}
      model={m}
      windowDays={7}
      generatedAt={GENERATED}
      variant={variant}
    />,
  );

describe("ReportCard - two windows, each labelled", () => {
  it("labels the chart with its 30-day window and the figure with its 7-day window", () => {
    const html = card(model({}));
    expect(html).toContain("Calibration, last 30 days");
    expect(html).toContain("9,330 graded, through Oct 8");
    expect(html).toContain("41.8%");
    expect(html).toContain("Top-1 of 5 classes, last 7 days");
    expect(html).toContain("18,406 graded. Daily range 40.1% to 43.0%.");
  });

  it("prints the generated note, never a hand-written reading", () => {
    const html = card(model({}));
    expect(html).toContain(
      "At 0.6 to 0.7 confidence these calls came true 60% of the time, against 65% predicted (4,330 calls).",
    );
  });

  it("keeps the chart when the 7-day window is no_live_truth (calibration is independent of status)", () => {
    const html = card(
      model({
        status: "no_live_truth",
        reason: "no games in the window",
        top1: null,
        n: null,
        buckets: null,
      }),
    );
    expect(html).toContain('role="img"');
    expect(html).toContain("Top-1, last 7 days: no games in the window");
    expect(html).not.toMatch(/\d+\.\d%/);
  });

  it("draws no chart below the 300-call floor and says so", () => {
    expect(card(model({ calibration: { ...CAL, n: 143 } }))).toContain(
      "Accumulating: 143 graded. The chart draws at 300.",
    );
    const empty = card(
      model({
        calibration: {
          ...CAL,
          n: 0,
          bins: [],
          truthFrom: null,
          truthThrough: null,
        },
      }),
    );
    expect(empty).toContain(
      "No graded calls in this window yet. The chart draws at 300.",
    );
    expect(empty).not.toContain('role="img"');
    expect(empty).not.toContain("graded, through");
  });

  it("a null calibration shows the model's reason, else the unavailable line", () => {
    expect(card(model({ calibration: null }))).toContain(
      "Calibration unavailable right now.",
    );
    expect(
      card(
        model({
          status: "no_live_truth",
          reason: "analytical store not configured",
          calibration: null,
          top1: null,
          n: null,
        }),
      ),
    ).toContain("analytical store not configured");
  });

  it("labels the offseason window as the last days of play, with dates", () => {
    const html = card(
      model({
        calibration: {
          ...CAL,
          windowKind: "last_days_of_play",
          truthFrom: "2026-09-12",
          truthThrough: "2026-11-01",
        },
      }),
    );
    expect(html).toContain("Calibration, the last 30 days of play");
    expect(html).toContain("9,330 graded, Sep 12 to Nov 1");
  });
});

describe("ReportCard - states carried over from LiveScorecard", () => {
  it("a live entry with null numbers NEVER renders a fabricated 0.0%", () => {
    const html = card(model({ top1: null, n: null }));
    expect(html).not.toContain("0.0%");
    expect(html).toContain("live status without its numbers");
  });

  it("a no_live_truth entry with a null reason states that the reason is missing", () => {
    expect(
      card(
        model({ status: "no_live_truth", reason: null, top1: null, n: null }),
      ),
    ).toContain("the endpoint gave no reason for this state.");
  });

  it("pitch_type BELOW the 500 floor renders n but refuses the %", () => {
    const html = card(
      model({ modelName: "pitch_type_pre", top1: 0.45, n: 312 }),
    );
    expect(html).toContain(
      "Accumulating: 312 of 500 graded in the last 7 days.",
    );
    expect(html).not.toContain("45.0%");
  });

  it("pitch_type AT the floor renders a supplementary % with the [183] framing and the endpoint's note", () => {
    const html = card(
      model({
        modelName: "pitch_type_pre",
        top1: 0.451,
        n: 500,
        note: "calibrated prior ([183])",
      }),
    );
    expect(html).toContain('class="ed-fig__value acc-supp"');
    expect(html).toContain("Top-1 of 7 classes, last 7 days. Supplementary");
    expect(html).toContain(
      "Promoted for calibration, so the chart is the claim.",
    );
    expect(html).toContain("calibrated prior ([183])");
  });

  it("a family missing from the payload renders a stated absence, never nothing", () => {
    expect(card(undefined, "pitch_outcome_pre")).toContain(
      "Not reported: the endpoint omitted this model from its response.",
    );
  });

  it("an unknown family still renders under its own name, with no invented class count", () => {
    const html = card(model({ modelName: "pitch_spin_pre" }));
    expect(html).toContain("pitch_spin_pre");
    expect(html).toContain("Top-1, last 7 days");
  });

  it("the lede links to the guide and down to the held-out record", () => {
    const html = card(
      model({ modelName: "pitch_outcome_post" }),
      "pitch_outcome_post",
      "lede",
    );
    expect(html).toContain("The post-pitch retrospective");
    expect(html).toContain('href="/models/guide#how-scored"');
    expect(html).toContain('href="#offline-record"');
  });
});

describe("NoLiveTruthStrip", () => {
  it("states the structural absence, quotes the endpoint and links to the retrodiction", () => {
    const html = render(
      <NoLiveTruthStrip
        model={model({
          modelName: "battedball_outcome",
          status: "no_live_truth",
          reason: "structurally unavailable",
          calibration: null,
        })}
      />,
    );
    expect(html).toContain("Batted balls: no live truth");
    expect(html).toContain("structurally unavailable");
    expect(html).toContain('href="#retrodicted"');
    expect(html).not.toContain('role="img"');
  });
});

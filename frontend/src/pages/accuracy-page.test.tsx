/**
 * /accuracy (decision [199], layout B on the [195] identity).
 *
 * Renders the full page with the three query caches seeded synchronously (setQueryData), so each
 * test drives one data shape through the real components. Assertions are SCOPED to the part they
 * are about (pins-decay rule: a whole-page toContain is a global assertion pinning a local
 * property, and this page legitimately prints "30%" in its headline and the holdout's "59.1%").
 */
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";

import type { ModelScorecardRow } from "../api/accuracy";
import {
  ROLLING_ACCURACY_KEY,
  type Calibration,
  type ModelRollingAccuracy,
} from "../api/rolling-accuracy";
import { theme } from "../design/theme";

import { asOfStamp } from "../lib/as-of";

import AccuracyPage from "./accuracy-page";
import { visibleText } from "../test-support/visible-text";

const HEADS = [
  "pitch_outcome_pre",
  "pitch_outcome_post",
  "battedball_outcome",
  "pitch_type_pre",
];

const EMPTY_CAL: Calibration = {
  definition: "top_label",
  binWidth: 0.1,
  minReadableN: 30,
  windowDays: 30,
  windowKind: "calendar",
  truthFrom: null,
  truthThrough: null,
  gameDays: 0,
  n: 0,
  bins: [],
};

const FULL_CAL: Calibration = {
  ...EMPTY_CAL,
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

/** The pre-deploy prod reality: every head no_live_truth, calibration n 0 with null dates. */
function preDeployModels(): ModelRollingAccuracy[] {
  return HEADS.map((modelName) => ({
    modelName,
    status: "no_live_truth" as const,
    reason: "no truth-joined volume in this environment",
    top1: null,
    n: null,
    buckets: null,
    note: null,
    calibration: modelName === "battedball_outcome" ? null : EMPTY_CAL,
  }));
}

function liveModels(): ModelRollingAccuracy[] {
  return HEADS.map((modelName) =>
    modelName === "battedball_outcome"
      ? {
          modelName,
          status: "no_live_truth" as const,
          reason: "structurally unavailable",
          top1: null,
          n: null,
          buckets: null,
          note: null,
          calibration: null,
        }
      : {
          modelName,
          status: "live" as const,
          reason: null,
          top1: 0.594,
          n: 18377,
          buckets: null,
          note: null,
          calibration: FULL_CAL,
        },
  );
}

const ROW = (over: Partial<ModelScorecardRow>): ModelScorecardRow => ({
  modelName: "pitch_outcome_post",
  evidenceModelName: null,
  stage: "champion",
  baselineModelName: null,
  primaryMetric: null,
  evaluation: "offline rolling-origin CV (4 folds, 2015-2025 held-out)",
  gateStatus: "passed",
  verdictOutcome: null,
  sampleSize: 710214,
  brier: 0.104,
  ece: 0.0013,
  logLoss: null,
  eceVsRetro: null,
  vsBaselineMargin: -0.045,
  brierCvMean: null,
  brierCvStd: null,
  eceCvMean: null,
  eceCvStd: null,
  calibrationNote: null,
  generatedAt: null,
  gitCommit: null,
  ...over,
});

function render({
  models = preDeployModels(),
  generatedAt = "2026-10-09T23:30:00Z" as unknown,
  scorecard = [] as ModelScorecardRow[],
}: {
  models?: ModelRollingAccuracy[];
  generatedAt?: unknown;
  scorecard?: ModelScorecardRow[];
} = {}): string {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  client.setQueryData(["ops", "accuracy"], scorecard);
  client.setQueryData(["ops", "backfill-accuracy"], null);
  client.setQueryData(ROLLING_ACCURACY_KEY, {
    windowDays: 7,
    calibrationWindowDays: 30,
    generatedAt,
    models,
  });
  return renderToStaticMarkup(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <MantineProvider theme={theme}>
          <AccuracyPage />
        </MantineProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** The two parts, split on their section anchors. */
function parts(html: string): { live: string; offline: string } {
  const liveAt = html.indexOf('aria-labelledby="live-record"');
  const offlineAt = html.indexOf('id="offline-record"');
  return {
    live: html.slice(liveAt, offlineAt),
    offline: html.slice(offlineAt),
  };
}

/** Visible text only (Mantine style blocks and attributes dropped); see test-support/visible-text. */
const text = visibleText;

describe("asOfStamp", () => {
  it("renders nothing for an unparseable or null stamp - never a 1969 date", () => {
    expect(asOfStamp("not-a-date")).toBe("");
    expect(asOfStamp(null)).toBe("");
  });

  it("renders the ET stamp for a valid one", () => {
    expect(asOfStamp("2026-10-09T23:30:00Z")).toBe(
      "as of Oct 9, 2026, 7:30 PM ET, refreshed every 5 min",
    );
  });
});

describe("AccuracyPage", () => {
  it("survives an unparseable generatedAt and renders no stamp", () => {
    const html = render({ generatedAt: "not-a-date" });
    expect(html).toContain("The offline record");
    expect(html).not.toContain("as of ");
    expect(html).not.toContain("1969");
  });

  it("has exactly one <h1>", () => {
    expect((render().match(/<h1/g) ?? []).length).toBe(1);
  });

  it("keeps the live and offline parts as separate, ordered sections with their surface labels", () => {
    const html = render();
    const { live, offline } = parts(html);
    expect(live).toContain("Part one. Live, truth-joined");
    expect(offline).toContain(
      "Part two. Offline, held-out, never live outcomes",
    );
    expect(html.indexOf('aria-labelledby="live-record"')).toBeLessThan(
      html.indexOf('id="offline-record"'),
    );
  });

  it("puts the 2026 holdout in Part two ONLY - never beside a live figure", () => {
    const { live, offline } = parts(render({ models: liveModels() }));
    expect(offline).toContain("The 2026 holdout");
    expect(offline).toContain("59.1% top-1");
    expect(live).not.toContain("59.1%");
    expect(live).not.toContain("holdout");
  });

  it("renders the pre-deploy state honestly: no chart, no dates, no number in Part one", () => {
    const { live } = parts(render());
    expect(live).not.toContain('role="img"');
    expect(live).toContain(
      "No graded calls in this window yet. The chart draws at 300.",
    );
    expect(live).not.toContain("graded, through");
    expect(text(live)).not.toMatch(/\d+(\.\d+)?%/);
    expect(text(live)).not.toMatch(/\b0\.\d{2,3}\b/);
  });

  it("populated: one chart per pitch head, none for batted balls, each window labelled", () => {
    const { live } = parts(render({ models: liveModels() }));
    expect(live.match(/role="img"/g)).toHaveLength(3);
    expect(
      live.match(/Calibration, last 30 days/g)?.length,
    ).toBeGreaterThanOrEqual(3);
    expect(live).toContain("Top-1 of 5 classes, last 7 days");
    expect(live).toContain("Batted balls: no live truth");
    expect(live).toContain("structurally unavailable");
  });

  it("the how-to-read panel states both windows from the payload", () => {
    expect(render()).toContain(
      "The charts cover 30 days so the confident bins fill up. The top-1 figures cover the last 7.",
    );
  });

  it("offline empty states never invent numbers", () => {
    const { offline } = parts(render());
    expect(offline).toContain("No held-out scores yet.");
    expect(offline).toContain("Not served yet:");
  });

  it("offline table: evaluation label verbatim, gate sentence derived, ece_vs_retro caveat with its value", () => {
    const { offline } = parts(
      render({
        scorecard: [
          ROW({}),
          ROW({
            modelName: "battedball_outcome",
            ece: 0.009,
            eceVsRetro: 0.003,
          }),
        ],
      }),
    );
    expect(offline).toContain(
      "offline rolling-origin CV (4 folds, 2015-2025 held-out)",
    );
    expect(offline).toContain("All 2 passed their declared gate.");
    expect(offline).toContain(
      "battedball_outcome: reality ECE, calibration against what actually happened. Its ece_vs_retro (0.003) is a self-referential gap",
    );
    expect(offline).toContain("0.104");
  });

  it("offline table: a non-passing gate is named, never summarised as passed", () => {
    const { offline } = parts(
      render({
        scorecard: [
          ROW({}),
          ROW({
            modelName: "pitch_type_pre",
            gateStatus: "would_fail_guardrail",
          }),
        ],
      }),
    );
    expect(offline).toContain(
      "Not passed: pitch_type_pre (would_fail_guardrail).",
    );
    expect(offline).not.toContain("passed their declared gate");
  });
});

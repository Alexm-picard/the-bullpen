// @vitest-environment jsdom
/**
 * /ops, "The systems register" (SPEC-ops section 12). The live state renders with every query
 * cache seeded (setQueryData with a fixed updatedAt), so the real mappers, status rules and
 * components run; fetch is stubbed to never resolve so nothing replaces the seeded data.
 *
 * Assertions are SCOPED to a section (pins-decay rule): a whole-page toContain would pin a local
 * property globally. Section text comes from visibleText (the shared helper, never a regex strip);
 * `_ ` is normalized to `_` because <wbr> after each underscore is a tag boundary there.
 */
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import {
  afterEach,
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from "vitest";

import type {
  DriftMetric,
  LatencyStat,
  ModelVersion,
  OpsEventsPage,
  RetrainingTrigger,
} from "../../api/ops";
import { readTime } from "../../components/ops/ops-format";
import { theme } from "../../design/theme";
import { installMantineShims } from "../../test-support/behavioral";
import { visibleText } from "../../test-support/visible-text";

import OpsPage from "./ops-page";

const NOW = new Date("2026-10-11T23:42:00Z");
const READ_AT = new Date("2026-10-11T23:41:00Z").getTime();

function mv(
  id: number,
  modelName: string,
  version: string,
  stage: string,
): ModelVersion {
  return {
    id,
    modelName,
    version,
    artifactPath: "",
    metadataPath: "",
    trainingDataHash: "",
    trainingDataWindow: "",
    featureSchemaHash: "",
    evalMetrics: "",
    trainedAt: "2026-07-22T12:00:00Z",
    promotedAt: null,
    stage,
    createdBy: null,
    notes: null,
    createdAt: "2026-07-22T12:00:00Z",
    updatedAt: "2026-07-22T12:00:00Z",
  };
}

const REGISTRY = [
  mv(1, "pitch_outcome_pre", "v2", "CHAMPION"),
  mv(2, "pitch_outcome_post", "v1", "CHAMPION"),
  mv(3, "pitch_type_pre", "v1", "CHAMPION"),
  mv(4, "battedball_outcome", "v2", "CHAMPION"),
  mv(12, "pitch_type_pre", "v9", "CANDIDATE"),
  mv(6, "pitch_outcome_lr_baseline", "v1", "SHADOW"),
  mv(7, "battedball_outcome", "v1", "ARCHIVED"),
];

const lat = (
  modelName: string,
  modelVersion: string,
  p99Ms: number,
  n: number,
): LatencyStat => ({
  modelName,
  modelVersion,
  sampleCount: n,
  p50Ms: 0.9,
  p95Ms: 1.6,
  p99Ms,
  p999Ms: 6.8,
});

const LAT_24H = [lat("pitch_outcome_pre", "v2", 2.1, 18204)];
const LAT_7D = [
  lat("pitch_outcome_pre", "v2", 2.2, 121400),
  lat("battedball_outcome", "v2", 5.1, 15920),
];

const dm = (over: Partial<DriftMetric>): DriftMetric => ({
  computedAt: "2026-10-11T06:00:00Z",
  modelName: "pitch_outcome_post",
  modelVersionId: 2,
  metricType: "psi_feature",
  featureOrSegment: "releaseSpeedMph",
  metricValue: 0.06,
  sampleSize: 41200,
  windowStart: "",
  windowEnd: "",
  tag: "",
  ...over,
});

const DRIFT: Record<string, DriftMetric[]> = {
  pitch_outcome_post: [
    dm({}),
    dm({
      featureOrSegment: "pfxXIn",
      metricValue: 0.31,
      tag: "e2-induced-drill",
    }),
    dm({ featureOrSegment: "spinAxisDeg", metricValue: 0.21, sampleSize: 180 }),
    dm({
      metricType: "calibration_error",
      featureOrSegment: "all",
      metricValue: 0.013,
      sampleSize: 6112,
    }),
  ],
  battedball_outcome: [
    dm({
      modelName: "battedball_outcome",
      featureOrSegment: "launchSpeedMph",
      metricValue: 0.04,
      sampleSize: 2140,
    }),
  ],
};

const trig = (over: Partial<RetrainingTrigger>): RetrainingTrigger => ({
  id: 1,
  triggerId: "t1",
  modelName: "pitch_type_pre",
  triggerType: "SCHEDULED",
  triggerMetadata: null,
  status: "SUCCEEDED",
  enqueuedAt: "2026-10-10T06:00:00Z",
  startedAt: "2026-10-10T06:04:00Z",
  finishedAt: "2026-10-11T06:52:00Z",
  producedVersionId: 12,
  errorMessage: null,
  ...over,
});

const RETRAIN = [
  trig({}),
  trig({
    triggerId: "t2",
    modelName: "pitch_outcome_pre",
    status: "RUNNING",
    finishedAt: null,
    producedVersionId: null,
  }),
  trig({ triggerId: "t3", modelName: "pitch_type_pre", producedVersionId: 3 }), // promoted since
];

const EVENTS: OpsEventsPage = {
  rows: [
    {
      id: 3,
      occurredAt: "2026-10-11T07:00:00Z",
      type: "DRIFT_OK",
      detail: "Nightly sweep.",
    },
    {
      id: 2,
      occurredAt: "2026-10-10T15:40:00Z",
      type: "DEPLOY",
      detail: "Build deployed via deploy.sh.",
    },
    {
      id: 1,
      occurredAt: "2026-10-08T22:10:00Z",
      type: "RESTORE_DRILL",
      detail: "Restore drill passed.",
    },
  ],
  page: 0,
  size: 20,
  hasNext: false,
};

function seeded(): QueryClient {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  });
  const set = (key: readonly unknown[], data: unknown) =>
    client.setQueryData(key, data, { updatedAt: READ_AT });
  set(["ops", "registry", "all"], REGISTRY);
  set(["ops", "routing"], []);
  set(["ops", "latency", 1], LAT_24H);
  set(["ops", "latency", 7], LAT_7D);
  set(["ops", "retrain", "_all"], RETRAIN);
  set(["ops", "events", 0, 20], EVENTS);
  for (const name of new Set(REGISTRY.map((v) => v.modelName))) {
    set(["ops", "drift", name], DRIFT[name] ?? []);
  }
  return client;
}

function renderLive() {
  return render(
    <MemoryRouter>
      <QueryClientProvider client={seeded()}>
        <MantineProvider theme={theme}>
          <OpsPage />
        </MantineProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

const section = (name: string) => screen.getByRole("region", { name });
const textOf = (el: Element) => visibleText(el.outerHTML).replace(/_ /g, "_");
const statusOf = (name: string) => {
  const li = within(
    screen.getByRole("navigation", { name: "In this register" }),
  )
    .getByRole("link", { name })
    .closest("li");
  if (!li) throw new Error(`no contents row for ${name}`);
  return li.querySelector(".op-toc__status")?.textContent ?? "";
};

beforeAll(installMantineShims);
beforeEach(() => {
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(NOW);
  vi.stubGlobal(
    "fetch",
    vi.fn(() => new Promise(() => {})),
  );
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("OpsPage, live", () => {
  it("serving: stage words, no LIVE/AWAITING badges, no ECE column, archived rows gone", () => {
    renderLive();
    const t = textOf(section("Serving"));
    expect(t).toContain("pitch_outcome_pre v2");
    expect(t).toMatch(/Champion/);
    expect(t).toMatch(/Candidate/);
    expect(t).toMatch(/Shadow/);
    expect(t).not.toMatch(/LIVE|AWAITING/);
    expect(t).not.toContain("ECE");
    expect(t).not.toContain("battedball_outcome v1");
  });

  it("every number in the Serving status line is on the page: counts match the rows, p99 matches the table", () => {
    renderLive();
    const line = statusOf("Serving");
    const fleet = section("Serving").querySelector('[data-testid="ops-fleet"]');
    const latency = section("Serving").querySelector(
      '[data-testid="ops-latency"]',
    );
    if (!fleet || !latency) throw new Error("serving tables missing");
    const stages = [...fleet.querySelectorAll(".op-stage")].map(
      (s) => s.textContent,
    );
    const count = (w: string) => stages.filter((s) => s === w).length;
    expect(line).toContain(`${count("Champion")} champions`);
    expect(line).toContain(
      `${count("Shadow")} ${count("Shadow") === 1 ? "shadow" : "shadows"}`,
    );
    expect(line).toContain(`${count("Candidate")} candidate`);
    const p99 = /slowest p99 ([\d.]+) ms/.exec(line)?.[1];
    expect(p99).toBeDefined();
    const p99Cells = [...latency.querySelectorAll("tbody td.op-strong")].map(
      (c) => c.textContent,
    );
    expect(p99Cells).toContain(p99);
  });

  it("drift: the drill row is labelled in the row, the notice and the status line, never as organic", () => {
    renderLive();
    const drift = section("Drift");
    const notice = within(drift).getByRole("status");
    expect(notice.textContent).toContain("e2-induced-drill");
    expect(notice.textContent).toContain("not organic production drift");
    const psi = drift.querySelector('[data-testid="ops-psi"]');
    if (!psi) throw new Error("psi table missing");
    const pfx = [...psi.querySelectorAll("tbody tr")].find((r) =>
      r.querySelector("td")?.textContent?.startsWith("pfxXIn"),
    );
    expect(pfx?.querySelector("td:last-child")?.textContent).toBe("overdrill");

    const line = statusOf("Drift");
    expect(line).toContain("Nothing organic over a line");
    const drillRows = [...psi.querySelectorAll("tbody tr")].filter((r) =>
      r.querySelector("td:last-child")?.textContent?.includes("drill"),
    ).length;
    expect(line).toContain(`${drillRows} drill row over, labelled`);
  });

  it("drift: a value under the sample floor is shown but not counted", () => {
    renderLive();
    const t = textOf(section("Drift"));
    expect(t).toMatch(/spinAxisDeg[\s\S]*0\.21[\s\S]*n under 300, not counted/);
  });

  it("drift: the awaiting count in the status line equals the awaiting cells on the page", () => {
    renderLive();
    const drift = section("Drift");
    // The desktop Reads column (last td) carries one note per row; the phone copy is a duplicate.
    const awaiting = [
      ...drift.querySelectorAll("tbody tr td:last-child"),
    ].filter((td) => td.textContent === "awaiting first value").length;
    expect(awaiting).toBeGreaterThan(0);
    // Three clauses max: with no organic over, the order is D1, D2, D4.
    expect(statusOf("Drift")).toContain(`${awaiting} cells awaiting values`);
  });

  it("retraining: awaiting only while the produced version is a candidate; promoted says so", () => {
    renderLive();
    const t = textOf(section("Retraining"));
    expect(t).toContain("pitch_type_pre (v9)");
    expect(t).toContain("Awaiting promotion");
    expect(t).toContain("Promoted");
    expect(statusOf("Retraining")).toBe("1 running, 1 waiting on a person");
  });

  it("event log: 12-hour times and the status line's deploy time appears in the log", () => {
    renderLive();
    const t = textOf(section("Event log"));
    expect(t).toContain("3:00 AM");
    expect(t).toContain("Yesterday, 11:40 AM");
    expect(t).toContain("Oct 8, 6:10 PM");
    expect(statusOf("Event log")).toBe(
      "0 alerts in 24 h; last deploy yesterday, 11:40 AM",
    );
  });

  it("stamps print the query's own read time, not the render time", () => {
    renderLive();
    const stamp =
      section("Serving").querySelector(".op-src")?.textContent ?? "";
    expect(stamp).toBe(
      `Live. registry x routing x latency, read ${readTime(READ_AT)}`,
    );
    expect(readTime(READ_AT)).toBe("7:41 PM ET");
  });

  it("infrastructure asserts no service state", () => {
    renderLive();
    const t = textOf(section("Infrastructure"));
    expect(t).not.toMatch(/\b(UP|DOWN|DEGRADED)\b/);
    expect(t).toContain("ClickHouseDown");
    expect(t).toContain("Uptime Robot");
  });

  it("the dateline counts the answered sections", () => {
    renderLive();
    expect(document.querySelector(".ed-dateline")?.textContent).toContain(
      "4 of 5 sections live, 1 watched elsewhere",
    );
  });
});

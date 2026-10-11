import { describe, expect, it } from "vitest";

import type {
  DriftCell,
  DriftGroup,
  FleetRow,
  LatencyRow,
  OpsLogEntry,
  RetrainEntry,
} from "../api/ops-rows";

import {
  BANNED_WORDS,
  driftStatus,
  logStatus,
  retrainStatus,
  sectionsAnsweredLine,
  servingStatus,
  sourceState,
  type SourceState,
} from "./ops-status";

const T = { psiNotice: 0.25, psiMinSample: 300, ecePage: 0.1 };
const NOW = new Date("2026-10-11T23:42:00Z");

const fleet = (...stages: FleetRow["stage"][]): FleetRow[] =>
  stages.map((stage, i) => ({
    modelName: `m${i}`,
    version: "v1",
    stage,
    traffic: "100%",
    calls24h: 10,
    p99Ms: 1,
    trained: null,
  }));

const lat = (...p99: number[]): LatencyRow[] =>
  p99.map((p, i) => ({
    label: `m${i} v1`,
    calls: 1,
    p50: 0.1,
    p95: 0.2,
    p99: p,
    p999: 9,
  }));

const cell = (
  value: number | null,
  sampleSize: number | null = 1000,
  tag: string | null = null,
  key = "f",
): DriftCell => ({
  key,
  modelName: "pitch_outcome_post",
  value,
  sampleSize,
  tag,
});

const group = (...cells: DriftCell[]): DriftGroup[] => [
  { modelName: "pitch_outcome_post", cells },
];

describe("sourceState", () => {
  const empty = (d: number[]) => d.length === 0;
  it("is loading before data, error when a read failed with nothing to show", () => {
    expect(sourceState({ data: undefined, isError: false }, empty)).toBe(
      "loading",
    );
    expect(sourceState({ data: undefined, isError: true }, empty)).toBe(
      "error",
    );
  });
  it("is stale when a refresh failed but earlier data is kept, never error", () => {
    expect(sourceState({ data: [1], isError: true }, empty)).toBe("stale");
  });
  it("tells a resolved empty answer apart from a live one", () => {
    expect(sourceState({ data: [], isError: false }, empty)).toBe("empty");
    expect(sourceState({ data: [1], isError: false }, empty)).toBe("live");
  });
});

describe("servingStatus", () => {
  it("S-err / S0 / loading", () => {
    expect(servingStatus("error", undefined, undefined)).toBe(
      "Roster only; no figures",
    );
    expect(servingStatus("empty", [], undefined)).toBe(
      "The registry returned no models",
    );
    expect(servingStatus("loading", undefined, undefined)).toBe("Reading.");
  });
  it("S1 counts with correct plurals and drops zero parts, champions always shown", () => {
    expect(servingStatus("live", fleet("Champion"), undefined)).toBe(
      "1 champion",
    );
    expect(
      servingStatus(
        "live",
        fleet("Champion", "Champion", "Shadow", "Shadow", "Candidate"),
        undefined,
      ),
    ).toBe("2 champions, 2 shadows, 1 candidate");
    expect(servingStatus("live", fleet("Shadow"), undefined)).toBe(
      "0 champions, 1 shadow",
    );
  });
  it("S2 appends the slowest 7-day p99, printed as the table prints it", () => {
    expect(servingStatus("live", fleet("Champion"), lat(2.2, 5.1, 0.4))).toBe(
      "1 champion; slowest p99 5.1 ms",
    );
    expect(servingStatus("live", fleet("Champion"), [])).toBe("1 champion");
  });
  it("marks stale data so it is never read as current", () => {
    expect(servingStatus("stale", fleet("Champion"), undefined)).toBe(
      "1 champion (stale)",
    );
  });
});

describe("driftStatus", () => {
  it("D-err and D0", () => {
    expect(driftStatus("error", group(cell(0.3)), [], T)).toBe(
      "Watched surface only",
    );
    expect(
      driftStatus("empty", group(cell(null), cell(null)), [cell(null)], T),
    ).toBe("Watching 2 features; no values yet");
  });

  it("D1 boundary: 0.25 is not over, 0.2501 is (strictly greater, as the evaluator)", () => {
    expect(driftStatus("live", group(cell(0.25)), [], T)).toBe(
      "Nothing organic over a line",
    );
    expect(driftStatus("live", group(cell(0.2501)), [], T)).toBe(
      "1 feature over the notice line",
    );
    expect(driftStatus("live", group(cell(0.4), cell(0.3)), [], T)).toBe(
      "2 features over the notice line",
    );
  });

  it("D1 sample floor: n 299 is not counted, n 300 is", () => {
    expect(driftStatus("live", group(cell(0.4, 299)), [], T)).toBe(
      "No countable values yet",
    );
    expect(driftStatus("live", group(cell(0.4, 300)), [], T)).toBe(
      "1 feature over the notice line",
    );
  });

  it("D2 counts drill-tagged rows separately and never as organic", () => {
    expect(
      driftStatus("live", group(cell(0.31, 41200, "e2-induced-drill")), [], T),
    ).toBe("Nothing organic over a line; 1 drill row over, labelled");
  });

  it("D3 ECE boundary: 0.10 is not over, 0.1001 is", () => {
    const ece = (v: number) => [
      { ...cell(v, 6112), modelName: "pitch_outcome_pre", key: "all" },
    ];
    expect(driftStatus("live", group(cell(0.05)), ece(0.1), T)).toBe(
      "Nothing organic over a line",
    );
    expect(driftStatus("live", group(cell(0.05)), ece(0.1001), T)).toBe(
      "Nothing organic over a line; pitch_outcome_pre calibration over 0.10",
    );
  });

  it("D4 counts awaiting cells and is the first clause dropped past three", () => {
    expect(driftStatus("live", group(cell(0.05), cell(null)), [], T)).toBe(
      "Nothing organic over a line; 1 cell awaiting values",
    );
    const ece = [
      { ...cell(0.2, 100), modelName: "pitch_type_pre", key: "all" },
    ];
    expect(
      driftStatus(
        "live",
        group(cell(0.4), cell(0.31, 41200, "drill"), cell(null)),
        ece,
        T,
      ),
    ).toBe(
      "1 feature over the notice line; 1 drill row over, labelled; pitch_type_pre calibration over 0.10",
    );
  });
});

describe("retrainStatus", () => {
  const e = (status: RetrainEntry["status"]): RetrainEntry => ({
    id: status,
    trigger: "Scheduled",
    modelLabel: "m",
    reason: "r",
    queuedAt: "",
    ranAt: "",
    status,
  });
  it("R-err / R0", () => {
    expect(retrainStatus("error", undefined)).toBe("Could not be read");
    expect(retrainStatus("empty", [])).toBe("Queue empty");
  });
  it("R1 drops zero parts; 'waiting on a person' is only Awaiting promotion", () => {
    expect(
      retrainStatus("live", [
        e("Running"),
        e("Awaiting promotion"),
        e("Promoted"),
      ]),
    ).toBe("1 running, 1 waiting on a person");
    expect(retrainStatus("live", [e("Queued"), e("Queued")])).toBe("2 queued");
    expect(retrainStatus("live", [e("Promoted"), e("Done")])).toBe(
      "Nothing running or waiting",
    );
  });
});

describe("logStatus", () => {
  const row = (
    type: string,
    occurredAt: string,
    when = "3:00 AM",
  ): OpsLogEntry => ({
    id: occurredAt + type,
    when,
    occurredAt,
    type,
    label: type,
    detail: "",
  });
  it("L-err / L0", () => {
    expect(logStatus("error", undefined, NOW)).toBe("Could not be read");
    expect(logStatus("empty", { rows: [], hasNext: false }, NOW)).toBe(
      "No events recorded yet",
    );
  });
  it("L1 counts alerts inside 24 h from the raw instant; 0 is a count, not a verdict", () => {
    const page = {
      rows: [
        row("ALERT", "2026-10-11T07:00:00Z"),
        row("ALERT", "2026-10-10T23:41:00Z"), // 24 h + 1 min ago: outside
      ],
      hasNext: false,
    };
    expect(logStatus("live", page, NOW)).toBe("1 alert in 24 h");
    expect(
      logStatus(
        "live",
        { rows: [row("DRIFT_OK", "2026-10-11T07:00:00Z")], hasNext: false },
        NOW,
      ),
    ).toBe("0 alerts in 24 h");
  });
  it("L1 says 'At least' only when the page is full AND its oldest row is still inside 24 h", () => {
    const inside = {
      rows: [row("ALERT", "2026-10-11T07:00:00Z")],
      hasNext: true,
    };
    expect(logStatus("live", inside, NOW)).toBe("At least 1 alert in 24 h");
    const reachesPast = {
      rows: [
        row("ALERT", "2026-10-11T07:00:00Z"),
        row("DEPLOY", "2026-10-08T22:10:00Z", "Oct 8, 6:10 PM"),
      ],
      hasNext: true,
    };
    expect(logStatus("live", reachesPast, NOW)).toBe(
      "1 alert in 24 h; last deploy Oct 8, 6:10 PM",
    );
  });
  it("L2 names the newest deploy, lower-casing 'Yesterday' mid-sentence, and is dropped when absent", () => {
    const page = {
      rows: [
        row("DEPLOY", "2026-10-10T15:40:00Z", "Yesterday, 11:40 AM"),
        row("DEPLOY", "2026-10-08T22:10:00Z", "Oct 8, 6:10 PM"),
      ],
      hasNext: false,
    };
    expect(logStatus("live", page, NOW)).toBe(
      "0 alerts in 24 h; last deploy yesterday, 11:40 AM",
    );
  });
});

describe("sectionsAnsweredLine", () => {
  it("names the static section when all four live sections answered", () => {
    expect(sectionsAnsweredLine(["live", "empty", "stale", "live"])).toBe(
      "4 of 5 sections live, 1 watched elsewhere",
    );
  });
  it("counts answers when some failed", () => {
    expect(sectionsAnsweredLine(["live", "error", "live", "error"])).toBe(
      "2 of 4 live sections answered",
    );
    expect(sectionsAnsweredLine(["error", "error", "error", "error"])).toBe(
      "0 of 4 live sections answered",
    );
  });
  it("does not count while anything is still loading", () => {
    expect(sectionsAnsweredLine(["live", "loading", "live", "live"])).toBe(
      "Reading the live sections",
    );
  });
});

describe("no evaluative words in any generated line", () => {
  it("holds across a matrix of states and inputs", () => {
    const states: SourceState[] = [
      "loading",
      "live",
      "empty",
      "error",
      "stale",
    ];
    const lines: string[] = [];
    for (const s of states) {
      lines.push(servingStatus(s, fleet("Champion", "Shadow"), lat(1.2)));
      for (const v of [null, 0.01, 0.25, 0.3]) {
        for (const n of [10, 300]) {
          for (const tag of [null, "drill"]) {
            lines.push(driftStatus(s, group(cell(v, n, tag)), [cell(v, n)], T));
          }
        }
      }
      lines.push(retrainStatus(s, []));
      lines.push(
        logStatus(s, { rows: [], hasNext: false }, NOW),
        sectionsAnsweredLine([s, s, s, s]),
      );
    }
    for (const line of lines) {
      for (const word of BANNED_WORDS) {
        expect(line.toLowerCase(), line).not.toContain(word);
      }
      expect(line).not.toContain("—");
    }
  });
});

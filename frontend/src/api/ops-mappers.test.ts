import { describe, expect, it } from "vitest";

import type {
  DriftMetric,
  LatencyStat,
  ModelVersion,
  OpsEvent,
  RetrainingTrigger,
} from "./ops";
import {
  fmtWhen,
  opsEventToLogEntry,
  toDriftCells,
  toFleetRows,
  toLatencyRows,
  toRetrainEntries,
} from "./ops-mappers";

/** 7:42 PM ET on Sunday, October 11, 2026. */
const NOW = new Date("2026-10-11T23:42:00Z");

function mv(
  over: Partial<ModelVersion> & Pick<ModelVersion, "id">,
): ModelVersion {
  return {
    modelName: "pitch_outcome_pre",
    version: "v2",
    artifactPath: "",
    metadataPath: "",
    trainingDataHash: "",
    trainingDataWindow: "",
    featureSchemaHash: "",
    evalMetrics: "",
    trainedAt: "2026-07-22T12:00:00Z",
    promotedAt: null,
    stage: "CHAMPION",
    createdBy: null,
    notes: null,
    createdAt: "2026-07-22T12:00:00Z",
    updatedAt: "2026-07-22T12:00:00Z",
    ...over,
  };
}

function lat(over: Partial<LatencyStat>): LatencyStat {
  return {
    modelName: "pitch_outcome_pre",
    modelVersion: "v2",
    sampleCount: 1000,
    p50Ms: 0.44,
    p95Ms: 0.91,
    p99Ms: 1.37,
    p999Ms: 2.1,
    ...over,
  };
}

describe("fmtWhen (12-hour ET, relative day)", () => {
  it("prints the clock alone for today", () => {
    expect(fmtWhen("2026-10-11T07:00:00Z", NOW)).toBe("3:00 AM");
  });
  it("prefixes Yesterday across the ET day boundary", () => {
    expect(fmtWhen("2026-10-10T15:40:00Z", NOW)).toBe("Yesterday, 11:40 AM");
  });
  it("prints month and day beyond yesterday", () => {
    expect(fmtWhen("2026-10-08T22:10:00Z", NOW)).toBe("Oct 8, 6:10 PM");
  });
  it("uses the ET day, not UTC: 1 AM UTC on the 12th is still the 11th in ET", () => {
    expect(
      fmtWhen("2026-10-12T01:00:00Z", new Date("2026-10-12T02:00:00Z")),
    ).toBe("9:00 PM");
  });
  it("renders an em-dash for missing or unparseable input", () => {
    expect(fmtWhen(null, NOW)).toBe("—");
    expect(fmtWhen("not a date", NOW)).toBe("—");
  });
});

describe("toFleetRows", () => {
  it("uses the registry's own stage words, never LIVE/SHADOW", () => {
    const rows = toFleetRows(
      [
        mv({ id: 1 }),
        mv({ id: 2, version: "v3", stage: "CANDIDATE" }),
        mv({ id: 3, modelName: "pitch_outcome_lr_baseline", stage: "SHADOW" }),
      ],
      [],
      [],
    );
    expect(rows.map((r) => r.stage)).toEqual([
      "Champion",
      "Candidate",
      "Shadow",
    ]);
    expect(JSON.stringify(rows)).not.toMatch(/LIVE|AWAITING/);
  });

  it("joins 24 h latency by name AND version and rounds p99 to one decimal", () => {
    const [row] = toFleetRows([mv({ id: 1 })], [], [lat({})]);
    expect(row).toMatchObject({ calls24h: 1000, p99Ms: 1.4, traffic: "100%" });
    expect(row?.trained).toBe("Jul 22");
    const [other] = toFleetRows(
      [mv({ id: 1 })],
      [],
      [lat({ modelVersion: "v1" })],
    );
    expect(other).toMatchObject({ calls24h: null, p99Ms: null });
  });

  it("says 'none' for traffic a version never serves, and splits A/B traffic from routing", () => {
    const rows = toFleetRows(
      [mv({ id: 1 }), mv({ id: 2, version: "v3", stage: "SHADOW" })],
      [
        {
          id: 1,
          modelName: "pitch_outcome_pre",
          championVersionId: 1,
          challengerVersionId: 2,
          challengerTrafficPct: 10,
          mode: "AB",
          updatedAt: "",
        },
      ],
      [],
    );
    expect(rows.map((r) => r.traffic)).toEqual(["90%", "10%"]);
    const shadowOnly = toFleetRows([mv({ id: 2, stage: "SHADOW" })], [], []);
    expect(shadowOnly[0]?.traffic).toBe("none");
  });

  it("filters ARCHIVED and orders champions, then candidates, then shadows", () => {
    const rows = toFleetRows(
      [
        mv({ id: 9, modelName: "z_model", stage: "SHADOW" }),
        mv({ id: 8, stage: "ARCHIVED" }),
        mv({ id: 7, modelName: "battedball_outcome", stage: "CANDIDATE" }),
        mv({ id: 6, modelName: "battedball_outcome" }),
      ],
      [],
      [],
    );
    expect(rows.map((r) => `${r.modelName} ${r.stage}`)).toEqual([
      "battedball_outcome Champion",
      "battedball_outcome Candidate",
      "z_model Shadow",
    ]);
  });
});

describe("toLatencyRows", () => {
  it("labels each row and rounds to one decimal", () => {
    expect(toLatencyRows([lat({})])).toEqual([
      {
        label: "pitch_outcome_pre v2",
        calls: 1000,
        p50: 0.4,
        p95: 0.9,
        p99: 1.4,
        p999: 2.1,
      },
    ]);
  });
});

describe("toDriftCells", () => {
  const WATCHED = { pitch_outcome_post: ["releaseSpeedMph", "plateXIn"] };
  const ECE = ["pitch_outcome_pre", "pitch_outcome_post"];

  function dm(over: Partial<DriftMetric>): DriftMetric {
    return {
      computedAt: "2026-10-11T06:00:00Z",
      modelName: "pitch_outcome_post",
      modelVersionId: 5,
      metricType: "psi_feature",
      featureOrSegment: "releaseSpeedMph",
      metricValue: 0.06,
      sampleSize: 41200,
      windowStart: "",
      windowEnd: "",
      tag: "",
      ...over,
    };
  }

  it("renders the watched surface with null cells when drift_metrics is empty", () => {
    const { psi, ece, drillTags } = toDriftCells([], WATCHED, ECE);
    expect(psi).toEqual([
      {
        modelName: "pitch_outcome_post",
        cells: [
          {
            key: "releaseSpeedMph",
            modelName: "pitch_outcome_post",
            value: null,
            sampleSize: null,
            tag: null,
          },
          {
            key: "plateXIn",
            modelName: "pitch_outcome_post",
            value: null,
            sampleSize: null,
            tag: null,
          },
        ],
      },
    ]);
    expect(ece.map((c) => [c.modelName, c.value])).toEqual([
      ["pitch_outcome_pre", null],
      ["pitch_outcome_post", null],
    ]);
    expect(drillTags).toEqual([]);
  });

  it("keeps the newest value per cell and carries sampleSize", () => {
    const { psi } = toDriftCells(
      [
        dm({ metricValue: 0.03, computedAt: "2026-10-09T06:00:00Z" }),
        dm({
          metricValue: 0.06,
          sampleSize: 180,
          computedAt: "2026-10-11T06:00:00Z",
        }),
        dm({ metricValue: 0.04, computedAt: "2026-10-10T06:00:00Z" }),
      ],
      WATCHED,
      ECE,
    );
    expect(psi[0]?.cells[0]).toMatchObject({ value: 0.06, sampleSize: 180 });
  });

  it("appends live keys and models the watch list does not carry, sorted, after the canonical rows", () => {
    const { psi } = toDriftCells(
      [
        dm({ featureOrSegment: "stand" }),
        dm({ featureOrSegment: "baseState" }),
        dm({ modelName: "pitch_type_pre", featureOrSegment: "balls" }),
      ],
      WATCHED,
      ECE,
    );
    expect(psi.map((g) => g.modelName)).toEqual([
      "pitch_outcome_post",
      "pitch_type_pre",
    ]);
    expect(psi[0]?.cells.map((c) => c.key)).toEqual([
      "releaseSpeedMph",
      "plateXIn",
      "baseState",
      "stand",
    ]);
  });

  it("routes CALIBRATION_ERROR at segment 'all' to ece and leaves other metrics off the page", () => {
    const { psi, ece } = toDriftCells(
      [
        dm({
          metricType: "calibration_error",
          featureOrSegment: "all",
          metricValue: 0.013,
          sampleSize: 6112,
        }),
        dm({
          metricType: "calibration_error",
          featureOrSegment: "count_0_0",
          metricValue: 0.9,
        }),
        dm({
          metricType: "psi_prediction",
          featureOrSegment: "releaseSpeedMph",
          metricValue: 0.9,
        }),
        dm({ metricType: "brier", featureOrSegment: "all", metricValue: 0.2 }),
      ],
      WATCHED,
      ECE,
    );
    expect(ece.find((c) => c.modelName === "pitch_outcome_post")).toMatchObject(
      {
        value: 0.013,
        sampleSize: 6112,
      },
    );
    // The PSI_PREDICTION row must not land in the feature table (owner default: off the page).
    expect(psi[0]?.cells[0]?.value).toBeNull();
  });

  it("carries the V027 drill tag on its cell and lists the distinct tags", () => {
    const { psi, drillTags } = toDriftCells(
      [
        dm({
          featureOrSegment: "plateXIn",
          metricValue: 0.31,
          tag: "e2-induced-drill",
        }),
        dm({ featureOrSegment: "releaseSpeedMph", tag: "" }),
      ],
      WATCHED,
      ECE,
    );
    expect(psi[0]?.cells.map((c) => c.tag)).toEqual([null, "e2-induced-drill"]);
    expect(drillTags).toEqual(["e2-induced-drill"]);
  });
});

describe("toRetrainEntries", () => {
  function trig(over: Partial<RetrainingTrigger>): RetrainingTrigger {
    return {
      id: 1,
      triggerId: "t-1",
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
    };
  }

  it("is 'Awaiting promotion' only while the produced version is still a CANDIDATE", () => {
    const [e] = toRetrainEntries(
      [trig({})],
      [mv({ id: 12, modelName: "pitch_type_pre", stage: "CANDIDATE" })],
      NOW,
    );
    expect(e).toMatchObject({
      status: "Awaiting promotion",
      modelLabel: "pitch_type_pre (v2)",
      trigger: "Scheduled",
      ranAt: "2:52 AM, done",
      queuedAt: "Yesterday, 2:00 AM",
    });
  });

  it("says 'Promoted' once a person promoted the produced version (the old mapper said awaiting forever)", () => {
    const [e] = toRetrainEntries(
      [trig({})],
      [mv({ id: 12, modelName: "pitch_type_pre", stage: "CHAMPION" })],
      NOW,
    );
    expect(e?.status).toBe("Promoted");
  });

  it("says 'Done' for a finished job with no produced version, or one archived or unknown", () => {
    const out = toRetrainEntries(
      [
        trig({ triggerId: "a", producedVersionId: null }),
        trig({ triggerId: "b", producedVersionId: 12 }),
        trig({ triggerId: "c", producedVersionId: 99 }),
      ],
      [mv({ id: 12, stage: "ARCHIVED" })],
      NOW,
    );
    expect(out.map((e) => e.status)).toEqual(["Done", "Done", "Done"]);
    expect(out[2]?.modelLabel).toBe("pitch_type_pre (id 99)");
  });

  it("maps queued/running, reads the metadata reason, and drops FAILED/CANCELLED", () => {
    const out = toRetrainEntries(
      [
        trig({
          triggerId: "q",
          status: "QUEUED",
          triggerType: "DRIFT",
          startedAt: null,
          finishedAt: null,
          producedVersionId: null,
          triggerMetadata: '{"reason":"PSI plateXIn over 0.25"}',
        }),
        trig({
          triggerId: "r",
          status: "RUNNING",
          triggerType: "MANUAL",
          finishedAt: null,
          producedVersionId: null,
        }),
        trig({ triggerId: "f", status: "FAILED" }),
        trig({ triggerId: "x", status: "CANCELLED" }),
      ],
      [],
      NOW,
    );
    expect(out.map((e) => [e.id, e.status, e.trigger])).toEqual([
      ["q", "Queued", "Drift"],
      ["r", "Running", "Manual"],
    ]);
    expect(out[0]?.reason).toBe("PSI plateXIn over 0.25");
    expect(out[0]?.ranAt).toBe("—");
    expect(out[1]?.reason).toBeNull();
  });
});

describe("opsEventToLogEntry", () => {
  const ev = (over: Partial<OpsEvent>): OpsEvent => ({
    id: 7,
    occurredAt: "2026-10-11T07:00:00Z",
    type: "DRIFT_OK",
    detail: "Nightly sweep.",
    ...over,
  });

  it("keeps the raw instant and prints a sentence-case label and 12-hour time", () => {
    expect(opsEventToLogEntry(ev({}), NOW)).toEqual({
      id: "oe-7",
      when: "3:00 AM",
      occurredAt: "2026-10-11T07:00:00Z",
      type: "DRIFT_OK",
      label: "Drift ok",
      detail: "Nightly sweep.",
    });
  });

  it("labels every backend event type, and keeps an unknown type's own words", () => {
    const labels = [
      "DEPLOY",
      "REGISTER",
      "PROMOTE",
      "ALERT",
      "RETRAIN_OK",
      "RESTORE_DRILL",
      "NEW_THING",
    ].map((type) => opsEventToLogEntry(ev({ type }), NOW).label);
    expect(labels).toEqual([
      "Deploy",
      "Register",
      "Promote",
      "Alert",
      "Retrain ok",
      "Restore drill",
      "New thing",
    ]);
  });
});

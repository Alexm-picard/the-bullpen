import { describe, expect, it } from "vitest";

import type {
  LatencyStat,
  ModelVersion,
  RetrainingTrigger,
  RoutingConfig,
} from "./ops";
import { toFleetRows, toLatencyRows, toRetrainEntries } from "./ops-mappers";

function mv(
  over: Partial<ModelVersion> & Pick<ModelVersion, "id">,
): ModelVersion {
  return {
    id: over.id,
    modelName: "pitch_outcome_pre",
    version: "v3",
    artifactPath: "",
    metadataPath: "",
    trainingDataHash: "",
    trainingDataWindow: "",
    featureSchemaHash: "",
    evalMetrics: "",
    trainedAt: "2026-04-12T00:00:00Z",
    promotedAt: null,
    stage: "CHAMPION",
    createdBy: null,
    notes: null,
    createdAt: "2026-04-12T00:00:00Z",
    updatedAt: "2026-04-12T00:00:00Z",
    ...over,
  };
}

function lat(over: Partial<LatencyStat>): LatencyStat {
  return {
    modelName: "pitch_outcome_pre",
    modelVersion: "v3",
    sampleCount: 1000,
    p50Ms: 0.4,
    p95Ms: 0.9,
    p99Ms: 1.37,
    p999Ms: 2.1,
    ...over,
  };
}

describe("toFleetRows", () => {
  it("maps a champion to LIVE @ 100% and joins its latency", () => {
    const rows = toFleetRows([mv({ id: 1 })], [], [lat({})]);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({
      modelName: "pitch_outcome_pre",
      version: "v3",
      state: "LIVE",
      traffic: "100%",
      predictions24h: 1000,
      p99Ms: 1.37,
      // drift columns are honest-null until the in-season jobs run
      psiMax: null,
      eceDelta: null,
      lastRegistered: "2026-04-12",
    });
  });

  it("renders null p99/predictions (→ em-dash) for a model with no served latency", () => {
    const rows = toFleetRows([mv({ id: 1, modelName: "batted_ball" })], [], []);
    expect(rows[0]?.p99Ms).toBeNull();
    expect(rows[0]?.predictions24h).toBeNull();
  });

  it("derives challenger traffic from the routing row", () => {
    const champion = mv({ id: 10, version: "v3" });
    const challenger = mv({ id: 11, version: "v4", stage: "SHADOW" });
    const routing: RoutingConfig = {
      id: 1,
      modelName: "pitch_outcome_pre",
      championVersionId: 10,
      challengerVersionId: 11,
      challengerTrafficPct: 25,
      mode: "AB",
      updatedAt: "2026-05-01T00:00:00Z",
    };
    const rows = toFleetRows([champion, challenger], [routing], []);
    expect(rows.find((r) => r.version === "v3")?.traffic).toBe("75%");
    expect(rows.find((r) => r.version === "v4")?.traffic).toBe("25%");
    expect(rows.find((r) => r.version === "v4")?.state).toBe("SHADOW");
  });

  it("filters out ARCHIVED versions", () => {
    const rows = toFleetRows(
      [mv({ id: 1, stage: "ARCHIVED" }), mv({ id: 2, version: "v4" })],
      [],
      [],
    );
    expect(rows).toHaveLength(1);
    expect(rows[0]?.version).toBe("v4");
  });
});

describe("toLatencyRows", () => {
  it("maps each latency stat to a labelled percentile row", () => {
    const rows = toLatencyRows([lat({ modelVersion: "v3" })]);
    expect(rows[0]).toEqual({
      label: "pitch_outcome_pre v3",
      p50: 0.4,
      p95: 0.9,
      p99: 1.37,
      p999: 2.1,
    });
  });
});

describe("toRetrainEntries", () => {
  function trig(over: Partial<RetrainingTrigger>): RetrainingTrigger {
    return {
      id: 1,
      triggerId: "trig-1",
      modelName: "pitch_outcome_pre",
      triggerType: "DRIFT",
      triggerMetadata: null,
      status: "QUEUED",
      enqueuedAt: "2026-05-30T15:08:00Z",
      startedAt: null,
      finishedAt: null,
      producedVersionId: null,
      errorMessage: null,
      ...over,
    };
  }

  it("maps backend trigger/status enums to the display labels", () => {
    const [e] = toRetrainEntries([
      trig({ triggerType: "SCHEDULED", status: "RUNNING" }),
    ]);
    expect(e?.trigger).toBe("SCHEDULE");
    expect(e?.status).toBe("RUNNING");
  });

  it("maps SUCCEEDED to AWAITING-PROMOTION and reads the metadata reason", () => {
    const [e] = toRetrainEntries([
      trig({
        status: "SUCCEEDED",
        triggerMetadata: '{"reason":"PSI release_spin 0.22"}',
        producedVersionId: 42,
      }),
    ]);
    expect(e?.status).toBe("AWAITING-PROMOTION");
    expect(e?.reason).toBe("PSI release_spin 0.22");
    expect(e?.modelLabel).toContain("v42 candidate");
  });

  it("drops FAILED / CANCELLED rows the queue list doesn't render", () => {
    expect(toRetrainEntries([trig({ status: "FAILED" })])).toHaveLength(0);
    expect(toRetrainEntries([trig({ status: "CANCELLED" })])).toHaveLength(0);
  });
});

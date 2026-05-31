import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  fetchAllModelNames,
  fetchCalibrationSummary,
  fetchDrift,
  fetchOpsEvents,
  fetchRegistryRows,
  fetchRetrainQueue,
  fetchRouting,
  opsEventToLogEntry,
  OpsApiError,
} from "./ops";

describe("ops API client", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("fetchAllModelNames hits /v1/ops/registry", async () => {
    (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => ["pitch_outcome_pre", "_toy_batted_ball"],
    });
    expect(await fetchAllModelNames()).toEqual([
      "pitch_outcome_pre",
      "_toy_batted_ball",
    ]);
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining("/v1/ops/registry"),
    );
  });

  it("fetchRegistryRows urlencodes the model name", async () => {
    (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => [],
    });
    await fetchRegistryRows("_toy_batted_ball");
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining("/v1/ops/registry/_toy_batted_ball"),
    );
  });

  it("fetchDrift passes the model param", async () => {
    (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => [],
    });
    await fetchDrift("pitch_outcome_pre");
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining("/v1/ops/drift?model=pitch_outcome_pre"),
    );
  });

  it("fetchRouting hits /v1/ops/routing", async () => {
    (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => [],
    });
    await fetchRouting();
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining("/v1/ops/routing"),
    );
  });

  it("fetchRetrainQueue without model name hits /v1/ops/retrain bare", async () => {
    (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => [],
    });
    await fetchRetrainQueue();
    expect(fetch).toHaveBeenCalledWith(
      expect.stringMatching(/\/v1\/ops\/retrain$/),
    );
  });

  it("fetchRetrainQueue with model name adds the query param", async () => {
    (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => [],
    });
    await fetchRetrainQueue("pitch_outcome_pre");
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining("/v1/ops/retrain?model=pitch_outcome_pre"),
    );
  });

  it("fetchCalibrationSummary hits the calibration-summary path", async () => {
    (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => ({}),
    });
    await fetchCalibrationSummary();
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining("/v1/ops/calibration-summary"),
    );
  });

  it("fetchOpsEvents passes the limit param", async () => {
    (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => [],
    });
    await fetchOpsEvents(5);
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining("/v1/ops/events?limit=5"),
    );
  });

  it("opsEventToLogEntry maps backend type names to hyphenated display labels", () => {
    const entry = opsEventToLogEntry({
      id: 7,
      occurredAt: "2026-05-30T19:00:00Z",
      type: "DRIFT_OK",
      detail: "pitch_outcome_pre nightly sweep — PSI max 0.07",
    });
    expect(entry.id).toBe("oe-7");
    expect(entry.type).toBe("DRIFT-OK");
    expect(entry.detail).toContain("nightly sweep");
    expect(entry.timestamp).toContain("ET");
  });

  it("throws OpsApiError on non-200", async () => {
    (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: false,
      status: 500,
      json: async () => null,
    });
    const err = await fetchAllModelNames().catch((e) => e);
    expect(err).toBeInstanceOf(OpsApiError);
    expect(err.status).toBe(500);
  });
});

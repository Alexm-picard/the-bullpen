import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  CANONICAL_BBE_INPUT,
  ParksApiError,
  predictAllParks,
  type AllParksResponse,
} from "./parks";

const RESPONSE: AllParksResponse = {
  probHrByPark: { NYY: 0.42, COL: 0.55, BOS: 0.38 },
  modelName: "_toy_batted_ball",
  modelVersion: "v0",
  latencyMicros: 312,
  correlationId: "cid-1",
};

describe("predictAllParks", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("POSTs JSON to /v1/predict/batted-ball/all-parks", async () => {
    (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => RESPONSE,
    });

    expect(await predictAllParks(CANONICAL_BBE_INPUT)).toEqual(RESPONSE);
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining("/v1/predict/batted-ball/all-parks"),
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "Content-Type": "application/json",
        }),
      }),
    );
  });

  it("throws ParksApiError on non-200", async () => {
    (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: false,
      status: 500,
      json: async () => null,
    });
    const err = await predictAllParks(CANONICAL_BBE_INPUT).catch((e) => e);
    expect(err).toBeInstanceOf(ParksApiError);
    expect(err.status).toBe(500);
  });

  it("exports canonical input matching the backend AllParksOutcomeRequest", () => {
    expect(CANONICAL_BBE_INPUT).toEqual({
      launchSpeedMph: 110,
      launchAngleDeg: 28,
      sprayAngleDeg: 0,
      hitDistanceFt: 400,
      stand: "R",
      baseState: 0,
      outs: 0,
    });
  });
});

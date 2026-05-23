import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { PredictError, predictBattedBall, type ApiErrorBody } from "./predict";

const REQ = {
  launchSpeedMph: 105,
  launchAngleDeg: 28,
  releaseSpeedMph: 94,
  parkId: "NYY",
  stand: "R" as const,
};

describe("predictBattedBall", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("returns the prediction body on 200", async () => {
    const responseBody = {
      probHr: 0.87,
      modelName: "_toy_batted_ball",
      modelVersion: "v0",
      latencyMicros: 4321,
      correlationId: "cid-1",
    };
    (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => responseBody,
    });
    const out = await predictBattedBall(REQ);
    expect(out).toEqual(responseBody);
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining("/v1/predict/batted-ball"),
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "Content-Type": "application/json",
        }),
      }),
    );
  });

  it("throws PredictError with canonical body on 4xx", async () => {
    const apiError: ApiErrorBody = {
      error: {
        code: "validation_failed",
        message: "one or more fields failed validation",
        correlationId: "cid-2",
        details: [{ field: "stand", message: "stand must be 'L' or 'R'" }],
      },
    };
    (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: false,
      status: 400,
      json: async () => apiError,
    });
    await expect(predictBattedBall(REQ)).rejects.toMatchObject({
      code: "validation_failed",
      details: [{ field: "stand" }],
    });
  });

  it("falls back to a generic Error when the server returns no JSON body", async () => {
    (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: false,
      status: 502,
      json: async () => {
        throw new Error("no body");
      },
    });
    await expect(predictBattedBall(REQ)).rejects.toBeInstanceOf(Error);
    await expect(predictBattedBall(REQ)).rejects.not.toBeInstanceOf(
      PredictError,
    );
  });
});

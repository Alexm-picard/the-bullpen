/**
 * Contract test for the player-lookup API client. Mirrors `predict.test.ts`'s
 * stubGlobal(fetch) pattern.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  PlayerLookupError,
  getPlayer,
  getPlayerCalibration,
  getPlayerPredictions,
  searchPlayers,
  type CalibrationBin,
  type PlayerPredictionRow,
  type PlayerSearchResult,
} from "./players";

const ROWS: PlayerSearchResult[] = [
  { id: 660271, name: "Aaron Judge", primaryPosition: "RF", active: true },
  { id: 660272, name: "Other Judge", primaryPosition: "C", active: false },
];

describe("searchPlayers", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("returns an empty array for blank q without calling fetch", async () => {
    expect(await searchPlayers("")).toEqual([]);
    expect(await searchPlayers("   ")).toEqual([]);
    expect(fetch).not.toHaveBeenCalled();
  });

  it("urlencodes q and forwards limit", async () => {
    (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => ROWS,
    });

    const result = await searchPlayers("Aaron Judge", 5);
    expect(result).toEqual(ROWS);
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining("/v1/players/search?q=Aaron%20Judge&limit=5"),
    );
  });

  it("trims q before sending", async () => {
    (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => ROWS,
    });

    await searchPlayers("  judge  ");
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining("q=judge&limit=10"),
    );
  });

  it("throws PlayerLookupError on non-200 status", async () => {
    (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: false,
      status: 500,
      json: async () => null,
    });

    await expect(searchPlayers("judge")).rejects.toBeInstanceOf(
      PlayerLookupError,
    );
  });
});

describe("getPlayer", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("returns the player on 200", async () => {
    (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => ROWS[0],
    });

    expect(await getPlayer(660271)).toEqual(ROWS[0]);
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining("/v1/players/660271"),
    );
  });

  it("throws PlayerLookupError with status 404 on 404", async () => {
    (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: false,
      status: 404,
      json: async () => null,
    });

    const err = await getPlayer(999).catch((e) => e);
    expect(err).toBeInstanceOf(PlayerLookupError);
    expect(err.status).toBe(404);
  });

  it("throws PlayerLookupError on other non-200", async () => {
    (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: false,
      status: 503,
      json: async () => null,
    });

    const err = await getPlayer(660271).catch((e) => e);
    expect(err).toBeInstanceOf(PlayerLookupError);
    expect(err.status).toBe(503);
  });
});

describe("getPlayerPredictions", () => {
  const ROW: PlayerPredictionRow = {
    requestAt: "2026-05-20T18:30:00Z",
    modelName: "pitch_outcome_pre",
    modelVersion: "v3",
    role: "champion",
    winnerClass: "ball",
    winnerProb: 0.42,
    observedOutcome: null,
    agreed: null,
  };

  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("returns rows on 200 with default limit=50", async () => {
    (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => [ROW],
    });

    expect(await getPlayerPredictions(660271)).toEqual([ROW]);
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining("/v1/players/660271/predictions?limit=50"),
    );
  });

  it("forwards a custom limit", async () => {
    (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => [],
    });
    await getPlayerPredictions(660271, 25);
    expect(fetch).toHaveBeenCalledWith(expect.stringContaining("limit=25"));
  });

  it("throws PlayerLookupError(404) on player-not-found", async () => {
    (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: false,
      status: 404,
      json: async () => null,
    });
    const err = await getPlayerPredictions(999).catch((e) => e);
    expect(err).toBeInstanceOf(PlayerLookupError);
    expect(err.status).toBe(404);
  });

  it("throws PlayerLookupError on other non-200", async () => {
    (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: false,
      status: 500,
      json: async () => null,
    });
    const err = await getPlayerPredictions(660271).catch((e) => e);
    expect(err).toBeInstanceOf(PlayerLookupError);
    expect(err.status).toBe(500);
  });
});

describe("getPlayerCalibration", () => {
  const BIN: CalibrationBin = {
    binStart: 0.4,
    binEnd: 0.5,
    predicted: 0.45,
    actual: 0.43,
    n: 250,
  };

  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("returns bins on 200 with the model param urlencoded", async () => {
    (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => [BIN],
    });

    expect(await getPlayerCalibration(660271, "pitch_outcome_pre")).toEqual([
      BIN,
    ]);
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining(
        "/v1/players/660271/calibration?model=pitch_outcome_pre",
      ),
    );
  });

  it("throws PlayerLookupError(404) on player-not-found", async () => {
    (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: false,
      status: 404,
      json: async () => null,
    });
    const err = await getPlayerCalibration(999, "pitch_outcome_pre").catch(
      (e) => e,
    );
    expect(err).toBeInstanceOf(PlayerLookupError);
    expect(err.status).toBe(404);
  });

  it("throws PlayerLookupError on 400 (unknown model)", async () => {
    (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: false,
      status: 400,
      json: async () => null,
    });
    const err = await getPlayerCalibration(660271, "pitch_outcome_pre").catch(
      (e) => e,
    );
    expect(err).toBeInstanceOf(PlayerLookupError);
    expect(err.status).toBe(400);
  });
});

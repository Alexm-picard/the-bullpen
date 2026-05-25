import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  GameApiError,
  fetchGame,
  fetchLivePitchesSince,
  fetchTodaysGames,
  statusPollIntervalMs,
  type GameSummary,
  type LivePitchRow,
} from "./games";

const SUMMARY: GameSummary = {
  gameId: 777001,
  gameDate: "2026-05-25",
  homeTeam: "NYY",
  awayTeam: "BOS",
  homeScore: 3,
  awayScore: 2,
  inning: 7,
  status: "IN_PROGRESS",
  detailedState: "In Progress",
};

const PITCH: LivePitchRow = {
  gameId: 777001,
  atBatIndex: 1,
  pitchNumber: 1,
  cursor: 101,
  ingestedAt: "2026-05-25T18:30:00Z",
  pitcherId: 660271,
  batterId: 545361,
  description: "called_strike",
  pitchType: "FF",
  releaseSpeedMph: 94.3,
  plateXIn: 0.1,
  plateZIn: 2.6,
  balls: 0,
  strikes: 1,
  outs: 0,
  inning: 1,
  homeScore: 0,
  awayScore: 0,
  predictedClasses: null,
  predictedWinner: null,
};

describe("statusPollIntervalMs", () => {
  it("returns 12s for IN_PROGRESS and MID_INNING", () => {
    expect(statusPollIntervalMs("IN_PROGRESS")).toBe(12_000);
    expect(statusPollIntervalMs("MID_INNING")).toBe(12_000);
  });

  it("returns false for terminal states (stops polling)", () => {
    expect(statusPollIntervalMs("POSTPONED")).toBe(false);
    expect(statusPollIntervalMs("COMPLETED")).toBe(false);
  });

  it("returns longer cadence for warmup / delayed / suspended", () => {
    expect(statusPollIntervalMs("WARMUP")).toBe(60_000);
    expect(statusPollIntervalMs("DELAYED")).toBe(120_000);
    expect(statusPollIntervalMs("SUSPENDED")).toBe(600_000);
  });

  it("defaults unknown values to a sane fallback", () => {
    expect(statusPollIntervalMs(undefined)).toBe(300_000);
    expect(statusPollIntervalMs("something_new")).toBe(300_000);
  });
});

describe("fetch helpers", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("fetchTodaysGames hits /v1/games/today and returns the array", async () => {
    (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => [SUMMARY],
    });
    expect(await fetchTodaysGames()).toEqual([SUMMARY]);
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining("/v1/games/today"),
    );
  });

  it("fetchGame hits /v1/games/{id}", async () => {
    (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => SUMMARY,
    });
    expect(await fetchGame(777001)).toEqual(SUMMARY);
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining("/v1/games/777001"),
    );
  });

  it("fetchGame throws GameApiError(404) when not found", async () => {
    (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: false,
      status: 404,
      json: async () => null,
    });
    const err = await fetchGame(999).catch((e) => e);
    expect(err).toBeInstanceOf(GameApiError);
    expect(err.status).toBe(404);
  });

  it("fetchLivePitchesSince forwards the since cursor in the query string", async () => {
    (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => [PITCH],
    });
    expect(await fetchLivePitchesSince(777001, 305)).toEqual([PITCH]);
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining("/v1/games/777001/pitches?since=305"),
    );
  });

  it("fetchLivePitchesSince throws GameApiError on 500", async () => {
    (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: false,
      status: 500,
      json: async () => null,
    });
    const err = await fetchLivePitchesSince(777001, 0).catch((e) => e);
    expect(err).toBeInstanceOf(GameApiError);
    expect(err.status).toBe(500);
  });
});

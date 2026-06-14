import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  MatchupApiError,
  fetchTodaysMatchups,
  type MatchupSummary,
} from "./matchups";

const ROW: MatchupSummary = {
  gameId: 823370,
  gameDate: "2026-06-13",
  gameTimeUtc: "2026-06-13T23:10:00Z",
  homeTeam: "DET",
  awayTeam: "NYY",
  lean: "pitching",
  homePlayerId: 1,
  homePlayerName: "Tarik Skubal",
  homeRole: "pitcher",
  awayPlayerId: 2,
  awayPlayerName: "Gerrit Cole",
  awayRole: "pitcher",
  battleScore: 7.4,
  stage: "default",
};

describe("fetchTodaysMatchups", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("hits /v1/matchups/today and returns the array", async () => {
    (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => [ROW],
    });
    expect(await fetchTodaysMatchups()).toEqual([ROW]);
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining("/v1/matchups/today"),
    );
  });

  it("throws MatchupApiError on a non-ok response", async () => {
    (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: false,
      status: 503,
      json: async () => null,
    });
    const err = await fetchTodaysMatchups().catch((e) => e);
    expect(err).toBeInstanceOf(MatchupApiError);
    expect(err.status).toBe(503);
  });
});

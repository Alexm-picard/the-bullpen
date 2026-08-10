import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  GameApiError,
  fetchGame,
  fetchLivePitchesSince,
  fetchTodaysGames,
  mergePitchesNewestFirst,
  nextPitchRequest,
  statusPollIntervalMs,
  type GameSummary,
  type CurrentMatchup,
  type LivePitchRow,
  pitchTypeRequest,
  predictPitchType,
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
  currentMatchup: null,
  mostRecentBattedBall: null,
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
  pitcherThrows: "R",
  batterStand: "L",
  baseState: 0,
  parkId: "BOS",
  scoreDiff: 0,
  predictedClasses: null,
  predictedWinner: null,
  launchSpeedMph: null,
  launchAngleDeg: null,
  hitDistanceFt: null,
  bbType: null,
  event: null,
  sprayAngleDeg: null,
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

  it("polls fast while status is still loading (undefined), not the 5-min fallback", () => {
    // FE-H2: the "frozen first five minutes" fix - an unloaded status must not stall a live game.
    expect(statusPollIntervalMs(undefined)).toBe(12_000);
  });

  it("defaults an unrecognised (non-undefined) status to the conservative fallback", () => {
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

describe("mergePitchesNewestFirst", () => {
  const at = (
    cursor: number,
    extra: Partial<LivePitchRow> = {},
  ): LivePitchRow => ({
    ...PITCH,
    cursor,
    ...extra,
  });

  it("returns pitches newest-first (highest cursor first)", () => {
    const out = mergePitchesNewestFirst(new Map(), [at(101), at(103), at(102)]);
    expect(out.map((p) => p.cursor)).toEqual([103, 102, 101]);
  });

  it("dedups + reconciles a re-sent cursor (replaces, not duplicates)", () => {
    const store = new Map<number, LivePitchRow>();
    mergePitchesNewestFirst(store, [at(101, { description: "ball" })]);
    const out = mergePitchesNewestFirst(store, [
      at(101, { description: "called_strike" }),
      at(102),
    ]);
    expect(out.map((p) => p.cursor)).toEqual([102, 101]);
    expect(out.find((p) => p.cursor === 101)?.description).toBe(
      "called_strike",
    );
    expect(store.size).toBe(2); // bounded to distinct cursors, not appended
  });

  it("keeps the newest pitch first so the header [0] and slice(0,N) stay current", () => {
    const many = Array.from({ length: 60 }, (_, i) => at(100 + i));
    const out = mergePitchesNewestFirst(new Map(), many);
    expect(out[0]?.cursor).toBe(159); // most recent, not the opener
    expect(out.slice(0, 50)[0]?.cursor).toBe(159); // slice keeps the newest, never cuts it
  });
});

describe("nextPitchRequest with the LIVE current matchup (V031)", () => {
  const GAME_DATE = "2026-07-20";

  function row(over: Partial<LivePitchRow> = {}): LivePitchRow {
    return {
      ...PITCH,
      atBatIndex: 5,
      description: "ball",
      balls: 1,
      strikes: 1,
      pitcherThrows: "R",
      batterStand: "L",
      baseState: 5,
      parkId: "BOS",
      scoreDiff: 0,
      ...over,
    };
  }

  const matchup = (over: Partial<CurrentMatchup> = {}): CurrentMatchup => ({
    batterId: 900001,
    pitcherId: 900002,
    batSide: "R",
    pitchHand: "L",
    atBatIndex: 5,
    ...over,
  });

  it("predicts for the batter STANDING IN, not the one who took the last pitch", () => {
    // The pinch-hitter case: same at-bat, new batter. The count carries over (a pinch hitter
    // really does inherit it), so the live identity AND handedness must win.
    const req = nextPitchRequest(row(), GAME_DATE, matchup());
    expect(req?.batterId).toBe(900001);
    expect(req?.pitcherId).toBe(900002);
    expect(req?.pitcherThrows).toBe("L");
    expect(req?.batterStand).toBe("R");
  });

  it("resolves a switch hitter against the CURRENT pitcher", () => {
    // The thing a last-pitch lookup cannot do: "S" is only meaningful against the pitcher now
    // on the mound. Live pitchHand L -> the switch hitter bats R.
    const req = nextPitchRequest(
      row(),
      GAME_DATE,
      matchup({ batSide: "S", pitchHand: "L" }),
    );
    expect(req?.batterStand).toBe("R");
    const vsRighty = nextPitchRequest(
      row(),
      GAME_DATE,
      matchup({ batSide: "S", pitchHand: "R" }),
    );
    expect(vsRighty?.batterStand).toBe("L");
  });

  it("WITHHOLDS the request when the matchup has moved PAST the row's at-bat", () => {
    // The asymmetric guard, direction 1. A matchup ahead of the row is positive evidence the
    // row's at-bat is over - information the row cannot supply about itself. This closes three
    // sequences the row's own terminal-outcome switch cannot see (inning-ending caught stealing
    // or pickoff on a live count, a two-strike foul BUNT, a foul tip caught for strike three),
    // each of which otherwise logs a prediction for a pitch that will never be thrown.
    expect(
      nextPitchRequest(row(), GAME_DATE, matchup({ atBatIndex: 6 })),
    ).toBeNull();
  });

  it("falls back to the row when the matchup LAGS it, rather than withholding", () => {
    // Direction 2. The game and pitches queries poll on separate schedules, so the matchup can
    // legitimately be a tick behind; here the row is the fresher source and withholding would
    // gate the panel for no reason.
    const req = nextPitchRequest(row(), GAME_DATE, matchup({ atBatIndex: 4 }));
    expect(req).not.toBeNull();
    expect(req?.batterId).toBe(PITCH.batterId);
    expect(req?.pitcherId).toBe(PITCH.pitcherId);
    expect(req?.pitcherThrows).toBe("R"); // the row's, not the matchup's "L"
  });

  it("falls back to the row when there is no live matchup at all", () => {
    const withNull = nextPitchRequest(row(), GAME_DATE, null);
    const withNothing = nextPitchRequest(row(), GAME_DATE);
    expect(withNull).toEqual(withNothing);
    expect(withNull?.batterId).toBe(PITCH.batterId);
  });

  it("falls back per-field when the feed omitted a handedness code", () => {
    // "" is V031's unpopulated marker, not a value - it must not overwrite a good row value.
    const req = nextPitchRequest(
      row(),
      GAME_DATE,
      matchup({ batSide: "", pitchHand: "" }),
    );
    expect(req?.pitcherThrows).toBe("R"); // row's
    expect(req?.batterStand).toBe("L"); // row's
    expect(req?.batterId).toBe(900001); // identity still live
  });

  it("still gates on the row's terminal outcome - a live matchup does not force a request", () => {
    // Ball four ends the at-bat; the request must stay null even though a matchup exists, so
    // the panel gates rather than logging a prediction for a pitch that will not be thrown.
    expect(
      nextPitchRequest(row({ balls: 3 }), GAME_DATE, matchup()),
    ).toBeNull();
  });
});

describe("nextPitchRequest (A6 settled-at-bat gate)", () => {
  // 2026-07-20 is a Monday -> ISO dow 1.
  const GAME_DATE = "2026-07-20";

  function row(over: Partial<LivePitchRow> = {}): LivePitchRow {
    return {
      ...PITCH,
      description: "ball",
      balls: 1,
      strikes: 1,
      pitcherThrows: "R",
      batterStand: "L",
      baseState: 5,
      parkId: "BOS",
      scoreDiff: 0,
      ...over,
    };
  }

  it("advances the count from the row's PRE-pitch state ([143]) and carries the A5 context", () => {
    const req = nextPitchRequest(row(), GAME_DATE);
    expect(req).toEqual({
      countBalls: 2, // 1-1 + ball -> 2-1
      countStrikes: 1,
      outs: PITCH.outs,
      inning: PITCH.inning,
      baseState: 5,
      scoreDiff: 0,
      dow: 1, // Monday
      pitcherThrows: "R",
      batterStand: "L",
      parkId: "BOS",
      pitcherId: PITCH.pitcherId,
      batterId: PITCH.batterId,
    });
  });

  it("withholds on a walk (ball four) - the at-bat is over", () => {
    expect(nextPitchRequest(row({ balls: 3 }), GAME_DATE)).toBeNull();
  });

  it("withholds on a strikeout (strike three)", () => {
    expect(
      nextPitchRequest(
        row({ description: "swinging_strike", strikes: 2 }),
        GAME_DATE,
      ),
    ).toBeNull();
  });

  it("caps a two-strike foul at two strikes (at-bat continues)", () => {
    const req = nextPitchRequest(
      row({ description: "foul", strikes: 2 }),
      GAME_DATE,
    );
    expect(req?.countStrikes).toBe(2);
  });

  it("withholds on terminal or untrusted outcomes (in_play / hit_by_pitch / unknown)", () => {
    for (const d of ["in_play", "hit_by_pitch", "unknown"]) {
      expect(nextPitchRequest(row({ description: d }), GAME_DATE)).toBeNull();
    }
  });

  it("resolves a switch hitter to the opposite of the pitcher's hand (server convention)", () => {
    expect(
      nextPitchRequest(row({ batterStand: "S", pitcherThrows: "R" }), GAME_DATE)
        ?.batterStand,
    ).toBe("L");
    expect(
      nextPitchRequest(row({ batterStand: "S", pitcherThrows: "L" }), GAME_DATE)
        ?.batterStand,
    ).toBe("R");
  });

  it("withholds on pre-V028 rows (blank hands / null baseState = unknown, not empty)", () => {
    expect(nextPitchRequest(row({ pitcherThrows: "" }), GAME_DATE)).toBeNull();
    expect(nextPitchRequest(row({ batterStand: "" }), GAME_DATE)).toBeNull();
    expect(nextPitchRequest(row({ baseState: null }), GAME_DATE)).toBeNull();
  });
});

describe("predictPitchType error envelope", () => {
  // B1 REGRESSION. The backend envelope is `record ApiError(Body error)`, so the reason lives at
  // $.error.message. Reading body.message yielded "" for EVERY refusal - the panel rendered "the
  // server gave no reason" and all four designed explanations were discarded in production. The
  // panel tests could not catch it: they construct GameApiError directly and never run this parse.
  const REQ = {
    pitcherId: 1,
    gameId: 2,
    gameDate: "2026-07-20",
    atBatIndex: 3,
    pitchNumber: 2,
    balls: 1,
    strikes: 1,
    outs: 0,
    inning: 1,
    baseState: 0,
    stand: "R",
    pThrows: "R",
    parkId: "BOS",
    timesThroughOrder: null,
    atBatNumberInGame: null,
    timesFacedToday: null,
  };

  function respond(body: unknown, ok = false) {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok,
        status: ok ? 200 : 503,
        json: async () => {
          if (body === "NOT_JSON") throw new SyntaxError("not json");
          return body;
        },
      }),
    );
  }

  afterEach(() => vi.unstubAllGlobals());

  it("extracts the reason from the REAL nested envelope", async () => {
    respond({
      error: {
        code: "service_unavailable",
        message: "pitcher 476454 has no career-prior snapshot row",
        correlationId: "cid",
        details: [],
      },
    });
    await expect(predictPitchType(REQ)).rejects.toMatchObject({
      status: 503,
      message: "pitcher 476454 has no career-prior snapshot row",
    });
  });

  it("yields an empty reason for a flat body rather than inventing one", async () => {
    // Pins the shape: were the parse ever "fixed" back to body.message, the nested test above
    // would red AND this would start returning a reason - so the pair brackets the contract.
    respond({ message: "flat" });
    await expect(predictPitchType(REQ)).rejects.toMatchObject({ message: "" });
  });

  it("degrades to an empty reason on a null body and on non-JSON", async () => {
    respond(null);
    await expect(predictPitchType(REQ)).rejects.toMatchObject({ message: "" });
    respond("NOT_JSON");
    await expect(predictPitchType(REQ)).rejects.toMatchObject({ message: "" });
  });
});

describe("pitchTypeRequest", () => {
  const GAME_DATE = "2026-07-20";
  const GAME_ID = 777;

  function row(over: Partial<LivePitchRow> = {}): LivePitchRow {
    return {
      ...PITCH,
      description: "ball",
      balls: 1,
      strikes: 1,
      pitcherThrows: "R",
      batterStand: "L",
      baseState: 5,
      parkId: "BOS",
      scoreDiff: 0,
      ...over,
    };
  }

  it("asks about the NEXT pitch, advancing pitchNumber by one", () => {
    // The server's cutoff is strictly-less-than on (at_bat_index, pitch_number), so passing
    // row.pitchNumber + 1 means "every pitch up to and including the one just thrown" - exactly
    // the history preceding the pitch being asked about. at_bat_index does NOT advance with it.
    const req = pitchTypeRequest(row({ pitchNumber: 4 }), GAME_DATE, GAME_ID)!;
    expect(req.pitchNumber).toBe(5);
    expect(req.atBatIndex).toBe(row().atBatIndex);
    expect(req.gameId).toBe(GAME_ID);
    expect(req.balls).toBe(2); // 1-1 + ball
    expect(req.strikes).toBe(1);
  });

  it("sends the three unknowable context fields as null, never a guess", () => {
    const req = pitchTypeRequest(row(), GAME_DATE, GAME_ID)!;
    expect(req.timesThroughOrder).toBeNull();
    expect(req.atBatNumberInGame).toBeNull();
    expect(req.timesFacedToday).toBeNull();
  });

  it("is null EXACTLY when nextPitchRequest is null, across every gate", () => {
    // The property the shared derivation exists to guarantee: two panels describing the same
    // upcoming pitch must never disagree about whether it exists. Asserted as an equivalence over
    // the real gates rather than spot-checked, so a future edit to either builder that breaks the
    // pairing reds here.
    const cases: LivePitchRow[] = [
      row(),
      row({ description: "ball", balls: 3 }), // walk
      row({ description: "called_strike", strikes: 2 }), // strikeout
      row({ description: "in_play" }), // at-bat over
      row({ description: "hit_by_pitch" }),
      row({ description: "foul", strikes: 2 }),
      row({ baseState: null }), // pre-V028 row
      row({ parkId: "" }),
      row({ pitcherThrows: "" }),
      row({ batterStand: "" }),
    ];
    for (const r of cases) {
      const a = nextPitchRequest(r, GAME_DATE) == null;
      const b = pitchTypeRequest(r, GAME_DATE, GAME_ID) == null;
      expect(
        b,
        `disagreement on ${r.description}/${r.balls}-${r.strikes}`,
      ).toBe(a);
    }
  });
});

// @vitest-environment jsdom
/**
 * The front page's settled states against stubbed fetches (C-34: mock at the fetch boundary so the
 * real hooks, posture logic and fallbacks run). One test per row of SPEC-home §6's state matrix that
 * a visitor can actually land on: pre-game, live, no games, backend offline.
 */
import { cleanup, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";

import {
  installMantineShims,
  renderWithProviders,
  stubFetchRoutes,
  type StubRoute,
} from "../test-support/behavioral";

import HomePage from "./home-page";

beforeAll(() => installMantineShims());
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const GAME_ID = 813001;
const MESSICK = 700001;
const SMITH = 700002;

const DUEL = {
  gameId: GAME_ID,
  gameDate: "2026-10-08",
  gameTimeUtc: "2026-10-09T00:00:00Z",
  homeTeam: "CWS",
  awayTeam: "CLE",
  lean: "pitching",
  homePlayerId: SMITH,
  homePlayerName: "Hagen Smith",
  homeRole: "pitcher",
  awayPlayerId: MESSICK,
  awayPlayerName: "Parker Messick",
  awayRole: "pitcher",
  battleScore: 3.9,
  stage: "lineup",
};

function game(status: string, inning = 0) {
  return {
    gameId: GAME_ID,
    gameDate: "2026-10-08",
    homeTeam: "CWS",
    awayTeam: "CLE",
    homeScore: 1,
    awayScore: 2,
    inning,
    status,
    detailedState: status === "SCHEDULED" ? "Scheduled" : "In Progress",
    currentMatchup:
      status === "IN_PROGRESS"
        ? {
            batterId: 1,
            pitcherId: MESSICK,
            batSide: "R",
            pitchHand: "L",
            atBatIndex: 17,
          }
        : null,
    mostRecentBattedBall: null,
  };
}

const ARSENAL = [
  {
    pitchType: "SL",
    count: 300,
    usagePct: 0.24,
    veloMinMph: 80,
    veloAvgMph: 84,
    veloMaxMph: 87,
  },
  {
    pitchType: "FF",
    count: 500,
    usagePct: 0.41,
    veloMinMph: 91,
    veloAvgMph: 94,
    veloMaxMph: 97,
  },
  {
    pitchType: "CH",
    count: 200,
    usagePct: 0.17,
    veloMinMph: 83,
    veloAvgMph: 86,
    veloMaxMph: 88,
  },
  {
    pitchType: "CU",
    count: 90,
    usagePct: 0.08,
    veloMinMph: 75,
    veloAvgMph: 78,
    veloMaxMph: 80,
  },
];

function version(id: number, modelName: string, stage: string) {
  return {
    id,
    modelName,
    version: "v2",
    artifactPath: "",
    metadataPath: "",
    trainingDataHash: "",
    trainingDataWindow: "",
    featureSchemaHash: "",
    evalMetrics: "{}",
    trainedAt: "2026-08-01T00:00:00Z",
    promotedAt: null,
    stage,
    createdBy: null,
    notes: null,
  };
}

const REGISTRY = [
  version(1, "pitch_outcome_pre", "CHAMPION"),
  version(2, "pitch_type_pre", "CHAMPION"),
  version(3, "pitch_outcome_post", "CHAMPION"),
  version(4, "battedball_outcome", "CHAMPION"),
  version(5, "pitch_outcome_pre_lr_baseline", "SHADOW"),
];

const PRIOR = {
  FF: 0.34,
  SL: 0.22,
  CH: 0.16,
  SI: 0.1,
  CU: 0.08,
  FC: 0.06,
  OFF: 0.04,
};

const LIVE_STATE = {
  status: "IN_PROGRESS",
  matchup: {
    batterId: 1,
    pitcherId: MESSICK,
    batSide: "R",
    pitchHand: "L",
    atBatIndex: 17,
  },
  upcomingPitch: {
    atBatIndex: 17,
    pitchNumber: 4,
    balls: 1,
    strikes: 2,
    outs: 1,
    baseState: 1,
  },
  lastPitchCursor: 1703,
  prePrediction: { probabilities: { ball: 0.38 }, winner: "ball" },
  pitchTypePrediction: { probabilities: PRIOR, winner: "FF" },
  modelVersions: { pre: "v2", pitchType: "v1" },
  predictedAt: "2026-10-09T00:31:00Z",
  asOf: "2026-10-09T00:31:02Z",
};

/** Routes shared by every state; order matters (first substring match wins). */
function routes(over: { matchups: StubRoute; games: StubRoute }): StubRoute[] {
  return [
    over.matchups,
    { match: `/v1/games/${GAME_ID}/live`, body: LIVE_STATE },
    over.games,
    { match: "/v1/ops/registry/all", body: REGISTRY },
    { match: "/v1/ops/routing", body: [] },
    {
      match: "/v1/ops/rolling-accuracy",
      body: { windowDays: 7, generatedAt: "", models: [] },
    },
    { match: `/v1/players/${MESSICK}/arsenal`, body: ARSENAL },
    {
      match: "/v1/players/",
      body: {
        id: 1,
        name: "Some Reliever",
        primaryPosition: "P",
        active: true,
        team: "CLE",
      },
    },
  ];
}

describe("HomePage front page states", () => {
  it("pre-game: names the matchup and the starter's career mix, labelled as career usage", async () => {
    stubFetchRoutes(
      routes({
        matchups: { match: "/v1/matchups/today", body: [DUEL] },
        games: { match: "/v1/games/today", body: [game("SCHEDULED")] },
      }),
    );
    renderWithProviders(<HomePage />);

    const h1 = await screen.findByRole("heading", {
      level: 1,
      name: "Messick and Smith, and Messick’s mix: four-seam, slider and changeup.",
    });
    // The entrance animation must ADD its class, never replace the typography class.
    expect(h1).toHaveClass("ed-h1");
    expect(screen.getByText("Tonight: CLE at CWS, 8:00 PM ET")).toHaveClass(
      "ed-slug",
    );
    expect(
      screen.getByText(/Career usage, not a prediction/),
    ).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2 })).toHaveTextContent(
      "Parker Messick’s mix",
    );
    // Evidence figures: the two offline gate numbers and the live champion count (4 of 5 rows).
    expect(screen.getByText("0.016")).toBeInTheDocument();
    expect(screen.getByText("0.104")).toBeInTheDocument();
    await waitFor(() =>
      expect(
        screen.getByText("Champions in the registry right now."),
      ).toBeInTheDocument(),
    );
    expect(
      screen.getByText("Models serving live").previousSibling,
    ).toHaveTextContent("4");
    // The band: a one-game slate says so, and the desk shows the newest write-up.
    expect(
      screen.getByRole("heading", { name: "One game tonight" }),
    ).toBeInTheDocument();
    expect(screen.getByText("A thin slate tonight.")).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "Two months of silent staleness" }),
    ).toBeInTheDocument();
  });

  it("live: shows the worker's pitch-type prior, seven uniform rows, and adds no inference call", async () => {
    const fetchMock = stubFetchRoutes(
      routes({
        matchups: { match: "/v1/matchups/today", body: [DUEL] },
        games: { match: "/v1/games/today", body: [game("IN_PROGRESS", 3)] },
      }),
    );
    const { container } = renderWithProviders(<HomePage />);

    const table = await screen.findByRole("table", {
      name: "Calibrated prior, not a call.",
    });
    const rows = within(table).getAllByRole("row").slice(1); // drop the header row
    expect(rows).toHaveLength(7);
    // Ranked by probability...
    expect(rows.map((r) => r.textContent?.slice(0, 2))).toEqual([
      "FF",
      "SL",
      "CH",
      "SI",
      "CU",
      "FC",
      "OF",
    ]);
    // ...and [183]: every row identical in markup - same row class, same cell classes, no bolding.
    const shape = (r: HTMLElement) =>
      [
        r.className,
        ...Array.from(r.querySelectorAll("td, span")).map((el) => el.className),
      ].join("|");
    const first = rows[0];
    if (!first) throw new Error("no rows");
    for (const r of rows) expect(shape(r)).toBe(shape(first));
    expect(table.querySelector("tbody strong, tbody b, tbody em")).toBeNull();

    expect(
      screen.getByText("Parker Messick pitching, 1-2, 1 out"),
    ).toBeInTheDocument();
    expect(container.querySelector(".ed-front")).toHaveAttribute(
      "data-live",
      "true",
    );
    expect(screen.getByText(/Live: CLE at CWS, inning 3/)).toBeInTheDocument();
    // [194]: home reads the worker's answer; it never calls the logging predict endpoints.
    const urls = fetchMock.mock.calls.map(([u]) => String(u));
    expect(urls.some((u) => u.includes("/v1/predict"))).toBe(false);
  });

  it("no games: the evergreen front page, no showcase dressing", async () => {
    stubFetchRoutes(
      routes({
        matchups: { match: "/v1/matchups/today", body: [] },
        games: { match: "/v1/games/today", body: [] },
      }),
    );
    renderWithProviders(<HomePage />);
    expect(
      await screen.findByRole("heading", {
        level: 1,
        name: "Four models, scored against what actually happened.",
      }),
    ).toBeInTheDocument();
    expect(screen.getByText("No games tonight")).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { level: 2, name: "The record" }),
    ).toBeInTheDocument();
    expect(screen.getByText("No games on the schedule.")).toBeInTheDocument();
    expect(screen.queryByText(/Showcase data/)).toBeNull();
  });

  it("backend offline: the showcase slate, labelled, with no live prior", async () => {
    stubFetchRoutes(
      routes({
        matchups: { match: "/v1/matchups/today", status: 503, body: {} },
        games: { match: "/v1/games/today", status: 503, body: {} },
      }),
    );
    renderWithProviders(<HomePage />);
    expect(
      await screen.findByText(
        "Showcase data: the backend is unreachable, so this is a sample slate.",
      ),
    ).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(
      /^Cole and Skubal/,
    );
    expect(
      screen.getByText("The live prior appears once a real game is on."),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("table", { name: /Calibrated prior/ }),
    ).toBeNull();
  });
});

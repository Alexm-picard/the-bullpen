// @vitest-environment jsdom
/**
 * Behavioral test for <GamePage> (C-34). The status-driven live view: an IN_PROGRESS game polls its
 * pitch log (poll interval derived by statusPollIntervalMs, unit-tested in api/games.test.ts), and
 * the page renders the live board from the game + pitches queries. This drives the REAL fetch
 * boundary (not the cache seeding the smoke suite uses), so the query -> render flow runs for real,
 * and pins the invalid-id contract text the e2e suite depends on.
 *
 * The static/seeded content cases (chrome, one-h1, LIVE-BIP vs showcase) stay in the smoke suite
 * game-page.test.tsx - per plan task 12, static renders remain as smoke.
 */
import "@testing-library/jest-dom/vitest";

import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";

import type { GameSummary, LivePitchRow } from "../api/games";
import { theme } from "../design/theme";
import {
  installMantineShims,
  stubFetchRoutes,
} from "../test-support/behavioral";

import { GamePage } from "./game-page";

const GAME_ID = 12345;

const GAME: GameSummary = {
  gameId: GAME_ID,
  gameDate: "2026-06-25",
  homeTeam: "DET",
  awayTeam: "NYY",
  homeScore: 1,
  awayScore: 2,
  inning: 5,
  status: "IN_PROGRESS",
  detailedState: "In Progress",
};

function pitch(over: Partial<LivePitchRow> = {}): LivePitchRow {
  return {
    gameId: GAME_ID,
    atBatIndex: 1,
    pitchNumber: 1,
    cursor: 1,
    ingestedAt: "2026-06-25T20:00:00Z",
    pitcherId: 200,
    batterId: 111,
    description: "ball",
    pitchType: "FF",
    releaseSpeedMph: 97.3,
    plateXIn: 0,
    plateZIn: 24,
    balls: 0,
    strikes: 0,
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
    ...over,
  };
}

/** GamePage reads :id from the route, so it needs a Routes wrapper the shared helper doesn't set up. */
function renderAt(path: string) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MantineProvider theme={theme}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/games/:id" element={<GamePage />} />
          </Routes>
        </MemoryRouter>
      </MantineProvider>
    </QueryClientProvider>,
  );
}

beforeAll(installMantineShims);

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});

describe("GamePage (interaction)", () => {
  it("renders the live pitch board from the game + pitches queries for an in-progress game", async () => {
    // "/pitches" ordered first so the pitches URL matches it before the broader "/v1/games/" route.
    // The fixture is a settled at-bat on a live game, so the A6 next-pitch query FIRES - stub it
    // with the honest current-prod reality (503, PRE not yet promoted) and assert the panel shows
    // the CLEAN not-promoted state rather than an error (ADR-0014 / rule 6).
    stubFetchRoutes([
      { match: "/v1/predict/pitch", status: 503, body: null },
      { match: "/pitches", body: [pitch()] },
      { match: "/v1/games/", body: GAME },
    ]);
    renderAt(`/games/${GAME_ID}`);

    expect(await screen.findByText(/Live Pitch Log/i)).toBeInTheDocument();
    // The board rendered the polled pitch (its distinctive velo), not the empty waiting state.
    expect(await screen.findByText("97.3")).toBeInTheDocument();
    // A6: the gated query fired (live + settled) and the 503 renders the clean unpromoted line.
    expect(
      await screen.findByTestId("next-pitch-unpromoted"),
    ).toHaveTextContent(/not yet promoted/i);
  });

  it("shows the invalid-id contract message for a non-numeric id", () => {
    stubFetchRoutes([{ match: "/v1/games/", body: GAME }]);
    renderAt("/games/not-a-number");
    expect(screen.getByText("Invalid game id.")).toBeInTheDocument();
  });
});

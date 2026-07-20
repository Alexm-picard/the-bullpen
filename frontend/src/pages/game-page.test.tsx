/**
 * Smoke tests for /games/:id on the BROADCAST identity (redesign PR-2,
 * decision [160]). Same narrow posture as before: the page wires real
 * TanStack hooks, so we assert the shell + chrome render, the one-h1 rule
 * holds, and the invalid-id contract survives (the e2e suite depends on its
 * exact text). Data-driven states live in live-pitch-board.test.tsx.
 *
 * Phase 1.2 adds two cache-seeded cases for the live batted-ball card: when a
 * recent in-play pitch carries launch physics AND its all-parks prediction is
 * present, the card renders that BIP; otherwise it falls back to the showcase
 * fixture. Seeding (not fetch-mocking) is required because renderToStaticMarkup
 * will not await async queries - the same pattern as accuracy-page.test.tsx.
 */
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";

import { type GameSummary, type LivePitchRow } from "../api/games";
import { type AllParksRequest } from "../api/parks";
import { colors } from "../design/broadcast";
import { theme } from "../design/theme";

import { GamePage } from "./game-page";

function render(
  node: ReactNode,
  initialPath: string,
  client?: QueryClient,
): string {
  const qc =
    client ??
    new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return renderToStaticMarkup(
    <QueryClientProvider client={qc}>
      <MantineProvider theme={theme}>
        <MemoryRouter initialEntries={[initialPath]}>
          <Routes>
            <Route path="/games/:id" element={node} />
          </Routes>
        </MemoryRouter>
      </MantineProvider>
    </QueryClientProvider>,
  );
}

const GAME_ID = 12345;

function makeGame(overrides: Partial<GameSummary> = {}): GameSummary {
  return {
    gameId: GAME_ID,
    gameDate: "2026-06-25",
    homeTeam: "DET",
    awayTeam: "NYY",
    homeScore: 1,
    awayScore: 2,
    inning: 5,
    status: "IN_PROGRESS",
    detailedState: "In Progress",
    ...overrides,
  };
}

function makePitch(overrides: Partial<LivePitchRow> = {}): LivePitchRow {
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
    releaseSpeedMph: 95,
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
    ...overrides,
  };
}

function seededClient(): QueryClient {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

describe("GamePage (broadcast identity)", () => {
  it("renders the light field under broadcast chrome", () => {
    const html = render(<GamePage />, "/games/12345");
    expect(html.toLowerCase()).toContain(colors.field.toLowerCase());
    expect(html.toLowerCase()).toContain(colors.chrome.toLowerCase());
  });

  it("renders exactly one h1 (the matchup masthead)", () => {
    const html = render(<GamePage />, "/games/12345");
    const h1Count = (html.match(/<h1/g) ?? []).length;
    expect(h1Count).toBe(1);
  });

  it("renders the scorebug as a status element and the pitch-log lower third", () => {
    const html = render(<GamePage />, "/games/12345");
    expect(html).toContain('role="status"');
    expect(html).toContain("Live Pitch Log");
  });

  it("renders the honest next-pitch gated state (ADR-0014 supersedes the [154] pending line)", () => {
    // A6: the old "pitch model pending" header line is replaced by the Next-Pitch Model section.
    // With no pitches loaded the at-bat is not settled, so the panel renders its GATED state - the
    // honest champion-less/context-less surface, and no request ever fires from a static render.
    const html = render(<GamePage />, "/games/12345");
    expect(html).toContain("Next-Pitch Model");
    expect(html).toContain("Awaiting a settled at-bat");
    expect(html).not.toContain("pitch model pending");
  });

  it("renders the chrome footer", () => {
    const html = render(<GamePage />, "/games/12345");
    expect(html).toContain("THE BULLPEN · LIVE GAME");
  });

  it("renders the invalid-id message when :id is non-numeric (e2e contract text)", () => {
    const html = render(<GamePage />, "/games/not-a-number");
    expect(html).toContain("Invalid game id.");
  });

  it("renders the LIVE batted ball when a recent in-play pitch carries launch data", () => {
    const client = seededClient();
    client.setQueryData(["games", "byId", GAME_ID], makeGame());
    // Newest-first store: the qualifying in-play BIP is the only/first row.
    const bip = makePitch({
      cursor: 100,
      description: "in_play",
      batterId: 111,
      pitcherId: 200,
      outs: 2,
      launchSpeedMph: 104.3,
      launchAngleDeg: 27,
      hitDistanceFt: 389,
      bbType: "line_drive",
      event: "field_out",
    });
    client.setQueryData(["games", "pitches", GAME_ID], [bip]);
    client.setQueryData(["players", "byId", 111], {
      id: 111,
      name: "Live Batter",
      primaryPosition: "RF",
      active: true,
      team: "NYY",
    });
    client.setQueryData(["players", "byId", 200], {
      id: 200,
      name: "Live Pitcher",
      primaryPosition: "P",
      active: true,
      team: "DET",
    });
    // The exact all-parks request the page derives for this BIP (hitDistanceFt is
    // the row's own value, stand defaults to R, outs flow through). React Query
    // hashes keys deterministically, so structural equality is what matters.
    const req: AllParksRequest = {
      launchSpeedMph: 104.3,
      launchAngleDeg: 27,
      sprayAngleDeg: 0,
      hitDistanceFt: 389,
      stand: "R",
      baseState: 0,
      outs: 2,
    };
    client.setQueryData(["parks", "all-parks", req], {
      probHrByPark: { DET: 0.2, NYY: 0.72, BOS: 0.55, COL: 0.9 },
      carryFtByPark: { DET: 401, NYY: 404, BOS: 402, COL: 419 },
      modelName: "batted_ball",
      modelVersion: "v1.4",
      latencyMicros: 1234,
      correlationId: "test-corr",
    });

    const html = render(<GamePage />, `/games/${GAME_ID}`, client);
    // The live BIP card replaces the showcase: live batter + humanized event +
    // the BIP's own metrics, and the live caption + meta swap in.
    expect(html).toContain("Live Batter");
    expect(html).toContain("Field Out");
    expect(html).toContain("104.3");
    expect(html).toContain("LIVE BIP");
    expect(html).toContain("most recent in-play batted ball this game");
    expect(html).not.toContain("Giancarlo Stanton");
  });

  it("falls back to the showcase batted ball when no in-play pitch carries launch data", () => {
    const client = seededClient();
    client.setQueryData(["games", "byId", GAME_ID], makeGame());
    // A called strike (not in_play) and an in_play row with NULL launch data:
    // neither satisfies the launch-data predicate, so the showcase stays.
    client.setQueryData(
      ["games", "pitches", GAME_ID],
      [
        makePitch({ cursor: 2, description: "called_strike" }),
        makePitch({
          cursor: 1,
          description: "in_play",
          launchSpeedMph: null,
          launchAngleDeg: null,
        }),
      ],
    );

    const html = render(<GamePage />, `/games/${GAME_ID}`, client);
    expect(html).toContain("Giancarlo Stanton");
    expect(html).toContain("A static example of the per-park HR model");
    expect(html).toContain("MODEL EXAMPLE");
    expect(html).not.toContain("LIVE BIP");
  });
});

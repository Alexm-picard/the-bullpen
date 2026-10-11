// @vitest-environment jsdom
/**
 * /games settled postures against stubbed fetches (C-34: mock at the fetch boundary so the real
 * hooks, posture logic, merge and board order run). One test per row of SPEC-games-404 §5.1 that a
 * visitor can land on, plus the filter.
 */
import { cleanup, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";

import {
  installMantineShims,
  renderWithProviders,
  stubFetchRoutes,
} from "../test-support/behavioral";

import GamesPage from "./games-page";

beforeAll(() => installMantineShims());
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function matchup(
  gameId: number,
  away: string,
  home: string,
  utc: string,
  score: number,
) {
  return {
    gameId,
    gameDate: "2026-06-13",
    gameTimeUtc: utc,
    homeTeam: home,
    awayTeam: away,
    lean: "pitching",
    homePlayerId: gameId * 10,
    homePlayerName: `${home} Starter`,
    homeRole: "pitcher",
    awayPlayerId: gameId * 10 + 1,
    awayPlayerName: `${away} Starter`,
    awayRole: "pitcher",
    battleScore: score,
    stage: "default",
  };
}

function game(
  gameId: number,
  away: string,
  home: string,
  status: string,
  o: object = {},
) {
  return {
    gameId,
    gameDate: "2026-06-13",
    homeTeam: home,
    awayTeam: away,
    homeScore: 1,
    awayScore: 2,
    inning: 5,
    status,
    detailedState: "In Progress",
    currentMatchup: null,
    mostRecentBattedBall: null,
    ...o,
  };
}

describe("GamesPage postures", () => {
  it("degrades to the labelled showcase when both sources answer empty (not posted yet)", async () => {
    stubFetchRoutes([
      { match: "matchups/today", body: [] },
      { match: "games/today", body: [] },
    ]);
    renderWithProviders(<GamesPage />);
    expect(
      await screen.findByText(/No games have posted for today yet/),
    ).toBeInTheDocument();
    expect(screen.getByText("Games, showcase slate")).toBeInTheDocument();
    // Showcase rows are plain text: no link into a fixture game id.
    expect(document.querySelector('a[href^="/games/"]')).toBeNull();
    expect(screen.getAllByText("NYY at DET").length).toBeGreaterThan(0);
    expect(screen.queryByText("scores refresh every minute")).toBeNull();
  });

  it("says the backend is unreachable when a source errors", async () => {
    stubFetchRoutes([
      { match: "matchups/today", status: 503, body: {} },
      { match: "games/today", status: 503, body: {} },
    ]);
    renderWithProviders(<GamesPage />);
    expect(
      await screen.findByText(/The backend is unreachable/),
    ).toBeInTheDocument();
  });

  it("renders the live board: composed headline, first-pitch order, numeric links", async () => {
    stubFetchRoutes([
      {
        match: "matchups/today",
        body: [
          // mergeSlate puts the best rating first; the board re-orders by first pitch.
          matchup(1, "LAD", "SF", "2026-06-14T02:15:00Z", 9.0),
          matchup(2, "SEA", "TEX", "2026-06-14T00:05:00Z", 5.0),
          matchup(3, "NYY", "DET", "2026-06-13T23:10:00Z", 8.6),
        ],
      },
      { match: "games/today", body: [game(3, "NYY", "DET", "IN_PROGRESS")] },
    ]);
    renderWithProviders(<GamesPage />);
    expect(
      await screen.findByRole("heading", {
        level: 1,
        name: "One under way, two to come.",
      }),
    ).toBeInTheDocument();
    const toCome = screen
      .getByRole("heading", { level: 2, name: "Still to come" })
      .closest("section")!;
    const pairs = within(toCome)
      .getAllByRole("link")
      .map((a) => a.textContent);
    expect(pairs).toEqual(["SEA at TEX", "LAD at SF"]);
    expect(
      screen.getByRole("link", { name: "Open game for NYY at DET" }),
    ).toHaveAttribute("href", "/games/3");
    expect(screen.queryByText(/Showcase/)).toBeNull();
    expect(screen.getByText("scores refresh every minute")).toBeInTheDocument();
  });

  it("filters to one status, says when nothing matches, and resets", async () => {
    stubFetchRoutes([
      {
        match: "matchups/today",
        body: [matchup(3, "NYY", "DET", "2026-06-13T23:10:00Z", 8.6)],
      },
      { match: "games/today", body: [game(3, "NYY", "DET", "IN_PROGRESS")] },
    ]);
    const user = userEvent.setup();
    renderWithProviders(<GamesPage />);
    const group = await screen.findByRole("group", {
      name: "Filter games by status",
    });
    await waitFor(() =>
      expect(
        within(group).getByRole("button", { name: /^Final/ }),
      ).toBeInTheDocument(),
    );
    await user.click(within(group).getByRole("button", { name: /^Final/ }));
    expect(
      within(group).getByRole("button", { name: /^Final/ }),
    ).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByText("No final games right now.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Show all games" }));
    expect(within(group).getByRole("button", { name: /^All/ })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(
      screen.getByRole("link", { name: "Open game for NYY at DET" }),
    ).toBeInTheDocument();
  });
});

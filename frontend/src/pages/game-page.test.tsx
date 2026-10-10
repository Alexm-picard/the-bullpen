/**
 * Smoke tests for /games/:id on the [195] editorial identity (SPEC-game). The page wires real
 * TanStack hooks, so we assert the shell renders, the one-h1 rule holds, the invalid-id contract
 * survives (the e2e suite depends on its exact text), every game state reads as what it is, and the
 * batted-ball, current-batter and live-state honesty rules carried over from the broadcast page.
 * The account's derivations (line score, at-bat sentences, model gave it) are unit-tested in
 * components/games/game-account.test.ts.
 *
 * Seeding (not fetch-mocking) is required because renderToStaticMarkup will not await async
 * queries - the same pattern as accuracy-page.test.tsx.
 */
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter, Route, Routes } from "react-router";
import { describe, expect, it } from "vitest";

import {
  type GameSummary,
  type LiveGameState,
  type LivePitchRow,
} from "../api/games";
import { CANONICAL_BBE_INPUT, type AllParksRequest } from "../api/parks";
import { theme } from "../design/theme";

import { GamePage } from "./game-page";
import { visibleText } from "../test-support/visible-text";

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
    currentMatchup: null,
    mostRecentBattedBall: null,
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
    sprayAngleDeg: null,
    ...overrides,
  };
}

function seededClient(): QueryClient {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

describe("GamePage (editorial identity)", () => {
  it("renders on the editorial page ground", () => {
    const html = render(<GamePage />, "/games/12345");
    expect(html).toContain('class="ed-page"');
    expect(html).not.toContain("broadcast-live-dot");
  });

  it("renders exactly one h1 (the score headline)", () => {
    const html = render(<GamePage />, "/games/12345");
    const h1Count = (html.match(/<h1/g) ?? []).length;
    expect(h1Count).toBe(1);
  });

  it("heads a live game with its score, its inning in words, and the account", () => {
    const client = seededClient();
    client.setQueryData(["games", "byId", GAME_ID], makeGame());
    client.setQueryData(
      ["games", "pitches", GAME_ID],
      [makePitch({ pitchType: "SL", releaseSpeedMph: 86.4 })],
    );
    const text = visibleText(render(<GamePage />, `/games/${GAME_ID}`, client));
    expect(text).toContain("NYY 2, DET 1");
    expect(text).toContain("Live, 5th inning");
    expect(text).toContain("Every at-bat, newest first");
    // The pitch reaches the account's sequence, not a separate board.
    expect(text).toMatch(/SL 86\.4\s+ball/);
  });

  it("prints no score at all, not a fabricated 0-0, before the summary loads", () => {
    const html = render(<GamePage />, "/games/12345");
    const h1 = html.match(/<h1[^>]*>([\s\S]*?)<\/h1>/)?.[1] ?? "";
    expect(visibleText(h1)).not.toMatch(/\d/);
    expect(visibleText(html)).toContain("Loading the game");
  });

  it.each([
    ["DELAYED", "Delayed in the 5th", "Delayed Start: Rain"],
    ["SUSPENDED", "Suspended in the 5th", "Suspended: Rain"],
  ])(
    "reads %s as stopped play with MLB's own description, never as live",
    (status, slug, detailedState) => {
      const client = seededClient();
      client.setQueryData(
        ["games", "byId", GAME_ID],
        makeGame({ status, detailedState, inning: 5 }),
      );
      client.setQueryData(["games", "pitches", GAME_ID], [makePitch()]);
      const text = visibleText(
        render(<GamePage />, `/games/${GAME_ID}`, client),
      );
      expect(text).toContain(slug);
      expect(text).toContain(detailedState);
      expect(text).not.toContain("Live,");
      // Stopped play estimates nothing: both model slots gate.
      expect(countOccurrences(text, "Awaiting a settled at-bat")).toBe(2);
    },
  );

  it("does not place a delayed START in an inning (no pitch has been thrown)", () => {
    const client = seededClient();
    client.setQueryData(
      ["games", "byId", GAME_ID],
      makeGame({
        status: "DELAYED",
        detailedState: "Delayed Start: Rain",
        inning: 1,
        awayScore: 0,
        homeScore: 0,
      }),
    );
    client.setQueryData(["games", "pitches", GAME_ID], []);
    const text = visibleText(render(<GamePage />, `/games/${GAME_ID}`, client));
    expect(text).toContain("Delayed");
    expect(text).not.toContain("Delayed in the");
    // No baseball yet, so no score either.
    expect(text).toContain("NYY at DET");
    expect(text).not.toContain("NYY 0, DET 0");
  });

  it.each([
    ["POSTPONED", "Postponed"],
    ["UNKNOWN", "Status unknown"],
  ])("reads %s as %s, with no score and never an inning 0", (status, slug) => {
    const client = seededClient();
    client.setQueryData(
      ["games", "byId", GAME_ID],
      makeGame({ status, inning: 0, detailedState: "" }),
    );
    client.setQueryData(["games", "pitches", GAME_ID], []);
    const text = visibleText(render(<GamePage />, `/games/${GAME_ID}`, client));
    expect(text).toContain(slug);
    expect(text).toContain("NYY at DET");
    expect(text).not.toContain("NYY 2, DET 1");
    expect(text).not.toContain("0th");
  });

  it("routes the What guide links client-side, not as full document loads", () => {
    const html = render(<GamePage />, "/games/12345");
    for (const anchor of ["next-pitch", "pitch-type"]) {
      const tag = html.match(
        new RegExp(`<a [^>]*href="/models/guide#${anchor}"[^>]*>`),
      )?.[0];
      expect(tag).toBeDefined();
      expect(tag).toContain("ed-link");
      // The class owns the link colour; an inline colour would outrank its hover state.
      expect(tag).not.toMatch(/style="[^"]*(?<![-\w])color:/);
    }
  });

  it("renders the honest gated state in BOTH model slots (ADR-0014)", () => {
    // With no pitches loaded the at-bat is not settled, so both slots gate - and no request ever
    // fires from a static render.
    const html = render(<GamePage />, "/games/12345");
    expect(html).toContain("The next pitch, by outcome");
    expect(html).toContain("The next pitch, by type");
    // BOTH gated slots must say it - one occurrence would mean one of them stopped gating.
    expect(countOccurrences(html, "Awaiting a settled at-bat")).toBe(2);
    expect(html).not.toContain("pitch model pending");
  });

  it("renders the editorial colophon", () => {
    const html = render(<GamePage />, "/games/12345");
    expect(html).toContain("The Bullpen. Self-hosted, honestly scored.");
  });

  it("renders the invalid-id message when :id is non-numeric (e2e contract text)", () => {
    const html = render(<GamePage />, "/games/not-a-number");
    expect(html).toContain("Invalid game id.");
  });

  it("names the at-bat that is OVER once the live matchup has moved on, never keeping the old batter at bat", () => {
    const client = seededClient();
    client.setQueryData(
      ["games", "byId", GAME_ID],
      makeGame({
        currentMatchup: {
          batterId: 900001,
          pitcherId: 900002,
          batSide: "R",
          pitchHand: "R",
          atBatIndex: 2,
        },
      }),
    );
    client.setQueryData(["games", "pitches", GAME_ID], [makePitch()]);
    client.setQueryData(["players", "byId", 111], {
      id: 111,
      name: "Previous Batter",
      primaryPosition: "RF",
      active: true,
      team: "NYY",
    });
    const text = visibleText(render(<GamePage />, `/games/${GAME_ID}`, client));
    expect(text).not.toContain("Previous Batter, 1-0 so far.");
    expect(text).toMatch(
      /Previous Batter(&#x27;|')s at-bat ends after 1 pitch; the feed does not say how\./,
    );
  });

  it("renders the batted ball the SUMMARY names, not one scavenged from the pitch list", () => {
    // The source moved: the page reads game.mostRecentBattedBall rather than scanning its newest-50
    // pitch window, which found a ball only while it happened to still be inside.
    const client = seededClient();
    const bb = {
      batterId: 111,
      atBatIndex: 4,
      pitchNumber: 3,
      ts: "2026-08-04T23:10:00Z",
      event: "Field Out",
      bbType: "fly_ball",
      launchSpeedMph: 104.3,
      launchAngleDeg: 27,
      hitDistanceFt: 389,
      sprayAngleDeg: 18.4,
      stand: "L",
      baseState: 0,
      parkId: "TOR",
      outs: 2,
    };
    client.setQueryData(
      ["games", "byId", GAME_ID],
      makeGame({ mostRecentBattedBall: bb }),
    );
    client.setQueryData(["games", "pitches", GAME_ID], []);
    client.setQueryData(["players", "byId", 111], {
      id: 111,
      name: "Live Batter",
      primaryPosition: "RF",
      active: true,
      team: "NYY",
    });
    // The seeded key must equal what the page builds - spray, stand and baseState all come from the
    // summary record now, so a fabricated 0/"R" would no longer match and the seed would miss.
    const req: AllParksRequest = {
      launchSpeedMph: 104.3,
      launchAngleDeg: 27,
      sprayAngleDeg: 18.4,
      hitDistanceFt: 389,
      stand: "L",
      baseState: 0,
      outs: 2,
    };
    client.setQueryData(["parks", "all-parks", req], {
      modelName: "battedball_outcome",
      modelVersion: "v2",
      probHrByPark: { TOR: 0.61, BOS: 0.4 },
      carryFtByPark: { TOR: 401, BOS: 388 },
    });

    const html = render(<GamePage />, `/games/${GAME_ID}`, client);
    expect(html).toContain("Live Batter");
    // The summary's "Field Out" said as the account says it, with the ball's own physics.
    expect(visibleText(html)).toContain("Live Batter flies out.");
    expect(html).toContain("104.3");
    // The comparison is the model's, counted over the parks it scored: TOR .61 >= .5, BOS .40 not.
    expect(visibleText(html)).toContain("A home run in 1 of 2 parks");
    expect(html).not.toContain("Giancarlo Stanton");
  });

  it("cannot borrow another page's prediction from cache for a withheld ball", () => {
    // B1. `enabled: false` suppresses FETCHING, not cache reads, and the all-parks key hashes
    // STRUCTURALLY. /parks fires exactly CANONICAL_BBE_INPUT on mount - 110/28/0, stand R, and
    // estimateLandingDistanceFt(110,28) is exactly 400 - so a game page gated off with that same
    // placeholder subscribed to /parks' cache entry and rendered a REAL batted ball scored as a
    // 110 mph straightaway one, under a LIVE chip. Fixed by passing null: no key, nothing to
    // collide with. This test fails against the placeholder version.
    const client = seededClient();
    client.setQueryData(["parks", "all-parks", CANONICAL_BBE_INPUT], {
      modelName: "battedball_outcome",
      modelVersion: "v2",
      probHrByPark: { TOR: 0.99, BOS: 0.99 },
      carryFtByPark: { TOR: 460, BOS: 455 },
    });
    client.setQueryData(
      ["games", "byId", GAME_ID],
      makeGame({
        mostRecentBattedBall: {
          batterId: 111,
          atBatIndex: 4,
          pitchNumber: 3,
          ts: "2026-08-04T23:10:00Z",
          event: "Groundout",
          bbType: "ground_ball",
          launchSpeedMph: 71.7,
          launchAngleDeg: -12,
          hitDistanceFt: 9,
          sprayAngleDeg: null, // DECLINED - the comparison must be withheld
          stand: "R",
          baseState: 0,
          parkId: "TOR",
          outs: 1,
        },
      }),
    );
    client.setQueryData(["games", "pitches", GAME_ID], []);

    const html = render(<GamePage />, `/games/${GAME_ID}`, client);
    // Assert on the ALWAYS-RENDERED header, not the park rows: those live behind "Compare across
    // parks", so `not.toContain("460")` would have passed whatever the code did - the same collapse
    // trap that made the first B3 pin vacuous, recurring in the test written to fix B1.
    expect(visibleText(html)).not.toContain("A home run in");
    expect(visibleText(html)).toContain("withheld rather than estimated");
  });

  it("derives ONE band, so the sub-line and the distance metric cannot disagree", () => {
    // Q4. The metric used to re-derive its own band from the ROUNDED angle while the sub-line used
    // the descriptor, so a raw 9.6 degrees read "Ground ball" in one place and "Line drive" in the
    // other - and a present bbType made them disagree by SOURCE too. The earlier version of this
    // pin fed `band` straight to the explorer and therefore never exercised this computation at
    // all: it was vacuous against a page-side change.
    const client = seededClient();
    const bb = {
      batterId: 111,
      atBatIndex: 4,
      pitchNumber: 3,
      ts: "2026-08-04T23:10:00Z",
      event: "Single",
      bbType: "line_drive", // Statcast says line drive...
      launchSpeedMph: 96.2,
      launchAngleDeg: 9.6, // ...while a raw-angle band would say ground ball
      hitDistanceFt: 212,
      sprayAngleDeg: 12.1,
      stand: "R",
      baseState: 0,
      parkId: "TOR",
      outs: 1,
    };
    client.setQueryData(
      ["games", "byId", GAME_ID],
      makeGame({ mostRecentBattedBall: bb }),
    );
    client.setQueryData(["games", "pitches", GAME_ID], []);
    client.setQueryData(["players", "byId", 111], {
      id: 111,
      name: "Live Batter",
      primaryPosition: "RF",
      active: true,
      team: "NYY",
    });
    client.setQueryData(
      [
        "parks",
        "all-parks",
        {
          launchSpeedMph: 96.2,
          launchAngleDeg: 9.6,
          sprayAngleDeg: 12.1,
          hitDistanceFt: 212,
          stand: "R",
          baseState: 0,
          outs: 1,
        },
      ],
      {
        modelName: "battedball_outcome",
        modelVersion: "v2",
        probHrByPark: { TOR: 0.02 },
        carryFtByPark: { TOR: 215 },
      },
    );

    const text = visibleText(render(<GamePage />, `/games/${GAME_ID}`, client));
    // Statcast's classification wins over a raw-angle band. The editorial page prints the
    // descriptor once (the physics line), from ONE derivation; a raw-angle re-derivation would say
    // "Ground ball" for this 9.6-degree liner.
    expect(text).toContain("Line drive, 96.2 mph");
    expect(text).not.toMatch(/ground ball/i);
  });

  it("captions each unearned state as what it is, not as an absence of baseball", () => {
    // The caption chain gained three states, none of them previously pinned - in a file whose own
    // comment is titled "THE REGRESSION THIS FILE MISSED" about a caption that outlived its data.
    const bb = {
      batterId: 111,
      atBatIndex: 4,
      pitchNumber: 3,
      ts: "2026-08-04T23:10:00Z",
      event: "Single",
      bbType: "line_drive",
      launchSpeedMph: 96.2,
      launchAngleDeg: 12,
      hitDistanceFt: 212,
      sprayAngleDeg: 12.1,
      stand: "R",
      baseState: 0,
      parkId: "TOR",
      outs: 1,
    };
    // In flight: the happy path. Every new ball re-keys the query, so this is what a viewer sees
    // for the moment after contact - it must not say no ball has been put in play.
    const c1 = seededClient();
    c1.setQueryData(
      ["games", "byId", GAME_ID],
      makeGame({ mostRecentBattedBall: bb }),
    );
    c1.setQueryData(["games", "pitches", GAME_ID], []);
    const inFlight = visibleText(render(<GamePage />, `/games/${GAME_ID}`, c1));
    expect(inFlight).toContain("Scoring this batted ball");
    expect(inFlight).not.toContain("No ball has been put in play");
    expect(inFlight).not.toContain("A home run in"); // nothing scored is claimed while in flight

    // Withheld: spray declined, so the comparison is refused rather than estimated.
    const c2 = seededClient();
    c2.setQueryData(
      ["games", "byId", GAME_ID],
      makeGame({ mostRecentBattedBall: { ...bb, sprayAngleDeg: null } }),
    );
    c2.setQueryData(["games", "pitches", GAME_ID], []);
    const withheld = visibleText(render(<GamePage />, `/games/${GAME_ID}`, c2));
    expect(withheld).toContain("withheld rather than estimated");
    expect(withheld).not.toContain("No ball has been put in play");
  });

  it("never promises a static example it no longer renders", () => {
    // THE REGRESSION THIS FILE MISSED. Retiring the fixture removed the card and the
    // "MODEL EXAMPLE" meta, but left the caption saying "This is a static example of the per-park
    // HR model" - describing a thing that was no longer on the page. The existing assertions all
    // passed, because they pinned what I remembered to check (no Stanton, no MODEL EXAMPLE) and
    // never asserted the sentence I forgot to delete.
    const client = seededClient();
    client.setQueryData(
      ["games", "byId", GAME_ID],
      makeGame({ mostRecentBattedBall: null }),
    );
    client.setQueryData(["games", "pitches", GAME_ID], []);
    const html = render(<GamePage />, `/games/${GAME_ID}`, client);
    expect(html).not.toMatch(/static example/i);
    expect(html).not.toMatch(/not this game/i);
  });

  it("says a FINISHED game had no ball in play, without promising more baseball", () => {
    // "yet" is a claim about the future. A completed game has no more at-bats.
    const client = seededClient();
    client.setQueryData(
      ["games", "byId", GAME_ID],
      makeGame({ mostRecentBattedBall: null, status: "COMPLETED" }),
    );
    client.setQueryData(["games", "pitches", GAME_ID], []);
    const html = render(<GamePage />, `/games/${GAME_ID}`, client);
    expect(html).toContain("No ball was put in play in this game.");
    expect(html).not.toContain("in this game yet");
  });

  it("shows NO card at all when the game has had no ball in play", () => {
    // The fixture is retired from this page. A labelled static example was defensible while no real
    // batted ball could ever render here; once one can, a Stanton card on a live game page is the
    // fixtures-presented-as-content defect. The honest empty state is the caption and nothing else.
    const client = seededClient();
    client.setQueryData(
      ["games", "byId", GAME_ID],
      makeGame({ mostRecentBattedBall: null }),
    );
    client.setQueryData(["games", "pitches", GAME_ID], []);

    const html = render(<GamePage />, `/games/${GAME_ID}`, client);
    expect(html).toContain("No ball has been put in play in this game yet");
    expect(html).not.toContain("Giancarlo Stanton");
    expect(html).not.toContain("MODEL EXAMPLE");
    expect(visibleText(html)).not.toContain("A home run in");
  });
});

/**
 * Count occurrences of a string, so a pin on shared copy cannot be satisfied by the wrong panel.
 *
 * Both the Next-Pitch and Pitch-Type panels gate on the same condition and say so in the same
 * words - deliberately, since both describe the one upcoming pitch that does not exist. A bare
 * toContain therefore stopped pinning the next-pitch gate the moment the second panel shipped:
 * mutating next-pitch's copy alone left this file GREEN. Playwright's strict mode caught the twin
 * of this in e2e; vitest has no strict mode, so it went quiet instead of red.
 */
function countOccurrences(haystack: string, needle: string): number {
  return haystack.split(needle).length - 1;
}

describe("GamePage current batter (V031 live matchup)", () => {
  function seed(game: GameSummary, pitchOver: Partial<LivePitchRow> = {}) {
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    client.setQueryData(["games", "byId", GAME_ID], game);
    client.setQueryData(["games", "pitches", GAME_ID], [makePitch(pitchOver)]);
    // 111/200 = the LAST PITCH's batter/pitcher; 900001/900002 = who is standing in NOW.
    client.setQueryData(["players", "byId", 111], {
      id: 111,
      name: "Previous Batter",
      primaryPosition: "RF",
      active: true,
      team: "NYY",
    });
    client.setQueryData(["players", "byId", 200], {
      id: 200,
      name: "Previous Pitcher",
      primaryPosition: "P",
      active: true,
      team: "DET",
    });
    client.setQueryData(["players", "byId", 900001], {
      id: 900001,
      name: "Now Batting",
      primaryPosition: "1B",
      active: true,
      team: "NYY",
    });
    client.setQueryData(["players", "byId", 900002], {
      id: 900002,
      name: "Now Pitching",
      primaryPosition: "P",
      active: true,
      team: "DET",
    });
    return render(<GamePage />, `/games/${GAME_ID}`, client);
  }

  it("shows the batter STANDING IN, not the one who took the last pitch", () => {
    // The whole defect: at an at-bat rollover the page named the previous batter until the new
    // one's first pitch landed. With a live matchup it flips immediately.
    const html = seed(
      makeGame({
        currentMatchup: {
          batterId: 900001,
          pitcherId: 900002,
          batSide: "R",
          pitchHand: "R",
          atBatIndex: 2,
        },
      }),
    );
    expect(html).toContain("Now Batting");
    expect(html).toContain("Now Pitching");
    // The SITUATION names who is standing in now. The previous batter may appear in the account's
    // history (correctly, as an at-bat that is over), but never as the one at bat.
    expect(visibleText(html)).toMatch(/At bat\s+Now Batting/);
    expect(visibleText(html)).not.toMatch(/At bat\s+Previous Batter/);
    // The fixture row is at-bat 1 while the matchup is at-bat 2 - the PAST-TENSE case. The
    // prediction panel must be gated (never predicting for a finished at-bat) and the row-derived
    // Count/Outs must read as unknown rather than pairing the live batter with a dead count.
    expect(countOccurrences(html, "Awaiting a settled at-bat")).toBe(2);
    // Anchored to the STAT LABEL, so the pitch board's own count column cannot satisfy it.
    expect(visibleText(html)).toMatch(/Count\s+—/);
    expect(visibleText(html)).toMatch(/Outs\s+—/);
  });

  it("shows the POST-pitch count when the matchup describes the SAME at-bat", () => {
    // The row's balls/strikes is the PRE-pitch count; after a ball on 2-1 the current count
    // is 3-1. The tile must show postPitchCount, not the raw pre-pitch field.
    const html = seed(
      makeGame({
        currentMatchup: {
          batterId: 900001,
          pitcherId: 900002,
          batSide: "R",
          pitchHand: "R",
          atBatIndex: 1,
        },
      }),
      { balls: 2, strikes: 1, atBatIndex: 1, description: "ball" },
    );
    expect(html).toContain("Now Batting");
    expect(visibleText(html)).toMatch(/Count\s+3-1/);
  });

  it("resolves a switch hitter's side against the current pitcher, never showing a raw (S)", () => {
    // "S" is a roster fact, not a handedness for THIS matchup. nextPitchRequest already resolves
    // it against the pitcher before the model sees it; if the chyron printed the raw code the
    // header would claim (S) while the model input said L - the page contradicting itself.
    const html = seed(
      makeGame({
        currentMatchup: {
          batterId: 900001,
          pitcherId: 900002,
          batSide: "S",
          pitchHand: "R",
          atBatIndex: 1,
        },
      }),
      { atBatIndex: 1 },
    );
    const text = visibleText(html);
    expect(text).toMatch(/Now Batting\s*\(L\)/);
    expect(text).toMatch(/Now Pitching\s*\(R\)/);
    expect(text).not.toContain("(S)");
  });

  it("omits the side entirely when a switch hitter's pitcher hand is unknown", () => {
    // Storage yields '' for an unpopulated hand while isPopulated() gates only on the IDS, so
    // this row IS reachable. "S" with nothing to resolve against must print no parenthetical -
    // not an empty " ()", and not a guessed side.
    const html = seed(
      makeGame({
        currentMatchup: {
          batterId: 900001,
          pitcherId: 900002,
          batSide: "S",
          pitchHand: "",
          atBatIndex: 1,
        },
      }),
      { atBatIndex: 1 },
    );
    const text = visibleText(html);
    expect(text).toContain("Now Batting");
    expect(text).not.toContain("()");
    expect(text).not.toContain("(S)");
  });

  it("never attaches handedness to a name that has not resolved yet", () => {
    // This feature makes the pending window RECUR - every at-bat, pitching change and
    // half-inning re-keys the player lookups - so "- (R)" would be a routine sight, handedness
    // hanging off nobody.
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    client.setQueryData(
      ["games", "byId", GAME_ID],
      makeGame({
        currentMatchup: {
          batterId: 900007,
          pitcherId: 900008,
          batSide: "L",
          pitchHand: "R",
          atBatIndex: 1,
        },
      }),
    );
    client.setQueryData(["games", "pitches", GAME_ID], []);
    const text = visibleText(render(<GamePage />, `/games/${GAME_ID}`, client));
    expect(text).not.toContain("(L)");
    expect(text).not.toContain("(R)");
  });

  it("falls back to the last pitch when the feed carries no current play", () => {
    // Pre-game, between plays, final, and every pre-V031 row. The page must never render worse
    // than it did before this feature existed.
    const html = seed(makeGame({ currentMatchup: null }));
    expect(html).toContain("Previous Batter");
    expect(html).toContain("Previous Pitcher");
  });

  it("does not fabricate a name when neither source has one", () => {
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    client.setQueryData(["games", "byId", GAME_ID], makeGame());
    client.setQueryData(["games", "pitches", GAME_ID], []);
    const html = render(<GamePage />, `/games/${GAME_ID}`, client);
    // Assert against VISIBLE TEXT: Mantine injects a <style> block full of hex tokens
    // (chrome/gold color values), so a raw "#0" probe matches the stylesheet, not the page.
    // The hoisted strip is why this can probe for "#": raw markup carries Mantine's injected
    // color tokens (and quoting one here would itself trip lint:hex-codes).
    const text = visibleText(html);
    expect(text).not.toContain("Now Batting");
    expect(text).not.toMatch(/#\d/);
    expect(text).toContain("\u2014"); // the em-dash placeholder, not a fabricated id
  });
});

describe("GamePage live-state consumer (decision [194])", () => {
  function makeLiveState(
    overrides: Partial<LiveGameState> = {},
  ): LiveGameState {
    return {
      status: "IN_PROGRESS",
      matchup: {
        batterId: 900001,
        pitcherId: 900002,
        batSide: "R",
        pitchHand: "R",
        atBatIndex: 2,
      },
      upcomingPitch: {
        atBatIndex: 2,
        pitchNumber: 1,
        balls: 0,
        strikes: 0,
        outs: 1,
        baseState: 0,
      },
      lastPitchCursor: 101,
      prePrediction: {
        probabilities: {
          ball: 0.35,
          called_strike: 0.2,
          swinging_strike: 0.15,
          foul: 0.15,
          in_play: 0.15,
        },
        winner: "ball",
      },
      pitchTypePrediction: {
        probabilities: { FF: 0.6, SL: 0.2, CH: 0.15, CU: 0.05 },
        winner: "FF",
      },
      modelVersions: { pre: "v2", pitchType: "v1" },
      predictedAt: "2026-09-13T20:00:00Z",
      asOf: "2026-09-13T20:00:01Z",
      ...overrides,
    };
  }

  function seedLive(
    liveState: LiveGameState,
    pitchOverrides: Partial<LivePitchRow> = {},
  ) {
    const client = seededClient();
    client.setQueryData(
      ["games", "byId", GAME_ID],
      makeGame({
        currentMatchup: liveState.matchup,
      }),
    );
    client.setQueryData(
      ["games", "pitches", GAME_ID],
      [makePitch(pitchOverrides)],
    );
    client.setQueryData(["games", "live-state", GAME_ID], liveState);
    client.setQueryData(["players", "byId", 900001], {
      id: 900001,
      name: "Live Batter",
      primaryPosition: "1B",
      active: true,
      team: "NYY",
    });
    client.setQueryData(["players", "byId", 900002], {
      id: 900002,
      name: "Live Pitcher",
      primaryPosition: "P",
      active: true,
      team: "DET",
    });
    return render(<GamePage />, `/games/${GAME_ID}`, client);
  }

  it("fills count and outs from upcomingPitch when the row is past-tense", () => {
    const html = seedLive(makeLiveState());
    const text = visibleText(html);
    expect(text).toMatch(/Count\s+0-0/);
    expect(text).toMatch(/Outs\s+1/);
  });

  it("prefers fresh /live over the log even on the same at-bat", () => {
    // upcomingPitch key = 1*100+2 = 102, log cursor = 1 (default). 102 >= 2 = true,
    // so /live wins. The row's post-pitch count (1-0) happens to agree, but the tile
    // must show /live's value, not derive its own.
    const html = seedLive(
      makeLiveState({
        matchup: {
          batterId: 900001,
          pitcherId: 900002,
          batSide: "R",
          pitchHand: "R",
          atBatIndex: 1,
        },
        upcomingPitch: {
          atBatIndex: 1,
          pitchNumber: 2,
          balls: 1,
          strikes: 0,
          outs: 0,
          baseState: 0,
        },
      }),
      { balls: 0, strikes: 0, atBatIndex: 1, description: "ball" },
    );
    const text = visibleText(html);
    expect(text).toMatch(/Count\s+1-0/);
  });

  it("renders live-state predictions in the next-pitch panel", () => {
    const html = seedLive(makeLiveState());
    expect(html).toContain("Next-pitch outcome probabilities");
    expect(visibleText(html)).toContain("pitch_outcome_pre v2");
    expect(html).toContain("35.0%");
  });

  it("renders live-state predictions in the pitch-type panel", () => {
    const html = seedLive(makeLiveState());
    expect(visibleText(html)).toContain("pitch_type_pre v1");
    expect(html).toContain("60%");
    expect(html).toContain("Calibrated prior, not a call.");
  });

  it("falls back to em-dashes when live state has no upcomingPitch", () => {
    const html = seedLive(makeLiveState({ upcomingPitch: null }));
    const text = visibleText(html);
    expect(text).toMatch(/Count\s+\u2014/);
    expect(text).toMatch(/Outs\s+\u2014/);
  });

  it("uses upcomingPitch when its key is ahead of the log cursor", () => {
    // upcomingPitch key = 2*100+1 = 201, log cursor = 101 (pitch at-bat 1, pitch 1).
    // 201 >= 101+1 = true, so upcomingPitch wins.
    const html = seedLive(
      makeLiveState({
        upcomingPitch: {
          atBatIndex: 2,
          pitchNumber: 1,
          balls: 3,
          strikes: 2,
          outs: 2,
          baseState: 0,
        },
        lastPitchCursor: 101,
      }),
      { cursor: 101, atBatIndex: 1, pitchNumber: 1 },
    );
    const text = visibleText(html);
    expect(text).toMatch(/Count\s+3-2/);
    expect(text).toMatch(/Outs\s+2/);
  });

  it("em-dashes when upcomingPitch is stale (key behind log cursor)", () => {
    // upcomingPitch key = 1*100+2 = 102, log cursor = 103 (pitch at-bat 1, pitch 3).
    // 102 >= 103+1 = false, so upcomingPitch is stale.
    const html = seedLive(
      makeLiveState({
        matchup: {
          batterId: 900001,
          pitcherId: 900002,
          batSide: "R",
          pitchHand: "R",
          atBatIndex: 5,
        },
        upcomingPitch: {
          atBatIndex: 1,
          pitchNumber: 2,
          balls: 1,
          strikes: 0,
          outs: 0,
          baseState: 0,
        },
        lastPitchCursor: 101,
      }),
      { cursor: 103, atBatIndex: 1, pitchNumber: 3 },
    );
    const text = visibleText(html);
    expect(text).toMatch(/Count\s+\u2014/);
    expect(text).toMatch(/Outs\s+\u2014/);
  });

  it("log resolving after /live on the same at-bat does not regress the count", () => {
    // The reported symptom: /live shows 3-2 (correct), the log resolves with the
    // same at-bat's most recent pitch (a ball on 3-1), and the tile drops to 3-1
    // because it was showing mostRecent.balls raw. With postPitchCount the log
    // branch shows 3-2 (the advanced count) too, and /live takes priority when fresh.
    const html = seedLive(
      makeLiveState({
        matchup: {
          batterId: 900001,
          pitcherId: 900002,
          batSide: "R",
          pitchHand: "R",
          atBatIndex: 1,
        },
        upcomingPitch: {
          atBatIndex: 1,
          pitchNumber: 5,
          balls: 3,
          strikes: 2,
          outs: 1,
          baseState: 0,
        },
        lastPitchCursor: 104,
      }),
      // Log's newest pitch: ball on 3-1 (pre-pitch), cursor 104. Post-pitch = 3-2 (full count).
      {
        cursor: 104,
        atBatIndex: 1,
        pitchNumber: 4,
        balls: 2,
        strikes: 2,
        description: "ball",
      },
    );
    const text = visibleText(html);
    // /live upcomingPitch key = 1*100+5 = 105 >= 104+1 = true, so /live wins.
    expect(text).toMatch(/Count\s+3-2/);
    expect(text).toMatch(/Outs\s+1/);
  });

  it("shows post-pitch count from the log when /live has no upcomingPitch mid-at-bat", () => {
    // Mid-at-bat, matchup on the same at-bat (rowIsPastTense = false), no upcomingPitch.
    // The tile must show postPitchCount(mostRecent), not the raw pre-pitch count.
    const html = seedLive(
      makeLiveState({
        matchup: {
          batterId: 900001,
          pitcherId: 900002,
          batSide: "R",
          pitchHand: "R",
          atBatIndex: 1,
        },
        upcomingPitch: null,
      }),
      {
        cursor: 101,
        atBatIndex: 1,
        pitchNumber: 1,
        balls: 1,
        strikes: 0,
        description: "called_strike",
      },
    );
    const text = visibleText(html);
    expect(text).toMatch(/Count\s+1-1/);
  });

  it("em-dashes count when the log's pitch ended the at-bat (strikeout)", () => {
    // A swinging strike on 0-2: postPitchCount returns null (3 strikes = at-bat over).
    // The tile must em-dash, not show 0-2 or 0-3.
    const html = seedLive(
      makeLiveState({
        matchup: {
          batterId: 900001,
          pitcherId: 900002,
          batSide: "R",
          pitchHand: "R",
          atBatIndex: 1,
        },
        upcomingPitch: null,
      }),
      {
        cursor: 103,
        atBatIndex: 1,
        pitchNumber: 3,
        balls: 0,
        strikes: 2,
        description: "swinging_strike",
      },
    );
    const text = visibleText(html);
    expect(text).toMatch(/Count\s+\u2014/);
  });

  it("fresh /live shows 0-0 after a strikeout when the log alone would em-dash", () => {
    // The log's last pitch is strike three (at-bat over), so postPitchCount = null.
    // But /live already knows the NEXT batter is up with a 0-0 count. Fresh /live wins.
    const html = seedLive(
      makeLiveState({
        matchup: {
          batterId: 900001,
          pitcherId: 900002,
          batSide: "R",
          pitchHand: "R",
          atBatIndex: 2,
        },
        upcomingPitch: {
          atBatIndex: 2,
          pitchNumber: 1,
          balls: 0,
          strikes: 0,
          outs: 2,
          baseState: 0,
        },
        lastPitchCursor: 103,
      }),
      {
        cursor: 103,
        atBatIndex: 1,
        pitchNumber: 3,
        balls: 0,
        strikes: 2,
        description: "swinging_strike",
      },
    );
    const text = visibleText(html);
    // upcomingPitch key = 2*100+1 = 201 >= 103+1 = true.
    expect(text).toMatch(/Count\s+0-0/);
    expect(text).toMatch(/Outs\s+2/);
  });
});

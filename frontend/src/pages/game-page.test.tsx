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
import { MemoryRouter, Route, Routes } from "react-router";
import { describe, expect, it } from "vitest";

import { type GameSummary, type LivePitchRow } from "../api/games";
import { CANONICAL_BBE_INPUT, type AllParksRequest } from "../api/parks";
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
    expect(html).toContain("Field Out");
    expect(html).toContain("104.3");
    expect(html).toContain("LIVE BIP");
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
    expect(html).not.toContain("LIVE BIP");
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
    // Statcast's classification appears TWICE - once in the sub-line, once as the metric key -
    // and it is the same string by construction. A raw-angle re-derivation would print
    // "Line drive" in one place and "Ground ball" in the other; a rounded one, "Line drive" and
    // "Line drive" from two sources that can drift apart. Counting both is what pins the SHARING.
    expect(text.split("Line Drive").length - 1).toBe(2);
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
    expect(inFlight).toContain("SCORING"); // the chip must not say AWAITING either

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
    expect(html).not.toContain("LIVE BIP");
  });
});

/**
 * The visible text of a rendered fragment - what a reader would actually see.
 *
 * Assertions on this page MUST run against this rather than raw markup: Mantine injects a
 * stylesheet, and the live pitch board renders the same pitch's count in its own `Cnt` column, so
 * a bare `toContain("2-1")` matches the BOARD and passes even when the Count stat correctly
 * em-dashes. That is exactly how the first version of the past-tense assertion could not fail.
 *
 * Scanned rather than regex-stripped, deliberately. The obvious
 * `.replace(/<style[\s\S]*?<\/style>/g, "").replace(/<[^>]+>/g, " ")` chain is shaped like an
 * HTML sanitizer, and CodeQL flags it as one (js/incomplete-multi-character-sanitization, high) -
 * correctly in general, even though nothing untrusted reaches this helper and its output is only
 * ever asserted on. Rewriting removes the finding at its root instead of dismissing it. The scan
 * is also simply more accurate: it skips a <style> element's CONTENT rather than hoping a lazy
 * quantifier lines up, and drops attributes wholesale so no inline style= value can leak a token
 * into an assertion.
 *
 * A tag boundary emits one space, so adjacent elements stay separate tokens and label/value
 * assertions like /Count\s+—/ mean what they look like. Entity references are left ENCODED
 * (&#x27; stays &#x27;), which is why assertions here compare rendered words, not punctuation.
 */
function visibleText(html: string): string {
  const out: string[] = [];
  let i = 0;
  while (i < html.length) {
    const lt = html.indexOf("<", i);
    if (lt === -1) {
      out.push(html.slice(i));
      break;
    }
    out.push(html.slice(i, lt));
    const gt = html.indexOf(">", lt);
    if (gt === -1) break; // truncated final tag: nothing visible can follow it
    const name = html
      .slice(lt + 1, gt)
      .trim()
      .toLowerCase()
      .split(/[\s/]/)[0];
    if (name === "style" || name === "script") {
      // Skip the whole ELEMENT: its content is CSS/JS, never page text.
      const close = html.toLowerCase().indexOf(`</${name}`, gt);
      const closeEnd = close === -1 ? -1 : html.indexOf(">", close);
      i = closeEnd === -1 ? html.length : closeEnd + 1;
    } else {
      i = gt + 1;
    }
    out.push(" ");
  }
  return out.join("");
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
    expect(html).not.toContain("Previous Batter");
    // The fixture row is at-bat 1 while the matchup is at-bat 2 - the PAST-TENSE case. The
    // prediction panel must be gated (never predicting for a finished at-bat) and the row-derived
    // Count/Outs must read as unknown rather than pairing the live batter with a dead count.
    expect(html).toContain("Awaiting a settled at-bat");
    // Anchored to the STAT LABEL, so the pitch board's own count column cannot satisfy it.
    expect(visibleText(html)).toMatch(/Count\s+—/);
    expect(visibleText(html)).toMatch(/Outs\s+—/);
  });

  it("uses the row's count when the matchup describes the SAME at-bat", () => {
    // The agreeing-index path, which no page-level test covered: the live batter is shown AND
    // the row's count still describes him, so it must render rather than em-dash.
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
      { balls: 2, strikes: 1, atBatIndex: 1 },
    );
    expect(html).toContain("Now Batting");
    expect(visibleText(html)).toMatch(/Count\s+2-1/);
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

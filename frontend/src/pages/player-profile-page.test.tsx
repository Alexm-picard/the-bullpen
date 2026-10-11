/**
 * /players/:id on the [195] identity (SPEC-players, layout B "Magazine spread").
 *
 * Renders the whole page with the per-player query caches seeded synchronously (setQueryData), so
 * each test drives one data shape through the real components. Assertions are SCOPED to the region
 * they are about (pins-decay rule), and read the page through visibleText(), never raw markup.
 */
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter, Route, Routes } from "react-router";
import { describe, expect, it } from "vitest";

import {
  PlayerLookupError,
  type ArsenalPitch,
  type BattedBallRow,
  type CalibrationBin,
  type PlayerPredictionRow,
  type PlayerSearchResult,
} from "../api/players";
import { theme } from "../design/theme";
import { visibleText } from "../test-support/visible-text";

import PlayerProfilePage from "./player-profile-page";

const SKUBAL: PlayerSearchResult = {
  id: 669373,
  name: "Tarik Skubal",
  primaryPosition: "P",
  active: true,
  team: "DET",
};
const JUDGE: PlayerSearchResult = {
  id: 592450,
  name: "Aaron Judge",
  primaryPosition: "RF",
  active: true,
  team: "NYY",
};
const OHTANI: PlayerSearchResult = {
  id: 660271,
  name: "Shohei Ohtani",
  primaryPosition: "TWP",
  active: true,
  team: "LAD",
};

const ARSENAL: ArsenalPitch[] = [
  {
    pitchType: "FF",
    count: 4548,
    usagePct: 0.32,
    veloMinMph: 93.1,
    veloAvgMph: 96.6,
    veloMaxMph: 99.8,
  },
  {
    pitchType: "CH",
    count: 3411,
    usagePct: 0.24,
    veloMinMph: 84.0,
    veloAvgMph: 87.4,
    veloMaxMph: 89.9,
  },
  {
    pitchType: "SI",
    count: 2274,
    usagePct: 0.16,
    veloMinMph: 92.4,
    veloAvgMph: 95.7,
    veloMaxMph: 98.7,
  },
  {
    pitchType: "SL",
    count: 2132,
    usagePct: 0.15,
    veloMinMph: 86.1,
    veloAvgMph: 88.9,
    veloMaxMph: 91.2,
  },
  {
    pitchType: "CU",
    count: 1137,
    usagePct: 0.08,
    veloMinMph: 77.5,
    veloAvgMph: 80.2,
    veloMaxMph: 83.4,
  },
  {
    pitchType: "FC",
    count: 710,
    usagePct: 0.05,
    veloMinMph: 88.2,
    veloAvgMph: 90.6,
    veloMaxMph: 92.5,
  },
];

function pred(i: number, agreed: boolean): PlayerPredictionRow {
  return {
    requestAt: `2026-10-10T23:${String(10 + i).padStart(2, "0")}:00Z`,
    modelName: "pitch_outcome_pre",
    modelVersion: "v2",
    role: "champion",
    winnerClass: "ball",
    winnerProb: 0.41,
    observedOutcome: agreed ? "ball" : "in_play",
    agreed,
  };
}
const UNSETTLED: PlayerPredictionRow = {
  ...pred(40, false),
  observedOutcome: null,
  agreed: null,
};
// 38 settled (16 true) + one unsettled row that must not count.
const PREDS: PlayerPredictionRow[] = [
  ...Array.from({ length: 38 }, (_, i) => pred(i, i < 16)),
  UNSETTLED,
];

const BINS: CalibrationBin[] = [
  { binStart: 0.3, binEnd: 0.4, predicted: 0.35, actual: null, n: 88 },
  { binStart: 0.4, binEnd: 0.5, predicted: 0.45, actual: null, n: 141 },
];

const YEAR = new Date().getUTCFullYear();

function bb(over: Partial<BattedBallRow> = {}): BattedBallRow {
  return {
    gameDate: `${YEAR}-03-26`,
    events: "field_out",
    bbType: "fly_ball",
    launchSpeedMph: 95,
    launchAngleDeg: 25,
    hitDistanceFt: 350,
    parkId: "NYY",
    stand: "R",
    ...over,
  };
}
const BALLS: BattedBallRow[] = [
  bb({
    gameDate: `${YEAR}-10-04`,
    events: "home_run",
    launchSpeedMph: 117.4,
    launchAngleDeg: 27,
    hitDistanceFt: 468,
  }),
  ...Array.from({ length: 11 }, () =>
    bb({
      bbType: "ground_ball",
      launchSpeedMph: 88,
      launchAngleDeg: -5,
      hitDistanceFt: null,
    }),
  ),
  ...Array.from({ length: 8 }, () => bb({ launchSpeedMph: 101 })),
  bb({ launchSpeedMph: null, launchAngleDeg: null }),
];

type Seed = {
  player?: PlayerSearchResult;
  playerError?: PlayerLookupError;
  arsenal?: ArsenalPitch[];
  balls?: BattedBallRow[];
  preds?: PlayerPredictionRow[];
  bins?: CalibrationBin[];
};

function render(path: string, seed: Seed = {}): string {
  // retryOnMount off so a seeded error state renders as the error it is (not an optimistic refetch).
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, retryOnMount: false } },
  });
  const id = Number(path.split("/").pop());
  if (seed.player) client.setQueryData(["players", "byId", id], seed.player);
  if (seed.playerError) {
    client
      .getQueryCache()
      .build(client, { queryKey: ["players", "byId", id] })
      .setState({ status: "error", error: seed.playerError });
  }
  if (seed.arsenal)
    client.setQueryData(["players", "arsenal", id], seed.arsenal);
  if (seed.balls) {
    client.setQueryData(
      ["players", "battedBalls", id, "", "", "", "", 200],
      seed.balls,
    );
  }
  if (seed.preds)
    client.setQueryData(["players", "predictions", id, 50], seed.preds);
  if (seed.bins) {
    client.setQueryData(
      ["players", "calibration", id, "pitch_outcome_pre"],
      seed.bins,
    );
  }
  return renderToStaticMarkup(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <MantineProvider theme={theme}>
          <Routes>
            <Route path="/players/:id" element={<PlayerProfilePage />} />
          </Routes>
        </MantineProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** The markup of the element whose opening tag carries `marker`, up to its matching close. */
function region(html: string, marker: string): string {
  const at = html.indexOf(marker);
  if (at < 0) return "";
  const open = html.lastIndexOf("<", at);
  const tag = /^<([a-z0-9]+)/.exec(html.slice(open))?.[1] ?? "div";
  let depth = 0;
  const re = new RegExp(`<(/?)${tag}\\b[^>]*>`, "g");
  re.lastIndex = open;
  for (let m = re.exec(html); m; m = re.exec(html)) {
    depth += m[1] ? -1 : 1;
    if (depth === 0) return html.slice(open, re.lastIndex);
  }
  return html.slice(open);
}

const PITCHER_SEED: Seed = {
  player: SKUBAL,
  arsenal: ARSENAL,
  preds: PREDS,
  bins: BINS,
};

describe("PlayerProfilePage - pitcher file", () => {
  const html = render("/players/669373", PITCHER_SEED);
  const text = visibleText(html);

  it("leads with the name, the role/place slug and the generated dek", () => {
    expect(text).toContain("Pitcher, Detroit");
    expect(text).toContain("Tarik Skubal");
    expect(text).toContain(
      "Six pitches on file. The four-seam leads at 32% and runs 93 to 100 mph; the changeup, at 24%, sits nine miles an hour under it.",
    );
  });

  it("every numeral in the dek is printed elsewhere on the page (the page is the oracle)", () => {
    const dek = visibleText(region(html, 'class="ed-dek'));
    expect(dek).toContain("Six pitches");
    const rest = text.replace(dek.trim(), " ");
    const pageNums = [...rest.matchAll(/\d*\.?\d+/g)].map((m) => Number(m[0]));
    const dekNums = [...dek.matchAll(/\d+/g)].map((m) => Number(m[0]));
    expect(dekNums).toEqual([32, 93, 100, 24]);
    for (const d of dekNums) {
      const shown = pageNums.some(
        (p) => Math.round(p) === d || Math.round(p * 100) === d,
      );
      expect(shown, `dek number ${d} is not printed elsewhere`).toBe(true);
    }
    // "Six" is the count of ranked rows in the aside.
    const aside = region(html, 'aria-labelledby="pl-mix-title"');
    expect(aside.match(/class="ed-agate__row"/g)).toHaveLength(6);
    // A populated aside never carries its empty-state sentence.
    expect(visibleText(aside)).not.toContain("No pitches on file");
  });

  it("figures: pitches tracked, pitch types, hardest - never the came-true count", () => {
    const figs = visibleText(region(html, 'class="ed-front__figs'));
    expect(figs).toContain("14,212");
    expect(figs).toContain("Pitch types on file");
    expect(figs).toContain("99.8");
    expect(figs).toContain("Four-seam, the top of its range.");
    expect(figs).not.toContain("came true");
  });

  it("demotes 'came true' to a register line: a count over SETTLED calls, never a percentage", () => {
    const reg = visibleText(region(html, 'data-testid="calls-register"'));
    expect(reg).toContain("16 of his last 38 settled calls came true.");
    expect(reg).toContain("a small sample");
    expect(reg).not.toMatch(/\d%/);
  });

  it("draws velocity by pitch on the fixed scale, with every value printed", () => {
    const velo = visibleText(region(html, 'id="velocity"'));
    expect(velo).toContain("How hard, pitch by pitch");
    expect(velo).toContain("96.6");
    expect(velo).toContain("93.1 to 99.8");
    expect(velo).toContain("4,548 pitches");
  });

  it("shows where his calls landed as CONFIDENCE ONLY, linking to the model-wide record", () => {
    const calls = region(html, 'id="calls"');
    expect(visibleText(calls)).toContain(
      "pitch_outcome_pre, 229 calls, by confidence",
    );
    const note = visibleText(region(calls, 'data-testid="landed-note"'));
    expect(note).toContain(
      "How confident the model was, not how often it was right.",
    );
    expect(calls).toContain('href="/accuracy"');
    // No reliability chart: no diagonal, no observed axis, no observed column.
    expect(calls).not.toContain("acc-chart");
    expect(calls).not.toContain(">Observed<");
  });

  it("keeps team colour out of the profile entirely", () => {
    expect(html).not.toContain("pl-mark");
  });

  it("does not render a contact section for a pitcher", () => {
    expect(html).not.toContain('id="contact"');
  });
});

describe("PlayerProfilePage - confidence only, whatever the payload says", () => {
  it("never draws an observed line or diagonal even when bins carry an actual rate", () => {
    const withActual = BINS.map((b) => ({ ...b, actual: 0.4 }));
    const a = region(
      render("/players/669373", { ...PITCHER_SEED, bins: BINS }),
      'id="calls"',
    );
    const b = region(
      render("/players/669373", { ...PITCHER_SEED, bins: withActual }),
      'id="calls"',
    );
    expect(b).toBe(a);
  });
});

describe("PlayerProfilePage - batter file", () => {
  const html = render("/players/592450", {
    player: JUDGE,
    balls: BALLS,
    preds: [],
    bins: [],
  });
  const text = visibleText(html);

  it("routes a right fielder to the contact file with a short-window dek", () => {
    expect(text).toContain("Right field, New York (AL)");
    expect(text).toContain(
      "His 21 balls in play on file reach back to March 26. The hardest left the bat at 117.4 mph, a home run; the most common were ground balls, at 52%.",
    );
    expect(html).toContain('id="contact"');
    expect(html).not.toContain('id="velocity"');
  });

  it("ranks his hit types in the aside under a 'Type' header, with no empty-state sentence", () => {
    const aside = region(html, 'aria-labelledby="pl-bb-title"');
    expect(aside).toContain(">Type</th>");
    expect(visibleText(aside)).toContain("Ground ball");
    expect(visibleText(aside)).not.toContain("No balls in play on file.");
  });

  it("figures name their window and skip untracked balls", () => {
    const figs = visibleText(region(html, 'class="ed-front__figs'));
    expect(figs).toContain("117.4");
    expect(figs).toContain("468");
    expect(figs).toContain("Of his 21 balls in play on file.");
    // 9 of the 20 balls with a tracked speed are 95+ (the untracked one is not a 0).
    expect(figs).toContain("45%");
  });

  it("counts the balls it cannot place instead of plotting them at 0", () => {
    const contact = visibleText(region(html, 'id="contact"'));
    expect(contact).toContain(
      "1 ball has no tracked speed or angle and is left off.",
    );
    expect(contact).toContain("The newest 12 of 21.");
  });

  it("renders the first-class empty calls states", () => {
    expect(text).toContain("No settled calls for him yet.");
    expect(text).toContain("No calls logged for him yet.");
    expect(html).not.toContain("calls-register");
  });
});

describe("PlayerProfilePage - two-way player", () => {
  it("shows both heroes, pitcher first", () => {
    const html = render("/players/660271", {
      player: OHTANI,
      arsenal: ARSENAL,
      balls: BALLS,
    });
    const v = html.indexOf('id="velocity"');
    const c = html.indexOf('id="contact"');
    expect(v).toBeGreaterThan(-1);
    expect(c).toBeGreaterThan(v);
    expect(visibleText(html)).toContain("Two-way player, Los Angeles (NL)");
  });
});

describe("PlayerProfilePage - loading and missing", () => {
  it("pre-sizes static shells while the player loads (no invented name)", () => {
    const html = render("/players/669373");
    expect(html).toContain('aria-busy="true"');
    expect(html).toContain("ed-shell");
    expect(visibleText(html)).toContain("Loading the player");
  });

  it("a 404 says so and points back to the index", () => {
    const html = render("/players/1", {
      playerError: new PlayerLookupError(404, "player not found"),
    });
    const text = visibleText(html);
    expect(text).toContain("No player with that ID");
    expect(text).toContain("Nothing on file for Statcast ID 1.");
    expect(html).toContain('href="/players"');
  });
});

describe("PlayerProfilePage - showcase file (/players/:slug)", () => {
  const html = render("/players/judge_aaron");
  const text = visibleText(html);

  it("opens with the D4 banner as a note and labels every section as showcase", () => {
    const banner = region(html, 'role="note"');
    expect(visibleText(banner)).toContain(
      "A demonstration matchup with illustrative values, not live scouting.",
    );
    expect(text).toContain("Showcase file");
    expect(text).toContain("Matchup (showcase)");
    expect(text).toContain("The pitcher (showcase)");
    expect(text).toContain("The batter (showcase)");
    expect(text).toContain("Key notes (showcase)");
  });

  it("links both players to their LIVE numeric files", () => {
    expect(html).toContain('href="/players/592450"');
    expect(html).toContain('href="/players/669373"');
  });

  it("carries no live section, no colour mark, and no unmeasured model claim", () => {
    expect(text).not.toContain("What the models said");
    expect(html).not.toContain("pl-mark");
    expect(text).not.toContain("ECE");
    expect(text.toLowerCase()).not.toContain("calibration");
  });

  it("an unknown slug falls back to the Judge-Skubal showcase", () => {
    expect(visibleText(render("/players/nobody"))).toContain(
      "Aaron Judge against Tarik Skubal",
    );
  });
});

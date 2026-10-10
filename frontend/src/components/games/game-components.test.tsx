/**
 * SSR tests for the game page's editorial pieces (SPEC-game §12). Oracles are literal: the copy and
 * numbers a reader would see, written out by hand.
 */
import { MantineProvider } from "@mantine/core";
import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";

import { GameApiError } from "../../api/games";
import { theme } from "../../design/theme";

import { BasesGlyph } from "./bases-glyph";
import {
  basesLabel,
  basesPhrase,
  lineScoreFrom,
  parkLines,
  hrParkCount,
} from "./game-account";
import { LineScore } from "./line-score";
import { OutcomeAgate } from "./outcome-agate";
import { ParkAgate } from "./park-agate";
import { PitchTypeSection } from "./pitch-type-section";
import { PhoneScoreStrip, ScorecardRail, type Scorecard } from "./scorecard";

function html(node: ReactNode): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>
      <MemoryRouter>{node}</MemoryRouter>
    </MantineProvider>,
  );
}

const PRED = {
  probabilities: {
    ball: 0.36,
    called_strike: 0.12,
    swinging_strike: 0.15,
    foul: 0.21,
    in_play: 0.16,
  },
  winner: "ball",
  modelName: "pitch_outcome_pre",
  modelVersion: "v2",
};

describe("OutcomeAgate ([180])", () => {
  it("keeps the fixed class order and the calibration caption", () => {
    const out = html(
      <OutcomeAgate prediction={PRED} isLoading={false} error={null} enabled />,
    );
    const order = [
      "Ball",
      "Called strike",
      "Swinging strike",
      "Foul",
      "In play",
    ];
    const positions = order.map((l) => out.indexOf(`<td>${l}</td>`));
    expect(positions.every((p) => p > 0)).toBe(true);
    expect([...positions].sort((a, b) => a - b)).toEqual(positions);
    expect(out).toContain(
      "Calibrated pre-pitch estimate. It passes calibration (ECE &lt; 0.02); it is not an accuracy claim.",
    );
    expect(out).toContain("pitch_outcome_pre v2");
  });

  it("emphasises only the most probable row, by weight class - never the accent", () => {
    const out = html(
      <OutcomeAgate prediction={PRED} isLoading={false} error={null} enabled />,
    );
    const rows = out.split("<tr").slice(2); // drop thead row
    const top = rows.filter((r) => r.includes("ed-agate__row--top"));
    expect(top).toHaveLength(1);
    expect(top[0]).toContain("<td>Ball</td>");
    expect(out).not.toContain("--ed-accent");
  });

  it("reads gated, unpromoted (503) and degraded states as what they are", () => {
    expect(
      html(
        <OutcomeAgate
          prediction={undefined}
          isLoading={false}
          error={null}
          enabled={false}
        />,
      ),
    ).toContain("Awaiting a settled at-bat.");
    const unpromoted = html(
      <OutcomeAgate
        prediction={undefined}
        isLoading={false}
        error={new GameApiError(503, "")}
        enabled
      />,
    );
    expect(unpromoted).toContain('data-testid="next-pitch-unpromoted"');
    expect(unpromoted).toContain("not yet promoted");
    expect(
      html(
        <OutcomeAgate
          prediction={undefined}
          isLoading={false}
          error={new Error("boom")}
          enabled
        />,
      ),
    ).toContain("Next-pitch estimate unavailable right now.");
  });
});

describe("PitchTypeSection ([183])", () => {
  it("renders the server's reason verbatim on a 503", () => {
    const out = html(
      <PitchTypeSection
        prior={undefined}
        isLoading={false}
        error={new GameApiError(503, "career prior snapshot is stale")}
        enabled
      />,
    );
    expect(out).toContain(
      "Pitch-type prior unavailable: career prior snapshot is stale",
    );
  });

  it("claims no distribution while gated", () => {
    const out = html(
      <PitchTypeSection
        prior={undefined}
        isLoading={false}
        error={null}
        enabled={false}
      />,
    );
    expect(out).toContain("Awaiting a settled at-bat.");
    expect(out).not.toContain("%");
  });

  it("ranks the prior, captions it as a prior, and names the sample it came from", () => {
    const out = html(
      <PitchTypeSection
        prior={{
          probabilities: { SL: 0.24, FF: 0.38, CH: 0.14 },
          modelName: "pitch_type_pre",
          servingVersion: "v1",
          priorPitches: 2431,
        }}
        isLoading={false}
        error={null}
        enabled
      />,
    );
    expect(out.indexOf("Four-seam")).toBeLessThan(out.indexOf("Slider"));
    expect(out.indexOf("Slider")).toBeLessThan(out.indexOf("Changeup"));
    expect(out).toContain("Calibrated prior, not a call.");
    expect(out).toContain("computed over 2,431 career pitches");
  });
});

describe("parkLines + ParkAgate", () => {
  const pred = {
    probHrByPark: { CWS: 0.31, COL: 0.74, NYY: 0.5 },
    carryFtByPark: { CWS: 371, COL: 402.4, NYY: 384 },
  };

  it("reads HR at the 0.5 threshold, sorts by P(HR), and marks tonight's park", () => {
    const lines = parkLines(pred, "CWS", 360);
    expect(lines.map((l) => [l.id, l.hr, l.here, l.carryFt])).toEqual([
      ["COL", true, false, 402],
      ["NYY", true, false, 384],
      ["CWS", false, true, 371],
    ]);
    expect(hrParkCount(lines)).toBe(2);
  });

  it("falls back to the ball's own distance, and says so, when the champion serves no carry", () => {
    const lines = parkLines({ probHrByPark: { CWS: 0.1 } }, "CWS", 359.6);
    expect(lines[0]!.carryFt).toBe(360);
    const out = html(<ParkAgate lines={lines} carryIsModel={false} />);
    expect(out).toContain("this champion serves no per-park carry");
    expect(out).toContain("The model reports no per-park uncertainty");
    expect(out).not.toMatch(/±/);
  });
});

describe("LineScore", () => {
  it("prints a dash for an unknown inning, blanks for innings not played, and marks the current one", () => {
    const data = lineScoreFrom(
      [
        {
          ...pitchBase,
          atBatIndex: 0,
          cursor: 1,
          inning: 1,
        },
        { ...pitchBase, atBatIndex: 9, cursor: 901, inning: 3, awayScore: 1 },
      ],
      { status: "IN_PROGRESS", inning: 3, awayScore: 1, homeScore: 0 },
    )!;
    const out = html(<LineScore data={data} awayTeam="CLE" homeTeam="CWS" />);
    expect(out).toContain("not logged");
    expect(out).toContain("not played");
    expect(out).toMatch(/<th scope="col" aria-current="true">3<\/th>/);
    expect(out).toContain(
      "Runs only, worked out from the score on each logged pitch.",
    );
  });
});

describe("Scorecard rail + phone strip", () => {
  const card: Scorecard = {
    away: "CLE",
    home: "CWS",
    awayScore: 3,
    homeScore: 2,
    inningLabel: "6th inning",
    situation: { count: "1-2", outs: "1", bases: 1 },
    pitchCount: { label: "Pitches", value: 84 },
    who: "Pitching Smith",
    asOf: "7:42:07 PM ET",
  };

  it("carries the score, the situation and the way back", () => {
    const out = html(<ScorecardRail card={card} />);
    expect(out).toContain('aria-label="Scorecard"');
    expect(out).toContain('aria-label="Runner on first"');
    expect(out).toContain('href="#game-top"');
    expect(out).toContain("Back to the top");
  });

  it("prints no score rather than 0-0 before a game starts", () => {
    const out = html(
      <ScorecardRail card={{ ...card, awayScore: null, homeScore: null }} />,
    );
    expect(out).not.toMatch(/<td>0<\/td>/);
  });

  it("keeps the phone strip inert (unfocusable, unread) until shown", () => {
    expect(html(<PhoneScoreStrip card={card} shown={false} />)).toContain(
      "inert",
    );
    expect(html(<PhoneScoreStrip card={card} shown />)).not.toContain("inert");
  });
});

describe("BasesGlyph", () => {
  it.each([
    [0, "Bases empty"],
    [1, "Runner on first"],
    [5, "Runners on first and third"],
    [7, "Bases loaded"],
  ])("labels mask %i as %s", (mask, label) => {
    expect(basesLabel(mask)).toBe(label);
    expect(basesPhrase(mask)).toBe(
      {
        0: "the bases empty",
        1: "a runner on first",
        5: "runners on first and third",
        7: "the bases loaded",
      }[mask],
    );
    expect(html(<BasesGlyph mask={mask} />)).toContain(`aria-label="${label}"`);
  });
});

const pitchBase = {
  gameId: 1,
  atBatIndex: 0,
  pitchNumber: 1,
  cursor: 1,
  ingestedAt: "2026-10-08T23:41:00Z",
  pitcherId: 20,
  batterId: 10,
  description: "ball",
  pitchType: "FF",
  releaseSpeedMph: 95,
  plateXIn: 0,
  plateZIn: 2,
  balls: 0,
  strikes: 0,
  outs: 0,
  inning: 1,
  homeScore: 0,
  awayScore: 0,
  pitcherThrows: "R",
  batterStand: "L",
  baseState: 0,
  parkId: "CWS",
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

/**
 * The account's derivations (SPEC-game §6, §12). Oracles are literal expectations written out by
 * hand - never recomputed with the module under test. Each line-score case names the mutation it
 * exists to catch.
 */
import { describe, expect, it } from "vitest";

import type { LivePitchRow } from "../../api/games";

import {
  atBatSentence,
  atBatsFrom,
  battedBallLine,
  eventPhrase,
  foldAtBats,
  isInferred,
  lineScoreFrom,
  modelGave,
  type AtBat,
} from "./game-account";

function p(over: Partial<LivePitchRow> = {}): LivePitchRow {
  const atBatIndex = over.atBatIndex ?? 0;
  const pitchNumber = over.pitchNumber ?? 1;
  return {
    gameId: 1,
    atBatIndex,
    pitchNumber,
    cursor: over.cursor ?? atBatIndex * 100 + pitchNumber,
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
    ...over,
  };
}

const LIVE = { status: "IN_PROGRESS" } as const;

describe("lineScoreFrom", () => {
  it("returns null before any pitch is logged", () => {
    expect(
      lineScoreFrom([], { ...LIVE, inning: 0, awayScore: 0, homeScore: 0 }),
    ).toBeNull();
  });

  it("attributes runs to the inning they scored in (row scores are the state ENTERING the at-bat)", () => {
    // CLE scores 2 in the 1st: the rows of the 1st still read 0-0 (entering state); the first row
    // of the 2nd reads 2-0. Mutation target: differencing the LAST row of an inning instead of the
    // next inning's first row would put those runs in the 2nd.
    const pitches = [
      p({ atBatIndex: 0, inning: 1 }),
      p({ atBatIndex: 1, inning: 1 }),
      p({ atBatIndex: 2, inning: 2, awayScore: 2 }),
      p({ atBatIndex: 3, inning: 3, awayScore: 2, homeScore: 1 }),
    ];
    const ls = lineScoreFrom(pitches, {
      ...LIVE,
      inning: 3,
      awayScore: 2,
      homeScore: 1,
    })!;
    expect(ls.away).toEqual([2, 0, 0]);
    expect(ls.home).toEqual([0, 1, 0]);
    expect(ls.played).toBe(3);
    expect(ls.innings).toBe(9);
    expect(ls.currentInning).toBe(3);
  });

  it("opens each inning at its FIRST logged row, so runs scored mid-inning stay in that inning", () => {
    // Two runs in the 1st, one per at-bat: rows read 0-0, then 1-0 (entering the 2nd at-bat);
    // the 2nd inning opens at 2-0. Mutation target: opening an inning at its LAST row would start
    // the 1st at 1-0 and lose a run.
    const pitches = [
      p({ atBatIndex: 0, inning: 1 }),
      p({ atBatIndex: 1, inning: 1, awayScore: 1 }),
      p({ atBatIndex: 2, inning: 2, awayScore: 2 }),
      p({ atBatIndex: 3, inning: 2, awayScore: 2 }),
    ];
    const ls = lineScoreFrom(pitches, {
      ...LIVE,
      inning: 2,
      awayScore: 2,
      homeScore: 0,
    })!;
    expect(ls.away).toEqual([2, 0]);
  });

  it("closes the inning in progress against the summary score", () => {
    // A run scores in the current inning: no later row exists, so only the summary knows.
    const pitches = [
      p({ atBatIndex: 0, inning: 1 }),
      p({ atBatIndex: 1, inning: 2 }),
    ];
    const ls = lineScoreFrom(pitches, {
      ...LIVE,
      inning: 2,
      awayScore: 0,
      homeScore: 1,
    })!;
    expect(ls.home).toEqual([0, 1]);
    expect(ls.homeTotal).toBe(1);
  });

  it("renders an inning with no logged pitches as unknown, never as zero", () => {
    const pitches = [
      p({ atBatIndex: 0, inning: 1 }),
      // inning 2 missing entirely (ingest gap)
      p({ atBatIndex: 9, inning: 3, awayScore: 1 }),
    ];
    const ls = lineScoreFrom(pitches, {
      ...LIVE,
      inning: 3,
      awayScore: 1,
      homeScore: 0,
    })!;
    // Inning 1 cannot be closed (no first row of inning 2), inning 2 cannot be opened.
    expect(ls.away).toEqual([null, null, 0]);
  });

  it("refuses a negative difference (a feed correction) rather than printing it", () => {
    const pitches = [
      p({ atBatIndex: 0, inning: 1, awayScore: 1 }),
      p({ atBatIndex: 1, inning: 2, awayScore: 0 }),
    ];
    const ls = lineScoreFrom(pitches, {
      ...LIVE,
      inning: 2,
      awayScore: 0,
      homeScore: 0,
    })!;
    expect(ls.away[0]).toBeNull();
  });

  it("draws extra innings and drops the current marker once final", () => {
    const pitches = Array.from({ length: 10 }, (_, i) =>
      p({ atBatIndex: i, inning: i + 1 }),
    );
    const ls = lineScoreFrom(pitches, {
      status: "COMPLETED",
      inning: 10,
      awayScore: 1,
      homeScore: 0,
    })!;
    expect(ls.innings).toBe(10);
    expect(ls.away[9]).toBe(1);
    expect(ls.currentInning).toBeNull();
  });
});

describe("atBatsFrom + atBatSentence", () => {
  const name = "Kwan";
  function only(pitches: LivePitchRow[], status = "IN_PROGRESS"): AtBat {
    return atBatsFrom(pitches, status)[0]!;
  }

  it("groups by at-bat, newest first, pitches oldest first", () => {
    const abs = atBatsFrom(
      [
        p({ atBatIndex: 1, pitchNumber: 2, description: "foul" }),
        p({ atBatIndex: 0, pitchNumber: 1 }),
        p({ atBatIndex: 1, pitchNumber: 1 }),
      ],
      "IN_PROGRESS",
    );
    expect(abs.map((a) => a.atBatIndex)).toEqual([1, 0]);
    expect(abs[0]!.pitches.map((x) => x.pitchNumber)).toEqual([1, 2]);
  });

  it("reads strike three swinging as a strikeout, flagged inferred", () => {
    const ab = only(
      [
        p({ atBatIndex: 0, pitchNumber: 1, description: "swinging_strike" }),
        p({
          atBatIndex: 0,
          pitchNumber: 2,
          strikes: 2,
          balls: 3,
          description: "swinging_strike",
        }),
      ],
      "MID_INNING",
    );
    expect(ab.ending).toBe("strikeout_swinging");
    expect(isInferred(ab.ending)).toBe(true);
    expect(atBatSentence(ab, name)).toBe(
      "Kwan strikes out swinging on a full count.",
    );
  });

  it("reads strike three called as a strikeout looking", () => {
    const ab = only(
      [p({ strikes: 2, description: "called_strike" })],
      "MID_INNING",
    );
    expect(atBatSentence(ab, name)).toBe("Kwan strikes out looking.");
  });

  it("reads ball four as a walk, and says four pitches when it was four", () => {
    const four = [1, 2, 3, 4].map((n) =>
      p({ pitchNumber: n, balls: n - 1, description: "ball" }),
    );
    const ab = only(four, "MID_INNING");
    expect(ab.ending).toBe("walk");
    expect(atBatSentence(ab, name)).toBe("Kwan walks on four pitches.");
  });

  it("reads a finished two-strike foul as a foul-tip strikeout (foul tips are stored as foul)", () => {
    const ab = only([p({ strikes: 2, description: "foul" })], "MID_INNING");
    expect(ab.ending).toBe("strikeout_foul_tip");
    expect(atBatSentence(ab, name)).toBe("Kwan strikes out on a foul tip.");
  });

  it("does not guess how an at-bat ended when the count says it was still alive", () => {
    // e.g. a runner caught stealing for the third out on 1-1.
    const ab = only(
      [p({ balls: 1, strikes: 1, description: "ball" })],
      "MID_INNING",
    );
    expect(ab.ending).toBe("unknown");
    expect(isInferred(ab.ending)).toBe(false);
    expect(atBatSentence(ab, name)).toBe(
      "Kwan's at-bat ends after 1 pitch; the feed does not say how.",
    );
  });

  it("keeps the newest at-bat in progress only while the game is in progress", () => {
    const pitches = [
      p({ description: "called_strike" }),
      p({ pitchNumber: 2, strikes: 1, description: "ball" }),
    ];
    const live = only(pitches, "IN_PROGRESS");
    expect(live.ending).toBe("in_progress");
    expect(atBatSentence(live, name)).toBe("Kwan, 1-1 so far.");
    expect(only(pitches, "DELAYED").ending).toBe("unknown");
  });

  it("ends the at-bat once the live matchup has moved on, even if no pitch said so", () => {
    // The page names the NEW batter from the live matchup; the account must not keep the previous
    // batter "at bat" beside it.
    const pitches = [p({ atBatIndex: 1, description: "ball" })];
    expect(atBatsFrom(pitches, "IN_PROGRESS", 1)[0]!.ending).toBe(
      "in_progress",
    );
    expect(atBatsFrom(pitches, "IN_PROGRESS", 2)[0]!.ending).toBe("unknown");
  });

  it("names a ball in play by the feed's event, and quotes an unknown event rather than inventing a verb", () => {
    const hr = only([p({ description: "in_play", event: "home_run" })]);
    expect(hr.ending).toBe("in_play");
    expect(isInferred(hr.ending)).toBe(false);
    expect(atBatSentence(hr, name)).toBe("Kwan homers.");
    const fo = only([p({ description: "in_play", event: "Field Out" })]);
    expect(atBatSentence(fo, name)).toBe("Kwan is out on a ball in play.");
    // With the feed's contact type, a field out is said as the feed classifies it.
    const fly = only([
      p({ description: "in_play", event: "field_out", bbType: "fly_ball" }),
    ]);
    expect(atBatSentence(fly, name)).toBe("Kwan flies out.");
    const odd = only([p({ description: "in_play", event: "catcher_interf" })]);
    expect(atBatSentence(odd, name)).toBe("Kwan: catcher interf.");
    expect(eventPhrase(null)).toBe("puts the ball in play");
  });

  it("reports hit by pitch as named, not inferred", () => {
    const ab = only([p({ description: "hit_by_pitch" })]);
    expect(ab.ending).toBe("hit_by_pitch");
    expect(isInferred(ab.ending)).toBe(false);
    expect(atBatSentence(ab, name)).toBe("Kwan is hit by a pitch.");
  });

  it("marks an ingest gap between logged at-bats", () => {
    const abs = atBatsFrom(
      [p({ atBatIndex: 0 }), p({ atBatIndex: 3, description: "in_play" })],
      "IN_PROGRESS",
    );
    expect(abs[0]!.gapBefore).toBe(2);
    expect(abs[1]!.gapBefore).toBe(0);
  });
});

describe("modelGave", () => {
  const classes = {
    ball: 0.36,
    called_strike: 0.12,
    swinging_strike: 0.15,
    foul: 0.21,
    in_play: 0.16,
  };
  it("returns the probability given to the outcome that happened", () => {
    expect(
      modelGave(p({ description: "foul", predictedClasses: classes })),
    ).toBe(0.21);
  });
  it("returns null with no logged prediction, or for an outcome outside the classes", () => {
    expect(modelGave(p({ description: "foul" }))).toBeNull();
    expect(
      modelGave(p({ description: "hit_by_pitch", predictedClasses: classes })),
    ).toBeNull();
  });
});

describe("battedBallLine + foldAtBats", () => {
  it("states the physics the row carries, and nothing it does not", () => {
    expect(
      battedBallLine(
        p({
          launchSpeedMph: 101.24,
          launchAngleDeg: 26.6,
          hitDistanceFt: 371.2,
          bbType: "fly_ball",
        }),
      ),
    ).toBe("Fly ball, 101.2 mph, 27°, 371 ft");
    expect(battedBallLine(p({ launchSpeedMph: 88 }))).toBe("88.0 mph");
    expect(battedBallLine(p())).toBeNull();
  });

  it("shows the newest at-bat's inning and the one before, folding the rest", () => {
    const abs = atBatsFrom(
      [
        p({ atBatIndex: 0, inning: 4 }),
        p({ atBatIndex: 1, inning: 5 }),
        p({ atBatIndex: 2, inning: 6 }),
      ],
      "IN_PROGRESS",
    );
    const [shown, folded] = foldAtBats(abs);
    expect(shown.map((a) => a.inning)).toEqual([6, 5]);
    expect(folded.map((a) => a.inning)).toEqual([4]);
  });
});

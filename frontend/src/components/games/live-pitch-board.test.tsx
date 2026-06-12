/**
 * Data-state tests for <LivePitchBoard> (redesign PR-2): row content, the
 * gold just-thrown tick, the [154] champion-less "n/a" prediction contract,
 * agree/disagree reads, and the empty state.
 */
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { LivePitchRow } from "../../api/games";

import { LivePitchBoard } from "./live-pitch-board";

function pitch(over: Partial<LivePitchRow> = {}): LivePitchRow {
  return {
    gameId: 823619,
    atBatIndex: 12,
    pitchNumber: 3,
    cursor: over.cursor ?? 1,
    ingestedAt: "2026-06-11T17:11:00Z",
    pitcherId: 1,
    batterId: 2,
    description: "foul",
    pitchType: "FF",
    releaseSpeedMph: 94.8,
    plateXIn: null,
    plateZIn: null,
    balls: 1,
    strikes: 1,
    outs: 2,
    inning: 6,
    homeScore: 2,
    awayScore: 1,
    predictedClasses: null,
    predictedWinner: null,
    ...over,
  };
}

describe("LivePitchBoard", () => {
  it("renders one row per pitch with type, velo, count, and outcome chip", () => {
    const html = renderToStaticMarkup(
      <LivePitchBoard
        pitches={[
          pitch({ cursor: 2, description: "swinging_strike", pitchType: "SL" }),
          pitch({ cursor: 1 }),
        ]}
      />,
    );
    expect(html).toContain("SL");
    expect(html).toContain("94.8");
    expect(html).toContain("1-1");
    expect(html).toContain("swinging strike");
    expect(html).toContain("foul");
  });

  it("marks only the newest pitch with the just-thrown tick", () => {
    const html = renderToStaticMarkup(
      <LivePitchBoard pitches={[pitch({ cursor: 3 }), pitch({ cursor: 2 })]} />,
    );
    expect(html.match(/just-thrown-tick/g)).toHaveLength(1);
  });

  it("reads n/a while live runs champion-less ([154])", () => {
    const html = renderToStaticMarkup(<LivePitchBoard pitches={[pitch()]} />);
    expect(html).toContain("n/a");
    expect(html).toContain("[154]");
  });

  it("reads agreement and disagreement once a model predicts", () => {
    const agree = renderToStaticMarkup(
      <LivePitchBoard
        pitches={[pitch({ predictedWinner: "foul", description: "foul" })]}
      />,
    );
    expect(agree).toContain("✓ foul");

    const disagree = renderToStaticMarkup(
      <LivePitchBoard
        pitches={[pitch({ predictedWinner: "ball", description: "foul" })]}
      />,
    );
    expect(disagree).toContain("✗ ball");
  });

  it("caps rendered rows at the limit", () => {
    const many = Array.from({ length: 60 }, (_, i) => pitch({ cursor: i + 1 }));
    const html = renderToStaticMarkup(
      <LivePitchBoard pitches={many} limit={50} />,
    );
    expect(html.match(/<tr/g)).toHaveLength(51); // header + 50 rows
  });

  it("renders the waiting state for an empty log", () => {
    const html = renderToStaticMarkup(<LivePitchBoard pitches={[]} />);
    expect(html).toContain("Waiting for the first pitch");
    expect(html).not.toContain("<table");
  });
});

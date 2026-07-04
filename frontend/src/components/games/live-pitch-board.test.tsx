// @vitest-environment jsdom
/**
 * Behavioral (render + DOM query) tests for <LivePitchBoard> (C-34). Upgraded from
 * renderToStaticMarkup string matching to real DOM queries against the rendered output: row
 * content, the gold just-thrown tick, the [154] champion-less "n/a" prediction contract,
 * agree/disagree reads, the row cap, and the empty state. The board is purely presentational
 * (plain table + design tokens, no Mantine / no fetch), so this needs only jsdom + render.
 */
import "@testing-library/jest-dom/vitest";

import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";

import type { LivePitchRow } from "../../api/games";

import { LivePitchBoard } from "./live-pitch-board";

// The suite runs without vitest `globals`, so testing-library's auto-cleanup is not registered.
afterEach(cleanup);

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
    launchSpeedMph: null,
    launchAngleDeg: null,
    hitDistanceFt: null,
    bbType: null,
    event: null,
    ...over,
  };
}

describe("LivePitchBoard", () => {
  it("renders one row per pitch with type, velo, count, and outcome chip", () => {
    render(
      <LivePitchBoard
        pitches={[
          pitch({
            cursor: 2,
            description: "swinging_strike",
            pitchType: "SL",
            releaseSpeedMph: 88.1,
          }),
          pitch({ cursor: 1 }),
        ]}
      />,
    );
    expect(screen.getByText("SL")).toBeInTheDocument();
    expect(screen.getByText("88.1")).toBeInTheDocument();
    expect(screen.getByText("94.8")).toBeInTheDocument();
    expect(screen.getAllByText("1-1")).toHaveLength(2);
    expect(screen.getByText("swinging strike")).toBeInTheDocument();
    expect(screen.getByText("foul")).toBeInTheDocument();
    // header row + one row per pitch
    expect(screen.getAllByRole("row")).toHaveLength(3);
  });

  it("marks only the newest pitch with the just-thrown tick", () => {
    render(
      <LivePitchBoard pitches={[pitch({ cursor: 3 }), pitch({ cursor: 2 })]} />,
    );
    expect(screen.getAllByTestId("just-thrown-tick")).toHaveLength(1);
  });

  it("reads n/a while live runs champion-less ([154])", () => {
    render(<LivePitchBoard pitches={[pitch()]} />);
    expect(screen.getByText("n/a")).toBeInTheDocument();
    expect(screen.getByTitle(/\[154\]/)).toBeInTheDocument();
  });

  it("reads agreement and disagreement once a model predicts", () => {
    const { unmount } = render(
      <LivePitchBoard
        pitches={[pitch({ predictedWinner: "foul", description: "foul" })]}
      />,
    );
    expect(screen.getByText(/✓\s*foul/)).toBeInTheDocument();
    unmount();

    render(
      <LivePitchBoard
        pitches={[pitch({ predictedWinner: "ball", description: "foul" })]}
      />,
    );
    expect(screen.getByText(/✗\s*ball/)).toBeInTheDocument();
  });

  it("caps rendered rows at the limit", () => {
    const many = Array.from({ length: 60 }, (_, i) => pitch({ cursor: i + 1 }));
    render(<LivePitchBoard pitches={many} limit={50} />);
    expect(screen.getAllByRole("row")).toHaveLength(51); // header + 50 rows
  });

  it("renders the waiting state for an empty log", () => {
    render(<LivePitchBoard pitches={[]} />);
    expect(screen.getByRole("status")).toHaveTextContent(
      "Waiting for the first pitch",
    );
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });
});

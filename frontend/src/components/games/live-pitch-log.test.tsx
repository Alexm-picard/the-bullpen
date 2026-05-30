/**
 * Unit tests for <LivePitchLog>.
 *
 * Covers list rendering (N PitchCards), the newest-card scarlet left-edge
 * accent rule, the empty-state placeholder, and the aria-live="polite"
 * region semantics.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { LivePitchRow } from "../../api/games";
import { colors } from "../../design/tokens";
import { theme } from "../../design/theme";

import { LivePitchLog } from "./live-pitch-log";

const BASE_PITCH: LivePitchRow = {
  gameId: 778899,
  atBatIndex: 1,
  pitchNumber: 1,
  cursor: 100,
  ingestedAt: "2026-05-30T20:42:00Z",
  pitcherId: 669373,
  batterId: 592450,
  description: "foul",
  pitchType: "FF",
  releaseSpeedMph: 97.2,
  plateXIn: null,
  plateZIn: null,
  balls: 2,
  strikes: 1,
  outs: 1,
  inning: 5,
  homeScore: 2,
  awayScore: 4,
  predictedClasses: {
    ball: 0.2,
    called_strike: 0.2,
    swinging_strike: 0.1,
    foul: 0.4,
    in_play: 0.1,
  },
  predictedWinner: "foul",
};

function pitch(cursor: number, description: string): LivePitchRow {
  return { ...BASE_PITCH, cursor, description };
}

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

describe("LivePitchLog", () => {
  it("renders one card per pitch", () => {
    const html = render(
      <LivePitchLog
        pitches={[
          pitch(105, "foul"),
          pitch(104, "called_strike"),
          pitch(103, "ball"),
        ]}
      />,
    );
    // Each card renders its inning + count + the description.
    expect(html).toContain("Inning 5");
    // 3 cards × "Inning 5" label.
    expect(html.match(/Inning 5/g)?.length ?? 0).toBeGreaterThanOrEqual(3);
  });

  it("marks the first (newest) card with a scarlet left-edge accent rule", () => {
    const html = render(
      <LivePitchLog pitches={[pitch(105, "foul"), pitch(104, "ball")]} />,
    );
    expect(html).toContain('data-newest="true"');
    // The newest card uses `border-left: 3px solid ${scarlet}`.
    expect(html.toLowerCase()).toContain(
      `border-left:3px solid ${colors.scarlet.toLowerCase()}`,
    );
  });

  it("renders the empty-state placeholder when there are no pitches", () => {
    const html = render(<LivePitchLog pitches={[]} />);
    expect(html).toContain("No pitches yet");
  });

  it("has aria-live polite on the log region", () => {
    const html = render(<LivePitchLog pitches={[pitch(105, "foul")]} />);
    expect(html).toContain('aria-live="polite"');
    expect(html).toContain('role="log"');
  });

  it("renders the optional caption", () => {
    const html = render(
      <LivePitchLog pitches={[pitch(105, "foul")]} caption="LIVE FEED" />,
    );
    expect(html).toContain("LIVE FEED");
  });
});

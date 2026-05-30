import { MantineProvider } from "@mantine/core";
import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { LivePitchRow } from "../../api/games";
import { theme } from "../../design/theme";
import { colors } from "../../design/tokens";

import { PitchCard } from "./pitch-card";

const BASE: LivePitchRow = {
  gameId: 777001,
  atBatIndex: 1,
  pitchNumber: 1,
  cursor: 101,
  ingestedAt: "2026-05-25T18:30:00Z",
  pitcherId: 660271,
  batterId: 545361,
  description: "called_strike",
  pitchType: "FF",
  releaseSpeedMph: 94.3,
  plateXIn: 0.1,
  plateZIn: 2.6,
  balls: 0,
  strikes: 1,
  outs: 0,
  inning: 1,
  homeScore: 0,
  awayScore: 0,
  predictedClasses: null,
  predictedWinner: null,
};

function render(node: ReactNode): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{node}</MantineProvider>,
  );
}

describe("PitchCard", () => {
  it("renders count, inning, outs, and pitch metadata", () => {
    const html = render(<PitchCard pitch={BASE} />);
    expect(html).toContain("Inning 1");
    expect(html).toContain("0-1");
    expect(html).toContain("0 outs");
    expect(html).toContain("FF");
    expect(html).toContain("94.3 mph");
  });

  it("shows n/a when there's no prediction", () => {
    const html = render(<PitchCard pitch={BASE} />);
    expect(html).toContain("n/a");
  });

  it("shows ✓ when predicted == observed", () => {
    const html = render(
      <PitchCard
        pitch={{
          ...BASE,
          predictedWinner: "called_strike",
          predictedClasses: {
            ball: 0.2,
            called_strike: 0.5,
            swinging_strike: 0.1,
            foul: 0.1,
            in_play: 0.1,
          },
        }}
      />,
    );
    expect(html).toContain("✓ predicted called_strike");
    expect(html).not.toContain(colors.scarlet.toLowerCase().slice(1)); // no brick-red rule
  });

  it("shows ✗ and the brick-red rule when predicted != observed", () => {
    const html = render(
      <PitchCard
        pitch={{
          ...BASE,
          description: "ball",
          predictedWinner: "called_strike",
          predictedClasses: {
            ball: 0.2,
            called_strike: 0.5,
            swinging_strike: 0.1,
            foul: 0.1,
            in_play: 0.1,
          },
        }}
      />,
    );
    expect(html).toContain("✗ predicted called_strike");
    expect(html.toLowerCase()).toContain(colors.scarlet.toLowerCase());
  });
});

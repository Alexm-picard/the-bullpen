/**
 * Unit tests for <NowBattingPair>.
 *
 * Covers both halves rendering (role, jersey, team, name, position/hand/age,
 * this-game line), the navy header bars, and the section labelling that
 * supports the matching aria-labelledby anchor.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { NowBattingPairData } from "../../data/games-fixtures";
import { colors } from "../../design/tokens";
import { theme } from "../../design/theme";

import { NowBattingPair } from "./now-batting-pair";

const DATA: NowBattingPairData = {
  batter: {
    role: "BATTER",
    jersey: "99",
    team: "NYY",
    name: "Aaron Judge",
    position: "RF",
    hand: "R/R",
    age: 33,
    thisGame: "1-3, BB, HR in 4th (442 ft, LF)",
  },
  pitcher: {
    role: "PITCHER",
    jersey: "29",
    team: "DET",
    name: "Tarik Skubal",
    position: "SP",
    hand: "L/L",
    age: 28,
    thisGame: "4.1 IP · 87 pitches · 6 K · 2 ER",
  },
};

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

describe("NowBattingPair", () => {
  it("renders both role labels in navy header bars", () => {
    const html = render(<NowBattingPair {...DATA} />);
    expect(html).toContain("BATTER");
    expect(html).toContain("PITCHER");
    expect(html.toLowerCase()).toContain(colors.navy.toLowerCase());
  });

  it("renders jersey + team in the header bar", () => {
    const html = render(<NowBattingPair {...DATA} />);
    expect(html).toContain("#99 · NYY");
    expect(html).toContain("#29 · DET");
  });

  it("renders both player names in uppercase Saira display", () => {
    const html = render(<NowBattingPair {...DATA} />);
    expect(html).toContain("Aaron Judge");
    expect(html).toContain("Tarik Skubal");
    expect(html).toContain("Saira Condensed");
  });

  it("renders position, hand, and age for each player", () => {
    const html = render(<NowBattingPair {...DATA} />);
    expect(html).toContain("RF");
    expect(html).toContain("R/R");
    expect(html).toContain("Age 33");
    expect(html).toContain("SP");
    expect(html).toContain("L/L");
    expect(html).toContain("Age 28");
  });

  it("renders the THIS GAME line for both halves", () => {
    const html = render(<NowBattingPair {...DATA} />);
    expect(html).toContain("1-3, BB, HR in 4th");
    expect(html).toContain("4.1 IP");
    // Both halves carry the "THIS GAME" mono micro-label.
    expect((html.match(/This Game/g) ?? []).length).toBeGreaterThanOrEqual(2);
  });

  it("anchors each half with aria-labelledby for landmark navigation", () => {
    const html = render(<NowBattingPair {...DATA} />);
    expect(html).toContain('aria-labelledby="now-batting-batter"');
    expect(html).toContain('aria-labelledby="now-batting-pitcher"');
  });
});

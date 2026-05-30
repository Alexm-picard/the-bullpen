/**
 * Unit tests for <LiveGameHeader>.
 *
 * Covers the two-line nameplate ("LIVE GAME" / "{away} @ {home}" as separate
 * display:block spans), the HeroEyebrow text, the byline strip (batter ⟷
 * pitcher · halfInning · score), and the mono context line.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { colors } from "../../design/tokens";
import { theme } from "../../design/theme";

import { LiveGameHeader } from "./live-game-header";

const DEFAULT_PROPS = {
  issueDate: "Wed · May 30, 2026",
  awayTeam: "NYY",
  homeTeam: "DET",
  awayScore: 4,
  homeScore: 2,
  halfInning: "BOT 5TH",
  batterName: "Aaron Judge",
  pitcherName: "Tarik Skubal",
  issuedAt: "8:42 PM ET",
  modelLabel: "pitch_outcome_pre v3.2 LIVE",
};

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

describe("LiveGameHeader", () => {
  it("renders the eyebrow text with issue date", () => {
    const html = render(<LiveGameHeader {...DEFAULT_PROPS} />);
    expect(html).toContain("The Bullpen");
    expect(html).toContain("Live Game");
    expect(html).toContain("Wed · May 30, 2026");
  });

  it("renders 'LIVE GAME' and the matchup as separate block spans", () => {
    const html = render(<LiveGameHeader {...DEFAULT_PROPS} />);
    expect(html).toMatch(/style="display:block"[^>]*>Live Game/);
    expect(html).toMatch(/style="display:block"[^>]*>NYY @ DET/);
  });

  it("renders the scarlet ⟷ matchup arrow", () => {
    const html = render(<LiveGameHeader {...DEFAULT_PROPS} />);
    expect(html).toContain("⟷");
    expect(html.toLowerCase()).toContain(colors.scarlet.toLowerCase());
  });

  it("renders the batter and pitcher last names in the byline strip", () => {
    const html = render(<LiveGameHeader {...DEFAULT_PROPS} />);
    expect(html).toContain("JUDGE");
    expect(html).toContain("SKUBAL");
  });

  it("renders the half-inning and score line in the byline strip", () => {
    const html = render(<LiveGameHeader {...DEFAULT_PROPS} />);
    expect(html).toContain("BOT 5TH");
    expect(html).toContain("NYY 4");
    expect(html).toContain("DET 2");
  });

  it("renders the issued timestamp and model label in the mono context line", () => {
    const html = render(<LiveGameHeader {...DEFAULT_PROPS} />);
    expect(html).toContain("8:42 PM ET");
    expect(html).toContain("pitch_outcome_pre v3.2 LIVE");
  });

  it("uses the Saira display font on the h1", () => {
    const html = render(<LiveGameHeader {...DEFAULT_PROPS} />);
    expect(html).toContain("Saira Condensed");
  });

  it("includes a screen-reader-only 'facing' label for the arrow glyph", () => {
    const html = render(<LiveGameHeader {...DEFAULT_PROPS} />);
    expect(html).toContain("facing");
  });
});

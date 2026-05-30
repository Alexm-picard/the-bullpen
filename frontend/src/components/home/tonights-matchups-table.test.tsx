/**
 * Unit tests for <TonightsMatchupsTable>.
 *
 * Covers: all matchup row labels render, EDGE values render with sign, OPEN
 * cells are real anchors routing to /players/{batterId}, the EDGE column gets
 * a cellColor tint (at least one strong-good and one strong-bad in the
 * default fixture set), and the navy header chrome is in effect.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import { TONIGHT_MATCHUPS } from "../../data/home-fixtures";
import { theme } from "../../design/theme";
import { colors } from "../../design/tokens";

import { TonightsMatchupsTable } from "./tonights-matchups-table";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>
      <MemoryRouter>{ui}</MemoryRouter>
    </MantineProvider>,
  );
}

describe("TonightsMatchupsTable", () => {
  it("renders all 6 column headers", () => {
    const html = render(<TonightsMatchupsTable matchups={TONIGHT_MATCHUPS} />);
    expect(html).toContain("Matchup");
    expect(html).toContain("Time");
    expect(html).toContain("Starters");
    expect(html).toContain("Edge");
    expect(html).toContain("Top Read");
    expect(html).toContain("Open");
  });

  it("renders all matchup team pairs", () => {
    const html = render(<TonightsMatchupsTable matchups={TONIGHT_MATCHUPS} />);
    for (const m of TONIGHT_MATCHUPS) {
      expect(html).toContain(m.away);
      expect(html).toContain(m.home);
    }
  });

  it("renders the first-pitch times", () => {
    const html = render(<TonightsMatchupsTable matchups={TONIGHT_MATCHUPS} />);
    expect(html).toContain("7:10 PM ET");
    expect(html).toContain("10:15 PM ET");
  });

  it("renders edge values with a sign", () => {
    const html = render(<TonightsMatchupsTable matchups={TONIGHT_MATCHUPS} />);
    expect(html).toContain("+0.7");
    expect(html).toContain("-1.2");
    expect(html).toContain("+1.4");
  });

  it("renders OPEN cells as anchors to /players/{batterId}", () => {
    const html = render(<TonightsMatchupsTable matchups={TONIGHT_MATCHUPS} />);
    expect(html).toContain('href="/players/judge_aaron"');
    expect(html).toContain('href="/players/trout_mike"');
    expect(html).toContain('href="/players/soto_juan"');
  });

  it("applies cellColor tint to the EDGE column", () => {
    const html = render(<TonightsMatchupsTable matchups={TONIGHT_MATCHUPS} />);
    const html_lower = html.toLowerCase();
    // At least one good-side fill (positive edge row, e.g. +1.4 LAD @ SF).
    const hasGood = [colors.condFormat.good1, colors.condFormat.good3].some(
      (hex) => html_lower.includes(hex.toLowerCase()),
    );
    expect(hasGood).toBe(true);
    // At least one bad-side fill (negative edge row, e.g. -1.2 LAA @ HOU).
    const hasBad = [colors.condFormat.bad1, colors.condFormat.bad3].some(
      (hex) => html_lower.includes(hex.toLowerCase()),
    );
    expect(hasBad).toBe(true);
  });

  it("renders the navy header chrome", () => {
    const html = render(<TonightsMatchupsTable matchups={TONIGHT_MATCHUPS} />);
    expect(html.toLowerCase()).toContain(colors.navy.toLowerCase());
  });

  it("renders the silver row-label column", () => {
    const html = render(<TonightsMatchupsTable matchups={TONIGHT_MATCHUPS} />);
    expect(html.toLowerCase()).toContain(colors.silver.toLowerCase());
  });

  it("renders an empty <tbody> path safely with no rows", () => {
    const html = render(<TonightsMatchupsTable matchups={[]} />);
    // Headers still present; no row anchors.
    expect(html).toContain("Matchup");
    expect(html).not.toContain('href="/players/');
  });

  it("renders the optional caption when provided", () => {
    const html = render(
      <TonightsMatchupsTable
        matchups={TONIGHT_MATCHUPS}
        caption="Tonight's matchups · 8 games"
      />,
    );
    expect(html).toContain("Tonight&#x27;s matchups");
  });
});

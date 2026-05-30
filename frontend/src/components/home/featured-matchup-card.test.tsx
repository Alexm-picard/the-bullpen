/**
 * Unit tests for <FeaturedMatchupCard>.
 *
 * Covers: header text, both player names, context line, exactly two
 * key-reads with numbered <ol> semantics, scarlet CTA <Link> to the
 * provided href, navy chrome on the header strip.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import { PLAYERS } from "../../data/matchup-fixtures";
import { theme } from "../../design/theme";
import { colors } from "../../design/tokens";

import { FeaturedMatchupCard } from "./featured-matchup-card";

const DEFAULT_PROPS = {
  batter: PLAYERS.judge_aaron,
  pitcher: PLAYERS.skubal_tarik,
  context: "NYY @ DET · 7:10 PM ET · Comerica Park",
  keyReads: [
    "First key read about the slider.",
    "Second key read about the heater.",
  ] as [string, string],
  ctaHref: "/players/judge_aaron",
  ctaLabel: "Pull the full report →",
};

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>
      <MemoryRouter>{ui}</MemoryRouter>
    </MantineProvider>,
  );
}

describe("FeaturedMatchupCard", () => {
  it("renders the navy header strip text", () => {
    const html = render(<FeaturedMatchupCard {...DEFAULT_PROPS} />);
    expect(html).toContain("Featured Matchup");
    expect(html).toContain("Top Read");
  });

  it("renders both player names", () => {
    const html = render(<FeaturedMatchupCard {...DEFAULT_PROPS} />);
    expect(html).toContain("Aaron Judge");
    expect(html).toContain("Tarik Skubal");
  });

  it("renders BATTER and PITCHER eyebrows", () => {
    const html = render(<FeaturedMatchupCard {...DEFAULT_PROPS} />);
    expect(html).toContain("Batter");
    expect(html).toContain("Pitcher");
  });

  it("renders the context line", () => {
    const html = render(<FeaturedMatchupCard {...DEFAULT_PROPS} />);
    expect(html).toContain("NYY @ DET");
    expect(html).toContain("Comerica Park");
  });

  it("renders both key-reads", () => {
    const html = render(<FeaturedMatchupCard {...DEFAULT_PROPS} />);
    expect(html).toContain("First key read about the slider.");
    expect(html).toContain("Second key read about the heater.");
  });

  it("renders the key-reads inside an <ol> with zero-padded markers", () => {
    const html = render(<FeaturedMatchupCard {...DEFAULT_PROPS} />);
    expect(html).toContain("<ol");
    expect(html).toContain("01");
    expect(html).toContain("02");
  });

  it("renders the CTA as a scarlet link to the provided href", () => {
    const html = render(<FeaturedMatchupCard {...DEFAULT_PROPS} />);
    expect(html).toContain('href="/players/judge_aaron"');
    expect(html).toContain("Pull the full report");
    expect(html.toLowerCase()).toContain(colors.scarlet.toLowerCase());
  });

  it("uses the navy chrome on the header strip", () => {
    const html = render(<FeaturedMatchupCard {...DEFAULT_PROPS} />);
    expect(html.toLowerCase()).toContain(colors.navy.toLowerCase());
  });

  it("provides an aria-labelledby pointing at the header", () => {
    const html = render(<FeaturedMatchupCard {...DEFAULT_PROPS} />);
    expect(html).toContain('aria-labelledby="featured-matchup-header"');
    expect(html).toContain('id="featured-matchup-header"');
  });
});

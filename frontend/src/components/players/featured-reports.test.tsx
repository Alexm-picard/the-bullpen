/**
 * <FeaturedReports> - the /players landing card row. Static-render coverage
 * (matching the broadcast component-test convention): a card per report, the
 * NUMERIC /players/:id href, team color as a fill (never text), and the chips.
 */
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import { FEATURED_REPORTS } from "../../data/players-landing-fixtures";
import { teamColor } from "../../design/teamColors";

import { FeaturedReports } from "./featured-reports";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(<MemoryRouter>{ui}</MemoryRouter>);
}

describe("FeaturedReports", () => {
  it("renders a card per report with name, role, stats, and chips", () => {
    const html = render(<FeaturedReports reports={FEATURED_REPORTS} />);
    expect(html).toContain("Shohei Ohtani");
    expect(html).toContain("Paul Skenes");
    expect(html).toContain("DH/SP");
    expect(html).toContain("HR prob");
    expect(html).toContain("HIT 70");
    expect(html).toContain("Open report");
  });

  it("links each card to /players/{playerId}", () => {
    const html = render(
      <FeaturedReports reports={FEATURED_REPORTS.slice(0, 1)} />,
    );
    expect(html).toContain('href="/players/660271"');
    expect(html).toContain("Open scouting report for Shohei Ohtani");
  });

  it("uses team color as a fill (edge bar), present in the markup", () => {
    const html = render(
      <FeaturedReports reports={FEATURED_REPORTS.slice(0, 1)} />,
    );
    expect(html).toContain(teamColor("LAD"));
  });

  it("renders nothing but the grid for an empty list", () => {
    const html = render(<FeaturedReports reports={[]} />);
    expect(html).not.toContain("Open report");
  });
});

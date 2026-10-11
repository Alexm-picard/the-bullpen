/**
 * <FeaturedReports> - the /players showcase list ([195]). A hand-written fixture: names link to the
 * LIVE numeric file, and the tone-coded grade chips the fixture carries are NOT rendered.
 */
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";

import { FEATURED_REPORTS } from "../../data/players-landing-fixtures";
import { visibleText } from "../../test-support/visible-text";

import { FeaturedReports } from "./featured-reports";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(<MemoryRouter>{ui}</MemoryRouter>);
}

describe("FeaturedReports", () => {
  it("renders a row per report with name, team, role and both stats", () => {
    const text = visibleText(
      render(<FeaturedReports reports={FEATURED_REPORTS} />),
    );
    expect(text).toContain("Shohei Ohtani");
    expect(text).toContain("DH/SP");
    expect(text).toContain("HR prob 7.1%");
    expect(text).toContain("Showcase figures, illustrative.");
  });

  it("links each name to /players/{playerId}", () => {
    const html = render(
      <FeaturedReports reports={FEATURED_REPORTS.slice(0, 1)} />,
    );
    expect(html).toContain('href="/players/660271"');
  });

  it("does not render the fixture's grade chips", () => {
    const text = visibleText(
      render(<FeaturedReports reports={FEATURED_REPORTS} />),
    );
    for (const r of FEATURED_REPORTS) {
      for (const c of r.chips) expect(text).not.toContain(c.label);
    }
  });

  it("renders nothing for an empty list", () => {
    expect(render(<FeaturedReports reports={[]} />)).toBe("");
  });
});

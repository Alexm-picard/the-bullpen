/**
 * Unit + snapshot test for <ParkSpotlight>.
 *
 * Asserts the field SVG, the heatmap SVG, and the two numbered key-reads
 * are all present in the render. A snapshot pins the overall composition.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { COORS_SPOTLIGHT } from "../../data/parks-fixtures";
import { theme } from "../../design/theme";

import { ParkSpotlight } from "./park-spotlight";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

describe("ParkSpotlight", () => {
  it("renders both the field SVG and the heatmap SVG", () => {
    const html = render(<ParkSpotlight spotlight={COORS_SPOTLIGHT} />);
    // Two SVGs total: field outline + heatmap grid.
    const svgMatches = html.match(/<svg\b/g) ?? [];
    expect(svgMatches.length).toBe(2);
  });

  it("renders the field SVG aria-label referencing the spotlight park name", () => {
    const html = render(<ParkSpotlight spotlight={COORS_SPOTLIGHT} />);
    expect(html).toContain("COORS FIELD");
  });

  it("renders the heatmap caption", () => {
    const html = render(<ParkSpotlight spotlight={COORS_SPOTLIGHT} />);
    expect(html).toContain("Batted-Ball Landing Density");
  });

  it("renders both key-read paragraphs", () => {
    const html = render(<ParkSpotlight spotlight={COORS_SPOTLIGHT} />);
    // renderToStaticMarkup HTML-escapes apostrophes, so we match on
    // substrings that don't contain them.
    expect(html).toContain("Coors plays as the league");
    expect(html).toContain("most distinctive hitter park");
    expect(html).toContain(
      "Despite the slugging surface, K factor lands at 0.97",
    );
  });

  it("renders the KeyNotes numbered list (01 + 02)", () => {
    const html = render(<ParkSpotlight spotlight={COORS_SPOTLIGHT} />);
    expect(html).toContain("01");
    expect(html).toContain("02");
  });

  it("matches the spotlight snapshot", () => {
    const html = render(<ParkSpotlight spotlight={COORS_SPOTLIGHT} />);
    expect(html.length).toBeGreaterThan(100);
    // Snapshot the entire markup.
    expect(html).toMatchSnapshot();
  });
});

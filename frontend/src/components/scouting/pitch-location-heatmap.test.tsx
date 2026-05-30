/**
 * Unit tests for <PitchLocationHeatmap>.
 *
 * Covers panel-per-pitch rendering, role + aria-label per SVG (a11y rule),
 * heat-color application from the heatWarm ramp, strike-zone navy stroke,
 * and the velo / usage redundant labels.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { makeGrid, type PitchMixRow } from "../../data/matchup-fixtures";
import { colors } from "../../design/tokens";
import { theme } from "../../design/theme";

import { PitchLocationHeatmap } from "./pitch-location-heatmap";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

const PITCHES: PitchMixRow[] = [
  {
    code: "FF",
    name: "Four-seam",
    usage: 0.42,
    velo: 97.1,
    whiff: 0.31,
    xwoba: 0.29,
    putaway: 0.24,
    locationGrid: makeGrid(2, 7, 1.8),
  },
  {
    code: "SL",
    name: "Slider",
    usage: 0.27,
    velo: 88.4,
    whiff: 0.41,
    xwoba: 0.24,
    putaway: 0.33,
    locationGrid: makeGrid(9, 8, 1.8),
  },
];

describe("PitchLocationHeatmap", () => {
  it("renders one panel per pitch", () => {
    const html = render(<PitchLocationHeatmap pitches={PITCHES} />);
    expect(html).toContain("Four-seam");
    expect(html).toContain("Slider");
  });

  it("includes role=img + aria-label on every panel SVG", () => {
    const html = render(<PitchLocationHeatmap pitches={PITCHES} />);
    expect(html).toContain('role="img"');
    expect(html).toMatch(/aria-label="Four-seam location density/);
    expect(html).toMatch(/aria-label="Slider location density/);
  });

  it("renders the usage % and velo as redundant labels", () => {
    const html = render(<PitchLocationHeatmap pitches={PITCHES} />);
    expect(html).toContain("42%");
    expect(html).toContain("97.1 mph");
  });

  it("uses at least one heatWarm ramp color in the rendered cells", () => {
    const html = render(<PitchLocationHeatmap pitches={PITCHES} />);
    const hit = colors.heatWarm.some((c) =>
      html.toLowerCase().includes(c.toLowerCase()),
    );
    expect(hit).toBe(true);
  });

  it("draws the strike zone in navy", () => {
    const html = render(<PitchLocationHeatmap pitches={PITCHES} />);
    expect(html.toLowerCase()).toContain(colors.navy.toLowerCase());
  });

  it("includes whiff% + xwOBA in the figcaption", () => {
    const html = render(<PitchLocationHeatmap pitches={PITCHES} />);
    expect(html).toContain("Whiff");
    expect(html).toContain("xwOBA");
  });

  it("renders the caption when provided", () => {
    const html = render(
      <PitchLocationHeatmap pitches={PITCHES} caption="LOCATIONS · vs RHB" />,
    );
    expect(html).toContain("LOCATIONS");
  });
});

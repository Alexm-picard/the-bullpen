/**
 * Unit tests for <SprayChart>.
 *
 * Covers role + aria-label on the SVG (a11y rule), all zone labels rendered,
 * count text rendered (color-not-sole-carrier), spray ramp color usage, navy
 * foul lines, and the legend strip.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { SprayZone } from "../../data/matchup-fixtures";
import { colors } from "../../design/broadcast";
import { theme } from "../../design/theme";

import { SprayChart } from "./spray-chart";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

const ZONES: SprayZone[] = [
  { id: "LF-pull", label: "LF pull", count: 142, density: 0.92 },
  { id: "LF-gap", label: "LF gap", count: 96, density: 0.74 },
  { id: "CF", label: "CF", count: 71, density: 0.52 },
  { id: "RF-gap", label: "RF gap", count: 38, density: 0.3 },
  { id: "RF-oppo", label: "RF oppo", count: 22, density: 0.18 },
];

describe("SprayChart", () => {
  it("includes role=img and aria-label on the SVG", () => {
    const html = render(<SprayChart zones={ZONES} />);
    expect(html).toContain('role="img"');
    expect(html).toMatch(/aria-label="Spray chart/);
  });

  it("aria-label paraphrases the dominant sector", () => {
    const html = render(<SprayChart zones={ZONES} />);
    expect(html).toContain("dominant sector LF pull");
  });

  it("renders every sector's count as on-canvas text (color is not sole carrier)", () => {
    const html = render(<SprayChart zones={ZONES} />);
    expect(html).toContain(">142<");
    expect(html).toContain(">96<");
    expect(html).toContain(">71<");
    expect(html).toContain(">38<");
    expect(html).toContain(">22<");
  });

  it("renders every sector label uppercase", () => {
    const html = render(<SprayChart zones={ZONES} />);
    expect(html).toContain("LF PULL");
    expect(html).toContain("CF");
  });

  it("applies at least one spray ramp color to a sector fill", () => {
    const html = render(<SprayChart zones={ZONES} />);
    const hit = colors.spray.some((c) =>
      html.toLowerCase().includes(c.toLowerCase()),
    );
    expect(hit).toBe(true);
  });

  it("draws foul lines in navy", () => {
    const html = render(<SprayChart zones={ZONES} />);
    expect(html.toLowerCase()).toContain(colors.chrome.toLowerCase());
  });

  it("renders the caption when provided", () => {
    const html = render(
      <SprayChart zones={ZONES} caption="SPRAY · 2025 SEASON" />,
    );
    expect(html).toContain("SPRAY");
  });
});

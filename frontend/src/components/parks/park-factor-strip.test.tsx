/**
 * Unit tests for <ParkFactorStrip>.
 *
 * Covers:
 *   - 5 figure blocks (4 numeric + 1 WIND)
 *   - 48px display figure for numeric blocks; 32px for WIND
 *   - Mono "+18% vs lg" caption format present
 *   - cellColor tint applied to numeric block backgrounds (no tint on WIND)
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { COORS_SPOTLIGHT } from "../../data/parks-fixtures";
import { colors } from "../../design/broadcast";
import { theme } from "../../design/theme";

import { ParkFactorStrip } from "./park-factor-strip";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

describe("ParkFactorStrip", () => {
  it("renders all 5 factor blocks (HR / BABIP / 3B / WIND / OPS)", () => {
    const html = render(<ParkFactorStrip factors={COORS_SPOTLIGHT.factors} />);
    expect(html).toContain("HR FACTOR");
    expect(html).toContain("BABIP FACTOR");
    expect(html).toContain("3B FACTOR");
    expect(html).toContain("WIND BIAS");
    expect(html).toContain("OPS FACTOR");
  });

  it("uses a 48px figure for numeric blocks", () => {
    const html = render(<ParkFactorStrip factors={COORS_SPOTLIGHT.factors} />);
    // 4 numeric blocks should each carry a font-size:48px declaration on the
    // figure span. We just assert the substring appears.
    expect(html).toMatch(/font-size:\s*48px/i);
  });

  it("uses a 32px display string for the WIND block", () => {
    const html = render(<ParkFactorStrip factors={COORS_SPOTLIGHT.factors} />);
    expect(html).toMatch(/font-size:\s*32px/i);
    // The WIND display string from the fixture
    expect(html).toContain("LF → RF");
  });

  it("renders mono '+18% vs lg' caption format", () => {
    const html = render(<ParkFactorStrip factors={COORS_SPOTLIGHT.factors} />);
    expect(html).toContain("+18% vs lg");
  });

  it("applies cellColor tint to numeric block backgrounds", () => {
    const html = render(<ParkFactorStrip factors={COORS_SPOTLIGHT.factors} />);
    // Coors HR 1.18 / BABIP 1.12 / 3B 1.42 / OPS 1.13 all land outside the
    // neutral band — we expect at least one bad1 or bad3 fill in the markup.
    const lower = html.toLowerCase();
    const bad1 = colors.condFormat.bad1.toLowerCase();
    const bad3 = colors.condFormat.bad3.toLowerCase();
    expect(lower.includes(bad1) || lower.includes(bad3)).toBe(true);
  });
});

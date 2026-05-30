/**
 * Unit tests for <AgreementByInningTable>.
 *
 * Covers the row + column rendering, the navy table-header chrome inherited
 * from <StatTable>, and the conditional-formatting fill on the AGREED%
 * column (via AGREEMENT_METRIC).
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { InningAgreementRow } from "../../data/games-fixtures";
import { colors } from "../../design/tokens";
import { theme } from "../../design/theme";

import { AgreementByInningTable } from "./agreement-by-inning-table";

const ROWS: InningAgreementRow[] = [
  { inning: 1, pitches: 38, agreed: 0.82, inPlay: 7, ks: 2, swings: 18 },
  { inning: 2, pitches: 31, agreed: 0.68, inPlay: 5, ks: 3, swings: 14 },
  { inning: 3, pitches: 42, agreed: 0.79, inPlay: 8, ks: 1, swings: 19 },
  { inning: 4, pitches: 29, agreed: 0.76, inPlay: 6, ks: 2, swings: 13 },
  { inning: 5, pitches: 42, agreed: 0.83, inPlay: 9, ks: 2, swings: 21 },
];

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

describe("AgreementByInningTable", () => {
  it("renders one row per inning with the formatted percentage", () => {
    const html = render(<AgreementByInningTable rows={ROWS} caption="cap" />);
    expect(html).toContain("Inning 1");
    expect(html).toContain("Inning 5");
    // 0.82 → "82%"
    expect(html).toContain("82%");
    expect(html).toContain("83%");
  });

  it("renders the five column headers", () => {
    const html = render(<AgreementByInningTable rows={ROWS} />);
    expect(html).toContain("Pitches");
    expect(html).toContain("Agreed%");
    expect(html).toContain("In-play");
    expect(html).toContain("K&#x27;s");
    expect(html).toContain("Swings");
  });

  it("uses navy in the table header (inherited StatTable chrome)", () => {
    const html = render(<AgreementByInningTable rows={ROWS} />);
    expect(html.toLowerCase()).toContain(colors.navy.toLowerCase());
  });

  it("applies a conditional-format fill to AGREED% cells via the agreement metric", () => {
    const html = render(<AgreementByInningTable rows={ROWS} />);
    // Inning 5 (0.83) lands in the good1 band of the diverging ramp.
    expect(html.toLowerCase()).toContain(colors.condFormat.good1.toLowerCase());
    // Inning 2 (0.68) lands in the neutral band.
    expect(html.toLowerCase()).toContain(
      colors.condFormat.neutral.toLowerCase(),
    );
  });

  it("renders the caption when provided", () => {
    const html = render(
      <AgreementByInningTable rows={ROWS} caption="hello inning cap" />,
    );
    expect(html).toContain("hello inning cap");
  });
});

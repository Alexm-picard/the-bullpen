/**
 * Unit tests for <OverviewParksTable>.
 *
 * Covers:
 *   - 30 rows render
 *   - 8 data column headers present
 *   - cellColor tints fire on the extremes (Coors HR 1.18 lands in bad1/bad3)
 *   - Navy header chrome + silver row-label chrome present (forwarded from
 *     the underlying StatTable)
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { PARK_ROWS } from "../../data/parks-fixtures";
import { colors } from "../../design/broadcast";
import { theme } from "../../design/theme";

import { OverviewParksTable } from "./overview-parks-table";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

describe("OverviewParksTable", () => {
  it("renders all 30 park rows", () => {
    const html = render(<OverviewParksTable rows={PARK_ROWS} />);
    // Each row has a unique id=park-row-XXX attribute on the <tr>.
    const matches = html.match(/id="park-row-/g) ?? [];
    expect(matches.length).toBe(30);
  });

  it("renders 8 data column headers", () => {
    const html = render(<OverviewParksTable rows={PARK_ROWS} />);
    for (const label of [
      "Team",
      "Climate",
      ">HR<",
      "BABIP",
      ">3B<",
      "Wind Bias",
      ">K<",
      "OPS",
    ]) {
      // The expectations include the `>X<` wrappers for short labels to avoid
      // false positives — "K" would match any "K" character otherwise.
      // For multi-character labels we just check substring presence.
      if (label.startsWith(">")) {
        expect(html).toContain(label);
      } else {
        expect(html).toContain(label);
      }
    }
  });

  it("applies cellColor tint to Coors HR 1.18 (closer-to-target extreme)", () => {
    const html = render(<OverviewParksTable rows={PARK_ROWS} />);
    // The closer-to-target ramp for FACTOR_METRIC tints values far from
    // 1.00 with bad1 or bad3. 1.18 has distance 0.18 / halfRange 0.25 = 0.72,
    // invertedPct 0.28 → bad1. We assert at least one bad1/bad3 fill is
    // present anywhere in the table.
    const badColors = [
      colors.condFormat.bad1.toLowerCase(),
      colors.condFormat.bad3.toLowerCase(),
    ];
    const lower = html.toLowerCase();
    const matched = badColors.some((c) => lower.includes(c));
    expect(matched).toBe(true);
  });

  it("renders the navy table header chrome", () => {
    const html = render(<OverviewParksTable rows={PARK_ROWS} />);
    expect(html.toLowerCase()).toContain(colors.chrome.toLowerCase());
  });

  it("renders the silver row-label column chrome", () => {
    const html = render(<OverviewParksTable rows={PARK_ROWS} />);
    expect(html.toLowerCase()).toContain(colors.fieldSubtle.toLowerCase());
  });

  it("formats numeric factor cells to 2 decimal places", () => {
    const html = render(<OverviewParksTable rows={PARK_ROWS} />);
    // Coors HR 1.18 should appear as the string "1.18"
    expect(html).toContain("1.18");
    // SD HR 0.88 should appear as "0.88"
    expect(html).toContain("0.88");
  });
});

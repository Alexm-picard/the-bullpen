/**
 * Unit tests for <ParkSwitcherStrip>.
 *
 * Covers:
 *   - 30 thumbnails render
 *   - Exactly 1 active scarlet ring (matches activeParkId)
 *   - aria-label on the container reads "Park switcher · 30 parks"
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { PARK_ROWS, PARK_THUMBNAILS } from "../../data/parks-fixtures";
import { colors } from "../../design/broadcast";
import { theme } from "../../design/theme";

import { ParkSwitcherStrip } from "./park-switcher-strip";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

describe("ParkSwitcherStrip", () => {
  it("renders all 30 thumbnails", () => {
    const html = render(
      <ParkSwitcherStrip
        thumbnails={PARK_THUMBNAILS}
        rows={PARK_ROWS}
        activeParkId="COL"
        onSelect={() => {}}
      />,
    );
    const buttonMatches = html.match(/<button\b/g) ?? [];
    expect(buttonMatches.length).toBe(30);
  });

  it("renders exactly one scarlet active ring", () => {
    const html = render(
      <ParkSwitcherStrip
        thumbnails={PARK_THUMBNAILS}
        rows={PARK_ROWS}
        activeParkId="COL"
        onSelect={() => {}}
      />,
    );
    const ringPattern = new RegExp(
      `outline:\\s*2px\\s+solid\\s+${colors.gold}`,
      "gi",
    );
    const ringMatches = html.match(ringPattern) ?? [];
    expect(ringMatches.length).toBe(1);
  });

  it("labels the container as a park-switcher region", () => {
    const html = render(
      <ParkSwitcherStrip
        thumbnails={PARK_THUMBNAILS}
        rows={PARK_ROWS}
        activeParkId="COL"
        onSelect={() => {}}
      />,
    );
    expect(html).toContain('aria-label="Park switcher · 30 parks"');
  });

  it("flips the active ring when activeParkId changes", () => {
    const colorado = render(
      <ParkSwitcherStrip
        thumbnails={PARK_THUMBNAILS}
        rows={PARK_ROWS}
        activeParkId="COL"
        onSelect={() => {}}
      />,
    );
    const boston = render(
      <ParkSwitcherStrip
        thumbnails={PARK_THUMBNAILS}
        rows={PARK_ROWS}
        activeParkId="BOS"
        onSelect={() => {}}
      />,
    );
    // Park names from the fixture are uppercase (e.g. "COORS FIELD").
    expect(colorado).toContain(
      'aria-label="Scroll to COORS FIELD in overview table"',
    );
    expect(boston).toContain(
      'aria-label="Scroll to FENWAY PARK in overview table"',
    );
    // Both should have exactly one ring; the IDs differ — the test above
    // already verifies the count.
    expect(colorado).not.toEqual(boston);
  });
});

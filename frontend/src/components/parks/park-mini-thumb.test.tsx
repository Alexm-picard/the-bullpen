/**
 * Unit tests for <ParkMiniThumb>.
 *
 * Covers:
 *   - Renders as a <button> element
 *   - aria-label contains the park name
 *   - 36 (6×6) <rect> cells render in the heatmap
 *   - Scarlet outline ring only present when isActive=true
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { colors } from "../../design/tokens";
import { theme } from "../../design/theme";

import { ParkMiniThumb } from "./park-mini-thumb";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

// Trivial 6×6 grid for fixture: all cells 0.5 — produces 36 rects regardless
// of the ramp choice.
const FAKE_GRID: number[][] = Array.from({ length: 6 }, () =>
  Array.from({ length: 6 }, () => 0.5),
);

describe("ParkMiniThumb", () => {
  it("renders as a <button> element", () => {
    const html = render(
      <ParkMiniThumb
        parkId="COL"
        parkName="Coors Field"
        abbr="COL"
        grid={FAKE_GRID}
        isActive={false}
        onSelect={() => {}}
      />,
    );
    expect(html).toMatch(/<button\b/i);
  });

  it("includes the park name in the aria-label", () => {
    const html = render(
      <ParkMiniThumb
        parkId="COL"
        parkName="Coors Field"
        abbr="COL"
        grid={FAKE_GRID}
        isActive={false}
        onSelect={() => {}}
      />,
    );
    expect(html).toContain(
      'aria-label="Scroll to Coors Field in overview table"',
    );
  });

  it("renders 36 (6×6) cell rects plus the outer background rect", () => {
    const html = render(
      <ParkMiniThumb
        parkId="COL"
        parkName="Coors Field"
        abbr="COL"
        grid={FAKE_GRID}
        isActive={false}
        onSelect={() => {}}
      />,
    );
    // <rect ... /> tags — count occurrences. SVG has 1 background rect + 36 cells.
    const rectMatches = html.match(/<rect\b/g) ?? [];
    expect(rectMatches.length).toBe(37);
  });

  it("renders a scarlet outline ring only when isActive=true", () => {
    const activeHtml = render(
      <ParkMiniThumb
        parkId="COL"
        parkName="Coors Field"
        abbr="COL"
        grid={FAKE_GRID}
        isActive={true}
        onSelect={() => {}}
      />,
    );
    const inactiveHtml = render(
      <ParkMiniThumb
        parkId="BOS"
        parkName="Fenway Park"
        abbr="BOS"
        grid={FAKE_GRID}
        isActive={false}
        onSelect={() => {}}
      />,
    );
    const scarletPattern = new RegExp(
      `outline:\\s*2px\\s+solid\\s+${colors.scarlet}`,
      "i",
    );
    expect(scarletPattern.test(activeHtml)).toBe(true);
    // Inactive should NOT have the scarlet outline string.
    expect(scarletPattern.test(inactiveHtml)).toBe(false);
  });

  it("uses aria-pressed to expose the active state to AT", () => {
    const html = render(
      <ParkMiniThumb
        parkId="COL"
        parkName="Coors Field"
        abbr="COL"
        grid={FAKE_GRID}
        isActive={true}
        onSelect={() => {}}
      />,
    );
    expect(html).toContain('aria-pressed="true"');
  });
});

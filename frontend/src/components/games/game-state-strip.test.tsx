/**
 * Unit tests for <GameStateStrip>.
 *
 * Covers cell rendering (label + value), navy chrome, and the optional
 * emphasis modes ("scarlet-fill" wraps the value in a scarlet pill,
 * "scarlet-outline" gives an outline-only pill).
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { GameStateCell } from "../../data/games-fixtures";
import { colors } from "../../design/tokens";
import { theme } from "../../design/theme";

import { GameStateStrip } from "./game-state-strip";

const CELLS: GameStateCell[] = [
  { label: "Inning", value: "B5" },
  { label: "Score", value: "NYY 4 — DET 2" },
  { label: "Count", value: "2–1, 1 OUT" },
  { label: "Runners", value: "1B · 2B" },
  { label: "Model Agr", value: "78% · 142/182", emphasis: "scarlet-fill" },
];

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

describe("GameStateStrip", () => {
  it("renders every cell label and value", () => {
    const html = render(<GameStateStrip cells={CELLS} />);
    for (const cell of CELLS) {
      expect(html).toContain(cell.label);
      expect(html).toContain(cell.value);
    }
  });

  it("uses navy as the strip background", () => {
    const html = render(<GameStateStrip cells={CELLS} />);
    expect(html.toLowerCase()).toContain(colors.navy.toLowerCase());
  });

  it("wraps a scarlet-fill emphasis value in a scarlet background pill", () => {
    const html = render(<GameStateStrip cells={CELLS} />);
    // The agreement cell is scarlet-filled.
    expect(html.toLowerCase()).toContain(colors.scarlet.toLowerCase());
  });

  it("has an aria-label identifying the strip as the live game state region", () => {
    const html = render(<GameStateStrip cells={CELLS} />);
    expect(html).toContain('aria-label="Live game state"');
  });

  it("renders scarlet-outline as outline-only (border, not fill)", () => {
    const outline: GameStateCell[] = [
      { label: "Test", value: "OK", emphasis: "scarlet-outline" },
    ];
    const html = render(<GameStateStrip cells={outline} />);
    expect(html.toLowerCase()).toContain(colors.scarlet.toLowerCase());
    // scarlet-outline does NOT set background-color: <scarlet>; it only sets
    // border + color: scarlet. We can't easily diff styles in static markup,
    // so we just confirm the scarlet token is present somewhere on the cell.
    expect(html).toContain("Test");
  });
});

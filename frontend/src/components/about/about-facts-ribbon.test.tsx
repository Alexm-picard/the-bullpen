/**
 * Unit tests for <AboutFactsRibbon>.
 *
 * Covers: all 4 cells render, all 4 figures appear, the navy chrome bg is
 * present, and the strip is display-only (no <a> or click handlers — these
 * are facts, not navigation).
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { FactCell } from "../../data/about-fixtures";
import { theme } from "../../design/theme";
import { colors } from "../../design/broadcast";

import { AboutFactsRibbon } from "./about-facts-ribbon";

const CELLS: FactCell[] = [
  { figure: "133", eyebrow: "Locked", unit: "Decisions" },
  { figure: "7", eyebrow: "Architecture", unit: "ADRs" },
  { figure: "3", eyebrow: "Calibrated", unit: "Models" },
  { figure: "4", eyebrow: "Rolling-Origin", unit: "CV Folds" },
];

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

describe("AboutFactsRibbon", () => {
  it("renders all 4 cells with their figures", () => {
    const html = render(<AboutFactsRibbon cells={CELLS} />);
    expect(html).toContain(">133<");
    expect(html).toContain(">7<");
    expect(html).toContain(">3<");
    expect(html).toContain(">4<");
  });

  it("renders all 4 unit labels", () => {
    const html = render(<AboutFactsRibbon cells={CELLS} />);
    expect(html).toContain("Decisions");
    expect(html).toContain("ADRs");
    expect(html).toContain("Models");
    expect(html).toContain("CV Folds");
  });

  it("renders all 4 eyebrow labels", () => {
    const html = render(<AboutFactsRibbon cells={CELLS} />);
    expect(html).toContain("Locked");
    expect(html).toContain("Architecture");
    expect(html).toContain("Calibrated");
    expect(html).toContain("Rolling-Origin");
  });

  it("is display-only — no anchor elements, no click handlers", () => {
    const html = render(<AboutFactsRibbon cells={CELLS} />);
    expect(html).not.toContain("<a ");
    expect(html).not.toContain("onClick");
  });

  it("uses the navy chrome background", () => {
    const html = render(<AboutFactsRibbon cells={CELLS} />);
    expect(html.toLowerCase()).toContain(colors.chrome.toLowerCase());
  });

  it("has a role=group landmark with an aria-label", () => {
    const html = render(<AboutFactsRibbon cells={CELLS} />);
    expect(html).toContain('role="group"');
    expect(html).toContain('aria-label="Project artifact counts"');
  });
});

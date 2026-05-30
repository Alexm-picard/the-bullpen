/**
 * Unit tests for <CoverSheetHeader>.
 *
 * Covers the two-line nameplate (TONIGHT'S / SLATE on separate <span> blocks),
 * the HeroEyebrow text, the byline strip (date + matchup count + L/R counts),
 * and the mono context line.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { theme } from "../../design/theme";

import { CoverSheetHeader } from "./cover-sheet-header";

const DEFAULT_PROPS = {
  issueDate: "Wed · May 30, 2026",
  matchupCount: 8,
  lhpCount: 3,
  rhpCount: 5,
  issuedAt: "19:05 ET",
  firstPitchWindow: "FIRST PITCH 18:40 ET — 22:15 ET",
};

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

describe("CoverSheetHeader", () => {
  it("renders the eyebrow text", () => {
    const html = render(<CoverSheetHeader {...DEFAULT_PROPS} />);
    expect(html).toContain("The Bullpen");
    expect(html).toContain("Advance Scouting");
  });

  it("renders 'TONIGHT'S' and 'SLATE' as separate block spans", () => {
    const html = render(<CoverSheetHeader {...DEFAULT_PROPS} />);
    // The display:block wrap is what makes the nameplate two-line — assert
    // both words appear inside spans that declare display:block.
    expect(html).toMatch(/style="display:block"[^>]*>Tonight/);
    expect(html).toMatch(/style="display:block"[^>]*>Slate/);
  });

  it("renders the issue date in the byline strip", () => {
    const html = render(<CoverSheetHeader {...DEFAULT_PROPS} />);
    expect(html).toContain("Wed · May 30, 2026");
  });

  it("renders the matchup count", () => {
    const html = render(<CoverSheetHeader {...DEFAULT_PROPS} />);
    expect(html).toContain("8 matchups");
  });

  it("renders L/R starter hand counts", () => {
    const html = render(<CoverSheetHeader {...DEFAULT_PROPS} />);
    expect(html).toContain("3L");
    expect(html).toContain("5R");
  });

  it("renders the issue timestamp and first-pitch window in the mono line", () => {
    const html = render(<CoverSheetHeader {...DEFAULT_PROPS} />);
    expect(html).toContain("19:05 ET");
    expect(html).toContain("FIRST PITCH 18:40 ET");
  });

  it("uses the Saira display font on the h1", () => {
    const html = render(<CoverSheetHeader {...DEFAULT_PROPS} />);
    expect(html).toContain("Saira Condensed");
  });
});

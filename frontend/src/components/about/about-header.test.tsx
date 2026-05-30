/**
 * Unit tests for <AboutHeader>.
 *
 * Covers the eyebrow text, the two-line nameplate (ABOUT / THE BULLPEN on
 * separate <span> blocks), the byline strip (built solo + edition +
 * calendar + weekly hours), and the mono ISSUED line.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { theme } from "../../design/theme";

import { AboutHeader } from "./about-header";

const DEFAULT_PROPS = {
  issueDate: "2026-05-30",
  builtBy: "Built solo",
  edition: "Edition v0.4 (Phase 2a)",
  calendar: "~8–10 mo",
  weeklyHours: "~12–15 h/wk",
};

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

describe("AboutHeader", () => {
  it("renders the eyebrow text", () => {
    const html = render(<AboutHeader {...DEFAULT_PROPS} />);
    expect(html).toContain("The Bullpen");
    expect(html).toContain("Colophon");
    expect(html).toContain("Back Matter");
  });

  it("renders 'About' and 'The Bullpen' as separate block spans", () => {
    const html = render(<AboutHeader {...DEFAULT_PROPS} />);
    // The display:block wrap is what makes the nameplate two-line — assert
    // both words appear inside spans that declare display:block.
    expect(html).toMatch(/style="display:block"[^>]*>About</);
    expect(html).toMatch(/style="display:block"[^>]*>The Bullpen</);
  });

  it("renders the byline containing 'Built solo'", () => {
    const html = render(<AboutHeader {...DEFAULT_PROPS} />);
    expect(html).toContain("Built solo");
  });

  it("renders the edition string", () => {
    const html = render(<AboutHeader {...DEFAULT_PROPS} />);
    expect(html).toContain("Edition v0.4");
    expect(html).toContain("Phase 2a");
  });

  it("renders the calendar and weekly-hours metadata", () => {
    const html = render(<AboutHeader {...DEFAULT_PROPS} />);
    expect(html).toContain("8");
    expect(html).toContain("10 mo");
    expect(html).toContain("12");
    expect(html).toContain("15 h/wk");
  });

  it("renders the ISSUED line with the date", () => {
    const html = render(<AboutHeader {...DEFAULT_PROPS} />);
    expect(html).toContain("ISSUED");
    expect(html).toContain("2026-05-30");
  });

  it("uses the Saira display font on the h1", () => {
    const html = render(<AboutHeader {...DEFAULT_PROPS} />);
    expect(html).toContain("Saira Condensed");
  });
});

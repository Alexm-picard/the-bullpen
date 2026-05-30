/**
 * Unit tests for <CoverSheetFooter>.
 *
 * Covers: identity text, build SHA + date appear in the center slot,
 * methodology link routes to /about, navy chrome background is in effect.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import { theme } from "../../design/theme";
import { colors } from "../../design/tokens";

import { CoverSheetFooter } from "./cover-sheet-footer";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>
      <MemoryRouter>{ui}</MemoryRouter>
    </MantineProvider>,
  );
}

describe("CoverSheetFooter", () => {
  it("renders the identity text", () => {
    const html = render(
      <CoverSheetFooter buildSha="abc1234" buildDate="2026.05.30" />,
    );
    expect(html).toContain("The Bullpen");
    expect(html).toContain("Advance Scouting");
  });

  it("renders the build SHA and date", () => {
    const html = render(
      <CoverSheetFooter buildSha="abc1234" buildDate="2026.05.30" />,
    );
    expect(html).toContain("abc1234");
    expect(html).toContain("2026.05.30");
  });

  it("renders a methodology link to /about", () => {
    const html = render(
      <CoverSheetFooter buildSha="abc1234" buildDate="2026.05.30" />,
    );
    expect(html).toContain('href="/about"');
    expect(html).toContain("Methodology");
  });

  it("uses the navy chrome background", () => {
    const html = render(
      <CoverSheetFooter buildSha="abc1234" buildDate="2026.05.30" />,
    );
    expect(html.toLowerCase()).toContain(colors.navy.toLowerCase());
  });
});

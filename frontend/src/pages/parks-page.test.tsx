/**
 * Smoke test for /parks (Stage 3c Park Factors appendix).
 *
 * Renders the full page inside MemoryRouter + MantineProvider and asserts
 * the section labels + all 30 park abbreviations are present in the markup.
 * The deeper component tests cover the individual primitives.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import { PARK_ROWS } from "../data/parks-fixtures";
import { theme } from "../design/theme";

import ParksPage from "./parks-page";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MemoryRouter>
      <MantineProvider theme={theme}>{ui}</MantineProvider>
    </MemoryRouter>,
  );
}

describe("ParksPage", () => {
  it("renders all three section labels", () => {
    const html = render(<ParksPage />);
    expect(html).toContain("Overview");
    expect(html).toContain("Park Switcher");
    expect(html).toContain("Spotlight");
  });

  it("renders the masthead", () => {
    const html = render(<ParksPage />);
    expect(html).toContain("Park Factors");
    expect(html).toContain("Appendix A");
  });

  it("includes all 30 park abbreviations in the markup", () => {
    const html = render(<ParksPage />);
    for (const row of PARK_ROWS) {
      // Each abbreviation should appear at least once (in the switcher
      // and in the TEAM column of the overview).
      expect(html).toContain(row.team);
    }
  });

  it("renders the methodology line", () => {
    const html = render(<ParksPage />);
    expect(html).toContain("3-yr rolling window");
    expect(html).toContain("n=437,210");
  });

  it("renders the footer build SHA", () => {
    const html = render(<ParksPage />);
    expect(html).toContain("b1b62ec");
  });
});

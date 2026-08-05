/**
 * Smoke test for /parks (Stage 3c Park Factors appendix).
 *
 * Renders the full page inside MemoryRouter + MantineProvider and asserts
 * the section labels + all 30 park abbreviations are present in the markup.
 * The deeper component tests cover the individual primitives.
 */
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";

import { BUILD_SHA } from "../build-info";
import { OBSERVED_HOME_RUN_SPRAY_MAX_DEG, SPRAY_LIMIT_DEG } from "../api/parks";
import { PARK_ROWS } from "../data/parks-fixtures";
import { theme } from "../design/theme";

import ParksPage from "./parks-page";

function render(ui: React.ReactElement): string {
  // The live HR-by-park section (B1) uses TanStack Query; static render leaves it
  // in the loading state, which does not affect the fixture-backed sections these
  // assertions cover.
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return renderToStaticMarkup(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <MantineProvider theme={theme}>{ui}</MantineProvider>
      </MemoryRouter>
    </QueryClientProvider>,
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
    expect(html).toContain(BUILD_SHA);
  });

  it("labels spray direction ABSOLUTELY, since pull and oppo swap with handedness", () => {
    // The label read "- pull / + oppo", which is batter-relative and therefore only correct for a
    // LEFT-handed batter - while this page defaults stand to "R", so the default view was exactly
    // backwards. Training's convention is absolute (+ toward 3B/LF) and the feature pipeline passes
    // spray through unmodified, so the axis has one true description and it is not handedness
    // dependent. Nothing pinned this copy before, which is how it stayed wrong.
    const html = render(<ParksPage />);
    expect(html).toContain("toward LF");
    expect(html).toContain("toward RF");
    expect(html).not.toMatch(/pull/i);
    expect(html).not.toMatch(/oppo/i);
  });

  it("lets the user reach the sprays the model was trained on", () => {
    // The input was clamped to plus/minus 45, which excluded ~9.8% of the training corpus and 195
    // home runs (max 52.7 degrees). A control for exploring spray that cannot reach the model's
    // domain is a worse defect than the label that described it wrongly.
    // Pinned to the EMPIRICAL bound, not to itself: the limit must admit the widest spray ever
    // observed on a home run. Asserting SPRAY_LIMIT_DEG === 90 would only prove the constant equals
    // the constant.
    expect(SPRAY_LIMIT_DEG).toBeGreaterThan(OBSERVED_HOME_RUN_SPRAY_MAX_DEG);
  });
});

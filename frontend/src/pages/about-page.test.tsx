/**
 * Smoke test for /about (slim colophon per [191]/ADR-0017).
 *
 * The page slimmed to: masthead + facts ribbon + stack table + rejected
 * alternatives + colophon footer. Opening Pitch, Model Fleet, Operational
 * Discipline, and Roadmap sections moved to /models/guide or /ops.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";

import { BUILD_DATE, BUILD_SHA } from "../build-info";
import { theme } from "../design/theme";

import AboutPage from "./about-page";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MemoryRouter>
      <MantineProvider theme={theme}>{ui}</MantineProvider>
    </MemoryRouter>,
  );
}

describe("AboutPage ([191] slim colophon)", () => {
  it("renders the two surviving section headings", () => {
    const html = render(<AboutPage />);
    expect(html).toContain("The Stack");
    expect(html).toContain("Intentionally Not Here");
  });

  it("does NOT render the removed sections", () => {
    const html = render(<AboutPage />);
    expect(html).not.toContain("Opening Pitch");
    expect(html).not.toContain("Model Fleet");
    expect(html).not.toContain("Operational Discipline");
    expect(html).not.toContain("Roadmap Honesty");
  });

  it("renders the masthead nameplate", () => {
    const html = render(<AboutPage />);
    expect(html).toMatch(/style="display:block"[^>]*>About/);
    expect(html).toMatch(/style="display:block"[^>]*>The Bullpen/);
    expect(html).toContain("Colophon");
  });

  it("has exactly one <h1> on the page", () => {
    const html = render(<AboutPage />);
    const h1Count = (html.match(/<h1/g) ?? []).length;
    expect(h1Count).toBe(1);
  });

  it("renders the colophon footer with build SHA + date", () => {
    const html = render(<AboutPage />);
    expect(html).toContain(BUILD_SHA);
    expect(html).toContain(BUILD_DATE);
    expect(html).toContain("COLOPHON");
  });

  it("renders the rejected-alternatives tag list", () => {
    const html = render(<AboutPage />);
    expect(html).toContain("LLM for pitch outcome");
    expect(html).toContain("WebSockets");
    expect(html).toContain("Dark mode v1");
  });
});

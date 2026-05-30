/**
 * Smoke test for /about (Stage 3e colophon).
 *
 * Renders the full page inside MemoryRouter + MantineProvider and asserts
 * the masthead + all 6 SectionLabel headings + a single <h1> + the footer
 * SHA appear in the markup. The deeper component tests cover individual
 * primitives.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import { ABOUT_META } from "../data/about-fixtures";
import { theme } from "../design/theme";

import AboutPage from "./about-page";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MemoryRouter>
      <MantineProvider theme={theme}>{ui}</MantineProvider>
    </MemoryRouter>,
  );
}

describe("AboutPage", () => {
  it("renders all 6 SectionLabel headings", () => {
    const html = render(<AboutPage />);
    expect(html).toContain("Opening Pitch");
    expect(html).toContain("The Stack");
    expect(html).toContain("Model Fleet");
    expect(html).toContain("Operational Discipline");
    expect(html).toContain("Intentionally Not Here");
    expect(html).toContain("Roadmap Honesty");
  });

  it("renders the masthead nameplate", () => {
    const html = render(<AboutPage />);
    expect(html).toContain("About");
    expect(html).toContain("The Bullpen");
    expect(html).toContain("Colophon");
  });

  it("has exactly one <h1> on the page", () => {
    const html = render(<AboutPage />);
    const h1Count = (html.match(/<h1/g) ?? []).length;
    expect(h1Count).toBe(1);
  });

  it("renders the facts ribbon figures", () => {
    const html = render(<AboutPage />);
    expect(html).toContain("133");
    expect(html).toContain("Decisions");
    expect(html).toContain("ADRs");
  });

  it("renders the colophon footer with build SHA + date", () => {
    const html = render(<AboutPage />);
    expect(html).toContain(ABOUT_META.buildSha);
    expect(html).toContain(ABOUT_META.buildDate);
    expect(html).toContain("COLOPHON");
  });

  it("renders the rejected-alternatives tag list", () => {
    const html = render(<AboutPage />);
    expect(html).toContain("LLM for pitch outcome");
    expect(html).toContain("WebSockets");
    expect(html).toContain("Dark mode v1");
  });
});

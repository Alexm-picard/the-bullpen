/**
 * Unit tests for <AboutRoadmap>.
 *
 * Covers: the paragraph renders, contains 'Phase 2a' and '2026' substrings,
 * and uses the .about-prose className for the 62ch editorial measure.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { ROADMAP_PARA } from "../../data/about-fixtures";
import { theme } from "../../design/theme";

import { AboutRoadmap } from "./about-roadmap";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

describe("AboutRoadmap", () => {
  it("renders the paragraph", () => {
    const html = render(<AboutRoadmap paragraph={ROADMAP_PARA} />);
    expect(html).toContain("<p");
  });

  it("contains the 'Phase 2a' substring", () => {
    const html = render(<AboutRoadmap paragraph={ROADMAP_PARA} />);
    expect(html).toContain("Phase 2a");
  });

  it("contains the '2026' substring", () => {
    const html = render(<AboutRoadmap paragraph={ROADMAP_PARA} />);
    expect(html).toContain("2026");
  });

  it("uses the .about-prose class for the 62ch editorial measure", () => {
    const html = render(<AboutRoadmap paragraph={ROADMAP_PARA} />);
    expect(html).toContain("about-prose");
  });
});

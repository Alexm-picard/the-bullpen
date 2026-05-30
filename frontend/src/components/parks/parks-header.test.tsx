/**
 * Unit tests for <ParksHeader>.
 *
 * Covers:
 *   - Eyebrow string present
 *   - Two-line nameplate: "PARK" and "FACTORS" each on their own line
 *   - Byline strip contains "30 parks"
 *   - Mono context line contains the data-window + model tag
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { theme } from "../../design/theme";

import { ParksHeader } from "./parks-header";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

const PROPS = {
  edition: "2026.05.30",
  sampleN: 437_210,
  dataWindow: "DATA WINDOW 2023 — 2025",
  modelTag: "MODEL park_factor_v2",
} as const;

describe("ParksHeader", () => {
  it("renders the eyebrow string", () => {
    const html = render(<ParksHeader {...PROPS} />);
    expect(html).toContain("Park Factors");
    expect(html).toContain("Appendix A");
  });

  it("renders PARK and FACTORS on separate lines via block spans", () => {
    const html = render(<ParksHeader {...PROPS} />);
    // Each line is wrapped in <span style="display: block">. We check both
    // strings appear AND that the markup contains two display:block spans
    // inside the h1.
    expect(html).toContain(">Park<");
    expect(html).toContain(">Factors<");
    const blockSpanMatches =
      html.match(/<span[^>]*display:\s*block[^>]*>/g) ?? [];
    expect(blockSpanMatches.length).toBeGreaterThanOrEqual(2);
  });

  it("includes 30 parks in the byline strip", () => {
    const html = render(<ParksHeader {...PROPS} />);
    expect(html).toContain("30 parks");
  });

  it("includes the sample-size n in the byline", () => {
    const html = render(<ParksHeader {...PROPS} />);
    expect(html).toContain("n=437,210");
  });

  it("includes the data window + model tag in the mono context line", () => {
    const html = render(<ParksHeader {...PROPS} />);
    expect(html).toContain("DATA WINDOW 2023 — 2025");
    expect(html).toContain("MODEL park_factor_v2");
  });
});

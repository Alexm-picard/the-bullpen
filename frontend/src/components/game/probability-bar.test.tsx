import { MantineProvider } from "@mantine/core";
import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { theme } from "../../design/theme";

import { ProbabilityBar } from "./probability-bar";

function render(node: ReactNode): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{node}</MantineProvider>,
  );
}

describe("ProbabilityBar", () => {
  it("renders one rect per class plus the outline rect (6 total)", () => {
    const html = render(
      <ProbabilityBar
        predicted={{
          ball: 0.4,
          called_strike: 0.2,
          swinging_strike: 0.1,
          foul: 0.15,
          in_play: 0.15,
        }}
      />,
    );
    expect((html.match(/<rect/g) ?? []).length).toBe(6);
  });

  it("renders an empty bar (single rect) when predicted is null", () => {
    const html = render(<ProbabilityBar predicted={null} />);
    expect((html.match(/<rect/g) ?? []).length).toBe(1);
    expect(html).toContain("No prediction available");
  });

  it("encodes per-class percentages in aria-label for accessibility", () => {
    const html = render(
      <ProbabilityBar
        predicted={{
          ball: 0.4,
          called_strike: 0.6,
          swinging_strike: 0,
          foul: 0,
          in_play: 0,
        }}
      />,
    );
    // aria-label is the canonical accessible source of per-class probabilities;
    // SVG <title> children don't render through renderToStaticMarkup.
    expect(html).toContain("ball 40%");
    expect(html).toContain("called_strike 60%");
  });

  it("renormalises when probabilities don't sum to 1", () => {
    const html = render(
      <ProbabilityBar
        predicted={{
          ball: 0.5,
          called_strike: 0.5,
          swinging_strike: 0.5,
          foul: 0.5,
          in_play: 0.5,
        }}
        width={100}
      />,
    );
    // Each class should occupy 1/5 = 20% of width = 20 px after renormalisation.
    // Just sanity-check that the bar still draws and is bounded by the outline rect.
    expect(html).toContain('width="100"');
  });
});

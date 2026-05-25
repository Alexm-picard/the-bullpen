/**
 * Snapshot + structure test for StadiumSvg. Same renderToStaticMarkup pattern
 * as TokenSampleCard / ReliabilityDiagram.
 */
import { MantineProvider } from "@mantine/core";
import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { theme } from "../../design/theme";

import { StadiumSvg } from "./stadium-svg";

function render(node: ReactNode): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{node}</MantineProvider>,
  );
}

describe("StadiumSvg", () => {
  it("renders an SVG with the parkId aria-label and a use href to the field symbol", () => {
    const html = render(<StadiumSvg parkId="NYY" />);
    expect(html).toContain('aria-label="NYY field"');
    expect(html).toContain('<use href="/parks/NYY.svg#field"');
    expect(html).toContain('viewBox="0 0 500 500"');
  });

  it("accepts a custom aria-label and size", () => {
    const html = render(
      <StadiumSvg parkId="COL" ariaLabel="Coors Field" size={200} />,
    );
    expect(html).toContain('aria-label="Coors Field"');
    expect(html).toContain('width="200"');
  });

  it("nests children in a bullpen-overlay group", () => {
    const html = render(
      <StadiumSvg parkId="BOS">
        <circle cx="100" cy="100" r="5" />
      </StadiumSvg>,
    );
    expect(html).toContain("bullpen-overlay");
    expect(html).toContain("<circle");
  });

  it("snapshots stable structure for a known park", () => {
    expect(render(<StadiumSvg parkId="NYY" />)).toMatchSnapshot();
  });
});

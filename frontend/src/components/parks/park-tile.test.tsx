/**
 * Tests for <ParkTile>.
 *
 * Static-markup rendering only — the hover/focus border-color transitions are
 * CSS and verified visually by Playwright. We assert the always-on chrome:
 * rank chip, park id, name, P(HR) value rendered into the probability bar's
 * aria-label, role="button" + tabIndex for keyboard activation, and that the
 * landing dot SVG circle appears only when probHr is non-null.
 */
import { MantineProvider } from "@mantine/core";
import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { theme } from "../../design/theme";

import { ParkTile } from "./park-tile";

function render(node: ReactNode): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{node}</MantineProvider>,
  );
}

describe("ParkTile", () => {
  it("renders rank, park id, and park name", () => {
    const html = render(
      <ParkTile
        parkId="COL"
        name="Coors Field"
        rank={1}
        probHr={0.512}
        landingDistanceFt={420}
        sprayAngleDeg={0}
        onSelect={() => undefined}
      />,
    );
    expect(html).toContain("Coors Field");
    expect(html).toContain("COL");
    expect(html).toContain(">1<");
  });

  it("renders the probability via the thin bar's aria-label", () => {
    const html = render(
      <ParkTile
        parkId="COL"
        name="Coors Field"
        rank={1}
        probHr={0.512}
        landingDistanceFt={420}
        sprayAngleDeg={0}
        onSelect={() => undefined}
      />,
    );
    // ProbBarThin emits a percent string into the aria-label.
    expect(html).toContain("Coors Field home run probability 51.2 percent");
  });

  it("renders the landing-zone dot when probHr is present", () => {
    const html = render(
      <ParkTile
        parkId="COL"
        name="Coors Field"
        rank={1}
        probHr={0.4}
        landingDistanceFt={420}
        sprayAngleDeg={0}
        onSelect={() => undefined}
      />,
    );
    expect(html).toContain("<circle");
  });

  it("omits the landing-zone dot when probHr is null", () => {
    const html = render(
      <ParkTile
        parkId="OAK"
        name="Oakland Coliseum"
        rank={7}
        probHr={null}
        landingDistanceFt={420}
        sprayAngleDeg={0}
        onSelect={() => undefined}
      />,
    );
    expect(html).not.toContain("<circle");
  });

  it("omits the landing-zone dot while loading", () => {
    const html = render(
      <ParkTile
        parkId="OAK"
        name="Oakland Coliseum"
        rank={7}
        probHr={0.3}
        isLoading
        landingDistanceFt={420}
        sprayAngleDeg={0}
        onSelect={() => undefined}
      />,
    );
    expect(html).not.toContain("<circle");
  });

  it("exposes a button role and tabindex for keyboard activation", () => {
    const html = render(
      <ParkTile
        parkId="COL"
        name="Coors Field"
        rank={1}
        probHr={0.5}
        landingDistanceFt={420}
        sprayAngleDeg={0}
        onSelect={() => undefined}
      />,
    );
    expect(html).toContain('role="button"');
    expect(html).toContain('tabindex="0"');
  });

  it("includes an aria-label that mentions rank and name", () => {
    const html = render(
      <ParkTile
        parkId="COL"
        name="Coors Field"
        rank={1}
        probHr={0.5}
        landingDistanceFt={420}
        sprayAngleDeg={0}
        onSelect={() => undefined}
      />,
    );
    expect(html).toContain("Rank 1: Coors Field");
  });

  it("renders a thin probability bar (progressbar role)", () => {
    const html = render(
      <ParkTile
        parkId="COL"
        name="Coors Field"
        rank={1}
        probHr={0.5}
        landingDistanceFt={420}
        sprayAngleDeg={0}
        onSelect={() => undefined}
      />,
    );
    expect(html).toContain('role="progressbar"');
  });
});

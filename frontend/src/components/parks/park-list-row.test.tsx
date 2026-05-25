/**
 * Tests for <ParkListRow>.
 *
 * Static-markup rendering only — the hover background change is CSS-only and
 * verified visually by Playwright. We cover the always-on chrome: rank /
 * name+id / meta (fence + altitude) / P(HR) value, the probability bar's
 * aria-label, role="button" + tabIndex for keyboard activation, and graceful
 * handling of null altitudes + null probabilities.
 */
import { MantineProvider } from "@mantine/core";
import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { theme } from "../../design/theme";

import { ParkListRow } from "./park-list-row";

function render(node: ReactNode): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{node}</MantineProvider>,
  );
}

describe("ParkListRow", () => {
  it("renders rank, park id, and park name", () => {
    const html = render(
      <ParkListRow
        parkId="COL"
        name="Coors Field"
        rank={1}
        probHr={0.512}
        shortFenceFt={347}
        altitudeM={1580}
        onSelect={() => undefined}
      />,
    );
    expect(html).toContain("Coors Field");
    expect(html).toContain("COL");
    expect(html).toContain(">1<");
  });

  it("renders the short fence + altitude meta in one line", () => {
    const html = render(
      <ParkListRow
        parkId="COL"
        name="Coors Field"
        rank={1}
        probHr={0.512}
        shortFenceFt={347}
        altitudeM={1580}
        onSelect={() => undefined}
      />,
    );
    expect(html).toContain("347 ft · 1580 m");
  });

  it("renders the probability as a percentage", () => {
    const html = render(
      <ParkListRow
        parkId="COL"
        name="Coors Field"
        rank={1}
        probHr={0.512}
        shortFenceFt={347}
        altitudeM={1580}
        onSelect={() => undefined}
      />,
    );
    expect(html).toContain("51.2%");
  });

  it("renders an em-dash when probHr is null", () => {
    const html = render(
      <ParkListRow
        parkId="COL"
        name="Coors Field"
        rank={1}
        probHr={null}
        shortFenceFt={347}
        altitudeM={1580}
        onSelect={() => undefined}
      />,
    );
    expect(html).toContain("—");
  });

  it("renders an em-dash for null altitude", () => {
    const html = render(
      <ParkListRow
        parkId="OAK"
        name="Oakland Coliseum"
        rank={7}
        probHr={0.2}
        shortFenceFt={330}
        altitudeM={null}
        onSelect={() => undefined}
      />,
    );
    expect(html).toContain("330 ft · —");
  });

  it("exposes a button role and tabindex for keyboard activation", () => {
    const html = render(
      <ParkListRow
        parkId="COL"
        name="Coors Field"
        rank={1}
        probHr={0.5}
        shortFenceFt={347}
        altitudeM={1580}
        onSelect={() => undefined}
      />,
    );
    expect(html).toContain('role="button"');
    expect(html).toContain('tabindex="0"');
  });

  it("renders a thin probability bar (progressbar role)", () => {
    const html = render(
      <ParkListRow
        parkId="COL"
        name="Coors Field"
        rank={1}
        probHr={0.5}
        shortFenceFt={347}
        altitudeM={1580}
        onSelect={() => undefined}
      />,
    );
    expect(html).toContain('role="progressbar"');
  });

  it("includes an aria-label that mentions rank and name", () => {
    const html = render(
      <ParkListRow
        parkId="COL"
        name="Coors Field"
        rank={1}
        probHr={0.5}
        shortFenceFt={347}
        altitudeM={1580}
        onSelect={() => undefined}
      />,
    );
    expect(html).toContain("Rank 1: Coors Field");
  });
});

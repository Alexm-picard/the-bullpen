/**
 * Tests for <ParkDetailModal>.
 *
 * Mantine's Modal portal-mounts client-side only; SSR renders just its style
 * sheet. We test the visible body through <ParkDetailModalBody>, which is the
 * exact content the modal renders when opened. Open/close, focus return, and
 * Escape/backdrop dismiss are Mantine's responsibility and verified visually
 * by Playwright in the redesign verification pass.
 */
import { MantineProvider } from "@mantine/core";
import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { theme } from "../../design/theme";

import { ParkDetailModalBody, PARK_META_FOR_TESTS } from "./park-detail-modal";

function render(node: ReactNode): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{node}</MantineProvider>,
  );
}

describe("ParkDetailModalBody", () => {
  it("renders the park name", () => {
    const html = render(
      <ParkDetailModalBody
        parkId="COL"
        meta={PARK_META_FOR_TESTS.COL}
        probHr={0.512}
        landingDistanceFt={420}
        sprayAngleDeg={0}
        modelName="batted_ball_v1"
        modelVersion="2025.11.04-shadow"
      />,
    );
    expect(html).toContain("Coors Field");
  });

  it("renders the altitude sub-line in meters", () => {
    const html = render(
      <ParkDetailModalBody
        parkId="COL"
        meta={PARK_META_FOR_TESTS.COL}
        probHr={0.512}
        landingDistanceFt={420}
        sprayAngleDeg={0}
        modelName="m"
        modelVersion="v"
      />,
    );
    expect(html).toMatch(/altitude\s+\d+\s+m/);
  });

  it("renders the probability as a percentage with one decimal", () => {
    const html = render(
      <ParkDetailModalBody
        parkId="COL"
        meta={PARK_META_FOR_TESTS.COL}
        probHr={0.512}
        landingDistanceFt={420}
        sprayAngleDeg={0}
        modelName="m"
        modelVersion="v"
      />,
    );
    expect(html).toContain("51.2%");
  });

  it("renders an em-dash for unknown P(HR)", () => {
    const html = render(
      <ParkDetailModalBody
        parkId="COL"
        meta={PARK_META_FOR_TESTS.COL}
        probHr={null}
        landingDistanceFt={420}
        sprayAngleDeg={0}
        modelName="m"
        modelVersion="v"
      />,
    );
    expect(html).toContain(">—<");
  });

  it("renders all 5 fence rows from park-meta.json", () => {
    const html = render(
      <ParkDetailModalBody
        parkId="BOS"
        meta={PARK_META_FOR_TESTS.BOS}
        probHr={0.4}
        landingDistanceFt={400}
        sprayAngleDeg={0}
        modelName="m"
        modelVersion="v"
      />,
    );
    expect(html).toContain(">LF<");
    expect(html).toContain(">LC<");
    expect(html).toContain(">CF<");
    expect(html).toContain(">RC<");
    expect(html).toContain(">RF<");
  });

  it("renders a fence note when present (BOS Green Monster)", () => {
    const html = render(
      <ParkDetailModalBody
        parkId="BOS"
        meta={PARK_META_FOR_TESTS.BOS}
        probHr={0.4}
        landingDistanceFt={400}
        sprayAngleDeg={0}
        modelName="m"
        modelVersion="v"
      />,
    );
    expect(html).toContain("Green Monster");
  });

  it("renders the model name@version footer credit", () => {
    const html = render(
      <ParkDetailModalBody
        parkId="COL"
        meta={PARK_META_FOR_TESTS.COL}
        probHr={0.4}
        landingDistanceFt={400}
        sprayAngleDeg={0}
        modelName="batted_ball_v1"
        modelVersion="2025.11.04-shadow"
      />,
    );
    expect(html).toContain("batted_ball_v1");
    expect(html).toContain("2025.11.04-shadow");
  });

  it("renders an em-dash for missing model identity", () => {
    const html = render(
      <ParkDetailModalBody
        parkId="COL"
        meta={PARK_META_FOR_TESTS.COL}
        probHr={0.4}
        landingDistanceFt={400}
        sprayAngleDeg={0}
        modelName={null}
        modelVersion={null}
      />,
    );
    expect(html).toContain("Modeled with —@—");
  });

  it("renders a labelled table of fence depths", () => {
    const html = render(
      <ParkDetailModalBody
        parkId="COL"
        meta={PARK_META_FOR_TESTS.COL}
        probHr={0.4}
        landingDistanceFt={400}
        sprayAngleDeg={0}
        modelName="m"
        modelVersion="v"
      />,
    );
    expect(html).toContain('role="table"');
    expect(html).toContain("Coors Field fence depths");
  });
});

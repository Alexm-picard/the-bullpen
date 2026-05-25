import { MantineProvider } from "@mantine/core";
import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { theme } from "../../design/theme";

import { estimateLandingDistanceFt } from "./estimate-landing";
import { ParkThumbnailPolished } from "./park-thumbnail-polished";

function render(node: ReactNode): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{node}</MantineProvider>,
  );
}

describe("ParkThumbnailPolished", () => {
  it("renders parkId, name, P(HR) and a landing-zone dot when probHr is set", () => {
    const html = render(
      <ParkThumbnailPolished
        parkId="NYY"
        name="Yankee Stadium"
        probHr={0.421}
        landingDistanceFt={400}
        sprayAngleDeg={0}
      />,
    );
    expect(html).toContain(">NYY<");
    expect(html).toContain("Yankee Stadium");
    expect(html).toContain("0.421");
    expect(html).toContain("<rect");
    expect(html).toContain("<circle");
  });

  it("hides the landing dot when probHr is null", () => {
    const html = render(
      <ParkThumbnailPolished
        parkId="BOS"
        name="Fenway Park"
        probHr={null}
        landingDistanceFt={400}
        sprayAngleDeg={0}
      />,
    );
    expect(html).not.toContain("<circle");
    expect(html).toContain("—");
  });

  it("hides the tint + dot during loading even when probHr is set", () => {
    const html = render(
      <ParkThumbnailPolished
        parkId="COL"
        name="Coors Field"
        probHr={0.6}
        landingDistanceFt={420}
        sprayAngleDeg={0}
        isLoading
      />,
    );
    expect(html).not.toContain("<rect");
    expect(html).not.toContain("<circle");
  });
});

describe("estimateLandingDistanceFt", () => {
  it("returns ~400 ft for the canonical 110 mph / 28° input", () => {
    const d = estimateLandingDistanceFt(110, 28);
    expect(d).toBeGreaterThan(380);
    expect(d).toBeLessThan(420);
  });

  it("returns a shorter distance at lower exit velocity", () => {
    expect(estimateLandingDistanceFt(85, 28)).toBeLessThan(
      estimateLandingDistanceFt(110, 28),
    );
  });

  it("falls off as launch angle leaves the 28° sweet spot", () => {
    const center = estimateLandingDistanceFt(110, 28);
    expect(estimateLandingDistanceFt(110, 8)).toBeLessThan(center);
    expect(estimateLandingDistanceFt(110, 55)).toBeLessThan(center);
  });

  it("clamps to a floor for extreme inputs", () => {
    expect(estimateLandingDistanceFt(60, -5)).toBeGreaterThanOrEqual(60);
  });
});

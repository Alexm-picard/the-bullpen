import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { PARK_ROWS } from "../../data/parks-fixtures";

import { ParkHrHeatmap } from "./park-hr-heatmap";

describe("ParkHrHeatmap", () => {
  it("renders one labeled row per park with its P(HR), sorted descending", () => {
    const probHrByPark = { COL: 0.12, NYY: 0.09, SD: 0.04 };
    const html = renderToStaticMarkup(
      <ParkHrHeatmap probHrByPark={probHrByPark} parkRows={PARK_ROWS} />,
    );

    // P(HR) printed alongside the color (the a11y rule).
    expect(html).toContain("12.0%");
    expect(html).toContain("9.0%");
    expect(html).toContain("4.0%");

    // Sorted descending: the most HR-prone park's row comes first in the markup.
    expect(html.indexOf("park-hr-row-COL")).toBeLessThan(
      html.indexOf("park-hr-row-SD"),
    );
  });

  it("maps a park id to its full name", () => {
    const html = renderToStaticMarkup(
      <ParkHrHeatmap probHrByPark={{ COL: 0.1 }} parkRows={PARK_ROWS} />,
    );
    const coors = PARK_ROWS.find((p) => p.id === "COL");
    expect(coors).toBeDefined();
    expect(html).toContain(coors!.parkName);
  });

  it("shows an honest empty state when no parks are returned", () => {
    const html = renderToStaticMarkup(
      <ParkHrHeatmap probHrByPark={{}} parkRows={PARK_ROWS} />,
    );
    expect(html).toContain("No per-park probabilities");
  });
});

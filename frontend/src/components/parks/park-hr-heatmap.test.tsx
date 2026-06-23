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

  it("scales bar width to the absolute P(HR), not relative to the 30-park spread", () => {
    // Near-equal parks must read near-equal. The old relative normalisation made the min park a
    // 20% stub and the max a full bar; absolute scaling renders each park at its own probability.
    const html = renderToStaticMarkup(
      <ParkHrHeatmap
        probHrByPark={{ ATL: 0.511, BOS: 0.5 }}
        parkRows={PARK_ROWS}
      />,
    );
    expect(html).toContain("width:50.0%"); // BOS at its absolute P(HR), not a 20% stub
    expect(html).toContain("width:51.1%"); // ATL barely longer, not a full bar
  });

  it("renders a proportionally small bar for a low absolute P(HR)", () => {
    const html = renderToStaticMarkup(
      <ParkHrHeatmap probHrByPark={{ SD: 0.05 }} parkRows={PARK_ROWS} />,
    );
    expect(html).toContain("width:5.0%");
  });
});

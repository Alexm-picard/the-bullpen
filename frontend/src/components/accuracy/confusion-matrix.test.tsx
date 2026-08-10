/**
 * Smoke test for <ConfusionMatrix> (Phase 3 PR-gamma).
 *
 * Asserts the grid renders the integer counts + axis labels for a well-formed
 * matrix, and that the built-in empty path renders an explanatory note (never
 * an empty grid) for an empty/malformed matrix.
 */
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { ConfusionMatrix } from "./confusion-matrix";

describe("ConfusionMatrix", () => {
  it("renders integer counts and axis labels", () => {
    const labels = ["out", "1b", "2b", "3b", "hr"];
    const matrix = [
      [120, 4, 2, 0, 1],
      [8, 30, 3, 0, 0],
      [3, 5, 18, 1, 0],
      [0, 0, 2, 4, 0],
      [1, 0, 0, 0, 12],
    ];
    const html = renderToStaticMarkup(
      <ConfusionMatrix labels={labels} matrix={matrix} caption="home-park" />,
    );
    // Labels present on both axes (rendered once per axis).
    expect(html).toContain("out");
    expect(html).toContain("hr");
    // Integer counts rendered as text (diagonal + an off-diagonal).
    expect(html).toContain("120");
    expect(html).toContain("30");
    expect(html).toContain("18");
    // role="img" with an aria-label for the whole grid.
    expect(html).toContain('role="img"');
    expect(html).toContain("aria-label");
    // Caption surfaced.
    expect(html).toContain("home-park");
  });

  it("renders the empty path for an empty matrix", () => {
    const html = renderToStaticMarkup(
      <ConfusionMatrix labels={[]} matrix={[]} />,
    );
    expect(html).toContain("no scored events");
  });

  it("renders the empty path for a malformed (non-square) matrix", () => {
    const html = renderToStaticMarkup(
      <ConfusionMatrix labels={["out", "hr"]} matrix={[[1, 2, 3]]} />,
    );
    expect(html).toContain("no scored events");
  });
});

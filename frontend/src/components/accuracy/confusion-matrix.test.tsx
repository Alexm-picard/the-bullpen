/**
 * <ConfusionMatrix> (editorial re-skin, decision [199]): a real table whose counts a screen
 * reader can reach, agreement carried by ink weight (a class), and an explanatory empty path
 * (never an empty grid) for an empty or malformed matrix.
 */
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { ConfusionMatrix } from "./confusion-matrix";

const LABELS = ["out", "1b", "2b", "3b", "hr"];
const MATRIX = [
  [261402, 4, 2, 0, 1],
  [8, 30, 3, 0, 0],
  [3, 5, 18, 1, 0],
  [0, 0, 2, 4, 0],
  [1, 0, 0, 0, 12],
];

describe("ConfusionMatrix", () => {
  it("renders a real table with row and column headers, formatted counts and the caption", () => {
    const html = renderToStaticMarkup(
      <ConfusionMatrix labels={LABELS} matrix={MATRIX} caption="home-park" />,
    );
    expect(html).toContain("<table");
    expect(html).not.toContain('role="img"');
    expect(html).toContain('<th scope="row" abbr="hr" title="hr">hr</th>');
    expect(html).toContain('<th scope="col" abbr="out" title="out">Out</th>');
    expect(html).toContain("261,402");
    expect(html).toContain("<caption>home-park</caption>");
  });

  it("abbreviates the known outcome codes for agate width, keeping the full name for assistive tech", () => {
    const html = renderToStaticMarkup(
      <ConfusionMatrix
        labels={["out", "single", "double", "triple", "home_run"]}
        matrix={MATRIX}
      />,
    );
    expect(html).toContain(
      '<th scope="col" abbr="home run" title="home run">HR</th>',
    );
    expect(html).toContain(
      '<th scope="row" abbr="single" title="single">1B</th>',
    );
  });

  it("marks exactly the diagonal cells as agreement (ink weight, not colour)", () => {
    const html = renderToStaticMarkup(
      <ConfusionMatrix labels={LABELS} matrix={MATRIX} />,
    );
    expect(html.match(/class="acc-cm__hit"/g)).toHaveLength(LABELS.length);
    expect(html).toContain('<td class="acc-cm__hit">30</td>');
    expect(html).not.toMatch(/style="[^"]*(background|color)/);
  });

  it("renders the empty path for an empty matrix", () => {
    const html = renderToStaticMarkup(
      <ConfusionMatrix labels={[]} matrix={[]} />,
    );
    expect(html).toContain("no scored events");
    expect(html).not.toContain("<table");
  });

  it("renders the empty path for a malformed (non-square) matrix", () => {
    const html = renderToStaticMarkup(
      <ConfusionMatrix labels={["out", "hr"]} matrix={[[1, 2, 3]]} />,
    );
    expect(html).toContain("no scored events");
  });
});

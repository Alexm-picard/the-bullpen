import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { ReliabilityBin } from "../../api/rolling-accuracy";
import { theme } from "../../design/theme";

import { ReliabilityChart } from "./reliability-chart";

const BINS: ReliabilityBin[] = [
  {
    lower: 0.2,
    upper: 0.3,
    n: 1020,
    hits: 275,
    meanConfidence: 0.262,
    observed: 0.27,
  },
  {
    lower: 0.5,
    upper: 0.6,
    n: 11180,
    hits: 6037,
    meanConfidence: 0.548,
    observed: 0.54,
  },
  {
    lower: 0.8,
    upper: 0.9,
    n: 24,
    hits: 17,
    meanConfidence: 0.84,
    observed: 0.72,
  },
];

function render(bins = BINS, maxSide = 300) {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>
      <ReliabilityChart
        bins={bins}
        minReadableN={30}
        maxSide={maxSide}
        label="pitch_outcome_post calibration, last 30 days"
        tableCaption="pitch_outcome_post: Calibration, last 30 days"
      />
    </MantineProvider>,
  );
}

// Geometry before measurement: plot side = maxSide (300), margins l34 t8.
const px = (p: number) => 34 + p * 300;
const py = (p: number) => 8 + (1 - p) * 300;

describe("ReliabilityChart", () => {
  it("is a labelled image with a hidden table listing every bin", () => {
    const html = render();
    expect(html).toContain('role="img"');
    expect(html).toContain(
      'aria-label="pitch_outcome_post calibration, last 30 days"',
    );
    expect(html).toContain(
      "<caption>pitch_outcome_post: Calibration, last 30 days</caption>",
    );
    expect(html.match(/<tr>/g)?.length).toBe(1 + BINS.length);
    expect(html).toContain("0.8 to 0.9 (too few to read)");
    expect(html).toContain("11,180");
  });

  it("plots x at the bin's MEAN confidence, not its centre", () => {
    const html = render();
    expect(html).toContain(`cx="${px(0.548)}"`);
    expect(html).not.toContain(`cx="${px(0.55)}"`);
    expect(html).toContain(`cy="${py(0.54)}"`);
  });

  it("draws bins below the readable floor hollow and leaves them off the line", () => {
    const html = render();
    expect(html.match(/data-thin="true"/g)).toHaveLength(1);
    expect(html.match(/data-thin="false"/g)).toHaveLength(2);
    const d = /<path class="acc-chart__line" d="([^"]+)"/.exec(html)?.[1] ?? "";
    expect(d.split(/[ML]/).filter(Boolean)).toHaveLength(2);
    expect(d).not.toContain(px(0.84).toFixed(1));
  });

  it("fixes both axes at 0..1 and draws the perfect-calibration diagonal corner to corner", () => {
    const html = render();
    expect(html).toContain(
      `<line class="acc-chart__diag" x1="${px(0)}" y1="${py(0)}" x2="${px(1)}" y2="${py(1)}"></line>`,
    );
    expect(html).toContain(">1.0</text>");
  });

  it("uses only ink-token classes: no accent, no inline colour", () => {
    const html = render();
    expect(html).not.toMatch(/accent|fill="#|stroke="#|style="[^"]*color/);
  });

  it("caps the plot at maxSide", () => {
    const html = render(BINS, 440);
    expect(html).toContain(`width="${440 + 34 + 8}"`);
  });
});

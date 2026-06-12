/**
 * Snapshot + state tests for the SVG reliability diagram. Same renderToStaticMarkup
 * pattern as TokenSampleCard — no jsdom dep.
 */
import { MantineProvider } from "@mantine/core";
import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { CalibrationBin } from "../../api/players";
import { theme } from "../../design/theme";
import { colors } from "../../design/broadcast";

import {
  MIN_SAMPLE_THRESHOLD,
  ReliabilityDiagram,
} from "./reliability-diagram";

const FIXTURE_BINS: CalibrationBin[] = [
  { binStart: 0.0, binEnd: 0.1, predicted: 0.05, actual: 0.06, n: 120 },
  { binStart: 0.4, binEnd: 0.5, predicted: 0.45, actual: 0.43, n: 220 },
  { binStart: 0.8, binEnd: 0.9, predicted: 0.85, actual: 0.82, n: 80 },
];

function render(node: ReactNode): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{node}</MantineProvider>,
  );
}

describe("ReliabilityDiagram", () => {
  it("renders a stable snapshot for the 3-bin fixture", () => {
    expect(
      render(<ReliabilityDiagram bins={FIXTURE_BINS} />),
    ).toMatchSnapshot();
  });

  it("draws an SVG with three circles for three bins", () => {
    const html = render(<ReliabilityDiagram bins={FIXTURE_BINS} />);
    expect(html).toContain("<svg");
    expect((html.match(/<circle/g) ?? []).length).toBeGreaterThanOrEqual(3);
  });

  it("uses the brand accent for the diagonal reference line", () => {
    const html = render(<ReliabilityDiagram bins={FIXTURE_BINS} />);
    expect(html.toLowerCase()).toContain(colors.goldInk.toLowerCase());
  });

  it("shows insufficient-data placeholder below the threshold", () => {
    const lowN: CalibrationBin[] = [
      { binStart: 0.4, binEnd: 0.5, predicted: 0.45, actual: 0.43, n: 5 },
    ];
    const html = render(<ReliabilityDiagram bins={lowN} />);
    expect(html).toContain(`Insufficient data`);
    expect(html).toContain(String(MIN_SAMPLE_THRESHOLD));
    expect(html).not.toContain("<svg");
  });

  it("shows loading state explicitly", () => {
    const html = render(<ReliabilityDiagram bins={undefined} isLoading />);
    expect(html).toContain("Loading calibration");
  });

  it("shows error state with the message", () => {
    const html = render(
      <ReliabilityDiagram bins={undefined} isError errorMessage="HTTP 500" />,
    );
    expect(html).toContain("Could not load calibration");
    expect(html).toContain("HTTP 500");
  });
});

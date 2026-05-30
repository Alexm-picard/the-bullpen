/**
 * Unit tests for <LatencyDetailTable>.
 *
 * Covers: all 4 percentile columns render, all model rows render, latency tint
 * applied via the condFormat ramp, mono "ms" formatting on cells.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { LATENCY_BY_MODEL } from "../../data/ops-fixtures";
import { theme } from "../../design/theme";
import { colors } from "../../design/tokens";

import { LatencyDetailTable } from "./latency-detail-table";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

describe("LatencyDetailTable", () => {
  it("renders all four percentile column headers", () => {
    const html = render(<LatencyDetailTable rows={LATENCY_BY_MODEL} />);
    expect(html).toContain("p50");
    expect(html).toContain("p95");
    expect(html).toContain("p99");
    expect(html).toContain("p99.9");
  });

  it("renders all model rows", () => {
    const html = render(<LatencyDetailTable rows={LATENCY_BY_MODEL} />);
    for (const r of LATENCY_BY_MODEL) {
      expect(html).toContain(r.label);
    }
  });

  it("formats values with a 'ms' unit", () => {
    const html = render(<LatencyDetailTable rows={LATENCY_BY_MODEL} />);
    expect(html).toContain("12 ms");
    expect(html).toContain("48 ms");
    expect(html).toContain("84 ms");
  });

  it("applies cellColor tints from the condFormat ramp", () => {
    const html = render(<LatencyDetailTable rows={LATENCY_BY_MODEL} />);
    const html_lower = html.toLowerCase();
    const hasAnyCondFormat = Object.values(colors.condFormat).some((hex) =>
      html_lower.includes(hex.toLowerCase()),
    );
    expect(hasAnyCondFormat).toBe(true);
  });

  it("renders the optional caption when provided", () => {
    const html = render(
      <LatencyDetailTable
        rows={LATENCY_BY_MODEL}
        caption="Latency by percentile"
      />,
    );
    expect(html).toContain("Latency by percentile");
  });
});

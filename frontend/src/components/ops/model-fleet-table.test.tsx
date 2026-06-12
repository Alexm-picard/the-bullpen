/**
 * Unit tests for <ModelFleetTable>.
 *
 * Covers: 4 fleet rows render, p99 column present, p50 NOT present (locked
 * pick L3), cellColor tints applied to drift + latency columns, ECE Δ formats
 * with sign, state column renders all three values (LIVE / SHADOW / AWAITING).
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { MODEL_FLEET } from "../../data/ops-fixtures";
import { theme } from "../../design/theme";
import { colors } from "../../design/broadcast";

import { ModelFleetTable } from "./model-fleet-table";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

describe("ModelFleetTable", () => {
  it("renders all four fleet rows", () => {
    const html = render(<ModelFleetTable rows={MODEL_FLEET} />);
    expect(html).toContain("pitch_outcome_pre v3.2");
    expect(html).toContain("batted_ball v1.4");
    expect(html).toContain("pitch_outcome_pre v3.3");
    expect(html).toContain("lr_baseline v1.0");
  });

  it("includes the p99 column but NOT p50 (locked pick L3)", () => {
    const html = render(<ModelFleetTable rows={MODEL_FLEET} />);
    expect(html).toContain("p99·ms");
    expect(html).not.toContain("p50");
  });

  it("includes the state and traffic columns", () => {
    const html = render(<ModelFleetTable rows={MODEL_FLEET} />);
    expect(html).toContain("State");
    expect(html).toContain("Traffic");
  });

  it("renders all three registry states", () => {
    const html = render(<ModelFleetTable rows={MODEL_FLEET} />);
    expect(html).toContain("LIVE");
    expect(html).toContain("SHADOW");
    expect(html).toContain("AWAITING-PROMOTION");
  });

  it("renders predictions with thousands separators", () => {
    const html = render(<ModelFleetTable rows={MODEL_FLEET} />);
    expect(html).toContain("12,400");
  });

  it("renders ECE delta with a sign", () => {
    const html = render(<ModelFleetTable rows={MODEL_FLEET} />);
    expect(html).toContain("+0.004");
    expect(html).toContain("-0.002");
  });

  it("formats p99 with a 'ms' unit", () => {
    const html = render(<ModelFleetTable rows={MODEL_FLEET} />);
    expect(html).toContain("48 ms");
    expect(html).toContain("71 ms");
  });

  it("applies cellColor tints from the condFormat ramp", () => {
    const html = render(<ModelFleetTable rows={MODEL_FLEET} />);
    const html_lower = html.toLowerCase();
    const hasAnyCondFormat = Object.values(colors.condFormat).some((hex) =>
      html_lower.includes(hex.toLowerCase()),
    );
    expect(hasAnyCondFormat).toBe(true);
  });

  it("renders the optional caption when provided", () => {
    const html = render(
      <ModelFleetTable
        rows={MODEL_FLEET}
        caption="Model registry · 4 entries"
      />,
    );
    expect(html).toContain("Model registry");
  });

  it("renders a legend with tolerance / watch / action labels", () => {
    const html = render(<ModelFleetTable rows={MODEL_FLEET} />);
    expect(html).toContain("Within tolerance");
    expect(html).toContain("Watch");
    expect(html).toContain("Action");
  });
});

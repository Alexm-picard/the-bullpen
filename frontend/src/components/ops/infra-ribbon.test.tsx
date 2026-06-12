/**
 * Unit tests for <InfraRibbon>.
 *
 * Covers: all service labels + details render, state badges, no anchors (the
 * ribbon is non-interactive — important contract since it intentionally
 * diverges from ModelFleetRibbon), nav landmark with aria-label.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { INFRA_SERVICES, type InfraService } from "../../data/ops-fixtures";
import { theme } from "../../design/theme";
import { colors } from "../../design/broadcast";

import { InfraRibbon } from "./infra-ribbon";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

describe("InfraRibbon", () => {
  it("renders all service labels", () => {
    const html = render(<InfraRibbon services={INFRA_SERVICES} />);
    for (const svc of INFRA_SERVICES) {
      expect(html).toContain(svc.label);
    }
  });

  it("renders all service detail lines", () => {
    const html = render(<InfraRibbon services={INFRA_SERVICES} />);
    expect(html).toContain("v25.3");
    expect(html).toContain("2.4 MB");
    expect(html).toContain("3 routes");
    expect(html).toContain("4d uptime");
  });

  it("renders UP state badges", () => {
    const html = render(<InfraRibbon services={INFRA_SERVICES} />);
    // All five fixtures are UP — should appear at least once per chip.
    expect((html.match(/UP/g) ?? []).length).toBeGreaterThanOrEqual(
      INFRA_SERVICES.length,
    );
  });

  it("is non-interactive (no anchor elements)", () => {
    const html = render(<InfraRibbon services={INFRA_SERVICES} />);
    expect(html).not.toContain("<a ");
  });

  it("renders the navy chrome", () => {
    const html = render(<InfraRibbon services={INFRA_SERVICES} />);
    expect(html.toLowerCase()).toContain(colors.chrome.toLowerCase());
  });

  it("uses the good3 green for UP state badges", () => {
    const html = render(<InfraRibbon services={INFRA_SERVICES} />);
    expect(html.toLowerCase()).toContain(colors.condFormat.good3.toLowerCase());
  });

  it("uses the alarm red (condFormat.bad3) for DOWN state badges", () => {
    const downService: InfraService = {
      id: "test-down",
      label: "Test service",
      detail: "—",
      state: "DOWN",
    };
    const html = render(<InfraRibbon services={[downService]} />);
    expect(html).toContain("DOWN");
    expect(html.toLowerCase()).toContain(colors.condFormat.bad3.toLowerCase());
  });

  it("uses gold for DEGRADED state badges", () => {
    const degradedService: InfraService = {
      id: "test-deg",
      label: "Test service",
      detail: "—",
      state: "DEGRADED",
    };
    const html = render(<InfraRibbon services={[degradedService]} />);
    expect(html).toContain("DEGRADED");
    expect(html.toLowerCase()).toContain(
      colors.gold.toLowerCase(),
    );
  });

  it("has a nav landmark with an aria-label", () => {
    const html = render(<InfraRibbon services={INFRA_SERVICES} />);
    expect(html).toContain('aria-label="Infrastructure services"');
  });
});

/**
 * Unit tests for <OpsHeader>.
 *
 * Covers: eyebrow text, two-line nameplate (MODEL / OPERATIONS as separate
 * display:block spans), byline strip with model + alert + awaiting-promotion
 * counts, scarlet color when counts > 0, muted color when 0, mono context line.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { theme } from "../../design/theme";
import { colors } from "../../design/tokens";

import { OpsHeader } from "./ops-header";

const BASE_PROPS = {
  issueDate: "Wed · May 30, 2026",
  modelCount: 4,
  alertCount: 2,
  awaitingPromotionCount: 1,
  issuedAt: "19:05 ET",
  window: "WINDOW LAST 24H",
};

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

describe("OpsHeader", () => {
  it("renders the eyebrow text", () => {
    const html = render(<OpsHeader {...BASE_PROPS} />);
    expect(html).toContain("The Bullpen");
    expect(html).toContain("Operations Desk");
  });

  it("renders 'MODEL' and 'OPERATIONS' as separate block spans", () => {
    const html = render(<OpsHeader {...BASE_PROPS} />);
    expect(html).toMatch(/style="display:block"[^>]*>Model/);
    expect(html).toMatch(/style="display:block"[^>]*>Operations/);
  });

  it("renders the issue date and model count in the byline strip", () => {
    const html = render(<OpsHeader {...BASE_PROPS} />);
    expect(html).toContain("Wed · May 30, 2026");
    expect(html).toContain("4 models");
  });

  it("uses scarlet for alert count when > 0", () => {
    const html = render(<OpsHeader {...BASE_PROPS} />);
    expect(html).toContain("2 alerts");
    expect(html.toLowerCase()).toContain(colors.scarlet.toLowerCase());
  });

  it("uses muted color for alert count when 0", () => {
    const html = render(
      <OpsHeader {...BASE_PROPS} alertCount={0} awaitingPromotionCount={0} />,
    );
    expect(html).toContain("0 alerts");
    expect(html).toContain("0 awaiting promotion");
    expect(html.toLowerCase()).toContain(colors.textMuted.toLowerCase());
  });

  it("renders 'awaiting promotion' count", () => {
    const html = render(<OpsHeader {...BASE_PROPS} />);
    expect(html).toContain("1 awaiting promotion");
  });

  it("uses singular 'alert' when count is 1", () => {
    const html = render(<OpsHeader {...BASE_PROPS} alertCount={1} />);
    expect(html).toContain("1 alert");
    // and not "1 alerts"
    expect(html).not.toContain("1 alerts");
  });

  it("renders the issue timestamp and window in the mono line", () => {
    const html = render(<OpsHeader {...BASE_PROPS} />);
    expect(html).toContain("19:05 ET");
    expect(html).toContain("WINDOW LAST 24H");
  });

  it("uses the Saira display font on the h1", () => {
    const html = render(<OpsHeader {...BASE_PROPS} />);
    expect(html).toContain("Saira Condensed");
  });
});

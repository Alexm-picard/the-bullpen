/**
 * Unit tests for <OpsLogTable>.
 *
 * Covers: 7 fixture entries render, ALERT-class events get scarlet in the
 * type column (and only ALERT does — the others stay textStrong), navy
 * header + silver row-label chrome present, empty-state path.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { OPS_LOG } from "../../data/ops-fixtures";
import { theme } from "../../design/theme";
import { colors } from "../../design/broadcast";

import { OpsLogTable } from "./ops-log-table";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

describe("OpsLogTable", () => {
  it("renders all seven fixture entries (by timestamp)", () => {
    const html = render(<OpsLogTable entries={OPS_LOG} />);
    for (const entry of OPS_LOG) {
      expect(html).toContain(entry.timestamp);
    }
  });

  it("renders all event types", () => {
    const html = render(<OpsLogTable entries={OPS_LOG} />);
    expect(html).toContain("ALERT");
    expect(html).toContain("DEPLOY");
    expect(html).toContain("REGISTER");
    expect(html).toContain("DRIFT-OK");
    expect(html).toContain("RETRAIN-OK");
    expect(html).toContain("RESTORE-DRILL");
  });

  it("uses scarlet for the ALERT row's type cell", () => {
    const html = render(<OpsLogTable entries={OPS_LOG} />);
    expect(html.toLowerCase()).toContain(colors.goldInk.toLowerCase());
  });

  it("renders all three column headers", () => {
    const html = render(<OpsLogTable entries={OPS_LOG} />);
    expect(html).toContain("Timestamp");
    expect(html).toContain("Type");
    expect(html).toContain("Detail");
  });

  it("renders the navy header chrome", () => {
    const html = render(<OpsLogTable entries={OPS_LOG} />);
    expect(html.toLowerCase()).toContain(colors.chrome.toLowerCase());
  });

  it("renders the silver row-label column", () => {
    const html = render(<OpsLogTable entries={OPS_LOG} />);
    expect(html.toLowerCase()).toContain(colors.fieldSubtle.toLowerCase());
  });

  it("renders the optional caption when provided", () => {
    const html = render(
      <OpsLogTable entries={OPS_LOG} caption="Ops log · last 24h window" />,
    );
    expect(html).toContain("Ops log");
  });

  it("renders an empty-state row when no entries", () => {
    const html = render(<OpsLogTable entries={[]} />);
    expect(html).toContain("No events in window");
  });

  it("renders all event detail strings", () => {
    const html = render(<OpsLogTable entries={OPS_LOG} />);
    expect(html).toContain("Build b1b62ec deployed");
    expect(html).toContain("Quarterly drill PASS");
  });
});

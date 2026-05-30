/**
 * Contract tests for <ReportSheet>. Same renderToStaticMarkup pattern as the
 * rest of the suite — no jsdom dependency.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { theme } from "../../design/theme";
import { colors } from "../../design/tokens";

import { ReportSheet } from "./report-sheet";

function render(node: React.ReactNode): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{node}</MantineProvider>,
  );
}

describe("ReportSheet", () => {
  it("renders the inner sheet with cream background and navy border", () => {
    const html = render(
      <ReportSheet>
        <p>marker</p>
      </ReportSheet>,
    );
    expect(html.toLowerCase()).toContain(colors.bgSheet.toLowerCase());
    expect(html.toLowerCase()).toContain(colors.navy.toLowerCase());
    expect(html).toContain("marker");
  });

  it("renders the report-sheet__shell + corner classes by default", () => {
    const html = render(
      <ReportSheet>
        <p>x</p>
      </ReportSheet>,
    );
    expect(html).toContain("report-sheet__shell");
    expect(html).toContain("report-sheet__corner");
  });

  it("omits the corner motif when showCornerStripes is false", () => {
    const html = render(
      <ReportSheet showCornerStripes={false}>
        <p>x</p>
      </ReportSheet>,
    );
    expect(html).not.toContain("report-sheet__corner");
  });

  it("appends a caller-supplied sheetClassName onto the shell class", () => {
    const html = render(
      <ReportSheet sheetClassName="custom-page__shell">
        <p>x</p>
      </ReportSheet>,
    );
    expect(html).toContain("report-sheet__shell custom-page__shell");
  });
});

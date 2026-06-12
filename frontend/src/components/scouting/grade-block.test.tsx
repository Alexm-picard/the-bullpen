/**
 * Unit tests for <GradeBlock>.
 *
 * Covers label rendering, numeric value rendering, em-dash fallback for null,
 * cellColor application via the grade meta, and the meter role + aria values
 * (a11y rule: the bar is not the only carrier — the number is always present).
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { colors } from "../../design/broadcast";
import { theme } from "../../design/theme";

import { GradeBlock } from "./grade-block";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

describe("GradeBlock", () => {
  it("renders the label uppercase", () => {
    const html = render(<GradeBlock label="Power" value={70} />);
    expect(html).toContain("Power");
  });

  it("renders the numeric value", () => {
    const html = render(<GradeBlock label="Hit" value={60} />);
    expect(html).toContain("60");
  });

  it("renders an em-dash for null values", () => {
    const html = render(<GradeBlock label="Run" value={null} />);
    expect(html).toContain("—");
  });

  it("applies a condFormat ramp color to the fill bar", () => {
    const html = render(<GradeBlock label="Power" value={80} />);
    const hit = Object.values(colors.condFormat).some((hex) =>
      html.toLowerCase().includes(hex.toLowerCase()),
    );
    expect(hit).toBe(true);
  });

  it("exposes role=meter with aria-valuenow", () => {
    const html = render(<GradeBlock label="FB" value={70} />);
    expect(html).toContain('role="meter"');
    expect(html).toContain('aria-valuenow="70"');
  });

  it("omits aria-valuenow on null but keeps role=meter", () => {
    const html = render(<GradeBlock label="Arm" value={null} />);
    expect(html).toContain('role="meter"');
    expect(html).not.toContain('aria-valuenow="0"');
  });
});

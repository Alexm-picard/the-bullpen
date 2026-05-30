/**
 * Unit tests for <AboutStackTable>.
 *
 * Covers: all 10 rows render, the 3 column headers (LAYER / CHOICE / WHY)
 * appear, and the table contains representative stack content like
 * "Java 21".
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { STACK_ROWS } from "../../data/about-fixtures";
import { theme } from "../../design/theme";

import { AboutStackTable } from "./about-stack-table";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

describe("AboutStackTable", () => {
  it("renders all 10 stack rows", () => {
    const html = render(<AboutStackTable rows={STACK_ROWS} />);
    const trCount = (html.match(/<tr/g) ?? []).length;
    // 10 body rows + 1 header row = 11
    expect(trCount).toBe(11);
  });

  it("renders the 3 column headers LAYER, CHOICE, WHY", () => {
    const html = render(<AboutStackTable rows={STACK_ROWS} />);
    expect(html).toContain("Layer");
    expect(html).toContain("Choice");
    expect(html).toContain("Why");
  });

  it("renders the Java 21 stack row contents", () => {
    const html = render(<AboutStackTable rows={STACK_ROWS} />);
    expect(html).toContain("Java 21");
    expect(html).toContain("Spring Boot 3.x");
  });

  it("renders every layer label", () => {
    const html = render(<AboutStackTable rows={STACK_ROWS} />);
    for (const row of STACK_ROWS) {
      expect(html).toContain(row.layer);
    }
  });
});

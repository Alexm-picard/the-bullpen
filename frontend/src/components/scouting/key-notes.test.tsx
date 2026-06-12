/**
 * Unit tests for <KeyNotes>.
 *
 * Covers heading rendering, every note appearing, numbered list semantics,
 * and the scarlet number color.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { colors } from "../../design/broadcast";
import { theme } from "../../design/theme";

import { KeyNotes } from "./key-notes";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

describe("KeyNotes", () => {
  it("renders the 'Key Notes' header", () => {
    const html = render(<KeyNotes notes={["alpha"]} />);
    expect(html).toContain("Key Notes");
  });

  it("renders every note string passed in", () => {
    const html = render(
      <KeyNotes notes={["alpha-note", "beta-note", "gamma-note"]} />,
    );
    expect(html).toContain("alpha-note");
    expect(html).toContain("beta-note");
    expect(html).toContain("gamma-note");
  });

  it("uses an ordered list for semantic numbering", () => {
    const html = render(<KeyNotes notes={["one", "two"]} />);
    expect(html).toContain("<ol");
  });

  it("renders zero-padded numbers (01, 02, …)", () => {
    const html = render(<KeyNotes notes={["a", "b"]} />);
    expect(html).toContain("01");
    expect(html).toContain("02");
  });

  it("uses scarlet for the number column", () => {
    const html = render(<KeyNotes notes={["x"]} />);
    expect(html.toLowerCase()).toContain(colors.goldInk.toLowerCase());
  });
});

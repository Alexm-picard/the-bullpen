/**
 * Unit tests for <AboutDiscipline>.
 *
 * Covers: the KeyNotes wrapper renders, all 5 numbered notes appear, and
 * the prose substrings ("Rolling-origin", "promotion stays human-gated")
 * appear in the rendered output.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { DISCIPLINE_NOTES } from "../../data/about-fixtures";
import { theme } from "../../design/theme";

import { AboutDiscipline } from "./about-discipline";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

describe("AboutDiscipline", () => {
  it("renders the KeyNotes header", () => {
    const html = render(<AboutDiscipline notes={DISCIPLINE_NOTES} />);
    expect(html).toContain("Key Notes");
  });

  it("renders an ordered list semantically", () => {
    const html = render(<AboutDiscipline notes={DISCIPLINE_NOTES} />);
    expect(html).toContain("<ol");
  });

  it("renders 5 numbered notes", () => {
    const html = render(<AboutDiscipline notes={DISCIPLINE_NOTES} />);
    expect(html).toContain("01");
    expect(html).toContain("02");
    expect(html).toContain("03");
    expect(html).toContain("04");
    expect(html).toContain("05");
  });

  it("contains the 'Rolling-origin' substring", () => {
    const html = render(<AboutDiscipline notes={DISCIPLINE_NOTES} />);
    expect(html).toContain("Rolling-origin");
  });

  it("contains the 'human-gated' substring", () => {
    const html = render(<AboutDiscipline notes={DISCIPLINE_NOTES} />);
    expect(html).toContain("human-gated");
  });
});

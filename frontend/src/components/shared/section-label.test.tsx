/**
 * Unit tests for <SectionLabel>.
 *
 * Tiny presentational component — assert that children render and that the
 * scouting-report identity (Saira display, uppercase) is in effect.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { theme } from "../../design/theme";
import { typography } from "../../design/tokens";

import { SectionLabel } from "./section-label";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

describe("SectionLabel", () => {
  it("renders the child text", () => {
    const html = render(<SectionLabel>Tonight's Matchups</SectionLabel>);
    expect(html).toContain("Tonight&#x27;s Matchups");
  });

  it("applies the Saira display font", () => {
    const html = render(<SectionLabel>X</SectionLabel>);
    // Saira Condensed appears in the inline font-family.
    expect(html).toContain("Saira Condensed");
  });

  it("uppercases via text-transform", () => {
    const html = render(<SectionLabel>X</SectionLabel>);
    expect(html).toContain("text-transform:uppercase");
  });

  it("uses display family from tokens", () => {
    // Defensive: prove the family string the component pulls from is Saira.
    expect(typography.fonts.display).toContain("Saira Condensed");
  });
});

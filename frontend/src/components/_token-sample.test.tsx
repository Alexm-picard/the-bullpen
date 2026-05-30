/**
 * Contract tests for the scouting-report token sample.
 *
 * Previously this file carried a renderToStaticMarkup snapshot that included
 * Mantine's emitted <style data-mantine-styles> block. That block depends on
 * Mantine's internal color-stop generation order and broke on every minor
 * theme tweak. The focused assertions below cover the same intent (the
 * canonical hex anchors must reach the rendered DOM as inline values, every
 * primitive section must render) without the version-coupling brittleness.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { theme } from "../design/theme";
import { colors } from "../design/tokens";

import { TokenSampleCard } from "./_token-sample";

function render(): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>
      <TokenSampleCard />
    </MantineProvider>,
  );
}

describe("TokenSampleCard", () => {
  it("references the scarlet accent inline (the canonical chromatic anchor)", () => {
    expect(render().toLowerCase()).toContain(colors.scarlet.toLowerCase());
  });

  it("references the navy chrome hex inline", () => {
    expect(render().toLowerCase()).toContain(colors.navy.toLowerCase());
  });

  it("references every conditional-format ramp stop inline", () => {
    const html = render().toLowerCase();
    expect(html).toContain(colors.condFormat.bad3.toLowerCase());
    expect(html).toContain(colors.condFormat.bad1.toLowerCase());
    expect(html).toContain(colors.condFormat.neutral.toLowerCase());
    expect(html).toContain(colors.condFormat.good1.toLowerCase());
    expect(html).toContain(colors.condFormat.good3.toLowerCase());
  });

  it("renders every primitive section label in order", () => {
    const html = render();
    const labels = [
      "Display face",
      "Body face",
      "Mono stat face",
      "Surface palette",
      "Broadcast chrome",
      "Conditional-format diverging ramp",
      "Sequential ramps",
      "Categorical viz palette",
      "StatTable signature primitive",
    ];
    let cursor = 0;
    for (const label of labels) {
      const next = html.indexOf(label, cursor);
      expect(
        next,
        `section "${label}" must appear after the previous one`,
      ).toBeGreaterThan(cursor - 1);
      cursor = next;
    }
  });

  it("renders the live StatTable with all three sample rows", () => {
    const html = render();
    expect(html).toContain("Shohei Ohtani");
    expect(html).toContain("Gerrit Cole");
    expect(html).toContain("Pete Alonso");
  });
});

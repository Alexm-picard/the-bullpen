/**
 * Unit tests for <AboutRejectedAlternatives>.
 *
 * Covers: the framing paragraph is present, all 11 tags render, each tag
 * carries the `✗` glyph, the first tag is "LLM for pitch outcome", and
 * no anchor element wraps any tag (these are labels, not links).
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { REJECTED_PARA, REJECTED_TAGS } from "../../data/about-fixtures";
import { theme } from "../../design/theme";

import { AboutRejectedAlternatives } from "./about-rejected-alternatives";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

describe("AboutRejectedAlternatives", () => {
  it("renders the framing paragraph", () => {
    const html = render(
      <AboutRejectedAlternatives
        paragraph={REJECTED_PARA}
        tags={REJECTED_TAGS}
      />,
    );
    expect(html).toContain("considered and rejected");
    expect(html).toContain("design discipline");
  });

  it("renders all 11 tags", () => {
    const html = render(
      <AboutRejectedAlternatives
        paragraph={REJECTED_PARA}
        tags={REJECTED_TAGS}
      />,
    );
    const liCount = (html.match(/<li/g) ?? []).length;
    expect(liCount).toBe(11);
    for (const tag of REJECTED_TAGS) {
      expect(html).toContain(tag);
    }
  });

  it("renders the ✗ glyph for every tag", () => {
    const html = render(
      <AboutRejectedAlternatives
        paragraph={REJECTED_PARA}
        tags={REJECTED_TAGS}
      />,
    );
    const xCount = (html.match(/✗/g) ?? []).length;
    expect(xCount).toBe(REJECTED_TAGS.length);
  });

  it("has 'LLM for pitch outcome' as the first tag", () => {
    const html = render(
      <AboutRejectedAlternatives
        paragraph={REJECTED_PARA}
        tags={REJECTED_TAGS}
      />,
    );
    expect(REJECTED_TAGS[0]).toBe("LLM for pitch outcome");
    expect(html).toContain("LLM for pitch outcome");
  });

  it("renders tags as plain text, not as anchors", () => {
    const html = render(
      <AboutRejectedAlternatives
        paragraph={REJECTED_PARA}
        tags={REJECTED_TAGS}
      />,
    );
    expect(html).not.toContain("<a ");
  });
});

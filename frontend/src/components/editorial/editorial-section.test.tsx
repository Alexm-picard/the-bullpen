import { MantineProvider } from "@mantine/core";
import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { theme } from "../../design/theme";

import { EditorialSection } from "./editorial-section";

function render(node: ReactNode): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{node}</MantineProvider>,
  );
}

describe("EditorialSection", () => {
  it("renders the title", () => {
    const html = render(
      <EditorialSection title="What this is">
        <p>body</p>
      </EditorialSection>,
    );
    expect(html).toContain("What this is");
    expect(html).toContain("body");
  });

  it("renders the eyebrow above the title", () => {
    const html = render(
      <EditorialSection eyebrow="01 — Overview" title="The project">
        <p>x</p>
      </EditorialSection>,
    );
    expect(html).toContain("01 — Overview");
  });

  it("applies the 720-px editorial max-width", () => {
    const html = render(
      <EditorialSection title="t">
        <p>p</p>
      </EditorialSection>,
    );
    expect(html).toContain("max-width:720px");
  });
});

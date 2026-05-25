import { MantineProvider } from "@mantine/core";
import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { theme } from "../../design/theme";

import { ParkThumbnail } from "./park-thumbnail";

function render(node: ReactNode): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{node}</MantineProvider>,
  );
}

describe("ParkThumbnail", () => {
  it("renders parkId, name, and 3-decimal P(HR)", () => {
    const html = render(
      <ParkThumbnail parkId="NYY" name="Yankee Stadium" probHr={0.421} />,
    );
    expect(html).toContain(">NYY<");
    expect(html).toContain("Yankee Stadium");
    expect(html).toContain("0.421");
  });

  it("shows an em-dash and dimmed style when probHr is null", () => {
    const html = render(
      <ParkThumbnail parkId="BOS" name="Fenway Park" probHr={null} />,
    );
    expect(html).toContain("—");
  });

  it("shows an em-dash while loading", () => {
    const html = render(
      <ParkThumbnail parkId="COL" name="Coors Field" probHr={0.6} isLoading />,
    );
    expect(html).toContain("—");
    // Loading suppresses the tint overlay rect.
    expect(html).not.toContain("<rect");
  });

  it("renders a rect tint overlay when probHr is set and not loading", () => {
    const html = render(
      <ParkThumbnail parkId="NYY" name="Yankee Stadium" probHr={0.5} />,
    );
    expect(html).toContain("<rect");
    expect(html).toContain('opacity="0.5"');
  });
});

import { MantineProvider } from "@mantine/core";
import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { theme } from "../../design/theme";

import { LaunchParamSliders, type LaunchParams } from "./launch-param-sliders";

const VALUES: LaunchParams = {
  launchSpeedMph: 110,
  launchAngleDeg: 28,
  sprayAngleDeg: 0,
};

function render(node: ReactNode): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{node}</MantineProvider>,
  );
}

describe("LaunchParamSliders", () => {
  it("renders all three values with correct units", () => {
    const html = render(
      <LaunchParamSliders values={VALUES} onChange={() => undefined} />,
    );
    expect(html).toContain("110.0 mph");
    expect(html).toContain("28.0°");
    expect(html).toContain("0°");
  });

  it("hides the Updating indicator by default", () => {
    const html = render(
      <LaunchParamSliders values={VALUES} onChange={() => undefined} />,
    );
    expect(html).not.toContain("Updating");
  });

  it("shows the Updating indicator when isUpdating is true", () => {
    const html = render(
      <LaunchParamSliders
        values={VALUES}
        onChange={() => undefined}
        isUpdating
      />,
    );
    expect(html).toContain("Updating");
  });
});

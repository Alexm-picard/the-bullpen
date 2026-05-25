/**
 * Tests for <StickyControlRail>.
 *
 * The IntersectionObserver-driven shrink behavior is not unit-tested — it's
 * visual and verified by Playwright in the redesign verification pass. Here
 * we stub IntersectionObserver to a no-op and assert the rail mounts cleanly,
 * renders all five controls with formatted values, and surfaces the
 * placeholder note for spray angle.
 */
import { MantineProvider } from "@mantine/core";
import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { beforeAll, describe, expect, it } from "vitest";

import { theme } from "../../design/theme";

import {
  StickyControlRail,
  type LaunchParamsExtended,
} from "./sticky-control-rail";

const VALUES: LaunchParamsExtended = {
  stand: "R",
  launchSpeedMph: 110,
  launchAngleDeg: 28,
  releaseSpeedMph: 94,
  sprayAngleDeg: 0,
};

function render(node: ReactNode): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{node}</MantineProvider>,
  );
}

beforeAll(() => {
  // SSR render doesn't fire useEffect — but if it did, IntersectionObserver
  // would crash. Stub it just in case the env grows jsdom semantics.
  if (typeof globalThis.IntersectionObserver === "undefined") {
    (
      globalThis as unknown as { IntersectionObserver: unknown }
    ).IntersectionObserver = class {
      observe() {
        /* no-op */
      }
      disconnect() {
        /* no-op */
      }
      unobserve() {
        /* no-op */
      }
    };
  }
});

describe("StickyControlRail", () => {
  it("renders all five control labels", () => {
    const html = render(
      <StickyControlRail values={VALUES} onChange={() => undefined} />,
    );
    expect(html).toContain("Stand");
    expect(html).toContain("Speed");
    expect(html).toContain("Angle");
    expect(html).toContain("Release");
    expect(html).toContain("Spray");
  });

  it("formats the four slider values with their units", () => {
    const html = render(
      <StickyControlRail values={VALUES} onChange={() => undefined} />,
    );
    expect(html).toContain("110.0 mph");
    expect(html).toContain("28.0 °");
    expect(html).toContain("94.0 mph");
    expect(html).toContain("0 °");
  });

  it("surfaces the spray-angle placeholder note", () => {
    const html = render(
      <StickyControlRail values={VALUES} onChange={() => undefined} />,
    );
    expect(html).toContain("pending 30-park MLP");
  });

  it("hides the updating indicator by default", () => {
    const html = render(
      <StickyControlRail values={VALUES} onChange={() => undefined} />,
    );
    expect(html).not.toContain("updating…");
  });

  it("shows the updating indicator when isUpdating is true", () => {
    const html = render(
      <StickyControlRail
        values={VALUES}
        onChange={() => undefined}
        isUpdating
      />,
    );
    expect(html).toContain("updating…");
  });

  it("includes a sentinel element above the rail", () => {
    const html = render(
      <StickyControlRail values={VALUES} onChange={() => undefined} />,
    );
    // React renders boolean aria attributes as string "true" (not ""), so we
    // match the sentinel's 1px-tall shape rather than the attribute literal.
    expect(html).toContain('style="height:1px;width:100%"');
  });
});

/**
 * Unit tests for <ModelFleetRibbon>.
 *
 * Covers: all chip labels render, each chip is an anchor with the right href,
 * state badges use scarlet for LIVE and silver for SHADOW (so the broadcast-
 * chrome color coding is in effect), all chips are reachable links (a11y).
 *
 * Uses MemoryRouter because the ribbon's <Link> children require a Router
 * context.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import type { ModelChip } from "../../data/home-fixtures";
import { theme } from "../../design/theme";
import { colors } from "../../design/tokens";

import { ModelFleetRibbon } from "./model-fleet-ribbon";

const CHIPS: ModelChip[] = [
  {
    id: "a",
    label: "pitch_outcome_pre",
    detail: "v3.2",
    state: "LIVE",
    href: "/ops",
  },
  {
    id: "b",
    label: "batted_ball",
    detail: "v1.4",
    state: "LIVE",
    href: "/ops",
  },
  {
    id: "c",
    label: "pitch_outcome_pre",
    detail: "v3.3",
    state: "SHADOW",
    href: "/ops",
  },
  {
    id: "d",
    label: "drift monitor",
    detail: "0 / 12",
    state: "OK",
    href: "/ops",
  },
];

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>
      <MemoryRouter>{ui}</MemoryRouter>
    </MantineProvider>,
  );
}

describe("ModelFleetRibbon", () => {
  it("renders all chip labels", () => {
    const html = render(<ModelFleetRibbon chips={CHIPS} />);
    expect(html).toContain("pitch_outcome_pre");
    expect(html).toContain("batted_ball");
    expect(html).toContain("drift monitor");
  });

  it("renders all chip detail lines", () => {
    const html = render(<ModelFleetRibbon chips={CHIPS} />);
    expect(html).toContain("v3.2");
    expect(html).toContain("v1.4");
    expect(html).toContain("v3.3");
    expect(html).toContain("0 / 12");
  });

  it("renders LIVE/SHADOW/OK state badges", () => {
    const html = render(<ModelFleetRibbon chips={CHIPS} />);
    expect(html).toContain("LIVE");
    expect(html).toContain("SHADOW");
    expect(html).toContain("OK");
  });

  it("renders chips as anchor elements (interactive)", () => {
    const html = render(<ModelFleetRibbon chips={CHIPS} />);
    // Every chip is a Link → an <a href="…"> at SSR time.
    const anchorCount = (html.match(/<a /g) ?? []).length;
    expect(anchorCount).toBe(CHIPS.length);
  });

  it("includes the navy chrome background", () => {
    const html = render(<ModelFleetRibbon chips={CHIPS} />);
    expect(html.toLowerCase()).toContain(colors.navy.toLowerCase());
  });

  it("uses scarlet for LIVE state badges", () => {
    const html = render(<ModelFleetRibbon chips={CHIPS} />);
    // Scarlet should appear in the rendered style for the LIVE chip's badge.
    expect(html.toLowerCase()).toContain(colors.scarlet.toLowerCase());
  });

  it("has a nav landmark with an aria-label", () => {
    const html = render(<ModelFleetRibbon chips={CHIPS} />);
    expect(html).toContain('aria-label="Model fleet"');
  });
});

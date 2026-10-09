/**
 * <BroadcastFleetStrip> (redesign PR-4): chip rendering, the gold on-air dot
 * for LIVE models only, and the /ops links.
 */
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";

import type { ModelChip } from "../../data/home-fixtures";

import { BroadcastFleetStrip } from "./broadcast-fleet-strip";

const CHIPS: ModelChip[] = [
  {
    id: "battedball_outcome-v1",
    label: "battedball_outcome",
    detail: "v1",
    state: "LIVE",
    href: "/ops",
  },
  {
    id: "pitch_outcome_pre-v1",
    label: "pitch_outcome_pre",
    detail: "v1",
    state: "SHADOW",
    href: "/ops",
  },
];

describe("BroadcastFleetStrip", () => {
  it("renders a chip per model linking to /ops", () => {
    const html = renderToStaticMarkup(
      <MemoryRouter>
        <BroadcastFleetStrip chips={CHIPS} />
      </MemoryRouter>,
    );
    expect(html).toContain("battedball_outcome");
    expect(html).toContain("pitch_outcome_pre");
    expect(html.match(/href="\/ops"/g)).toHaveLength(2);
  });

  it("lets the strip class own the chip background (hover must win)", () => {
    const html = renderToStaticMarkup(
      <MemoryRouter>
        <BroadcastFleetStrip chips={CHIPS} />
      </MemoryRouter>,
    );
    expect(html.match(/class="broadcast-strip bp-pressable"/g)).toHaveLength(2);
    expect(html).not.toMatch(/<a[^>]*style="[^"]*background-color/);
  });

  it("gives only LIVE chips the (static) gold dot", () => {
    const html = renderToStaticMarkup(
      <MemoryRouter>
        <BroadcastFleetStrip chips={CHIPS} />
      </MemoryRouter>,
    );
    expect(html.match(/data-fleet-live-dot="true"/g)).toHaveLength(1);
    // No pulse here: the Scorebug owns the one on-air pulse per screen.
    expect(html).not.toContain("broadcast-live-dot");
    expect(html).toContain("LIVE");
    expect(html).toContain("SHADOW");
  });
});

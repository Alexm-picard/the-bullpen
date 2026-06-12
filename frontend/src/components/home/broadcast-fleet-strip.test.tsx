/**
 * <BroadcastFleetStrip> (redesign PR-4): chip rendering, the gold on-air dot
 * for LIVE models only, and the /ops links.
 */
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router-dom";
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

  it("gives only LIVE chips the on-air dot", () => {
    const html = renderToStaticMarkup(
      <MemoryRouter>
        <BroadcastFleetStrip chips={CHIPS} />
      </MemoryRouter>,
    );
    expect(html.match(/broadcast-live-dot/g)).toHaveLength(1);
    expect(html).toContain("LIVE");
    expect(html).toContain("SHADOW");
  });
});

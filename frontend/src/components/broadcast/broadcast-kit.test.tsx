/**
 * SSR markup tests for the broadcast kit (decision [160]) - structure, a11y
 * affordances, and the [160] rules (team color as fills only, real heading
 * elements in lower-thirds). The scorebug, big-stat and ticker left with the
 * game page's move to the [195] editorial identity.
 */
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { teamColor } from "../../design/teamColors";

import { BroadcastPanel } from "./broadcast-panel";
import { LowerThird } from "./lower-third";

describe("LowerThird", () => {
  it("renders a real heading element (default h2) with the slanted bar", () => {
    const html = renderToStaticMarkup(
      <LowerThird id="sec-pitch-log" meta="LAST 50">
        Pitch Log
      </LowerThird>,
    );
    expect(html).toContain("<h2");
    expect(html).toContain('id="sec-pitch-log"');
    expect(html).toContain("Pitch Log");
    expect(html).toContain("LAST 50");
    expect(html).toContain("polygon");
  });

  it("supports a team-colored tick and custom heading levels", () => {
    const html = renderToStaticMarkup(
      <LowerThird accent="NYM" as="h3">
        Matchup
      </LowerThird>,
    );
    expect(html).toContain("<h3");
    expect(html).toContain(teamColor("NYM"));
  });
});

describe("BroadcastPanel", () => {
  it("renders the team edge bar and the diagonal cut on demand", () => {
    const html = renderToStaticMarkup(
      <BroadcastPanel cut edgeTeam="SEA">
        body
      </BroadcastPanel>,
    );
    expect(html).toContain(teamColor("SEA"));
    expect(html).toContain("polygon");
    expect(html).toContain("body");
  });

  it("renders plain (no cut, no edge) by default", () => {
    const html = renderToStaticMarkup(<BroadcastPanel>x</BroadcastPanel>);
    expect(html).not.toContain("polygon");
  });
});

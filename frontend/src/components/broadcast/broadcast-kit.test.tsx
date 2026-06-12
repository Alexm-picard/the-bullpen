/**
 * SSR markup tests for the broadcast kit (decision [160]) - structure, a11y
 * affordances, and the [160] rules (team color as fills only, reduced-motion
 * ticker contract, real heading elements in lower-thirds).
 */
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { colors } from "../../design/broadcast";
import { teamColor } from "../../design/teamColors";

import { BigStat } from "./big-stat";
import { BroadcastPanel } from "./broadcast-panel";
import { LowerThird } from "./lower-third";
import { Scorebug } from "./scorebug";
import { TickerStrip } from "./ticker-strip";

describe("Scorebug", () => {
  const bug = (
    <Scorebug
      awayTeam="BAL"
      homeTeam="BOS"
      awayScore={1}
      homeScore={2}
      state="TOP 6"
      live
      detail="94.8 FF"
    />
  );

  it("announces the full game state as a status element", () => {
    const html = renderToStaticMarkup(bug);
    expect(html).toContain('role="status"');
    expect(html).toContain("BAL 1, BOS 2, TOP 6, live");
  });

  it("renders both team-color fills and the gold live dot", () => {
    const html = renderToStaticMarkup(bug);
    expect(html).toContain(teamColor("BAL"));
    expect(html).toContain(teamColor("BOS"));
    expect(html).toContain("broadcast-live-dot");
    expect(html).toContain("LIVE");
    expect(html).toContain("94.8 FF");
  });

  it("omits the live affordance when not live", () => {
    const html = renderToStaticMarkup(
      <Scorebug
        awayTeam="BAL"
        homeTeam="BOS"
        awayScore={3}
        homeScore={2}
        state="FINAL"
      />,
    );
    expect(html).not.toContain("broadcast-live-dot");
    expect(html).not.toContain("LIVE");
  });
});

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

describe("BigStat", () => {
  it("renders label, value, and sub-line; gold tone switches the numeral color", () => {
    const plain = renderToStaticMarkup(
      <BigStat label="Exit Velo" value="104.6" sub="mph" />,
    );
    expect(plain).toContain("Exit Velo");
    expect(plain).toContain("104.6");
    expect(plain).toContain("mph");

    const gold = renderToStaticMarkup(
      <BigStat label="P(HR)" value=".78" tone="gold" />,
    );
    expect(gold).toContain(colors.goldInk);
  });
});

describe("TickerStrip", () => {
  it("doubles the item run for the seamless loop and stays aria-hidden", () => {
    const html = renderToStaticMarkup(
      <TickerStrip items={["COL P(HR) .81", "BOS xOUT .55"]} />,
    );
    expect(html).toContain('aria-hidden="true"');
    expect(html).toContain("broadcast-ticker__track");
    // Each item appears twice (run x2) so the -50% translate loops seamlessly.
    expect(html.match(/COL P\(HR\) \.81/g)).toHaveLength(2);
  });

  it("renders nothing for an empty item list", () => {
    expect(renderToStaticMarkup(<TickerStrip items={[]} />)).toBe("");
  });
});

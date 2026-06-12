/**
 * <FeaturedMatchupPanel> (redesign PR-4): nameplates with team bars, the two
 * key reads, and the gold CTA link.
 */
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import { PLAYERS } from "../../data/matchup-fixtures";
import { teamColor } from "../../design/teamColors";

import { FeaturedMatchupPanel } from "./featured-matchup-panel";

const batter = PLAYERS.judge_aaron;
const pitcher = PLAYERS.skubal_tarik;

function render(): string {
  if (!batter || !pitcher) throw new Error("fixture players missing");
  return renderToStaticMarkup(
    <MemoryRouter>
      <FeaturedMatchupPanel
        batter={batter}
        pitcher={pitcher}
        context="AL leaders collide"
        keyReads={["Read one body.", "Read two body."]}
        ctaHref={`/players/${batter.id}`}
        ctaLabel="Pull the full report →"
      />
    </MemoryRouter>,
  );
}

describe("FeaturedMatchupPanel", () => {
  it("renders both nameplates with team-color bars and the context meta", () => {
    const html = render();
    expect(html).toContain(batter!.name);
    expect(html).toContain(pitcher!.name);
    expect(html).toContain(teamColor(batter!.team));
    expect(html).toContain(teamColor(pitcher!.team));
    expect(html).toContain("AL leaders collide");
    expect(html).toContain("Featured Matchup");
  });

  it("renders both key reads and the CTA link", () => {
    const html = render();
    expect(html).toContain("Read one body.");
    expect(html).toContain("Read two body.");
    expect(html).toContain(`href="/players/${batter!.id}"`);
    expect(html).toContain("Pull the full report");
  });
});

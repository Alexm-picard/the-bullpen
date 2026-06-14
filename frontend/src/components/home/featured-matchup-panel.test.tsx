/**
 * <FeaturedMatchupPanel> (Phase 4b): the lean badge, the two lean-driven
 * nameplates with team bars, the battle score, and the CTAs (nameplate ->
 * player report, gold link -> the live game).
 */
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import type { FeaturedMatchupView } from "../../api/matchups-view";
import { teamColor } from "../../design/teamColors";

import { FeaturedMatchupPanel } from "./featured-matchup-panel";

const VIEW: FeaturedMatchupView = {
  gameId: 823370,
  away: { playerId: 543037, name: "Gerrit Cole", team: "NYY", role: "pitcher" },
  home: {
    playerId: 669373,
    name: "Tarik Skubal",
    team: "DET",
    role: "pitcher",
  },
  leanLabel: "Pitching Duel",
  firstPitchEt: "7:10 PM ET",
  battleScore: 8.6,
  stage: "default",
};

function render(view: FeaturedMatchupView = VIEW): string {
  return renderToStaticMarkup(
    <MemoryRouter>
      <FeaturedMatchupPanel matchup={view} />
    </MemoryRouter>,
  );
}

describe("FeaturedMatchupPanel", () => {
  it("renders both nameplates with team-color bars and the lean meta", () => {
    const html = render();
    expect(html).toContain("Gerrit Cole");
    expect(html).toContain("Tarik Skubal");
    expect(html).toContain(teamColor("NYY"));
    expect(html).toContain(teamColor("DET"));
    expect(html).toContain("Pitching Duel");
    expect(html).toContain("7:10 PM ET");
    expect(html).toContain("Featured Matchup");
  });

  it("renders the battle score and links the nameplates + the game CTA", () => {
    const html = render();
    expect(html).toContain("Battle score 8.6");
    expect(html).toContain('href="/players/543037"');
    expect(html).toContain('href="/players/669373"');
    expect(html).toContain('href="/games/823370"');
    expect(html).toContain("Open the game");
  });

  it("notes the stage (probables vs lineup confirmed)", () => {
    expect(render()).toContain("probables");
    expect(render({ ...VIEW, stage: "lineup" })).toContain("lineup confirmed");
  });
});

/**
 * <TonightsMatchupsBoard> (Phase 4b): the six-column contract, team-color marks,
 * the lean-driven people, the lean badge + battle score, and the OPEN target
 * (the live game).
 */
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import type { BoardRowView } from "../../api/matchups-view";
import { teamColor } from "../../design/teamColors";

import { TonightsMatchupsBoard } from "./tonights-matchups-board";

const ROW: BoardRowView = {
  gameId: 823412,
  awayTeam: "NYY",
  homeTeam: "DET",
  firstPitchEt: "7:10 PM ET",
  away: { playerId: 543037, name: "Gerrit Cole", team: "NYY", role: "pitcher" },
  home: {
    playerId: 669373,
    name: "Tarik Skubal",
    team: "DET",
    role: "pitcher",
  },
  leanLabel: "Pitching Duel",
  battleScore: 7.4,
  stage: "default",
};

function render(rows: BoardRowView[] = [ROW]): string {
  return renderToStaticMarkup(
    <MemoryRouter>
      <TonightsMatchupsBoard rows={rows} caption="showcase" />
    </MemoryRouter>,
  );
}

describe("TonightsMatchupsBoard", () => {
  it("renders matchup, first pitch, the people, lean, and battle score", () => {
    const html = render();
    expect(html).toContain("NYY");
    expect(html).toContain("DET");
    expect(html).toContain("7:10 PM ET");
    expect(html).toContain("Gerrit Cole");
    expect(html).toContain("Tarik Skubal");
    expect(html).toContain("Pitching Duel");
    expect(html).toContain("7.4");
  });

  it("draws team-color marks for both sides", () => {
    const html = render();
    expect(html).toContain(teamColor("NYY"));
    expect(html).toContain(teamColor("DET"));
  });

  it("links the OPEN cell to the live game", () => {
    const html = render();
    expect(html).toContain('href="/games/823412"');
    expect(html).toContain("Open game for NYY at DET");
  });
});

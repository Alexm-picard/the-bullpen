/**
 * <TonightsMatchupsBoard> (redesign PR-4): the six-column contract, team-color
 * bars (fills only), the broadcast EDGE tint, and the OPEN link target.
 */
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import type { TonightMatchup } from "../../data/home-fixtures";
import { EDGE_METRIC } from "../../data/home-fixtures";
import { colors } from "../../design/broadcast";
import { cellColorWith, rampFrom } from "../../design/cellColor";
import { teamColor } from "../../design/teamColors";

import { TonightsMatchupsBoard } from "./tonights-matchups-board";

const MATCHUP: TonightMatchup = {
  id: "nyy-det",
  away: "NYY",
  home: "DET",
  timeEt: "7:10 PM ET",
  awayStarter: { name: "Gerrit Cole", hand: "R" },
  homeStarter: { name: "Tarik Skubal", hand: "L" },
  edge: 1.4,
  topRead: "Skubal's whiff rate vs the Yankees' chase profile.",
  batterId: "judge_aaron",
};

function render(): string {
  return renderToStaticMarkup(
    <MemoryRouter>
      <TonightsMatchupsBoard matchups={[MATCHUP]} caption="showcase" />
    </MemoryRouter>,
  );
}

describe("TonightsMatchupsBoard", () => {
  it("renders matchup, time, starters, edge, and top read", () => {
    const html = render();
    expect(html).toContain("NYY");
    expect(html).toContain("DET");
    expect(html).toContain("7:10 PM ET");
    expect(html).toContain("Gerrit Cole");
    expect(html).toContain("Tarik Skubal");
    expect(html).toContain("+1.4");
    expect(html).toContain("whiff rate");
  });

  it("draws team-color bars and the broadcast-ramp EDGE tint", () => {
    const html = render();
    expect(html).toContain(teamColor("NYY"));
    expect(html).toContain(teamColor("DET"));
    const expectedTint = cellColorWith(
      rampFrom(colors.condFormat),
      MATCHUP.edge,
      EDGE_METRIC,
    );
    expect(html.toLowerCase()).toContain(expectedTint.toLowerCase());
  });

  it("links the OPEN cell to the batter's report", () => {
    const html = render();
    expect(html).toContain('href="/players/judge_aaron"');
    expect(html).toContain("Open report for NYY at DET");
  });
});

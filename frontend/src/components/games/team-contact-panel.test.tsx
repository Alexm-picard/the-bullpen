/**
 * TeamContactPanel - the pre-first-pitch state, and the honesty constraints on it.
 *
 * The load-bearing tests are the ones asserting what it must NOT claim: it is season-to-date rather
 * than this game, it never renders a percentage for a team it could not profile, and it always says
 * how many balls are behind each mean.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { GameApiError, type TeamContactResponse } from "../../api/games";
import { theme } from "../../design/theme";

import { TeamContactPanel } from "./team-contact-panel";

const DATA: TeamContactResponse = {
  homeTeam: "TOR",
  home: {
    team: "TOR",
    parkId: "TOR",
    meanHrProbability: 0.0412,
    n: 231,
    modelVersion: "v2",
  },
  awayTeam: "BOS",
  away: {
    team: "BOS",
    parkId: "TOR",
    meanHrProbability: 0.0357,
    n: 198,
    modelVersion: "v2",
  },
  since: "2026-01-01",
};

function render(
  props: Partial<React.ComponentProps<typeof TeamContactPanel>> = {},
): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>
      <TeamContactPanel
        data={DATA}
        isLoading={false}
        error={undefined}
        {...props}
      />
    </MantineProvider>,
  );
}

function visibleText(html: string): string {
  const out: string[] = [];
  let i = 0;
  while (i < html.length) {
    const lt = html.indexOf("<", i);
    if (lt === -1) {
      out.push(html.slice(i));
      break;
    }
    out.push(html.slice(i, lt));
    const gt = html.indexOf(">", lt);
    if (gt === -1) break;
    const name = html
      .slice(lt + 1, gt)
      .trim()
      .toLowerCase()
      .split(/[\s/]/)[0];
    if (name === "style" || name === "script") {
      const close = html.toLowerCase().indexOf(`</${name}`, gt);
      const closeEnd = close === -1 ? -1 : html.indexOf(">", close);
      i = closeEnd === -1 ? html.length : closeEnd + 1;
    } else {
      i = gt + 1;
    }
    out.push(" ");
  }
  return out.join("");
}

describe("TeamContactPanel", () => {
  it("shows both teams with their percentage AND the n behind it", () => {
    const text = visibleText(render());
    expect(text).toContain("TOR");
    expect(text).toContain("BOS");
    expect(text).toContain("4.1%");
    expect(text).toContain("3.6%");
    // n is not decoration: a mean over 8 and a mean over 800 are not the same claim.
    expect(text).toContain("n=231");
    expect(text).toContain("n=198");
  });

  it("says season-to-date, and says it is not this game", () => {
    const text = visibleText(render());
    expect(text).toContain("since 2026-01-01");
    expect(text).toMatch(/not this game/i);
    expect(text).toMatch(/REAL batted balls/);
  });

  it("carries the current-team caveat rather than leaving it implied", () => {
    // players.team is CURRENT affiliation, so a mid-season trade attributes a batter's earlier
    // contact to his new club. The window is season-long, so this is reachable, not theoretical.
    expect(visibleText(render())).toMatch(/mid-season trade/i);
  });

  it("never renders a percentage for a team it could not profile", () => {
    // A null profile means no answer. 0.0% would read as "this team never homers" on a card whose
    // job is being honest about what it knows.
    const text = visibleText(render({ data: { ...DATA, away: null } }));
    expect(text).toContain("no profile");
    expect(text).not.toContain("0.0%");
    expect(text).toContain("4.1%"); // the profileable team still renders
  });

  it("states an absence when neither team is profileable", () => {
    const text = visibleText(
      render({ data: { ...DATA, home: null, away: null } }),
    );
    expect(text).toContain("No profileable contact");
    expect(text).not.toMatch(/\d+\.\d%/);
  });

  it("renders a busy state, and an error state that claims nothing", () => {
    expect(render({ data: undefined, isLoading: true })).toContain(
      'aria-busy="true"',
    );
    const text = visibleText(
      render({ data: undefined, error: new GameApiError(503, "down") }),
    );
    expect(text).toContain("Could not load");
    expect(text).not.toMatch(/\d+\.\d%/);
  });
});

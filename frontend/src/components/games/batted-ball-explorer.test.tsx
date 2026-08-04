/**
 * <BattedBallExplorer> - static render covers the collapsed Statcast card; the
 * cross-park set + headcount + pinned-park invariants are asserted at the
 * fixture-contract level (the add/remove/dropdown interaction is covered by the
 * e2e pass, no DOM-interaction lib here).
 */
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { SHOWCASE_BATTED_BALL } from "../../data/batted-ball-fixtures";

import { BattedBallExplorer, distanceLabel } from "./batted-ball-explorer";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(ui);
}

describe("BattedBallExplorer", () => {
  it("renders the Statcast card with the compare trigger collapsed", () => {
    const html = render(<BattedBallExplorer data={SHOWCASE_BATTED_BALL} />);
    expect(html).toContain("Giancarlo Stanton");
    expect(html).toContain("Fly Out");
    expect(html).toContain("108.1");
    expect(html).toContain("402");
    expect(html).toContain(".540");
    expect(html).toContain("Compare across parks");
    // collapsed: the per-park grid is not rendered until opened
    expect(html).not.toContain("per-park heads");
  });

  it("keeps the full-30 headline decoupled from the displayed subset", () => {
    expect(SHOWCASE_BATTED_BALL.parkCount).toBe(30);
    expect(SHOWCASE_BATTED_BALL.hrParkCount).toBe(17);
    // the displayed subset is smaller than the full park set
    expect(SHOWCASE_BATTED_BALL.defaultShown.length).toBeLessThan(
      SHOWCASE_BATTED_BALL.parks.length,
    );
  });

  it("pins exactly one current park and seeds it into the default view", () => {
    const here = SHOWCASE_BATTED_BALL.parks.filter((p) => p.here);
    expect(here).toHaveLength(1);
    expect(here[0]!.park).toBe("Comerica (here)");
    expect(SHOWCASE_BATTED_BALL.defaultShown).toContain("Comerica (here)");
  });

  it("gives every park a team abbrev (dropdown) and a carry estimate + error", () => {
    expect(
      SHOWCASE_BATTED_BALL.parks.every(
        (p) => p.team.length >= 2 && p.dist > 0 && p.err > 0,
      ),
    ).toBe(true);
  });

  it("labels the distance with the batted-ball class, not the bare word Distance", () => {
    // The 12 ft single that motivated this. totalDistance is PROJECTED LANDING distance - correct
    // and tiny for a ball that was never in the air. "Distance 12 ft" reads as broken data;
    // "Ground ball 12 ft" reads as what it is. The number is deliberately NOT suppressed: a
    // missing field would read as missing data, which is a different lie.
    const html = render(
      <BattedBallExplorer
        data={{ ...SHOWCASE_BATTED_BALL, launchDeg: -9, distanceFt: 12 }}
      />,
    );
    expect(html).toContain("Ground ball");
    expect(html).toContain("12");
    expect(html).not.toContain(">Distance<");
  });

  it("keeps the classification honest across the Statcast bands", () => {
    expect(distanceLabel(-9)).toBe("Ground ball");
    expect(distanceLabel(9.9)).toBe("Ground ball");
    expect(distanceLabel(10)).toBe("Line drive");
    expect(distanceLabel(24.9)).toBe("Line drive");
    expect(distanceLabel(25)).toBe("Fly ball");
    expect(distanceLabel(49.9)).toBe("Fly ball");
    expect(distanceLabel(50)).toBe("Pop up");
  });

  it("labels a real home run as a fly ball, where distance is the meaningful read", () => {
    const html = render(
      <BattedBallExplorer
        data={{ ...SHOWCASE_BATTED_BALL, launchDeg: 24, distanceFt: 403 }}
      />,
    );
    expect(html).toContain("Line drive");
    expect(html).toContain("403");
  });
});

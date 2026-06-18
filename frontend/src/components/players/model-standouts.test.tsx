/**
 * <ModelStandouts> - the /players landing leaderboard. Static render shows the
 * default (xwOBA) board; the toggle interaction is covered by the e2e pass
 * (no DOM-interaction lib in this project). The xFIP path is asserted at the
 * fixture level so both metrics stay covered.
 */
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import { MODEL_STANDOUTS } from "../../data/players-landing-fixtures";

import { ModelStandouts } from "./model-standouts";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(<MemoryRouter>{ui}</MemoryRouter>);
}

describe("ModelStandouts", () => {
  it("renders the default xwOBA leaderboard with linked players", () => {
    const html = render(<ModelStandouts />);
    expect(html).toContain("Model Standouts");
    expect(html).toContain("Aaron Judge");
    expect(html).toContain(".451");
    expect(html).toContain("+.118");
    expect(html).toContain('href="/players/592450"');
  });

  it("offers both metric toggles and labels the showcase honestly", () => {
    const html = render(<ModelStandouts />);
    // both toggle buttons present
    expect(html).toContain(">xwOBA<");
    expect(html).toContain(">xFIP<");
    // the default tag + the no-endpoint caveat
    expect(html).toContain("Top predicted xwOBA");
    expect(html).toContain("no live leaders endpoint yet");
  });

  it("carries a distinct pitcher board on the xFIP metric (fixture contract)", () => {
    const topPitcher = MODEL_STANDOUTS.xfip.rows[0];
    const topHitter = MODEL_STANDOUTS.xwoba.rows[0];
    expect(topPitcher).toBeDefined();
    expect(topHitter).toBeDefined();
    expect(MODEL_STANDOUTS.xfip.column).toBe("xFIP");
    expect(topPitcher?.name).toBe("Paul Skenes");
    expect(topPitcher?.value).toBe("2.44");
    // hitters vs pitchers are genuinely different leaders
    expect(topHitter?.name).not.toBe(topPitcher?.name);
  });
});

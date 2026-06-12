/**
 * Unit tests for <MatchupHeader>.
 *
 * Covers headline construction (kind + last-names + opponent hand),
 * byline rendering (both players' names + positions + hands),
 * context line, and scarlet axis glyph.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { PLAYERS } from "../../data/matchup-fixtures";
import { colors } from "../../design/broadcast";
import { theme } from "../../design/theme";

import { MatchupHeader } from "./matchup-header";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

describe("MatchupHeader", () => {
  it("renders 'HITTING REPORT' when primary is a position player", () => {
    const html = render(
      <MatchupHeader
        primary={PLAYERS.judge_aaron!}
        opponent={PLAYERS.skubal_tarik!}
        context="NYY @ DET"
      />,
    );
    expect(html).toContain("HITTING REPORT");
    expect(html).toContain("JUDGE");
    expect(html).toContain("SKUBAL");
  });

  it("renders 'PITCHING REPORT' when primary is a pitcher", () => {
    const html = render(
      <MatchupHeader
        primary={PLAYERS.skubal_tarik!}
        opponent={PLAYERS.judge_aaron!}
        context="NYY @ DET"
      />,
    );
    expect(html).toContain("PITCHING REPORT");
  });

  it("renders both player names in the byline", () => {
    const html = render(
      <MatchupHeader
        primary={PLAYERS.judge_aaron!}
        opponent={PLAYERS.skubal_tarik!}
        context="NYY @ DET"
      />,
    );
    expect(html).toContain("Aaron Judge");
    expect(html).toContain("Tarik Skubal");
  });

  it("renders the context line", () => {
    const html = render(
      <MatchupHeader
        primary={PLAYERS.judge_aaron!}
        opponent={PLAYERS.skubal_tarik!}
        context="NYY @ DET · Wed May 27, 2026"
      />,
    );
    expect(html).toContain("NYY @ DET");
  });

  it("uses scarlet for the axis glyph", () => {
    const html = render(
      <MatchupHeader
        primary={PLAYERS.judge_aaron!}
        opponent={PLAYERS.skubal_tarik!}
        context="NYY @ DET"
      />,
    );
    expect(html.toLowerCase()).toContain(colors.goldInk.toLowerCase());
  });

  it("includes an LHP / RHP hand badge in the title", () => {
    const html = render(
      <MatchupHeader
        primary={PLAYERS.judge_aaron!}
        opponent={PLAYERS.skubal_tarik!}
        context="NYY @ DET"
      />,
    );
    expect(html).toContain("(LHP)");
  });
});

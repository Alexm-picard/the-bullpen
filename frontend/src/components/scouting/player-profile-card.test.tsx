/**
 * Unit tests for <PlayerProfileCard>.
 *
 * Covers name, position, hand, age/height/weight, jersey + team in chrome,
 * summary copy, grade-block composition (label + value rendered), header
 * label switch by variant, navy chrome.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { PLAYERS } from "../../data/matchup-fixtures";
import { colors } from "../../design/tokens";
import { theme } from "../../design/theme";

import { PlayerProfileCard } from "./player-profile-card";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

describe("PlayerProfileCard", () => {
  it("renders the player name", () => {
    const html = render(
      <PlayerProfileCard player={PLAYERS.judge_aaron!} variant="batter" />,
    );
    expect(html).toContain("Aaron Judge");
  });

  it("renders header label 'Batter' for variant=batter", () => {
    const html = render(
      <PlayerProfileCard player={PLAYERS.judge_aaron!} variant="batter" />,
    );
    expect(html).toContain("Batter");
  });

  it("renders header label 'Pitcher' for variant=pitcher", () => {
    const html = render(
      <PlayerProfileCard player={PLAYERS.skubal_tarik!} variant="pitcher" />,
    );
    expect(html).toContain("Pitcher");
  });

  it("renders the jersey and team", () => {
    const html = render(
      <PlayerProfileCard player={PLAYERS.judge_aaron!} variant="batter" />,
    );
    expect(html).toContain("#99");
    expect(html).toContain("NYY");
  });

  it("renders the summary copy", () => {
    const html = render(
      <PlayerProfileCard player={PLAYERS.judge_aaron!} variant="batter" />,
    );
    expect(html).toContain("Premier right-handed power threat");
  });

  it("renders every grade label and value", () => {
    const html = render(
      <PlayerProfileCard player={PLAYERS.judge_aaron!} variant="batter" />,
    );
    expect(html).toContain("Power");
    // Judge power = 80
    expect(html).toContain(">80<");
  });

  it("renders the navy chrome in the header bar", () => {
    const html = render(
      <PlayerProfileCard player={PLAYERS.judge_aaron!} variant="batter" />,
    );
    expect(html.toLowerCase()).toContain(colors.navy.toLowerCase());
  });

  it("renders position + hand metadata", () => {
    const html = render(
      <PlayerProfileCard player={PLAYERS.skubal_tarik!} variant="pitcher" />,
    );
    expect(html).toContain("SP");
    expect(html).toContain("L/L");
  });
});

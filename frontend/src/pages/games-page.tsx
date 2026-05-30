/**
 * /games — Live Game variant of the Matchup Report (Stage 3d, decision [133]
 * identity).
 *
 * Replaces the editorial-data "Today's games" table (leaves 4d.1) — the old
 * `TodaysGamesPage` export in `game-page.tsx` was a holdover from the
 * tech-product redesign. The new /games is the live-game scouting packet:
 * same shell, masthead, and chrome vocabulary as /home and /players/:id,
 * with the pitch log as the hero.
 *
 * Composition order (top → bottom, inside the report-sheet shell):
 *   1. <LiveGameHeader />          — masthead (eyebrow + 2-line nameplate +
 *                                    byline + mono context)
 *   2. <GameStateStrip />          — navy lower-third bar, 5 cells
 *   3. <LivePitchLog />            — the hero — 20 PitchCards stacked
 *   4. <NowBattingPair />          — compact 2-col pitcher / batter ID block
 *   5. <AgreementByInningTable />  — per-inning agreement summary StatTable
 *   6. <OtherGamesSwitcher />      — horizontal chip strip → /games/{id}
 *   7. <CoverSheetFooter />        — navy footer strip (reused)
 *
 * Fixture-driven (`games-fixtures.ts`); no API calls. The api/games.ts hooks
 * (useTodaysGames, useGame, useLivePitches) are reserved for the per-game-
 * detail leaf at /games/:id, which is unchanged by this stage.
 *
 * Constraints honored:
 *   - One <Title order={1}> only (the masthead h1 inside LiveGameHeader).
 *   - No hex codes — every color via tokens or CSS-var utilities.
 *   - No live data fetches; the page is a design-system showcase in v1.
 *   - Reuses CornerStripes + SectionLabel + CoverSheetFooter + PitchCard
 *     + StatTable from prior stages.
 *   - No useEffect for server state, no WebSockets, no polling (rules out
 *     useQuery against /v1/games/* on this page in v1).
 */

import { Stack } from "@mantine/core";

import { CoverSheetFooter } from "../components/scouting/cover-sheet-footer";
import { CornerStripes } from "../components/shared/corner-stripes";
import { SectionLabel } from "../components/shared/section-label";
import {
  AGREEMENT_BY_INNING,
  GAME_STATE_CELLS,
  LIVE_GAME_CONTEXT,
  LIVE_PITCHES,
  NOW_BATTING,
  OTHER_GAMES,
} from "../data/games-fixtures";
import { colors, layouts } from "../design/tokens";
import { AgreementByInningTable } from "../components/games/agreement-by-inning-table";
import { GameStateStrip } from "../components/games/game-state-strip";
import { LiveGameHeader } from "../components/games/live-game-header";
import { LivePitchLog } from "../components/games/live-pitch-log";
import { NowBattingPair } from "../components/games/now-batting-pair";
import { OtherGamesSwitcher } from "../components/games/other-games-switcher";

import "./games/games.css";

export default function GamesPage() {
  return (
    <div
      style={{
        backgroundColor: colors.bgBase,
        minHeight: "calc(100vh - 56px)",
        paddingTop: 32,
        paddingBottom: 64,
      }}
    >
      <div
        style={{
          maxWidth: layouts.reportSheetMaxWidth,
          margin: "0 auto",
          padding: "0 16px",
        }}
      >
        <div
          className="games__shell"
          style={{
            backgroundColor: colors.bgSheet,
            border: `1px solid ${colors.navy}`,
            borderRadius: 2,
            padding: 32,
          }}
        >
          <CornerStripes className="games__corner" />
          <Stack gap={24}>
            <LiveGameHeader
              issueDate={LIVE_GAME_CONTEXT.issueDate}
              awayTeam={LIVE_GAME_CONTEXT.awayTeam}
              homeTeam={LIVE_GAME_CONTEXT.homeTeam}
              awayScore={LIVE_GAME_CONTEXT.awayScore}
              homeScore={LIVE_GAME_CONTEXT.homeScore}
              halfInning={LIVE_GAME_CONTEXT.halfInning}
              batterName={LIVE_GAME_CONTEXT.batterName}
              pitcherName={LIVE_GAME_CONTEXT.pitcherName}
              issuedAt={LIVE_GAME_CONTEXT.issuedAt}
              modelLabel={LIVE_GAME_CONTEXT.modelLabel}
            />

            <GameStateStrip cells={GAME_STATE_CELLS} />

            <section aria-labelledby="games-pitch-log-label">
              <div id="games-pitch-log-label">
                <SectionLabel>
                  Pitch Log · Most Recent {LIVE_PITCHES.length}
                </SectionLabel>
              </div>
              <LivePitchLog pitches={LIVE_PITCHES} />
            </section>

            <section aria-labelledby="games-now-batting-label">
              <div id="games-now-batting-label">
                <SectionLabel>Now Batting</SectionLabel>
              </div>
              <NowBattingPair
                batter={NOW_BATTING.batter}
                pitcher={NOW_BATTING.pitcher}
              />
            </section>

            <section aria-labelledby="games-agreement-label">
              <div id="games-agreement-label">
                <SectionLabel>Agreement By Inning</SectionLabel>
              </div>
              <AgreementByInningTable
                rows={AGREEMENT_BY_INNING}
                caption={`per-inning model agreement · this game · ${LIVE_GAME_CONTEXT.modelLabel}`}
              />
            </section>

            <section aria-labelledby="games-others-label">
              <div id="games-others-label">
                <SectionLabel>
                  Tonight&rsquo;s Other Games · {OTHER_GAMES.length} Live
                </SectionLabel>
              </div>
              <OtherGamesSwitcher chips={OTHER_GAMES} />
            </section>

            <CoverSheetFooter
              buildSha={LIVE_GAME_CONTEXT.buildSha}
              buildDate={LIVE_GAME_CONTEXT.buildDate}
            />
          </Stack>
        </div>
      </div>
    </div>
  );
}

/**
 * /games/demo - the Stage-3d Live Game design showcase (decision [133]
 * identity), preserved verbatim when /games went live (FE-H1, 2026-06-11).
 *
 * This page is fixture-driven by design: it is the design-system reference
 * for the live-game vocabulary (masthead, state strip, pitch log, now-batting
 * pair, agreement table, switcher chips) with deterministic data. The live
 * slate lives at /games; the live per-game view at /games/:id.
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
 * Constraints honored:
 *   - One <Title order={1}> only (the masthead h1 inside LiveGameHeader).
 *   - No hex codes — every color via tokens or CSS-var utilities.
 *   - No useEffect for server state, no WebSockets.
 */

import { Stack } from "@mantine/core";

import { AgreementByInningTable } from "../components/games/agreement-by-inning-table";
import { GameStateStrip } from "../components/games/game-state-strip";
import { LiveGameHeader } from "../components/games/live-game-header";
import { LivePitchLog } from "../components/games/live-pitch-log";
import { NowBattingPair } from "../components/games/now-batting-pair";
import { OtherGamesSwitcher } from "../components/games/other-games-switcher";
import { CoverSheetFooter } from "../components/scouting/cover-sheet-footer";
import { ReportSheet } from "../components/shared/report-sheet";
import { SectionLabel } from "../components/shared/section-label";
import {
  AGREEMENT_BY_INNING,
  GAME_STATE_CELLS,
  LIVE_GAME_CONTEXT,
  LIVE_PITCHES,
  NOW_BATTING,
  OTHER_GAMES,
} from "../data/games-fixtures";

import "./games/games.css";

export default function GamesDemoPage() {
  return (
    <ReportSheet>
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
    </ReportSheet>
  );
}

/**
 * `/games/:id` — per-game live-detail page on the scouting-report identity
 * (Stage 4 follow-up D, decision [133]).
 *
 * This is the ONE leaf in the app that wires real live data (not fixtures):
 * the `useGame` / `useLivePitches` hooks from `api/games.ts` poll on a
 * status-driven interval (12s while in-progress, longer when warming up or
 * delayed). Stage 3d's `/games` slate page is a design showcase; this leaf
 * is the actual live game in progress.
 *
 * Composition (inside the locked `<ReportSheet>` shell):
 *   1. `<LiveGameHeader />`     — masthead synthesized from the GameSummary
 *   2. `<GameStateStrip />`     — game state cells synthesized from the
 *                                 most recent pitch (count, outs, inning,
 *                                 score, last pitch type/velocity)
 *   3. `<LivePitchLog />`       — the live pitch log; pitches come straight
 *                                 from the API (shapes already align)
 *   4. `<CoverSheetFooter />`   — bookends the shell
 *
 * Loading + error states render INSIDE the sheet (not bare loaders) so the
 * identity reads even before the first pitch arrives.
 */
import { Stack, Text } from "@mantine/core";
import { useParams } from "react-router-dom";

import {
  useGame,
  useLivePitches,
  type GameSummary,
  type LivePitchRow,
} from "../api/games";
import { GameStateStrip } from "../components/games/game-state-strip";
import { LiveGameHeader } from "../components/games/live-game-header";
import { LivePitchLog } from "../components/games/live-pitch-log";
import { CoverSheetFooter } from "../components/scouting/cover-sheet-footer";
import { ReportSheet } from "../components/shared/report-sheet";
import { SectionLabel } from "../components/shared/section-label";
import type { GameStateCell } from "../data/games-fixtures";
import { colors } from "../design/tokens";

/** Stable build metadata fallback so the colophon footer always renders. */
const BUILD_FALLBACK = {
  sha: "live",
  date: new Date().toISOString().slice(0, 10),
};

function formatHalfInning(summary: GameSummary | undefined): string {
  if (!summary) return "—";
  // The API exposes `inning` but not top/bottom — show the integer with TOP/BOT
  // unknown. Use "INN" prefix as a neutral marker.
  return `INN ${summary.inning}`;
}

function todayIssueDate(): string {
  return new Intl.DateTimeFormat("en-US", {
    weekday: "short",
    month: "short",
    day: "numeric",
    year: "numeric",
  }).format(new Date());
}

function nowEt(): string {
  return new Intl.DateTimeFormat("en-US", {
    hour: "numeric",
    minute: "2-digit",
    hour12: true,
    timeZone: "America/New_York",
  }).format(new Date());
}

/**
 * Derive the 5-cell GameStateStrip from the most recent live pitch. When no
 * pitch has arrived yet the cells render as "—" so the strip still draws
 * (better than an empty space).
 */
function deriveStateCells(
  summary: GameSummary | undefined,
  mostRecent: LivePitchRow | undefined,
): GameStateCell[] {
  return [
    {
      label: "Inning",
      value: summary ? String(summary.inning) : "—",
    },
    {
      label: "Score",
      value: summary
        ? `${summary.awayTeam} ${summary.awayScore} — ${summary.homeTeam} ${summary.homeScore}`
        : "—",
    },
    {
      label: "Count",
      value: mostRecent ? `${mostRecent.balls}-${mostRecent.strikes}` : "—",
    },
    {
      label: "Outs",
      value: mostRecent ? String(mostRecent.outs) : "—",
    },
    {
      label: "Last Pitch",
      value: mostRecent
        ? `${mostRecent.pitchType || "—"}${
            mostRecent.releaseSpeedMph != null
              ? ` · ${mostRecent.releaseSpeedMph.toFixed(1)}`
              : ""
          }`
        : "—",
    },
  ];
}

export function GamePage() {
  const { id } = useParams<{ id: string }>();
  const numericId = id ? Number(id) : null;
  const valid = numericId != null && Number.isFinite(numericId);

  const game = useGame(valid ? numericId : null);
  const pitches = useLivePitches(valid ? numericId : null, game.data?.status);

  if (!valid) {
    return (
      <ReportSheet>
        <Stack gap={16}>
          <Text style={{ color: colors.scarlet, fontWeight: 600 }}>
            Invalid game id.
          </Text>
        </Stack>
      </ReportSheet>
    );
  }

  const summary = game.data;
  const mostRecent = pitches.pitches[0];
  const issueDate = todayIssueDate();
  const issuedAt = nowEt();
  // Honest label per decision [154]: live is ingest-only until a pitch head clears its
  // promotion gate, so no model version is claimed here. Pitch cards render predictions
  // as n/a until a champion exists.
  const modelLabel = "ingest live · pitch model pending";

  return (
    <ReportSheet>
      <Stack gap={24}>
        <LiveGameHeader
          issueDate={issueDate}
          awayTeam={summary?.awayTeam ?? "—"}
          homeTeam={summary?.homeTeam ?? "—"}
          awayScore={summary?.awayScore ?? 0}
          homeScore={summary?.homeScore ?? 0}
          halfInning={formatHalfInning(summary)}
          batterName={
            mostRecent
              ? `Batter #${mostRecent.batterId}`
              : "Awaiting first pitch"
          }
          pitcherName={
            mostRecent
              ? `Pitcher #${mostRecent.pitcherId}`
              : "Awaiting first pitch"
          }
          issuedAt={issuedAt}
          modelLabel={modelLabel}
        />

        <GameStateStrip cells={deriveStateCells(summary, mostRecent)} />

        {game.isError ? (
          <Text style={{ color: colors.scarlet, fontWeight: 600 }}>
            Could not load game
            {game.error instanceof Error ? `: ${game.error.message}` : ""}.
          </Text>
        ) : null}

        <section aria-labelledby="game-pitch-log-label">
          <div id="game-pitch-log-label">
            <SectionLabel>Live Pitch Log</SectionLabel>
          </div>
          {pitches.isError ? (
            <Text style={{ color: colors.scarlet, fontWeight: 600 }}>
              Could not load pitches
              {pitches.error instanceof Error
                ? `: ${pitches.error.message}`
                : ""}
              .
            </Text>
          ) : pitches.isLoading && pitches.pitches.length === 0 ? (
            <Text style={{ color: colors.textMuted }}>
              Waiting for the first pitch…
            </Text>
          ) : (
            <LivePitchLog pitches={pitches.pitches.slice(0, 50)} />
          )}
        </section>

        <CoverSheetFooter
          buildSha={BUILD_FALLBACK.sha}
          buildDate={BUILD_FALLBACK.date}
        />
      </Stack>
    </ReportSheet>
  );
}

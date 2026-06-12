/**
 * `/games/:id` - per-game live page, FIRST screen on the broadcast identity
 * (redesign PR-2, decision [160]).
 *
 * Composition (light field under dark chrome):
 *   1. Masthead - condensed-italic matchup h1 + context line + <Scorebug>
 *      (team-color wells, wedge state, gold on-air dot, last-pitch detail)
 *   2. State band - <BigStat> row (count / outs / last pitch / pitches seen)
 *   3. <LowerThird> "Live Pitch Log" + <LivePitchBoard> (the hero)
 *   4. <TickerStrip> - decorative recent-pitch crawl (aria-hidden; the same
 *      facts live in the board), dead under prefers-reduced-motion
 *   5. Chrome footer strip
 *
 * Data wiring is UNCHANGED from the paper-era page: `useGame` /
 * `useLivePitches` poll on the status-driven cadence; this PR is presentation
 * only. This page imports ONLY the broadcast token namespace ([160] migration
 * rule: one namespace per screen).
 */
import { useParams } from "react-router-dom";

import {
  useGame,
  useLivePitches,
  type GameSummary,
  type LivePitchRow,
} from "../api/games";
import { BigStat } from "../components/broadcast/big-stat";
import { BroadcastPanel } from "../components/broadcast/broadcast-panel";
import { LowerThird } from "../components/broadcast/lower-third";
import { Scorebug } from "../components/broadcast/scorebug";
import { TickerStrip } from "../components/broadcast/ticker-strip";
import { LivePitchBoard } from "../components/games/live-pitch-board";
import { colors, layouts, typography } from "../design/broadcast";

/** Stable build metadata fallback so the footer always renders. */
const BUILD_FALLBACK = {
  sha: "live",
  date: new Date().toISOString().slice(0, 10),
};

function todayIssueDate(): string {
  return new Intl.DateTimeFormat("en-US", {
    weekday: "short",
    month: "short",
    day: "numeric",
    year: "numeric",
  }).format(new Date());
}

/** Scorebug state read. The API exposes `inning` but not top/bottom - the
 * neutral "INN n" marker carries over from the paper-era page. */
function scorebugState(summary: GameSummary | undefined): string {
  if (!summary) return "—";
  switch (summary.status) {
    case "COMPLETED":
      return "FINAL";
    case "WARMUP":
      return "WARMUP";
    case "SCHEDULED":
      return "PREGAME";
    default:
      return `INN ${summary.inning}`;
  }
}

function isLive(summary: GameSummary | undefined): boolean {
  return summary?.status === "IN_PROGRESS" || summary?.status === "MID_INNING";
}

function lastPitchRead(p: LivePitchRow | undefined): string {
  if (!p) return "—";
  const type = p.pitchType || "—";
  return p.releaseSpeedMph != null
    ? `${type} · ${p.releaseSpeedMph.toFixed(1)}`
    : type;
}

function tickerItems(pitches: LivePitchRow[]): string[] {
  return pitches
    .slice(0, 12)
    .map(
      (p) =>
        `${p.pitchType || "?"} ${
          p.releaseSpeedMph != null ? p.releaseSpeedMph.toFixed(1) : "—"
        } → ${p.description.replace(/_/g, " ")}`,
    );
}

const fieldStyle: React.CSSProperties = {
  backgroundColor: colors.field,
  minHeight: "100%",
  padding: "24px 16px 0",
};

const columnStyle: React.CSSProperties = {
  maxWidth: layouts.broadcastMaxWidth,
  margin: "0 auto",
  display: "flex",
  flexDirection: "column",
  gap: 24,
};

const errorTextStyle: React.CSSProperties = {
  fontFamily: typography.fonts.body,
  fontWeight: typography.weights.semibold,
  color: colors.goldInk,
};

export function GamePage() {
  const { id } = useParams<{ id: string }>();
  const numericId = id ? Number(id) : null;
  const valid = numericId != null && Number.isFinite(numericId);

  const game = useGame(valid ? numericId : null);
  const pitches = useLivePitches(valid ? numericId : null, game.data?.status);

  if (!valid) {
    return (
      <div style={fieldStyle}>
        <div style={columnStyle}>
          <p style={errorTextStyle}>Invalid game id.</p>
        </div>
      </div>
    );
  }

  const summary = game.data;
  const mostRecent = pitches.pitches[0];

  return (
    <div style={fieldStyle}>
      <div style={columnStyle}>
        <header>
          <h1
            style={{
              margin: 0,
              fontFamily: typography.fonts.display,
              fontStyle: "italic",
              fontWeight: typography.weights.heavy,
              fontSize: typography.scale[6],
              lineHeight: typography.lineHeights.display,
              letterSpacing: "0.01em",
              textTransform: "uppercase",
              color: colors.ink,
            }}
          >
            {summary?.awayTeam ?? "—"}{" "}
            <span style={{ color: colors.textMuted, fontWeight: 600 }}>@</span>{" "}
            {summary?.homeTeam ?? "—"}
          </h1>
          <p
            style={{
              margin: "2px 0 12px",
              fontFamily: typography.fonts.mono,
              fontSize: 12,
              fontFeatureSettings: '"tnum" 1',
              letterSpacing: "0.02em",
              color: colors.textMuted,
            }}
          >
            {todayIssueDate()} · live ingest · pitch model pending
          </p>
          <Scorebug
            awayTeam={summary?.awayTeam ?? "—"}
            homeTeam={summary?.homeTeam ?? "—"}
            awayScore={summary?.awayScore ?? 0}
            homeScore={summary?.homeScore ?? 0}
            state={scorebugState(summary)}
            live={isLive(summary)}
            detail={mostRecent ? lastPitchRead(mostRecent) : undefined}
          />
        </header>

        {game.isError ? (
          <p style={errorTextStyle}>
            Could not load game
            {game.error instanceof Error ? `: ${game.error.message}` : ""}.
          </p>
        ) : null}

        <BroadcastPanel cut>
          <div style={{ display: "flex", flexWrap: "wrap", gap: 40 }}>
            <BigStat
              label="Count"
              value={
                mostRecent ? `${mostRecent.balls}-${mostRecent.strikes}` : "—"
              }
            />
            <BigStat
              label="Outs"
              value={mostRecent ? String(mostRecent.outs) : "—"}
            />
            <BigStat label="Last Pitch" value={lastPitchRead(mostRecent)} />
            <BigStat
              label="Pitches"
              value={String(pitches.pitches.length)}
              tone="gold"
            />
          </div>
        </BroadcastPanel>

        <section aria-labelledby="game-pitch-log-label">
          <div style={{ marginBottom: 12 }}>
            <LowerThird
              id="game-pitch-log-label"
              meta={`NEWEST ${Math.min(pitches.pitches.length, 50)}`}
            >
              Live Pitch Log
            </LowerThird>
          </div>
          {pitches.isError ? (
            <p style={errorTextStyle}>
              Could not load pitches
              {pitches.error instanceof Error
                ? `: ${pitches.error.message}`
                : ""}
              .
            </p>
          ) : (
            <LivePitchBoard pitches={pitches.pitches} />
          )}
        </section>

        <TickerStrip items={tickerItems(pitches.pitches)} />

        <footer
          style={{
            display: "flex",
            justifyContent: "space-between",
            alignItems: "center",
            margin: "0 -16px",
            padding: "10px 16px",
            backgroundColor: colors.chromeDeep,
            fontFamily: typography.fonts.mono,
            fontSize: 11,
            letterSpacing: "0.04em",
            color: colors.textOnChromeMuted,
          }}
        >
          <span>THE BULLPEN · LIVE GAME</span>
          <span>
            build {BUILD_FALLBACK.sha} · {BUILD_FALLBACK.date}
          </span>
        </footer>
      </div>
    </div>
  );
}

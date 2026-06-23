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
import { usePlayer } from "../api/players";
import { BigStat } from "../components/broadcast/big-stat";
import { BroadcastPanel } from "../components/broadcast/broadcast-panel";
import { LowerThird } from "../components/broadcast/lower-third";
import { Scorebug } from "../components/broadcast/scorebug";
import { TickerStrip } from "../components/broadcast/ticker-strip";
import { BattedBallExplorer } from "../components/games/batted-ball-explorer";
import { LivePitchBoard } from "../components/games/live-pitch-board";
import { SHOWCASE_BATTED_BALL } from "../data/batted-ball-fixtures";
import { BUILD_DATE, BUILD_SHA } from "../build-info";
import { colors, layouts, typography } from "../design/broadcast";

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
  const mostRecent = pitches.pitches[0];
  // Current pitcher + batter (id -> name). Hooks run before the early return; null id disables them.
  const currentPitcher = usePlayer(mostRecent?.pitcherId ?? null);
  const currentBatter = usePlayer(mostRecent?.batterId ?? null);

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
  const pitcherName =
    currentPitcher.data?.name ??
    (mostRecent ? `#${mostRecent.pitcherId}` : "—");
  const batterName =
    currentBatter.data?.name ?? (mostRecent ? `#${mostRecent.batterId}` : "—");
  // Per-pitcher pitch count - the CURRENT pitcher only, not the whole-game total.
  const pitcherPitchCount = mostRecent
    ? pitches.pitches.filter((p) => p.pitcherId === mostRecent.pitcherId).length
    : 0;

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
          <p
            style={{
              margin: "10px 0 0",
              fontFamily: typography.fonts.body,
              fontSize: 13,
              color: colors.text,
            }}
          >
            Pitching: <strong>{pitcherName}</strong> &middot; At bat:{" "}
            <strong>{batterName}</strong>
          </p>
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
              label="Pitcher Pitches"
              value={String(pitcherPitchCount)}
              tone="gold"
            />
          </div>
        </BroadcastPanel>

        <section aria-labelledby="batted-ball-label">
          <div style={{ marginBottom: 12 }}>
            <LowerThird id="batted-ball-label" meta="MODEL EXAMPLE">
              Batted-Ball Model
            </LowerThird>
          </div>
          <p
            style={{
              margin: "0 0 12px",
              fontFamily: typography.fonts.body,
              fontSize: 12,
              color: colors.textMuted,
            }}
          >
            A static example of the per-park HR model - not this game&rsquo;s
            batted ball. Live batted-ball capture (exit velo / launch /
            distance) is pending: the live feed doesn&rsquo;t carry batted-ball
            physics yet.
          </p>
          <BattedBallExplorer data={SHOWCASE_BATTED_BALL} />
        </section>

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
            build {BUILD_SHA} · {BUILD_DATE}
          </span>
        </footer>
      </div>
    </div>
  );
}

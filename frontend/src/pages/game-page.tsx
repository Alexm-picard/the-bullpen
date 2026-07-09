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
import { useMemo } from "react";
import { useParams } from "react-router-dom";

import {
  useGame,
  useLivePitches,
  usePostPredictions,
  type GameSummary,
  type LivePitchRow,
} from "../api/games";
import {
  CANONICAL_BBE_INPUT,
  useAllParksPrediction,
  type AllParksRequest,
  type AllParksResponse,
} from "../api/parks";
import { usePlayer } from "../api/players";
import { BigStat } from "../components/broadcast/big-stat";
import { BroadcastPanel } from "../components/broadcast/broadcast-panel";
import { LowerThird } from "../components/broadcast/lower-third";
import { Scorebug } from "../components/broadcast/scorebug";
import { TickerStrip } from "../components/broadcast/ticker-strip";
import { BattedBallExplorer } from "../components/games/batted-ball-explorer";
import { LivePitchBoard } from "../components/games/live-pitch-board";
import { PostPredictionPanel } from "../components/games/post-prediction-panel";
import { estimateLandingDistanceFt } from "../components/parks/estimate-landing";
import {
  SHOWCASE_BATTED_BALL,
  type BattedBall,
  type ParkOutcome,
  type ParkOutcomeTone,
} from "../data/batted-ball-fixtures";
import { PARK_ROWS } from "../data/parks-fixtures";
import { BroadcastFooter, PageChrome } from "../components/shared/page-chrome";
import { colors, typography } from "../design/broadcast";

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

// ── Phase 1.2: live batted-ball -> BattedBall mapping ────────────────────────
//
// The all-parks endpoint exposes P(HR) per park ONLY - not a full fielded-outcome
// distribution. So the per-park chip is honestly HR-likelihood, NOT a fabricated
// 1B/2B/3B/OUT: a park reads HR at/above HR_THRESHOLD, else "In play" (the ball
// stays in the yard; the model makes no claim whether it's a hit or an out). The
// actual realized result is the card's top-line `result` (from the live event).
// hrParkCount uses the same HR_THRESHOLD so the headline and chips agree. err is a
// fixed placeholder band because AllParksResponse carries no per-park uncertainty.
const HR_THRESHOLD = 0.5;
const CARRY_ERR_FT = 9;

function titleCaseFromSnake(value: string): string {
  return value
    .split("_")
    .map((w) => (w ? w[0]!.toUpperCase() + w.slice(1) : w))
    .join(" ");
}

// Contact descriptor when bb_type is absent: derive a coarse hit class from the
// launch angle so the sub-line still reads (mirrors the showcase "Fly ball ...").
function bandFromLaunchAngle(deg: number): string {
  if (deg < 10) return "Ground ball";
  if (deg < 25) return "Line drive";
  if (deg < 50) return "Fly ball";
  return "Pop up";
}

function outcomeForProb(p: number): { outcome: string; tone: ParkOutcomeTone } {
  // P(HR)-only model -> honest binary: likely-HR vs stays-in-play. No invented 2B.
  if (p >= HR_THRESHOLD) return { outcome: "HR", tone: "hr" };
  return { outcome: "In play", tone: "out" };
}

/**
 * Map the most-recent in-play pitch + the all-parks prediction into the
 * BattedBall the explorer consumes. The launch fields are guaranteed non-null by
 * the caller's predicate; the park id -> name/team join mirrors <ParkHrHeatmap>.
 * Per-park dist uses the model's carry when the champion serves one, else the
 * BIP's own (estimated) distance; xBA is a placeholder (the endpoint has none).
 */
function buildLiveBattedBall(
  inPlay: LivePitchRow,
  pred: AllParksResponse,
  batterName: string | undefined,
  homeTeam: string | undefined,
): BattedBall {
  const exitVeloMph = inPlay.launchSpeedMph ?? 0;
  const launchAngleDeg = inPlay.launchAngleDeg ?? 0;
  const distanceFt = Math.round(
    inPlay.hitDistanceFt ??
      estimateLandingDistanceFt(exitVeloMph, launchAngleDeg),
  );

  const rowById = new Map(PARK_ROWS.map((row) => [row.id, row]));
  const carry = pred.carryFtByPark;
  const probEntries = Object.entries(pred.probHrByPark);

  const parks: ParkOutcome[] = probEntries.map(([id, p]) => {
    const row = rowById.get(id);
    const { outcome, tone } = outcomeForProb(p);
    const parkCarry = carry?.[id];
    return {
      park: row?.parkName ?? id,
      team: row?.team ?? id,
      outcome,
      tone,
      dist: parkCarry != null ? Math.round(parkCarry) : distanceFt,
      err: CARRY_ERR_FT,
      here: id === homeTeam,
    };
  });

  const parkCount = probEntries.length;
  const hrParkCount = probEntries.filter(([, p]) => p >= HR_THRESHOLD).length;

  // Default-shown: the home park (pinned) first, then the most interesting parks
  // by P(HR), capped at six (matching the showcase's six-row default).
  const byProbDesc = [...probEntries]
    .sort((a, b) => b[1] - a[1])
    .map(([id]) => rowById.get(id)?.parkName ?? id);
  const homeParkName = parks.find((pk) => pk.here)?.park;
  const defaultShown: string[] = [];
  if (homeParkName) defaultShown.push(homeParkName);
  for (const name of byProbDesc) {
    if (defaultShown.length >= 6) break;
    if (!defaultShown.includes(name)) defaultShown.push(name);
  }

  const descriptor = inPlay.bbType
    ? titleCaseFromSnake(inPlay.bbType)
    : bandFromLaunchAngle(launchAngleDeg);

  return {
    batter: batterName ?? `#${inPlay.batterId}`,
    description: `${descriptor} · ${inPlay.outs} out`,
    result: inPlay.event ? titleCaseFromSnake(inPlay.event) : "In play",
    exitVeloMph,
    launchDeg: Math.round(launchAngleDeg),
    distanceFt,
    xba: "—", // AllParksResponse carries no xBA; do not fabricate one.
    hrParkCount,
    parkCount,
    parks,
    defaultShown,
    // Name the real served champion (calibration source); omit the editorial narrative on the
    // live path so no hardcoded "caught at the track" line contradicts the actual result.
    modelName: pred.modelName,
    modelVersion: pred.modelVersion,
  };
}

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
  const postPredictions = usePostPredictions(
    valid ? numericId : null,
    game.data?.status,
  );
  const mostRecent = pitches.pitches[0];
  // Current pitcher + batter (id -> name). Hooks run before the early return; null id disables them.
  const currentPitcher = usePlayer(mostRecent?.pitcherId ?? null);
  const currentBatter = usePlayer(mostRecent?.batterId ?? null);

  // Phase 1.2: the most recent in-play batted ball carrying launch physics. The
  // pitch store is newest-first, so .find() yields the LATEST qualifying BIP.
  const inPlay = pitches.pitches.find(
    (p) =>
      p.description === "in_play" &&
      p.launchSpeedMph != null &&
      p.launchAngleDeg != null,
  );
  // The BIP's batter, keyed to the in-play pitch (NOT mostRecent, which may be a
  // later non-BIP pitch in the same at-bat or a new one).
  const inPlayBatter = usePlayer(inPlay?.batterId ?? null);

  // All-parks prediction for the live BIP. The query is GATED on a live BIP
  // (enabled below): POST /v1/predict/batted-ball/all-parks logs every request to
  // prediction_log (the drift-baseline source), so a throwaway prediction on a
  // pregame / between-BIP mount would pollute the drift baselines the Phase-6
  // postmortem reads. When there is no BIP the req is a stable placeholder that is
  // NEVER fetched (the gate is off); it only keeps the hook's arg typed.
  const allParksReq = useMemo<AllParksRequest>(() => {
    if (inPlay?.launchSpeedMph == null || inPlay.launchAngleDeg == null) {
      return CANONICAL_BBE_INPUT;
    }
    return {
      launchSpeedMph: inPlay.launchSpeedMph,
      launchAngleDeg: inPlay.launchAngleDeg,
      sprayAngleDeg: 0,
      hitDistanceFt:
        inPlay.hitDistanceFt ??
        estimateLandingDistanceFt(inPlay.launchSpeedMph, inPlay.launchAngleDeg),
      stand: "R",
      baseState: 0,
      outs: inPlay.outs,
    };
  }, [inPlay]);
  const allParks = useAllParksPrediction(allParksReq, {
    enabled: inPlay != null,
  });

  // The live BattedBall, or null until BOTH the BIP and its prediction exist (->
  // the showcase fallback below). Memoised so polls with no new data are cheap.
  const liveBattedBall = useMemo<BattedBall | null>(() => {
    if (!inPlay || !allParks.data) return null;
    return buildLiveBattedBall(
      inPlay,
      allParks.data,
      inPlayBatter.data?.name,
      game.data?.homeTeam,
    );
  }, [inPlay, allParks.data, inPlayBatter.data?.name, game.data?.homeTeam]);

  if (!valid) {
    return (
      <PageChrome gap={24}>
        <p style={errorTextStyle}>Invalid game id.</p>
      </PageChrome>
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

  // Live batted ball when this game has one; otherwise the showcase empty-state.
  const battedBall = liveBattedBall ?? SHOWCASE_BATTED_BALL;
  const battedBallLive = liveBattedBall != null;

  return (
    <PageChrome gap={24}>
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
          <LowerThird
            id="batted-ball-label"
            meta={battedBallLive ? "LIVE BIP" : "MODEL EXAMPLE"}
          >
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
          {battedBallLive ? (
            <>
              The most recent in-play batted ball this game, scored through the
              per-park batted-ball champion: the same contact at all 30 parks,
              carry and outcome shifting with each park.
            </>
          ) : (
            <>
              A static example of the per-park HR model - not this game&rsquo;s
              batted ball. Live batted-ball capture (exit velo / launch /
              distance) is pending: the live feed doesn&rsquo;t carry
              batted-ball physics yet.
            </>
          )}
        </p>
        <BattedBallExplorer data={battedBall} />
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
            {pitches.error instanceof Error ? `: ${pitches.error.message}` : ""}
            .
          </p>
        ) : (
          <LivePitchBoard pitches={pitches.pitches} />
        )}
      </section>

      <section aria-labelledby="game-post-predictions-label">
        <div style={{ marginBottom: 12 }}>
          <LowerThird id="game-post-predictions-label" meta="RETROSPECTIVE">
            Post-Pitch Model Scorecard
          </LowerThird>
        </div>
        {postPredictions.isError ? (
          <p style={errorTextStyle}>
            Could not load post-pitch predictions
            {postPredictions.error instanceof Error
              ? `: ${postPredictions.error.message}`
              : ""}
            .
          </p>
        ) : (
          <PostPredictionPanel
            rows={postPredictions.data?.rows ?? []}
            hasNext={postPredictions.data?.hasNext ?? false}
          />
        )}
      </section>

      <TickerStrip items={tickerItems(pitches.pitches)} />

      <BroadcastFooter>LIVE GAME</BroadcastFooter>
    </PageChrome>
  );
}

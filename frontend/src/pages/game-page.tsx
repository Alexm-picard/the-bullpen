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
import { useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router";

import {
  matchupIsAheadOf,
  nextPitchRequest,
  pitchTypeRequest,
  postPitchCount,
  useGame,
  useLivePitches,
  useLiveState,
  usePitchPrediction,
  usePitchTypePrediction,
  useTeamContact,
  type GameSummary,
  type LivePitchRow,
  type RecentBattedBall,
} from "../api/games";
import {
  useAllParksPrediction,
  type AllParksRequest,
  type AllParksResponse,
} from "../api/parks";
import { usePlayer } from "../api/players";
import { BigStat } from "../components/broadcast/big-stat";
import { BroadcastPanel } from "../components/broadcast/broadcast-panel";
import { LowerThird } from "../components/broadcast/lower-third";
import { Scorebug } from "../components/broadcast/scorebug";
import {
  TickerStrip,
  type TickerItem,
} from "../components/broadcast/ticker-strip";
import { BattedBallExplorer } from "../components/games/batted-ball-explorer";
import { LivePitchBoard } from "../components/games/live-pitch-board";
import { NextPitchPanel } from "../components/games/next-pitch-panel";
import { PitchTypePanel } from "../components/games/pitch-type-panel";
import { TeamContactPanel } from "../components/games/team-contact-panel";
import {
  type BattedBall,
  type ParkOutcome,
  type ParkOutcomeTone,
} from "../data/batted-ball-fixtures";
import { PARK_ROWS } from "../data/parks-fixtures";
import { BroadcastFooter, PageChrome } from "../components/shared/page-chrome";
import { colors, typography } from "../design/broadcast";

// Hoisted: constructing an Intl formatter is not free, and the page re-renders on every poll.
const ISSUE_DATE = new Intl.DateTimeFormat("en-US", {
  weekday: "short",
  month: "short",
  day: "numeric",
  year: "numeric",
});

function todayIssueDate(): string {
  return ISSUE_DATE.format(new Date());
}

/** Scorebug state read. The API exposes `inning` but not top/bottom - the
 * neutral "INN n" marker carries over from the paper-era page. Only a game
 * actually in play reads an inning: a postponed game must not print "INN 0",
 * nor a rain delay "INN 5" as if play were on. */
function scorebugState(summary: GameSummary | undefined): string {
  if (!summary) return "—";
  switch (summary.status) {
    case "COMPLETED":
      return "FINAL";
    case "WARMUP":
      return "WARMUP";
    case "SCHEDULED":
      return "PREGAME";
    case "IN_PROGRESS":
    case "MID_INNING":
      return `INN ${summary.inning}`;
    case "DELAYED":
      return "DELAY";
    case "SUSPENDED":
      return "SUSP";
    case "POSTPONED":
      return "PPD";
    default:
      return "—";
  }
}

/** Stopped-play statuses: the scorebug's detail reads MLB's own description
 * ("Delayed Start: Rain") instead of a stale last pitch. */
function isStoppedPlay(summary: GameSummary | undefined): boolean {
  return summary?.status === "DELAYED" || summary?.status === "SUSPENDED";
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

function tickerItems(pitches: LivePitchRow[]): TickerItem[] {
  // Keyed by the pitch cursor: two pitches can read identically ("FF 94.8 → ball").
  return pitches.slice(0, 12).map((p) => ({
    key: p.cursor,
    text: `${p.pitchType || "?"} ${
      p.releaseSpeedMph != null ? p.releaseSpeedMph.toFixed(1) : "—"
    } → ${p.description.replace(/_/g, " ")}`,
  }));
}

/** The "What" link to the model guide: a client-side route change, so the live
 * page's polling and cache survive the round trip. */
function GuideLink({ anchor }: { anchor: string }) {
  return (
    <Link
      to={`/models/guide#${anchor}`}
      className="bp-link bp-pressable"
      style={{
        display: "inline-flex",
        alignItems: "center",
        gap: 4,
        minHeight: 32,
        fontFamily: typography.fonts.mono,
        fontSize: 12,
        letterSpacing: typography.tracking.eyebrow,
        textTransform: "uppercase",
        border: `1px solid ${colors.rule}`,
        padding: "6px 10px",
      }}
    >
      <span style={{ color: colors.gold, fontSize: 11 }}>{"ⓘ"}</span> What
    </Link>
  );
}

/** Section header row: the lower third plus its guide link, wrapping the link
 * under the bar at phone width instead of overflowing. */
const sectionHeaderRow: React.CSSProperties = {
  marginBottom: 12,
  display: "flex",
  flexWrap: "wrap",
  alignItems: "center",
  gap: 12,
};

/** The gold "primary" rail: 3px border + 13px pad = the 16px gutter exactly,
 * so the rail never pokes past the page edge. */
const primaryRail: React.CSSProperties = {
  borderLeft: `3px solid ${colors.gold}`,
  paddingLeft: 13,
  marginLeft: -16,
};

/** Stable identity of a batted ball across polls (the summary object can be re-created). */
function bipKeyOf(bb: RecentBattedBall | null): string | null {
  return bb ? `${bb.batterId}-${bb.atBatIndex}-${bb.pitchNumber}` : null;
}

// ── Phase 1.2: live batted-ball -> BattedBall mapping ────────────────────────
//
// The all-parks endpoint exposes P(HR) per park ONLY - not a full fielded-outcome
// distribution. So the per-park chip is honestly HR-likelihood, NOT a fabricated
// 1B/2B/3B/OUT: a park reads HR at/above HR_THRESHOLD, else "In play" (the ball
// stays in the yard; the model makes no claim whether it's a hit or an out). The
// actual realized result is the card's top-line `result` (from the live event).
// hrParkCount uses the same HR_THRESHOLD so the headline and chips agree. err is NULL on the live
// path: AllParksResponse carries no per-park uncertainty, and printing a fixed band beside a real
// carry would be an invented confidence interval read as the model's own precision.
const HR_THRESHOLD = 0.5;

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
  inPlay: RecentBattedBall,
  pred: AllParksResponse,
  batterName: string | undefined,
  homeTeam: string | undefined,
): BattedBall {
  const exitVeloMph = inPlay.launchSpeedMph;
  const launchAngleDeg = inPlay.launchAngleDeg;
  const distanceFt = Math.round(inPlay.hitDistanceFt);

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
      err: null, // the model reports no per-park uncertainty; do not invent one
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

  // ONE band, used by both the sub-line and the distance metric. Previously the sub-line used
  // this value while the metric re-derived its own from the ROUNDED angle - so a raw 9.6 degrees
  // read "Ground ball" in one place and "Line drive" in the other, and a present bbType made the
  // two disagree by source as well as by input.
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
    band: descriptor,
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
  const mostRecent = pitches.pitches[0];

  // The fan-facing error copy drops the raw message; keep it for whoever opens the console. Logged
  // once per distinct error object, not on every re-render.
  useEffect(() => {
    if (game.error) console.error("game summary failed to load", game.error);
  }, [game.error]);
  useEffect(() => {
    if (pitches.error) console.error("pitch log failed to load", pitches.error);
  }, [pitches.error]);

  // Decision [194]: poll the worker-computed live state at 2s. When the flag is off or the
  // endpoint returns no predictions, fall back to the derive-and-POST path below.
  const liveState = useLiveState(valid ? numericId : null, game.data?.status);
  const ls = liveState.data;
  const lsHasPredictions = ls?.prePrediction != null;

  // WHO IS BATTING: prefer the live-state matchup (2s freshness) when available, else the game
  // summary's currentPlay matchup, else the last thrown pitch. The fallback chain means this can
  // never render worse than before.
  const liveMatchup = ls?.matchup ?? game.data?.currentMatchup ?? null;
  const rowIsPastTense = matchupIsAheadOf(liveMatchup, mostRecent);

  // Freshness guard: use upcomingPitch only when it describes a pitch AHEAD of the log.
  const upcomingKey = ls?.upcomingPitch
    ? ls.upcomingPitch.atBatIndex * 100 + ls.upcomingPitch.pitchNumber
    : 0;
  const newestLogCursor = mostRecent?.cursor ?? 0;
  const upcomingIsFresh = upcomingKey >= newestLogCursor + 1;
  const shownPitcherId =
    liveMatchup?.pitcherId ?? mostRecent?.pitcherId ?? null;
  const shownBatterId = liveMatchup?.batterId ?? mostRecent?.batterId ?? null;
  const currentPitcher = usePlayer(shownPitcherId);
  const currentBatter = usePlayer(shownBatterId);

  // A6: the forward-looking next-pitch estimate (ADR-0014). When live-state predictions are
  // available, the POST hooks are disabled - the worker already computed them. When the live-state
  // endpoint is off or has no predictions, the original derive-and-POST path fires.
  const nextReq =
    mostRecent && game.data
      ? nextPitchRequest(mostRecent, game.data.gameDate, liveMatchup)
      : null;
  const nextPitchEnabled =
    isLive(game.data) && nextReq != null && !lsHasPredictions;
  const nextPitch = usePitchPrediction(nextReq, { enabled: nextPitchEnabled });

  const pitchTypeReq =
    mostRecent && game.data
      ? pitchTypeRequest(
          mostRecent,
          game.data.gameDate,
          game.data.gameId,
          liveMatchup,
        )
      : null;
  const pitchTypeEnabled =
    isLive(game.data) && pitchTypeReq != null && !lsHasPredictions;
  const pitchType = usePitchTypePrediction(pitchTypeReq, {
    enabled: pitchTypeEnabled,
  });

  // Synthesize panel-compatible prediction objects from the live-state data when available.
  // The panels expect PitchPredictionResponse / PitchTypePriorResponse shapes; the live-state
  // response is a subset (probabilities + winner, no latency/correlation). Fields the panels
  // don't actually render (latencyMicros, correlationId, elapsedMicros, priorPitches) are filled
  // with placeholder values. The panel rendering logic only reads probabilities, winner,
  // modelName, and modelVersion/servingVersion.
  const nextPitchData = useMemo(() => {
    if (lsHasPredictions && ls?.prePrediction) {
      return {
        probabilities: ls.prePrediction.probabilities,
        winner: ls.prePrediction.winner,
        modelName: "pitch_outcome_pre",
        modelVersion: ls.modelVersions?.pre ?? "",
        latencyMicros: 0,
        correlationId: "",
      };
    }
    return nextPitch.data;
  }, [lsHasPredictions, ls, nextPitch.data]);

  const pitchTypeData = useMemo(() => {
    if (lsHasPredictions && ls?.pitchTypePrediction) {
      return {
        probabilities: ls.pitchTypePrediction.probabilities,
        modelName: "pitch_type_pre",
        servingVersion: ls.modelVersions?.pitchType ?? "",
        priorPitches: 0,
        elapsedMicros: 0,
        correlationId: "",
      };
    }
    return pitchType.data;
  }, [lsHasPredictions, ls, pitchType.data]);

  // The enabled state for the panels: live-state predictions count as "enabled" too.
  const nextPitchPanelEnabled =
    (isLive(game.data) && nextReq != null) || lsHasPredictions;
  const pitchTypePanelEnabled =
    (isLive(game.data) && pitchTypeReq != null) || lsHasPredictions;

  // Phase 1.2: the most recent in-play batted ball carrying launch physics. The
  // pitch store is newest-first, so .find() yields the LATEST qualifying BIP.
  // The most recent COMPLETED ball in play, from the game summary rather than a scan of the pitch
  // list. That list is the newest 50 pitches - a window, not the game - so scanning it found a
  // batted ball only while it happened to still be inside, and failed by looking like "no batted
  // ball yet" rather than like a bug.
  const inPlay = game.data?.mostRecentBattedBall ?? null;
  // ADR-0017 §3 entrance, gated by [112]: only a ball that arrives WHILE the page is open animates.
  // The baseline is the ball (or absence of one) in the FIRST loaded summary - not the first
  // render, which has no summary yet and would make a mid-game page load look like a new ball.
  const bipKey = bipKeyOf(inPlay);
  // Keyed to the game: back/forward between two /games/:id URLs keeps this component mounted, so
  // the baseline must re-seed when the game changes or the other game's ball would animate in.
  const [bipBaseline, setBipBaseline] = useState<{
    gameId: number;
    key: string | null;
  } | null>(null);
  const summaryGameId = game.data?.gameId ?? null;
  if (summaryGameId != null && bipBaseline?.gameId !== summaryGameId) {
    setBipBaseline({ gameId: summaryGameId, key: bipKey });
  }
  const bipArrivedLive =
    bipKey != null &&
    bipBaseline != null &&
    bipBaseline.gameId === summaryGameId &&
    bipKey !== bipBaseline.key;
  // The BIP's batter, keyed to the in-play pitch (NOT mostRecent, which may be a
  // later non-BIP pitch in the same at-bat or a new one).
  const inPlayBatter = usePlayer(inPlay?.batterId ?? null);

  // All-parks prediction for the live BIP. The query is GATED on a live BIP
  // (enabled below): POST /v1/predict/batted-ball/all-parks logs every request to
  // prediction_log (the drift-baseline source), so a throwaway prediction on a
  // pregame / between-BIP mount would pollute the drift baselines the Phase-6
  // postmortem reads. When any required field is missing the request is NULL, so the query has no
  // key at all - it neither fires nor reads a cache entry another page populated.
  const allParksReq = useMemo<AllParksRequest | null>(() => {
    if (
      inPlay == null ||
      // Spray is REQUIRED and cannot be invented. The server declines it where the geometry
      // degenerates (a ball tracked at or behind the plate, or an angle outside the foul lines),
      // and the honest response to a declined value is to not ask the model - not to send 0.
      // Measured cost: ~2% of balls at 150+ ft, so this almost never fires on a ball anyone would
      // want compared across parks.
      inPlay.sprayAngleDeg == null ||
      inPlay.baseState == null
    ) {
      return null;
    }
    // Batter side from the ROW, resolved for switch hitters exactly as nextPitchRequest resolves
    // it. Previously hardcoded "R": harmless only while the card almost never rendered live, and a
    // live-scored left-hander would otherwise be modelled as a right-hander on the page whose
    // entire purpose is showing the real batted ball.
    // Switch hitters are already resolved server-side, at the source, so the page does not
    // re-derive a side the model input might disagree with.
    const stand = inPlay.stand;
    if (stand !== "R" && stand !== "L") return null;
    return {
      launchSpeedMph: inPlay.launchSpeedMph,
      launchAngleDeg: inPlay.launchAngleDeg,
      sprayAngleDeg: inPlay.sprayAngleDeg,
      hitDistanceFt: inPlay.hitDistanceFt,
      stand,
      baseState: inPlay.baseState,
      outs: inPlay.outs,
    };
  }, [inPlay]);
  // A null request means the query has no key at all, so it neither fires nor reads a cache entry
  // some other page populated. Withholding is the whole point: an incomplete request must not
  // become a prediction, and must not silently borrow one.
  const allParks = useAllParksPrediction(allParksReq, {
    enabled: allParksReq != null,
    context:
      game.data && inPlay
        ? { gameId: game.data.gameId, parkId: inPlay.parkId }
        : undefined,
  });

  // The pre-first-pitch comparison fills the state the retired fixture used to occupy. Gated on
  // there being NO batted ball: it is a season-wide scan plus N inferences per team, so it must not
  // run alongside the live card it stands in for.
  const teamContact = useTeamContact(game.data?.gameId ?? null, {
    enabled: game.data != null && inPlay == null,
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
  // While the lookup for a JUST-CHANGED player is in flight, show the em-dash rather than a raw
  // MLB id: this feature makes identity flip at every at-bat, pitching change and half-inning, so
  // what used to be a rare glimpse of a bare numeric player id would now be a regular one in the
  // page's most prominent live line. (An MLB id is six digits, so a literal example here reads as
  // a color to lint:hex-codes - hence the prose.) Deliberately NOT keeping the previous name as placeholder data - that
  // would re-introduce, for a few hundred milliseconds, exactly the wrong-batter display this
  // whole change exists to remove.
  const playerName = (
    q: { data?: { name?: string }; isPending: boolean },
    id: number | null,
  ): string => q.data?.name ?? (q.isPending || id == null ? "—" : `#${id}`);
  const pitcherName = playerName(currentPitcher, shownPitcherId);
  const batterName = playerName(currentBatter, shownBatterId);
  // Handedness rides the name only when there IS a name: "— (R)" attaches a hand to an unknown
  // player, and this feature makes that pending window recur at every at-bat, pitching change and
  // half-inning. "S" is resolved against the current pitcher exactly as nextPitchRequest resolves
  // it, so the chyron and the model input never disagree about which side a switch hitter bats -
  // an unresolved "(S)" would read to a viewer as a handedness, which it is not.
  // The "#id" fallback DOES identify a player, so handedness on it is truthful; only the pending
  // em-dash names nobody.
  const named = (n: string) => n !== "—";
  // Gated on the RESOLVED code, not the raw one: a switch hitter whose pitcher hand has not
  // arrived resolves to "", and gating on the raw "S" would render an empty " ()".
  const handSuffix = (resolved: string, name: string) =>
    resolved !== "" && named(name) ? ` (${resolved})` : "";
  const livePitchHand = liveMatchup?.pitchHand ?? "";
  const liveBatSideRaw = liveMatchup?.batSide ?? "";
  const liveBatSide =
    liveBatSideRaw === "S"
      ? livePitchHand === "R"
        ? "L"
        : livePitchHand === "L"
          ? "R"
          : ""
      : liveBatSideRaw;
  const shownPitchHand = handSuffix(livePitchHand, pitcherName);
  const shownBatSide = handSuffix(liveBatSide, batterName);
  // Per-pitcher pitch count - the CURRENT pitcher only, not the whole-game total. Counted against
  // the pitcher actually on the mound, so a pitching change resets it immediately rather than
  // carrying the reliever's count over from the pitcher he replaced.
  const pitcherPitchCount =
    shownPitcherId != null
      ? pitches.pitches.filter((p) => p.pitcherId === shownPitcherId).length
      : 0;

  // No fixture fallback. A labelled static example was defensible while no real batted ball could
  // ever render here; now that one can, a Stanton card sitting on a live game page is the
  // fixtures-presented-as-content defect the audit named. When there is no ball in play yet the
  // page says so and shows nothing, which is the truth about the game.
  const battedBall = liveBattedBall;
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
          {todayIssueDate()} · live ingest
        </p>
        {summary ? (
          <Scorebug
            awayTeam={summary.awayTeam}
            homeTeam={summary.homeTeam}
            awayScore={summary.awayScore}
            homeScore={summary.homeScore}
            state={scorebugState(summary)}
            live={isLive(summary)}
            detail={
              isStoppedPlay(summary)
                ? summary.detailedState || undefined
                : mostRecent
                  ? lastPitchRead(mostRecent)
                  : undefined
            }
            announceDetail={isStoppedPlay(summary)}
          />
        ) : (
          // Reserve the scorebug's height (18px line + 12px well padding + 2px border, rounded to
          // the rendered box) so the masthead does not jump when the summary lands - and print no
          // score at all rather than a fabricated 0-0.
          <div aria-hidden="true" style={{ height: 41 }} />
        )}
        <p
          style={{
            margin: "10px 0 0",
            fontFamily: typography.fonts.body,
            fontSize: 13,
            color: colors.text,
          }}
        >
          Pitching: <strong>{pitcherName}</strong>
          {shownPitchHand} &middot; At bat: <strong>{batterName}</strong>
          {shownBatSide}
        </p>
      </header>

      {game.isError ? (
        <p role="alert" style={errorTextStyle}>
          Could not load this game right now. Retrying automatically.
        </p>
      ) : null}

      <BroadcastPanel cut>
        <div style={{ display: "flex", flexWrap: "wrap", gap: "16px 40px" }}>
          {/* Count and Outs are derived from the newest STORED pitch, so once the live matchup
              has moved past that pitch's at-bat they describe a moment that is over. Naming the
              live batter beside a finished at-bat's count would read as one confident composite
              ("leadoff hitter, 1-2 count, 2 outs") that is checkable against the broadcast and
              wrong - worse than the stale-but-consistent page this replaced. Em-dash is this
              page's existing way of saying "not known right now". */}
          <BigStat
            label="Count"
            minCh={3}
            value={(() => {
              if (ls?.upcomingPitch && upcomingIsFresh)
                return `${ls.upcomingPitch.balls}-${ls.upcomingPitch.strikes}`;
              if (mostRecent && !rowIsPastTense) {
                const post = postPitchCount(mostRecent);
                if (post) return `${post.balls}-${post.strikes}`;
              }
              return "—";
            })()}
          />
          <BigStat
            label="Outs"
            minCh={1}
            value={(() => {
              if (ls?.upcomingPitch && upcomingIsFresh)
                return String(ls.upcomingPitch.outs);
              if (mostRecent && !rowIsPastTense) {
                const post = postPitchCount(mostRecent);
                if (post) return String(mostRecent.outs);
              }
              return "—";
            })()}
          />
          {/* Speed is the numeral, type the sub-line: the 48px figure stays <= 5 chars. */}
          <BigStat
            label="Last Pitch"
            value={
              mostRecent?.releaseSpeedMph != null
                ? mostRecent.releaseSpeedMph.toFixed(1)
                : "—"
            }
            sub={mostRecent ? mostRecent.pitchType || "—" : undefined}
          />
          <BigStat
            label="Pitch Count"
            minCh={3}
            value={String(pitcherPitchCount)}
            tone="gold"
          />
        </div>
      </BroadcastPanel>

      <section
        aria-labelledby="next-pitch-label"
        style={nextPitchPanelEnabled ? primaryRail : undefined}
      >
        <div style={sectionHeaderRow}>
          <LowerThird
            id="next-pitch-label"
            meta={nextPitchPanelEnabled ? "LIVE ESTIMATE" : "GATED"}
          >
            Next-Pitch Model
          </LowerThird>
          <GuideLink anchor="next-pitch" />
        </div>
        <NextPitchPanel
          prediction={nextPitchData}
          isLoading={lsHasPredictions ? false : nextPitch.isLoading}
          error={lsHasPredictions ? null : nextPitch.error}
          enabled={nextPitchPanelEnabled}
        />
      </section>

      <section aria-labelledby="pitch-type-label">
        <div style={sectionHeaderRow}>
          <LowerThird
            id="pitch-type-label"
            meta={pitchTypePanelEnabled ? "LIVE PRIOR" : "GATED"}
          >
            Pitch-Type Model
          </LowerThird>
          <GuideLink anchor="pitch-type" />
        </div>
        <PitchTypePanel
          prior={pitchTypeData}
          isLoading={lsHasPredictions ? false : pitchType.isLoading}
          error={lsHasPredictions ? null : pitchType.error}
          enabled={pitchTypePanelEnabled}
        />
      </section>

      <section
        aria-labelledby="batted-ball-label"
        style={battedBallLive ? primaryRail : undefined}
      >
        <div style={sectionHeaderRow}>
          <LowerThird
            id="batted-ball-label"
            // Not "MODEL EXAMPLE" any more - there is no example. The fixture is retired, so
            // the un-live state is an absence of data, not a substitute for it.
            // Three-way, not two. "AWAITING BIP" above a caption that says "Scoring this batted
            // ball..." asserts a known falsehood in the highest-contrast element of the section -
            // the same defect the caption was just fixed for, one line up, and now contradicting
            // the fix rather than merely agreeing with the old bug.
            meta={
              battedBallLive
                ? "LIVE BIP"
                : inPlay != null
                  ? "SCORING"
                  : "AWAITING BIP"
            }
          >
            Batted-Ball Model
          </LowerThird>
          <GuideLink anchor="batted-ball" />
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
            <>Most recent in-play batted ball, scored across all 30 parks.</>
          ) : inPlay != null ? (
            // A ball WAS put in play - the page has been told so. Saying "no ball has been put in
            // play yet" here is the same defect as saying it on an error: asserting a fact the page
            // knows to be false. Three sub-states, all reachable, one of them the HAPPY PATH: every
            // new ball re-keys the all-parks query, so `data` is undefined while it fetches.
            allParksReq == null ? (
              <>
                A ball was put in play, but its landing coordinates were not
                tracked cleanly enough to score it across parks - so the
                comparison is withheld rather than estimated.
              </>
            ) : allParks.isError ? (
              <>Could not score this batted ball across parks.</>
            ) : (
              <>Scoring this batted ball across all 30 parks&hellip;</>
            )
          ) : game.isError ? (
            // NOT "no ball in play yet": that asserts a fact about the game when we simply failed
            // to load it. An error state must say what it knows, which is nothing.
            <>Could not load this game&rsquo;s batted balls.</>
          ) : game.isPending ? (
            <>Loading this game&rsquo;s batted balls&hellip;</>
          ) : summary?.status === "COMPLETED" ? (
            // "yet" promises more baseball. A finished game with no ball in play is finished.
            <>No ball was put in play in this game.</>
          ) : (
            <>No ball has been put in play in this game yet.</>
          )}
        </p>
        {battedBall ? (
          // Keyed per ball, so each new ball remounts and (when it arrived live) enters.
          <BattedBallExplorer
            key={bipKey ?? undefined}
            data={battedBall}
            enter={bipArrivedLive}
          />
        ) : inPlay == null && !game.isError ? (
          // Not while the game itself failed to load: the comparison never fires then, and its
          // panel would read "Scoring..." under a caption that already says the load failed.
          <TeamContactPanel
            data={teamContact.data}
            isLoading={teamContact.isLoading}
            error={teamContact.error}
          />
        ) : null}
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
          <p role="alert" style={errorTextStyle}>
            Could not load pitches right now. Retrying automatically.
          </p>
        ) : (
          <LivePitchBoard
            pitches={pitches.pitches}
            isPending={pitches.isPending}
          />
        )}
      </section>

      <TickerStrip items={tickerItems(pitches.pitches)} />

      <BroadcastFooter>LIVE GAME</BroadcastFooter>
    </PageChrome>
  );
}

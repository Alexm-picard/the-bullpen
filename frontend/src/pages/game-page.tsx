/**
 * `/games/:id` - the live game page on the [195] editorial "Front Page" identity (approved
 * SPEC-game, 2026-10-09; owner revision: B's left-rail scorecard beside the account).
 *
 * Reading order (one at both widths):
 *   1. Dateline + breadcrumb.
 *   2. TOP BLOCK: slug, score headline, dek; the situation; then the NOW-SLOT (the next pitch, by
 *      outcome - or the ball just put in play) beside the next pitch, by type.
 *   3. THE ACCOUNT: a sticky scorecard rail (phones: a one-line strip), the line score, every
 *      at-bat newest first with "model gave it" per pitch, and sidenotes.
 *
 * Data wiring is UNCHANGED from the broadcast page - same hooks, same gates, same freshness guards:
 *  - useGame / useLivePitches poll on the status-driven cadence; useLiveState ([194]) at 2s.
 *  - usePitchPrediction / usePitchTypePrediction fire only when the game is live, a request can be
 *    built, AND /live has no worker-computed predictions (exactly as before; no new caller).
 *  - useAllParksPrediction stays NULL-KEYED unless a real ball in play carries spray + base state:
 *    the endpoint logs every request to prediction_log, so it is never called speculatively.
 *  - useTeamContact runs only while the game has no ball in play.
 *  - New reads are cache-backed lookups only: player names for the account (`/v1/players/:id`, the
 *    same key usePlayer uses) and today's matchups for pre-game probables.
 *
 * Temporal hierarchy (ADR-0017 §3): during an at-bat the next-pitch estimate holds the now-slot. A
 * ball in play takes the slot ONLY when it arrived while the page was open and nothing has been
 * thrown since; it enters once (reduced motion: a fade), and never on page load - a ball that was
 * already there when the page opened lives in the account.
 */
import { VisuallyHidden } from "@mantine/core";
import { useQueries } from "@tanstack/react-query";
import { useEffect, useMemo, useRef, useState } from "react";
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
  type RecentBattedBall,
} from "../api/games";
import { useTodaysMatchups } from "../api/matchups";
import { firstPitchEt } from "../api/matchups-view";
import { useAllParksPrediction, type AllParksRequest } from "../api/parks";
import { getPlayer, usePlayer } from "../api/players";
import { BUILD_DATE, BUILD_SHA } from "../build-info";
import {
  AccountStream,
  type LatestBall,
} from "../components/games/account-stream";
import { BasesGlyph } from "../components/games/bases-glyph";
import {
  atBatSentence,
  atBatsFrom,
  basesPhrase,
  eventPhrase,
  hrParkCount,
  isFinalStatus,
  lineScoreFrom,
  ordinal,
  parkLines,
} from "../components/games/game-account";
import { LineScore } from "../components/games/line-score";
import { OutcomeAgate } from "../components/games/outcome-agate";
import { ParkAgate } from "../components/games/park-agate";
import { PitchTypeSection } from "../components/games/pitch-type-section";
import {
  PhoneScoreStrip,
  ScorecardRail,
  type Scorecard,
} from "../components/games/scorecard";
import { useScrolledPast } from "../components/games/use-scrolled-past";
import { TeamContactPanel } from "../components/games/team-contact-panel";
import { INFERRED_NOTE, MODEL_GAVE_NOTE } from "../data/game-claims";

// Hoisted: constructing an Intl formatter is not free, and the page re-renders on every poll.
const ET_DATE = new Intl.DateTimeFormat("en-US", {
  weekday: "long",
  month: "long",
  day: "numeric",
  year: "numeric",
  timeZone: "America/New_York",
});
const ET_CLOCK = new Intl.DateTimeFormat("en-US", {
  hour: "numeric",
  minute: "2-digit",
  second: "2-digit",
  timeZone: "America/New_York",
});
const ET_HM = new Intl.DateTimeFormat("en-US", {
  hour: "numeric",
  minute: "2-digit",
  timeZone: "America/New_York",
});

function etClock(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const t = Date.parse(iso);
  return Number.isNaN(t) ? null : `${ET_CLOCK.format(new Date(t))} ET`;
}

const PLAYING = new Set(["IN_PROGRESS", "MID_INNING"]);
const STOPPED = new Set(["DELAYED", "SUSPENDED"]);
const NOT_STARTED = new Set(["SCHEDULED", "WARMUP", "POSTPONED", "UNKNOWN"]);

function isLive(summary: GameSummary | undefined): boolean {
  return summary?.status === "IN_PROGRESS" || summary?.status === "MID_INNING";
}

/** Score is printed only for a game that has started - a postponed game must not read "0, 0". */
function hasScore(summary: GameSummary | undefined, pitchCount: number) {
  if (!summary) return false;
  if (NOT_STARTED.has(summary.status)) return false;
  if (STOPPED.has(summary.status)) return pitchCount > 0;
  return true;
}

/** The sentence-case slug: state in words, so it needs no colour to be read. */
function slugFor(
  summary: GameSummary | undefined,
  pitchCount: number,
  firstPitch: string | null,
): string {
  if (!summary) return "Loading the game";
  const inn = summary.inning > 0 ? ordinal(summary.inning) : null;
  switch (summary.status) {
    case "SCHEDULED":
      return firstPitch ? `Tonight, first pitch ${firstPitch}` : "Tonight";
    case "WARMUP":
      return "Warmups";
    case "IN_PROGRESS":
      return inn ? `Live, ${inn} inning` : "Live";
    case "MID_INNING":
      return inn ? `Between half-innings, ${inn} inning` : "Between innings";
    case "DELAYED":
    case "SUSPENDED": {
      const word = summary.status === "DELAYED" ? "Delayed" : "Suspended";
      // A delayed START has an inning in the feed but no baseball: only a game with logged pitches
      // was stopped "in" an inning.
      return pitchCount > 0 && inn ? `${word} in the ${inn}` : word;
    }
    case "POSTPONED":
      return "Postponed";
    case "COMPLETED":
      return "Final";
    default:
      return summary.detailedState || "Status unknown";
  }
}

function outsWords(n: number): string {
  return n === 0 ? "no outs" : n === 1 ? "one out" : `${n} outs`;
}

/** Stable identity of a batted ball across polls (the summary object can be re-created). */
function bipKeyOf(bb: RecentBattedBall | null): string | null {
  return bb ? `${bb.batterId}-${bb.atBatIndex}-${bb.pitchNumber}` : null;
}

function titleCaseFromSnake(value: string): string {
  const s = value.replace(/_/g, " ").trim();
  return s ? s[0]!.toUpperCase() + s.slice(1).toLowerCase() : s;
}

/** Contact descriptor when bb_type is absent: a coarse class from the launch angle. */
function bandFromLaunchAngle(deg: number): string {
  if (deg < 10) return "Ground ball";
  if (deg < 25) return "Line drive";
  if (deg < 50) return "Fly ball";
  return "Pop up";
}

function GuideLink({ anchor }: { anchor: string }) {
  // A client-side route change, so the live page's polling and cache survive the round trip.
  return (
    <Link to={`/models/guide#${anchor}`} className="ed-link ed-what">
      What is this?
    </Link>
  );
}

export function GamePage() {
  const { id } = useParams<{ id: string }>();
  const numericId = id ? Number(id) : null;
  const valid = numericId != null && Number.isFinite(numericId);

  const game = useGame(valid ? numericId : null);
  const pitches = useLivePitches(valid ? numericId : null, game.data?.status);
  const mostRecent = pitches.pitches[0];

  // The fan-facing error copy drops the raw message; keep it for whoever opens the console.
  useEffect(() => {
    if (game.error) console.error("game summary failed to load", game.error);
  }, [game.error]);
  useEffect(() => {
    if (pitches.error) console.error("pitch log failed to load", pitches.error);
  }, [pitches.error]);

  // Decision [194]: the worker-computed live state at 2s; fall back to derive-and-POST otherwise.
  const liveState = useLiveState(valid ? numericId : null, game.data?.status);
  const ls = liveState.data;
  const lsHasPredictions = ls?.prePrediction != null;
  // The derive-and-POST fallback may only fire once the live-state read has SETTLED: fetched at
  // least once (data or error), or not running at all (flag off / game not live). Before this
  // gate, a page open fired the POSTs while the first /live response was still in flight, logging
  // one duplicate served prediction to prediction_log per open.
  const liveStateSettled =
    liveState.isFetched || liveState.fetchStatus === "idle";

  // WHO IS BATTING: live-state matchup, else the summary's currentPlay, else the last pitch.
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

  // A6: the forward-looking next-pitch estimate (ADR-0014) - gates unchanged.
  const nextReq =
    mostRecent && game.data
      ? nextPitchRequest(mostRecent, game.data.gameDate, liveMatchup)
      : null;
  const nextPitchEnabled =
    isLive(game.data) &&
    nextReq != null &&
    liveStateSettled &&
    !lsHasPredictions;
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
    isLive(game.data) &&
    pitchTypeReq != null &&
    liveStateSettled &&
    !lsHasPredictions;
  const pitchType = usePitchTypePrediction(pitchTypeReq, {
    enabled: pitchTypeEnabled,
  });

  const nextPitchData = useMemo(() => {
    if (lsHasPredictions && ls?.prePrediction) {
      return {
        probabilities: ls.prePrediction.probabilities,
        winner: ls.prePrediction.winner,
        modelName: "pitch_outcome_pre",
        modelVersion: ls.modelVersions?.pre ?? "",
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
      };
    }
    return pitchType.data;
  }, [lsHasPredictions, ls, pitchType.data]);

  const nextPitchPanelEnabled =
    (isLive(game.data) && nextReq != null) || lsHasPredictions;
  const pitchTypePanelEnabled =
    (isLive(game.data) && pitchTypeReq != null) || lsHasPredictions;

  // The most recent COMPLETED ball in play, from the summary (authoritative for the ball the page
  // scores; the pitch list is not consulted for this).
  const inPlay = game.data?.mostRecentBattedBall ?? null;
  // ADR-0017 §3 entrance, gated by [112]: only a ball that arrives WHILE the page is open animates.
  // The baseline is the ball (or absence of one) in the FIRST loaded summary, keyed to the game.
  const bipKey = bipKeyOf(inPlay);
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
  const inPlayBatter = usePlayer(inPlay?.batterId ?? null);

  // All-parks prediction for the ball - NULL-KEYED unless the ball carries what the model needs.
  // POST /v1/predict/batted-ball/all-parks logs every request to prediction_log, so a throwaway
  // request would pollute the drift baselines; a missing input withholds the comparison.
  const allParksReq = useMemo<AllParksRequest | null>(() => {
    if (
      inPlay == null ||
      inPlay.sprayAngleDeg == null ||
      inPlay.baseState == null
    ) {
      return null;
    }
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
  const allParks = useAllParksPrediction(allParksReq, {
    enabled: allParksReq != null,
    context:
      game.data && inPlay
        ? { gameId: game.data.gameId, parkId: inPlay.parkId }
        : undefined,
  });

  // The pre-first-ball comparison: gated on there being NO ball in play.
  const teamContact = useTeamContact(game.data?.gameId ?? null, {
    enabled: game.data != null && inPlay == null,
  });

  // Pre-game probables: a cache-backed read of today's matchups (no logging).
  const matchups = useTodaysMatchups();
  const matchup = matchups.data?.find((m) => m.gameId === numericId) ?? null;

  // The account.
  const atBats = useMemo(
    () =>
      atBatsFrom(
        pitches.pitches,
        game.data?.status,
        liveMatchup?.atBatIndex ?? null,
      ),
    [pitches.pitches, game.data?.status, liveMatchup?.atBatIndex],
  );
  const lineScore = useMemo(
    () => (game.data ? lineScoreFrom(pitches.pitches, game.data) : null),
    [pitches.pitches, game.data],
  );
  // ~20 unique batters per game, each the same cached key usePlayer uses (SPEC-game §13 Q6).
  const batterIds = useMemo(
    () => [...new Set(atBats.map((ab) => ab.batterId))],
    [atBats],
  );
  const batterQueries = useQueries({
    queries: batterIds.map((pid) => ({
      queryKey: ["players", "byId", pid],
      queryFn: () => getPlayer(pid),
      staleTime: 60_000,
    })),
  });
  const nameById = new Map<number, string>();
  batterIds.forEach((pid, i) => {
    const name = batterQueries[i]?.data?.name;
    if (name) nameById.set(pid, name);
  });

  // The phone strip follows the masthead out of view.
  const mastRef = useRef<HTMLElement | null>(null);
  const scrolledPast = useScrolledPast(mastRef, 56);

  if (!valid) {
    return (
      <div className="ed-page">
        <div className="ed-col">
          <p className="ed-note" role="alert">
            Invalid game id.
          </p>
        </div>
      </div>
    );
  }

  const summary = game.data;
  const status = summary?.status;
  const final = isFinalStatus(status);
  const pitchCount = pitches.pitches.length;

  // While a JUST-CHANGED player's lookup is in flight, show the em-dash, never a raw id or the
  // previous name (identity flips at every at-bat, pitching change and half-inning).
  const playerName = (
    q: { data?: { name?: string }; isPending: boolean },
    pid: number | null,
  ): string => q.data?.name ?? (q.isPending || pid == null ? "—" : `#${pid}`);
  const pitcherName = playerName(currentPitcher, shownPitcherId);
  const batterName = playerName(currentBatter, shownBatterId);
  const named = (n: string) => n !== "—";
  // Handedness rides a resolved name only; "S" is resolved against the pitcher exactly as
  // nextPitchRequest resolves it, so the page and the model input never disagree.
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
  // Per-pitcher pitch count: the pitcher on the mound only (the log holds the whole game).
  const pitcherPitchCount =
    shownPitcherId != null
      ? pitches.pitches.filter((p) => p.pitcherId === shownPitcherId).length
      : 0;

  // Count / Outs: fresh /live first; else the log's post-pitch count while the matchup is on the
  // same at-bat; else unknown (an em-dash), never a finished at-bat's count beside the new batter.
  const countText = (() => {
    if (ls?.upcomingPitch && upcomingIsFresh)
      return `${ls.upcomingPitch.balls}-${ls.upcomingPitch.strikes}`;
    if (mostRecent && !rowIsPastTense) {
      const post = postPitchCount(mostRecent);
      if (post) return `${post.balls}-${post.strikes}`;
    }
    return "—";
  })();
  const outsNum: number | null = (() => {
    if (ls?.upcomingPitch && upcomingIsFresh) return ls.upcomingPitch.outs;
    if (mostRecent && !rowIsPastTense && postPitchCount(mostRecent))
      return mostRecent.outs;
    return null;
  })();
  // Bases only from a FRESH /live reading: a log row's runners are the state entering its at-bat,
  // stale after a steal, so the log is not used for this.
  const basesMask =
    ls?.upcomingPitch && upcomingIsFresh ? ls.upcomingPitch.baseState : null;

  const scored = hasScore(summary, pitchCount);
  const firstPitch = matchup?.gameTimeUtc
    ? firstPitchEt(matchup.gameTimeUtc)
    : null;
  const slug = slugFor(summary, pitchCount, firstPitch);
  const headline = !summary
    ? null
    : scored
      ? `${summary.awayTeam} ${summary.awayScore}, ${summary.homeTeam} ${summary.homeScore}`
      : `${summary.awayTeam} at ${summary.homeTeam}`;

  // Probables: the matchup's pitcher roles, when the board names them.
  const probables =
    matchup != null
      ? [
          matchup.awayRole === "pitcher"
            ? `${matchup.awayPlayerName} (${matchup.awayTeam})`
            : null,
          matchup.homeRole === "pitcher"
            ? `${matchup.homePlayerName} (${matchup.homeTeam})`
            : null,
        ].filter((x): x is string => x != null)
      : [];

  // The now-slot hosts the ball only if it arrived in-session AND nothing has been thrown since.
  const bipCursor = inPlay
    ? inPlay.atBatIndex * 100 + inPlay.pitchNumber
    : null;
  const bipIsLatest = bipCursor != null && newestLogCursor <= bipCursor;
  const bipInNowSlot = bipArrivedLive && bipIsLatest && isLive(summary);

  const lines =
    inPlay && allParks.data
      ? parkLines(allParks.data, summary?.homeTeam, inPlay.hitDistanceFt)
      : null;
  const inPlayName = playerName(inPlayBatter, inPlay?.batterId ?? null);
  const inPlayDescriptor = inPlay
    ? inPlay.bbType
      ? titleCaseFromSnake(inPlay.bbType)
      : bandFromLaunchAngle(inPlay.launchAngleDeg)
    : null;
  const inPlayPhysics = inPlay
    ? `${inPlayDescriptor}, ${inPlay.launchSpeedMph.toFixed(1)} mph, ${Math.round(
        inPlay.launchAngleDeg,
      )}°, ${Math.round(inPlay.hitDistanceFt)} ft`
    : null;
  const inPlaySentence = inPlay
    ? (() => {
        const phrase = eventPhrase(inPlay.event, inPlay.bbType);
        return phrase
          ? `${inPlayName} ${phrase}.`
          : `${inPlayName}: ${inPlay.event.toLowerCase()}.`;
      })()
    : "";
  // The comparison's state, said as what it is (never "no ball yet" when there is one).
  const comparisonStatus: string | null = !inPlay
    ? null
    : allParksReq == null
      ? "A ball was put in play, but its landing coordinates were not tracked cleanly enough to score it across parks, so the comparison is withheld rather than estimated."
      : allParks.isError
        ? "Could not score this batted ball across parks."
        : !allParks.data
          ? "Scoring this batted ball across all 30 parks…"
          : null;
  const comparison =
    lines && allParks.data ? (
      <>
        <p className="ed-sub">
          A home run in {hrParkCount(lines)} of {lines.length} parks by the
          batted-ball estimate. Realized result here:{" "}
          {titleCaseFromSnake(inPlay?.event ?? "in play")}.
        </p>
        <ParkAgate
          lines={lines}
          carryIsModel={allParks.data.carryFtByPark != null}
        />
        <p className="ed-prov">
          {`${allParks.data.modelName} ${allParks.data.modelVersion}`.trim()}
        </p>
      </>
    ) : null;

  const latestBall: LatestBall | null = inPlay
    ? {
        atBatIndex: inPlay.atBatIndex,
        inNowSlot: bipInNowSlot,
        comparison,
        status: comparisonStatus,
        openByDefault: false,
        fromSummary: {
          sentence: inPlaySentence,
          physics: inPlayPhysics,
          time: (() => {
            const t = Date.parse(inPlay.ts);
            return Number.isNaN(t) ? null : `${ET_HM.format(new Date(t))} ET`;
          })(),
          inning: null,
        },
      }
    : null;

  const dek = (() => {
    if (!summary) return " ";
    if (bipInNowSlot) return inPlaySentence;
    switch (summary.status) {
      case "SCHEDULED":
      case "WARMUP":
        return probables.length === 2
          ? `${probables[0]} against ${probables[1]}. The models wake at first pitch.`
          : "The models wake at first pitch.";
      case "IN_PROGRESS": {
        if (!named(pitcherName) || !named(batterName)) return "Play is on.";
        const bits: string[] = [];
        if (countText !== "—") bits.push(countText);
        if (outsNum != null) bits.push(`with ${outsWords(outsNum)}`);
        if (basesMask != null && basesMask !== 0)
          bits.push(`and ${basesPhrase(basesMask)}`);
        return bits.length > 0
          ? `${pitcherName} pitching to ${batterName}, ${bits.join(" ")}.`
          : `${pitcherName} pitching to ${batterName}.`;
      }
      case "MID_INNING":
        return "The estimates return with the next at-bat.";
      case "DELAYED":
      case "SUSPENDED":
        return `${summary.detailedState || "Play is stopped"}. Play is stopped, so no estimates are made.`;
      case "POSTPONED":
        return summary.detailedState
          ? `${summary.detailedState}.`
          : "This game will not be played today.";
      case "COMPLETED":
        return "How the post-pitch model read this game is scored on the record page.";
      default:
        return summary.detailedState || " ";
    }
  })();

  const whoLine = (
    <>
      Pitching <strong>{pitcherName}</strong>
      {shownPitchHand} &middot; At bat <strong>{batterName}</strong>
      {shownBatSide}
    </>
  );

  // The situation box (top block) by state.
  let situation;
  if (
    summary &&
    (summary.status === "SCHEDULED" || summary.status === "WARMUP")
  ) {
    situation = (
      <aside className="ed-sit" aria-label="Probable starters">
        <p className="ed-label">Probable starters</p>
        <p className="ed-sit__who">
          {probables.length > 0 ? probables.join(" and ") : "Not posted yet."}
        </p>
        {firstPitch ? (
          <p className="ed-prov">First pitch {firstPitch}</p>
        ) : null}
      </aside>
    );
  } else if (summary && summary.status === "POSTPONED") {
    situation = (
      <aside className="ed-sit" aria-label="Game state">
        <p className="ed-label">Postponed</p>
        <p className="ed-sit__who">{summary.detailedState || "Postponed"}</p>
      </aside>
    );
  } else if (final) {
    situation = (
      <aside className="ed-sit" aria-label="Game state">
        <p className="ed-label">Final</p>
        <dl className="ed-sit__grid">
          <dt>Innings</dt>
          <dd>{summary?.inning ?? "—"}</dd>
          <dt>Pitches</dt>
          <dd>{pitchCount}</dd>
        </dl>
        <p className="ed-sit__who">Final. The page stops polling.</p>
      </aside>
    );
  } else {
    situation = (
      <aside className="ed-sit" aria-label="The situation">
        <p className="ed-label">
          {STOPPED.has(status ?? "") ? "Play stopped" : "The situation"}
        </p>
        <dl className="ed-sit__grid">
          <dt>Count</dt>
          <dd>{countText}</dd>
          <dt>Outs</dt>
          <dd>{outsNum != null ? String(outsNum) : "—"}</dd>
          <dt>Bases</dt>
          <dd>{basesMask != null ? <BasesGlyph mask={basesMask} /> : "—"}</dd>
          <dt>Pitches</dt>
          <dd>{pitcherPitchCount}</dd>
        </dl>
        <p className="ed-sit__who">
          {STOPPED.has(status ?? "") && summary?.detailedState ? (
            <>
              MLB reports: <strong>{summary.detailedState}</strong>.{" "}
            </>
          ) : null}
          {whoLine}
        </p>
      </aside>
    );
  }

  const scorecard: Scorecard = {
    away: summary?.awayTeam ?? "—",
    home: summary?.homeTeam ?? "—",
    awayScore: scored && summary ? summary.awayScore : null,
    homeScore: scored && summary ? summary.homeScore : null,
    inningLabel: !summary
      ? "—"
      : final
        ? `Final, ${summary.inning}`
        : summary.status === "SCHEDULED" || summary.status === "WARMUP"
          ? (firstPitch ?? "Not started")
          : summary.status === "POSTPONED"
            ? "Postponed"
            : summary.status === "MID_INNING"
              ? `Between halves, ${ordinal(summary.inning)}`
              : STOPPED.has(summary.status)
                ? slug
                : summary.inning > 0
                  ? `${ordinal(summary.inning)} inning`
                  : "—",
    situation:
      summary && PLAYING.has(summary.status)
        ? {
            count: countText,
            outs: outsNum != null ? String(outsNum) : "—",
            bases: basesMask,
          }
        : null,
    pitchCount:
      summary && !NOT_STARTED.has(summary.status) && pitchCount > 0
        ? {
            label: final ? "Game pitches" : "Pitches",
            value: final ? pitchCount : pitcherPitchCount,
          }
        : null,
    who:
      summary && PLAYING.has(summary.status) ? (
        <>
          Pitching <strong>{pitcherName}</strong>
          {shownPitchHand}
          <br />
          At bat <strong>{batterName}</strong>
          {shownBatSide}
        </>
      ) : summary && STOPPED.has(summary.status) ? (
        `MLB reports: ${summary.detailedState || summary.status.toLowerCase()}.`
      ) : final ? (
        "Final. The page stops polling."
      ) : probables.length > 0 ? (
        `${probables.join(" and ")}, probable.`
      ) : (
        "Waiting for play."
      ),
    asOf: isLive(summary) ? etClock(ls?.predictedAt) : null,
  };

  // Now-slot content.
  let nowSlot;
  if (bipInNowSlot && inPlay) {
    nowSlot = (
      <section
        key={bipKey ?? undefined}
        className="ed-now__slot ed-enter"
        aria-labelledby="next-pitch-label"
      >
        <div className="ed-sechead">
          <p className="ed-slug">Ball in play, just now</p>
          <GuideLink anchor="batted-ball" />
        </div>
        <h2 className="ed-h2" id="next-pitch-label">
          {inPlaySentence}
        </h2>
        <p className="ed-sub">{inPlayPhysics}</p>
        {comparison ?? <p className="ed-note">{comparisonStatus}</p>}
      </section>
    );
  } else if (final) {
    nowSlot = (
      <section className="ed-now__slot" aria-labelledby="next-pitch-label">
        <div className="ed-sechead">
          <p className="ed-slug">The record</p>
          <GuideLink anchor="next-pitch" />
        </div>
        <h2 className="ed-h2" id="next-pitch-label">
          How the models read this game
        </h2>
        <p className="ed-sub">
          The estimates stop at the final out; the scoring starts.
        </p>
        <div className="ed-gated">
          <p className="ed-note">
            The post-pitch model&rsquo;s logged reads of this game&rsquo;s
            pitches are scored against what happened on the record page.{" "}
            <Link className="ed-link" to="/accuracy">
              The Live Retrospective
            </Link>
          </p>
        </div>
      </section>
    );
  } else {
    const sub =
      nextPitchPanelEnabled && named(pitcherName) && named(batterName)
        ? `${pitcherName} to ${batterName}${countText !== "—" ? `, ${countText}` : ""}${
            outsNum != null ? `, ${outsWords(outsNum)}` : ""
          }`
        : summary?.status === "SCHEDULED" || summary?.status === "WARMUP"
          ? "Waiting for first pitch"
          : STOPPED.has(status ?? "")
            ? "Play is stopped"
            : "Waiting for the next pitch";
    nowSlot = (
      <section className="ed-now__slot" aria-labelledby="next-pitch-label">
        <div className="ed-sechead">
          <p className="ed-slug">At the plate</p>
          <GuideLink anchor="next-pitch" />
        </div>
        <h2 className="ed-h2" id="next-pitch-label">
          The next pitch, by outcome
        </h2>
        <p className="ed-sub">{sub}</p>
        <OutcomeAgate
          prediction={nextPitchData}
          isLoading={lsHasPredictions ? false : nextPitch.isLoading}
          error={lsHasPredictions ? null : nextPitch.error}
          enabled={nextPitchPanelEnabled}
          computed={
            lsHasPredictions && ls?.predictedAt
              ? `worker-computed ${etClock(ls.predictedAt) ?? ""}`.trim()
              : undefined
          }
        />
      </section>
    );
  }

  const noBallText = game.isError
    ? null
    : game.isPending
      ? "Loading this game’s batted balls…"
      : final
        ? "No ball was put in play in this game."
        : "No ball has been put in play in this game yet.";

  // One polite announcement per at-bat or state change, never per pitch.
  const newestDone = atBats.find((ab) => ab.ending !== "in_progress");
  const announcement = [
    slug,
    newestDone
      ? atBatSentence(
          newestDone,
          nameById.get(newestDone.batterId) ?? "The batter",
        )
      : null,
  ]
    .filter(Boolean)
    .join(". ");

  const nameOf = (pid: number) => nameById.get(pid) ?? "—";

  return (
    <div className="ed-page">
      <div className="ed-col" id="game-top">
        <p className="ed-dateline">
          <span>{ET_DATE.format(new Date())}</span>
          <span className="ed-dateline__issued">
            {isLive(summary)
              ? `Live, updated ${etClock(ls?.predictedAt) ?? "every few seconds"}`
              : final
                ? "Final edition"
                : "Game day"}
          </span>
          <span>
            {summary ? `${summary.awayTeam} at ${summary.homeTeam}` : " "}
          </span>
        </p>
        <p className="ed-crumb">
          <Link to="/">Tonight</Link> / <Link to="/games">Games</Link> /{" "}
          {summary ? `${summary.awayTeam} at ${summary.homeTeam}` : "This game"}
        </p>

        {game.isError ? (
          <p role="alert" className="ed-note">
            Could not load this game right now. Retrying automatically.
          </p>
        ) : null}

        <header className="ed-gmast" ref={mastRef}>
          <div>
            <p className="ed-slug">{slug}</p>
            {headline ? (
              <h1 className="ed-h1">{headline}</h1>
            ) : (
              <h1 className="ed-h1">
                <span
                  className="ed-shell"
                  style={{ display: "block", height: "4.5rem", width: "60%" }}
                  aria-hidden="true"
                />
                <VisuallyHidden>This game</VisuallyHidden>
              </h1>
            )}
            <p className="ed-dek">{dek}</p>
          </div>
          {situation}
        </header>

        <div className="ed-now" id="now-slot">
          {nowSlot}
          <section className="ed-now__side" aria-labelledby="pitch-type-label">
            <div className="ed-sechead">
              <p className="ed-slug">By type</p>
              <GuideLink anchor="pitch-type" />
            </div>
            <h2
              className="ed-h2"
              id="pitch-type-label"
              style={{ fontSize: "1.5rem" }}
            >
              The next pitch, by type
            </h2>
            <p className="ed-sub">
              {named(pitcherName)
                ? `${pitcherName}’s calibrated prior for this count`
                : "The pitcher’s calibrated prior for this count"}
            </p>
            {final ? (
              <PitchTypeSectionClosed />
            ) : bipInNowSlot ? (
              <div className="ed-gated">
                <p className="ed-note">
                  Resets for the next batter. The prior describes one specific
                  upcoming pitch.
                </p>
              </div>
            ) : (
              <PitchTypeSection
                prior={pitchTypeData}
                isLoading={lsHasPredictions ? false : pitchType.isLoading}
                error={lsHasPredictions ? null : pitchType.error}
                enabled={pitchTypePanelEnabled}
              />
            )}
          </section>
        </div>

        <section
          className="ed-acct"
          id="game-account"
          aria-labelledby="game-pitch-log-label"
        >
          <ScorecardRail card={scorecard} />
          <div className="ed-acct__col">
            <h2 className="ed-h2" id="game-pitch-log-label">
              {pitchCount === 0 && !inPlay
                ? "Pitch by pitch, from first pitch"
                : "Every at-bat, newest first"}
            </h2>
            {lineScore && summary ? (
              <div style={{ marginTop: "1rem" }}>
                <LineScore
                  data={lineScore}
                  awayTeam={summary.awayTeam}
                  homeTeam={summary.homeTeam}
                />
              </div>
            ) : null}

            {inPlay == null && !game.isError ? (
              <div style={{ marginTop: "1.25rem" }}>
                {noBallText ? <p className="ed-note">{noBallText}</p> : null}
                {game.isPending ? null : (
                  <>
                    <h3 className="ed-h3" style={{ fontSize: "1.125rem" }}>
                      Contact at this park, season to date
                    </h3>
                    <TeamContactPanel
                      data={teamContact.data}
                      isLoading={teamContact.isLoading}
                      error={teamContact.error}
                    />
                  </>
                )}
              </div>
            ) : null}

            {pitches.isError ? (
              <p role="alert" className="ed-note">
                Could not load pitches right now. Retrying automatically.
              </p>
            ) : pitchCount === 0 && latestBall == null ? (
              <p className="ed-note" style={{ marginTop: "1.25rem" }}>
                {pitches.isPending
                  ? "Loading the pitch log…"
                  : summary?.status === "POSTPONED"
                    ? "No pitches were thrown."
                    : "Waiting for the first pitch. The account starts there."}
              </p>
            ) : (
              <AccountStream
                atBats={atBats}
                nameOf={nameOf}
                latestBall={latestBall}
              />
            )}
            <p className="ed-sn ed-sn-inline" style={{ marginTop: "1rem" }}>
              {MODEL_GAVE_NOTE}
            </p>
            <p className="ed-sn ed-sn-inline">{INFERRED_NOTE}</p>
          </div>
          <aside className="ed-acct__notes" aria-label="Notes">
            <p className="ed-sn">
              <span className="ed-label">Model gave it</span>
              {MODEL_GAVE_NOTE}{" "}
              <Link className="ed-link" to="/accuracy">
                How we score ourselves
              </Link>
            </p>
            <p className="ed-sn">
              <span className="ed-label">Ball in play</span>
              Only the latest ball is scored across parks; earlier balls keep
              their tracked physics. <GuideLink anchor="batted-ball" />
            </p>
            <p className="ed-sn">
              <span className="ed-label">Inferred results</span>
              {INFERRED_NOTE}
            </p>
          </aside>
        </section>

        <VisuallyHidden aria-live="polite">{announcement}</VisuallyHidden>

        <footer className="ed-footer">
          <span>The Bullpen. Self-hosted, honestly scored.</span>
          <span>
            build {BUILD_SHA}, {BUILD_DATE}
          </span>
        </footer>
      </div>
      <PhoneScoreStrip card={scorecard} shown={scrolledPast} />
    </div>
  );
}

function PitchTypeSectionClosed() {
  return (
    <div className="ed-gated">
      <p className="ed-note">No next pitch. Game over.</p>
    </div>
  );
}

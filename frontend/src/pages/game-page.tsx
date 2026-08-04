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
import { useParams } from "react-router";

import {
  matchupIsAheadOf,
  nextPitchRequest,
  useGame,
  useLivePitches,
  usePitchPrediction,
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
import { NextPitchPanel } from "../components/games/next-pitch-panel";
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
  // WHO IS BATTING: the live currentPlay matchup when the feed has one, the last thrown pitch
  // otherwise. The fallback is why this can never render worse than before - it IS the old
  // behaviour - but when the matchup is present the page flips to the new batter within one poll
  // of him stepping in, instead of a plate appearance later when his first pitch lands.
  const liveMatchup = game.data?.currentMatchup ?? null;
  // The matchup has moved PAST the newest stored pitch: that pitch is now past tense, so anything
  // derived from it describes a moment that is over. Before this feature the whole page described
  // one moment - stale, but internally consistent; naming the live batter alongside a finished
  // at-bat's count would trade that consistency for a confidently wrong composite ("leadoff
  // hitter, 1-2 count, 2 outs"), which is checkable against the broadcast and worse than stale.
  const rowIsPastTense = matchupIsAheadOf(liveMatchup, mostRecent);
  const shownPitcherId =
    liveMatchup?.pitcherId ?? mostRecent?.pitcherId ?? null;
  const shownBatterId = liveMatchup?.batterId ?? mostRecent?.batterId ?? null;
  // Hooks run before the early return; null id disables them.
  const currentPitcher = usePlayer(shownPitcherId);
  const currentBatter = usePlayer(shownBatterId);

  // A6: the forward-looking next-pitch estimate (ADR-0014). nextPitchRequest returns null unless
  // the at-bat is settled (mid-at-bat, full V028 context), and the query additionally gates on the
  // game being live - both required, because every fired request logs to prediction_log.
  // NOTE the matchup is applied CONDITIONALLY here, unlike the display above: nextPitchRequest
  // derives the count from the row, so it uses the matchup only when the two describe the same
  // at-bat, and withholds the request entirely once the matchup has moved past it.
  const nextReq =
    mostRecent && game.data
      ? nextPitchRequest(mostRecent, game.data.gameDate, liveMatchup)
      : null;
  const nextPitchEnabled = isLive(game.data) && nextReq != null;
  const nextPitch = usePitchPrediction(nextReq, { enabled: nextPitchEnabled });

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
    if (
      inPlay?.launchSpeedMph == null ||
      inPlay.launchAngleDeg == null ||
      // Spray is REQUIRED and cannot be invented. The server declines it where the geometry
      // degenerates (a ball tracked at or behind the plate, or an angle outside the foul lines),
      // and the honest response to a declined value is to not ask the model - not to send 0.
      // Measured cost: ~2% of balls at 150+ ft, so this almost never fires on a ball anyone would
      // want compared across parks.
      inPlay.sprayAngleDeg == null ||
      inPlay.baseState == null
    ) {
      return CANONICAL_BBE_INPUT;
    }
    // Batter side from the ROW, resolved for switch hitters exactly as nextPitchRequest resolves
    // it. Previously hardcoded "R": harmless only while the card almost never rendered live, and a
    // live-scored left-hander would otherwise be modelled as a right-hander on the page whose
    // entire purpose is showing the real batted ball.
    const throws = inPlay.pitcherThrows;
    let stand = inPlay.batterStand;
    if (stand === "S") stand = throws === "R" ? "L" : "R";
    if (stand !== "R" && stand !== "L") return CANONICAL_BBE_INPUT;
    return {
      launchSpeedMph: inPlay.launchSpeedMph,
      launchAngleDeg: inPlay.launchAngleDeg,
      sprayAngleDeg: inPlay.sprayAngleDeg,
      hitDistanceFt:
        inPlay.hitDistanceFt ??
        estimateLandingDistanceFt(inPlay.launchSpeedMph, inPlay.launchAngleDeg),
      stand,
      baseState: inPlay.baseState,
      outs: inPlay.outs,
    };
  }, [inPlay]);
  // Gated on a COMPLETE request, not merely on a batted ball existing: an incomplete one falls
  // back to CANONICAL_BBE_INPUT, and firing on that would log a canonical-fixture prediction to
  // prediction_log under this game's traffic - polluting the drift baseline with a request the
  // page never displays.
  const allParksComplete = allParksReq !== CANONICAL_BBE_INPUT;
  const allParks = useAllParksPrediction(allParksReq, {
    enabled: inPlay != null && allParksComplete,
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
          {todayIssueDate()} · live ingest
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
          Pitching: <strong>{pitcherName}</strong>
          {shownPitchHand} &middot; At bat: <strong>{batterName}</strong>
          {shownBatSide}
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
          {/* Count and Outs are derived from the newest STORED pitch, so once the live matchup
              has moved past that pitch's at-bat they describe a moment that is over. Naming the
              live batter beside a finished at-bat's count would read as one confident composite
              ("leadoff hitter, 1-2 count, 2 outs") that is checkable against the broadcast and
              wrong - worse than the stale-but-consistent page this replaced. Em-dash is this
              page's existing way of saying "not known right now". */}
          <BigStat
            label="Count"
            value={
              mostRecent && !rowIsPastTense
                ? `${mostRecent.balls}-${mostRecent.strikes}`
                : "—"
            }
          />
          <BigStat
            label="Outs"
            value={
              mostRecent && !rowIsPastTense ? String(mostRecent.outs) : "—"
            }
          />
          <BigStat label="Last Pitch" value={lastPitchRead(mostRecent)} />
          <BigStat
            label="Pitch Count"
            value={String(pitcherPitchCount)}
            tone="gold"
          />
        </div>
      </BroadcastPanel>

      <section aria-labelledby="next-pitch-label">
        <div style={{ marginBottom: 12 }}>
          <LowerThird
            id="next-pitch-label"
            meta={nextPitchEnabled ? "LIVE ESTIMATE" : "GATED"}
          >
            Next-Pitch Model
          </LowerThird>
        </div>
        <NextPitchPanel
          prediction={nextPitch.data}
          isLoading={nextPitch.isLoading}
          error={nextPitch.error}
          enabled={nextPitchEnabled}
        />
      </section>

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
              No ball has been put in play in this game yet. This is a static
              example of the per-park HR model - not this game&rsquo;s batted
              ball.
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

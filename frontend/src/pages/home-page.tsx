/**
 * / - the FRONT PAGE ([195] editorial identity; approved SPEC-home, 2026-10-09).
 *
 * Reading order: dateline -> lede (kicker, matchup/arsenal headline, dek) -> the next pitch, by
 * type -> three evidence figures -> lower band (slate / fleet / desk) -> colophon. On a phone
 * during a live game the pitch-type table moves above the figures (CSS grid areas keyed on
 * `data-live`), because the live prior is the news.
 *
 * Data posture (all TanStack Query, polling only, no new endpoints):
 *  - matchups (`/v1/matchups/today`) pick the featured game; games (`/v1/games/today`) carry status;
 *    `mergeSlate` orders the band. Empty or unreachable -> the committed showcase slate, LABELLED.
 *  - the live prior is decision [194]'s worker-computed `pitchTypePrediction` from
 *    GET /v1/games/{id}/live. `useLiveState` only enables while the game is in progress, and
 *    TanStack pauses interval polling in a hidden tab, so the read is gated to "live game AND tab
 *    visible" without any new caller of the logging POST endpoint.
 *  - before first pitch the table shows the pitcher's CAREER usage (`/arsenal`), captioned as such.
 *  - evidence figures are committed offline gate numbers (data/home-evidence.ts, SHELF-marked and
 *    test-pinned to their JSON); the champion count is live from the registry.
 *
 * [183]: the headline names people and a mix, never a likely pitch or a probability; the table
 * styles every row identically.
 */

import { VisuallyHidden } from "@mantine/core";
import { useEffect, useState } from "react";
import { Link } from "react-router";

import { useTodaysGames, useLiveState } from "../api/games";
import type { GameSummary } from "../api/games";
import type { MatchupSummary } from "../api/matchups";
import { useTodaysMatchups } from "../api/matchups";
import { firstPitchEt, splitSlate } from "../api/matchups-view";
import { useAllRegistryRows, useRouting } from "../api/ops";
import { toFleetRows } from "../api/ops-mappers";
import { usePitcherArsenal, usePlayer } from "../api/players";
import { useRollingAccuracy } from "../api/rolling-accuracy";
import { mergeSlate } from "../api/slate-view";
import { BUILD_DATE, BUILD_SHA } from "../build-info";
import {
  DeskColumn,
  FleetColumn,
  SlateColumn,
} from "../components/home/front-page-band";
import {
  FLEET_FALLBACK,
  type FleetEntry,
} from "../components/home/front-page-view";
import { NO_GAMES_HEADLINE, headlineFor } from "../components/home/headline";
import { PitchTypeAgate } from "../components/home/pitch-type-agate";
import {
  CHAMPION_COUNT_FALLBACK,
  POST_PITCH_BRIER,
  PITCH_TYPE_ECE,
} from "../data/home-evidence";
import { SHOWCASE_MATCHUPS } from "../data/matchups-showcase";
import { latestPostmortem } from "../data/postmortems";
import { SHOWCASE_GAMES } from "../data/slate-fixtures";
import { rankShares } from "../lib/pitch-type-prior";

// ── Formatters ────────────────────────────────────────────────────────────────

const ET_TIME = new Intl.DateTimeFormat("en-US", {
  hour: "numeric",
  minute: "2-digit",
  hour12: false,
  timeZone: "America/New_York",
});
const ET_DATE = new Intl.DateTimeFormat("en-US", {
  weekday: "long",
  month: "long",
  day: "numeric",
  year: "numeric",
  timeZone: "America/New_York",
});
const COUNT = new Intl.NumberFormat("en-US");

/** The earliest..latest first-pitch window for the slate, ET, or null when unknown. */
function firstPitchWindow(slate: readonly MatchupSummary[]): string | null {
  const times = slate
    .map((m) => (m.gameTimeUtc ? Date.parse(m.gameTimeUtc) : Number.NaN))
    .filter((n) => !Number.isNaN(n));
  if (times.length === 0) return null;
  const lo = firstPitchEt(new Date(Math.min(...times)).toISOString());
  const hi = firstPitchEt(new Date(Math.max(...times)).toISOString());
  return lo === hi ? lo : `${lo} to ${hi}`;
}

const LIVE_STATUSES = new Set(["IN_PROGRESS", "MID_INNING"]);
const FINAL_STATUSES = new Set(["COMPLETED", "GAME_OVER", "FINAL"]);

/**
 * The one entrance plays once per app load ("once per session"): a visitor bouncing between pages
 * should not watch the edition arrive every time. Module state, not React state - it outlives the
 * page's unmount, which is the point.
 */
let entranceShown = false;

type Posture =
  | "loading"
  | "live"
  | "showcase-unposted"
  | "showcase-offline"
  | "no-games";

function outs(n: number): string {
  return `${n} ${n === 1 ? "out" : "outs"}`;
}

// ── Page ──────────────────────────────────────────────────────────────────────

export default function HomePage() {
  const [animate] = useState(() => !entranceShown);
  useEffect(() => {
    entranceShown = true;
  }, []);

  const matchups = useTodaysMatchups();
  const todaysGames = useTodaysGames();
  const registry = useAllRegistryRows();
  const routing = useRouting();
  const rolling = useRollingAccuracy();

  // ── Posture: what kind of night is this? ───────────────────────────────────
  const liveMatchups = matchups.data ?? [];
  const liveGames = todaysGames.data ?? [];
  const posture: Posture =
    liveMatchups.length > 0
      ? "live"
      : matchups.isLoading
        ? "loading"
        : matchups.isError
          ? "showcase-offline"
          : todaysGames.isLoading
            ? "loading"
            : todaysGames.isError
              ? "showcase-offline"
              : liveGames.length === 0
                ? "no-games"
                : "showcase-unposted";
  const showcase =
    posture === "showcase-offline" || posture === "showcase-unposted";

  const slate: MatchupSummary[] =
    posture === "live" ? liveMatchups : showcase ? SHOWCASE_MATCHUPS : [];
  const games: GameSummary[] =
    posture === "live" ? liveGames : showcase ? SHOWCASE_GAMES : [];
  const { featured } = splitSlate(slate);
  const cards = mergeSlate(slate, games);

  // ── The featured game and its live state ([194]) ───────────────────────────
  const featuredGame =
    posture === "live" && featured
      ? liveGames.find((g) => g.gameId === featured.gameId)
      : undefined;
  const status = featuredGame?.status;
  const isLive = status !== undefined && LIVE_STATUSES.has(status);
  const isFinal = status !== undefined && FINAL_STATUSES.has(status);
  const liveState = useLiveState(
    isLive && featured ? featured.gameId : null,
    status,
  );
  const ls = liveState.data;

  // Whose arsenal: the pitcher on the mound when live, else the away starter of a pitching duel.
  const pitcherId: number | null =
    posture !== "live" || !featured
      ? null
      : isLive
        ? (ls?.matchup?.pitcherId ??
          featuredGame?.currentMatchup?.pitcherId ??
          null)
        : featured.away.role === "pitcher"
          ? featured.away.playerId
          : featured.home.role === "pitcher"
            ? featured.home.playerId
            : null;
  const knownName =
    featured && pitcherId === featured.away.playerId
      ? featured.away.name
      : featured && pitcherId === featured.home.playerId
        ? featured.home.name
        : null;
  // Only a reliever needs a lookup; a starter's name is already on the matchup.
  const reliever = usePlayer(knownName == null ? pitcherId : null);
  const pitcherName = knownName ?? reliever.data?.name ?? null;
  const arsenal = usePitcherArsenal(pitcherId);

  // ── Lede ───────────────────────────────────────────────────────────────────
  const now = new Date();
  const issuedAt = `${ET_TIME.format(now)} ET`;
  const pitchWindow = firstPitchWindow(slate);

  const kicker =
    posture === "loading"
      ? "Tonight"
      : posture === "no-games"
        ? "No games tonight"
        : !featured
          ? "Tonight"
          : showcase
            ? `Showcase: ${featured.away.team} at ${featured.home.team}`
            : isLive
              ? `Live: ${featured.away.team} at ${featured.home.team}${
                  featuredGame?.inning ? `, inning ${featuredGame.inning}` : ""
                }`
              : isFinal && featuredGame
                ? `Final: ${featured.away.team} ${featuredGame.awayScore}, ${featured.home.team} ${featuredGame.homeScore}`
                : `Tonight: ${featured.away.team} at ${featured.home.team}, ${featured.firstPitchEt}`;

  const headline =
    posture === "no-games" || !featured
      ? posture === "loading"
        ? null
        : NO_GAMES_HEADLINE
      : headlineFor({
          awayName: featured.away.name,
          homeName: featured.home.name,
          awayTeam: featured.away.team,
          homeTeam: featured.home.team,
          pitcherName,
          arsenal: arsenal.data ?? null,
        });

  const dek =
    posture === "no-games"
      ? "Self-hosted, drift-watched, and graded against what actually happened. The board fills when the next slate posts."
      : "Four calibrated models read every pitch of tonight’s game, self-hosted and scored against what actually happens.";

  // ── Aside: the next pitch, by type ─────────────────────────────────────────
  const priorRows = ls?.pitchTypePrediction
    ? rankShares(ls.pitchTypePrediction.probabilities)
    : [];
  const careerRows = (arsenal.data ?? []).length
    ? rankShares(
        Object.fromEntries(
          (arsenal.data ?? []).map((p) => [p.pitchType, p.usagePct]),
        ),
      ).slice(0, 7)
    : [];
  const showPrior = isLive && priorRows.length > 0;
  const recordMode = posture === "no-games" || isFinal;
  const up = ls?.upcomingPitch;

  const postRolling = rolling.data?.models.find(
    (m) => m.modelName === "pitch_outcome_post",
  );

  const asideTitle = recordMode
    ? "The record"
    : showPrior
      ? "The next pitch, by type"
      : pitcherName
        ? `${pitcherName}’s mix`
        : "The next pitch, by type";

  const asideSub = recordMode
    ? "How the models have scored lately."
    : showPrior && up
      ? `${pitcherName ?? "The pitcher"} pitching, ${up.balls}-${up.strikes}, ${outs(up.outs)}`
      : pitcherName
        ? "Career usage, all seasons."
        : showcase
          ? "The live prior appears once a real game is on."
          : "The live prior appears at first pitch.";

  let aside;
  if (recordMode) {
    aside = (
      <p className="ed-body">
        {postRolling?.status === "live" && postRolling.n != null ? (
          <>
            The post-pitch model has read {COUNT.format(postRolling.n)} live
            pitches in the last {rolling.data?.windowDays} days.{" "}
          </>
        ) : null}
        <Link className="ed-link" to="/accuracy">
          See how every model scored
        </Link>
      </p>
    );
  } else if (
    posture === "loading" ||
    (pitcherId != null && arsenal.isLoading && !showPrior)
  ) {
    aside = <PitchTypeAgate rows={[]} caption="" valueHeader="" busy />;
  } else if (showPrior && ls?.pitchTypePrediction) {
    aside = (
      <PitchTypeAgate
        rows={priorRows}
        caption="Calibrated prior, not a call."
        valueHeader="Prob."
        provenance={`pitch_type_pre ${ls.modelVersions?.pitchType ?? ""}`.trim()}
      />
    );
  } else if (careerRows.length > 0) {
    aside = (
      <PitchTypeAgate
        rows={careerRows}
        caption={
          isLive
            ? "Career usage, not a prediction. The live prior returns with the next pitch."
            : "Career usage, not a prediction. The live prior appears at first pitch."
        }
        valueHeader="Usage"
        provenance="Statcast, all seasons"
      />
    );
  } else {
    aside = (
      <PitchTypeAgate
        rows={[]}
        caption=""
        valueHeader=""
        note={
          arsenal.isError
            ? "Career usage is unavailable right now."
            : "Nothing to show until a pitcher is on the mound."
        }
      />
    );
  }

  // ── Figures ────────────────────────────────────────────────────────────────
  const fleetRows =
    registry.data && registry.data.length > 0
      ? toFleetRows(registry.data, routing.data ?? [], []).filter(
          (r) => r.state === "LIVE",
        )
      : null;
  const fleetLive = fleetRows !== null && fleetRows.length > 0;
  const fleetEntries: FleetEntry[] = fleetLive
    ? fleetRows.map((r) => ({ modelName: r.modelName, version: r.version }))
    : [...FLEET_FALLBACK];
  const championCount = fleetLive ? fleetRows.length : CHAMPION_COUNT_FALLBACK;

  const figures = [
    PITCH_TYPE_ECE,
    POST_PITCH_BRIER,
    {
      value: String(championCount),
      label: championCount === 1 ? "Model serving live" : "Models serving live",
      basis: fleetLive
        ? "Champions in the registry right now."
        : "Champion families. Registry unreachable.",
    },
  ];

  // The entrance class is APPENDED to the element's own class (a spread `className` would replace
  // it and silently strip the typography - the first render of this page did exactly that).
  const enter = (base: string, i: number) =>
    animate
      ? {
          className: `${base} ed-enter`,
          style: { "--ed-stagger": i } as React.CSSProperties,
        }
      : { className: base };

  // One polite announcement per at-bat (never per pitch) for screen-reader users.
  const announcement =
    showPrior && up
      ? `Pitch-type prior updated for at-bat ${up.atBatIndex + 1}.`
      : "";

  return (
    <div className="ed-page">
      <div className="ed-col">
        <p className="ed-dateline">
          <span>{ET_DATE.format(now)}</span>
          <span className="ed-dateline__issued">Edition issued {issuedAt}</span>
          <span>
            {posture === "no-games"
              ? "No games scheduled"
              : posture === "loading"
                ? "Slate loading"
                : `${slate.length} ${slate.length === 1 ? "game" : "games"}${
                    pitchWindow ? `, first pitch ${pitchWindow}` : ""
                  }`}
          </span>
        </p>

        <div className="ed-front" data-live={showPrior ? "true" : "false"}>
          <div className="ed-front__head">
            <p {...enter("ed-slug", 0)}>{kicker}</p>
            {headline ? (
              <h1 {...enter("ed-h1", 1)}>{headline}</h1>
            ) : (
              <h1 className="ed-h1">
                <span
                  className="ed-shell"
                  style={{ display: "block", height: "8rem" }}
                  aria-hidden="true"
                />
                <VisuallyHidden>Tonight&rsquo;s front page</VisuallyHidden>
              </h1>
            )}
            <p {...enter("ed-dek", 2)}>{dek}</p>
            {showcase ? (
              <p className="ed-note">
                {posture === "showcase-offline"
                  ? "Showcase data: the backend is unreachable, so this is a sample slate."
                  : "Showcase data: tonight’s slate has not posted yet, so this is a sample slate."}
              </p>
            ) : null}
          </div>

          <div className="ed-figs ed-front__figs">
            {figures.map((f, i) => (
              <p key={f.label} {...enter("ed-fig", 3 + i)}>
                <span className="ed-fig__value">{f.value}</span>
                <span className="ed-fig__label">{f.label}</span>
                <span className="ed-fig__basis">{f.basis}</span>
              </p>
            ))}
          </div>

          <p className="ed-front__links">
            {featured && !showcase ? (
              <Link className="ed-link" to={`/games/${featured.gameId}`}>
                Read the game
              </Link>
            ) : (
              <Link className="ed-link" to="/games">
                See the games
              </Link>
            )}
            <Link className="ed-link" to="/accuracy">
              How we score ourselves
            </Link>
          </p>

          <aside className="ed-front__aside" aria-labelledby="ed-aside-title">
            <h2 className="ed-h2" id="ed-aside-title">
              {asideTitle}
            </h2>
            <p className="ed-sub">{asideSub}</p>
            {aside}
            <VisuallyHidden aria-live="polite">{announcement}</VisuallyHidden>
          </aside>
        </div>

        <div className="ed-band">
          <SlateColumn
            cards={cards}
            source={
              posture === "loading"
                ? "loading"
                : posture === "no-games"
                  ? "live"
                  : posture
            }
          />
          <FleetColumn
            entries={fleetEntries}
            live={fleetLive}
            loading={registry.isLoading}
          />
          <DeskColumn entry={latestPostmortem()} />
        </div>

        <footer className="ed-footer">
          <span>The Bullpen. Self-hosted, honestly scored.</span>
          <span>
            build {BUILD_SHA}, {BUILD_DATE}
          </span>
        </footer>
      </div>
    </div>
  );
}

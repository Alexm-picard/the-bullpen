/**
 * /games - the day's scoreboard ([195] editorial identity; approved SPEC-games-404, 2026-10-11).
 *
 * Reading order: dateline -> head (slug, an H1 composed from the counts, dek) beside "Reading the
 * board" -> status facets -> the showcase note when it applies -> three status columns of line
 * scores (in progress / still to come / final).
 *
 * Data posture (all TanStack Query, polling only, no new endpoints): the day's matchups
 * (`/v1/matchups/today`) and live game rows (`/v1/games/today`) merge through the shared
 * `mergeSlate`, then `orderForBoard` puts "Still to come" in first-pitch order for THIS page only
 * (home keeps mergeSlate's order). With no rows, the page degrades to the committed showcase slate,
 * LABELLED with the reason home uses: backend unreachable (a query errored) or nothing posted yet
 * (both answered empty). Showcase rows are plain text, never links (SPEC §6.3).
 */
import { VisuallyHidden } from "@mantine/core";
import { useState } from "react";

import { useTodaysGames } from "../api/games";
import { useTodaysMatchups } from "../api/matchups";
import { mergeSlate, type SlateStatus } from "../api/slate-view";
import { BUILD_DATE, BUILD_SHA } from "../build-info";
import { Scoreboard, ScoreboardShell } from "../components/games/scoreboard";
import {
  boardCounts,
  boardHeadline,
  firstPitchWindow,
  orderForBoard,
} from "../components/games/slate-lines";
import { RATING_CLAIM } from "../data/games-claims";
import { SHOWCASE_MATCHUPS } from "../data/matchups-showcase";
import { SHOWCASE_GAMES } from "../data/slate-fixtures";

const ET_DATE = new Intl.DateTimeFormat("en-US", {
  weekday: "long",
  month: "long",
  day: "numeric",
  year: "numeric",
  timeZone: "America/New_York",
});

type Filter = "all" | SlateStatus;
type Posture = "loading" | "live" | "showcase-offline" | "showcase-unposted";

const FILTERS: readonly { key: Filter; label: string }[] = [
  { key: "all", label: "All" },
  { key: "live", label: "Live" },
  { key: "scheduled", label: "Scheduled" },
  { key: "final", label: "Final" },
];

/** Why the page is showing the sample slate, in the same two postures home distinguishes. */
function showcaseReason(posture: Posture): string | null {
  if (posture === "showcase-offline") {
    return "The backend is unreachable, so this is a sample slate.";
  }
  if (posture === "showcase-unposted") {
    return "No games have posted for today yet, so this is a sample slate.";
  }
  return null;
}

export default function GamesPage() {
  const games = useTodaysGames();
  const matchups = useTodaysMatchups();
  const [filter, setFilter] = useState<Filter>("all");

  const liveMatchups = matchups.data ?? [];
  const liveGames = games.data ?? [];
  const posture: Posture =
    liveMatchups.length > 0 || liveGames.length > 0
      ? "live"
      : matchups.isLoading || games.isLoading
        ? "loading"
        : matchups.isError || games.isError
          ? "showcase-offline"
          : "showcase-unposted";
  const reason = showcaseReason(posture);
  const showcase = reason !== null;

  const cards = orderForBoard(
    posture === "live"
      ? mergeSlate(liveMatchups, liveGames)
      : showcase
        ? mergeSlate(SHOWCASE_MATCHUPS, SHOWCASE_GAMES)
        : [],
  );
  const counts = boardCounts(cards);
  const total = cards.length;
  const pitchWindow = firstPitchWindow(cards);

  const datelineRight =
    posture === "loading"
      ? "Slate loading"
      : showcase
        ? `Showcase slate, ${total} sample ${total === 1 ? "game" : "games"}`
        : `${total} ${total === 1 ? "game" : "games"}${
            pitchWindow ? `, first pitch ${pitchWindow}` : ""
          }`;

  const postponedNote =
    counts.postponed === 0
      ? ""
      : counts.postponed === 1
        ? ", one game postponed"
        : `, ${counts.postponed} games postponed`;
  const dek = showcase
    ? "A sample board, so the page still reads when there is nothing live to show. Every game opens to its pitch-by-pitch account."
    : `The day’s board${postponedNote}. Every game opens to its pitch-by-pitch account and the models’ read of the next pitch.`;

  return (
    <div className="ed-page">
      <div className="ed-col">
        <p className="ed-dateline">
          <span>{ET_DATE.format(new Date())}</span>
          <span className="ed-dateline__issued">The slate</span>
          <span>{datelineRight}</span>
        </p>

        <div className="pl-split pl-head gm-head">
          <div>
            <p className="ed-slug">
              {showcase ? "Games, showcase slate" : "Games"}
            </p>
            {posture === "loading" ? (
              <h1 className="ed-h1">
                <span
                  className="ed-shell"
                  style={{ display: "block", height: "8rem" }}
                  aria-hidden="true"
                />
                <VisuallyHidden>Today&rsquo;s games</VisuallyHidden>
              </h1>
            ) : (
              <h1 className="ed-h1">{boardHeadline(counts)}</h1>
            )}
            <p className="ed-dek">{dek}</p>
          </div>
          <aside className="gm-holds gm-aside" aria-labelledby="gm-holds-h">
            <p className="ed-label" id="gm-holds-h">
              Reading the board
            </p>
            <dl>
              <dt>Columns</dt>
              <dd>
                Games under way, games still to come, and finals. A rain delay
                stays with the games under way and says so.
              </dd>
              <dt>Rating</dt>
              <dd>{RATING_CLAIM}</dd>
            </dl>
          </aside>
        </div>
        <details className="gm-key-sum">
          <summary>Reading the board</summary>
          <p>
            A rain delay stays with the games under way and says so.{" "}
            {RATING_CLAIM}
          </p>
        </details>

        <div className="gm-filter">
          <span className="gm-filter__label" aria-hidden="true">
            Show
          </span>
          <div
            className="pl-facets"
            role="group"
            aria-label="Filter games by status"
          >
            {FILTERS.map((f) => (
              <button
                key={f.key}
                type="button"
                className="pl-facet"
                aria-pressed={filter === f.key}
                onClick={() => setFilter(f.key)}
              >
                {f.label}
                {posture === "loading" ? null : (
                  <small>{f.key === "all" ? total : counts[f.key]}</small>
                )}
              </button>
            ))}
          </div>
          {posture === "live" ? (
            <span className="gm-filter__asof">scores refresh every minute</span>
          ) : null}
        </div>

        {showcase ? (
          <p className="gm-showcase" role="note">
            <span className="pl-tag">Showcase</span>
            {reason} The teams, players and scores are illustrative, not
            today&rsquo;s games.
          </p>
        ) : null}

        {posture === "loading" ? (
          <ScoreboardShell />
        ) : (
          <Scoreboard
            cards={cards}
            counts={counts}
            filter={filter}
            showcase={showcase}
            onReset={() => setFilter("all")}
          />
        )}

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

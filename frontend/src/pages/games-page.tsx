/**
 * /games - the day's slate as a card board on the BROADCAST identity (decision
 * [160]). Merges the live game rows (GET /v1/games/today: scores + status) with
 * the day's matchups (GET /v1/matchups/today: lean + battle score + featured
 * people + first pitch) by gameId, via the pure {@link mergeSlate}.
 *
 * Status-bucket filter tabs (All / Live / Scheduled / Completed). When BOTH
 * sources are unreachable / empty the page degrades to the showcase slate
 * (SHOWCASE_MATCHUPS + SHOWCASE_GAMES merged through the same path) with an
 * honest "showcase data" caption - never the old raw "Failed to fetch".
 *
 * This page imports ONLY the broadcast token namespace ([160] migration rule).
 */
import { useMemo, useState } from "react";

import { useTodaysGames } from "../api/games";
import { useTodaysMatchups } from "../api/matchups";
import {
  mergeSlate,
  slateCounts,
  type SlateCard,
  type SlateStatus,
} from "../api/slate-view";
import { LowerThird } from "../components/broadcast/lower-third";
import { SlateBoard } from "../components/games/slate-board";
import { SHOWCASE_MATCHUPS } from "../data/matchups-showcase";
import { SHOWCASE_GAMES } from "../data/slate-fixtures";
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

type Filter = "all" | SlateStatus;

const FILTERS: { key: Filter; label: string }[] = [
  { key: "all", label: "All" },
  { key: "live", label: "Live" },
  { key: "scheduled", label: "Scheduled" },
  { key: "final", label: "Completed" },
];

function countTag(counts: Record<SlateStatus, number>): string {
  const parts: string[] = [];
  if (counts.live) parts.push(`${counts.live} live`);
  if (counts.scheduled) parts.push(`${counts.scheduled} scheduled`);
  if (counts.final) parts.push(`${counts.final} final`);
  return parts.join(" · ").toUpperCase();
}

function filterButtonStyle(active: boolean): React.CSSProperties {
  return {
    fontFamily: typography.fonts.mono,
    fontWeight: typography.weights.medium,
    fontSize: 12,
    letterSpacing: "0.04em",
    padding: "6px 15px",
    border: "none",
    cursor: "pointer",
    backgroundColor: active ? colors.chrome : "transparent",
    color: active ? colors.textOnChrome : colors.textMuted,
  };
}

export default function GamesPage() {
  const games = useTodaysGames();
  const matchups = useTodaysMatchups();
  const [filter, setFilter] = useState<Filter>("all");

  const settled = !games.isLoading && !matchups.isLoading;
  const hasLive =
    (matchups.data?.length ?? 0) > 0 || (games.data?.length ?? 0) > 0;
  const usingShowcase = !hasLive && settled;
  const loading = !hasLive && !settled;

  // Live wins whenever either source has data; otherwise (both settled empty or
  // errored) fall back to the merged showcase slate. Defaulting lives inside the
  // memo so the deps stay the stable query-data references.
  const cards = useMemo<SlateCard[]>(() => {
    const liveMatchups = matchups.data ?? [];
    const liveGames = games.data ?? [];
    if (liveMatchups.length > 0 || liveGames.length > 0) {
      return mergeSlate(liveMatchups, liveGames);
    }
    if (settled) {
      return mergeSlate(SHOWCASE_MATCHUPS, SHOWCASE_GAMES);
    }
    return [];
  }, [matchups.data, games.data, settled]);

  const counts = slateCounts(cards);
  const filtered =
    filter === "all" ? cards : cards.filter((c) => c.status === filter);

  return (
    <PageChrome gap={22} bottomPad={48}>
      <header
        style={{
          display: "flex",
          alignItems: "flex-end",
          justifyContent: "space-between",
          gap: 16,
          flexWrap: "wrap",
        }}
      >
        <div>
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
            Today&rsquo;s Games
          </h1>
          <p
            style={{
              margin: "2px 0 0",
              fontFamily: typography.fonts.mono,
              fontSize: 12,
              fontFeatureSettings: '"tnum" 1',
              letterSpacing: "0.02em",
              color: colors.textMuted,
            }}
          >
            {todayIssueDate()} &middot; live ingest &middot; slate fills as
            games go live
          </p>
        </div>

        <div
          role="group"
          aria-label="Filter games by status"
          style={{
            display: "inline-flex",
            border: `1px solid ${colors.rule}`,
            backgroundColor: colors.panel,
          }}
        >
          {FILTERS.map((f, i) => (
            <button
              key={f.key}
              type="button"
              aria-pressed={filter === f.key}
              onClick={() => setFilter(f.key)}
              style={{
                ...filterButtonStyle(filter === f.key),
                borderLeft: i > 0 ? `1px solid ${colors.rule}` : "none",
              }}
            >
              {f.label}
            </button>
          ))}
        </div>
      </header>

      <section aria-labelledby="games-slate-label">
        <div style={{ marginBottom: 14 }}>
          <LowerThird
            id="games-slate-label"
            meta={cards.length > 0 ? countTag(counts) : undefined}
          >
            Slate
          </LowerThird>
        </div>

        {loading ? (
          <p
            style={{
              fontFamily: typography.fonts.body,
              color: colors.textMuted,
            }}
          >
            Loading today&rsquo;s slate&hellip;
          </p>
        ) : (
          <>
            <SlateBoard cards={filtered} />
            {usingShowcase && (
              <p
                style={{
                  margin: "12px 0 0",
                  fontFamily: typography.fonts.mono,
                  fontSize: 11,
                  letterSpacing: "0.04em",
                  color: colors.textMuted,
                }}
              >
                Showcase slate · backend unreachable or no games posted yet
              </p>
            )}
          </>
        )}
      </section>

      <BroadcastFooter>TODAY&rsquo;S GAMES</BroadcastFooter>
    </PageChrome>
  );
}

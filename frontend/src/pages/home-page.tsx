/**
 * /home - Tonight's Slate on the BROADCAST identity (redesign PR-4, decision
 * [160]; matchup wiring Phase 4b).
 *
 * Composition (light field under dark chrome):
 *   1. Masthead - condensed-italic h1 + mono byline (date, issue time, matchup
 *      count, live/showcase, first-pitch window derived from the slate)
 *   2. <BroadcastFleetStrip> - chrome strip of model chips; LIVE from the
 *      registry (useAllRegistryRows + useRouting), honest caption when offline
 *   3. <LiveTonightStrip> - the live /v1/games/today slate
 *   4. <TonightsMatchupsBoard> - per-game lean-aware matchups (the rest of the
 *      slate after the Featured pick)
 *   5. <FeaturedMatchupPanel> - the single best battle of the slate
 *   6. Chrome footer strip
 *
 * Matchup data posture: LIVE from GET /v1/matchups/today (the morning
 * pitcher-vs-pitcher pass + the ~20-min lineup re-classification). When the
 * endpoint is empty (the slate has not posted yet) or the backend is
 * unreachable, the board + Featured panel fall back to SHOWCASE_MATCHUPS with an
 * honest "showcase data" caption - the same posture as the fleet strip. This
 * page imports ONLY the broadcast namespace.
 */

import type { MatchupSummary } from "../api/matchups";
import { useTodaysMatchups } from "../api/matchups";
import { firstPitchEt, splitSlate } from "../api/matchups-view";
import { useTodaysGames } from "../api/games";
import { useAllRegistryRows, useRouting } from "../api/ops";
import { toFleetRows } from "../api/ops-mappers";
import { BroadcastFleetStrip } from "../components/home/broadcast-fleet-strip";
import { FeaturedMatchupPanel } from "../components/home/featured-matchup-panel";
import { LiveTonightStrip } from "../components/home/live-tonight-strip";
import { TonightsMatchupsBoard } from "../components/home/tonights-matchups-board";
import { LowerThird } from "../components/broadcast/lower-third";
import { ISSUE_META, MODEL_CHIPS } from "../data/home-fixtures";
import type { ModelChip, ModelChipState } from "../data/home-fixtures";
import { SHOWCASE_MATCHUPS } from "../data/matchups-showcase";
import { colors, layouts, typography } from "../design/broadcast";

// ── Formatters ────────────────────────────────────────────────────────────────

const ET_TIME = new Intl.DateTimeFormat("en-US", {
  hour: "numeric",
  minute: "2-digit",
  hour12: false,
  timeZone: "America/New_York",
});
const ET_DATE = new Intl.DateTimeFormat("en-US", {
  weekday: "short",
  month: "short",
  day: "numeric",
  year: "numeric",
  timeZone: "America/New_York",
});

// ── Helpers ───────────────────────────────────────────────────────────────────

/** The earliest..latest first-pitch window for the slate, ET, or "TBD". */
function firstPitchWindow(slate: MatchupSummary[]): string {
  const times = slate
    .map((m) => m.gameTimeUtc)
    .filter((t): t is string => !!t)
    .map((t) => Date.parse(t))
    .filter((n) => !Number.isNaN(n));
  if (times.length === 0) {
    return "TBD";
  }
  const lo = new Date(Math.min(...times)).toISOString();
  const hi = new Date(Math.max(...times)).toISOString();
  return lo === hi
    ? firstPitchEt(lo)
    : `${firstPitchEt(lo)} - ${firstPitchEt(hi)}`;
}

/**
 * Map live registry rows to the ModelChip shape the fleet strip needs.
 * CHAMPION stage -> LIVE badge; everything else -> SHADOW. Chips link to /ops.
 */
function registryToChips(
  versions: Parameters<typeof toFleetRows>[0],
  routing: Parameters<typeof toFleetRows>[1],
): ModelChip[] {
  const rows = toFleetRows(versions, routing, []);
  return rows.map((r) => {
    const state: ModelChipState = r.state === "LIVE" ? "LIVE" : "SHADOW";
    return {
      id: `${r.modelName}-${r.version}`,
      label: r.modelName,
      detail: r.version,
      state,
      href: "/ops",
    };
  });
}

// ── Styles ────────────────────────────────────────────────────────────────────

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

const captionStyle: React.CSSProperties = {
  margin: "4px 0 0",
  fontFamily: typography.fonts.mono,
  fontSize: 11,
  fontStyle: "italic",
  letterSpacing: "0.02em",
  color: colors.textMuted,
};

// ── Page component ────────────────────────────────────────────────────────────

export default function HomePage() {
  // Fleet strip: LIVE when the registry returns at least one row.
  const registry = useAllRegistryRows();
  const routing = useRouting();
  // Tonight's Games strip: LIVE from the same /v1/games/today slate /games uses.
  const todaysGames = useTodaysGames();
  // Matchups: LIVE from /v1/matchups/today, showcase fallback when empty/offline.
  const matchups = useTodaysMatchups();

  const liveChips =
    registry.data && registry.data.length > 0
      ? registryToChips(registry.data, routing.data ?? [])
      : null;
  const chips = liveChips ?? MODEL_CHIPS;
  const ribbonIsLive = liveChips !== null;

  const matchupsAreLive = !!matchups.data && matchups.data.length > 0;
  const slate = matchupsAreLive ? matchups.data : SHOWCASE_MATCHUPS;
  const { featured, board } = splitSlate(slate);
  const matchupSource = matchupsAreLive ? "live slate" : "showcase";

  const now = new Date();
  const issuedAt = `${ET_TIME.format(now)} ET`;
  const issueDate = ET_DATE.format(now).replace(",", " ·");

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
            Tonight&rsquo;s Slate
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
            {issueDate} · issued {issuedAt} · {slate.length} matchups ·{" "}
            {matchupSource} · first pitch {firstPitchWindow(slate)}
          </p>
        </header>

        {/* Fleet strip: live from the registry when the backend is reachable */}
        <div>
          <BroadcastFleetStrip chips={chips} />
          {!ribbonIsLive && (
            <p style={captionStyle}>
              Model fleet · showcase data (backend unreachable)
            </p>
          )}
        </div>

        {/* Tonight's Games: LIVE slate from /v1/games/today */}
        <section aria-labelledby="tonight-games-label">
          <div style={{ marginBottom: 12 }}>
            <LowerThird
              id="tonight-games-label"
              meta={
                todaysGames.data && todaysGames.data.length > 0
                  ? `LIVE · ${todaysGames.data.length} GAMES`
                  : "LIVE"
              }
            >
              Tonight&rsquo;s Games
            </LowerThird>
          </div>
          {todaysGames.isError ? (
            <p style={captionStyle}>
              Could not load tonight&rsquo;s games
              {todaysGames.error instanceof Error
                ? `: ${todaysGames.error.message}`
                : ""}
              .
            </p>
          ) : todaysGames.isLoading ? (
            <p style={captionStyle}>Loading tonight&rsquo;s games&hellip;</p>
          ) : (
            <LiveTonightStrip games={todaysGames.data ?? []} />
          )}
        </section>

        {/* Tonight's Matchups: per-game lean-aware board (the rest of the slate) */}
        <section aria-labelledby="slate-section-label">
          <div style={{ marginBottom: 12 }}>
            <LowerThird
              id="slate-section-label"
              meta={
                matchupsAreLive ? `LIVE · ${board.length} GAMES` : "SHOWCASE"
              }
            >
              Tonight&rsquo;s Matchups
            </LowerThird>
          </div>
          {board.length > 0 ? (
            <TonightsMatchupsBoard
              rows={board}
              caption={
                matchupsAreLive
                  ? "Tonight's slate · lean-aware matchups from the morning + lineup passes"
                  : "Tonight's slate · showcase data (the morning slate has not posted yet)"
              }
            />
          ) : (
            <p style={captionStyle}>No further matchups on the slate yet.</p>
          )}
        </section>

        {/* Featured matchup: the single best battle of the slate */}
        {featured && (
          <div>
            <FeaturedMatchupPanel matchup={featured} />
            {!matchupsAreLive && (
              <p style={captionStyle}>
                Featured matchup · showcase data (the morning slate has not
                posted yet)
              </p>
            )}
          </div>
        )}

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
          <span>THE BULLPEN · TONIGHT&rsquo;S SLATE</span>
          <span>
            build {ISSUE_META.buildSha} · {ISSUE_META.buildDate}
          </span>
        </footer>
      </div>
    </div>
  );
}

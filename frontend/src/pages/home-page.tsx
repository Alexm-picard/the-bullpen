/**
 * /home - Tonight's Slate on the BROADCAST identity (redesign PR-4, decision
 * [160]).
 *
 * Composition (light field under dark chrome):
 *   1. Masthead - condensed-italic h1 + mono byline (date, issue time,
 *      matchup count, LHP/RHP split, first-pitch window)
 *   2. <BroadcastFleetStrip> - chrome strip of model chips; LIVE from the
 *      registry (useAllRegistryRows + useRouting) with the honest showcase
 *      caption when the backend is unreachable
 *   3. <TonightsMatchupsBoard> - the 6-col slate, EDGE tint from the
 *      broadcast condFormat ramp, team-color bars in the matchup cell
 *   4. <FeaturedMatchupPanel> - LowerThird + cut panel + gold CTA
 *   5. Chrome footer strip
 *
 * Data posture unchanged (W7): the fleet strip is live; the slate + featured
 * matchup remain showcase fixtures (no backend endpoint carries starters /
 * edge / top-reads yet - the hook point stays GET /v1/games/today once it
 * does), captioned honestly. This page imports ONLY the broadcast namespace.
 */

import { useTodaysGames } from "../api/games";
import { useAllRegistryRows, useRouting } from "../api/ops";
import { toFleetRows } from "../api/ops-mappers";
import { BroadcastFleetStrip } from "../components/home/broadcast-fleet-strip";
import { FeaturedMatchupPanel } from "../components/home/featured-matchup-panel";
import { LiveTonightStrip } from "../components/home/live-tonight-strip";
import { TonightsMatchupsBoard } from "../components/home/tonights-matchups-board";
import { LowerThird } from "../components/broadcast/lower-third";
import {
  FEATURED_CONTEXT,
  FEATURED_KEY_READS,
  ISSUE_META,
  MODEL_CHIPS,
  TONIGHT_MATCHUPS,
} from "../data/home-fixtures";
import type { ModelChip, ModelChipState } from "../data/home-fixtures";
import { PLAYERS } from "../data/matchup-fixtures";
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

// ── Helpers (carried over unchanged) ──────────────────────────────────────────

function computeHandCounts() {
  let l = 0;
  let r = 0;
  for (const m of TONIGHT_MATCHUPS) {
    if (m.awayStarter.hand === "L") l++;
    else r++;
    if (m.homeStarter.hand === "L") l++;
    else r++;
  }
  return { l, r };
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

  const liveChips =
    registry.data && registry.data.length > 0
      ? registryToChips(registry.data, routing.data ?? [])
      : null;
  const chips = liveChips ?? MODEL_CHIPS;
  const ribbonIsLive = liveChips !== null;

  const { l: lhpCount, r: rhpCount } = computeHandCounts();
  const featuredBatter = PLAYERS.judge_aaron;
  const featuredPitcher = PLAYERS.skubal_tarik;
  if (!featuredBatter || !featuredPitcher) {
    throw new Error(
      "home fixture inconsistency: missing featured Judge/Skubal",
    );
  }

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
            {issueDate} · issued {issuedAt} · {TONIGHT_MATCHUPS.length} games ·{" "}
            {lhpCount} LHP / {rhpCount} RHP · first pitch{" "}
            {ISSUE_META.firstPitchWindow}
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

        {/* Tonight's Games: LIVE slate from /v1/games/today (FE-H1, the deferred
            half). The showcase matchups board below carries edge model reads,
            which have no live endpoint yet. */}
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

        {/* Tonight's slate: showcase fixture - no live endpoint for
            starters, edge scores, or top-reads yet */}
        <section aria-labelledby="slate-section-label">
          <div style={{ marginBottom: 12 }}>
            <LowerThird
              id="slate-section-label"
              meta={`${TONIGHT_MATCHUPS.length} GAMES`}
            >
              Tonight&rsquo;s Matchups
            </LowerThird>
          </div>
          <TonightsMatchupsBoard
            matchups={TONIGHT_MATCHUPS}
            caption="Tonight's slate · edge model reads · starters · showcase data (no live slate endpoint yet)"
          />
        </section>

        {/* Featured matchup: showcase fixture for the same reason */}
        <div>
          <FeaturedMatchupPanel
            batter={featuredBatter}
            pitcher={featuredPitcher}
            context={FEATURED_CONTEXT}
            keyReads={FEATURED_KEY_READS}
            ctaHref={`/players/${featuredBatter.id}`}
            ctaLabel="Pull the full report →"
          />
          <p style={captionStyle}>
            Featured matchup · showcase data (no live endpoint for starters /
            edge / top-reads)
          </p>
        </div>

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

/**
 * /home -- Tonight's Slate cover-sheet (Stage 3a, decision [133] identity).
 *
 * Composition order (top -> bottom, inside <ReportSheet> shell):
 *   1. <CoverSheetHeader />      -- masthead (eyebrow + 2-line nameplate + byline)
 *   2. <ModelFleetRibbon />      -- clickable navy strip, model chips
 *   3. <TonightsMatchupsTable /> -- 6-col slate with cellColor EDGE tint
 *   4. <FeaturedMatchupCard />   -- full-width card, 2 key-reads, scarlet CTA
 *   5. <CoverSheetFooter />      -- navy strip footer (bookends the ribbon)
 *
 * Data sourcing (W7):
 *   - Model fleet ribbon: LIVE via useAllRegistryRows + useRouting (same
 *     pattern as ops-page). Falls back to MODEL_CHIPS fixture when the
 *     backend is unreachable or the registry is empty, captioned honestly.
 *   - Tonight's slate (matchups table + featured card): showcase fixture.
 *     No backend endpoint delivers per-game starters, edge scores, or
 *     top-reads today. The natural hook point is GET /v1/games/today once
 *     that endpoint carries the required fields. Captioned clearly.
 *
 * Constraints honored:
 *   - One <Title order={1}> only (the masthead h1).
 *   - No hex codes -- every color via tokens.
 *   - TanStack Query for live ribbon data; no useEffect for server state.
 */

import { Stack, Text } from "@mantine/core";

import { useAllRegistryRows, useRouting } from "../api/ops";
import { toFleetRows } from "../api/ops-mappers";
import { FeaturedMatchupCard } from "../components/home/featured-matchup-card";
import { TonightsMatchupsTable } from "../components/home/tonights-matchups-table";
import { CoverSheetFooter } from "../components/scouting/cover-sheet-footer";
import { CoverSheetHeader } from "../components/scouting/cover-sheet-header";
import { ModelFleetRibbon } from "../components/scouting/model-fleet-ribbon";
import { ReportSheet } from "../components/shared/report-sheet";
import { SectionLabel } from "../components/shared/section-label";
import {
  FEATURED_CONTEXT,
  FEATURED_KEY_READS,
  ISSUE_META,
  MODEL_CHIPS,
  TONIGHT_MATCHUPS,
} from "../data/home-fixtures";
import type { ModelChip, ModelChipState } from "../data/home-fixtures";
import { PLAYERS } from "../data/matchup-fixtures";
import { colors } from "../design/tokens";

import "./home/home.css";

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
 * Map live registry rows to the ModelChip shape the fleet ribbon needs.
 * CHAMPION stage -> LIVE badge; everything else -> SHADOW. Chips link to /ops.
 */
function registryToChips(
  versions: Parameters<typeof toFleetRows>[0],
  routing: Parameters<typeof toFleetRows>[1],
): ModelChip[] {
  // Pass an empty latency array -- we only need state/name/version for chips.
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

const showcaseSuffix = (live: boolean) =>
  live ? "" : " · showcase data (backend unreachable)";

// ── Page component ────────────────────────────────────────────────────────────

export default function HomePage() {
  // Fleet ribbon: LIVE when the registry returns at least one row.
  const registry = useAllRegistryRows();
  const routing = useRouting();

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
    <ReportSheet>
      <Stack gap={28}>
        <CoverSheetHeader
          issueDate={issueDate}
          matchupCount={TONIGHT_MATCHUPS.length}
          lhpCount={lhpCount}
          rhpCount={rhpCount}
          issuedAt={issuedAt}
          firstPitchWindow={ISSUE_META.firstPitchWindow}
        />

        {/* Fleet ribbon: live from registry when backend reachable */}
        <div>
          <ModelFleetRibbon chips={chips} />
          {!ribbonIsLive && (
            <Text
              size="xs"
              style={{ color: colors.textMuted, fontStyle: "italic" }}
              mt={4}
            >
              Model fleet · showcase data (backend unreachable)
            </Text>
          )}
        </div>

        {/* Tonight's slate: showcase fixture -- no live endpoint for
            starters, edge scores, or top-reads yet */}
        <section aria-labelledby="slate-section-label">
          <div id="slate-section-label">
            <SectionLabel>
              Tonight&rsquo;s Matchups &middot; {TONIGHT_MATCHUPS.length} Games
            </SectionLabel>
          </div>
          <TonightsMatchupsTable
            matchups={TONIGHT_MATCHUPS}
            caption={`Tonight's slate · edge model reads · starters${showcaseSuffix(false)}`}
          />
        </section>

        {/* Featured matchup: showcase fixture for the same reason */}
        <div>
          <FeaturedMatchupCard
            batter={featuredBatter}
            pitcher={featuredPitcher}
            context={FEATURED_CONTEXT}
            keyReads={FEATURED_KEY_READS}
            ctaHref={`/players/${featuredBatter.id}`}
            ctaLabel="Pull the full report &#x2192;"
          />
          <Text
            size="xs"
            style={{ color: colors.textMuted, fontStyle: "italic" }}
            mt={4}
          >
            Featured matchup · showcase data (no live endpoint for
            starters / edge / top-reads)
          </Text>
        </div>

        <CoverSheetFooter
          buildSha={ISSUE_META.buildSha}
          buildDate={ISSUE_META.buildDate}
        />
      </Stack>
    </ReportSheet>
  );
}

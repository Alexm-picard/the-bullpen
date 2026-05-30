/**
 * /home — Tonight's Slate cover-sheet (Stage 3a, decision [133] identity).
 *
 * Replaces the editorial-data "tech-product" home (2026-05-25, commit 7ef8958).
 * The new home is the front of the printed advance-scouting packet: masthead
 * with two-line nameplate, broadcast model-fleet ribbon, the night's slate as
 * a conditionally-formatted table, then a featured matchup pulled from that
 * table. Visual vocabulary is lifted from the Matchup Report at /players/:id.
 *
 * Composition order (top → bottom, inside <ReportSheet> shell):
 *   1. <CoverSheetHeader />      — masthead (eyebrow + 2-line nameplate + byline)
 *   2. <ModelFleetRibbon />      — clickable navy strip, 4 model chips
 *   3. <TonightsMatchupsTable /> — 6-col slate with cellColor EDGE tint
 *   4. <FeaturedMatchupCard />   — full-width card, 2 key-reads, scarlet CTA
 *   5. <CoverSheetFooter />      — navy strip footer (bookends the ribbon)
 *
 * Fixture-driven (`home-fixtures.ts`); no API calls. Reuses Judge + Skubal
 * from matchup-fixtures.ts for the featured card.
 *
 * Constraints honored:
 *   - One <Title order={1}> only (the masthead h1).
 *   - No hex codes — every color via tokens or CSS-var utilities.
 *   - No live data fetches; the page is a design-system showcase in v1.
 *   - Reuses CornerStripes + SectionLabel from shared/ (extracted from
 *     players-page.tsx so both routes use the same primitives).
 */

import { Stack } from "@mantine/core";

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
import { PLAYERS } from "../data/matchup-fixtures";

import "./home/home.css";

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

export default function HomePage() {
  const { l: lhpCount, r: rhpCount } = computeHandCounts();
  const featuredBatter = PLAYERS.judge_aaron;
  const featuredPitcher = PLAYERS.skubal_tarik;
  if (!featuredBatter || !featuredPitcher) {
    throw new Error(
      "home fixture inconsistency: missing featured Judge/Skubal",
    );
  }

  return (
    <ReportSheet>
      <Stack gap={28}>
        <CoverSheetHeader
          issueDate={ISSUE_META.issueDate}
          matchupCount={TONIGHT_MATCHUPS.length}
          lhpCount={lhpCount}
          rhpCount={rhpCount}
          issuedAt={ISSUE_META.issuedAt}
          firstPitchWindow={ISSUE_META.firstPitchWindow}
        />

        <ModelFleetRibbon chips={MODEL_CHIPS} />

        <section aria-labelledby="slate-section-label">
          <div id="slate-section-label">
            <SectionLabel>
              Tonight&rsquo;s Matchups &middot; {TONIGHT_MATCHUPS.length} Games
            </SectionLabel>
          </div>
          <TonightsMatchupsTable matchups={TONIGHT_MATCHUPS} />
        </section>

        <FeaturedMatchupCard
          batter={featuredBatter}
          pitcher={featuredPitcher}
          context={FEATURED_CONTEXT}
          keyReads={FEATURED_KEY_READS}
          ctaHref={`/players/${featuredBatter.id}`}
          ctaLabel="Pull the full report →"
        />

        <CoverSheetFooter
          buildSha={ISSUE_META.buildSha}
          buildDate={ISSUE_META.buildDate}
        />
      </Stack>
    </ReportSheet>
  );
}

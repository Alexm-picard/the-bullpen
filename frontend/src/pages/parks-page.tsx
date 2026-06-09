/**
 * /parks -- Park Factors appendix (Stage 3c, decision [133] identity).
 *
 * Composition order (top -> bottom, inside <ReportSheet> shell):
 *   1. <ParksHeader />         -- masthead (eyebrow + 2-line nameplate + byline)
 *   2. <ParksMethodology />    -- single mono strip
 *   3. <OverviewParksTable />  -- 30-row StatTable
 *   4. <ParkSwitcherStrip />   -- horizontal scroll of 30 mini-thumbs
 *   5. <ParkSpotlight />       -- two-column field + heatmap | factors + notes
 *   6. <CoverSheetFooter />    -- navy footer strip (reused from /home)
 *
 * Data sourcing (W7):
 *   All three data surfaces are showcase fixtures from parks-fixtures.ts.
 *   No backend endpoint exists today for park factors. The natural slot
 *   is GET /v1/parks/factors -- listed as a cross-team ask. The existing
 *   POST /v1/predict/batted-ball/all-parks delivers P(HR) per park for
 *   specific launch params; it is a prediction endpoint, not a factor
 *   table, and does not map to these rows without a backend aggregation
 *   layer. Every section is captioned honestly per the C4 rule.
 *
 * Constraints honored:
 *   - One <Title order={1}> only (the masthead h1 inside ParksHeader).
 *   - No hex codes -- every color via tokens.
 */

import { Stack, Text } from "@mantine/core";
import { useState } from "react";

import { OverviewParksTable } from "../components/parks/overview-parks-table";
import { ParkSpotlight } from "../components/parks/park-spotlight";
import { ParkSwitcherStrip } from "../components/parks/park-switcher-strip";
import { ParksHeader } from "../components/parks/parks-header";
import { ParksMethodology } from "../components/parks/parks-methodology";
import { CoverSheetFooter } from "../components/scouting/cover-sheet-footer";
import { ReportSheet } from "../components/shared/report-sheet";
import { SectionLabel } from "../components/shared/section-label";
import {
  COORS_SPOTLIGHT,
  PARK_ROWS,
  PARK_THUMBNAILS,
  PARKS_META,
} from "../data/parks-fixtures";
import { colors } from "../design/tokens";

import "./parks/parks.css";

// Shared caption for all fixture-backed sections on this page.
const SHOWCASE_NOTE =
  "Showcase data -- GET /v1/parks/factors not yet implemented.";

export default function ParksPage() {
  // The switcher tracks the active park id so the spotlight ring stays
  // in sync visually. The actual spotlight payload is locked to Coors in
  // v1 -- switching parks scrolls the table; it doesn't yet swap spotlight
  // content. That's the natural next step when /v1/parks/factors lands.
  const [activeParkId, setActiveParkId] = useState<string>(COORS_SPOTLIGHT.id);

  const handleSelect = (parkId: string) => {
    setActiveParkId(parkId);
    if (typeof document !== "undefined") {
      const el = document.getElementById(`park-row-${parkId}`);
      if (el) {
        el.scrollIntoView({ behavior: "smooth", block: "center" });
      }
    }
  };

  return (
    <ReportSheet>
      <Stack gap={28}>
        <ParksHeader
          edition={PARKS_META.edition}
          sampleN={PARKS_META.sampleN}
          dataWindow={PARKS_META.dataWindow}
          modelTag={PARKS_META.modelTag}
        />

        <ParksMethodology line={PARKS_META.methodologyLine} />

        <section aria-labelledby="parks-overview-label">
          <div id="parks-overview-label">
            <SectionLabel>Overview &middot; 30 Parks</SectionLabel>
          </div>
          <Text
            size="sm"
            style={{ color: colors.textMuted }}
            mb={8}
          >
            {SHOWCASE_NOTE}
          </Text>
          <OverviewParksTable rows={PARK_ROWS} />
        </section>

        <section aria-labelledby="parks-switcher-label">
          <div id="parks-switcher-label">
            <SectionLabel>Park Switcher &middot; Mini Heatmaps</SectionLabel>
          </div>
          <Text
            size="sm"
            style={{ color: colors.textMuted }}
            mb={8}
          >
            {SHOWCASE_NOTE}
          </Text>
          <ParkSwitcherStrip
            thumbnails={PARK_THUMBNAILS}
            rows={PARK_ROWS}
            activeParkId={activeParkId}
            onSelect={handleSelect}
          />
        </section>

        <section aria-labelledby="parks-spotlight-label">
          <div id="parks-spotlight-label">
            <SectionLabel>Spotlight &middot; Coors Field</SectionLabel>
          </div>
          <Text
            size="sm"
            style={{ color: colors.textMuted }}
            mb={8}
          >
            {SHOWCASE_NOTE}
          </Text>
          <ParkSpotlight spotlight={COORS_SPOTLIGHT} />
        </section>

        <CoverSheetFooter
          buildSha={PARKS_META.buildSha}
          buildDate={PARKS_META.buildDate}
        />
      </Stack>
    </ReportSheet>
  );
}

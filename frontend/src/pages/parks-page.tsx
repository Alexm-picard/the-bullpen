/**
 * /parks — Park Factors appendix (Stage 3c, decision [133] identity).
 *
 * Replaces the editorial-data "Park Explorer" (slider-driven fly-ball
 * heatmap). The new /parks is the printed advance-scouting packet's
 * back-of-book reference appendix: a 30-row conditionally-formatted park-
 * factors hero table, a horizontal-scroll switcher of 30 mini heatmaps,
 * and a SPOTLIGHT block for one park (Coors Field in v1) with a generic
 * field SVG, a 12×12 landing-density heatmap, a 5-block factor strip, and
 * two key-reads paragraphs.
 *
 * Composition order (top → bottom, inside <ReportSheet> shell):
 *   1. <ParksHeader />         — masthead (eyebrow + 2-line nameplate + byline)
 *   2. <ParksMethodology />    — single mono strip
 *   3. <OverviewParksTable />  — 30-row StatTable
 *   4. <ParkSwitcherStrip />   — horizontal scroll of 30 mini-thumbs
 *   5. <ParkSpotlight />       — two-column field + heatmap | factors + notes
 *   6. <CoverSheetFooter />    — navy footer strip (reused from /home)
 *
 * Fixture-only — `parks-fixtures.ts` provides the 30 parks, 30 thumbs, and
 * the Coors spotlight payload. No API calls in v1; the eventual
 * GET /v1/parks/factors endpoint slots in here.
 *
 * Constraints honored:
 *   - One <Title order={1}> only (the masthead h1 inside ParksHeader).
 *   - No hex codes — every color via tokens or CSS-var utilities.
 *   - No live data fetches; the page is a design-system showcase in v1.
 *   - Reuses CornerStripes + SectionLabel + CoverSheetFooter + KeyNotes.
 *
 * The legacy `components/parks/*` files (park-detail-modal, park-tile,
 * park-list-row, sticky-control-rail, league-leader-strip,
 * launch-param-sliders, stadium-svg, park-thumbnail, park-thumbnail-polished,
 * park-detail-panel) are now orphaned dead code — out of scope for this
 * commit per the Stage 3c spec.
 */

import { Stack } from "@mantine/core";
import { useState } from "react";

import { OverviewParksTable } from "../components/parks/overview-parks-table";
import { ParkSpotlight } from "../components/parks/park-spotlight";
import { ParkSwitcherStrip } from "../components/parks/park-switcher-strip";
import { ParksHeader } from "../components/parks/parks-header";
import { ParksMethodology } from "../components/parks/parks-methodology";
import { CoverSheetFooter } from "../components/scouting/cover-sheet-footer";
import { CornerStripes } from "../components/shared/corner-stripes";
import { SectionLabel } from "../components/shared/section-label";
import {
  COORS_SPOTLIGHT,
  PARK_ROWS,
  PARK_THUMBNAILS,
  PARKS_META,
} from "../data/parks-fixtures";
import { colors, layouts } from "../design/tokens";

import "./parks/parks.css";

export default function ParksPage() {
  // The switcher tracks an active park id so the spotlight stays in sync
  // visually with the active outline ring. The actual spotlight payload
  // is fixture-locked to Coors in v1 — switching parks scrolls the table,
  // it doesn't yet swap the spotlight content. That's the natural next
  // step when /v1/parks/factors lands.
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
    <div
      style={{
        backgroundColor: colors.bgBase,
        minHeight: "calc(100vh - 56px)",
        paddingTop: 32,
        paddingBottom: 64,
      }}
    >
      <div
        style={{
          maxWidth: layouts.reportSheetMaxWidth,
          margin: "0 auto",
          padding: "0 16px",
        }}
      >
        <div
          className="parks__shell"
          style={{
            backgroundColor: colors.bgSheet,
            border: `1px solid ${colors.navy}`,
            borderRadius: 2,
            padding: 32,
          }}
        >
          <CornerStripes className="parks__corner" />
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
              <OverviewParksTable rows={PARK_ROWS} />
            </section>

            <section aria-labelledby="parks-switcher-label">
              <div id="parks-switcher-label">
                <SectionLabel>
                  Park Switcher &middot; Mini Heatmaps
                </SectionLabel>
              </div>
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
              <ParkSpotlight spotlight={COORS_SPOTLIGHT} />
            </section>

            <CoverSheetFooter
              buildSha={PARKS_META.buildSha}
              buildDate={PARKS_META.buildDate}
            />
          </Stack>
        </div>
      </div>
    </div>
  );
}

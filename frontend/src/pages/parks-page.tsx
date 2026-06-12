/**
 * /parks - the Park Factors appendix on the BROADCAST identity (redesign
 * PR-6, decision [160]).
 *
 * Composition unchanged: ParksHeader, ParksMethodology, the 30-park overview
 * table (StatTable through the broadcast palette), the mini-heatmap switcher
 * strip (gold active ring), and the Coors spotlight (field SVG + heatmap grid
 * + factor strip + KeyNotes through the broadcast palette). All sections stay
 * showcase fixtures with their honest captions - GET /v1/parks/factors is not
 * implemented yet (the all-parks predict endpoint is a prediction surface,
 * not a factor table). This page imports ONLY the broadcast namespace.
 */

import { useState } from "react";

import { LowerThird } from "../components/broadcast/lower-third";
import { OverviewParksTable } from "../components/parks/overview-parks-table";
import { ParkSpotlight } from "../components/parks/park-spotlight";
import { ParkSwitcherStrip } from "../components/parks/park-switcher-strip";
import { ParksHeader } from "../components/parks/parks-header";
import { ParksMethodology } from "../components/parks/parks-methodology";
import {
  COORS_SPOTLIGHT,
  PARK_ROWS,
  PARK_THUMBNAILS,
  PARKS_META,
} from "../data/parks-fixtures";
import { colors, layouts, typography } from "../design/broadcast";

import "./parks/parks.css";

// Shared caption for all fixture-backed sections on this page.
const SHOWCASE_NOTE =
  "Showcase data -- GET /v1/parks/factors not yet implemented.";

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
  gap: 28,
};

const noteStyle: React.CSSProperties = {
  margin: "0 0 8px",
  fontFamily: typography.fonts.body,
  fontSize: 13,
  color: colors.textMuted,
};

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
    <div style={fieldStyle}>
      <div style={columnStyle}>
        <ParksHeader
          edition={PARKS_META.edition}
          sampleN={PARKS_META.sampleN}
          dataWindow={PARKS_META.dataWindow}
          modelTag={PARKS_META.modelTag}
        />

        <ParksMethodology line={PARKS_META.methodologyLine} />

        <section aria-labelledby="parks-overview-label">
          <div style={{ marginBottom: 12 }}>
            <LowerThird id="parks-overview-label" meta="30 PARKS">
              Overview
            </LowerThird>
          </div>
          <p style={noteStyle}>{SHOWCASE_NOTE}</p>
          <OverviewParksTable rows={PARK_ROWS} />
        </section>

        <section aria-labelledby="parks-switcher-label">
          <div style={{ marginBottom: 12 }}>
            <LowerThird id="parks-switcher-label">
              Park Switcher · Mini Heatmaps
            </LowerThird>
          </div>
          <p style={noteStyle}>{SHOWCASE_NOTE}</p>
          <ParkSwitcherStrip
            thumbnails={PARK_THUMBNAILS}
            rows={PARK_ROWS}
            activeParkId={activeParkId}
            onSelect={handleSelect}
          />
        </section>

        <section aria-labelledby="parks-spotlight-label">
          <div style={{ marginBottom: 12 }}>
            <LowerThird id="parks-spotlight-label">
              Spotlight · Coors Field
            </LowerThird>
          </div>
          <p style={noteStyle}>{SHOWCASE_NOTE}</p>
          <ParkSpotlight spotlight={COORS_SPOTLIGHT} />
        </section>

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
          <span>THE BULLPEN · PARK FACTORS</span>
          <span>
            build {PARKS_META.buildSha} · {PARKS_META.buildDate}
          </span>
        </footer>
      </div>
    </div>
  );
}

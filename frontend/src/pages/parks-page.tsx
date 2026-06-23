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

import { NumberInput, SegmentedControl } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { useMemo, useState } from "react";
import { Link } from "react-router-dom";

import { useAllParksPrediction } from "../api/parks";
import type { AllParksRequest } from "../api/parks";
import { LowerThird } from "../components/broadcast/lower-third";
import { OverviewParksTable } from "../components/parks/overview-parks-table";
import { estimateLandingDistanceFt } from "../components/parks/estimate-landing";
import { ParkHrHeatmap } from "../components/parks/park-hr-heatmap";
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
import { BUILD_DATE, BUILD_SHA } from "../build-info";
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

const controlsRowStyle: React.CSSProperties = {
  display: "flex",
  gap: 16,
  alignItems: "flex-end",
  flexWrap: "wrap",
  marginBottom: 12,
};

const controlLabelStyle: React.CSSProperties = {
  display: "block",
  marginBottom: 4,
  fontFamily: typography.fonts.mono,
  fontSize: 11,
  letterSpacing: "0.04em",
  textTransform: "uppercase",
  color: colors.textMuted,
};

const errorStyle: React.CSSProperties = {
  fontFamily: typography.fonts.body,
  fontWeight: typography.weights.semibold,
  color: colors.goldInk,
};

// M1 / decision [163]: the live HR heatmap is served by the batted-ball MLP, which is an honestly
// re-scoped per-park CALIBRATED PHYSICS ESTIMATE (not a reality-validated predictor) - so the
// section carries that caveat rather than presenting raw P(HR) as fact.
const caveatStyle: React.CSSProperties = {
  margin: "0 0 12px",
  padding: "8px 12px",
  borderLeft: `3px solid ${colors.gold}`,
  backgroundColor: colors.fieldSubtle,
  fontFamily: typography.fonts.body,
  fontSize: 13,
  lineHeight: typography.lineHeights.body,
  color: colors.text,
};

export default function ParksPage() {
  // The switcher tracks the active park id so the spotlight ring stays
  // in sync visually. The actual spotlight payload is locked to Coors in
  // v1 -- switching parks scrolls the table; it doesn't yet swap spotlight
  // content. That's the natural next step when /v1/parks/factors lands.
  const [activeParkId, setActiveParkId] = useState<string>(COORS_SPOTLIGHT.id);

  // B1: live HR-probability-by-park from the batted-ball champion's all-parks
  // endpoint. The launch-condition inputs are debounced (300ms) so typing does
  // not spam the endpoint; the query keys on the debounced request.
  const [launchSpeedMph, setLaunchSpeedMph] = useState(110);
  const [launchAngleDeg, setLaunchAngleDeg] = useState(28);
  const [sprayAngleDeg, setSprayAngleDeg] = useState(0);
  const [stand, setStand] = useState<"L" | "R">("R");
  const req: AllParksRequest = useMemo(
    () => ({
      launchSpeedMph,
      launchAngleDeg,
      sprayAngleDeg,
      // Derived so distance stays physically consistent with velo + angle as they
      // change (the post-contact model takes hitDistance as a feature). baseState
      // empty + 0 outs are neutral game-context defaults for the park HR surface.
      hitDistanceFt: estimateLandingDistanceFt(launchSpeedMph, launchAngleDeg),
      stand,
      baseState: 0,
      outs: 0,
    }),
    [launchSpeedMph, launchAngleDeg, sprayAngleDeg, stand],
  );
  const [debouncedReq] = useDebouncedValue(req, 300);
  const allParks = useAllParksPrediction(debouncedReq);

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
        <p style={noteStyle}>{SHOWCASE_NOTE}</p>

        <section aria-labelledby="parks-hr-label">
          <div style={{ marginBottom: 12 }}>
            <LowerThird id="parks-hr-label" meta="LIVE · 30 PARKS">
              Home-Run Probability by Park
            </LowerThird>
          </div>
          <p style={caveatStyle}>
            <strong>
              Physics estimate, not a reality-validated predictor.
            </strong>{" "}
            These per-park probabilities come from a calibrated batted-ball
            model whose physics retrodiction correlates only about 0.30 with
            realized outcomes (its linear baseline still wins on aggregate
            Brier). Read them as relative comparisons between parks, not
            absolute HR rates - see{" "}
            <Link to="/about" style={{ color: colors.goldInk }}>
              About
            </Link>{" "}
            for the methodology and roadmap.
          </p>
          <div style={controlsRowStyle}>
            <NumberInput
              label="Exit velo (mph)"
              value={launchSpeedMph}
              onChange={(v) =>
                setLaunchSpeedMph(typeof v === "number" ? v : Number(v) || 110)
              }
              min={40}
              max={125}
              step={1}
              w={130}
            />
            <NumberInput
              label="Launch angle (deg)"
              value={launchAngleDeg}
              onChange={(v) =>
                setLaunchAngleDeg(typeof v === "number" ? v : Number(v) || 28)
              }
              min={-20}
              max={60}
              step={1}
              w={150}
            />
            <NumberInput
              label="Spray angle (deg)"
              description="- pull / + oppo"
              value={sprayAngleDeg}
              onChange={(v) =>
                setSprayAngleDeg(typeof v === "number" ? v : Number(v) || 0)
              }
              min={-45}
              max={45}
              step={1}
              w={150}
            />
            <div>
              <span style={controlLabelStyle}>Bat side</span>
              <SegmentedControl
                value={stand}
                onChange={(v) => setStand(v === "L" ? "L" : "R")}
                data={[
                  { label: "LHB", value: "L" },
                  { label: "RHB", value: "R" },
                ]}
              />
            </div>
          </div>
          {allParks.isError ? (
            <p style={errorStyle}>
              Could not load the all-parks prediction
              {allParks.error instanceof Error
                ? `: ${allParks.error.message}`
                : ""}
              .
            </p>
          ) : allParks.isLoading ? (
            <p style={noteStyle}>Computing P(HR) across the 30 parks&hellip;</p>
          ) : allParks.data ? (
            <>
              <p style={noteStyle}>
                Live: {allParks.data.modelName} {allParks.data.modelVersion} -
                estimated P(HR) for a {launchSpeedMph} mph / {launchAngleDeg}
                &deg; / {sprayAngleDeg}&deg; spray batted ball,{" "}
                {stand === "R" ? "RHB" : "LHB"}, at each park.
              </p>
              <ParkHrHeatmap
                probHrByPark={allParks.data.probHrByPark}
                parkRows={PARK_ROWS}
              />
            </>
          ) : null}
        </section>

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
            build {BUILD_SHA} · {BUILD_DATE}
          </span>
        </footer>
      </div>
    </div>
  );
}

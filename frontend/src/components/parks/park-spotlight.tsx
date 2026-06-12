/**
 * <ParkSpotlight> — the SPOTLIGHT section of /parks.
 *
 * Two-column layout at ≥900px (1fr / 1fr via `.parks-spotlight__cols` in
 * parks.css), stacked at <900px.
 *
 *   Left column:  <ParkFieldSvg> + <ParkHeatmapGrid> stacked vertically.
 *   Right column: <ParkFactorStrip> + <KeyNotes>.
 *
 * Pure presentational glue; takes a {@link ParkSpotlightDatum} and routes
 * its fields into the right sub-components. The page never reaches past
 * this component — Coors-or-otherwise is selected upstream.
 */

import { broadcastKeyNotesPalette } from "../broadcast/palettes";
import { KeyNotes } from "../scouting/key-notes";
import { ParkFactorStrip } from "./park-factor-strip";
import { ParkFieldSvg } from "./park-field-svg";
import { ParkHeatmapGrid } from "./park-heatmap-grid";
import type { ParkSpotlightDatum } from "../../data/parks-fixtures";

export type ParkSpotlightProps = {
  spotlight: ParkSpotlightDatum;
};

export function ParkSpotlight({ spotlight }: ParkSpotlightProps) {
  return (
    <div className="parks-spotlight__cols">
      <div
        style={{
          display: "flex",
          flexDirection: "column",
          gap: 16,
        }}
      >
        <ParkFieldSvg
          width={360}
          height={300}
          ariaLabel={`Generic baseball field outline — home plate bottom-center, foul lines at 45 degrees, ${spotlight.parkName} reference canvas`}
        />
        <ParkHeatmapGrid
          grid={spotlight.landingGrid}
          caption="Batted-Ball Landing Density · 2023–2025"
        />
      </div>
      <div
        style={{
          display: "flex",
          flexDirection: "column",
          gap: 16,
        }}
      >
        <ParkFactorStrip factors={spotlight.factors} />
        <KeyNotes notes={spotlight.keyReads} palette={broadcastKeyNotesPalette} />
      </div>
    </div>
  );
}

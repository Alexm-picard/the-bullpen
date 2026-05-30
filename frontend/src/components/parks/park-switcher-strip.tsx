/**
 * <ParkSwitcherStrip> — horizontal scroll strip of 30 <ParkMiniThumb>.
 *
 * Layout: full-width container with overflow-x: auto and scroll-snap-type:
 * x mandatory. 30 thumbs in a flex row with 12 px gap. Each thumb gets
 * scroll-snap-align: start so swipes/wheel scrolls click into a clean tile
 * position on touch + trackpad. Active thumb is highlighted by the thumb
 * itself (scarlet outline ring) — the strip owns no per-tile styling.
 *
 * The container is labelled "Park switcher · 30 parks" so screen-reader
 * users can navigate by landmark; individual tiles have their own
 * aria-labels.
 */

import { radii, colors } from "../../design/tokens";
import { ParkMiniThumb } from "./park-mini-thumb";
import type { ParkRow, ParkThumbnailDatum } from "../../data/parks-fixtures";

export type ParkSwitcherStripProps = {
  /** 30 thumbnail records (id + 6×6 grid). Order = display order. */
  thumbnails: ParkThumbnailDatum[];
  /** 30 park rows — used to resolve full park name + abbreviation. */
  rows: ParkRow[];
  /** Currently-active park id (the spotlight target). */
  activeParkId: string;
  /** Click handler; parent does the scroll-into-view. */
  onSelect: (parkId: string) => void;
};

export function ParkSwitcherStrip({
  thumbnails,
  rows,
  activeParkId,
  onSelect,
}: ParkSwitcherStripProps) {
  // Build an id → ParkRow lookup so we don't re-traverse the rows array
  // 30 times during render.
  const rowById = new Map<string, ParkRow>();
  for (const r of rows) rowById.set(r.id, r);

  return (
    <div
      role="region"
      aria-label="Park switcher · 30 parks"
      style={{
        backgroundColor: colors.bgSheet,
        border: `1px solid ${colors.bgEmphasis}`,
        borderRadius: radii.sm,
        padding: 12,
        overflowX: "auto",
        scrollSnapType: "x mandatory",
        WebkitOverflowScrolling: "touch",
      }}
    >
      <div
        style={{
          display: "flex",
          flexDirection: "row",
          gap: 12,
        }}
      >
        {thumbnails.map((t) => {
          const row = rowById.get(t.id);
          // If the fixture is inconsistent, fall back to the id; the
          // shape mismatch is caught by the tests.
          const parkName = row?.parkName ?? t.id;
          const abbr = row?.team ?? t.id;
          return (
            <ParkMiniThumb
              key={t.id}
              parkId={t.id}
              parkName={parkName}
              abbr={abbr}
              grid={t.grid}
              isActive={t.id === activeParkId}
              onSelect={onSelect}
            />
          );
        })}
      </div>
    </div>
  );
}

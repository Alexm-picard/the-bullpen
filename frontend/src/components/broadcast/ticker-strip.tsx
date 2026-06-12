/**
 * <TickerStrip> - the broadcast identity's stat ticker (decision [160]): a
 * chrome strip whose items scroll continuously. The track holds the item run
 * TWICE and translates -50% for a seamless loop (broadcast.css); under
 * prefers-reduced-motion the animation is removed entirely and the strip is a
 * static row ([112] discipline). Hover pauses.
 *
 * Decorative by contract: aria-hidden ticker content must never be the only
 * place a fact appears.
 */

import { colors, typography } from "../../design/broadcast";

import "../../design/broadcast.css";

export type TickerStripProps = {
  items: string[];
  /** Seconds for one full loop; scale with item count. */
  durationSeconds?: number;
};

export function TickerStrip({ items, durationSeconds = 30 }: TickerStripProps) {
  if (items.length === 0) {
    return null;
  }
  const run = items.map((item, i) => (
    <span
      key={i}
      style={{
        display: "inline-flex",
        alignItems: "center",
        padding: "5px 18px",
        fontFamily: typography.fonts.mono,
        fontSize: 12,
        fontFeatureSettings: '"tnum" 1',
        color: colors.textOnChrome,
        borderRight: `1px solid ${colors.chromeEdge}`,
      }}
    >
      {item}
    </span>
  ));
  return (
    <div
      className="broadcast-ticker"
      aria-hidden="true"
      style={{
        overflow: "hidden",
        backgroundColor: colors.chromeDeep,
        borderTop: `2px solid ${colors.gold}`,
      }}
    >
      <div
        className="broadcast-ticker__track"
        style={{ ["--ticker-duration" as string]: `${durationSeconds}s` }}
      >
        {run}
        {run}
      </div>
    </div>
  );
}

/**
 * <TickerStrip> - the broadcast identity's stat ticker (decision [160]): a
 * chrome strip whose items scroll continuously. The track holds the item run
 * TWICE and translates -50% for a seamless loop (broadcast.css); under
 * prefers-reduced-motion the animation is removed entirely and the strip is a
 * static row ([112] discipline).
 *
 * The crawl never relabels mid-loop: the rendered run is frozen and swapped for
 * the latest items only at the animation's loop boundary, so a reader tracking
 * one item never sees its text change under them. A visible Pause / Play control
 * (WCAG 2.2.2) sits OUTSIDE the aria-hidden strip; hover-pause stays a
 * pointer-only convenience in broadcast.css.
 *
 * Decorative by contract: aria-hidden ticker content must never be the only
 * place a fact appears.
 */

import { useReducedMotion } from "@mantine/hooks";
import { useState } from "react";

import { colors, typography } from "../../design/broadcast";

import "../../design/broadcast.css";

/** A ticker item: plain text, or text with a stable key when texts can repeat. */
export type TickerItem = string | { key: string | number; text: string };

export type TickerStripProps = {
  items: TickerItem[];
  /** Seconds for one full loop; scale with item count. */
  durationSeconds?: number;
};

const itemKey = (item: TickerItem): string | number =>
  typeof item === "string" ? item : item.key;
const itemText = (item: TickerItem): string =>
  typeof item === "string" ? item : item.text;

export function TickerStrip({ items, durationSeconds = 30 }: TickerStripProps) {
  const reduceMotion = useReducedMotion();
  const [paused, setPaused] = useState(false);
  // The run on screen. Newer `items` wait until the loop boundary: the
  // iteration handler below is re-bound every render, so it always adopts the
  // latest props without a ref.
  const [shown, setShown] = useState(items);
  // Nothing is crawling yet (first items of the game): adopt them immediately.
  if (shown.length === 0 && items.length > 0) {
    setShown(items);
  }
  // A static (reduced-motion) row has no loop boundary and nothing moving to
  // protect, so it simply shows the latest items.
  const visible = reduceMotion ? items : shown;

  if (visible.length === 0) {
    return null;
  }
  const run = visible.map((item) => (
    <span
      key={itemKey(item)}
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
      {itemText(item)}
    </span>
  ));
  return (
    <div
      style={{
        display: "flex",
        alignItems: "stretch",
        backgroundColor: colors.chromeDeep,
        borderTop: `2px solid ${colors.gold}`,
      }}
    >
      <div
        className="broadcast-ticker"
        aria-hidden="true"
        data-paused={paused ? "true" : "false"}
        style={{ overflow: "hidden", flex: "1 1 auto", minWidth: 0 }}
      >
        <div
          className="broadcast-ticker__track"
          style={{ ["--ticker-duration" as string]: `${durationSeconds}s` }}
          onAnimationIteration={() => setShown(items)}
        >
          {run}
          {run}
        </div>
      </div>
      {reduceMotion ? null : (
        <button
          type="button"
          className="bp-pressable"
          // Media-player pattern: the label names the action it will take. (An
          // aria-pressed toggle whose visible text also flips would read
          // "Play, pressed" - two signals saying opposite things.)
          aria-label={
            paused ? "Play the pitch ticker" : "Pause the pitch ticker"
          }
          onClick={() => setPaused((p) => !p)}
          style={{
            flex: "0 0 auto",
            minWidth: 56,
            padding: "0 12px",
            fontFamily: typography.fonts.mono,
            fontSize: 11,
            letterSpacing: typography.tracking.chip,
            textTransform: "uppercase",
            color: colors.textOnChrome,
            backgroundColor: colors.chrome,
            border: "none",
            borderLeft: `1px solid ${colors.chromeEdge}`,
          }}
        >
          {paused ? "Play" : "Pause"}
        </button>
      )}
    </div>
  );
}

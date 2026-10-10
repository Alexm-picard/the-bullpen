import { basesLabel } from "./game-account";

/**
 * The bases as three rotated squares, ink only: an occupied base is filled, an empty one outlined.
 * `mask` is the feed's 1/2/4 occupancy bitmask (1 = first, 2 = second, 4 = third). The accessible
 * name says the occupancy in words; the drawing is the same fact for sighted readers.
 */
export function BasesGlyph({ mask }: { mask: number }) {
  return (
    <svg
      className="ed-bases"
      viewBox="0 0 44 32"
      role="img"
      aria-label={basesLabel(mask)}
    >
      <rect
        data-on={mask & 2 ? "true" : "false"}
        x="16"
        y="4"
        width="12"
        height="12"
        transform="rotate(45 22 10)"
      />
      <rect
        data-on={mask & 4 ? "true" : "false"}
        x="4"
        y="16"
        width="12"
        height="12"
        transform="rotate(45 10 22)"
      />
      <rect
        data-on={mask & 1 ? "true" : "false"}
        x="28"
        y="16"
        width="12"
        height="12"
        transform="rotate(45 34 22)"
      />
    </svg>
  );
}

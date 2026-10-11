/** Shared non-component helpers for the /ops register (kept out of the component modules so fast
 * refresh stays component-only). */

const ET_CLOCK = new Intl.DateTimeFormat("en-US", {
  hour: "numeric",
  minute: "2-digit",
  timeZone: "America/New_York",
});

/** "7:42 PM ET" from a query's dataUpdatedAt (ms); empty when the query never resolved. */
export function readTime(updatedAt: number): string {
  return updatedAt > 0 ? `${ET_CLOCK.format(new Date(updatedAt))} ET` : "";
}

/** The em-dash every missing figure renders as. */
export const DASH = "\u2014";

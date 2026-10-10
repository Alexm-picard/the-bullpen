const ET_STAMP = new Intl.DateTimeFormat("en-US", {
  dateStyle: "medium",
  timeStyle: "short",
  timeZone: "America/New_York",
});

/**
 * The live part's as-of stamp. Validated: an unparseable or missing generatedAt renders
 * NOTHING - a thrown RangeError would take the whole page (offline part included) to the
 * ErrorBoundary, and a null coalesced into Dec 31 1969 would be a fabricated date on the one
 * part labelled live.
 */
export function asOfStamp(generatedAt: string | null | undefined): string {
  if (generatedAt == null) return "";
  const d = new Date(generatedAt);
  if (!Number.isFinite(d.getTime())) return "";
  return `as of ${ET_STAMP.format(d)} ET, refreshed every 5 min`;
}

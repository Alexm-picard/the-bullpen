/**
 * "2026-05-20 18:30 UTC" — short enough to fit in a column, unambiguous on TZ.
 * Lives in its own module so React Fast Refresh stays happy with the component
 * file (Fast Refresh refuses to mix component + non-component exports).
 */
export function formatTimestamp(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const pad = (n: number) => String(n).padStart(2, "0");
  return (
    `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())} ` +
    `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())} UTC`
  );
}

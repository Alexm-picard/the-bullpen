/**
 * <DistributionShell> - the non-data state of a live distribution panel
 * (NextPitchPanel, PitchTypePanel), drawn at the SAME geometry as the data
 * branch: one grid row per class (label / empty track / em-dash) followed by
 * the state's note.
 *
 * Without it every gated / 503 / error / loading branch rendered a one-line
 * paragraph while the data branch rendered a 5- or 7-row list, so the panel
 * collapsed ~130px at every at-bat boundary and re-expanded a moment later.
 *
 * The rows are decorative scaffolding (aria-hidden) and carry no number: the
 * note is the only thing the state says, and it is the only thing announced.
 */
import { colors, typography } from "../../design/broadcast";

/** Grid columns shared by the shell and both panels' data rows. */
export const DISTRIBUTION_COLUMNS = "minmax(96px, 130px) minmax(0, 1fr) 6ch";

export type DistributionShellProps = {
  /** The panel's class labels, in the order the data branch would show them. */
  rows: string[];
  /** The state's visible copy (kept verbatim - tests and e2e pin it). */
  note: React.ReactNode;
  /** True while a request is in flight. */
  busy?: boolean;
  testId?: string;
};

export function DistributionShell({
  rows,
  note,
  busy,
  testId,
}: DistributionShellProps) {
  return (
    <div>
      <div aria-hidden="true">
        {rows.map((label) => (
          <div
            key={label}
            style={{
              display: "grid",
              gridTemplateColumns: DISTRIBUTION_COLUMNS,
              alignItems: "center",
              gap: 10,
              padding: "3px 0",
            }}
          >
            <span
              style={{
                minWidth: 0,
                overflowWrap: "anywhere",
                fontFamily: typography.fonts.body,
                fontSize: 13,
                color: colors.textMuted,
              }}
            >
              {label}
            </span>
            <span
              style={{
                display: "block",
                height: 10,
                background: colors.fieldSubtle,
              }}
            />
            <span
              style={{
                fontFamily: typography.fonts.mono,
                fontSize: 12,
                textAlign: "right",
                color: colors.textMuted,
              }}
            >
              —
            </span>
          </div>
        ))}
      </div>
      <p
        role={busy ? undefined : "status"}
        aria-busy={busy ? "true" : undefined}
        data-testid={testId}
        style={{
          margin: "8px 0 0",
          fontFamily: typography.fonts.body,
          fontSize: 13,
          lineHeight: typography.leading.dense,
          letterSpacing: 0,
          color: colors.textMuted,
        }}
      >
        {note}
      </p>
    </div>
  );
}

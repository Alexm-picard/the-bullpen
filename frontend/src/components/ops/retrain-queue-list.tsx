/**
 * <RetrainQueueList> — the compact list of active / queued / awaiting-promotion
 * retrain jobs. Sits between the drift snapshot and the ops log.
 *
 * NOT a StatTable — the row shape (trigger badge + model+reason + timestamps +
 * status) doesn't fit StatTable's "row-label + uniform value columns" model
 * cleanly, and we need real <abbr title=…> markup on the AWAITING-PROMOTION
 * status so the meaning is in the title-tip (not color alone).
 *
 * Visual: same panel chrome as <KeyNotes> (bgSheet inside, bgEmphasis border,
 * navy lower-third header bar). Up to 3 entries; the empty-state path is
 * exercised when the queue is empty.
 *
 * a11y: status colors pair with Saira-heavy uppercase text (color is not the
 * sole carrier). AWAITING-PROMOTION wraps in <abbr> so screen readers and
 * hover-tip both surface the rule-6 meaning.
 */

import type { RetrainEntry, RetrainStatus } from "../../data/ops-fixtures";
import { radii, colors, typography } from "../../design/tokens";

export type RetrainQueueListProps = {
  entries: RetrainEntry[];
};

// All triggers currently share the scarlet badge — the action color in the
// operator's mental model. If per-trigger hues become needed, reintroduce a
// trigger argument here and switch callers in one place.
function triggerColor(): string {
  return colors.scarlet;
}

function statusColor(status: RetrainStatus): string {
  if (status === "AWAITING-PROMOTION") return colors.scarlet;
  return colors.textStrong;
}

function statusAbbrTitle(status: RetrainStatus): string | undefined {
  if (status === "AWAITING-PROMOTION") {
    return "Awaiting human promotion gate (discipline rule 6)";
  }
  return undefined;
}

export function RetrainQueueList({ entries }: RetrainQueueListProps) {
  return (
    <section
      style={{
        backgroundColor: colors.bgSheet,
        border: `1px solid ${colors.bgEmphasis}`,
        borderRadius: radii.sm,
      }}
      aria-labelledby="retrain-queue-header"
    >
      <div
        id="retrain-queue-header"
        style={{
          backgroundColor: colors.navy,
          color: colors.textOnNavy,
          fontFamily: typography.fonts.display,
          fontSize: typography.scale[1], // 14
          fontWeight: typography.weights.bold,
          textTransform: "uppercase",
          letterSpacing: "0.06em",
          padding: "8px 16px",
        }}
      >
        Retrain Queue
      </div>
      {entries.length === 0 ? (
        <div
          style={{
            padding: 16,
            fontFamily: typography.fonts.body,
            fontSize: typography.scale[1],
            color: colors.textMuted,
          }}
        >
          No retrain jobs in queue · last drift sweep 19:00 ET
        </div>
      ) : (
        <ol
          className="ops-retrain__list"
          style={{
            listStyle: "none",
            margin: 0,
            padding: 0,
          }}
        >
          {entries.map((entry, i) => {
            const isLast = i === entries.length - 1;
            const status = entry.status;
            const abbr = statusAbbrTitle(status);
            return (
              <li
                key={entry.id}
                className="ops-retrain__row"
                style={{
                  display: "grid",
                  gridTemplateColumns: "120px 1fr 200px 180px",
                  alignItems: "center",
                  gap: 16,
                  padding: "12px 16px",
                  borderBottom: isLast
                    ? "none"
                    : `1px solid ${colors.bgEmphasis}`,
                }}
              >
                {/* Trigger badge */}
                <span
                  style={{
                    fontFamily: typography.fonts.mono,
                    fontSize: 11,
                    fontWeight: typography.weights.bold,
                    letterSpacing: "0.06em",
                    textTransform: "uppercase",
                    color: triggerColor(),
                    border: `1px solid ${triggerColor()}`,
                    padding: "2px 6px",
                    borderRadius: radii.sm,
                    whiteSpace: "nowrap",
                    width: "fit-content",
                  }}
                >
                  {entry.trigger}
                </span>

                {/* Model name + reason */}
                <div
                  style={{
                    display: "flex",
                    flexDirection: "column",
                    gap: 2,
                    minWidth: 0,
                  }}
                >
                  <span
                    style={{
                      fontFamily: typography.fonts.display,
                      fontSize: 14,
                      fontWeight: typography.weights.semibold,
                      textTransform: "uppercase",
                      letterSpacing: "0.02em",
                      color: colors.textStrong,
                    }}
                  >
                    {entry.modelLabel}
                  </span>
                  <span
                    style={{
                      fontFamily: typography.fonts.body,
                      fontSize: 13,
                      color: colors.textMuted,
                    }}
                  >
                    {entry.reason}
                  </span>
                </div>

                {/* Times */}
                <div
                  style={{
                    fontFamily: typography.fonts.mono,
                    fontSize: 12,
                    color: colors.textMuted,
                    letterSpacing: "0.02em",
                    whiteSpace: "nowrap",
                  }}
                >
                  <span style={{ display: "block" }}>
                    QUEUED {entry.queuedAt}
                  </span>
                  <span style={{ display: "block" }}>
                    SCHEDULED {entry.scheduledFor}
                  </span>
                </div>

                {/* Status — Saira heavy, abbr title where applicable */}
                <span
                  style={{
                    fontFamily: typography.fonts.display,
                    fontSize: 13,
                    fontWeight: typography.weights.heavy,
                    textTransform: "uppercase",
                    letterSpacing: "0.06em",
                    color: statusColor(status),
                    textAlign: "right",
                    justifySelf: "end",
                    whiteSpace: "nowrap",
                  }}
                >
                  {abbr ? (
                    <abbr
                      title={abbr}
                      style={{
                        textDecoration: "none",
                        borderBottom: `1px dotted ${statusColor(status)}`,
                        cursor: "help",
                      }}
                    >
                      {status}
                    </abbr>
                  ) : (
                    status
                  )}
                </span>
              </li>
            );
          })}
        </ol>
      )}
    </section>
  );
}

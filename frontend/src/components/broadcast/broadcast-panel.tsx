/**
 * <BroadcastPanel> - the v2 content surface (decision [160]): a white panel on
 * the light field with a 1px rule border, an optional diagonal top-right cut,
 * and an optional team-color edge bar. Replaces the paper <ReportSheet> look
 * in migrated screens.
 *
 * The [101]-resolution rule applies INSIDE this panel: data stays calm - the
 * chrome energy is the frame around it.
 */

import { colors, cuts } from "../../design/broadcast";
import { teamColor } from "../../design/teamColors";

export type BroadcastPanelProps = {
  children: React.ReactNode;
  /** Diagonal-cut top-right corner (the broadcast edge language). */
  cut?: boolean;
  /** Team abbreviation for a 4px left edge bar; omit for none. */
  edgeTeam?: string;
  /** Inner padding (px). */
  padding?: number;
  style?: React.CSSProperties;
};

export function BroadcastPanel({
  children,
  cut = false,
  edgeTeam,
  padding = 16,
  style,
}: BroadcastPanelProps) {
  return (
    <div
      style={{
        position: "relative",
        display: "flex",
        alignItems: "stretch",
        backgroundColor: colors.panel,
        border: `1px solid ${colors.rule}`,
        clipPath: cut ? cuts.panelCorner : undefined,
        ...style,
      }}
    >
      {edgeTeam && (
        <span
          aria-hidden="true"
          style={{
            width: 4,
            flex: "0 0 auto",
            backgroundColor: teamColor(edgeTeam),
          }}
        />
      )}
      <div style={{ flex: 1, minWidth: 0, padding }}>{children}</div>
    </div>
  );
}

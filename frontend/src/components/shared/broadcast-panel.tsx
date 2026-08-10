import type { CSSProperties, ReactNode } from "react";

import { colors, typography } from "../../design/broadcast";

type Variant = "default" | "primary";

const PANEL_BASE: CSSProperties = {
  backgroundColor: colors.panel,
  border: `1px solid ${colors.panelEdge}`,
  borderRadius: 2,
  overflow: "hidden",
};

const PANEL_PRIMARY: CSSProperties = {
  ...PANEL_BASE,
  borderColor: colors.gold,
  borderWidth: 1,
};

const HEADER_BASE: CSSProperties = {
  display: "flex",
  alignItems: "center",
  justifyContent: "space-between",
  padding: "10px 16px",
  borderBottom: `1px solid ${colors.panelEdge}`,
  fontFamily: typography.fonts.display,
  fontWeight: typography.weights.heavy,
  fontSize: 14,
  letterSpacing: "0.08em",
  textTransform: "uppercase",
  color: colors.ink,
};

const HEADER_PRIMARY: CSSProperties = {
  ...HEADER_BASE,
  backgroundColor: "rgba(242, 169, 0, 0.06)",
  borderBottomColor: colors.gold,
};

const BODY: CSSProperties = {
  padding: 16,
};

export function BroadcastPanel({
  variant = "default",
  title,
  right,
  children,
  noPad,
  style,
}: {
  variant?: Variant;
  title?: ReactNode;
  right?: ReactNode;
  children: ReactNode;
  noPad?: boolean;
  style?: CSSProperties;
}) {
  const panelStyle = variant === "primary" ? PANEL_PRIMARY : PANEL_BASE;
  const headerStyle = variant === "primary" ? HEADER_PRIMARY : HEADER_BASE;
  return (
    <div style={{ ...panelStyle, ...style }}>
      {title != null && (
        <div style={headerStyle}>
          <span>{title}</span>
          {right}
        </div>
      )}
      <div style={noPad ? { padding: 0 } : BODY}>{children}</div>
    </div>
  );
}

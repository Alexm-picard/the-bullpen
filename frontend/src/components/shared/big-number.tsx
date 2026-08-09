import type { CSSProperties, ReactNode } from "react";

import { colors, typography } from "../../design/broadcast";

const CONTAINER: CSSProperties = {
  display: "flex",
  flexDirection: "column",
  alignItems: "center",
  gap: 2,
};

const VALUE: CSSProperties = {
  fontFamily: typography.fonts.mono,
  fontWeight: typography.weights.bold,
  fontSize: 36,
  lineHeight: 1,
  color: colors.ink,
  fontFeatureSettings: '"tnum" 1',
};

const LABEL: CSSProperties = {
  fontFamily: typography.fonts.mono,
  fontSize: 10,
  letterSpacing: "0.14em",
  textTransform: "uppercase",
  color: colors.textMuted,
};

export function BigNumber({
  value,
  label,
  valueColor,
  style,
}: {
  value: ReactNode;
  label: string;
  valueColor?: string;
  style?: CSSProperties;
}) {
  return (
    <div style={{ ...CONTAINER, ...style }}>
      <span style={{ ...VALUE, ...(valueColor ? { color: valueColor } : {}) }}>
        {value}
      </span>
      <span style={LABEL}>{label}</span>
    </div>
  );
}

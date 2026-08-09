import type { CSSProperties } from "react";

import { colors, typography } from "../../design/broadcast";

const STYLE: CSSProperties = {
  display: "inline-flex",
  alignItems: "center",
  gap: 6,
  fontFamily: typography.fonts.mono,
  fontSize: 10,
  letterSpacing: "0.12em",
  color: colors.textMuted,
  textDecoration: "none",
  textTransform: "uppercase",
  border: `1px solid ${colors.rule}`,
  padding: "4px 10px",
  transition: "color 0.15s, border-color 0.15s",
};

export function WhatLink({
  href,
  children = "Model guide",
}: {
  href: string;
  children?: string;
}) {
  return (
    <a href={href} style={STYLE}>
      <span style={{ color: colors.gold, fontSize: 11 }}>&#9432;</span>
      {children}
    </a>
  );
}

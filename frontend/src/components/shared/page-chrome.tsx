import type { CSSProperties, ReactNode } from "react";

import { BUILD_DATE, BUILD_SHA } from "../../build-info";
import { colors, layouts, typography } from "../../design/broadcast";

/**
 * Shared broadcast page shell. Every top-level page rendered the same two nested
 * divs (a light "field" background + a centered, max-width column) inline; this
 * pulls that skeleton into one place. Tokens only - no hex codes.
 *
 * The per-page axes that actually varied are the only props: `gap` (column
 * flex-gap; 28 is the dominant value), `bottomPad` (outer bottom padding; 0 for
 * most, 48 for the long list/search pages), and `topPad` (column paddingTop;
 * only not-found uses it).
 */
export function PageChrome({
  children,
  gap = 28,
  bottomPad = 0,
  topPad = 0,
}: {
  children: ReactNode;
  gap?: number;
  bottomPad?: number;
  topPad?: number;
}) {
  const fieldStyle: CSSProperties = {
    backgroundColor: colors.field,
    minHeight: "100%",
    padding: `24px 16px ${bottomPad}px`,
  };
  const columnStyle: CSSProperties = {
    maxWidth: layouts.broadcastMaxWidth,
    margin: "0 auto",
    display: "flex",
    flexDirection: "column",
    gap,
    ...(topPad ? { paddingTop: topPad } : {}),
  };
  return (
    <div style={fieldStyle}>
      <div style={columnStyle}>{children}</div>
    </div>
  );
}

const FOOTER_STYLE: CSSProperties = {
  display: "flex",
  justifyContent: "space-between",
  alignItems: "center",
  margin: "0 -16px",
  padding: "10px 16px",
  backgroundColor: colors.chromeDeep,
  fontFamily: typography.fonts.mono,
  fontSize: 11,
  letterSpacing: "0.04em",
  color: colors.textOnChromeMuted,
};

/**
 * The dark chrome footer strip. `children` is the left-span label (the only
 * thing that differed page to page). The middot separators are the literal
 * U+00B7 the pages used, so the rendered "THE BULLPEN · <LABEL>" text is
 * byte-identical (the page tests assert on it).
 */
export function BroadcastFooter({ children }: { children: ReactNode }) {
  return (
    <footer style={FOOTER_STYLE}>
      <span>THE BULLPEN · {children}</span>
      <span>
        build {BUILD_SHA} · {BUILD_DATE}
      </span>
    </footer>
  );
}

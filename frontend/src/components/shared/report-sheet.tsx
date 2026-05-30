/**
 * <ReportSheet> — the cream-paper bordered shell every scouting-report page
 * sits inside.
 *
 * Before this primitive existed, each of the six routes inlined the same shell
 * by hand (outer cream page bg + centered max-width container + bordered cream
 * sheet + CornerStripes positioned top-right). That duplication was flagged in
 * the Stage 4 review.
 *
 * Composition:
 *   - Outer wrapper: full-bleed cream `bgBase`, minHeight covers viewport
 *     minus the 56px header, top/bottom padding for breathing room.
 *   - Centered container: capped at `layouts.reportSheetMaxWidth` with 16px
 *     horizontal padding so the sheet never touches the viewport edges.
 *   - Inner sheet: `bgSheet` background, 1px navy border, 2px radius, 32px
 *     padding (matches the spec across all redesigned routes).
 *   - Optional CornerStripes in the top-right (default on; opt out via
 *     `showCornerStripes={false}`).
 *
 * The corner motif uses the shared `.report-sheet__corner` class declared in
 * `report-sheet.css`, which also handles the < 900px shrink to 80×80.
 */

import type { ReactNode } from "react";

import { radii, colors, layouts } from "../../design/tokens";

import { CornerStripes } from "./corner-stripes";

import "./report-sheet.css";

export type ReportSheetProps = {
  children: ReactNode;
  /** Default true. Set false for routes that intentionally omit the motif. */
  showCornerStripes?: boolean;
  /** Optional className on the inner sheet for page-specific scoped CSS. */
  sheetClassName?: string;
};

export function ReportSheet({
  children,
  showCornerStripes = true,
  sheetClassName,
}: ReportSheetProps) {
  return (
    <div
      style={{
        backgroundColor: colors.bgBase,
        minHeight: "calc(100vh - 56px)",
        paddingTop: 32,
        paddingBottom: 64,
      }}
    >
      <div
        style={{
          maxWidth: layouts.reportSheetMaxWidth,
          margin: "0 auto",
          padding: "0 16px",
        }}
      >
        <div
          className={
            sheetClassName
              ? `report-sheet__shell ${sheetClassName}`
              : "report-sheet__shell"
          }
          style={{
            backgroundColor: colors.bgSheet,
            border: `1px solid ${colors.navy}`,
            borderRadius: radii.sm,
            padding: 32,
          }}
        >
          {showCornerStripes && (
            <CornerStripes className="report-sheet__corner" />
          )}
          {children}
        </div>
      </div>
    </div>
  );
}

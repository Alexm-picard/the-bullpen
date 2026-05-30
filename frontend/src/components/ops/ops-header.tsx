/**
 * <OpsHeader> — the masthead for the Operator's Marginalia page (/ops).
 *
 * Same visual vocabulary as <CoverSheetHeader>: HeroEyebrow → two-line nameplate
 * h1 → bordered byline strip → mono context line. Distinct component because the
 * ops payload (alert count + awaiting-promotion count) doesn't unify with the
 * cover sheet's slate-count payload — sharing visual language, not code.
 *
 * Alert and awaiting-promotion counts ALWAYS render: zero counts read in muted
 * text, nonzero in scarlet + Saira heavy, so the operator's eye can resolve
 * "all clear" vs "something needs attention" in one glance without color being
 * the sole carrier (the words "ALERTS" / "AWAITING PROMOTION" carry it too).
 */

import { Stack, Title } from "@mantine/core";

import { colors, typography } from "../../design/tokens";
import { HeroEyebrow } from "../shared/hero-eyebrow";

export type OpsHeaderProps = {
  /** Human-friendly issue date, e.g. "Wed · May 30, 2026". */
  issueDate: string;
  /** Number of registered models in the fleet. */
  modelCount: number;
  /** Number of active drift alerts in the window. */
  alertCount: number;
  /** Number of models waiting on a human promotion gate (rule 6). */
  awaitingPromotionCount: number;
  /** Issue timestamp, e.g. "19:05 ET". */
  issuedAt: string;
  /** Observation window label, e.g. "WINDOW LAST 24H". */
  window: string;
};

const COUNT_LABEL_STYLE = {
  fontFamily: typography.fonts.display,
  fontSize: 14,
  fontWeight: typography.weights.heavy,
  textTransform: "uppercase" as const,
  letterSpacing: "0.06em",
};

export function OpsHeader({
  issueDate,
  modelCount,
  alertCount,
  awaitingPromotionCount,
  issuedAt,
  window,
}: OpsHeaderProps) {
  const alertColor = alertCount > 0 ? colors.scarlet : colors.textMuted;
  const awaitingColor =
    awaitingPromotionCount > 0 ? colors.scarlet : colors.textMuted;

  return (
    <Stack gap={10}>
      <HeroEyebrow>The Bullpen · Operations Desk</HeroEyebrow>
      <Title
        order={1}
        className="ops-cover__title"
        style={{
          fontFamily: typography.fonts.display,
          fontSize: typography.scale[7], // 64
          fontWeight: typography.weights.heavy,
          color: colors.textStrong,
          textTransform: "uppercase",
          letterSpacing: "0.005em",
          lineHeight: typography.lineHeights.display,
          margin: 0,
        }}
      >
        <span style={{ display: "block" }}>Model</span>
        <span style={{ display: "block" }}>Operations</span>
      </Title>
      <div
        style={{
          display: "flex",
          alignItems: "center",
          gap: 12,
          flexWrap: "wrap",
          fontFamily: typography.fonts.body,
          fontSize: typography.scale[2], // 16
          color: colors.textDefault,
          paddingTop: 6,
          paddingBottom: 6,
          borderTop: `1px solid ${colors.bgEmphasis}`,
          borderBottom: `1px solid ${colors.bgEmphasis}`,
        }}
      >
        <span style={{ fontWeight: typography.weights.semibold }}>
          {issueDate}
        </span>
        <span style={{ color: colors.textMuted }}>·</span>
        <span style={{ fontWeight: typography.weights.semibold }}>
          {modelCount} models
        </span>
        <span style={{ color: colors.textMuted }}>·</span>
        <span style={{ ...COUNT_LABEL_STYLE, color: alertColor }}>
          {alertCount} {alertCount === 1 ? "alert" : "alerts"}
        </span>
        <span style={{ color: colors.textMuted }}>·</span>
        <span style={{ ...COUNT_LABEL_STYLE, color: awaitingColor }}>
          {awaitingPromotionCount} awaiting promotion
        </span>
      </div>
      <div
        style={{
          fontFamily: typography.fonts.mono,
          fontSize: typography.scale[0], // 12
          color: colors.textMuted,
          letterSpacing: "0.04em",
          textTransform: "uppercase",
        }}
      >
        ISSUE {issuedAt} · {window}
      </div>
    </Stack>
  );
}

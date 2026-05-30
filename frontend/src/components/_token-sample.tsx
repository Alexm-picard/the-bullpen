/**
 * Visual ground-truth for the scouting-report design system (Stage 1).
 *
 * Renders every primitive of the new identity so that future page reviews
 * have a canonical reference: "does my new component look consistent with this?"
 *
 * Sections rendered (in order):
 *   1. Display headline — Saira Condensed 64px heavy uppercase
 *   2. Body paragraph — IBM Plex Sans 16px with scarlet chromatic anchor
 *   3. Mono stat block — IBM Plex Mono 24px tabular-nums
 *   4. Surface swatches — bgBase / bgSheet / bgSubtle / bgEmphasis
 *   5. Chrome swatches — navy / navyDeep / silver / scarlet
 *   6. Conditional-format ramp — bad3 / bad1 / neutral / good1 / good3
 *   7. Heat ramp — 4-stop warm yellow→scarlet
 *   8. Spray ramp — 4-stop green monochrome
 *   9. Categorical viz palette — 5 stops
 *  10. Live StatTable — 3 rows × 4 columns, 2 CF columns
 *
 * Uses `tokens.colors.*` directly (not Tailwind classes) so the visual is
 * unambiguous about canonical values.
 *
 * Updated 2026-05-29: scouting-report identity per decision [133].
 */

import { Box, Stack, Text } from "@mantine/core";

import { colors, typography } from "../design/tokens";
import type { MetricMeta } from "../design/cellColor";
import { StatTable } from "./shared/stat-table";
import type { StatTableColumn, StatTableRow } from "./shared/stat-table";

// ── Section helpers ──────────────────────────────────────────────────────────

function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <Text
      style={{
        fontFamily: typography.fonts.mono,
        fontSize: 11,
        fontWeight: typography.weights.semibold,
        letterSpacing: "0.1em",
        textTransform: "uppercase",
        color: colors.textMuted,
        borderBottom: `1px solid ${colors.bgEmphasis}`,
        paddingBottom: 4,
        marginBottom: 12,
      }}
    >
      {children}
    </Text>
  );
}

function Swatch({
  color,
  label,
  hex,
  textOnSwatch,
}: {
  color: string;
  label: string;
  hex: string;
  textOnSwatch?: string;
}) {
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 4 }}>
      <div
        style={{
          width: 80,
          height: 56,
          backgroundColor: color,
          border: `1px solid ${colors.bgEmphasis}`,
          borderRadius: 2,
          display: "flex",
          alignItems: "flex-end",
          padding: "4px 6px",
        }}
      >
        {textOnSwatch && (
          <span
            style={{
              fontFamily: typography.fonts.mono,
              fontSize: 10,
              color: textOnSwatch,
              fontWeight: 600,
            }}
          >
            Aa
          </span>
        )}
      </div>
      <Text
        style={{
          fontFamily: typography.fonts.mono,
          fontSize: 10,
          color: colors.textStrong,
          fontWeight: typography.weights.semibold,
        }}
      >
        {label}
      </Text>
      <Text
        style={{
          fontFamily: typography.fonts.mono,
          fontSize: 10,
          color: colors.textMuted,
        }}
      >
        {hex}
      </Text>
    </div>
  );
}

function RampSwatch({
  colors: rampColors,
  label,
}: {
  colors: readonly string[];
  label: string;
}) {
  return (
    <div>
      <Text
        style={{
          fontFamily: typography.fonts.mono,
          fontSize: 10,
          color: colors.textMuted,
          marginBottom: 4,
        }}
      >
        {label}
      </Text>
      <div style={{ display: "flex", gap: 2 }}>
        {rampColors.map((c, i) => (
          <div
            key={i}
            style={{
              width: 48,
              height: 32,
              backgroundColor: c,
              border: `1px solid ${colors.bgEmphasis}`,
              borderRadius: 2,
            }}
            title={c}
          />
        ))}
      </div>
    </div>
  );
}

// ── StatTable demo data ──────────────────────────────────────────────────────

const WHIFF_META: MetricMeta = {
  key: "whiff_rate",
  direction: "higher-is-better",
  reference: { min: 0.05, p25: 0.18, median: 0.24, p75: 0.31, max: 0.5 },
};

const ERA_PLUS_META: MetricMeta = {
  key: "era_plus",
  direction: "higher-is-better",
  reference: { min: 40, p25: 82, median: 100, p75: 120, max: 200 },
};

const TABLE_COLUMNS: StatTableColumn[] = [
  { key: "pa", label: "PA" },
  {
    key: "whiff",
    label: "Whiff%",
    metricMeta: WHIFF_META,
    format: (v) => `${(Number(v) * 100).toFixed(1)}%`,
  },
  {
    key: "era_plus",
    label: "ERA+",
    metricMeta: ERA_PLUS_META,
    format: (v) => String(Math.round(Number(v))),
  },
  { key: "k_pct", label: "K%" },
];

const TABLE_ROWS: StatTableRow[] = [
  {
    label: "Shohei Ohtani",
    values: { pa: 450, whiff: 0.34, era_plus: 147, k_pct: "24.2%" },
  },
  {
    label: "Gerrit Cole",
    values: { pa: 612, whiff: 0.29, era_plus: 131, k_pct: "28.7%" },
  },
  {
    label: "Pete Alonso",
    values: { pa: 498, whiff: 0.19, era_plus: 98, k_pct: "21.1%" },
  },
];

// ── Main component ───────────────────────────────────────────────────────────

export function TokenSampleCard() {
  return (
    <Box
      style={{
        backgroundColor: colors.bgBase,
        color: colors.textDefault,
        padding: 40,
        maxWidth: 820,
        fontFamily: typography.fonts.body,
      }}
    >
      <Stack gap={40}>
        {/* 1. Display headline */}
        <div>
          <SectionLabel>1 — Display face (Saira Condensed)</SectionLabel>
          <div
            style={{
              fontFamily: typography.fonts.display,
              fontSize: 64,
              fontWeight: typography.weights.heavy,
              lineHeight: typography.lineHeights.display,
              color: colors.textStrong,
              textTransform: "uppercase",
              letterSpacing: "0.01em",
            }}
          >
            SCOUTING REPORT — TOKENS
          </div>
        </div>

        {/* 2. Body paragraph */}
        <div>
          <SectionLabel>2 — Body face (IBM Plex Sans)</SectionLabel>
          <Text
            style={{
              fontFamily: typography.fonts.body,
              fontSize: 16,
              color: colors.textDefault,
              lineHeight: typography.lineHeights.body,
              maxWidth: 620,
            }}
          >
            UI body copy in IBM Plex Sans. Used everywhere the user reads prose,
            labels, table cells, and tooltip text. The{" "}
            <span
              style={{
                color: colors.scarlet,
                fontWeight: typography.weights.semibold,
              }}
            >
              scarlet accent ({colors.scarlet})
            </span>{" "}
            is the single chromatic anchor: team-graphics chrome, not a
            commercial CTA colour.
          </Text>
        </div>

        {/* 3. Mono stat block */}
        <div>
          <SectionLabel>3 — Mono stat face (IBM Plex Mono)</SectionLabel>
          <div
            style={{
              fontFamily: typography.fonts.mono,
              fontSize: 24,
              color: colors.textStrong,
              fontFeatureSettings: '"tnum" 1',
            }}
          >
            0.187{" "}
            <span style={{ color: colors.textMuted, fontSize: 16 }}>BRIER</span>
            {"  "}·{"  "}
            0.0036{" "}
            <span style={{ color: colors.textMuted, fontSize: 16 }}>ECE</span>
            {"  "}·{"  "}
            15,234{" "}
            <span style={{ color: colors.textMuted, fontSize: 16 }}>PREDS</span>
          </div>
        </div>

        {/* 4. Surface swatches */}
        <div>
          <SectionLabel>4 — Surface palette</SectionLabel>
          <div style={{ display: "flex", gap: 16, flexWrap: "wrap" }}>
            <Swatch color={colors.bgBase} label="bgBase" hex={colors.bgBase} />
            <Swatch
              color={colors.bgSheet}
              label="bgSheet"
              hex={colors.bgSheet}
            />
            <Swatch
              color={colors.bgSubtle}
              label="bgSubtle"
              hex={colors.bgSubtle}
            />
            <Swatch
              color={colors.bgEmphasis}
              label="bgEmphasis"
              hex={colors.bgEmphasis}
            />
          </div>
        </div>

        {/* 5. Chrome swatches */}
        <div>
          <SectionLabel>5 — Broadcast chrome</SectionLabel>
          <div style={{ display: "flex", gap: 16, flexWrap: "wrap" }}>
            <Swatch
              color={colors.navy}
              label="navy"
              hex={colors.navy}
              textOnSwatch={colors.textOnNavy}
            />
            <Swatch
              color={colors.navyDeep}
              label="navyDeep"
              hex={colors.navyDeep}
              textOnSwatch={colors.textOnNavy}
            />
            <Swatch color={colors.silver} label="silver" hex={colors.silver} />
            <Swatch
              color={colors.scarlet}
              label="scarlet"
              hex={colors.scarlet}
              textOnSwatch={colors.textOnNavy}
            />
          </div>
        </div>

        {/* 6. Conditional-format ramp */}
        <div>
          <SectionLabel>6 — Conditional-format diverging ramp</SectionLabel>
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
            {(
              [
                ["bad3", colors.condFormat.bad3],
                ["bad1", colors.condFormat.bad1],
                ["neutral", colors.condFormat.neutral],
                ["good1", colors.condFormat.good1],
                ["good3", colors.condFormat.good3],
              ] as [string, string][]
            ).map(([name, hex]) => (
              <div key={name} style={{ textAlign: "center" }}>
                <div
                  style={{
                    width: 72,
                    height: 40,
                    backgroundColor: hex,
                    border: `1px solid ${colors.bgEmphasis}`,
                    borderRadius: 2,
                    marginBottom: 4,
                  }}
                />
                <Text
                  style={{
                    fontFamily: typography.fonts.mono,
                    fontSize: 10,
                    color: colors.textStrong,
                  }}
                >
                  {name}
                </Text>
                <Text
                  style={{
                    fontFamily: typography.fonts.mono,
                    fontSize: 10,
                    color: colors.textMuted,
                  }}
                >
                  {hex}
                </Text>
              </div>
            ))}
          </div>
        </div>

        {/* 7. Heat ramp */}
        <div>
          <SectionLabel>7 — Sequential ramps</SectionLabel>
          <Stack gap={12}>
            <RampSwatch
              colors={colors.heatWarm}
              label="heatWarm (pitch-location KDE)"
            />
            <RampSwatch
              colors={colors.spray}
              label="spray (batted-ball density)"
            />
          </Stack>
        </div>

        {/* 8+9 combined above — categorical */}
        <div>
          <SectionLabel>8 — Categorical viz palette (5 stops)</SectionLabel>
          <div style={{ display: "flex", gap: 8 }}>
            {colors.viz.categorical.map((c, i) => (
              <div
                key={i}
                style={{
                  width: 48,
                  height: 28,
                  backgroundColor: c,
                  borderRadius: 2,
                  border: `1px solid ${colors.bgEmphasis}`,
                }}
                title={c}
              />
            ))}
          </div>
        </div>

        {/* 10. Live StatTable */}
        <div>
          <SectionLabel>9 — StatTable signature primitive (live)</SectionLabel>
          <StatTable
            columns={TABLE_COLUMNS}
            rows={TABLE_ROWS}
            caption="Sample scouting data — Whiff% and ERA+ are conditionally formatted"
          />
        </div>
      </Stack>
    </Box>
  );
}

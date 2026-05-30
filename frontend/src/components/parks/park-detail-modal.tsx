/**
 * <ParkDetailModal> — the detail surface opened from a tile or list row click.
 *
 * Two-column body inside a Mantine <Modal centered size="lg" padding="xl">:
 *   - Left: the full-size StadiumSvg with the same landing-zone dot the tile
 *     overlays, but at 280px instead of ~124px. Same coordinate space (viewBox
 *     0 0 500 500), so the overlay logic is copied straight from <ParkTile>.
 *   - Right: name + sub-line + P(HR) hero + 5-row fence-depth table + footer
 *     credit line with model name@version.
 *
 * Controlled by the parent via openParkId (string | null) — null closes the
 * modal. Closing fires onClose; Mantine handles Escape + backdrop-click +
 * focus return for us.
 *
 * Body is exported separately as <ParkDetailModalBody> so unit tests can render
 * it directly without going through Mantine's Modal portal (which does not
 * render content during SSR — its mount runs client-side only).
 *
 * Memoization isn't needed here — the modal only mounts when openParkId is
 * truthy, so it's not in the slider-drag render hot path.
 */
import { Modal, Stack, Text, Title } from "@mantine/core";

import parkMetaJson from "../../data/park-meta.json";
import { colors, spacing, typography } from "../../design/tokens";
import { HeroEyebrow } from "../shared/hero-eyebrow";
import { ProbBarThin } from "../shared/prob-bar-thin";

import { StadiumSvg } from "./stadium-svg";

type FenceRow = {
  angleDeg: number;
  label: string;
  distanceFt: number;
  heightFt: number;
  note?: string;
};

type ParkMeta = {
  name: string;
  svgPath: string;
  altitudeM: number | null;
  shortFenceFt: number;
  centerFenceFt: number | null;
  deepestFenceFt: number;
  fences: FenceRow[];
};

const META = parkMetaJson as Record<string, ParkMeta>;

export type ParkDetailModalProps = {
  /** Park to show; null = closed. */
  openParkId: string | null;
  onClose: () => void;
  /** Probability for the open park; null if not yet computed. */
  probHr: number | null;
  /** Deterministic landing-zone estimate (ft from home plate). */
  landingDistanceFt: number;
  /** Spray angle in degrees; + = LF, 0 = CF, - = RF. */
  sprayAngleDeg: number;
  /** Live model identity from the all-parks response (footer credit). */
  modelName: string | null;
  modelVersion: string | null;
};

export type ParkDetailModalBodyProps = {
  parkId: string;
  meta: ParkMeta;
  probHr: number | null;
  landingDistanceFt: number;
  sprayAngleDeg: number;
  modelName: string | null;
  modelVersion: string | null;
};

/**
 * The body content of the modal — exported separately so unit tests can
 * render it without the Mantine portal. The container modal wires it up via
 * `<ParkDetailModalBody {...} />` inside the Modal's children slot.
 */
export function ParkDetailModalBody({
  parkId,
  meta,
  probHr,
  landingDistanceFt,
  sprayAngleDeg,
  modelName,
  modelVersion,
}: ParkDetailModalBodyProps) {
  // Landing-zone math — identical to <ParkTile>. SVG inner stadium is centered
  // at (250, 480) with CF straight up; 1 SVG unit = 1 foot.
  const sprayRad = (sprayAngleDeg * Math.PI) / 180;
  const landX = 250 - landingDistanceFt * Math.sin(sprayRad);
  const landY = 480 - landingDistanceFt * Math.cos(sprayRad);

  return (
    <div
      style={{
        display: "flex",
        flexWrap: "wrap",
        gap: spacing[5],
        alignItems: "flex-start",
      }}
    >
      {/* Left: stadium SVG with landing dot */}
      <div
        style={{
          flex: "0 0 auto",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
        }}
      >
        <StadiumSvg
          parkId={parkId}
          size={280}
          ariaLabel={`${meta.name} field outline`}
          color={colors.textDefault}
        >
          {probHr != null ? (
            <circle
              cx={landX}
              cy={landY}
              r={11}
              fill={colors.scarlet}
              stroke={colors.bgSheet}
              strokeWidth={2}
              pointerEvents="none"
            />
          ) : null}
        </StadiumSvg>
      </div>

      {/* Right: name + sub + P(HR) + fences + footer */}
      <Stack gap={spacing[4]} style={{ flex: "1 1 280px", minWidth: 240 }}>
        <Stack gap={spacing[1]}>
          <Title
            order={2}
            style={{
              margin: 0,
              fontFamily: typography.fonts.body,
              fontSize: typography.scale[5], // 32
              fontWeight: typography.weights.semibold,
              color: colors.textStrong,
              lineHeight: 1.1,
              letterSpacing: "-0.02em",
            }}
          >
            {meta.name}
          </Title>
          <Text
            style={{
              fontFamily: typography.fonts.mono,
              fontSize: typography.scale[1], // 14
              color: colors.textMuted,
              letterSpacing: "0.04em",
              fontVariantNumeric: "tabular-nums",
            }}
          >
            {parkId} · altitude{" "}
            {meta.altitudeM == null ? "—" : `${meta.altitudeM} m`}
          </Text>
        </Stack>

        <Stack gap={spacing[2]}>
          <HeroEyebrow>P(HOME RUN)</HeroEyebrow>
          <Text
            style={{
              fontFamily: typography.fonts.mono,
              fontSize: typography.scale[6], // 40
              fontWeight: typography.weights.medium,
              color: probHr == null ? colors.textMuted : colors.textStrong,
              lineHeight: 1,
              letterSpacing: "-0.02em",
              fontVariantNumeric: "tabular-nums",
            }}
          >
            {probHr == null ? "—" : `${(probHr * 100).toFixed(1)}%`}
          </Text>
          <ProbBarThin
            value={probHr ?? 0}
            ariaLabel={
              probHr == null
                ? `${meta.name} home run probability unknown`
                : `${meta.name} home run probability ${(probHr * 100).toFixed(1)} percent`
            }
          />
        </Stack>

        <Stack gap={spacing[1]}>
          <HeroEyebrow>FENCE DEPTHS</HeroEyebrow>
          <div
            role="table"
            aria-label={`${meta.name} fence depths`}
            style={{
              display: "flex",
              flexDirection: "column",
              gap: spacing[1],
            }}
          >
            {meta.fences.map((f) => (
              <div
                role="row"
                key={`${meta.name}-${f.label}-${f.angleDeg}`}
                style={{
                  display: "grid",
                  gridTemplateColumns: "32px 1fr",
                  alignItems: "baseline",
                  columnGap: spacing[3],
                  paddingTop: spacing[1],
                  paddingBottom: spacing[1],
                  borderBottom: `1px solid ${colors.bgEmphasis}`,
                }}
              >
                <Text
                  component="span"
                  role="cell"
                  style={{
                    fontFamily: typography.fonts.mono,
                    fontSize: typography.scale[0], // 12
                    fontWeight: typography.weights.semibold,
                    color: colors.textMuted,
                    letterSpacing: "0.06em",
                  }}
                >
                  {f.label}
                </Text>
                <div
                  role="cell"
                  style={{
                    display: "flex",
                    flexWrap: "wrap",
                    alignItems: "baseline",
                    gap: spacing[2],
                  }}
                >
                  <Text
                    component="span"
                    style={{
                      fontFamily: typography.fonts.mono,
                      fontSize: typography.scale[1], // 14
                      color: colors.textStrong,
                      fontVariantNumeric: "tabular-nums",
                    }}
                  >
                    {f.distanceFt} ft · {f.heightFt} ft
                  </Text>
                  {f.note ? (
                    <Text
                      component="span"
                      style={{
                        fontFamily: typography.fonts.body,
                        fontSize: typography.scale[0], // 12
                        fontStyle: "italic",
                        color: colors.textMuted,
                      }}
                    >
                      {f.note}
                    </Text>
                  ) : null}
                </div>
              </div>
            ))}
          </div>
        </Stack>

        <Text
          style={{
            fontFamily: typography.fonts.mono,
            fontSize: typography.scale[0], // 12
            color: colors.textMuted,
            letterSpacing: "0.04em",
          }}
        >
          Modeled with {modelName ?? "—"}@{modelVersion ?? "—"}
        </Text>
      </Stack>
    </div>
  );
}

export function ParkDetailModal({
  openParkId,
  onClose,
  probHr,
  landingDistanceFt,
  sprayAngleDeg,
  modelName,
  modelVersion,
}: ParkDetailModalProps) {
  const meta = openParkId ? META[openParkId] : null;

  return (
    <Modal
      opened={Boolean(openParkId && meta)}
      onClose={onClose}
      centered
      size="lg"
      padding="xl"
      withCloseButton
      title={
        openParkId ? (
          <HeroEyebrow>PARK DETAIL · {openParkId}</HeroEyebrow>
        ) : null
      }
      aria-label={meta ? `${meta.name} detail` : "Park detail"}
    >
      {meta && openParkId ? (
        <ParkDetailModalBody
          parkId={openParkId}
          meta={meta}
          probHr={probHr}
          landingDistanceFt={landingDistanceFt}
          sprayAngleDeg={sprayAngleDeg}
          modelName={modelName}
          modelVersion={modelVersion}
        />
      ) : null}
    </Modal>
  );
}

/** Re-export for tests so they can resolve metadata by id. */
export { META as PARK_META_FOR_TESTS };

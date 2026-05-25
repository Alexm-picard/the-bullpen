/**
 * <StickyControlRail> — the 5-control launch-parameter row for /parks redesign.
 *
 * Controls (left to right):
 *   - Batter stand (SegmentedControl L/R)
 *   - Launch speed (Slider, 60–120 mph)
 *   - Launch angle (Slider, -10 to 60 deg)
 *   - Release speed (Slider, 50–100 mph)
 *   - Spray angle (Slider, -45 to 45 deg) — flagged as placeholder pending Phase 2c.5
 *
 * Sticky behavior:
 *   - Position: sticky, top: 56px (under the AppShell header).
 *   - An IntersectionObserver watching a 1px sentinel placed just above the
 *     rail's natural position toggles `shrink` state. When scrolled past the
 *     sentinel, the rail compresses: slider labels move inline (above → next to
 *     the slider), padding tightens, and a subtle bottom border appears so the
 *     rail visually separates from the scrolled content below.
 *   - The transition respects prefers-reduced-motion via the global
 *     `src/design/motion.css` reset — no extra plumbing here.
 *
 * Debouncing is the consumer's responsibility — the rail emits raw values on
 * every change. The parent page wraps with `useDebouncedValue`.
 *
 * Memoized: yes (React.memo). The rail re-renders cheaply but the parent grid
 * re-renders on every value change, so isolating this from those re-renders
 * keeps the 5-control row responsive at slider-drag rates.
 */
import { SegmentedControl, Slider, Text } from "@mantine/core";
import { memo, useEffect, useRef, useState } from "react";

import { colors, radii, spacing, typography } from "../../design/tokens";

export type LaunchParamsExtended = {
  stand: "L" | "R";
  launchSpeedMph: number;
  launchAngleDeg: number;
  releaseSpeedMph: number;
  sprayAngleDeg: number;
};

export type StickyControlRailProps = {
  values: LaunchParamsExtended;
  onChange: (next: LaunchParamsExtended) => void;
  /** Subtle indicator that a refetch is in flight. */
  isUpdating?: boolean;
};

const STAND_DATA = [
  { value: "L", label: "L" },
  { value: "R", label: "R" },
];

function StickyControlRailInner({
  values,
  onChange,
  isUpdating = false,
}: StickyControlRailProps) {
  const sentinelRef = useRef<HTMLDivElement | null>(null);
  const [shrink, setShrink] = useState(false);

  useEffect(() => {
    const sentinel = sentinelRef.current;
    if (!sentinel || typeof window === "undefined") return;
    if (typeof window.IntersectionObserver === "undefined") return;
    const obs = new window.IntersectionObserver(
      (entries) => {
        const entry = entries[0];
        if (!entry) return;
        // Sentinel is visible → not scrolled past → not shrunk.
        setShrink(!entry.isIntersecting);
      },
      { rootMargin: "-56px 0px 0px 0px", threshold: 0 },
    );
    obs.observe(sentinel);
    return () => obs.disconnect();
  }, []);

  const verticalPad = shrink ? spacing[3] : spacing[4];

  return (
    <>
      <div ref={sentinelRef} aria-hidden style={{ height: 1, width: "100%" }} />
      <div
        data-shrink={shrink}
        style={{
          position: "sticky",
          top: 56, // AppShell header height
          zIndex: 10,
          backgroundColor: colors.bgBase,
          borderBottom: shrink
            ? `1px solid ${colors.bgEmphasis}`
            : "1px solid transparent",
          paddingTop: verticalPad,
          paddingBottom: verticalPad,
          marginTop: spacing[5],
          transition:
            "padding 200ms cubic-bezier(0.4, 0, 0.2, 1), border-color 200ms cubic-bezier(0.4, 0, 0.2, 1)",
        }}
      >
        <div className="parks-rail-grid">
          {/* Stand */}
          <div className="parks-rail-cell">
            <Text
              component="label"
              style={{
                fontFamily: typography.fonts.ui,
                fontSize: typography.scale[0], // 12
                fontWeight: typography.weights.semibold,
                color: colors.textMuted,
                letterSpacing: "0.06em",
                textTransform: "uppercase",
              }}
            >
              Stand
            </Text>
            <SegmentedControl
              value={values.stand}
              onChange={(v) => onChange({ ...values, stand: v as "L" | "R" })}
              data={STAND_DATA}
              size="xs"
              color="brand"
              aria-label="Batter stand"
            />
          </div>

          <SliderCell
            label="Speed"
            unit="mph"
            value={values.launchSpeedMph}
            min={60}
            max={120}
            step={0.5}
            decimals={1}
            onChange={(v) => onChange({ ...values, launchSpeedMph: v })}
            ariaLabel="Launch speed in mph"
          />

          <SliderCell
            label="Angle"
            unit="°"
            value={values.launchAngleDeg}
            min={-10}
            max={60}
            step={0.5}
            decimals={1}
            onChange={(v) => onChange({ ...values, launchAngleDeg: v })}
            ariaLabel="Launch angle in degrees"
          />

          <SliderCell
            label="Release"
            unit="mph"
            value={values.releaseSpeedMph}
            min={50}
            max={100}
            step={0.5}
            decimals={1}
            onChange={(v) => onChange({ ...values, releaseSpeedMph: v })}
            ariaLabel="Release speed in mph"
          />

          <SliderCell
            label="Spray"
            unit="°"
            value={values.sprayAngleDeg}
            min={-45}
            max={45}
            step={1}
            decimals={0}
            onChange={(v) => onChange({ ...values, sprayAngleDeg: v })}
            ariaLabel="Spray angle in degrees (positive = left field)"
            note="pending 30-park MLP"
          />
        </div>

        {isUpdating ? (
          <Text
            style={{
              fontFamily: typography.fonts.data,
              fontSize: typography.scale[0], // 12
              color: colors.textMuted,
              marginTop: spacing[2],
              letterSpacing: "0.04em",
            }}
          >
            updating…
          </Text>
        ) : null}

        <style>{`
          .parks-rail-grid {
            display: grid;
            grid-template-columns: auto repeat(4, 1fr);
            column-gap: ${spacing[5]}px;
            row-gap: ${spacing[3]}px;
            align-items: end;
          }
          .parks-rail-cell {
            display: flex;
            flex-direction: column;
            gap: ${spacing[1]}px;
            min-width: 0;
          }
          [data-shrink="true"] .parks-rail-grid {
            column-gap: ${spacing[4]}px;
          }
          @media (max-width: 900px) {
            .parks-rail-grid {
              grid-template-columns: 1fr 1fr;
            }
          }
          @media (max-width: 480px) {
            .parks-rail-grid {
              grid-template-columns: 1fr;
            }
          }
        `}</style>
      </div>
    </>
  );
}

function SliderCell({
  label,
  unit,
  value,
  min,
  max,
  step,
  decimals,
  onChange,
  ariaLabel,
  note,
}: {
  label: string;
  unit: string;
  value: number;
  min: number;
  max: number;
  step: number;
  decimals: number;
  onChange: (v: number) => void;
  ariaLabel: string;
  note?: string;
}) {
  const display = `${value.toFixed(decimals)}${unit ? ` ${unit}` : ""}`;
  return (
    <div className="parks-rail-cell">
      <div
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "baseline",
          gap: spacing[2],
        }}
      >
        <Text
          component="span"
          style={{
            fontFamily: typography.fonts.ui,
            fontSize: typography.scale[0], // 12
            fontWeight: typography.weights.semibold,
            color: colors.textMuted,
            letterSpacing: "0.06em",
            textTransform: "uppercase",
          }}
        >
          {label}
          {note ? (
            <Text
              component="span"
              style={{
                marginLeft: spacing[1],
                fontFamily: typography.fonts.ui,
                fontSize: typography.scale[0],
                color: colors.textSubtle,
                fontWeight: typography.weights.regular,
                letterSpacing: 0,
                textTransform: "none",
              }}
            >
              ({note})
            </Text>
          ) : null}
        </Text>
        <Text
          component="span"
          style={{
            fontFamily: typography.fonts.data,
            fontSize: typography.scale[1], // 14
            color: colors.textStrong,
            fontVariantNumeric: "tabular-nums",
          }}
        >
          {display}
        </Text>
      </div>
      <Slider
        value={value}
        min={min}
        max={max}
        step={step}
        onChange={onChange}
        label={null}
        aria-label={ariaLabel}
        size="sm"
        color="brand"
        style={{ borderRadius: radii.pill }}
      />
    </div>
  );
}

export const StickyControlRail = memo(StickyControlRailInner);

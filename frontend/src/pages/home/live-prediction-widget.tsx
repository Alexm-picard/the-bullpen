/**
 * <LivePredictionWidget> — the hero-side interactive widget on /home.
 *
 * Five live controls drive a real POST /v1/predict/batted-ball against the toy
 * batted-ball model: a park dropdown, three sliders (launch speed, launch angle,
 * release speed), and a stand toggle. The probability output renders as a large
 * number + thin accent bar + footer (model name/version, latency, correlation tail).
 *
 * The widget is the credibility floor for the home page: "a real ML pipeline serves
 * a real number from a real registry, calibrated and traced". It is deliberately
 * NOT an editorial illustration.
 *
 * Wiring:
 *   - TanStack Query with the 6-tuple key per the approved spec.
 *   - Slider changes are debounced 250ms (Mantine's useDebouncedValue).
 *   - park/stand are discrete — they re-key the query immediately.
 *   - Width budget: 100% of the hero's 7/12 column; controls + output fit in ~520px.
 *   - Vertical rhythm: ≤ 80px taller than the 2-slider + park layout it replaces.
 */
import { SegmentedControl, Select, Slider, Stack, Text } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";

import { predictBattedBall } from "../../api/predict";
import { ProbBarThin } from "../../components/shared/prob-bar-thin";
import { colors, spacing, typography } from "../../design/tokens";

const PARK_OPTIONS = [
  { value: "FENWAY", label: "Fenway Park" },
  { value: "YANKEE", label: "Yankee Stadium" },
  { value: "WRIGLEY", label: "Wrigley Field" },
  { value: "COORS", label: "Coors Field" },
  { value: "ORACLE", label: "Oracle Park" },
];

const INITIAL = {
  parkId: "FENWAY",
  launchSpeedMph: 102.3,
  launchAngleDeg: 28.0,
  releaseSpeedMph: 95.0,
  stand: "R" as "L" | "R",
};

function SliderRow({
  label,
  value,
  display,
  min,
  max,
  step,
  onChange,
  ariaLabel,
}: {
  label: string;
  value: number;
  display: string;
  min: number;
  max: number;
  step: number;
  onChange: (v: number) => void;
  ariaLabel: string;
}) {
  return (
    <Stack gap={spacing[1]}>
      <div
        style={{
          display: "flex",
          alignItems: "baseline",
          justifyContent: "space-between",
        }}
      >
        <Text
          style={{
            fontFamily: typography.fonts.ui,
            fontSize: typography.scale[1], // 14
            fontWeight: typography.weights.medium,
            color: colors.textDefault,
          }}
        >
          {label}
        </Text>
        <Text
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
      />
    </Stack>
  );
}

export function LivePredictionWidget() {
  const [parkId, setParkId] = useState(INITIAL.parkId);
  const [launchSpeedMph, setLaunchSpeed] = useState(INITIAL.launchSpeedMph);
  const [launchAngleDeg, setLaunchAngle] = useState(INITIAL.launchAngleDeg);
  const [releaseSpeedMph, setReleaseSpeed] = useState(INITIAL.releaseSpeedMph);
  const [stand, setStand] = useState<"L" | "R">(INITIAL.stand);

  // Debounce only the slider values — park / stand are discrete and should re-key
  // the query immediately on click.
  const [debouncedSpeed] = useDebouncedValue(launchSpeedMph, 250);
  const [debouncedAngle] = useDebouncedValue(launchAngleDeg, 250);
  const [debouncedRelease] = useDebouncedValue(releaseSpeedMph, 250);

  const { data, isFetching, isError } = useQuery({
    queryKey: [
      "predict-batted-ball",
      parkId,
      debouncedSpeed,
      debouncedAngle,
      debouncedRelease,
      stand,
    ],
    queryFn: () =>
      predictBattedBall({
        parkId,
        launchSpeedMph: debouncedSpeed,
        launchAngleDeg: debouncedAngle,
        releaseSpeedMph: debouncedRelease,
        stand,
      }),
    staleTime: 30_000,
  });

  const prob = data?.probHr ?? 0;
  const pctDisplay = data ? `${(prob * 100).toFixed(1)}%` : "—";

  return (
    <div
      style={{
        backgroundColor: colors.bgElevated,
        border: `1px solid ${colors.bgEmphasis}`,
        borderRadius: 10,
        padding: spacing[5], // 24
        display: "flex",
        flexDirection: "column",
        gap: spacing[4], // 16
      }}
    >
      <Text
        component="span"
        style={{
          fontFamily: typography.fonts.data,
          fontSize: typography.scale[0], // 12
          fontWeight: typography.weights.semibold,
          letterSpacing: "0.12em",
          textTransform: "uppercase",
          color: colors.accent,
        }}
      >
        Live · POST /v1/predict/batted-ball
      </Text>

      {/* Park dropdown — full width row */}
      <Stack gap={spacing[1]}>
        <Text
          style={{
            fontFamily: typography.fonts.ui,
            fontSize: typography.scale[1], // 14
            fontWeight: typography.weights.medium,
            color: colors.textDefault,
          }}
        >
          Park
        </Text>
        <Select
          data={PARK_OPTIONS}
          value={parkId}
          onChange={(v) => v && setParkId(v)}
          allowDeselect={false}
          size="sm"
          aria-label="Park"
        />
      </Stack>

      {/* Three sliders, stacked */}
      <SliderRow
        label="Launch speed"
        value={launchSpeedMph}
        display={`${launchSpeedMph.toFixed(1)} mph`}
        min={60}
        max={120}
        step={0.5}
        onChange={setLaunchSpeed}
        ariaLabel="Launch speed in mph"
      />
      <SliderRow
        label="Launch angle"
        value={launchAngleDeg}
        display={`${launchAngleDeg.toFixed(1)}°`}
        min={-10}
        max={50}
        step={0.5}
        onChange={setLaunchAngle}
        ariaLabel="Launch angle in degrees"
      />
      <SliderRow
        label="Release speed"
        value={releaseSpeedMph}
        display={`${releaseSpeedMph.toFixed(1)} mph`}
        min={50}
        max={100}
        step={0.5}
        onChange={setReleaseSpeed}
        ariaLabel="Release speed in mph"
      />

      {/* Stand toggle — compact segmented control aligned right */}
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
        }}
      >
        <Text
          style={{
            fontFamily: typography.fonts.ui,
            fontSize: typography.scale[1], // 14
            fontWeight: typography.weights.medium,
            color: colors.textDefault,
          }}
        >
          Batter stand
        </Text>
        <SegmentedControl
          value={stand}
          onChange={(v) => setStand(v as "L" | "R")}
          data={[
            { value: "L", label: "L" },
            { value: "R", label: "R" },
          ]}
          size="xs"
          color="brand"
          aria-label="Batter stand"
        />
      </div>

      {/* Output block: probability number + thin bar + footer */}
      <div
        style={{
          marginTop: spacing[2],
          paddingTop: spacing[4],
          borderTop: `1px solid ${colors.bgEmphasis}`,
        }}
      >
        <div
          style={{
            display: "flex",
            alignItems: "baseline",
            justifyContent: "space-between",
            marginBottom: spacing[3],
          }}
        >
          <Text
            style={{
              fontFamily: typography.fonts.ui,
              fontSize: typography.scale[1], // 14
              color: colors.textMuted,
              letterSpacing: "0.04em",
              textTransform: "uppercase",
              fontWeight: typography.weights.medium,
            }}
          >
            P(home run)
          </Text>
          <Text
            style={{
              fontFamily: typography.fonts.data,
              fontSize: typography.scale[6], // 40
              fontWeight: typography.weights.medium,
              color: isError ? colors.status.danger : colors.textStrong,
              lineHeight: 1,
              letterSpacing: "-0.02em",
              fontVariantNumeric: "tabular-nums",
            }}
          >
            {isError ? "error" : pctDisplay}
          </Text>
        </div>
        <ProbBarThin
          value={prob}
          ariaLabel={`Home run probability ${pctDisplay}`}
        />
        <Text
          style={{
            marginTop: spacing[3],
            fontFamily: typography.fonts.data,
            fontSize: typography.scale[0], // 12
            color: colors.textMuted,
            fontVariantNumeric: "tabular-nums",
          }}
        >
          {data ? (
            <>
              {data.modelName} {data.modelVersion} · {data.latencyMicros}µs ·{" "}
              {data.correlationId.slice(0, 8)}
              {isFetching ? " · updating…" : ""}
            </>
          ) : isFetching ? (
            "querying registry…"
          ) : isError ? (
            "backend unreachable — start the api profile"
          ) : (
            "—"
          )}
        </Text>
      </div>
    </div>
  );
}

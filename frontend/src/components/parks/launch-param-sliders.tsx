/**
 * Launch-parameter rail (leaf 4c.3) — three sliders that drive the all-parks
 * prediction grid: launch speed, launch angle, spray angle.
 *
 * Spray angle is collected for the UI but the toy batted-ball model (Phase 1.3)
 * doesn't accept it as a feature. The slider is in place so the spine is ready
 * for the Phase 2c.5 30-park MLP, which will consume spray angle natively; for
 * v1 the value flows into the request body and the server ignores it (Spring
 * Boot's default Jackson config drops unknown properties).
 *
 * Debouncing is the consumer's responsibility — the slider rail emits raw
 * values on every change; `<ParksPage>` wraps them with Mantine's
 * `useDebouncedValue` before re-keying the TanStack Query.
 */
import { Group, Slider, Stack, Text } from "@mantine/core";

export type LaunchParams = {
  launchSpeedMph: number;
  launchAngleDeg: number;
  sprayAngleDeg: number;
};

export type LaunchParamSlidersProps = {
  values: LaunchParams;
  onChange: (next: LaunchParams) => void;
  /** Subtle indicator that a refetch is in flight after debounce. */
  isUpdating?: boolean;
};

export function LaunchParamSliders({
  values,
  onChange,
  isUpdating = false,
}: LaunchParamSlidersProps) {
  return (
    <Stack gap="md">
      <Stack gap={4}>
        <Group justify="space-between">
          <Text size="sm" fw={500}>
            Launch speed
          </Text>
          <Text size="sm" ff="monospace">
            {values.launchSpeedMph.toFixed(1)} mph
          </Text>
        </Group>
        <Slider
          value={values.launchSpeedMph}
          min={60}
          max={120}
          step={0.5}
          onChange={(v) => onChange({ ...values, launchSpeedMph: v })}
          label={(v) => `${v.toFixed(1)} mph`}
          aria-label="Launch speed in mph"
        />
      </Stack>

      <Stack gap={4}>
        <Group justify="space-between">
          <Text size="sm" fw={500}>
            Launch angle
          </Text>
          <Text size="sm" ff="monospace">
            {values.launchAngleDeg.toFixed(1)}°
          </Text>
        </Group>
        <Slider
          value={values.launchAngleDeg}
          min={-10}
          max={60}
          step={0.5}
          onChange={(v) => onChange({ ...values, launchAngleDeg: v })}
          label={(v) => `${v.toFixed(1)}°`}
          aria-label="Launch angle in degrees"
        />
      </Stack>

      <Stack gap={4}>
        <Group justify="space-between">
          <Text size="sm" fw={500}>
            Spray angle{" "}
            <Text span size="xs" c="dimmed">
              (placeholder until 30-park MLP lands)
            </Text>
          </Text>
          <Text size="sm" ff="monospace">
            {values.sprayAngleDeg.toFixed(0)}°
          </Text>
        </Group>
        <Slider
          value={values.sprayAngleDeg}
          min={-45}
          max={45}
          step={1}
          onChange={(v) => onChange({ ...values, sprayAngleDeg: v })}
          label={(v) => `${v.toFixed(0)}°`}
          aria-label="Spray angle in degrees (positive = left field)"
        />
      </Stack>

      {isUpdating ? (
        <Text size="xs" c="dimmed">
          Updating…
        </Text>
      ) : null}
    </Stack>
  );
}

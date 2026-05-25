/**
 * Park Explorer marquee page (leaves 4c.2 + 4c.3 + 4c.4).
 *
 * State flow:
 *   raw sliders ── 200 ms debounce ──> TanStack Query key
 *
 * Grid is sorted by descending P(HR) with stable parkId secondary key so close
 * probabilities don't jitter under slider tweaks. `keepPreviousData` keeps the
 * grid visible during refetch — no flash of 30 skeleton tiles after the first
 * load. Polished thumbnails use Viridis fill + landing-zone dot, and a hover
 * surfaces a detail panel beside the grid.
 */
import { Container, Grid, SimpleGrid, Stack, Text, Title } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useCallback, useMemo, useState } from "react";

import {
  ParksApiError,
  predictAllParks,
  type AllParksResponse,
} from "../api/parks";
import {
  LaunchParamSliders,
  type LaunchParams,
} from "../components/parks/launch-param-sliders";
import {
  ParkDetailPanel,
  type ParkDetail,
} from "../components/parks/park-detail-panel";
import { estimateLandingDistanceFt } from "../components/parks/estimate-landing";
import { ParkThumbnailPolished } from "../components/parks/park-thumbnail-polished";
import parkMetaJson from "../data/park-meta.json";

type ParkMeta = {
  name: string;
  svgPath: string;
  altitudeM: number | null;
  shortFenceFt: number;
  centerFenceFt: number | null;
  deepestFenceFt: number;
};

const META = parkMetaJson as Record<string, ParkMeta>;
const PARK_IDS = Object.keys(META).sort();

const DEFAULT_PARAMS: LaunchParams = {
  launchSpeedMph: 110,
  launchAngleDeg: 28,
  sprayAngleDeg: 0,
};

const RELEASE_SPEED_MPH = 94;

export default function ParksPage() {
  const [params, setParams] = useState<LaunchParams>(DEFAULT_PARAMS);
  const [debouncedParams] = useDebouncedValue(params, 200);
  const [hoveredParkId, setHoveredParkId] = useState<string | null>(null);

  const query = useQuery<AllParksResponse, ParksApiError>({
    queryKey: ["parks", "all-parks", debouncedParams],
    queryFn: () =>
      predictAllParks({
        launchSpeedMph: debouncedParams.launchSpeedMph,
        launchAngleDeg: debouncedParams.launchAngleDeg,
        releaseSpeedMph: RELEASE_SPEED_MPH,
        parkId: "NYY",
        stand: "R",
      }),
    placeholderData: keepPreviousData,
    staleTime: 30_000,
  });

  const isUpdating =
    query.isFetching ||
    debouncedParams.launchSpeedMph !== params.launchSpeedMph ||
    debouncedParams.launchAngleDeg !== params.launchAngleDeg ||
    debouncedParams.sprayAngleDeg !== params.sprayAngleDeg;

  // Stable sort: descending P(HR), secondary key alphabetical parkId.
  const orderedIds = useMemo(() => {
    const probs = query.data?.probHrByPark;
    if (!probs) return PARK_IDS;
    return [...PARK_IDS].sort((a, b) => {
      const pa = probs[a] ?? 0;
      const pb = probs[b] ?? 0;
      if (pb !== pa) return pb - pa;
      return a.localeCompare(b);
    });
  }, [query.data]);

  const landingFt = useMemo(
    () =>
      estimateLandingDistanceFt(
        debouncedParams.launchSpeedMph,
        debouncedParams.launchAngleDeg,
      ),
    [debouncedParams.launchSpeedMph, debouncedParams.launchAngleDeg],
  );

  // Stable callback so memoized thumbnails don't see a new reference each render.
  const handleHover = useCallback(
    (id: string | null) => setHoveredParkId(id),
    [],
  );

  // Empty state: when no park clears even a 0.5% chance, the input is so
  // unrealistic that the heatmap reads as flat-dark and gives the user no
  // signal — surface an explicit message instead.
  const allParksFlat = useMemo(() => {
    if (!query.data) return false;
    const probs = Object.values(query.data.probHrByPark);
    if (probs.length === 0) return false;
    return probs.every((p) => p < 0.005);
  }, [query.data]);

  const hoveredDetail: ParkDetail | null = useMemo(() => {
    if (!hoveredParkId) return null;
    const meta = META[hoveredParkId];
    if (!meta) return null;
    return {
      parkId: hoveredParkId,
      name: meta.name,
      altitudeM: meta.altitudeM,
      shortFenceFt: meta.shortFenceFt,
      centerFenceFt: meta.centerFenceFt,
      deepestFenceFt: meta.deepestFenceFt,
      probHr: query.data?.probHrByPark?.[hoveredParkId] ?? null,
    };
  }, [hoveredParkId, query.data]);

  return (
    <Container size="lg" py="xl">
      <Stack gap="md">
        <Title order={1}>Park Explorer</Title>
        <Text c="dimmed" fs="italic" ff="serif">
          Drag the sliders to see how the same batted ball would play in each
          MLB park.
        </Text>
        <Text c="dimmed" size="sm">
          Tint uses the Viridis ramp — darker = lower P(HR), brighter = higher.
          The dot on each stadium is the model's estimated landing zone for this
          input. Grid sorts most-HR-likely first.
        </Text>

        <LaunchParamSliders
          values={params}
          onChange={setParams}
          isUpdating={isUpdating}
        />

        {query.isError ? (
          <Text c="red">
            Could not load park predictions
            {query.error instanceof Error ? `: ${query.error.message}` : ""}.
          </Text>
        ) : null}

        {allParksFlat ? (
          <Text c="dimmed" size="sm" fs="italic">
            No realistic home-run scenarios at these inputs (every park's P(HR)
            is below 0.5%). Try a higher exit velocity or a launch angle in the
            20–35° range.
          </Text>
        ) : null}

        <Grid gap="md">
          <Grid.Col span={{ base: 12, md: 8 }}>
            <SimpleGrid
              cols={{ base: 2, xs: 3, sm: 4, md: 4, lg: 5 }}
              spacing="md"
            >
              {orderedIds.map((id) => {
                const meta = META[id];
                const prob = query.data?.probHrByPark?.[id] ?? null;
                return (
                  <ParkThumbnailPolished
                    key={id}
                    parkId={id}
                    name={meta.name}
                    probHr={prob}
                    isLoading={query.isLoading}
                    landingDistanceFt={landingFt}
                    sprayAngleDeg={debouncedParams.sprayAngleDeg}
                    onHoverChange={handleHover}
                  />
                );
              })}
            </SimpleGrid>
          </Grid.Col>
          <Grid.Col span={{ base: 12, md: 4 }}>
            <ParkDetailPanel detail={hoveredDetail} />
          </Grid.Col>
        </Grid>

        {query.data ? (
          <Text size="xs" c="dimmed">
            Served by {query.data.modelName}@{query.data.modelVersion} ·{" "}
            {(query.data.latencyMicros / 1000).toFixed(1)} ms total for all 30
            parks
          </Text>
        ) : null}
      </Stack>
    </Container>
  );
}

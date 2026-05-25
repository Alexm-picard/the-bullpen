/**
 * /parks — the Park Explorer marquee, tech-product polish redesign (2026-05-25).
 *
 * Page anatomy (top → bottom):
 *   1. Hero: HeroEyebrow + h1 + lede.
 *   2. <StickyControlRail> with all 5 launch controls. Sticks under the AppShell
 *      header on scroll, compresses to a slim row via IntersectionObserver.
 *   3. <LeagueLeaderStrip>: the rank-1 park hero block. Updates live with the
 *      rail's debounced output.
 *   4. Mode toggle row (right-aligned SegmentedControl, grid|list).
 *   5. Body: <SimpleGrid> of <ParkTile> (grid mode) OR a stack of
 *      <ParkListRow> (list mode), one entry per park sorted desc by P(HR).
 *   6. Footer meta line: latency + model + correlation id.
 *   7. <ParkDetailModal> opened by tile/row click.
 *
 * State flow:
 *   raw rail values ── 200 ms debounce ──> TanStack Query key for /all-parks.
 *   `keepPreviousData` keeps the grid visible during refetch — no flash of 30
 *   skeleton tiles after the first load.
 *
 * Rank-1 accent: leader treatment lives inside <ParkTile>, <ParkListRow>, and
 * <LeagueLeaderStrip>. The page itself never reaches for the accent color —
 * each presentational component decides for itself.
 */
import {
  Container,
  SegmentedControl,
  SimpleGrid,
  Stack,
  Text,
  Title,
} from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useCallback, useMemo, useState } from "react";

import {
  ParksApiError,
  predictAllParks,
  type AllParksResponse,
} from "../api/parks";
import { estimateLandingDistanceFt } from "../components/parks/estimate-landing";
import {
  LeagueLeaderStrip,
  type LeagueLeader,
} from "../components/parks/league-leader-strip";
import { ParkDetailModal } from "../components/parks/park-detail-modal";
import { ParkListRow } from "../components/parks/park-list-row";
import { ParkTile } from "../components/parks/park-tile";
import {
  StickyControlRail,
  type LaunchParamsExtended,
} from "../components/parks/sticky-control-rail";
import parkMetaJson from "../data/park-meta.json";
import { HeroEyebrow } from "../components/shared/hero-eyebrow";
import { colors, layouts, spacing, typography } from "../design/tokens";

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

const DEFAULT_PARAMS: LaunchParamsExtended = {
  stand: "R",
  launchSpeedMph: 110,
  launchAngleDeg: 28,
  releaseSpeedMph: 94,
  sprayAngleDeg: 0,
};

type ViewMode = "grid" | "list";

const MODE_DATA = [
  { value: "grid", label: "Grid" },
  { value: "list", label: "List" },
];

export default function ParksPage() {
  const [params, setParams] = useState<LaunchParamsExtended>(DEFAULT_PARAMS);
  const [debounced] = useDebouncedValue(params, 200);
  const [mode, setMode] = useState<ViewMode>("grid");
  const [openParkId, setOpenParkId] = useState<string | null>(null);

  const query = useQuery<AllParksResponse, ParksApiError>({
    queryKey: ["parks", "all-parks", debounced],
    queryFn: () =>
      predictAllParks({
        launchSpeedMph: debounced.launchSpeedMph,
        launchAngleDeg: debounced.launchAngleDeg,
        releaseSpeedMph: debounced.releaseSpeedMph,
        parkId: "NYY", // ignored by /all-parks but required by the request schema
        stand: debounced.stand,
      }),
    placeholderData: keepPreviousData,
    staleTime: 30_000,
  });

  const isUpdating =
    query.isFetching ||
    debounced.launchSpeedMph !== params.launchSpeedMph ||
    debounced.launchAngleDeg !== params.launchAngleDeg ||
    debounced.releaseSpeedMph !== params.releaseSpeedMph ||
    debounced.sprayAngleDeg !== params.sprayAngleDeg ||
    debounced.stand !== params.stand;

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
        debounced.launchSpeedMph,
        debounced.launchAngleDeg,
      ),
    [debounced.launchSpeedMph, debounced.launchAngleDeg],
  );

  // Empty state — every park's probability < 0.5% means the heatmap reads flat.
  const allParksFlat = useMemo(() => {
    if (!query.data) return false;
    const probs = Object.values(query.data.probHrByPark);
    if (probs.length === 0) return false;
    return probs.every((p) => p < 0.005);
  }, [query.data]);

  const leader: LeagueLeader | null = useMemo(() => {
    if (allParksFlat) return null;
    const leaderId = orderedIds[0];
    if (!leaderId) return null;
    const meta = META[leaderId];
    const prob = query.data?.probHrByPark?.[leaderId];
    if (!meta || prob == null) return null;
    return {
      parkId: leaderId,
      name: meta.name,
      probHr: prob,
      shortFenceFt: meta.shortFenceFt,
      centerFenceFt: meta.centerFenceFt,
      deepestFenceFt: meta.deepestFenceFt,
    };
  }, [allParksFlat, orderedIds, query.data]);

  const handleSelect = useCallback((id: string) => setOpenParkId(id), []);
  const handleClose = useCallback(() => setOpenParkId(null), []);

  const openProb =
    openParkId && query.data?.probHrByPark
      ? (query.data.probHrByPark[openParkId] ?? null)
      : null;

  return (
    <Container
      size="lg"
      py="xl"
      style={{ maxWidth: layouts.analyticalMaxWidth }}
    >
      <Stack gap={spacing[4]}>
        {/* Hero */}
        <Stack gap={spacing[3]}>
          <HeroEyebrow>PARK EXPLORER · 30 MLB PARKS · LIVE</HeroEyebrow>
          <Title
            order={1}
            style={{
              margin: 0,
              fontFamily: typography.fonts.display,
              fontSize: typography.scale[6], // 40
              fontWeight: typography.weights.bold,
              color: colors.textStrong,
              lineHeight: 1.1,
              letterSpacing: "-0.02em",
              maxWidth: 760,
            }}
          >
            How the same batted ball plays in 30 different parks.
          </Title>
          <Text
            style={{
              maxWidth: 720,
              fontFamily: typography.fonts.ui,
              fontSize: typography.scale[3], // 20
              color: colors.textMuted,
              lineHeight: typography.lineHeights.body,
            }}
          >
            Move a slider, watch every park react. The leader changes as the
            geometry of the swing shifts — short porches respond to one shape,
            big alleys to another. Click any park for fence depths and the
            served prediction.
          </Text>
        </Stack>

        <StickyControlRail
          values={params}
          onChange={setParams}
          isUpdating={isUpdating}
        />

        {query.isError ? (
          <Text
            style={{
              fontFamily: typography.fonts.ui,
              fontSize: typography.scale[1],
              color: colors.status.danger,
            }}
          >
            Could not load park predictions
            {query.error instanceof Error ? `: ${query.error.message}` : ""}.
          </Text>
        ) : null}

        <LeagueLeaderStrip leader={leader} isLoading={query.isLoading} />

        {allParksFlat ? (
          <Text
            style={{
              fontFamily: typography.fonts.ui,
              fontSize: typography.scale[1], // 14
              fontStyle: "italic",
              color: colors.textMuted,
            }}
          >
            No realistic home-run scenarios at these inputs (every park's P(HR)
            is below 0.5%). Try a higher exit velocity or a launch angle in the
            20–35° range.
          </Text>
        ) : null}

        {/* Mode toggle */}
        <div
          style={{
            display: "flex",
            justifyContent: "flex-end",
            alignItems: "center",
            gap: spacing[3],
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
            View
          </Text>
          <SegmentedControl
            value={mode}
            onChange={(v) => setMode(v as ViewMode)}
            data={MODE_DATA}
            size="xs"
            color="brand"
            aria-label="Park list view mode"
          />
        </div>

        {mode === "grid" ? (
          <SimpleGrid
            cols={{ base: 2, xs: 3, sm: 4, md: 5, lg: 6 }}
            spacing="md"
          >
            {orderedIds.map((id, i) => {
              const meta = META[id];
              const prob = query.data?.probHrByPark?.[id] ?? null;
              return (
                <ParkTile
                  key={id}
                  parkId={id}
                  name={meta.name}
                  rank={i + 1}
                  probHr={prob}
                  isLoading={query.isLoading}
                  landingDistanceFt={landingFt}
                  sprayAngleDeg={debounced.sprayAngleDeg}
                  onSelect={handleSelect}
                />
              );
            })}
          </SimpleGrid>
        ) : (
          <div
            style={{
              borderTop: `1px solid ${colors.bgEmphasis}`,
            }}
          >
            {orderedIds.map((id, i) => {
              const meta = META[id];
              const prob = query.data?.probHrByPark?.[id] ?? null;
              return (
                <ParkListRow
                  key={id}
                  parkId={id}
                  name={meta.name}
                  rank={i + 1}
                  probHr={prob}
                  shortFenceFt={meta.shortFenceFt}
                  altitudeM={meta.altitudeM}
                  onSelect={handleSelect}
                />
              );
            })}
          </div>
        )}

        {query.data ? (
          <Text
            style={{
              fontFamily: typography.fonts.data,
              fontSize: typography.scale[0], // 12
              color: colors.textMuted,
              letterSpacing: "0.04em",
              fontVariantNumeric: "tabular-nums",
            }}
          >
            Served by {query.data.modelName}@{query.data.modelVersion} ·{" "}
            {(query.data.latencyMicros / 1000).toFixed(1)} ms for all 30 parks ·
            corr {query.data.correlationId.slice(0, 8)}
          </Text>
        ) : null}
      </Stack>

      <ParkDetailModal
        openParkId={openParkId}
        onClose={handleClose}
        probHr={openProb}
        landingDistanceFt={landingFt}
        sprayAngleDeg={debounced.sprayAngleDeg}
        modelName={query.data?.modelName ?? null}
        modelVersion={query.data?.modelVersion ?? null}
      />
    </Container>
  );
}

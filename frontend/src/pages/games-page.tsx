/**
 * /games - the LIVE slate (FE-H1 closure, 2026-06-11).
 *
 * Replaces the Stage-3d fixture showcase as the default route; the showcase
 * moved intact to /games/demo (games-demo-page.tsx) as the design-system
 * reference. This page is live data only:
 *
 *   - `useTodaysGames()` polls GET /v1/games/today (60s cadence).
 *   - Each row links to /games/{gameId} with the NUMERIC gamePk - the
 *     per-game live page does Number(id), so slug hrefs were dead links.
 *   - [] is a first-class empty state: a game appears only after its first
 *     OBSERVED status transition (~first pitch), not at schedule time. A
 *     worker restart mid-game defers that game's row to its next transition
 *     (known edge, ledger L1).
 *
 * Loading + error states render INSIDE the report sheet so the identity
 * reads even before the API answers (same posture as /games/:id).
 */
import { Stack, Text, Title } from "@mantine/core";

import { useTodaysGames } from "../api/games";
import { TodaysSlateTable } from "../components/games/todays-slate-table";
import { CoverSheetFooter } from "../components/scouting/cover-sheet-footer";
import { HeroEyebrow } from "../components/shared/hero-eyebrow";
import { ReportSheet } from "../components/shared/report-sheet";
import { SectionLabel } from "../components/shared/section-label";
import { colors, typography } from "../design/tokens";

/** Stable build metadata fallback so the colophon footer always renders. */
const BUILD_FALLBACK = {
  sha: "live",
  date: new Date().toISOString().slice(0, 10),
};

function todayIssueDate(): string {
  return new Intl.DateTimeFormat("en-US", {
    weekday: "short",
    month: "short",
    day: "numeric",
    year: "numeric",
  }).format(new Date());
}

export default function GamesPage() {
  const games = useTodaysGames();
  const slate = games.data ?? [];

  return (
    <ReportSheet>
      <Stack gap={24}>
        <header>
          <HeroEyebrow>Live Slate &middot; The Bullpen</HeroEyebrow>
          <Title
            order={1}
            style={{
              fontFamily: typography.fonts.display,
              fontWeight: typography.weights.bold,
              textTransform: "uppercase",
              letterSpacing: "0.01em",
              color: colors.textStrong,
              marginTop: 4,
            }}
          >
            Today&rsquo;s Games
          </Title>
          <Text
            style={{
              fontFamily: typography.fonts.mono,
              fontSize: 12,
              color: colors.textMuted,
              letterSpacing: "0.02em",
            }}
          >
            {todayIssueDate()} &middot; live ingest &middot; slate fills as
            games go live
          </Text>
        </header>

        <section aria-labelledby="games-slate-label">
          <div id="games-slate-label">
            <SectionLabel>
              Today&rsquo;s Slate
              {slate.length > 0 ? ` · ${slate.length} Tracked` : ""}
            </SectionLabel>
          </div>
          {games.isError ? (
            <Text style={{ color: colors.scarlet, fontWeight: 600 }}>
              Could not load today&rsquo;s games
              {games.error instanceof Error ? `: ${games.error.message}` : ""}.
            </Text>
          ) : games.isLoading ? (
            <Text style={{ color: colors.textMuted }}>
              Loading today&rsquo;s slate&hellip;
            </Text>
          ) : (
            <TodaysSlateTable
              games={slate}
              caption={`live game tracker · ${todayIssueDate()}`}
            />
          )}
        </section>

        <CoverSheetFooter
          buildSha={BUILD_FALLBACK.sha}
          buildDate={BUILD_FALLBACK.date}
        />
      </Stack>
    </ReportSheet>
  );
}

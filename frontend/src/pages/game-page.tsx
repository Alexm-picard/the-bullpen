/**
 * Per-game live-detail page (leaves 4d.1, 4d.2).
 *
 * `/games/:id` — header + live pitch feed. 4d.1 shipped the header + pitch
 * list + status-driven polling; 4d.2 added the per-pitch prediction overlay.
 *
 * The slate-style `/games` index lives in `games-page.tsx` (Stage 3d,
 * scouting-report identity). The legacy `TodaysGamesPage` export that used
 * to live here was removed when /games moved to its Live Game variant of
 * the Matchup Report.
 */
import {
  Badge,
  Container,
  Group,
  Skeleton,
  Stack,
  Text,
  Title,
} from "@mantine/core";
import { useParams } from "react-router-dom";

import { useGame, useLivePitches, type GameSummary } from "../api/games";
import { PitchFeed } from "../components/game/pitch-feed";

export function GamePage() {
  const { id } = useParams<{ id: string }>();
  const numericId = id ? Number(id) : null;
  const valid = numericId != null && Number.isFinite(numericId);

  const game = useGame(valid ? numericId : null);
  const pitches = useLivePitches(valid ? numericId : null, game.data?.status);

  return (
    <Container size="md" py="xl">
      <Stack gap="md">
        <GameHeader summary={game.data} isLoading={game.isLoading} />
        {game.isError ? (
          <Text c="red">
            Could not load game
            {game.error instanceof Error ? `: ${game.error.message}` : ""}.
          </Text>
        ) : null}

        <Title order={3}>Pitch feed</Title>
        {pitches.isLoading ? <Skeleton height={120} /> : null}
        {pitches.isError ? (
          <Text c="red">
            Could not load pitches
            {pitches.error instanceof Error ? `: ${pitches.error.message}` : ""}
            .
          </Text>
        ) : null}
        {!pitches.isLoading && !pitches.isError ? (
          <PitchFeed pitches={pitches.pitches} limit={50} />
        ) : null}
        <Text size="xs" c="dimmed">
          Per-pitch prediction columns light up when the worker joins
          prediction_log with pitches_live by (game_id, at_bat_index,
          pitch_number).
        </Text>
      </Stack>
    </Container>
  );
}

function GameHeader({
  summary,
  isLoading,
}: {
  summary: GameSummary | undefined;
  isLoading: boolean;
}) {
  if (isLoading) {
    return (
      <Stack gap={4}>
        <Skeleton height={32} width={320} />
        <Skeleton height={20} width={240} />
      </Stack>
    );
  }
  if (!summary) {
    return <Title order={1}>Game</Title>;
  }
  return (
    <Stack gap={4}>
      <Title order={1}>
        {summary.awayTeam} @ {summary.homeTeam}
      </Title>
      <Group gap="md">
        <Text ff="monospace">
          {summary.awayScore} – {summary.homeScore}
        </Text>
        <Badge size="sm" variant="light">
          {summary.detailedState}
        </Badge>
        <Text c="dimmed" size="sm">
          Inning {summary.inning}
        </Text>
      </Group>
    </Stack>
  );
}

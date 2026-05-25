/**
 * Live-game pages (leaves 4d.1, 4d.2).
 *
 * `/games` — list of today's games (link out to each).
 * `/games/:id` — header + live pitch feed. 4d.1 ships the header + pitch list +
 * status-driven polling; 4d.2 adds the per-pitch prediction overlay.
 */
import {
  Anchor,
  Badge,
  Container,
  Group,
  Skeleton,
  Stack,
  Table,
  Text,
  Title,
} from "@mantine/core";
import { Link, useParams } from "react-router-dom";

import {
  useGame,
  useLivePitches,
  useTodaysGames,
  type GameSummary,
} from "../api/games";
import { PitchFeed } from "../components/game/pitch-feed";

export function TodaysGamesPage() {
  const { data, isLoading, isError, error } = useTodaysGames();

  return (
    <Container size="md" py="xl">
      <Stack gap="md">
        <Title order={1}>Today's games</Title>
        <Text c="dimmed">
          Live games are polled at 12 s while in progress. The pitches_live
          worker (Phase 4d.1 follow-up) populates this table from the MLB Stats
          API; until that lands, this list is empty in dev.
        </Text>

        {isLoading ? (
          <Stack gap={4}>
            {Array.from({ length: 5 }).map((_, i) => (
              <Skeleton key={i} height={36} />
            ))}
          </Stack>
        ) : null}

        {isError ? (
          <Text c="red">
            Could not load today's games
            {error instanceof Error ? `: ${error.message}` : ""}.
          </Text>
        ) : null}

        {!isLoading && !isError && data?.length === 0 ? (
          <Text c="dimmed">No live games right now.</Text>
        ) : null}

        {data && data.length > 0 ? (
          <Table>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Game</Table.Th>
                <Table.Th>Score</Table.Th>
                <Table.Th>Status</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {data.map((g) => (
                <Table.Tr key={g.gameId}>
                  <Table.Td>
                    <Anchor component={Link} to={`/games/${g.gameId}`}>
                      {g.awayTeam} @ {g.homeTeam}
                    </Anchor>
                  </Table.Td>
                  <Table.Td ff="monospace">
                    {g.awayScore} – {g.homeScore}
                  </Table.Td>
                  <Table.Td>{g.detailedState}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        ) : null}
      </Stack>
    </Container>
  );
}

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

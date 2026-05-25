/**
 * Player Lookup pages (leaf 4b.1 + 4b.2).
 *
 * `/players` — search-only landing.
 * `/players/:id` — header (name + position + active flag + summary) and the
 * recent-predictions table. Calibration plot lands in 4b.3.
 */
import { Container, Group, Stack, Text, Title } from "@mantine/core";
import { useNavigate, useParams } from "react-router-dom";

import { usePlayer, usePlayerPredictions } from "../api/players";
import { PlayerSearch } from "../components/players/player-search";
import { PredictionHistoryTable } from "../components/players/prediction-history-table";

export default function PlayersPage() {
  const navigate = useNavigate();
  return (
    <Container size="md" py="xl">
      <Stack gap="md">
        <Title order={1}>Player Lookup</Title>
        <Text c="dimmed">
          Find a pitcher or hitter by name or Statcast ID. Roster is the active
          + historical MLB set; type at least one character to begin.
        </Text>
        <PlayerSearch
          autoFocus
          onSelect={(p) => {
            navigate(`/players/${p.id}`);
          }}
        />
      </Stack>
    </Container>
  );
}

/** Profile + recent-predictions view at /players/:id (leaf 4b.2). */
export function PlayerProfilePage() {
  const { id } = useParams<{ id: string }>();
  const numericId = id ? Number(id) : null;
  const valid = numericId != null && Number.isFinite(numericId);

  const player = usePlayer(valid ? numericId : null);
  const predictions = usePlayerPredictions(valid ? numericId : null, 50);

  return (
    <Container size="lg" py="xl">
      <Stack gap="lg">
        <Stack gap={4}>
          <Title order={1}>
            {player.data
              ? player.data.name
              : player.isLoading
                ? "Loading…"
                : valid
                  ? `Player #${id}`
                  : "Player profile"}
          </Title>
          {player.data ? (
            <Group gap="md">
              <Text c="dimmed">
                Position {player.data.primaryPosition} ·{" "}
                {player.data.active ? "Active" : "Retired"} · Statcast id{" "}
                {player.data.id}
              </Text>
              <Text c="dimmed" size="sm">
                {predictions.data?.length ?? 0} recent prediction
                {(predictions.data?.length ?? 0) === 1 ? "" : "s"}
              </Text>
            </Group>
          ) : null}
          {player.isError ? (
            <Text c="red">
              Could not load this player
              {player.error instanceof Error ? `: ${player.error.message}` : ""}
              .
            </Text>
          ) : null}
        </Stack>

        <Stack gap="xs">
          <Title order={3}>Recent predictions</Title>
          <PredictionHistoryTable
            rows={predictions.data}
            isLoading={predictions.isLoading}
            isError={predictions.isError}
            errorMessage={
              predictions.error instanceof Error
                ? predictions.error.message
                : undefined
            }
          />
          <Text size="xs" c="dimmed">
            Outcome and agreement columns light up when truth-joining to the
            pitches table lands (next leaf). Calibration plot in 4b.3.
          </Text>
        </Stack>
      </Stack>
    </Container>
  );
}
